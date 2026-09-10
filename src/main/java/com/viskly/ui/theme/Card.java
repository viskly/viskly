// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;

import javax.swing.JPanel;

/** A raised surface with a hairline border. The only container shape in this window. */
public class Card extends JPanel {

    private static final long serialVersionUID = 1L;

    private Color accent;

    public Card(LayoutManager layout) {
        super(layout);
        setOpaque(false);
    }

    /** A coloured strip down the left edge: the status of the thing inside the card. */
    public Card accent(Color color) {
        this.accent = color;
        repaint();
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        int w = getWidth();
        int h = getHeight();
        Draw.fillRound(g2, Ink.RAISE, 0, 0, w, h, 14);
        Draw.strokeRound(g2, Ink.LINE, 0, 0, w, h, 14);
        if (accent != null) {
            Draw.fillRound(g2, accent, 1, h * 0.22f, 3, h * 0.56f, 3);
        }
        g2.dispose();
    }
}
