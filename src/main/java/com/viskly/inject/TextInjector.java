// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.inject;

/**
 * Port for inserting text into the frontmost application.
 *
 * <p>An implementation may not assume it will succeed: Terminal, some Electron
 * apps and autocomplete fields will happily swallow a synthetic paste. Hence a
 * return value rather than void — the caller owes the user the truth.
 */
public interface TextInjector {

    /**
     * @return {@link Result#PASTED} when the text reached the frontmost window,
     *         {@link Result#CLIPBOARD_ONLY} when it only made it to the clipboard,
     *         {@link Result#FAILED} when even that did not work
     */
    Result insert(String text);

    /**
     * Puts the text on the clipboard without pasting it, for when the user has switched
     * pasting off.
     *
     * @return {@link Result#CLIPBOARD_ONLY}, or {@link Result#FAILED}
     */
    Result copy(String text);

    /**
     * Whether pasting would work right now. On macOS this reports the Accessibility
     * consent, which {@code CGEventPost} needs and never complains about.
     */
    default boolean canPaste() {
        return true;
    }

    /**
     * Asks for what pasting needs. On macOS that is the system's Accessibility prompt,
     * which also puts the application on the Accessibility list, ready to be switched on.
     * Called from a button, never at startup: a prompt nobody asked for, before the
     * application has shown anything, is how the first run used to feel.
     */
    default void askToPaste() {
    }

    enum Result {
        PASTED,
        CLIPBOARD_ONLY,
        FAILED
    }
}
