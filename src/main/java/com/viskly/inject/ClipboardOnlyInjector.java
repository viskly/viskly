// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.inject;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Variant for systems without a native adapter: the text lands in the clipboard
 * and pasting is left to the user.
 */
public final class ClipboardOnlyInjector implements TextInjector {

    private static final Logger log = LoggerFactory.getLogger(ClipboardOnlyInjector.class);

    @Override
    public Result insert(String text) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
            return Result.CLIPBOARD_ONLY;
        } catch (RuntimeException e) {
            log.error("Could not write to the clipboard", e);
            return Result.FAILED;
        }
    }
}
