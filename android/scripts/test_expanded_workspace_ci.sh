#!/usr/bin/env bash
set -euo pipefail
# This script is for the disposable, single-emulator GitHub Actions job only.
[[ "${GITHUB_ACTIONS:-}" == true ]] || { echo 'Use the owned-AVD QA runner outside GitHub Actions.' >&2; exit 2; }
workspace_ci_devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
[[ "$workspace_ci_devices" =~ ^emulator-[0-9]+$ ]] || { echo 'Expected exactly one CI emulator and no physical devices.' >&2; exit 2; }
workspace_ci_serial="$workspace_ci_devices"
[[ "$(adb -s "$workspace_ci_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 2
workspace_ci_size="$(adb -s "$workspace_ci_serial" shell wm size)"
[[ "$workspace_ci_size" != *Override* ]] || exit 2
workspace_ci_density="$(adb -s "$workspace_ci_serial" shell wm density)"
[[ "$workspace_ci_density" != *Override* ]] || exit 2
workspace_ci_ime="$(adb -s "$workspace_ci_serial" shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')"
workspace_ci_restore() {
  local workspace_ci_result=$?
  adb -s "$workspace_ci_serial" shell wm size reset || workspace_ci_result=1
  adb -s "$workspace_ci_serial" shell wm density reset || workspace_ci_result=1
  if [[ "$workspace_ci_ime" == null ]]; then
    adb -s "$workspace_ci_serial" shell settings delete secure show_ime_with_hard_keyboard || workspace_ci_result=1
  else
    adb -s "$workspace_ci_serial" shell settings put secure show_ime_with_hard_keyboard "$workspace_ci_ime" || workspace_ci_result=1
  fi
  return "$workspace_ci_result"
}
trap workspace_ci_restore EXIT
adb -s "$workspace_ci_serial" shell wm size 2200x1600
adb -s "$workspace_ci_serial" shell wm density 200
ANDROID_SERIAL="$workspace_ci_serial" ./gradlew :app:connectedMapQaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bontecou.whereday.WorkspaceAnchorClockTest,com.bontecou.whereday.WorkspaceCallerDialogTest,com.bontecou.whereday.WorkspaceDialogTest,com.bontecou.whereday.InlineVisitInspectorTest \
  -Pandroid.testInstrumentationRunnerArguments.useSystemDensity=true --no-daemon
mkdir -p build/reports/app-qa-snapshots
cp -R app/build/outputs/androidTest-results/connected/mapQa build/reports/app-qa-snapshots/expanded-workspace

# A real software keyboard must leave the short tabletop date input/actions reachable.
adb -s "$workspace_ci_serial" shell wm size 2200x1000
adb -s "$workspace_ci_serial" shell settings put secure show_ime_with_hard_keyboard 1
ANDROID_SERIAL="$workspace_ci_serial" ./gradlew :app:connectedMapQaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bontecou.whereday.WorkspaceKeyboardTest --no-daemon
cp -R app/build/outputs/androidTest-results/connected/mapQa build/reports/app-qa-snapshots/short-workspace-keyboard

# MainActivity uses Android's default configuration recreation. Exercise actual
# compact/expanded display changes, independent of Compose density overrides.
workspace_ci_avd="$(adb -s "$workspace_ci_serial" shell getprop ro.boot.qemu.avd_name | tr -d '\r')"
[[ -n "$workspace_ci_avd" ]] || exit 2
adb -s "$workspace_ci_serial" shell wm size 800x1600
ANDROID_SERIAL="$workspace_ci_serial" ./gradlew :app:connectedMapQaAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bontecou.whereday.WorkspaceActivityResizeTest \
  -Pandroid.testInstrumentationRunnerArguments.allowWorkspaceResize=true \
  "-Pandroid.testInstrumentationRunnerArguments.expectedResizeAvd=$workspace_ci_avd" --no-daemon
cp -R app/build/outputs/androidTest-results/connected/mapQa build/reports/app-qa-snapshots/actual-workspace-resize
