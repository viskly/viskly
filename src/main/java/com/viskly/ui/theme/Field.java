// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JTextField;

/**
 * A text field with the window's background instead of the system's white one.
 *
 * <p>{@code setOpaque(false)} plus painting the background here, rather than
 * {@code setBackground}: the native field paints a light bevel below whatever colour it is
 * given, and on a dark window that shows up as a pale hairline along the top edge.
 */
public class Field extends JTextField {

    private static final long serialVersionUID = 1L;

    private final int width;

    public Field(String text, int width) {
        super(text);
        this.width = width;
        setOpaque(false);
        setFont(Ink.body(13));
        setForeground(Ink.IVORY);
        setCaretColor(Ink.ACCENT);
        setSelectionColor(Ink.SELECTED);
        setSelectedTextColor(Ink.IVORY);
        setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(width, 34);
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
        Draw.fillRound(g2, Ink.SUNK, 0, 0, getWidth(), getHeight(), 9);
        Draw.strokeRound(g2, hasFocus() ? Ink.ACCENT : Ink.EDGE, 0, 0, getWidth(), getHeight(), 9);
        g2.dispose();
        super.paintComponent(g);
    }
}
