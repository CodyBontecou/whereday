# Whereday 1.8.2 / build 58

The owner requested submission after approving the four English (US) iPhone Duo screenshots. Version 1.8.1 was already distributed, so a new 1.8.2 draft was created with manual release and inherited listing metadata. Four new Asset Library placements were added only to the English (US) Duo group, in filename order.

This is a screenshot update. Every source file in the shipped 1.8.1 build 57 source manifest still matches its SHA-256 checksum. The archive uses command-line `MARKETING_VERSION=1.8.2 CURRENT_PROJECT_VERSION=58` overrides; no app source or project version settings were edited. Existing uncommitted marketing work was excluded from the binary. The archive and IPA both passed four-bundle version inspection (phone, phone widget, Watch, Watch widget).

Nine localized release notes say that the App Store screenshots for iPhone Duo were updated. Reviewer notes identify the unchanged app source and fictional sample data in the screenshots. Purchase behavior, privacy behavior, products, prices, and availability are unchanged. Manual release is retained.

The Release archive and distribution export succeeded. No unit tests were rerun for this version/build-only archive; its source matches the already-shipped 1.8.1 candidate. The earlier candidate's qualification, including its unchanged local StoreKit cancellation-test limitation, remains documented in `../1.8.1/README.md`. This is not a claim of a new all-green test run.

Private archive, IPA, upload, validation, and submission receipts are retained in `.asc/artifacts/release-1.8.2-58/`. Screenshot capture provenance and placement receipts are in `marketing/brand/whereday-duo-v1/`.

App Store version: `8ec51400-9523-462c-ac5d-83d504290b45`.

Submitted October 30, 2026 at **16:06:19 America/Puerto_Rico** (20:06:19 UTC). Apple readback confirms **WAITING_FOR_REVIEW**, build **58**, and **MANUAL** release. All four Duo assets also report **WAITING_FOR_REVIEW**, and the requested placement order is unchanged. Submission ID: `fa4d9b89-8f00-4aa7-bc9c-0eb9327fa9c9`. Build ID: `d8398c20-1c1f-4728-bb08-6b15fb4a48c8`.

Final IPA-aware readiness validation returned **0 errors and 0 blocking findings**. Five warnings remain: the CLI's older size catalog does not recognize `APP_IPHONE_DUO` (Apple's live reference catalog and processed assets confirm the dimensions), plus four optional subscription promotional-image warnings inherited from the previous release. The two informational checks concern manual release and API-unverifiable App Privacy. Apple accepted the review submission.

The submission wrapper preserved two unrelated stale draft submissions. No public release was performed or authorized by this task.
