# PR 112 — reference regeneration after multipart backups

The `2da835c7` hosted Android build failed its Google Timeline provenance assertion after all Gradle build/tests had completed. The following duplicate-fixture check was also stale but unreachable behind the first failure. The dependent SwiftData job was skipped.

Both fixtures hash the entire `TimelineImportService.swift`, whose multipart file preview/combination implementation changed. The extracted Google parser and duplicate predicates did not change. We ran both actual Swift producers; all 50 Google cases and all 50 duplicate-boundary cases are structurally identical before/after. Only their whole-file source provenance changed. No checksum was edited manually, producer altered, guard relaxed, or expected result changed. `regeneration.json` records exact input/output hashes.

Verification on these generated inputs:

- All 30 CI source-provenance checks passed (`source-checks.json`).
- Focused Gradle consumers: six tests passed in 27 seconds, including the two 50-case reference consumers and the actual Android interoperability producer (`targeted-jvm.json`).
- Twelve actual Android export files passed current SwiftData preview, commit, metadata and duplicate checks (`swift-commit-acceptance.json`). Seven full current SwiftData admission scenarios passed (`swift-admission.json`).
- Prior signed multipart regression coverage at `2da835c7`: 26 targeted iOS tests passed, including 250,001 records, over-50-MiB roundtrip, all six models and invalid-part rejection. Production Swift source is unchanged by this reference refresh.

Contract review: the changed backups remain the app-local full-model backup format; ordinary Archive export renderers and Android JSON/CSV/Markdown wire formats are unchanged. Google fixture production executes verbatim extracted parser/validation and deliberately fails if backup decoding is reached; the duplicate fixture executes actual CoreLocation predicates. The actual SwiftData harnesses compile the full current backup/parser/model dependency set and check ordinary Android exports through public preview/commit. No broader cross-platform full-backup acceptance is inferred.

Native/Gradle work was serialized with `vox-pr-review`; no local emulator or device was created or used for this follow-up. Concise receipts and original execution logs are retained under the same evidence directory in the permanent `/Users/codybontecou/dev/whereday` workspace, outside the temporary integration checkout. Final hosted results and cleanup are recorded there after push; no merge, store operation or production write is authorized by these checks.
