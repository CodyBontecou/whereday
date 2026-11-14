# PR #113 current-main verification

Merged main `5344145e` without conflicts at `508f8980`. The existing subscription implementation, offline renewal distinctions, localization acceptance, and retained screenshot/native-test receipts remain unchanged. The uncommitted accessibility presentation-harness drafts in the fleet worktree are not included.

## Fresh checks

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A'
```

- **491 XCTest cases, zero failures; 20 Swift Testing tests passed.**
- Result bundle: `artifacts/test-results/IsoMe-20261113-224532-59997.xcresult`.
- Local log: `artifacts/merge-readiness/pr113-ios.log`.
- All **30** `--check-source` reference checks declared in `.github/workflows/android-tests.yml` passed.
- `git diff --check` passes.

## Remaining boundaries

These iOS/source checks do not establish Android subscription behavior, TalkBack speech, current Android build/lint/native gates, or current screenshots. Hosted checks must run on the pushed head. Issue #115 remains open: the retained earlier narrow English preview speech/focus observations do not cover actual Settings/Export owners, full static/heading traversal, large text, scrim dismissal or localized spoken workflows. Localization owner acceptance is unchanged.

Argent device tools are not exposed in the current session (tool discovery returned no matching device/emulator tools). Do not attach a UiAutomation helper to TalkBack and misreport its suppressed-service behavior as an app regression. Preserve the prior private audio-tooling receipts and the uncommitted QA harness for separate verified work; no new TalkBack pass is claimed here.
