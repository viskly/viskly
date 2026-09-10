# Developing Viskly

Java 25, Spring Boot, no web layer. A background agent with a menu bar icon, an
always-on-top indicator and one settings window. This file is for whoever changes the
code: how to build it, what already went wrong, and why things are the way they are.

## Commands

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # required, see below
mvn test                                            # unit tests
mvn spring-boot:run                                 # development
./scripts/build-app.sh --install                    # build Viskly.app into /Applications
./scripts/build-app.sh --dmg                        # also pack target/dist/Viskly-<version>-<arch>.dmg
./scripts/get-model.sh                              # fetch the model outside the app
```

Logs: `~/.viskly/viskly.log`. Settings: `~/.viskly/config.properties`. History:
`~/.viskly/history.db`. Model: `~/.viskly/models/`.

## Conventions

- **Everything human-readable is in English**: code, comments, Javadoc, log messages, UI
  strings, documentation, commit messages.
- **No em dashes in prose that ships** (UI strings, README, these documents). Use a colon,
  a full stop, or restructure. En dashes in numeric ranges (`500–700 ms`) are correct.
- **Comments explain why, not what.** The code is full of decisions that look arbitrary
  until you know what went wrong without them. Keep that reasoning when you touch the code
  around it.
- **New behaviour is a new listener**, not a new call inside `DictationSession`. See
  Architecture.

## Releasing

Set the version in `pom.xml` (no `-SNAPSHOT`), commit, then `git tag v<version>` and push
the tag. `.github/workflows/release.yml` builds on an Apple Silicon and an Intel runner,
signs with the Developer ID, notarises, and publishes `Viskly-Apple-Silicon.dmg`,
`Viskly-Intel.dmg` and `latest.properties` as a GitHub release. The secrets it needs are
listed at the top of the workflow. The workflow refuses a tag that does not match the pom.

Locally the same script signs for real when `VISKLY_SIGN_IDENTITY` and
`VISKLY_NOTARY_PROFILE` are set. Without them it signs ad-hoc, still under the hardened
runtime, so a missing entitlement shows up on your machine and not in a release.

## Traps that have already cost time

Every one of these was hit for real. They fail silently, which is why they are listed.

- **JDK 25 must be pinned explicitly.** `jpackage` bundles the runtime of whichever JDK
  runs it, and the `jpackage` on `PATH` is not necessarily the one `JAVA_HOME` points at.
  A mismatch builds fine and then dies on launch with `UnsupportedClassVersionError`.
  `build-app.sh` resolves the JDK itself and verifies the bundled runtime afterwards.
- **`java.awt.headless` must be set in `main()`, before `SpringApplication.run`.**
  `spring.main.headless` in `application.yml` is read after the logging and AWT decision
  has already been made, so it silently does nothing and the clipboard throws
  `HeadlessException`.
- **Nothing keeps a non-web Spring Boot app alive.** `main()` blocks on a latch released
  by `ContextClosedEvent`. Remove it and the JVM exits a fraction of a second after
  startup.
- **macOS has two different consents**, and they are easy to confuse. Input Monitoring
  (IOHID) is what a listen-only `CGEventTap` needs. Accessibility
  (`AXIsProcessTrusted`) is what `CGEventPost` needs to paste. Neither is reported to the
  application when missing: the shortcut just does nothing.
- **A tap that exists is not a tap that works.** Without Input Monitoring macOS still
  creates the listen-only tap, then keeps disabling it and delivers nothing. Whether the
  shortcut works is decided by `IOHIDCheckAccess` at startup, not by the tap.
- **Consents are tied to the signature.** A local build is ad-hoc signed, so every rebuild
  looks like a new application to macOS and both manual consents are wiped. While
  iterating, use `mvn spring-boot:run` and grant the consents to the terminal instead.
  After a rebuild the old Viskly entry stays in the list, ticked, and matches nothing:
  ticking it again does not help, it has to be removed with "-" and added again.
- **Quitting must not run on the AppKit thread.** The JDK's default handler for the quit
  Apple event (Cmd+Q, logout, `osascript ... quit`) calls `System.exit` on that thread, and
  the shutdown hook then waits for AWT work that needs it. The process hangs for good.
  `TrayUi` installs its own quit handler and closes the context on a separate thread, then
  exits explicitly: a settings window opened once keeps AWT, and so the JVM, alive.
- **A CGEventTap upcall must not block.** It runs on the system event-loop thread;
  blocking it freezes the keyboard for the whole machine until the timeout.
- **The indicator window must never take focus.** If it does, the frontmost application
  stops being frontmost and the synthetic ⌘V pastes somewhere else.
- **`CFRunLoop` pins its thread.** It has to be a platform thread, never a virtual one.
- **The FFM arena is `ofAuto`, not `ofShared`.** The run loop sits inside a downcall for
  the life of the process, so a shared arena can never be closed
  (`Session is acquired by 1 clients`).
- **`java.awt.Component` clashes with Spring's `@Component`.** In Swing classes the AWT
  one stays fully qualified.
- **Native libraries hide inside jars inside the jar.** whisper-jni and sqlite-jdbc ship
  their macOS `.dylib` files unsigned or ad-hoc signed, and notarisation rejects any
  unsigned binary in an archive. `build-app.sh` signs them in place before `jpackage`; the
  nested jars have to go back in stored (`zip -0`), or Spring Boot refuses to read them.
- **The hardened runtime refuses the microphone silently** unless the app carries
  `com.apple.security.device.audio-input`. macOS does not even ask. The full set of
  exceptions the JVM needs is in `scripts/entitlements.plist`.

## Architecture

Three ports with adapters; everything else is portable and testable without an OS.

```
com.viskly
├─ session/DictationSession    state machine, publishes Spring events, calls nothing directly
├─ hotkey/HotkeyListener       port → MacHotkeyListener (FFM) | ConsoleHotkeyListener
├─ asr/TranscriptionEngine     port → WhisperCppEngine (whisper-jni)
├─ inject/TextInjector         port → MacTextInjector (FFM) | ClipboardOnlyInjector
├─ audio/                      capture, pre-allocated buffer, level for the indicator
├─ model/ModelStore            in-app download, resumable, SHA-256 verified
├─ history/HistoryStore        SQLite over plain JDBC, one table
├─ settings/Settings           writable config, read live rather than bound at startup
├─ update/UpdateCheck          on request only: compares with the latest GitHub release
└─ ui/                         indicator, menu bar icon, settings window
```

Pasting, the indicator, the icon, the log and the history are five independent listeners
on the same events. Adding any of them required no change to the session. Keep it that
way.

## Verification

macOS-only APIs (`jpackage`, `sips`, `codesign`, `CGEventTap`, `NSPasteboard`) cannot be
exercised anywhere but a Mac. `javac` on JDK 25 catches signature errors in FFM bindings
but proves nothing about runtime behaviour. In a pull request, say what you ran on which
macOS version and which Mac, and say plainly what you could not test.

## Open work

- Latency: the remaining fixed cost per model call, now that `audioCtx` trims the encoder.
- Quality: VAD, a dictionary of proper nouns, filler-word removal, voice commands.
- Windows: shortcut and paste adapters. Nothing else should need to change; if it does,
  the ports were drawn in the wrong place.
- Distribution: the first real release. Signing, notarisation and the workflow are in
  place but have never run with a Developer ID; the first notarisation is where a missed
  binary would show up. The update check only points at the download page: replacing the
  app in place is worth doing only once the signature is stable across releases.
