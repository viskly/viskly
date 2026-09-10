// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.viskly.config.VisklyProperties;
import com.viskly.session.SessionEvents;
import com.viskly.settings.Settings;

class HistoryStoreTest {

    @TempDir
    Path dir;

    @Test
    void deletingEverythingLeavesNoTextInTheFile() throws IOException {
        // Settings reads ~/.viskly/config.properties; pointed at an empty directory it
        // takes the defaults, rather than whatever the developer's own copy says.
        String home = System.getProperty("user.home");
        Settings settings;
        try {
            System.setProperty("user.home", dir.toString());
            settings = new Settings(new VisklyProperties(
                    Path.of("model.bin"), "pl", 0,
                    new VisklyProperties.Hotkey("RIGHT_COMMAND"),
                    new VisklyProperties.Audio(16_000, 60, 300),
                    new VisklyProperties.Inject(true, 40, 200),
                    new VisklyProperties.Ui(40),
                    new VisklyProperties.Asr(true, 96, false)));
        } finally {
            System.setProperty("user.home", home);
        }
        Path db = dir.resolve("history.db");
        HistoryStore history = new HistoryStore(settings, db);
        String secret = "the account number is 7719 3342";

        history.onTranscript(new SessionEvents.TranscriptReady(secret, 400, 1800));
        assertThat(history.recent(10)).hasSize(1);

        history.clear();
        assertThat(history.recent(10)).isEmpty();
        history.destroy();
        // The point of the test: SQLite used to keep deleted rows in free pages, readable by
        // anyone who opened the file with a text editor.
        String bytes = new String(Files.readAllBytes(db), StandardCharsets.ISO_8859_1);
        assertThat(bytes).doesNotContain("7719 3342");
    }
}
