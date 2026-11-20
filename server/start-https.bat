@echo off
title Silvershire Inn HTTPS (Caddy)
cd /d "%~dp0"
REM Needs caddy.exe in this folder (https://caddyserver.com/download) and a Caddyfile
REM (copy Caddyfile.example to Caddyfile and put your domain in it). See docs\INTERNET.md.
if not exist caddy.exe ( echo  caddy.exe not found in this folder. & pause & exit /b 1 )
if not exist Caddyfile ( echo  Caddyfile not found: copy Caddyfile.example to Caddyfile first. & pause & exit /b 1 )
echo  Starting Caddy... leave this window open (the server must be running too).
caddy.exe run --config Caddyfile
pause
