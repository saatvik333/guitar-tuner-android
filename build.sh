#!/usr/bin/env bash
# Gradle-free build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs a JDK, zip, python3 with PIL, and the Android SDK packages
#   sdkmanager "platforms;android-36" "build-tools;36.0.0"
# SDK location: ANDROID_SDK_ROOT, ANDROID_HOME or ANDROID_SDK (Debian's /usr/lib/android-sdk is the fallback).
# Overrides: BUILD_TOOLS, PLATFORM_JAR, VERSION_NAME, VERSION_CODE, KEYSTORE.
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
[ -f "$JAR" ] || { echo "error: $JAR not found; run: sdkmanager \"platforms;android-36\"" >&2; exit 1; }

# zipalign and apksigner ship in build-tools; Debian packages them on PATH instead.
tool() {
    if [ -x "$BT/$1" ]; then echo "$BT/$1"; else command -v "$1" || { echo "error: $1 not found in $BT or on PATH" >&2; exit 1; }; fi
}
AAPT2=$(tool aapt2) D8=$(tool d8) ZIPALIGN=$(tool zipalign) APKSIGNER=$(tool apksigner)

# VERSION holds major.minor.patch; the code is major*10000 + minor*100 + patch, so it only grows.
VERSION_NAME=${VERSION_NAME:-$(tr -d '[:space:]' < VERSION)}
if [ -z "${VERSION_CODE:-}" ]; then
    [[ $VERSION_NAME =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "error: VERSION_NAME '$VERSION_NAME' is not major.minor.patch; set VERSION_CODE" >&2; exit 1; }
    IFS=. read -r major minor patch <<< "$VERSION_NAME"
    VERSION_CODE=$((10#$major * 10000 + 10#$minor * 100 + 10#$patch))
fi
KS=${KEYSTORE:-keystore/tuner.jks}
OUT=build

# Signing: the key must stay the same for every build that should install over a previous one.
# It is never generated silently; NEW_KEYSTORE=1 creates one on purpose.
: "${KEYSTORE_PASS:?set KEYSTORE_PASS to the keystore password}"
if [ ! -f "$KS" ]; then
    if [ "${NEW_KEYSTORE:-0}" != 1 ]; then
        echo "error: keystore $KS not found." >&2
        echo "Restore it, or run with NEW_KEYSTORE=1 to create a new key (a new key cannot update installs signed with the old one)." >&2
        exit 1
    fi
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias tuner -keyalg RSA -keysize 3072 \
        -validity 10000 -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS -dname "CN=Tuner"
    echo "created $KS - back it up; it signs every future update"
fi

rm -rf "$OUT"
mkdir -p "$OUT/flat" "$OUT/gen" "$OUT/classes" "$OUT/dex"

python3 tools/make_icons.py res

"$AAPT2" compile --dir res -o "$OUT/flat/res.zip"
# Assets (the Rubik fonts) come from the hairline design system.
"$AAPT2" link -I "$JAR" \
    --manifest AndroidManifest.xml \
    -A hairline/assets \
    --min-sdk-version 23 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o "$OUT/res.apk" "$OUT/flat/res.zip"

javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$JAR" -d "$OUT/classes" \
    $(find src hairline/src hairline/android "$OUT/gen" -name '*.java')

"$D8" --min-api 23 --lib "$JAR" --release --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/res.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$ZIPALIGN" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

"$APKSIGNER" sign --ks "$KS" --ks-pass env:KEYSTORE_PASS --ks-key-alias tuner \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/Tuner.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify "$OUT/Tuner.apk"
echo "built $OUT/Tuner.apk ($(stat -c %s "$OUT/Tuner.apk") bytes, $VERSION_NAME / $VERSION_CODE)"
