// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.viskly.config.VisklyProperties;
import com.viskly.hotkey.ModifierKey;

/**
 * The settings a user can change, held in memory and mirrored to
 * {@code ~/.viskly/config.properties}.
 *
 * <p>This exists because {@code application.yml} lives inside the jar. In a packaged
 * application it is not editable at all, so without a writable copy somewhere the
 * settings window would have nothing to save and the shortcut could never be changed
 * without a rebuild.
 *
 * <p>Values here are read live rather than bound once at startup, so most changes take
 * effect on the next dictation instead of the next launch. The shortcut is the exception:
 * changing it re-registers the system event tap, which {@code DictationSession} does when
 * it is told about the change.
 */
@Component
public class Settings {

    private static final Logger log = LoggerFactory.getLogger(Settings.class);

    /** The ceiling the settings window offers; anything longer is a typo, not a preference. */
    private static final int MAX_RESTORE_DELAY_MILLIS = 5000;

    private final Path file;
    private final Properties defaults = new Properties();
    private final Properties values = new Properties();
    private final List<Consumer<Settings>> listeners = new CopyOnWriteArrayList<>();

    // Marked because of the second constructor, which tests use: with two and no marker,
    // Spring looks for a no-argument one and fails the whole startup without it.
    @Autowired
    public Settings(VisklyProperties props) {
        this(props, Path.of(System.getProperty("user.home"), ".viskly", "config.properties"));
    }

    Settings(VisklyProperties props, Path file) {
        this.file = file;

        // Defaults come from the bundled application.yml, so a fresh install behaves the
        // same as it did before this file existed.
        defaults.setProperty("hotkey", props.hotkey().key());
        defaults.setProperty("language", props.language());
        defaults.setProperty("inject.enabled", String.valueOf(props.inject().enabled()));
        defaults.setProperty("inject.restoreDelayMillis", String.valueOf(props.inject().restoreDelayMillis()));
        defaults.setProperty("ui.indicatorBottomMargin", String.valueOf(props.ui().indicatorBottomMargin()));
        defaults.setProperty("history.enabled", "true");
        values.putAll(defaults);

        load();
    }

    /**
     * The file says "safe to edit by hand", so whatever is in it must not stop the
     * application. A malformed unicode escape (a backslash-u followed by something other than
     * four hex digits) used to throw out of {@code Properties.load} and
     * take the whole context down with no window and no icon; an unknown shortcut name
     * reached {@code ModifierKey.valueOf} in the menu bar icon and the settings window,
     * leaving the user no way in and no way to quit. Either now falls back to the default.
     */
    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        Properties saved = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            saved.load(in);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not read {}, falling back to defaults", file, e);
            return;
        }
        // Only keys we know about are taken. A stale key from an older version is ignored
        // rather than carried forward as a setting nothing reads.
        for (String key : defaults.stringPropertyNames()) {
            String v = saved.getProperty(key);
            if (v != null) {
                values.setProperty(key, v.strip());
            }
        }
        if (!ModifierKey.isKnown(values.getProperty("hotkey"))) {
            log.warn("Unknown shortcut '{}' in {}, using {}", values.getProperty("hotkey"), file,
                    defaults.getProperty("hotkey"));
            values.setProperty("hotkey", defaults.getProperty("hotkey"));
        }
        if (values.getProperty("language").isBlank()) {
            values.setProperty("language", defaults.getProperty("language"));
        }
        log.info("Settings loaded from {}", file);
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            // Written beside the real file and moved over it, so a crash mid-write leaves
            // the previous settings rather than an empty file.
            Path next = file.resolveSibling(file.getFileName() + ".new");
            try (OutputStream out = Files.newOutputStream(next)) {
                values.store(out, "Viskly settings. Edited by the settings window; safe to edit by hand.");
            }
            Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Settings saved to {}", file);
        } catch (IOException e) {
            log.error("Could not write {}", file, e);
        }
        listeners.forEach(listener -> listener.accept(this));
    }

    /**
     * Called after every save. The session re-registers the shortcut here, the menu bar item
     * renames the key in its hint. A single slot used to hold one listener, so whichever
     * registered second silently switched the other off.
     */
    public void onChange(Consumer<Settings> listener) {
        listeners.add(listener);
    }

    /** Always a name {@link ModifierKey#valueOf} accepts; see {@link #load()}. */
    public String hotkey() {
        return values.getProperty("hotkey");
    }

    public void hotkey(String value) {
        values.setProperty("hotkey",
                ModifierKey.isKnown(value) ? value : defaults.getProperty("hotkey"));
    }

    public String language() {
        return values.getProperty("language");
    }

    public void language(String value) {
        values.setProperty("language",
                value == null || value.isBlank() ? defaults.getProperty("language") : value.strip());
    }

    public boolean injectEnabled() {
        return Boolean.parseBoolean(values.getProperty("inject.enabled"));
    }

    public void injectEnabled(boolean value) {
        values.setProperty("inject.enabled", String.valueOf(value));
    }

    /** Clamped: a negative delay made Thread.sleep throw and the clipboard never came back. */
    public int restoreDelayMillis() {
        return Math.clamp(intValue("inject.restoreDelayMillis", 200), 0, MAX_RESTORE_DELAY_MILLIS);
    }

    public void restoreDelayMillis(int value) {
        values.setProperty("inject.restoreDelayMillis", String.valueOf(value));
    }

    public int indicatorBottomMargin() {
        return Math.max(0, intValue("ui.indicatorBottomMargin", 40));
    }

    public void indicatorBottomMargin(int value) {
        values.setProperty("ui.indicatorBottomMargin", String.valueOf(value));
    }

    public boolean historyEnabled() {
        return Boolean.parseBoolean(values.getProperty("history.enabled"));
    }

    public void historyEnabled(boolean value) {
        values.setProperty("history.enabled", String.valueOf(value));
    }

    public Path file() {
        return file;
    }

    private int intValue(String key, int fallback) {
        try {
            return Integer.parseInt(values.getProperty(key));
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
