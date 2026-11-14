#!/bin/bash
# Shared local/CI iOS test entry point. Simulator Keychain tests require signing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail() { echo "test-ios: $*" >&2; exit 2; }
usage() {
    echo "Usage: scripts/test-ios.sh [--destination 'platform=iOS Simulator,id=UDID']"
    echo "       [--result-bundle-path PATH] [-only-testing:TARGET/CLASS[/METHOD]] [-skip-testing:TARGET/CLASS[/METHOD]]"
}

DESTINATION=""
RESULT_BUNDLE="artifacts/test-results/IsoMe-$(date +%Y%m%d-%H%M%S)-$$.xcresult"
XCODE_ARGS=(test -project IsoMe.xcodeproj -scheme IsoMe -configuration Debug)

# XCODE_XCCONFIG_FILE overrides even command-line build settings; reject it before invoking Xcode.
if [[ "${XCODE_XCCONFIG_FILE+x}" == x ]]; then
    fail "Unset XCODE_XCCONFIG_FILE before running tests: it overrides command-line build settings and can disable simulator signing."
fi
# Reject other signing overrides and explicitly enable signing for this invocation.
if [[ -n "${CODE_SIGNING_ALLOWED:-}" && "${CODE_SIGNING_ALLOWED}" != YES ]]; then
    fail "Simulator Keychain tests require signing. Unset CODE_SIGNING_ALLOWED; this runner sets it to YES."
fi
while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage; exit 0 ;;
        --destination|--result-bundle-path)
            [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || fail "$1 requires a value"
            if [[ "$1" == --destination ]]; then DESTINATION="$2"; else RESULT_BUNDLE="$2"; fi
            shift 2 ;;
        -only-testing:?*|-skip-testing:?*) XCODE_ARGS+=("$1"); shift ;;
        CODE_SIGNING_ALLOWED=*) fail "Signing overrides are not supported. Simulator Keychain tests require CODE_SIGNING_ALLOWED=YES." ;;
        *) fail "Unsupported argument: $1 (see --help)" ;;
    esac
done

if [[ -z "$DESTINATION" ]]; then
    DEVICE_JSON="$(xcrun simctl list devices available -j)"
    UDID="$(printf '%s' "$DEVICE_JSON" | python3 -c '
import json, sys
candidates = [device for runtime, devices in json.load(sys.stdin)["devices"].items()
              if ".iOS-" in runtime for device in devices
              if device.get("isAvailable") and device["name"].startswith("iPhone")]
candidates.sort(key=lambda device: device.get("state") != "Booted")
print(candidates[0]["udid"] if candidates else "")
')"
    [[ -n "$UDID" ]] || fail "No available iPhone simulator. Install an iOS runtime in Xcode or pass --destination for an existing simulator."
    DESTINATION="platform=iOS Simulator,id=$UDID"
fi
[[ "$DESTINATION" == "platform=iOS Simulator,"* ]] || fail "--destination must target platform=iOS Simulator"
[[ ! -e "$RESULT_BUNDLE" ]] || fail "Result bundle already exists: $RESULT_BUNDLE (choose a new path)"
mkdir -p "$(dirname "$RESULT_BUNDLE")"
echo "Testing IsoMe (Debug) on $DESTINATION with simulator signing enabled."
echo "Result bundle: $RESULT_BUNDLE"
exec xcodebuild "${XCODE_ARGS[@]}" \
    -destination "$DESTINATION" \
    -resultBundlePath "$RESULT_BUNDLE" \
    CODE_SIGNING_ALLOWED=YES
