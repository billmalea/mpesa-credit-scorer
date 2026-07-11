#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${SCORER_PORT:-8091}"
# Bind target is loopback only — never expose FlexVertex UI (8080) or client API (10000).
URL="http://127.0.0.1:${PORT}"
CRED_FILE="${SCORER_DEMO_CREDENTIALS_FILE:-$ROOT/.demo-credentials}"

if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared not found. Install with: brew install cloudflared"
  exit 1
fi

if [[ "$PORT" == "8080" || "$PORT" == "10000" ]]; then
  echo "Refusing to tunnel FlexVertex ports ($PORT). Only the scorer (default 8091) may be public."
  exit 1
fi

ensure_credentials() {
  if [[ -n "${SCORER_BASIC_AUTH:-}" ]]; then
    return 0
  fi
  if [[ -f "$CRED_FILE" ]]; then
    # shellcheck disable=SC1090
    source "$CRED_FILE"
  fi
  if [[ -z "${SCORER_BASIC_AUTH:-}" ]]; then
    local user="demo"
    local pass
    pass="$(openssl rand -hex 12)"
    SCORER_BASIC_AUTH="${user}:${pass}"
    umask 077
    cat >"$CRED_FILE" <<EOF
# Generated for public demo tunnels. Do not commit.
export SCORER_BASIC_AUTH='${SCORER_BASIC_AUTH}'
EOF
  fi
  export SCORER_BASIC_AUTH
}

ensure_credentials

if ! curl -sf "$URL/health" >/dev/null 2>&1; then
  echo "Scorer is not running on $URL (health should stay public even with Basic Auth)."
  echo
  echo "Start the scorer WITH credentials from $CRED_FILE in another terminal:"
  echo "  set -a; source $CRED_FILE; set +a"
  echo "  cd $ROOT && ./scripts/run.sh serve"
  exit 1
fi

# Fail closed if the live process was started without auth.
UNAUTH_CODE="$(curl -s -o /dev/null -w '%{http_code}' "$URL/" || true)"
if [[ "$UNAUTH_CODE" != "401" ]]; then
  echo "Refusing to open a public tunnel: scorer UI is not protected by Basic Auth (HTTP $UNAUTH_CODE)."
  echo "Restart the scorer after: set -a; source $CRED_FILE; set +a"
  exit 1
fi

# Quick probe that secrets are not statically served.
for path in /policy.yml /.env /../policy.yml /samples/extracted/secret.csv; do
  code="$(curl -s -o /dev/null -w '%{http_code}' -u "$SCORER_BASIC_AUTH" "$URL$path" || true)"
  if [[ "$code" == "200" ]]; then
    echo "Refusing to tunnel: sensitive path unexpectedly reachable: $path"
    exit 1
  fi
done

USER_PART="${SCORER_BASIC_AUTH%%:*}"

echo "============================================================"
echo " Cloudflare quick tunnel (scorer only)"
echo "============================================================"
echo " Local:        $URL"
echo " Auth user:    $USER_PART"
echo " Auth pass:    (see $CRED_FILE — not printed)"
echo
echo " Full demo:    FlexVertex Iron must be running locally"
echo "               (audit graph is core — stays on loopback)."
echo " Not exposed:  FlexVertex UI :8080, client API :10000,"
echo "               policy.yml passwords, real PDF extracts"
echo " Open the public URL, then enter Basic Auth when prompted."
echo "============================================================"
echo "Press Ctrl+C to stop the tunnel."
echo

exec cloudflared tunnel --url "$URL" --no-autoupdate
