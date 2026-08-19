#!/bin/bash
# Builds and signs the launcher APK using raw Android SDK command-line
# tools only (no Gradle, no Android Studio).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/out"
KEYSTORE="${KEYSTORE_PATH:-$ROOT/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASSWORD:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "== javac =="
javac -source 8 -target 8 -Xlint:-options -parameters \
    -classpath "$PLATFORM" \
    -d "$OUT/classes" \
    "$APP/src/io/github/datapush3r/lemonlauncher/TvLauncherActivity.java"

echo "== d8 (dex) =="
"$BUILD_TOOLS/d8" --output "$OUT" \
    --min-api 21 --lib "$PLATFORM" \
    "$OUT"/classes/io/github/datapush3r/lemonlauncher/*.class

echo "== aapt2 compile (res/) =="
mkdir -p "$OUT/compiled_res"
"$BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$OUT/compiled_res.zip"

echo "== aapt2 link =="
"$BUILD_TOOLS/aapt2" link \
    -I "$PLATFORM" \
    --manifest "$APP/AndroidManifest.xml" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    "$OUT/compiled_res.zip" \
    -o "$OUT/app-unsigned.apk"

echo "== add classes.dex =="
(cd "$OUT" && zip -q app-unsigned.apk classes.dex)

echo "== zipalign =="
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "== generating debug keystore (one-time) =="
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

echo "== apksigner sign =="
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:"$KEYSTORE_PASS" --key-pass pass:"$KEYSTORE_PASS" \
    --out "$OUT/lemonlauncher.apk" "$OUT/app-aligned.apk"

echo "Built: $OUT/lemonlauncher.apk"
ls -la "$OUT/lemonlauncher.apk"
