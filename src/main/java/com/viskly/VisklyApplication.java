// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Entry point. The application type is set to "none" in application.yml — Spring is
 * a dependency and lifecycle container here, not a server.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VisklyApplication {

    public static void main(String[] args) throws InterruptedException {
        // This has to happen here, not in application.yml. SpringApplication sets
        // java.awt.headless in configureHeadlessProperty() before it reads any
        // configuration at all — so spring.main.headless from the file arrives too
        // late and the clipboard throws HeadlessException despite a correct entry.
        System.setProperty("java.awt.headless", "false");
        // macOS: without this, initialising AWT puts the app in the Dock and takes over
        // the menu bar. Set in code so it also holds for java -jar, not just the Maven plugin.
        System.setProperty("apple.awt.UIElement", "true");
        privateDataDirectory();

        ConfigurableApplicationContext context = SpringApplication.run(VisklyApplication.class, args);

        // An application with no web layer has nothing holding the process open: main
        // would return, and the event-loop thread is a daemon, so the JVM would exit a
        // fraction of a second after startup. A web server would keep the process alive
        // by itself — here we have to do it by hand.
        //
        // Block the main thread (non-daemon) until the context closes. Ctrl+C runs Spring
        // Boot's shutdown hook, that closes the context, we release the latch and the
        // process ends cleanly.
        CountDownLatch running = new CountDownLatch(1);
        context.addApplicationListener(
                (ApplicationListener<ContextClosedEvent>) event -> running.countDown());
        running.await();
    }

    /**
     * ~/.viskly holds every dictation (history.db) and the log. Home directories on macOS
     * are world-readable (755), and so was this one, so any other account on the Mac could
     * read what the user had said. Owner-only on the directory covers every file in it,
     * whatever mode SQLite or logback give the files themselves.
     *
     * <p>It has to run before {@code SpringApplication.run}: logback creates the directory
     * for its file the moment logging starts, and with the default mode.
     */
    private static void privateDataDirectory() {
        Path dir = Path.of(System.getProperty("user.home"), ".viskly");
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rwx------");
        try {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, ownerOnly);
        } catch (IOException | UnsupportedOperationException e) {
            // Logging is not up yet; this is the only channel there is.
            System.err.println("Could not make " + dir + " private: " + e);
        }
    }
}
