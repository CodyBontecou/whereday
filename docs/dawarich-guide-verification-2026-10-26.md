# Dawarich guide: verification hold

October 26, 2026. Internal preparation only; no indexed integration page was created.

## Finding

`ExportService.exportLocationPointsToOwnTracks` encodes an **array** of OwnTracks messages, including for a single point. `WebhookManager` sends that data as the request body. The current upstream [Dawarich OwnTracks controller](https://raw.githubusercontent.com/Freika/dawarich/master/app/controllers/api/v1/owntracks/points_controller.rb) permits the point fields at the top level and invokes the single-point creator. That is a payload-shape mismatch that must be tested or resolved before the site's existing compatibility statement can be relied on. This source inspection is not a completed failing test against a live Dawarich instance.

The [Overland batch controller](https://raw.githubusercontent.com/Freika/dawarich/master/app/controllers/api/v1/overland/batches_controller.rb) accepts a `locations` collection of feature objects. iso.me's Overland export has that broad shape, making it the next candidate to test. Dawarich's [official tracker setup](https://dawarich.app/docs/getting-started/track-your-location/) documents separate OwnTracks and Overland endpoints and a per-instance API key.

Docker is installed, but neither the default daemon nor the existing Colima context had a running socket in this session. No existing self-hosted receiver or credentials were supplied. No real location history, secret, or test point was sent to an external receiver, and no new daemon or large container stack was provisioned merely for article authoring.

## Additional product detail

`WebhookManager.testConnection` sends a real HTTP POST containing a synthetic coordinate at 0,0, timestamped at the time of the test. A receiver can store it. A 2xx response is not evidence that an intended route has been imported, and the test should not be described as a side-effect-free credential check.

## Draft guide scope after verification

Proposed destination: `/integrations/dawarich/`.

1. Introduce the supported reason to use iso.me alongside Dawarich's own iOS app: a local journal plus optional delivery.
2. Identify the tested Dawarich release and receiver configuration.
3. Pair the exact supported payload format with its matching endpoint. Prefer an HTTPS receiver. Keep credentials out of screenshots and repository files.
4. Show the existing iso.me webhook settings, API-key query configuration, and send mode; explain that this is a paid opt-in feature that transmits records.
5. Test connection with the synthetic coordinate and verify what the receiver stores; clean only the dedicated synthetic test record through the receiver's normal reversible flow.
6. Record or replay a synthetic route in an isolated app store and verify the received points/times in Dawarich.
7. Test a batch, authentication failure, and retry behavior. Do not promise persistent offline delivery: the current webhook queue is in memory.
8. Only then create an indexed page and link it from webhook docs, the export guide, the homepage, and an integrations hub.

## Publication gate

Keep this preparation file out of `marketing/public`. The public webhook reference now describes protocol matching and the unresolved OwnTracks shape instead of labelling these endpoint patterns “known-good.” No app code or test assertions were changed as part of this documentation work.
