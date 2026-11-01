# Format-choice guide with checked examples

October30,2026. Previous goal turn: progress, delivered GPX viewer/tutorial. This iteration delivers the roadmap’s format-choice article rather than waiting for recent performance data.

## Delivered

https://whereday.com/blog/gpx-kml-geojson-location-history/
Deployment: https://6c1ef163.spotted-8ez.pages.dev. Reader links from guide hub, inspection tutorial, route guide, export reference, tools hub and GPX viewer. Published/reviewed October30, sitemap and structured article inventory updated by the normal build. Existing keyword research GPXvsKML110USmonthly/KD0 reused; no repurchase or traffic forecast.

## Four controlled examples

Five invented latitude/longitude pairs starting at0/10, with two groups[3,2] and UTC times2000-01-01 12:00–12:14. Not personal history or new native-app output. No measured elevations.

-Source GPX has1track/2segments/5timed points.
-KML has2gxTracks insidegxMultiTrack, interpolation0 andclampToGround. Third-coordinate zeros are ignored placeholders, not measured elevation.
-GeoJSON has2LineString features with2D longitude-first positions, explicit synthetic markers and custom times arrays. The article explains the reader must understand that convention.
-CSV uses the actual browser tool’s toCSV function and contains5data rows, group/original point numbering, normalized and original times. It is not a native journal restore format.

[Manifest](../seo-format-example-manifest-2026-10-30.json) includes byte counts and hashes. `build_format_samples.mjs` derives outputs from source GPX; --check verifies deterministic bytes. Independent Python checker compares all four representations with separately fixed expected coordinates, dates and groups, including coordinate-order detection. It runs in every site build with standard-library dependencies only. The JS suite compares the saved CSV with browser exporter output. CSV CRLF bytes protected in .gitattributes.

## Verification

20JavaScript and16Python tests pass. Build:37HTML documents including404,36published pages,1327local targets; all36reachable within2links. Repeated full build is byte-identical. Full local HTTP representation/product checks pass36HTML/Markdown pairs. Production evidence and final HTTP result are recorded in [live checks](../seo-format-guide-live-2026-10-30.json).

Live checks cover the article HTML/Markdown, canonical/indexability, four byte-identical downloads, three attachment headers, six incoming links, sitemap and continued GPX-tool privacy policy.

In-app browser initially hit an unavailable-access error; documented tab recovery and one runtime reset restored it. No alternate browser was used. Guide inspected locally and in production at390px and1280px. At390px pagewidth equals viewportwidth; keyboard arrows move the comparison table horizontally. Screenshot evidence: [desktop](format-guide-desktop-2026-10-30.jpg), [phone-sized](format-guide-mobile-2026-10-30.jpg). Viewport override reset; production guide left open. This is browser-responsive verification, not physical phone testing.

Second SEO technical review found no material issue. Primary GPX1.1, Google KML track reference and GeoJSON RFC7946 checked; article links them. Content distinguishes verified sample fields from consumer app support and native-app export choices. No GoogleEarth/GISapp interoperability, full XSD validation, lossless conversion, ranking or Googleindexing claim for the new guide.

## Final production results

Full production HTTP checker passes36HTML/Markdown pairs and its complete product/representation contract. New OpenSEO audit80156d36-4204-4b8e-a1fd-8db27c8724da completed20:40:55UTC:53URLs,one existing thin-content warning for107-word tools hub. No issue reported for the new guide. Keep the useful one-tool navigation hub rather than padding/noindexing solely for the heuristic. [Audit evidence](../seo-format-guide-audit-2026-10-30.json).
