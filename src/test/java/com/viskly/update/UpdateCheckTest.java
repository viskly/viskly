// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.update;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class UpdateCheckTest {

    private HttpServer server;
    private URI latest;

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        latest = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest.properties");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int status, String body) {
        server.createContext("/latest.properties", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
    }

    @Test
    void reportsANewerReleaseWithItsPage() {
        answer(200, "version=0.2.0\npage=https://github.com/viskly/viskly/releases/tag/v0.2.0\n");

        UpdateCheck.Result result = new UpdateCheck(latest, "0.1.0").check();

        assertThat(result).isEqualTo(new UpdateCheck.Result.Available("0.1.0", "0.2.0",
                URI.create("https://github.com/viskly/viskly/releases/tag/v0.2.0")));
    }

    @Test
    void theSameVersionIsUpToDate() {
        answer(200, "version=0.1.0\n");

        assertThat(new UpdateCheck(latest, "0.1.0").check())
                .isEqualTo(new UpdateCheck.Result.UpToDate("0.1.0"));
    }

    @Test
    void aSnapshotIsOlderThanTheReleaseItLedTo() {
        answer(200, "version=0.1.0\n");

        assertThat(new UpdateCheck(latest, "0.1.0-SNAPSHOT").check())
                .isInstanceOf(UpdateCheck.Result.Available.class);
    }

    @Test
    void aPageThatIsNotHttpsFallsBackToTheReleasesPage() {
        answer(200, "version=9.0\npage=http://elsewhere.example/\n");

        UpdateCheck.Result result = new UpdateCheck(latest, "0.1.0").check();

        assertThat(((UpdateCheck.Result.Available) result).page())
                .isEqualTo(UpdateCheck.RELEASES);
    }

    @Test
    void aMissingFileIsAFailureNotAnUpdate() {
        answer(404, "");

        assertThat(new UpdateCheck(latest, "0.1.0").check())
                .isInstanceOf(UpdateCheck.Result.Failed.class);
    }

    @Test
    void aFileWithoutAVersionIsAFailure() {
        answer(200, "<html>not the file</html>");

        assertThat(new UpdateCheck(latest, "0.1.0").check())
                .isInstanceOf(UpdateCheck.Result.Failed.class);
    }

    @Test
    void aDevelopmentBuildDoesNotAsk() {
        assertThat(new UpdateCheck(latest, null).check())
                .isInstanceOf(UpdateCheck.Result.Failed.class);
    }

    @Test
    void comparesNumbersNotStrings() {
        assertThat(UpdateCheck.compare("0.10.0", "0.9.0")).isPositive();
        assertThat(UpdateCheck.compare("1.0", "1.0.0")).isZero();
        assertThat(UpdateCheck.compare("1.0.0-SNAPSHOT", "1.0.0")).isNegative();
    }
}
