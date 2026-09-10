# Viskly

Local dictation on macOS. Hold a key, talk, let go: the text appears where your cursor
was. No byte of audio leaves the machine.

```
hotkey (CGEventTap) → microphone (16 kHz mono) → whisper.cpp → clipboard + ⌘V
```

On Apple Silicon a typical utterance takes about 500–700 ms from key release to text.

## Quick start

```bash
./scripts/get-model.sh          # 574 MB into ~/.viskly/models, once
./scripts/build-app.sh --install
```

The app lands in `/Applications/Viskly.app`. Add `--login` to have it start with the
system.

For working on the code, `mvn spring-boot:run` is enough. It needs JDK 25
(`brew install openjdk@25`) and Maven; [DEVELOPMENT.md](DEVELOPMENT.md) has the rest.

## Permissions

macOS requires **three separate consents**, and this is the biggest friction on first
launch:

| Consent | What for | When |
|---|---|---|
| Microphone | recording | the system asks by itself |
| Input Monitoring | listening for the shortcut | you tick it by hand |
| Accessibility | pasting text | you tick it by hand |

The last two live in Settings → Privacy & Security. The app asks for both at startup, so
it appears on both lists on its own. You only have to flip the switch.

**After granting a consent, quit the app and start it again.** macOS reads permissions
when the process starts; it will not hand them to a running one. When working from a
terminal this applies to the terminal, not to Java: quit it with Cmd+Q, because a new tab
is not enough.

Released builds are signed with a Developer ID, and macOS keeps the consents across
updates. A build you make yourself is ad-hoc signed. macOS then identifies it by its
signature hash, so **every rebuild wipes both manual consents**.

## Configuration

`src/main/resources/application.yml`:

| Key | Default | Meaning |
|---|---|---|
| `viskly.hotkey.key` | `RIGHT_COMMAND` | also `LEFT_COMMAND`, `RIGHT_OPTION`, `LEFT_OPTION`, `RIGHT_CONTROL`, `FN` |
| `viskly.model-path` | `~/.viskly/models/ggml-large-v3-turbo-q5_0.bin` | path to the model |
| `viskly.language` | `pl` | ISO-639-1 code |
| `viskly.threads` | `0` | `0` = half the cores |
| `viskly.audio.max-seconds` | `60` | hard limit on one dictation |
| `viskly.audio.min-speech-millis` | `300` | below this we do not call the model |
| `viskly.inject.enabled` | `true` | `false` = text only goes to the log |
| `viskly.inject.restore-delay-millis` | `200` | when the previous clipboard content comes back |
| `viskly.ui.indicator-bottom-margin` | `40` | indicator distance from the top edge of the Dock |
| `viskly.asr.trim-audio-context` | `true` | trims the encoder input to the real recording length |
| `viskly.asr.audio-context-padding` | `96` | safety margin in frames for the above |

Pick a modifier for the shortcut: held on its own it types no character and fires no
system shortcut.

These are the defaults compiled into the application. What a user changes in the settings
window goes to `~/.viskly/config.properties` and wins over them.

## Settings

The menu bar item opens one window, and it exists for four things an icon cannot do.

**Model.** The 574 MB model is not part of the download. A fresh install opens this
section by itself and offers to fetch it, with a progress bar and a checksum. An
interrupted download resumes. When it finishes, dictation switches on without a restart.

**General.** Shortcut, language, whether to paste, how long to wait before restoring the
clipboard, where the indicator sits, whether to keep history. Saved to
`~/.viskly/config.properties`, which is also safe to edit by hand. A changed shortcut
applies immediately. Under Updates, Check now asks GitHub for the latest release; nothing
is asked until you press it.

**History.** Every dictation, kept in `~/.viskly/history.db` and never anywhere else.
Click one to copy it, reveal the file, or delete the lot.

**Permissions.** Which of the three macOS consents is actually in force, with a button
that opens the right pane. macOS never reports a missing consent to the application; the
only symptom is that the shortcut quietly does nothing. This tab is where that becomes
visible.

## Using it

Hold the shortcut and talk. A pill appears near the bottom of the screen with bars showing
the **actual signal level**: if they do not move, the microphone cannot hear you, and you
learn that during the sentence rather than after an empty transcript. Release the key, the
bars turn into an amber wave while the model runs, then the text goes into the frontmost
window.

**Escape while talking** throws the recording away without calling the model.

The menu bar icon turns red while recording and carries a "Quit Viskly" item.

Logs: `~/.viskly/viskly.log`. The packaged app has no console, so that is the only place
you will see an error.

## Structure

Three ports, each with adapters. The rest of the code is portable and testable without an
operating system.

```
com.viskly
├─ session/DictationSession    state machine IDLE → RECORDING → TRANSCRIBING
├─ session/SessionEvents       Spring events instead of direct calls
├─ hotkey/HotkeyListener       port → MacHotkeyListener (FFM) | ConsoleHotkeyListener
├─ audio/MicrophoneCapture     TargetDataLine, opened once at startup
├─ audio/AudioLevel            signal level port for the indicator
├─ asr/TranscriptionEngine     port → WhisperCppEngine (whisper-jni)
├─ inject/TextInjector         port → MacTextInjector (FFM) | ClipboardOnlyInjector
└─ ui/                         recording indicator, menu bar icon
```

`DictationSession` calls nothing directly: it publishes events. Pasting, the indicator,
the icon and the log are four independent components listening to the same events. Adding
each of them took not one line of change in the session. The full map, with the settings
window, the model download and the history, is in [DEVELOPMENT.md](DEVELOPMENT.md).

## Decisions worth remembering

**The shortcut goes through Panama FFM, not JNI.** `CGEventTapCreate` is called straight
from Java, with no C. The alternative, JNativeHook, sits on a release from March 2022, a
poor foundation for an application meant to survive future macOS versions. The tap runs
in *listen only* mode, so it cannot lock up the keyboard.

**The model and the microphone start with the application.** Loading large-v3-turbo takes
seconds, opening a `TargetDataLine` tens of milliseconds. Neither can happen after the key
goes down.

**The audio buffer is pre-allocated.** A GC pause in the middle of a sentence is audible
in the transcript. 60 s at 16 kHz is about 3.8 MB, small enough to keep in memory.

**Three guards against hallucination.** Whisper invents lines of film subtitles on silence.
Active: a minimum-length gate, `suppressBlank`, `suppressNonSpeechTokens`. A fourth, VAD,
is still to come.

**The encoder input is trimmed to the recording.** Whisper processes a full 30 seconds by
default whatever you recorded, and that fixed cost dominates short dictations. `audioCtx`
cuts it to `samples / 320` plus a safety margin.

**The indicator must not take focus.** If it did, the frontmost application would stop
being frontmost and ⌘V would paste in the wrong place.

**The indicator window has a shape, not a transparent background.** The alpha-channel
version flickered over any non-empty content: Swing cleared the area to transparency before
every frame and the macOS compositor caught that intermediate state.

**`java.awt.headless` is set in `main()`, not in the yml.** `SpringApplication` reads that
property before it reads any configuration, so an entry in the file arrives too late.

**A latch in `main()` keeps the process alive.** An application with no web layer has
nothing to stop the JVM exiting a second after startup.

## State

Done: recording, transcription, pasting, the indicator, the menu bar icon, cancelling with
Escape, packaging into a `.app` and a `.dmg`, the settings window, in-app model download,
history, a manual update check.

Next:

- **The first signed release.** Signing, notarisation and the release workflow are in
  place and have not yet run with a Developer ID.
- **Latency:** the remaining fixed cost per model call, now that the encoder input is
  trimmed.
- **Quality:** VAD, a dictionary of proper nouns, filler-word removal, voice commands.
- **Windows:** shortcut and paste adapters. Nothing else should need to change; if it
  does, the ports were drawn in the wrong place.

## Licence

GPL-3.0. The full text is in [LICENSE](LICENSE).

Copyright (C) 2026 Karol Krawczyk.

The choice is deliberate rather than a default. Viskly asks for three macOS consents
(microphone, Input Monitoring and Accessibility), which together is everything a keylogger
would need. The claim on the site is that no audio and no keystroke leaves the machine, and
in a closed binary that is a claim nobody can check. Readable source is what makes it
verifiable, and a copyleft licence is what keeps it readable in anything built on top of
this.

**The name is not part of the licence.** The GPL covers the code. *Viskly*, the wordmark
and the icon are not licensed for use in derived work, so a fork needs a name and an icon
of its own. Everything else the licence permits, it permits.

Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md). Issues and pull requests are
welcome; code is merged once its author has signed the [contributor licence
agreement](CLA.md), and that file explains why it exists.

### Dependencies

Nothing in the stack imposes terms of its own: the licence above is a choice, not an
obligation inherited from a library.

| | Licence |
|---|---|
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | MIT |
| [whisper-jni](https://github.com/GiviMAD/whisper-jni) | Apache-2.0 |
| Spring Boot | Apache-2.0 |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | Apache-2.0 |
| Whisper model weights (OpenAI) | MIT |

The model itself is not redistributed here. It is downloaded from Hugging Face on first
run, and verified by checksum.
