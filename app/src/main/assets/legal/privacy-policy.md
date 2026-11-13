# Privacy policy for {{APP}}

Last updated: 9 October 2026

{{APP}} is published by {{DEVELOPER}}. It shows the screen of another Android device that you own or are allowed to use, lets you control it, and moves files and text between the two. This policy explains what the app handles, where that information goes, and how to contact the developer: {{CONTACT}}.

## The short version

- The app has no account, no advertising and no analytics. It sends nothing to the developer or to any server the developer runs.
- Everything the app handles passes only between this phone and the devices you connect it to, over your local network.
- What is kept, such as your saved devices and settings, stays in the app's own storage on this phone until you remove it.

## How the app connects

The app uses Android Debug Bridge (ADB), the connection Android offers for developers. You turn on wireless debugging, or ADB over the network, on the other device, and approve this phone there. The app then copies the open-source scrcpy server, which is part of the app, to that device and starts it. The server streams the screen and sound to this phone and carries out your touches and keys. Its file stays in the device's temporary folder until the device removes it.

Connections made through wireless debugging on Android 11 and newer are encrypted. Connections to ADB over the network on older devices, or when the device was set up with "adb tcpip", are not encrypted: use them only on a network you trust, and never make a device's ADB port reachable from the internet.

## What the app handles

- **Screen and sound.** The connected device's screen and, when you turn it on, its sound are streamed to this phone, shown and played, and kept only briefly in memory. The selected sound source may capture playback, the connected device's microphone, or call audio, where that device and Android allow it. Microphone and call sources capture sound from the connected device, not this phone. Use them only with authorization and any consent required by applicable law. Nothing is saved unless you take a screenshot or record.
- **Your input.** Touches, keys, mouse and keyboard input, and text you type are sent to the connected device to be carried out there.
- **Clipboard.** When "Sync clipboards automatically" is on for a device (it is by default), text copied on that device is placed on this phone's clipboard. This phone's clipboard is sent to the device only when you choose Paste, or press Ctrl+V on a keyboard attached to this phone. You can turn syncing off for each device. Live keyboard input and Draft Send also paste text through the connected device's clipboard, even when automatic clipboard syncing is off. This replaces its previous clipboard contents; the app does not restore them afterwards.
- **Screenshots and recordings.** A screenshot or recording is written to the app's private storage first. It is kept only if you save it to a place you choose or share it with another app, and the unsaved copy is deleted when you discard it; screenshot copies are also deleted after 24 hours. Where you save or share it is up to you, and that place handles it under its own terms.
- **Files and apps you send.** A file you send, or share to the app, is copied into the app's private storage and then to the connected device's Download folder. An app you install is copied there, installed and then deleted from the device's temporary folder. The copies on this phone are deleted when the transfer ends.
- **Saved devices and settings.** The names, addresses and ports of the devices you save, their settings, favourites and appearance, and your calibration are kept in the app's storage so you can reconnect. They are excluded from Android's cloud backup and device transfer; use the app's own export to move your saved devices.
- **Connection history.** For each saved device, the app keeps its last few connection problems and when it last connected, so it can help when connecting fails. This history stays on this phone and is never included in a backup.
- **The app's ADB identity.** The first time you connect, the app creates a key pair that identifies this phone to your devices. The private key never leaves the app's storage and is never included in a backup. Devices you approve keep the public key until you revoke the approval in their Developer options.
- **Pairing codes.** A wireless debugging pairing code is used once, to pair, and is not kept.
- **Finding devices.** To find a device's wireless debugging port, the app can look for ADB services advertised on your local network. It does not scan the internet.
- **Profile backups.** If you export your saved devices, the app writes a file to a place you choose. The file includes device names and addresses but no keys, and it is not encrypted, so keep it somewhere private.

## What the app does not do

- It does not collect, sell or share personal information, and it uses no advertising or analytics services.
- It does not ask for access to this phone's camera, microphone, contacts, location or photos. It saves and opens files only where you choose, through Android's file picker.
- It cannot see content that the connected device protects from screen capture.

## Your choices

- Remove a saved device, clear its connection history, or turn off clipboard syncing at any time in the app.
- Uninstalling the app, or clearing its storage, removes everything it kept on this phone.
- To stop this phone from connecting, revoke its approval on the other device in Developer options, under "Revoke USB debugging authorizations", or remove it from the paired devices in "Wireless debugging".

## Children

The app is a tool for controlling your own devices and is not directed at children.

## Changes and contact

If this policy changes, the new version will be published at the same address with a new date. Questions about privacy can be sent to {{DEVELOPER}}: {{CONTACT}}.
