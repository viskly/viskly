// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.model;

/** Progress of a model download, in the terms the window needs to draw it. */
public record ModelDownload(long received, long total, State state, String message) {

    public enum State { IDLE, RUNNING, VERIFYING, DONE, FAILED }

    public int percent() {
        return total <= 0 ? 0 : (int) (received * 100 / total);
    }

    public static ModelDownload idle() {
        return new ModelDownload(0, 0, State.IDLE, "");
    }
}
