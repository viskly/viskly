// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;

/**
 * The few pictograms the window uses, drawn as strokes rather than loaded as images, for
 * the same reason as everything else here: a bitmap needs a Retina variant and still comes
 * out softer than a path.
 */
public final class Icons {

    /** Width and height of the stroke icons, in points. */
    public static final int SIZE = 12;

    private static final BasicStroke LINE =
            new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private Icons() {
    }

    /** Two overlapping sheets, the shape macOS itself uses for "copy". */
    public static void copy(Graphics2D g, float x, float y, Color color) {
        g.setColor(color);
        g.setStroke(LINE);
        g.draw(new RoundRectangle2D.Float(x + 3.5f, y + 0.5f, 8, 8, 2.5f, 2.5f));
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 3.5f, 8, 8, 2.5f, 2.5f));
    }

    public static void check(Graphics2D g, float x, float y, Color color) {
        Path2D.Float tick = new Path2D.Float();
        tick.moveTo(x + 1.5f, y + 6.5f);
        tick.lineTo(x + 4.5f, y + 9.5f);
        tick.lineTo(x + 10.5f, y + 2.5f);
        g.setColor(color);
        g.setStroke(LINE);
        g.draw(tick);
    }

    /**
     * A key cap with the symbol printed on it. People find a modifier by the symbol on the
     * keyboard, not by its name, so the name alone in a list made them translate it first.
     */
    public static Icon keyCap(String symbol) {
        return new KeyCap(symbol);
    }

    private record KeyCap(String symbol) implements Icon {

        private static final int W = 26;
        private static final int H = 22;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            Draw.fillRound(g2, Ink.SELECTED, x, y, W, H, 7);
            Draw.strokeRound(g2, Ink.EDGE, x, y, W, H, 7);
            // Symbols sit a size up from "fn": at the same size the command sign is a smudge.
            Font font = symbol.length() > 1 ? Ink.body(10) : Ink.body(13);
            Draw.centered(g2, symbol, font, Ink.IVORY, x, W, y + H / 2);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return W;
        }

        @Override
        public int getIconHeight() {
            return H;
        }
    }
}
