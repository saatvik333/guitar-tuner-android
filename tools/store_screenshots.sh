#!/usr/bin/env bash
# Captures the Play Store screenshots on the Android emulator with real readings: the app listens
# through the emulator's virtual microphone, which is fed plucked strings (tools/pluck.py) through
# a PulseAudio/PipeWire null sink. The computer's own microphone and speakers are never used.
# Boots the AVD (AVD, default pixel) read-only with its audio on those sinks, installs
# build/Tuner.apk, overwrites fastlane/metadata/android/en-US/images/{phone,sevenInch,tenInch}Screenshots/,
# then stops the emulator and removes the sinks.
# Needs: ./build.sh run first, the Android emulator and an AVD with audio input, adb, pactl and
# paplay (PulseAudio or pipewire-pulse), python3.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK:-}}}
: "${SDK:?set ANDROID_SDK_ROOT (or ANDROID_HOME / ANDROID_SDK) to the Android SDK directory}"
EMULATOR=$SDK/emulator/emulator
AVD=${AVD:-pixel}
PORT=5580
export ANDROID_SERIAL=emulator-$PORT
PKG=me.saatvik.tuner
APK=build/Tuner.apk
IMAGES=fastlane/metadata/android/en-US/images
MIC=tuner_store_mic
OUT=tuner_store_out
for f in "$EMULATOR" "$APK"; do [ -e "$f" ] || { echo "error: $f missing" >&2; exit 1; }; done
for c in adb pactl paplay python3; do command -v "$c" >/dev/null || { echo "error: $c not on PATH" >&2; exit 1; }; done
if adb devices | grep -q "^$ANDROID_SERIAL\b"; then
    echo "error: $ANDROID_SERIAL is already running; stop it first" >&2
    exit 1
fi

TMP=$(mktemp -d)
EMU_PID= FEED= MODULES=()

# Plays $TMP/$1.wav into the microphone feed until hush.
feed() { paplay -d "$MIC" "$TMP/$1.wav" & FEED=$!; }
hush() {
    if [ -n "$FEED" ]; then kill "$FEED" 2>/dev/null || true; wait "$FEED" 2>/dev/null || true; fi
    FEED=
}

cleanup() {
    hush
    if [ -n "$EMU_PID" ]; then
        adb emu kill >/dev/null 2>&1 || kill "$EMU_PID" 2>/dev/null || true
        wait "$EMU_PID" 2>/dev/null || true
    fi
    local m
    for m in "${MODULES[@]}"; do pactl unload-module "$m"; done
    rm -rf "$TMP"
}
trap cleanup EXIT

# Sounds: MIDI notes (E2 = 40, A2 = 45, D3 = 50, G3 = 55, B3 = 59, E4 = 64), detuned in cents.
clip() { local name=$1; shift; python3 tools/pluck.py "$TMP/$name.wav" "$@"; }
clip low_e_flat --period 2.2 --repeat 6 40-35
clip first_three --period 2.6 40+0.4 45-0.3 50+0.2
clip g_tuned --ring 6 55-0.3
clip a_sharp --period 1.6 --repeat 6 45+12
clip three_in_tune --period 2.4 40+0.3 45-0.4 50+0.2 50+0.2 50+0.2 50+0.2
clip strum --ring 5 40@0 45-24@0.018 50@0.036 55@0.054 59+11@0.072 64@0.09
clip low_d_flat --period 1.8 --repeat 6 38-8

# The microphone feed: whatever plays into $MIC is what the emulator records ($MIC.monitor);
# the emulator's own output goes to $OUT, which nothing plays.
MODULES+=("$(pactl load-module module-null-sink sink_name=$MIC rate=48000 channels=1 \
    'sink_properties=device.description="Tuner screenshots: microphone feed"')")
MODULES+=("$(pactl load-module module-null-sink sink_name=$OUT \
    'sink_properties=device.description="Tuner screenshots: emulator output"')")

# The windowed emulator binary, hidden: the -no-window build has no PulseAudio backend.
PULSE_SOURCE=$MIC.monitor PULSE_SINK=$OUT QT_QPA_PLATFORM=offscreen \
    "$EMULATOR" -avd "$AVD" -port "$PORT" -qt-hide-window -read-only -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect >"$TMP/emulator.log" 2>&1 &
EMU_PID=$!
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    kill -0 "$EMU_PID" 2>/dev/null || { cat "$TMP/emulator.log" >&2; exit 1; }
    sleep 2
done
adb emu avd hostmicon >/dev/null
adb install -r "$APK" >/dev/null

# Clean, constant status bar: fixed clock and battery, no network or system icons, no notifications.
# Airplane mode removes the satellite indicator, which ignores both demo mode and the icon list.
adb shell settings put global sysui_demo_allowed 1
adb shell settings put secure icon_blacklist wifi,mobile,airplane,ethernet,vpn,location,cast,hotspot
adb shell cmd connectivity airplane-mode enable
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo notifications -e visible false
keys=$(adb shell dumpsys notification --noredact | grep 'NotificationRecord(' | grep -o 'key=[^:]*' | cut -d= -f2- || true)
for key in $keys; do adb shell cmd notification snooze --for 3600000 "'$key'" >/dev/null; done

# Taps the centre of the first accessibility node whose description starts with $1 (a grep pattern).
# A slow emulator can raise "System UI isn't responding" over the app: answer Wait and look again.
tap() {
    local x1 y1 x2 y2 xml i
    for i in 1 2 3; do
        xml=$(timeout 30 adb exec-out uiautomator dump /dev/tty || true)
        read -r x1 y1 x2 y2 < <(grep -o "content-desc=\"$1[^\"]*\" [^>]*bounds=\"[^\"]*\"" <<<"$xml" \
            | head -1 | grep -o 'bounds="[^"]*"' | tr -c '0-9\n' ' ') || true
        [ -z "${y2:-}" ] || break
        read -r x1 y1 x2 y2 < <(grep -o 'text="Wait"[^>]*bounds="[^"]*"' <<<"$xml" \
            | head -1 | grep -o 'bounds="[^"]*"' | tr -c '0-9\n' ' ') || true
        [ -n "${y2:-}" ] && adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
        x1= y1= x2= y2=
        sleep 3
    done
    [ -n "${y2:-}" ] || { echo "error: no control '$1' on screen" >&2; exit 1; }
    adb shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

# Fresh start with the microphone allowed, in theme $1 (Aurum is the default).
fresh() {
    adb shell pm clear "$PKG" >/dev/null
    adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
    adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
    sleep 2
    [ "$1" != Aurum ] || return 0
    tap "Settings"
    sleep 0.8
    # The theme swatches are the last row: scroll there first on short screens.
    local w h
    read -r w h < <(adb shell wm size | tail -1 | grep -o '[0-9]*x[0-9]*' | tr 'x' ' ')
    adb shell input swipe $((w / 2)) $((h * 4 / 5)) $((w / 2)) $((h / 5)) 300
    sleep 0.8
    tap "Theme $1"
    sleep 0.6
    tap "Back"
    sleep 0.8
}

shot() {
    adb exec-out screencap -p > "$DIR/$N.png"
    echo "$DIR/$N.png"
    N=$((N + 1))
}

# The scenes. Each starts afresh; the sleeps leave a reading on screen when the capture is taken.
guide_tighten() {           # Easy: the top string well flat
    fresh "$1"
    feed low_e_flat; sleep 4; shot; hush
}
guide_tuned() {             # Easy: three strings tuned by Auto switch, the fourth reaching pitch
    fresh "$1"
    feed first_three; sleep 9; hush
    tap "Switch strings automatically"
    sleep 0.5
    feed g_tuned; sleep 3; shot; hush            # one pluck: Tuned stays up while it rings and after
}
dial_sharp() {              # Advanced: the A string sharp
    fresh "$1"
    tap "Advanced tuner"; sleep 1.5
    feed a_sharp; sleep 3.4; shot; hush
}
dial_in_tune() {            # Advanced: E and A tuned, D in tune
    fresh "$1"
    tap "Advanced tuner"; sleep 1.5
    feed three_in_tune; sleep 6.4; shot; hush
}
strum_check() {             # Advanced: one strum, every string measured
    fresh "$1"
    tap "Advanced tuner"; sleep 1.5
    feed strum; sleep 6; shot; hush
}
drop_d() {                  # Advanced, Drop D: the low D slightly flat
    fresh "$1"
    tap "Advanced tuner"; sleep 1.5
    tap "Tuning Standard. Next"; sleep 0.6
    tap "Tuning Chromatic. Next"; sleep 0.6
    feed low_d_flat; sleep 3.4; shot; hush
}
settings() {
    fresh "$1"
    tap "Settings"; sleep 1.5
    shot
}

capture() {
    DIR=$IMAGES/$1 N=1
    adb shell wm size "$2"
    adb shell wm density "$3"
    sleep 2
    rm -rf "$DIR"
    mkdir -p "$DIR"
    shift 3
    local scene
    for scene in "$@"; do
        # shellcheck disable=SC2086
        $scene
    done
}

capture phoneScreenshots 1080x2160 420 \
    "guide_tighten Aurum" \
    "guide_tuned Salvia" \
    "dial_sharp Celeste" \
    "dial_in_tune Notte" \
    "strum_check Ambra" \
    "drop_d Rosa" \
    "settings Aurum"

capture sevenInchScreenshots 1200x1920 240 \
    "guide_tighten Aurum" \
    "dial_sharp Celeste" \
    "strum_check Ambra"

capture tenInchScreenshots 2560x1600 320 \
    "guide_tuned Salvia" \
    "dial_in_tune Notte" \
    "settings Rosa"
