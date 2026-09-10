// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.hotkey;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback "shortcut" for systems without a native adapter — and a way to exercise
 * the whole pipeline without system permissions: Enter starts, Enter stops,
 * "x" plus Enter cancels.
 */
public final class ConsoleHotkeyListener implements HotkeyListener {

    private static final Logger log = LoggerFactory.getLogger(ConsoleHotkeyListener.class);

    private volatile boolean running = true;
    private Thread thread;

    @Override
    public boolean isActive() {
        return running;
    }

    @Override
    public void setKey(String name) {
        // Console mode has no key to change.
    }

    @Override
    public void listen(Callbacks callbacks) {
        log.info("Console mode: Enter starts and stops recording, 'x' + Enter cancels");
        thread = Thread.ofVirtual().name("viskly-console").start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
                boolean recording = false;
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (recording && "x".equalsIgnoreCase(line.trim())) {
                        recording = false;
                        callbacks.onCancel();
                    } else {
                        recording = !recording;
                        if (recording) {
                            callbacks.onPress();
                        } else {
                            callbacks.onRelease();
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Console input closed", e);
            }
        });
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
