#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

if [[ ! -f "$HERE/.env" ]]; then
  echo "Create $HERE/.env from .env.example before deploying." >&2
  exit 1
fi

cd "$HERE"
docker compose up -d flexvertex

cd "$ROOT"
./scripts/sync-flexvertex-libs.sh

cd "$HERE"
docker compose up --build -d
docker compose ps
