- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- KML viewer

iPhone location journals

# Open a KML or KMZ file in your browser

See what is inside a KML or KMZ file without installing Google Earth or uploading it, including a plain list of what the viewer does not draw.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 23, 2026

## Choose a KML or KMZ file. It is opened on this device.

See the placemarks, lines, polygons and timed tracks inside, and a plain list of what this viewer does not draw. No account, upload or analytics. The file is read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and never leaves the page.

JavaScript is needed to open files in your browser. The explanations and sample downloads below work without it.

Choose a KML or KMZ file or drop it here · one file, up to 10 MiB and 50,000 coordinates

Try a synthetic exampleTry the synthetic KMZTry the stored-method KMZClear

Choose a file, or try one of the synthetic examples first.

### File

### Folders and placemarks

Names and descriptions are shown as plain text. Markup inside a description is never rendered.

### Placemark table

Coordinates are longitude,latitude[,altitude], as in KML. Times are UTC and come only from gx:Track.

| Name | Type | Coordinates | First → last time | Bounding box (west,south to east,north)

### Skipped

Elements found in the file that this viewer does not draw.

### Other files inside the KMZ

### Next steps

[Convert this to GPX](https://whereday.com/tools/kml-to-gpx/) · [Check a GPX file](https://whereday.com/tools/gpx-viewer/)

On this page
- [KML vs KMZ](https://whereday.com/tools/kml-viewer/_t_kml#what-is-kml)
- [Other ways to open one](https://whereday.com/tools/kml-viewer/_t_kml#how-to-open-kml)
- [KMZ on iPhone](https://whereday.com/tools/kml-viewer/_t_kml#open-kmz-iphone)
- [What it shows](https://whereday.com/tools/kml-viewer/_t_kml#what-it-shows)
- [Synthetic examples](https://whereday.com/tools/kml-viewer/_t_kml#example)
- [Limits](https://whereday.com/tools/kml-viewer/_t_kml#limits)
- [Privacy](https://whereday.com/tools/kml-viewer/_t_kml#privacy)
- [Make your own](https://whereday.com/tools/kml-viewer/_t_kml#make-your-own)
- [Sources](https://whereday.com/tools/kml-viewer/_t_kml#sources)

## What is a KML file, and what is a KMZ?

KML (Keyhole Markup Language) is an XML format for places and shapes: placemarks, lines, polygons and folders that organise them. It is an [OGC standard](https://www.ogc.org/standard/kml/), and Google’s [KML reference](https://developers.google.com/kml/documentation/kmlreference) documents Google Earth’s extensions, such as `gx:Track`, which pairs each position with a time. Coordinates are written `longitude,latitude[,altitude]` in WGS84, so longitude comes first.

A KMZ is a KML file inside a ZIP archive, usually together with images or other files it refers to. Per Google’s [KMZ guide](https://developers.google.com/kml/documentation/kmzarchives), the main file is `doc.kml`; if there is none, the first `.kml` at the top level is used. This viewer follows the same rule. For the differences between KML, GPX, GeoJSON and CSV, see [which format to choose](https://whereday.com/blog/gpx-kml-geojson-location-history/).

## How to open a KML or KMZ file

- This page. Choose the file above. Nothing is uploaded, and it shows what is inside, including what it skips. It draws shapes only, not a map.
- Google Earth. Google Earth on the web, on the desktop and in its mobile apps can import KML and KMZ files and draws them with their styles. See [Google Earth Help](https://support.google.com/earth/) for the current import steps.
- Google My Maps. It can import some files into a map; its supported file types are listed in [Google’s import help](https://support.google.com/mymaps/answer/3024836). For a GPX file, see [how to open a GPX file in Google Maps](https://whereday.com/blog/gpx-to-google-maps/).

We have not compared the results with Google Earth, and this viewer does not claim to match it.

## Opening a KMZ on iPhone

We have not tested this page in Safari on an iPhone, so we give no iPhone steps here. The page uses only your browser’s file picker and its built-in unzip support ([DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream)); if your browser lacks it, the page says so. A computer is the tested route: open the KMZ in desktop Safari or Chrome.

## What it shows, and what it skips

| KML element | In this viewer
| `Point` | Counted, listed and drawn as a dot
| `LineString`, stand-alone `LinearRing` | Counted, listed and drawn as a line or outline
| `Polygon` (outer and inner rings) | Counted, listed and outlined; holes are dashed. Not filled
| `MultiGeometry` | Counted; each part inside is read and drawn
| `gx:Track`, `gx:MultiTrack` | Counted and drawn as lines; `<when>` times shown in UTC
| `Folder`, `Document` | Shown as a tree with names and descriptions, as text
| `NetworkLink`, `GroundOverlay`, `PhotoOverlay`, `ScreenOverlay`, `Model`, `Region` | Not drawn or followed; counted in the Skipped panel
| `Style`, `StyleMap`, `Icon`, `TimeStamp`, `TimeSpan`, `ExtendedData`, `gx:Tour` | Ignored; counted in the Skipped panel

For a KMZ, other files inside the archive are listed by name and size and are not drawn.

## Synthetic examples

These files are invented, near 0° N 10° E, and are not anyone’s recording. [synthetic-kml-viewer.kml](https://whereday.com/samples/synthetic-kml-viewer.kml) has 1 folder, 2 Points, 1 LineString, 1 Polygon with 1 inner ring and 1 `gx:Track` of 5 timed positions. [synthetic-kml-viewer.kmz](https://whereday.com/samples/synthetic-kml-viewer.kmz) (deflate) and [synthetic-kml-viewer-stored.kmz](https://whereday.com/samples/synthetic-kml-viewer-stored.kmz) (stored) hold the same `doc.kml` plus a one-pixel image, which is listed but not drawn. The buttons above load them.

## Limits

- One file, up to 10 MiB (and 10 MiB once unzipped) and 50,000 coordinates, in line with the other tools here.
- KMZ: stored and deflate entries only. Encrypted, ZIP64, split and other-compression archives give an error. Only one KML is opened, `doc.kml` or else the first top-level `.kml`.
- No styles or icons are rendered, and no overlays, 3D models or tours.
- No network links are followed; this page makes no network requests.
- No reprojection. KML is WGS84 longitude, latitude, and values outside ±180 and ±90 are skipped. The plot is a simple local projection with no map, and does not handle shapes that cross the 180° meridian.
- Altitude is shown as written; altitude modes are not interpreted.
- Files with a DOCTYPE or entity declaration are refused.

## Privacy: your file stays in the browser

Unzipping, parsing and plotting all happen in this page. Its content security policy blocks network connections, the page loads no analytics and no map tiles, and descriptions are inserted as text, not HTML. The web server sees an ordinary request for the page and its scripts, not your file. Following a link leaves this page. Clear removes the result from the page and never changes your original file.

## Make your own KML of where you’ve been

Whereday records your visits and routes on iPhone and exports KML, GPX, GeoJSON, CSV and more. Convert a file with the [KML ⇄ GPX converter](https://whereday.com/tools/kml-to-gpx/), check a GPX in the [GPX viewer](https://whereday.com/tools/gpx-viewer/), read [what a GPX file is](https://whereday.com/blog/what-is-a-gpx-file/), or browse all the [location file tools](https://whereday.com/tools/).

## Make your own KML of where you’ve been.

Whereday keeps your location history on your iPhone and exports it in the format you need.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Sources

- [OGC KML 2.2 standard](https://www.ogc.org/standard/kml/)
- [Google KML reference](https://developers.google.com/kml/documentation/kmlreference), including [gx:Track](https://developers.google.com/kml/documentation/kmlreference#gxtrack)
- [Google: KMZ files](https://developers.google.com/kml/documentation/kmzarchives) (doc.kml and the first root .kml)
- [MDN: DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream) (deflate-raw)
- [PKWARE ZIP APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT) (local and central directory headers)
- [Google Earth Help](https://support.google.com/earth/) and [My Maps Help](https://support.google.com/mymaps/)

## Read next

- [Convert this to GPX](https://whereday.com/tools/kml-to-gpx/)

Turn KML into GPX locally, keeping gx:Track times.

- [Check a GPX file](https://whereday.com/tools/gpx-viewer/)

View a GPX route, points and timestamps without uploading it.

- [Choose between GPX, KML, GeoJSON and CSV](https://whereday.com/blog/gpx-kml-geojson-location-history/)

See what each format keeps, with the same invented trip.

Source: https://whereday.com/tools/kml-viewer/_t_kml
