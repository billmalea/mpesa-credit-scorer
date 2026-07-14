#!/bin/sh
set -eu

quote_yaml() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

mkdir -p /app/docs

cat > /app/policy.local.yml <<EOF
flexvertex:
  enabled: true
  host: "$(quote_yaml "${FLEXVERTEX_HOST:-flexvertex}")"
  port: ${FLEXVERTEX_PORT:-10000}
  adminPassword: "$(quote_yaml "${FLEXVERTEX_PASSWORD:?FLEXVERTEX_PASSWORD is required}")"
  underwriterPassword: "$(quote_yaml "${FLEXVERTEX_UNDERWRITER_PASSWORD:-${FLEXVERTEX_PASSWORD}}")"
server:
  port: ${SCORER_PORT:-8091}
EOF

# system-scope FlexVertex JARs are not merged into the shaded JAR.
CP="/app/scorer.jar"
for j in /app/lib/*.jar; do
  CP="$CP:$j"
done

exec java -cp "$CP" com.ttacs.scorer.Main serve --policy /app/policy.yml
