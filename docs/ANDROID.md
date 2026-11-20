# The Android app

The app is a normal Android Studio project in `android/` (Kotlin, minimum Android 7.0, API 24).

## Building

### With Android Studio (easiest)

1. Install Android Studio: https://developer.android.com/studio
2. **File > Open** and pick the `android` folder. The first sync downloads Gradle and the
   libraries (a few minutes).
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. The APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

### From a terminal

You need **JDK 17** (Android Studio's bundled one works) and the Android SDK.

```bash
cd android
./gradlew assembleDebug      # Windows: gradlew.bat assembleDebug
```

If Gradle can't find the SDK, create `android/local.properties` with:

```
sdk.dir=/path/to/Android/Sdk          # Windows: sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

If you have a newer Java as default, point `JAVA_HOME` at Android Studio's `jbr` folder first.

## Signing (important for updates)

Android only installs an update over an app signed with the **same key**. Debug builds are signed
with the debug key of the computer that built them (`~/.android/debug.keystore`). So:

- Always build updates on the same computer, or copy `debug.keystore` to the new one.
- **Back up that file.** Without it, everyone has to uninstall the app (and lose its local data)
  before installing a build signed with a new key.

For a proper release key, create one in Android Studio (**Build > Generate Signed App Bundle /
APK**) and keep it, and its passwords, safe and out of this repository.

## App name and package

- The name shown on phones is **Silvershire Inn**. The admin panel's branding page can build a
  copy with another name and icon (see [CUSTOMIZE.md](CUSTOMIZE.md)).
- The package (application ID) is `com.silvershireinn.app`. If you publish your own fork for
  other people, change `applicationId` in `android/app/build.gradle.kts` so it doesn't clash with
  other builds.

## Publishing an update

Everyone on your server gets a message in the **Silvershire Updates** chat with a download
button, and the app installs the new version in place (all chats and settings stay).

1. Raise the version in **both** places (always higher than the last one you published):
   - `android/app/build.gradle.kts`: `versionCode` (a whole number, +1 each time) and
     `versionName` (e.g. `1.0.1`)
   - `server/server.js`: `APP_VERSION` (`versionCode`, `versionName`, and `notes`, the text
     people see)
2. Run `server/publish-update.bat` (Windows) or `server/publish-update.sh` (Linux / macOS). It
   builds the app, copies it to `server/data/download/silvershire-inn.apk` and restarts the
   server.
3. When the server is running (and unlocked, if you use a master password) it sends the message
   by itself, once per version.

If nothing arrives: the version number wasn't raised (the server only announces a higher
`versionCode`), or the server is still locked.

## Shared drawing code

Profile borders are drawn by `server/frames.js` on the web and by a copy of it in
`android/app/src/main/assets/frames.js` in the app (run in a hidden WebView). After changing
`server/frames.js`, run `python tools/sync_frames.py` (copies it and tells you to raise
`CACHE_VERSION` in `android/app/src/main/java/com/silvershireinn/app/utils/ProfileFrames.kt` so
phones redraw borders they already cached).
