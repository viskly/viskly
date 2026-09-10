// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;

import com.viskly.history.Dictation;
import com.viskly.ui.theme.Draw;
import com.viskly.ui.theme.Icons;
import com.viskly.ui.theme.Ink;

/**
 * The dictations, as a list of cards rather than a table.
 *
 * <p>A {@code JTable} was the obvious choice and the wrong one. Its header, grid lines and
 * selection colours all come from the system, it draws one line of plain text per row, and
 * what a user is scanning here is the sentence — not a column they are going to sort by.
 * Cards give the sentence the whole width and put the time and the word count where they
 * do not compete with it.
 *
 * <p>A click copies the card. Copying is the only thing anyone does with an entry here, so
 * selecting it first and then pressing a separate Copy button was a second step that only
 * confirmed what the user had already pointed at.
 */
public class HistoryList extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());

    /** Long enough to be read, short enough not to be mistaken for a state. */
    private static final int COPIED_MILLIS = 1400;

    private final transient List<Dictation> rows = new ArrayList<>();
    private transient Consumer<Dictation> onCopy = dictation -> { };
    private int copied = -1;
    private final transient Timer fade = new Timer(COPIED_MILLIS, e -> {
        copied = -1;
        repaint();
    });

    public HistoryList() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        fade.setRepeats(false);
    }

    public void onCopy(Consumer<Dictation> listener) {
        this.onCopy = listener;
    }

    public void reload(List<Dictation> next) {
        rows.clear();
        rows.addAll(next);
        copied = -1;
        removeAll();
        for (int i = 0; i < rows.size(); i++) {
            add(new Item(rows.get(i), i));
            add(Box.createVerticalStrut(8));
        }
        if (rows.isEmpty()) {
            add(new Empty());
        }
        revalidate();
        repaint();
    }

    private void copy(int index) {
        onCopy.accept(rows.get(index));
        copied = index;
        fade.restart();
        repaint();
    }

    private final class Item extends JComponent {

        private static final long serialVersionUID = 1L;

        private static final int H = 52;

        private final transient Dictation dictation;
        private final int index;
        private boolean hovered;

        Item(Dictation dictation, int index) {
            this.dictation = dictation;
            this.index = index;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(LEFT_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    copy(Item.this.index);
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400, H);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, H);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            int w = getWidth();
            boolean justCopied = index == copied;

            Draw.fillRound(g2, justCopied ? Ink.SELECTED : Ink.RAISE, 0, 0, w, H, 12);
            Draw.strokeRound(g2, justCopied ? Ink.EDGE : Ink.LINE, 0, 0, w, H, 12);
            if (justCopied) {
                Draw.fillRound(g2, Ink.OK, 1, 14, 3, H - 28f, 3);
            } else if (hovered) {
                Draw.fillRound(g2, Ink.HOVER, 1, 1, w - 2f, H - 2f, 12);
                Draw.strokeRound(g2, Ink.EDGE, 0, 0, w, H, 12);
            }

            Draw.text(g2, TIME.format(dictation.at()), Ink.mono(11), Ink.FAINT, 18, H / 2 - 7);
            Draw.text(g2, DAY.format(dictation.at()), Ink.mono(9), Ink.FAINT, 18, H / 2 + 9);

            // The right edge holds the word count, "Copy" under the pointer and "Copied"
            // after a click. Its width is reserved for the widest of them, so the sentence
            // does not re-cut its ellipsis every time the pointer crosses the card.
            String words = dictation.words() + " w";
            int wordsWidth = g2.getFontMetrics(Ink.mono(10)).stringWidth(words);
            int actionWidth = Icons.SIZE + 6 + g2.getFontMetrics(Ink.body(12)).stringWidth("Copied");
            int right = Math.max(wordsWidth, actionWidth);
            int textWidth = w - 76 - right - 32;
            Draw.text(g2, Draw.fit(g2, dictation.text().strip(), Ink.body(13), textWidth),
                    Ink.body(13), justCopied || hovered ? Ink.IVORY : Ink.MUTED, 76, H / 2);

            if (justCopied) {
                action(g2, w, "Copied", Ink.OK, true);
            } else if (hovered) {
                action(g2, w, "Copy", Ink.MUTED, false);
            } else {
                Draw.text(g2, words, Ink.mono(10), Ink.FAINT, w - 18 - wordsWidth, H / 2);
            }

            g2.dispose();
        }

        private void action(Graphics2D g2, int w, String label, Color color, boolean done) {
            int labelWidth = g2.getFontMetrics(Ink.body(12)).stringWidth(label);
            int x = w - 18 - labelWidth;
            Draw.text(g2, label, Ink.body(12), color, x, H / 2);
            float iconX = x - 6f - Icons.SIZE;
            float iconY = H / 2f - Icons.SIZE / 2f;
            if (done) {
                Icons.check(g2, iconX, iconY, color);
            } else {
                Icons.copy(g2, iconX, iconY, color);
            }
        }
    }

    /** The first-run state. An empty box with no explanation reads as a bug. */
    private static final class Empty extends JComponent {

        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(400, 120);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 120);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            Draw.text(g2, "Nothing yet.", Ink.body(13), Ink.MUTED, 0, 46);
            Draw.text(g2, "Dictations show up here once you have made one.",
                    Ink.body(12), Ink.FAINT, 0, 68);
            g2.dispose();
        }
    }
}
