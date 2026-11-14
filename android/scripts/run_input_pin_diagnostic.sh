#!/usr/bin/env bash
# Diagnostic branch only. The action executes each script line in a new shell,
# so identity, original status and capture must live together in this file.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
reports=build/reports/input-pin
mkdir -p "$reports"
diag_serial=$(adb get-serialno)
diag_avd=$(adb -s "$diag_serial" emu avd name)
diag_avd=${diag_avd%%$'\n'*}
diag_avd=${diag_avd%$'\r'}
printf 'serial=%s\navd=%s\n' "$diag_serial" "$diag_avd" > "$reports/identity.txt"
collector=''
capture() {
    diag_status=$?
    trap - EXIT
    if [[ -n $collector ]]; then
        kill "$collector" 2>/dev/null || true
        wait "$collector" 2>/dev/null || true
        cp "$reports/observations-live.log" "$reports/observations.log"
    else
        adb -s "$diag_serial" logcat -d -v brief -s System.out:I '*:S' | grep '\[DEBUG-input-pin\]' > "$reports/observations.log" || true
    fi
    if [[ $diag_status == 0 ]] && ! grep -q original-initial-pin-count "$reports/observations.log"; then
        echo 'No original first-pin observation captured' >&2
        diag_status=2
    fi
    exit "$diag_status"
}
trap capture EXIT
if [[ ${INPUT_PIN_SCOPE:-input-diagnostic} == input-full-diagnostic ]]; then
    # The existing canonical workflow remains the single source of the full
    # selection. Its original wrapper enforces 94 classes/594 outcomes/two ignores.
    full_selection=$(python3 - <<'PY'
from pathlib import Path
import re
workflow = Path('../.github/workflows/android-heavy-tests.yml').read_text()
commands = re.findall(r'^\s+bash scripts/run_ui_baseline\.sh (\S+)$', workflow, re.M)
assert len(commands) == 1
assert len(commands[0].split(',')) == 94
print(commands[0])
PY
)
    # Keep early observations before an hour of later tests can evict logcat.
    # This passive collector is diagnostic-only and may itself affect timing.
    adb -s "$diag_serial" logcat -v brief --regex='\[DEBUG-input-pin\]' -s System.out:I '*:S' > "$reports/observations-live.log" &
    collector=$!
    bash scripts/run_ui_baseline.sh "$full_selection"
    exit 0
fi
./gradlew :app:assembleMapQa :app:assembleMapQaAndroidTest --no-daemon
selection=com.bontecou.whereday.RoadAttributionRenderingTest,com.bontecou.whereday.GeoapifyRoutingConfigurationTest,com.bontecou.whereday.PhotoDotRenderingTest,com.bontecou.whereday.SoftwareNoticesTest,com.bontecou.whereday.SoftwareNoticesNavigationTest,com.bontecou.whereday.RoadConsentScreenTest,com.bontecou.whereday.RoadDisplayProducerTest,com.bontecou.whereday.RoadMapRenderingTest,com.bontecou.whereday.NativeMapInputReadinessTest
python3 scripts/run_qa_instrumentation.py \
    --adb "$(command -v adb)" --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \
    --serial "$diag_serial" --avd "$diag_avd" --module app \
    --classes "$selection" --expected-tests 38 --output "$reports/native"
