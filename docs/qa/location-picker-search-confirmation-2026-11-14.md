# Location selection confirmation during search

Hans reported that v2.0 could search for a location and place a pin, but offered no confirmation and would not save.

Source: https://discord.com/channels/1443424100759634003/1498086747123355699/1571001582483537993

## Cause and fix

While native SwiftUI search is active, iOS hides the location picker's navigation toolbar, including its Use action. Selecting a search result or placing a pin previously left search active. Both paths now dismiss search through its presentation binding, restoring Use without committing the location automatically. Cancel and the explicit confirmation step retain their behavior.

An unnamed manual pin still requires a name. Its validation message now appears directly below the name field instead of below the rest of the form.

## Reproduction and verification

Baseline: installed Whereday on iPhone 18 Pro, iOS 27.0, device 9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A. Fixed build: clean install on Whereday Reimagined Tests, iOS 27.0, device E96738E5-A399-4E1B-96BD-770C2293261A. Synthetic locations only; tracking remained off.

1. Open Places > Add Location > Choose Location.
2. Activate search, then choose Drop Pin at Map Center.
3. Assert that the button with accessibility label Use This Location is visible. The baseline failed this Argent await-ui-element check with cause=unmet after 1,001 ms; the fixed build passed in 119 ms. Screenshots confirmed the toolbar and keyboard state.
4. Tap Use. Verify Enter a location name appears directly below the blank name field. Enter Hans Pin Check and save.
5. Relaunch the app and return to Places. Hans Pin Check remains saved, with no visit created.
6. With Location Names enabled, repeat the picker path, search Ferry Building San Francisco, submit, and select Ferry Building. Use reappears, and confirmation fills the name and address. Tap Save place. Places shows 2 saved locations and 0 visits.

The Discord attachment initially returned HTTP 403. The user later supplied a local copy, which was audited. It shows ManualVisitSheet (Save Place) waiting for an accurate current location, the name Home already entered, and an unselected Drop a pin here row. It does not show the search or pin-selection step. The toolbar fix above addresses a related reproducible problem; it does not alone establish the cause of Hans's exact report.

## Checks

- Debug simulator build: passed.
- `scripts/test-ios.sh --destination 'platform=iOS Simulator,id=50225EBD-E03C-4094-B351-A859EF8B9FB8' -only-testing:IsoMeTests/SavedPlaceManagementTests`: 14 passed, 0 failures.
- `git diff --check`: passed.

The existing unit tests cover saved-place validation and persistence. They cannot catch native search hiding a SwiftUI toolbar; the live UI check above is the regression evidence for that behavior. No new unit test was added for private view state because it would not exercise the system presentation failure.

## Follow-up: explicit custom-location action

At the user's request, ManualVisitSheet now labels the row Save custom location, with Choose a place on the map as helper text. The row opens PlaceSelectionView and dismisses the inline search keyboard, rather than immediately choosing the preview centre. It remains available before the map camera has initialized. The picker starts from the preview's visible region and still requires explicit Use confirmation. The old preview crosshair and direct-pin reverse-geocoding task were removed; the picker handles pin lookup. Both new strings have translations for all 11 supported locales.

Verified on the clean Whereday Reimagined Tests simulator with location permission denied and network geocoding disabled: Save where I am > Save custom location > Drop Pin at Map Center > Use. The form showed a selected checkmark and Selected location ready. Entering Custom Home enabled Save; saving created a confirmed manual visit at the chosen coordinates. Debug build passed; localization_catalog.py audit passed with no missing translations or placeholder mismatches; git diff --check passed.
