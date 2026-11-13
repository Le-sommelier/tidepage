#!/usr/bin/env bash
# Build the pdfa-ffi cdylib for arm64-v8a, strip a copy, regenerate the Kotlin bindings, and copy
# both into android/engine (the isolated engine service).
# Requires the patched upstream clone at third_party/pdfcraft and cargo-ndk. When that clone already
# exists it is used as it is and the fetch script (which drives version control inside it) is skipped.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
# The NDK: ANDROID_NDK_HOME, else the newest one under the Android SDK (ANDROID_HOME, or the default macOS location).
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -n 1)}"
[ -d "$ANDROID_NDK_HOME" ] || { echo "no Android NDK found: set ANDROID_NDK_HOME" >&2; exit 1; }
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/target}"

ABI="arm64-v8a"
TRIPLE="aarch64-linux-android"
ENGINE="$ROOT/android/engine"
JNI_DIRS=("$ENGINE/src/main/jniLibs/$ABI")
KT_DIRS=("$ENGINE/src/main/java")
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pdfa-ffi-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if [ -d "$ROOT/third_party/pdfcraft" ]; then
  echo "using existing $ROOT/third_party/pdfcraft (fetch skipped)"
else
  "$ROOT/scripts/fetch-upstream.sh"
fi

cd "$ROOT"
cargo ndk -t "$ABI" build --release -p pdfa-ffi

SO="$CARGO_TARGET_DIR/$TRIPLE/release/libpdfa_ffi.so"
[ -f "$SO" ] || { echo "missing $SO" >&2; exit 1; }

# Bindings come from the unstripped library: uniffi reads its metadata from the symbol table.
cargo run -q -p pdfa-ffi --features cli --bin uniffi-bindgen -- \
  generate --library "$SO" --language kotlin --out-dir "$WORK/bindings"
KT="$(find "$WORK/bindings" -name pdfa_ffi.kt -print -quit)"
[ -n "$KT" ] || { echo "bindgen produced no pdfa_ffi.kt" >&2; exit 1; }

# Stripped copy: llvm-strip from the NDK matching this host.
NDK_BIN="$(ls -d "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin | head -n 1)"
cp "$SO" "$WORK/libpdfa_ffi.so"
"$NDK_BIN/llvm-strip" "$WORK/libpdfa_ffi.so"

for jni in "${JNI_DIRS[@]}"; do
  mkdir -p "$jni"
  cp "$WORK/libpdfa_ffi.so" "$jni/libpdfa_ffi.so"
done
for kt in "${KT_DIRS[@]}"; do
  mkdir -p "$kt/uniffi/pdfa_ffi"
  cp "$KT" "$kt/uniffi/pdfa_ffi/pdfa_ffi.kt"
done

echo "stripped $(wc -c < "$WORK/libpdfa_ffi.so") bytes -> ${JNI_DIRS[*]}"
echo "bindings -> ${KT_DIRS[*]} (uniffi/pdfa_ffi/pdfa_ffi.kt)"
