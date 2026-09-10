// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui.theme;

import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/**
 * The two dialogs this application needs, in its own colours.
 *
 * <p>{@code JOptionPane} draws a light grey box with Swing's own buttons and its own icon,
 * which lands in the middle of a dark window looking like a different program. It is also
 * the one piece of Swing whose appearance cannot be reached at all without replacing the
 * look and feel wholesale.
 *
 * <p>Undecorated on purpose: a modal alert with a title bar and a close button offers three
 * ways to dismiss it, two of which are unlabelled. Escape cancels, which is what the title
 * bar's close button would have done.
 */
public final class Ask {

    private Ask() {
    }

    /** Returns true when the user chose the action rather than cancelling. */
    public static boolean confirm(Window owner, String title, String message,
                                  String confirmLabel, boolean destructive) {
        boolean[] answer = {false};
        JDialog dialog = dialog(owner, title, message);

        FlatButton cancel = new FlatButton("Cancel", FlatButton.Kind.GHOST,
                e -> dialog.dispose());
        FlatButton confirm = new FlatButton(confirmLabel,
                destructive ? FlatButton.Kind.DANGER : FlatButton.Kind.PRIMARY, e -> {
                    answer[0] = true;
                    dialog.dispose();
                });

        finish(dialog, message, cancel, confirm);
        return answer[0];
    }

    /** A message with nothing to decide. */
    public static void tell(Window owner, String title, String message) {
        JDialog dialog = dialog(owner, title, message);
        finish(dialog, message, new FlatButton("OK", FlatButton.Kind.PRIMARY,
                e -> dialog.dispose()));
    }

    private static JDialog dialog(Window owner, String title, String message) {
        JDialog dialog = new JDialog(owner, ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);

        JPanel content = new JPanel() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
                Draw.fillRound(g2, Ink.RAISE, 0, 0, getWidth(), getHeight(), 14);
                Draw.strokeRound(g2, Ink.EDGE, 0, 0, getWidth(), getHeight(), 14);
                Draw.text(g2, title, Ink.title(16), Ink.IVORY, 28, 40);
                g2.dispose();
            }
        };
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(TOP, SIDE, SIDE, SIDE));
        dialog.setContentPane(content);
        return dialog;
    }

    private static void finish(JDialog dialog, String message, FlatButton... actions) {
        JPanel content = (JPanel) dialog.getContentPane();

        // The dialog sets its own width, so the text is measured at that width rather
        // than at the fallback Hint would use before it has been laid out.
        int textWidth = WIDTH - 56;
        Hint text = new Hint(message);
        Dimension size = new Dimension(textWidth, text.heightFor(textWidth));
        text.setPreferredSize(size);
        text.setMaximumSize(size);
        text.setMinimumSize(size);
        content.add(text);
        content.add(Box.createVerticalStrut(GAP));

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        row.add(Box.createHorizontalGlue());
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) {
                row.add(Box.createHorizontalStrut(12));
            }
            row.add(actions[i]);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUTTON_H));
        content.add(row);

        // Escape is the keyboard's cancel everywhere else on this platform, and an
        // undecorated window gives no other way out with the keyboard.
        content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        content.getActionMap().put("close", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.dispose();
            }
        });

        // The height is computed rather than packed. pack() asked BoxLayout for a
        // preferred height and came back 18 pixels short of the sum of what is in it,
        // which showed up as a button sitting almost on the bottom edge. Adding the parts
        // up here is both shorter and right.
        dialog.setSize(WIDTH, TOP + size.height + GAP + BUTTON_H + SIDE);
        // A shape rather than a translucent background, for the reason the indicator
        // documents: a transparent window is cleared before every frame and the compositor
        // can catch that. Here it also keeps the corners from showing a grey rectangle.
        try {
            dialog.setShape(new RoundRectangle2D.Double(
                    0, 0, dialog.getWidth(), dialog.getHeight(), 14, 14));
        } catch (UnsupportedOperationException e) {
            // Rectangular corners are a cosmetic loss, not a reason to fail the dialog.
        }
        dialog.setLocationRelativeTo(dialog.getOwner());
        // Without this the last button keeps a focus ring that belongs to no design here.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
        dialog.setVisible(true);
    }

    private static final int WIDTH = 420;
    /** Room above the text for the title, which is painted rather than laid out. */
    private static final int TOP = 62;
    private static final int SIDE = 28;
    private static final int GAP = 24;
    private static final int BUTTON_H = 36;
}
