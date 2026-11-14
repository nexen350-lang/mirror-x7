# Changelog

## Unreleased

### Added
- Mirror window (iPhone Mirroring style): Rendered the connected device screen inside a centered, aspect-ratio-preserving rounded floating window on a black background with a thin subtle border, a slim top drag bar for repositioning, a bottom-right corner grip for proportional resizing, accurate touch mapping at any window size/position, per-device layout persistence (`MirrorWindowPreferences`), and the vertical `CompactNavigationBar` attached to the left side of the window while keeping the right-edge floating handle unchanged.
- Stage 6 (Onboarding & friendly errors): Added a 3-step first-run onboarding guide (`OnboardingDialog`) explaining Developer options, Wireless debugging, and QR/nearby pairing (also accessible from Settings and the Wireless debugging card), replaced technical connection errors with plain-language actionable guidance in English and Arabic, and moved raw technical details behind a collapsible `Details` button in `ConnectionStatus`.
- Stage 5 (One-tap experience): Added `QuickConnectDialog` defaulting to `Balanced` / `Most responsive` presets with collapsed-by-default advanced toggles, ensured saved devices connect with a single tap and record the last used device, and added an optional `Reconnect to last device on launch` setting (OFF by default) persisted in `AppUxPreferences`.
- Stage 4 (Faster connection startup): Added per-phase startup timing logs, in-process verified server asset caching, faster socket polling (`25 ms`), and on-device `sha256sum` verification with fail-closed fallback to `AdbSync.sha256` (`RECV`) so already-deployed servers skip re-pushing and downloading 734 KB on every reconnect without ever skipping SHA-256 verification.
- Stage 3 (QR pairing): Added short-lived QR code pairing (`WIFI:T:ADB;S:<name>;P:<password>;;`) using `com.google.zxing:core` (Apache-2.0, documented in `THIRD_PARTY_NOTICES.md` and `notices.json`), matching the advertised `_adb-tls-pairing._tcp` instance name with strict numeric IP validation, countdown timeout, and Cancel.
- Stage 2 (Auto-add after pairing): After pairing succeeds, the app discovers `_adb-tls-connect._tcp` on the paired numeric host and asks the user to confirm (`Add and connect to <name>?`) before saving and connecting, falling back to the Add device dialog with host pre-filled when not found.
- Stage 1 (Pairing discovery): `PairDialog` now discovers `_adb-tls-pairing._tcp` devices on the local network via NSD (reusing multicast-lock handling and strict numeric-address validation), lets users tap a discovered device to auto-fill IP and pairing port while keeping manual entry as fallback, and adds Arabic strings (`values-ar`).

## 3.5.7

### Fixed
- Links: a short link to a host with an underscore or a number in its last part (for example scrcpy://Galaxy_S21.lan:5555) opens instead of being refused as invalid.
- Links: a name or value containing a broken emoji half is refused instead of being shown with a made-up question mark.
- The link confirmation now also lists the scrcpy server version when the link sets one.
- Device names: a name made only of the invisible joiner character, or with one at either end or two in a row, is rejected; real emoji sequences still work.
- Saved devices are sorted with the language's own rules, so names starting with an accented letter no longer sort after Z.
- File sizes just under the next rounding step no longer show as 100.0 MB.
- Audio sync: a very long audio queue can no longer overflow the delay calculation.
- Updating home-screen shortcuts no longer runs on the main thread.
- Saving a setting or restoring a removed device can no longer leave the screen stuck busy after an unexpected error; a screenshot that fails unexpectedly shows an error instead of closing the app.
- Tests: the Android backup-rules test now covers the shortcut-trust and precision-pad exclusions.

## 3.5.6

### Fixed
- Hardware keyboard: a key held while the window loses focus (split screen, picture-in-picture, a dialog) is released on the device, so it can no longer stay pressed there. A mouse Back or Forward click is only swallowed when it will be forwarded; during a camera session or while the mirror is hidden it works locally again.
- Fingers held on the screen are no longer forgotten without being lifted when input is locked while the connection is down.
- A hostile or faulty device can no longer crash the app or stall transfers by announcing a huge, zero or negative packet size at connection.
- Pairing: the connection and the secure handshake have time limits, a stalled device no longer blocks pairing until the app restarts, and failed attempts no longer leak connections.
- Device editor: names with hidden or direction-changing characters are flagged before Save; the video encoder field no longer disappears while you delete its last character; the port field takes only ASCII digits.
- Controls panel: with the keyboard open in landscape the draft field and keys stay reachable; Keyboard, Stats, Torch and Audio tiles announce on/off instead of repeating their name; tile labels widen at large font sizes; calibration buttons meet the 48 dp touch size; the handle no longer jumps after the keyboard closes; keyboard and calibration panels close when a camera session starts; the handle announces expanded or collapsed and has separate open and close labels; stream rate units come from string resources.
- Android backup rules also exclude the precision-pad speed; the privacy policy and Play data-safety draft now say the app's data is excluded from Android backup.

## 3.5.5

### Fixed
- Automatic reconnect: the retry allowance is refilled once a connection has stayed up for 20 seconds, so a long session on flaky Wi-Fi no longer runs out of reconnects after six drops in total. A connection that drops straight away still backs off.
- A cancelled manual retry no longer leaves a stale state that could overwrite a live connection when the app is hidden.
- Recording: finishing a recording while an earlier one is being saved can no longer delete the file being copied or the new unsaved recording.
- The screen-off toggle no longer shows a stale state while reconnecting.
- Re-pair from the connection diagnosis opens the pairing dialog even when the device list screen had to be recreated.
- Pinned home-screen shortcuts refresh when any saved device is renamed or edited, not only the four shown as dynamic shortcuts; the number of dynamic shortcuts respects the launcher's limit.
- Host names with underscores (for example Galaxy_S21.lan) can be saved and imported.
- Several timing-sensitive tests wait for events instead of fixed short delays, so they no longer fail under a heavily loaded build machine.

## 3.5.4

### Fixed
- Recording: leaving the screen with a recording open no longer finishes the file on the main thread, which could freeze the app on a slow disk. A changed audio configuration before the file opens now asks the device for a key frame at once instead of waiting for the next natural one.
- Audio: a late format change during shutdown can no longer start a new audio track after the player has stopped.
- Installing an app bundle: the size limit now counts the bytes actually read and the full install set, so a small compressed bundle can no longer fill the phone's storage or push far more than the limit. Parts that would never be installed (on-demand modules) no longer make a bundle look uninstallable.
- Camera list errors show a clear message instead of raw internal text.
- Sending a file: a provider that reports a size of 0 or -1 now falls back to the file's own length, so the file can be sent.
- A stalled ADB write can no longer block the shared inactivity timer, so later app, camera, display and encoder loads keep their deadlines.
- A slow install no longer has its APK deleted from the device mid-read.
- Pairing: a successful pairing is no longer reported as a failure when the clean-up disconnect fails; the Pair button stays disabled after success so the single-use code is not sent twice; error messages come from string resources.
- Check connection: a dismissed check no longer reopens, and one device's steps no longer appear under another device's name.
- Removing a device from the menu or with TalkBack while a save is running is disabled and no longer reports success.
- Encoder dialog shows a saved encoder that the device no longer lists, so you can tell what will be used.
- App info no longer crashes if a bundled text file cannot be read; privacy-policy headings are announced as headings and the bullet is no longer read aloud; headings uppercase with the device's locale rules.

## 3.5.3

### Fixed
- Live keyboard: Backspace on the panel no longer moves the field's cursor to the start, so the next letter you type lands where you expect and the device text stays identical. In Draft mode it now trims the draft instead of deleting on the device.
- Live keyboard: Enter and Backspace queue behind text still on its way, so Enter can no longer reach the device before the text you just typed.
- Live keyboard: queued edits are cleared when the connection drops or input is locked, and are refused while no session is connected, so they no longer replay into the next session.
- Locking input while the keyboard panel is open no longer leaves a hardware keyboard sending keys nowhere.
- First connection: the time allowed for \"Allow USB debugging\" now covers the whole handshake, and the remaining setup gets a fresh allowance once you accept, so a slow tap no longer ends in a spurious timeout.
- Exporting a profile retries a failed write once and is not cancelled halfway, so an existing backup is not left truncated.
- Sharing into the app no longer accepts the app's own recording/screenshot links.
- Shortcut trust keeps one nonce for the whole run even if saving it fails, so published shortcuts do not fall back to the confirmation prompt.
- Device names with control, bidirectional-override, zero-width or blank-filler characters are rejected when saved or imported; joined emoji are still allowed.
- Leftover profile-transfer files in the cache are swept whenever a new transfer starts.

## 3.5.2

### Fixed
- Recording: a full disk no longer loses the whole file; recording stops with a clear message while the index can still be written, and the file is kept. Stopping a recording no longer stalls video. A recording still open when the app is cleared is kept in the app's Movies folder instead of being deleted.
- Video: a surface change during picture-in-picture, zoom or a size change no longer switches low-latency decoding off for the rest of the session.
- Importing a profile can no longer crash the app on an unexpected error; backup files that start with a byte-order mark now import; dismissed storage warnings no longer return on every settings save.
- Files shared into the app are now capped at 1 GiB in total, oldest copies removed first.
- Connection diagnostics redact Bearer and Basic tokens and multi-word passwords, and no longer hide harmless values such as exit codes.
- Apps, cameras, displays and encoders dialogs no longer flash "none found" before loading, and a failed refresh keeps the list you already had. Encoder names wrap to two lines so similar names stay distinguishable.
- TalkBack announces each connection-check step and its result.

## 3.5.1

### Fixed
- Keyboard panel: closing and reopening it (locking input, compacting the controls) reset Draft mode back to Live, hiding a draft you had kept. The Draft/Live choice is now kept for the session; draft text stays in memory only, never in saved state.

### Tests
- Instrumented UI tests that type a draft now pick Draft mode explicitly, since Live is the default. 33 keyboard/compact/release UI tests pass on the emulator. Compose test sources compile again after the older-server removal.

## 3.5.0

### Added
- Connection health: a failed connection now explains the cause and next step, with Retry, Re-pair, wireless-debugging help, Wi-Fi settings and a redacted Copy details action. A saved device's menu has Check connection, which tests network, ADB authorization and the bundled server in order and stops at the first failure.

### Fixed
- A server that rejects an option or exits now fails the connection at once instead of waiting 15 seconds.
- A handshake closed early is retried as a transient failure; audio permission errors no longer send you to ADB re-authorization.
- Unexpected errors in audio playback and the video/audio readers are reported instead of freezing or crashing the app.
- Closing a session no longer skips later resources when one fails to close.
- Live keyboard: key state and clipboard sequence numbers are thread-safe while the app is backgrounded.
- The first touch after zoom, pan or a size change is no longer dropped.
- The pairing code accepts only ASCII digits; link labels made of invisible characters are refused.
- Draft text and the pairing code are no longer saved in instance state. The favorite star ignores taps while a save is running; the control handle's drag state no longer sticks.
- Sent-file reporting now says the media scan was requested, not that the device indexed it.

### Security
- Device addresses and preferences are excluded from cloud backup and device transfer.
- Launcher shortcuts connect without confirmation only when they carry this install's private token; other callers get the link confirmation.
- ADB library diagnostics use Android logging instead of standard output.
- Packaging now stores the bundled scrcpy 5.0.1 server uncompressed.

## 3.4.0

- Bundle only the official unmodified scrcpy 5.0.1 server; remove the 4.1 asset, server selectors and older-server retry actions.
- Migrate historical 4.1/5.0 settings, saved-device profiles, backups and links to 5.0.1 without discarding unrelated preferences. Unknown server tokens remain rejected.
- Offer all 11 audio sources without version gates. Android 13 playback duplication still requires Hear it here or disabling Keep audio playing on the device; duplication requires Android 14 or newer.
- Picture & sound retains video/audio codec selection, reconnect and revert without a server selector.



## 3.3.2 (versionCode 77) — 2026-10-14

### Updated
- Bundled default scrcpy server to the official unmodified 5.0.1 release, verified against the maintainer-signed SHA256SUMS. The 4.1 fallback remains available.
- Server registry, integrity tests, UI labels and open-source notices now identify 5.0.1. Saved V5_0 settings and legacy server=5.0 links select the patch upgrade.
- Upstream 5.0.1 fixes desktop hardware decoding; this Android client retains its existing decoder and unchanged server protocol.

## 3.3.1 (versionCode 76) — 2026-10-09

### Added
- App display name and Play title changed to Scrcpy Android; package ID remains unchanged pending publication review.

### Fixed
- Bundled and web privacy policies now disclose remote microphone/call audio and clipboard replacement by Live typing and Draft Send.

### Tests
- Regression coverage for the new disclosures and matching bundled/web policy text.

## 3.3.0 (versionCode 75) — 2026-10-08

### Added
- Keyboard key row: Tab, Esc, arrows, Home, End, Select all, Delete word, and Resync (start the live field afresh after the device's text changed elsewhere). Keys queue behind live edits, so they act on the text you see.
- Sound source switch in the mirror's controls: change the source for this session without leaving the mirror; the saved device keeps its own.
- Live stats line (Stats tile): frames per second, 95th-percentile delay, and video bitrate.

### Improved
- Automatic reconnect tries 6 times over about a minute (1, 2, 4, 8, 15, 30 s) instead of 3 over 7 s, and tries at once when the network returns instead of waiting out the delay.
- On Android 11, a session that starts while the device is locked now says "the other device must be unlocked; unlock it, then tap Retry audio", from the server's own explanation, instead of "The target could not enable audio."

### Fixed
- Live keyboard: Latin text was held back as "composing" by the phone keyboard's correction mark, so the device received partial words (`one two t two three`). Only scripts built from parts (Hangul, kana, pinyin) are held back now.
- Lint: closed-resource false positives annotated, remote-only `/sdcard` path, API-30 decoder flag guarded, dialogs sized from the window instead of the screen, two unused strings removed. 0 errors.

## 3.2.0 (versionCode 74) — 2026-10-08

### Added
- Live keyboard. The keyboard panel now types on the device as you type: each key, Backspace, the IME's autocorrect and suggestion picks, and edits in the middle of the text reach the device immediately. Text the IME is still composing (Hangul, pinyin) is sent when it commits. Text is pasted, one paste at a time and only after the device acknowledges the previous one, and queued keystrokes are merged into a single trip, so the device's text stays identical to the field. Enter is the device's Enter. The previous compose-then-Send mode stays as Draft.
- Connection links accept the new audio sources (`audio=mic-camcorder`, ...); the link prompt names them.

### Tests
- LiveTypingTest: keystrokes, deletions, autocorrect, mid-text edits, code points (emoji, Hangul), held-back composition.
- AudioSourceTest: every source has a link token equal to its server value; a link can ask for one.

## 3.1.1 (versionCode 73) — 2026-10-08

### Fixed
- A first connection to a device that had not yet allowed this phone failed with "ADB authorization — failed after 10 s" while the user was still accepting the prompt, so every new device needed Reconnect. The handshake now waits up to 60 seconds once the device shows "Allow debugging?", and stays bounded when nobody answers.

### Improved
- The status line says "Allow debugging on <device>: tap Allow on that device's screen" as soon as the prompt appears, instead of a silent wait.

### Tests
- UserAuthorisationTimeoutTest (libadb): a fake adbd that refuses the signature and accepts the key late; the extension applies only once the prompt shows, and is bounded.

## 3.1.0 (versionCode 72) — 2026-10-08

### Added
- Eight more audio sources, offered when the device runs scrcpy 5.0: microphone unprocessed, for video, for speech recognition, for calls, for live performance, and phone call (both sides, your side, other side). The 4.1 server's settings say where to find them; a saved choice stays visible after switching servers.
- Phone-call sources are marked as usually reserved for system apps. When a device refuses one, mirroring continues without sound and says why, instead of the generic "could not enable audio".

### Tests
- AudioSourceTest: every source name is checked against both bundled server binaries; 5.0-only offering; command line; settings round trip.

## 3.0.0

Carries both scrcpy servers, 5.0 and 4.1, unmodified, and lets each device choose which it runs, in its [settings](README.md#per-device-mirroring-settings) beside the other connection choices. They differ in sound: 5.0's playback capture takes every sound apps play, 4.1's takes media alone, and 4.1's is the capture Android 13 lets keep playing on the device too, so the notice for Android 13's refusal now also offers **Use scrcpy 4.1** for that session. **Picture & sound**, a new [session tool](README.md#interface), switches the video codec, the sound codec and the server for that session together with one reconnect: it offers the codecs this phone decodes, marks those the device's encoder list lacks, names a recording a switch would end, saves nothing, and if the new choice fails, the failure screen offers the one that worked. [Connection links](README.md#connection-links) can now also ask for the server, the sound quality, keep-active and keeping sound on the device, and refuse a link whose options contradict each other. Profile backups are now version 13, carrying the server choice; earlier versions of the app cannot import them. A [feature account](docs/SCRCPY_FEATURES.md) gives every scrcpy 5.0 change and server option a disposition in this app.

## 2.17.1

Fixes sound from Android 13 devices with the scrcpy 5.0 server. With [Playback capture](README.md#audio-source) and **Keep audio playing on the device**, an Android 13 device sent no sound at all: the 5.0 server's capture names voice calls, and Android 13 lets only holders of a permission its shell lacks capture those while they keep playing, so it refused the whole capture, where 4.1 asked for media alone and never met that check. The server stays upstream's, unmodified, so the app reads the device's Android version when that combination is asked for, says why there is no sound instead of only that it failed, and offers **Hear it here**, which reconnects the session with the sound moved here and leaves the saved setting as it was; Android 14 and newer keep the sound in both places. The setting's help and the README now say what each mode takes: without keeping sound on the device, every sound apps play comes here and stops playing there; keeping it there, Android shares only media, games and calls.

## 2.17.0

Upgrades the bundled scrcpy server from 4.1 to [5.0](README.md#bundled-server-and-dependencies). The new server was checked three ways before it went in: its SHA-256 against the release's `SHA256SUMS.txt`, that file's signature against the maintainer's key, which also signed 4.1, and its dex against 4.1's, class by class, where every difference matched upstream's source changes. The protocol the app speaks is unchanged. [Playback capture](README.md#audio-source) now takes every sound apps play, games and notifications included, where 4.1 took media only; a live test that records a game sound in that mode failed on 4.1 and passes on 5.0. With **Keep audio playing on the device**, Android 13 devices refused the 5.0 server's capture and sent no sound, which 2.17.1 fixes. The build now declares the server's digest beside its version, and a test ties both, and the version App info's licenses name, to one release record, so a server replaced without its digest can no longer fail every connection's integrity check unnoticed.

## 2.16.0

Prepares the app for Google Play. Wireless-debugging pairing now uses the app's own SPAKE2, ported from BoringSSL's (Apache-2.0), instead of an LGPL library with native code, so the app ships no copyleft code; it pairs with Android's adbd as before. TLS connections accept only the kind of certificate adbd presents rather than any certificate, and the app no longer carries Bouncy Castle code it never used, including a helper that trusted every certificate. App info shows the app's [privacy policy](README.md#privacy-policy) and the full [open-source licenses](README.md#open-source-licenses) of everything it includes, and names the developer when a Google Play build sets one. A connected session in the background is now a `connectedDevice` foreground service, the type Google Play names for a continuous connection to an external device, so it no longer loses its hold after Android 15's six-hour daily limit for data sync. The setup help says that a connection made with `adb tcpip` is not encrypted. A Google Play bundle refuses to build without its upload key and the developer's name, contact and privacy-policy address, and `tools/verify-play-bundle.ps1` checks a bundle before upload.

## 2.15.0

Installs [app bundles](README.md#app-bundles): `.apks`, `.xapk` and `.apkm` files, and zips of split APKs, are read on this phone and offered like a single app. The device gets only the files it needs for its Android version, processor, screen density and languages, installed together in one session of its package manager, and a bundle it cannot run is refused before anything is sent.

## 2.14.0

Adds [connection history](README.md#connection-history): for each saved device this phone remembers its last few connection problems and when it last connected, so a failure says whether it keeps happening, and the device's menu shows the history. It also fixes five connection and input issues: a network stall during a two-finger gesture ended the session for good, Ctrl+V on a keyboard pasted the device's own clipboard although clipboards were synced, a setting refused from a link or a session's controls was blamed on the saved settings, a file's notification said "Connected" before anything had, and a retry named the wrong port.

## 2.13.0

Lets a card be [swiped away](README.md#saved-devices), with **Undo** in place of a question first, and gives each saved device a look of its own choosing: one of eight colours, and its initials or a phone, tablet, TV or watch. It also fixes three lifecycle and input issues: a mirror screen Android rebuilt in the background reconnected to the first device it had shown and could queue a sent file again, a mouse's Back button on Android 13 and newer also asked to end the session, and an old storage message opened every later session.

## 2.12.0

UI refinement: the device list opens on the devices under one large title, each device has its own initials and colour, **Connect** is the one filled button on a card, an empty list offers Add and Pair where it explains them while **+** in the bar adds more, settings open as a full-height sheet with Save above the form and **App info** last, form fields sit together, and the controls panel's tiles fill its width on a grid under their headings, at any screen density.

## 2.11.0

Adds [App info and uninstalling](README.md#app-info-and-uninstalling): each app in **Apps** has a menu that opens the device's own App info page, or its own question before uninstalling an app the user installed. The app list keeps whole names at large text sizes, with **System** as a tag rather than appended to the name, can be refreshed for changes made on the device, and opening an app returns the panel to the compact bar. A device card's settings label now opens that device's settings instead of only looking like a button.

## 2.10.0

Adds [installing apps](README.md#installing-apps): **Install app** in the device's app list, or sending or sharing an `.apk`, shows the app as this phone reads it, installs it with the device's own package manager, says why in plain words when the device refuses, and offers to open it. The screen stays usable while the device installs, for any question it asks. It also fixes a failure worded "Could not reach" after the device had answered, an earlier share's error left over a new share, and a device list that offered search before any device was saved and focused it on Android 8.

## 2.9.0

Adds [recording with sound](README.md#recording-with-sound): an AAC session's sound goes into the MP4 beside the picture, lined up by the device's own timestamps, and a session on other sound asks before reconnecting with AAC. An indicator shows a running recording even with the controls hidden, and the save question says how long a recording is and whether it has sound.

## 2.8.0

Reworks **More controls**: touching the screen around it minimizes it to the compact bar without touching the device, it opens on anything that needs attention, and its tiles are grouped by use with the Input Lock paragraph gone. It also fixes a recording lost when Android ends the app behind the Save picker, a shared file skipped when a device is picked while it is still copying, settings dialogs that open scrolled on Android 8 or offer Save for a draft they mark invalid, two session questions left on screen, and panel layout/focus edge cases.

## 2.7.0

Adds a [precision pad](README.md#precision-pad): slide anywhere to move a pointer over the remote screen, and tap to click or hold to drag at its tip, so a small target is never covered by the finger aiming at it.

## 2.6.0

Adds a [connection checkup](README.md#connection-checkup): a connection that fails or takes a while shows which step it reached, how long each took and one next action. It also fixes including saved-device edits from a stale screen, links shared by several profiles and stalled file transfers.

## 2.5.1

Reliability fixes: lost connections now recover no matter which stream notices first, an unanswered address fails within the connection timeout, and sharing, clipboard, notification and Input Lock edge cases are fixed.

## 2.5.0

Distinguishes refused connections from unresponsive devices, pins remote Back/Home/Recents above More controls, and adds **App info** to main Settings.

## 2.4.2

Corrects per-packet ADB flow control, bounds incoming transport queues and finishes empty-packet, cancellation and buffered-EOF handling.

## 2.4.1

Fixes reproduced ADB stream-open/EOF races and stale multi-touch input after a capture rotation. The pinned libadb 3.1.1 source is built locally with a [documented maintenance patch](third_party/libadb/PATCHES.txt); pairing, cryptography and the scrcpy server are unchanged.

## 2.4.0

Adds [Input Lock](README.md#input-lock) for watching without accidental remote input.

## 2.3.2

Prevents a process crash when a connection is stopped or replaced before Android has acknowledged its newly requested foreground service.

## 2.3.1

Preserves an explicit End session across recording-offer recreation: it does not reconnect, and completing Save or Discard still closes that ended session.

## 2.3.0

Adds [camera picture-in-picture](README.md#camera-picture-in-picture) and [recent quality history](README.md#recent-quality), plus cancellation, diagnostic and target-state fixes. The local APK retains the v2.2.1 signing identity.
