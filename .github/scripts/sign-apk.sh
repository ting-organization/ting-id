#!/usr/bin/env bash
# Signs an APK with the release key and writes its certificate's SHA-256
# fingerprint: sign-apk.sh <in.apk> <out.apk> <fingerprint-file>
# The key comes from KEYSTORE_BASE64 (a PKCS12 keystore, base64) and
# KEYSTORE_PASSWORD (for the keystore and the key `ting-id` in it).
set -euo pipefail

fail() {
  echo "::error::$1"
  exit 1
}

[ -n "${KEYSTORE_BASE64:-}" ] && [ -n "${KEYSTORE_PASSWORD:-}" ] || fail "KEYSTORE_BASE64 and KEYSTORE_PASSWORD are needed"
keystore="$(mktemp)"
trap 'rm -f "$keystore"' EXIT
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$keystore" || fail "KEYSTORE_BASE64 is not base64"
keytool -list -keystore "$keystore" -storepass:env KEYSTORE_PASSWORD -alias ting-id > /dev/null ||
  fail "the keystore does not open with KEYSTORE_PASSWORD, or has no key named ting-id"

# One fixed build-tools release, so the same app is signed the same way every time.
BUILD_TOOLS=36.0.0
tools="$ANDROID_HOME/build-tools/$BUILD_TOOLS"
if [ ! -x "$tools/apksigner" ]; then
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install "build-tools;$BUILD_TOOLS" > /dev/null ||
    fail "build-tools $BUILD_TOOLS could not be installed"
fi
echo "Signing with $tools/apksigner"
"$tools/apksigner" sign --ks "$keystore" --ks-key-alias ting-id \
  --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEYSTORE_PASSWORD \
  --v4-signing-enabled false \
  --out "$2" "$1" || fail "apksigner could not sign the app"

# The certificate is public: anyone can read it from the app.
certs="$(mktemp)"
"$tools/apksigner" verify --print-certs "$2" > "$certs" || {
  cat "$certs"
  fail "the signed app does not verify"
}
cat "$certs"
# "Signer #1 certificate SHA-256 digest: …" before build-tools 37, "V3.0 Signer: certificate …" since.
sed -nE '/^(Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest: /{s///p;q}' "$certs" > "$3"
grep -qxE '[0-9a-f]{64}' "$3" || fail "apksigner gave no SHA-256 fingerprint of the certificate"
