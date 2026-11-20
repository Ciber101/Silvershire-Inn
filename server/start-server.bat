@echo off
title Silvershire Inn Server
cd /d "%~dp0"
echo.
echo  ==========================================
echo   SILVERSHIRE INN SERVER
echo  ==========================================
echo.
if not exist node_modules (
  echo  Installing dependencies...
  call npm install
)
echo  This window IS the chat server. Leave it open.
echo.
REM The server restarts itself after an update (exit code 99).
:run
node server.js
if "%errorlevel%"=="99" (
  echo.
  echo  Restarting for the update...
  timeout /t 1 /nobreak >nul
  goto run
)
echo.
echo  Server stopped.
pause
