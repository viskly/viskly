// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.history;

import java.time.Instant;

/** One finished dictation, as it is kept and shown. */
public record Dictation(long id, Instant at, String text, int audioMillis, int asrMillis) {

    public int words() {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }
}
