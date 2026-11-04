# GPX viewer and worked tutorial, October 30, 2026

## Delivered

- https://whereday.com/tools/gpx-viewer/ — local GPX viewer/file inspector with CSV point-table download.
- https://whereday.com/blog/check-gpx-file/ — worked five-point tutorial, warning explanations and two deliberately limited synthetic fixtures.
- https://whereday.com/tools/ — tools navigation hub, linked from home. Guide hub and existing GPX article link the tutorial/tool.
- Production deployment: https://2c89564a.spotted-8ez.pages.dev.

## Scope

Supports GPX1.0/1.1 tracks, segments, routes and waypoints. Preserves group boundaries; invalid coordinates break the plot and are omitted/count-reported. Missing/invalid timestamps remain usable-coordinate records with blank normalized CSV time. Diagnostics include consecutive duplicate positions, within-group gaps over10minutes, reversed time and invalid elevation. Input capped10MiB/50,000points; plot at2,000points; preview25rows. The CSV retains group/original point indices and original timestamp text, but not all metadata/extensions. It is not a lossless conversion, full schema validation, native importer or backup.

Point-name/original-time CSV cells guard against spreadsheet formulas; displayed filenames and values use textContent. DTD/entity declarations and foreign formats are rejected. No original is changed. The synthetic demo is generated directly from the unchanged five-point native-export sample.

## Verification

-19 JavaScript tests pass (11 parser/CSV/plot cases plus8 Worker/acquisition cases), including namespaced XML, malformed/entity input, limits, invalid coordinates, impossible dates, timezones, segment boundaries, formula text and response privacy for aliases.
-16 Python marketing tests pass. Full build:36HTML documents including404,35published pages,1,277local targets; all35reachable within2links.
-Full local and production HTTP representation checks pass for35HTML/Markdown pairs and API/error/Accept/method/asset contracts.
-[Production checks](../seo-gpx-tool-live-2026-10-30.json): new page pairs, policy headers, no injected analytics, JavaScript media types, six asset/fixture hashes and sitemap entries. A generic urllib asset request initially returned403; the established identification header succeeded. Browser demo also succeeded; no cause for the first response was inferred.
-In-app browser: synthetic demo shows5points/1track/2segments and correct UTC span; local file chooser opens the missing-time fixture with2valid points/1missing-time diagnostic. Downloaded CSV is byte-identical to expected five-point output. Clear removes output/disables download.
-Desktop1280×900 and phone-sized390×844 in-app views inspected. At390px the document scroll width equals viewport width. This is responsive browser verification, not physical iPhone testing. No browser console warnings/errors in checked views.
-Production screenshots: [desktop](gpx-viewer-desktop-2026-10-30.jpg), [phone-sized](gpx-viewer-mobile-2026-10-30.jpg). Production demo rechecked; viewport override reset; live viewer left open as deliverable.

## Privacy boundary

The tool omits site analytics scripts and denies network connections with response CSP connect-src none. Only self-origin scripts load; no external tiles/geocoding/storage/upload code. Worker covers canonical, slashless and direct-index aliases, adds no-referrer, nosniff and no-store/no-transform. Public scripts/page requests still reach the server; local file contents, coordinates, names and results are not transmitted. Other site pages retain existing gated analytics. Worker guidance/types were retrieved before the response-header change.

## Operations

Source HTML lives in marketing/content; registrations are in build_seo_pages.py. Frontend modules and CSS have no production package dependency. Test-only @xmldom/xmldom0.9.12 is pinned with lockfile; npm ci --prefix marketing then npm test --prefix marketing reproduces JS checks. No native source changes or iOS test claims. No observed ranking/traffic uplift or Google indexing claim for these new URLs.

## Review and final release

Second SEO review identified one issue: Clear hid the output but left text in hidden DOM nodes. Reset now erases filename, dates, counts, notes and diagnostics as well as plot/rows. Final release: https://5bfe4fdf.spotted-8ez.pages.dev. In-app production recheck confirms all eight result nodes are empty after Clear, download is disabled, and the demo opens correctly again. The final module hash and privacy header recheck are appended to the production evidence. Initial screenshot appearance is unchanged by this state-retention fix.

OpenSEO crawl c264cf26-3cd9-4e78-adfa-05603951eb11 completed20:26:14UTC with48 URLs and one warning: tools hub has80words. This is a functional navigation hub with one real tool; retain it without padding or noindex. No other issue type reported. The crawl’s HTML precedes the final JavaScript-only Clear fix; browser/hash checks cover that fix. See docs/seo-gpx-tool-audit-2026-10-30.json.
