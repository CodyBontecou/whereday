# Google Timeline import (development source)

This describes the importer in PR #119, not an assertion that the current public App Store build contains it. Keep your original Google export and check the review counts; approximate duplicate matching and conservative inference do not guarantee a complete, lossless migration.

## Import a file, several files, or an unzipped folder

1. Obtain your Google Timeline export. Supported JSON shapes are listed below; Google export availability and schemas vary.
2. If the export is a ZIP, use **Files to unzip it first**. Direct ZIP import is unsupported. Keep the original archive. [ZIP decision and future admission requirements: #122](https://github.com/CodyBontecou/whereday/issues/122).
3. Make the files/folder available offline in Files. The verified folder path is an already-local folder under **On My iPhone**; cloud-only, stale, denied and arbitrary third-party provider behavior is not yet accepted.
4. Open **Archive → Settings (gear) → Import files or a folder**. Choose either supported files together **or one Google folder on its own**. Do not mix folders and files or select several folders.
5. In the system picker, browse into the unzipped folder and choose **Open** to select that folder. For individual files, select the desired JSON files instead.
6. Review the detected format, date range, new visits/points, duplicate estimates and warnings. Folder review also shows validated Google JSON file and excluded-entry counts. No journal records are saved yet.
7. Choose visits and/or points, review inference/accuracy choices, then choose **Import selected records**. Cancel leaves existing records unchanged. Completion reports added records and duplicates skipped.

The Today Google-import entry uses the same picker and review. Only the Settings path has fresh native folder-picker evidence in this slice.

## Supported Google data

- Classic raw Location History: `locations`, E7 coordinates, millisecond or supported ISO timestamps.
- Semantic Timeline: `timelineObjects`, including place visits and activity/path points with usable timestamps.
- Device exports: `semanticSegments` or supported root arrays, including `geo:`/degree coordinates and timed paths.

Selected Google JSON files combine into one review. A folder recursively discovers likely Google JSON and validates every candidate with the actual codecs. Unrelated JSON/text, backups, hidden/package entries and links can be excluded; excluded-entry counts are not a recursive count of descendants beneath skipped directories. A malformed or indeterminate possible Google candidate rejects the whole folder, not just that file.

A Google folder is **not** a Whereday backup-restore directory. To restore a multipart Whereday backup, select all numbered backup JSON parts together through normal file selection. Missing/mixed/duplicate/invalid parts are rejected. Existing Whereday JSON, CSV and Markdown import behavior is retained. KML, GPX, GeoJSON and ZIP are not supported inputs here.

## Limits and troubleshooting

Folder discovery is bounded at10,000 enumerated entries,1,024 probed regular files,256 Google candidates and16 directory levels. Prefix probing is bounded per file and in aggregate; candidate payloads total at most50MiB. The combined import is limited to250,000 source visits/points and inference-evidence observations **before** deduplication. These are admission ceilings, not proven phone memory/latency targets. Parsing is not streaming. Split a large export into smaller, coherent selections instead of raising limits or discarding the original.

- **No supported files:** check that the export contains one of the Google shapes above, rather than account settings or a Whereday backup. A JSON extension alone is not proof of support.
- **Cannot read locally / source changed:** make a stable local copy available offline and retry selection. The importer does not implement automatic cloud materialization or hostile-writer coordination. Never assume a partial folder was imported after an error.
- **No inferred visits:** raw-only selections default inference on; semantic/device/mixed selections default it off. Conservative inference needs sustained, sufficiently dense, known-accuracy evidence. Sparse/moving/invalid/unknown-accuracy data may legitimately produce no visit. Inferred visits are estimates, not Google's semantic place visits.
- **Poor accuracy:** accepted accuracy defaults to100m. Poor-accuracy route points require an explicit opt-in; that opt-in never relaxes inference accuracy. Unknown accuracy is retained for routes only, not dwell evidence.
- **Duplicates:** points use rounded-second/time-and-distance matching; visits use bounded time/distance matching. Existing names, notes and confirmations are preserved. The commit rechecks duplicates, so previews are estimates rather than permission to overwrite records.

## Privacy and evidence

Processing uses the on-device parser, planner and SwiftData store. Import does not sign in to Google, upload history, request Google APIs or run place-name lookup. Names are imported only when present in the source. The system Files provider is separate; no network capture or all-provider offline guarantee is claimed.

[Native local-folder review/cancel/confirm evidence](evidence/google-folder-picker-20261114.md) covers two synthetic Google files, two exclusions, cancellation with zero records and one explicit commit adding two points/one visit. The full source suite and all original Google/duplicate reference outputs pass. Cloud/provider/error/localized/spoken/motion/device-performance and Watch/widget acceptance still remain; this guide does not mark #59 complete.
