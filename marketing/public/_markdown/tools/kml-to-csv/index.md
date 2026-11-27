- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- KML to CSV

iPhone location journals

# Convert KML or KMZ to CSV, and CSV to KML, in your browser

Drop a KML or KMZ to get a CSV for Excel or Google Sheets, or drop a CSV to get a KML. The file stays in your browser.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 26, 2026

## Drop a KML, KMZ or CSV file. It stays on this device.

Drop a KML or KMZ to get a CSV for Excel or Google Sheets, or drop a CSV to get a KML. The file stays in your browser. The type is detected from the file’s content, not its name. You see a report of what was read, written and skipped before you download anything. No account, no upload and no analytics. Files are read with your browser’s [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API), and a KMZ is unzipped with the browser’s built-in [DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream).

JavaScript is needed to read and convert files in your browser. The explanations and the sample downloads below work without it.

Choose a KML, KMZ or CSV file or drop it here · one file, up to 10 MiB and 50,000 coordinates or rows

Try the synthetic sampleTry the synthetic KMZTry the stored-method KMZTry a synthetic CSV (CSV to KML)Clear

Add a file, or try the synthetic sample first. It is invented and contains no personal data.

### KML or KMZ to CSV

1. Rows for lines, tracks and polygons One row per vertexEvery vertex of a LineString, `gx:Track` or polygon outer boundary gets a row, with its `placemark` and `vertex` numbers. A Point is one row either way. One row per placemarkOne row for each geometry, with its first vertex and a `vertex_count`. A MultiGeometry still gets one row per part, numbered in `part`.   2. Text and spreadsheet options Keep HTML in descriptionsOff by default: descriptions become plain text, with line breaks kept. Add a UTF-8 byte-order mark (BOM)Three bytes at the start of the file that mark it as UTF-8, which some spreadsheet programs use. Untick it if the first header shows stray characters. See [open the CSV in Excel or Sheets](https://whereday.com/tools/kml-to-csv/#excel).

### Report

A local plot with no map tiles, so nothing is fetched. North is up. Check that it has the shape you expect: swapped latitude and longitude would show it mirrored or in the wrong place.

#### Skipped, and why

#### First rows of the CSV

Download CSV

### CSV to KML

1. Columns

Latitude and longitude are found by header name (`lat`, `latitude`, `lon`, `lng`, `longitude` and the others listed under [make a KML from a spreadsheet](https://whereday.com/tools/kml-to-csv/#csv-to-kml)). Change any of them here. They must be decimal degrees.

Latitude

Longitude

Name (optional)

Altitude in metres (optional)

Time (optional)

SeparatorDetect (comma, semicolon or tab)CommaSemicolonTab

First rowDetectIs a headerIs a point

2. Points or a line PointsOne Placemark per row. The name comes from the name column, and every other column becomes `ExtendedData`. One lineOne LineString through the rows in file order. If every row has a time, it is written as a `gx:Track` so the times are kept. As exported by this toolThis CSV has the `placemark`, `geometry` and `vertex` columns this page writes. Rows are grouped back into the same placemarks, with their folders, parts and ExtendedData. File and document name

### Report

A local plot with no map tiles. North is up.

Download KMLDownload KMZ

On this page
- [What’s in the CSV](https://whereday.com/tools/kml-to-csv/#columns)
- [Why more rows than placemarks](https://whereday.com/tools/kml-to-csv/#lines)
- [Longitude first](https://whereday.com/tools/kml-to-csv/#coordinate-order)
- [KMZ files](https://whereday.com/tools/kml-to-csv/#kmz)
- [Excel and Sheets](https://whereday.com/tools/kml-to-csv/#excel)
- [CSV to KML](https://whereday.com/tools/kml-to-csv/#csv-to-kml)
- [Limits](https://whereday.com/tools/kml-to-csv/#limits)
- [Questions](https://whereday.com/tools/kml-to-csv/#faq)
- [Sources](https://whereday.com/tools/kml-to-csv/#sources)

## What’s in the CSV

Every row has the same fixed columns, in this order, followed by one column for each `ExtendedData` name found in the file. Each `<Data name="…">` and each `<SimpleData name="…">` becomes a column named after it. The header row is always written. The column names for latitude and longitude are spelled out, so the file does not depend on anyone remembering KML’s order.

| Column | What it holds
| `placemark` | The placemark’s number in the file, starting at 1. Rows with the same number belong together.
| `name`, `description` | The placemark’s `name` and `description`. Descriptions are turned into plain text unless you choose to keep the HTML.
| `folder` | The names of the folders it sits in, joined with “ / ”.
| `geometry` | `Point`, `LineString`, `LinearRing`, `Polygon` or `gx:Track`.
| `part` | The number of the part inside a `MultiGeometry` or `gx:MultiTrack`. Empty for a placemark with one geometry.
| `vertex`, `vertex_count` | The vertex’s number within its part, and how many vertices the part has.
| `latitude`, `longitude` | Decimal degrees, WGS 84, written exactly as they appear in the file.
| `altitude_m` | The third number of a KML coordinate, as written. Empty when there is none.
| `time_utc` | From `TimeStamp`/`when`, or from each `when` of a `gx:Track`, converted to UTC.
| `original_time` | The time exactly as written, when it is not already plain UTC (for example `2000-01-01T09:30:00-05:00`), or when it could not be read, such as a date with no time of day.

### Example: synthetic data

This is synthetic, invented data, from a made-up park. It is not anyone’s trip. [synthetic-kml-to-csv.kml](https://whereday.com/samples/synthetic-kml-to-csv.kml) has seven placemarks. These are five of the 17 rows it produces in the default one-row-per-vertex mode, with some columns left out to fit. The full output is in [synthetic-kml-to-csv.expected.csv](https://whereday.com/samples/synthetic-kml-to-csv.expected.csv).

| placemark | name | geometry | vertex | latitude | longitude | altitude_m | time_utc | category
| 1 | Made-up fountain | Point | 1 | 40.7829 | -73.9654 | 15 | 2000-01-01T14:30:00Z | landmark
| 2 | Bench, "north" side | Point | 1 | 40.7835 | -73.9660 |  |  | seating
| 4 | Made-up loop path | LineString | 1 | 40.7829 | -73.9654 | 15 |  | path
| 4 | Made-up loop path | LineString | 2 | 40.7832 | -73.9658 | 16 |  | path
| 4 | Made-up loop path | LineString | 3 | 40.7834 | -73.9663 | 17 |  | path

The fountain’s time in the file is `2000-01-01T09:30:00-05:00`, so `time_utc` shows 14:30 UTC and `original_time` keeps the original text. The `category` column came from the placemarks’ `ExtendedData`. The sample’s third placemark has a name that starts with `=`, which is why [cells get an apostrophe](https://whereday.com/tools/kml-to-csv/#excel).

## Why there are more rows than placemarks

A KML placemark can hold a whole line. A CSV row can hold only one position. So by default the tool writes one row per vertex: a LineString with 400 vertices becomes 400 rows that share a `placemark` number and count up in `vertex`. The report calls the extra rows “line vertices expanded”. A timed `gx:Track` is the same, with one `time_utc` per row. Google describes `gx:Track` in the [KML reference](https://developers.google.com/kml/documentation/kmlreference#gxtrack) as pairing each `when` with a `gx:coord`.

Choose one row per placemark if you want a list of places, not a list of points. Each geometry then gets one row with its first vertex and a `vertex_count`. The rest of the line is not in that CSV. Use it for counting and sorting, not for rebuilding a path.

- Polygons. A `Polygon` is written from its outer boundary: one row per vertex in vertex mode (the last vertex repeats the first, as KML rings do), or one row with the first vertex and a count in placemark mode. Inner boundaries (holes) are not written, and the report counts them.
- MultiGeometry. Its parts are numbered 1, 2, 3 in the `part` column, in file order. A `gx:MultiTrack` is numbered the same way.
- No geometry. A placemark with no usable coordinates has nothing to put in a row, so it is skipped and counted.

The Google [KML reference](https://developers.google.com/kml/documentation/kmlreference) defines each of these elements.

## KML is longitude first

In KML, a coordinate is written `longitude,latitude[,altitude]`, with no spaces inside a tuple and a space between tuples. The Google [KML reference](https://developers.google.com/kml/documentation/kmlreference) states this order for `coordinates`. Most spreadsheets, and most people, say latitude first. Copying the numbers across unchanged is the commonest way to end up with points in the wrong place.

This tool does the swap for you and labels the result. A KML coordinate `-73.9654,40.7829,15` becomes `latitude` 40.7829, `longitude` -73.9654 and `altitude_m` 15. Going the other way, a CSV row with latitude 40.7829 and longitude -73.9654 is written `-73.9654,40.7829`. The digits are copied as written, not rounded. Check the local plot in the report: a swapped pair would show up in a different place. A latitude outside −90 to 90, or a longitude outside −180 to 180, is rejected and counted, which also catches many swaps.

## KMZ files: what’s inside, and what is skipped

A KMZ is a ZIP archive that holds a KML file and, often, the files it refers to, such as icons and overlay images. Google’s [KMZ archives guide](https://developers.google.com/kml/documentation/kmzarchives) says the KML at the top level is normally named `doc.kml`. The tool reads `doc.kml` and, if there is none, the first `.kml` at the top level of the archive, the same way the [KML and KMZ viewer](https://whereday.com/tools/kml-viewer/) does. It does this in your browser, with no library and no upload. Both stored and deflate-compressed entries work, and the file is recognised by its ZIP signature, so a KMZ renamed `.zip` or `.kml` still opens.

What is not converted is listed in the report, with counts and a reason:

- Images and other files in the archive. Icons, overlay pictures and photos. A CSV has no place for them.
- NetworkLink. It points to content somewhere else. The tool makes no network requests, so it is not followed.
- GroundOverlay, PhotoOverlay, ScreenOverlay, Model and Tour. They are not points or lines, so there is nothing to write.
- Style and StyleMap. Colours, icons and line widths are not carried into a CSV.
- TimeSpan. Only `TimeStamp` and `gx:Track` times are written.

Encrypted or ZIP64 archives, and files with a `DOCTYPE` or entity declaration, are refused with a message.

## Open the CSV in Excel or Google Sheets

A CSV is plain text. Any spreadsheet program can read it, but each one decides how to split the columns and how to guess the type of each cell, so open it with an import step if you can, not by double-clicking.

- Look for the program’s import command rather than opening the file directly. Excel has Data → From Text/CSV, which Microsoft’s [text and CSV import guide](https://support.microsoft.com/en-us/excel/get-started/import-or-export-text-txt-or-csv-files) describes. Google Sheets has its own import command; check Google’s help for the current steps.
- Choose UTF-8 and a comma separator, and make sure the header becomes the column names.
- Keep `time_utc` as text at first if you want to see the exact value. Spreadsheets often reformat dates.
- Check the first rows against the report: `latitude` should be between −90 and 90.

We have not tested a particular version of any spreadsheet program, so these are general steps.

### Why there is a UTF-8 BOM, and an apostrophe

- The BOM. The optional byte-order mark is three bytes at the start of the file that say it is UTF-8. Some programs use it to choose the right character set, so names with accents show correctly; others ignore it. It is on by default. If your program shows odd characters in front of the first header, run the conversion again with it off.
- The apostrophe. A cell that starts with `=`, `+`, `-` or `@` can be run as a formula by a spreadsheet. [OWASP’s CSV injection page](https://owasp.org/www-community/attacks/CSV_Injection) describes the risk. Names, descriptions, folders and ExtendedData values like that get a leading apostrophe, so you see them as text. The numbers in `latitude`, `longitude` and `altitude_m` are not touched, so negative values stay numbers, and neither is an ExtendedData value that is a plain number. When you convert such a CSV back to KML here, the apostrophe is removed.

Values that contain a comma, a double quote or a line break are put in double quotes, and a quote inside is doubled, as in [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180). Lines end with CRLF.

## Make a KML from a spreadsheet

Save the sheet as a CSV (in most spreadsheet programs, File → Save As or Download offers it), then drop it above. The file needs a header row and two columns of decimal degrees: latitude from −90 to 90 and longitude from −180 to 180. Degrees-minutes-seconds text such as `40°46′58″N` is not converted; those rows are skipped and listed by row number.

| Field | Header names recognised | Written to KML as
| Latitude (required) | `lat`, `latitude`, `y` | The second number of `coordinates`
| Longitude (required) | `lon`, `lng`, `long`, `longitude`, `x` | The first number of `coordinates`
| Name | `name`, `title`, `label` | `<name>`
| Altitude | `ele`, `elevation`, `elevation_m`, `alt`, `altitude`, `altitude_m` | The third number of `coordinates`
| Time | `time`, `timestamp`, `time_utc`, `date`, `datetime` | `<TimeStamp>`, in UTC. A time with no zone is treated as UTC and counted in the report.
| Description | `description`, `desc`, `notes`, `comment` | `<description>`
| Every other column | Any header | A `<Data name="…">` in `ExtendedData`, when the cell is not empty

The output is KML 2.2, with coordinates in `longitude,latitude[,altitude]` order. Points writes one Placemark per row. One line writes a single LineString through the rows in file order. If you convert a CSV that this page wrote, the third option groups the rows back into the same placemarks, folders and parts. A placemark that was exported in one-row-per-placemark mode has only its first vertex in the CSV, so it comes back as a Point, and the report says so.

KMZ download. The page can also write a KMZ. It builds the ZIP itself with the stored method (no compression) and one entry, `doc.kml`, with no library. The KML download is the same content without the ZIP.

### Open the result in Google Earth or My Maps

For Google Earth, check [Google Earth Help](https://support.google.com/earth/) for the current import steps. For My Maps, Google lists the supported file types in its [import help](https://support.google.com/mymaps/answer/3024836); check it before you rely on a file type or size. The site’s guide to [opening a GPX file in Google Maps](https://whereday.com/blog/gpx-to-google-maps/) shows the My Maps steps on a computer. We have not tested these apps with this tool’s output, so open a small file first.

### Round trip

CSV written by this tool comes back as equivalent placemarks: the same names, the same coordinates in the same order, and the same ExtendedData values. The site’s tests check this with the synthetic sample. Styles are not in the CSV, so they are not in the result, and polygon holes are not kept. Folder names containing “ / ” come back as nested folders.

## Limits

- One file at a time, up to 10 MiB, 50,000 coordinates and 50,000 CSV rows, the same limits as the other tools here. The whole file is read into memory, so a very large file can make the page slow; we have not benchmarked it, so we do not promise a speed.
- No NetworkLinks. A KML that only links to other content converts to nothing, and the report says so.
- No styles. Colours, icons and line widths are not carried into the CSV or restored from it.
- No reprojection. KML coordinates are WGS 84 longitude and latitude. Values are copied as written, not converted from another coordinate system.
- Altitude is copied as written. KML altitude modes are not interpreted.
- Not every field is kept. Polygon holes, `TimeSpan`, overlays and models are not written, and the report counts them.
- Browsers. Opening a KMZ needs `DecompressionStream`; if your browser lacks it, the page says so and you can unzip the file and choose the `doc.kml` inside. We have not tested every browser.

## Related tools and guides

- [KML and KMZ viewer](https://whereday.com/tools/kml-viewer/): see what is inside a file first.
- [KML to GPX](https://whereday.com/tools/kml-to-gpx/): for apps that want GPX.
- [KMZ to GPX and GPX to KMZ](https://whereday.com/tools/kmz-to-gpx/): for a KMZ that a GPS app or watch will not open.
- [CSV to GPX](https://whereday.com/tools/csv-to-gpx/): for a GPX track or waypoints.
- [GPX viewer](https://whereday.com/tools/gpx-viewer/): check a GPX file and save it as CSV.
- [GPX, KML, GeoJSON and CSV compared](https://whereday.com/blog/gpx-kml-geojson-location-history/)
- [Open a GPX file in Google Maps](https://whereday.com/blog/gpx-to-google-maps/)
- [All tools](https://whereday.com/tools/)

## Questions

### Is it free?

Yes. There is no account, no limit on how many files you convert and nothing to install.

### Does it upload my file?

No. The file is read, converted and saved in your browser. The page blocks network connections and loads no analytics, so it cannot send the file anywhere. The web server sees an ordinary request for the page and its scripts, not your file or its name.

### Can I convert a KMZ to CSV?

Yes. Drop the KMZ as it is. The tool unzips it in your browser and converts the `doc.kml` inside. Images in the archive are listed as skipped.

### Can I convert to Excel (.xlsx)?

Not directly. The tool writes CSV, which Excel and other spreadsheet programs open, and has no .xlsx writer. Open the CSV in your spreadsheet program and save it as a workbook there if you need one.

### Why does my KML have more rows than placemarks in the CSV?

Lines and tracks are written one row per vertex. See [why there are more rows than placemarks](https://whereday.com/tools/kml-to-csv/#lines), or choose one row per placemark.

## Sources

- [Google KML reference](https://developers.google.com/kml/documentation/kmlreference): Placemark, Point, LineString, Polygon, MultiGeometry, ExtendedData, TimeStamp and the order of `coordinates`
- [Google KMZ archives guide](https://developers.google.com/kml/documentation/kmzarchives)
- [Google KML reference: gx:Track](https://developers.google.com/kml/documentation/kmlreference#gxtrack)
- [RFC 4180: Common Format and MIME Type for CSV Files](https://www.rfc-editor.org/rfc/rfc4180)
- [OWASP: CSV injection](https://owasp.org/www-community/attacks/CSV_Injection)
- MDN: [File API](https://developer.mozilla.org/en-US/docs/Web/API/File_API) and [DecompressionStream](https://developer.mozilla.org/en-US/docs/Web/API/DecompressionStream)
- [Google My Maps: import help](https://support.google.com/mymaps/answer/3024836) (supported file types)

## Make your own KML or CSV of where you’ve been.

Whereday records visits and routes on your iPhone and exports KML, CSV, GPX, GeoJSON and more. Exporting to a file requires export access, and existing users keep the access they already have; see [current pricing](https://whereday.com/#pricing).

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Look inside a KML or KMZ first](https://whereday.com/tools/kml-viewer/)

See placemarks, lines, polygons and what the viewer skips, without uploading the file.

- [Convert KML to GPX](https://whereday.com/tools/kml-to-gpx/)

Keep gx:Track times and see what was dropped.

- [Convert a CSV to GPX](https://whereday.com/tools/csv-to-gpx/)

Turn a spreadsheet of coordinates into a GPX track or waypoints.

- [Put it on Google Maps](https://whereday.com/blog/gpx-to-google-maps/)

Import a GPX into My Maps on a computer, then view it on iPhone.

Source: https://whereday.com/tools/kml-to-csv/
