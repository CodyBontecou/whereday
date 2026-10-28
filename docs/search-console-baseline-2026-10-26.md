# iso.me Search Console baseline

Captured October 26, 2026, using the owner's authenticated Google Search Console session.

## Connection and scope

The dedicated URL-prefix property **https://isome.isolated.tech/** is verified. Google confirmed automatic ownership verification through the existing domain-name-provider verification for isolated.tech. No DNS change, verification file, additional account, third-party analytics connection, or API credential was needed.

[Open the dedicated property](https://search.google.com/search-console?resource_id=https%3A%2F%2Fisome.isolated.tech%2F).

The dedicated property's performance and coverage reports are still processing. This is **unavailable data**, not a measured zero. To establish a historical baseline immediately, the existing **sc-domain:isolated.tech** property was filtered to matching page URLs:

```text
^https://isome\.isolated\.tech/
```

Search type: **Web (text-based)**. Period: **September 27–October 24, 2026**, the 28-day window available in Search Console on the capture date. These are Google's reporting dates. The UI showed its last update as three hours earlier. Data is restricted to the canonical HTTPS host, not all isolated.tech products, HTTP variants, or App Store discovery.

The exact filtered report URL is preserved in [the evidence directory](evidence/search-console-20261026/source-report-url.txt). This is a browser-based Search Console connection, not a newly provisioned API integration.

## Search-performance baseline

| Metric | Reported value |
| --- | ---: |
| Google web search clicks | **5** |
| Impressions | **297** |
| CTR | **1.7%** |
| Average position | **6.0** |

The 28 daily rows reconcile to 5 clicks and 297 impressions. Calculated CTR is approximately 1.6835%, matching Google's rounded display. Average position is Google's reported aggregate; it is not a rank for a specific keyword.

### Pages

| URL path | Clicks | Impressions | CTR | Average position |
| --- | ---: | ---: | ---: | ---: |
| `/` | 5 | 164 | 3.05% | 3.88 |
| `/blog/private-location-journal-iphone/` | 0 | 60 | 0% | 8.87 |
| `/docs/` | 0 | 24 | 0% | 8.71 |
| `/blog/` | 0 | 17 | 0% | 15.76 |
| `/docs/webhooks/` | 0 | 8 | 0% | 5.38 |
| `/docs/tracking/` | 0 | 5 | 0% | 1.00 |
| `/docs/getting-started/` | 0 | 5 | 0% | 3.20 |
| `/docs/siri-shortcuts/` | 0 | 5 | 0% | 9.60 |

All 16 exported page rows, including homepage fragment URLs, are retained. Their totals reconcile to the headline clicks and impressions. Page rows and impressions are not counts of indexed pages or unique visitors.

### Disclosed queries

| Query | Clicks | Impressions | Average position |
| --- | ---: | ---: | ---: |
| `iso me` | 1 | 91 | 3.02 |
| `isotopologs` | 0 | 9 | 14.78 |
| `iso tracking` | 0 | 3 | 20.33 |
| `will location store in iphone locally` | 0 | 2 | 10.50 |
| `export isochrones` | 0 | 2 | 53.50 |
| `gps tracking apps for iphone` | 0 | 1 | 2.00 |
| `iphone location tracker app` | 0 | 1 | 3.00 |
| `me iso` | 0 | 1 | 7.00 |
| `iso location` | 0 | 1 | 10.00 |

The largest disclosed query is branded. The privacy and GPS questions are useful vocabulary for existing pages, but one or two impressions do not justify volume claims or a new page by themselves. “isotopologs” and “export isochrones” appear off-target; they should not drive the editorial backlog merely because they received impressions.

### Breakdown limitations

Google displayed: **“Chart totals and table results might be partial when filters are applied.”**

The query export contains 1 click / 111 impressions, rather than the chart's 5 / 297. The country and device exports also total 1 / 111. The page export does reconcile to 5 / 297. These discrepancies are preserved rather than adjusted, imputed, or treated as missing clicks from a known query. This capture does not establish the specific cause of each omission.

There is not enough reliable country-level evidence here to prioritize website localization. Do not calculate a complete branded/nonbranded split from the disclosed query rows, or treat a one-impression average position as a stable ranking.

## Sitemap and indexing baseline

The dedicated property's sitemap report already contains **/sitemap.xml**:

| Field | Google report |
| --- | --- |
| Status | **Success** |
| Submitted | June 13, 2026 |
| Last read | October 25, 2026 |
| Discovered pages | **17** |
| Discovered videos | 0 |

The existing successful submission was retained; no duplicate submission was needed. Seventeen discovered URLs does not mean seventeen indexed URLs. The local SEO working tree contains 19 pages, including the new comparison section, but those changes have not been deployed in this work. The Search Console baseline must not be described as performance of the new content.

Three individual URLs were inspected in the existing domain property:

| URL | Index status | Last crawl displayed |
| --- | --- | --- |
| Homepage | **Page is indexed** | October 15, 2026, 12:14:34 AM |
| Private location journal article | **Page is indexed** | September 30, 2026, 5:37:50 AM |
| Export/import documentation | **Page is indexed** | September 28, 2026, 5:52:40 AM |

For all three, Google reported **Googlebot smartphone**, crawl allowed **Yes**, fetch **Successful**, indexing allowed **Yes**, the expected user-declared canonical, and Google-selected canonical **Inspected URL**. Times above reproduce the UI; its time zone was not independently established.

The homepage's discovery section showed a **Temporary processing error** for its sitemap association. That is separate from the successful sitemap submission and successful homepage index/fetch status. The article and export reference explicitly listed the sitemap. No indexing request, live fetch test, or removal was performed during baseline capture.

Whole-property indexed/not-indexed counts remain **unavailable while the new property's coverage report processes**. Three positive inspections are a sample, not a complete crawl or proof that all seventeen live pages are indexed.

## What this changes in the SEO plan

1. **Keep the existing article as a priority.** Its 60 impressions and zero clicks give a measurable starting point for evaluating its revised answer, title, internal links, and product workflow after deployment. The sample does not establish that the existing title caused the low CTR.
2. **Measure the homepage separately.** It received all five reported clicks. Track whether the new guide links help visitors continue to useful answers and App Store actions; Search Console itself does not measure those onsite actions.
3. **Continue the focused comparison and route/export plan.** The baseline does not validate search volume for those new intents. Treat them as product-fit hypotheses and review the queries they actually earn.
4. **Avoid expanding from irrelevant impressions.** An off-topic query is not automatically an opportunity.
5. **Record deployment as a separate intervention date.** Preserve this pre-deployment baseline. Later compare equally scoped windows, with explicit dates and a reporting-lag allowance. Do not attribute changes to SEO edits without accounting for deployment timing and the small sample.

## How to repeat the baseline

For continuity, use the stored parent-domain report with the exact HTTPS host regex and Web search type. Export the same 28-day dimensions: daily chart, pages, queries, countries, devices, and filters. Record the actual start/end dates, not just “last 28 days.” Retain the filter warning and unavailable/withheld values.

Once the dedicated property finishes processing, compare its overlapping date window with this source before switching reporting properties. Changing from a filtered domain report to a URL-prefix report can alter aggregation and data completeness; mark that transition rather than silently splicing the series.

No recurring automation was created. No private API credential was saved to the repository. Acquisition events, App Store campaign attribution, and deployment are separate tasks.

## Saved evidence

- [Machine-readable baseline](evidence/search-console-20261026/baseline.json)
- [Original Google CSV export ZIP](evidence/search-console-20261026/performance-28-days.zip)
- [Daily data](evidence/search-console-20261026/Chart.csv), [pages](evidence/search-console-20261026/Pages.csv), [queries](evidence/search-console-20261026/Queries.csv), [countries](evidence/search-console-20261026/Countries.csv), [devices](evidence/search-console-20261026/Devices.csv), and [filters](evidence/search-console-20261026/Filters.csv)
- Screenshots: [verification](evidence/search-console-20261026/verified.png), [sitemap](evidence/search-console-20261026/sitemap.png), [dedicated property processing](evidence/search-console-20261026/performance-pending.png), and [filtered historical baseline](evidence/search-console-20261026/baseline.png)
- Detailed inspection captures: [homepage](evidence/search-console-20261026/homepage-inspection.txt), [article](evidence/search-console-20261026/journal-inspection.txt), [export reference](evidence/search-console-20261026/export-inspection.txt)
