// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.asr;

/**
 * Speech recognition port. The only thing the rest of the application knows
 * about the engine.
 *
 * <p>Implementations: {@link WhisperCppEngine} today, sherpa-onnx later, once we
 * want partial results while the user is still speaking.
 */
public interface TranscriptionEngine {

    /**
     * @param samples mono PCM at 16 kHz, normalised to [-1, 1]
     * @param language ISO-639-1 code, e.g. "pl"
     * @return the recognised text, trimmed; empty string when nothing was recognised
     */
    String transcribe(float[] samples, String language);
}
