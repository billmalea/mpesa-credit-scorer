#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB_DIR="$ROOT/lib"
VERSION="1.3.0"
CONTAINER="${FLEXVERTEX_CONTAINER:-flexvertex}"

if [[ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to sync FlexVertex client libraries."
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "FlexVertex container '$CONTAINER' is not running. Start from repo root: ./scripts/start-flexvertex.sh"
  exit 1
fi

mkdir -p "$LIB_DIR"

copy_jar() {
  local name="$1"
  docker cp "$CONTAINER:/opt/flexvertex/fc/lib/$name" "$LIB_DIR/$name"
  echo "  synced $name"
}

echo "Syncing FlexVertex ${VERSION} client libraries into $LIB_DIR"

while IFS= read -r name; do
  [[ -n "$name" ]] && copy_jar "$name"
done < <(docker exec "$CONTAINER" ls /opt/flexvertex/fc/lib/ | grep "^com.flexvertex" || true)

while IFS= read -r name; do
  [[ -n "$name" ]] && copy_jar "$name"
done < <(docker exec "$CONTAINER" ls /opt/flexvertex/fc/lib/ | grep -E '^netty-.*\.jar$' || true)

for dep in \
  slf4j-api-2.0.9.jar \
  commons-logging-1.3.0.jar \
  commons-io-2.18.0.jar \
  commons-lang3-3.14.0.jar \
  guava-33.3.1-jre.jar \
  failureaccess-1.0.2.jar \
  error_prone_annotations-2.28.0.jar \
  j2objc-annotations-3.0.0.jar \
  listenablefuture-9999.0-empty-to-avoid-conflict-with-guava.jar \
  gson-2.11.0.jar \
  protobuf-java-3.25.5.jar; do
  if docker exec "$CONTAINER" test -f "/opt/flexvertex/fc/lib/$dep"; then
    copy_jar "$dep"
  fi
done

# Fallback: sync any guava jar present under another version name.
if ! ls "$LIB_DIR"/guava-*.jar >/dev/null 2>&1; then
  while IFS= read -r name; do
    [[ -n "$name" ]] && copy_jar "$name"
  done < <(docker exec "$CONTAINER" ls /opt/flexvertex/fc/lib/ | grep '^guava-.*\.jar$' || true)
fi

echo "Done. Run: mvn -f $ROOT/pom.xml -Pflexvertex test"
