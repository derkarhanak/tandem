#!/usr/bin/env bash
# Builds the Syncthing engine.
#   build-engine.sh          → Android arm64, dropped into the app as a native library
#   build-engine.sh linux    → Linux x86_64, for the desktop app (engine-out/linux-amd64/syncthing)
set -euo pipefail
VERSION="${SYNCTHING_VERSION:-v2.1.6}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/27.2.12479018}"
TOOLS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libsyncthingnative.so"
export PATH="$HOME/.local/go/bin:$PATH" GOTOOLCHAIN=local

if [ ! -d "$ROOT/engine-src" ]; then
  git clone -q --depth 1 --branch "$VERSION" https://github.com/syncthing/syncthing.git "$ROOT/engine-src"
fi
cd "$ROOT/engine-src"
if [ "${1:-android}" = linux ]; then
  OUT="$ROOT/engine-out/linux-amd64/syncthing"
  mkdir -p "$(dirname "$OUT")"
  CGO_ENABLED=1 go run build.go -no-upgrade -build-out "$OUT" build syncthing
  strip "$OUT"
  ls -la "$OUT"
  exit 0
fi
mkdir -p "$(dirname "$OUT")"
# -checklinkname=0: github.com/wlynxg/anet (Android network interface lookup) links to net internals.
CGO_ENABLED=1 EXTRA_LDFLAGS="-checklinkname=0" go run build.go \
  -goos android -goarch arm64 -cc "$TOOLS/aarch64-linux-android26-clang" \
  -no-upgrade -build-out "$OUT" build syncthing
"$TOOLS/llvm-strip" "$OUT"
ls -la "$OUT"
