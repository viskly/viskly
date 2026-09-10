// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.viskly.config.VisklyProperties;

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

    private final Path file;
    private final Properties values = new Properties();
    private final List<Consumer<Settings>> listeners = new CopyOnWriteArrayList<>();

    public Settings(VisklyProperties props) {
        this.file = Path.of(System.getProperty("user.home"), ".viskly", "config.properties");

        // Defaults come from the bundled application.yml, so a fresh install behaves the
        // same as it did before this file existed.
        values.setProperty("hotkey", props.hotkey().key());
        values.setProperty("language", props.language());
        values.setProperty("inject.enabled", String.valueOf(props.inject().enabled()));
        values.setProperty("inject.restoreDelayMillis", String.valueOf(props.inject().restoreDelayMillis()));
        values.setProperty("ui.indicatorBottomMargin", String.valueOf(props.ui().indicatorBottomMargin()));
        values.setProperty("history.enabled", "true");

        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties saved = new Properties();
            saved.load(in);
            // Only keys we know about are taken. A stale key from an older version is
            // ignored rather than carried forward as a setting nothing reads.
            for (String key : values.stringPropertyNames()) {
                String v = saved.getProperty(key);
                if (v != null) {
                    values.setProperty(key, v);
                }
            }
            log.info("Settings loaded from {}", file);
        } catch (IOException e) {
            log.warn("Could not read {} — falling back to defaults", file, e);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                values.store(out, "Viskly settings. Edited by the settings window; safe to edit by hand.");
            }
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

    public String hotkey() {
        return values.getProperty("hotkey");
    }

    public void hotkey(String value) {
        values.setProperty("hotkey", value);
    }

    public String language() {
        return values.getProperty("language");
    }

    public void language(String value) {
        values.setProperty("language", value);
    }

    public boolean injectEnabled() {
        return Boolean.parseBoolean(values.getProperty("inject.enabled"));
    }

    public void injectEnabled(boolean value) {
        values.setProperty("inject.enabled", String.valueOf(value));
    }

    public int restoreDelayMillis() {
        return intValue("inject.restoreDelayMillis", 200);
    }

    public void restoreDelayMillis(int value) {
        values.setProperty("inject.restoreDelayMillis", String.valueOf(value));
    }

    public int indicatorBottomMargin() {
        return intValue("ui.indicatorBottomMargin", 40);
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
