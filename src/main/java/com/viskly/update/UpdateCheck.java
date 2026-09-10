// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.update;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks GitHub whether a newer version is out, and only when the user presses the button.
 * An application whose whole claim is that nothing leaves the machine does not get to
 * phone home on its own, not even to ask about itself.
 *
 * <p>The answer is a file the release workflow attaches to every release, fetched through
 * {@code releases/latest/download/}, which GitHub redirects to the newest one. Nothing has
 * to be deployed anywhere for a release to be announced.
 *
 * <p>It never installs anything. The build is ad-hoc signed, so a replaced bundle loses
 * its consents, and doing that behind the user's back would switch the shortcut off without
 * a word. It points at the download page and leaves the rest to them.
 *
 * <p>The answer is a properties file, not JSON: two keys do not justify a JSON library in
 * the runtime, and the JDK reads this format without one.
 */
@Component
public class UpdateCheck {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheck.class);

    /** Written by .github/workflows/release.yml. */
    static final URI LATEST = URI.create(
            "https://github.com/viskly/viskly/releases/latest/download/latest.properties");
    static final URI RELEASES = URI.create("https://github.com/viskly/viskly/releases/latest");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public sealed interface Result {

        record UpToDate(String installed) implements Result {
        }

        record Available(String installed, String latest, URI page) implements Result {
        }

        record Failed(String reason) implements Result {
        }
    }

    private final URI source;
    /** Null when running from Maven: there is no manifest, so no version to compare. */
    private final String installed;

    public UpdateCheck() {
        this(LATEST, UpdateCheck.class.getPackage().getImplementationVersion());
    }

    UpdateCheck(URI source, String installed) {
        this.source = source;
        this.installed = installed;
    }

    /** Blocks for up to twice the timeout. The caller keeps it off the event thread. */
    public Result check() {
        if (installed == null) {
            return new Result.Failed("A development build has no version to compare.");
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(source).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Result.Failed(source.getHost() + " answered " + response.statusCode() + ".");
            }
            return read(response.body());
        } catch (IOException e) {
            log.warn("Could not check for updates at {}", source, e);
            return new Result.Failed("Could not reach " + source.getHost() + ".");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Failed("The check was interrupted.");
        }
    }

    Result read(String body) throws IOException {
        Properties latest = new Properties();
        latest.load(new StringReader(body));
        String version = latest.getProperty("version", "").strip();
        if (!version.matches("\\d+(\\.\\d+)*")) {
            return new Result.Failed("The answer from " + source.getHost() + " named no version.");
        }
        return compare(version, installed) > 0
                ? new Result.Available(installed, version, page(latest.getProperty("page")))
                : new Result.UpToDate(installed);
    }

    /**
     * The page is opened in the user's browser, so it has to be one this application would
     * link to itself. Anything that is not https falls back to the releases page rather
     * than trusting whatever the file says.
     */
    private static URI page(String value) {
        if (value != null && value.strip().startsWith("https://")) {
            try {
                return URI.create(value.strip());
            } catch (IllegalArgumentException e) {
                log.debug("Ignoring a malformed download page: {}", value);
            }
        }
        return RELEASES;
    }

    /**
     * Positive when {@code a} is newer. With equal numbers, a version carrying a qualifier
     * ("-SNAPSHOT") is the older one: it is the build that led up to that release.
     */
    static int compare(String a, String b) {
        String[] left = a.split("-", 2)[0].split("\\.");
        String[] right = b.split("-", 2)[0].split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length ? Integer.parseInt(left[i]) : 0;
            int y = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return Boolean.compare(!a.contains("-"), !b.contains("-"));
    }
}
