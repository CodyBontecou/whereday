# New catalog entries after the main refresh

Prepared from published main **2958387f81923206b547a5dab782cc5b39278d6d**. No feature PR was merged, rebased, force-pushed or updated by this work.

## Actual defect and corrected inventory

The existing audit prints only its first100 failures. The earlier report of100 missing values was therefore incomplete. Running the unchanged audit against all nine original catalogs at this exact base reports **465 catalog errors**, all missing values:

| Catalog | New keys missing locale values | Values added |
| --- | ---: | ---: |
| `IsoMe/Localizable.xcstrings` | 36 | 391 |
| `IsoMe/InfoPlist.xcstrings` | 2 | 20 |
| `IsoMe/AppShortcuts.xcstrings` | 1 | 10 |
| `IsoMeWatch/Localizable.xcstrings` | 4 | 44 |
| **Total** | **43** | **465** |

The complete inventory, rather than the truncated console excerpt, determines the fix. All existing entries, English wording, nonempty translations and catalog metadata remain unchanged. Removing just the new insertions recovers every original byte in each catalog. No stale entries were removed and no approved translations were regenerated. Apparent deleted lines in Git's diff are alignment around similar new blocks; semantic and byte-recovery checks verify preservation.

## Corrections and boundaries

New copy only was translated. Machine candidates were reviewed and corrected: data export is not cargo export; the map becomes the home *screen*, not a house; GPS recording is not audio/video recording; route points are not reward points; the day panel is not a spreadsheet. Import errors retain literal `departed_at`/`departedAt` fields. Whereday, Scrawl and the Siri `${applicationName}` slot remain intact. New Watch wording is copied from the same new iPhone messages, without protocol, ownership or runtime changes.

Actual Scrawl microphone copy still describes **audio narration**; the location-recording corrections do not alter that permission's meaning. This work does not request either permission.

The Check In phrase uses a real catalog **`stringSet`**, not a `stringUnit`. The first candidate used the wrong representation; the new regression caught it before commit. Corrected compiled `AppShortcuts.strings` contains the actual generated `#!SET#!_Check in with ${applicationName}[0]` keys. No Shortcuts editor/voice activation acceptance is inferred from resource compilation.

No native-speaker certification, full localized screen/large-text/motion review, spoken-device acceptance or new Watch owner approval is claimed. Existing Watch/localization owner acceptance is not reopened.

## Executed verification

- Original nine-catalog audit at exact base: **465 errors**, not100. All original catalogs were copied into a temporary root and passed to the existing unmodified audit.
- New five-method app regression initially fails (67 failed assertions/subtests); app inventory independently identifies391 missing values. Adding auxiliary cases catches74 more missing values and then catches the incorrect Siri `stringUnit` schema.
- Final **17 Python localization tests pass**. Full audit: **2,266 active keys / nine catalogs / 11 locales**, zero missing values or placeholder mismatches. Existing equivalent-to-English reports remain visible; they are not linguistic approval.
- Android localized resource generator `--check`: **1,730 resources / ten locale packs**; nine resource tests pass. No Android generated resource or coverage metadata change.
- Signed full iOS run via `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9DBF4207-832B-4A34-88DB-AE841A6383DA'`: **491 XCTest +20 Swift Testing**, zero failures. Result `artifacts/test-results/IsoMe-20261114-095504-50777.xcresult`; log `artifacts/merge-readiness/main-new-catalog-full.log`.
- **465 actual compiled resource lookups pass** in the built iPhone bundle, InfoPlist/Siri tables and embedded Watch bundle. The checker uses explicit missing sentinels and compares compiled values to catalog values. It checks only the465 newly filled values; the five pre-existing source-English entries and existing Siri source phrase retain their original metadata/fallback behavior.
- Counterfactual: compile the **four exact original base catalogs** using `xcrun xcstringstool compile` into a separate resource bundle. The same compiled checker fails `ar:%@ country stays · %@ photo matches`. This is a resource-level counterfactual, not a second full app/runtime build.
- Fourteen iOS runner guardrail tests pass. Existing signed runner and30-minute CI limit unchanged. The existing XCTest workflow now runs the full catalog audit/Python localization tests before the original signed run, then the exact compiled checker command. That exact new shell step also passes locally.

The manifest is scoped to the43 newly uncovered keys/465 missing locale values, not overall feature or merge readiness. The full audit separately checks all active keys. No test/assertion deadline, original test selection, production Swift implementation, public backup format or access policy was changed.

## Artifacts and environment

Adjacent `verification.json` records source/resource hashes and preservation checks; original/green audit and compiled failure/success logs are retained. Raw generator/review inputs and all intermediate failures remain under `/tmp/whereday-merge-readiness/`.

The explicit owned test host9DB was used, never a physical/default/UI-only guest. Final readback verifies it Shutdown; a redundant shutdown command reported already Shutdown, not a new failure or a claimed successful shutdown operation. No UI acceptance is inferred from the test-host state.

Current #113/#119/#120 branch conflicts still require an authorized sync with main and their own integrated verification. Preparing this base catalog correction does not resolve those conflicts by itself.
