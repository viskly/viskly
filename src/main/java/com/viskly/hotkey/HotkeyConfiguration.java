// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.hotkey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.viskly.config.VisklyProperties;

/**
 * Picks the adapter by operating system. Deliberately not by Spring profile —
 * a user should not have to pass a profile for the application to work.
 */
@Configuration
public class HotkeyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HotkeyConfiguration.class);

    @Bean(destroyMethod = "close")
    public HotkeyListener hotkeyListener(VisklyProperties props) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            ModifierKey key = ModifierKey.valueOf(props.hotkey().key());
            return new MacHotkeyListener(key);
        }
        log.warn("No shortcut adapter for {} yet — falling back to console mode", os);
        return new ConsoleHotkeyListener();
    }
}
