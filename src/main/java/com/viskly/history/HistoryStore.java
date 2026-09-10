// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.viskly.session.SessionEvents;
import com.viskly.settings.Settings;

/**
 * Keeps finished dictations in a SQLite file next to the model.
 *
 * <p>Plain JDBC rather than a persistence framework: there is one table, three queries and
 * no relationships, and the alternative would pull a mapping layer and its startup cost
 * into an application whose whole point is being ready before the user finishes pressing
 * a key.
 *
 * <p>Writes happen on the transcription thread, after the listener that pastes: the text is
 * already in the user's window by the time this runs, so a slow disk delays nothing they
 * can see.
 */
@Component
public class HistoryStore implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(HistoryStore.class);

    private final Settings settings;
    private final Path file;
    private Connection connection;

    // Marked because of the second constructor, which tests use: with two and no marker,
    // Spring looks for a no-argument one and fails the whole startup without it.
    @Autowired
    public HistoryStore(Settings settings) {
        this(settings, Path.of(System.getProperty("user.home"), ".viskly", "history.db"));
    }

    HistoryStore(Settings settings, Path file) {
        this.settings = settings;
        this.file = file;
        open();
    }

    private void open() {
        try {
            Files.createDirectories(file.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement s = connection.createStatement()) {
                // SQLite only marks deleted rows as free space, so "Delete everything" left
                // every dictation readable in the file's bytes. With this, freed pages are
                // overwritten with zeros; clear() also vacuums the file.
                s.execute("PRAGMA secure_delete = ON");
                s.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS dictation (
                          id           INTEGER PRIMARY KEY AUTOINCREMENT,
                          at           INTEGER NOT NULL,
                          text         TEXT    NOT NULL,
                          audio_millis INTEGER NOT NULL,
                          asr_millis   INTEGER NOT NULL
                        )""");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS dictation_at ON dictation (at DESC)");
            }
            log.info("History at {}", file);
        } catch (Exception e) {
            log.error("Could not open the history database — dictations will not be kept", e);
            connection = null;
        }
    }

    @EventListener
    public void onTranscript(SessionEvents.TranscriptReady event) {
        if (connection == null || !settings.historyEnabled()) {
            return;
        }
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO dictation (at, text, audio_millis, asr_millis) VALUES (?, ?, ?, ?)")) {
            s.setLong(1, Instant.now().toEpochMilli());
            s.setString(2, event.text());
            s.setInt(3, event.audioMillis());
            s.setInt(4, event.asrMillis());
            s.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not record the dictation", e);
        }
    }

    public List<Dictation> recent(int limit) {
        List<Dictation> out = new ArrayList<>();
        if (connection == null) {
            return out;
        }
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT id, at, text, audio_millis, asr_millis FROM dictation ORDER BY at DESC LIMIT ?")) {
            s.setInt(1, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    out.add(new Dictation(
                            rs.getLong("id"),
                            Instant.ofEpochMilli(rs.getLong("at")),
                            rs.getString("text"),
                            rs.getInt("audio_millis"),
                            rs.getInt("asr_millis")));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read the history", e);
        }
        return out;
    }

    /** Deleting has to be possible: this file holds everything the user has ever dictated. */
    public void clear() {
        if (connection == null) {
            return;
        }
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("DELETE FROM dictation");
            // Rebuilds the file without the freed pages, and drops rows deleted before
            // secure_delete was switched on, in files created by earlier versions.
            s.execute("VACUUM");
            log.info("History cleared");
        } catch (SQLException e) {
            log.warn("Could not clear the history", e);
        }
    }

    public Path file() {
        return file;
    }

    @Override
    public void destroy() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Error closing the history database", e);
            }
        }
    }
}
