# Local GPX-to-GeoJSON conversion

Previous turn: progress, delivered reusable Obsidian example. Current turn: progress, extending the existing viewer and tutorial for a researched task.

Production release https://9ebdea41.spotted-8ez.pages.dev:

- https://whereday.com/tools/gpx-viewer/#geojson-output
- https://whereday.com/blog/check-gpx-file/#geojson
- /samples/synthetic-gpx-converted.geojson

Existing 30 estimated US monthly searches/KD0 research reused. No new keyword spend, duplicate page or ranking/traffic claim. New action gives people already handling location files an output they can inspect and take to a compatible destination; native import and complete archive recovery are not promised.

## Conversion contract

FeatureCollection with 2D longitude-first geometry. Each continuous group/part becomes LineString when it has at least two usable points, otherwise Point. Independent waypoints remain independent. Null invalid-coordinate slots and longitude jumps over180 split parts, without invented boundary coordinates or time interpolation. No point resampling in downloads; 50,000-point case checked. Duplicate points and order within each viewer group retained; viewer groups process track segments, routes, waypoints. No complete original track hierarchy.

Custom properties: kind/group/part plus aligned arrays point_numbers, times_utc, original_times, elevations_m and point_names. Point features have one-element arrays. Missing/unusable normalized values null; original timestamp text retained when present. Point names remain JSON data. Geometry has no third ordinate with an assumed vertical datum. Metadata, track/route names, styling and extensions omitted. No lossless-conversion or consumer-app interoperability claim. RFC7946 and GPX1.1 primary specifications rechecked Oct30; public copy links them.

## Checks

43 JS tests (nine converter cases) and16 Python pass. Tests cover explicit expected two segments/five points, asymmetric coordinate order/negative values, timezone offsets, invalid-coordinate gaps, dateline crossings both directions, multiple tracks/routes/waypoints, duplicates, optional values, text escaping/omission, empty output, 50k non-sampled points and published-fixture parity. Independent Python standard-library XML/JSON comparison verifies all sample coordinates, UTC times, elevations and segment boundaries. Repeated build byte-identical;39HTML documents including404,38public pages,1412local targets, all reachable within two links.

In-app browser checked actual demo GeoJSON download bytes against fixture, CSV download bytes against unchanged exporter, actual file chooser for invalid-coordinate sample, single-feature handling, and Clear. Clear empties all result nodes including GeoJSON summary, erases input, disables both downloads, drops both models and revokes the object URL. Production browser download also equals fixture bytes. Screenshots at390x844 and1280x900; an initial screenshot captured a transitional viewport size, replaced after settled AX state and actual image dimensions verified. Worked tutorial also checked at390px with no overflow and expected example link. Viewport reset; live tool retained. No physical iPhone test claimed.

Response privacy remains connect-src none/no-referrer/no analytics; no upload/storage/basemap path introduced. Versioned viewer-script URL avoids stale cached UI code. Production evidence records five updated HTML/Markdown surfaces, exact two module hashes, GeoJSON MIME/attachment header/hash and privacy. Full local HTTP checker passes38pairs. First full production pass hit a connection reset; retry passed all38HTML/Markdown pairs and the complete public response/product contract.

Second review found no material issue. Audit38c9e397-f8c4-47b6-9d58-af83a5cdc9ba completed58/58URLs with no issues reported. Existing report updated in place; release details moved into a disclosure to keep the roadmap readable.
