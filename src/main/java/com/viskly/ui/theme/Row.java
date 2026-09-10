// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * A preference: a name and a line explaining it on the left, the control on the right.
 *
 * <p>The layout is done by hand rather than with {@code GridBagLayout} because there are
 * only two things to place and one of them is always flush right. The label and the hint
 * are painted rather than added as {@code JLabel}s, so their baselines are set from the
 * font metrics instead of from whatever vertical padding two nested boxes happen to
 * produce.
 */
public class Row extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int HEIGHT = 56;

    private final String label;
    private final String hint;
    private final transient JComponent control;

    public Row(String label, String hint, JComponent control) {
        this.label = label;
        this.hint = hint;
        this.control = control;
        setOpaque(false);
        setLayout(null);
        add(control);
        setAlignmentX(LEFT_ALIGNMENT);
    }

    public JComponent control() {
        return control;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(240, HEIGHT);
    }

    @Override
    public void doLayout() {
        Dimension size = control.getPreferredSize();
        control.setBounds(getWidth() - size.width, (HEIGHT - size.height) / 2,
                size.width, size.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        int text = Math.max(40, getWidth() - control.getPreferredSize().width - 24);
        Draw.text(g2, Draw.fit(g2, label, Ink.body(13), text), Ink.body(13),
                Ink.IVORY, 0, HEIGHT / 2 - 9);
        Draw.text(g2, Draw.fit(g2, hint, Ink.body(11), text), Ink.body(11),
                Ink.FAINT, 0, HEIGHT / 2 + 10);
        g2.dispose();
    }
}
