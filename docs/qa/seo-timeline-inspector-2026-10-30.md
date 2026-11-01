# Timeline JSON inspector and format tutorial

Published October 30, 2026:

- https://whereday.com/tools/google-timeline-inspector/
- https://whereday.com/blog/google-timeline-json-formats/
- Final deployment: https://cee606ec.spotted-8ez.pages.dev

Independent local coordinate/time inspection for raw locations, semantic timelineObjects, semanticSegments and recognizable device arrays. Does not authenticate Google provenance, validate every optional field, reproduce native duplicate rules, predict import counts or recover missing history. Limits: 10 MiB, 50,000 source entries or expanded components, 20 preview records and at most 2,000 plotted locations. Untimed points are reported; no inferred route lines.

Three supported synthetic fixtures plus an unrelated JSON example. Existing device fixture remains unchanged. Expected browser device counts 2 visits/4 points before duplicate handling; raw 2 timed points/1 invalid coordinate; semantic 1 visit/3 points/1 untimed coordinate/1 unsupported row. Native first-import preview for the repeated device sample is documented separately as 1 new visit/2 points.

## Verification

34 JavaScript tests (including 13 Timeline cases and privacy alias tests) and 16 Python tests pass. Build: 39 HTML documents including 404, 38 published pages, 1,397 local targets, all reader-reachable within two links. Repeated build byte-identical. Local and production HTTP checker passes all 38 HTML/Markdown pairs and product contract. Final live evidence checks eight asset hashes/MIME types, four JSON attachment headers, links from three reader surfaces and both sitemap entries.

Production canonical tool documents have connect-src none, no-referrer, no-transform and no analytics. Slashless/direct-index aliases return empty 308 redirects to the protected canonical page; local Worker unit checks also cover those aliases. An initial verifier assumed every alias returned HTML; updated it to verify these observed empty redirects instead.

In-app browser used as requested. Stale earlier tab recovered by creating a fresh tab in the same selected browser. Tested three demos, actual raw JSON file chooser, unsupported JSON chooser, Clear (all nine result nodes empty and input cleared), coordinate dots, mobile 390/desktop 1280 layout and keyboard horizontal table scrolling (40px observed). Production demos and Clear checked; page width equals mobile viewport width. Screenshots: timeline-inspector-desktop-2026-10-30.jpg and timeline-inspector-mobile-2026-10-30.jpg. Versioned stylesheet URL prevents a returning browser from retaining the previous GPX stylesheet without select styling. Viewport reset; tool left open. No physical iPhone test claimed.

Second review found one tutorial wording error: only raw/semantic examples start at 0/10, not the reused device example. Corrected before publication; no other material finding reported.

Audit 02d20586-0b85-4ba0-9f1d-d02b4bfd6c0b completed 55 URLs. Two informational description length findings (161/166 chars) corrected in final release and production descriptions verified at 149/143 characters. No warning or critical findings. Earlier tools-hub thin-content warning absent after adding second real tool. No new ranking, indexing or traffic gain claimed. Existing private OpenSEO content/tools report updated in place.

Evidence: ../seo-timeline-inspector-live-2026-10-30.json and ../seo-timeline-inspector-audit-2026-10-30.json.
