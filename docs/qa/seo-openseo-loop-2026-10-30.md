# OpenSEO improvement loop, October 30, 2026

Scope: the Whereday production site in this repository, https://whereday.com/. OpenSEO project `0e1fd2af-8434-4364-a635-ef57be220fa4` was created because the account only had an unrelated Health.md project.

## Findings and changes

Apple's public US lookup reported **Whereday: Location History 1.8**, released `2026-10-30T11:06:10Z`. This justified removing the temporary iso.me listing notices. Acquisition pages and references contained unconditional free-tracking claims despite version 1.8 new-user plans, and default-on native analytics claims despite production 1.8 disabling those flows.

Corrected the homepage, private-journal article, nine generated content pages, selected documentation, About, agent retrieval guidance, and the Simplified Chinese homepage. Preserve existing-user rights and free supported import/preview. Historical 1.7.16 workflow examples retain their test dates and limits; this task does not certify the released 1.8 importer or promote the migration draft. The comparison now distinguishes the tested older build from unverified current-version compatibility.

Tightened five titles and six description summaries. Documentation H1s remain descriptive; shorter titles use a separate generator field. Generated HTML/Markdown and sitemap modification dates are synchronized with the edited sources.

## Crawl loop

| Audit | Deployment covered | URLs | Issues |
| --- | --- | ---: | ---: |
| `16eca03a-17dd-449d-81be-f36eab7f139e` | Baseline | 42 | 12 |
| `d4c4ab9e-fe40-4e68-b144-1a1d253ec591` | `cb24f269.spotted-8ez.pages.dev` | 42 | 9 |
| `8a74be86-0dc3-44e0-8f0e-d89ab52a109c` | `59cd7043.spotted-8ez.pages.dev` | 42 | 1 |

The remaining warning is the 109-word comparison hub. Its one real comparison and working navigation do not justify padding or noindex. Crawl counts include non-HTML resources and redirect variants.

Final wording-only deployment: **https://ae031427.spotted-8ez.pages.dev**. This adds the Chinese access correction and historical-import clarification after the third crawl. Direct readback proves these files match the local source, accounting only for observed Cloudflare email obfuscation and injected Insights code. See `docs/seo-production-readback-2026-10-30.json` for 15 selected resources. Direct byte equality without accounting for these observed edge transformations is false and was not used as completion evidence.

## Checks

- `python3 marketing/scripts/build_site.py`: passed, 32 HTML documents including 404, 1,131 local targets, metadata, JSON-LD, IDs, sitemap.
- Repeated build: identical output at the first completed pass; final source remains deterministic.
- `node --test marketing/scripts/acquisition.test.mjs`: 6 passed. No runtime acquisition changes.
- `python3 marketing/scripts/check_agentic_http.py`: passed against production after metadata deployment: 31 public HTML/Markdown pairs, cache separation, 404s, API probes, product contract, Accept preferences, HEAD/methods, asset integrity.
- Fifteen final production resources match source after documented edge transformations.
- `git diff --check`: passed.
- No native app code changed; no iOS test run was required.

A generic Python user-agent received a 403 during readback. The site's existing `Whereday-site-verification/1.0` client and curl received successful responses. No edge protection configuration was changed. Three OpenSEO plain crawls read all 42 queued URLs successfully.

## Research limits and next iteration

OpenSEO GSC connection is missing, though repository records describe prior ownership verification elsewhere. Domain overview and ranked-keyword sample had no recorded data; that is not zero traffic. Backlink provider reports 43 links/42 referring domains, which does not establish useful referrals or justify asking Google to ignore links. Keyword research returned an estimated 2,400 monthly US searches for “iphone location history”; five other requested phrases had no metric row, not measured zeros.

Five live query checks are documented in the report; Google-wrapped destination URLs prevent complete position verification. One failed query was retried successfully. No ranking or conversion gain is claimed. Account credits changed from 9,484 to 9,314 during this run (170 credits; account-wide balance difference, not an isolated billing ledger). No recurring tracker or paid AI collection was enabled.

Next: verify Google Timeline import using the distributed 1.8 build and existing QA fixtures, then reconcile the draft, comparison, history, backup, and export references together. Search Console connection helps prioritize later work but does not block importer verification. The broader continuing SEO goal remains active.

The OpenSEO-required second-agent review checked the report and shortlist. It asked for current-versus-historical import clarification and simpler language; those findings were incorporated.
