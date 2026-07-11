#!/usr/bin/env bash
# Stop processes started by start-demo-stack.sh (scorer + quick tunnel).
# FlexVertex Docker is left running unless STOP_FLEXVERTEX=1.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
LOG_DIR="$ROOT/.demo-logs"
SCORER_PID_FILE="$LOG_DIR/scorer.pid"
TUNNEL_PID_FILE="$LOG_DIR/tunnel.pid"
CAFFEINATE_PID_FILE="$LOG_DIR/caffeinate.pid"

if [[ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

stop_pidfile() {
  local file="$1"
  local label="$2"
  if [[ -f "$file" ]]; then
    local pid
    pid="$(cat "$file")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      echo "Stopped $label (pid $pid)"
    fi
    rm -f "$file"
  fi
}

stop_pidfile "$CAFFEINATE_PID_FILE" "caffeinate"
stop_pidfile "$TUNNEL_PID_FILE" "Cloudflare tunnel"
stop_pidfile "$SCORER_PID_FILE" "scorer"

# Fallback if pid files were lost
pkill -f 'cloudflared tunnel --url http://127.0.0.1:8091' 2>/dev/null || true
pkill -f 'com.ttacs.scorer.Main' 2>/dev/null || true
pkill -f 'caffeinate -dims -w' 2>/dev/null || true

if [[ "${STOP_FLEXVERTEX:-0}" == "1" ]]; then
  if [[ -f "$REPO_ROOT/docker-compose.yml" ]]; then
    (cd "$REPO_ROOT" && docker compose down) || true
    echo "Stopped FlexVertex (docker compose down)"
  fi
else
  echo "Left FlexVertex Docker running (set STOP_FLEXVERTEX=1 to stop it)."
fi

echo "Demo stack stopped."
