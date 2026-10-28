# Whereday marketing website

The production host is `https://whereday.com`. The static site lives in
`public/`; edits do not become public until deployment.

## Build and check

```sh
python3 marketing/scripts/build_site.py
```

This regenerates documentation from `scripts/build_docs.py`, renders articles and comparison
pages from `scripts/build_seo_pages.py` and `content/`, updates sitemap coverage,
and validates local links, fragment/ARIA targets, canonical URLs, H1 counts,
descriptions, JSON-LD, and unresolved template tokens. Python 3.9+ is sufficient;
no package installation is needed. A repeated build produces the same output.

The homepage, blog hub, and original private-journal article remain directly
editable HTML. Keep generated HTML in version control. Change documentation in
its generator. Edit the newer articles and comparison in `content/`, with their
metadata in `scripts/build_seo_pages.py`, then rebuild. Do not patch only the
generated versions.

Content review dates are explicit. When materially updating a page, update its
visible date and structured data, plus the corresponding date entries in the
build scripts. Do not refresh every page's date to signal activity.

For checks without regeneration:

```sh
python3 marketing/scripts/check_site.py
```

## Preview

```sh
python3 marketing/scripts/preview_site.py
```

Open `http://127.0.0.1:8767/`. Check the homepage, blog, existing guide,
`/compare/google-maps-timeline/`, and export documentation on desktop and mobile.
The comparison table scrolls within its own region on narrow screens.

The location-history article batch adds `/blog/view-location-history-iphone/` and
`/blog/record-iphone-route-gpx/`. The route guide links to
`/samples/synthetic-route.gpx`, an invented three-point track rather than a real
person's location history. Its bytes were checked after serving locally and
validated against Topografix's official GPX 1.1 schema. Keep the sample labelled
as synthetic if editing it; do not substitute a private recording.

The next batch adds `/blog/back-up-iphone-location-history/` and expands the
existing `/docs/apple-watch/` reference. Backup and Watch instructions are
reviewed against the current source; no new physical-device restore or Watch
recording test is claimed. The article cluster contains the journal, history, GPX, backup, and battery guides; use the site checker for the current full-site page count.

The Dawarich walkthrough remains unpublished pending an actual receiver test.
See the [verification note](../docs/dawarich-guide-verification-2026-10-26.md) for
the OwnTracks payload concern and the Overland candidate. Public webhook copy
now explains receiver compatibility and that Test Connection may store a
synthetic point.

The battery guide at `/blog/iphone-location-tracking-battery/` covers battery
reports, distance filters, stopping controls, and a comparison checklist. Apple
sources are linked in the article. No device battery benchmark is claimed.

The backup article includes `samples/synthetic-visits.json` and
`samples/synthetic-points.json`: invented records near zero dated January 1,
2000, not captured exports. Verify these fixtures against the current Swift
importer on macOS with:

```sh
python3 marketing/scripts/check_import_samples.py
```

This checks record parsing and an invalid-file rejection, not persistence or a
full device restore. Do not import sample data into a real journal just to
inspect the file format. Import currently adds records without deduplication.

The troubleshooting guide at `/blog/missing-location-history-iphone/` handles
existing Whereday recordings; the broader history guide handles locating Apple,
Google, or existing journal records. Keep those search intents distinct.

The Obsidian guide at `/blog/iphone-location-journal-obsidian/` covers a manual
desktop Markdown handoff. Its hand-authored synthetic note was opened in
Obsidian 1.14.4 in a temporary vault. It does not claim verified iPhone vault
access, cloud transfer, scheduled delivery, or bidirectional edit sync.

Guide discovery is organized into finding history, recording routes, and keeping
records. Generated articles use task-specific next links from `RELATED` in
`build_seo_pages.py`; edit that map when changing an article’s next steps.

## Deploy

`marketing/deploy.sh` builds and validates before its existing Cloudflare Pages
deployment. If the optional IndexNow CLI is installed, the script then submits
recent sitemap entries. IndexNow does not replace Google Search Console or
guarantee indexing.

## Acquisition measurement

Search Console ownership is verified. The [October 26 baseline](../docs/search-console-baseline-2026-10-26.md)
preserves September 27–October 24 historical performance from the existing
isolated.tech domain property filtered to Whereday. The dedicated URL-prefix
property is processing; its sitemap already shows Success. Use the report's
stored filters and date windows for repeatable comparisons.

The existing gated Cloudflare Web Analytics integration is retained. This
content batch adds no analytics endpoint, visitor identifier, or campaign token.

1. After deployment, verify the new URLs in Search Console and confirm sitemap
   discovery. Keep the saved pre-deployment baseline; record the deployment date.
2. Add an outbound `app_store_clicked` event to an approved analytics collector,
   with page path and CTA placement. Preserve the current exclusion gate. Do not
   send location records, user text, or arbitrary query strings.
3. Generate genuine App Store Connect campaign links using the account's
   provider token. Suggested stable campaign labels: `seo-home`,
   `seo-private-journal`, `seo-google-timeline`, and `seo-export-reference`.
   Do not invent the provider token or treat ordinary UTM tags as Apple campaign
   attribution.
4. Report organic visits, outbound clicks, visible campaign downloads, and
   purchases separately. Account for reporting thresholds and delays.

See [Apple's campaign-link documentation](https://developer.apple.com/help/app-store-connect-analytics/acquisition/campaign-links)
and the [SEO execution plan](../docs/isome-seo-plan-2026-10-26.md).

## Lighthouse

Run mobile and desktop Lighthouse audits for every page, sequentially so the
browser instances do not compete for CPU. Node.js/npm and Chrome are required.
Lighthouse is pinned to 13.5.0. The command exits nonzero if any category scores
below 100, and saves the full HTML/JSON reports plus `summary.json`:

```sh
python3 marketing/scripts/audit_lighthouse.py --output /tmp/isome-lighthouse
```

Use `--path / --path /docs/` to select pages, `--device mobile` or
`--device desktop` to select a device, and `--base-url http://127.0.0.1:8767`
to audit a local preview. The preview compresses text responses and supports Pages' clean legal URLs.
It does not reproduce edge routing, caching, or production-only analytics;
verify the deployed site separately. Scores depend on test conditions.

Responsive screenshot assets use 480px and 720px WebP variants alongside the
original 900px homepage images and 1206px article images. Keep `sizes` matched
to the rendered image width when changing layouts. Navigation icons include
1x and 3x WebP sources for high-density screens. The docs and SEO generators
inline their small shared stylesheets at build time to avoid a blocking request
on cold loads; keep editing `public/docs/docs.css` and `public/assets/seo.css`,
then rebuild.

## Agent-readable content

The build generates About/Contact pages, a branded `404.html`, Markdown copies
of all public content, and `public/_worker.js`. Edit response behavior in
`worker.js`, trust-page content and conversion in `scripts/build_agentic.py`,
and retrieval guidance in `public/llms.txt`, then rebuild.

The Pages Worker negotiates HTML/Markdown using `Accept`, sends `Vary: Accept`
and `Cache-Control: no-store` for content representations, and preserves static
assets. Unknown URLs return 404; Markdown requests get recovery links, and
requests to missing `/api/` paths receive JSON problem details. The site exposes only public product metadata at `/api/v1/product`, with its
OpenAPI contract at `/openapi.json`; it does not expose a hosted journal API.
Edit that payload and contract together in `scripts/product_contract.py`. The 404 template is excluded from the sitemap
and Lighthouse page sweep.

Use the actual Pages runtime for response checks (the lightweight Python
preview does not run the Worker):

```sh
cd marketing
npx wrangler@4.152.0 pages dev public --compatibility-date 2026-10-26 --port 8890
```

In a second terminal, from the repository root:

```sh
python3 marketing/scripts/check_agentic_http.py --base-url http://127.0.0.1:8890
```

Run the same checker without `--base-url` after deployment to verify production.
