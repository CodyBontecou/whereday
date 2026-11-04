# Whereday iPhone Duo assets — upload package

Uploaded October 30, 2026 to Whereday's Asset Library and placed, in filename order, in the English (US) Duo section of 1.8.2. The owner then requested submission. Version **1.8.2 (58)** and all four assets are now **WAITING_FOR_REVIEW**, submitted October 30 at 16:06:19 America/Puerto_Rico. Manual release is retained; no public release was performed. Upload and placement IDs are in `upload-receipt.json`; final Apple readback is in `.asc/artifacts/release-1.8.2-58/duo-placements-final.json`.

Four opaque RGB PNG cards: three inner-display landscape cards (2853 × 2007) and one outer-display portrait card (1398 × 2034). Review `review/contact-sheet.png`; upload the four individual files in `exports/`, in filename order, to the English (US) iPhone Duo screenshot section. `whereday-iphone-duo-en-US-upload.zip` contains only those four PNGs; extract it before uploading.

Features: map and timeline together, inline visit details, dark appearance, and the outer-display map. Copy avoids pricing claims. All app views were freshly captured October 30, 2026 at native resolution from Whereday 1.8.1 (57), on an isolated iPhone Duo / iOS 27.1 simulator using built-in synthetic San Francisco data. The inner captures are rotated upright; the app views are only reduced when composed, never enlarged or retouched. Status bar: 9:41, Wi-Fi, 100% battery. Tracking was off. Original full-resolution PNGs are in `source/screens/`.

The fresh source build hit host disk exhaustion twice. This package instead uses the existing 1.8.1 (57) binary already installed on the earlier Duo simulator, copied into the new isolated simulator. It does not certify that binary against current HEAD. Existing simulator records were preserved. No app code changed.

Rebuild with `node marketing/brand/whereday-duo-v1/source/build.mjs` from the repository root. Uses the existing brand package's Sharp dependency. The builder rejects reduced-resolution source captures, verifies output dimensions and absence of alpha, and records SHA-256 checksums in `manifest.json`. `SHA256SUMS.txt` lists the packaged exports. Layout, text, map tiles, orientation, and light/dark appearance were visually reviewed on the fresh captures and final contact sheet.

The recorded navigation fragment `.argent/e2e/duo-appstore-capture-20261030.yaml` replayed 10/10 steps successfully. It uses raw AX-service checks and two AX-derived tap points because Duo native-devtools auto-targeting timed out on background system services. These tap points are specific to the default-text inner-panel orientation. The initial tree-read timeout was followed by a successful re-recorded check. This is a capture trace, not an app regression suite; appearance changes and PNG capture are separately performed host actions. No iOS unit tests were run because there are no app-code changes.

Apple specification checked October 30, 2026: https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications

Original background generated with the built-in image_gen tool; real app screenshots composed with Sharp. Generation prompt is preserved in `source/art-prompt.txt`.
