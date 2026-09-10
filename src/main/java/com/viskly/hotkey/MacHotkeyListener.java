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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A global shortcut on macOS via CGEventTap, called straight from Java through the
 * Foreign Function &amp; Memory API — no JNI, no C.
 *
 * <p>How it works: we create a keyboard event tap, attach it to a CoreFoundation run
 * loop on a dedicated platform thread, and wait. The system calls our upcall on every
 * modifier change.
 *
 * <p>The tap runs in <em>listen only</em> mode — it does not intercept events, so it
 * cannot lock up the user's keyboard even if something goes wrong.
 *
 * <h2>Permissions</h2>
 * macOS has <b>two separate consents</b> here and they are easy to confuse:
 * <ul>
 *   <li><b>Input Monitoring</b> (IOHID, kTCCServiceListenEvent) — what a listen-only
 *       tap requires. This is the one we need <em>now</em>.</li>
 *   <li><b>Accessibility</b> (AXIsProcessTrusted) — required to <em>post</em> events,
 *       which is what pasting text needs.</li>
 * </ul>
 * We ask for both through the system APIs, which add the application to the right list
 * in Settings by themselves — otherwise the user would have to hunt for the JVM binary.
 * Neither takes effect immediately: after ticking the box the process must be restarted.
 */
public final class MacHotkeyListener implements HotkeyListener {

    private static final Logger log = LoggerFactory.getLogger(MacHotkeyListener.class);

    private static final String APPLICATION_SERVICES =
            "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices";
    private static final String CORE_FOUNDATION =
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private static final String IO_KIT =
            "/System/Library/Frameworks/IOKit.framework/IOKit";

    // CGEventType
    private static final int KEY_DOWN = 10;
    private static final int FLAGS_CHANGED = 12;
    /** kCGKeyboardEventKeycode — the only event field we ever read. */
    private static final int KEYCODE_FIELD = 9;
    /** kVK_Escape */
    private static final long KEY_ESCAPE = 53;
    private static final int TAP_DISABLED_BY_TIMEOUT = -2;      // 0xFFFFFFFE
    private static final int TAP_DISABLED_BY_USER_INPUT = -1;   // 0xFFFFFFFF

    // CGEventTapLocation.kCGHIDEventTap / CGEventTapPlacement.kCGHeadInsertEventTap
    private static final int HID_EVENT_TAP = 0;
    private static final int HEAD_INSERT = 0;
    // CGEventTapOptions.kCGEventTapOptionListenOnly
    private static final int LISTEN_ONLY = 1;

    // IOHIDRequestType.kIOHIDRequestTypeListenEvent / IOHIDAccessType.kIOHIDAccessTypeGranted
    private static final int HID_REQUEST_LISTEN = 1;
    private static final int HID_ACCESS_GRANTED = 0;

    private volatile ModifierKey key;
    // ofAuto rather than ofShared: the run loop sits inside a downcall for the whole
    // life of the application, so the arena cannot be closed anyway ("Session is
    // acquired by 1 clients"). The GC releases it once the loop ends.
    private final Arena arena = Arena.ofAuto();
    private final ExecutorService dispatcher =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("viskly-hotkey").unstarted(r));

    private final MethodHandle cgEventTapCreate;
    private final MethodHandle cgEventTapEnable;
    private final MethodHandle cgEventGetFlags;
    private final MethodHandle cgEventGetIntegerValueField;
    private final MethodHandle axIsProcessTrustedWithOptions;
    private final MethodHandle cfDictionaryCreate;
    private final MethodHandle cfRelease;
    private final MethodHandle cfMachPortCreateRunLoopSource;
    private final MethodHandle cfRunLoopGetCurrent;
    private final MethodHandle cfRunLoopAddSource;
    private final MethodHandle cfRunLoopRun;
    private final MethodHandle cfRunLoopStop;
    private final MethodHandle ioHidCheckAccess;
    private final MethodHandle ioHidRequestAccess;

    private final MemorySegment commonModes;
    private final MemorySegment promptOptionKey;
    private final MemorySegment booleanTrue;
    private final MemorySegment dictKeyCallbacks;
    private final MemorySegment dictValueCallbacks;

    private volatile boolean down;
    private volatile boolean closing;
    private volatile MemorySegment tap = MemorySegment.NULL;
    /** Input Monitoring as it stood when the tap was installed, which is what the tap gets. */
    private volatile boolean listenConsent;
    private volatile MemorySegment runLoop = MemorySegment.NULL;
    private Thread eventThread;
    private volatile Callbacks callbacks = NOOP;

    private static final Callbacks NOOP = new Callbacks() {
        public void onPress() { }
        public void onRelease() { }
        public void onCancel() { }
    };

    public MacHotkeyListener(ModifierKey key) {
        this.key = key;

        Linker linker = Linker.nativeLinker();
        SymbolLookup services = SymbolLookup.libraryLookup(APPLICATION_SERVICES, arena);
        SymbolLookup foundation = SymbolLookup.libraryLookup(CORE_FOUNDATION, arena);
        SymbolLookup ioKit = SymbolLookup.libraryLookup(IO_KIT, arena);

        this.cgEventTapCreate = linker.downcallHandle(
                services.findOrThrow("CGEventTapCreate"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        this.cgEventTapEnable = linker.downcallHandle(
                services.findOrThrow("CGEventTapEnable"),
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_BOOLEAN));
        this.cgEventGetFlags = linker.downcallHandle(
                services.findOrThrow("CGEventGetFlags"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        this.cgEventGetIntegerValueField = linker.downcallHandle(
                services.findOrThrow("CGEventGetIntegerValueField"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        this.axIsProcessTrustedWithOptions = linker.downcallHandle(
                services.findOrThrow("AXIsProcessTrustedWithOptions"),
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS));
        this.cfDictionaryCreate = linker.downcallHandle(
                foundation.findOrThrow("CFDictionaryCreate"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        this.cfRelease = linker.downcallHandle(
                foundation.findOrThrow("CFRelease"),
                FunctionDescriptor.ofVoid(ADDRESS));
        this.cfMachPortCreateRunLoopSource = linker.downcallHandle(
                foundation.findOrThrow("CFMachPortCreateRunLoopSource"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
        this.cfRunLoopGetCurrent = linker.downcallHandle(
                foundation.findOrThrow("CFRunLoopGetCurrent"),
                FunctionDescriptor.of(ADDRESS));
        this.cfRunLoopAddSource = linker.downcallHandle(
                foundation.findOrThrow("CFRunLoopAddSource"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        this.cfRunLoopRun = linker.downcallHandle(
                foundation.findOrThrow("CFRunLoopRun"),
                FunctionDescriptor.ofVoid());
        this.cfRunLoopStop = linker.downcallHandle(
                foundation.findOrThrow("CFRunLoopStop"),
                FunctionDescriptor.ofVoid(ADDRESS));
        this.ioHidCheckAccess = linker.downcallHandle(
                ioKit.findOrThrow("IOHIDCheckAccess"),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        this.ioHidRequestAccess = linker.downcallHandle(
                ioKit.findOrThrow("IOHIDRequestAccess"),
                FunctionDescriptor.of(JAVA_BOOLEAN, JAVA_INT));

        // kCFRunLoopCommonModes and friends are global variables of pointer type: the
        // symbol points at the cell holding the pointer, so it has to be read once more.
        this.commonModes = dereference(foundation.findOrThrow("kCFRunLoopCommonModes"));
        this.promptOptionKey = dereference(services.findOrThrow("kAXTrustedCheckOptionPrompt"));
        this.booleanTrue = dereference(foundation.findOrThrow("kCFBooleanTrue"));

        // These two are structs, not pointers — we pass the symbol's own address.
        this.dictKeyCallbacks = foundation.findOrThrow("kCFTypeDictionaryKeyCallBacks");
        this.dictValueCallbacks = foundation.findOrThrow("kCFTypeDictionaryValueCallBacks");
    }

    private static MemorySegment dereference(MemorySegment symbol) {
        return symbol.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    /**
     * A tap that exists is not a tap that works. Without Input Monitoring macOS still hands
     * out a listen-only tap, then keeps disabling it (kCGEventTapDisabledByUserInput) and
     * delivers no key events at all, so the tap alone reported "listening" on a shortcut
     * that did nothing. The consent is read once, at install, because a consent granted
     * later only reaches a process started after it.
     */
    @Override
    public boolean isActive() {
        return !tap.equals(MemorySegment.NULL) && listenConsent;
    }

    @Override
    public void setKey(String name) {
        try {
            ModifierKey parsed = ModifierKey.valueOf(name);
            if (parsed != key) {
                key = parsed;
                // The callback reads the mask on every event, so nothing has to be
                // re-registered with the system — the tap itself does not care which
                // modifier we are watching for.
                log.info("Shortcut changed to {}", parsed);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unknown shortcut {}, keeping {}", name, key);
        }
    }

    @Override
    public void listen(Callbacks callbacks) {
        this.callbacks = callbacks;

        requestPermissions();

        CountDownLatch ready = new CountDownLatch(1);
        // CFRunLoop pins the thread — it has to be a platform thread, not a virtual one.
        eventThread = Thread.ofPlatform().name("viskly-eventtap").daemon().unstarted(() -> runLoop(ready));
        eventThread.start();

        try {
            if (!ready.await(5, TimeUnit.SECONDS)) {
                log.error("The event loop did not start within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Asks the system for both consents. These calls open the dialog themselves and add
     * the process to the right list in Settings, so the user never has to find the JVM
     * binary in a file picker.
     *
     * <p>We deliberately do not abort startup when a consent is missing: the tap is still
     * installed, so the process is ready the moment it is restarted with the consent. What
     * the check decides is only whether {@link #isActive()} may claim the shortcut works;
     * whether CGEventTapCreate returns a tap does not, see there.
     */
    private void requestPermissions() {
        try {
            int hid = (int) ioHidCheckAccess.invokeExact(HID_REQUEST_LISTEN);
            listenConsent = hid == HID_ACCESS_GRANTED;
            if (!listenConsent) {
                log.warn("No Input Monitoring consent — asking for it");
                listenConsent = (boolean) ioHidRequestAccess.invokeExact(HID_REQUEST_LISTEN);
                if (!listenConsent) {
                    log.warn("Consent was not granted outright — tick it in Settings");
                }
            }
        } catch (Throwable t) {
            log.debug("Could not ask for Input Monitoring", t);
        }

        // Accessibility is not needed for listening, but it will be for pasting text.
        // We ask now so the user goes through this once.
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment keys = temp.allocate(ADDRESS, 1);
            keys.set(ADDRESS, 0, promptOptionKey);
            MemorySegment values = temp.allocate(ADDRESS, 1);
            values.set(ADDRESS, 0, booleanTrue);

            MemorySegment options = (MemorySegment) cfDictionaryCreate.invokeExact(
                    MemorySegment.NULL, keys, values, 1L, dictKeyCallbacks, dictValueCallbacks);
            boolean trusted = (boolean) axIsProcessTrustedWithOptions.invokeExact(options);
            cfRelease.invokeExact(options);

            if (!trusted) {
                log.warn("No Accessibility consent — only needed for pasting text");
            }
        } catch (Throwable t) {
            log.debug("Could not ask for Accessibility", t);
        }
    }

    private void runLoop(CountDownLatch ready) {
        try {
            MemorySegment callback = Linker.nativeLinker().upcallStub(
                    MethodHandles.lookup()
                            .findVirtual(MacHotkeyListener.class, "onEvent",
                                    MethodType.methodType(MemorySegment.class,
                                            MemorySegment.class, int.class,
                                            MemorySegment.class, MemorySegment.class))
                            .bindTo(this),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    arena);

            // keyDown is added because Escape has to be caught while recording. From each
            // such event we read the key code alone, and only while the microphone is on —
            // we never read characters or content.
            long mask = (1L << FLAGS_CHANGED) | (1L << KEY_DOWN);
            MemorySegment created = (MemorySegment) cgEventTapCreate.invokeExact(
                    HID_EVENT_TAP, HEAD_INSERT, LISTEN_ONLY, mask, callback, MemorySegment.NULL);

            if (created.equals(MemorySegment.NULL)) {
                log.error("""

                        Could not install the keyboard tap.
                        Settings > Privacy & Security > Input Monitoring: tick the application
                        you launch the JVM from (Terminal, iTerm, IntelliJ), then quit it
                        completely (Cmd+Q) and start it again.
                        A new terminal tab is not enough — macOS reads permissions when the
                        process starts.
                        """);
                ready.countDown();
                return;
            }
            tap = created;

            MemorySegment source = (MemorySegment) cfMachPortCreateRunLoopSource
                    .invokeExact(MemorySegment.NULL, created, 0L);
            MemorySegment loop = (MemorySegment) cfRunLoopGetCurrent.invokeExact();
            runLoop = loop;

            cfRunLoopAddSource.invokeExact(loop, source, commonModes);
            cgEventTapEnable.invokeExact(created, true);

            if (listenConsent) {
                log.info("Listening for {}: hold, speak, release", key);
            } else {
                log.warn("The event tap is installed, but without Input Monitoring it receives "
                        + "nothing. Grant it and restart Viskly.");
            }
            ready.countDown();
            cfRunLoopRun.invokeExact(); // returns only on CFRunLoopStop
        } catch (Throwable t) {
            log.error("The event loop died", t);
            ready.countDown();
        }
    }

    /**
     * The upcall the system invokes. It runs on the event-loop thread, so it does the
     * absolute minimum and hands the work on — blocking this method freezes the keyboard
     * for the whole system until the timeout.
     */
    @SuppressWarnings("unused")
    private MemorySegment onEvent(MemorySegment proxy, int type, MemorySegment event, MemorySegment userInfo) {
        try {
            if (type == TAP_DISABLED_BY_TIMEOUT || type == TAP_DISABLED_BY_USER_INPUT) {
                // Our own cgEventTapEnable(false) in close() fires this event too — without
                // the flag we would switch the tap back on in the middle of shutting down.
                if (closing) {
                    return event;
                }
                log.warn("The system disabled the event tap (type {}), enabling it again", type);
                cgEventTapEnable.invokeExact(tap, true);
                return event;
            }
            if (type == KEY_DOWN) {
                if (down) {
                    long code = (long) cgEventGetIntegerValueField.invokeExact(event, KEYCODE_FIELD);
                    if (code == KEY_ESCAPE) {
                        // Setting down=false means the later modifier release no longer
                        // triggers a transcription.
                        down = false;
                        Callbacks target = callbacks;
                        dispatcher.execute(target::onCancel);
                    }
                }
                return event;
            }
            if (type == FLAGS_CHANGED) {
                long flags = (long) cgEventGetFlags.invokeExact(event);
                boolean pressed = (flags & key.deviceMask()) != 0;
                if (pressed != down) {
                    down = pressed;
                    Callbacks target = callbacks;
                    dispatcher.execute(pressed ? target::onPress : target::onRelease);
                }
            }
        } catch (Throwable t) {
            log.error("Error while handling an event", t);
        }
        return event;
    }

    @Override
    public void close() {
        closing = true;
        try {
            if (!tap.equals(MemorySegment.NULL)) {
                cgEventTapEnable.invokeExact(tap, false);
            }
            if (!runLoop.equals(MemorySegment.NULL)) {
                cfRunLoopStop.invokeExact(runLoop);
            }
        } catch (Throwable t) {
            log.debug("Error while closing the tap", t);
        }
        dispatcher.shutdownNow();
    }
}
