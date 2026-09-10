// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.viskly.audio.AudioLevel;
import com.viskly.ui.theme.Draw;
import com.viskly.ui.theme.Ink;
import com.viskly.settings.Settings;
import com.viskly.session.SessionEvents;

/**
 * An indicator above every window: a small pill near the bottom of the screen, visible
 * only while a dictation is running.
 *
 * <p>While recording, the bars show the <b>actual signal level from the microphone</b>,
 * not a decorative animation. That distinction is the whole point of this element: faked
 * movement looks identical whether the microphone works or is dead, so it carries no
 * information. Here, stillness means "I cannot hear you".
 *
 * <p>While transcribing, the bars turn into a smooth wave — movement without meaning,
 * because the processing time is not known in advance. A different shape and colour carry
 * the message "stop talking, wait".
 *
 * <p>This window's most important property is invisible: it <b>must not take focus</b>.
 * If it did, the frontmost application would stop being frontmost and the synthetic
 * ⌘V would paste into the wrong place — the indicator would break exactly what it is
 * there to watch over.
 */
@Component
public class RecordingIndicator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RecordingIndicator.class);

    /**
     * A shade darker than the settings window's background. The pill floats over other
     * applications, so it has to read as separate from whatever is behind it rather than
     * as part of it.
     */
    private static final Color PILL = new Color(0x111517);
    private static final Color EDGE = new Color(0x262E30);

    private enum Phase { HIDDEN, RECORDING, WORKING }

    private final Settings settings;
    private final AudioLevel microphone;

    private volatile boolean running;
    private JWindow window;
    private Meter meter;

    public RecordingIndicator(Settings settings, AudioLevel microphone) {
        this.settings = settings;
        this.microphone = microphone;
    }

    @Override
    public void start() {
        running = true;
        SwingUtilities.invokeLater(this::build);
    }

    private void build() {
        try {
            meter = new Meter(microphone);
            window = new JWindow();
            window.setAlwaysOnTop(true);
            // Both calls are needed: the first refuses keyboard focus, the second stops
            // the system from activating the window when it is shown.
            window.setFocusableWindowState(false);
            window.setAutoRequestFocus(false);
            window.setContentPane(meter);
            window.setSize(meter.getPreferredSize());

            // The rounding comes from the window shape, not a transparent background.
            //
            // The version with setBackground(alpha=0) flickered over any non-empty content:
            // the panel was transparent, so Swing cleared the area to full transparency
            // before every frame and only then drew. The macOS compositor would catch that
            // intermediate state and show what was underneath for a fraction of a second.
            // Over an empty desktop there is nothing to see — hence the impression that the
            // problem only happened sometimes.
            //
            // A window with a defined shape is opaque, so that step does not exist at all.
            // The price: edges are clipped hard, without antialiasing.
            try {
                Dimension size = meter.getPreferredSize();
                window.setShape(new RoundRectangle2D.Double(
                        0, 0, size.width, size.height, size.height, size.height));
            } catch (UnsupportedOperationException e) {
                log.debug("Window shapes unsupported — the indicator will be rectangular");
            }
            place();
        } catch (RuntimeException | Error e) {
            log.warn("Could not build the indicator — carrying on without it", e);
            window = null;
        }
    }

    /**
     * getMaximumWindowBounds() returns the area without the Dock and the menu bar, so the
     * margin is measured from the top edge of the Dock — the pill can never hide behind it.
     */
    private void place() {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        window.setLocation(
                screen.x + (screen.width - window.getWidth()) / 2,
                screen.y + screen.height - window.getHeight() - settings.indicatorBottomMargin());
    }

    @EventListener
    public void onRecordingStarted(SessionEvents.RecordingStarted event) {
        show(Phase.RECORDING);
    }

    @EventListener
    public void onRecordingStopped(SessionEvents.RecordingStopped event) {
        show(Phase.WORKING);
    }

    @EventListener
    public void onTranscript(SessionEvents.TranscriptReady event) {
        show(Phase.HIDDEN);
    }

    @EventListener
    public void onDiscarded(SessionEvents.DictationDiscarded event) {
        show(Phase.HIDDEN);
    }

    private void show(Phase phase) {
        SwingUtilities.invokeLater(() -> {
            if (window == null) {
                return;
            }
            if (phase == Phase.HIDDEN) {
                meter.idle();
                window.setVisible(false);
                return;
            }
            meter.set(phase);
            if (!window.isVisible()) {
                place();
                window.setVisible(true);
            }
        });
    }

    @Override
    public void stop() {
        running = false;
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                meter.idle();
                window.dispose();
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Drawn by hand: a dot and 18 rectangles is less code than any Swing component. */
    private static final class Meter extends JPanel {

        private static final long serialVersionUID = 1L;

        private static final int BARS = 18;
        private static final int BAR_W = 3;
        private static final int GAP = 4;
        private static final int DOT = 8;
        /** Room for the dot on the left; the right side only needs breathing space. */
        private static final int PAD_LEFT = 42;
        private static final int PAD_RIGHT = 22;
        private static final int HEIGHT = 44;
        private static final int MAX_BAR = 22;
        private static final int MIN_BAR = 3;

        private final transient AudioLevel source;
        private final transient Timer clock;
        private final float[] history = new float[BARS];

        private Phase phase = Phase.HIDDEN;
        private double wave;

        Meter(AudioLevel source) {
            this.source = source;
            // Opaque: it was the lack of this that caused the flicker.
            setOpaque(true);
            setBackground(PILL);
            // 30 frames per second: smooth to the eye, and 18 rectangles cost nothing
            // even when the model is about to take half the cores.
            this.clock = new Timer(33, e -> tick());
        }

        private void tick() {
            if (phase == Phase.RECORDING) {
                // Shift the history left and append the current reading on the right —
                // that is what makes it look like a scrolling wave.
                System.arraycopy(history, 1, history, 0, BARS - 1);
                history[BARS - 1] = source.level();
            } else if (phase == Phase.WORKING) {
                wave += 0.22;
                for (int i = 0; i < BARS; i++) {
                    double v = Math.sin(wave - i * 0.42);
                    history[i] = (float) (0.18 + 0.42 * (0.5 + 0.5 * v));
                }
            }
            // Repaint the bar strip alone. The background, the border and the dot are
            // static, so redrawing them thirty times a second was pure waste.
            repaint(PAD_LEFT - 1, 0, BARS * (BAR_W + GAP) - GAP + 2, HEIGHT);
        }

        void set(Phase phase) {
            this.phase = phase;
            if (phase == Phase.RECORDING) {
                Arrays.fill(history, 0f);
            }
            if (!clock.isRunning()) {
                clock.start();
            }
        }

        void idle() {
            clock.stop();
            phase = Phase.HIDDEN;
            Arrays.fill(history, 0f);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(PAD_LEFT + BARS * (BAR_W + GAP) - GAP + PAD_RIGHT, HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());

            int w = getWidth();
            int h = getHeight();

            // The window shape clips the corners, so a plain rectangle fills the
            // background — that way repainting the strip alone leaves no bright seams.
            g2.setColor(PILL);
            g2.fillRect(0, 0, w, h);
            Draw.strokeRound(g2, EDGE, 0, 0, w, h, h);

            boolean working = phase == Phase.WORKING;

            // The dot says which of the two states this is without any text to read: red
            // while the microphone is open, amber while the model runs.
            g2.setColor(working ? Ink.AMBER : Ink.ACCENT);
            g2.fillOval(18, (h - DOT) / 2, DOT, DOT);

            // White bars while recording, because they are showing a real measurement and
            // should be the brightest thing here. Amber while transcribing, matching the
            // dot, because that movement means nothing beyond "still working".
            g2.setColor(working ? Ink.AMBER : Ink.IVORY);
            for (int i = 0; i < BARS; i++) {
                int bar = MIN_BAR + Math.round(history[i] * (MAX_BAR - MIN_BAR));
                float x = PAD_LEFT + i * (BAR_W + GAP);
                Draw.fillRound(g2, g2.getColor(), x, (h - bar) / 2f, BAR_W, bar, BAR_W);
            }

            g2.dispose();
        }
    }
}
