#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/sync-tests"

TS="$(date +%Y%m%d-%H%M%S)"
LOG="$ROOT/sync-tests/tmp/run-$TS.log"
PID_FILE="$ROOT/sync-tests/tmp/run-$TS.pid"

mkdir -p "$ROOT/sync-tests/tmp"

nohup python3 -m pytest "$@" -v >"$LOG" 2>&1 &
PID=$!
echo "$PID" >"$PID_FILE"

echo "log: $LOG"
echo "pid: $PID"
