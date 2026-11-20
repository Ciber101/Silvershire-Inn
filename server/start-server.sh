#!/usr/bin/env sh
# Silvershire Inn server. Leave this running. Restarts itself after an update (exit code 99).
cd "$(dirname "$0")"
[ -d node_modules ] || npm install
while true; do
  node server.js
  code=$?
  if [ "$code" = "99" ]; then echo "Restarting for the update..."; sleep 1; continue; fi
  echo "Server stopped (exit code $code)."
  break
done
