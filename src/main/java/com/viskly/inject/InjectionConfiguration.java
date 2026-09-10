// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.viskly.config.VisklyProperties;
import com.viskly.settings.Settings;

/**
 * Picks the adapter by operating system, exactly as the keyboard shortcut does.
 */
@Configuration
public class InjectionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InjectionConfiguration.class);

    @Bean
    public TextInjector textInjector(VisklyProperties props, Settings settings) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return new MacTextInjector(
                    props.inject().settleMillis(),
                    settings::restoreDelayMillis);
        }
        log.warn("No paste adapter for {} yet — text will only reach the clipboard", os);
        return new ClipboardOnlyInjector();
    }
}
