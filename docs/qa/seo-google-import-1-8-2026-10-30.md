# Google Timeline guide release verification, October 30, 2026

## Release provenance

Public Apple US lookup confirms Whereday 1.8. The release ledger ties distributed version 1.8 to build 52 (`82ecbf53-4b82-4d07-80f0-62799f50d009`). The archived IPA's phone/Watch/widgets all report 1.8/build 52. Frozen source is `.asc/artifacts/stage-1.8-52/source`, base `f774ee6d2c0d5b89dbd521a681b5aef6eaad0fa0` plus recorded candidate patch. The full source manifest was rechecked; see `docs/seo-google-import-source-2026-10-30.json`. Import services, preview, Settings, tests, and fixture also match the current checkout byte for byte.

## Targeted native checks

Ran from the frozen source with simulator signing enabled:

```
scripts/test-ios.sh --destination 'platform=iOS Simulator,id=4457437A-95AB-44D9-B5D1-7371B30B32E7' -only-testing:IsoMeTests/TimelineImportTests
```

Result: **12 tests, 0 failures; TEST SUCCEEDED**. The `.xcresult` finalized successfully at `.asc/artifacts/stage-1.8-52/source/artifacts/test-results/IsoMe-20261030-123714-77725.xcresult`.

Covers raw/semantic/device/iOS-array Google formats, coordinate/time parsing, invalid and unsupported inputs, file limits, Whereday backups, read-only preparation, cancellation, selected types, duplicate tolerances, repeat import, and preserving existing data. Persistence tests in this class use isolated in-memory stores; they do not establish a full phone-to-phone migration.

## UI and sample

The same source was built in Debug and installed through Argent on the booted isolated Whereday Release Tests simulator, iOS 26.5. Onboarding was bypassed through its existing UserDefaults launch argument, not a source change. Location permission was not granted and tracking remained off. Simulator StoreKit already reported Family Lifetime; purchase and new-user entitlement acceptance were not part of this check.

Fixture copied into isolated Files provider storage: `IsoMeTests/Fixtures/google-timeline-preview.json`, published unchanged as `/samples/synthetic-google-timeline.json`. SHA-256: `c268f859c184a26aa34eabf168d729b6f4af9dfabeb600ffad9f5fe3b3b04bf4`. All records, coordinates, place labels, and dates are invented.

Observed Settings → Data Import → Import File → system Files picker → selected fixture:

- Detected Google Device Timeline.
- Preview: 1 new visit, 2 new points, 1 duplicate visit, 2 duplicate points, 1 invalid entry.
- Both types selected; confirmation reports 1 visit and 2 points imported, skipping the in-file duplicates.
- Settings totals changed from 0/0 to 1/2. An earlier first-import run in this same isolated workflow retained 1/2 after process relaunch.
- Sample UTC dates appeared as December 31, 2019, 8–9 PM in the UTC−4 environment. The guide explains display-timezone differences.

Screenshots retained in `marketing/public/screenshots/google-import-preview-1-8.png` and `google-import-complete-1-8.png`, 1206×2622 pixels. An initial capture was removed when the simulator tooling cleaned its temporary media; the saved public assets are from a repeated clean first-import run, not reconstructed images.

## Automation limits

The first gesture hit a dead CoreDevice transport; a scoped transport restart recovered it. Accessibility and runner trees disagreed over Settings controls, and several taps did not open the picker. A press-hold opened it, with an incidental context menu that was dismissed. Native accessibility trees, screenshots, counts, and source checks established the successful first import. Reopening the picker after completion was unreliable during this run. A new repeat-import UI pass and robust e2e replay are **not** claimed. Repeat duplicate handling is supported by the passing native tests and earlier manual QA on the unchanged importer, not a fresh complete replay.

The exploratory recorder output remains under `/private/tmp/whereday-seo-import-1-8/`; it contains weak selectors and is not a committed regression test. No product bug is inferred solely from the automation misses. Scoped Argent services were stopped after copying retained images. No physical iPhone, Google account, real location archive, App Store purchase, or shipping IPA was driven through import.

## Website reconciliation

Promote the prepared migration page only with these limits stated. Keep its intent distinct from the buying comparison, finding-existing-history article, and Whereday-backup guide. Link from homepage, guide hub, comparison, history, and export documentation. Rebuild HTML, Markdown, article inventory, API metadata, and sitemap together. Preserve 1.7.16 historical examples without applying their old duplicate behavior to 1.8.

## Publication

Published through the existing Cloudflare Pages project, final deployment
https://9278ada2.spotted-8ez.pages.dev. The import page is indexable with a self-canonical,
October 30 publication/modification dates, synchronized HTML/Markdown, and a sitemap entry.
Seven discovery surfaces link to it: home, guide hub, comparison hub, comparison article,
history article, backup guide, and export/import reference. The Chinese homepage and
agent retrieval guidance also link to the English workflow.

The comparison hub now answers the distinct archive-migration decision instead of merely
repeating the comparison link. The existing small hub has a substantive new task to route.

Build validation: 33 HTML documents including 404; 1,181 local link/fragment targets;
metadata, JSON-LD, IDs, and sitemap passed. Repeated final build is identical.
Six acquisition tests passed. Local browser use was disabled in Work settings, so no
new browser screenshot or responsive-layout pass is claimed. The guide uses the existing
article stylesheet and labelled scroll regions; actual images are dimensioned and inspected
native UI captures. Post-deployment HTTP and OpenSEO results are recorded separately.

The first post-migration crawl (`99561094-509e-422f-a482-d9e24fee6191`) read
43 URLs. Its only issue was the new guide’s 165-character description; the
comparison-hub thin-content warning is resolved by the new migration section.
The description was shortened to 147 characters and rebuilt. Current production
HTTP validation passes all 32 HTML/Markdown page pairs. Twenty-one selected
production resources match local source after the observed edge transformations;
the synthetic JSON and both PNGs also match byte-for-byte, with attachment
headers on the sample. Final crawl follows the metadata correction.

Final metadata deployment: https://e7ab230b.spotted-8ez.pages.dev.
Audit `c33637c3-7077-4d8a-8da2-6de8d8d61ada` completed October 30 at
16:54:52 UTC, **43 URLs / zero reported issues**. The migration page returned
200, was indexable, and was found in the sitemap. Its crawl-reported word count
was 983. This proves crawler access, not Google indexation or increased visits.
The final 21-resource live/source comparison passed after the metadata deployment.
The saved OpenSEO report stylesheet was repaired during the final report check;
the full starter CSS is now present and scripts/embeds/template tokens are absent.
