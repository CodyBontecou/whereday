- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Open a GPX file in Google Maps

Practical iPhone guides

# How to open a GPX file in Google Maps, via My Maps

The documented route, the import limits, what the iPhone app can and cannot do, and what to check when it fails.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 22, 2026

In this guide
- [Can Google Maps open a GPX file?](https://whereday.com/blog/gpx-to-google-maps/#short-answer)
- [What you need](https://whereday.com/blog/gpx-to-google-maps/#before-you-start)
- [Import the GPX into My Maps](https://whereday.com/blog/gpx-to-google-maps/#import-steps)
- [See it in Google Maps on iPhone](https://whereday.com/blog/gpx-to-google-maps/#view-on-iphone)
- [If the import fails or looks wrong](https://whereday.com/blog/gpx-to-google-maps/#troubleshooting)
- [View a GPX without uploading it](https://whereday.com/blog/gpx-to-google-maps/#private-alternative)
- [Put a day from your iPhone on Google Maps](https://whereday.com/blog/gpx-to-google-maps/#from-whereday)
- [Questions](https://whereday.com/blog/gpx-to-google-maps/#faq)

## Can Google Maps open a GPX file?

Not directly. The Google Maps app has no “open GPX” button. Google’s documented route is [Google My Maps](https://support.google.com/mymaps/answer/3024836) on a computer: create a map, add a layer and import the file. The map then appears in the Google Maps app, where you can view it.

The steps below are for a computer. The Google Maps app on your iPhone only displays the finished map; it is not where you import or edit. A [GPX file](https://www.topografix.com/GPX/1/1/) is a text file of GPS points; see [what is a GPX file](https://whereday.com/blog/what-is-a-gpx-file/) if you are unsure what yours holds.

## What you need

- A Google account.
- The `.gpx` file on your computer or in Google Drive.
- A file within Google’s documented limits. [Google’s import help](https://support.google.com/mymaps/answer/3024836) lists these file types: CSV, TSV, KML, KMZ, GPX, XLSX, Google Sheets, and images from Drive or Google Photos.

Import limits from Google’s help
| Limit | Value
| Rows per import | 2,000
| KML or KMZ, unzipped | 5 MB
| Other file types | 40 MB

Google also notes that there is [no standalone My Maps app on Android](https://support.google.com/mymaps/answer/10656656), which is why this guide uses a computer.

## Import the GPX into My Maps (on a computer)

- On a computer, sign in to [Google My Maps](https://www.google.com/mymaps) with your Google account.
- Create a new map, or open an existing one.
- In the left panel, choose Add layer.
- Name the layer if you want to. Give each imported file its own layer so you can show, hide or delete one track without touching the others.
- In the new layer, choose Import.
- Upload the GPX file, or select it from Google Drive.

These steps follow Google’s [Import map features from a file](https://support.google.com/mymaps/answer/3024836). Button labels can change, so if one differs, use Google’s page as the reference. Once the import finishes, the track is a layer on your map.

## See it in the Google Maps app on iPhone

Open the Google Maps app on the iPhone signed in to the same Google account. Your My Maps appear under Saved → Maps, as described in Google’s [View your My Maps using Google Maps](https://support.google.com/maps/answer/3045850). The page has an iPhone and iPad tab; check it for the current wording of the labels on your version.

- Google Maps shows only your 5 most recent My Maps. An older map may not appear there, but it is still in My Maps on a computer.
- To edit a map, use My Maps on a computer. The Google Maps app is for viewing it.

## If the import fails or the track looks wrong

- The file has more than 2,000 rows. That is Google’s documented limit per import. Split the track into shorter files, for example one per day, and import each into its own layer. Google does not document how it handles points beyond this, so this page makes no claim about it.
- A KML or KMZ over 5 MB unzipped. Google documents 5 MB for unzipped KML/KMZ and 40 MB for other files. If your track is a KML or KMZ, [convert it to GPX](https://whereday.com/tools/kml-to-gpx/) in your browser and check the result against the limits.
- You have a KMZ. A KMZ is a zipped KML. Convert or unzip it first; the [KML to GPX tool](https://whereday.com/tools/kml-to-gpx/) reads KML.
- You have several files. To make one trip file, [merge the GPX files](https://whereday.com/tools/merge-gpx/). Or keep them separate, one layer each.
- You are not sure the file is valid. Open it in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) and read [Check a GPX file before importing it](https://whereday.com/blog/check-gpx-file/) to see the point counts, segments and warnings.

## View a GPX without uploading it

If you only want to look at a track, you don’t need a Google account. The [Whereday GPX viewer](https://whereday.com/tools/gpx-viewer/) runs in your browser, draws the route shape, and reports point counts and dates. Your file is not uploaded. It can also save the points as CSV or GeoJSON; see [how GPX, KML, GeoJSON and CSV differ](https://whereday.com/blog/gpx-kml-geojson-location-history/). The viewer does not show street maps.

## Put a day from your iPhone on Google Maps

Record your days on your iPhone with Whereday, then export them whenever you want them on a map. The [export docs](https://whereday.com/docs/export-import/) list GPX (for GPS tools and route viewers; routes become tracks and visits become waypoints) and KML (for Google Earth and Earth-compatible viewers). You can choose a date range, and “One file per day” if you want daily files. The [route guide](https://whereday.com/blog/record-iphone-route-gpx/) has the full export steps; choose Points to get a path rather than pins.

Then follow the [import steps above](https://whereday.com/blog/gpx-to-google-maps/#import-steps) with the exported file. This is a synthetic example, not real data: a one-day export with 2 segments is one track in one file, so it imports as a single layer. If you exported one file per day for a week, you would have 7 files, and each would get its own layer, or you could [merge them](https://whereday.com/tools/merge-gpx/) first.

From version 1.8, new users need an eligible trial, subscription or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Exported coordinates and times can reveal your movements, and a file you upload to Google follows Google’s privacy rules.

## Questions

### Can I import GPX in the Google Maps app on iPhone?

Google’s documented import steps are for My Maps on a computer. The iPhone app displays maps you have already made; see [the iPhone section](https://whereday.com/blog/gpx-to-google-maps/#view-on-iphone).

### Does My Maps accept KML?

Yes. Google lists KML and KMZ among the supported types, with a 5 MB limit for them unzipped.

### Why is only part of my track showing?

Check the file against the 2,000-row limit, and open it in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) to confirm it holds the points you expect. Google does not document more than that, so the viewer is the way to tell a file problem from a display one.

### Why can’t I see my map on the phone?

Google Maps shows your 5 most recent My Maps, under the same Google account you used on the computer.

## Record your days.
Put them on a map.

Record your days and routes on your iPhone, then export GPX or KML whenever you want them on a map.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [View it without uploading](https://whereday.com/tools/gpx-viewer/)

See the route, point counts and dates locally in your browser.

- [Combine several files first](https://whereday.com/tools/merge-gpx/)

Merge daily GPX files into one trip file.

- [Record and export your own route](https://whereday.com/blog/record-iphone-route-gpx/)

Record in Whereday and export Points as GPX.

Source: https://whereday.com/blog/gpx-to-google-maps/
