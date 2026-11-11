#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Keep the original class selection and collect device evidence before the
# emulator action tears down a failed/incomplete instrumentation run.
[[ $# == 1 ]] || { echo 'Expected the complete comma-separated UI class selection' >&2; exit 2; }
selection=$1
IFS=, read -r -a classes <<< "$selection"
for class in "${classes[@]}"; do
    [[ "$class" =~ ^com\.bontecou\.whereday\.[A-Za-z0-9_]+$ ]] || exit 2
done
baseline_serial=$(adb get-serialno)
[[ "$baseline_serial" =~ ^emulator-[0-9]+$ ]] || { echo 'A single disposable emulator is required' >&2; exit 2; }
reports=build/reports/ui-baseline-diagnostics
mkdir -p "$reports"
capture() {
    baseline_status=$?
    trap - EXIT
    python3 - "$baseline_serial" "$reports" <<'PY' || true
from pathlib import Path
import subprocess
import sys

adb = ['adb', '-s', sys.argv[1]]
commands = [
    ('logcat.txt', ['logcat', '-d', '-b', 'main', '-b', 'system', '-b', 'crash', '-b', 'events']),
    ('app-exit-info.txt', ['shell', 'dumpsys', 'activity', 'exit-info', 'com.bontecou.whereday.mapqa']),
    ('app-memory.txt', ['shell', 'dumpsys', 'meminfo', 'com.bontecou.whereday.mapqa']),
]
for name, args in commands:
    with (Path(sys.argv[2]) / name).open('w') as stream:
        try:
            subprocess.run(adb + args, stdout=stream, stderr=subprocess.STDOUT, timeout=15)
        except (subprocess.TimeoutExpired, OSError) as error:
            print(f'Diagnostic unavailable: {error}', file=stream)
PY
    exit "$baseline_status"
}
trap capture EXIT
./gradlew :app:connectedMapQaAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$selection" --no-daemon
