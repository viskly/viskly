// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.asr;

import java.io.IOException;
import java.nio.file.Files;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import com.viskly.config.VisklyProperties;

/**
 * whisper.cpp over JNI.
 *
 * <p>The model context is created once, at startup, and lives until the process ends.
 * Loading large-v3-turbo takes a few seconds — the user cannot be made to wait for it
 * after pressing the key.
 *
 * <p>A whisper.cpp context is not thread-safe, hence the lock around transcription.
 * With push-to-talk there is never more than one in flight anyway.
 */
@Component
public class WhisperCppEngine implements TranscriptionEngine, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WhisperCppEngine.class);

    private final VisklyProperties props;
    private final Object lock = new Object();

    // Volatile: set on the thread that loads the model (startup, or a virtual thread after
    // the download) and read by isReady() on the hotkey thread. Without it that thread was
    // not guaranteed to see a model loaded after startup.
    private volatile WhisperJNI whisper;
    private volatile WhisperContext context;

    public WhisperCppEngine(VisklyProperties props) {
        this.props = props;
    }

    /** False until a model is loaded. Dictation is refused rather than attempted. */
    public boolean isReady() {
        return context != null;
    }

    @Override
    public void afterPropertiesSet() {
        load();
    }

    /**
     * Loads the model if it is on disk. Missing is not an error here: a fresh install has
     * no model yet, and dying at startup would leave the user with an application that
     * shows nothing at all and explains nothing. The settings window offers the download
     * instead, and calls this again when it finishes.
     */
    public synchronized void load() {
        if (context != null) {
            return;
        }
        if (!Files.exists(props.modelPath())) {
            log.warn("No model at {} — dictation stays disabled until it is downloaded",
                    props.modelPath());
            return;
        }
        try {
            loadModel();
        } catch (Exception | LinkageError e) {
            // LinkageError: a native library that will not load throws UnsatisfiedLinkError,
            // an Error, which used to escape here and abort the whole startup.
            log.error("Could not load the model from {}", props.modelPath(), e);
        }
    }

    private void loadModel() throws Exception {
        WhisperJNI.loadLibrary();
        if (!props.asr().verboseModelLog()) {
            // Otherwise whisper.cpp floods the console. Turn it on in configuration when
            // you want to see which backend it chose — that is the only way to tell
            // whether Metal is doing the work or we are on the CPU.
            WhisperJNI.setLibraryLogger(null);
        }
        whisper = new WhisperJNI();

        long started = System.currentTimeMillis();
        context = whisper.init(props.modelPath());
        log.info("Model loaded in {} ms ({} threads)",
                System.currentTimeMillis() - started, props.effectiveThreads());
    }

    @Override
    public String transcribe(float[] samples, String language) {
        if (samples.length == 0 || !isReady()) {
            return "";
        }
        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.language = language;
        params.nThreads = props.effectiveThreads();
        params.printProgress = false;
        params.printTimestamps = false;
        params.printSpecial = false;
        params.translate = false;
        // Without this the model will "guess the continuation" of the previous call.
        params.noContext = true;
        // Two guards against hallucination on quiet audio — the third (a length gate)
        // lives in DictationSession, the fourth (VAD) is still to come.
        params.suppressBlank = true;
        params.suppressNonSpeechTokens = true;
        params.audioCtx = audioContextFor(samples.length);

        synchronized (lock) {
            WhisperContext context = this.context;
            if (context == null) {
                return ""; // closed by destroy() while this call waited for the lock
            }
            int result = whisper.full(context, params, samples, samples.length);
            if (result != 0) {
                log.warn("whisper.full returned {}", result);
                return "";
            }
            StringBuilder text = new StringBuilder();
            int segments = whisper.fullNSegments(context);
            for (int i = 0; i < segments; i++) {
                text.append(whisper.fullGetSegmentText(context, i));
            }
            return text.toString().trim();
        }
    }

    /**
     * Whisper's encoder chews through a full 30 seconds by default, no matter how much
     * you actually recorded — and that is the fixed cost which dominates everything else
     * on a two-second utterance. {@code audioCtx} trims the encoder input to the real
     * length of the recording.
     *
     * <p>The arithmetic: one mel frame is 160 samples and the encoder's convolution halves
     * them, so the context is {@code samples / 320}. A full 30 s gives 1500 — exactly the
     * model's default.
     *
     * <p>The padding is deliberate: trimming too tightly damages the end of an utterance.
     * If quality drops, raise the padding or turn trimming off and compare.
     *
     * @return 0 when trimming is disabled — whisper.cpp then uses the full value
     */
    private int audioContextFor(int samples) {
        if (!props.asr().trimAudioContext()) {
            return 0;
        }
        int trimmed = trimmedAudioContext(samples, props.asr().audioContextPadding());
        log.debug("audioCtx {} instead of 1500 ({} samples)", trimmed, samples);
        return trimmed;
    }

    /**
     * Rounded up to a multiple of 4, and that is not tidiness. The Metal backend of
     * whisper.cpp 1.7.1 asserts that rows of a half-precision matrix start on an 8-byte
     * boundary ({@code nb01 % 8 == 0}, ggml-metal.m line 1710), and an audio context above
     * 256 that is not a multiple of 4 breaks it. The assert calls abort(): the process dies
     * while transcribing, and nothing reaches the log. Measured on an M-series Mac with
     * large-v3-turbo: 257, 1297, 1298 and 1299 abort, every multiple of 4 from 148 to 1500
     * passes. Without the rounding three dictations in four longer than about three seconds
     * killed the application.
     */
    static int trimmedAudioContext(int samples, int padding) {
        int needed = (samples + 319) / 320 + padding;
        return Math.clamp((needed + 3) & ~3, 128, 1500);
    }

    /**
     * Under the same lock as transcription: quitting while whisper.full() runs used to free
     * the context out from under the native call.
     */
    @Override
    public void destroy() throws IOException {
        synchronized (lock) {
            WhisperContext closing = context;
            context = null;
            if (closing != null) {
                closing.close();
            }
        }
    }
}
