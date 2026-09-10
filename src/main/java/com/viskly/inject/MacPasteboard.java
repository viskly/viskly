// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.inject;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a transcript on the general pasteboard as content that is only passing through.
 *
 * <p>AWT's clipboard can only write plain text, and plain text on the pasteboard is fair
 * game for everything else on the Mac: clipboard managers keep it in their history, and
 * Universal Clipboard offers it to the user's other Apple devices. For an application
 * whose claim is that nothing the user says leaves the machine, both were a way out. This
 * writes through NSPasteboard instead, with two markers:
 * <ul>
 *   <li>{@code NSPasteboardContentsCurrentHostOnly}, which keeps the item off Universal
 *       Clipboard;</li>
 *   <li>{@code org.nspasteboard.TransientType} (the nspasteboard.org convention), which
 *       clipboard managers honour by not recording the item.</li>
 * </ul>
 *
 * <p>Objective-C is reached through {@code objc_msgSend} over FFM, one downcall handle per
 * method signature: the function is not variadic in the ABI, so every call must be made
 * with exactly the types of the method it lands in.
 */
final class MacPasteboard {

    private static final Logger log = LoggerFactory.getLogger(MacPasteboard.class);

    private static final String OBJC = "/usr/lib/libobjc.A.dylib";
    private static final String APPKIT = "/System/Library/Frameworks/AppKit.framework/AppKit";

    /** NSPasteboardContentsOptions.NSPasteboardContentsCurrentHostOnly */
    private static final long CURRENT_HOST_ONLY = 1;
    private static final String TRANSIENT_TYPE = "org.nspasteboard.TransientType";

    private final Arena arena = Arena.ofAuto();
    private final MethodHandle getClass;
    private final MethodHandle registerName;
    private final MethodHandle poolPush;
    private final MethodHandle poolPop;
    /** id (id, SEL) */
    private final MethodHandle sendForObject;
    /** NSInteger (id, SEL, NSUInteger) */
    private final MethodHandle sendWithLong;
    /** id (id, SEL, const char *) */
    private final MethodHandle sendWithCString;
    /** BOOL (id, SEL, id, id) */
    private final MethodHandle sendWithTwoObjects;
    /** NSPasteboardTypeString, read from AppKit rather than spelled out. */
    private final MemorySegment stringType;

    MacPasteboard() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup objc = SymbolLookup.libraryLookup(OBJC, arena);
        SymbolLookup appKit = SymbolLookup.libraryLookup(APPKIT, arena);
        MemorySegment msgSend = objc.findOrThrow("objc_msgSend");

        this.getClass = linker.downcallHandle(objc.findOrThrow("objc_getClass"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        this.registerName = linker.downcallHandle(objc.findOrThrow("sel_registerName"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        this.poolPush = linker.downcallHandle(objc.findOrThrow("objc_autoreleasePoolPush"),
                FunctionDescriptor.of(ADDRESS));
        this.poolPop = linker.downcallHandle(objc.findOrThrow("objc_autoreleasePoolPop"),
                FunctionDescriptor.ofVoid(ADDRESS));
        this.sendForObject = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        this.sendWithLong = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
        this.sendWithCString = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.sendWithTwoObjects = linker.downcallHandle(msgSend,
                FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

        // A global of type NSString *: the symbol is the cell, so it is read once more.
        this.stringType = appKit.findOrThrow("NSPasteboardTypeString")
                .reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    }

    /**
     * @return false when the pasteboard refused the text; the caller then falls back to
     *         AWT, which at least gets it pasted
     */
    boolean writeTransient(String text) {
        try (Arena call = Arena.ofConfined()) {
            // Everything created here is autoreleased; with no pool on this thread it
            // would simply leak, one string per dictation.
            MemorySegment pool = (MemorySegment) poolPush.invokeExact();
            try {
                MemorySegment board = (MemorySegment) sendForObject.invokeExact(
                        cls(call, "NSPasteboard"), sel(call, "generalPasteboard"));
                // Clears the pasteboard, as clearContents would, and sets the option for
                // what is written next.
                long changeCount = (long) sendWithLong.invokeExact(
                        board, sel(call, "prepareForNewContentsWithOptions:"), CURRENT_HOST_ONLY);
                boolean written = (boolean) sendWithTwoObjects.invokeExact(
                        board, sel(call, "setString:forType:"), string(call, text), stringType);
                MemorySegment empty = (MemorySegment) sendForObject.invokeExact(
                        cls(call, "NSData"), sel(call, "data"));
                boolean marked = (boolean) sendWithTwoObjects.invokeExact(
                        board, sel(call, "setData:forType:"), empty, string(call, TRANSIENT_TYPE));
                if (written && !marked) {
                    log.debug("Pasteboard change {}: text written, transient marker refused", changeCount);
                }
                return written;
            } finally {
                poolPop.invokeExact(pool);
            }
        } catch (Throwable t) {
            log.warn("Could not write to NSPasteboard", t);
            return false;
        }
    }

    private MemorySegment cls(Arena call, String name) throws Throwable {
        return (MemorySegment) getClass.invokeExact(call.allocateFrom(name));
    }

    private MemorySegment sel(Arena call, String name) throws Throwable {
        return (MemorySegment) registerName.invokeExact(call.allocateFrom(name));
    }

    private MemorySegment string(Arena call, String value) throws Throwable {
        return (MemorySegment) sendWithCString.invokeExact(
                cls(call, "NSString"), sel(call, "stringWithUTF8String:"), call.allocateFrom(value));
    }
}
