# Timeline JSON to GPX

October 30, 2026. Added conversion to the existing local inspector and a worked
section to its existing format tutorial. Final production release:
https://a459cdde.spotted-8ez.pages.dev.

- Tool: https://whereday.com/tools/google-timeline-inspector/
- Tutorial: https://whereday.com/blog/google-timeline-json-formats/#convert-gpx

## Conversion rules

`timeline-core.mjs` retains source stream and component position for accepted
points. Invalid raw entries and nested components therefore leave a break;
filtering them out cannot connect their neighbors. Existing inspection counts
remain the same. Invalid ISO calendar years outside 1–9999 are now rejected.

`timeline-gpx.mjs` puts raw locations and explicitly timed paths in GPX tracks.
Separate source paths, backwards time, UTC day changes, gaps over ten minutes,
and longitude jumps over 180 degrees start another segment. Normalizing exact
longitude 180 to −180 also cannot introduce a crossing line. Source point order
within each path and duplicates remain. One-point segments are allowed.

Visits become waypoints with arrival time and departure in a description.
Activity endpoints are separate waypoints, even when the record also has a
path. No line is inferred between endpoints. All accepted coordinate/time
records are included independently of the preview and plot caps. The GPX omits
names, addresses, activity labels, elevation and other optional data. Coordinates
round to at most 15 decimal places, with decimal notation rather than exponents;
UTC times normalize to millisecond precision. It cannot rebuild missing travel,
merge duplicates, reproduce native import or preserve a complete Timeline archive.

The converter's ten-minute/day breaks are display choices, not evidence of a
trip ending. The generated GPX can exceed source JSON size; the separate viewer
retains its 10 MiB / 50,000-point limit. No input limit was relaxed or data silently
trimmed. Input remains 10 MiB / 50,000 source or expanded components.

## Automated and independent checks

`npm --prefix marketing test`: 51 passed, including eight new converter cases:
three worked fixtures; invalid raw/path gaps; separate paths/endpoints; time/day/
dateline boundaries; original order/duplicates/offsets/visit end; small decimals/
normalized boundary/calendar years; empty data; all 50,000 accepted points.

`python3 -m unittest discover -s marketing/scripts -p 'test_*.py'`: 16 passed.
Build: 39 HTML documents, 38 public pages, 1740 local targets, maximum reader depth 2.
Final repeat build byte-identical across 203 public files; git diff --check passes.
No native implementation changed in this iteration, so no new iOS run was needed.

Fetched the official schema from https://www.topografix.com/GPX/1/1/gpx.xsd
into `/private/tmp/whereday-gpx11.xsd`. `xmllint --schema` validates all three
final GPX fixtures plus a tiny-coordinate/longitude-boundary example. Schema
reference: https://www.topografix.com/GPX/1/1/.

Independent ElementTree parsing verifies every expected output coordinate and
time, plus record/segment counts. Browser downloads match fixtures byte for
byte. Results: `docs/seo-timeline-gpx-files-2026-10-30.json`.

| Example | Track points | Segments | Visit waypoints | Endpoint waypoints |
| --- | ---: | ---: | ---: | ---: |
| Raw |2|1|0|0|
| Semantic |1|1|1|2|
| Device |4|4|2|0|

The device fixture intentionally repeats records and has 30-minute gaps. Its
four one-point segments are expected. Semantic untimed waypoints remain omitted.

## In-app browser

Used the actual example selector and download button for all three families.
Opened each downloaded GPX through the GPX viewer's real file chooser: raw 2
valid points / 1 segment / 0 waypoints; semantic 4 / 1 / 3; device 6 / 4 / 2. The viewer includes
waypoints in its total valid-point count, while the converter reports them
separately. Its time span does not include departure held in waypoint text.

The real JSON file chooser accepts the raw fixture, rejects unrelated JSON,
and handles recognized empty locations. Clear/unsupported/empty states leave
download disabled and clear previous conversion data. Fresh production load
uses versioned UI and parser dependencies; live semantic download also matches.

Mobile 390px: page width 390px, download control fits. Tutorial result table is
inside a named focusable scroll region, scrolls with ArrowRight, and does not
widen the page. Desktop 1280px: table fits. Viewport override reset afterward.
Screenshots: `timeline-gpx-mobile-2026-10-30.jpg`,
`timeline-gpx-guide-mobile-2026-10-30.jpg`,
`timeline-gpx-desktop-2026-10-30.jpg`, `timeline-gpx-live-2026-10-30.jpg`.
The first desktop proof was initially named mobile; dimensions revealed the
viewport had applied to a second tab. It was renamed, that tab was closed, and
the actual 390px layout was verified and captured separately.

## Production and scope

All 38 HTML/Markdown response pairs pass, including cache separation, 404,
API/product contract, methods and asset integrity. Three current pages, three
module hashes and three GPX download hashes/MIME/attachment headers pass.
The local tool still omits analytics and has connection-blocking CSP. Raw checks:
`docs/seo-timeline-gpx-live-2026-10-30.json`. Local preview stopped at completion.

Second review found no material correctness or disclosure issue. Prior research
estimated 10 US monthly searches for Timeline-to-GPX, difficulty unknown; reused
without new keyword purchases. Heatmaps remain a later candidate with distinct
large-file/privacy scope; a new-phone restore needs that exact device workflow.
No traffic, ranking, indexing gain or external-app interoperability is claimed.
