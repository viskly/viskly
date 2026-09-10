// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.hotkey;

/**
 * Global shortcut port.
 *
 * <p>A note for implementors: {@link Callbacks} methods may be called on the
 * system event-loop thread. Nothing in them may block.
 */
public interface HotkeyListener extends AutoCloseable {

    void listen(Callbacks callbacks);

    /** Changes the key without restarting. Named as in {@link ModifierKey}. */
    void setKey(String name);

    /**
     * Whether the shortcut is actually being received. On macOS this is false when the
     * system refused the event tap, which is the only visible symptom of a missing
     * Input Monitoring consent.
     */
    boolean isActive();

    @Override
    void close();

    interface Callbacks {

        void onPress();

        void onRelease();

        /** Escape while recording: throw the audio away, do not call the model. */
        void onCancel();
    }
}
