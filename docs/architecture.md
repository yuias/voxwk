# voxwk: architecture

This describes the phone app as implemented. Build and signing steps are in [README.md](../README.md).

---

## 1. Overview

voxwk translates speech between Japanese and eight other languages. By default all inference runs on the device. The
inference core is the Rust crate `translatecore`, called from the Jetpack Compose UI through UniFFI and JNA. Online
translation is optional: it hands a whole turn to a cloud speech-to-speech model.

```
Kotlin / Compose (android/app)   UI, capture, OS TTS, playback routing, model downloads, settings
        |  UniFFI + JNA
        v
Rust core (crates/translatecore)  VAD and ASR orchestration, translation through llama.cpp
        |  C API / static linking
        v
Native libs (jniLibs)             sherpa-onnx + onnxruntime, libtranslatecore.so (llama.cpp linked statically)
```

Audio I/O (`AudioRecord`, `TextToSpeech`, `AudioTrack`) stays on the Kotlin side. The Rust side only converts and runs
inference, which keeps it testable on the host.

### Repository layout

| Path | Contents |
| --- | --- |
| `Cargo.toml`, `Cargo.lock` | Rust workspace (one member, `crates/translatecore`) |
| `.cargo/config.toml` | Sets `CMAKE_GENERATOR=Ninja` for the llama.cpp build |
| `crates/translatecore/` | The Rust core: `lib.rs` (exported API), `mt.rs`, `asr.rs`, `vad.rs` |
| `android/` | Gradle root project; `app/` is the app |
| `android/app/src/main/java/uniffi/translatecore/translatecore.kt` | Generated UniFFI bindings, committed |
| `tools/gen-bindings.sh` | Regenerates the bindings from the host build of the core |
| `tools/mt-eval/` | Desktop scoring of the translation models; not part of the app |
| `poc/` | Throwaway hardware experiments; not part of the app build |

The app code is under `android/app/src/main/java/app/rly3h/voxwk/`:

| Package | Role |
| --- | --- |
| `lang/` | `Language`, the pair rules and the recognizer choice (section 4) |
| `data/` | Settings (DataStore), the key store, the session store, the model catalogue and downloads |
| `translate/` | The engines and the callback seam between an engine and the screen (section 2) |
| `audio/` | `AudioInput`, output routing, TTS and PCM playback (section 6) |
| `ui/` | Session, history and settings screens, each with a ViewModel |
| `theme/` | The DayNight theme |

---

## 2. Engine seam

`translate/SpeechTranslator.kt` is the one interface between an engine and the screen.

- `SpeechTranslator`: `start(scope, callbacks)` does not block, `stop()` is idempotent. Conversation engines also
  implement `ConversationTranslator.setSpeaking`.
- `TranslatorCallbacks`: `onTurnStart`, `onTurnUpdate`, `onTurnComplete`, `onPartial`, `onStatus`, `onSpeak`,
  `onPersist` and `onFinished`. Each maps onto one piece of screen state, so an engine never touches Compose.
- `EngineStatus` is typed, not text: `Listening`, `Connecting`, `NoEarphones`, `NoTtsVoice(language)`,
  `Failed(stage, detail)` and `Directional(speaker, inner)`. The screen turns it into a string resource. A native
  error message (`detail`) is shown verbatim inside a resource template.
- `LiveTurn` carries the transcript, `src` (null means Auto in Listen), `tgt`, the raw ASR tag, the translation, the
  speaker (Conversation only) and the start time.
- Engines take an `AppSettings` snapshot at session start. Mute and speech rate act mid-session, so engines read them
  through `autoSpeak` and `speechRate` lambdas backed by the live settings flow.

`ui/session/NewSessionViewModel.kt` owns a running session. The engine runs on `viewModelScope`, so opening the drawer,
rotating or visiting Settings does not stop it. `RecordingService` is a foreground service of type `microphone` that
only keeps the process alive while recording; it holds no session state.

### Engines

| Engine | Used for | Pipeline |
| --- | --- | --- |
| `OfflineTranslator` | Listen and (with hooks) Conversation | capture, then Nemotron streaming recognition or Silero VAD + SenseVoice, then llama.cpp translation, then OS TTS |
| `OfflineConversationTranslator` | Offline Conversation | `OfflineTranslator` driven by the Speaking control; owns the router and `TtsPlayback` |
| `OpenAiTranslator`, `GeminiTranslator` | Online Listen | WebSocket speech-to-speech translation (section 5) |
| `OnlineConversationTranslator` | Online Conversation | one `OpenAiTranslator` or `GeminiTranslator` per direction, sharing one capture; plays through `PcmPlayback` |

`RealtimeSupport.kt` holds what both online engines share: `RealtimeTurnAssembler` (joins source and translation
deltas into one turn) and `RealtimeAudioSink` (plays 24 kHz PCM16 for Listen).

### Audio input

`audio/AudioInput.kt` is the input seam: engines never open `AudioRecord` themselves. An input receives a
`CaptureFormat` (sample rate, chunk length, purpose, optional preferred device) and blocks, emitting PCM16 little-endian
mono chunks until `running` turns false.

| Path | Rate | Chunk | Capture source |
| --- | --- | --- | --- |
| Offline | 16 kHz | 100 ms | `VOICE_RECOGNITION` in Listen, `VOICE_COMMUNICATION` in Conversation |
| Online | 24 kHz (OpenAI), 16 kHz (Gemini) | 40 ms | `VOICE_COMMUNICATION` (hardware echo cancellation) |

`PhoneAudioInput` is the only input today. A device that cannot open a 24 kHz `AudioRecord` captures 16 kHz and
upsamples it linearly. Each saved session records its input kind (`PHONE` or `GLASSES`); only `PHONE` is produced.

---

## 3. Offline path

```
capture (16 kHz mono PCM16, 100 ms chunks)
  -> recognizer   Nemotron 3.5 streaming  |  Silero VAD v6 -> SenseVoice int8
  -> translation  PLaMo 3 Translate (llama.cpp, GGUF Q4_0)
  -> speech       OS TextToSpeech
  -> storage      filesDir/sessions/<id>.json
```

### Recognizers

`lang/Language.kt` chooses the recognizer with `asrEngineFor(speaker, jaRecognizer)`. SenseVoice is used only when the
speaker's language is known to be Japanese and Settings has "Japanese recognition" set to SenseVoice. Everything else,
including Auto, uses Nemotron.

- **Nemotron 3.5 streaming (`asr_stream`)** is the default. It is multilingual and shows a live partial transcript. It
  reports no language tag. Kotlin always passes `"auto"` as the stream language: one stream may carry two speakers, and
  a fixed language would misrecognize the other one. Turns end on sherpa's endpoint rules
  (`EndpointParams`: rule1 2.4 s, rule2 1.2 s, rule3 20 s by default), where rule2 (silence after speech) is the main
  end-of-turn setting.
- **SenseVoice (`asr`) with Silero VAD (`vad`)** is more accurate for Japanese. The VAD cuts a segment per utterance
  and SenseVoice transcribes it and returns a language tag. `VadParams` defaults: threshold 0.5, `minSilenceMs` 500,
  `minSpeechMs` 250, `maxSpeechMs` 15000.
- Nemotron 3.5 recognizes ja, en, fr, de, it, es, ru and Mandarin (used for `zh-hans`). It has no Traditional-script
  recognition, so `zh-hant` has no offline recognizer: a session that must recognize `zh-hant` runs online
  (section 4). The Nemotron transcript is shown as recognized; there is no script conversion.

When the two directions of a Conversation need different recognizers (Japanese on SenseVoice, the partner on
Nemotron), `DirectionalAsrDispatcher` feeds one capture to both. Each chunk goes to the path of its speaker's engine.
Both paths hold state, so when the speaker changes the path that served the previous speaker is closed first (the VAD
is flushed, or the stream's finished text is emitted and the stream reset) and its output is tagged with that
speaker. Recognition and translation then run on one worker so turns commit in spoken order.

### Translation

`crates/translatecore/src/mt.rs` runs a PLaMo 3 Translate GGUF with llama.cpp on the CPU. Settings selects one model:
0.8B (default, small and fast) or 2B (more accurate, larger and slower). Q4_0 is used because llama.cpp's Arm CPU
backend has optimized kernels for it.

- **Only the GGUF architecture `plamo3` is accepted.** Another architecture fails at load, as does a vocabulary
  without the `<|plamo:tag|>` and `<|plamo:bos|>` control tokens.
- **The prompt is built directly in PFML3** instead of through a chat template:
  `<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:msg|><source><|plamo:tag|>output<|plamo:key|>lang<|plamo:val|><target><|plamo:msg|>`.
  The source language is omitted, so a misdetected source still translates. llama.cpp's plamo2 tokenizer does not insert
  BOS, so the BOS token is prepended.
- **Target codes.** The core accepts exactly nine codes, the PFML3 `lang` values: `ja en fr de it es ru zh-hans
  zh-hant`. The model does not reject an unknown code but produces garbage, so `mt_translate_streaming` returns an
  error (`unsupported target language: ...`) instead.
- **Tokenizer override.** The GGUF declares the `plamo3` tokenizer, which the bundled llama.cpp predates. The model is
  loaded with `tokenizer.ggml.model` overridden to `plamo2`, the same Unigram tokenizer without plamo3's
  pre-segmentation (only runs of four or more identical characters, or two or more spaces, split differently). Remove
  the override once `llama-cpp-2` bundles a llama.cpp with the plamo3 tokenizer.
- **Generation** is greedy (temperature 0) and stops at an end-of-generation token or at `<|plamo:tag|>` or
  `<|plamo:bos|>`, which the model emits after the translation.
- **Budget.** The context is 1024 tokens. New tokens are capped at
  `min(factor * source_tokens + 32, 512)` and by the room the prompt leaves, where the factor is 3, or 8 when the
  target is `ru`. Cyrillic needs far more tokens than token-dense Japanese: in the FLORES evaluation 3x truncated 13% of
  the ja to ru outputs, and 8x showed no truncation. The other targets showed none at 3x. A prompt that leaves less than
  16 tokens for the answer is rejected with `input too long for translation`.
- **Streaming.** `mt_translate_streaming(model_path, text, tgt_lang, sink)` calls `sink.on_partial` after each decoded
  token with the accumulated text so far, not a delta. A token piece can end inside a UTF-8 character, so bytes are held
  until they form a complete character. The screen shows the translation filling in word by word.

### CPU requirement

The arm64 build of ggml-cpu uses `armv8.2-a+dotprod`. On a CPU without the dot-product instruction the first quantized
dot product raises SIGILL. Every translation entry point therefore checks the `ASIMDDP` bit of `AT_HWCAP` before it
initializes llama.cpp (`cpu_supported()`; the bit test is unit-tested on the host). `mt_supported()` returns false on
such a CPU, the session screen refuses to start offline with a message, and Settings shows a note. Online mode still
works. The x86_64 build uses baseline flags, so its check always passes.

### Model residency

The recognizers, the VAD and the translation model live in process-global Rust state
(`OnceLock<Mutex<Option<Engine>>>`) and load once.

- **Translation** keeps only the weights (the memory-mapped GGUF). The llama.cpp context is created and dropped per
  request. Loading a different model frees the old one first, so two models are never in memory together.
- **Unloading.** `asr_unload`, `asr_stream_unload`, `vad_unload` and `mt_unload` take the engine mutex, so a running
  recognition or translation finishes before the engine is freed.
- **Preloading.** `ModelPreloader` lives on the app scope and loads, in the background, what the current settings
  need (`requiredModelIds` over `asrEnginesFor`) as soon as the models are present. It reloads when the language,
  recognizer or translation model settings change and unloads engines that are no longer needed. The mic button shows
  its progress. Starting a session before it finishes is allowed: translation waits on the engine mutex.
- **Session start** also frees the recognizers the session does not use (`releaseUnusedEngines`), and **deleting a
  model** unloads its engine first because the GGUF is memory-mapped and the ONNX engines keep their files open.

### Speech output

Speech uses the OS TTS engine (`Locale` plus `setSpeechRate`). The OS handles grapheme-to-phoneme conversion for
Japanese, so no on-device TTS model is shipped. Android 11 and later need a `<queries>` entry for `TTS_SERVICE`, which
the manifest has. If the OS has no voice for the target language the translation is shown as text only: Listen reports
`EngineStatus.NoTtsVoice`, and Conversation skips the utterance without switching the route or muting the mic.

---

## 4. Languages and pairs

`Language` is the single source of truth for everything language-specific. Its `code` is the canonical id: the PFML3
`lang` value, also stored in settings and in session logs.

| Enum | Code | Badge | OpenAI output | Gemini | SenseVoice | Offline input |
| --- | --- | --- | --- | --- | --- | --- |
| `JA` | `ja` | JA | `ja` | `ja` | yes | yes |
| `EN` | `en` | EN | `en` | `en` | yes | yes |
| `FR` | `fr` | FR | `fr` | `fr` | no | yes |
| `DE` | `de` | DE | `de` | `de` | no | yes |
| `IT` | `it` | IT | `it` | `it` | no | yes |
| `ES` | `es` | ES | `es` | `es` | no | yes |
| `RU` | `ru` | RU | `ru` | `ru` | no | yes |
| `ZH_HANS` | `zh-hans` | ZH-S | `zh` | `zh-Hans` | yes | yes |
| `ZH_HANT` | `zh-hant` | ZH-T | none | `zh-Hant` | yes | **no** |

"My language" is one of `MY_LANGUAGES`, which holds only Japanese today (Settings shows the picker only when the list has more than one entry). Adding another
row to that list enables it everywhere without other code changes. The Listen source and the Conversation partner can
be any other language. A stored value equal to my language is reset (Listen to Auto, partner to English).

### Pair matrix

Offline needs every spoken language of the session to have `offlineInput` (Nemotron). The translation target only
needs PLaMo, which covers all nine. With my language Japanese:

| Session | Offline | OpenAI | Gemini |
| --- | --- | --- | --- |
| Listen X to ja, X in {en fr de it es ru zh-hans}, or Auto | yes | yes | yes |
| Listen zh-hant to ja | no | yes | yes |
| Conversation ja and X, X in {en fr de it es ru zh-hans} | yes | yes | yes |
| Conversation ja and zh-hant | no | no | yes |

- `offlineCapable(spoken)` is true when every spoken language has `offlineInput` (Listen: the source, or true for
  Auto; Conversation: mine and the partner). When it is false the session starts only with the Online toggle on, and
  the picker marks the language "(online only)".
- `onlineSupported(provider, targets)` is true when the provider has an output code for every target. OpenAI has no
  `zh-hant` output, so the Online toggle is disabled for a session that targets `zh-hant` with OpenAI. The code never
  falls back to another route.
- One engine serves a whole session: either offline or one online provider, never a mix.
- Japanese and English are the verified pair. The other languages are best effort.

### Listen direction

Listen is one-way: the partner's language, or Auto, into my language. The source is the Listen partner setting; the
target is my language. The translator is given only the target.

- Auto uses Nemotron with `"auto"`, and PLaMo detects the source.
- `listenSkips` drops an utterance that is already in my language before it becomes a turn: by the SenseVoice tag
  when there is one, otherwise by the presence of kana when the target is Japanese. Kanji-only Japanese is not skipped;
  it is translated ja to ja, which is harmless.
- Because the source can never equal my language, the Japanese-recognizer setting has no effect in Listen. It matters
  in Conversation's Japanese direction.

### Conversation direction

The direction comes from the Speaking control, not from detection. The speaker is `ME` or `PARTNER`. While Speaking is
off the microphone is treated as the partner (partner to my language); while it is on, as me (my language to the
partner's). A session always starts with Speaking off.

- The Speaking button is a `PttLatch`: a tap latches it on until the next tap, and a press held for 400 ms or longer
  from off is momentary and turns off on release. The latch resets when recording stops.
- The listener of a translation is the opposite of the speaker: the translation of `ME` goes to
  `Audience.PARTNER`, and the translation of `PARTNER` to `Audience.ME`.
- `ConversationInputGate` (used on the capture thread) decides the direction of every chunk in one place. The first
  chunk after a switch carries the previous speaker, so the offline path can close the old direction before the new
  audio reaches the recognizer, and the online path can send the previous session a silent tail.
- Turn ids are numbered per speaker in the online path, so `directionalTurnId` makes `ME` odd and `PARTNER` even to
  keep list keys unique. Saved turns store the speaker and the session stores its mode.

---

## 5. Online path (optional)

The provider and its key are set in Settings. The toggle next to the microphone switches a session to online. It is
enabled only when the network is available, the selected provider has a key, and the provider can output every target
of the session (section 4). The default is offline.

| | OpenAI Realtime | Gemini Live |
| --- | --- | --- |
| Model | `gpt-realtime-translate` | `gemini-3.5-live-translate-preview` |
| Auth | Mints a 600 s ephemeral secret with `POST /v1/realtime/translations/client_secrets`, then opens the WebSocket | Opens the WebSocket with the API key as a query parameter |
| Audio sent | 24 kHz PCM16 | 16 kHz PCM16 |
| Turn end | The provider's transcript-done event | Sentence-final punctuation in the translation, or the provider's `turnComplete` |
| Fallback (both) | A pause of `onlineIdleGapMs` (default 1200 ms) closes the turn | The same |

- **Pre-connect buffer.** Capture starts before the connection is ready. Until then audio goes into
  `PreConnectAudioBuffer`, which keeps up to 2 s and drops the oldest whole chunks beyond that. Once ready, the
  buffered audio is sent in order and later audio is sent directly, so speech spoken while connecting is not lost.
- **Playback.** Translated audio is 24 kHz PCM16 played through `AudioTrack`; the OS TTS is not used.
- **Keys.** Each provider has its own key, encrypted with Tink AEAD under an Android Keystore master key (the
  associated data names the provider). Only the base64 ciphertext is stored, in a separate DataStore file, so the
  plaintext never reaches the settings file.
- **Listen** translates one way into my language. The provider detects the source; only the target is pinned.

### Online Conversation

`OnlineConversationTranslator` opens one session per direction with the same provider, two at once. Both connect at
start so that pressing Speaking does not wait for a handshake; billing can therefore double.

- One capture (`VOICE_COMMUNICATION`, pinned to the built-in microphone) is shared. Each chunk goes only to the
  session the Speaking control selects. The idle session receives nothing.
- On a switch, the session that was just left gets `SWITCH_TAIL_MS` (800 ms) of silence so the provider closes its open
  turn.
- When either session ends or fails, the whole session stops and keeps the first error. Stop waits up to 5 s for the
  inner sessions before it releases the router.
- The status line combines both directions through `EngineStatus.Directional`.

---

## 6. Audio output policy

Where a translation is heard depends on the mode. `audio/AudioRouter.kt` holds the pure rules and the router
interface; `ResolvedOutput` is `EARPHONE`, `SPEAKER` or `NONE`.

| Mode | Speaks to |
| --- | --- |
| Listen, offline or online | A private output only; otherwise nothing (text only). The phone speaker is never used. |
| Conversation, partner's speech (heard by me) | Earphones; otherwise nothing, and a notice is shown |
| Conversation, my speech (heard by the partner) | The phone's built-in speaker |

- **Listen** keeps the audio mode at `MODE_NORMAL`. `listenHasPrivateOutput` checks the media output devices for a
  wired headset or headphones, a USB headset, A2DP, a BLE headset or a hearing aid. Offline Listen checks it when it
  speaks; online Listen decides once per turn when its first audio chunk arrives, so earphones plugged in mid-turn do
  not make a turn start playing halfway. Devices are queried each time, so plugging in or out takes effect on the next
  utterance.
- **Conversation** uses `CommunicationDeviceRouter`: `MODE_IN_COMMUNICATION` plus `setCommunicationDevice`, which is
  why the minimum SDK is 31. `routeTo(audience)` runs per utterance. For `ME` it selects the first earphone found in
  the order wired headset, wired headphones, USB headset, BLE headset, Bluetooth SCO, hearing aid, and returns `NONE`
  when there is none, and the caller skips playback. For `PARTNER` it selects the built-in speaker. An explicit device
  is always set, and after a switch the router waits 200 ms (`ROUTE_SETTLE_MS`) because the start of the next
  utterance can otherwise play on the old device. A2DP-only devices do not count as earphones here. `begin()` reports
  `EngineStatus.NoEarphones` at session start when none is connected.
- **Playback attributes.** Conversation `AudioTrack` and TTS use `USAGE_VOICE_COMMUNICATION`, because normal media
  attributes do not follow the communication device. The screen sets `volumeControlStream` to the voice-call stream
  while a Conversation records.
- **One at a time.** Conversation playback is one segment at a time; the two audiences alternate and never play
  together. `TtsPlayback` (offline) owns its own `TextToSpeech` so its audio attributes do not leak into Listen's.
  `PcmPlayback` (online) makes a new `AudioTrack` per segment on the chosen device.
- **Cleanup.** `end()` clears the communication device and restores `MODE_NORMAL`; it is idempotent and callable from
  any thread. The engine's `finally` calls it, a new session first ends any leftover router, and
  `RecordingService.onDestroy` ends only the router of its own generation (a generation check keeps it from ending a
  newer session's router).

### Echo gate

If the microphone picks up translated audio from the speaker and feeds it into the other direction, translations
trigger more translations. `TtsPlayback` and `PcmPlayback` expose `EchoGate.speakerBusy`: true only while audio plays
on the speaker, plus 300 ms (`ECHO_TAIL_MS`) for reverberation. A failed route switch counts as speaker playback,
which errs on the side of muting. `NONE` and `EARPHONE` are never busy.

The capture thread asks `ConversationInputGate.decide(speaking, speakerBusy)` for every chunk. Both directions are
muted while the speaker is busy: the translation of my own speech plays on the speaker, and with Speaking still on the
mic would hear it as a new turn of mine. The platform AEC is not relied on; on one device it had no echo reference.
Muted chunks are replaced with silence rather than dropped, so the audio timeline given to the recognizer or the
provider stays continuous.

### Behavior on Stop

Stopping a Conversation discards queued and playing translated audio at once. The translated text was saved when it was
shown, so it is kept. Stopping a Listen session does not cut off the utterance the offline TTS is speaking.

### Not verified on a device

Routing has been checked by building and by unit tests of the pure rules, not on a phone. These are assumptions:

- During `MODE_IN_COMMUNICATION`, `setCommunicationDevice` can switch between wired, USB or Bluetooth earphones and the
  built-in speaker per utterance, and 200 ms is enough settle time.
- Bluetooth earphones are reachable as SCO or LE Audio communication devices, and capture stays on the built-in
  microphone.
- `TextToSpeech.setAudioAttributes` makes TTS follow the selected device too.
- After a session ends, other apps' playback is unaffected.

---

## 7. Models

Models are not bundled in the APK. They are downloaded into internal storage (`filesDir`) according to the manifest
`android/app/src/main/assets/models.json`.

| ID | Model | Used for | Download |
| --- | --- | --- | --- |
| `vad` | Silero VAD v6.2.3 | Segmenting speech for SenseVoice | ~2 MB |
| `asr` | SenseVoice int8 (zh-en-ja-ko-yue) | Japanese recognition option | ~163 MB archive |
| `asr_stream` | Nemotron 3.5 ASR streaming 0.6B int8 | Default recognizer | ~475 MB archive |
| `mt_plamo3_0_8b` | PLaMo 3 Translate 0.8B, GGUF Q4_0 | Translation (default) | ~548 MB |
| `mt_plamo3_2b` | PLaMo 3 Translate 2B, GGUF Q4_0 | Translation (more accurate) | ~1.7 GB |

The default set is Nemotron plus PLaMo 0.8B. SenseVoice with the VAD, and PLaMo 2B, are optional. A session needs
`requiredModelIds(engines, mtModel)`: `asr_stream` for Nemotron, `vad` and `asr` for SenseVoice, then the translation
model. The 2B model can be selected once it is downloaded.

- **Manifest.** An entry has `id`, `dir`, `checkFile` and either `archive` (tar.bz2) or `files[]` (individual URLs).
  `size` and `sha256` are optional; Hugging Face URLs pin a revision, because the old revisions return 403.
- **Downloads** run as `ModelDownloadWorker`, a WorkManager `CoroutineWorker` that runs as a foreground service of
  type `dataSync`. There is one unique work per model, named `model-<id>`, with policy KEEP. Progress goes through
  `setProgress` (`bytes`, `total`, `phase` = download or extract). Cancelling calls `cancelUniqueWork`, and the worker
  polls `isStopped`.
- **Retry and resume.** An `IOException` returns `Result.retry()` (WorkManager backoff). A failed integrity check or an
  HTTP error fails the work. A `.part` file is resumed with a `Range` request. `resumeAction(partBytes, code)` decides:
  206 appends, 200 restarts from zero, anything else (416 included) deletes the `.part` and fails.
- **Integrity.** SHA-256 is computed while writing, and size and sha256 are compared with the manifest before the
  `.part` is renamed to its final name. On a mismatch the `.part` is deleted. A file under its final name is therefore
  always verified, and `isPresent` is a plain existence check. An archive is verified before it is extracted.
- **Atomic extraction.** A tar.bz2 is extracted with Apache Commons Compress into a staging directory and renamed into
  place when complete, so an interruption never leaves a partial model that looks present. Entries that escape the
  destination are rejected.
- Redirects are followed manually (GitHub and Hugging Face CDN hops), up to eight.
- **Deleting** a model is not allowed while recording. The native engine using it is unloaded first.

Internal storage is required: on some OEM builds a raw NDK `open()` on external storage (`Android/data`) fails with
`EACCES`, while Java's `File.exists()` succeeds there and `listFiles()` is denied.

---

## 8. Persistence and settings

- **Sessions.** `data/SessionStore.kt` writes one JSON file per session at `filesDir/sessions/<id>.json`. There are few
  sessions and no schema is needed, so kotlinx.serialization JSON is used instead of a database. A turn stores the
  transcript, source and target codes, the ASR tag, the translation, the speaker and the start time. A corrupt file is
  skipped when listing.
- **Settings.** `data/SettingsRepository.kt` keeps `AppSettings` in a Preferences DataStore
  (`datastore/settings.preferences_pb`) and exposes a `Flow<AppSettings>`. Parsing is the pure function
  `parseSettings`, the one place stored data is validated: an unknown or mistyped value falls back to its default. There
  is no migration code. Both stores are declared once at top level, so there is one DataStore per file per process
  however many repositories are created.
- **API keys.** `datastore/secrets.preferences_pb` holds only the ciphertexts. `SecureKeyStore` uses Tink AEAD
  (AES256-GCM), with its keyset in a private SharedPreferences file wrapped by a master key in the Android Keystore. A
  key that cannot be decrypted (a lost keyset, for example) reads as "no key" so the app asks for it again.
  `EncryptedSharedPreferences` is not used because it is deprecated.
- **Backup.** `allowBackup` is true, and the data extraction rules include only `datastore/settings.preferences_pb`
  and `sessions/`, for both cloud backup and device transfer. The models (gigabytes), the secrets and the Tink keyset
  are not backed up: they are large or bound to one device's Keystore.

---

## 9. Build

Every `.so` in `android/app/src/main/jniLibs/` is a build output or an upstream binary, so none is committed (the
directory is git-ignored). Supported ABIs are `arm64-v8a` (devices) and `x86_64` (emulator). Gradle runs two kinds of
task before `preBuild`:

1. **`fetchSherpaPrebuilt`** downloads the sherpa-onnx 1.13.8 Android release (cached in the Gradle user home) and
   extracts `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so` and `libonnxruntime.so` into `jniLibs/<abi>/`.
2. **`cargoBuildRustCoreArm64`** and **`cargoBuildRustCoreX86`** cross-compile `crates/translatecore` with cargo-ndk
   (`cargo ndk --platform 31 -t <abi> build --release -p translatecore`) into `jniLibs/<abi>/libtranslatecore.so`.

Order matters: `build.rs` links `libsherpa-onnx-c-api.so`, so step 1 runs first. Each task declares its inputs and
outputs and is skipped when nothing changed. The two ABI builds are serialized because they share one cargo target
directory.

### Building llama.cpp

The `llama-cpp-2` crate builds llama.cpp with CMake and links it statically into `libtranslatecore.so`.

- **CMake 3.22 or later and Ninja** are required. `.cargo/config.toml` sets `CMAKE_GENERATOR=Ninja` (cmake-rs adds no
  `-G` for Android targets, and a Windows host would otherwise pick a Visual Studio generator, which cannot drive the
  NDK).
- **One cargo-ndk run per ABI.** `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod` is set for `arm64-v8a` only. llama-cpp-sys-2
  forwards every `GGML_*` environment variable to CMake for all ABIs, so putting it in `.cargo/config.toml` would leak
  it into the x86_64 build. A value exported in the developer's shell is removed from the x86_64 task.
- **`--platform 31`** matches `minSdk`; llama.cpp's CMake build reads it as `ANDROID_PLATFORM`.
- **libc++ is linked statically** (the `android-static-stdcxx` feature of `llama-cpp-2`). sherpa-onnx does not ship
  `libc++_shared.so`, so `libtranslatecore.so` must not list it among its NEEDED entries.
- `[profile.dev.package.llama-cpp-sys-2]` sets `opt-level = 3`, so ggml is optimized even in debug builds.
- llama.cpp is an Android-only dependency, so host builds (`cargo test`, `uniffi-bindgen`) need no CMake or C++
  toolchain.

`libonnxruntime.so` is shipped only because sherpa-onnx links it. The Rust core has no ONNX Runtime binding of its own.

### Bindings

The Kotlin bindings (`uniffi/translatecore/translatecore.kt`) are committed. Every exported function also exists on the
host as a stub, so the host library yields the same bindings as the Android build. After changing the Rust public API,
run `tools/gen-bindings.sh` and commit the result.

### Rust FFI conventions

- sherpa's C structs from `c-api.h` are mirrored with `#[repr(C)]` including every field. Unused embedded configs must
  be defined too, or the offsets of later fields shift. They are initialized with `mem::zeroed()`.
- Raw pointers are kept resident, so engines use `unsafe impl Send` and free everything in one `Drop`.
- PCM crosses the FFI as `ByteArray` (PCM16 little-endian) and is converted to f32 in Rust, which avoids boxed `Float`
  arrays.
- sherpa's native logs go to stderr and do not reach logcat. To diagnose a failure it is fastest to check file
  readability on the Rust side first and include the result in the error message.

---

## 10. Known limitations

- Offline recognition covers ja, en, fr, de, it, es, ru and `zh-hans`. A session that must recognize `zh-hant` needs
  Online mode, and with OpenAI a `zh-hant` target is not offered at all (use Gemini).
- Offline translation needs an arm64 CPU with the dot-product instruction (`ASIMDDP`) and Android 12 (API 31) or
  later. Remaining risk: ggml-cpu's C++ static initializers run when `libtranslatecore.so` is loaded, before any Rust
  code. If one of them executed a dot-product instruction the check could not prevent the crash. They normally do not,
  but startup on the oldest cores (Cortex-A53 only, for example) has not been confirmed.
- PLaMo 3 Translate is tokenized with llama.cpp's plamo2 tokenizer until the bundled llama.cpp supports plamo3
  (section 3).
- Japanese accuracy of Nemotron streaming is lower than SenseVoice's.
- Online Conversation connects two sessions at once, so billing can double.
- Without earphones, the translation of the partner's speech is text only, because it must not play on the speaker.
- While a translation plays on the speaker in Conversation, the mic is muted in both directions, so speech during
  playback is lost and an utterance in progress is cut off. Playback on earphones does not mute the mic.
- Output routing (`MODE_IN_COMMUNICATION` plus `setCommunicationDevice`) is not verified on a device (section 6).
- Turn splitting is heuristic (VAD parameters, endpoint rules, the idle gap), so it depends on the environment and
  speaking style.
