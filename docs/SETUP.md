# Setting up Silvershire Inn

This guide takes you from nothing to a working server, the web app and the Android app on your
phones. It takes about 30 minutes, most of it waiting for the first Android build.

1. [Install Node.js](#1-install-nodejs)
2. [Get the code](#2-get-the-code)
3. [Start the server](#3-start-the-server)
4. [Passwords and the admin panel](#4-passwords-and-the-admin-panel)
5. [Use the web app](#5-use-the-web-app)
6. [Build the Android app](#6-build-the-android-app)
7. [Put the app on your server and invite people](#7-put-the-app-on-your-server-and-invite-people)
8. [Install and sign in on a phone](#8-install-and-sign-in-on-a-phone)
9. [Keep it running](#9-keep-it-running)

---

## 1. Install Node.js

Install the **LTS** version (18 or newer) from https://nodejs.org.

Check it in a terminal (Command Prompt / PowerShell on Windows, Terminal on Linux or macOS):

```bash
node --version
```

## 2. Get the code

On the GitHub page press **Code**, then **Download ZIP**, and unzip it somewhere permanent
(for example `C:\SilvershireInn` or `~/silvershire-inn`). Or with git:

```bash
git clone https://github.com/Ciber101/Silvershire-Inn.git
```

Install the server's two small dependencies:

```bash
cd Silvershire-Inn/server
npm install
```

## 3. Start the server

| Windows | Linux / macOS |
| --- | --- |
| Double-click `server/start-server.bat` | `cd server && ./start-server.sh` |

(`node server.js` in the `server` folder works everywhere too.)

Leave that window open: it *is* the server. You should see:

```
-------- Silvershire Inn server running on port 8080
   Admin GUI : http://localhost:8081/admin
```

The server uses these ports:

| Port | What | Who needs to reach it |
| --- | --- | --- |
| 8080 (TCP) | App, web app, uploads | Everyone who uses the chat |
| 8081 (TCP) | Admin panel | Only this computer (default) |
| 3478 (UDP + TCP), 49160-49200 (UDP) | Call relay (TURN) | Phones calling across different networks |

The first time, Windows asks whether Node.js may use the network: allow **private** networks
(and public ones if you will reach the server from outside without a VPN).

All data lives in `server/data/` (accounts, encrypted messages, pictures, keys). Back it up
(see [SECURITY.md](SECURITY.md#backups)).

## 4. Passwords and the admin panel

On the very first start the server makes two random passwords and prints them:

- **Server password**: people type it once when they create an account (it keeps strangers
  from signing up on your server).
- **Admin password**: opens the admin panel.

They are also saved in `server/data/FIRST-RUN-PASSWORDS.txt`.

1. Open **http://localhost:8081/admin/vault** on the server computer.
2. Change both passwords to ones you choose (the current one is needed; the admin password must
   be different from the server password).
3. Delete `FIRST-RUN-PASSWORDS.txt`.

Recommended on the same page:

- **Master password**: without it, the data key is protected by Windows (DPAPI) on Windows,
  and is only *stored* (not protected) on Linux / macOS. With a master password nothing can be
  read until it is entered after each start (on this page or in the server window).
- **Recovery key**: show it once and keep it somewhere safe (paper, password manager). It is the
  only way back in if the master password is forgotten or Windows is reinstalled.

The admin panel also shows connected people, lets you ban addresses, lock the server, and
publish app updates.

## 5. Use the web app

On the server computer open **http://localhost:8080**. From another device on the same Wi-Fi use
the server computer's local address, e.g. **http://192.168.1.50:8080** (find it with `ipconfig`
on Windows or `ip addr` / `ifconfig` on Linux and macOS).

- **New Account**: choose a username and an account password (8 characters or more), enter the
  server address and the server password.
- To use the **same account on the phone and the web**, create it on the phone, then on the phone
  go to Profile > **Export Identity Key** and paste it into the web login. Your encryption keys
  stay on your devices; the server never has them.

To use it from outside your home, see [INTERNET.md](INTERNET.md).

## 6. Build the Android app

See [ANDROID.md](ANDROID.md) for details. The short version:

1. Install **Android Studio** (https://developer.android.com/studio). It includes Java 17 and the
   Android SDK.
2. Open the `android` folder in Android Studio and wait for the first sync to finish.
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

Or from a terminal (with Java 17 and the Android SDK installed):

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

The app is created at `android/app/build/outputs/apk/debug/app-debug.apk`.

## 7. Put the app on your server and invite people

Copy the app to the server's download folder:

```
android/app/build/outputs/apk/debug/app-debug.apk  ->  server/data/download/silvershire-inn.apk
```

(`server/publish-update.bat` / `server/publish-update.sh` builds and copies it for you, and
restarts the server.)

Now anyone who can reach your server can open **http://your-server-address:8080/download** on
their phone and install the app. In the app, **Profile > Share App / Invite** shares that link: the
server tells the app its public address automatically (the address people reach it on). To set
it yourself, add `"publicUrl"` to `server/data/config.json` (see
[INTERNET.md](INTERNET.md#the-share-link)).

## 8. Install and sign in on a phone

1. Open the download link on the phone and install the APK. Android asks once to allow
   installing apps from your browser: allow it.
2. Open **Silvershire Inn** and enter the **server address**:
   - at home on the same Wi-Fi: `192.168.1.50:8080` (your server computer's address)
   - from anywhere: your public address, e.g. `https://chat.example.org` (see
     [INTERNET.md](INTERNET.md))
3. Enter the **server password**, then **Create Account** (new) or **Sign In** (an account made
   before).
4. Allow notifications (and "display over other apps" if you want floating chat heads).
5. Add friends by their username from the Contacts tab.

## 9. Keep it running

- Keep the server window open, or start it automatically when the computer starts:
  - **Windows**: Task Scheduler > Create Task > Trigger "At log on" > Action
    `server\start-server.bat`.
  - **Linux**: a systemd service, e.g.
    ```ini
    # /etc/systemd/system/silvershire.service
    [Unit]
    Description=Silvershire Inn
    After=network-online.target
    [Service]
    WorkingDirectory=/opt/silvershire-inn/server
    ExecStart=/usr/bin/node server.js
    Restart=always
    User=silvershire
    [Install]
    WantedBy=multi-user.target
    ```
    then `sudo systemctl enable --now silvershire`. (With a master password the server waits for
    it on the Vault page after each start.)
- Stop the computer from sleeping.
- When a new version of the app is ready, see [ANDROID.md](ANDROID.md#publishing-an-update).
