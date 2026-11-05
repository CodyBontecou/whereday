# Whereday Play purchase verifier

Cloudflare Worker that checks Android purchases with Google and returns a short signed lease the app verifies offline. The app never unlocks anything from a Play client callback alone.

## Endpoints

- `POST /v1/challenges` — `{ publicKey, packageName }` → `{ challengeId, nonce }`. One use, five minutes, bound to the device key.
- `POST /v1/verify` — `{ challengeId, publicKey, packageName, productId, purchaseToken, signature, integrityToken }` → `{ status: "active", lease }`, `{ status: "pending" }` or `{ status: "revoked" }`. Any other failure is a non-200 answer, which the app treats as "verification unavailable" and keeps a still-valid cached lease.
- `GET /health`

`verify` checks, in order:

1. The challenge exists, is unexpired and belongs to this device key. It is deleted either way.
2. The device signed `whereday-purchase-v1\nchallenge\nnonce\npackage\nproduct\nsha256(token)\nsha256(key)` with its Android Keystore key.
3. Play Integrity attests that exact message: `PLAY_RECOGNIZED` app, `MEETS_DEVICE_INTEGRITY` device, within five minutes.
4. The Android Publisher API reports the purchase. Lifetime (`whereday_export_lifetime`) must be purchased. The subscription (`whereday_individual`, weekly and yearly base plans) must be active, in grace, or canceled but still paid through. The Worker acknowledges unacknowledged purchases, since Play refunds those after three days.
5. At most five device keys per purchase.

The RS256 lease carries `aud` (package), `sub` (device key hash), `product_id`, `purchase_token_hash`, `iat`, `refresh_after` (one day) and `exp`: seven days, or the subscription's paid-through time if sooner. D1 stores only challenges and hashed token/device pairs: no tokens, accounts or locations.

## Setup

1. In Google Cloud, enable the Google Play Android Developer API and the Play Integrity API for the project linked to Play Console. Create a service account and a JSON key.
2. In Play Console → Users and permissions, invite the service account with access to Whereday's financial data and order management.
3. Create the lease key and the D1 database:

   ```sh
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out lease.pem
   openssl pkey -in lease.pem -pubout -outform DER | base64   # → whereday.purchaseVerifierPublicKey
   wrangler d1 create whereday-play-purchases                 # → database_id in wrangler.toml
   wrangler d1 migrations apply whereday-play-purchases --remote
   wrangler secret put LEASE_PRIVATE_KEY < lease.pem
   wrangler secret put GOOGLE_SERVICE_ACCOUNT < service-account.json
   wrangler deploy
   ```

4. Build the app with these Gradle properties (for example in `~/.gradle/gradle.properties`):

   ```properties
   whereday.purchaseVerifierUrl=https://whereday-play-purchase-verifier.<account>.workers.dev
   whereday.purchaseVerifierPublicKey=<base64 SPKI from step 3>
   whereday.playCloudProjectNumber=<Google Cloud project number>
   ```

   Without all three, the app treats Play billing as unconfigured: recording stays free and exports stay locked.

## Tests

```sh
npm test
npm run typecheck
```
