# Whereday Scheduled Notifications Worker

Cloudflare Worker that stores routing-only schedule metadata and sends silent APNs pushes for due scheduled exports (once daily or multiple times per day on a fixed interval).

## Endpoints

- `POST /devices/register` — `{ userId, platform, apnsToken, bundleId }`
- `POST /schedules/upsert` — `{ userId, timezone, schedule: { isEnabled, frequency, hour, minute, weekday?, intervalMinutes? } }`
- `DELETE /devices/:userId/:platform`
- `GET /health`

The worker must not store location records, export files, destination folder paths, filename templates, or any user location data. It stores only APNs routing and schedule timing metadata.

## Schedules

- `frequency: "daily"` — fires once per day at `hour:minute` in the install's timezone.
- `frequency: "weekly"` — fires weekly on `weekday` (ISO 1 = Mon … 7 = Sun).
- `frequency: "interval"` — fires multiple times per day. Requires `intervalMinutes` (60–1380, i.e. 1–23 hours). The cycle resets every 24 hours at the daily anchor (`hour:minute`): occurrences fire at `anchor + k × interval` for every `k` with `k × interval < 24h`. Intervals that do not divide 24 (9h, 23h, …) end their cycle early and the next cycle begins at the next daily anchor; a cycle's tail can spill past local midnight.

## APNs payload

Due schedules send background-only APNs:

```json
{
  "aps": { "content-available": 1 },
  "type": "scheduled-export",
  "fireAt": "2026-06-04T21:00:00.000Z",
  "scheduleVersion": 1
}
```

Headers: `apns-push-type: background`, `apns-priority: 5`, `apns-topic: com.bontecou.isome`.

## Setup

```bash
npm install
wrangler d1 create isome-notifications
# paste database_id into wrangler.toml
wrangler d1 migrations apply isome-notifications --remote
wrangler secret put APNS_AUTH_KEY
wrangler secret put APNS_KEY_ID
wrangler secret put APNS_TEAM_ID
npm run deploy
```
