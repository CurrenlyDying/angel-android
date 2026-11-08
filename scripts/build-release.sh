#!/usr/bin/env bash
set -euo pipefail

# Signing inputs stay outside Gradle, its configuration cache, and the source tree.
: "${ANGEL_KEYSTORE:?Set ANGEL_KEYSTORE to the existing release keystore path}"
: "${ANGEL_KEYSTORE_PASSWORD_FILE:?Set ANGEL_KEYSTORE_PASSWORD_FILE to its password file path}"
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
task_signer="${ANGEL_APKSIGNER:-$task_sdk/build-tools/34.0.0/apksigner}"
task_alias="${ANGEL_KEY_ALIAS:-angel-release}"
task_expected="${ANGEL_RELEASE_CERT_SHA256:-773df628227f9ee4d2e24e7505f0f3b68a65b4eb97cafe467dcf366c58d11058}"
[[ -r "$ANGEL_KEYSTORE" && -r "$ANGEL_KEYSTORE_PASSWORD_FILE" ]]
task_key_password_args=()
if [[ -n "${ANGEL_KEY_PASSWORD_FILE:-}" && "$ANGEL_KEY_PASSWORD_FILE" != "$ANGEL_KEYSTORE_PASSWORD_FILE" ]]; then
    [[ -r "$ANGEL_KEY_PASSWORD_FILE" ]]
    task_key_password_args=(--key-pass "file:$ANGEL_KEY_PASSWORD_FILE")
fi
[[ -x "$task_signer" ]]
cd "$task_root"
python3 scripts/verify-gradle-wrapper.py
./gradlew :app:assembleRelease --console=plain --rerun-tasks -Pkotlin.incremental=false "$@"

read -r task_version task_code < <(python3 - app/build/outputs/apk/release/output-metadata.json <<'PY'
import json, re, sys
m = json.load(open(sys.argv[1]))
assert m["applicationId"] == "com.bruh.angel", "Refusing to publish a test application ID"
assert len(m["elements"]) == 1, "Expected one ARM64 APK"
e = m["elements"][0]
assert re.fullmatch(r"[0-9A-Za-z._-]+", e["versionName"])
print(e["versionName"], e["versionCode"])
PY
)
task_dist="${ANGEL_DIST_DIR:-$task_root/app/build/outputs/distribution}"
mkdir -p "$task_dist"
task_tmp="$(mktemp "$task_dist/.angel-release-XXXXXX")"
trap 'rm -f "$task_tmp" "$task_tmp.cert"' EXIT
"$task_signer" sign --ks "$ANGEL_KEYSTORE" --ks-key-alias "$task_alias" \
    --ks-pass "file:$ANGEL_KEYSTORE_PASSWORD_FILE" "${task_key_password_args[@]}" \
    --v4-signing-enabled false --out "$task_tmp" \
    app/build/outputs/apk/release/app-release-unsigned.apk
"$task_signer" verify --verbose --print-certs "$task_tmp" > "$task_tmp.cert"
task_actual="$(awk '/Signer #1 certificate SHA-256 digest:/ { print $NF; exit }' "$task_tmp.cert")"
[[ "$task_actual" == "$task_expected" ]] || {
    echo "Release certificate does not match the configured public certificate." >&2
    exit 1
}
task_name="angel-android-$task_version-release.apk"
mv -- "$task_tmp" "$task_dist/$task_name"
cp -- "$task_tmp.cert" "$task_dist/angel-android-$task_version-certificate.txt"
(
    cd "$task_dist"
    sha256sum "$task_name" > "SHA256SUMS-$task_version.txt"
)
echo "Signed version $task_version (code $task_code): $task_dist/$task_name"
cat "$task_dist/SHA256SUMS-$task_version.txt"
