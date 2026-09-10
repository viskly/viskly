// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.model;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.viskly.config.VisklyProperties;

/**
 * Finds the speech model and, when it is missing, fetches it.
 *
 * <p>This is what makes the packaged application distributable. Until it existed, a user
 * who downloaded the app got 155 MB of something that died on first launch because a
 * 574 MB file they had no way of knowing about was not on disk.
 *
 * <p>The download resumes and verifies, for the same reasons the shell script does: the
 * CDN serving these files ends transfers by resetting the connection often enough that
 * treating a transport error as failure would strand people who already have the bytes.
 * What decides success is the SHA-256, not the exit of the request.
 */
@Component
public class ModelStore {

    private static final Logger log = LoggerFactory.getLogger(ModelStore.class);

    /**
     * Pinned to the commit that holds the file with {@link #SHA256}, not to main. A new
     * upload under the same name on main would otherwise fail the checksum on every fresh
     * install, with nothing the user could do about it.
     */
    private static final String URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/"
            + "98aa99a0a9db05ae2342309f5096248665f7cba3/ggml-large-v3-turbo-q5_0.bin";
    private static final String SHA256 =
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2";
    private static final long EXPECTED_BYTES = 574_041_195L;
    /** Anything smaller than this cannot be a model, whatever the server said. */
    private static final long MIN_BYTES = 10_000_000L;
    /** No byte for this long means the connection is dead, even if the socket says not. */
    private static final Duration STALL = Duration.ofSeconds(60);

    private final Path target;
    private volatile boolean cancelled;

    public ModelStore(VisklyProperties props) {
        this.target = props.modelPath();
    }

    public Path path() {
        return target;
    }

    public boolean isPresent() {
        try {
            return Files.exists(target) && Files.size(target) > MIN_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    public void cancel() {
        cancelled = true;
    }

    /**
     * Downloads the model, reporting progress. Blocking — the caller runs it off the
     * event thread.
     */
    public void download(Consumer<ModelDownload> progress) {
        cancelled = false;
        Path part = target.resolveSibling(target.getFileName() + ".part");

        // HTTP/1.1 on purpose: an HTTP/2 stream through a middlebox that does not close it
        // cleanly shows up as a reset at the very end of the transfer, which is exactly the
        // failure this download kept hitting.
        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            Files.createDirectories(target.getParent());
            for (int attempt = 1; attempt <= 2; attempt++) {
                if (fetch(client, part, progress) != Fetch.RANGE_REJECTED) {
                    return;
                }
                // 416: the server has nothing past what .part already holds, so the file
                // is complete but wrong, or longer than the model. Resuming can never
                // fix either; one fresh start can.
                log.warn("The server refused to resume from {} bytes, starting over", Files.size(part));
                Files.deleteIfExists(part);
            }
            progress.accept(new ModelDownload(0, 0, ModelDownload.State.FAILED,
                    "The server would not send the file. Try again later."));
        } catch (Exception e) {
            log.error("Model download failed", e);
            progress.accept(new ModelDownload(0, 0, ModelDownload.State.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private enum Fetch { FINISHED, RANGE_REJECTED }

    private Fetch fetch(HttpClient client, Path part, Consumer<ModelDownload> progress)
            throws IOException, InterruptedException {
        long have = Files.exists(part) ? Files.size(part) : 0;

        // The timeout covers everything up to the response headers; the body is watched
        // separately, below.
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(60)).GET();
        if (have > 0) {
            request.header("Range", "bytes=" + have + "-");
            log.info("Resuming the model download from {} MB", have / 1024 / 1024);
        } else {
            log.info("Downloading the model from {}", URL);
        }

        HttpResponse<InputStream> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 416) {
            response.body().close();
            return Fetch.RANGE_REJECTED;
        }
        boolean resumed = response.statusCode() == 206;
        if (response.statusCode() != 200 && !resumed) {
            response.body().close();
            progress.accept(new ModelDownload(0, 0, ModelDownload.State.FAILED,
                    "Server answered " + response.statusCode()));
            return Fetch.FINISHED;
        }
        if (!resumed) {
            have = 0; // server ignored the Range header, start over
        }

        long total = response.headers().firstValueAsLong("content-length").orElse(-1) + have;
        AtomicLong lastByte = new AtomicLong(System.nanoTime());
        AtomicBoolean stalled = new AtomicBoolean();

        try (InputStream in = response.body();
             var out = Files.newOutputStream(part,
                     resumed ? StandardOpenOption.APPEND : StandardOpenOption.CREATE,
                     resumed ? StandardOpenOption.WRITE : StandardOpenOption.TRUNCATE_EXISTING)) {
            // A read on a connection that went quiet without closing blocks forever, and
            // the window would sit on the same percentage with no way out. Closing the
            // stream from here is what makes that read return.
            Thread watchdog = Thread.ofVirtual().name("viskly-model-watchdog").start(() -> {
                try {
                    while (true) {
                        Thread.sleep(5_000);
                        if (System.nanoTime() - lastByte.get() > STALL.toNanos()) {
                            stalled.set(true);
                            in.close();
                            return;
                        }
                    }
                } catch (InterruptedException | IOException e) {
                    // interrupted: the transfer ended on its own
                }
            });
            try {
                byte[] buffer = new byte[1 << 16];
                long received = have;
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    lastByte.set(System.nanoTime());
                    if (cancelled) {
                        progress.accept(new ModelDownload(received, total,
                                ModelDownload.State.IDLE, "Cancelled"));
                        return Fetch.FINISHED;
                    }
                    out.write(buffer, 0, read);
                    received += read;
                    // Reporting every chunk would repaint the window thousands of times a
                    // second for no visible gain.
                    if (received - lastReport > 1_000_000) {
                        lastReport = received;
                        progress.accept(new ModelDownload(received, total,
                                ModelDownload.State.RUNNING, ""));
                    }
                }
            } finally {
                watchdog.interrupt();
            }
        } catch (IOException e) {
            // The bytes on disk may still be complete; the checksum decides, not this.
            log.warn(stalled.get()
                    ? "No data for " + STALL.toSeconds() + " s, checking what is on disk"
                    : "The transfer ended with an error, checking what is on disk anyway", e);
        }

        long size = Files.size(part);
        progress.accept(new ModelDownload(size, total,
                ModelDownload.State.VERIFYING, "Checking the file"));

        if (!verify(part)) {
            if (size >= EXPECTED_BYTES) {
                // Complete and still wrong: resuming would only append to a bad file and
                // fail the same way on every later attempt.
                Files.deleteIfExists(part);
                progress.accept(new ModelDownload(0, total, ModelDownload.State.FAILED,
                        "The downloaded file was damaged and has been removed. Try again."));
            } else {
                progress.accept(new ModelDownload(size, total, ModelDownload.State.FAILED,
                        stalled.get()
                                ? "The connection stalled. Starting again resumes where it stopped."
                                : "The file is incomplete. Starting again resumes where it stopped."));
            }
            return Fetch.FINISHED;
        }

        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        progress.accept(new ModelDownload(Files.size(target), Files.size(target),
                ModelDownload.State.DONE, "Ready"));
        log.info("Model ready at {}", target);
        return Fetch.FINISHED;
    }

    /**
     * Takes a model the user already has on disk.
     *
     * <p>Worth the twenty lines: this file has been downloaded by hand more than once,
     * and without this the only way to use a copy already sitting in ~/Downloads is to
     * know the exact path the application expects and move it there in a terminal.
     *
     * <p>It is copied rather than moved. A model that stops working because the user
     * tidied up their Downloads folder is a support question with no visible cause.
     */
    public void install(Path source, Consumer<ModelDownload> progress) {
        try {
            long size = Files.size(source);
            progress.accept(new ModelDownload(size, size, ModelDownload.State.VERIFYING,
                    "Checking the file"));

            if (!verify(source)) {
                progress.accept(new ModelDownload(size, size, ModelDownload.State.FAILED,
                        "That file is not the model Viskly expects, or it is incomplete."));
                return;
            }

            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(new ModelDownload(size, size, ModelDownload.State.DONE, "Ready"));
            log.info("Model installed from {} to {}", source, target);

        } catch (Exception e) {
            log.error("Could not install the model from {}", source, e);
            progress.accept(new ModelDownload(0, 0, ModelDownload.State.FAILED,
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private boolean verify(Path file) {
        try {
            if (Files.size(file) < MIN_BYTES) {
                return false;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 20];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!SHA256.equals(actual)) {
                log.warn("Checksum mismatch: {}", actual);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Could not verify the model file", e);
            return false;
        }
    }
}
