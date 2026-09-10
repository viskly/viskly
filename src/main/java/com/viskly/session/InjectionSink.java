// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.viskly.settings.Settings;
import com.viskly.inject.TextInjector;

/**
 * A second listener on the same event as {@link ConsoleSink} — this one inserts
 * the text into the frontmost application.
 *
 * <p>Insertion waits for the transcription to finish rather than streaming, because
 * the user may switch windows meanwhile. Pasting into the wrong application is worse
 * than pasting a second later.
 */
@Component
public class InjectionSink {

    private static final Logger log = LoggerFactory.getLogger(InjectionSink.class);

    private final TextInjector injector;
    private final Settings settings;
    private final ApplicationEventPublisher events;

    public InjectionSink(TextInjector injector, Settings settings,
                         ApplicationEventPublisher events) {
        this.injector = injector;
        this.settings = settings;
        this.events = events;
    }

    @EventListener
    public void onTranscript(SessionEvents.TranscriptReady event) {
        if (!settings.injectEnabled()) {
            return;
        }
        switch (injector.insert(event.text())) {
            case PASTED -> log.debug("Pasted {} characters", event.text().length());
            case CLIPBOARD_ONLY -> events.publishEvent(
                    new SessionEvents.TextNotPasted("Paste it yourself: Cmd+V"));
            case FAILED -> events.publishEvent(
                    new SessionEvents.TextNotPasted("Could not even reach the clipboard"));
        }
    }
}
