#!/bin/bash
# Points a simulator's StoreKit at the local IsoMe.storekit catalog for com.bontecou.isome,
# with no purchases and purchase dialogs off, for Argent e2e tests that buy (see
# .argent/e2e/onboarding-free-week.yaml). Launch the app with --use-storekit afterwards.
# Runs one skipped-by-default XCTest through scripts/test-ios.sh, which also installs the app.
set -euo pipefail
[[ $# -eq 1 ]] || { echo "Usage: scripts/qa-storekit-session.sh <simulator udid>" >&2; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export TEST_RUNNER_ISOME_QA_STOREKIT_SESSION=1
exec "$ROOT/scripts/test-ios.sh" --destination "platform=iOS Simulator,id=$1" \
    -only-testing:IsoMeTests/StoreKitQASessionTests
