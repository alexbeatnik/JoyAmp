# JoyAmp

[![Release](https://github.com/alexbeatnik/JoyAmp/actions/workflows/release.yml/badge.svg)](https://github.com/alexbeatnik/JoyAmp/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

A local music player for keypad Android phones with a joystick / D-pad — built for the
**Rongyue E5** (Android 13, 320×480) and usable entirely without the touchscreen, including from the
**lock screen**. Dark amber UI, one screen, no accounts, no network.

Sibling app for audiobooks: **[JoyBook](https://github.com/alexbeatnik/JoyBook)**.

## Features

- Plays every audio file in one folder you pick (Storage Access Framework, recursive, natural sort:
  `2 - A` before `10 - B`); no storage permission needed
- One home screen: now-playing card (title / artist / cover art from tags, progress) + the library
- The joystick moves a highlight through the list one track per click; OK plays it
- **Lock-screen control** through an Accessibility service: switch tracks, pause **and resume**,
  seek — with a small on-screen pill confirming each press
- Foreground media service + MediaSession: headset buttons, Android media controls, pause on
  unplugging headphones, pause for calls / alarms and resume afterwards
- Shuffle, repeat all, optional keypad-only mode (touch ignored)

## Keys

| Key | In the app | On the lock screen |
|-----|------------|--------------------|
| Up / Down | Move the highlight (wraps) | Seek ±5 s (hold to scrub) |
| Left / Right | Previous / next track | Previous / next track |
| OK (center) | Play the highlighted track, or play / pause | Play / pause |
| `2` / `8` | Page up / down | — |
| `5` | Jump to the playing track | — |
| `#` or Menu | Library ↔ Settings | — |
| `*` | Left alone (flashlight on the E5) | — |

## Lock screen

Turn on **Settings → Lock-screen joystick** (Android Accessibility → JoyAmp). The stick is
intercepted only when **all** of these hold, otherwise it behaves normally:

- the lock screen is showing and the display is on;
- a JoyAmp session is open (a track is loaded, playing *or* paused);
- JoyAmp was the last app to take the audio, so it never fights JoyBook for the stick;
- no call, alarm or ringtone, and no app holding audio focus briefly;
- on a PIN-protected phone you haven't just typed digits / Menu (15 s grace for the PIN pad).

With the display **fully off** Android hands the stick to no app, so light up the lock screen
first (Power / Menu). The session ends with **✕** in the media controls or notification.

If only one of JoyAmp / [JoyBook](https://github.com/alexbeatnik/JoyBook) has its joystick service
enabled, that service also drives whatever the other app is playing through standard media keys
(Left / Right = previous / next, OK = play / pause), so the stick never ends up scrolling the lock
screen's media carousel. The home screen shows a warning while JoyAmp's own service is off.

The Accessibility service only looks at the five stick keys and never logs key presses.

Some Unisoc builds drop Accessibility services after an update, and force-stopping an app always
disables its service. JoyAmp can re-enable itself if you grant it permission to write secure
settings once:

```sh
adb shell pm grant com.local.joyamp android.permission.WRITE_SECURE_SETTINGS
```

## Install

1. Download `JoyAmp-x.y.z.apk` from [Releases](https://github.com/alexbeatnik/JoyAmp/releases) and
   install it (allow installing from unknown sources).
2. Open JoyAmp → `#` → **Music folder** and pick your music folder.
3. For lock-screen control: `#` → **Lock-screen joystick** → enable **JoyAmp**.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 34). Then:

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Push a tag such as `v1.2.0`, or run **Actions → Release → Run workflow** and enter the version.
The [release workflow](.github/workflows/release.yml) builds `assembleRelease`, derives the
version code from the version (`1.2.3` → `10203`) and publishes `JoyAmp-1.2.0.apk` plus
`SHA256SUMS.txt` as a GitHub release. Versions with a suffix (`1.2.0-beta1`) become pre-releases.

### Signing

Android installs an update only if it is signed with the same key as the installed copy, so add
these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|--------|-------|
| `SIGNING_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

Encode the keystore with `base64 -w0 release.jks` (Linux) or
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))` (PowerShell).
Without these secrets the workflow still publishes an APK, signed with a throwaway debug key
(fine for a fresh install, but it cannot update an existing one).

## License

Copyright 2026 Oleksii Poliakov

Licensed under the [Apache License, Version 2.0](LICENSE).
