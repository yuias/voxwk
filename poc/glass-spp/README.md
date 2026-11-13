# Glass SPP PoC

Throwaway Android app for checking the MemoMind Glass Classic SPP protocol on real hardware before the voxwk design
is fixed. It is not part of the app build.

- `gm/`: pure-JVM GM frame codec, TLVs, stream splitting for the command and recording links, and request/response
  sessions over `InputStream`/`OutputStream`. Unit tests run on the desktop.
- `app/`: a single debug screen that opens the SPP sockets, sends commands, and records every byte to a log file.
- `tools/decode_recording.py`: decodes a recording-link capture to a 16 kHz WAV on the desktop.

The tests and the decoder use the MemoMind SDK clone in `third_party/plugin-open-platform/` (see the top-level
README). The codec cross-check test is skipped when the clone is absent.

## Build

Requires JDK 17+ and the Android SDK (`ANDROID_HOME`).

```sh
cd poc/glass-spp
./gradlew :gm:test assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## On the device

1. Pair the glasses in Android Bluetooth settings (and, for the concurrency check, connect them in the official App).
2. Open the PoC, grant the Nearby devices permission, select the glasses.
3. **SDP** logs the service UUIDs the phone knows for the device.
4. **Connect cmd** tries `00007033-…` and then the `00001101-…` fallback, secure then insecure RFCOMM, and logs each
   failure. **Connect rec** opens `00002024-…`.
5. **Heartbeat / Info / Status** send `01/01`, `01/02`, `01/06`.
6. **Set 01/2F** selects ENC/raw and the pickup mode; **Rec start / Rec stop** negotiate the record format, start,
   and stop the microphone stream.
7. The translation section sends `03/01`–`03/04` (or the conversation variants `03/05`–`03/08`) with editable source
   and translation text, either of which may be empty. The JSON toggle switches between raw UTF-8 and `\u` escapes.
   **Burst provisional** sends unacknowledged provisional updates at the given rate. `03/0C` selects the recording
   source.

Captures are written to the app's external files directory:

```sh
adb pull /sdcard/Android/data/app.rly3h.voxwk.poc.glass/files/glass-poc .
python3 tools/decode_recording.py glass-poc/rec-<time>.bin   # -> rec-<time>.wav; layout from the 52 13 reply
```

`wire-<time>.log` has one line per read or write: seconds since start, link (`cmd` / `rec`), direction (`>` sent,
`<` received), and hex bytes. Recording audio is stored in `rec-*.bin` instead of the log.
