#!/usr/bin/env bash
# Start the full public demo stack:
#   1) FlexVertex Iron (audit graph — required)
#   2) M-Pesa credit scorer with Basic Auth
#   3) Cloudflare quick tunnel (scorer only)
#
# Usage:
#   ./scripts/start-demo-stack.sh
# Stop:
#   ./scripts/stop-demo-stack.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
cd "$ROOT"

PORT="${SCORER_PORT:-8091}"
SCORER_URL="http://127.0.0.1:${PORT}"
CRED_FILE="$ROOT/.demo-credentials"
SHARE_FILE="$ROOT/DEMO_AUTH.txt"
LOG_DIR="$ROOT/.demo-logs"
SCORER_LOG="$LOG_DIR/scorer.log"
TUNNEL_LOG="$LOG_DIR/tunnel.log"
PID_DIR="$LOG_DIR"
SCORER_PID_FILE="$PID_DIR/scorer.pid"
TUNNEL_PID_FILE="$PID_DIR/tunnel.pid"
CAFFEINATE_PID_FILE="$PID_DIR/caffeinate.pid"

mkdir -p "$LOG_DIR"
umask 077

if [[ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

die() {
  echo "ERROR: $*" >&2
  exit 1
}

daemonize() {
  # daemonize <pidfile> <logfile> -- <command> [args...]
  local pidfile="$1"
  local logfile="$2"
  shift 2
  [[ "${1:-}" == "--" ]] && shift
  python3 - "$pidfile" "$logfile" "$@" <<'PY'
import os, sys, subprocess
pidfile, logfile, *cmd = sys.argv[1:]
env = os.environ.copy()
with open(logfile, "ab", buffering=0) as log:
    proc = subprocess.Popen(
        cmd,
        stdout=log,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        env=env,
        start_new_session=True,
        close_fds=True,
    )
with open(pidfile, "w", encoding="utf-8") as fh:
    fh.write(str(proc.pid))
print(proc.pid)
PY
}

ensure_credentials() {
  if [[ ! -f "$CRED_FILE" ]]; then
    cat >"$CRED_FILE" <<EOF
# Generated for public demo tunnels. Do not commit.
export SCORER_BASIC_AUTH='demo:$(openssl rand -hex 12)'
EOF
  fi
  set -a
  # shellcheck disable=SC1090
  source "$CRED_FILE"
  set +a
  [[ -n "${SCORER_BASIC_AUTH:-}" ]] || die "SCORER_BASIC_AUTH missing in $CRED_FILE"
}

write_policy_local() {
  local env_file="$REPO_ROOT/.env"
  local admin_pw="change_me"
  if [[ -f "$env_file" ]]; then
    # shellcheck disable=SC1090
    set -a
    # Prefer only FLEXVERTEX_PASSWORD from parent .env
    admin_pw="$(grep -E '^FLEXVERTEX_PASSWORD=' "$env_file" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")"
    set +a
    [[ -n "$admin_pw" ]] || admin_pw="change_me"
  fi
  umask 077
  cat >"$ROOT/policy.local.yml" <<EOF
# Gitignored local overlay — written by start-demo-stack.sh
flexvertex:
  enabled: true
  adminPassword: "${admin_pw}"
  underwriterPassword: "scorer123"
EOF
  echo "  Wrote policy.local.yml (FlexVertex passwords from parent .env)"
}

# Ensure Docker Desktop is up when needed
ensure_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    die "Docker not found"
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "  Starting Docker Desktop..."
    open -a Docker 2>/dev/null || true
    local i
    for ((i = 1; i <= 60; i++)); do
      if docker info >/dev/null 2>&1; then
        echo "  OK  Docker daemon"
        return 0
      fi
      sleep 3
    done
    die "Docker daemon not ready — open Docker Desktop and retry"
  fi
}

wait_http() {
  local url="$1"
  local label="$2"
  local attempts="${3:-60}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    if curl -sf --max-time 2 "$url" >/dev/null 2>&1; then
      echo "  OK  $label"
      return 0
    fi
    sleep 2
  done
  die "$label not ready after ${attempts} tries ($url)"
}

wait_tcp() {
  local host="$1"
  local port="$2"
  local label="$3"
  local attempts="${4:-90}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    if nc -z "$host" "$port" >/dev/null 2>&1; then
      echo "  OK  $label ($host:$port)"
      return 0
    fi
    sleep 2
  done
  die "$label not ready after ${attempts} tries ($host:$port)"
}

echo "============================================================"
echo " TTACS M-Pesa demo stack"
echo "============================================================"

ensure_credentials
USER_PART="${SCORER_BASIC_AUTH%%:*}"
PASS_PART="${SCORER_BASIC_AUTH#*:}"

# --- 1) FlexVertex Iron -------------------------------------------------
echo
echo "[1/3] FlexVertex Iron Edition"
ensure_docker
write_policy_local
if [[ -x "$REPO_ROOT/scripts/start-flexvertex.sh" ]]; then
  "$REPO_ROOT/scripts/start-flexvertex.sh"
else
  die "Missing $REPO_ROOT/scripts/start-flexvertex.sh (expected Flex-vertex monorepo parent)"
fi
wait_tcp localhost 10000 "FlexVertex client API" 90

if [[ ! -d "$ROOT/lib" ]] || ! ls "$ROOT"/lib/com.flexvertex.multiverse-client-*.jar >/dev/null 2>&1; then
  echo "  Syncing FlexVertex client JARs..."
  "$ROOT/scripts/sync-flexvertex-libs.sh"
fi

# --- 2) Scorer ----------------------------------------------------------
echo
echo "[2/3] M-Pesa credit scorer (Basic Auth)"

# Free port if a stale instance is listening without our pid file.
if curl -sf --max-time 2 "$SCORER_URL/health" >/dev/null 2>&1; then
  if [[ -f "$SCORER_PID_FILE" ]] && kill -0 "$(cat "$SCORER_PID_FILE")" 2>/dev/null; then
    echo "  Scorer already running (pid $(cat "$SCORER_PID_FILE"))"
  else
    echo "  Port $PORT in use — stopping previous Java scorer..."
    pkill -f 'com.ttacs.scorer.Main' 2>/dev/null || true
    sleep 2
  fi
fi

if ! curl -sf --max-time 2 "$SCORER_URL/health" >/dev/null 2>&1; then
  : >"$SCORER_LOG"
  # Detach into a new session so agent/IDE shell teardown does not kill the scorer.
  export SCORER_BASIC_AUTH
  daemonize "$SCORER_PID_FILE" "$SCORER_LOG" -- "$ROOT/scripts/run.sh" serve >/dev/null
  echo "  Started scorer pid $(cat "$SCORER_PID_FILE") — log $SCORER_LOG"
fi

# Maven package + FlexVertex bootstrap can take a couple of minutes on cold start.
for ((i = 1; i <= 90; i++)); do
  if curl -sf --max-time 2 "$SCORER_URL/health" >/dev/null 2>&1; then
    echo "  OK  Scorer /health"
    break
  fi
  if [[ -f "$SCORER_PID_FILE" ]] && ! kill -0 "$(cat "$SCORER_PID_FILE")" 2>/dev/null; then
    echo "---- scorer log (process exited) ----" >&2
    tail -80 "$SCORER_LOG" >&2 || true
    die "Scorer process exited before /health became ready — see $SCORER_LOG"
  fi
  sleep 2
  if ((i == 90)); then
    echo "---- scorer log (timeout) ----" >&2
    tail -80 "$SCORER_LOG" >&2 || true
    die "Scorer /health not ready after 90 tries ($SCORER_URL/health)"
  fi
done

UNAUTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$SCORER_URL/" || true)"
if [[ "$UNAUTH_CODE" != "401" ]]; then
  die "Scorer UI is not Basic-Auth protected (HTTP $UNAUTH_CODE). Check SCORER_BASIC_AUTH."
fi
echo "  OK  Basic Auth required on UI"

# --- 3) Cloudflare tunnel -----------------------------------------------
echo
echo "[3/3] Cloudflare quick tunnel (scorer only — not FlexVertex ports)"

if ! command -v cloudflared >/dev/null 2>&1; then
  die "cloudflared not found. Install: brew install cloudflared"
fi

# Replace prior quick tunnel only (leave named tunnel --token alone).
if [[ -f "$TUNNEL_PID_FILE" ]] && kill -0 "$(cat "$TUNNEL_PID_FILE")" 2>/dev/null; then
  kill "$(cat "$TUNNEL_PID_FILE")" 2>/dev/null || true
  sleep 1
fi
pkill -f 'cloudflared tunnel --url http://127.0.0.1:8091' 2>/dev/null || true
sleep 1

: >"$TUNNEL_LOG"
daemonize "$TUNNEL_PID_FILE" "$TUNNEL_LOG" -- cloudflared tunnel --url "$SCORER_URL" --protocol http2 --no-autoupdate >/dev/null
echo "  Started tunnel pid $(cat "$TUNNEL_PID_FILE") — log $TUNNEL_LOG"

PUBLIC_URL=""
for ((i = 1; i <= 45; i++)); do
  PUBLIC_URL="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" 2>/dev/null | head -1 || true)"
  if [[ -n "$PUBLIC_URL" ]]; then
    break
  fi
  sleep 1
done
[[ -n "$PUBLIC_URL" ]] || die "Cloudflare URL not found in $TUNNEL_LOG — check cloudflared output"

# Wait until public health works (DNS can lag; some environments cannot resolve trycloudflare).
PUBLIC_OK=0
for ((i = 1; i <= 20; i++)); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$PUBLIC_URL/health" || true)"
  if [[ "$code" == "200" ]]; then
    PUBLIC_OK=1
    break
  fi
  sleep 2
done
if [[ "$PUBLIC_OK" != "1" ]]; then
  echo "  WARN public /health not verified from this shell (often DNS)."
  echo "  Tunnel is registered — open the URL in your browser to confirm."
fi

# Keep Mac from idle-sleeping while the demo runs (lid-close still sleeps — see notes).
if [[ -f "$CAFFEINATE_PID_FILE" ]] && kill -0 "$(cat "$CAFFEINATE_PID_FILE")" 2>/dev/null; then
  echo "  caffeinate already running (pid $(cat "$CAFFEINATE_PID_FILE"))"
else
  # -d display  -i idle  -m disk  -s system sleep (AC power)
  nohup caffeinate -dims -w "$(cat "$TUNNEL_PID_FILE")" >"$LOG_DIR/caffeinate.log" 2>&1 &
  echo $! >"$CAFFEINATE_PID_FILE"
  disown %% 2>/dev/null || true
  echo "  Started caffeinate (prevents idle sleep while tunnel runs)"
fi

cat >"$SHARE_FILE" <<EOF
Demo Basic Auth for the public Cloudflare tunnel

Username: ${USER_PART}
Password: ${PASS_PART}

Public URL: ${PUBLIC_URL}

Local scorer: ${SCORER_URL}
FlexVertex UI (local only): http://localhost:8080
FlexVertex API (local only): localhost:10000

Stop stack: ./scripts/stop-demo-stack.sh

Lid closed: MacBooks still sleep when the lid closes unless you use
clamshell mode (power + external display) or leave the lid open.
caffeinate only blocks idle sleep while awake.

This file is gitignored. Do not commit.
EOF

echo
echo "============================================================"
echo " DEMO IS LIVE — share these"
echo "============================================================"
echo " Public URL:  $PUBLIC_URL"
echo " Username:    $USER_PART"
echo " Password:    (see $SHARE_FILE)"
echo
echo " Verify:      curl -s $PUBLIC_URL/health"
echo " Stop:        ./scripts/stop-demo-stack.sh"
echo "============================================================"
echo
echo "Keep the Mac awake:"
echo "  - Prefer lid OPEN + power adapter (caffeinate is already running)."
echo "  - Lid CLOSED only works in clamshell mode: power + external monitor."
echo "  - Closing the lid on battery will kill the public link."
echo "Quick-tunnel URLs change every restart — re-run this script for a new link."
