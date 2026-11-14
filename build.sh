#!/usr/bin/env bash
# Gradle-free build: aapt2 -> javac -> R8 -> bundletool.
#   build/Tuner.aab          Google Play upload (signed with the upload key, R8 mapping embedded)
#   build/Tuner.apk          universal APK built from that bundle, for sideloading
#   build/mapping.txt        R8 mapping for retracing stack traces from either
# Needs: Android SDK (platforms;android-36, build-tools, cmdline-tools;latest), JDK 17+, bundletool,
# python3 with Pillow, zip.
# SDK location: ANDROID_SDK_ROOT, ANDROID_HOME or ANDROID_SDK (Debian's /usr/lib/android-sdk is the fallback).
# Overrides: BUILD_TOOLS, PLATFORM_JAR, R8_JAR, BUNDLETOOL, VERSION_CODE, VERSION_NAME, KEYSTORE, KEYSTORE_ALIAS.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK:-}}}
if [ -z "$SDK" ] && [ -d /usr/lib/android-sdk ]; then SDK=/usr/lib/android-sdk; fi
: "${SDK:?set ANDROID_SDK_ROOT (or ANDROID_HOME / ANDROID_SDK) to the Android SDK directory}"

BT=${BUILD_TOOLS:-}
if [ -z "$BT" ]; then
    newest=$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -n 1)
    [ -n "$newest" ] || { echo "error: no build-tools in $SDK; run: sdkmanager \"build-tools;36.0.0\"" >&2; exit 1; }
    BT=$SDK/build-tools/$newest
fi
JAR=${PLATFORM_JAR:-$SDK/platforms/android-36/android.jar}
R8_JAR=${R8_JAR:-$SDK/cmdline-tools/latest/lib/r8.jar}
for f in "$BT/aapt2" "$BT/lib/apksigner.jar" "$JAR" "$R8_JAR"; do
    [ -e "$f" ] || { echo "error: $f missing; install it with sdkmanager (build-tools, platforms;android-36, cmdline-tools;latest)" >&2; exit 1; }
done
BUNDLETOOL=${BUNDLETOOL:-bundletool}
command -v "$BUNDLETOOL" >/dev/null || { echo "error: bundletool not on PATH (or set BUNDLETOOL)" >&2; exit 1; }

MIN_SDK=24
TARGET_SDK=36
VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.1.0}
KS=${KEYSTORE:-${XDG_DATA_HOME:-$HOME/.local/share}/guitar-tuner-android/upload.jks}
KS_ALIAS=${KEYSTORE_ALIAS:-upload}
OUT=build

# Upload key: Play App Signing holds the app signing key; this key only authenticates uploads.
# The password comes from KEYSTORE_PASS or, failing that, the desktop keyring.
if [ -z "${KEYSTORE_PASS:-}" ] && command -v secret-tool >/dev/null; then
    KEYSTORE_PASS=$(secret-tool lookup service guitar-tuner-android key upload-keystore || true)
fi
: "${KEYSTORE_PASS:?set KEYSTORE_PASS (or store it: secret-tool store --label=guitar-tuner-android service guitar-tuner-android key upload-keystore)}"
export KEYSTORE_PASS
if [ ! -f "$KS" ]; then
    if [ "${NEW_KEYSTORE:-0}" != 1 ]; then
        echo "error: keystore $KS not found." >&2
        echo "Restore it, or run with NEW_KEYSTORE=1 to create a new upload key." >&2
        exit 1
    fi
    mkdir -p -m 700 "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias "$KS_ALIAS" -keyalg RSA -keysize 4096 \
        -validity 10000 -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
        -dname "CN=Saatvik Sharma, O=Tuner" 2>/dev/null
    chmod 600 "$KS"
    echo "created $KS - back it up"
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/base/manifest" "$OUT/base/dex"

python3 tools/make_icons.py res

# Resources, compiled once in protobuf form: bundletool turns them into the APKs Play serves.
# Assets (the Rubik fonts) come from the hairline design system.
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link --proto-format -I "$JAR" \
    --manifest AndroidManifest.xml \
    -A hairline/assets \
    --proguard "$OUT/aapt-rules.pro" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/proto.zip" "$OUT/res.zip"

javac -nowarn -Xlint:-options -XDsuppressNotes -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$JAR" -d "$OUT/classes" \
    $(find src hairline/src hairline/android -name '*.java')
(cd "$OUT/classes" && jar --create --file ../classes.jar .)

java -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api "$MIN_SDK" \
    --lib "$JAR" --pg-conf proguard-rules.pro --pg-conf "$OUT/aapt-rules.pro" \
    --pg-map-output "$OUT/mapping.txt" --output "$OUT/dex" "$OUT/classes.jar"

# Bundle module layout: manifest/, dex/, res/, assets/, resources.pb.
(cd "$OUT/base" && unzip -q ../proto.zip && mv AndroidManifest.xml manifest/)
cp "$OUT/dex/classes.dex" "$OUT/base/dex/"
(cd "$OUT/base" && zip -q -X -r ../base.zip .)

"$BUNDLETOOL" build-bundle --modules="$OUT/base.zip" --output="$OUT/Tuner.aab" \
    --metadata-file=com.android.tools.build.obfuscation/proguard.map:"$OUT/mapping.txt"
# Upload certificates are self-signed by design; jarsigner's warning about that is noise.
jarsigner -keystore "$KS" -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS \
    -sigalg SHA256withRSA -digestalg SHA-256 "$OUT/Tuner.aab" "$KS_ALIAS" >/dev/null
jarsigner -verify "$OUT/Tuner.aab" | grep '^jar verified' >/dev/null \
    || { echo "error: $OUT/Tuner.aab signature does not verify" >&2; exit 1; }
"$BUNDLETOOL" validate --bundle="$OUT/Tuner.aab" >/dev/null

"$BUNDLETOOL" build-apks --bundle="$OUT/Tuner.aab" --output="$OUT/Tuner.apks" --mode=universal \
    --ks="$KS" --ks-key-alias="$KS_ALIAS" \
    --ks-pass=file:<(printf %s "$KEYSTORE_PASS") --key-pass=file:<(printf %s "$KEYSTORE_PASS")
unzip -q -p "$OUT/Tuner.apks" universal.apk > "$OUT/Tuner.apk"
java --enable-native-access=ALL-UNNAMED -jar "$BT/lib/apksigner.jar" verify "$OUT/Tuner.apk"

echo "built $OUT/Tuner.aab ($(stat -c %s "$OUT/Tuner.aab") bytes), $OUT/Tuner.apk ($(stat -c %s "$OUT/Tuner.apk") bytes), $VERSION_NAME / $VERSION_CODE"
