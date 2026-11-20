@echo off
REM Example: keep a free DuckDNS name (https://www.duckdns.org) pointing at your home IP.
REM Copy this file (e.g. update-duckdns.bat), fill in YOUR name and token, and run it every
REM 30 minutes with Task Scheduler. Never share or publish your token.
set DOMAIN=your-name-here
set TOKEN=your-token-here
curl -s "https://www.duckdns.org/update?domains=%DOMAIN%&token=%TOKEN%&ip="
echo.
