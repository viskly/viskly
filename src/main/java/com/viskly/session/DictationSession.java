// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.session;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.viskly.asr.WhisperCppEngine;
import com.viskly.audio.MicrophoneCapture;
import com.viskly.config.VisklyProperties;
import com.viskly.hotkey.HotkeyListener;
import com.viskly.settings.Settings;

/**
 * The state machine for one dictation: IDLE -&gt; RECORDING -&gt; TRANSCRIBING -&gt; IDLE.
 *
 * <p>The only place that knows the whole sequence. Every step is a port, so this class
 * knows neither which operating system it runs on nor which model recognises the speech.
 */
@Component
public class DictationSession {

    private static final Logger log = LoggerFactory.getLogger(DictationSession.class);

    private enum State { IDLE, RECORDING, TRANSCRIBING }

    private final HotkeyListener hotkey;
    private final MicrophoneCapture microphone;
    private final WhisperCppEngine engine;
    private final Settings settings;
    private final VisklyProperties props;
    private final ApplicationEventPublisher events;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    public DictationSession(HotkeyListener hotkey,
                            MicrophoneCapture microphone,
                            WhisperCppEngine engine,
                            VisklyProperties props,
                            Settings settings,
                            ApplicationEventPublisher events) {
        this.hotkey = hotkey;
        this.microphone = microphone;
        this.engine = engine;
        this.props = props;
        this.settings = settings;
        this.events = events;
    }

    /**
     * We only subscribe once the whole context is up — the model is in memory, the
     * microphone is open. The first key press has to behave like the hundredth.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // A saved shortcut takes effect immediately; asking the user to restart for a
        // one-word setting would be the kind of friction this whole tool exists to remove.
        settings.onChange(s -> hotkey.setKey(s.hotkey()));
        hotkey.setKey(settings.hotkey());
        hotkey.listen(new HotkeyListener.Callbacks() {
            @Override
            public void onPress() {
                press();
            }

            @Override
            public void onRelease() {
                release();
            }

            @Override
            public void onCancel() {
                cancel();
            }
        });
    }

    private void press() {
        if (!engine.isReady()) {
            // Recording audio we cannot transcribe would only produce a silent failure.
            events.publishEvent(new SessionEvents.ModelMissing());
            return;
        }
        if (!state.compareAndSet(State.IDLE, State.RECORDING)) {
            return; // duplicate event, or the previous transcription is still running
        }
        microphone.startRecording();
        events.publishEvent(new SessionEvents.RecordingStarted());
    }

    private void release() {
        if (!state.compareAndSet(State.RECORDING, State.TRANSCRIBING)) {
            return;
        }
        float[] samples = microphone.stopRecording();
        int audioMillis = samples.length * 1000 / props.audio().sampleRate();
        events.publishEvent(new SessionEvents.RecordingStopped(audioMillis, samples.length));

        if (audioMillis < props.audio().minSpeechMillis()) {
            // On a short or empty recording Whisper will happily invent a line of
            // film subtitles. We do not call the model, so it never gets the chance.
            state.set(State.IDLE);
            events.publishEvent(new SessionEvents.DictationDiscarded(
                    "too short: " + audioMillis + " ms"));
            return;
        }

        Thread.ofVirtual().name("viskly-asr").start(() -> transcribe(samples, audioMillis));
    }

    /**
     * Escape while recording. The audio is dropped without calling the model — that is
     * this path's entire job: a way to back out of a mistake before anything lands in
     * the frontmost window.
     */
    private void cancel() {
        if (!state.compareAndSet(State.RECORDING, State.IDLE)) {
            return;
        }
        microphone.stopRecording();
        events.publishEvent(new SessionEvents.DictationDiscarded("cancelled with Escape"));
    }

    private void transcribe(float[] samples, int audioMillis) {
        try {
            long started = System.nanoTime();
            String text = engine.transcribe(samples, settings.language());
            int asrMillis = (int) ((System.nanoTime() - started) / 1_000_000);

            if (text.isBlank()) {
                events.publishEvent(new SessionEvents.DictationDiscarded("the model returned no text"));
                return;
            }
            events.publishEvent(new SessionEvents.TranscriptReady(text, asrMillis, audioMillis));
        } catch (RuntimeException e) {
            log.error("Transcription failed", e);
            events.publishEvent(new SessionEvents.DictationDiscarded("error: " + e.getMessage()));
        } finally {
            state.set(State.IDLE);
        }
    }
}
