#!/usr/bin/env bash
set -euo pipefail
umask 077
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="${1:-$SCRIPT_DIR}"
KEY_ALIAS_VALUE="${2:-personaltoolbox}"
mkdir -p -- "$OUTPUT_DIR"
OUTPUT_DIR="$(cd -- "$OUTPUT_DIR" && pwd)"
KEYSTORE_FILE="$OUTPUT_DIR/release.jks"
PROPERTIES_FILE="$OUTPUT_DIR/keystore.properties"
if [[ -e "$KEYSTORE_FILE" || -e "$PROPERTIES_FILE" ]]; then
    echo 'release.jks or keystore.properties already exists. Keep the existing signing key; nothing was overwritten.' >&2
    exit 1
fi
if [[ ! "$KEY_ALIAS_VALUE" =~ ^[a-zA-Z0-9_-]+$ ]]; then
    echo 'Alias must contain letters, digits, underscore or hyphen.' >&2; exit 1
fi
KEYTOOL_BIN="keytool"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then KEYTOOL_BIN="$JAVA_HOME/bin/keytool"; fi
command -v "$KEYTOOL_BIN" >/dev/null || { echo 'Install JDK 17+ or set JAVA_HOME.' >&2; exit 1; }
PASSWORD_VALUE="$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
export TOOLBOX_KEYTOOL_PASSWORD="$PASSWORD_VALUE"
trap 'unset TOOLBOX_KEYTOOL_PASSWORD PASSWORD_VALUE' EXIT
"$KEYTOOL_BIN" -genkeypair -noprompt -storetype JKS -keystore "$KEYSTORE_FILE" \
    -storepass:env TOOLBOX_KEYTOOL_PASSWORD -keypass:env TOOLBOX_KEYTOOL_PASSWORD \
    -alias "$KEY_ALIAS_VALUE" -keyalg RSA -keysize 3072 -validity 10000 \
    -dname 'CN=Personal Toolbox, OU=Personal, O=Personal Toolbox, C=TW'
printf 'storeFile=release.jks\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
    "$PASSWORD_VALUE" "$KEY_ALIAS_VALUE" "$PASSWORD_VALUE" > "$PROPERTIES_FILE"
printf 'Created %s and %s. Back up both files securely and keep this key for all releases.\n\n' "$KEYSTORE_FILE" "$PROPERTIES_FILE"
printf 'GitHub Actions secrets (do not publish this output):\nKEYSTORE_PASSWORD=%s\nKEY_ALIAS=%s\nKEY_PASSWORD=%s\nKEYSTORE_BASE64=\n' \
    "$PASSWORD_VALUE" "$KEY_ALIAS_VALUE" "$PASSWORD_VALUE"
base64 < "$KEYSTORE_FILE" | tr -d '\r\n'
printf '\n'
