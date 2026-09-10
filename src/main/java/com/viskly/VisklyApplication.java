// Copyright (C) 2026 Karol Krawczyk
// SPDX-License-Identifier: GPL-3.0-only

package com.viskly;

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
}
