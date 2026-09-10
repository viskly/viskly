// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.viskly.config.VisklyProperties;
import com.viskly.history.HistoryStore;
import com.viskly.settings.Settings;
import com.viskly.update.UpdateCheck;

/**
 * Spring can build the beans that have a second constructor for tests.
 *
 * <p>Unit tests call those constructors directly and never notice when Spring cannot
 * choose between two; the packaged application then dies at startup. This found exactly
 * that once. The full context is not started here: it would open the microphone and
 * install a keyboard tap.
 */
class WiringTest {

    @TempDir
    Path home;

    @Test
    void springBuildsTheBeansWithTwoConstructors() {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(VisklyProperties.class, () -> new VisklyProperties(
                    home.resolve("model.bin"), "pl", 0,
                    new VisklyProperties.Hotkey("RIGHT_COMMAND"),
                    new VisklyProperties.Audio(16_000, 60, 300),
                    new VisklyProperties.Inject(true, 40, 200),
                    new VisklyProperties.Ui(40),
                    new VisklyProperties.Asr(true, 96, false)));
            context.register(Settings.class, HistoryStore.class, UpdateCheck.class);
            context.refresh();

            assertThat(context.getBean(HistoryStore.class).file())
                    .isEqualTo(home.resolve(".viskly").resolve("history.db"));
        } finally {
            System.setProperty("user.home", realHome);
        }
    }
}
