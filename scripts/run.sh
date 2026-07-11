#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

resolve_java_home() {
  local candidate

  # Load common shell profiles that may set JAVA_HOME (non-login subshells skip these).
  for env_file in "$HOME/.zprofile" "$HOME/.zshrc" "$HOME/.bash_profile" "$HOME/.bashrc"; do
    if [[ -f "$env_file" ]]; then
      # shellcheck disable=SC1090
      source "$env_file" >/dev/null 2>&1 || true
    fi
  done

  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    echo "$JAVA_HOME"
    return 0
  fi

  if [[ -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      echo "$candidate"
      return 0
    fi
  fi

  for candidate in \
    /opt/homebrew/opt/openjdk \
    /opt/homebrew/opt/openjdk@26 \
    /opt/homebrew/opt/openjdk@21 \
    /opt/homebrew/opt/openjdk@17 \
    /usr/local/opt/openjdk \
    /usr/local/opt/openjdk@21 \
    /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      echo "$candidate"
      return 0
    fi
  done

  return 1
}

java_works() {
  command -v java >/dev/null 2>&1 && java -version >/dev/null 2>&1
}

if ! java_works; then
  if JAVA_HOME="$(resolve_java_home)"; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi

if ! java_works; then
  cat >&2 <<'EOF'
Error: No working Java runtime found.

macOS ships /usr/bin/java as a stub unless a JDK is installed. Install one of:
  brew install openjdk
  brew install openjdk@21

Then either open a new terminal or set JAVA_HOME, for example:
  export JAVA_HOME="$(/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home)"
  export PATH="$JAVA_HOME/bin:$PATH"
EOF
  exit 1
fi

PROFILE=()
if [[ -d "$ROOT/lib" ]] && ls "$ROOT/lib"/com.flexvertex.multiverse-client-*.jar >/dev/null 2>&1; then
  PROFILE+=(-Pflexvertex)
fi

if ((${#PROFILE[@]} > 0)); then
  mvn -q "${PROFILE[@]}" -DskipTests package
  JAR="$ROOT/target/mpesa-credit-scorer-1.0.0-SNAPSHOT.jar"
  CP="$JAR"
  for j in "$ROOT"/lib/*.jar; do
    CP="$CP:$j"
  done
  exec java -cp "$CP" com.ttacs.scorer.Main "$@"
else
  mvn -q -DskipTests package
  exec java -jar target/mpesa-credit-scorer-1.0.0-SNAPSHOT.jar "$@"
fi
