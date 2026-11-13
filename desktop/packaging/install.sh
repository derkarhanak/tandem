#!/usr/bin/env bash
# Tandem for Linux — installs for the current user only (no sudo).
#   ./install.sh            install and start syncing
#   ./install.sh --remove   uninstall (your folders and sync settings are kept)
set -euo pipefail
cd "$(dirname "$0")"
BIN="$HOME/.local/bin"; LIB="$HOME/.local/lib/tandem"; UNIT="$HOME/.config/systemd/user/tandem-engine.service"
APPS="$HOME/.local/share/applications"; ICON="$HOME/.local/share/icons/hicolor/scalable/apps/io.github.samsyk.Tandem.svg"

if [ "${1:-}" = "--remove" ]; then
  systemctl --user disable --now tandem-engine.service 2>/dev/null || true
  rm -f "$BIN/tandem" "$LIB/syncthing" "$UNIT" "$APPS/io.github.samsyk.Tandem.desktop" "$ICON"
  rmdir "$LIB" 2>/dev/null || true
  systemctl --user daemon-reload
  echo "Tandem removed. Your synced folders and settings (~/.local/state/syncthing) were left in place."
  exit 0
fi

install -Dm755 tandem "$BIN/tandem"
install -Dm755 syncthing "$LIB/syncthing"
install -Dm644 tandem-engine.service "$UNIT"
install -Dm644 io.github.samsyk.Tandem.svg "$ICON"
mkdir -p "$APPS"
sed "s|^Exec=.*|Exec=$BIN/tandem|" io.github.samsyk.Tandem.desktop > "$APPS/io.github.samsyk.Tandem.desktop"
systemctl --user daemon-reload
gtk-update-icon-cache -qf -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

# Upgrading an existing Tandem: restart its engine on the new version.
if systemctl --user is-active --quiet tandem-engine.service; then
  systemctl --user restart tandem-engine.service
  echo "Tandem updated. Its engine was restarted on the new version."
# Never run two engines on the same identity: if Syncthing is already running, let the user switch on purpose.
elif systemctl --user is-active --quiet syncthing.service 2>/dev/null || pgrep -x syncthing >/dev/null; then
  cat <<'MSG'
Tandem is installed, but Syncthing is already running on this computer, so Tandem's engine was NOT started.
Tandem uses the same settings, so your folders and paired devices carry over. To switch:

  systemctl --user disable --now syncthing.service
  systemctl --user enable --now tandem-engine.service

(If you started Syncthing another way, quit it first.) To switch back, run the same commands the other way round.
MSG
else
  systemctl --user enable --now tandem-engine.service
  echo "Tandem is installed and syncing in the background. Open \"Tandem\" from your apps."
fi
