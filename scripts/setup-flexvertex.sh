#!/usr/bin/env bash
# Required for a full demo: install FlexVertex Iron locally and wire the credit scorer to it.
# FlexVertex audit graph is a core demo pillar (not optional).
# Docs: https://docs.flexvertex.com/en/iron-installation
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/.." && pwd)"
cd "$ROOT"

if [[ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required. Install Docker Desktop:"
  echo "  https://docs.docker.com/desktop/install/mac-install/"
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Starting Docker Desktop…"
  open -a Docker 2>/dev/null || true
  for _ in $(seq 1 45); do
    docker info >/dev/null 2>&1 && break
    sleep 2
  done
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Open Docker Desktop, wait until it is ready, then rerun:"
  echo "  ./scripts/setup-flexvertex.sh"
  exit 1
fi

echo "==> Pulling FlexVertex Iron Edition 1.3.0"
docker pull flexvertex/datamultiverse-iron:1.3.0

echo "==> Starting FlexVertex (docker compose from repo root)"
"$REPO_ROOT/scripts/start-flexvertex.sh"

echo "==> Waiting for FlexVertex client API on localhost:10000"
for _ in $(seq 1 60); do
  if nc -z localhost 10000 >/dev/null 2>&1; then
    echo "    Client API is up"
    break
  fi
  sleep 2
done

if ! nc -z localhost 10000 >/dev/null 2>&1; then
  echo "FlexVertex client API not reachable yet. Check logs:"
  echo "  cd $REPO_ROOT && docker compose logs -f flexvertex"
  exit 1
fi

echo "==> Syncing FlexVertex Java client libraries"
"$ROOT/scripts/sync-flexvertex-libs.sh"

echo "==> Building scorer with FlexVertex profile"
# shellcheck source=/dev/null
[[ -f "$REPO_ROOT/.env" ]] && source "$REPO_ROOT/.env"
export JAVA_HOME="${JAVA_HOME:-}"
"$ROOT/scripts/run.sh" policy >/dev/null 2>&1 || true

mvn -q -Pflexvertex -DskipTests package

cat <<EOF

FlexVertex + M-Pesa Credit Scorer are ready for a full demo.

  FlexVertex Web UI:  ${FLEXVERTEX_WEB_UI:-http://localhost:8080}
    Admin login:      /System/System/System/Admin
    Password:         see FLEXVERTEX_PASSWORD in $REPO_ROOT/.env

  Credit scorer UI:   http://localhost:8091/
  Audit graph:        flexvertex.enabled: true in policy.yml (demo default)
                      Set adminPassword / underwriterPassword to match Iron Edition.

Next:
  ./scripts/run.sh serve
  # After evaluate: reconstruct in the UI + inspect TTACS/Scorer/MpesaCredit in Cartographer

EOF
