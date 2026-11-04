# Search Console comparison across the Whereday move

Captured October 30, 2026 after explicit user authorization to read the dedicated `https://isome.isolated.tech/` property and compare it with Whereday. Raw responses: [JSON](../seo-domain-move-comparison-2026-10-30.json).

## Scope and timing

Final Web data, Pacific reporting dates, through October 27. The move was confirmed October 26 in Puerto Rico; Google's move UI displayed October 27. Daily rows cannot isolate the exact cutover. Today's SEO deployments are outside the measurement dates. Whereday was restored as the selected project property after each legacy read.

## Findings

- Unfiltered property/date series for September 30–October 27: old prefix property returns 0 clicks/26 impressions on October 25–27 only; Whereday returns 0 clicks/5 impressions on October 26–27 only. These are separate property results, not before/after totals.
- With each property filtered to its own HTTPS host (page aggregation), October 25 old/new impressions are 7/no returned row, October 26 are 6/0, and October 27 are 14/14. No clicks returned. The equal 14s do not demonstrate traffic transfer or a combined unique audience.
- The property/date counts for October 27 are instead 13 old/5 new. This is an aggregation difference; do not silently mix these series.
- Matching page rows in September 30–October 27 have old/new impressions: home 10/2, private journal 2/2, history 8/1, webhooks 1/1, about 1/1. All have zero clicks. Different reporting-history availability prevents treating these as a content performance experiment.
- The legacy prefix property returns no rows for September 27–October 24, while the saved parent-domain filtered baseline for those dates reports 5 clicks/297 impressions. Preserve the historical source and label the gap; empty prefix data is not evidence that earlier traffic vanished.

The six established Whereday URLs inspected earlier are indexed with matching canonicals. Combined with new-host impressions this establishes some Google visibility, not a completed migration, retained rankings, or a traffic gain/loss. Keep redirects, canonicals, sitemap and ownership verification; evaluate later completed windows and matching page paths.

## Access limit

The user approved the dedicated old property. Automatic approval review separately rejected binding the broader `sc-domain:isolated.tech` property because it could expose unrelated sites' private data. The rejected binding did not execute. Subsequent old-host-filtered calls still targeted Whereday; they are excluded from comparison evidence. No parent-property data was read. A new user question requests permission for that property solely with the `https://isome.isolated.tech/` page filter. Do not retry without it.

## Saved report

Updated the existing October 30 OpenSEO audit report in place, preserving the crawl, deployment and measurement evidence. No website changes or recurring tracking were needed for this comparison.

## Parent-host history recovered after explicit approval

User approved the broader property solely with the old HTTPS hostname page filter. All four successful parent-property queries used that filter, final Web data and Pacific dates; no unfiltered parent data was requested. Raw requests/responses: [parent history](../seo-domain-move-parent-history-2026-10-30.json). Earlier access limits above are superseded. Whereday was restored after the reads.

- September 27–October 24: 28 daily rows, 5 clicks/297 impressions, exactly reproducing the saved baseline.
- September 2–29: 5 clicks/396 impressions; September 30–October 27: 3 clicks/286 impressions on the old host. The second window is mostly pre-move, so the reduction cannot be attributed to the move.
- New-host page-filtered September 30–October 27 response: 0 clicks/14 impressions, with rows only October 26–27. This differs from the unfiltered property/date count of 5 because of aggregation.
- September 28–October 25 old-host context: 5 clicks/293 impressions over 28 days. October 26–27: old 0/20, new 0/14 over two dates. Unequal periods do not establish retention, gain or loss.
- Fuller old page history: home 3 clicks/146 impressions; private journal 0/56; history 0/8; webhooks 0/9; about 0/1. The earlier prefix-property path table has limited history and is superseded for historical reporting.

The updated report uses the parent-host-filtered series for old history and retains the prefix results only as an explicit reporting-source comparison. Keep the current redirects and content and evaluate later completed windows.
