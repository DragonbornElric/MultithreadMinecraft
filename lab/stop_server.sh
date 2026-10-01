#!/bin/bash
# Stop the lab server (RCON "stop", then wait; kill after 60 s).
LAB_DIR="${1:-${LAB_DIR:-/tmp/mtmc-lab}}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PID="$(cat "$LAB_DIR/server/server.pid" 2>/dev/null || true)"
[ -n "$PID" ] && kill -0 "$PID" 2>/dev/null || exit 0
python3 "$HERE/rcon.py" stop >/dev/null 2>&1 || kill "$PID"
for _ in $(seq 1 60); do kill -0 "$PID" 2>/dev/null || exit 0; sleep 1; done
kill -9 "$PID"
