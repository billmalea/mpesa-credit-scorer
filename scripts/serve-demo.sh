#!/usr/bin/env bash
# Start the scorer with demo Basic Auth from .demo-credentials (never prints the password).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
CRED_FILE="$ROOT/.demo-credentials"

if [[ ! -f "$CRED_FILE" ]]; then
  umask 077
  {
    echo "# Generated for public demo tunnels. Do not commit."
    echo "export SCORER_BASIC_AUTH='demo:$(openssl rand -hex 12)'"
  } >"$CRED_FILE"
fi

set -a
# shellcheck disable=SC1090
source "$CRED_FILE"
set +a

echo "Starting scorer with Basic Auth enabled (password in $CRED_FILE)"
exec ./scripts/run.sh serve
