# Tandem

A simple, friendly Android app for keeping folders in sync between your phone and your other devices, directly and with no cloud in between.

Tandem uses [Syncthing](https://syncthing.net)'s sync engine underneath, so it works with Syncthing on your computer. Everything you see is new: one plain-language status, folders and devices as cards, and pairing by QR code.

- **One status at a glance**: *Everything's in sync*, *Syncing 12 files*, *Waiting for Wi-Fi*, *Your devices are offline*.
- **Requests as cards**: when your computer wants to pair or share a folder, accept it with one tap and choose where it goes on the phone.
- **Pairing by QR code**: scan your computer's code, or show the phone's code.
- **Folders**: progress, size, pause/resume, check for changes, share with another device, stop syncing (your files are kept).
- **Sync rules**: only on Wi-Fi, only while charging, pause in Battery Saver, start when the phone turns on.
- **Runs in the background** as a foreground service with a *Turn off* button in its notification, and restarts the engine if it crashes.
- **Engine log** in Settings for troubleshooting.

## How it works

The Syncthing engine is cross-compiled for Android (arm64) and shipped inside the APK as `libsyncthingnative.so`, so Android allows it to be executed. The app runs it as a child process with its own data folder and talks to it only through its REST API on `127.0.0.1`, using a random API key. The engine's web interface is password-protected so other apps on the phone can't control it.

## Requirements

- Android 8.0 or newer, arm64
- To build: Go 1.26+, Android SDK (platform 37), Android NDK r27, and a JDK 17+ that includes `javac`

## Build

```bash
./scripts/build-engine.sh        # clones Syncthing v2.1.6 and builds the engine for Android
./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`build-engine.sh` expects Go in `~/.local/go` (or on `PATH`) and the NDK in `~/Android/Sdk/ndk/27.2.12479018`; set `ANDROID_NDK_HOME` or `SYNCTHING_VERSION` to change them.

## Layout

- `app/src/main/java/.../engine/`: starting and supervising the engine (`Engine`), the background service and sync rules (`SyncService`), the REST client (`SyncthingApi`) and reading state / making changes (`Repo`)
- `app/src/main/java/.../ui/`: the Jetpack Compose screens (home, pairing, folders, devices, settings, onboarding)
- `scripts/build-engine.sh`: builds the Syncthing engine for Android

## Status

Early. Tested on a Motorola Edge 50 Pro (Android 16): pairing, accepting folders, two-way sync, deletions, pause/resume and turning sync on and off. Not yet tested over long periods in the background.
