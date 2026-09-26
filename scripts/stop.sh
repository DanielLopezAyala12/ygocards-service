#!/usr/bin/env bash
# Stop ygocards-service with SIGTERM and wait for it to finish on its own terms.
#
# SIGTERM is the signal a container runtime sends, so this is the same path a deployment
# takes. The process answers it by closing the listener, letting in-flight requests finish
# inside the grace period, and logging what it is doing. SIGKILL is only used if the grace
# period is exceeded, and that case is reported rather than hidden.

set -euo pipefail

cd "$(dirname "$0")/.."

PID_FILE="service.pid"
LOG_FILE="service.log"
GRACE_SECONDS="${STOP_GRACE_SECONDS:-30}"

if [ ! -f "$PID_FILE" ]; then
  echo "[stop] no $PID_FILE, nothing to stop"
  exit 1
fi

PID="$(cat "$PID_FILE")"

if ! kill -0 "$PID" 2>/dev/null; then
  echo "[stop] pid $PID is not running, removing stale $PID_FILE"
  rm -f "$PID_FILE"
  exit 0
fi

echo "[stop] sending SIGTERM to pid $PID"
kill -TERM "$PID"

for _ in $(seq 1 "$GRACE_SECONDS"); do
  if ! kill -0 "$PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    echo "[stop] stopped gracefully"
    echo "[stop] shutdown lines from $LOG_FILE:"
    grep -E "Graceful shutdown|Shutdown signal" "$LOG_FILE" || true
    exit 0
  fi
  sleep 1
done

echo "[stop] still running after ${GRACE_SECONDS}s, sending SIGKILL"
kill -KILL "$PID"
rm -f "$PID_FILE"
exit 1
