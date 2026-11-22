#!/usr/bin/env bash
# Two-phase integration test with strict kill discipline (same rules as
# boot-test.sh: recorded PGID, identity re-verified before TERM, TERM only).
set -u
cd "$(dirname "$0")/.."

run_phase() {
  local phase=$1 marker=$2
  rm -f run/logs/latest.log
  setsid gradle runServer -PeeSelftest=$phase --no-daemon --console=plain -q > /tmp/easyeconomy-selftest-$phase.out 2>&1 &
  local leader=$!
  local start
  start=$(ps -o lstart= -p "$leader" 2>/dev/null || true)
  echo "[$phase] leader pid=$leader start=$start"
  local found=""
  for i in $(seq 1 72); do
    sleep 5
    if grep -q "$marker" run/logs/latest.log 2>/dev/null; then found=1; break; fi
    if grep -q 'SELFTEST FAIL' run/logs/latest.log 2>/dev/null; then found=""; break; fi
    kill -0 "$leader" 2>/dev/null || break
  done
  # give the server a moment to halt itself, then stop whatever remains
  sleep 10
  local now_start now_cmd
  now_start=$(ps -o lstart= -p "$leader" 2>/dev/null || true)
  if [ -n "$now_start" ] && [ "$now_start" = "$start" ]; then
    now_cmd=$(tr '\0' ' ' < /proc/"$leader"/cmdline 2>/dev/null || true)
    case "$now_cmd" in
      *gradle*runServer*|*GradleDaemon*) kill -TERM -- -"$leader" 2>/dev/null || kill -TERM "$leader" 2>/dev/null || true ;;
      *) echo "[$phase] unexpected cmdline, refusing to signal: $now_cmd" ;;
    esac
    for i in $(seq 1 10); do kill -0 "$leader" 2>/dev/null || break; sleep 1; done
  fi
  grep -E 'selftest ok|SELFTEST|Exception|Caused' run/logs/latest.log 2>/dev/null | head -50
  if [ "$found" = 1 ]; then echo "[$phase] PASS"; return 0; else echo "[$phase] FAIL"; return 1; fi
}

run_phase run 'SELFTEST RUN DONE' || exit 1
run_phase verify 'SELFTEST VERIFY DONE' || exit 1
echo "INTEGRATION PASS"
