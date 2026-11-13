> Only official unmodified scrcpy **5.0.1** is bundled. Server selection and the
> 4.1 fallback described in the historical comparison below have been removed.
> Historical server tokens migrate to 5.0.1; all audio sources are offered without version gates.
> Android 13 playback duplication recovery is Hear it here, not an older server.

# scrcpy Server Features & Protocol Support

Scrcpy Android bundles the official unmodified `scrcpy` **5.0.1** server and implements a native Android client using `MediaCodec`, Jetpack Compose, and `:libadb`. This document maps upstream `scrcpy` 5.0.x server changes and all 57 server options to their support in this client.

Desktop-client features do not carry over. The app decodes with Android's MediaCodec, draws
with Android views and speaks the ADB protocol itself, so a feature marked *desktop only* is
not missing from the app: it has no counterpart there.

| Disposition | Meaning |
|---|---|
| Existing | In the app before 3.0. |
| New in 3.0 | Added in this release. |
| Inherited | Server behavior the app gets by bundling the server; there is nothing to set. |
| Desktop only | Belongs to scrcpy's desktop client; there is nothing to port. |
| Internal | The app sets it itself; offering it would break the session or its protocol. |
| Deferred | Applicable, but not in 3.0, for the reason given. |

## Changes from 4.1 to 5.0

Upstream, 4.1 to 5.0 is 64 commits. Only two server source files changed in substance,
`AudioPlaybackCapture.java` and `CameraCapture.java`; everything else is the desktop client,
its build and its documentation. See the [README](../README.md#bundled-server-and-dependencies) for server verification details.

| 5.0 release-note item | Part of scrcpy | In this app |
|---|---|---|
| Add hardware decoding (#7057, #7001, #1894, #1672) | Desktop decoder | **Desktop only.** The app already decodes with MediaCodec, which uses the phone's hardware decoders, so there is no `--hwdec` to offer. |
| Improve video buffering (#7057) | Desktop renderer | **Desktop only.** The app keeps its own bounded queues and its Low latency and Synchronized modes. |
| Capture all audio usages in audio playback capture (#6975) | Server | **Inherited with 5.0, explained where it matters.** Playback capture now takes every kind of sound apps play, not media alone. The current server captures all usages; no media-only legacy server remains. |
| Fix voice capture configuration having no effect (part of #6975, not listed separately) | Server, same file | **Inherited with 5.0.** With the voice-call rule now in effect, Android 13 refuses 5.0's playback capture when the sound also keeps playing on the device. The session explains the refusal and offers **Hear it here**; duplication requires Android 14 or newer. |
| Use ANDROID_SERIAL in OTG mode (#7053) | Desktop OTG mode | **Desktop only.** The app has no OTG mode. |
| Build Windows ARM64 releases (#6857) | Desktop packaging | **Desktop only.** |
| Fix dropped keys in UHID for some keyboard layouts (#6983, #6990) | Desktop keyboard (SDL to UHID) | **Desktop only.** The app sends Android key codes with their meta state (`INJECT_KEYCODE`), not UHID reports. |
| Fix AltGr forwarding on some layouts (#7050, #7051) | Desktop keyboard (SDL on X11 and Wayland) | **Desktop only.** Android reports AltGr as `KEYCODE_ALT_RIGHT` with its meta state, and the app forwards both unchanged. |
| Fix --time-limit data race (#7029) | Desktop client | **Desktop only.** The app has no time limit. |
| Fix --no-mipmaps | Desktop renderer | **Desktop only.** |
| Avoid "camera capture failed" warnings on stop (#7018) | Server | **Inherited with 5.0.** Stopping a camera now stops its repeating capture first; 4.1 keeps the old behavior, which only adds a warning to the server log. Camera start, stop and reopen, including picture-in-picture round trips, run in the API 36 live suite (`CameraPipActivityTest`). |
| Generate mipmaps for all YV12 planes (#7034) | Desktop renderer | **Desktop only.** |
| Upgrade platform-tools (adb) to 37.0.1 | Desktop packaging | **Desktop only.** The app speaks ADB itself through its bundled libadb. |
| Upgrade FFmpeg to 9.0.2, SDL to 3.4.18 and dav1d to 1.5.4 | Desktop dependencies | **Desktop only.** None of them is in the app. AV1 plays where the phone has an AV1 decoder, and the app only offers codecs this phone can decode. |
| Various technical fixes | Mostly desktop | On the server side, only the two changes above. |

## Server options

The server accepts 57 options. Their `Options.java` has 60 `case` labels, three of
which (`local`, `fallback`, `hide`) are values of `display_ime_policy`. The server's version
is not an option but the first argument, and the app sends the version of the server it runs.

### Set from settings, session choices or links

Link names are the `scrcpy://connect` parameters described in the
[README](../README.md#connection-links); those marked 3.0 are new in this release.

| Option | Effect | Where in the app | Link |
|---|---|---|---|
| (version argument) | Which bundled server runs | **Removed in 3.4.0:** only scrcpy 5.0.1 may run; old tokens migrate | `server` (3.0) |
| `video_codec` | H.264, H.265, AV1, VP8 or VP9 | Settings, *Video codec*; **new in 3.0:** the session's Picture & sound | `codec` |
| `video_bit_rate`, `max_size`, `max_fps` | Picture quality | Settings, presets and device settings | `bitrate`, `maxSize`, `fps`, `preset` |
| `capture_orientation` | Locks the captured orientation | Settings, *Capture orientation* | `orientation` |
| `video_encoder` | A named video encoder | Session, *Choose the video encoder*; device settings | No: a link may not name an encoder |
| `video_source`, `camera_id`, `camera_facing`, `camera_size`, `camera_ar`, `camera_fps`, `camera_high_speed` | A camera instead of the screen | Session, *Choose a camera*; device settings | No |
| `new_display`, `flex_display`, `display_ime_policy` | A virtual display, the adaptive desktop, and its keyboard staying on that display | Device settings; session, *Choose the mirrored display* | No: a link may not create displays |
| `display_id` | Another existing display | Session, *Choose the mirrored display*; device settings | `displayId` |
| `audio` | Sound on or off | Settings | `audio=off` |
| `audio_codec` | Opus, AAC, FLAC or raw PCM | Settings, *Audio codec*; **new in 3.0:** the session's Picture & sound | `audioCodec` |
| `audio_source` | Device audio, playback capture or microphone | Settings, *Audio source* | `audio` |
| `audio_dup` | Keeps playback-captured sound on the device too | Settings | `audioDup` (3.0) |
| `audio_bit_rate` | Sound quality | Settings | `audioBitrate` (3.0) |
| `audio_encoder` | A named audio encoder | Session encoder choices; device settings | No |
| `stay_awake` | Keeps the device awake while plugged in | Settings | `stayAwake` |
| `keep_active` | Signals activity so the screen stays on, leaving nothing changed | Settings | `keepActive` (3.0) |
| `show_touches` | Shows touches on the device | Settings | `showTouches` |
| `power_off_on_close` | Turns the device screen off when the session ends | Settings | No |
| `clipboard_autosync` | Keeps the clipboards in step automatically | Settings | No |

Some scrcpy features are control messages rather than options, and the app has those too:
turning the device screen off (`SET_DISPLAY_POWER`: Settings, the session panel and the link's
`screenOff`), starting an app (`START_APP`: device settings and *Apps on device*), and camera
torch and zoom (`CAMERA_SET_TORCH`, `CAMERA_ZOOM_IN`, `CAMERA_ZOOM_OUT`: session controls).

### Set by the app itself

| Option | Why it is not a setting |
|---|---|
| `scid`, `tunnel_forward`, `log_level` | **Internal.** They name and reach this session's sockets and keep its server log readable. |
| `send_device_meta`, `send_frame_meta`, `send_dummy_byte`, `send_stream_meta`, `raw_stream` | **Internal.** The app's stream reader depends on this framing, so they stay at the server defaults. |
| `cleanup` | **Internal.** The server deletes its own jar once it starts; the app's list commands turn that off so the jar survives for the session that follows. |
| `list_encoders`, `list_displays`, `list_camera_sizes`, `list_apps` | **Internal.** The app's own lookups behind encoder, display and camera choices and *Apps on device*. `list_cameras` is covered by `list_camera_sizes`. |
| `video`, `control` | **Internal.** Always on: a session is a picture you can control. Sound-only and view-only sessions are not offered. |
| `power_on` | **Internal.** Left at the default, so connecting wakes the device. |
| `ignore_video_encoder_constraints`, `min_size_alignment`, `downsize_on_error` | **Internal, at the server defaults.** When an encoder refuses a size, the server applies the encoder's constraints and, failing that, a smaller size. That recovery is what keeps sessions working on limited encoders, such as the emulator's H.265 encoder, which ends up at 230 × 512. A switch could only let those sessions fail. |

### Not sent: parity candidates

| Option or feature | Decision |
|---|---|
| `angle` (rotate the picture by any angle) | **Deferred.** Orientation locks cover quarter turns, and any other angle needs its own touch-mapping and recording checks. |
| `crop` | **Deferred.** The options class accepts it, but nothing sets it. A setting needs a way to pick the rectangle and touch-mapping and rotation coverage for it; *Magnify this screen* zooms locally meanwhile. |
| `camera_zoom`, `camera_torch` at start | **Existing as session controls** (torch, zoom in and out). Start values are deferred: they would add saved settings for something the controls do once the camera runs. |
| `screen_off_timeout` | **Deferred.** It changes a system setting the server must later restore; keep awake, keep active and turning the screen off already cover the uses it serves. |
| Microphone variants (`mic-unprocessed`, `mic-camcorder`, `mic-voice-recognition`, `mic-voice-communication`, `voice-performance`) and call sources (`voice-call`, `voice-call-uplink`, `voice-call-downlink`) | **Supported (since v3.1.0).** All 11 scrcpy 5.0.1 audio sources are selectable in settings, mid-session controls, and `scrcpy://` links; privileged call-audio refusals fall back to video-only mirroring with an explicit notice. |
| `video_codec_options`, `audio_codec_options` | **Deferred.** Free-form encoder parameters can make a device's encoder fail in ways the app cannot explain, so raw server encoder options are not exposed. The options class keeps an unused video field. |
| `vd_destroy_content`, `vd_system_decorations` | **Deferred, defaults kept.** Apps on a virtual display close with it, and the display shows system decorations. Changing either needs live checks of launcher behavior on each Android version with the adaptive desktop. |
| UHID keyboard and gamepad input | **Deferred.** Only the message types exist; it needs HID descriptors and report mapping, and matters mainly for a physical keyboard attached to the controlling phone. |

## Feature area summary

| Area | Client support |
|---|---|
| Broader playback capture and duplication | Explained in Settings, Picture & sound, the launcher and the Android 13 refusal; historical releases were live-tested on Android 13 (API 33) and Android 16 (API 36). Android 14 and 15 targets were not available to test. |
| Camera shutdown fix | Inherited with 5.0 (above). |
| Video and audio codec and encoder choice | **New in 3.0:** Picture & sound shows what runs and what is chosen, offers the codecs this phone decodes, marks the ones the device's encoder list lacks, applies everything in one restart, warns that a recording ends, and offers the working choice back if the new one fails. Encoders keep their existing choosers. |
| Camera, torch and zoom, size, frame rate, high speed; displays and the adaptive desktop | Existing tools, audited above. Nothing was duplicated. |
| Sound quality, screen off, keep awake and keep active, orientation, recording | Existing settings. Links now also reach sound quality, keep active and keeping sound on the device. |
| Desktop hardware decoding, buffering, Windows ARM64 | Desktop only. |
| Desktop keyboard and UHID changes | Desktop only; the app's key injection does not involve them. |
| Socket framing, metadata, cleanup and transport | Internal. |

Session choices (the server, codecs, encoders, display and camera) last as long as the
session's screen, including rotation and picture-in-picture. If Android stops the app
altogether, the session restarts from the saved settings and the link that opened it.
Keeping session choices beyond that is deferred: it needs one design for every kind of choice.
