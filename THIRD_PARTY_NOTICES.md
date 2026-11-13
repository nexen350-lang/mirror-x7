# Third-Party Software Notices

This project's original code is licensed under the [Apache License 2.0](LICENSE).

Every runtime component shipped with the application is permissively licensed, and **Settings -> App info -> Open-source licenses** displays each component's full license and notice text from [`app/src/main/assets/open-source/notices.json`](app/src/main/assets/open-source/notices.json).

## Bundled and runtime components

| Component | Version | License | Notes |
|---|---|---|---|
| scrcpy server | 5.0.1 | Apache-2.0 | Official unmodified upstream server jar; SHA-256 verified in `BundledServerTest` |
| libadb-android | 3.1.1 (patched) | Apache-2.0 (selected from `GPL-3.0-or-later OR Apache-2.0`), plus retained BSD-3-Clause and MIT file notices | Local `:libadb` module; modifications documented in [`third_party/libadb/PATCHES.txt`](third_party/libadb/PATCHES.txt) |
| SPAKE2 | BoringSSL port | Apache-2.0 | `Spake2.java` in `:libadb`, ported from BoringSSL `spake25519.cc` |
| Conscrypt | 2.7.0 | Apache-2.0 (with BoringSSL and Chromium notices) | TLS 1.3 provider for ADB wireless debugging |
| Bouncy Castle (`bcprov`, `bcpkix`, `bcutil`) | 1.86 | Bouncy Castle License (MIT-style) | X.509 certificate generation for ADB identity |
| Android Jetpack & Jetpack Compose | Per [`runtime-artifacts.json`](third_party/runtime-artifacts.json) | Apache-2.0 | UI, lifecycle, activity, core, and graphics libraries |
| Kotlin standard library | 2.4.20 | Apache-2.0 (with NOTICE) | Runtime library |
| kotlinx.coroutines, kotlinx.serialization | 1.11.0 | Apache-2.0 | Concurrency and JSON serialization |
| JetBrains annotations, Guava ListenableFuture, JSpecify | 23.0.0, 1.0, 1.0.0 | Apache-2.0 | Transitive compile/runtime annotations and futures |
| ZXing Core | 3.5.3 | Apache-2.0 | QR code matrix generation for Wireless Debugging QR pairing |

## Verification and inventory

[`third_party/runtime-artifacts.json`](third_party/runtime-artifacts.json) records all resolved runtime code archives and their SHA-256 digests without local machine paths. `LegalDocumentsTest` verifies on every test run that:

1. Every artifact in `third_party/runtime-artifacts.json` is covered by an entry in `notices.json`.
2. Every license and notice text referenced by `notices.json` is packaged in the app's assets.
3. The bundled `scrcpy-server` entry version matches `ScrcpyServerVersion`.

To refresh `runtime-artifacts.json` after changing dependencies:

```bash
./gradlew -I tools/runtime-inventory.init.gradle :app:publicationRuntimeInventory
```

## Local `:libadb` module

[`third_party/libadb/`](third_party/libadb/) builds `libadb-android` 3.1.1 from source with local transport, flow-control, stream-EOF, `AdbTrustManager`, and BoringSSL-derived Java `Spake2` patches listed in [`third_party/libadb/PATCHES.txt`](third_party/libadb/PATCHES.txt). Each modified source file carries a modification notice alongside its upstream license header, and upstream notices are packaged under `open-source/libadb/`.

## Test-only dependencies

Test-only dependencies are never packaged in release or debug app builds: JUnit 4 (EPL-1.0), AndroidX Test / Compose UI Test (Apache-2.0), and `spake2-android` 2.2.1 (LGPL-3.0, used solely in `Spake2InteropTest` to verify protocol interoperability against the Java SPAKE2 implementation; see [`docs/CORRESPONDING_SOURCE.md`](docs/CORRESPONDING_SOURCE.md)).

## Trademarks and Non-Affiliation

- **scrcpy:** `scrcpy` is maintained by Genymobile and Romain Vimont. Under Section 6 of the Apache License 2.0, no trademark rights are granted by upstream licenses. Scrcpy Android is an independent open-source client and is not affiliated with, endorsed by, or sponsored by Genymobile or Romain Vimont.
- **Android:** Android is a trademark of Google LLC. Google Play and Google Play Protect are trademarks of Google LLC.

## Media Codecs and Patent Notice

This project neither implements nor distributes video or audio codec binaries (such as FFmpeg, x264, x265, or libfdk-aac). All H.264/AVC, H.265/HEVC, AV1, VP8, VP9, Opus, AAC, and FLAC encoding and decoding are performed by the operating system's platform `android.media.MediaCodec` APIs on the user's hardware. Patent licensing for platform codecs rests with the respective device manufacturers and OS distributors.

## Authorized Use and Audio Recording Compliance

Remote control, screen capture, microphone streaming, and call-audio capture (`voice-call` sources) may only be used on devices you own or have explicit legal authorization to administer. Recording or intercepting microphone or telephone audio without consent is restricted or prohibited in many jurisdictions (including all-party consent jurisdictions); users are solely responsible for obtaining all consents required by applicable law.

## Cryptographic Software Notice

This repository includes and links open-source cryptographic software used strictly for authenticated ADB transport and Android 11+ Wireless Debugging pairing:

- **SPAKE2 (`Spake2.java`):** Password-authenticated key exchange over Curve25519 (ported from BoringSSL).
- **Conscrypt (`org.conscrypt:conscrypt-android`):** TLS 1.3 transport security backed by BoringSSL.
- **Bouncy Castle (`org.bouncycastle:bcpkix-jdk15to18`):** RSA keypair and self-signed X.509 certificate generation for the client ADB identity.
