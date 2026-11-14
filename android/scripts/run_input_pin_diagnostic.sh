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
capture() {
    diag_status=$?
    trap - EXIT
    adb -s "$diag_serial" logcat -d -v brief -s System.out:I '*:S' | grep '\[DEBUG-input-pin\]' > "$reports/observations.log" || true
    if [[ $diag_status == 0 ]] && ! grep -q original-initial-pin-count "$reports/observations.log"; then
        echo 'No original first-pin observation captured' >&2
        diag_status=2
    fi
    exit "$diag_status"
}
trap capture EXIT
selection=com.bontecou.whereday.RoadAttributionRenderingTest,com.bontecou.whereday.GeoapifyRoutingConfigurationTest,com.bontecou.whereday.PhotoDotRenderingTest,com.bontecou.whereday.SoftwareNoticesTest,com.bontecou.whereday.SoftwareNoticesNavigationTest,com.bontecou.whereday.RoadConsentScreenTest,com.bontecou.whereday.RoadDisplayProducerTest,com.bontecou.whereday.RoadMapRenderingTest,com.bontecou.whereday.NativeMapInputReadinessTest
python3 scripts/run_qa_instrumentation.py \
    --adb "$(command -v adb)" --aapt2 "$ANDROID_HOME/build-tools/35.0.0/aapt2" \
    --serial "$diag_serial" --avd "$diag_avd" --module app \
    --classes "$selection" --expected-tests 38 --output "$reports/native"
