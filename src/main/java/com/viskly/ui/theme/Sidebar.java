// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JComponent;

/**
 * The left column: the wordmark, the four sections, and the state of the application.
 *
 * <p>It replaces a {@code JTabbedPane}, which was the single element that dated this
 * window most — its tabs are drawn by the system and take neither a colour nor a font.
 *
 * <p>The footer is the part that earns its space. Viskly has no Dock icon and no window
 * open most of the time, so "is it listening, and on which key" is a question the user
 * otherwise has to answer by pressing the shortcut somewhere harmless and watching whether
 * anything happens.
 */
public class Sidebar extends JComponent {

    private static final long serialVersionUID = 1L;

    public static final int WIDTH = 210;

    /**
     * Space for the window buttons. The frame runs its content under the title bar, so
     * the top-left corner of this component is where macOS draws close/minimise/zoom.
     */
    private static final int INSET = 38;

    private static final int TOP = INSET + 56;
    private static final int ITEM_H = 38;
    private static final int GAP = 6;

    private final transient List<String> items;
    private final transient Consumer<Integer> onSelect;

    private int selected;
    private int hovered = -1;

    private String status = "";
    private Color statusColor = Ink.FAINT;
    private String version = "";

    public Sidebar(List<String> items, Consumer<Integer> onSelect) {
        this.items = List.copyOf(items);
        this.onSelect = onSelect;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int was = hovered;
                hovered = indexAt(e.getY());
                if (was != hovered) {
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = -1;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                int index = indexAt(e.getY());
                if (index >= 0) {
                    select(index);
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void select(int index) {
        this.selected = index;
        repaint();
        onSelect.accept(index);
    }

    public void status(String text, Color color) {
        this.status = text;
        this.statusColor = color;
        repaint();
    }

    public void version(String text) {
        this.version = text;
        repaint();
    }

    private int indexAt(int y) {
        int index = (y - TOP) / (ITEM_H + GAP);
        return index >= 0 && index < items.size() && y >= TOP ? index : -1;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, 100);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        int w = getWidth();
        int h = getHeight();

        g2.setColor(Ink.SUNK);
        g2.fillRect(0, 0, w, h);
        g2.setColor(Ink.LINE);
        g2.fillRect(w - 1, 0, 1, h);

        wordmark(g2, 24, INSET + 24);

        int y = TOP;
        for (int i = 0; i < items.size(); i++) {
            boolean on = i == selected;
            if (on) {
                Draw.fillRound(g2, Ink.SELECTED, 12, y, w - 26, ITEM_H, 10);
                Draw.fillRound(g2, Ink.ACCENT, 12, y + 10f, 3, ITEM_H - 20f, 3);
            } else if (i == hovered) {
                Draw.fillRound(g2, Ink.HOVER, 12, y, w - 26, ITEM_H, 10);
            }
            Draw.text(g2, items.get(i), Ink.body(13), on ? Ink.IVORY : Ink.DIM,
                    30, y + ITEM_H / 2);
            y += ITEM_H + GAP;
        }

        g2.setColor(Ink.LINE);
        g2.fillRect(20, h - 72, w - 44, 1);
        g2.setColor(statusColor);
        g2.fillOval(22, h - 51, 7, 7);
        Draw.text(g2, Draw.fit(g2, status, Ink.body(12), w - 60), Ink.body(12),
                Ink.DIM, 38, h - 47);
        Draw.text(g2, version, Ink.mono(10), Ink.FAINT, 22, h - 25);

        g2.dispose();
    }

    /**
     * The level bars from the icon and the landing page, drawn rather than loaded: at this
     * size a bitmap would need its own @2x file and would still be softer than five
     * rectangles.
     */
    private void wordmark(Graphics2D g, int x, int centerY) {
        int[] heights = {8, 15, 24, 15, 8};
        float bx = x;
        for (int i = 0; i < heights.length; i++) {
            Draw.fillRound(g, i == 2 ? Ink.ACCENT : Ink.IVORY,
                    bx, centerY - heights[i] / 2f, 3.5f, heights[i], 3.5f);
            bx += 8;
        }
        Draw.text(g, "Viskly", Ink.title(18), Ink.IVORY, (int) bx + 10, centerY + 1);
    }
}
