// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PcmBufferTest {

    @Test
    void normalisesSamplesToTheRangeTheModelExpects() {
        PcmBuffer buffer = new PcmBuffer(16_000, 1);

        // 0x0000 = silence, 0x7FFF = positive maximum, 0x8000 = minimum (little endian)
        byte[] pcm = {0x00, 0x00, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80};
        buffer.append(pcm, pcm.length);

        float[] samples = buffer.toArray();
        assertThat(samples).hasSize(3);
        assertThat(samples[0]).isZero();
        assertThat(samples[1]).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(samples[2]).isEqualTo(-1.0f);
    }

    @Test
    void neverGrowsPastTheLengthLimit() {
        PcmBuffer buffer = new PcmBuffer(16_000, 1); // 1 s = 16 000 samples

        byte[] chunk = new byte[20_000 * 2];
        boolean hasRoom = buffer.append(chunk, chunk.length);

        assertThat(hasRoom).isFalse();
        assertThat(buffer.size()).isEqualTo(16_000);
        assertThat(buffer.millis(16_000)).isEqualTo(1000);
    }

    @Test
    void resetMakesTheBufferReusable() {
        PcmBuffer buffer = new PcmBuffer(16_000, 1);
        buffer.append(new byte[400], 400);
        assertThat(buffer.size()).isEqualTo(200);

        buffer.reset();

        assertThat(buffer.size()).isZero();
        assertThat(buffer.toArray()).isEmpty();
    }
}
