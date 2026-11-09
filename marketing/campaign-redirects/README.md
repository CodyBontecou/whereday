# Whereday short-form attribution

The November campaign follows Health.md's per-creative public shortlink convention:

`https://whereday.com/v/<platform>-<angle>-<sequence>`

The source of truth is `../content/fastlane-nov-2026.json`. Each sequence belongs to exactly one creative. `route` and `watch` distinguish this campaign's two product themes. Platforms are `tt`, `ig`, `yt`, `fb`, `x`, `th`, and `web`. Shared, unscheduled Fastlane captions use `web`; replace it with the destination's platform before publishing. Never reuse a token for a different creative.

Every issued link redirects to Whereday app `6761960794`, with the verified developer provider token `126424970`, matching `ct=<platform>_<angle>_<sequence>`, and `mt=8`. Incoming query parameters are discarded. There are no Whereday custom product pages in ASC as of November 4, 2026. The default product page is used until an app-specific, approved CPP is recorded in `customProductPages`; Health.md CPP IDs must never be reused.

## Social → click → Apple campaign

1. Use the platform-specific shortlink in a clickable destination where the platform permits it. Preserve the identical creative sequence in Fastlane captions/descriptions.
2. Instagram and TikTok captions normally do not provide a clickable outbound link. Use a post-specific bio destination, or the channel's selectable bio hub (`/v/tt`, `/v/ig`, etc.) and instruct viewers to choose the matching numbered post. A single generic bio link does not establish per-video attribution. Record the bio/destination configuration before scheduling.
3. The Worker counts eligible GET redirects by UTC day and public campaign token. HEAD checks, known preview bots/prefetches and privacy opt-outs do not increment counts. Bot filtering is best effort, not proof that all remaining requests are people. Privacy opt-outs also receive an untagged App Store URL.
4. In App Store Connect Analytics → Acquisition → Campaigns, compare the identical `ct` tokens. Apple counts first-time downloads within its attribution window and applies reporting thresholds/delay. Keep Fastlane views/engagement, redirect requests, Apple first-time downloads, and sales/subscriptions separate. No user-level join, view-through attribution, or guaranteed install accounting is provided.

The developer provider token is a public campaign parameter, not a credential. No app binary, analytics SDK, identifier, cookie, fingerprint, location upload or install collector is introduced. The table stores aggregate day/token/destination/count only. Request metadata is not written by this Worker; observability is disabled. Unknown links fail closed. Counter failures do not block the App Store handoff.

## Export the reconciliation sheet

```sh
python3 marketing/scripts/list_fastlane_campaign_links.py > /tmp/whereday-campaign-links.csv
```

The sheet maps Fastlane campaign/content/day to the exact channel-specific Apple campaign token. Add actual published Fastlane post IDs/URLs when scheduling; content IDs alone are not published post IDs.

Read aggregate clicks:

```sh
WRANGLER_LOG_PATH=/tmp/whereday-wrangler.log wrangler d1 execute iso-me-onboarding-analytics \
  --config marketing/campaign-redirects/wrangler.jsonc --remote --json \
  --command "SELECT day,campaign_token,destination,count FROM campaign_redirect_daily WHERE day >= date('now','-28 days') ORDER BY day,campaign_token"
```

Apple campaign report (requires an existing, user-authenticated Apple web session in addition to API auth):

```sh
asc web analytics campaigns --app 6761960794 --start YYYY-MM-DD --end YYYY-MM-DD --output json
```

An absent/suppressed campaign row is **unavailable**, not zero downloads. Do not fabricate a conversion rate, sales result, or CPP approval from a working redirect.

## Validate and deploy

```sh
node --test marketing/scripts/campaign-redirects.test.mjs
wrangler deploy --config marketing/campaign-redirects/wrangler.jsonc --dry-run
wrangler d1 execute iso-me-onboarding-analytics --config marketing/campaign-redirects/wrangler.jsonc \
  --remote --file marketing/campaign-redirects/migrations/0001_campaign_redirect_daily.sql
wrangler deploy --config marketing/campaign-redirects/wrangler.jsonc
```

This separately routed Worker handles only `whereday.com/v/*` and `www.whereday.com/v/*`. It does not redeploy the marketing Pages site. The schema is additive and does not change historical app analytics. The database binding reuses the existing website D1 database; the new table is separate.

The browser's existing Apple session was used to verify the provider token by generating `web_route_001` in the official Whereday Campaigns UI. The API CLI has no cached Apple web session, but that does not block browser reporting. The existing `seo-home` campaign is visible in Apple's 90-day report. Its private financial/usage baseline was retained outside version control; it is not evidence of results for the new campaign.

November 4 production version: `282f95d4-0139-4cde-94f5-408b1e3035fb`. All 196 issued links passed HEAD checks for app ID, `pt`, `ct`, and `mt`; see `docs/marketing/2026-11-fastlane/live-link-checks.json`. Recheck after changing routing or the manifest.

Apple's current campaign rules: <https://developer.apple.com/help/app-store-connect-analytics/acquisition/campaign-links>.
