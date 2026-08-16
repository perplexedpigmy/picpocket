#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/sync-tests"

TS="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$ROOT/sync-tests/tmp"

mode="${1:-serial}"
shift || true

if [ "$mode" = "parallel" ]; then
  # Phase C: two workers, one emulator slot each (5554/w1 and 5556/w2),
  # splitting the SINGLE-device suite. Two-device tests (test_01::test_b,
  # test_04, test_05) must run via 'run_e2e.sh serial' afterwards: they need
  # both emulators in one session and would collide with a sibling worker
  # (boot_device_b refuses them under NEXTCLOUD_SUBDIR).
  w1=( "scenarios/test_01_happy_path.py::TestHappyPath::test_a_imports_3page_pdf_drive_verifies"
       "scenarios/test_saf_to_drive.py" )
  w2=( "scenarios/test_02_remote_add_page.py"
       "scenarios/test_03_conflict.py" )
  for i in 1 2; do
    log="$ROOT/sync-tests/tmp/worker-$i-$TS.log"
    pidf="$ROOT/sync-tests/tmp/worker-$i-$TS.pid"
    if [ "$i" = "1" ]; then
      nohup env EMULATOR_SERIAL=emulator-5554 NEXTCLOUD_SUBDIR=w1 \
        python3 -m pytest "${w1[@]}" -v >"$log" 2>&1 &
    else
      nohup env EMULATOR_SERIAL=emulator-5556 NEXTCLOUD_SUBDIR=w2 \
        python3 -m pytest "${w2[@]}" -v >"$log" 2>&1 &
    fi
    echo $! > "$pidf"
    echo "worker-$i: pid=$! log=$log"
  done
  exit 0
fi

# serial (default): full gate on the default worker (no NEXTCLOUD_SUBDIR,
# rooted at PicPocketTest itself). Pass test paths as "$@".
LOG="$ROOT/sync-tests/tmp/run-$TS.log"
PID_FILE="$ROOT/sync-tests/tmp/run-$TS.pid"

nohup python3 -m pytest "$@" -v >"$LOG" 2>&1 &
PID=$!
echo "$PID" >"$PID_FILE"

echo "log: $LOG"
echo "pid: $PID"