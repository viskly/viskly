// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

/**
 * A paragraph of explanatory text that wraps to the width it is given.
 *
 * <p>A {@code JLabel} with HTML looked like the cheap way to do this and is not: its
 * preferred height depends on the width it will be laid out at, and BoxLayout asks for
 * the height first. The label reports the height of a single line, gets that much space,
 * and the rest of the sentence is simply not drawn — which is what happened here, in the
 * one paragraph explaining why permissions need a restart.
 *
 * <p>Measuring the wrap here means the height is right at any window size, including
 * after the user drags the window narrower.
 */
public class Hint extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int LINE = 18;

    private final String text;

    public Hint(String text) {
        this.text = text;
        setAlignmentX(LEFT_ALIGNMENT);
        // The number of lines depends on the width, and the width is only known once the
        // component has been laid out. Without this the height reported before layout is
        // kept afterwards, and a paragraph that turned out to need a third line is drawn
        // with two lines' worth of room — which is exactly how the confirmation dialog
        // lost the end of its sentence.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                revalidate();
            }
        });
    }

    /** The height this text needs at a given width, for callers that size it themselves. */
    public int heightFor(int width) {
        return wrap(width).size() * LINE + 4;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : 460;
        return new Dimension(width, heightFor(width));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        int y = LINE - 4;
        for (String line : wrap(getWidth())) {
            Draw.text(g2, line, Ink.body(12), Ink.FAINT, 0, y);
            y += LINE;
        }
        g2.dispose();
    }

    private List<String> wrap(int width) {
        List<String> lines = new ArrayList<>();
        FontMetrics fm = getFontMetrics(Ink.body(12));
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
