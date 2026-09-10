// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionListener;

import javax.swing.JButton;

/**
 * A button painted by hand, in three weights.
 *
 * <p>The native macOS button is the single element that dates this window most: it comes
 * with its own light background, its own corner radius and its own focus ring, and none of
 * them can be changed. Painting it here costs about sixty lines and removes the last piece
 * of system chrome from the interface.
 *
 * <p>{@code setContentAreaFilled(false)} is what stops Swing drawing its own version
 * underneath this one.
 */
public class FlatButton extends JButton {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** One per screen: the action the user came here for. */
        PRIMARY,
        /** Everything else. */
        GHOST,
        /** Destructive. Text only, so it cannot be hit by accident. */
        DANGER
    }

    private final Kind kind;

    public FlatButton(String text, Kind kind, ActionListener action) {
        super(text);
        this.kind = kind;
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(kind == Kind.PRIMARY ? Ink.bodyBold(13) : Ink.body(13));
        if (action != null) {
            addActionListener(action);
        }
        setSize(width(), height());
    }

    private int width() {
        int text = getFontMetrics(getFont()).stringWidth(getText());
        return kind == Kind.DANGER ? text : Math.max(96, text + 44);
    }

    private int height() {
        return kind == Kind.DANGER ? 24 : 36;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(width(), height());
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
        int w = getWidth();
        int h = getHeight();
        boolean down = getModel().isArmed() && getModel().isPressed();
        boolean over = getModel().isRollover();
        boolean on = isEnabled();

        switch (kind) {
            case PRIMARY -> {
                Color face = !on ? Ink.EDGE : down ? Ink.IVORY_PRESSED : Ink.IVORY;
                Draw.fillRound(g2, face, 0, 0, w, h, 10);
                Draw.centered(g2, getText(), getFont(), on ? Ink.VOID : Ink.FAINT, 0, w, h / 2);
            }
            case GHOST -> {
                if (over && on) {
                    Draw.fillRound(g2, Ink.HOVER, 0, 0, w, h, 10);
                }
                Draw.strokeRound(g2, on ? Ink.EDGE : Ink.LINE, 0, 0, w, h, 10);
                Draw.centered(g2, getText(), getFont(), on ? Ink.IVORY : Ink.FAINT, 0, w, h / 2);
            }
            case DANGER -> {
                Color face = on ? Ink.ACCENT : Ink.FAINT;
                Draw.centered(g2, getText(), getFont(), face, 0, w, h / 2);
                if (over && on) {
                    // An underline rather than a filled background: it says "this is a
                    // link you can press" without giving a delete button the visual
                    // weight of the primary action.
                    int text = g2.getFontMetrics().stringWidth(getText());
                    g2.fillRect((w - text) / 2, h / 2 + 9, text, 1);
                }
            }
        }
        g2.dispose();
    }
}
