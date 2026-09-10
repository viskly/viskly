// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * One section of the window: a small labelled dot, a heading, and a column of content.
 *
 * <p>The dot is not decoration. It carries the section's state — red where something
 * needs attention, green where it does not — so the sidebar's four entries are readable
 * at a glance once you are inside one of them. The heading below it says what that state
 * <em>is</em>, which is the line that actually changes: "Not installed yet" becomes
 * "Ready" when the download finishes.
 */
public class Pane extends JPanel {

    private static final long serialVersionUID = 1L;

    public static final int PAD = 40;

    private final Eyebrow eyebrow;
    private final JLabel heading = new JLabel();
    private final JPanel body = new JPanel();

    public Pane(String label, String heading, Color dot) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));

        this.eyebrow = new Eyebrow(label, dot);
        this.heading.setFont(Ink.title(26));
        this.heading.setForeground(Ink.IVORY);
        this.heading.setAlignmentX(LEFT_ALIGNMENT);
        this.heading.setText(heading);

        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(LEFT_ALIGNMENT);

        add(eyebrow);
        add(Box.createVerticalStrut(8));
        add(this.heading);
        add(Box.createVerticalStrut(28));
        add(body);
    }

    /** The heading is the state, so it is replaced as the state changes. */
    public void heading(String text, Color dot) {
        heading.setText(text);
        eyebrow.dot(dot);
    }

    public JPanel body() {
        return body;
    }

    public Pane row(Component child, int spaceBefore) {
        if (spaceBefore > 0) {
            body.add(Box.createVerticalStrut(spaceBefore));
        }
        // Glue and struts are plain Components; everything else has to be told to line up
        // on the left, or BoxLayout centres it and the column looks accidental.
        if (child instanceof JComponent aligned) {
            aligned.setAlignmentX(LEFT_ALIGNMENT);
        }
        body.add(child);
        return this;
    }

    /**
     * Stops BoxLayout stretching a fixed-height row to fill whatever is left. Without it
     * a single card in an otherwise empty pane grows to the height of the window.
     */
    public static void cap(JComponent component, int height) {
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        component.setPreferredSize(new Dimension(component.getPreferredSize().width, height));
        component.setAlignmentX(LEFT_ALIGNMENT);
    }

    /** The dot and its label, drawn together so the two never drift apart. */
    private static final class Eyebrow extends JComponent {

        private static final long serialVersionUID = 1L;

        private final String label;
        private Color dot;

        Eyebrow(String label, Color dot) {
            this.label = label;
            this.dot = dot;
            setAlignmentX(LEFT_ALIGNMENT);
        }

        void dot(Color color) {
            this.dot = color;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(300, 16);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 16);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            g2.setColor(dot);
            g2.fillOval(1, getHeight() / 2 - 3, 7, 7);
            Draw.text(g2, label, Ink.body(12), Ink.DIM, 16, getHeight() / 2);
            g2.dispose();
        }
    }
}
