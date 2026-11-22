#!/usr/bin/env bash
# Headless boot test with strict kill discipline:
# - the server runs in its own session, PGID recorded at launch
# - before signaling, re-verify the leader's identity (cmdline + start time)
# - TERM only, never name-pattern kills
set -u
cd "$(dirname "$0")/.."
rm -f run/logs/latest.log
setsid gradle runServer --no-daemon --console=plain -q > /tmp/easyeconomy-runserver.out 2>&1 &
LEADER=$!
START=$(ps -o lstart= -p "$LEADER" 2>/dev/null || true)
PGID=$(ps -o pgid= -p "$LEADER" 2>/dev/null | tr -d ' ' || true)
echo "launched leader pid=$LEADER pgid=$PGID start=$START"
if [ "$PGID" != "$LEADER" ]; then
  echo "leader is not its own process group leader (pgid=$PGID), refusing to run"
  exit 1
fi
trap stop_server EXIT

booted=0
for i in $(seq 1 30); do
  sleep 5
  if grep -q 'Done (' run/logs/latest.log 2>/dev/null; then booted=1; break; fi
  if ! kill -0 "$LEADER" 2>/dev/null; then echo "leader exited early"; break; fi
done

stop_server() {
  # re-verify identity: same pid, same start time, cmdline is our gradle run
  local now_start now_cmd
  now_start=$(ps -o lstart= -p "$LEADER" 2>/dev/null || true)
  now_cmd=$(tr '\0' ' ' < /proc/"$LEADER"/cmdline 2>/dev/null || true)
  if [ -z "$now_start" ]; then echo "leader already gone, nothing to stop"; return 0; fi
  if [ "$now_start" != "$START" ]; then echo "PID $LEADER recycled (start changed), refusing to signal"; return 1; fi
  case "$now_cmd" in
    *gradle*runServer*|*GradleDaemon*) ;;
    *) echo "unexpected cmdline for $LEADER, refusing to signal: $now_cmd"; return 1 ;;
  esac
  if [ "$(ps -o pgid= -p "$LEADER" 2>/dev/null | tr -d ' ')" = "$LEADER" ]; then
    kill -TERM -- -"$LEADER" 2>/dev/null || true
  else
    echo "PGID no longer matches leader, signaling pid only"
    kill -TERM "$LEADER" 2>/dev/null || true
  fi
  for i in $(seq 1 10); do kill -0 "$LEADER" 2>/dev/null || break; sleep 1; done
  kill -0 "$LEADER" 2>/dev/null && echo "leader still alive after TERM" || echo "server stopped (TERM, verified pid=$LEADER)"
}

grep -E 'easyeconomy loaded|challenges loaded|player shop loaded|recovery|ERROR|Exception' run/logs/latest.log 2>/dev/null | head -15
stop_server
trap - EXIT
[ "$booted" = 1 ] && echo "BOOT OK" || echo "BOOT FAILED"
