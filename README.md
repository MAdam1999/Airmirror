<div align="center">

# 📺 AirMirror TV

**Mirror your iPhone, iPad or Mac to any Android TV or TV box. Screen and sound, no app needed on the Apple device.**

![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![AirPlay](https://img.shields.io/badge/AirPlay-Screen%20Mirroring-000000?logo=apple&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

</div>

<p align="center"><img src="docs/screenshot.png" alt="AirMirror TV home screen" width="800"></p>

---

## ✨ Features

| | |
|---|---|
| 🖥️ **Full-screen mirroring** | Hardware H.264 decoding, letterboxed to the right shape, handles rotation |
| 🔊 **Sound** | AAC-ELD, AAC and ALAC audio through the TV |
| ✏️ **Rename your receiver** | Press OK on the remote to change the name your iPhone shows |
| 📡 **Zero setup on iPhone** | Shows up as a receiver in Control Center → Screen Mirroring |
| 🔒 **PIN lock** | Settings → *Require a PIN on the iPhone*: phones must type your 4–8 digit PIN to mirror |
| 📦 **Small** | About 2.2 MB, no Google services needed |
| 🕰️ **Old boxes welcome** | Works on Android 5.0 (Lollipop) and newer |

## 🚀 Quick start

1. **Install** the APK for your TV's Android version (see below):
   - [`AirMirrorTV-android5.apk`](AirMirrorTV-android5.apk): Android 5.0 – 7.1 (works on newer too)
   - [`AirMirrorTV-android8.apk`](AirMirrorTV-android8.apk): Android 8.0 and newer
2. Put the TV box and the iPhone on the **same Wi‑Fi network**.
3. Open **AirMirror TV** on the box. It shows the receiver name.
4. On the iPhone: **Control Center → Screen Mirroring →** pick that name.
5. To stop: tap **Stop Mirroring** on the iPhone, or press Back on the remote.

### Installing the APK

```bash
adb connect <tv-box-ip>
adb install -r AirMirrorTV-android8.apk   # or AirMirrorTV-android5.apk on Android 5–7
```

Or copy the APK to a USB stick, open it on the TV and allow *Install unknown apps*.

### Renaming the receiver

On the home screen press **OK** on the remote (or tap) on **Rename**, type a name and press Done.

<p align="center"><img src="docs/rename.png" alt="Rename dialog" width="700"></p>

## 🧩 How it works

```
iPhone ──mDNS──▶ discovery ──▶ pairing ──▶ FairPlay handshake ──▶ encrypted stream
                                                                       │
                       SurfaceView ◀── MediaCodec H.264 ◀── decrypt ◀──┤
                       AudioTrack  ◀── MediaCodec AAC/ALAC ◀───────────┘
```

| Module | Role |
|---|---|
| `airplay/` | AirPlay receiver protocol, based on [serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT), patched for Android 5 and converted to Kotlin |
| `app/` | Android TV app: `MainActivity`, `VideoDecoder`, `AudioDecoder`, `MirrorPlayback` |

## 🛠️ Build

Written in Kotlin. Needs JDK 17, Android SDK 34 and Gradle 8.7 (the Kotlin 2.0.20 plugin is fetched by Gradle).

```bash
gradle assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
```

This builds two APKs: `app/build/outputs/apk/android5/release/` (Android 5.0+) and
`app/build/outputs/apk/android8/release/` (Android 8.0+).

### PIN lock

In **Settings → Who can connect**, pick **Require a PIN on the iPhone** and choose 4–8 digits.
When an iPhone starts mirroring (or plays audio to the TV), it shows an *AirPlay password* box and
must type that PIN; it can remember it. After 5 wrong tries the connection is dropped. Casting a
video from an app's own AirPlay button doesn't use the PIN, so it still asks Allow/Deny on the TV.

## ⚠️ Limits

- The app must be **open on the TV** to receive.
- Casting a video with an app's own AirPlay button (YouTube and similar) is turned off. Screen Mirroring works with every app.
- Music-only AirPlay uses ALAC and needs a box with an ALAC decoder.

## 🐞 Troubleshooting

| Problem | Try |
|---|---|
| Receiver not in the list | Same Wi‑Fi? Router *client isolation* off? App open on the TV? |
| Stuck on "connecting" | Restart the app, then mirror again |
| Black screen | Stop mirroring, reopen the app, mirror again, and open an issue with `adb logcat` output |

## 📄 License

Copyright (c) 2026 mbnetworku. Released under the [MIT License](LICENSE).
The `airplay/` module is derived from serezhka/java-airplay (MIT), whose notice is kept in
[`airplay/LICENSE-java-airplay`](airplay/LICENSE-java-airplay).
