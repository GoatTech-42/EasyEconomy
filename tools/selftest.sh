#!/usr/bin/env bash
# Two-phase integration test with strict kill discipline (same rules as
# boot-test.sh: recorded PGID, identity re-verified before TERM, TERM only).
# A phase passes only when its completion marker is in the log AND the
# whole log contains no SELFTEST FAIL - a marker alone is never enough.
set -u
cd "$(dirname "$0")/.."

LEADER=""
cleanup() {
  [ -z "$LEADER" ] && return 0
  local now_start now_cmd now_pgid
  now_start=$(ps -o lstart= -p "$LEADER" 2>/dev/null || true)
  [ -z "$now_start" ] && return 0
  [ "$now_start" != "$START" ] && { echo "trap: pid $LEADER recycled, not signaling"; return 0; }
  now_cmd=$(tr '\0' ' ' < /proc/"$LEADER"/cmdline 2>/dev/null || true)
  now_pgid=$(ps -o pgid= -p "$LEADER" 2>/dev/null | tr -d ' ' || true)
  case "$now_cmd" in
    *gradle*runServer*|*GradleDaemon*|*java*) ;;
    *) echo "trap: unexpected cmdline, not signaling: $now_cmd"; return 0 ;;
  esac
  if [ "$now_pgid" = "$LEADER" ]; then
    kill -TERM -- -"$LEADER" 2>/dev/null || true
  else
    kill -TERM "$LEADER" 2>/dev/null || true
  fi
}
trap cleanup EXIT

run_phase() {
  local phase=$1 marker=$2
  rm -f run/logs/latest.log
  setsid gradle runServer -PeeSelftest=$phase --no-daemon --console=plain -q > /tmp/easyeconomy-selftest-$phase.out 2>&1 &
  LEADER=$!
  START=""; local pgid=""
  for i in $(seq 1 10); do
    START=$(ps -o lstart= -p "$LEADER" 2>/dev/null || true)
    pgid=$(ps -o pgid= -p "$LEADER" 2>/dev/null | tr -d ' ' || true)
    [ -n "$START" ] && [ -n "$pgid" ] && break
    sleep 1
  done
  echo "[$phase] leader pid=$LEADER pgid=$pgid start=$START"
  if [ "$pgid" != "$LEADER" ]; then
    echo "[$phase] FAIL: leader is not its own process group leader (pgid=$pgid), refusing to run"
    return 1
  fi
  local found=""
  for i in $(seq 1 72); do
    sleep 5
    if grep -q "$marker" run/logs/latest.log 2>/dev/null; then found=1; break; fi
    if grep -q 'SELFTEST FAIL' run/logs/latest.log 2>/dev/null; then found=""; break; fi
    kill -0 "$LEADER" 2>/dev/null || break
  done
  # give the server a moment to halt itself, then stop whatever remains
  sleep 10
  cleanup
  LEADER=""
  grep -E 'selftest ok|SELFTEST|Exception|Caused' run/logs/latest.log 2>/dev/null
  # fail-closed: any SELFTEST FAIL anywhere in the log kills the phase,
  # even when the marker is also present
  if grep -q 'SELFTEST FAIL' run/logs/latest.log 2>/dev/null; then
    echo "[$phase] FAIL (fail line present, marker ignored)"
    return 1
  fi
  if [ "$found" = 1 ]; then echo "[$phase] PASS"; return 0; else echo "[$phase] FAIL (no completion marker)"; return 1; fi
}

run_phase run 'SELFTEST RUN DONE' || exit 1
run_phase verify 'SELFTEST VERIFY DONE' || exit 1
echo "INTEGRATION PASS"
