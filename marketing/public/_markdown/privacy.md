§ Legal · Document 01 

# Privacy Policy

Effective

August 22, 2026

Updated

October 26, 2026

Scope

Whereday — iOS & watchOS

Whereday ("the App") is built with privacy as a core principle. This policy explains how the App handles your information.

## Data Collection and Storage

By default, all location data — visits, routes, saved places, and photo associations — is stored exclusively on your device. There is no Whereday account, and no Whereday server keeps a copy of your location history.

A small set of optional or auxiliary features do communicate over the network. Each is listed under Network Requests below, together with exactly what is sent and where.

## Location Data

The App uses your device's location services to:

- Automatically detect places you visit 
- Record detailed routes when tracking is enabled 
- Feed optional surfaces such as the Apple Watch app, widgets, and Live Activities  

Your location history stays on your device unless you deliberately enable a feature that sends it elsewhere. The only such feature is optional webhook delivery (see Network Requests), which posts your data to a destination you configure yourself.

## Network Requests

The App may make network requests in the following circumstances:

- Apple system services (optional): if readable place names are enabled, coordinates are sent to Apple's geocoding service. Place search, nearby-business suggestions, road-matched route lines, and lock-screen map snapshots also use Apple Maps services. These requests are handled by Apple and governed by Apple's privacy policy. 
- First-party onboarding analytics (on by default): the App reports a limited allowlist of onboarding and purchase events — such as which onboarding step you viewed or whether a purchase succeeded — to Whereday's own analytics endpoint hosted on Cloudflare Workers. Each report includes a random install identifier, the app version, and coarse status such as your permission choice. Reports never include coordinates, visits, routes, addresses, place names, photos, or anything you type. Deleting the App removes the local install identifier. 
- Webhook delivery (off by default, Pro): if you configure a webhook, the App posts your location points and visits, in your chosen format, directly to the endpoint you specify. Credentials are stored in the iOS Keychain; Whereday never sees your endpoint or your data. 
- Scheduled export wake-ups (only if enabled, Pro): if you enable automatic exports, the App registers a random install identifier, an Apple push token, your time zone, and your schedule time with Whereday's scheduling endpoint so the App can be woken at the right moment. This service stores timing metadata only — never location records, export contents, folder names, or file names. 
- App Store: purchases are handled entirely by Apple's App Store. Whereday does not process or store payment information.  

## Data Export

You can export visits, route points, and outings at any time as JSON, CSV, Markdown, GeoJSON, GPX, KML, OwnTracks, or Overland. Exported files are generated locally on your device. What you do with exported data is your responsibility.

## Analytics and Tracking

Whereday contains no third-party analytics SDKs, no advertising frameworks, and no trackers from other companies. The only telemetry is the first-party onboarding analytics described under Network Requests: a short allowlist of funnel events with a random install identifier and no location content. This website (not the App) additionally uses Cloudflare Web Analytics, which does not use cookies and does not track you across other sites.

## Third-Party Services

- Apple: geocoding, Maps search and directions, map snapshots, push notifications, and App Store purchases are Apple system services governed by Apple's privacy policy. 
- Cloudflare: hosts Whereday's own analytics ingest and export-scheduling endpoints (as described above), plus this website. Cloudflare processes data as our infrastructure provider. 
- Destinations you choose: webhook delivery and file exports go only to endpoints and folders you configure yourself.  

## Data Deletion

You can delete all locally stored data at any time from within the App's Settings. Deleting the App also removes all location history and the local analytics install identifier from that device, unless you exported or backed data up first. Already-delivered analytics events cannot be recalled, but they contain no location data and no identifiers beyond the now-deleted random install ID.

## Children's Privacy

Whereday does not knowingly collect information from children under 13. The data flows described in this policy, including first-party telemetry enabled by default, also apply to use of the App. Location records are not included in that telemetry.

## Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted on this page with an updated effective date.

## Contact

If you have questions about this Privacy Policy, please contact us at [cody.bontecou@gmail.com](mailto:cody.bontecou@gmail.com).

Source: https://whereday.com/privacy
