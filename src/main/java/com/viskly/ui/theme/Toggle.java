// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JToggleButton;

/**
 * An on/off switch.
 *
 * <p>A {@code JCheckBox} would do the same job, but it draws a square with a tick, which
 * on macOS is the control for "include this in a list", not for "this feature is running".
 * The switch is what the platform uses for a setting that takes effect immediately, which
 * is what these are.
 */
public class Toggle extends JToggleButton {

    private static final long serialVersionUID = 1L;

    private static final int W = 42;
    private static final int H = 24;

    public Toggle(boolean on) {
        setSelected(on);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(W, H);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        boolean on = isSelected();
        Draw.fillRound(g2, on ? Ink.ACCENT : Ink.EDGE, 0, 0, W, H, H);
        if (!on) {
            Draw.strokeRound(g2, Ink.LINE, 0, 0, W, H, H);
        }
        int knob = H - 6;
        g2.setColor(Ink.IVORY);
        g2.fillOval(on ? W - knob - 3 : 3, 3, knob, knob);
        g2.dispose();
    }
}
