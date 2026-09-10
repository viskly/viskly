// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * A six-pixel progress bar.
 *
 * <p>A {@code JProgressBar} on macOS is a blue capsule with its own height and its own
 * animation, and none of that belongs here. This one also carries the indeterminate case,
 * for the stretch between "the file has arrived" and "the checksum matches", where there
 * is nothing to count but something is still happening.
 */
public class ThinProgress extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int H = 6;

    private final transient Timer clock;
    private float value;
    private boolean indeterminate;
    private float sweep;

    public ThinProgress() {
        this.clock = new Timer(33, e -> {
            sweep += 0.014f;
            if (sweep > 1f) {
                sweep -= 1f;
            }
            repaint();
        });
    }

    public void value(float fraction) {
        this.indeterminate = false;
        this.value = Math.max(0f, Math.min(1f, fraction));
        clock.stop();
        repaint();
    }

    public void indeterminate() {
        this.indeterminate = true;
        if (!clock.isRunning()) {
            clock.start();
        }
    }

    /** Stops the animation. Left running, the timer keeps the window awake for nothing. */
    public void idle() {
        clock.stop();
        indeterminate = false;
        value = 0f;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, H);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, H);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
        int w = getWidth();
        Draw.fillRound(g2, Ink.EDGE, 0, 0, w, H, H);
        if (indeterminate) {
            float band = w * 0.28f;
            float x = (w + band) * sweep - band;
            Draw.fillRound(g2, Ink.AMBER, Math.max(0, x), 0,
                    Math.min(band, w - Math.max(0, x)), H, H);
        } else if (value > 0f) {
            Draw.fillRound(g2, Ink.AMBER, 0, 0, w * value, H, H);
        }
        g2.dispose();
    }
}
