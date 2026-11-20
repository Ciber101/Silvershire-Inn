@echo off
REM Builds the Android app, puts it on the server's download page and restarts the server.
REM Raise versionCode / versionName in android\app\build.gradle.kts AND APP_VERSION in
REM server\server.js first, or nobody is told about the update.
title Silvershire Inn - Publish Update
cd /d "%~dp0"
echo.
echo  1) Building the app...
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
pushd ..\android
call gradlew.bat assembleDebug
if errorlevel 1 (
  popd
  echo  BUILD FAILED. See the errors above.
  pause
  exit /b 1
)
popd
echo.
echo  2) Copying the app to data\download...
if not exist "data\download" mkdir "data\download"
copy /Y "..\android\app\build\outputs\apk\debug\app-debug.apk" "data\download\silvershire-inn.apk" >nul
echo.
echo  3) Restarting the server (it must have been started with start-server.bat)...
echo restart> "restart.request"
echo.
echo  Done. Once the server is running (and unlocked) everyone gets the update message.
pause
