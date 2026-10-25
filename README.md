# 22Cast

An independent Android TV AirPlay receiver with a quiet, remote-friendly interface.
The TV interface is written in Kotlin using native Android views and dialogs.
The Android APK contains no WebView, HTML interface or JavaScript bridge.

Launch the app, join the same local network from your iPhone/iPad/Mac, and select
**22Cast Living Room** in **Control Center → Screen Mirroring**.

## Features

- Centered casting mark and the receiver's advertised name.
- Bottom menu: How to connect, Network, Settings, About.
- Persistent device name, 720p/1080p, 30/60 fps, optional PIN, audio and screen-awake settings.
- PIN mode requires a new on-screen code for each connection; sender trust is not saved.
- Network status, restart, pause/resume and disconnect controls.
- Casting controls hide after five seconds of inactivity and reappear with remote keys or touch.
- Local pairing, FairPlay transport key handling, H.264 mirroring, AAC-ELD/AAC/ALAC audio.
- ARMv7 and ARM64 Android libraries. No root, account or cloud key service.
- A separate desktop browser interface controls the local native desktop receiver.
- Anonymous startup update recommendations from `https://22cast.elias.dev/api/version`.

## Android TV

Install `dist/22Cast.apk` on Android 7.0 or later. The application includes the TV
launcher category, accepts D-pad input, and also runs on landscape Android devices.

```sh
adb install -r dist/22Cast.apk
adb shell am start -n dev.elias.cast22/.MainActivity
```

The public APK uses a dedicated distribution signing certificate. Keep its private
signing key for future upgrades. The private key and owner-only signing settings
are kept locally under `.native-deps/` and are excluded from downloads.
Back up `release.keystore` and `release-signing.json` securely before deleting local
build caches. Building from the source archive creates a development signing key;
it cannot replace the official signed application.

The distribution package ID is `dev.elias.cast22`. It installs separately from the
earlier `com.twentytwo.cast` development build and starts with fresh settings.

## Run locally on macOS

Prerequisites: Xcode command-line tools, Python 3.9+, CMake, pkg-config, OpenSSL 3,
FFmpeg development libraries and SDL2. With Homebrew:

```sh
brew install cmake pkg-config openssl@3 ffmpeg sdl2
./run-local
```

Open **http://127.0.0.1:2244**. Keep that process running and choose the displayed
receiver name from your Apple device. Video plays in an aspect-correct native SDL
window; sound plays through the computer. Closing the video window or pressing
Escape disconnects the sender and returns the receiver to its ready state.

`python3 local.py --headless` runs the real protocol server without video/audio
output, for protocol testing. It is not the normal playback mode.

The settings and receiver identity stay on the local machine. The control UI binds
only to loopback; AirPlay discovery and media ports are available on the LAN.
No external fonts, analytics or advertising are used by 22Cast. At Android app
startup, one HTTPS request checks the public release endpoint. It sends no receiver
identity, installed-version number or stream content, and fails silently offline.
Casting stays on the local network. Cloudflare delivers the website, downloads and
release endpoint; see the website's short privacy policy for connection processing.
Discovery prefers an active multicast-capable LAN over point-to-point VPN routes;
the Network screen displays the address used by discovery.

## Build Android

Requirements: JDK 17+, Python 3.9+, CMake 3.22+, Make, Perl, Android SDK platform 36,
build-tools 36.1.0 and Android NDK 28.2.13676358. Set `ANDROID_HOME` to the SDK.

```sh
./build-android
```

For an official release after building the native libraries:

```sh
python3 scripts/package-android.py --release
```

This uses the local owner-only `.native-deps/release-signing.json` configuration.
Signing passwords are passed to the signing tool through its environment, never
included in command arguments or the source archive. The standard build and Android
Studio path use a development signing identity.

The first run downloads the pinned OpenSSL 3.5.4 source archive and verifies its
SHA-256 before building it for both Android architectures. The FFmpeg 8.0.1 audio
decoder source archive and other receiver sources are included under `native/vendor`.
The script builds the native receiver, compiles Kotlin and Java, packages with
aapt2/D8, aligns native libraries to 16 KiB and signs the installable APK.
The build downloads pinned Kotlin 2.1.21 compiler/runtime JARs from public Maven
Central and verifies their recorded SHA-256 values. The only additional Android
runtime is Kotlin's standard library (plus its annotation definitions).

The Gradle project can also be imported into Android Studio. Build the native
libraries first with `./build-android`; Gradle packages `native/prebuilt-jni`.
`local.properties`, if present, is machine-specific and should not be committed.

## Architecture

| Layer | Android TV | Local desktop |
| --- | --- | --- |
| Interface | Kotlin activity and native Android views | Separate HTML interface in local browser |
| Receiver | UxPlay through NDK/JNI | UxPlay native executable |
| H.264 video | MediaCodec and Surface | FFmpeg and SDL2 |
| AAC / ALAC audio | FFmpeg → PCM → AudioTrack | FFmpeg → PCM → SDL2 |

`MainActivity.kt` owns the native TV interface, remote focus, settings, PIN prompt
and playback overlay. The existing Java service and MediaCodec adapter own
reception and playback through JNI. The desktop launcher serves
`desktop/index.html` and supervises the native SDL receiver. Desktop HTML is not
packaged into the Android application.

## Validation

```sh
python3 local.py --headless
# In another terminal; optional development dependency:
python3 -m pip install cryptography
python3 tests/protocol_smoke.py
python3 tests/receiver_smoke.py
# macOS, with the local receiver running:
python3 tests/discovery_smoke.py
```

The smoke test connects to the actual native receiver and checks the advertised
identity, Ed25519 pairing, X25519 secret agreement, authenticated encrypted
pair-verification and FairPlay setup. It does not use AirScreen's servers.

The second test starts an isolated native receiver, completes SRP PIN setup and
AES-GCM identity authentication, rejects wrong PINs and malformed requests, and
checks availability after abrupt TCP disconnects. It leaves the normal receiver
and its settings alone.

For a selected development emulator, after building the APK:

```sh
python3 tests/android_smoke.py --serial emulator-5580 --adb-port 5038
```

Use your emulator's actual serial and ADB server port (normally 5037). This builds
and installs a separate test APK, requires FFmpeg with libx264, and refuses physical
devices. It checks the installed receiver's PIN protocol, native view hierarchy,
D-pad Settings controls and saving, five-second casting-control timeout and
key/touch wake-up, portrait/landscape output, video queue recovery and a real
decoder error/reset.
Screenshots are saved in `tests/output/validation`. Injected H.264 frames bypass
AirPlay transport; these checks do not prove Apple-to-TV playback or audible audio.

Physical iPhone-to-TCL playback still needs device testing. Wi-Fi multicast,
decoder implementations and iOS versions can affect compatibility. Emulator
networking usually does not expose mDNS to a physical phone.

For manual acceptance, install the APK on the TV, connect an Apple sender to the
same LAN, select the advertised name, and check video, audible sound and sync.
Also try rotation, enabling PIN, disconnect/reconnect and a longer streaming session.

The initial release targets ordinary screen mirroring and audio. It does not
advertise HLS URL handoff, HEVC, multiroom AirPlay 2 or DRM-protected movie playback.

## Source and licenses

22Cast is distributed under GPL-3.0-or-later to match the included UxPlay/PlayFair
components. Upstream notices are retained in the vendor files. See
`THIRD_PARTY_NOTICES.md` and `native/vendor/uxplay/LICENSE`.
Local receiver-core changes are listed in `native/vendor/UXPLAY_PATCHES.md`.

`python3 scripts/package-source.py` creates `dist/22Cast-source.zip` with source,
pinned vendor inputs and the two production JNI libraries. It excludes caches,
test output, local settings and all signing/receiver keys. The APK is separate.

AirPlay is a trademark of Apple Inc. 22Cast is not affiliated with Apple or AirScreen.
