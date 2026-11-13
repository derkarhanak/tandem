#!/usr/bin/env bash
# Installs Tandem for the current user: the window, the light-mode engine and its service.
# It does NOT switch off an existing Syncthing; see "Switching from Syncthing" in the README.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=..
ENGINE="$ROOT/engine-out/linux-amd64/syncthing"
[ -x "$ENGINE" ] || "$ROOT/scripts/build-engine.sh" linux
cargo build --release
install -Dm755 target/release/tandem "$HOME/.local/bin/tandem"
install -Dm755 "$ENGINE" "$HOME/.local/lib/tandem/syncthing"
install -Dm644 packaging/tandem-engine.service "$HOME/.config/systemd/user/tandem-engine.service"
install -Dm644 packaging/io.github.samsyk.Tandem.svg "$HOME/.local/share/icons/hicolor/scalable/apps/io.github.samsyk.Tandem.svg"
sed "s|^Exec=.*|Exec=$HOME/.local/bin/tandem|" packaging/io.github.samsyk.Tandem.desktop > "$HOME/.local/share/applications/io.github.samsyk.Tandem.desktop"
systemctl --user daemon-reload
gtk-update-icon-cache -qf -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
echo "Installed. Start the engine with: systemctl --user enable --now tandem-engine"
