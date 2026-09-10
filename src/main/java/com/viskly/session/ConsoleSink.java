// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A view of what is happening: timings and the recognised text. Inserting into the
 * frontmost window is {@code InjectionSink}'s job, listening to the same event —
 * adding it took not one line of change in the session.
 */
@Component
public class ConsoleSink {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSink.class);

    @EventListener
    public void onRecordingStarted(SessionEvents.RecordingStarted event) {
        log.info("● recording");
    }

    @EventListener
    public void onTranscript(SessionEvents.TranscriptReady event) {
        log.info("✓ {} ms audio, {} ms model ({}x realtime)",
                event.audioMillis(), event.asrMillis(),
                String.format("%.1f", event.realtimeFactor()));
        System.out.println("  " + event.text());
    }

    @EventListener
    public void onDiscarded(SessionEvents.DictationDiscarded event) {
        log.info("· skipped ({})", event.reason());
    }
}
