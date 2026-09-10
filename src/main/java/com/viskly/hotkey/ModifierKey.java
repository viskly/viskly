// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.hotkey;

/**
 * Modifier keys suitable for push-to-talk: held on their own they type no
 * character and trigger no system shortcut.
 *
 * <p>The masks come from the device-dependent bits of a CGEvent — unlike the
 * public kCGEventFlagMask* constants, these tell the left key from the right one.
 */
public enum ModifierKey {

    LEFT_CONTROL(0x00000001L),
    LEFT_SHIFT(0x00000002L),
    RIGHT_SHIFT(0x00000004L),
    LEFT_COMMAND(0x00000008L),
    RIGHT_COMMAND(0x00000010L),
    LEFT_OPTION(0x00000020L),
    RIGHT_OPTION(0x00000040L),
    RIGHT_CONTROL(0x00002000L),
    /** kCGEventFlagMaskSecondaryFn — the fn key has no device-dependent bit. */
    FN(0x00800000L);

    private final long deviceMask;

    ModifierKey(long deviceMask) {
        this.deviceMask = deviceMask;
    }

    /** Whether {@link #valueOf} would accept the name, without the exception. */
    public static boolean isKnown(String name) {
        for (ModifierKey key : values()) {
            if (key.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** "RIGHT_COMMAND" reads as a constant. This reads as a key. */
    public String label() {
        if (this == FN) {
            return "Fn";
        }
        String[] parts = name().toLowerCase().split("_");
        return capitalise(parts[0]) + " " + capitalise(parts[1]);
    }

    /** The same key as the user sees it printed: "right \u2318". */
    public String shortLabel() {
        if (this == FN) {
            return "fn";
        }
        String side = name().startsWith("RIGHT") ? "right " : "left ";
        return side + symbol();
    }

    /** What is printed on the key itself, without the side. */
    public String symbol() {
        if (this == FN) {
            return "fn";
        }
        if (name().endsWith("COMMAND")) {
            return "\u2318";
        }
        if (name().endsWith("OPTION")) {
            return "\u2325";
        }
        if (name().endsWith("CONTROL")) {
            return "\u2303";
        }
        return "\u21e7";
    }

    private static String capitalise(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    public long deviceMask() {
        return deviceMask;
    }
}
