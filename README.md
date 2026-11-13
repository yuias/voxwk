# voxwk

An Android app that translates speech on the device. Its main use is for Japanese speakers abroad: speech in other
languages is translated into Japanese offline, spoken aloud, and optionally shown on MemoMind Glass.

Status: early development. Nothing is buildable yet.

## Repository layout

The planned layout; directories are added as code lands.

| Path | Contents |
| --- | --- |
| `android/` | Android app (Kotlin, Jetpack Compose, Gradle) |
| `crates/` | Rust workspace members: the on-device speech/translation core and host-testable libraries |
| `docs/` | Design and architecture documents |
| `tools/` | Build and release scripts, and desktop evaluation tools |
| `poc/` | Throwaway experiments (for example the Glass Bluetooth protocol check); not part of the app build |
| `third_party/` | External checkouts that are not committed (see below) |

The Rust workspace is defined at the repository root, so `cargo test --workspace` will run the host tests from there.

## MemoMind Glass SDK

Glass support uses the public MemoMind Bluetooth protocol documents. The SDK is distributed under its own licence and is
not part of this repository. Clone it locally before working on Glass code:

```sh
git clone https://github.com/memomind-open/plugin-open-platform.git third_party/plugin-open-platform
```
