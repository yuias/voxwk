#!/bin/sh
# Regenerates the committed UniFFI Kotlin bindings. Every exported function also
# exists on the host (host stubs), so the host library yields the same bindings
# as the Android build. Run from anywhere; paths are resolved from this script.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

case "$(uname -s)" in
  Darwin) ext=dylib ;;
  *) ext=so ;;
esac

cargo build -p translatecore
cargo run --quiet -p translatecore --bin uniffi-bindgen -- generate \
  --library "target/debug/libtranslatecore.$ext" \
  --language kotlin \
  --no-format \
  --out-dir android/app/src/main/java
