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

    private WhisperJNI whisper;
    private WhisperContext context;

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
        } catch (Exception e) {
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
        if (samples.length == 0 || context == null) {
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
        int needed = (samples + 319) / 320 + props.asr().audioContextPadding();
        int trimmed = Math.clamp(needed, 128, 1500);
        log.debug("audioCtx {} instead of 1500 ({} samples)", trimmed, samples);
        return trimmed;
    }

    @Override
    public void destroy() throws IOException {
        if (context != null) {
            context.close();
        }
    }
}
