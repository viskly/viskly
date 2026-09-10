// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.asr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WhisperCppEngineTest {

    private static final int PADDING = 96;

    @Test
    void audioContextIsAlwaysAMultipleOfFourBecauseMetalAbortsOnAnythingElse() {
        // Every length a dictation can have, up to the 60-second limit, one frame at a time.
        for (int samples = 0; samples <= 60 * 16_000; samples += 320) {
            assertThat(WhisperCppEngine.trimmedAudioContext(samples, PADDING) % 4)
                    .as("audioCtx for %d samples", samples)
                    .isZero();
        }
    }

    @Test
    void audioContextStillCoversTheWholeRecording() {
        for (int samples = 1; samples <= 30 * 16_000; samples += 97) {
            int frames = (samples + 319) / 320;
            assertThat(WhisperCppEngine.trimmedAudioContext(samples, PADDING))
                    .as("audioCtx for %d samples", samples)
                    .isGreaterThanOrEqualTo(Math.min(frames + PADDING, 1500));
        }
    }

    @Test
    void audioContextStaysWithinWhatTheModelAccepts() {
        assertThat(WhisperCppEngine.trimmedAudioContext(0, PADDING)).isEqualTo(128);
        assertThat(WhisperCppEngine.trimmedAudioContext(60 * 16_000, PADDING)).isEqualTo(1500);
        // 25 seconds: 1250 frames + 96 = 1346, which 0.1.0 passed on as it was, and aborted.
        assertThat(WhisperCppEngine.trimmedAudioContext(25 * 16_000, PADDING)).isEqualTo(1348);
    }
}
