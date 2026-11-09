# Sustained Android edge-case QA

November 8, 2026. Only the assigned API35 emulator-5554 / whereday-redesign-api35 and `com.bontecou.whereday.mapqa` were driven. The owner app and other agents’ devices were untouched. Android changes remained disjoint from the coordinated Whereday iOS work; native builds used explicit Herdr handoffs.

## Confirmed fixes

- [Saved CSV review lost on rotation](../android-saved-csv-herdr-20261108/README.md): retain completed review in the process AppGraph and invalidate stale picker/read callbacks. Two unchanged full47-check regressions passed, plus focused callback tests and app/domain/export suites.
- [Lifetime-only access descriptions](../android-access-copy-herdr-20261108/README.md): correct Automatic exports policy/failure and Automation scope descriptions for all verified plans, including reviewed Spanish resources. Localization, lint/build, direct live checks and two unchanged full27-step regressions passed.

## Coverage

| Area | Exercised boundary and outcome |
| --- | --- |
| Places/CSV | BOM, Unicode, multiline quoting, invalid numeric rows and duplicate planning; completed review rotation/navigation; cancel confirmation/discard. Duplicate case-insensitive headers and empty files reject without writes. |
| Map/current location | Accepting an actual synthetic device fix was optional; cancellation kept both coordinate fields empty and recording stopped. |
| Map/local search | Long Arabic/Japanese/emoji/combining-character paste produced an empty-result state and closed cleanly. |
| Manual visit | Large mixed-Unicode draft text retained exactly through both rotations, Home/foreground, Keep editing and discard. No visit saved. |
| Atlas | Previous year, current year and disabled future-year boundary. Earlier conversation checks cover month recap/DST and empty-month behavior. |
| Archive | Existing25 route points retained after navigation and restart. Earlier conversation checks cover malformed/mixed imports, preview, export date/clock boundaries. |
| Permissions/photos | OS app-info round trip; photo-picker and document-picker cancellation kept0 photo references; denied library access left auto-sync disabled. No runtime grants changed. |
| Offline policy | Disable network geocoding; online place search explains restriction; offline coordinate map remains reachable; cancellation/discard is safe. Preference persists over restart and is restored to enabled. |
| Automatic exports | Disabled schedule/no folder/no successful export baseline; verified-plan wording. No run or schedule activation. |
| Automation | Absent companion app, repeated install checks and disabled scope/save taps leave access disabled. No grants. |
| Webhooks | Synthetic HTTP endpoint draft, rotation, leave confirmation and discard. No save/test/send; URL validation branch was not exercised. |
| Other earlier coverage | Country-stay picker anchors/rotation, saved-place numeric validation including NaN, layer/road navigation and settings notices rotation. See earlier Android evidence directories. |

## Verification and limits

The completion criterion was met after both fixes: two distinct post-last-fix broad sweeps found no new actionable bugs. Sweep1 passed128 executable actions/checks in1m36s ([log](broad-sweep-1.txt)). Sweep2 passed124 in1m38s and an unchanged repeat passed124 in1m35s ([pass1](broad-sweep-2-pass-1.txt), [pass2](broad-sweep-2-pass-2.txt)). The local-search idle step warned about a changing small region while its text caret was active; an explicit empty-result check had already passed. No loading result relied on idle alone.

The focused native command for the CSV fix was `./gradlew :platform:testDebugUnitTest --tests com.bontecou.whereday.platform.SavedPlaceImportReviewTest :export:test :domain:test :app:testDebugUnitTest :app:lintDebug :app:assembleMapQa` (exit0,8m48s). The access-copy update passed `./gradlew :app:lintDebug :app:assembleMapQa` (exit0,23s). `python3 android/scripts/generate_localized_resources.py --check` verified1680 resources/one locale; `git diff --check` passed. Detailed native counts, the removed redundant test, exact logs and focused replay results are in the two fix reports.

Restored portrait, network geocoding enabled ([fresh tree](restored-settings.txt)), stopped recording and no open draft. Final UI rechecked25 points; original one saved place and Test stop remain visible. Permission grants match baseline: notifications/fine/coarse granted, media images denied. No journal writes/import commits, grants or deliveries were performed. Only the three session-created CSV documents and their now-empty device folder were removed. Synthetic clipboard content was replaced with a blank (the tool rejects an empty clipboard paste). Other device files and settings were preserved.

Saved sweep YAMLs require the populated English mapQa fixture and2026 Atlas boundary. To replay the CSV paths, copy the bad-header/empty CSVs alongside this report and the odd CSV in the saved-review report into a QA-only Download folder on the assigned emulator so DocumentsUI can see them. Final cleanup removed those device copies; missing fixtures are prerequisites, not test passes. Raw authoring failures and the concise live ledger are in ignored `.pi/android-qa-loop-20261108`. Failed/unreadable tool checks are never counted as passing. A missing scroll before Automation in the second replay was a harness issue and was corrected without weakening an app assertion.

This is bounded exploratory evidence, not exhaustive proof. Real billing/purchases, external sharing, actual webhook/automatic-export delivery and an installed Automate integration were excluded. Nearby-business lookup is unconfigured in mapQa, so provider results were not tested. OS network loss/online retry and a fresh foreground-location denial prompt were not exercised; the geocoding-off policy and existing denied-photo path were exercised. Completed CSV reviews are process-owned, with no promised process-death durability or continuation of an interrupted document read. Intermittent emulator ANRs/stale helper trees required device-scoped recovery; no app-code deadlock was established.
