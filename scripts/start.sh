#!/usr/bin/env bash
# Start ygocards-service in the background, on Linux, macOS or WSL.
#
# This script is what makes .env effective: Spring Boot never reads that file, so the
# variables have to be exported into the process environment before the JVM starts.
#
# On native Windows use scripts/start.ps1 instead. Git Bash can launch the process but
# cannot deliver a real termination signal to it later, which breaks the stop script.

set -euo pipefail

cd "$(dirname "$0")/.."

JAR="target/ygocards-service.jar"
PID_FILE="service.pid"
LOG_FILE="service.log"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "[start] already running with pid $(cat "$PID_FILE")"
  exit 1
fi

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
  echo "[start] environment loaded from .env"
else
  echo "[start] no .env present, using the defaults in application.properties"
fi

if [ ! -f "$JAR" ]; then
  echo "[start] $JAR not found, building"
  ./mvnw -B -DskipTests package
fi

java -jar "$JAR" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"

echo "[start] pid $(cat "$PID_FILE"), port ${PORT:-8080}, log $LOG_FILE"
echo "[start] liveness:  curl http://localhost:${PORT:-8080}/health"
echo "[start] upstream:  curl http://localhost:${PORT:-8080}/health/upstream"
