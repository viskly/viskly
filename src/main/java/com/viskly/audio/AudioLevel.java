// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.audio;

/**
 * Current microphone signal level, on a 0..1 scale.
 *
 * <p>Split out as its own port so the visual layer needs to know nothing about
 * {@code TargetDataLine} or sample formats — and so it can be tested without a
 * microphone.
 */
@FunctionalInterface
public interface AudioLevel {

    /** 0 on silence or when not recording, 1 on loud speech. */
    float level();
}
