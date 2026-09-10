// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * A scroll pane with no chrome: no border, no track, and a thumb the colour of the rest
 * of the window.
 *
 * <p>The default scroll bar draws a light track with two arrow buttons, which is both the
 * wrong colour and a control macOS itself stopped drawing years ago. The buttons cannot be
 * removed through a property — the UI delegate creates them — so they are replaced with
 * zero-sized ones.
 */
public final class Scroll {

    private Scroll() {
    }

    public static JScrollPane of(JComponent view) {
        JScrollPane pane = new JScrollPane(view,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getVerticalScrollBar().setUnitIncrement(18);
        pane.getVerticalScrollBar().setUI(new Bar());
        pane.getVerticalScrollBar().setOpaque(false);
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
        return pane;
    }

    private static final class Bar extends BasicScrollBarUI {

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroSized();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroSized();
        }

        private JButton zeroSized() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            // Nothing. The track is the window.
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            Draw.fillRound(g2, isThumbRollover() ? Ink.DIM : Ink.EDGE,
                    bounds.x + 1, bounds.y, bounds.width - 2f, bounds.height, 4);
            g2.dispose();
        }
    }
}
