# Tandem

Simple, friendly folder sync between your phone and your computers, directly and with no cloud in between. There's an **Android app** and a light **desktop app for Linux** (Windows planned).

Tandem uses [Syncthing](https://syncthing.net)'s sync engine underneath, so it works with any Syncthing device. Everything you see is new: one plain-language status, folders and devices as cards, and pairing by QR code.

- **One status at a glance**: *Everything's in sync*, *Syncing 12 files*, *Waiting for Wi-Fi*, *Your devices are offline*.
- **Requests as cards**: when another device wants to pair or share a folder, accept it with one click and choose where it goes.
- **Pairing by QR code**: the computer shows its code, the phone scans it (or copy and paste the code).
- **Folders**: progress, size, pause/resume, check for changes, share with another device, stop syncing (your files are kept).
- **Devices**: connected or last seen, direct or through a relay, rename, remove.

## Download

Get the latest version from [**Releases**](https://github.com/derkarhanak/tandem/releases/latest):

- **Android**: `tandem-<version>-android-arm64.apk`. Open it on your phone and allow installing from your browser or file manager when asked. Needs Android 8.0+ on a 64-bit (arm64) phone.
- **Linux**: `tandem-<version>-linux-x86_64.tar.gz`. Extract it and run `./install.sh` (no sudo). It installs into `~/.local` and starts syncing in the background. Needs a 64-bit PC with glibc 2.35+ (Ubuntu 22.04, Debian 12, Fedora 36 or newer) and systemd. `./install.sh --remove` uninstalls it and keeps your folders.

Then pair them: on the computer, open Tandem → **+** to show its code; on the phone, open Tandem → **Add** → **Pair a device** → **Scan its code**.

## Android app (`app/`)

- **Sync rules**: only on Wi-Fi, only while charging, pause in Battery Saver, start when the phone turns on.
- **Runs in the background** as a foreground service with a *Turn off* button in its notification, and restarts the engine if it crashes.
- **Engine log** in Settings for troubleshooting.

The engine is cross-compiled for Android (arm64) and shipped inside the APK as `libsyncthingnative.so`, so Android allows it to be executed. The app runs it as a child process and talks to it only through its REST API on `127.0.0.1`, with a random API key. The engine's web interface is password-protected so other apps on the phone can't control it.

```bash
./scripts/build-engine.sh        # clones Syncthing v2.1.6 and builds the engine for Android
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs Go 1.26+, the Android SDK (platform 37), NDK r27 and a JDK 17+ that includes `javac`. `build-engine.sh` expects Go in `~/.local/go` (or on `PATH`) and the NDK in `~/Android/Sdk/ndk/27.2.12479018`; set `ANDROID_NDK_HOME` or `SYNCTHING_VERSION` to change them.

## Desktop app (`desktop/`, Rust + Slint)

Built to be light:

- **The window** is a small Rust app (~14 MB on disk, about **24 MB of memory** while open). Closing it frees everything; nothing stays in the tray.
- **The engine** runs as its own background service, `tandem-engine`, in **light mode**. Measured on a real 243-file folder, both freshly started: **64 MB vs 103 MB** for standard Syncthing, with the same idle CPU. Light mode means:
  - one process instead of two (systemd restarts the engine instead of Syncthing's separate watchdog process)
  - `GOMEMLIMIT=40MiB GOGC=50`, so unused memory is handed back sooner (tighter values made CPU use jump, so they're not used)
  - one hashing thread, one folder at a time, no progress events

### Install

```bash
./scripts/build-engine.sh linux    # builds the engine for Linux x86_64
./desktop/install.sh               # builds the app; installs app, engine, service and launcher
```

Needs Rust 1.85+ and Go 1.26+. No `-devel` packages are needed (fontconfig is loaded at runtime).

To make release files, run `./gradlew assembleRelease` (signing key read from `~/.android/tandem-release.properties`) and build the desktop app inside an older distro (e.g. `rust:1-bookworm`) so it runs on glibc 2.35+. Then run `./scripts/package-release.sh <that binary>`.

### Switching from Syncthing

Tandem uses the same settings folder as a regular Syncthing install (`~/.local/state/syncthing`), so it keeps your identity, folders and paired devices. Nothing needs re-pairing.

```bash
systemctl --user disable --now syncthing.service
systemctl --user enable --now tandem-engine.service
```

To go back, run the same two commands the other way round. Once everything works, the distribution package can be removed (`sudo dnf remove syncthing`).

## Layout

- `app/src/main/java/.../engine/`: Android engine supervisor (`Engine`), background service and sync rules (`SyncService`), REST client (`SyncthingApi`), state and changes (`Repo`)
- `app/src/main/java/.../ui/`: the Jetpack Compose screens
- `desktop/src/`: `engine.rs` (finding and controlling the engine service), `api.rs` (REST client and snapshot), `main.rs` (window wiring and presentation)
- `desktop/ui/app.slint`: the desktop interface
- `desktop/packaging/`: the engine service, launcher and icon
- `scripts/build-engine.sh`: builds the Syncthing engine for Android or Linux

## Status

Early.

- **Android**: tested on a Motorola Edge 50 Pro (Android 16): pairing, accepting folders, two-way sync, deletions, pause/resume and turning sync on and off.
- **Desktop**: tested on Fedora 45 (GNOME, Wayland): taking over an existing Syncthing setup and two-way sync with a phone.
- **Not yet tested**: long periods in the background, Windows.
