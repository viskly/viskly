// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.swing.UIManager;

/**
 * The palette and the typography, in one place.
 *
 * <p>The colours are the landing page's, to the digit. A product that looks like two
 * different products across two surfaces reads as unfinished, and the cheapest way to
 * avoid that is to keep a single list of values rather than pick a dark grey by eye in
 * every component.
 *
 * <p>Fonts are derived from the platform's own UI font rather than named outright.
 * Hard-coding "Helvetica Neue" would look correct on the machine it was written on and
 * dated everywhere else; asking Swing for {@code Label.font} gives whatever macOS
 * currently considers the system face, which is what every other application uses.
 */
public final class Ink {

    private Ink() {
    }

    /** Window background. */
    public static final Color VOID = new Color(0x0E1113);
    /** Cards and anything that should sit above the background. */
    public static final Color RAISE = new Color(0x14181A);
    /** Input fields and the sidebar — anything that should sit below it. */
    public static final Color SUNK = new Color(0x0B0E0F);
    /** The selected sidebar item. */
    public static final Color SELECTED = new Color(0x1C2426);
    /** Hairlines and card borders. */
    public static final Color LINE = new Color(0x1F2729);
    /** Borders on things you can interact with — a step brighter than LINE. */
    public static final Color EDGE = new Color(0x232B2D);

    /** Primary text. Not pure white: on a near-black background it glares. */
    public static final Color IVORY = new Color(0xECF0EF);
    /** Secondary text that still has to be read. */
    public static final Color MUTED = new Color(0xA9B5B2);
    /** Labels and inactive navigation. */
    public static final Color DIM = new Color(0x8B9794);
    /** Hints, paths, timestamps — present but not competing. */
    public static final Color FAINT = new Color(0x6E7A77);

    /** The brand accent. Also: recording, and destructive actions. */
    public static final Color ACCENT = new Color(0xE95A5E);
    /** Work in progress: downloading, transcribing. */
    public static final Color AMBER = new Color(0xD99B4A);
    /** A granted permission, a working microphone. */
    public static final Color OK = new Color(0x3FA37A);

    /** Pressed state on a light button. */
    public static final Color IVORY_PRESSED = new Color(0xC9D0CE);
    /** Hover on a dark surface. */
    public static final Color HOVER = new Color(0x1A2123);

    private static final Font BASE = baseFont();
    private static final Font MONO_BASE = monoFont();

    public static Font body(int size) {
        return BASE.deriveFont(Font.PLAIN, size);
    }

    public static Font bodyBold(int size) {
        return BASE.deriveFont(Font.BOLD, size);
    }

    public static Font title(int size) {
        return BASE.deriveFont(Font.BOLD, size);
    }

    /** For paths, timestamps and byte counts — places where alignment carries meaning. */
    public static Font mono(int size) {
        return MONO_BASE.deriveFont(Font.PLAIN, size);
    }

    /** Uppercase labels need air between the letters or they read as one block. */
    public static Font tracked(Font font, float tracking) {
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.TRACKING, tracking);
        return font.deriveFont(attributes);
    }

    private static Font baseFont() {
        Font font = UIManager.getFont("Label.font");
        return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    /**
     * Swing has no handle on the platform's monospaced UI face, so this one is named.
     * The list runs from the best case down to a font every JVM is required to have.
     */
    private static Font monoFont() {
        Set<String> available = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : new String[] {"SF Mono", "Menlo", "Consolas", "DejaVu Sans Mono"}) {
            if (available.contains(family)) {
                return new Font(family, Font.PLAIN, 11);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, 11);
    }
}
