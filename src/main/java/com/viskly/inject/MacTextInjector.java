// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.inject;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inserting text on macOS: the clipboard plus a synthetic <kbd>⌘V</kbd>.
 *
 * <p>Why not "type" it character by character: on longer text you can watch it being
 * spelled out, Polish diacritics would need mapping onto the keyboard layout, and every
 * character is a separate system event. The clipboard inserts the whole thing at once.
 *
 * <p>The price is that we tread on the user's clipboard — hence we remember what was
 * there and put it back after pasting. Remembering only works for text, though: images
 * and files from someone else's clipboard cannot be reproduced faithfully once we have
 * taken ownership. When the clipboard held something other than text we say so in the
 * log rather than pretend nothing happened.
 *
 * <p>Requires Accessibility consent — without it {@code CGEventPost} silently does
 * nothing. There is no error code for that, hence the check before the first paste.
 */
public final class MacTextInjector implements TextInjector {

    private static final Logger log = LoggerFactory.getLogger(MacTextInjector.class);

    private static final String APPLICATION_SERVICES =
            "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices";
    private static final String CORE_FOUNDATION =
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    /** kVK_ANSI_V — the V key code, independent of keyboard layout. */
    private static final short KEY_V = 9;
    /** kCGEventFlagMaskCommand */
    private static final long FLAG_COMMAND = 0x00100000L;
    /** kCGHIDEventTap — injected at the system level, not into one application. */
    private static final int HID_EVENT_TAP = 0;

    private final Arena arena = Arena.ofAuto();
    private final MethodHandle createKeyboardEvent;
    private final MethodHandle setFlags;
    private final MethodHandle post;
    private final MethodHandle release;
    private final MethodHandle isProcessTrusted;

    private final int settleMillis;
    /** Read on every paste: the settings window changes it while the application runs. */
    private final IntSupplier restoreDelayMillis;

    public MacTextInjector(int settleMillis, IntSupplier restoreDelayMillis) {
        this.settleMillis = settleMillis;
        this.restoreDelayMillis = restoreDelayMillis;

        Linker linker = Linker.nativeLinker();
        SymbolLookup services = SymbolLookup.libraryLookup(APPLICATION_SERVICES, arena);
        SymbolLookup foundation = SymbolLookup.libraryLookup(CORE_FOUNDATION, arena);

        this.createKeyboardEvent = linker.downcallHandle(
                services.findOrThrow("CGEventCreateKeyboardEvent"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_SHORT, JAVA_BOOLEAN));
        this.setFlags = linker.downcallHandle(
                services.findOrThrow("CGEventSetFlags"),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        this.post = linker.downcallHandle(
                services.findOrThrow("CGEventPost"),
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        this.release = linker.downcallHandle(
                foundation.findOrThrow("CFRelease"),
                FunctionDescriptor.ofVoid(ADDRESS));
        this.isProcessTrusted = linker.downcallHandle(
                services.findOrThrow("AXIsProcessTrusted"),
                FunctionDescriptor.of(JAVA_BOOLEAN));
    }

    @Override
    public Result insert(String text) {
        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (RuntimeException | Error e) {
            log.error("No clipboard access — is the application starting in headless mode?", e);
            return Result.FAILED;
        }

        String previous = readText(clipboard);
        if (!write(clipboard, new StringSelection(text))) {
            return Result.FAILED;
        }

        if (!trusted()) {
            log.warn("""
                    The text is in the clipboard, but I cannot paste it: no Accessibility
                    consent. Settings > Privacy & Security > Accessibility — tick the
                    application you launch the JVM from, quit it with Cmd+Q and start it
                    again. For now, paste it yourself (Cmd+V).""");
            restoreLater(clipboard, previous);
            return Result.CLIPBOARD_ONLY;
        }

        // A moment for the clipboard to settle — without it you occasionally get the
        // previous contents pasted, especially when the target app reads it lazily.
        sleep(settleMillis);

        try {
            pressCommandV();
        } catch (Throwable t) {
            log.error("CGEventPost failed — the text stays in the clipboard", t);
            restoreLater(clipboard, previous);
            return Result.CLIPBOARD_ONLY;
        }

        restoreLater(clipboard, previous);
        return Result.PASTED;
    }

    private void pressCommandV() throws Throwable {
        MemorySegment keyDown = (MemorySegment) createKeyboardEvent
                .invokeExact(MemorySegment.NULL, KEY_V, true);
        setFlags.invokeExact(keyDown, FLAG_COMMAND);
        post.invokeExact(HID_EVENT_TAP, keyDown);
        release.invokeExact(keyDown);

        MemorySegment keyUp = (MemorySegment) createKeyboardEvent
                .invokeExact(MemorySegment.NULL, KEY_V, false);
        setFlags.invokeExact(keyUp, FLAG_COMMAND);
        post.invokeExact(HID_EVENT_TAP, keyUp);
        release.invokeExact(keyUp);
    }

    @Override
    public boolean canPaste() {
        return trusted();
    }

    private boolean trusted() {
        try {
            return (boolean) isProcessTrusted.invokeExact();
        } catch (Throwable t) {
            return false;
        }
    }

    private String readText(Clipboard clipboard) {
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return (String) clipboard.getData(DataFlavor.stringFlavor);
            }
            if (clipboard.getContents(null) != null) {
                log.debug("The clipboard held something other than text — that will not be restored");
            }
        } catch (Exception e) {
            log.debug("Could not read the clipboard", e);
        }
        return null;
    }

    private boolean write(Clipboard clipboard, StringSelection content) {
        // Another application can hold the clipboard for a moment — normal, not an error.
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                clipboard.setContents(content, null);
                return true;
            } catch (IllegalStateException e) {
                sleep(20);
            }
        }
        log.error("The clipboard stayed locked by another application");
        return false;
    }

    /**
     * Restores the previous text in the background. The target application reads the
     * clipboard asynchronously, so restoring immediately after the paste can swap the
     * content out from under it.
     */
    private void restoreLater(Clipboard clipboard, String previous) {
        if (previous == null) {
            return;
        }
        Thread.ofVirtual().name("viskly-clipboard-restore").start(() -> {
            sleep(restoreDelayMillis.getAsInt());
            write(clipboard, new StringSelection(previous));
        });
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
