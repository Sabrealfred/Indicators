#!/bin/sh
# Runs the whole demo as one service, for hosts that give you a single $PORT
# (Render, Railway, Fly). The API listens privately on 4000; the web app is
# public on $PORT and proxies /api/* to it. The database lives on local disk
# and is re-seeded with demo data whenever it is empty.
set -e
WEB_PORT="${PORT:-3000}"
DB_PATH="${DB_PATH:-/tmp/ledgerbank.db}" PORT=4000 node --no-warnings api/dist/server.js &
exec npx next start web -p "$WEB_PORT"
