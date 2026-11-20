#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SDK_PATH=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then SDK_PATH="$candidate"; break; fi
done
if [[ -z "$SDK_PATH" ]]; then echo 'Android SDK not found. Install Android Studio or set ANDROID_HOME.' >&2; exit 1; fi
SDK_VALUE="${SDK_PATH//\\/\\\\}"
SDK_VALUE="${SDK_VALUE//:/\\:}"
printf 'sdk.dir=%s\n' "$SDK_VALUE" > "$SCRIPT_DIR/local.properties"
printf 'local.properties configured: %s\n' "$SDK_PATH"
if [[ ! -f "$SDK_PATH/platforms/android-34/android.jar" ]]; then
    echo 'Install Android SDK Platform 34 in Android Studio > SDK Manager before building.'
fi
