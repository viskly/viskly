// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every setting in one place, validated at startup.
 */
@ConfigurationProperties(prefix = "viskly")
public record VisklyProperties(
        Path modelPath,
        String language,
        int threads,
        Hotkey hotkey,
        Audio audio,
        Inject inject,
        Ui ui,
        Asr asr) {

    public record Hotkey(String key) {
    }

    public record Audio(int sampleRate, int maxSeconds, int minSpeechMillis) {
    }

    public record Inject(boolean enabled, int settleMillis, int restoreDelayMillis) {
    }

    /** {@code indicatorBottomMargin} is measured from the top edge of the Dock, not the screen. */
    public record Ui(int indicatorBottomMargin) {
    }

    public record Asr(boolean trimAudioContext, int audioContextPadding, boolean verboseModelLog) {
    }

    /**
     * 0 means "half the cores". whisper.cpp scales poorly past that, and leaving
     * cores to the system keeps the interface smooth while a transcription runs.
     */
    public int effectiveThreads() {
        return threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
