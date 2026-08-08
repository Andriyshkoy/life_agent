#!/usr/bin/env bash
set -Eeuo pipefail

readonly TARGET_PACKAGE="ru.andriyshkoy.lifeagent.local"
readonly TEST_PACKAGE="ru.andriyshkoy.lifeagent.local.test"
readonly MAIN_ACTIVITY="ru.andriyshkoy.lifeagent.MainActivity"
readonly TEST_RUNNER="${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
readonly TEST_CLASS="ru.andriyshkoy.lifeagent.persistence.MainActivityOfflinePersistenceInstrumentedTest"
readonly SEED_METHOD="phase1SeedSyntheticNoteForExternalColdStart"
readonly VERIFY_METHOD="phase2VerifySyntheticNoteAfterExternalColdStart"
readonly DEVICE_QUERY_TIMEOUT="15s"
readonly INSTALL_TIMEOUT="180s"
readonly INSTRUMENTATION_TIMEOUT="180s"
readonly COLD_LAUNCH_TIMEOUT="45s"
readonly UI_DUMP_TIMEOUT="15s"

usage() {
    cat <<'EOF'
Usage:
  run_android_local_cold_start_smoke.sh --destructive-local-test DEBUG_APK TEST_APK

Runs the local airplane-mode + force-stop acceptance on the fixed debug package
ru.andriyshkoy.lifeagent.local. The script clears and finally uninstalls that
debug package and its test package. It refuses to target the production package.

Set ANDROID_SERIAL when more than one adb device is connected.
EOF
}

if [[ ${1:-} != "--destructive-local-test" || $# -ne 3 ]]; then
    usage >&2
    exit 64
fi

readonly DEBUG_APK=$2
readonly TEST_APK=$3

if [[ ! -f "$DEBUG_APK" || ! -f "$TEST_APK" ]]; then
    printf 'Both DEBUG_APK and TEST_APK must be existing files.\n' >&2
    exit 66
fi

if ! command -v adb >/dev/null 2>&1; then
    printf 'adb is required.\n' >&2
    exit 69
fi
if ! command -v timeout >/dev/null 2>&1; then
    printf 'GNU timeout is required.\n' >&2
    exit 69
fi

apk_package_name() {
    local apk_path=$1
    local sdk_root sdk_aapt2
    if command -v apkanalyzer >/dev/null 2>&1; then
        apkanalyzer manifest application-id "$apk_path"
        return
    fi
    if command -v aapt2 >/dev/null 2>&1; then
        aapt2 dump packagename "$apk_path"
        return
    fi
    if command -v aapt >/dev/null 2>&1; then
        aapt dump badging "$apk_path" |
            sed -nE "s/^package: name='([^']+)'.*/\\1/p"
        return
    fi
    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
        if [[ -z "$sdk_root" || ! -d "$sdk_root/build-tools" ]]; then
            continue
        fi
        sdk_aapt2=$(
            find "$sdk_root/build-tools" \
                -mindepth 2 -maxdepth 2 -type f -name aapt2 -print |
                sort -V |
                tail -n 1
        )
        if [[ -n "$sdk_aapt2" ]]; then
            "$sdk_aapt2" dump packagename "$apk_path"
            return
        fi
    done
    printf 'apkanalyzer, aapt2, or aapt must be on PATH to verify APK identities.\n' >&2
    return 69
}

debug_apk_package=$(apk_package_name "$DEBUG_APK")
test_apk_package=$(apk_package_name "$TEST_APK")
if [[ "$debug_apk_package" != "$TARGET_PACKAGE" ]]; then
    printf 'Refusing APK package %s; expected fixed debug package %s.\n' \
        "$debug_apk_package" "$TARGET_PACKAGE" >&2
    exit 65
fi
if [[ "$test_apk_package" != "$TEST_PACKAGE" ]]; then
    printf 'Refusing test APK package %s; expected %s.\n' \
        "$test_apk_package" "$TEST_PACKAGE" >&2
    exit 65
fi

adb_command=(adb)
if [[ -n ${ANDROID_SERIAL:-} ]]; then
    adb_command+=(-s "$ANDROID_SERIAL")
else
    if ! connected_device_output=$(timeout --foreground "$DEVICE_QUERY_TIMEOUT" adb devices); then
        printf 'Timed out listing adb devices.\n' >&2
        exit 69
    fi
    mapfile -t connected_devices < <(
        awk 'NR > 1 && $2 == "device" { print $1 }' <<<"$connected_device_output"
    )
    if [[ ${#connected_devices[@]} -ne 1 ]]; then
        printf 'Expected exactly one ready adb device; set ANDROID_SERIAL explicitly.\n' >&2
        exit 69
    fi
    adb_command+=(-s "${connected_devices[0]}")
fi

if ! device_api=$(
    timeout --foreground "$DEVICE_QUERY_TIMEOUT" \
        "${adb_command[@]}" shell getprop ro.build.version.sdk |
        tr -d '\r[:space:]'
); then
    printf 'Timed out reading the Android API level.\n' >&2
    exit 69
fi
if [[ "$device_api" != "35" ]]; then
    printf 'Local cold-start gate requires Android API 35; device reports %s.\n' \
        "${device_api:-unknown}" >&2
    exit 65
fi

if ! device_abis=$(
    timeout --foreground "$DEVICE_QUERY_TIMEOUT" \
        "${adb_command[@]}" shell getprop ro.product.cpu.abilist |
        tr -d '\r[:space:]'
); then
    printf 'Timed out reading the Android ABI list.\n' >&2
    exit 69
fi
if [[ ",$device_abis," != *,x86_64,* ]]; then
    printf 'Local cold-start gate requires x86_64; device reports %s.\n' \
        "${device_abis:-unknown}" >&2
    exit 65
fi

readonly SYNTHETIC_MARKER="local-cold-$(date -u +%Y%m%dT%H%M%SZ)-$$"
readonly REMOTE_UI_DUMP="/data/local/tmp/${SYNTHETIC_MARKER}.xml"

debug_installed=false
test_installed=false
original_airplane_mode=

read_airplane_mode() {
    "${adb_command[@]}" shell settings get global airplane_mode_on |
        tr -d '\r[:space:]'
}

wait_for_airplane_mode() {
    local expected=$1
    local attempt
    for attempt in {1..20}; do
        if [[ $(read_airplane_mode) == "$expected" ]]; then
            return 0
        fi
        sleep 0.25
    done
    return 1
}

set_airplane_mode() {
    local enabled=$1
    local verb state
    if [[ "$enabled" == "1" ]]; then
        verb=enable
        state=true
    else
        verb=disable
        state=false
    fi

    if ! "${adb_command[@]}" shell cmd connectivity airplane-mode "$verb" \
        >/dev/null 2>&1; then
        "${adb_command[@]}" shell settings put global airplane_mode_on "$enabled" \
            >/dev/null
        "${adb_command[@]}" shell am broadcast \
            -a android.intent.action.AIRPLANE_MODE \
            --ez state "$state" \
            >/dev/null
    fi
    if ! wait_for_airplane_mode "$enabled"; then
        printf 'Could not set airplane mode to %s.\n' "$enabled" >&2
        return 1
    fi
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e

    "${adb_command[@]}" shell rm -f "$REMOTE_UI_DUMP" >/dev/null 2>&1
    "${adb_command[@]}" shell am force-stop "$TARGET_PACKAGE" >/dev/null 2>&1
    if [[ "$test_installed" == true ]]; then
        "${adb_command[@]}" shell pm clear "$TEST_PACKAGE" >/dev/null 2>&1
        "${adb_command[@]}" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
    fi
    if [[ "$debug_installed" == true ]]; then
        "${adb_command[@]}" shell pm clear "$TARGET_PACKAGE" >/dev/null 2>&1
        "${adb_command[@]}" uninstall "$TARGET_PACKAGE" >/dev/null 2>&1
    fi
    if [[ "$original_airplane_mode" == "0" || "$original_airplane_mode" == "1" ]]; then
        set_airplane_mode "$original_airplane_mode" >/dev/null 2>&1
    fi

    exit "$status"
}
trap cleanup EXIT INT TERM

run_phase() {
    local method=$1
    local output
    if ! output=$(
        timeout --foreground "$INSTRUMENTATION_TIMEOUT" \
            "${adb_command[@]}" shell am instrument -w -r \
            -e class "${TEST_CLASS}#${method}" \
            -e localColdStartMarker "$SYNTHETIC_MARKER" \
            -e localRequireAirplaneMode true \
            "$TEST_RUNNER" 2>&1
    ); then
        printf '%s\n' "$output" >&2
        return 1
    fi
    printf '%s\n' "$output"
    if ! grep -Eq 'OK \(1 test\)' <<<"$output"; then
        printf 'Instrumentation phase %s did not report one passing test.\n' "$method" >&2
        return 1
    fi
}

wait_for_stopped_process() {
    local attempt
    for attempt in {1..20}; do
        if [[ -z $(
            "${adb_command[@]}" shell pidof "$TARGET_PACKAGE" 2>/dev/null |
                tr -d '\r[:space:]'
        ) ]]; then
            return 0
        fi
        sleep 0.25
    done
    return 1
}

screen_dimensions() {
    "${adb_command[@]}" shell wm size |
        tr -d '\r' |
        sed -nE 's/.* ([0-9]+)x([0-9]+)$/\1 \2/p' |
        tail -n 1
}

cold_ui_contains_marker() {
    timeout --foreground "$UI_DUMP_TIMEOUT" \
        "${adb_command[@]}" shell uiautomator dump "$REMOTE_UI_DUMP" \
        >/dev/null 2>&1 &&
        timeout --foreground "$DEVICE_QUERY_TIMEOUT" \
            "${adb_command[@]}" shell grep -Fq \
            "$SYNTHETIC_MARKER" "$REMOTE_UI_DUMP"
}

wait_for_marker_on_cold_ui() {
    local attempt width height center_x from_y to_y
    read -r width height < <(screen_dimensions)
    center_x=$((width / 2))
    from_y=$((height * 3 / 4))
    to_y=$((height / 4))

    for attempt in {1..20}; do
        if cold_ui_contains_marker; then
            return 0
        fi
        if (( attempt == 5 || attempt == 10 || attempt == 15 )); then
            "${adb_command[@]}" shell input swipe \
                "$center_x" "$from_y" "$center_x" "$to_y" 250 \
                >/dev/null
        fi
        sleep 0.5
    done
    return 1
}

printf 'Installing isolated API %s / %s debug acceptance packages on %s.\n' \
    "$device_api" "$device_abis" "${adb_command[*]}"
timeout --foreground "$INSTALL_TIMEOUT" \
    "${adb_command[@]}" install -r -t "$DEBUG_APK"
debug_installed=true
timeout --foreground "$INSTALL_TIMEOUT" \
    "${adb_command[@]}" install -r -t "$TEST_APK"
test_installed=true

"${adb_command[@]}" shell pm clear "$TEST_PACKAGE" >/dev/null
"${adb_command[@]}" shell pm clear "$TARGET_PACKAGE" >/dev/null

original_airplane_mode=$(read_airplane_mode)
if [[ "$original_airplane_mode" != "0" && "$original_airplane_mode" != "1" ]]; then
    printf 'Could not determine the original airplane-mode state.\n' >&2
    exit 1
fi
set_airplane_mode 1

run_phase "$SEED_METHOD"
seed_pid=$(
    {
        "${adb_command[@]}" shell pidof "$TARGET_PACKAGE" 2>/dev/null || true
    } |
        tr -d '\r[:space:]'
)

"${adb_command[@]}" shell am force-stop "$TARGET_PACKAGE"
if ! wait_for_stopped_process; then
    printf 'Target process survived am force-stop.\n' >&2
    exit 1
fi

if ! launch_output=$(
    timeout --foreground "$COLD_LAUNCH_TIMEOUT" \
        "${adb_command[@]}" shell am start -W \
        -n "${TARGET_PACKAGE}/${MAIN_ACTIVITY}" 2>&1
); then
    printf '%s\n' "$launch_output" >&2
    printf 'Cold MainActivity launch failed or timed out.\n' >&2
    exit 1
fi
printf '%s\n' "$launch_output"
if ! grep -q 'Status: ok' <<<"$launch_output"; then
    printf 'Cold MainActivity launch did not report Status: ok.\n' >&2
    exit 1
fi

cold_pid=$(
    {
        "${adb_command[@]}" shell pidof "$TARGET_PACKAGE" 2>/dev/null || true
    } |
        tr -d '\r[:space:]'
)
if [[ -z "$cold_pid" ]]; then
    printf 'Cold MainActivity launch did not create a target process.\n' >&2
    exit 1
fi
if [[ -n "$seed_pid" && "$cold_pid" == "$seed_pid" ]]; then
    printf 'Cold launch unexpectedly reused the pre-force-stop PID.\n' >&2
    exit 1
fi
if ! wait_for_marker_on_cold_ui; then
    printf 'Synthetic marker was not visible after the real cold launch.\n' >&2
    exit 1
fi

run_phase "$VERIFY_METHOD"

printf 'PASS: airplane-mode UI seed, force-stop, cold-launch UI, and local SQLCipher note persistence.\n'
