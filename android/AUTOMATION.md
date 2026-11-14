# Android automation contract under implementation

Automate is the selected integration client. The public command Activity, explicit Settings consent screen, executor and private result provider are implemented. Isolated cross-UID transport tests pass; the installed Automate walkthrough and supported-device/lifecycle matrix remain open. This is a development contract, not release acceptance.

## Caller and approval

The intended client transport is Automate's [App decision](https://llamalab.com/automate/doc/block/activity_start_result.html), which waits for an Activity result. The Activity obtains the recipient from Android's [getCallingPackage/getCallingActivity](https://developer.android.com/reference/android/app/Activity#getCallingPackage()), rejects a missing recipient, and never trusts a package-name extra, referrer or callback component from the request. Incoming `FLAG_ACTIVITY_FORWARD_RESULT` is rejected. On API 35+, a known immediate caller differing from the result recipient is also rejected. A real different-UID fixture verifies successful result delivery, denied spoofing and rejection of a forwarded result token on API 37. The [Android 10 ActivityStarter source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/ActivityStarter.java) confirms that a forwarded result changes the result recipient; `getCallingPackage()` alone must not be treated as immediate-caller proof. Source inspection does not replace the still-required API 29 forwarding/device check.

`AutomationAccess` is default-off. An explicit approval pins the installed package, UID, first-install time and SHA-256 set of current APK signing certificates, with individual scopes. Reinstallation, a changed UID or signer set requires approval again. Approval state is private, versioned and atomically written; it contains no bearer token or location data. Corrupt, duplicate-key, unknown-version and unknown-scope documents fail closed. Approval changes freeze the reviewed identity and scope set before waiting for older work.

Every operation checks the current installed identity and approved scope. All instances in this process share one per-directory operation owner. Revoke/change cancels and joins active work and fences requests that were queued under the older epoch. A settings-write failure inhibits access in the current process and surfaces an error; do not report durable revocation until its write succeeds. There is no separate Android process in the implementation. The public UI and transport share this ownership model.

Scopes are `summary`, `visits`, `outings`, `renameVisits`, `renameOutings`, `recording`, `exports`, `places` and `manualVisits`. Existing approvals do not acquire either new scope; each requires explicit reviewed approval. Settings → Automation access offers individual scopes, a reviewed installed-identity confirmation, explicit save and disable confirmations, and a dirty-navigation guard. Opening the screen or selecting checkboxes does not grant access. The application-owned controller serializes consent changes; clear-all cancels and joins older queued consent before revoking access and removing results. The installed client identity is refreshed on app resume. Corrupt approval state offers a reset path without claiming that access status loaded successfully. Export authorization must also use the existing verified lifetime-access gate; a scope approval is not a purchase entitlement.

## Launcher shortcuts

After opening Whereday, supported launchers offer Start recording, Stop recording, Timeline and Export, subject to the launcher's shortcut limit. These dynamic shortcuts contain explicit Intents targeting the installed application ID, so the disposable mapQa build never opens the owner app. SINGLE_TOP/CLEAR_TOP preserve the existing main Activity instead of clearing its task. Labels/icons refresh after application updates and system locale changes, including known pinned shortcuts. Shortcut labels contain no journal data.

Start and Stop open a foreground confirmation; Start uses the same in-context location/notification flow as the recording button. An already-active Start cannot toggle recording off; an idle Stop reports that recording is stopped. First-run onboarding must finish before a pending shortcut confirmation or destination appears. Recreation restores a still-pending confirmation and never executes its command; a dismissed request stays dismissed. Timeline and Export select their native app destinations; opening Export does not grant file access or bypass the verified lifetime gate.

The public shortcut actions request app UI and are not authenticated automation commands. Apps that need data/results or direct scoped operations must use the for-result contract below. There is no declared Assistant capability or Siri-equivalent voice phrase/entity support; launcher visibility does not establish voice integration. See Android's [shortcut task/flag guidance](https://developer.android.com/develop/ui/compose/system/shortcuts/managing-shortcuts).

The shortcut milestone passes nine isolated API35 emulator tests (14.310 seconds) and a [recorded launcher replay](.argent/e2e/launcher-drawer-api35-20261028.yaml): nineteen passing actions/checks plus an echo in 16.0 seconds, no warnings or failures. The replay requires onboarded idle mapQa and English Pixel Launcher with the QA app visible in the drawer. It verifies revealing shortcuts, opening Start confirmation and canceling back to the journal. Native tests additionally cover warm navigation/recreation and onboarding deferral. This is not real-device, other-launcher, API29, voice, full-motion or comprehensive accessibility acceptance.

## Activity transport, version 1

Launch `com.bontecou.whereday.AutomationActivity` explicitly **for a result**, with action `com.bontecou.whereday.automation.RUN` and string extra `com.bontecou.whereday.automation.REQUEST`. The production application ID is `com.bontecou.whereday`; disposable QA uses `com.bontecou.whereday.mapqa`. No BROWSABLE route, background broadcast command or caller-package extra authorizes access. The Activity uses standard launch mode and waits at most fifteen seconds for window focus before executing.

A successful `RESULT_OK` Intent contains integer extra `com.bontecou.whereday.automation.VERSION` = `1`, the actual result MIME type and `data = content://<applicationId>.automation-results/<UUID>`. ClipData contains that same URI with `FLAG_GRANT_READ_URI_PERMISSION`. Read it through ContentResolver while access remains valid; large response bodies are not copied into extras.

A handled failure returns `RESULT_CANCELED`, the version extra and string extra `com.bontecou.whereday.automation.ERROR`:

| Code | Meaning |
| --- | --- |
| `not_authorized` | Missing/invalid recipient, identity, approval, scope or usable settings |
| `invalid_request` | Wrong action, forwarded result flag or malformed request |
| `open_app` | Recording needs a focused foreground Activity or an existing location grant |
| `purchase_required` | Verified lifetime export access is unavailable |
| `not_found` | Requested stored entity/current outing is unavailable |
| `cancelled` | User pressed Back or Cancel |
| `interrupted` | Recreation, substituted Intent or interrupted execution |
| `unavailable` | Other execution failure |

Android can cancel delivery or kill the process without these extras. Callers must tolerate missing replies and inspect state before retrying mutations. Errors do not include request names, coordinates or exception details. No automatic retry occurs on Activity recreation or `onNewIntent`.

The installed-client acceptance path is Automate's App decision followed by reading the returned content URI. On October 31, actual Automate 1.53.2 successfully requested and copied a summary result from mapQa after explicit summary-only approval. The manually authored QA flow subsequently looped because of incorrect canvas wiring, and was stopped and removed. This is actual-client read evidence, not a completed walkthrough or passing reusable regression. The complete flow, notification launch, other commands and supported-device/lifecycle acceptance remain open. Do not substitute the synthetic native client or a block-entry log line for those gates.

### Actual Automate setup findings, October 31

These inputs reached the real client/provider successfully; the complete flow still needs a clean, terminating acceptance run. For disposable QA, choose package `com.bontecou.whereday.mapqa` and activity `com.bontecou.whereday.AutomationActivity`. Production uses package `com.bontecou.whereday`. Set Action to `com.bontecou.whereday.automation.RUN`, with no forwarded-result flags. Assign Result URI to `resultUri` and Result extras dictionary to `resultExtras`.

The Extras input is an **Automate expression**, not the raw request object. An embedded JSON string must escape its opening brace because Automate otherwise treats it as [string interpolation](https://llamalab.com/automate/doc/value.html#text). This expression preserves the protocol's integer version:

```text
{"com.bontecou.whereday.automation.REQUEST": "\{\"version\":1,\"command\":\"statistics\",\"zone\":\"UTC\"}"}
```

In Content read, set Content URI to the expression `resultUri`. A hard-coded destination in an absent application directory failed with `NoSuchFileException` on the fresh client. Configure [File make directory](https://llamalab.com/automate/doc/block/file_make_directory.html) with `storage("external")` before the request, then use `storage("external", "whereday-qa-statistics.json")` for the Content read destination. The [storage function](https://llamalab.com/automate/doc/function/storage.html) resolves the client's directory rather than assuming an Android user/profile path. The observed result was copied to Automate's own external files directory with broad file access and battery exemptions still disabled.

Intended terminating graph: Flow beginning → File make directory → App decision; YES → Content read → end, NO → end. The QA canvas edit left a return connection from Content read to App decision; it must not be distributed as a ready-to-import flow. All seven authoring/correction takes are retained only as ignored diagnostics, outside the runnable e2e directory. No `.flo` fixture or successful full replay is claimed.

Show window directly if possible worked for the actual request while Automate was foreground; no overlay permission was granted during this test. The notification-based path remains unverified: the notification was visible, but the QA helper returned an empty shade tree even after a scoped restart. Whereday onboarding/settings also exposed stale helper trees, resolved for individual observations by scoped helper refreshes. These are limitations of the captured test evidence, not grounds to bypass consent or broaden permissions.

The actual copied 165-byte JSON contained version 1, command `statistics`, zero point/outlier/visit counts and distance, and inactive recording. The empty QA history had no location/media permissions. Private approval/result records verified the installed Automate UID, LlamaLab signer and summary scope. Copied bytes, hashes, installed identity and failed/corrective recording reports are retained under ignored `captures/automate-live-20261031/`. Neither this result nor a `Content read` log entry alone establishes that a flow terminated successfully.

## Request parser, version 1

Requests are strict JSON objects, at most 32,768 characters and eight nested levels. Duplicate keys, trailing input, unknown fields, wrong types, unsupported versions and incomplete date bounds are rejected. Version is an integer. Parameters and names are redacted from request `toString()`.

| Command | Scope | Additional fields |
| --- | --- | --- |
| `statistics` | `summary` | Optional paired `from`/`through` ISO instants; optional `zone` |
| `visits` | `visits` | Same date fields |
| `outings` | `outings` | Same date fields |
| `currentOuting` | `outings` | None |
| `savedPlaces` | `places` | None; all saved-place IDs/names |
| `openManualVisits` | `visits` | None; all open manual visits |
| `checkIn` | `manualVisits` | Optional `savedPlaceId`, `name`, paired numeric `latitude`/`longitude`, ISO `arrivedAt`, boolean `allowOverlap` (default false) |
| `checkOut` | `manualVisits` | Optional `id`, ISO `departedAt` |
| `renameVisit` | `renameVisits` | Nonblank `id`, nonblank trimmed `name` |
| `renameOuting` | `renameOutings` | Nonblank `id`, nonblank trimmed `name` |
| `renameCurrentOuting` | `renameOutings` | Nonblank trimmed `name` |
| `startRecording` | `recording` | None |
| `stopRecording` | `recording` | None |
| `export` | `exports` | Complete version-2 `selection` object from `ExportSelectionCodec` |

For example, `{"version":1,"command":"statistics","zone":"America/Puerto_Rico"}`. Date-free `statistics`, `visits` and `outings` reads default to the current civil day in the chosen zone, or the device zone when omitted. Explicit date bounds are inclusive. Visit/movement reads filter arrival/start events rather than interval overlap. Export selection uses its existing filter/time/field/splitting contract and cannot silently default missing or malformed filters to all history. `AutomationActivity` accepts the string request and dispatches it through `AutomationExecutor`.

`JournalCommands` provides bounded snapshot reads and atomic renames/manual check-in/checkout behind the executor. Exact stored outings are mutable; inferred outings have no stored rename identity. A persisted open outing is not evidence that the recording service is currently running. Start/stop acknowledgments and duration results account for foreground-service rules and actual runtime state, rather than treating a queued request as a running service.

## Manual check-in and checkout

`checkIn` accepts one saved-place ID, one explicit coordinate pair, or no destination fields for a current-location request. Saved place and coordinate fields cannot be combined. A nonblank name is required without a saved place; a missing/blank saved-place override uses the stored name. The saved place is resolved inside the commit transaction; it is not modified. Arrival defaults to the command's invocation time and cannot be in the future. The visit is confirmed/manual with user-entered provenance. These commands are free under Android's approved tracking policy.

Current location performs one foreground request with existing coarse/fine permission, a twelve-second limit, maximum thirty-second elapsed age and accuracy of at most 100 meters. It checks foreground/permission eligibility before and after the asynchronous fix and cancels the fused token on cancellation/deadline. It does not prompt for permission, start route/Visits only recording, search businesses or geocode. Approximate or unavailable fixes may fail the accuracy requirement. Real physical-provider acceptance remains separate from isolated adapter tests.

Overlaps with stored visits are rejected unless this request explicitly sets `allowOverlap:true`. The predicate matches the selected current Swift manual-store reference, including adjacent boundaries, zero-length records and its actual distant-future sentinel. Select/validate/insert is one writer transaction, so concurrent unqualified requests cannot both bypass admission. The native editor also requires explicit review for overlapping creation or manual time changes. Its separate Check Out action closes the stored visit without applying unsaved form edits; expanded editor/device acceptance remains in the implementation checklist.

`checkOut` with an ID accepts only an open manual visit. Without an ID it closes the unique open manual visit; multiple candidates return `ambiguous_visit`. Call `openManualVisits` with approved `visits` scope to choose an ID. Departure defaults to now and must be between arrival and now. Checkout retains other metadata and marks its explicit departure as not inferred. Repeating checkout reports `visit_closed`; no candidates reports `no_open_visit`. Missing saved places report `missing_place`; overlapping check-ins report `overlap`. Current-location failure reports `location_unavailable`, or `open_app` when foreground/existing access is missing. Other malformed/time-invalid inputs use `invalid_request`.

Saved-place results contain only `id` and `name`, sorted by stored name/ID. Open-manual results are newest-first and use the existing bounded visit entity fields. Both stream all pages under a committed read snapshot. Check-in/checkout return the committed visit entity in the normal private, expiring, read-only result file. No coordinates or notes are added to result entities. These mutations can commit before a later result-write/caller failure; clients must query state after an ambiguous interruption rather than automatically retrying with overlap enabled. No exactly-once mutation guarantee is introduced.

Examples:

```json
{"version":1,"command":"checkIn","savedPlaceId":"saved-place-id"}
{"version":1,"command":"checkIn","name":"Cafe","latitude":18.0,"longitude":-66.0}
{"version":1,"command":"checkIn","name":"Current stop"}
{"version":1,"command":"openManualVisits"}
{"version":1,"command":"checkOut","id":"visit-id"}
```

## Executor and result contents

`AppGraph.automation(recording)` binds the shared journal commands, private result store and export engine to a supplied recording adapter. `AppGraph.automation(activity)` now supplies the real `RecordingCommands` adapter. It requires a visible focused Activity and existing coarse/fine location permission before dispatching Start, without implicitly opening permission dialogs. Executor-only tests retain a synthetic adapter; separate provider/command tests and a disposable live QA walkthrough cover the recording path. A start/stop result distinguishes request acceptance from actual runtime state. Statistics use that runtime binding for current recording duration, rather than inventing it from route-point timestamps or persisted toggles.

Non-export responses are streamed JSON files with `version`, `command` and `data`. Summary data contains complete point/outlier/visit counts, distance in meters, `recordingActive` and nullable `currentRecordingSeconds`. Visit entities contain only ID, display name, address and arrival/departure instants. Outing entities contain only ID, display name and start/end instants. Raw route coordinates, notes and correction provenance are not included in these read entity responses. Rename responses contain committed updated entities while preserving unrelated stored fields.

Export always returns one streaming ZIP result, preserving the export engine's generated relative filenames, day/outing splits and subfolders for all selected formats. It uses the same export selection and saved inferred-outing settings as the app. Prepared bytes are verified under the export delivery lock, and cancellation/generation fences reject a clear during reading. Private prepared bundles are discarded after use on a best-effort basis; the cache's regular cleanup remains a fallback.

Export scope approval does not confer paid access. Both the export engine and result store check verified lifetime access before preparing/copying/publishing, and export results check paid access again on metadata/content reads. The production graph uses the purchase controller; a result store without an export authorizer fails closed for exports. Native tests inject synthetic entitlement decisions only in their isolated fixtures.

A mutation can commit before a later response-write failure or caller interruption. The executor does not promise exactly-once mutation receipts or roll back a completed rename because response serialization failed. The Activity returns `interrupted` on recreation instead of automatically rerunning a command; callers should query state after an ambiguous interruption. No external result grant is issued by the executor itself.

## Recording acknowledgment and cancellation

The non-exported recording service now acknowledges Start only after fused-location registration is accepted, and Stop only after owned recording state has been closed. The provider registration waits at most ten seconds; the command facade waits at most fifteen seconds for a service reply. A reply failure or timeout is not evidence that an operation never took effect; inspect current state after an ambiguous interruption.

Process-local start tickets reject canceled requests before dispatch, cancel attached startup work, and prevent a queued acknowledged Start from executing after its caller is canceled. Ordinary UI starts also receive owned tickets so pending startup is visible to recovery and Stop. The service serializes commands and fences old point/error/ticker callbacks by generation. Duplicate Start on a valid active session does not reset the source. Matching service start IDs prevent an older stop from shutting down a newer accepted request. Clear-all and deletion of the current outing now wait for Stop acknowledgment before deleting owned data.

A dispatched foreground-service start must satisfy Android's notification deadline even if cancellation wins before its callback runs. The sender checks canceled tickets and location access before dispatch; the service promotes before evaluating late cancellation, and rejects an illegal promotion by stopping the service rather than leaving an outstanding foreground promise. This was verified after a native regression reproduced a foreground-deadline crash in the earlier cancellation ordering. See Android's [foreground-service troubleshooting](https://developer.android.com/develop/background-work/services/fgs/troubleshooting).

`FusedLocationSource` removes canceled/failed and late registrations. Callbacks from a superseded or stopped source cannot deliver into the current one. Provider acceptance establishes registration, not an accurate fix, movement, visit, battery budget or unrestricted restart guarantee. A duration requires both actual runtime activity and a requested open session; persisted requested state alone reports inactive. Relaunch reconciliation does not close a currently pending live startup. Process death loses ephemeral tickets and requires the existing truthful recovery/user-start path.

## Private result files

`AutomationResults` writes immutable, fsynced payloads and metadata into the app-private cache. Each result has a random UUID, byte count and SHA-256. It is bound to one approval revision, recipient identity and scope. Opens reauthorize and verify the payload. Reapproving the same app does not revive results from an older approval.

Access expires ten minutes after a complete write finishes. Expiry checks both wall and elapsed clocks and the boot count; reboot, rollback or either deadline denies a new open. Private bytes are removed by the next result sweep, clear-all or cache eviction; access expiration is not a claim of deletion at exactly ten minutes. Failed/canceled writes remove their owned partial artifacts. Cleanup preserves unrelated files. Callers must not close the OutputStream supplied to a result writer.

`AutomationResultProvider` is **non-exported** and read-only, with URI grants enabled for the Activity response. It serves one exact result UUID per URI and supports only file-name/size metadata. SQL selections, other columns, path traversal, query parameters and write modes are rejected. It never queries history. Metadata/content opens verify Android's calling package and the current approval. Before replying, the Activity issues an exact read grant to the approved recipient package while holding the approval/result locks. This allows that package's background flow to read after its requesting Activity finishes; Android's [result-Intent grant alone ends when that Activity finishes](https://developer.android.com/reference/android/app/Activity#setResult(int,android.content.Intent)). The cross-UID fixture verifies actual ContentResolver reads, denied write/persistable access, recipient pinning, background Service reads after requester destruction and failed reopening after revocation.

Package grants are removed when their owned result is deleted, including expiration sweeps and explicit clear/disable. Cleanup failures remain visible and retain receipts for retry. The provider's ten-minute, boot/clock/identity/revision checks still apply to every metadata/content open even if Android retains a grant until cleanup or reboot. No write, prefix or persistable grant is issued.

Android may perform [anonymous MIME lookup](https://developer.android.com/reference/android/content/ContentProvider#getTypeAnonymous(android.net.Uri)). The provider returns a generic `application/octet-stream` for any structurally valid result URI, without revealing its existence or per-file metadata. The command result Intent carries the actual MIME type and grants only read access to the returned URI, without prefix/write/persistable grants.

Revocation/expiry cannot withdraw bytes already read or descriptors already opened. The recipient can retain data it was legitimately allowed to read. Clear-all revokes/cancels automation first, then removes owned result files before clearing the journal.

## Evidence and remaining acceptance

Pixel API37: 26 isolated platform tests pass, covering real package-signature lookup, scope/identity checks, strict parsing, revocation/cancellation, consent snapshots, storage failures, immutable payloads, expiry/clocks/boot, cleanup, the merged non-exported provider and actual ContentResolver reads. A separate actual Settings clear-all test passes in `com.bontecou.whereday.mapqa`, including an active result writer. These are synthetic test-owned results; no owner history or permission grant is involved.

An expanded 40-case Pixel platform run also passes the nine executor and five existing export-delivery tests. It verifies 1,201-entity read responses, runtime-duration binding, all three renames, scope-denied dispatch, all eight formats across two day folders, unpaid/mid-operation revocation, export-result open denial, corrupted prepared bytes and cache clear during reading. Debug assembly and lint pass.

The current milestone passes **17 isolated mapQa tests** (29.301 seconds), **43 isolated platform tests** (7.157 seconds) and **11 billing JVM tests** (1.992 seconds), with no failures, errors or skips. The app suite includes seven real cross-UID transport cases, five consent UI cases, actual Settings clear-all and existing webhook/editor regressions. The platform suite includes three controller cases for default-off behavior, reviewed scopes, queued-consent fencing and corrupt reset. Debug/mapQa assembly, debug lint and release-manifest generation pass. Remote CI has not run.

The `automation-client` module is a separate debug-only application, hardcoded to the disposable mapQa package; release variants are disabled. Its test receipt provider and the mapQa-only forwarding probe are absent from the merged production release manifest. Install its debug APK before the selected app transport tests; the Android CI workflow now does so and removes it afterward. The fixture was removed from the Pixel after testing.

A live setup recording was interrupted when an unrelated Device Cloud Probe app took foreground during onboarding. The raw take is preserved in ignored `captures/automation-install-setup-interrupted.yaml`; it is not a passing reusable test. The owner explicitly requested leaving the Pixel for the other task, so no further device interaction, cleanup or helper shutdown was attempted. The disposable mapQa app remains partially onboarded, without location/media/notification grants, recording history or automation approval. No screen video is active. No owner update, instrumentation or journal operation occurred in this milestone.

Installed Automate integration, launcher-device acceptance, full-motion/expanded accessibility inspection and supported-device/process-death acceptance remain required. Automate was not installed at the last check. No full automation or release acceptance is claimed.

The recording milestone also passed 53 combined isolated platform cases, then the final 13 focused recording/provider/notification cases and five actual mapQa lifecycle/settings/edit cases after the pending-start recovery guard. A fresh-install recorded Pixel replay passes 42 actions/checks plus two echoes in 37.6 seconds: onboarding, in-context precise location/notification grants, provider registration, Home/resume while active, and acknowledged Stop followed by idle Home/resume. The first live take showed three temporary GPS points. The disposable QA app and both takes' GPS data/permissions were removed afterward; the owner app remained installed and untouched. This is provider/foreground smoke evidence, not a screen-lock, force-stop, OEM, battery, visit-precision or process-death acceptance verdict.
