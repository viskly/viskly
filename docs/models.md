# Models

What Viskly can run instead of `large-v3-turbo-q5_0`, and what it cannot.

## The constraint that decides everything

Speech recognition goes through **whisper-jni 1.7.1**, which is a JNI wrapper around a
pinned copy of whisper.cpp (submodule at `ebca09a3`). Two consequences:

**Only ggml `.bin` files work.** Not safetensors, not ONNX, not Core ML packages, not
GGUF from another project. A model has to have been converted to ggml for whisper.cpp, and
swapping one is then a matter of pointing Viskly at a different file. No code at all.

**Metal is on. The Neural Engine is not.** This distinction is the important one, and it
is easy to get wrong, because whisper-jni's `CMakeLists.txt` forwards no GPU flag at all:
only `GGML_AVX`, `GGML_AVX2`, `GGML_FMA`, `GGML_F16C`, `GGML_STATIC` and `GGML_NATIVE`. An
absent flag does not mean a disabled backend here: whisper.cpp turns it on itself. The
chain:

1. The pinned submodule `ebca09a3` is whisper.cpp **v1.7.1, October 2024**.
2. That release's `ggml/CMakeLists.txt` has `if (APPLE) set(GGML_METAL_DEFAULT ON)`, and
   `option(GGML_METAL ... ${GGML_METAL_DEFAULT})`. Metal is on by default.
3. ggml is not yet split into per-backend libraries at this version. `ggml-metal.m` goes
   into `GGML_SOURCES_METAL`, which goes straight into `add_library(ggml ...)`, so Metal
   lives *inside* `libggml.dylib`, the file `build-natives.sh` puts into the bundle.
4. `GGML_METAL_EMBED_LIBRARY` defaults to `${GGML_METAL}`, so the shaders are embedded in
   the binary and no `.metal` file has to ship beside it.
5. `whisper_context_default_params()` sets `use_gpu = true`, whisper-jni's
   `WhisperContextParams` has `useGPU = true`, and `WhisperCppEngine` calls
   `whisper.init(path)`, which takes those defaults.

What is absent is `WHISPER_COREML`. It appears nowhere in this build, so the encoder does
**not** use the Apple Neural Engine. That is where the headroom is, not in Metal.

Confirming it takes a minute in a development run (`mvn spring-boot:run`), where
`application.yml` can be edited:

```yaml
viskly:
  asr:
    verbose-model-log: true
```

whisper.cpp then prints its system line to `~/.viskly/viskly.log`. It should read
`METAL = 1`. If it somehow reads `0`, everything below becomes secondary: CPU against GPU
dwarfs any choice of model.

## Drop-in replacements

These need nothing but a different file, chosen in the Model tab or named in `model.path`
(see the last section). All of them speak Polish, because they are the multilingual builds;
the `.en` variants are English-only and not listed here.

| Model | Size | When it makes sense |
|---|---|---|
| `large-v3-turbo-q5_0` | 574 MB | **Current.** The usual best trade-off for dictation. |
| `large-v3-turbo-q8_0` | 874 MB | Same model, gentler quantisation. The accuracy q5_0 gives up comes back for 300 MB and a little speed. The first thing to try if a word keeps coming out wrong. |
| `large-v3-turbo` | 1.62 GB | Unquantised. Rarely worth triple the file over q8_0. |
| `large-v3-q5_0` | 1.08 GB | The full large model, not the turbo distillation. Better on hard audio: accents, noise, overlapping speech. Distinctly slower, since turbo has 4 decoder layers against 32. |
| `medium-q5_0` | 539 MB | Same size as the current model and worse at Polish. No reason to pick it. |
| `small-q5_1` | 190 MB | Four times faster, noticeably weaker on Polish inflection and proper nouns. A reasonable fallback on an Intel Mac. |
| `base-q5_1` | 59.7 MB | Usable for English commands. Polish output is not worth reading. |
| `tiny-q5_1` | 32.2 MB | Only for checking that the pipeline works at all. |

The pattern: below `small`, Polish falls apart much faster than English does, because the
training data is far thinner. This is why `large-v3-turbo` is the floor rather than the
ceiling for a Polish dictation tool.

## macOS-specific options

### Core ML encoder: the one worth the work

whisper.cpp can run the **encoder** on the Apple Neural Engine through Core ML, keeping the
decoder on CPU. Hugging Face publishes a matching `.mlmodelc` for every model, including
`ggml-large-v3-turbo-encoder.mlmodelc.zip`.

This lines up exactly with where the time goes. The encoder is the fixed cost per call (it
is the reason `audioCtx` trimming was added), and the ANE is the part of the chip designed
for it. It is also the only place left to win anything, now that Metal is accounted for.

The catch is that it cannot be switched on from Java. whisper-jni would have to be rebuilt
with `-DWHISPER_COREML=1`, the resulting dylibs packaged, and the `.mlmodelc` shipped
beside the `.bin`. `build-natives.sh` already builds whisper-jni from source, so the
rebuild itself is a flag away; the Core ML model beside the `.bin`, and keeping that
working across whisper.cpp versions, is the real day of work.

### Apple SpeechAnalyzer: ruled out by language

macOS 26 introduced `SpeechAnalyzer` / `SpeechTranscriber`: on-device, free, no model to
download, reportedly around twice as fast as Whisper large-v3.

**It does not support Polish.** The list is Cantonese, Chinese, English, French, German,
Italian, Japanese, Korean, Portuguese and Spanish. For an application whose default
language is `pl`, it cannot be the engine. It could be an optional fast path for people
dictating in English, at the cost of a second engine to maintain and an FFM binding to the
Speech framework.

### Parakeet TDT 0.6B v3: plausible, but a different engine

NVIDIA's model, 600M parameters, CC-BY-4.0, and it does cover Polish among 25 European
languages. It scores well on the open ASR leaderboard and is much smaller than what it
competes with.

It is not a Whisper model, so whisper.cpp will not load it. Using it would mean a separate
inference path: a C++ runtime through FFM, or a sidecar process. Worth watching rather than
adopting. If a Java-friendly binding appears, a 600 MB model with this accuracy would be a
serious alternative.

### Polish fine-tunes

Community fine-tunes of Whisper on Polish exist, such as `bardsai/whisper-large-v2-pl-v2`.
They are built on **large-v2**, which predates v3 and the turbo distillation, and they ship
in the Transformers format, so converting to ggml is a step you would own. The gain over
`large-v3-turbo` is not established; the older base is a real cost. Not recommended without
measuring on your own audio first.

## What to try, in order

1. **Try `large-v3-turbo-q8_0`.** 300 MB more, one file, and it is the only free accuracy
   on the list.
2. **Glance at the log once** with `verbose-model-log` to confirm `METAL = 1`. A formality,
   but a cheap one.
3. **Keep `large-v3-q5_0` for comparison** if dictation happens somewhere noisy.
4. **Treat Core ML as a project**, not a setting. It is the only real headroom left: Metal
   is already working, and the encoder still runs off the Neural Engine.

## Where the model is chosen

`ModelStore` has one URL and one SHA-256 compiled in, so the download button fetches
`large-v3-turbo-q5_0` and nothing else. Every other model goes in through the **Choose a
file** button in the Model tab, which takes any `.bin` from disk.

The two go through different checks. The download has to match its known hash exactly. A
file chosen from disk is checked for being a whisper.cpp model at all: at least 10 MB, and
opening with the ggml magic number, which on disk reads as `lmgg`. Until the two were split,
the button compared every file against the checksum of `large-v3-turbo-q5_0`, so picking
`large-v3-turbo-q8_0` was rejected as "not the model Viskly expects".

A chosen file is copied into `~/.viskly/models` under its own name, and its path is written
to `model.path` in `~/.viskly/config.properties`, which is what the engine loads at every
start. The copy used to take the name of the downloaded model, so any other model sat on
disk and in the log as `large-v3-turbo-q5_0`.

The button shows only while no model is installed. Switching when one already works means
editing `model.path` (a leading `~/` is fine) and restarting Viskly; the engine loads one
model per process.

Offering a list of models in the settings window, rather than a file picker, means turning
the URL and checksum constants into a table.
