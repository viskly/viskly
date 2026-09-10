// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.session;

/**
 * Dictation session events. The session calls nothing directly — it publishes.
 * That way the audio thread never waits on the interface, and tests need neither
 * a microphone nor a screen.
 */
public final class SessionEvents {

    private SessionEvents() {
    }

    /** The microphone started collecting samples. The on-screen indicator must appear now. */
    public record RecordingStarted() {
    }

    /** Key released, audio in hand. */
    public record RecordingStopped(int millis, int samples) {
    }

    /** The model is done. {@code asrMillis} is the transcription time alone. */
    public record TranscriptReady(String text, int asrMillis, int audioMillis) {

        /** How many times faster than real time the model ran. */
        public double realtimeFactor() {
            return asrMillis == 0 ? 0 : (double) audioMillis / asrMillis;
        }
    }

    /** Recording discarded — too short, empty, or cancelled. */
    public record DictationDiscarded(String reason) {
    }

    /** Text recognised, but it never reached the frontmost window. Needs the user. */
    public record TextNotPasted(String reason) {
    }

    /** Someone tried to dictate before the model was on disk. */
    public record ModelMissing() {
    }
}
