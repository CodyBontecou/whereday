§ Legal · Document 01

# Privacy Policy

Effective

August 22, 2026

Updated

November 6, 2026

Scope

Whereday — iOS, watchOS & Android

Whereday ("the App") is built with privacy as a core principle. This policy explains how the App handles your information.

## Data Collection and Storage

By default, all location data — visits, routes, saved places, and photo associations — is stored exclusively on your device. There is no Whereday account, and no Whereday server keeps a copy of your location history.

A small set of optional or auxiliary features do communicate over the network. Each is listed under Network Requests below, together with exactly what is sent and where.

## Location Data

The App uses your device's location services to:

- Automatically detect places you visit
- Record detailed routes when tracking is enabled
- Feed optional surfaces such as the Apple Watch app, widgets, and Live Activities on iPhone, and home-screen widgets on Android

Your location history stays on your device unless you deliberately enable a feature that sends it elsewhere. The only such feature is optional webhook delivery (see Network Requests), which posts your data to a destination you configure yourself. Showing the map and looking up place names send the coordinates involved to map and geocoding services, as described below; those services do not receive your saved history.

## Network Requests

The App may make network requests in the following circumstances:

- Apple system services (optional): if readable place names are enabled, coordinates are sent to Apple's geocoding service. Place search, nearby-business suggestions, road-matched route lines, and lock-screen map snapshots also use Apple Maps services. These requests are handled by Apple and governed by Apple's privacy policy.
- Earlier-version onboarding analytics: beginning with version 1.8, production builds disable onboarding/purchase analytics and discard queued unsent events. Earlier versions enable first-party analytics by default: onboarding steps, permission/tracking choices, paywall interactions, product IDs, purchase/restore outcomes, coarse technical errors, app version/build, and platform. Our Cloudflare-hosted service stores these with random installation/event IDs and receipt time. These are installation-linked records, not anonymous aggregate counts. Reports do not include coordinates, visits, routes, addresses, place names, photos, export contents, or text you enter. We use them to understand onboarding/purchases and diagnose failures, not cross-company targeted advertising or data-broker sharing.
- Webhook delivery (off by default, Pro): if you configure a webhook, the App posts your location points and visits, in your chosen format, directly to the endpoint you specify. Credentials are stored in the iOS Keychain; Whereday never sees your endpoint or your data.
- Scheduled export wake-ups (only if enabled): our Cloudflare-hosted service stores a separate installation identifier, Apple push token, platform/bundle identifier, time zone, schedule settings, and service timing information so it can send scheduled wake-ups. It receives no location records, export contents, folder names, or file names. The scheduling identifier is stored in Keychain and may survive uninstalling the app.
- Optional myself.md purchase claims in earlier versions: if you choose to verify an eligible lifetime purchase, Whereday sends signed App Store purchase proof to myself.md, which verifies eligibility with Apple and retains the purchase source and transaction reference. Redeeming while signed in associates the purchase with your myself.md account to grant access and prevent duplicate claims. The proof request sends neither location history nor payment-card details. This feature is disabled in version 1.8. The separate site describes account handling in its [Privacy Policy](https://myself.md/privacy).
- App Store: purchases are handled entirely by Apple's App Store. Whereday does not process or store payment information.

### On Android

The Android app keeps your journal on your device in the same way. Android cloud backup and device-to-device transfer are turned off for its data. It makes these network requests:

- Street map tiles (on by default): the home map loads street tiles from OpenFreeMap (tiles.openfreemap.org). Each request names the map area being shown, which can reveal the approximate or precise area you are looking at, along with your IP address. No journal records are sent. Turn this off in Map → Layers → Street map.
- Place names and search (on by default): when you search for a place or look up the address of a pin, the text you typed or that one coordinate is sent to your device's geocoding service, which is Google's on most Android devices. If you run a country analysis, the coordinates in the selected history are sent the same way. Turn this off in Settings → Network geocoding.
- Google Play purchases: subscriptions and Lifetime are bought through Google Play Billing. Whereday never receives your payment details.
- Purchase verification: to unlock paid features, the app sends your Google Play purchase token, the product ID, a public key created on your device for this installation, a signature, and a Google Play Integrity token to our purchase verifier, a Cloudflare-hosted service. The verifier checks the purchase with Google Play and the integrity token with Google, then returns a short-lived signed pass the app keeps on the device. It stores one-time challenges for five minutes, and SHA-256 hashes of the purchase token and device key with the date first seen, to limit each purchase to five devices. Those hashes have no automatic expiry. It stores no location data, account, name, or email.
- Device location: the app reads your position from Android's location services (Google Play services' fused location provider). Whereday itself does not send that position anywhere except as listed here.
- Webhook delivery (off by default, paid): works as on iPhone. Location points and visits go only to the endpoint you configure.

Photos you link to places are read on your device and never uploaded. The Android app contains no advertising or third-party analytics SDKs and does not use the advertising ID.

## Data Export

You can export visits, route points, and outings at any time as JSON, CSV, Markdown, GeoJSON, GPX, KML, OwnTracks, or Overland. Exported files are generated locally on your device. What you do with exported data is your responsibility.

## Analytics and Tracking

Whereday contains no third-party analytics SDKs, no advertising frameworks, and no trackers from other companies. Production version 1.8 disables optional onboarding/purchase telemetry. Earlier versions collect the installation-linked events described above. Scheduling and optional earlier-version purchase claims use the technical records described in this policy. This website (not the App) additionally uses Cloudflare Web Analytics, which does not use cookies and does not track you across other sites. After the existing analytics exclusion check allows measurement, the website also counts page loads and App Store link activations by public page, link placement, UTC day, and a coarse referrer category (search, internal, other, or direct). These counters use no cookies, browser storage, visitor identifiers, raw referrer URLs, query strings, or location files. They measure activations, not unique visitors, installs, or purchases. This collection is disabled when the browser sends Global Privacy Control or Do Not Track. Cloudflare hosts the aggregate counters. On selected acquisition pages, App Store download links also include a public campaign label and developer provider token so Apple can report campaign downloads and purchases subject to its reporting rules. These labels contain no personal information or location records.

## Third-Party Services

- Apple: geocoding, Maps search and directions, map snapshots, push notifications, and App Store purchases are Apple system services governed by Apple's privacy policy.
- Google (Android): Google Play Billing, Google Play Integrity, Google Play services location, and the device geocoding service are Google services governed by Google's privacy policy.
- OpenFreeMap (Android): serves the street map tiles shown on the Android home map.
- Cloudflare: hosts Whereday's own analytics ingest, export-scheduling, and Android purchase-verification endpoints (as described above), plus this website. Cloudflare processes data as our infrastructure provider.
- Destinations you choose: webhook delivery and file exports go only to endpoints and folders you configure yourself.

## Data Deletion

### Delete your Whereday data

- Your journal (iPhone and Android): open Whereday, go to Settings, and use the option to delete or clear your journal data. On Android this is Settings → Clear all journal data, which also clears linked-photo references, the webhook queue, and app activity. Uninstalling the app also removes the journal from the device.
- Records on our servers: email [cody.bontecou@gmail.com](mailto:cody.bontecou@gmail.com) with the subject "Whereday data deletion" to ask us to delete analytics, scheduling, or Android purchase-verification records. You can do this without the app installed. Say which platform you use. For Android purchase records, include your Google Play order number (it starts with GPA.) so we can find the matching hashes.
- Purchases: subscriptions are managed and canceled in the App Store or Google Play. Deleting data does not cancel a subscription.

You can delete locally stored journal data in Settings. Deleting local records or uninstalling the app does not itself delete server records, initiate myself.md account deletion, or remove copies you exported/backed up. Keychain-backed scheduling identity may remain after uninstalling.

### Retention of technical records

Earlier-version analytics events have no configured automatic expiry in our analytics service. Version 1.8 stops sending these events and discards unsent queued events, but does not automatically erase events already received.

After the scheduling service successfully receives a disable request, it deletes the active schedule. Device registrations have no age-based expiry; they are removed when Apple reports an invalid push token or a device-deletion request is processed. Turning scheduling off does not itself remove every device registration.

Purchase-claim links are valid for 15 minutes. Expiry prevents use but does not immediately erase every stored claim row. A daily cleanup removes expired directory routing rows; expired purchase-object claim rows are removed when another claim is created for that purchase. An unused expired row has no guaranteed storage deadline. Redeemed grants have no automatic expiry. After myself.md account deletion completes successfully, the service removes the account link but retains purchase references without timed expiry to prevent duplicate claims. Use myself.md's account-deletion controls and check that cleanup completes.

Cloudflare processes network requests to provide and secure these services. The myself.md service retains operational error logs; Cloudflare's Workers Logs store retains logs for up to seven days. This is not a maximum for every provider security record or backup. Database recovery history may retain earlier copies after live records change or are deleted. Live-record deletion does not immediately erase all recovery copies.

For privacy or deletion questions about retained analytics/scheduling records, contact [cody.bontecou@gmail.com](mailto:cody.bontecou@gmail.com). We may need information to identify the relevant installation records and verify a request. Nothing in this policy limits deletion or other rights provided by applicable law.

## Children's Privacy

Whereday does not knowingly collect information from children under 13. The version-dependent data flows described in this policy also apply to use of the App. Location records are not included in that telemetry.

## Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted on this page with an updated effective date.

## Contact

If you have questions about this Privacy Policy, please contact us at [cody.bontecou@gmail.com](mailto:cody.bontecou@gmail.com).

Source: https://whereday.com/privacy
