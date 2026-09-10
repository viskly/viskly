// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * The four or five painting operations this interface is made of.
 *
 * <p>Every custom component needs the same antialiasing setup, the same rounded
 * rectangle and the same vertically centred string. Written out in each of them, those
 * are the lines where a component quietly ends up one pixel off from its neighbour.
 */
public final class Draw {

    private Draw() {
    }

    /** Text drawn without this looks like a screenshot of a much older application. */
    public static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    public static void fillRound(Graphics2D g, Color color, float x, float y,
                                 float w, float h, float radius) {
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, radius, radius));
    }

    /**
     * The -1 is the reason this exists. {@code draw} strokes on the shape's edge, so a
     * border at the full width spills half a pixel outside the component and comes out
     * blurred on the right and the bottom, but sharp on the left and the top.
     */
    public static void strokeRound(Graphics2D g, Color color, float x, float y,
                                   float w, float h, float radius) {
        g.setColor(color);
        g.draw(new RoundRectangle2D.Float(x, y, w - 1, h - 1, radius, radius));
    }

    /** Baseline placement from the font's own metrics, not from height/2 plus a guess. */
    public static void text(Graphics2D g, String s, Font font, Color color, int x, int centerY) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, x, centerY + (fm.getAscent() - fm.getDescent()) / 2);
    }

    public static void centered(Graphics2D g, String s, Font font, Color color,
                                int x, int w, int centerY) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        text(g, s, font, color, x + (w - fm.stringWidth(s)) / 2, centerY);
    }

    /** Cuts a string to fit, so a long dictation ends in an ellipsis instead of overflowing. */
    public static String fit(Graphics2D g, String s, Font font, int width) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        int ellipsis = fm.stringWidth("…");
        int end = s.length();
        while (end > 0 && fm.stringWidth(s.substring(0, end)) + ellipsis > width) {
            end--;
        }
        return s.substring(0, end).stripTrailing() + "…";
    }
}
