# Bing and Yandex setup — October 30, 2026

Checked the existing authenticated console accounts for `https://whereday.com/`
and `https://gitsync.md/`.

| Site | Bing Webmaster Tools | Yandex Webmaster |
| --- | --- | --- |
| whereday.com | Added and verified using BingSiteAuth.xml; sitemap.xml submitted October 30, currently Processing | Added and ownership verified October 30 using HTML file; sitemap submission blocked by service read-only mode |
| gitsync.md | Existing verified property; sitemap.xml shows Success, last submitted/crawled October 26, 19 URLs discovered | Existing verified property remains accessible; sitemap submission blocked by service read-only mode |

Yandex explicitly reports: “The Sitemap files tool is in read-only mode.” The Add
button is disabled for both properties. Neither Yandex sitemap was submitted.
The next remaining action is to submit each site's `/sitemap.xml` when Yandex
restores the tool. No scheduled follow-up was created.

Both live robots.txt files allow crawling and declare their canonical sitemap.
Both public sitemaps returned successfully and parsed as XML. Whereday has 32
URLs; GitSync has 24 (Bing's earlier crawl discovered 19). Console submission, discovered URL counts, and verified
ownership do not establish indexing.

## Whereday deployment

The dedicated `marketing/search-verification/` Worker serves only:

- `https://whereday.com/BingSiteAuth.xml`
- `https://whereday.com/yandex_c33e9b984dcbf487.html`

Deployed Worker: `whereday-search-verification`; version:
`b97ee761-934b-4ec1-a641-d121ab50d63c`. Retain these public proofs for future
ownership checks. The main marketing site was not redeployed.

Validation: Wrangler dry run, successful production deployment, live proof
fetches, both console ownership checks, XML sitemap parsing, and local GET,
HEAD, POST, wrong-host, and missing-path response checks. No iOS source changed.

## Evidence

Screenshots are saved in
`/Users/codybontecou/.codex/visualizations/2026/10/30/01a18e25-8a3a-7331-9d76-c65ea9a87ee5/`:

- `bing-whereday-sitemap.png`: submission Processing.
- `bing-gitsync-sitemap.png`: Success and 19 discovered URLs.
- `yandex-whereday-verified.png`: Owner role and October 30 verification date.
- `yandex-whereday-readonly.png`: disabled sitemap submission.
- `yandex-gitsync-readonly.png`: disabled sitemap submission.

Console links:

- [Whereday in Bing](https://www.bing.com/webmasters/sitemaps?siteUrl=https://whereday.com/)
- [GitSync in Bing](https://www.bing.com/webmasters/sitemaps?siteUrl=https://gitsync.md/)
- [Whereday in Yandex](https://webmaster.yandex.com/site/https:whereday.com:443/indexing/sitemap/)
- [GitSync in Yandex](https://webmaster.yandex.com/site/https:gitsync.md:443/indexing/sitemap/)
