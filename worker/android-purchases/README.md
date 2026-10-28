# Android lifetime purchase verifier

Local implementation for the approved individual lifetime Android export purchase. **Not deployed or connected to a Play product.** The all-zero D1 ID, empty signing-certificate allowlist and missing secrets intentionally prevent a working production endpoint. No successful real purchase has been tested.

## Verify locally

Use Node 22.12 or newer. `npm ci`, `npm run types`, `npm run check`, `npm test`, and `npm run dry-run` do not create remote resources. Tests generate synthetic RSA keys and use an ephemeral local D1 database. Google transport is intercepted in its test; no test sends purchase proofs to Google. The missing-secret warning during tests is expected.

Wrangler generates `worker-configuration.d.ts` from `wrangler.jsonc`, including required secret bindings. Regenerate after changing configuration. Tracing is disabled because Publisher API URLs contain purchase tokens. Application logs contain only fixed event/category fields. Keep external logging, fetch tracing and request-body capture disabled for this service.

## Protocol and ownership

1. `POST /v1/challenges` accepts `packageName` and an RSA public key in Base64 X.509 SPKI form. The server returns a random `challengeId` and `nonce`, valid for two minutes and bound to the SHA-256 hash of the key. Per-minute request buckets limit abuse.
2. Android queries Google Play for its owned purchases. Only this internal query feeds verification; there is no exported token-submission intent/provider. For a purchased lifetime product it hashes the token and signs the canonical message below using an app-private Android Keystore RSA key. Play Integrity Standard attests the Base64URL SHA-256 hash of that exact message.
3. `POST /v1/verify` includes `challengeId`, `publicKey`, `packageName`, `productId`, `purchaseToken`, `signature` and `integrityToken`. The server verifies the signature and atomically consumes the challenge before contacting Google. Retry a failed attempt using a new challenge.
4. Google decrypts the Integrity verdict. The service requires the expected request hash/package, a timestamp within five minutes, a recognized version signed by an allowlisted Play signing certificate, an eligible version code, a licensed account, and device integrity. It then queries Publisher `purchases.products.get` with the exact package/product/token. Unknown states fail closed; pending and canceled purchases never grant access. An eligible lifetime purchase must be unconsumed and single quantity.
5. The ledger saves a stable grant for the token hash **before** Publisher acknowledgement. Acknowledgement failure does not issue a lease; a fresh challenge retries safely. This service never consumes lifetime products. Already acknowledged purchases can be restored.
6. The server returns an RS256 JWT bound to package (`aud`), product (`product_id`), device key hash (`sub`), stable grant (`jti`) and purchase-token hash. The client pins the verifier's RSA public key. `iat` is current server time, `refresh_after` is one day, and `exp` is seven days. No editable boolean or client purchase callback grants paid access.

Canonical UTF-8 message, seven lines, **no trailing newline**:

```text
whereday-purchase-v1
<challengeId>
<nonce>
<packageName>
<productId>
<lowercase SHA-256 hex of purchaseToken UTF-8>
<lowercase SHA-256 hex of public key SPKI bytes>
```

The signature is SHA256withRSA/PKCS#1 v1.5, encoded Base64URL without padding. JWT signatures use the same algorithm. Device and server keys must be at least 2048 bits. No raw purchase token, order ID, email, sender JWT, notification body, Integrity proof or private key is stored in D1. Permanent hash-only revocation tombstones prevent an unseen or previously revoked token from becoming active. Notification receipts retain only a message hash, a processing lease, fixed outcome and timestamps for 35 days; cleanup does not delete revocation fences. Grants retain token hashes and product/state/verification timestamps for stable restores; challenges/key hashes and daily salted address rate buckets are removed by daily cleanup after expiry. No location history enters the purchase protocol.

Play tokens remain bearer proof. The recognized application obtains them from Play and binds that query result to the device signature and Integrity request; this is not an account-based license or a family service. A mandatory app account/device-count policy is not introduced. Review token ownership against the release threat model and test multiple Play accounts/devices before launch.

## Concrete production setup remaining

- Create the proposed non-consumable product `whereday_export_lifetime` in Play Console, with one non-rental purchase option, regional prices and multi-quantity disabled. The identifier is a proposal until actually provisioned. This service currently uses Publisher's supported single-product endpoint; do not enable a multi-product lifetime bundle without extending and testing verification.
- Link the Play app to its Google Cloud project, enable Publisher and Play Integrity APIs, and grant a dedicated service account the minimum Play Console purchase permissions needed to query/acknowledge this app. Record the numeric Cloud project number for Android.
- Set `PLAY_CERTIFICATE_SHA256` to the Play **app signing** certificate digests encoded Base64URL SHA-256, comma separated for permitted key rotation. Set the minimum supported version. Do not allow a debug signing certificate in production.
- Create a dedicated D1 database, replace the placeholder ID and apply all migrations, `0001_purchases.sql` through `0003_voided_reconciliation.sql`, before deploying this code. Generate a dedicated RSA signing key. Store the Google service-account JSON and RSA PKCS#8 private key as `GOOGLE_SERVICE_ACCOUNT_JSON` and `LEASE_PRIVATE_KEY_PKCS8` Wrangler secrets. Neither private key belongs in the app or source control. Export only the verifier's Base64 X.509 SPKI public key to Android.
- Bind an HTTPS hostname/route, verify logs and retention settings, deploy the reviewed service, and confirm the hourly maintenance trigger. The Voided Purchases scan remains separately disabled until explicitly configured. Keep the prior verification key supported during a planned pin rotation/app rollout.
- Build Android with nonsecret Gradle properties `whereday.purchaseProductId`, `whereday.purchaseVerifierUrl` (HTTPS origin or path prefix), `whereday.purchaseVerifierPublicKey`, and `whereday.playCloudProjectNumber`. Without valid values the UI offers no checkout.
- Upload the signed app to a Play internal track and test with license testers: pending/completed/canceled, acknowledgement interruption, restore/reinstall/second device/account switch, refund, no-network grace, expiry, clock error, integrity failures and every paid export entry point. A sideloaded debug build cannot prove these flows.

## Reconciliation and remaining work

Each verification re-queries Play; canceled/missing purchases revoke the ledger entry and the client clears its cached grant when the response is received. A fresh successful empty ownership query also clears the cache. Temporary service failures may use a still-valid signed lease, for at most seven days after its issue time. Android refreshes when the activity resumes and on purchase callbacks. Paid foreground/background entry points can renew expired access through Play/server verification; concurrent renewals coalesce, and still-valid offline grants need no request. Free features do not wait for that work.

**Authenticated RTDN/Pub/Sub ingestion and resumable Voided Purchases reconciliation are implemented locally below. Both remain disabled by default.** The hourly maintenance handler always performs local-ledger cleanup and polls Play only when `VOIDED_RECONCILIATION_ENABLED=true`. Real Google provisioning, delivery, retry/dead-letter monitoring and refund/revocation sandbox acceptance remain unverified. Existing offline signed leases cannot be recalled remotely; revocation becomes effective on a successful refresh or lease expiry. Scheduled/background paid actions must refresh appropriately before using the shared gate. Operational alerting, Google quotas, failover, key rotation, abuse testing, ownership review and a complete retention/deletion policy remain release gates. The seven-day cache interval is the current implementation policy and must be reflected in customer-facing purchase terms before launch.

Primary references: [Play billing security](https://developer.android.com/google/play/billing/security), [product purchase resource](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products), [product query](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/get), [acknowledgement](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/acknowledge), [Integrity Standard](https://developer.android.com/google/play/integrity/standard), [Integrity verdicts](https://developer.android.com/google/play/integrity/verdicts).


## Authenticated real-time notifications

`POST /v1/rtdn` is disabled by default (`RTDN_ENABLED=false`). Its production settings are empty, so it cannot currently accept a real notification. It uses pinned `jose` 6.2.13 with Web Crypto to verify RS256 Google OIDC signatures using only the fixed Google JWKS endpoint. Public verification keys may be cached; sender tokens and message bodies are not cached or logged. Key fetches have a five-second timeout, reject redirects and limit responses to 64 KiB. Requests limit the bearer header and JSON/decoded message sizes before processing.

Enable only after provisioning a dedicated authenticated wrapped Pub/Sub push subscription. Set nonsecret `RTDN_AUDIENCE` to the exact HTTPS audience (normally the full `/v1/rtdn` endpoint URL, without query/fragment), `RTDN_PUSH_EMAIL` and `RTDN_PUSH_SUBJECT` to the push service account's email and numeric unique identity, and `RTDN_SUBSCRIPTION` to the complete `projects/.../subscriptions/...` name. The verifier checks issuer, exact audience, subject, verified email and token times. Follow Google's [push authentication setup](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions) for the service agent's token-creation permission, and its [Play RTDN setup](https://developer.android.com/google/play/billing/getting-ready#configure-rtdn) for the topic publisher and Play Console configuration. Restrict publishers and use a dedicated push identity; the bearer identity authenticates delivery and does not independently sign the body. Keep header/body capture disabled at every proxy.

The wrapped envelope must match the configured subscription and package, supported version and one mutually exclusive event. The handler returns 204 only after durable processing or a completed duplicate receipt. A concurrent in-progress delivery returns a retryable non-success response; a processing lease can be reclaimed after two minutes if the worker dies. Failed processing releases its claim. Completed receipt hashes expire after 35 days. Configure and monitor a [dead-letter topic](https://docs.cloud.google.com/pubsub/docs/dead-letter-topics); malformed/unknown versions, authentication failures, unavailable services and unsettled Publisher state return non-success rather than being silently acknowledged.

Purchased/canceled one-time notifications re-query Publisher using the exact configured product and received token. Pending/unsettled results retry. Notifications never create grants, issue leases or acknowledge Play purchases: that remains the attested device verification path. A canceled/missing authoritative purchase revokes its token hash. Google's [full one-time void notification](https://developer.android.com/google/play/billing/rtdn-reference#voidedpurchasenotification) supplies the token needed for entitlement adjustment; the handler permanently revokes it even before a grant exists. Other products, subscriptions, pending refund-review events and quantity-based partial refunds are acknowledged as irrelevant to the current single-quantity lifetime grant policy; no entitlement change is made for them. Pending refund-review operations still need an owner workflow.

Revocation creates its tombstone and updates an existing grant atomically. New grants cannot bypass that fence or reactivate an older revoked grant. Device verification checks again after acknowledgment/signing, and reports revoked if a concurrent notification reached the ledger before that final check. This closes the tested in-flight windows; it does not recall leases already issued or guarantee revocation after the final check but before HTTP delivery. Existing signed offline leases can remain usable until a successful refresh or expiry, as described above.

Local tests use synthetic RSA identities, intercepted fixed-host key responses and ephemeral real D1. They cover identity/audience/algorithm/time tampering; bounded malformed envelopes; duplicate/busy/expired claims; fresh Publisher re-query and unsettled retries; full voids before/after a grant; stale purchased delivery; a failure after revocation but before receipt completion; migration from legacy revoked rows; and revocation before grant, during acknowledgment and during signing. No real Google request, purchase, push subscription or deployment is used. The existing CI worker job includes these tests automatically.

The separate pull recovery path is implemented below. Local RTDN/scanner tests do not prove production notification coverage or financial-permission acceptance.


## Resumable Voided Purchases recovery

The hourly maintenance trigger is defined at minute 17, but no service/cron has been deployed by this task. `VOIDED_RECONCILIATION_ENABLED=false` prevents provider work. Before enabling it, apply migration 0003, grant the service account the app-specific financial/purchase access required by the [Voided Purchases API](https://developers.google.com/android-publisher/voided-purchases), and store a separate random 32-byte Base64URL AES key as the `VOIDED_CURSOR_KEY` Wrangler secret. It belongs only on the server. Production API access and quota/latency acceptance still need verification.

Each invocation processes at most four pages of fifty records, with a 1 MiB upstream JSON bound. The confirmed Publisher wire parameters are `maxResults` and `token`; Google's public discovery schema and its REST example were checked. Only in-app products are requested (`type=0`), with quantity-based partial refunds excluded under the single-quantity lifetime policy. This scan creates hash-only revocation fences for known/unseen tokens and never grants access. Raw tokens, orders and page bodies are discarded after processing. Counts describe processed records, including overlap/replay, rather than unique new refunds.

On bootstrap, Google selects its full default available window, and the requested end is one minute before execution time. After a completed scan, the next window overlaps the prior end by one day. An immediate duplicate completed invocation is throttled for thirty minutes. The [API time filter](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.voidedpurchases/list) is based on when Google sees a record as voided, so the scanner does not drop returned records with an older actual `voidedTimeMillis`.

Migration 0003 stores one active window/lease per package. A three-minute claim, renewed at checkpoints, excludes concurrent work; a stale owner cannot checkpoint, complete or release a newer owner. Revocations apply in parameter-bounded atomic chunks of twenty-five before the page checkpoint advances. A later write failure leaves earlier terminal fences intact and the page replayable. Completion/next-page bookkeeping follows all revocations for that page; it never advances based only on a fetched response.

The opaque next cursor is AES-GCM encrypted with fresh nonces and associated data binding the package and window. Plaintext cursors are limited to 8 KiB of UTF-8; persisted ciphertext is bounded and authenticated. Returning invocations reconstruct/decrypt the checkpoint without needing the original worker. Wrong keys, corrupt/moved cursors and invalid pages fail closed and preserve progress. Before key rotation, retain the old key until active runs have drained, disable scanning, and verify no active window/lease remains. Do not rotate it blindly while ciphertext is pending. Emergency key loss requires an explicitly audited recovery; it is not silently converted to an empty scan.

An old cursor is attempted even when its original time window has aged; pagination may still be valid. A provider rejection triggers one bounded recovery from the available default window per invocation and latches `coverage_gap`. A failed first page with no durable snapshot also recomputes a now-valid window, flagging uncertain bootstrap coverage. Proven overlap from a previous completion is retained when possible. Outages beyond the provider's available history are flagged; the code cannot recover unavailable older records or claim complete lifetime refund coverage. Repeated cursors fail without false completion. Busy/disabled results use `coverageGap=null` because that invocation did not assess coverage.

Operational logs contain fixed event/status/category fields, processed counts and a gap boolean, never exception text, URLs or tokens. Connect real alerts for `voided_reconciliation_failed`, `voided_reconciliation_coverage_gap`, stalled coverage and RTDN dead letters before launch. Inspect health without selecting the encrypted cursor:

```sql
SELECT scope, last_completed_end, last_success_at, last_error_at,
       restarts, coverage_gap, run_end IS NOT NULL AS pending_window,
       cursor IS NOT NULL AS has_cursor, lease_until
FROM voided_reconciliation;
```

API window timestamps above are milliseconds; maintenance timestamps/leases are seconds. `complete` means one provider window finished, not that a latched coverage gap was repaired. A large backlog can return `more` across multiple hours. Gaps stay latched until an independent audit resolves them. Keep supported recovery code at or after the terminal-revocation migration; rolling back to code that can reactivate revoked tokens is not a supported recovery strategy. Review database retention, key rotation, forward-fix/restore and customer offline-cache terms before release.

The complete local Worker suite has thirty tests, including fourteen scanner cases: multi-invocation encrypted pagination, known/unseen token fences, malformed/oversized pages, loops, failed later chunks, failure after revocation before completion, competing/expired owners, wrong keys/window binding, expired provider cursors, old valid snapshots, overlap/outages, interrupted first pages, state constraints, byte bounds, and the real scheduled handler with intercepted OAuth/Publisher transport. Type checking and a local bundle dry-run pass. The scanner's live Google permissions, real refunds/chargebacks, production cron delivery, quotas, long backlogs, alerting and operator recovery remain release gates.
