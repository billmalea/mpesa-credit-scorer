#!/usr/bin/env bash
# Start the scorer with demo Basic Auth from .demo-credentials (never prints the password).
# Full demos also require FlexVertex Iron (audit graph) — run ./scripts/setup-flexvertex.sh first.
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

if [[ ! -d "$ROOT/lib" ]] || ! ls "$ROOT/lib"/com.flexvertex.multiverse-client-*.jar >/dev/null 2>&1; then
  echo "Warning: FlexVertex client JARs not in lib/. Demo is incomplete without FlexVertex."
  echo "  Run: ./scripts/setup-flexvertex.sh"
fi

echo "Starting scorer with Basic Auth enabled (password in $CRED_FILE)"
echo "Expect FlexVertex Iron on localhost:10000 (core audit graph for demos)."
exec ./scripts/run.sh serve
