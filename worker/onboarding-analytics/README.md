# Whereday Onboarding Analytics Worker

Cloudflare Worker + D1 ingestion endpoint for Whereday onboarding and activation analytics.

This is intentionally **not** a UI. Data is queried with Wrangler/D1.

## Privacy contract

The worker stores only validated, coarse onboarding/activation fields:

- pseudonymous client UUID (`install_id`); Android creates it only after opt-in and rotates it after opt-out/reapproval
- event name
- app version/build/platform
- coarse onboarding step
- coarse location authorization status and request kind
- coarse tracking-start intent
- paywall context
- allowlisted store product ID and purchase/restore outcome
- coarse error category

It rejects unknown event names/properties and does **not** store raw request IPs,
user agents, coordinates, addresses, place names, route points, raw location
timestamps, exported content, webhook URLs, file/folder paths, device names, or
user-entered text.

## Setup

```bash
cd worker/onboarding-analytics
npm install

# Create the D1 database once.
npm run db:create

# Copy the returned database_id into wrangler.toml.

# Apply schema locally or remotely.
npm run db:migrate:local
npm run db:migrate:remote

# Optional abuse-throttling token.
wrangler secret put INGEST_TOKEN

npm run deploy
```

Expected production endpoint:

```text
https://iso-me-onboarding-analytics.costream.workers.dev
```

The iOS app has this endpoint as its release fallback. You can override it per build
with:

```bash
ONBOARDING_ANALYTICS_ENDPOINT_URL=https://iso-me-onboarding-analytics.costream.workers.dev
ONBOARDING_ANALYTICS_INGEST_TOKEN=<same token as INGEST_TOKEN, if configured>
```

If an ingest token is configured later, treat it as an abuse-throttling key, not
a true secret, because mobile app bundle values are extractable.

## Local smoke test

```bash
npm run db:migrate:local
npm run dev
```

In another shell:

```bash
curl -sS http://127.0.0.1:8787/v1/events \
  -H 'content-type: application/json' \
  -d '{
    "installId":"00000000-0000-4000-8000-000000000001",
    "events":[{
      "eventId":"00000000-0000-4000-8000-000000000101",
      "eventName":"onboarding_step_viewed",
      "properties":{
        "appVersion":"1.8.2",
        "buildNumber":"204",
        "platform":"ios",
        "onboardingStep":"permissions",
        "authorizationStatus":"when_in_use"
      }
    }]
  }'

wrangler d1 execute iso-me-onboarding-analytics --local \
  --command "SELECT received_at,event_name,onboarding_step,authorization_status FROM onboarding_events ORDER BY received_at DESC LIMIT 5;"
```

## Wrangler queries

Recent events:

```bash
wrangler d1 execute iso-me-onboarding-analytics --remote --command \
"SELECT received_at,event_name,onboarding_step,authorization_status,tracking_intent,paywall_context,purchase_outcome
 FROM onboarding_events
 ORDER BY received_at DESC
 LIMIT 20;"
```

Onboarding step funnel:

```bash
wrangler d1 execute iso-me-onboarding-analytics --remote --command \
"SELECT
   onboarding_step,
   COUNT(DISTINCT install_id) AS users,
   COUNT(*) AS events
 FROM onboarding_events
 WHERE event_name='onboarding_step_viewed'
 GROUP BY onboarding_step
 ORDER BY CASE onboarding_step
   WHEN 'welcome' THEN 1
   WHEN 'features' THEN 2
   WHEN 'permissions' THEN 3
   WHEN 'ready' THEN 4
   ELSE 9
 END;"
```

Completion and tracking-start rate:

```bash
wrangler d1 execute iso-me-onboarding-analytics --remote --command \
"SELECT
   COUNT(DISTINCT CASE WHEN event_name='onboarding_started' THEN install_id END) AS started_users,
   COUNT(DISTINCT CASE WHEN event_name='onboarding_completed' THEN install_id END) AS completed_users,
   COUNT(DISTINCT CASE WHEN event_name='onboarding_completed' AND tracking_intent='start_immediately' THEN install_id END) AS start_now_users
 FROM onboarding_events;"
```


## Android contract preparation (October 28, 2026)

The local receiver now accepts `platform: android`, the `notifications` onboarding step/request kind, notification `authorized` status only with an Android notification request, and the individual Android SKU `whereday_export_lifetime`. Android cannot submit Apple product IDs; Android-only fields require the Android platform. Existing valid iOS payloads remain accepted. Both clients still use the coarse property allowlist; device models, advertising IDs, coordinates and arbitrary user text are rejected.

The [shared synthetic fixture](../../android/platform/src/test/resources/usage-analytics-android.json) and [notification fixture](../../android/platform/src/test/resources/usage-analytics-android-notification.json) are checked against the production Kotlin encoder and this receiver. Request streams are bounded to 64 KiB before decoding, including chunked requests without Content-Length; oversized readers are canceled. The local 14-test receiver suite and TypeScript typecheck pass. No migration or deployment was run for this change. The deployed receiver must not be assumed to accept Android yet.

Android now has explicit consent UI, AppGraph ownership, a private AtomicFile queue, event-boundary permission/purchase attempts, cancellable HTTPS and constrained WorkManager recovery. Build availability still defaults false; it does not use an enabled iOS endpoint fallback. Real Play checkout and the wider Android consent matrix remain unverified. The Android ready-page choice now reports its actual intent using the original click-boundary consent, and completion is reported only after acknowledged start when requested and successful setup persistence. Its default is off: metadata and identifiers are not created before consent, a missing transport cannot be enabled, and pre-consent/support-log events cannot be replayed after approval. Successful opt-out clears the consent ID and queue before draining a cancelling request; already received data cannot be recalled. Identity rotation means client UUID counts are not counts of unique people. Retention, operations and Android privacy disclosures remain release work.
