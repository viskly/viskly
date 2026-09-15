// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.hotkey;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Escape while recording, through an event tap that is switched on only for the length of
 * one dictation.
 *
 * <p>Not an NSEvent monitor, although the shortcut itself is one: a global monitor gets key
 * events only with Input Monitoring, the consent the move to monitors got rid of. It
 * installed, reported nothing, and Escape silently did nothing. An active tap needs
 * Accessibility instead, which pasting requires anyway, and it takes effect in the running
 * process the moment the switch goes on.
 *
 * <p>An active tap holds every key event until its callback returns, so this one exists
 * enabled only between the shortcut going down and coming up, reads nothing but the key
 * code, and runs on a thread of its own rather than the main thread, where a busy window
 * would delay typing across the whole system. Escape itself is swallowed: it cancels the
 * dictation, and the frontmost application is not also told to close a dialog.
 */
final class EscapeTap {

    private static final Logger log = LoggerFactory.getLogger(EscapeTap.class);

    private static final String APPLICATION_SERVICES =
            "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices";
    private static final String CORE_FOUNDATION =
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private static final int KEY_DOWN = 10;
    /** kCGKeyboardEventKeycode */
    private static final int KEYCODE_FIELD = 9;
    private static final long KEY_ESCAPE = 53;
    private static final int TAP_DISABLED_BY_TIMEOUT = -2;
    private static final int TAP_DISABLED_BY_USER_INPUT = -1;
    // kCGSessionEventTap, kCGHeadInsertEventTap, kCGEventTapOptionDefault
    private static final int SESSION_EVENT_TAP = 1;
    private static final int HEAD_INSERT = 0;
    private static final int ACTIVE = 0;

    private final Runnable onEscape;
    private final Arena arena = Arena.ofAuto();

    private final MethodHandle axIsProcessTrusted;
    private final MethodHandle tapCreate;
    private final MethodHandle tapEnable;
    private final MethodHandle getField;
    private final MethodHandle sourceCreate;
    private final MethodHandle loopCurrent;
    private final MethodHandle loopAdd;
    private final MethodHandle loopRun;
    private final MemorySegment commonModes;
    private final MemorySegment callback;

    private volatile MemorySegment tap = MemorySegment.NULL;
    private volatile boolean armed;
    private volatile boolean starting;

    EscapeTap(Runnable onEscape) {
        this.onEscape = onEscape;

        Linker linker = Linker.nativeLinker();
        SymbolLookup services = SymbolLookup.libraryLookup(APPLICATION_SERVICES, arena);
        SymbolLookup foundation = SymbolLookup.libraryLookup(CORE_FOUNDATION, arena);

        this.axIsProcessTrusted = linker.downcallHandle(services.findOrThrow("AXIsProcessTrusted"),
                FunctionDescriptor.of(JAVA_BOOLEAN));
        this.tapCreate = linker.downcallHandle(services.findOrThrow("CGEventTapCreate"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        this.tapEnable = linker.downcallHandle(services.findOrThrow("CGEventTapEnable"),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));
        this.getField = linker.downcallHandle(services.findOrThrow("CGEventGetIntegerValueField"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        this.sourceCreate = linker.downcallHandle(foundation.findOrThrow("CFMachPortCreateRunLoopSource"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        this.loopCurrent = linker.downcallHandle(foundation.findOrThrow("CFRunLoopGetCurrent"),
                FunctionDescriptor.of(ADDRESS));
        this.loopAdd = linker.downcallHandle(foundation.findOrThrow("CFRunLoopAddSource"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        this.loopRun = linker.downcallHandle(foundation.findOrThrow("CFRunLoopRun"),
                FunctionDescriptor.ofVoid());
        this.commonModes = foundation.findOrThrow("kCFRunLoopCommonModes")
                .reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);

        try {
            MethodHandle target = MethodHandles.lookup().findVirtual(EscapeTap.class, "onEvent",
                    MethodType.methodType(MemorySegment.class,
                            MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class))
                    .bindTo(this);
            this.callback = linker.upcallStub(target,
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS), arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The shortcut went down. Without Accessibility nothing is attempted: creating a tap
     * then would make macOS put up its own consent prompt, and asking belongs to the
     * permissions window, not to the middle of a sentence.
     */
    void arm() {
        armed = true;
        try {
            if (!tap.equals(MemorySegment.NULL)) {
                tapEnable.invokeExact(tap, true);
                return;
            }
            if (!starting && (boolean) axIsProcessTrusted.invokeExact()) {
                starting = true;
                // CFRunLoop pins its thread, so a platform thread, created once and kept.
                Thread.ofPlatform().name("viskly-escape").daemon().start(this::runLoop);
            }
        } catch (Throwable t) {
            log.warn("Could not watch for Escape", t);
        }
    }

    /** The shortcut came up, or the dictation was cancelled: key events flow untouched. */
    void disarm() {
        armed = false;
        try {
            if (!tap.equals(MemorySegment.NULL)) {
                tapEnable.invokeExact(tap, false);
            }
        } catch (Throwable t) {
            log.debug("Could not disable the Escape tap", t);
        }
    }

    private void runLoop() {
        try {
            MemorySegment created = (MemorySegment) tapCreate.invokeExact(
                    SESSION_EVENT_TAP, HEAD_INSERT, ACTIVE, 1L << KEY_DOWN, callback, MemorySegment.NULL);
            if (created.equals(MemorySegment.NULL)) {
                log.warn("macOS refused the Escape tap; Escape will not cancel a dictation");
                starting = false;
                return;
            }
            MemorySegment source = (MemorySegment) sourceCreate.invokeExact(MemorySegment.NULL, created, 0L);
            MemorySegment loop = (MemorySegment) loopCurrent.invokeExact();
            loopAdd.invokeExact(loop, source, commonModes);
            // Published before it is switched, so a disarm() racing this sees the tap. A tap
            // is created enabled; it stays so only if the shortcut is still held.
            tap = created;
            tapEnable.invokeExact(created, armed);
            loopRun.invokeExact();
        } catch (Throwable t) {
            log.error("The Escape tap stopped", t);
        }
    }

    @SuppressWarnings("unused")
    private MemorySegment onEvent(MemorySegment proxy, int type, MemorySegment event, MemorySegment info) {
        try {
            if (type == TAP_DISABLED_BY_TIMEOUT || type == TAP_DISABLED_BY_USER_INPUT) {
                if (armed) {
                    tapEnable.invokeExact(tap, true);
                }
                return event;
            }
            if (type == KEY_DOWN && armed) {
                long code = (long) getField.invokeExact(event, KEYCODE_FIELD);
                if (code == KEY_ESCAPE) {
                    disarm();
                    onEscape.run();
                    return MemorySegment.NULL;
                }
            }
        } catch (Throwable t) {
            log.error("Error while handling a key", t);
        }
        return event;
    }
}
