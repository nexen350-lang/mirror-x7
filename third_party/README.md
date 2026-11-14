# Third-Party Sources, Inventory, and Licenses

## Runtime notices packaged in the app

Authoritative license and notice texts shipped inside the APK live under [`app/src/main/assets/open-source/`](../app/src/main/assets/open-source/) and `third_party/libadb/src/main/assets/open-source/`, indexed by `notices.json` and displayed in **Settings -> App info -> Open-source licenses**. `LegalDocumentsTest` verifies that every runtime dependency in [`runtime-artifacts.json`](runtime-artifacts.json) has a matching notice entry, that every referenced license file exists in the app's assets, and that every modified `:libadb` file carries an Apache-2.0 Section 4(b) modification header matching `PATCHES.txt`.

- [`runtime-artifacts.json`](runtime-artifacts.json): resolved `localReleaseRuntimeClasspath` archive coordinates and SHA-256 digests. Regenerate with `./gradlew -I tools/runtime-inventory.init.gradle :app:publicationRuntimeInventory`.
- [`libadb/`](libadb/): locally maintained `libadb-android` 3.1.1 module, [`upstream-manifest.json`](libadb/upstream-manifest.json), upstream license texts, and [`PATCHES.txt`](libadb/PATCHES.txt).
- [Project license (`LICENSE`)](../LICENSE) and [third-party summary (`THIRD_PARTY_NOTICES.md`)](../THIRD_PARTY_NOTICES.md).

The sole bundled scrcpy server asset is official unmodified `scrcpy-server-v5.0.1`:

```text
764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536  scrcpy-server-v5.0.1
```
