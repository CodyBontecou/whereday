# Export/import and access claim corrections

Previous goal turn: progress, resource navigation and Google import FAQ correction. Current turn: progress, correcting further observed public claims before adding lower-demand converter work. Goal remains active.

Production deployment https://b3ceb9d1.spotted-8ez.pages.dev.

## Findings and changes

Homepage visible/structured export FAQ made all eight formats sound interchangeable for every record kind; SoftwareApplication feature list used broad round-trip backup wording. Export docs incorrectly said selecting Visits for OwnTracks/Overland fell back to JSON, while the released UI/adapter uses Points. Universal paid/Pro-export labels also obscured the actual new-user/existing-user access rules.

Current copy distinguishes eight export choices from native import support (supported Whereday JSON/CSV/Markdown visit and point files; supported Google Timeline JSON). Map and protocol exports are not native restore files. OwnTracks/Overland carry GPS points, including only outing route points without visit rows or outing notes.

Released1.8 policy keeps exports available for new users after tracking expires; existing free users retain the earlier export-unlock requirement. Auto Export and webhook settings follow canExport, with separate configuration requirements. No claim that a never-purchased new user bypasses initial paywall, that scheduling always runs, or that live purchases were verified.

Corrected homepage access anchors/labels/FAQ/schema, affected current guide copy, comparison table, docs callouts and three hub Pro badges, Country Days wording, Chinese feature/FAQ/access/footer scope, agent guidance and OpenAPI export-format description. Terms changed only in its product meta description; legal body unchanged. Historical1.7.16 native examples and purchase-test limitations retain their original provenance.

## Release evidence and native checks

Four reviewed current files exactly match the frozen1.8/build52 source and manifest: ExportService, IsoMeExportKitAdapter, ExportView and TimelineImportService. StoreManager's whole current file differs in product-ID mapping. Its frozen file matches the release manifest; canExport policy/comment matches current source. Do not describe every current file as a byte-for-byte release match.

Ran signed repository runner from frozen source:

- StoreManagerTests/testNewUsersMustPurchaseButExpiredUsersKeepHistoryAndExport:1test/0failures; finalized result .asc/artifacts/stage-1.8-52/source/artifacts/test-results/IsoMe-20261030-172851-73985.xcresult.
- ExportRoundTripTests/testAllExportFormatsRoundTripRepresentativeDataWithoutSchemaDrift and ExportKitIntegrationTests/testOutingTrackingProtocolFormatKeepsOutingsDataKind:2tests/0failures; finalized result .asc/artifacts/stage-1.8-52/source/artifacts/test-results/IsoMe-20261030-173120-78802.xcresult.

The eight-format test uses its own format readers; it does not prove that the native importer accepts all export formats. These are policy/serializer/adapter checks, not a fresh purchase, physical recording or end-to-end file-sharing test. No native source edited.

## Website verification

16Python tests pass. Build39documents including404/38published pages/1722local targets, all reader-reachable within2links. Rebuild idempotent. Visible export/import FAQ text equals structured text. Review found and resolved missed comparison/docs/Chinese labels; no remaining material finding. Current universal paid-export scan only retains an unused generic tag-renderer branch, not an active Pro-tagged guide.

In-app browser checked mobile390 homepage access anchor/labels and export reference, plus live claims. Homepage anchor scroll margin keeps the new explanation below the sticky header. Both page widths match390 with no horizontal overflow. Screenshot export-access-mobile-2026-10-30.jpg. Viewport reset; live export reference retained. No physical phone-browser verification claimed.

Production claim readback passed12affectedpages/agent source plus docs hub and OpenAPI. Initial verifier expected a capitalized phrase after a semicolon; corrected its case without changing valid copy, and it passed. Full38HTML/Markdown response/product contract passes. API shape/read-only behavior unchanged. No new crawler issue, ranking, indexing or conversion outcome is claimed; this turn relied on targeted content and contract verification rather than rerunning the same full crawl.

Evidence: ../seo-export-import-claims-source-2026-10-30.json and ../seo-export-import-claims-live-2026-10-30.json. Existing audit and content/tools reports updated in place; no additional keyword research purchased.
