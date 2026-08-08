#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_DIR="${LIFE_AGENT_ANDROID_PROJECT_DIR:-/workspace}"
readonly DIAGNOSTICS_DIR="$PROJECT_DIR/app/build/outputs/local-cold-start"
readonly DEBUG_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
readonly TEST_APK="$PROJECT_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly AVD_NAME="life_agent_local_ci_api35"
readonly TEST_RESULTS_DIR="$PROJECT_DIR/app/build/outputs/androidTest-results/connected"
readonly MIN_INSTRUMENTED_TESTS=31

: "${ANDROID_SERIAL:?ANDROID_SERIAL must select the CI emulator}"
: "${ANDROID_AVD_HOME:?ANDROID_AVD_HOME must be writable}"

if [[ ! "$ANDROID_SERIAL" =~ ^emulator-([0-9]+)$ ]]; then
    printf 'ANDROID_SERIAL must use emulator-PORT form, got %s.\n' \
        "$ANDROID_SERIAL" >&2
    exit 64
fi
readonly EMULATOR_PORT="${BASH_REMATCH[1]}"
if (( 10#$EMULATOR_PORT < 5554 || 10#$EMULATOR_PORT > 5682 ||
    10#$EMULATOR_PORT % 2 != 0 )); then
    printf 'Emulator port must be an even number from 5554 through 5682.\n' >&2
    exit 64
fi

adb_target=(adb -s "$ANDROID_SERIAL")
emulator_pid=

mkdir -p "$ANDROID_AVD_HOME" "$DIAGNOSTICS_DIR"

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e

    if [[ "$status" -ne 0 ]]; then
        timeout 15s "${adb_target[@]}" logcat -d -v threadtime \
            > "$DIAGNOSTICS_DIR/failure-logcat.txt" 2>&1
        timeout 15s "${adb_target[@]}" exec-out screencap -p \
            > "$DIAGNOSTICS_DIR/failure-screen.png" 2>/dev/null
        tail -n 200 "$DIAGNOSTICS_DIR/emulator.log" >&2
    fi

    timeout 10s "${adb_target[@]}" emu kill >/dev/null 2>&1
    if [[ -n "$emulator_pid" ]]; then
        for _ in {1..20}; do
            kill -0 "$emulator_pid" 2>/dev/null || break
            sleep 0.5
        done
        kill "$emulator_pid" >/dev/null 2>&1
        sleep 1
        kill -KILL "$emulator_pid" >/dev/null 2>&1
        wait "$emulator_pid" >/dev/null 2>&1
    fi
    timeout 5s adb kill-server >/dev/null 2>&1

    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

test -c /dev/kvm
test -r /dev/kvm
test -w /dev/kvm
timeout 15s emulator -accel-check > "$DIAGNOSTICS_DIR/accel-check.txt"

cd "$PROJECT_DIR"
timeout --signal=TERM --kill-after=30s 15m \
    ./gradlew --no-daemon --stacktrace \
        :app:assembleDebug \
        :app:assembleDebugAndroidTest
test -s "$DEBUG_APK"
test -s "$TEST_APK"

timeout 2m avdmanager create avd \
    --force \
    --name "$AVD_NAME" \
    --device 'pixel_6' \
    --package 'system-images;android-35;aosp_atd;x86_64' \
    <<< 'no'

emulator \
    -avd "$AVD_NAME" \
    -port "$EMULATOR_PORT" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-snapshot \
    -wipe-data \
    -gpu swiftshader_indirect \
    -accel on \
    -no-metrics \
    < /dev/null \
    > "$DIAGNOSTICS_DIR/emulator.log" 2>&1 &
emulator_pid=$!

timeout 120s "${adb_target[@]}" wait-for-device
boot_deadline=$((SECONDS + 240))
while true; do
    if boot_completed=$(
        timeout 5s "${adb_target[@]}" shell getprop sys.boot_completed 2>/dev/null
    ) && [[ ${boot_completed//$'\r'/} == 1 ]]; then
        break
    fi
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "Emulator exited before boot completed." >&2
        exit 1
    fi
    if (( SECONDS >= boot_deadline )); then
        echo "Emulator did not complete boot within 240 seconds." >&2
        exit 1
    fi
    sleep 1
done

timeout 15s "${adb_target[@]}" shell input keyevent 82 >/dev/null
for scale in \
    window_animation_scale \
    transition_animation_scale \
    animator_duration_scale; do
    timeout 15s "${adb_target[@]}" shell settings put global "$scale" 0
done

device_api=$(
    timeout 15s "${adb_target[@]}" shell getprop ro.build.version.sdk |
        tr -d '\r[:space:]'
)
device_abis=$(
    timeout 15s "${adb_target[@]}" shell getprop ro.product.cpu.abilist |
        tr -d '\r[:space:]'
)
boot_completed=$(
    timeout 15s "${adb_target[@]}" shell getprop sys.boot_completed |
        tr -d '\r[:space:]'
)
if [[ "$device_api" != "35" ]]; then
    printf 'Local instrumented gate requires API 35, got %s.\n' "$device_api" >&2
    exit 1
fi
if [[ ",$device_abis," != *,x86_64,* ]]; then
    printf 'Local instrumented gate requires x86_64, got %s.\n' "$device_abis" >&2
    exit 1
fi
if [[ "$boot_completed" != "1" ]]; then
    printf 'Emulator stopped reporting a completed boot.\n' >&2
    exit 1
fi

{
    printf 'serial=%s\n' "$ANDROID_SERIAL"
    printf 'api=%s\n' "$device_api"
    printf 'abis=%s\n' "$device_abis"
    printf 'boot_completed=%s\n' "$boot_completed"
} > "$DIAGNOSTICS_DIR/device-evidence.txt"

rm -rf -- "$TEST_RESULTS_DIR"
timeout --signal=TERM --kill-after=30s 25m \
    ./gradlew --no-daemon --stacktrace \
        :app:connectedDebugAndroidTest \
    |& tee "$DIAGNOSTICS_DIR/connected-tests.log"

python3 - "$TEST_RESULTS_DIR" "$MIN_INSTRUMENTED_TESTS" <<'PY'
from pathlib import Path
from sys import argv
from xml.etree import ElementTree

results_dir = Path(argv[1])
minimum_tests = int(argv[2])
files = sorted(results_dir.rglob("TEST-*.xml"))
totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

for path in files:
    suite = ElementTree.parse(path).getroot()
    if suite.tag != "testsuite":
        raise SystemExit(f"Unexpected Android test report root in {path}: {suite.tag}")
    for name in totals:
        totals[name] += int(suite.attrib.get(name, 0))

print(f"Instrumented report: files={len(files)}, {totals}")
if not files:
    raise SystemExit("No connected Android test XML reports were produced")
if totals["tests"] < minimum_tests:
    raise SystemExit(
        f"Expected at least {minimum_tests} instrumented tests, got {totals['tests']}"
    )
if any(totals[name] for name in ("failures", "errors", "skipped")):
    raise SystemExit(f"Instrumented report is not clean: {totals}")
PY

timeout --signal=TERM --kill-after=30s 25m \
    bash /runner/run-cold-smoke.sh \
        --destructive-local-test \
        "$DEBUG_APK" \
        "$TEST_APK" \
    |& tee "$DIAGNOSTICS_DIR/cold-start.log"
