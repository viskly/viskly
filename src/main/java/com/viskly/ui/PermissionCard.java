// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;
import javax.swing.JPanel;

import com.viskly.ui.theme.Draw;
import com.viskly.ui.theme.FlatButton;
import com.viskly.ui.theme.Ink;

/**
 * One of the three macOS consents: what it is for, whether it is in force, and a way to
 * go and grant it.
 *
 * <p>The button only appears when the consent is missing. macOS gives no way to revoke
 * one from inside an application, so a button next to a granted permission would either
 * do nothing or send the user somewhere to find that out.
 */
public class PermissionCard extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final int H = 74;

    private final String name;
    private final String what;
    private final transient FlatButton open;

    private boolean granted;

    public PermissionCard(String name, String what, Runnable openSettings) {
        this.name = name;
        this.what = what;
        this.open = new FlatButton("Open Settings", FlatButton.Kind.GHOST,
                e -> openSettings.run());
        setOpaque(false);
        setLayout(null);
        add(open);
        setAlignmentX(LEFT_ALIGNMENT);
    }

    public void granted(boolean value) {
        this.granted = value;
        open.setVisible(!value);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, H);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, H);
    }

    @Override
    public void doLayout() {
        Dimension size = open.getPreferredSize();
        open.setBounds(getWidth() - size.width - 18, (H - size.height) / 2,
                size.width, size.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        int w = getWidth();

        Draw.fillRound(g2, Ink.RAISE, 0, 0, w, H, 14);
        Draw.strokeRound(g2, Ink.LINE, 0, 0, w, H, 14);
        Draw.fillRound(g2, granted ? Ink.OK : Ink.ACCENT, 1, 16, 3, H - 32f, 3);

        Draw.text(g2, name, Ink.body(13), Ink.IVORY, 24, H / 2 - 9);
        Draw.text(g2, what, Ink.body(11), Ink.FAINT, 24, H / 2 + 10);

        String state = granted ? "GRANTED" : "MISSING";
        int stateWidth = g2.getFontMetrics(Ink.mono(10)).stringWidth(state);
        int right = granted ? w - 24 : w - 42 - open.getPreferredSize().width;
        g2.setColor(granted ? Ink.OK : Ink.ACCENT);
        g2.fillOval(right - stateWidth - 14, H / 2 - 4, 7, 7);
        Draw.text(g2, state, Ink.mono(10), granted ? Ink.OK : Ink.ACCENT,
                right - stateWidth, H / 2);

        g2.dispose();
    }

    /** A left-aligned strut so a stack of these keeps its width in a BoxLayout. */
    public static JComponent gap(int height) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        spacer.setPreferredSize(new Dimension(1, height));
        spacer.setAlignmentX(LEFT_ALIGNMENT);
        return spacer;
    }
}
