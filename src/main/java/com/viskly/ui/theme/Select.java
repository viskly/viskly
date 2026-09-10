// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.BasicStroke;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;

/**
 * A dropdown in the window's own colours.
 *
 * <p>Restyling a {@code JComboBox} means replacing its UI delegate: the arrow is a
 * {@code JButton} the delegate creates, the popup is a {@code JList} inside a
 * {@code JScrollPane}, and neither takes its colours from the combo box itself. Setting
 * a background on the component alone leaves a native grey arrow attached to a dark field,
 * which looks worse than leaving the whole thing native.
 *
 * @param <T> what the list holds
 */
public class Select<T> extends JComboBox<T> {

    private static final long serialVersionUID = 1L;

    public Select(T[] items, T selected, java.util.function.Function<T, String> label) {
        this(items, selected, label, null);
    }

    /** @param icon drawn before the label, in the list and in the closed field; may be null */
    public Select(T[] items, T selected, java.util.function.Function<T, String> label,
                  java.util.function.Function<T, Icon> icon) {
        super(items);
        setSelectedItem(selected);
        setFocusable(false);
        // Without this the combo box fills its whole rectangle with the default control
        // colour first, and the rounded field is drawn inside a visible lighter box.
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Ink.body(13));
        setUI(new DarkUi());
        setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            @SuppressWarnings("unchecked")
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean hasFocus) {
                JLabel item = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, hasFocus);
                item.setText(value == null ? "" : label.apply((T) value));
                item.setIcon(value == null || icon == null ? null : icon.apply((T) value));
                item.setIconTextGap(10);
                item.setFont(Ink.body(13));
                item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
                item.setBackground(isSelected ? Ink.SELECTED : Ink.RAISE);
                item.setForeground(isSelected ? Ink.IVORY : Ink.MUTED);
                item.setOpaque(true);
                return item;
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, 34);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    private final class DarkUi extends BasicComboBoxUI {

        @Override
        protected JButton createArrowButton() {
            // The delegate insists on a button. It gets one with nothing in it — the
            // chevron is painted with the field, so it lines up with the text.
            JButton empty = new JButton();
            empty.setBorder(BorderFactory.createEmptyBorder());
            empty.setContentAreaFilled(false);
            empty.setFocusable(false);
            return empty;
        }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup popup = new BasicComboPopup(comboBox) {
                private static final long serialVersionUID = 1L;

                @Override
                protected void configurePopup() {
                    super.configurePopup();
                    setBorder(BorderFactory.createLineBorder(Ink.EDGE));
                    setBackground(Ink.RAISE);
                }
            };
            popup.getList().setBackground(Ink.RAISE);
            popup.getList().setSelectionBackground(Ink.SELECTED);
            JComponent scroller = (JComponent) popup.getComponent(0);
            scroller.setBorder(BorderFactory.createEmptyBorder());
            scroller.setBackground(Ink.RAISE);
            return popup;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            int w = c.getWidth();
            int h = c.getHeight();

            Draw.fillRound(g2, Ink.SUNK, 0, 0, w, h, 9);
            Draw.strokeRound(g2, Ink.EDGE, 0, 0, w, h, 9);

            Object value = comboBox.getSelectedItem();
            JLabel rendered = value == null ? null : rendered(value);
            int x = 14;
            Icon icon = rendered == null ? null : rendered.getIcon();
            if (icon != null) {
                // Closer to the edge than the text would sit: the key cap has its own frame.
                x = 6;
                icon.paintIcon(c, g2, x, (h - icon.getIconHeight()) / 2);
                x += icon.getIconWidth() + 10;
            }
            String text = rendered == null ? "" : rendered.getText();
            Draw.text(g2, Draw.fit(g2, text, Ink.body(13), w - x - 32), Ink.body(13),
                    Ink.IVORY, x, h / 2);

            chevron(g2, w - 20, h / 2, Ink.DIM);
            g2.dispose();
        }

        private JLabel rendered(Object value) {
            Component rendered = comboBox.getRenderer().getListCellRendererComponent(
                    new JList<>(), castValue(value), -1, false, false);
            return rendered instanceof JLabel label ? label : new JLabel(String.valueOf(value));
        }

        @SuppressWarnings("unchecked")
        private T castValue(Object value) {
            return (T) value;
        }
    }

    static void chevron(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x - 4, y - 2, x, y + 2);
        g.drawLine(x, y + 2, x + 4, y - 2);
    }
}
