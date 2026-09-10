// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.viskly.config.VisklyProperties;
import com.viskly.hotkey.ModifierKey;

class SettingsTest {

    private static final VisklyProperties DEFAULTS = new VisklyProperties(
            Path.of("model.bin"), "pl", 0,
            new VisklyProperties.Hotkey("RIGHT_COMMAND"),
            new VisklyProperties.Audio(16_000, 60, 300),
            new VisklyProperties.Inject(true, 40, 200),
            new VisklyProperties.Ui(40),
            new VisklyProperties.Asr(true, 96, false));

    @TempDir
    Path dir;

    private Settings loadFrom(String content) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, content);
        return new Settings(DEFAULTS, file);
    }

    @Test
    void anUnknownShortcutFallsBackToTheDefault() throws IOException {
        Settings settings = loadFrom("hotkey=right_command\n");

        assertThat(settings.hotkey()).isEqualTo("RIGHT_COMMAND");
        assertThat(ModifierKey.isKnown(settings.hotkey())).isTrue();
    }

    @Test
    void aMalformedEscapeDoesNotStopTheApplication() throws IOException {
        Settings settings = loadFrom("language=\\uZZZZ\nhotkey=LEFT_OPTION\n");

        assertThat(settings.language()).isEqualTo("pl");
        assertThat(settings.hotkey()).isEqualTo("RIGHT_COMMAND");
    }

    @Test
    void outOfRangeNumbersAreClamped() throws IOException {
        Settings settings = loadFrom("inject.restoreDelayMillis=-50\nui.indicatorBottomMargin=-5\n");

        assertThat(settings.restoreDelayMillis()).isZero();
        assertThat(settings.indicatorBottomMargin()).isZero();
    }

    @Test
    void aBlankLanguageFallsBackToTheDefault() throws IOException {
        assertThat(loadFrom("language=   \n").language()).isEqualTo("pl");
    }

    @Test
    void savingReplacesTheFileAndLeavesNoTemporaryBehind() throws IOException {
        Settings settings = loadFrom("hotkey=FN\n");
        settings.language("en");

        settings.save();

        assertThat(Files.readString(dir.resolve("config.properties"))).contains("language=en");
        assertThat(dir.resolve("config.properties.new")).doesNotExist();
        assertThat(new Settings(DEFAULTS, dir.resolve("config.properties")).hotkey()).isEqualTo("FN");
    }
}
