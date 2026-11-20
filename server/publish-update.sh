#!/usr/bin/env sh
# Builds the Android app, puts it on the server's download page and restarts the server.
# Raise versionCode / versionName in android/app/build.gradle.kts AND APP_VERSION in
# server/server.js first, or nobody is told about the update.
set -e
cd "$(dirname "$0")"
echo "1) Building the app..."
(cd ../android && ./gradlew assembleDebug)
echo "2) Copying the app to data/download..."
mkdir -p data/download
cp ../android/app/build/outputs/apk/debug/app-debug.apk data/download/silvershire-inn.apk
echo "3) Restarting the server (it must have been started with start-server.sh)..."
echo restart > restart.request
echo "Done. Once the server is running (and unlocked) everyone gets the update message."
