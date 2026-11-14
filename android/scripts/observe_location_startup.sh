#!/usr/bin/env bash
# Diagnostic branch only: no input, focus repair, retries, or deadline changes.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
reports=build/reports/location-startup
mkdir -p "$reports"
serial=$(adb get-serialno)
avd=$(adb -s "$serial" emu avd name)
avd=${avd%%$'\n'*}; avd=${avd%$'\r'}
if [[ $serial != emulator-* || $avd != test ]]; then
    echo 'Expected the action-owned test emulator' >&2; exit 2
fi
if [[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') != 1 ]]; then
    echo 'Expected an emulator, not a physical device' >&2; exit 2
fi
printf 'serial=%s\navd=%s\n' "$serial" "$avd" > "$reports/identity.txt"
sampler=''
snapshot() {
    local phase=$1
    date -u +%FT%TZ >> "$reports/$phase-time.txt"
    # All targets are the disposable action-created synthetic guest. Each
    # observer is bounded and cannot dismiss an ANR or change foreground state.
    timeout 15 adb -s "$serial" shell dumpsys activity lastanr > "$reports/$phase-lastanr.txt" 2>&1 || true
    timeout 15 adb -s "$serial" shell dumpsys cpuinfo > "$reports/$phase-cpuinfo.txt" 2>&1 || true
    timeout 15 adb -s "$serial" shell dumpsys window windows > "$reports/$phase-window.txt" 2>&1 || true
    timeout 15 adb -s "$serial" logcat -b events -d -v threadtime -s am_anr > "$reports/$phase-anr-events.txt" 2>&1 || true
}
finish() {
    local status=$?
    trap - EXIT
    snapshot final
    if [[ -n $sampler ]]; then kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true; fi
    printf '%s\n' "$status" > "$reports/exit-status.txt"
    exit "$status"
}
trap finish EXIT
# Host-only scalar samples, no commands/environment/token-bearing process args.
python3 -u -c '
import json,pathlib,time
while True:
    mem = dict(line.split(":",1) for line in pathlib.Path("/proc/meminfo").read_text().splitlines())
    print(json.dumps({"monotonic":time.monotonic(), "load":pathlib.Path("/proc/loadavg").read_text().split()[:3], "cpu":pathlib.Path("/proc/stat").read_text().splitlines()[0], "availableKiB":int(mem["MemAvailable"].split()[0])}), flush=True)
    time.sleep(5)
' > "$reports/host-samples.jsonl" &
sampler=$!
snapshot before-build
./gradlew :app:assembleMapQa :app:assembleMapQaAndroidTest --no-daemon
snapshot after-build
# Exact original nine-case selection, order, guards, driver and timeout policy.
for mode in route visits both; do
    for termination in kill force-stop reboot; do
        python3 scripts/verify_location_process_recovery.py \
            --adb "$(command -v adb)" --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \
            --serial "$serial" --avd "$avd" \
            --apk app/build/outputs/apk/mapQa/app-mapQa.apk \
            --test-apk app/build/outputs/apk/androidTest/mapQa/app-mapQa-androidTest.apk \
            --mode "$mode" --termination "$termination" \
            --output "build/reports/location-recovery/$mode-$termination"
    done
done
