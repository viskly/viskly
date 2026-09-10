// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.FileDialog;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.viskly.asr.WhisperCppEngine;
import com.viskly.history.Dictation;
import com.viskly.history.HistoryStore;
import com.viskly.hotkey.HotkeyListener;
import com.viskly.hotkey.ModifierKey;
import com.viskly.inject.TextInjector;
import com.viskly.model.ModelDownload;
import com.viskly.model.ModelStore;
import com.viskly.session.SessionEvents;
import com.viskly.settings.Settings;
import com.viskly.ui.theme.Ask;
import com.viskly.ui.theme.Card;
import com.viskly.ui.theme.Draw;
import com.viskly.ui.theme.Field;
import com.viskly.ui.theme.Hint;
import com.viskly.ui.theme.FlatButton;
import com.viskly.ui.theme.Icons;
import com.viskly.ui.theme.Ink;
import com.viskly.ui.theme.Pane;
import com.viskly.ui.theme.Row;
import com.viskly.ui.theme.Scroll;
import com.viskly.ui.theme.Select;
import com.viskly.ui.theme.Sidebar;
import com.viskly.ui.theme.ThinProgress;
import com.viskly.ui.theme.Toggle;
import com.viskly.update.UpdateCheck;

/**
 * The one window this application has.
 *
 * <p>It exists for four things a menu bar icon cannot do: download 574 MB with something
 * to look at, change a setting that used to live inside the jar, show what has been
 * dictated, and say which of the three macOS consents is missing.
 *
 * <p>Everything in it is painted by this codebase. That is not decoration for its own
 * sake — a {@code JTabbedPane} with default buttons and a {@code JTable} is recognisably a
 * Java application from fifteen years ago, and for a tool that sits in the menu bar all
 * day, looking untrustworthy is a functional problem.
 *
 * <p>Built lazily on first use. Most sessions never open it, and a window nobody asks for
 * has no business costing startup time.
 */
@Component
public class SettingsWindow {

    private static final Logger log = LoggerFactory.getLogger(SettingsWindow.class);

    private static final int MODEL = 0;
    private static final int GENERAL = 1;
    private static final int HISTORY = 2;
    private static final int PERMISSIONS = 3;

    private final Settings settings;
    private final ModelStore models;
    private final WhisperCppEngine engine;
    private final HistoryStore history;
    private final HotkeyListener hotkey;
    private final TextInjector injector;
    private final UpdateCheck updates;

    private JFrame frame;
    private Sidebar sidebar;
    private CardLayout cards;
    private JPanel deck;

    private Pane modelPane;
    private ModelCard modelCard;
    private FlatButton downloadButton;
    private FlatButton chooseButton;

    private Select<ModifierKey> keySelect;
    private Field languageField;
    private Toggle pasteToggle;
    private Toggle historyToggle;
    private Field restoreField;
    private Field marginField;

    private Pane historyPane;
    private HistoryList historyList;

    private Pane permissionsPane;
    private PermissionCard microphoneCard;
    private PermissionCard inputMonitoringCard;
    private PermissionCard accessibilityCard;

    public SettingsWindow(Settings settings, ModelStore models, WhisperCppEngine engine,
                          HistoryStore history, HotkeyListener hotkey, TextInjector injector,
                          UpdateCheck updates) {
        this.settings = settings;
        this.models = models;
        this.engine = engine;
        this.history = history;
        this.hotkey = hotkey;
        this.injector = injector;
        this.updates = updates;
    }

    /**
     * First run: with no model there is nothing the application can do, and with no Dock
     * icon there is nothing for the user to click. So it opens itself, once, on the
     * section that matters.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!models.isPresent()) {
            show(MODEL);
        }
    }

    @EventListener
    public void onModelMissing(SessionEvents.ModelMissing event) {
        show(MODEL);
    }

    /** Opened from the menu bar. */
    public void show() {
        show(models.isPresent() ? GENERAL : MODEL);
    }

    private void show(int section) {
        SwingUtilities.invokeLater(() -> {
            if (frame == null) {
                build();
            }
            refresh();
            sidebar.select(section);
            frame.setVisible(true);
            // An agent application is never the active one by itself, so toFront() alone
            // leaves the window behind whatever the user is looking at, or on another
            // display, and the first run looks as if nothing happened.
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                Desktop.getDesktop().requestForeground(true);
            }
            frame.toFront();
            frame.requestFocus();
        });
    }

    // ---------- frame ----------

    private void build() {
        frame = new JFrame("Viskly");
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        // The window's content runs under the title bar, so the dark background reaches
        // the top edge and only the three round buttons float over it. Without this the
        // window wears a light grey strip that belongs to no part of the design. The
        // properties are macOS-only and ignored elsewhere, which is why there is no
        // platform check around them.
        frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);

        deck = new JPanel(cards = new CardLayout());
        deck.setOpaque(false);
        deck.add(modelPane(), "model");
        deck.add(generalPane(), "general");
        deck.add(historyPane(), "history");
        deck.add(permissionsPane(), "permissions");

        sidebar = new Sidebar(List.of("Model", "General", "History", "Permissions"),
                this::onSection);
        sidebar.version(version());

        JPanel root = new JPanel(new BorderLayout()) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Ink.VOID);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        root.setOpaque(true);
        root.add(sidebar, BorderLayout.WEST);
        root.add(deck, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(760, 560));
        frame.setSize(new Dimension(880, 640));
        frame.setLocationRelativeTo(null);
    }

    private void onSection(int index) {
        String name = switch (index) {
            case MODEL -> "model";
            case GENERAL -> "general";
            case HISTORY -> "history";
            default -> "permissions";
        };
        cards.show(deck, name);
        if (index == HISTORY) {
            historyList.reload(history.recent(200));
        }
        if (index == PERMISSIONS) {
            refreshPermissions();
        }
    }

    // ---------- Model ----------

    private JComponent modelPane() {
        modelPane = new Pane("Speech model", "Checking…", Ink.AMBER);

        modelCard = new ModelCard(models.path());
        Pane.cap(modelCard, ModelCard.H);
        modelPane.row(modelCard, 0);

        downloadButton = new FlatButton("Download the model", FlatButton.Kind.PRIMARY,
                e -> startDownload());
        chooseButton = new FlatButton("Choose a file", FlatButton.Kind.GHOST,
                e -> chooseFile());
        modelPane.row(buttons(downloadButton, chooseButton), 24);

        modelPane.row(new Hint("574 MB, downloaded once from Hugging Face and verified by "
                + "checksum. An interrupted transfer resumes where it stopped."), 24);
        modelPane.row(Box.createVerticalGlue(), 0);
        return modelPane;
    }

    private void startDownload() {
        downloadButton.setEnabled(false);
        chooseButton.setEnabled(false);
        modelCard.status("Starting", "");
        modelCard.progress().indeterminate();

        // A virtual thread, so a 574 MB transfer cannot block the event thread and the
        // window keeps repainting while it runs.
        Thread.ofVirtual().name("viskly-model-download").start(() ->
                models.download(update -> SwingUtilities.invokeLater(() -> onProgress(update))));
    }

    /**
     * Taking a model the user already has. The last time this file failed to download it
     * was fetched by hand into ~/Downloads, and there was no way to point the application
     * at it.
     */
    private void chooseFile() {
        // FileDialog, not JFileChooser. JFileChooser is drawn by Swing: on macOS it is a
        // panel that has looked like the 2009 version of the system for fifteen years, and
        // no amount of styling reaches it. FileDialog is AWT, and on macOS AWT hands the
        // job to NSOpenPanel — the real one, with the sidebar, search and iCloud.
        FileDialog chooser = new FileDialog(frame, "Choose a ggml model file", FileDialog.LOAD);
        // Where this file lands when someone fetches it by hand, which is the case this
        // button exists for.
        chooser.setDirectory(System.getProperty("user.home") + "/Downloads");
        chooser.setFilenameFilter((dir, name) -> name.endsWith(".bin"));
        chooser.setVisible(true);
        if (chooser.getFile() == null) {
            return;
        }
        // The filter is a hint the panel may ignore, so what actually decides is the
        // checksum in install() — a .bin that is not this model is reported, not loaded.
        Path source = Path.of(chooser.getDirectory(), chooser.getFile());
        downloadButton.setEnabled(false);
        chooseButton.setEnabled(false);
        modelCard.progress().indeterminate();
        Thread.ofVirtual().name("viskly-model-install").start(() ->
                models.install(source, update -> SwingUtilities.invokeLater(() -> onProgress(update))));
    }

    private void onProgress(ModelDownload update) {
        switch (update.state()) {
            case RUNNING -> {
                if (update.total() > 0) {
                    modelCard.progress().value(update.percent() / 100f);
                } else {
                    modelCard.progress().indeterminate();
                }
                modelCard.status(megabytes(update.received()) + " of "
                        + megabytes(Math.max(update.total(), 0)), "downloading");
            }
            case VERIFYING -> {
                modelCard.progress().indeterminate();
                modelCard.status("Checking the file", "574 MB to hash, this takes a moment");
            }
            case DONE -> {
                modelCard.progress().indeterminate();
                modelCard.status("Loading the model", "");
                // Loading is what turns dictation on without a restart. From a cold disk
                // it takes around five seconds, which on this thread would freeze the
                // window on a full progress bar.
                Thread.ofVirtual().name("viskly-model-load").start(() -> {
                    engine.load();
                    SwingUtilities.invokeLater(this::onModelLoaded);
                });
            }
            case FAILED -> {
                modelCard.progress().idle();
                modelCard.status("Did not finish", "");
                downloadButton.setEnabled(true);
                downloadButton.setText("Try again");
                chooseButton.setEnabled(true);
                Ask.tell(frame, "The model did not arrive", update.message());
            }
            case IDLE -> {
                modelCard.progress().idle();
                modelCard.status("", "");
                downloadButton.setEnabled(true);
                chooseButton.setEnabled(true);
            }
        }
    }

    private void onModelLoaded() {
        modelCard.progress().value(1f);
        modelCard.status(engine.isReady() ? "Ready" : "Downloaded, but it would not load", "");
        refresh();
    }

    private static String megabytes(long bytes) {
        return bytes / 1024 / 1024 + " MB";
    }

    // ---------- General ----------

    private JComponent generalPane() {
        Pane pane = new Pane("Preferences", "General", Ink.AMBER);

        keySelect = new Select<>(ModifierKey.values(),
                ModifierKey.valueOf(settings.hotkey()), ModifierKey::label,
                key -> Icons.keyCap(key.symbol()));
        pane.row(new Row("Shortcut", "Hold it and speak. Modifiers only, so it types nothing.",
                keySelect), 0);

        languageField = new Field(settings.language(), 200);
        pane.row(new Row("Language", "ISO-639-1 code, for example pl or en.",
                languageField), 4);

        pasteToggle = new Toggle(settings.injectEnabled());
        pane.row(new Row("Paste into the frontmost app",
                "Off: the text only reaches the clipboard.", pasteToggle), 4);

        historyToggle = new Toggle(settings.historyEnabled());
        pane.row(new Row("Keep a history of dictations",
                "Stored on this machine only, in ~/.viskly/history.db.", historyToggle), 4);

        restoreField = new Field(String.valueOf(settings.restoreDelayMillis()), 120);
        pane.row(new Row("Clipboard restore",
                "Milliseconds before your own clipboard comes back.", restoreField), 4);

        marginField = new Field(String.valueOf(settings.indicatorBottomMargin()), 120);
        pane.row(new Row("Indicator height",
                "Pixels between the pill and the top edge of the Dock.", marginField), 4);

        FlatButton check = new FlatButton("Check now", FlatButton.Kind.GHOST, null);
        check.addActionListener(e -> checkForUpdates(check));
        pane.row(new Row("Updates",
                "Asks GitHub for the latest release. Only when you press this.", check), 4);

        FlatButton save = new FlatButton("Save", FlatButton.Kind.PRIMARY, e -> save());
        JPanel footer = buttons(save);
        footer.add(Box.createHorizontalStrut(16));
        footer.add(note("The shortcut applies immediately."));
        pane.row(footer, 28);
        pane.row(Box.createVerticalGlue(), 0);
        return pane;
    }

    private void checkForUpdates(FlatButton button) {
        button.setEnabled(false);
        button.setText("Checking");
        Thread.ofVirtual().name("viskly-update-check").start(() -> {
            UpdateCheck.Result result = updates.check();
            SwingUtilities.invokeLater(() -> {
                button.setText("Check now");
                button.setEnabled(true);
                showUpdate(result);
            });
        });
    }

    private void showUpdate(UpdateCheck.Result result) {
        switch (result) {
            case UpdateCheck.Result.UpToDate up -> Ask.tell(frame, "Up to date",
                    "Version " + up.installed() + " is the latest one.");
            case UpdateCheck.Result.Failed failed -> Ask.tell(frame, "Could not check",
                    failed.reason());
            case UpdateCheck.Result.Available available -> {
                // Said before the download, not after: an ad-hoc signed build loses its
                // consents when replaced, and the shortcut silently doing nothing after an
                // update is exactly the surprise worth a sentence here.
                boolean open = Ask.confirm(frame, "Version " + available.latest() + " is out",
                        "You have " + available.installed() + ". The download page opens in "
                                + "your browser. After replacing the app, grant Input Monitoring "
                                + "and Accessibility to the new one again.",
                        "Open the download page", false);
                if (open) {
                    open(available.page());
                }
            }
        }
    }

    private void save() {
        settings.hotkey(((ModifierKey) keySelect.getSelectedItem()).name());
        settings.language(languageField.getText().strip());
        settings.injectEnabled(pasteToggle.isSelected());
        settings.historyEnabled(historyToggle.isSelected());
        settings.restoreDelayMillis(number(restoreField.getText(), settings.restoreDelayMillis()));
        settings.indicatorBottomMargin(
                number(marginField.getText(), settings.indicatorBottomMargin()));
        settings.save();
        refresh();
    }

    /** A typo in a numeric field keeps the old value instead of throwing away the save. */
    private int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------- History ----------

    private JComponent historyPane() {
        historyPane = new Pane("Kept on this machine", "History", Ink.OK);

        historyList = new HistoryList();
        historyList.onCopy(this::copy);
        JScrollPane scroller = Scroll.of(historyList);
        scroller.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        historyPane.row(scroller, 0);

        FlatButton reveal = new FlatButton("Show the file", FlatButton.Kind.GHOST,
                e -> open(history.file().getParent().toUri()));
        FlatButton clear = new FlatButton("Delete everything", FlatButton.Kind.DANGER,
                e -> clearHistory());

        JPanel footer = buttons(reveal);
        footer.add(Box.createHorizontalStrut(16));
        footer.add(note("Click a dictation to copy it."));
        footer.add(Box.createHorizontalGlue());
        footer.add(clear);
        historyPane.row(footer, 20);
        return historyPane;
    }

    private void copy(Dictation dictation) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(dictation.text()), null);
    }

    private void clearHistory() {
        boolean confirmed = Ask.confirm(frame, "Delete everything?",
                "Every dictation kept on this machine goes, and there is no undo. "
                        + "The setting stays on, so new ones will still be recorded.",
                "Delete everything", true);
        if (confirmed) {
            history.clear();
            historyList.reload(history.recent(200));
        }
    }

    // ---------- Permissions ----------

    private JComponent permissionsPane() {
        permissionsPane = new Pane("macOS consents", "Permissions", Ink.AMBER);

        microphoneCard = new PermissionCard("Microphone",
                "Recording. The system asks for this one by itself.",
                () -> openPane("Privacy_Microphone"));
        inputMonitoringCard = new PermissionCard("Input Monitoring",
                "Receiving the shortcut. Without it the key does nothing, silently.",
                () -> openPane("Privacy_ListenEvent"));
        accessibilityCard = new PermissionCard("Accessibility",
                "Pasting the text. Without it the transcript stays in the clipboard.",
                () -> openPane("Privacy_Accessibility"));

        permissionsPane.row(microphoneCard, 0);
        permissionsPane.row(inputMonitoringCard, 10);
        permissionsPane.row(accessibilityCard, 10);
        permissionsPane.row(new Hint("After granting a consent, quit Viskly and start it "
                + "again. macOS hands permissions to a process when it launches, never to "
                + "one already running."), 24);
        permissionsPane.row(Box.createVerticalGlue(), 0);
        return permissionsPane;
    }

    /**
     * Read on every open rather than cached. The answer changes outside this application,
     * in System Settings, and nothing tells us when it does.
     */
    private void refreshPermissions() {
        // The microphone is the one consent macOS prompts for, and it refuses the audio
        // line rather than reporting a denial, so the capture opening at startup is the
        // only evidence available here.
        microphoneCard.granted(true);
        inputMonitoringCard.granted(hotkey.isActive());
        accessibilityCard.granted(injector.canPaste());

        boolean all = hotkey.isActive() && injector.canPaste();
        permissionsPane.heading(all ? "All three granted" : "Something is missing",
                all ? Ink.OK : Ink.ACCENT);
    }

    // ---------- shared ----------

    private void refresh() {
        boolean present = models.isPresent();
        boolean ready = engine.isReady();

        // Whether the file is on disk and whether dictation works are separate questions.
        // A model that failed to load would otherwise be reported here as working, and the
        // user would go looking for the fault in the shortcut.
        modelPane.heading(
                ready ? "Ready" : present ? "On disk, but it did not load" : "Not installed yet",
                ready ? Ink.OK : Ink.ACCENT);
        modelCard.installed(present);
        downloadButton.setVisible(!present);
        chooseButton.setVisible(!present);

        if (historyList != null) {
            historyList.reload(history.recent(200));
        }
        refreshPermissions();

        // The reason, not just "off": the two causes are fixed in different places.
        ModifierKey key = ModifierKey.valueOf(settings.hotkey());
        if (!ready) {
            sidebar.status("Off until the model loads", Ink.ACCENT);
        } else if (!hotkey.isActive()) {
            sidebar.status("Off: no Input Monitoring", Ink.ACCENT);
        } else {
            sidebar.status("Listening on " + key.shortLabel(), Ink.OK);
        }
    }

    private JPanel buttons(JComponent... children) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        for (int i = 0; i < children.length; i++) {
            if (i > 0) {
                panel.add(Box.createHorizontalStrut(12));
            }
            panel.add(children[i]);
        }
        Pane.cap(panel, 40);
        return panel;
    }

    /** A single line beside a button. Anything longer belongs in a {@link Hint}. */
    private JLabel note(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Ink.body(12));
        label.setForeground(Ink.FAINT);
        label.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return label;
    }

    private void openPane(String pane) {
        open(URI.create("x-apple.systempreferences:com.apple.preference.security?" + pane));
    }

    private void open(URI uri) {
        try {
            Desktop.getDesktop().browse(uri);
        } catch (IOException | UnsupportedOperationException e) {
            try {
                new ProcessBuilder("open", uri.toString()).start();
            } catch (IOException fallback) {
                log.warn("Could not open {}", uri, fallback);
            }
        }
    }

    /**
     * The jar manifest carries the Maven version, "0.1.0-SNAPSHOT" until a release sets it.
     * The qualifier is dropped so the window says what Finder says: build-app.sh strips it
     * the same way for CFBundleShortVersionString. Absent when run from Maven.
     */
    private String version() {
        String implementation = getClass().getPackage().getImplementationVersion();
        return implementation != null
                ? "version " + implementation.replaceFirst("-SNAPSHOT$", "")
                : "dev";
    }

    /**
     * The model's own card: what it is, where it lives, and how far along it is.
     *
     * <p>The path is shown because the first question when dictation does not work is
     * "where does it expect the file", and the answer used to be findable only in the log.
     */
    private static final class ModelCard extends Card {

        private static final long serialVersionUID = 1L;

        static final int H = 170;

        private final transient Path path;
        private final transient ThinProgress progress = new ThinProgress();

        private String left = "";
        private String right = "";
        private boolean installed;

        ModelCard(Path path) {
            super(null);
            this.path = path;
            add(progress);
        }

        ThinProgress progress() {
            return progress;
        }

        void status(String left, String right) {
            this.left = left;
            this.right = right;
            repaint();
        }

        void installed(boolean value) {
            this.installed = value;
            progress.setVisible(!value);
            if (value) {
                progress.idle();
            }
            repaint();
        }

        @Override
        public void doLayout() {
            progress.setBounds(24, 100, Math.max(40, getWidth() - 48), 6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = Draw.smooth((Graphics2D) g.create());
            int w = getWidth();

            Draw.text(g2, "large-v3-turbo, quantised. Runs entirely on this machine.",
                    Ink.body(13), Ink.MUTED, 24, 40);
            Draw.text(g2, Draw.fit(g2, home(path), Ink.mono(11), w - 48),
                    Ink.mono(11), Ink.FAINT, 24, 64);

            if (installed) {
                Draw.text(g2, "On disk. Nothing is sent anywhere when you dictate.",
                        Ink.body(12), Ink.FAINT, 24, 112);
            } else {
                Draw.text(g2, left, Ink.body(12), Ink.DIM, 24, 128);
                if (!right.isEmpty()) {
                    int width = g2.getFontMetrics(Ink.mono(11)).stringWidth(right);
                    Draw.text(g2, right, Ink.mono(11), Ink.FAINT, w - 24 - width, 128);
                }
            }
            g2.dispose();
        }

        /** An absolute path to a home directory is noise; the tilde is what people read. */
        private static String home(Path path) {
            String home = System.getProperty("user.home");
            String text = path.toString();
            return text.startsWith(home) ? "~" + text.substring(home.length()) : text;
        }
    }
}
