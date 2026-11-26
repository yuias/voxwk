# voxwk: architecture

This describes the app as implemented: the phone app and its MemoMind Glass support. Build and signing steps, and the
user-facing description of the Glass, are in [README.md](../README.md).

---

## 1. Overview

voxwk translates speech between Japanese and eight other languages. By default all inference runs on the device. The
inference core is the Rust crate `translatecore`, called from the Jetpack Compose UI through UniFFI and JNA. Online
translation is optional: it hands a whole turn to a cloud speech-to-speech model. The MemoMind Glass is an optional
second device: its microphone can be the input, and its display and speaker show and speak the translations
(section 7).

```
Kotlin / Compose (android/app)   UI, capture, OS TTS, playback routing, model downloads, settings
        |  UniFFI + JNA
        v
Rust core (crates/translatecore)  VAD and ASR orchestration, translation through llama.cpp
        |  C API / static linking
        v
Native libs (jniLibs)             sherpa-onnx + onnxruntime, libtranslatecore.so (llama.cpp linked statically)
```

Audio I/O (`AudioRecord`, `TextToSpeech`, `AudioTrack`, the Glass Bluetooth links) stays on the Kotlin side. The Rust
side only converts and runs inference, which keeps it testable on the host. The one exception to "inference" is
`opus.rs`, which decodes the Glass microphone's Opus packets to PCM.

### Repository layout

| Path | Contents |
| --- | --- |
| `Cargo.toml`, `Cargo.lock` | Rust workspace (one member, `crates/translatecore`) |
| `.cargo/config.toml` | Sets `CMAKE_GENERATOR=Ninja` for the llama.cpp build |
| `crates/translatecore/` | The Rust core: `lib.rs` (exported API), `mt.rs`, `asr.rs`, `vad.rs`, `opus.rs` (Glass Opus decoder) |
| `android/` | Gradle root project; `app/` is the app |
| `android/glass-gm/` | The MemoMind GM codec (frames, sessions, recording stream parser, screenshot decoding), pure JVM; shared with the PoC |
| `android/app/src/main/java/uniffi/translatecore/translatecore.kt` | Generated UniFFI bindings, committed |
| `tools/gen-bindings.sh` | Regenerates the bindings from the host build of the core |
| `tools/mt-eval/` | Desktop scoring of the translation models; not part of the app |
| `poc/` | Throwaway hardware experiments; not part of the app build (the Glass PoC builds `android/glass-gm/` too) |

The app code is under `android/app/src/main/java/app/rly3h/voxwk/`:

| Package | Role |
| --- | --- |
| `lang/` | `Language`, the pair rules and the recognizer choice (section 4) |
| `data/` | Settings (DataStore), the key store, the session store, the model catalogue and downloads |
| `translate/` | The engines and the callback seam between an engine and the screen (section 2) |
| `audio/` | `AudioInput` and its phone and Glass implementations, output routing, TTS and PCM playback (sections 2 and 6) |
| `glass/` | The Glass link, the HUD display, the display callbacks decorator and screenshots (section 7) |
| `ui/` | Session, history and settings screens, each with a ViewModel |
| `theme/` | The DayNight theme |

---

## 2. Engine seam

`translate/SpeechTranslator.kt` is the one interface between an engine and the screen.

- `SpeechTranslator`: `start(scope, callbacks)` does not block, `stop()` is idempotent. Conversation engines also
  implement `ConversationTranslator.setSpeaking`.
- `TranslatorCallbacks`: `onTurnStart`, `onTurnUpdate`, `onTurnComplete`, `onPartial`, `onStatus`, `onSpeak`,
  `onPersist` and `onFinished`. Each maps onto one piece of screen state, so an engine never touches Compose.
  `onPartial(text, speaker)` is an overload for Conversation: the offline engine tags a partial with the direction of
  the chunk it was decoded from. The default drops the tag, so only a callback that needs it (the Glass display)
  overrides it. The tag cannot come from the Speaking state at display time, because the offline loops decode queued
  chunks later, and a partial of my last words can arrive after Speaking was released.
- `GlassDisplayCallbacks` (section 7) is a decorator over a `TranslatorCallbacks`: it delegates every call and mirrors
  the turns to the Glass display, so the engines do not know about the Glass.
- `EngineStatus` is typed, not text: `Listening`, `Connecting`, `NoEarphones`, `NoTtsVoice(language)`,
  `Failed(stage, detail)` and `Directional(speaker, inner)`. The screen turns it into a string resource. A native
  error message (`detail`) is shown verbatim inside a resource template.
- `LiveTurn` carries the transcript, `src` (null means Auto in Listen), `tgt`, the raw ASR tag, the translation, the
  speaker (Conversation only) and the start time.
- Engines take an `AppSettings` snapshot at session start. The speak switches and the speech rate act mid-session, so
  engines read them from the live settings flow through lambdas. Playbacks that serve both audiences (`TtsPlayback`,
  `PcmPlayback` and the two Conversation engines) take `speaks(audience)`, backed by `AppSettings.speaks`: `autoSpeak`
  is "speak what I hear" (`Audience.ME`) and `autoSpeakPartner` is "speak what my partner hears". Paths that serve only
  me (Listen's media TTS and the online Listen audio sink) take a plain `autoSpeak` lambda. The speech rate applies to
  `Audience.ME` only: `playbackRate(audience, rate)` is the rate for me and always 1.0 for the partner, set explicitly
  on every utterance because the `TextToSpeech` instance keeps its last rate. Online speech is never rate-adjusted.

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

`PhoneAudioInput` captures from the phone. A device that cannot open a 24 kHz `AudioRecord` captures 16 kHz and
upsamples it linearly. Each saved session records its input kind (`PHONE` or `GLASSES`).

`GlassAudioInput` takes the microphone of the Glass instead. It is constructed with the session's `GlassLink`, the
Opus decoder and the pickup mode; `purpose` and the preferred device of the format are ignored.

- It starts a recording on the Glass: `01/2F` sets noise reduction 0 and the pickup mode, the recording is negotiated in
  compact form and started. The Glass sends Opus packets of 20 ms; each is decoded to 640 bytes of 16 kHz PCM, regrouped
  into the chunk size of the format (`PcmRechunker`) and upsampled when the format is 24 kHz.
- Streams: `0x92` is this client's recording. `0x93` replaces it while Bluetooth call audio (SCO) is up, but speech in it
  is not usable. It is accepted because inside a voxwk recording it only appears while SCO is up, which is also while
  the Glass plays and the input is muted, so gating it separately would change nothing. `0x90` and `0x91` are the Glass's own
  recordings and arrive unsolicited, and `0x94` was not observed; all three are dropped.
- **Restart after a stall.** When SCO ends the Glass stops recording and does not resume by itself. If no accepted
  packet arrives for 300 ms (`restartAfterMs`; packets normally arrive every 20 ms) the input negotiates and starts the
  recording again. Watching the stream instead of taking a signal from the router also covers SCO ending for other
  reasons, such as a phone call. The record mode (compact or legacy) is kept while a restart negotiates: the parser
  follows each negotiation reply, so records that are still arriving are parsed in the mode they were sent in.
  Ten restarts with no accepted packet in between (`MAX_RESTARTS`, about 3.5 s of silence after the start was re-sent)
  end the session with `IOException("Glass stopped sending audio")`; the worst recovery measured was 1.24 s. Every
  accepted packet resets the count. Online Listen does not end: it only reports a microphone failure and keeps
  recording until Stop.
- **Pickup mode.** `GlassMic.FRONT` is mode 0 and `ALL_AROUND` is mode 1 (the documented 3-microphone mode). Only mode 0
  was tested.
- A closed link while running throws an `IOException`, which ends the session.

`EchoMutedInput` wraps any input and delivers silence while a `busy` function is true. Listen with a Glass uses it so the
Glass microphone does not hear the Glass playing. It sends zeros instead of dropping chunks, so the recognizer's time
axis stays continuous, as in Conversation.

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
  reports no language tag. Listen passes `"auto"`. Conversation sets each direction's language (`Language.nemotronCode`,
  such as `ja-JP`; a bare `ja` is not a Nemotron key and falls back to auto) whenever the direction changes, where the
  stream is reset, because on a device `"auto"` decoded Japanese as Chinese. Turns end on sherpa's endpoint rules
  (`EndpointParams`: rule1 2.4 s, rule2 1.2 s, rule3 20 s by default), where rule2 (silence after speech) is the main
  end-of-turn setting.
- **Empty endpoints.** An endpoint with an empty transcript is rule1 (trailing silence with no token decoded) and repeats
  about every 2.8 s in silence. Resetting the stream there discarded the head of speech whose first word was not decoded
  yet: on a device, every lost turn head had such an empty endpoint 0.15 to 2.1 s after onset. `should_reset` therefore
  resets on a non-empty endpoint, or on an empty one only after 2 s without a loud sample, or 20 s without any reset (so
  constant noise cannot grow the stream without bound). A skipped reset reports `is_endpoint = false`, so the caller
  does not end a turn. In a device run with the Glass microphone, 20 of 21 turn heads were kept (12 of 21 before); the remaining loss is
  not explained.
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
  its progress. Starting a session before it finishes is allowed: translation waits on the engine mutex. It also runs
  again after a model download finishes and after a delete (`ModelPreloader.refresh()`), so the first session after
  either does not load lazily. The re-run forgets what it believes is loaded and syncs again; that is cheap because
  every native load is idempotent for equal arguments, so an engine that is still resident returns at once.
- **Session start** also frees the recognizers the session does not use (`releaseUnusedEngines`), and **deleting a
  model** unloads its engine first because the GGUF is memory-mapped and the ONNX engines keep their files open.

### Speech output

Speech uses the OS TTS engine (`Locale` plus `setSpeechRate`). The OS handles grapheme-to-phoneme conversion for
Japanese, so no on-device TTS model is shipped. Android 11 and later need a `<queries>` entry for `TTS_SERVICE`, which
the manifest has. If the OS has no voice for the target language the translation is shown as text only: Listen reports
`EngineStatus.NoTtsVoice`, and Conversation skips the utterance without switching the route or muting the mic.

`TtsPlayback` (Conversation, and Listen with a Glass) waits for the engine's completion callback before it releases the
route, and it cuts the utterance off if the callback never comes. That wait is estimated from the text length (2 s
plus 150 ms per character). It grows as 1/rate when the speech rate is below 1x, stays at the 1x estimate above 1x
because the per-character figure is not measured against fast speech, and is capped at 60 s. The partner's speech is always 1x, so it is never scaled.

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
interface; `ResolvedOutput` is `EARPHONE`, `SPEAKER`, `GLASS` or `NONE`.

| Mode | Speaks to |
| --- | --- |
| Listen, offline or online | A private output only; otherwise nothing (text only). The phone speaker is never used. |
| Conversation, partner's speech (heard by me) | Earphones; otherwise nothing, and a notice is shown |
| Conversation, my speech (heard by the partner) | The phone's built-in speaker |
| Glass session, Listen, or Conversation partner's speech (heard by me) | The Glass speaker (`GLASS`), over Bluetooth call audio |

- **Listen** keeps the audio mode at `MODE_NORMAL`, except in a Glass session (section 7), where it has a router like
  Conversation. Otherwise `listenHasPrivateOutput` checks the media output devices for
  a wired headset or headphones, a USB headset, A2DP, a BLE headset or a hearing aid. Offline Listen checks it when it
  speaks; online Listen decides once per turn when its first audio chunk arrives, so earphones plugged in mid-turn do
  not make a turn start playing halfway. Devices are queried each time, so plugging in or out takes effect on the next
  utterance.
- **Conversation** uses `CommunicationDeviceRouter`: `MODE_IN_COMMUNICATION` plus `setCommunicationDevice`, which is
  why the minimum SDK is 31. `routeTo(audience)` runs per utterance. For `ME` it selects the first earphone found in
  the order wired headset, wired headphones, USB headset, BLE headset, Bluetooth SCO, hearing aid, and returns `NONE`
  when there is none, and the caller skips playback. For `PARTNER` it selects the built-in speaker. An explicit device
  is always set, and after a switch the router waits 200 ms (`ROUTE_SETTLE_MS`) because the start of the next
  utterance can otherwise play on the old device. A2DP-only devices do not count as earphones here. In a Glass session ME's output is the Glass
  (section 7). `begin()` reports `EngineStatus.NoEarphones` at session start when none is connected.
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
on the speaker or the Glass, plus a tail: 300 ms (`ECHO_TAIL_MS`) for reverberation on the speaker, and 800 ms
(`GLASS_ECHO_TAIL_MS`) after Glass playback (section 7). A failed route switch counts as speaker playback, which errs
on the side of muting. `NONE` and `EARPHONE` are never busy: earphone audio does not reach the microphone, but the Glass
microphone hears the Glass speaker about 30 dB above speech.

The capture thread asks `ConversationInputGate.decide(speaking, speakerBusy)` for every chunk. Both directions are
muted while the speaker is busy: the translation of my own speech plays on the speaker, and with Speaking still on the
mic would hear it as a new turn of mine. The platform AEC is not relied on; on one device it had no echo reference.
Muted chunks are replaced with silence rather than dropped, so the audio timeline given to the recognizer or the
provider stays continuous.

### Behavior on Stop

Stopping a Conversation discards queued and playing translated audio at once. The translated text was saved when it was
shown, so it is kept. Stopping a Listen session does not cut off the utterance the offline TTS is speaking.

### Verified and not verified on a device

Routing was checked by building, by unit tests of the pure rules, and on one phone with one Glass. The statements below
are for that phone only.

Verified:

- In `MODE_IN_COMMUNICATION`, `setCommunicationDevice` switches per utterance between the built-in speaker (the
  partner's audience) and the Glass over SCO (my audience), offline.
- `TextToSpeech.setAudioAttributes` makes TTS follow the selected device.
- SCO ends within 0.16 s of `clearCommunicationDevice()` (11 of 11 Glass playbacks).

Not verified:

- Online playback (`PcmPlayback`) on either device.
- Wired, USB and Bluetooth earphone routing, and whether 200 ms of settle time is enough for them.
- Capture staying on the built-in microphone with Bluetooth earphones.
- Other apps' playback after a session ends.

---

## 7. MemoMind Glass

The Glass is a pair of glasses with a microphone, a display and a speaker. voxwk talks to it over Bluetooth using the
MemoMind protocols (GM frames on RFCOMM). The codec is the pure-JVM module `android/glass-gm/`, which the app and the
Glass PoC both build. The app side is in `glass/` and `audio/`. A session uses the Glass when Settings has "Use Glass" on
and a device address.

### Link

`GlassLink` opens two secure RFCOMM sockets at session start and closes them when the session ends: the command channel
(UUID `00007033-...`) and the recording channel (`00002024-...`). Both connect in about 250 ms in total. There is no
insecure attempt and no `1101` fallback: both connect secure, and the Glass's service record does not list `1101`.

- **One link per session.** The official MemoMind app and voxwk cannot use the Glass at the same time: while the official
  app holds it, opening the command channel fails. voxwk therefore holds the Glass only while a session runs, which costs
  the connect time at each start.
- **Notification access.** The official app has a notification listener, and while it has notification access Android
  restarts it within about a second of a force-stop, after which it takes the Glass again. Turning its notification
  access off is the expected remedy. That a force-stop then keeps it stopped was not measured. The in-app message does not
  mention this.
- **Connect failure.** A failed connect looks the same whether the official app holds the Glass or the Glass is off or
  out of range, so there is one message ("Can't connect to the Glass. Force-stop the MemoMind app, or turn Glass off in
  Settings."). A missing Bluetooth permission has its own message. The session does not start; there is no automatic retry
  and no fallback to the phone microphone.
- **Frames.** The uplink's continuation frames are headed `0xFA`, not `0x01` as documented. The codec in `glass-gm`
  accepts them, and its tests cover the case.
- **Teardown** (link, display, decoder) runs on the application scope on `Dispatchers.IO`, because clearing the
  ViewModel cancels its own scope.

The microphone input over this link is `GlassAudioInput` (section 2).

### Display

`GlassDisplayCallbacks` mirrors the turns of any engine to a `GlassDisplay`, and `NativeTranslationDisplay` renders them
in the Glass's own translation UI (service `03`).

| Frame | Sent when |
| --- | --- |
| `03/01` | the session begins (title), and again before the first update after an idle close |
| `03/03` | a provisional update: the partial transcript, a turn start, or a translation filling in |
| `03/04` | a turn's final translation |
| `03/02` | the session ends, or the display has been idle for 15 s |

- Text is raw UTF-8 (`ASCII_ONLY` is false); Japanese rendered correctly on the Glass. A provisional with an empty
  translation shows a recording indicator and keeps the previous final line.
- **Coalescing.** Provisional updates less than 250 ms apart are merged (the latest wins, sent on the next call after the
  interval); a final is always sent at once. No rate-limit reply and no flicker were seen at 250 ms.
- **Display mode.** "Translation only" empties the source of the final only. The provisionals still carry the source, so
  the partial transcript shows while someone speaks.
- **`showMine`.** With "Show what I say in Conversation" off, the turns and partials of `Speaker.ME` are delegated to the
  screen but not shown on the Glass: they are not stored, so their updates and final show nothing, and they do not count
  toward the coalescing interval. The HUD keeps the partner's last line while I speak. The partner's partial is told
  apart from mine by `onPartial(text, speaker)` (section 2). Empty partials, which clear the line, are untagged and
  ignored by the Glass display. The setting is read at session start.
- **Writer.** Every frame is posted with `GmSession.post` from one single-thread executor, in call order, without waiting
  for a reply, because the callbacks arrive on engine threads (the offline engine calls back on Main) and a blocking
  request could stall them. After `close()` the display drops calls. A write error (the link dropped) is caught on that
  thread; the input ends the session.
- **Idle close.** On the tested firmware the Glass never dims its display while the translation UI is open, even in
  silence, and dims soon after `03/02`. The display therefore posts `03/02` after 15 s without a shown update and `03/01`
  before the next one. The check runs on the writer's executor, with an update counter so that a check firing as an
  update arrives sees it is stale. Measured: the display dims about 15 s plus the Glass's own timeout after the last
  update, the link stays up, and the next translation opens the UI again. After the idle close, raising the head shows
  the Glass home screen with "App disconnected", because the official app is not connected; this is expected.

### Speaker and Bluetooth call audio

The Glass speaker is reached with Bluetooth call audio (SCO), through the Conversation router
(`MODE_IN_COMMUNICATION` plus `setCommunicationDevice(TYPE_BLUETOOTH_SCO)`).

- **Which device is the Glass.** The SCO communication device of the Glass reports a hidden address, so it cannot be
  matched to the paired address. In a Glass session any SCO device is the Glass (`pickMeDevice` returns `GLASS`); the
  configured address is only the flag that this is a Glass session. The cost is that a second Bluetooth headset with
  SCO connected during a Glass session counts as the Glass.
- **The microphone during SCO.** While SCO is up the Glass records on `0x93` instead of `0x92`, within about 0.5 s, and
  speech in `0x93` is not usable. Glass playback also arrives in `0x93` about 30 dB above speech. So the input is muted
  while the Glass plays (`isEchoBusy(GLASS)` is true; this is the exception to "earphone playback never mutes").
- **SCO released after each utterance.** After each utterance on `GLASS`, `TtsPlayback` and `PcmPlayback` call
  `releaseCommunicationDevice()` (`clearCommunicationDevice()`, with the mode left at `MODE_IN_COMMUNICATION`), and the
  next utterance selects the Glass again. Without this the OS keeps SCO up for 6.0 s after each
  `USAGE_VOICE_COMMUNICATION` playback, which left the Glass on the unusable `0x93` for that long. When SCO ends the Glass
  stops recording, and `GlassAudioInput` restarts it after the 300 ms stall (section 2). The whole SCO-off and restart
  window measured about 0.65 s, with one in 11 taking 1.24 s. The echo hold after Glass playback is
  `GLASS_ECHO_TAIL_MS` (800 ms), so the mute covers the period in which the Glass delivers no microphone audio. On the device no echo turns
  followed Glass playback in Listen or Conversation.
- **Listen** has no router on the phone. In a Glass session the ViewModel creates one before the recording service
  starts (so the service's generation check covers it), calls `begin()`, and puts a `TtsPlayback` (offline) or a
  `PcmPlayback` (online, through the engines' `audioOut`) on it. The input is wrapped in `EchoMutedInput`. When the
  session finishes the playback is closed and `router.end()` is called.
- **Conversation** needs nothing more: the Glass microphone serves both directions, the translation for me goes to the
  Glass, and the translation for my partner goes to the phone speaker. `ConversationInputGate` already mutes both
  directions while `speakerBusy`.

### Screenshots

The session screen shows a screenshot button while a Glass session runs. It reads the Glass's framebuffer over the command
channel (service `01`).

- **Wire.** `01/65` requests a capture, the Glass uploads `01/66` chunks (index `0xFFFE` start with JSON metadata,
  `0..n-1` data, `0xFFFD` complete, `0xFFFF` error), and `01/6A` asks for missing chunks.
- **Routing.** `GmSession` passes every message that matches no pending request to a sink on `GlassLink`
  (`onGmMessage`). That stream carries the uploads and also the acks of every posted frame. The sink only enqueues. The
  capture runs on a caller IO thread and sends with `post`; the reader thread never writes or waits for a reply.
- **Assembler.** `ScreenshotAssembler` validates the start metadata once (compression, bit depth, sizes, band layout),
  stores chunks by index, and gives `Done`, `Resend` or `Failed`. It never throws on radio data. The idle deadline is
  reset only by messages of the capture, because the HUD's acks reach the same inbox several times a second and would
  otherwise never time out. The policy, not measured: 8 s idle timeout, up to 8 resend rounds of at most 64 chunks.
- **Decoding.** The image is a 4-bit grayscale framebuffer compressed with LZ4, plain or in bands. `Lz4Block` is a
  hand-written block decoder of about 40 lines: it decodes into an array of the known size and rejects any step that
  would read or write out of range. A library would add native code to a module the PoC also builds, or (the stream
  classes in commons-compress) know no output size. The image is cropped to its visible width and encoded as a
  4-bit grayscale PNG by `toPng`, with `Deflater` and `CRC32`, so the 16 levels are kept exactly. `javax.imageio` does
  not exist on Android and `android.graphics.Bitmap` does not exist in `glass-gm`, so the encoder is plain JVM and
  tested on the host.
- **Saving.** The PNG is inserted through `MediaStore` into `Pictures/voxwk` with `IS_PENDING`, and no permission is
  needed (API 29 and later). The result is shown as a Toast. A capture on the device gave a 600 by 350 image with
  the correct orientation.

### SDK clone

`third_party/plugin-open-platform` is not needed to build or run the app. It is used only by the codec cross-check test in
`glass-gm`, which is skipped when the clone is absent.

---

## 8. Models

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

## 9. Persistence and settings

- **Sessions.** `data/SessionStore.kt` writes one JSON file per session at `filesDir/sessions/<id>.json`. There are few
  sessions and no schema is needed, so kotlinx.serialization JSON is used instead of a database. A turn stores the
  transcript, source and target codes, the ASR tag, the translation, the speaker and the start time. A corrupt file is
  skipped when listing. The session is rewritten whenever an engine reports `onPersist`, and every turn that has a
  translation is saved. A turn whose translation failed completes with an empty translation and is saved like any
  other, including as the last turn of a session (offline path).
- **Settings.** `data/SettingsRepository.kt` keeps `AppSettings` in a Preferences DataStore
  (`datastore/settings.preferences_pb`) and exposes a `Flow<AppSettings>`. Parsing is the pure function
  `parseSettings`, the one place stored data is validated: an unknown or mistyped value falls back to its default. There
  is no migration code. Both stores are declared once at top level, so there is one DataStore per file per process
  however many repositories are created.
  - The speech fields are `autoSpeak` (speak what I hear), `autoSpeakPartner` (speak what my partner hears) and
    `speechRate` (what I hear only). `autoSpeak` kept its name and stored key from before the per-direction split, so
    nothing needed migrating; a stored `autoSpeak = false` mutes only my direction, because `autoSpeakPartner` defaults
    to true.
  - The Glass fields are `glassEnabled`, `glassAddress` (validated at parse as six upper-case hex pairs separated by
    colons), `glassMic` (`FRONT` or `ALL_AROUND`), `glassDisplay` (`SOURCE_AND_TRANSLATION` or `TRANSLATION_ONLY`) and
    `glassShowMine`. A session snapshots them at start, so a change applies to the next session.
- **Settings screen.** Settings is a list of categories; each row (a Material 3 `ListItem`) shows its title and one line
  with the current value, and opens a page that is its own back-stack entry with the app bar and a back arrow
  (`SettingsDetail(page)` in Navigation3). Every setting is on exactly one page.

  | Page | Holds |
  | --- | --- |
  | Playback | the two speak switches and the speech rate |
  | Recognition and translation | the Japanese recognizer and the translation model |
  | Models | the model list and downloads |
  | Online translation | provider, API key and its test |
  | MemoMind Glass | Use Glass, the device, the microphone, the display mode and "Show what I say in Conversation" |
  | Turn detection | the end-of-turn rules, the voice detection (only while SenseVoice is selected) and the online pause |
  | About | the version and the system information |

  Your language is a page too, but it is hidden while Japanese is the only choice. The pure pieces (`SettingsPage`, the
  row summaries) are in `ui/settings/SettingsPages.kt` and the shared rows are in `SettingsComponents.kt`. The pages
  share one `SettingsViewModel`, and the list and the Models and Glass pages refresh when the activity resumes.
  `android:enableOnBackInvokedCallback` is on, so predictive back works app-wide.
- **API keys.** `datastore/secrets.preferences_pb` holds only the ciphertexts. `SecureKeyStore` uses Tink AEAD
  (AES256-GCM), with its keyset in a private SharedPreferences file wrapped by a master key in the Android Keystore. A
  key that cannot be decrypted (a lost keyset, for example) reads as "no key" so the app asks for it again.
  `EncryptedSharedPreferences` is not used because it is deprecated.
- **Backup.** `allowBackup` is true, and the data extraction rules include only `datastore/settings.preferences_pb`
  and `sessions/`, for both cloud backup and device transfer. The models (gigabytes), the secrets and the Tink keyset
  are not backed up: they are large or bound to one device's Keystore.

---

## 10. Build

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

## 11. Known limitations

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
- Without earphones or a Glass, the translation of the partner's speech is text only, because it must not play on the
  speaker.
- While a translation plays on the speaker in Conversation, the mic is muted in both directions, so speech during
  playback is lost and an utterance in progress is cut off. Playback on earphones does not mute the mic.
- Output routing is verified on one phone only for the built-in speaker and the Glass, offline. Online playback and wired,
  USB and Bluetooth earphone routing are not verified (section 6).
- Turn splitting is heuristic (VAD parameters, endpoint rules, the idle gap), so it depends on the environment and
  speaking style.

### MemoMind Glass

- The Glass cannot be used together with the official MemoMind app, because the Glass cannot be shared (section 7).
  While the official app has notification access it restarts itself after a force-stop and takes the Glass again.
- Speech during Glass playback is not translated: the microphone is muted (the Glass hears its own speaker far above
  speech), and the call-audio stream `0x93` does not carry usable speech. The mute covers the SCO-off and restart window
  (800 ms).
- The start of an utterance is occasionally lost: 1 of 21 turn heads in one device run. The cause is not known; it
  may be an empty-endpoint reset just before speech onset (section 3).
- Online speech always plays at 1.0x, so the speech rate setting does not apply online. Online playback on the Glass was
  not tested.
- Turning the Glass off during a session ends it, because the input sees the closed link (section 2). This path was
  not tested on a device.
- A second Bluetooth headset with SCO during a Glass session is treated as the Glass (section 7).
- The All-around microphone mode was not tested. The Glass's own recording streams (`0x90`, `0x91`) are dropped.
- Recovery after Glass playback occasionally takes longer than the 800 ms hold (one in 11), leaving a short window with
  no audio.
