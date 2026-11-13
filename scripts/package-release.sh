#!/usr/bin/env bash
# Assembles release files into dist/:
#   tandem-<ver>-android-arm64.apk          (signed release build)
#   tandem-<ver>-linux-x86_64.tar.gz        (desktop app + light engine + installer)
# Usage: package-release.sh <portable-desktop-binary>
#   Build that binary on an older distro (e.g. the rust:1-bookworm container) so it runs on glibc 2.35+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER=$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/desktop/Cargo.toml" | head -1)
DESKTOP_BIN="${1:?path to the portable desktop binary}"
DIST="$ROOT/dist"; mkdir -p "$DIST"

cp "$ROOT/app/build/outputs/apk/release/app-release.apk" "$DIST/tandem-$VER-android-arm64.apk"

NAME="tandem-$VER-linux-x86_64"; STAGE=$(mktemp -d); mkdir "$STAGE/$NAME"
install -m755 "$DESKTOP_BIN" "$STAGE/$NAME/tandem"
install -m755 "$ROOT/engine-out/linux-amd64/syncthing" "$STAGE/$NAME/syncthing"
install -m755 "$ROOT/desktop/packaging/install.sh" "$STAGE/$NAME/install.sh"
install -m644 "$ROOT/desktop/packaging/tandem-engine.service" "$ROOT/desktop/packaging/io.github.samsyk.Tandem.desktop" \
  "$ROOT/desktop/packaging/io.github.samsyk.Tandem.svg" "$STAGE/$NAME/"
tar -C "$STAGE" -czf "$DIST/$NAME.tar.gz" "$NAME"
rm -r "$STAGE"
(cd "$DIST" && sha256sum "tandem-$VER-android-arm64.apk" "$NAME.tar.gz" > SHA256SUMS)
ls -la "$DIST"; cat "$DIST/SHA256SUMS"
