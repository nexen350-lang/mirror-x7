# Historical LGPL Dependency Notes (Resolved in v2.16.0)

Builds from **v2.16.0** onward ship **no LGPL or GPL runtime code**.

Prior to v2.16.0, wireless-debugging pairing pulled in `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1` (LGPL-3.0, wrapping native `spake2-c` under LGPL-3.0-or-later / LGPL-2.1 / Apache-2.0 / MIT). In v2.16.0, that dependency was replaced by `Spake2.java` in `:libadb` — a pure Java port of BoringSSL's `spake25519.cc` under Apache-2.0.

`spake2-android` remains solely as an `androidTestImplementation` dependency for `Spake2InteropTest` to verify interoperability in both SPAKE2 roles on emulators. Test APKs are not distributed, and `tools/verify-play-bundle.ps1` verifies that `libspake2.so` is absent from release bundles.

## Reference commits for pre-2.16.0 historical builds

| Upstream repository | Commit |
|---|---|
| `MuntashirAkon/libadb-android` (3.1.1) | `c849886ebc6d48e7b46d967e78a6bb65c90c3b74` |
| `MuntashirAkon/spake2-java` (2.2.1) | `7615ddd680b990e14513ebb66eac4cb0dbf82464` |
| `spake2-c` submodule | `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31` |
