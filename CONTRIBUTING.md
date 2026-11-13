# Contributing

Contributions are welcome under the [Apache License 2.0](LICENSE). Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in this repository is licensed under Apache-2.0 without additional terms.

## Development setup

Use **JDK 17**, **Android SDK 36**, and the checked-in Gradle wrapper:

```bash
./gradlew :app:testDebugUnitTest :libadb:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On Windows PowerShell, use `.\gradlew.bat` in place of `./gradlew`.

## Guidelines

- Keep the client protocol aligned with the bundled unmodified scrcpy `5.0.1` server.
- Preserve saved-device profiles, migration compatibility, ADB key identity, and audio/video synchronization behavior.
- Add or update unit tests for protocol, storage, session, and UI state changes. Check UI changes at compact window sizes, 2x font scale, and both orientations.
- Do not run debug instrumentation against the minified APK; run instrumentation against `debug` and smoke-test `localRelease` separately.
- Never commit generated APKs/AABs, `local.properties`, signing keys, ADB keys, exported device profiles, or unredacted device logs.
- If dependencies or bundled assets change, regenerate the runtime inventory (`./gradlew -I tools/runtime-inventory.init.gradle :app:publicationRuntimeInventory`) and update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `app/src/main/assets/open-source/notices.json` so `LegalDocumentsTest` passes.

## Reporting issues

Use synthetic device names and RFC 1918 / documentation addresses (such as `192.168.1.20`) in bug reports. Never attach pairing codes, ADB private keys, signing credentials, or private screen/clipboard captures. For security-sensitive issues, follow [SECURITY.md](SECURITY.md).
