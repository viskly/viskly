// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.audio;

/**
 * Pre-allocated sample buffer. Recording must not allocate or grow while it runs —
 * a GC pause in the middle of a sentence is audible in the transcript.
 *
 * <p>60 s at 16 kHz is 960 000 floats, about 3.8 MB. We keep it in memory.
 */
public final class PcmBuffer {

    private final float[] samples;
    private int size;

    public PcmBuffer(int sampleRate, int maxSeconds) {
        this.samples = new float[sampleRate * maxSeconds];
    }

    public void reset() {
        size = 0;
    }

    /**
     * Appends a 16-bit little-endian PCM frame, normalising to the [-1, 1] range
     * whisper.cpp expects. Anything past the end of the buffer is dropped.
     *
     * @return false when the buffer is full (the dictation length limit)
     */
    public boolean append(byte[] pcm16le, int length) {
        int frames = Math.min(length / 2, samples.length - size);
        for (int i = 0; i < frames; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            samples[size++] = (short) ((hi << 8) | lo) / 32768f;
        }
        return size < samples.length;
    }

    /** A copy of the collected samples — the model gets its own array, the buffer is reused. */
    public float[] toArray() {
        float[] copy = new float[size];
        System.arraycopy(samples, 0, copy, 0, size);
        return copy;
    }

    public int size() {
        return size;
    }

    public int millis(int sampleRate) {
        return (int) (size * 1000L / sampleRate);
    }
}
