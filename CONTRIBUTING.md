# Contributing to Whereday

Thanks for your interest in contributing! Whereday is open source under the [AGPL-3.0](LICENSE), and we welcome bug reports, feature ideas, design feedback, and pull requests.

## Ways to contribute

- **Pick up a [good first issue](https://github.com/CodyBontecou/whereday/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)** — these are scoped and labelled to be approachable.
- **File a bug** — open an issue with reproduction steps, iOS version, and device.
- **Propose a feature** — start a thread in [GitHub Discussions](https://github.com/CodyBontecou/whereday/discussions) before opening a large PR, so we can align on scope.
- **Improve docs** — typo fixes and clearer explanations are always appreciated.
- **Hang out** — join the [Isolated Tech Discord](https://discord.gg/jNRWSSSz4N) and say hi in `#iso-me-chat`. Pick the **Whereday** option in onboarding to unlock the channel.

## Development setup

### Prerequisites

- macOS with **Xcode 15 or later**
- Apple Developer account (free tier is fine for local builds; paid for device installs)
- A physical iPhone for location features (the simulator can fake fixed coordinates but does not produce realistic visit detection or motion activity)

### Clone and open

```bash
git clone https://github.com/CodyBontecou/whereday.git whereday
cd whereday
open IsoMe.xcodeproj
```

### Configure signing

There are four targets — set the Team and Bundle Identifier on each:

| Target | Default Bundle ID | Notes |
|---|---|---|
| `IsoMe` | `com.bontecou.isome` | Main iOS app |
| `IsoMeWidgetExtension` | `com.bontecou.isome.Widget` | Live Activities + widgets |
| `IsoMeWatch` | `com.bontecou.isome.watchkitapp` | watchOS companion |
| `IsoMeWatchWidgetExtension` | `com.bontecou.isome.watchkitapp.Widget` | watchOS complications |

For local development, change the bundle ID prefix to your own (e.g. `com.yourname.isome`) on all four targets so signing doesn't conflict with the published build.

### App Group entitlement

The app, widget, and watch app share data via an App Group. Update the App Group identifier on each target's **Signing & Capabilities** tab to match your team prefix (e.g. `group.com.yourname.isome`). The corresponding string is read from `Shared/AppGroup.swift` — search for `group.com.bontecou.isome` and replace as needed.

### Build & run

1. Select the **IsoMe** scheme.
2. Choose your physical device.
3. ⌘R to build and run.
4. On first launch the app requests **Location (Always)** and **Motion & Fitness** permissions — both are required to exercise visit detection and auto-start.

### Run tests

Pull requests run the shared `IsoMe` scheme's XCTest suite in GitHub Actions. To run the same gate locally against the first available iPhone simulator:

```bash
SIMULATOR_UDID=$(xcrun simctl list devices available -j | jq -r \
  '[.devices | to_entries[] | select(.key | contains("iOS")) | .value[] | select(.isAvailable == true and (.name | startswith("iPhone")))] | .[0].udid')

xcodebuild test \
  -project IsoMe.xcodeproj \
  -scheme IsoMe \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID"
```

The current tests use isolated in-memory or system test state where possible. Tests that touch `UserDefaults` clean up their keys in `setUp`/`tearDown`, and Keychain tests write only to test-specific account names.

## Testing on TestFlight

If you have a paid Apple Developer account and want to dogfood your fork via TestFlight:

```bash
# Archive
xcodebuild -project IsoMe.xcodeproj \
  -scheme IsoMe \
  -configuration Release \
  -archivePath build/IsoMe.xcarchive \
  archive

# Export signed IPA
xcodebuild -exportArchive \
  -archivePath build/IsoMe.xcarchive \
  -exportPath build/ipa \
  -exportOptionsPlist exportOptions.plist
```

Then upload the IPA to App Store Connect via Transporter.app or `xcrun altool`.

## Maintainer App Store Connect release automation

Publishing a GitHub Release now runs `.github/workflows/deploy-asc.yml`, which archives the tagged commit and uploads the build to App Store Connect with `asc`.

Required repository secrets:

- `ASC_KEY_ID`
- `ASC_ISSUER_ID`
- `ASC_API_KEY_P8` — base64-encoded App Store Connect `.p8` key

Use a release tag like `v1.4.4`. The workflow strips the leading `v`, sets `MARKETING_VERSION` from the tag, asks ASC for the next safe iOS build number, archives `IsoMe`, and uploads the archive with `.asc/ExportOptions.plist`.

## Code style

- **SwiftUI-first** — prefer SwiftUI over UIKit unless a feature genuinely requires it.
- **No external dependencies** — the project deliberately uses only Apple frameworks. Adding a Swift Package needs a strong justification and discussion first.
- **Privacy by default** — no analytics, no telemetry, no network calls that ship user data off-device.
- **Match existing patterns** — read the surrounding files before adding a new model, service, or view.

## Tests

Run the iOS unit tests from the command line with:

```bash
xcodebuild test -project IsoMe.xcodeproj -scheme IsoMe -destination 'platform=iOS Simulator,name=iPhone 17'
```

`IsoMeTests/WebhookManagerTests.swift` uses a custom `URLProtocol` fixture to mock webhook HTTP responses. Queue responses with `MockWebhookURLProtocol.enqueue(statusCode:)`, inject the matching ephemeral `URLSession` into `WebhookManager`, and keep retry tests fast by passing a `sleep` closure that records requested backoff delays instead of waiting.

## Pull request workflow

1. Fork the repo and create a feature branch off `main`.
2. Keep PRs focused — one logical change per PR.
3. Include before/after screenshots for any UI change.
4. Run the app on a physical device and confirm tracking still works end-to-end.
5. Open the PR with a description that explains *why*, not just *what*.

## Code of conduct

Be kind. Assume good faith. Disagree with ideas, not people. If something feels off, email codybontecou@gmail.com.

## License

By contributing to Whereday you agree that your contribution will be licensed under the [AGPL-3.0](LICENSE).
