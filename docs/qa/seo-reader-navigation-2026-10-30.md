# Reader navigation and orphan-page prevention

October 30, 2026. Previous goal turn: progress. Nine lossless screenshot copies were deployed and verified. This turn checks discovery independently of sitemap inclusion and adds a release guard against orphan pages.

## Production evidence

Fetched the live sitemap and all 32 listed HTML pages. All returned HTTP 200 and expected canonicals; sitemap membership matches the local published inventory. Following same-host anchor links from the homepage reaches all 32 within at most two links. [Raw depths](../seo-reader-navigation-2026-10-30.json). No orphan page was found and no new internal links were added merely to inflate counts.

This checks static HTML anchor reachability. It does not establish visual prominence, rendered link visibility, Google crawling, indexing, or traffic gains. The earlier Google inspections and performance records remain separate evidence.

## Guardrail

`check_site.py` now records reader anchors separately from metadata and resource targets. Its normal build check walks those links from home and fails on any unreachable published HTML page. It excludes the 404 page and nofollow anchors, and does not count canonical links, alternate metadata, schema, or sitemap entries as reader navigation. Link-target existence checks still run independently.

Four regression scenarios verify a working two-link path, metadata-only orphan, disconnected page cycle, and an orphan linked only by 404/nofollow. All sixteen Python marketing tests pass. The full site build passes 33 documents, 1,181 local targets and the new 32-page reachability check. This adds build validation without changing production HTML.

The user subsequently explicitly approved saving the combined report, including Search Console figures and internal verification notes, to the existing private OpenSEO report. This resolves the earlier export restriction.
