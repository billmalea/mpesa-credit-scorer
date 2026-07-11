#!/usr/bin/env bash
# Extract raw text + parsed transactions from an M-Pesa statement PDF.
# Writes under samples/private/ by default (gitignored) to avoid committing PII.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <pdf-path> <password> [output-dir]"
  echo "Example: $0 ./statement.pdf 'password' samples/private"
  exit 1
fi

PDF="$1"
PASSWORD="$2"
OUT_DIR="${3:-samples/private}"

exec "$ROOT/scripts/run.sh" extract --file "$PDF" --password "$PASSWORD" --out "$OUT_DIR"
