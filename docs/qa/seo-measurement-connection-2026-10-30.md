# Whereday measurement connection, October 30, 2026

Previous goal turn: progress. It published the import guide and completed the zero-issue crawl. This turn connects first-party measurement rather than repeating the same crawl or buying more keyword research.

## Connection

OpenSEO's `list_search_console_sites` found the already-connected Google account with verified owner access to `sc-domain:whereday.com`. Selected that property for project `0e1fd2af-8434-4364-a635-ef57be220fa4` through `set_search_console_site`; no new Google account, OAuth scopes, DNS record, paid tracker, or recurring spend was created. Fresh readback confirms Whereday remains the selected property.

The earlier report's statement that a new connection was needed is superseded: the account connection already existed; only the project/property selection was missing.

## Search baseline

Explicit 28-day windows, final Web data, Google Pacific reporting dates:

- Current: September 30–October 27, 2026.
- Previous: September 2–29, 2026.
- Daily current result: October 26 zero impressions/clicks; October 27 five impressions, zero clicks, average position 5.2. Other dates had no returned rows. Do not interpret the zero-impression position of 0 as a search ranking.
- Query+page current: one disclosed row, `wheredies` → homepage, one impression, zero clicks, average position 2. This is not enough evidence for a content strategy.
- Query+page previous: no rows. With this newly observed domain property, that does not establish that the business had no earlier traffic.
- Page current: eleven rows, each with only one or two impressions and no clicks. Page aggregation differs from the property/date series; do not sum page impressions into a replacement headline total.
- All these dates precede the October 30 changes, so no effect of today's deployments can be measured.

Raw structured responses are saved in `docs/seo-whereday-measurement-baseline-2026-10-30.json`. No pagination was outstanding. The API does not promise every row; a complete returned page is not an exhaustive query inventory.

## Acquisition counters

Ran `python3 marketing/scripts/report_acquisition.py --summary --as-of 2026-10-30 --days 28` against the existing remote aggregate table.

| Completed UTC window | Page loads | Download-link activations | Days with recorded events |
| --- | ---: | ---: | ---: |
| September 4–October 1 | 0 | 0 | 0 |
| October 2–29 | 79 | 5 | 4 |

Current events begin October 26. All five download-link activations are on the homepage with a direct referrer; two page loads carry a search referrer category (home and history guide, one each). These are not unique visitors, organic sessions, installs, purchases, or a click-to-install attribution chain. The collector can include verification and bot requests. No percentage growth or conversion rate is computed. Previous zero counters do not prove collector uptime or zero people visiting.

Saved summary: `docs/seo-acquisition-completed-windows-2026-10-30.json`. Query covers both windows and excludes October 30 entirely. Search Console uses Pacific dates and the collector uses UTC receipt dates; the windows are deliberately not joined.

## Access limit

Attempted to select `https://isome.isolated.tech/` to read migration-era history. Automatic approval review rejected the action as access to a separate private property outside the approved Whereday focus. No predecessor data was read, no alternate account or indirect route was used, and fresh readback confirms the project still selects `sc-domain:whereday.com`. An explicit user approval question is pending. Repository records identify the hostname as Whereday's predecessor, but that documentation was not treated as an override of the rejection.

## Decision

Preserve the deployed, checked content. The sample is too small to justify title rewrites, removing pages, more locales, or more articles. Use later completed data to inspect relevant query/page impressions, discovery of the new import URL, and separate Apple campaign results. Do not interpret zero crawler issues as traffic growth. No future automation or paid recurring tracking was enabled.

Source for API limits, omitted dates, Pacific date boundaries and page/property aggregation: https://developers.google.com/webmaster-tools/v1/searchanalytics/query (read October 30, 2026).

## Google index inspection

Inspected seven priority URLs using the selected Whereday property. Six established URLs (home, private journal, Google Timeline comparison, GPX recording, location history, and export/import docs) report Submitted and indexed, successful fetches, indexing allowed, and matching Google/user canonical URLs. Stored crawls range from October 27–30. The new import guide reports URL is unknown to Google, with unspecified fetch/indexing fields and no crawl time or canonical. These are stored-index observations, not live tests. The earlier production crawl confirms the new guide is crawlable and in the sitemap; its same-day unknown status is not evidence of a technical blocker.

Full response: `docs/seo-whereday-index-inspection-2026-10-30.json`. Preserve the URL and internal links, then inspect discovery with later data.

The audit reviewer checked the measurement numbers and scopes. Incorporated its wording corrections to avoid suggesting an all-time total or a still-missing connection.

## Subsequent authorized comparison

The user subsequently explicitly approved the dedicated legacy property. Its data was read and the project restored to Whereday. See [the domain comparison](seo-domain-move-comparison-2026-10-30.md). That supersedes the earlier dedicated-property access limit; separate parent-domain filtered history remains pending approval.

## Parent-property permission resolved

The user explicitly approved the parent isolated.tech property restricted to the old-host page filter. Reads succeeded and reproduced the saved 5-click/297-impression historical baseline. See the domain comparison and `docs/seo-domain-move-parent-history-2026-10-30.json`. No broader unfiltered data was queried. Whereday remains selected.
