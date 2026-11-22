- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Visited countries map

iPhone location journals

# Map the countries you’ve visited from your own location file

Drop in a file you already have. The map fills itself in from your points, and every country shows the dates behind it, so you can check each one.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 20, 2026

On this page
- [Make your map](https://whereday.com/tools/visited-countries-map/#make-map)
- [How points become countries](https://whereday.com/tools/visited-countries-map/#how-it-works)
- [Which file to use](https://whereday.com/tools/visited-countries-map/#which-file)
- [What counts as visited](https://whereday.com/tools/visited-countries-map/#count-rules)
- [Limits](https://whereday.com/tools/visited-countries-map/#limits)
- [Your file stays in this page](https://whereday.com/tools/visited-countries-map/#file-privacy)

## Add your files. They stay in this page.

Drop in a GPX, KML, GeoJSON, Google Timeline JSON or Whereday Country Days CSV file, or several. The countries your points fall in are shaded on the map and listed with the first date, last date and number of distinct dates, so you can check each one. No account, upload or analytics.

This is a record of where a phone was, not proof of where you lived or were present. GPS points can be wrong or missing, and this page is not legal or tax evidence. For day counts, use the [days-in-country calculator](https://whereday.com/tools/country-days-calculator/).

JavaScript is needed to read files and draw the map in your browser. The explanations below work without it.

Choose files or drop them here · GPX, KML, GeoJSON, Google Timeline JSON or Country Days CSV · up to 10 MiB and 50,000 points per file

Try a synthetic exampleReset manual changesClear everything

Add files, or try the synthetic example first: five invented points in three countries.

### Files

The count depends on the boundary set. Territories and disputed areas follow Natural Earth, so it can differ from other lists. [See what counts](https://whereday.com/tools/visited-countries-map/#count-rules).

Visited countries are dark red. Click a country on the map to add or remove it by hand, or use the table or the country list below, which also work from the keyboard. The table is the full, accessible version of this map.

### How the points matched

### Near a border or coast, not counted

These countries only have points within about 10 km of another country’s border, or just off a simplified coastline. They are not counted, because the boundary data is not exact enough to say. If you know you were there, add them by hand.

Add a country by hand (for trips before your file)  Add country

Manual changes are marked “manual” or “removed manually” in the table and in the CSV.

Download map (SVG)Download map (PNG)Download table (CSV)

The map downloads have no watermark.

The synthetic example is [synthetic-countries-trip.gpx](https://whereday.com/samples/synthetic-countries-trip.gpx): five invented points on 1, 3, 4, 6 and 7 January 2000, one in Portugal, two in Spain and two in France. It is not anyone’s recording.

## How points become countries

- Read. GPX track points, route points and waypoints; Google Timeline visits and timed points (accepted records only); GeoJSON Point, LineString and MultiLineString coordinates; and KML Point and LineString coordinates (not Google Earth’s `gx:Track` elements; [convert such a KML to GPX](https://whereday.com/tools/kml-to-gpx/) first). A Country Days CSV already names its countries, so it needs no lookup. The format is detected from the file’s content, not its name.
- Thin. To keep long tracks fast, the page keeps one point per 0.01° cell (about 1 km) for each date. Every file’s list shows how many points were read and how many were kept.
- Look up. Each point is tested against country outlines stored in this page, using a bounding-box check and then a point-in-polygon test. Nothing is sent to a geocoder or map service. Only the points themselves are tested: the line between two points is not, so a flight is not a visit to the countries it crosses.
- Border and water. A point inside one country but within about 10 km of another country’s outline is counted as “near a border”, not as a visit. A point in no outline, such as open water, is “unmatched”. A country that only has such points is listed under “near a border or coast, not counted”, with a button to add it by hand.
- Dates. A timestamp with a UTC offset (such as `+02:00`) uses the date as written. A timestamp ending in `Z` uses its UTC date. Google Timeline times are read as UTC dates. Each file shows which rule it used. Points with no usable date still count towards the country, but add no date.

The boundaries are [Natural Earth 1:50m Admin 0 – Countries](https://www.naturalearthdata.com/downloads/50m-cultural-vectors/50m-admin-0-countries-2/), version 5.1.2, which are in the public domain ([terms of use](https://www.naturalearthdata.com/about/terms-of-use/)). We simplified them to a tolerance of about 0.01° (roughly 1 km) and bundled them as a script, `/assets/visited-countries-data.mjs`: about 420 KB, about 150 KB gzipped, with 238 country and territory outlines (Antarctica included). The file formats are defined in the [GPX 1.1 schema](https://www.topografix.com/GPX/1/1/), [GeoJSON (RFC 7946)](https://www.rfc-editor.org/rfc/rfc7946) and [OGC KML 2.2](https://www.ogc.org/standard/kml/).

Longitudes are wrapped so that 180° and −180° are the same meridian, and the outlines are already split there, so a track that crosses the date line is handled. Points at the poles are tested like any other and usually come out unmatched, except in Antarctica.

## Which file to use

Where to get a location file for this page
| Your data is in | File to use | Where to start
| iPhone | Whereday GPX or Country Days CSV; Apple Maps and Significant Locations do not export a file | [Find your iPhone location history](https://whereday.com/blog/view-location-history-iphone/), then [back it up](https://whereday.com/blog/back-up-iphone-location-history/)
| Google Timeline | Extracted Timeline JSON (device, semantic or raw) | [Which Timeline JSON file you have](https://whereday.com/blog/google-timeline-json-formats/); Google’s [Timeline export help](https://support.google.com/maps/answer/6258979?co=GENIE.Platform%3DiOS&hl=en)
| A GPS watch or route app | GPX | [Record and export a route as GPX](https://whereday.com/blog/record-iphone-route-gpx/)
| Many daily or trip files | One merged GPX, or add them all here at once | [Merge GPX files](https://whereday.com/tools/merge-gpx/)

Not sure what is inside a file? Check a GPX in the [GPX viewer](https://whereday.com/tools/gpx-viewer/) or a Timeline file in the [Timeline inspector](https://whereday.com/tools/google-timeline-inspector/) first. Renaming a file does not convert it. ZIP archives are not read: extract the JSON first.

## What counts as visited

Here, a country counts if at least one of your points, after thinning, falls inside its outline and not within about 10 km of another country’s outline, or if you add it by hand. Time spent there does not matter, and neither does the reason for the stop. A layover counts only if your phone recorded a point there.

- Natural Earth’s list. Its Admin 0 set includes many territories as their own entries, such as Greenland, Puerto Rico or Hong Kong, and includes Antarctica. Three entries without an ISO code (Northern Cyprus, Somaliland and Siachen Glacier) use Natural Earth’s own three-letter codes. Disputed areas follow Natural Earth’s choices, which not everyone agrees with. Dependencies it does not draw separately may show under another country or be missing.
- Travel clubs. Clubs have their own lists. The Travelers’ Century Club, for example, [counts a specific list of countries and territories](https://travelerscenturyclub.org/countries-and-territories), so your number there can differ from this one.
- UN member states. The [United Nations member list](https://www.un.org/en/about-us/member-states) is shorter than any list that includes territories. This map does not filter to it.

## Limits

- Boundaries are simplified. Outlines are at about 1:50,000,000 and cut down further for size. Points near borders and coastlines can land on the wrong side or in no country. That is why they are flagged rather than guessed. Small islands and very small states may be missing or crude.
- Coastal cities. A point in a harbour or on a river mouth can fall just outside the coast outline and be unmatched. When it is within about 10 km of land the country is offered under “near a border or coast”.
- Airports and layovers. Flying over a country is not a visit. Only recorded points are tested, and a phone in flight mode records nothing. A stop at an airport counts only if a point was recorded there.
- Missing coverage. A file shows only what was recorded. Gaps, switched-off phones and trips before your file began do not appear. Add those by hand.
- Trips before your file began. To rebuild them from official and personal records, see how to [find your travel history](https://whereday.com/blog/find-your-travel-history/).
- Size. Each file can be up to 10 MiB and 50,000 points, the same limits as the [GPX viewer](https://whereday.com/tools/gpx-viewer/). For more, split the export by date range.
- Not a residence or tax record. GPS points show where a phone was, not where a person was. This page does not decide residency, visa days or tax status. For Schengen 90/180 and 183-day questions, use the [days-in-country calculator](https://whereday.com/tools/country-days-calculator/), and check the official rules.

## Your file stays in this page

Reading, matching, drawing the map and making the downloads all happen in your browser. This page blocks network connections and does not include the website’s analytics. The web server receives an ordinary request when you open the page and its scripts, but not your files, their names or the result. Clearing the page or closing the tab removes them; your original files are never changed.

## Stop rebuilding the map.

Whereday records the countries you visit on your iPhone and keeps every day on the map. Timeline → Country Days lists the countries with their dates, and the map’s All range shows your places with day counts. It does not draw a shaded country map like this page. Viewing Country Days and adding manual stays are free. From version 1.8, new users keep their exports after tracking access expires; existing free users keep their previous export-unlock requirement. See [pricing](https://whereday.com/#pricing) and [how Country Days works](https://whereday.com/docs/timeline-outings/).

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Count days instead of countries](https://whereday.com/tools/country-days-calculator/)

Check Schengen 90/180, rolling windows and 183-day totals from a Country Days CSV.

- [Record the countries automatically](https://whereday.com/blog/track-days-in-country-iphone/)

See how Whereday records country days on your iPhone.

- [Merge several files first](https://whereday.com/tools/merge-gpx/)

Combine daily GPX files into one before you map them.

Source: https://whereday.com/tools/visited-countries-map/
