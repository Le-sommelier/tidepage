#!/usr/bin/env bash
# APK size gate. Usage: tools/perf/apk-size.sh <limit in MB> [apk]
#
# Fails when the release APK is larger than the limit, and also when the APK does not contain the
# native engine: an APK without libpdfa_ffi.so is small for the wrong reason, so it must never pass.
# The unsigned release APK is measured; signing adds a few kilobytes.
set -euo pipefail

LIMIT_MB="${1:?usage: apk-size.sh <limit in MB> [apk]}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ABI="arm64-v8a"

if [ $# -ge 2 ]; then
  APK="$2"
else
  APK="$(find "$ROOT/android/app/build/outputs/apk/release" -name '*.apk' -print -quit 2>/dev/null || true)"
fi
[ -n "${APK:-}" ] && [ -f "$APK" ] || { echo "apk-size: no release APK found (run :app:assembleRelease first)" >&2; exit 1; }

LISTING="$(unzip -l "$APK")"
SO_BYTES="$(awk -v f="lib/$ABI/libpdfa_ffi.so" '$4 == f { print $1 }' <<< "$LISTING")"
if [ -z "$SO_BYTES" ]; then
  echo "apk-size: $APK has no lib/$ABI/libpdfa_ffi.so (build it with scripts/build-android.sh)" >&2
  exit 1
fi

BYTES="$(wc -c < "$APK" | tr -d ' ')"
LIMIT_BYTES=$((LIMIT_MB * 1024 * 1024))

awk -v b="$BYTES" -v s="$SO_BYTES" -v l="$LIMIT_MB" 'BEGIN {
  printf "apk-size: %.2f MiB (limit %d MiB), of which libpdfa_ffi.so %.2f MiB\n", b / 1048576, l, s / 1048576
}'
if [ "$BYTES" -gt "$LIMIT_BYTES" ]; then
  echo "apk-size: FAILED, the APK is over the limit" >&2
  exit 1
fi
