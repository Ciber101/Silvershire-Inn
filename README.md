# Silvershire Inn

A private, self-hosted messenger for your own group of people: an Android app, a web app and a
small server you run yourself (on a PC at home, a mini PC, a Raspberry Pi class machine or a
rented server). Nothing goes through anyone else's servers.

Dark forged-iron look, one of a kind profile borders (dragons, weapons, shields, iron, gold...)
and the features you would expect from a modern messenger.

## Features

- **End-to-end encrypted** 1:1 and group chats, pictures, videos, voice messages and files
  (the server only relays and stores encrypted data)
- **Encryption at rest** on the server, with an optional master password, a recovery key and a
  "lock now" button
- **Voice and video calls** (WebRTC, with a built-in TURN relay for calls across networks)
- **Android app** with floating chat heads, notifications with quick reply, camera, photo editor,
  media viewer, voice notes, link previews, reactions, replies, read receipts, presence
- **Web app** served by the same server (works in any modern browser)
- **Profile borders**: a border generator draws a unique border from a seed; friends see it
  around your picture everywhere (chat lists, chat heads, calls, and set into the corner of
  each message bubble like a seal)
- **Notes**: a private chat with yourself, pinned at the top, encrypted to your own key and
  synced between your phone and the web app (can be turned off)
- **Photo editor** in the media viewer (app and web): crop, rotate, flip, filters, brightness,
  contrast, saturation, drawing and text, then send it in the chat or save it
- **Silvershire theme** (forged iron, dark stone) or the original **Classic** look
- **Panic phrase**: a typed or spoken phrase locks the account (and optionally the whole
  server) on every device until the unlock phrase is said
- **App updates** you publish yourself: everyone gets a message in the "Silvershire Updates" chat
  and installs the new version from it
- Branding: change the app name and icon for your group from the admin panel

## What you need

| To run the server | To build the Android app |
| --- | --- |
| Windows, Linux or macOS that stays on | Android Studio (includes Java 17), or the Android command-line tools + JDK 17 |
| [Node.js](https://nodejs.org) 18 or newer (LTS) | About 8 GB of free disk space for the first build |

## Download

- **Android app:** the newest APK is on the [Releases](https://github.com/Ciber101/Silvershire-Inn/releases/latest)
  page. It only works with a Silvershire Inn server, so set one up first (below). If you publish
  app updates to your own group, build the app yourself (see [docs/ANDROID.md](docs/ANDROID.md))
  so every update is signed with your key.
- **Server and web app:** this repository.

## Quick start

Download this repository (green **Code** button, then **Download ZIP**, and unzip it), or:

```bash
git clone https://github.com/Ciber101/Silvershire-Inn.git
cd Silvershire-Inn/server
npm install
node server.js          # or: start-server.bat (Windows) / ./start-server.sh (Linux, macOS)
```

On the first start the server creates a **server password** (people need it to create an
account) and an **admin password** (for the admin panel), prints them and saves them in
`server/data/FIRST-RUN-PASSWORDS.txt`. Change them on the admin Vault page
(http://localhost:8081/admin/vault) and delete that file.

Open **http://localhost:8080** for the web app, then build the Android app and share it.

**Full step by step guide: [docs/SETUP.md](docs/SETUP.md)**

## Documentation

| Guide | |
| --- | --- |
| [docs/SETUP.md](docs/SETUP.md) | Server, first start, passwords, web app, Android app, inviting people |
| [docs/INTERNET.md](docs/INTERNET.md) | Reaching your server from outside your home: Tailscale, or a domain with HTTPS (Caddy) |
| [docs/ANDROID.md](docs/ANDROID.md) | Building and signing the app, publishing updates |
| [docs/SECURITY.md](docs/SECURITY.md) | How the encryption works, the vault, backups, panic phrase |
| [docs/CUSTOMIZE.md](docs/CUSTOMIZE.md) | Branding, theme art, profile borders |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Common problems |

## Project layout

```
server/      Node.js server (server.js), web app (webclient.html), border drawing code (frames.js)
android/     Android app (Kotlin, Android Studio project)
tools/       Theme art generator and small helper scripts
docs/        Guides
```

## Support the project

If Silvershire Inn is useful to you, you can support its development on
[Ko-fi](https://ko-fi.com/ciberai) (also linked from the Support section in the app).

## License

Copyright (C) 2026 CiberAI

Silvershire Inn is free software: you can redistribute it and/or modify it under the terms of
the **GNU General Public License version 3** (or, at your option, any later version), as
published by the Free Software Foundation. See [LICENSE](LICENSE).

In short: anyone can use, change and share it, the credit stays, and anything built from it
must also be shared under the same license with its source code.

All artwork in this project (borders, dragons, theme pieces) is original and generated by the
code in this repository.
