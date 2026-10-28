# iso.me SEO audit and execution plan

Prepared October 26, 2026. The original audit below describes the pre-implementation site. Implementation has now started locally; no deployment, analytics changes, or scheduled jobs have been performed.

## Implementation progress — October 26, 2026

- Updated homepage category copy, tracking/telemetry statements, discovery links, and direct export/Watch references.
- Improved the existing article's immediate answer, recording steps, restore boundaries, screenshot labeling, review date, and contextual comparison link.
- Reworked the blog hub around recording, comparison, and export tasks.
- Added `/compare/` and `/compare/google-maps-timeline/`, with sourced tradeoffs, developer attribution, clear pricing and import limits, breadcrumbs, and mobile table scrolling.
- Updated generated tracking, onboarding, export, and privacy references. Removed template residue and unsupported lossless-restore wording.
- Added a repeatable site build and pre-deployment checks for local targets, metadata, JSON-LD, IDs, and sitemap coverage. The article work brought the site to 23 HTML pages before concurrent trust-page additions; use the site checker for the current complete count.
- Added `/blog/view-location-history-iphone/` and `/blog/record-iphone-route-gpx/`, with separate search intents, linked sources, article metadata, breadcrumbs, and relevant links from the existing site. The route guide includes a clearly synthetic GPX download validated against the official GPX 1.1 schema. Desktop/mobile rendering was checked; no new physical-device recording test is claimed.
- Added `/blog/back-up-iphone-location-history/` with separate Visits/Points export steps, supported import boundaries, file checks, and automatic-export limitations. Linked it from the homepage, guide hub, existing articles, and export reference.
- Expanded `/docs/apple-watch/` around independent recording, permission/state checks, acknowledged sync, missing routes, and daily counters. This is source-reviewed guidance; a new physical Watch walk and restore test remain outstanding.
- Held the Dawarich guide after finding a possible OwnTracks payload mismatch. Corrected compatibility claims and the synthetic Test Connection warning in the public webhook reference and homepage FAQ. See the [verification note](dawarich-guide-verification-2026-10-26.md); no live receiver success is claimed.
- Added `/blog/iphone-location-tracking-battery/` for battery diagnosis and recording tradeoffs, with Apple sources, a repeatable comparison checklist, and links from the guide hub, journal article, and tracking reference. Tightened distance-filter wording: high-accuracy GPS remains active, and no battery saving or duration was measured. This support-oriented expansion is an editorial choice, not a newly observed Search Console opportunity.
- Strengthened the backup guide with synthetic visit/point JSON downloads and a minimal format example. The unchanged Swift importer accepted two visits and three points and rejected an incomplete visit; the repeatable check is `marketing/scripts/check_import_samples.py`. Clarified that imports add records without deduplication. GPX guidance now links both the sample structures and battery comparison. These parser checks are not a device restore test.
- Added `/blog/missing-location-history-iphone/` as a focused diagnostic guide for existing iso.me users. It distinguishes absent visits, hidden/rejected route points, recording timers, Watch transfer status, and export filters. Linked it from the guide hub, history/GPX/battery articles, and tracking/Timeline/Watch references. Product behavior is source-reviewed; no recovery or fresh device test is claimed. This is an editorial support choice, not a measured search-demand finding.
- Added `/blog/iphone-location-journal-obsidian/` as a deliberately limited manual desktop Markdown workflow, with a downloadable synthetic note rendered in Obsidian 1.14.4 in a temporary vault. Linked it from the blog hub, export reference, and backup article. This does not fulfill the wider automatic/mobile integration gate: selected iPhone vault access, cloud delivery, and scheduled writes remain unverified.
- Organized the blog hub into finding/checking history, recording/reviewing routes, and keeping/using records. Added two contextual next-step links to each generated article and the Google comparison, with explanatory labels. This improves discovery across the existing cluster; no new search-demand or ranking claim is made. The [content inventory](seo-content-inventory-2026-10-26.md) records intent boundaries and verification gates.
- Website content is ready for review. Search Console ownership is now verified and the [28-day baseline is captured](search-console-baseline-2026-10-26.md): 5 clicks, 297 impressions, 1.7% CTR, average position 6.0 for September 27–October 24, using the parent domain filtered to iso.me while the dedicated property's reports process. The existing sitemap shows Success with 17 discovered pages, and three sampled URLs are indexed.
- Outbound event collection, genuine Apple campaign links, deployment, and tested Dawarich/Watch workflows remain in the backlog. Existing screenshots are illustrative; this batch does not claim a new device-recording test.

## Recommendation

Build an English acquisition cluster around **your own iPhone location history, recorded routes, and portable exports**. Use the existing private-journal article as the product introduction; add one Google Timeline comparison, two distinct how-to guides, and a tested Dawarich integration. Improve the existing export and Apple Watch references instead of creating duplicate pages for every feature.

Apply Vaultaire's intent coverage, internal linking, and search-data feedback loop. Its thousands of localized URLs are not an appropriate starting target for iso.me. Rankings, traffic, search volume, keyword difficulty, and conversion rates are unmeasured here; the priority order is an editorial judgment based on product fit and effort.

## Current site: evidence and gaps

The website is [isome.isolated.tech](https://isome.isolated.tech/). “iso.me” is the product name; this plan does not assume ownership of the separate iso.me domain or propose a domain migration.

Local inventory of `marketing/public`:

| Page type | Count |
| --- | ---: |
| Homepage | 1 |
| Blog hub and one article | 2 |
| Documentation hub and 11 reference guides | 12 |
| Privacy and terms | 2 |
| Total HTML pages / local sitemap entries | 17 / 17 |

The [public documentation hub](https://isome.isolated.tech/docs/) confirms the 11-guide structure. The [homepage](https://isome.isolated.tech/) already communicates local storage, no account, free tracking, eight export formats, Watch support, and one-time paid export plans. Current public homepage text includes the newer US Individual/Family pricing; the older site-gap analysis is not a reliable list of still-open issues.

**Strengths to preserve:** static HTML, meaningful titles and descriptions, self-referencing canonical URLs, robots and sitemap files, actual product screenshots, clear free/paid boundaries, substantive docs, and creator/support information. Local homepage and article JSON-LD already exist. There is no evidence that a framework migration or broad redesign would improve search acquisition.

**Highest-value gaps:**

- Only one discovery article and no comparison section. Most URLs explain how to use a product the visitor already knows.
- The homepage mentions “Google Timeline alternative” but has no dedicated page explaining that choice.
- Homepage body links do not directly expose individual docs or the existing article; the sampled article links to docs but has no adjacent acquisition articles yet.
- Blog hub copy describes the publishing process and “field notes” instead of helping readers pick a task. Backup and battery entries are sections of the same article, not separate guides.
- Reference titles such as “Export & import” and “Apple Watch” provide little search context.
- Local pages use gated Cloudflare Web Analytics, but no explicit outbound App Store click event or page-specific campaign links were found. Search Console and account-side campaign configuration were not inspected.
- No localized web pages or `hreflang` annotations were found in the local HTML. Localization is an expansion opportunity after evidence, not an immediate fix.

**Inspection limits:** public search extraction was available, but direct HTTP fetches from this audit environment returned 403. That does not establish that the origin blocks Googlebot. The public article extraction lacks some sections present in the local October 25 revision; caching or deployment differences require a fresh browser/URL Inspection check before drawing conclusions. A full live crawl, mobile visual audit, performance benchmark, and backlink audit were not performed.

## First dependency: accuracy and measurement

Complete these before publishing the first new guide:

1. **Correct the tracking model in copy.** Current `LocationManager.startTracking()` starts visit monitoring, significant-change monitoring, and high-accuracy GPS together; `stopTracking()` stops all three. Homepage/article/docs must not imply a separate selectable passive visit-only mode. Describe what Start does, background permission, Stop, distance filtering, and auto-stop. Verify shipped behavior before final wording. This is a content correction, not a proposal to change the tracking engine.
2. **Keep privacy claims specific.** Core history is local, but Apple Maps/geocoding services, default-on first-party onboarding telemetry, optional webhooks, and export scheduling have distinct network boundaries. Replace the ambiguous “Analytics SDKs: None listed” proof row with a concrete statement and policy link. The local privacy policy's blanket “no personal data is collected or transmitted” children's sentence also needs consistency with its disclosed telemetry. Do not expand unsupported “zero network” or encryption claims.
3. **Describe competitors accurately.** [Google Timeline on iPhone](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en) is already device-based, supports encrypted backup, and exports a location-history JSON file. [Apple Visited Places](https://support.apple.com/guide/iphone/view-visited-places-iph0c2597c4f/ios) supports encrypted visits, notes/corrections, and device sync, with regional availability. Differentiate on no Google account, route workflow, formats, pricing, and optional integrations rather than claiming competitors have no private history or exports.
4. **State import limits.** `IsoMe/Utilities/ImportService.swift` explicitly supports iso.me JSON/CSV/Markdown. A JSON extension does not make Google's export importable. No Google migration, deleted-history recovery, retrospective GPS recovery, universal GPX import, or complete lossless restore promise without a tested implementation.
5. **Establish the baseline.** Verify the existing Search Console property or connect the canonical host, submit the sitemap, inspect the homepage/article/docs, and save the latest available 28-day query/page/country metrics. If there is little data, label the baseline as sparse rather than waiting to publish.
6. **Add explicit acquisition measurement.** Record an outbound `app_store_clicked` event with page path and placement, without coordinates, place names, or user-entered text. Follow existing analytics exclusion preferences. Generate real [App Store Connect campaign links](https://developer.apple.com/help/app-store-connect-analytics/acquisition/campaign-links) with the account's provider token and stable page-level campaign tokens. Do not substitute invented tokens or generic UTM parameters for Apple's attribution.

Source files for copy corrections: `marketing/public/index.html`, existing blog article, and `marketing/scripts/build_docs.py`. Edit generated docs through the generator, then regenerate. The generator also leaves `__DOCS_CURRENT__` in output; remove this template residue during cleanup. Treat it as a minor quality issue rather than the main ranking problem.

## Ordered page backlog

Keep existing URLs. Proposed new paths are deliberate search-intent owners, not variants to publish simultaneously.

| Order | Action and URL | Proposed search title / target intent | Why this comes first |
| --- | --- | --- | --- |
| 1 | Improve `/` | **Private iPhone Location History & Route Journal — iso.me** | Clarify the category and route visitors into guides, comparison, and download. |
| 2 | Improve `/blog/private-location-journal-iphone/` | **How to Keep a Private Location Journal on iPhone** | Existing article and screenshots provide the fastest substantive improvement. Owns choosing and setting up a private journal. |
| 3 | Create `/compare/google-maps-timeline/` | **Google Timeline Alternative for iPhone: iso.me Compared** | Strong product-evaluation intent; currently scattered across homepage copy. Competitive query, not assumed to be an easy ranking. |
| 4 | Improve `/docs/export-import/` | **Export and Restore iso.me Location History: 8 Formats** | Establish one accurate reference and paid-export boundary for all acquisition pages. |
| 5 | Create `/blog/record-iphone-route-gpx/` | **How to Record an iPhone Route and Export a GPX File** | Concrete recording-to-file task closely matches the paid product. Owns newly recorded routes, not recovery of Apple/Google history. |
| 6 | Create `/blog/view-location-history-iphone/` | **How to See Your Location History on iPhone** | Broader discovery entry point. Answer existing-history questions before offering future recording. |
| 7 | Create `/integrations/dawarich/` | **Use iso.me as an iPhone GPS Logger for Dawarich** | Narrow audience and a specific workflow; requires an actual receiver test. |
| 8 | Improve `/docs/apple-watch/` | **Record Apple Watch GPS Routes Without Your iPhone — iso.me** | Existing page already covers a meaningful differentiator. Expand it before adding a competing Watch tutorial. |
| 9 | Improve `/blog/` | **iPhone Location History Guides — iso.me** | Turn an editorial placeholder into a task-oriented hub as the first batch goes live. |

### Brief 1: homepage and blog hub

Keep the current core layout. Suggested homepage H1: **“Remember where you went. Keep your location history on your iPhone.”** Supporting copy should explain visits and routes recorded while tracking is enabled, no account, and free recording with optional paid exports. Primary action: **Download free for iPhone**. Use screenshots or real product facts as proof.

Add a compact “Start with what you need” section linking to the private-journal article, Google comparison, iPhone-history guide, and GPX tutorial once each exists. Link Watch and export feature explanations directly to their reference pages. Add a desktop QR download option after the core funnel is measured; no SMS collection is needed.

Replace blog “publishing queue” and editorial promises with three task groups: find your history, record your routes, export and connect your data. Keep the existing `/blog/` path; no separate `/guides/` migration is required. Create a small `/compare/` hub with the first comparison and a small `/integrations/` hub with the tested integration, each with useful context rather than placeholder cards.

### Brief 2: existing private-journal article

Answer the question in the opening paragraph. Replace general positioning with a verified first-recording walkthrough: permission choice, Start, short route, Stop, Timeline, and one corrected visit. Show a sanitized real outing with the device, OS, app version, and test date. Explain which data existed before setup and which must be recorded going forward.

Retain a concise privacy and battery section, link to detailed references, and explain one backup path with a verified restore. Do not imply that all map export formats are backups. Use a free-download CTA after the first successful-recording example and at the end; mark export as paid when discussed. Link the comparison and GPX tutorial after publication.

### Brief 3: Google Timeline comparison

Start with who benefits from each product. Use a sourced table for account requirement, device storage, backup/sync, visits/routes, format exports, platform access, price model, and migration support. Include strengths Google retains, such as its established Timeline and backup workflow; acknowledge iso.me's local-backup responsibility and lack of Google import.

Include a “Preserve your existing history first” section linking to Google's official export instructions. Explain that beginning iso.me recording does not move the old Google archive. Show an actual iso.me day and exported GPX/CSV examples. CTA: **Try iso.me's free tracking**. Link to private-journal setup, GPX tutorial, privacy, and pricing.

### Brief 4: export reference and GPX tutorial

The reference owns supported formats, data kinds, filters, restore compatibility, destinations, and free versus paid behavior. Add a clear matrix: map-viewing formats versus supported iso.me restore formats. Confirm exactly which entities restore before using “lossless.” Add a preview screenshot and link to automatic exports.

The separate tutorial owns one completed task: **new iPhone recording → stop → select outing/date range → preview → paid GPX export → Files → inspect in a verified compatible viewer**. Include a synthetic example download, timestamps, timezone, and one explanation of GPS gaps/segment breaks. Mention CSV for spreadsheet rows and link to the reference; do not create another near-identical CSV page initially. No guaranteed battery numbers or uninterrupted tracking claims.

### Brief 5: view location history on iPhone

Give an immediate three-path answer: Apple records if enabled/available, Google Timeline if previously enabled, or a dedicated recorder for future history. Show current Apple/Google menus with version and region qualifications. Explain that installing iso.me cannot recover unrecorded past movement.

Use a task comparison table and real screenshots. End with the future-recording option and a link to setup; avoid aggressively pitching an app to someone merely looking for an existing Apple record. Link to the Google comparison and export tutorial. This page owns locating existing records; the private-journal article owns setting up a new journal.

### Brief 6: Dawarich integration

Verify the complete path on a current Dawarich instance: receiver URL, format, authentication, Test Connection, a short recorded route, and confirmation in the destination. Publish supported versions and redacted screenshots. Use [Dawarich's receiver documentation](https://dawarich.app/docs/getting-started/track-your-location/) rather than assuming any JSON endpoint accepts any payload.

Explain that Dawarich has its own iOS app. Position iso.me for users who also want its local journal and export workflow. Webhooks are paid, off by default, and deliberately transmit location records to the configured receiver. If the receiver test fails, fix compatibility or hold this page; publish the improved Watch reference instead.

### Brief 7: Apple Watch reference

Add a verified watch-only walk, GPS/background permissions, reconnect-and-sync steps, screenshots, and troubleshooting for missing points. State supported shipping OS/hardware. Distinguish a route journal from Apple's Workout navigation, training, and Health features. The Watch visit counter is not equivalent to iPhone detected visits. Show free recording and paid iPhone export accurately. Link to the GPX tutorial, timeline docs, and homepage.

## Internal-link map

Homepage → private-journal article, Google comparison, history guide, GPX tutorial, export/Watch docs.

History guide → Google comparison and private-journal setup.

Private-journal article → comparison, GPX tutorial, tracking/privacy references.

Google comparison → setup, GPX tutorial, pricing, privacy.

GPX tutorial → export reference, recording reference, Watch reference.

Export/webhook references → GPX tutorial and tested Dawarich integration, respectively.

Dawarich integration → webhook reference, export reference, local-journal explanation.

Each new detail page receives a contextual link from at least one existing relevant page and its hub, plus a sitemap entry. Use descriptive anchor text. Keep reference docs and acquisition pages self-canonical when they serve distinct purposes; merge genuinely overlapping content rather than cross-canonicalizing distinct guides.

## Proposed schedule and acceptance checks

This is an execution sequence, not a promise of rankings within 30 days.

| Window | Deliverable | Completion check |
| --- | --- | --- |
| Days 1–3 | Fix claim inconsistencies; baseline Search Console; implement outbound/campaign measurement; improve homepage routing and reusable article template | Verify rendered copy against shipping behavior, inspect canonical/indexability, confirm clicks counted once and Apple links use real tokens. |
| Days 4–7 | Improve existing article and export reference; publish Google comparison and `/compare/` | Sources checked; actual app screenshots; no Google import promise; links and sitemap updated. |
| Week 2 | Publish GPX tutorial and iPhone-history guide; update blog hub | Complete both documented tasks on current devices; verify sample files; validate all links and mobile reading/CTA behavior. |
| Week 3 | Improve Watch reference; test and publish Dawarich integration and `/integrations/` | Show watch-to-phone result and actual receiver evidence. If compatibility is unverified, defer the integration. |
| Week 4 | Review discovery, queries, CTR, and outbound clicks; fix mismatches; improve pages receiving relevant impressions | Distinguish pending indexing from weak performance; do not manufacture a winning page from sparse data. |
| Days 31–60 | Add the strongest next intent, then update existing winners | Candidate pool below; choose using Search Console queries and support questions. |

Per-page release checklist: direct useful answer, one distinct intent, accurate steps and screenshots, source links, honest free/paid boundary, descriptive title/meta, one H1, self-canonical, crawlable internal links, relevant Article/Breadcrumb structured data where appropriate, visible author/review date, valid sample downloads, mobile readability, App Store CTA, sitemap coverage. Dates change when content materially changes. Validate the deployment using a fresh browser and Google URL Inspection; direct-fetch 403 in this environment is not proof of a Googlebot problem. Do not spend the first sprint adding FAQ/HowTo markup as a ranking tactic.

## Weekly feedback loop

Use a weekly review initially; the site may not have enough data to justify a daily agent publishing job.

1. Export Search Console query/page/country data and index status. Group synonymous queries by intent.
2. For relevant impressions at weak positions, inspect the existing answer before creating a new URL. Consider a new page only for a materially different task.
3. For relevant impressions at reasonably visible positions but weak CTR, check title, snippet, and intent match. Do not treat every zero-click query as an opportunity.
4. For organic landing traffic without outbound clicks, improve the walkthrough, product fit, and CTA placement. For clicks without visible downloads, inspect App Store messaging and attribution coverage.
5. Record what changed and compare equal-length available windows. At low traffic, use the evidence to select work rather than declare causation.

Report separately: indexed acquisition pages, nonbrand organic impressions/clicks, query relevance, organic landing visits, App Store outbound click rate, visible campaign first-time downloads, and visible paid conversions/proceeds. Account for Apple's attribution window, reporting thresholds, delays, and incomplete cross-system joins. An outbound click is not an install; a paid export unlock is not recurring revenue.

Do not schedule automation without a separate request. Agents can assist research/drafting, but publishing follows verified instructions and real examples rather than a fixed volume quota.

## Expansion order after evidence

1. **Obsidian location journal workflow:** `/integrations/obsidian/`, only after verifying a selected vault/Files path, Markdown structure, date filenames, and best-effort scheduled updates on iOS. Do not imply direct plugin integration without testing.
2. **Walking-route or travel diary guide:** choose one observed intent, with real recording/replay evidence. A walking guide must add materially different guidance from the GPX tutorial.
3. **Apple Maps Visited Places comparison:** create only when Apple-specific queries justify deeper treatment beyond the broad history guide. Avoid three near-duplicate “Significant Locations” pages.
4. **Competitor roundup:** only after testing actual alternatives. No self-awarded “best” rankings backed solely by marketing claims.
5. **Localization:** choose one or two markets using Search Console country/query evidence and App Store demand. Translate a proven cluster, screenshots, navigation, price wording, and relevant docs; use reciprocal `hreflang`, self-canonicals, and a real language selector. Avoid exposing a translation to an unsupported or unusable app flow.
6. **Utility tool:** consider a local GPX/GeoJSON viewer only if query evidence or support requests justify it. Avoid generic converters unrelated to the product audience.

Keep the canonical host stable through this work. Reuse static delivery; extract shared page metadata/layout and generate sitemap dates to prevent copy drift. A CMS, domain move, thousands of pages, or a broad performance rewrite is not a prerequisite.

## Source notes

- Existing [homepage](https://isome.isolated.tech/), [blog](https://isome.isolated.tech/blog/), [article](https://isome.isolated.tech/blog/private-location-journal-iphone/), [docs hub](https://isome.isolated.tech/docs/), and [privacy policy](https://isome.isolated.tech/privacy).
- Local source inventory: `marketing/public`, `marketing/scripts/build_docs.py`, `docs/FEATURES.md`, `docs/pricing-20261025.md`, `IsoMe/Services/LocationManager.swift`, and `IsoMe/Utilities/ImportService.swift`. Source reflects the current working tree and is not proof every feature is in the released binary.
- [Google Timeline iPhone help](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en), [Apple Visited Places](https://support.apple.com/guide/iphone/view-visited-places-iph0c2597c4f/ios), [Dawarich tracking setup](https://dawarich.app/docs/getting-started/track-your-location/), [Apple campaign links](https://developer.apple.com/help/app-store-connect-analytics/acquisition/campaign-links).
- Google guidance: [helpful content](https://developers.google.com/search/docs/fundamentals/creating-helpful-content), [crawlable links](https://developers.google.com/search/docs/crawling-indexing/links-crawlable), [localized versions](https://developers.google.com/search/docs/specialty/international/localized-versions).
