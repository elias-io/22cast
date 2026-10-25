# Third-party notices

This distribution includes independently sourced open-source receiver code.
AirScreen's APK, code, graphics, backend, keys and branding are not included.

| Component | Version / source | License |
| --- | --- | --- |
| UxPlay receiver core | FDH2/UxPlay, commit in `native/vendor/UXPLAY_REVISION` | GPL-3.0; individual files retain their LGPL notices |
| PlayFair | Included in UxPlay `lib/playfair` | GPL; original file headers retained |
| llhttp | Included in UxPlay `lib/llhttp` | MIT; original file headers retained |
| Internal mdnsd | Included in UxPlay `lib/mdnsd` | Original file notices retained |
| libplist | 2.3.0, libimobiledevice/libplist | LGPL-2.1-or-later; COPYING files included |
| OpenSSL | 3.5.4 | Apache-2.0; license included |
| FFmpeg audio decoders | 8.0.1, bundled source archive | LGPL-2.1-or-later; LGPL-2.1 text included |
| SDL2, desktop only | System dependency | zlib license |
| FFmpeg, desktop only | System dependency | Consult the installed FFmpeg build's licensing |
| Kotlin standard library | 2.1.21, JetBrains | Apache-2.0; license included in app assets |
| JetBrains annotations | 13.0, JetBrains | Apache-2.0 |

The modified adapter, Android application, local launcher and interface are
22Cast source code licensed under GPL-3.0-or-later. Source for compiled receiver
components is included. OpenSSL source is reproducibly obtained by the build
script from the pinned release archive with its recorded SHA-256.

Changes to the vendored UxPlay core are documented in
`native/vendor/UXPLAY_PATCHES.md`.

