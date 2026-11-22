# voxwk

An Android app that translates speech on the device. Its main use is for Japanese speakers abroad: speech in other
languages is translated into Japanese offline and spoken aloud. MemoMind Glass can serve as the microphone, and as the display and speaker for translations.

Status: the phone app is buildable and complete. MemoMind Glass is part of the app; it was tested with one phone and
one Glass, and the limits are listed under [Known limits](#known-limits). `poc/glass-spp/` is a buildable hardware
test.

## Features

- Offline speech recognition, translation and spoken output. Online translation with OpenAI Realtime or Gemini Live is
  optional.
- Nine languages: Japanese, English, French, German, Italian, Spanish, Russian, Chinese (Simplified) and Chinese
  (Traditional). "Your language" is Japanese; the other eight are partner languages.
- Two session modes:
  - **Listen**: hears the other person (a chosen language, or Auto) and translates into your language. Translations are
    spoken only when earphones or another private output are connected.
  - **Conversation**: two-way, with a **Speaking** button that tells the app who is talking.
- MemoMind Glass: its microphone as the input, translations on its display and speaker, and screenshots of its
  display.
- Translation with PLaMo 3 Translate (0.8B by default, 2B optional) through llama.cpp.
- Session history is saved on the device.

Japanese and English are the verified pair. The other languages are best effort.

## Requirements

- An arm64 device with Android 12 (API 31) or later. Offline translation needs the Arm dot-product instructions
  (Cortex-A55 or A76 generation and newer); without them only Online mode works. x86_64 builds are for the emulator.
- Storage for the models (about 1 GB for the default set, more for the optional ones).

## Building

### Prerequisites

- JDK 17 or later (the Gradle build uses a Java 17 toolchain)
- Android SDK with platform `android-37` and NDK `27.2.12479018`. Set `ANDROID_HOME` and `ANDROID_NDK_HOME`
  (the Opus library's CMake build reads the NDK path from it).
- Rust (stable) with the Android targets, and `cargo-ndk`:
  ```sh
  rustup target add aarch64-linux-android x86_64-linux-android
  cargo install cargo-ndk
  ```
- CMake 3.22 or later and Ninja. The Android SDK package `cmake;<version>` ships both; put its `bin` directory on
  `PATH` if you use it.

### Build and install

Run these from the repository root. On Windows use `android\gradlew.bat`.

```sh
android/gradlew -p android assembleDebug
adb install -r android/app/build/outputs/apk/debug/voxwk.debug.apk
```

- The Gradle build downloads the prebuilt sherpa-onnx libraries (1.13.8) and cross-compiles the Rust core, which
  includes llama.cpp, with `cargo-ndk`. The first build takes a while.
- The ABIs are `arm64-v8a` and `x86_64`.
- The debug build has the application id suffix `.debug`, so it can be installed next to a release build.
- The Kotlin bindings for the Rust core (`android/app/src/main/java/uniffi/translatecore/translatecore.kt`) are
  committed. After changing the public API of `crates/translatecore`, regenerate them with `tools/gen-bindings.sh`.

### Tests

```sh
cargo test --workspace
android/gradlew -p android :app:testDebugUnitTest :glass-gm:test
```

The unit tests run on the host. Behavior that needs a phone, Bluetooth or Glass is not covered by them.
`:glass-gm:test` includes a cross-check of the Glass codec against the MemoMind reference; it is skipped when the SDK is
not cloned (see [MemoMind Glass SDK](#memomind-glass-sdk)).

### Release build and signing

A release build is signed with your own key. Android accepts an update only when it is signed with the same key, so
keep the keystore and its passwords backed up somewhere off the build machine.

1. Create a keystore once. `keytool` ships with the JDK.
   ```sh
   keytool -genkeypair -v -keystore android/release.jks -alias voxwk \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Give Gradle the credentials in one of two ways. Both are read per value, and a value in the file wins over the
   environment variable.
   - A file `android/keystore.properties`, which is git-ignored:
     ```properties
     storeFile=release.jks
     storePassword=<keystore password>
     keyAlias=voxwk
     keyPassword=<key password>
     ```
     `storeFile` is relative to `android/`.
   - Environment variables `VOXWK_KEYSTORE_FILE`, `VOXWK_KEYSTORE_PASSWORD`, `VOXWK_KEY_ALIAS` and
     `VOXWK_KEY_PASSWORD`, for example in CI.
3. Raise `versionCode` (it must increase for every shipped build) and `versionName` in `android/app/build.gradle.kts`.
   Settings shows the version and the short git hash of the build under About.
4. Build and install:
   ```sh
   android/gradlew -p android assembleRelease
   adb install -r android/app/build/outputs/apk/release/voxwk.apk
   ```

When no signing configuration is found, the release build is not signed and the APK is named `voxwk-unsigned.apk`. Android
refuses to install it. A blank value counts as missing. If an install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
the installed app was signed with a different key; uninstall it first with `adb uninstall app.rly3h.voxwk`, which also
deletes its downloaded models and saved sessions.

## Usage

1. Open **Settings > Models** and download the models for the current settings. The app loads them in the background
   afterwards, and the microphone button shows the progress.
2. On the session screen, pick Listen or Conversation, choose the partner language, and tap the microphone.

### Listen

Listen is one way: speech in the partner language (or Auto) is translated into your language. Speech that is already in
your language is skipped. Translations are spoken only when a private output (wired or Bluetooth earphones, a USB
headset, a hearing aid) is connected; otherwise they are shown as text. The speaker icon next to the microphone mutes
spoken output. With the Glass, see [MemoMind Glass](#memomind-glass).

### Conversation

1. Choose the partner language and tap the microphone. A session starts with the partner's turn: their speech is
   translated into your language.
2. Press **Speaking** when you talk. A tap switches it on until the next tap; holding it keeps it on only while pressed.
   While it is on, your speech is translated into the partner's language.
3. The translation of the partner's speech plays on your earphones. The translation of your speech plays on the phone's
   speaker. Without earphones the partner's speech is shown as text only.
4. The two speaker icons beside the microphone switch spoken output on and off per direction: the left one for the
   translation spoken to your partner, the right one for the translation you hear. Each carries a language badge. The
   same two switches are in **Settings > Playback**.

Switching per utterance between the built-in speaker and the Glass was tested on one phone (offline). Switching between
the speaker and wired, USB or Bluetooth earphones was not tested on a device. Some phones may clip the start of an
utterance after a switch, or route Bluetooth differently than expected.

### Online mode

1. In **Settings > Online translation**, choose OpenAI Realtime or Gemini Live and save its API key.
2. Use **Test connection** to check it.
3. Switch the toggle beside the microphone to Online.

- Audio is sent to the chosen provider and billed to your key. The key is stored encrypted on the device.
- Online Conversation connects two sessions at once, one per direction, so the cost can double.
- **Pause to split a turn** in **Settings > Turn detection** sets how long a pause closes a turn online.

### MemoMind Glass

The Glass can be the microphone of a session and show its translations. It is optional; without it the app works as
described above.

**Setup**

1. Pair the Glass in Android's Bluetooth settings.
2. In **Settings > MemoMind Glass**, allow Bluetooth access, pick the Glass under Device and turn on **Use Glass**.
3. The official MemoMind app cannot use the Glass at the same time as voxwk. Force-stop it before a session. If it has
   notification access, Android restarts it within about a second and it takes the Glass again, so turn its
   notification access off first.
4. voxwk holds the Glass only while a session runs, and connects it when you tap the microphone. If the connection
   fails, the session does not start; tap the microphone again to retry.

**Listen**

- The Glass microphone is the input, and translations appear on the Glass display.
- With **Speak translations for me** on, the translation is also spoken on the Glass speaker, as Bluetooth call audio.
- The microphone is muted while the Glass plays, and the call audio is released after each utterance so that the
  microphone comes back.

**Conversation**

- The Glass microphone serves both directions, and **Speaking** still decides who talks.
- The partner's translation goes to the Glass, and the translation of your speech to the phone's speaker.
- With **Show what I say in Conversation** off (**Settings > MemoMind Glass**, under Display), the Glass shows only the
  partner's direction. Your speech and its translation appear only on the phone. The setting applies from the next
  session.

**Display**

- **Source and translation**, or **Translation only**. Translation only still shows the source while someone is
  speaking; the final line is the translation only.
- The Glass display is closed after 15 seconds without updates so that the Glass can dim. The next translation opens it
  again.

**Microphone**

- **Front microphone** or **All-around microphone**. Only the front microphone was tested.

**Screenshots**

- During a Glass session a screenshot button appears at the top right of the session screen. It saves what the Glass
  shows as a PNG in `Pictures/voxwk`.

**Speech per direction**

- **Settings > Playback** has **Speak translations for me** and **Speak translations for my partner**. In Conversation
  the session screen has one toggle per direction as well.
- The speech rate applies only to what you hear. What your partner hears always plays at normal speed.

#### Known limits

- The Glass cannot be used together with the official MemoMind app (see Setup).
- Speech during Glass playback is not translated: the microphone is muted, and the call-audio stream of the Glass does
  not carry usable speech.
- The start of an utterance is occasionally lost on the Glass microphone: 1 of 21 turns in one test run.
- Online speech always plays at normal speed, and the speech rate setting does not apply to it. Online mode with the
  Glass was not tested on a device.
- Turning the Glass off during a session ends the session. This was not tested on a device.
- The All-around microphone was not tested.
- If a second Bluetooth headset is connected for calls during a Glass session, voxwk treats it as the Glass.

### Which sessions run offline

| Session | Offline | OpenAI | Gemini |
| --- | --- | --- | --- |
| Listen from en, fr, de, it, es, ru, zh-hans or Auto | yes | yes | yes |
| Listen from zh-hant | no | yes | yes |
| Conversation with en, fr, de, it, es, ru or zh-hans | yes | yes | yes |
| Conversation with zh-hant | no | no | yes |

The offline recognizer, Nemotron 3.5, has no Traditional Chinese recognition, so a `zh-hant` speaker needs Online mode.
Translation into any of the nine languages works offline. Spoken output depends on the voices the phone's text-to-speech
engine has; without one the translation is shown as text.

## Models

Models are not part of the APK. The app downloads them on first use, from the URLs in
`android/app/src/main/assets/models.json`, and checks size and SHA-256 where the manifest gives them. Spoken output uses
the phone's own text-to-speech engine, so no speech synthesis model is used.

| ID | Model | Use | Download | License |
| --- | --- | --- | --- | --- |
| `vad` | [Silero VAD](https://github.com/snakers4/silero-vad) v6.2.3 | Detects speech for SenseVoice | ~2 MB | MIT |
| `asr` | [SenseVoice](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) int8, converted by sherpa-onnx | Japanese recognition option | ~163 MB | FunASR Model Open Source License |
| `asr_stream` | [Nemotron 3.5 ASR streaming 0.6B](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) int8, converted by sherpa-onnx | Default speech recognition | ~475 MB | OpenMDW-1.1 |
| `mt_plamo3_0_8b` | [PLaMo 3 Translate 0.8B](https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF) (GGUF Q4_0) | Translation (default) | ~548 MB | PLaMo Community License |
| `mt_plamo3_2b` | [PLaMo 3 Translate 2B](https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF) (GGUF Q4_0) | Translation (more accurate) | ~1.7 GB | PLaMo Community License |

- The default set is Nemotron plus PLaMo 0.8B. SenseVoice with the VAD, and PLaMo 2B, are optional downloads.
- Only the selected translation model is held in memory. The 0.8B model is small and fast; the 2B model is more
  accurate but needs more RAM.
- Licence names are taken from the model cards of the original models, and the Silero licence from its repository.
  The sherpa-onnx releases that host the converted SenseVoice and Nemotron files carry no licence of their own, so the
  terms of the original models apply to them. Check each licence before you redistribute a model or use one commercially.

### PLaMo

Built with PLaMo.

PLaMo 3 Translate is provided by Preferred Networks under the
[PLaMo Community License](https://plamo.preferredai.jp/info/plamo-community-license-en). According to the model card,
personal use and other non-commercial use, including academic use, are free of charge with no registration. Commercial
use is free after registering the intended use with Preferred Networks if the revenue or sales of the user and its
affiliates in the most recent fiscal year is 1 billion yen or less. Above that, a separate licence agreement is
required. The licence also requires anyone who provides the model or a derived model to a third party to include a copy
of the agreement and to state "Built with PLaMo" on related websites, user interfaces, blog posts, product pages or
product documentation. The app shows that notice in Settings.

## Repository layout

| Path | Contents |
| --- | --- |
| `android/` | Android app (Kotlin, Jetpack Compose, Gradle) and `glass-gm`, the MemoMind Glass codec shared with the PoC |
| `crates/` | Rust workspace members: the on-device speech and translation core |
| `docs/` | Architecture document: [docs/architecture.md](docs/architecture.md) |
| `tools/` | Binding generation script and the desktop translation evaluation tool |
| `poc/` | Throwaway experiments (for example the Glass Bluetooth protocol check); not part of the app build |
| `third_party/` | External checkouts that are not committed (see below) |

The Rust workspace is defined at the repository root, so `cargo test --workspace` runs the host tests from there.

## MemoMind Glass SDK

Glass support uses the public MemoMind Bluetooth protocol documents. The SDK is distributed under its own licence and is
not part of this repository. Building and using the app does not need it. The clone is needed only for the codec
cross-check test in `android/glass-gm`:

```sh
git clone https://github.com/memomind-open/plugin-open-platform.git third_party/plugin-open-platform
```

## License

The application is released under the MIT license. See [LICENSE](LICENSE). The models are separate works under the
licences listed above.
