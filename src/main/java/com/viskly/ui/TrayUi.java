// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.viskly.hotkey.ModifierKey;
import com.viskly.settings.Settings;
import com.viskly.session.SessionEvents;

/**
 * The menu bar item: proof that the application is running, and the only way to quit
 * it without going back to a terminal.
 *
 * <p>The icon is the application icon's five bars, drawn in code rather than loaded from a
 * file: five rounded rectangles are less than a resource that would still need a Retina
 * variant. The colour is a deliberate compromise: a mid grey reads on both a light and a
 * dark menu bar. A template image, which macOS tints itself ({@code
 * apple.awt.enableTemplateImages}), would read better, but a template has no colour of its
 * own and the red that says "recording" would be gone.
 */
@Component
public class TrayUi implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TrayUi.class);

    private static final Color IDLE = new Color(128, 132, 130);
    private static final Color ACTIVE = new Color(229, 72, 77);

    /** Menu bar height, in points. */
    private static final int SIZE = 22;
    /** Bar heights in points, in the proportions of the bars in scripts/icon.png. */
    private static final double[] BARS = {4.3, 8.7, 14, 8.7, 4.3};
    private static final double BAR_WIDTH = 2.4;
    private static final double BAR_PITCH = 4.0;

    private final ConfigurableApplicationContext context;
    private final Settings settings;
    private final SettingsWindow window;

    private volatile boolean running;
    private TrayIcon icon;

    public TrayUi(ConfigurableApplicationContext context, Settings settings,
                  SettingsWindow window) {
        this.context = context;
        this.settings = settings;
        this.window = window;
    }

    @Override
    public void start() {
        running = true;
        installQuitHandler();
        if (!SystemTray.isSupported()) {
            log.warn("This system has no menu bar for applications — skipping the icon");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();

            MenuItem hint = new MenuItem(hint(settings.hotkey()));
            hint.setEnabled(false);
            settings.onChange(s -> hint.setLabel(hint(s.hotkey())));
            menu.add(hint);
            menu.addSeparator();

            MenuItem open = new MenuItem("Settings…");
            open.addActionListener(e -> window.show());
            menu.add(open);

            MenuItem quit = new MenuItem("Quit Viskly");
            quit.addActionListener(e -> quit(() -> System.exit(0)));
            menu.add(quit);

            icon = new TrayIcon(glyph(IDLE), "Viskly", menu);
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            log.warn("Could not add the menu bar icon — carrying on without it", e);
            icon = null;
        }
    }

    @EventListener
    public void onRecordingStarted(SessionEvents.RecordingStarted event) {
        paint(ACTIVE, "Viskly — recording");
    }

    @EventListener
    public void onTranscript(SessionEvents.TranscriptReady event) {
        paint(IDLE, "Viskly");
    }

    @EventListener
    public void onDiscarded(SessionEvents.DictationDiscarded event) {
        paint(IDLE, "Viskly");
    }

    /**
     * One of the two cases where a notification is worth the interruption: the user pressed
     * the shortcut, nothing happened, and without this they would have no idea why. An "it
     * worked" message on every dictation would be exactly the kind of noise that makes
     * people turn notifications off.
     */
    @EventListener
    public void onModelMissing(SessionEvents.ModelMissing event) {
        if (icon != null) {
            icon.displayMessage("Viskly needs its model",
                    "Dictation is off until the model is downloaded. Opening settings.",
                    TrayIcon.MessageType.WARNING);
        }
    }

    @EventListener
    public void onNotPasted(SessionEvents.TextNotPasted event) {
        if (icon == null) {
            return;
        }
        icon.displayMessage("Viskly — text is in the clipboard", event.reason(),
                TrayIcon.MessageType.WARNING);
    }

    /**
     * Cmd+Q, Quit from Activity Monitor and logging out all arrive as an Apple event. The
     * JDK's default handler answers it by calling System.exit on the AppKit thread, and the
     * shutdown hook then waits for AWT work that needs that same thread: removing this icon
     * blocks on the tree lock while the event thread, holding it, waits for AppKit. The
     * process hangs for good and only kill -9 ends it.
     */
    private void installQuitHandler() {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            return;
        }
        Desktop.getDesktop().setQuitHandler((event, response) -> quit(response::performQuit));
    }

    /**
     * Closes the context on a thread of its own, because closing removes this icon and
     * disposes the indicator, and both need the event thread and the AppKit thread free.
     *
     * <p>The exit afterwards is not optional. A settings window that was opened once stays
     * displayable after it is hidden, and a displayable window keeps AWT's threads, and with
     * them the process, alive with no icon left to quit it from. macOS then treats that
     * process as the running instance and the next launch only brings it forward.
     */
    private void quit(Runnable exit) {
        Thread.ofPlatform().name("viskly-quit").start(() -> {
            context.close();
            exit.run();
        });
    }

    /** The key as it is printed on the keyboard, not the constant it is stored as. */
    private static String hint(String key) {
        return "Hold " + ModifierKey.valueOf(key).shortLabel() + " and speak";
    }

    private void paint(Color color, String tooltip) {
        if (icon == null) {
            return;
        }
        icon.setImage(glyph(color));
        icon.setToolTip(tooltip);
    }

    /**
     * Both scales, because the bars are under three points wide: a single 22-pixel image
     * is stretched on a Retina menu bar and they blur into a smudge. macOS picks the variant
     * itself; the JDK hands every resolution of a {@link MultiResolutionImage} to NSImage.
     */
    private static Image glyph(Color color) {
        return new BaseMultiResolutionImage(glyph(color, 1), glyph(color, 2));
    }

    private static BufferedImage glyph(Color color, int scale) {
        BufferedImage image = new BufferedImage(SIZE * scale, SIZE * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(scale, scale);
        g.setColor(color);
        double left = (SIZE - (BARS.length - 1) * BAR_PITCH - BAR_WIDTH) / 2;
        for (int i = 0; i < BARS.length; i++) {
            g.fill(new RoundRectangle2D.Double(left + i * BAR_PITCH, (SIZE - BARS[i]) / 2,
                    BAR_WIDTH, BARS[i], BAR_WIDTH, BAR_WIDTH));
        }
        g.dispose();
        return image;
    }

    @Override
    public void stop() {
        running = false;
        if (icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
