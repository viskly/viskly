// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.viskly.config.VisklyProperties;

/**
 * The microphone. The line is opened once at startup, not per dictation — opening a
 * {@link TargetDataLine} can take tens of milliseconds, and that is exactly the gap
 * between the key going down and the user's first syllable.
 */
@Component
public class MicrophoneCapture implements SmartLifecycle, DisposableBean, AudioLevel {

    private static final Logger log = LoggerFactory.getLogger(MicrophoneCapture.class);

    private final VisklyProperties props;
    private final AudioFormat format;
    private final PcmBuffer buffer;

    private TargetDataLine line;
    private volatile boolean running;
    private volatile Thread reader;
    private volatile float level;

    public MicrophoneCapture(VisklyProperties props) {
        this.props = props;
        int rate = props.audio().sampleRate();
        // whisper.cpp wants 16 kHz mono float; we take 16-bit PCM from the line and normalise
        this.format = new AudioFormat(rate, 16, 1, true, false);
        this.buffer = new PcmBuffer(rate, props.audio().maxSeconds());
    }

    @Override
    public void start() {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            log.error("The microphone does not offer format {} — check the input device", format);
            return;
        }
        try {
            TargetDataLine opening = (TargetDataLine) AudioSystem.getLine(info);
            opening.open(format, format.getFrameSize() * props.audio().sampleRate() / 10); // ~100 ms
            // Only an open line is kept. A line that failed to open used to stay in the
            // field, and every dictation then read from it in a loop that never blocked.
            line = opening;
            log.info("Microphone ready: {} Hz, mono, 16 bit", props.audio().sampleRate());
        } catch (LineUnavailableException | RuntimeException e) {
            log.error("Could not open the microphone. On macOS check Settings > Privacy > "
                    + "Microphone for your terminal or for the app itself.", e);
        }
        running = true;
    }

    /** Starts collecting samples into the buffer. Called from the hotkey thread — never blocks. */
    public void startRecording() {
        if (line == null || reader != null) {
            return;
        }
        buffer.reset();
        level = 0f;
        line.flush();
        line.start();
        reader = Thread.ofVirtual().name("viskly-mic").start(this::readLoop);
    }

    /** Ends the recording and hands back the collected samples. */
    public float[] stopRecording() {
        if (line == null) {
            return new float[0];
        }
        Thread r = reader;
        reader = null;
        line.stop();
        level = 0f;
        if (r != null) {
            try {
                r.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return buffer.toArray();
    }

    private void readLoop() {
        byte[] chunk = new byte[format.getFrameSize() * props.audio().sampleRate() / 50]; // 20 ms
        while (reader == Thread.currentThread()) {
            int read = line.read(chunk, 0, chunk.length);
            if (read <= 0) {
                // On an open, started line read() blocks until it has data. Zero means the
                // line has gone (device unplugged, line closed); spinning here would burn a
                // core until the key is released.
                if (reader == Thread.currentThread()) {
                    log.warn("The microphone stopped delivering audio, ending the recording");
                }
                return;
            }
            level = loudness(chunk, read);
            if (!buffer.append(chunk, read)) {
                log.warn("Reached the {} s limit — ending the recording", props.audio().maxSeconds());
                return;
            }
        }
    }

    @Override
    public float level() {
        return level;
    }

    /**
     * The frame's RMS, rescaled for the eye. Raw RMS is small and linear for speech —
     * the square root stretches the quiet end, so the meter moves at ordinary speaking
     * volume instead of only when you shout.
     */
    private static float loudness(byte[] pcm16le, int length) {
        int frames = length / 2;
        if (frames == 0) {
            return 0f;
        }
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            int lo = pcm16le[i * 2] & 0xFF;
            int hi = pcm16le[i * 2 + 1];
            double sample = (short) ((hi << 8) | lo) / 32768.0;
            sum += sample * sample;
        }
        double rms = Math.sqrt(sum / frames);
        return (float) Math.min(1.0, Math.sqrt(rms) * 2.6);
    }

    public int recordedMillis() {
        return buffer.millis(props.audio().sampleRate());
    }

    @Override
    public void stop() {
        running = false;
        reader = null;
        if (line != null) {
            line.stop();
            line.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stop();
    }
}
