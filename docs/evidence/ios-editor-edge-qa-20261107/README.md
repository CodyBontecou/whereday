# iOS saved-place and country-stay edge QA — 2026-11-07

Used Argent on the existing iPhone 18 Pro fixture, iOS 27.0, UDID `9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A`. This pass focused on editor input/layout, draft loss, and failed country-stay persistence. Verification launches used English and a process-only webhook-disabled override. Existing simulator records were preserved; all exploratory edits were discarded.

## Fixed findings

### Long radius input pushes the entire saved-place form off screen

Typing 48 nines into the radius field (alongside its initial `150`) stretched the editor horizontally. Name/section labels, kind chips, and other controls were clipped. The text field's unconstrained `fixedSize()` forced the whole form to its ideal width.

The radius field now has a bounded width, allowing its text to scroll while editing and truncate when unfocused. The full input remains intact. Existing numeric validation and the preview's safe fallback remain in effect.

Argent entered the identical long value on the rebuilt app and checked both focused and unfocused states: the name, kind controls, MATCH RADIUS label, help text, coordinates disclosure, and save controls remain within the screen. See [before](radius-overflow-before.png) and [after](radius-overflow-after.png). This layout-only fix was verified live rather than with an implementation-mirroring unit test.

### Country search rejects whitespace-padded codes and names

Searching `  JP ` returned no results. The picker now trims surrounding whitespace/newlines before filtering localized names or country codes; whitespace-only searches restore the full list.

Argent repeated the exact input and selected Japan successfully without resetting the other draft values. See [before](country-whitespace-before.png) and [after](country-whitespace-after.png). Unit coverage includes padded lowercase codes, padded names, whitespace-only input, and unmatched Unicode.

### Country-stay cancellation silently loses edits

Before the fix, entering `QA unsaved - keep this note` and tapping Cancel immediately dismissed the editor with no confirmation. The country-stay editor now tracks a value snapshot after initialization, prompts before discarding edits, and disables interactive sheet dismissal while dirty. Returning from the country picker keeps the original snapshot rather than accepting edits as a new baseline.

Argent changed the country to Japan, entered the same note, and verified the [discard confirmation](stay-discard-confirmation.png). Dismissing that popover to keep editing preserved both values. A downward sheet-dismiss gesture also [retained the draft](stay-draft-retained.png). Explicit Discard Changes returned to Atlas with Japan still at 16 days. Reopening showed the original clean defaults, and cancelling that untouched editor dismissed immediately without a prompt.

### A failed country-stay save can leak changes or create duplicates later

Code-path testing exposed a persistence failure beyond the UI findings. Previously, the editor inserted a new stay or mutated an existing stay before calling `ModelContext.save()`, then only displayed the error if saving threw. The shared context retained those changes. An unrelated later save could commit a supposedly failed insert/update; retrying an insert could create two stays.

Moved the existing save operation into `CountryStayDraft.save`, then reproduced this behavior with injected commit failures and real in-memory SwiftData contexts. Both failure cases went red before recovery was added (8 failed assertions): a failed insert leaked into a later save and duplicated on retry, and a failed update retained all changed fields in memory and storage.

The save operation now snapshots original stored values, restores all fields exactly on failed updates, and removes a failed insert from the context before rethrowing. It restores only its own operation, preserving unrelated pending edits. Successful retries keep the existing identity or create exactly one new stay. Notes retain existing trimming behavior. No storage-failure condition was forced on the simulator's existing database.

## Tests

All runs used the required signed runner and explicit simulator destination.

| Run | Result | Bundle |
| --- | --- | --- |
| Extracted original persistence behavior with failure-injection tests | 2 failure tests failed as expected; search test passed | `artifacts/test-results/IsoMe-20261107-213115-4319.xcresult` |
| Final editor changes plus existing country-day and saved-place tests | **37 passed, 0 failures** | `artifacts/test-results/IsoMe-20261107-213223-8615.xcresult` |

Final command:

```sh
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=9A45202D-BD45-4AF2-8B8E-CA0BE5F2F07A' \
  -only-testing:IsoMeTests/CountryStayEditorTests \
  -only-testing:IsoMeTests/CountryDayTests \
  -only-testing:IsoMeTests/SavedPlaceManagementTests
```

The final run contains 4 CountryStayEditorTests, 22 existing CountryDayTests, and 11 existing SavedPlaceManagementTests. The new tests check failed insert cleanup and retry, failed update restoration and retry, preservation of unrelated pending changes, and country-search edge inputs. `git diff --check` passes. The full suite was not rerun for this pass.

## Argent replay limitation

Live accessibility checks and screenshots verified the UI fixes. Saved replay remains blocked by the native hierarchy/selector mismatch observed in earlier passes. On the rebuilt app, `native-find-views` successfully found UIKit tab buttons labelled Places, while the recorder captured a Places tap as Map. After correcting the captured label, replay launched and passed its raw AX wait, then failed with `no visible element matched selector text="Places"`.

The [replay diagnostic](editor-replay-blocked.yaml) is archived outside the active e2e folder. It is not a passing regression test. No coordinate fallback was used to claim a portable replay pass. The successful evidence is the signed tests and direct Argent accessibility/visual checks described above.
