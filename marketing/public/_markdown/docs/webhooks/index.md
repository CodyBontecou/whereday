§ Docs · Guide 8 of 11

# Webhooks & self-hosting

The optional webhook feature posts your data, in your chosen format, to a server you control. Off by default, fully configurable, and nothing is sent until you set it up.

Platform

iOS 17+

Updated

October 30, 2026

Reading

4 min

Back to

[All docs](https://whereday.com/docs/)

Export access, off by default. New users from 1.8 retain export access after tracking expires; existing free users keep their previous export-unlock requirement. Webhooks can send your location data off your device — to a server you choose. Nothing is sent until you configure an endpoint, and the settings screen leads with a prominent warning card.

## What it's for

Webhooks deliver records to a receiver you choose, but the payload must match that receiver's API. An endpoint accepting HTTP POST is not enough to establish compatibility. Verify a dedicated test destination before enabling delivery of your history.

| Service | Endpoint shape | Format
| Dawarich batch receiver | `…/api/v1/overland/batches` | Overland; verify against your instance before use
| OwnTracks receiver | per your installation | Verify it accepts arrays of location messages
| Other receiver | its documented HTTPS endpoint | Use its required schema and authentication

Whereday encodes OwnTracks points as an array, including a single point. Dawarich's current OwnTracks receiver expects individual point fields, so do not assume those two shapes are interchangeable. Its Overland batch receiver is a different protocol. Check [Dawarich's receiver documentation](https://dawarich.app/docs/getting-started/track-your-location/) and confirm actual imported points; the [checked Overland walkthrough](https://whereday.com/integrations/dawarich/) records native batch delivery and receiver persistence on Dawarich 1.18.0. Physical recording and unattended delivery remain separate checks.

## Configuration

- Endpoint — the URL to POST to.
- Format — JSON, CSV, Markdown, GeoJSON, GPX, KML, OwnTracks, or Overland.
- Authentication — none, API key in the query string, Bearer token, Basic auth, or a custom header. Credentials live in the iOS Keychain, never in plain preferences, and error messages mask them.

## Send modes

| Mode | Behavior
| Real-time (default) | Each newly recorded point is sent as it's saved. On launch the sender “arms” itself first, so enabling webhooks never bulk-dumps your history.
| By count | Points queue and flush every 5 / 10 / 25 / 50 / 100 points.
| By time | Queued points flush every 1–60 minutes.
| Manual | Nothing sends automatically; Send now posts your entire saved history (visits and points).

Batch modes show a live queue count with a Flush queue button. Failed sends retry automatically with backoff on network errors and server hiccups (5xx, timeouts, rate limits); authentication failures don't retry — they surface as an error. Test connection POSTs a synthetic point at latitude 0, longitude 0, with a current timestamp. A receiver can store it. A successful HTTP response confirms acceptance of that request, not that a real route was correctly imported.

Know your destination. This is the one feature that transmits your location data off-device. Use endpoints you trust (ideally your own servers), and remember queued-but-unsent points in batch modes are lost if iOS terminates the app before a flush.

[← PreviousAutomatically export iPhone location history to a folder](https://whereday.com/docs/automatic-exports/) [Next →Siri & Shortcuts for iPhone location tracking](https://whereday.com/docs/siri-shortcuts/)

Source: https://whereday.com/docs/webhooks/
