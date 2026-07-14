#!/usr/bin/env bash
# First-time Contabo bootstrap: Docker, clone, .env, FlexVertex JAR sync, full stack.
# Run on the VPS as root or a user with sudo:
#   curl -fsSL https://raw.githubusercontent.com/billmalea/mpesa-credit-scorer/main/deploy/contabo/scripts/bootstrap-contabo.sh | bash
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/billmalea/mpesa-credit-scorer.git}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/mpesa-credit-scorer}"
CONTABO_DIR="$INSTALL_DIR/deploy/contabo"
EXPECTED_IP="${EXPECTED_IP:-169.58.16.179}"
SCORER_HOST="${SCORER_HOST:-scorer.ttacs.co.ke}"

log() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

if command -v sudo >/dev/null 2>&1 && [[ "$(id -u)" -ne 0 ]]; then
  SUDO=sudo
else
  SUDO=
fi

if ! command -v docker >/dev/null 2>&1; then
  log "Installing Docker"
  $SUDO apt-get update -qq
  $SUDO apt-get install -y ca-certificates curl git
  curl -fsSL https://get.docker.com | $SUDO sh
  $SUDO usermod -aG docker "${SUDO_USER:-$USER}" 2>/dev/null || true
  log "Docker installed. If docker permission errors appear, log out/in and rerun this script."
fi

if ! docker info >/dev/null 2>&1; then
  die "Docker is not usable for $(whoami). Run: sudo usermod -aG docker $USER && exit, then SSH back in."
fi

if [[ ! -d "$INSTALL_DIR/.git" ]]; then
  log "Cloning $REPO_URL into $INSTALL_DIR"
  git clone "$REPO_URL" "$INSTALL_DIR"
else
  log "Updating existing clone at $INSTALL_DIR"
  git -C "$INSTALL_DIR" pull --ff-only
fi

mkdir -p "$CONTABO_DIR"
if [[ ! -f "$CONTABO_DIR/.env" ]]; then
  log "Creating $CONTABO_DIR/.env with generated secrets"
  FV_PASS="$(openssl rand -hex 24)"
  SCORER_USER="${SCORER_USER:-ttacs}"
  SCORER_PASS="$(openssl rand -hex 16)"
  cat >"$CONTABO_DIR/.env" <<EOF
CADDY_EMAIL=${CADDY_EMAIL:-ops@ttacs.co.ke}
FLEXVERTEX_PASSWORD=${FV_PASS}
FLEXVERTEX_UNDERWRITER_PASSWORD=
SCORER_BASIC_AUTH=${SCORER_USER}:${SCORER_PASS}
EOF
  chmod 600 "$CONTABO_DIR/.env"
  log "Saved credentials — store these securely:"
  echo "  SCORER_BASIC_AUTH user: ${SCORER_USER}"
  echo "  SCORER_BASIC_AUTH pass: ${SCORER_PASS}"
  echo "  FLEXVERTEX_PASSWORD:    ${FV_PASS}"
else
  log "Using existing $CONTABO_DIR/.env"
fi

RESOLVED="$(getent ahostsv4 "$SCORER_HOST" 2>/dev/null | awk '{print $1; exit}' || true)"
if [[ -n "$RESOLVED" && "$RESOLVED" != "$EXPECTED_IP" ]]; then
  log "WARNING: $SCORER_HOST resolves to $RESOLVED (expected $EXPECTED_IP)"
  log "Caddy TLS may fail until DNS A record points to this VPS."
elif [[ -z "$RESOLVED" ]]; then
  log "WARNING: $SCORER_HOST does not resolve yet. Add DNS A → $EXPECTED_IP before relying on HTTPS."
fi

log "Starting FlexVertex Iron"
cd "$CONTABO_DIR"
docker compose up -d flexvertex

log "Waiting for FlexVertex client API on port 10000"
for _ in $(seq 1 60); do
  if docker compose exec -T flexvertex sh -c 'nc -z localhost 10000' >/dev/null 2>&1 \
    || docker compose logs flexvertex 2>&1 | grep -qi 'ready\|listening\|started'; then
    sleep 5
    break
  fi
  sleep 2
done

log "Syncing FlexVertex client JARs"
cd "$INSTALL_DIR"
./scripts/sync-flexvertex-libs.sh

log "Building and starting scorer + Caddy"
cd "$CONTABO_DIR"
docker compose up --build -d
docker compose ps

log "Local health (via Caddy may need DNS first):"
sleep 3
curl -sf "http://127.0.0.1/health" 2>/dev/null && echo || curl -sf "http://localhost/health" 2>/dev/null && echo || true

cat <<EOF

Bootstrap complete.

Next:
  1. Ensure DNS:  ${SCORER_HOST}  A  ${EXPECTED_IP}
  2. Test:        curl -I https://${SCORER_HOST}/health
  3. Merge Vercel PR on new-ttacs, then open https://ttacs.co.ke/mpesa-scorer/
  4. Login with SCORER_BASIC_AUTH credentials printed above (or in ${CONTABO_DIR}/.env)

Logs:  cd ${CONTABO_DIR} && docker compose logs -f scorer
EOF
