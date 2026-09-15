// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.hotkey;

import static java.lang.foreign.ValueLayout.ADDRESS;
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
 * The global shortcut on macOS, observed through NSEvent monitors over FFM.
 *
 * <p>It replaced a listen-only CGEventTap, which needs Input Monitoring. Without that
 * consent macOS put up its "Keystroke Receiving" prompt at every launch, and once the
 * switch was on it asked for the application to be restarted. A global monitor for
 * modifier changes needs no consent at all: measured on macOS 26.4 with a Developer ID
 * build and every consent reset, it received right Command and right Option with neither
 * Accessibility nor Input Monitoring. A push-to-talk shortcut is a modifier, so that is
 * all it takes.
 *
 * <p>Escape is a key, not a modifier, and a global monitor gets keys only with Input
 * Monitoring, so it is not watched here but by {@link EscapeTap}, on Accessibility, and
 * only while the shortcut is held. The rest of the time this application is handed no key
 * events at all.
 *
 * <p>Monitors deliver on the main thread, the one AppKit and AWT share. The handlers read
 * one field of the event and hand the work on; anything slower would stall every window of
 * the application. Adding and removing monitors is sent to the same thread through
 * {@code dispatch_async_f}.
 */
public final class MacModifierMonitor implements HotkeyListener {

    private static final Logger log = LoggerFactory.getLogger(MacModifierMonitor.class);

    private static final String OBJC = "/usr/lib/libobjc.A.dylib";
    private static final String APPKIT = "/System/Library/Frameworks/AppKit.framework/AppKit";
    private static final String SYSTEM = "/usr/lib/libSystem.B.dylib";

    /** NSEventMaskFlagsChanged */
    private static final long MASK_FLAGS_CHANGED = 1L << 12;
    /** Block_layout flags: a global block is never copied or freed by the runtime. */
    private static final int BLOCK_IS_GLOBAL = 1 << 28;

    private volatile ModifierKey key;
    private volatile Callbacks callbacks = NOOP;
    private volatile boolean down;
    private volatile boolean closing;

    // ofAuto: the upcall stubs and blocks are handed to AppKit, which holds on to them for
    // the life of the process. Nothing here may be freed while a monitor could still fire.
    private final Arena arena = Arena.ofAuto();
    private final Linker linker = Linker.nativeLinker();
    private final ExecutorService dispatcher =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("viskly-hotkey").unstarted(r));

    private final MethodHandle addMonitor;
    private final MethodHandle removeMonitor;
    private final MethodHandle sendForObject;
    private final MethodHandle sendForLong;
    private final MethodHandle dispatchAsync;
    private final MemorySegment mainQueue;
    private final MemorySegment nsEvent;
    private final MemorySegment selAddGlobal;
    private final MemorySegment selAddLocal;
    private final MemorySegment selRemove;
    private final MemorySegment selRetain;
    private final MemorySegment selModifierFlags;

    private final MemorySegment flagsBlock;
    private final MemorySegment localFlagsBlock;
    private final MemorySegment installTask;
    private final MemorySegment removeTask;
    private final EscapeTap escape = new EscapeTap(this::escaped);

    // Touched on the main thread only, apart from isActive() reading the first one.
    private volatile MemorySegment globalMonitor = MemorySegment.NULL;
    private MemorySegment localMonitor = MemorySegment.NULL;
    private volatile CountDownLatch installed = new CountDownLatch(1);

    private static final Callbacks NOOP = new Callbacks() {
        public void onPress() { }
        public void onRelease() { }
        public void onCancel() { }
    };

    public MacModifierMonitor(ModifierKey key) {
        this.key = key;

        SymbolLookup objc = SymbolLookup.libraryLookup(OBJC, arena);
        // Loaded for NSEvent. AWT has loaded it already; this only makes the lookup below
        // independent of the order in which beans start.
        SymbolLookup.libraryLookup(APPKIT, arena);
        SymbolLookup system = SymbolLookup.libraryLookup(SYSTEM, arena);

        MethodHandle getClass = linker.downcallHandle(objc.findOrThrow("objc_getClass"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        MethodHandle registerName = linker.downcallHandle(objc.findOrThrow("sel_registerName"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        MemorySegment msgSend = objc.findOrThrow("objc_msgSend");
        // One handle per method signature: objc_msgSend is not variadic in the ABI, so every
        // call has to be made with exactly the types of the method it lands in.
        this.addMonitor = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        this.removeMonitor = linker.downcallHandle(msgSend,
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        this.sendForObject = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        this.sendForLong = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
        this.dispatchAsync = linker.downcallHandle(system.findOrThrow("dispatch_async_f"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        // dispatch_get_main_queue() is a macro for the address of this symbol.
        this.mainQueue = system.findOrThrow("_dispatch_main_q");

        try {
            this.nsEvent = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSEvent"));
            this.selAddGlobal = selector(registerName, "addGlobalMonitorForEventsMatchingMask:handler:");
            this.selAddLocal = selector(registerName, "addLocalMonitorForEventsMatchingMask:handler:");
            this.selRemove = selector(registerName, "removeMonitor:");
            this.selRetain = selector(registerName, "retain");
            this.selModifierFlags = selector(registerName, "modifierFlags");
        } catch (Throwable t) {
            throw new IllegalStateException("Could not bind NSEvent", t);
        }

        MemorySegment globalBlockIsa = system.findOrThrow("_NSConcreteGlobalBlock");
        MemorySegment descriptor = arena.allocate(16, 8);
        descriptor.set(JAVA_LONG, 0, 0L);   // reserved
        descriptor.set(JAVA_LONG, 8, 32L);  // size of the block below

        this.flagsBlock = block(globalBlockIsa, descriptor, "onFlags",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        // A local monitor returns the event, or NULL to swallow it. It is always returned.
        this.localFlagsBlock = block(globalBlockIsa, descriptor, "onLocalFlags",
                MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

        this.installTask = task("install");
        this.removeTask = task("removeAll");
    }

    private MemorySegment selector(MethodHandle registerName, String name) throws Throwable {
        return (MemorySegment) registerName.invokeExact(arena.allocateFrom(name));
    }

    /**
     * An Objective-C block built by hand: the layout clang emits for a block that captures
     * nothing. The monitor APIs take a block, and a global one needs no copy or dispose
     * helpers, so the invoke pointer can be a plain FFM upcall.
     */
    private MemorySegment block(MemorySegment isa, MemorySegment descriptor, String method,
                                MethodType type, FunctionDescriptor signature) {
        try {
            MethodHandle target = MethodHandles.lookup()
                    .findVirtual(MacModifierMonitor.class, method, type).bindTo(this);
            MemorySegment invoke = linker.upcallStub(target, signature, arena);
            MemorySegment block = arena.allocate(32, 8);
            block.set(ADDRESS, 0, isa);
            block.set(JAVA_INT, 8, BLOCK_IS_GLOBAL);
            block.set(JAVA_INT, 12, 0);
            block.set(ADDRESS, 16, invoke);
            block.set(ADDRESS, 24, descriptor);
            return block;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A function for dispatch_async_f: void (*)(void *context). */
    private MemorySegment task(String method) {
        try {
            MethodHandle target = MethodHandles.lookup().findVirtual(MacModifierMonitor.class, method,
                    MethodType.methodType(void.class, MemorySegment.class)).bindTo(this);
            return linker.upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS), arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void onMain(MemorySegment task) {
        try {
            dispatchAsync.invokeExact(mainQueue, MemorySegment.NULL, task);
        } catch (Throwable t) {
            log.error("Could not reach the main thread", t);
        }
    }

    /** True once the modifier monitor is installed. No consent decides this any more. */
    @Override
    public boolean isActive() {
        return !globalMonitor.equals(MemorySegment.NULL);
    }

    @Override
    public void setKey(String name) {
        try {
            ModifierKey parsed = ModifierKey.valueOf(name);
            if (parsed != key) {
                key = parsed;
                // The handler reads the mask on every event; nothing is registered per key.
                log.info("Shortcut changed to {}", parsed);
            }
        } catch (IllegalArgumentException e) {
            log.warn("Unknown shortcut {}, keeping {}", name, key);
        }
    }

    @Override
    public void listen(Callbacks callbacks) {
        this.callbacks = callbacks;
        onMain(installTask);
        try {
            if (!installed.await(5, TimeUnit.SECONDS)) {
                log.error("The main thread did not install the shortcut within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- on the main thread ----------

    @SuppressWarnings("unused")
    private void install(MemorySegment context) {
        try {
            globalMonitor = retained((MemorySegment) addMonitor.invokeExact(
                    nsEvent, selAddGlobal, MASK_FLAGS_CHANGED, flagsBlock));
            // A global monitor sees only events meant for other applications. The local one
            // covers the moment Viskly's own window is in front.
            localMonitor = retained((MemorySegment) addMonitor.invokeExact(
                    nsEvent, selAddLocal, MASK_FLAGS_CHANGED, localFlagsBlock));
            if (isActive()) {
                log.info("Listening for {}: hold, speak, release", key);
            } else {
                log.error("AppKit refused the modifier monitor");
            }
        } catch (Throwable t) {
            log.error("Could not install the shortcut", t);
        } finally {
            installed.countDown();
        }
    }

    @SuppressWarnings("unused")
    private void removeAll(MemorySegment context) {
        localMonitor = removed(localMonitor);
        globalMonitor = removed(globalMonitor);
    }

    /**
     * The monitor object is autoreleased; the pool of the main run loop would free it while
     * AppKit still delivers to it, and removeMonitor: would later message a dead object.
     */
    private MemorySegment retained(MemorySegment monitor) throws Throwable {
        if (!monitor.equals(MemorySegment.NULL)) {
            MemorySegment ignored = (MemorySegment) sendForObject.invokeExact(monitor, selRetain);
        }
        return monitor;
    }

    private MemorySegment removed(MemorySegment monitor) {
        if (!monitor.equals(MemorySegment.NULL)) {
            try {
                removeMonitor.invokeExact(nsEvent, selRemove, monitor);
            } catch (Throwable t) {
                log.debug("Could not remove a monitor", t);
            }
        }
        return MemorySegment.NULL;
    }

    // ---------- handlers, called by AppKit on the main thread ----------

    @SuppressWarnings("unused")
    private void onFlags(MemorySegment block, MemorySegment event) {
        handleFlags(event);
    }

    @SuppressWarnings("unused")
    private MemorySegment onLocalFlags(MemorySegment block, MemorySegment event) {
        handleFlags(event);
        return event;
    }

    /**
     * An exception thrown out of an upcall ends the JVM, so nothing leaves this method.
     * NSEvent's modifierFlags carries the same device-dependent low bits as a CGEvent's
     * flags, which is what tells the right Command key from the left one.
     */
    private void handleFlags(MemorySegment event) {
        try {
            if (closing) {
                return;
            }
            long flags = (long) sendForLong.invokeExact(event, selModifierFlags);
            boolean pressed = (flags & key.deviceMask()) != 0;
            if (pressed == down) {
                return;
            }
            down = pressed;
            if (pressed) {
                escape.arm();
            } else {
                escape.disarm();
            }
            Callbacks target = callbacks;
            dispatcher.execute(pressed ? target::onPress : target::onRelease);
        } catch (Throwable t) {
            log.error("Error while handling a modifier change", t);
        }
    }

    /** Called on the Escape tap's thread. */
    private void escaped() {
        if (closing || !down) {
            return;
        }
        // The later release of the modifier then finds down already false and does not
        // start a transcription.
        down = false;
        Callbacks target = callbacks;
        dispatcher.execute(target::onCancel);
    }

    @Override
    public void close() {
        closing = true;
        escape.disarm();
        onMain(removeTask);
        dispatcher.shutdownNow();
    }
}
