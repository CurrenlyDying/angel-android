#!/usr/bin/env bash
# Save-to-deploy loop: rebuild, install over the existing app (its data is kept) and relaunch it on the phone.
#   ./dev.sh        watch the sources and redeploy on every save
#   ./dev.sh once   build + install + launch a single time
set -u
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/codebase/android-studio/jbr}"
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"   # the SDK adb, not a distro adb

APP=com.bruh.angel/.MainActivity
STAMP=$(mktemp)
trap 'rm -f "$STAMP"' EXIT

deploy() {
  touch "$STAMP"   # stamp before building, so a save made mid-build triggers another round
  local start=$SECONDS
  if ./gradlew installDebug --console=plain -q && adb shell am start -n "$APP" >/dev/null; then
    echo "[$(date +%T)] deployed in $((SECONDS - start))s"
  else
    echo "[$(date +%T)] FAILED - fix the error above and save again"
  fi
}

changed() {
  # The vendored llama.cpp tree is skipped: only our own sources should trigger a rebuild.
  find app/src app/build.gradle.kts gradle/libs.versions.toml \
    -path app/src/main/cpp/llama.cpp -prune -o -type f -newer "$STAMP" -print -quit | grep -q .
}

deploy
[ "${1:-}" = once ] && exit 0

echo "Watching for changes (Ctrl-C to stop)..."
while true; do
  if changed; then
    sleep 0.5   # let "Save All" finish writing before building
    deploy
  fi
  sleep 1
done
