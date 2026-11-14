# Smart Start localization slice — 2026-11-14

PR #120 stays draft. This completes missing catalog resources for the new Settings/Shortcut-source slice, not issue #60's device/automation/accessibility contract.

## Missing-resource RED → GREEN

A source-driven inventory covers every literal in `SmartStartSettingsView.swift` except stable accessibility IDs, plus the seven new source/description/error literals in `IsoMeIntents.swift`. Existing common labels are included, not assumed covered.

Actual initial run: three tests, two failures, **35 missing keys / 385 missing locale values**. The source inventory itself passed. Added 35 keys in `IsoMe/Localizable.xcstrings` for all supported locales: ar, bn, en, es, fr, hi, ja, pl, pt-BR, ru, zh-Hans. The **2,380 existing entries and every pre-existing catalog byte** are unchanged; no owner-approved translation was regenerated or reopened. English product/permission text remains exact. Smart Start remains the feature name in all locales; French “Source” is naturally identical.

Candidate generation reused the repository's translator on public feature copy only. Candidates were not accepted blindly: semantic review corrected, among other things, Arabic force-quit mistranslated as quitting smoking, audio-recording words in Japanese/Chinese, classifier confidence mistaken for personal confidence, and the named Always permission confused with an adverb. Long permission, opt-in, ownership/battery and reliability passages were replaced. Recipe action titles match the **existing actual localized** Start Tracking/Stop Tracking App Intent titles. No native-speaker certification is claimed.

The repository catalog generator populated reviewed values. An initial guarded invocation refused 183 unrelated refresh requests before writing; the subsequent scoped generation pinned existing values and asserted complete old-entry equality. Its whole-file formatting normalization was discarded in favor of retaining the original old bytes and inserting only the generated new block. The final diff adds only those35 entries. Temporary candidates/review/generation scripts remain under `/tmp/whereday-merge-readiness/`; they are not runtime inputs.

Final source checks:

- **14 localization Python tests**, including five new Smart Start tests: source inventory, all keys/locales, placeholders/exact English/unexpected fallback, actual recipe action names, and regression checks for observed mistranslation classes.
- Full existing audit: **2,606 active keys across nine catalogs and11 locales**, no missing translations or placeholder mismatches. Its reported English-equivalent counts are retained, not hidden or treated as linguistic approval.
- **31 actual Android CI `--check-source` commands pass**; no provenance fixtures changed.

## Actual compiled lookup acceptance

`scripts/check_smart_start_localization.swift` checks the real compiled app with Foundation `Bundle.localizedString`: **47 current view/intent keys ×11 locales =517 exact lookups**, all passing. It requires every language bundle and compares each result to that locale's catalog value. A missing bundle or fallback sentinel fails.

Actual counterfactual: the preserved pre-localization app `/tmp/whereday-merge-readiness/smart-settings-final.app` fails immediately at `ar:ACTIVITIES: __MISSING_SMART_START__`. Thus catalog presence alone cannot satisfy this check.

The existing `IsoMe XCTest` workflow now runs the localization tests/audit, the unchanged signed iOS test runner, and the compiled Bundle checker using the app path resolved from simulator build settings. YAML/shell syntax and the actual build-settings/lookup command were tested locally. New hosted acceptance remains pending.

## Observed RTL shaping defect and bounded correction

Actual Arabic launch on the separate UI-only owned simulator exposed separated letters in the custom-monospace/tracked captions (`ar-top.png`). Merely removing letter spacing **did not fix it** (`ar-rtl-top.png`); this intermediate result is not called a pass. The custom IBM Plex Mono caption also needed replacement for connected-script rendering.

`BackPageHeader` and `BackPageLabel` now accept `preservesCursiveShaping`, default **false**. Only the new Smart Start page passes true for RTL layout, selecting a system caption without extra tracking. Existing screens retain exactly their old default font/spacing. The final Arabic screenshot `ar-system-caption-top.png` was actually inspected: joined section/caption letters, mirrored Back/control layout, readable wrapped permission/reliability text. This is sampled visual evidence, not an automated glyph/pixel regression or a claim about uninspected languages.

## Signed tests and scoped simulator observations

Final source after the system-caption correction:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9DBF4207-832B-4A34-88DB-AE841A6383DA'
```

**585 XCTest +20 Swift Testing**, zero failures. Result `artifacts/test-results/IsoMe-20261114-073246-94095.xcresult`, log `artifacts/merge-readiness/smart-localization-system-caption-full.log`. Earlier catalog-only and spacing-only builds also passed tests, but neither substitutes for inspection of the final font correction.

A separate UI-only iPhone17Pro/iOS26.5 guest `EEBF0516-FB5A-42E8-9759-CCDA6D2139F5` never ran XCTest. Actual Archive → Settings → Smart Start navigation with per-process Spanish/Arabic language arguments verified:

- Spanish translated page/confirmation; cancel returns actual switch value0.
- Arabic RTL final page/confirmation; cancel returns actual switch value0.
- Inspected Spanish dark/accessibility-extra-large permission text and Arabic dark/accessibility-extra-large timing controls, including native localized duration labels.
- No sensor permission prompt was observed on these open/cancel paths. Neither localized path confirmed enable or purports to test unavailable physical motion.

Argent tools were unavailable on discovery. AXe/simctl drove the explicit owned guest; no Argent replay/YAML, spoken accessibility, full-motion/60fps or complete all-state/all-language UI pass is claimed. The Spanish samples preceded the RTL-only font correction; its LTR default code path remains unchanged. Raw semantic captures and intermediate/final images are in `/tmp/whereday-merge-readiness/smart-localization-ui-1/`. Adjacent `verification.json` retains actual Off checkpoints, source/catalog hashes and coverage counts.

Appearance/content size restored and read back **light/large**, app terminated, UI guest shut down. Test host also confirmed shut down. No default or physical device was operated.

## Still open

Full localized UI/VoiceOver/motion inspection (including the remaining languages and states), actual Shortcuts editor/locked/background automation, duration/profile design, optional onboarding, real permission/wake/reboot/battery/false-positive matrix and physical runtime acceptance remain. Existing Watch/localization owner acceptance is preserved; no repeat owner QA was requested. This slice is not whole-PR merge readiness or goal completion.
