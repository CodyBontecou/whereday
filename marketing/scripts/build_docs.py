#!/usr/bin/env python3
"""Generate the Whereday docs section (marketing/public/docs/*.html)."""
import pathlib, html

OUT = pathlib.Path(__file__).resolve().parent.parent / "public" / "docs"
BASE = "https://whereday.com/docs/"
UPDATED = "October 26, 2026"
REVIEWED = {"getting-started", "tracking", "export-import", "privacy-data", "apple-watch", "webhooks", "timeline-outings", "siri-shortcuts", "places", "automatic-exports"}

GUIDES = [
    # (slug, title, short, lede, platform, minutes, tag)
    ("getting-started", "Getting started",
     "Install Whereday, choose location permissions, and check your first recorded route and visits on iPhone.",
     "Everything from installing Whereday to your first recorded day — what each permission is for and where your data lives from minute one.",
     "iOS 17+", 3, None),
    ("tracking", "Visits, routes & battery",
     "How automatic visit detection and route recording work, and the settings that control them.",
     "Start records GPS routes and monitors visits together. Learn the shared controls, permissions, distance filter, and auto-stop timer.",
     "iOS 17+", 4, None),
    ("timeline-outings", "Timeline & outings",
     "The daily timeline, inferred outings, route replay, and editing or deleting sessions.",
     "The Timeline tab merges visits and movement into one day view. Learn the difference between recorded and inferred outings, how replay works, and what deletion actually removes.",
     "iOS 17+", 4, None),
    ("places", "Save and edit places in your iPhone location journal",
     "Manage saved locations, enter coordinates, import a places CSV, and distinguish reusable places from past visits in Whereday.",
     "Save a reusable location for future visit names, correct an existing visit, or add a past stop. Choose the right action before changing your journal.",
     "iOS 17+", 5, None),
    ("photos", "Photos on the map",
     "Optionally connect your Photos library to place pictures on your map and outings.",
     "An opt-in connection between your photo library and your location journal. Photo files never leave your library — Whereday only matches metadata.",
     "iOS 17+", 2, None),
    ("export-import", "Export and restore location history",
     "Eight export formats, filters, filename templates, and free import for restores.",
     "Choose among eight export formats; content depends on the record type. Preview and supported import are free. Export access depends on when you joined; new users retain exports after tracking expires.",
     "iOS 17+", 5, None),
    ("automatic-exports", "Automatically export iPhone location history to a folder",
     "Set up daily or interval exports, check rewrite and append behavior, and verify saved files in Whereday.",
     "Save the current local day to a folder you choose. Check a manual run first, then choose how later runs update the file and how you will spot a missed day.",
     "iOS 17+", 6, None),
    ("webhooks", "Webhooks & self-hosting",
     "Configure optional delivery and verify your receiver’s payload and authentication requirements.",
     "The optional webhook feature posts your data, in your chosen format, to a server you control. Off by default, fully configurable, and nothing is sent until you set it up.",
     "iOS 17+", 4, None),
    ("siri-shortcuts", "Siri & Shortcuts for iPhone location tracking",
     "Start and stop tracking, read saved visits, rename outings, and export a chosen date range with Whereday Shortcuts actions.",
     "Build shortcuts for recording, reading your journal, renaming stops, and exporting files. Choose the dates and data you need, then check the result before adding an automation.",
     "iOS 17+", 5, None),
    ("apple-watch", "Record Apple Watch GPS routes without your iPhone",
     "Record on Apple Watch, check transfer status, and verify the phone copy with a checked offline-queue example.",
     "Start a route on Apple Watch, record without your iPhone nearby, then check the transfer back to your phone. Learn the recording controls, sync status, and recovery checks.",
     "watchOS 10+", 6, None),
    ("privacy-data", "Privacy & your data",
     "Understand Whereday’s on-device location storage, optional network services, version-specific analytics, and deletion controls.",
     "A plain-language map of every place your data can go: on-device storage, Apple system services, first-party analytics, and the optional features you control.",
     "iOS 17+ · watchOS 10+", 4, None),
]

BODIES = {}

BODIES["getting-started"] = """
<h2>Install and open</h2>
<p>Whereday is a free download on the App Store. There is no account to create and nothing to sign up for — the first thing you see is a five-step introduction.</p>

<h2>The onboarding steps</h2>
<ol>
<li><strong>Welcome</strong> — a short overview of what Whereday records and where it keeps it.</li>
<li><strong>Core features</strong> — visit timeline, route recording, and exports at a glance.</li>
<li><strong>Permission setup</strong> — Whereday asks for <em>While Using</em> location access first, then offers <em>Always</em>. <strong>Always</strong> is what enables automatic visit detection while the app is in the background; you can decline and still use manual tracking. If permission is denied, the app links you straight to iOS Settings.</li>
<li><strong>Connect Photos (optional)</strong> — you can enable photo moments now or skip entirely. Skipping changes nothing else.</li>
<li><strong>You're all set</strong> — optionally tick <em>Start tracking immediately</em> and Whereday begins recording the moment onboarding finishes.</li>
</ol>

<h2>After onboarding</h2>
<p>You land on the <strong>Map</strong> tab. The other tabs are <strong>Timeline</strong> (your day, visit by visit), <strong>Export</strong>, and <strong>Settings</strong>. The floating pill on the map starts and stops both GPS recording and visit monitoring. With <em>Always</em> permission, recording can continue in the background. Installing the app does not reconstruct earlier history.</p>

<h2>Your first day</h2>
<ul>
<li>Start tracking from the map pill before a walk or drive — you'll see the path draw in, and a Live Activity appears on your lock screen.</li>
<li>Stop tracking from the pill, the Live Activity's Stop button, Siri, or automatically with the auto-stop timer.</li>
<li>Open the Timeline tab to review where you went, how long you stayed, and how far you moved.</li>
</ul>

<div class="callout"><p><strong>Where is my data?</strong> On your iPhone only. See the <a href="/docs/privacy-data">Privacy &amp; your data</a> guide for the complete picture of storage, network requests, and deletion.</p></div>

<h2>Replay the intro</h2>
<p>Settings → <em>Replay onboarding</em> shows the introduction again at any time.</p>
"""

BODIES["tracking"] = """
<h2>Two kinds of history</h2>
<p>Whereday records <strong>visits</strong> (arrivals and departures) and <strong>routes</strong> (GPS paths). <strong>Start enables visit monitoring, significant location changes, and detailed GPS together; Stop ends all three.</strong> There is no separate passive visit-only recording mode. Always location permission allows background recording.</p>

<h2>Starting and stopping</h2>
<ul>
<li><strong>Map pill</strong> — the floating control on the Map tab shows live duration and distance while recording.</li>
<li><strong>Live Activity</strong> — lock screen and Dynamic Island show progress and include a Stop button.</li>
<li><strong>Siri / Shortcuts</strong> — “Start Whereday” and “Stop tracking with Whereday” phrases work hands-free.</li>
<li><strong>Auto-stop timer</strong> — optional safety net that ends tracking automatically.</li>
</ul>
<p>For a walkthrough, see <a href="/blog/private-location-journal-iphone/">how to keep a private location journal</a> or <a href="/blog/record-iphone-route-gpx/">record an iPhone route and export GPX</a>.</p>
<p>Tracking state survives relaunches: if Whereday was recording when you last used your phone, it resumes when you open it again. If iOS relaunches the app in the background for a location event, recording continues without you touching anything.</p>

<h2>Visit detection</h2>
<p>Visits use iOS visit monitoring plus significant-location-change triggers. When you arrive somewhere, Whereday creates a visit; when you leave, it closes it. Behind the scenes Whereday also merges duplicate detections of the same stop (within roughly 100 meters and half an hour) and closes any visit left open when a new arrival makes it clearly stale — so you don't accumulate phantom “current” pins.</p>
<p>New visits are reverse-geocoded to a name and address automatically, unless you've turned <strong>Settings → Location tracking → Location names</strong> off (this toggle controls whether coordinates are sent to Apple's geocoding service).</p>

<h2>Route recording quality</h2>
<ul>
<li>Only fixes with reported accuracy of 100 m or better are saved; wilder fixes are dropped.</li>
<li>Obvious glitches — impossible jumps and out-and-back spikes — are flagged as <em>GPS glitches</em> and excluded from distances and maps by default. Enable <strong>Settings → Map display → Show GPS glitches</strong> to see them.</li>
</ul>

<h2>Tracking settings reference</h2>
<div role="region" aria-label="Tracking settings and defaults" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<caption>Tracking settings and defaults</caption>
<tr><th scope="col">Setting</th><th scope="col">Options</th><th scope="col">Default</th><th scope="col">What it does</th></tr>
<tr><td><strong>Distance filter</strong></td><td>5 / 10 / 25 / 50 / 100 / 200 m</td><td>5 m</td><td>Movement threshold requested for location updates. Larger values may reduce point density and detail; high-accuracy GPS remains active.</td></tr>
<tr><td><strong>Stop after</strong></td><td>Never / 1 / 2 / 4 / 8 / 12 h</td><td>Never</td><td>Automatically ends tracking after this long, so a forgotten session can't run all day.</td></tr>
<tr><td><strong>Location names</strong></td><td>On / Off</td><td>On</td><td>Whether coordinates are sent to Apple's geocoding service to name visits.</td></tr>
<tr><td><strong>Live Activity</strong></td><td>On / Off</td><td>On</td><td>Lock screen / Dynamic Island status while tracking.</td></tr>
<tr><td><strong>Units</strong></td><td>Metric / US</td><td>Metric</td><td>Applies everywhere: app, widgets, Live Activity, watch.</td></tr>
</table>
</div>

<div class="callout"><p><strong>Battery.</strong> Tracking includes detailed GPS as well as visit monitoring, so it uses battery for as long as recording stays on. Stop when you finish, consider a 25–50 m distance filter, and set an auto-stop timer. Check the resulting route before relying on a larger filter; no battery duration is guaranteed. Follow the <a href="/blog/iphone-location-tracking-battery/">battery-use comparison checklist</a> to check the tradeoff on your own phone.</p></div>
<p>If a recorded day looks incomplete, follow the <a href="/blog/missing-location-history-iphone/">missing visits and route points checklist</a> before deleting records or retrying an import.</p>

"""

BODIES["timeline-outings"] = """
<h2>The day view</h2>
<p>The <strong>Timeline</strong> tab shows one day at a time: visits and movement sessions interleaved in order, with a day summary of moves, visits, and total distance. Step through days with the arrows, jump straight to Today, or pick any date. If today is empty, Whereday automatically opens your most recent day with data.</p>

<h2>Recorded vs inferred outings</h2>
<p>A movement session (<em>outing</em>) is <strong>recorded</strong> when you pressed Start, and <strong>inferred</strong> when Whereday notices a stretch of GPS points that clearly forms a trip — including older data recorded before outings existed, or imported from your Watch. Inferred outings wear an <code>INFERRED</code> badge; the live one wears <code>LIVE</code>.</p>
<p>Inference is configurable in <strong>Settings → Inferred outings</strong>:</p>
<table>
<tr><th>Setting</th><th>Options</th><th>Default</th></tr>
<tr><td>Auto-generate</td><td>On / Off</td><td>On</td></tr>
<tr><td>Split after (quiet gap)</td><td>15 m / 30 m / 1 h / 2 h / 4 h</td><td>30 minutes</td></tr>
<tr><td>Ignore shorter than</td><td>None / 5 m / 15 m / 30 m / 1 h</td><td>None</td></tr>
<tr><td>Minimum GPS points</td><td>1 / 2 / 3 / 5 / 10</td><td>1</td></tr>
</table>

<h2>Outing detail</h2>
<p>Tap any outing for its full record: a road-matched mini map, start and end times, duration, distance, point count, GPS-glitch count, and average speed. Recorded outings can be renamed and given notes (inferred ones can't — edit the points' day instead). Linked visits and photos appear below.</p>
<ul>
<li><strong>Show on map</strong> focuses the Map tab on this outing's path.</li>
<li><strong>Export</strong> saves this single outing in your choice of format (subject to your export access; the picker remembers your format).</li>
<li><strong>Delete outing</strong> removes the session and its GPS points — <strong>visits are kept</strong>. Deleting the active outing stops tracking first.</li>
</ul>

<h2>Route replay</h2>
<p>Both the map (for a whole day) and the outing detail page (for one outing) offer <strong>replay</strong>: a playhead walks the path in order while a scrubber, elapsed time, and running distance update. It's a quick way to answer “where was I around 3 pm?”</p>

<h2>Sorting</h2>
<p>Outing lists can be sorted by newest, oldest, longest duration, distance, or point count.</p>
<p>If a recorded day looks incomplete, follow the <a href="/blog/missing-location-history-iphone/">missing visits and route points checklist</a> before deleting records or retrying an import.</p>

"""

BODIES["places"] = """
<h2>Choose a place or a visit</h2>
<p>A <strong>saved location</strong> is a reusable name, coordinate, optional address, and match radius. It helps name future automatic visits; creating one does not create a visit or record a GPS path. A <strong>visit</strong> is a journal entry with arrival and departure information.</p>
<table>
<caption>Choose the action for your task</caption>
<tr><th scope="col">Your goal</th><th scope="col">Use</th></tr>
<tr><th scope="row">Name a place you will revisit</th><td>Settings → Saved Locations → Manage Locations.</td></tr>
<tr><th scope="row">Fix a stop already in your journal</th><td>Open that visit and confirm or correct it.</td></tr>
<tr><th scope="row">Add a stop from an earlier day</th><td>Map → + → Add past visit.</td></tr>
</table>
<p>From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement. See <a href="/#pricing">current pricing</a>.</p>

<h2 id="saved-locations">Manage reusable saved locations</h2>
<ol>
<li>Open <strong>Settings</strong>, scroll to <strong>Saved Locations</strong>, and choose <strong>Manage Locations</strong>.</li>
<li>Choose <strong>Add Location</strong>. Enter a name and, optionally, an address.</li>
<li>Use <strong>Choose Location</strong> to search or position a pin, or enter <strong>Latitude</strong> and <strong>Longitude</strong> directly. Latitude must be between −90 and 90; longitude between −180 and 180.</li>
<li>Review the <strong>Match radius</strong>. The default is 150 metres; this editor accepts 25–10,000 metres. A larger radius can match a wider area, so choose one that fits the place.</li>
<li>Save, then check the location in the list. Search the list or open an existing location to edit its name, coordinate, address, or radius.</li>
</ol>
<p>Nearby future automatic visits can use the saved name. Editing or deleting a location in this management screen keeps past visits unchanged. To fix an older visit, edit that visit itself. A place with the same name is not necessarily the same location; check the pin and radius.</p>
<figure style="max-width:302px;margin:24px auto"><img src="/screenshots/saved-location-editor.webp" width="302" height="656" style="height:auto" alt="Whereday Add Location editor with name, optional address, direct latitude and longitude fields, and a default 150 metre match radius" loading="lazy" decoding="async"><figcaption><p>The editor opened with location permission denied in an isolated Debug simulator rebuild of release 1.7.16, iOS 26.5, checked October 26, 2026. This confirms the available controls, not a completed save through this screen.</p></figcaption></figure>
<p>Direct coordinate entry does not require the phone’s current GPS position. Map search and map previews use Apple services; disabling Location names does not turn those interactive map services into an offline feature. See the <a href="/docs/privacy-data/">network and storage reference</a>.</p>

<h2 id="locations-csv">Import saved locations from CSV</h2>
<p>In <strong>Manage Locations</strong>, choose <strong>Import Locations from CSV</strong>. This is separate from the general <strong>Import File</strong> control for visit and route-point history. A locations CSV defines reusable places, not visits.</p>
<table>
<caption>Saved-location CSV columns</caption>
<tr><th scope="col">Column</th><th scope="col">Requirement</th></tr>
<tr><th scope="row"><code>name</code></th><td>A nonempty location name.</td></tr>
<tr><th scope="row"><code>latitude</code></th><td>A finite number from −90 through 90.</td></tr>
<tr><th scope="row"><code>longitude</code></th><td>A finite number from −180 through 180.</td></tr>
<tr><th scope="row"><code>address</code></th><td>Optional. Quote values containing commas.</td></tr>
<tr><th scope="row"><code>radius_meters</code></th><td>Optional. Defaults to 150 when empty; otherwise use 25–10,000.</td></tr>
</table>
<p>Use UTF-8 CSV, up to 5 MB and 10,000 data rows. Review valid rows, invalid rows, and duplicates before importing. Invalid rows are skipped rather than repaired automatically.</p>
<ul>
<li><strong>Keep existing locations</strong> skips matching locations.</li>
<li><strong>Update matching locations</strong> changes the reusable location’s values for future matching while keeping historical visits unchanged.</li>
</ul>
<p>Matching considers the name and nearby position, using match radii. Name case and surrounding whitespace are ignored. Review the summary and pin instead of assuming that equal names or slightly different coordinates will always create separate entries.</p>
<p><a href="/samples/synthetic-saved-locations.csv" download>Download the synthetic saved-locations CSV</a>. Its two invented stops contain no personal history. Native tests against released 1.7.16 parsed and stored both, checked their coordinates and radii, then confirmed that a repeat import skips two under Keep existing and updates two under Update matching. The existing historical visit remained unchanged.</p>
<p>Those are parser and in-memory storage checks. The file-picker handoff and a complete on-device CSV import have not been newly verified here. Try a small test file and review the preview before importing your own locations.</p>

<h2>Confirm or correct an existing visit</h2>
<p>Open the visit from Map or Timeline. <strong>Confirm place</strong> accepts the detected place; correcting a name, address, or position records your chosen values. Arrival, departure, and notes can also be edited. Nearby business suggestions use Apple Maps around the visit’s coordinate.</p>
<p>Visits can be unconfirmed, confirmed, or corrected. Automatic geocoding does not overwrite a visit that is protected by its confirmation or correction state. <strong>Undo correction</strong> restores the original detected place information. Confirming or correcting a visit can also save reusable place knowledge; that is different from editing a saved location without changing a past visit.</p>

<h2>Add a missing past visit</h2>
<p>On the Map tab, open <strong>+</strong> and choose <strong>Add past visit</strong>. Select the location explicitly by search, pin, or saved place, then enter the arrival and departure times. Past visits do not silently use the phone’s current position when a location is missing.</p>
<p>The same menu offers <strong>Save current place</strong> and <strong>Add place manually</strong> for other entry tasks. Current-position workflows depend on location access; choosing a known past place is a different task. A manual entry records what you enter. It cannot recreate missing GPS points or prove an automatic visit occurred.</p>
<p>For a gap in recorded history, use the <a href="/blog/missing-location-history-iphone/">missing visits and routes checklist</a>. Keep a <a href="/blog/back-up-iphone-location-history/">separate backup</a> before making extensive journal edits.</p>

<h2>Optional current-place suggestions</h2>
<p><strong>Settings → Map display → Visit suggestions</strong> is off by default. When enabled, a nearby saved place or business can be offered as a Save Place prompt. This does not replace tracking permission or guarantee that every stop becomes a detected visit.</p>
<p>Reviewed October 26, 2026 against released Whereday 1.7.16. The editor screenshot and native fixture tests have the limits described above; no new physical route or visit-detection test is claimed.</p>
"""

BODIES["photos"] = """
<h2>What it does</h2>
<p>With photo moments enabled, Whereday can show geotagged photos from your library as markers on the map — clustered when they're close together — and attach them to the outings and visits they belong to. It's an entirely optional layer: off until you turn it on.</p>

<h2>How photos are placed</h2>
<p>Each photo is matched to a location using the first source that works:</p>
<ol>
<li><strong>The photo's own GPS</strong> — geotagged photos are placed exactly where they were taken.</li>
<li><strong>Your route</strong> — otherwise Whereday looks for the nearest recorded GPS point within about 15 minutes of the capture time.</li>
<li><strong>A visit</strong> — otherwise, if the capture time falls inside a visit (with a little grace), the photo attaches to that place.</li>
</ol>
<p>Photos that match none of these are simply skipped.</p>

<h2>Privacy mechanics</h2>
<div class="callout"><p><strong>Photo files never leave your Photos library.</strong> Whereday stores only each photo's local identifier and match metadata on-device. Nothing is uploaded, and limited-access mode is fully supported — only the photos you've selected for Whereday will appear.</p></div>

<h2>Controls</h2>
<ul>
<li><strong>Automatic syncing</strong> (off by default) re-scans your library in the background, at most every 10 minutes, and re-syncs whenever your library changes.</li>
<li><strong>Map markers</strong> (off by default) — enable from the map's filter bar or Settings; nearby photos group into stacked clusters with a count badge.</li>
<li><strong>Thumbnails on markers</strong> can be turned off if you prefer plain pins.</li>
<li>Tap any marker for a quick view, or open it full-screen to swipe through the day's photos.</li>
</ul>
<p>If a photo disappears from your library (with full access), Whereday cleans up its record automatically.</p>
"""

BODIES["export-import"] = """
<div class="callout pro"><p><strong>Preview, import and export access.</strong> Export Preview and supported import are free. New users from version 1.8 retain file exports after tracking access expires. Existing free users keep their previous export-unlock requirement. Auto Export and webhook settings follow the same export access; folder selection, scheduling and receiver setup still need to be configured. See <a href="/#export-access">the access explanation</a>.</p></div>

<h2>Eight formats</h2>
<table>
<tr><th>Format</th><th>Best for</th></tr>
<tr><td><strong>JSON</strong></td><td>Structured visit and point records, scripts, and tooling.</td></tr>
<tr><td><strong>CSV</strong></td><td>Spreadsheets and analysis.</td></tr>
<tr><td><strong>Markdown</strong></td><td>Human-readable journals — single outings export as a front-matter page with visits and route points.</td></tr>
<tr><td><strong>GeoJSON</strong></td><td>Mapping tools and GIS.</td></tr>
<tr><td><strong>GPX</strong></td><td>GPS tools and route viewers; visits become waypoints, routes become tracks.</td></tr>
<tr><td><strong>KML</strong></td><td>Google Earth and Earth-compatible viewers.</td></tr>
<tr><td><strong>OwnTracks</strong></td><td>OwnTracks-ecosystem tools (route points).</td></tr>
<tr><td><strong>Overland</strong></td><td>Overland-ecosystem tools (route points).</td></tr>
</table>
<p>Choose any mix of formats in one export; each becomes its own file. OwnTracks and Overland carry GPS points. In the released 1.8 export flow, choosing either uses Points even if Visits or All is selected; other selected formats keep their selected data type. For an outing, these two formats contain its route points, not visit rows or outing notes. Check Preview for each format. For receiver delivery, follow the <a href="/integrations/dawarich/">Dawarich Overland walkthrough</a> and verify the destination’s saved points.</p>

<h2>What gets exported</h2>
<ul>
<li><strong>Data</strong>: visits, route points, outings, or all of it.</li>
<li><strong>Date range</strong>: all time (default), today, yesterday, last 7 or 30 days, this month, or a custom range.</li>
<li><strong>Time of day</strong> (off by default): keep only records between two times — windows that cross midnight work too.</li>
<li><strong>Filters</strong>: only completed visits, minimum visit duration, exclude GPS glitches, and a maximum-accuracy cap (≤ 10 / 25 / 50 / 100 m).</li>
<li><strong>Fields</strong>: per-format toggles for names, addresses, durations, coordinates, notes, altitude, speed, accuracy, and glitch flags.</li>
</ul>
<p>Your entire setup — formats, data kind, filters, field toggles — is remembered between launches.</p>

<h2>Filename templates</h2>
<p>File names come from a template you control. These tokens are available:</p>
<p><code>{date}</code> <code>{year}</code> <code>{month}</code> <code>{dayNumber}</code> <code>{weekday}</code> <code>{monthName}</code> <code>{quarter}</code> <code>{datetime}</code> <code>{time}</code> <code>{day}</code> <code>{type}</code> <code>{title}</code> <code>{name}</code> <code>{format}</code></p>
<ul>
<li>Use <code>/</code> in a template to build dated subfolders.</li>
<li>Built-in presets: <em>Readable</em> (default) <code>Whereday - {day} {date} - {type}</code>, <em>Compact</em>, and <em>Dated</em> <code>{year}/{year}-{month}/Daily Track - {date}</code>.</li>
<li>A live preview shows the resolved path as you type.</li>
</ul>

<h2>Output layout</h2>
<ul>
<li><strong>Single file</strong> (default) or <strong>one file per day</strong> — handy for journals and incremental backups.</li>
<li>Exporting <em>outings</em> produces one file per outing automatically.</li>
<li>Collisions are auto-suffixed so nothing overwrites.</li>
</ul>

<h2>Saving and sharing</h2>
<p>Pick a <strong>default folder</strong> (with auto-save on by default) and exports land there silently with a confirmation toast; otherwise the standard share sheet appears. The <strong>Preview</strong> button renders everything without writing — free, and a good way to sanity-check filters. Single outings can also be exported straight from their detail page.</p>

<h2 id="supported-import">Import supported visit and point files</h2>
<p>Compare <a href="/blog/gpx-kml-geojson-location-history/">GPX, KML, GeoJSON and CSV</a> with checked synthetic files before choosing an output for another app.</p>
<p>Settings → <strong>Data Import → Import File</strong> accepts supported Whereday JSON, CSV, or Markdown and, in version 1.8, supported Google Timeline JSON. Importing is free. Review format, dates, selected record types, and approximate duplicates before confirming. A matching file extension alone does not make another app's file compatible. Follow the <a href="/blog/import-google-timeline-iphone/">Google Timeline import walkthrough</a> for supported structures and a checked synthetic example.</p>
<div role="region" aria-label="Supported import file formats" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<caption>Choose a file for the next task</caption>
<tr><th scope="col">Format</th><th scope="col">Useful for</th><th scope="col">Import into Whereday</th></tr>
<tr><th scope="row">Whereday JSON</th><td>Structured visit or point records</td><td>Supported visit and point exports</td></tr>
<tr><th scope="row">Whereday CSV / Markdown</th><td>Spreadsheets / readable records</td><td>Supported visit and point exports; required fields must remain</td></tr>
<tr><th scope="row">Google Timeline JSON</th><td>Moving supported Google visits and timed points</td><td>Version 1.8: classic raw, legacy semantic, and device Timeline formats; preview first</td></tr>
<tr><th scope="row">GPX / KML / GeoJSON</th><td>Mapping tools and route viewers</td><td>No</td></tr>
<tr><th scope="row">OwnTracks / Overland</th><td>Compatible location tools</td><td>No</td></tr>
</table>
</div>
<p>If Preview is unexpectedly empty, follow the <a href="/blog/missing-location-history-iphone/#export">export filter diagnosis</a> before changing or importing journal records. It covers record type, arrival dates, time of day, accuracy, and visit completion.</p>
<h2>Keep a copy and check it</h2>
<ol>
<li>Select visits or route points, JSON, and the dates you want to retain. Export each data type you need; keep all generated files.</li>
<li>Preview the result. Check that filters, field choices, and dates have not excluded required records.</li>
<li>Export to a folder you control and confirm the files exist in Files before deleting or replacing anything.</li>
<li>When importing later, keep the originals and inspect several dates, visits, and points after the import. Do not assume every setting, photo association, or edited outing is restored.</li>
</ol>
<p><a href="/docs/automatic-exports/">Automatic exports</a> can keep scheduled copies, subject to iOS background execution. For the recording workflow, see <a href="/blog/private-location-journal-iphone/">the private location journal guide</a>. For a route file, follow <a href="/blog/record-iphone-route-gpx/">the GPX recording walkthrough</a>. For restorable records, use <a href="/blog/back-up-iphone-location-history/">the backup and restore checklist</a>. Comparing apps? Read <a href="/compare/google-maps-timeline/">Whereday and Google Timeline</a>.</p>
<p>Version 1.8 previews and skips approximate duplicate visits and points. In the historical 1.7.16 release-source example, post-import visit reconciliation combined two nearby visits into one, while importing the point file again added repeated points. The import result is not necessarily the final stored visit count. See the <a href="/blog/back-up-iphone-location-history/#checked-import">checked disk import and JSON re-export</a>. Inspect the current preview and individual records before retrying; the historical 1.7.16 counts do not describe the newer duplicate-skipping importer. The <a href="/blog/back-up-iphone-location-history/#sample">synthetic JSON examples</a> show the visit and point structures without using private history.</p>
<p>For a readable note in a desktop vault, follow the <a href="/blog/iphone-location-journal-obsidian/">Markdown-to-Obsidian workflow</a>. It covers manual file placement and keeps generated notes separate from personal writing.</p>

"""

BODIES["automatic-exports"] = """
<div class="callout pro"><p><strong>Optional scheduled exports.</strong> Auto Export appears in the Export tab after you choose a default export folder and have export access. From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement.</p></div>

<h2 id="setup">Set up one file you can check</h2>
<ol>
<li>In Export, choose a default folder that you can find again in Files. Confirm the folder is available before enabling a schedule.</li>
<li>In Auto Export, choose the data type and format. Visits describe stops; Points describe the recorded path. A file of one type does not preserve every other type.</li>
<li>Choose Once daily or an interval of 1–23 hours, plus a start time. Interval schedules also offer Rewrite or Append.</li>
<li>Use <strong>Run now</strong>, then find and open the actual file. Check the date, record type, and some expected records. A last-run time alone does not show that the file contains what you need.</li>
<li>Enable the schedule and check a later file yourself. Notification and background permissions can affect delivery opportunities; a successful manual run does not verify an unattended run.</li>
</ol>
<p>For a restorable archive, first make separate full-history Visits and Points JSON copies using the <a href="/blog/back-up-iphone-location-history/">backup guide</a>. Auto Export starts with the current day; it does not backfill all earlier history. For a readable note, follow the <a href="/blog/iphone-location-journal-obsidian/">manual Obsidian handoff</a>. A folder selection alone does not establish delivery into an Obsidian vault.</p>

<h2 id="day-window">Which day is saved?</h2>
<p>Every run selects <strong>the current local day up to the run time</strong>. Its date range is separate from the manual Export tab’s chosen dates and daily splitting preference. Repeated runs use a stable path for that date; the next local date gets its own path.</p>
<p>If an overdue run happens after midnight, it selects the new day. It does not reopen yesterday’s file and fill its missing final hours. Use a separate manual export for an earlier date that you need to recover from retained journal records.</p>
<p>A once-daily run at 21:00 saves the day so far at that time. Records captured after it need another run that day or a later manual export. Choose the schedule with that cutoff in mind rather than treating one dated file as proof of a complete calendar day.</p>

<h2 id="schedules">How interval times work</h2>
<p>Once daily uses your chosen local time, initially 21:00. An interval schedule starts from its daily anchor and includes occurrences less than 24 hours after that anchor, including times past midnight. For example, 09:00 every nine hours gives 09:00, 18:00, then 03:00 the next morning; the new cycle starts at 09:00. It is not an endless nine-hour sequence across every day.</p>
<p>These are scheduled opportunities. iOS controls actual background execution. Apple explains that a background task’s requested earliest start is <a href="https://developer.apple.com/documentation/backgroundtasks/bgtaskrequest/earliestbegindate">not a guaranteed launch time</a>.</p>

<h2 id="update-mode">Choose Rewrite or Append</h2>
<table>
<caption>How a later run changes the current day’s file</caption>
<tr><th scope="col">Mode</th><th scope="col">What it does</th><th scope="col">What to check</th></tr>
<tr><th scope="row">Rewrite</th><td>Replaces the day’s file with the selected records from local midnight through now. Once-daily runs always use this mode.</td><td>Reflects current record values. Keep personal writing in a separate file because generated output can be replaced.</td></tr>
<tr><th scope="row">Append</th><td>For interval schedules, selects records from the last successful append cursor through now, clamped to the current day.</td><td>It is not a general duplicate or edit sync. A point at the boundary can repeat in CSV; a visit that arrived earlier can retain its old exported fields.</td></tr>
</table>
<p>Append is available for CSV, JSON, Markdown, OwnTracks, Overland, and GeoJSON. Their merge rules differ. GPX and KML always rewrite the full day, even when an interval schedule has Append selected. Changing formats or modes warrants checking the next file rather than assuming its contents match an earlier format.</p>

<h2 id="checked-files">Checked file behavior in release source</h2>
<p>We tested the 1.7.16 (46) automatic-export planner, serializers, and file writer with invented records in a temporary folder on an iOS 26.5 simulator. These are file tests, not a timed background delivery test.</p>
<table>
<caption>What the native checks found</caption>
<tr><th scope="col">Example</th><th scope="col">Saved result</th></tr>
<tr><th scope="row">Two points at 10:00 and 11:00, then another run through 12:00 with a new point</th><td>CSV append saved four rows: the 11:00 point appeared twice. JSON append saved three points in this example.</td></tr>
<tr><th scope="row">A 10:00 visit was exported open, then its departure was set to 12:00</th><td>The next JSON append still had no departure. Rewriting the same day’s file included the 12:00 departure.</td></tr>
<tr><th scope="row">A run at 23:55, another point at 23:59, then a run at 00:10</th><td>Yesterday’s file stayed unchanged and did not gain the 23:59 point. Today’s file included only today’s point.</td></tr>
</table>
<p>Choose Rewrite when you need the current values of today’s visits in each snapshot. For important earlier dates, use a manual date-range export and check it separately. The <a href="/blog/back-up-iphone-location-history/#checked-import">checked restore example</a> explains a different issue: importing files can reconcile nearby visits and add repeated points.</p>
<p class="source-note">Checked October 26, 2026 against unchanged 1.7.16 release-source planning and file-writing code. All 27 selected native tests passed. No scheduled wake, notification delivery, folder-picker access, cloud synchronization, or unattended export was newly verified.</p>

<h2 id="late-file">If a scheduled file is missing or incomplete</h2>
<ol>
<li>Open Whereday and inspect Auto Export’s last run and any error. Check that the schedule and selected data type are still what you intended.</li>
<li>Check the chosen folder in Files. Confirm you are looking at the intended local date and file type, then open the file itself.</li>
<li>Use Run now while the app is open, then inspect the result. A folder that has become unavailable needs attention before another scheduled attempt can succeed.</li>
<li>If yesterday’s file is incomplete, make a separate manual export for yesterday. A run today does not repair it.</li>
<li>If the file lacks records visible in the journal, compare the record type and date selection. Follow the <a href="/blog/missing-location-history-iphone/#export">export diagnosis</a> before deleting or importing anything.</li>
</ol>
<p>Whereday can request background refresh, register a silent scheduling push, offer a fallback notification, and check for overdue work when opened. These are opportunities to run, not a delivery guarantee. A fallback notification can be tapped to attempt the export; inspect the saved file afterward.</p>
<p>The scheduling service receives routing and timing metadata, including an installation identifier, push token, time zone, and schedule. It does not receive your journal or export contents through this scheduling path. Exported copies follow the storage rules of the folder or service you select. See the <a href="/docs/privacy-data/">privacy reference</a>.</p>

<h2 id="questions">Common questions</h2>
<h3>Does Auto Export make a full backup when I enable it?</h3>
<p>No. It selects the current local day. Make initial full-history visit and point exports separately and keep their originals.</p>
<h3>Does Append keep old exported visits up to date?</h3>
<p>Not reliably. Selection uses record time, and a later departure edit does not make an old arrival new. The checked example above needed Rewrite to refresh its departure.</p>
<h3>Can I keep my own notes inside an automatically generated file?</h3>
<p>Use a separate note unless you have checked the specific format’s merge behavior. Rewrite replaces the generated file, and different append formats handle existing content differently.</p>
"""

BODIES["webhooks"] = """
<div class="callout pro"><p><strong>Export access, off by default.</strong> New users from 1.8 retain export access after tracking expires; existing free users keep their previous export-unlock requirement. Webhooks can send your location data off your device — to a server <em>you</em> choose. Nothing is sent until you configure an endpoint, and the settings screen leads with a prominent warning card.</p></div>

<h2>What it's for</h2>
<p>Webhooks deliver records to a receiver you choose, but the payload must match that receiver's API. An endpoint accepting HTTP POST is not enough to establish compatibility. Verify a dedicated test destination before enabling delivery of your history.</p>
<div role="region" aria-label="Receiver endpoints and formats" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<tr><th>Service</th><th>Endpoint shape</th><th>Format</th></tr>
<tr><td>Dawarich batch receiver</td><td><code>…/api/v1/overland/batches</code></td><td>Overland; verify against your instance before use</td></tr>
<tr><td>OwnTracks receiver</td><td>per your installation</td><td>Verify it accepts arrays of location messages</td></tr>
<tr><td>Other receiver</td><td>its documented HTTPS endpoint</td><td>Use its required schema and authentication</td></tr>
</table>
</div>

<p>Whereday encodes OwnTracks points as an array, including a single point. Dawarich's current OwnTracks receiver expects individual point fields, so do not assume those two shapes are interchangeable. Its Overland batch receiver is a different protocol. Check <a href="https://dawarich.app/docs/getting-started/track-your-location/">Dawarich's receiver documentation</a> and confirm actual imported points; the <a href="/integrations/dawarich/">checked Overland walkthrough</a> records native batch delivery and receiver persistence on Dawarich 1.18.0. Physical recording and unattended delivery remain separate checks.</p>

<h2>Configuration</h2>
<ul>
<li><strong>Endpoint</strong> — the URL to POST to.</li>
<li><strong>Format</strong> — JSON, CSV, Markdown, GeoJSON, GPX, KML, OwnTracks, or Overland.</li>
<li><strong>Authentication</strong> — none, API key in the query string, Bearer token, Basic auth, or a custom header. Credentials live in the iOS <strong>Keychain</strong>, never in plain preferences, and error messages mask them.</li>
</ul>

<h2>Send modes</h2>
<table>
<tr><th>Mode</th><th>Behavior</th></tr>
<tr><td><strong>Real-time</strong> (default)</td><td>Each newly recorded point is sent as it's saved. On launch the sender “arms” itself first, so enabling webhooks never bulk-dumps your history.</td></tr>
<tr><td><strong>By count</strong></td><td>Points queue and flush every 5 / 10 / 25 / 50 / 100 points.</td></tr>
<tr><td><strong>By time</strong></td><td>Queued points flush every 1–60 minutes.</td></tr>
<tr><td><strong>Manual</strong></td><td>Nothing sends automatically; <strong>Send now</strong> posts your entire saved history (visits and points).</td></tr>
</table>
<p>Batch modes show a live queue count with a <strong>Flush queue</strong> button. Failed sends retry automatically with backoff on network errors and server hiccups (5xx, timeouts, rate limits); authentication failures don't retry — they surface as an error. <strong>Test connection</strong> POSTs a synthetic point at latitude 0, longitude 0, with a current timestamp. A receiver can store it. A successful HTTP response confirms acceptance of that request, not that a real route was correctly imported.</p>

<div class="callout warn"><p><strong>Know your destination.</strong> This is the one feature that transmits your location data off-device. Use endpoints you trust (ideally your own servers), and remember queued-but-unsent points in batch modes are lost if iOS terminates the app before a flush.</p></div>
"""

BODIES["siri-shortcuts"] = """
<h2>Find Whereday actions in Shortcuts</h2>
<p>Open Apple’s Shortcuts app, create a shortcut, and search for Whereday when adding an action. The verified Whereday 1.7.16 release defines twelve actions. Nine have built-in Siri phrases; the three additional read actions can be added to a custom shortcut. Use the action names below when an older recipe shows different labels.</p>
<p>Set up location permission in Whereday before using <strong>Start Tracking</strong>. These actions are designed to run without opening Whereday, but iOS can still require interaction or permission. Test a shortcut manually before relying on an automation.</p>

<h2>Recording and today’s stats</h2>
<div role="region" aria-label="Released Siri and Shortcuts actions" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<caption>Recording controls and snapshot values</caption>
<tr><th scope="col">Action</th><th scope="col">What it does</th><th scope="col">Built-in Siri phrase example</th></tr>
<tr><th scope="row">Start Tracking</th><td>Starts recording with the app’s location permission.</td><td>“Start Whereday”</td></tr>
<tr><th scope="row">Stop Tracking</th><td>Stops recording. If Whereday is not running, closes the saved active session.</td><td>“Stop tracking with Whereday”</td></tr>
<tr><th scope="row">Rename Current Outing</th><td>Names the active outing. Requires a nonempty Name and an active outing.</td><td>“Name my Whereday outing”</td></tr>
<tr><th scope="row">Today’s Distance</th><td>Returns today’s distance from the latest shared app snapshot.</td><td>“How far have I gone with Whereday”</td></tr>
<tr><th scope="row">Today’s Tracking Duration</th><td>Returns elapsed time for the current active recording, or zero when stopped. It is not a sum of every session today.</td><td>“Whereday tracking duration”</td></tr>
<tr><th scope="row">Today’s Visits</th><td>Returns today’s visit count from the latest shared app snapshot.</td><td>“How many visits today in Whereday”</td></tr>
</table>
</div>

<h2>Read and rename saved records</h2>
<div role="region" aria-label="Saved journal Shortcuts actions" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<caption>Journal actions for a custom shortcut</caption>
<tr><th scope="col">Action</th><th scope="col">Inputs and result</th><th scope="col">Siri setup</th></tr>
<tr><th scope="row">Get Current Outing Name</th><td>Returns the active outing’s name, or its start date if unnamed. Reports an error if there is no active outing.</td><td>Add to a custom shortcut.</td></tr>
<tr><th scope="row">Get Visits</th><td>Optional Start Date and End Date. Returns visits whose arrival falls in the range.</td><td>Add to a custom shortcut.</td></tr>
<tr><th scope="row">Get Movements</th><td>Optional Start Date and End Date. Returns recorded outings whose start falls in the range; “Movement” is the action’s label for an outing.</td><td>Add to a custom shortcut.</td></tr>
<tr><th scope="row">Rename Visit</th><td>A saved Visit and a nonempty Name. Returns the renamed visit.</td><td>“Rename a visit in Whereday”</td></tr>
<tr><th scope="row">Rename Movement</th><td>A saved Movement and a nonempty Name. Returns the renamed outing.</td><td>“Rename a movement in Whereday”</td></tr>
</table>
</div>
<p>For a rename workflow, get the visits or movements, choose the intended result, and pass it to the matching rename action. Check the date and item before running: renaming changes the saved journal. These actions do not create missing GPS points or visits.</p>

<h2 id="export-data">Export a chosen date range</h2>
<p><strong>Export Data</strong> is the single configurable export action in this release. It returns a file for the next Shortcuts action. Its built-in Siri phrases include “Export Whereday data” and “Export data from Whereday”.</p>
<table>
<caption>Export Data parameters</caption>
<tr><th scope="col">Parameter</th><th scope="col">Choices or default</th></tr>
<tr><th scope="row">Start Date</th><td>Defaults to the start of today in the device’s calendar.</td></tr>
<tr><th scope="row">End Date</th><td>Defaults to the time the action runs.</td></tr>
<tr><th scope="row">Data Kind</th><td>Visits, Points, Outings, or All Data. Defaults to All Data.</td></tr>
<tr><th scope="row">Format</th><td>JSON, CSV, Markdown, GeoJSON, GPX, KML, OwnTracks, or Overland. Defaults to JSON.</td></tr>
</table>
<p>For yesterday or any past period, set <strong>both</strong> dates. Leaving Start Date empty while setting yesterday’s End Date creates an inverted range and returns an error. An end before the start is invalid. Export formats organize records differently; consult the <a href="/docs/export-import/">export reference</a> before choosing a file for another app.</p>

<h2 id="markdown-recipe">Recipe: save today’s visits as Markdown</h2>
<ol>
<li>Create a shortcut in Apple’s Shortcuts app and add Whereday’s <strong>Export Data</strong> action.</li>
<li>Set <strong>Data Kind</strong> to <strong>Visits</strong> and <strong>Format</strong> to <strong>Markdown</strong>. Leave both date parameters empty for today up to the run time.</li>
<li>Add Apple’s <strong>Save File</strong> action after Export Data, using the returned file as its input. For the first run, choose a test folder you control.</li>
<li>Run the shortcut manually. Confirm the saved file has the expected date heading and visits. A successful action is not proof that you recorded every stop.</li>
<li>For a desktop vault, follow the <a href="/blog/iphone-location-journal-obsidian/">Obsidian file handoff</a>. Keep personal writing separate from generated exports.</li>
</ol>
<p>This recipe follows the released action’s parameters and Apple’s documented file actions. The export logic is covered by native tests; the complete Shortcuts editor, Save File destination, and unattended delivery have not been newly verified here. Apple explains <a href="https://support.apple.com/guide/shortcuts/create-a-custom-shortcut-apd84c576f8c/ios">creating a custom shortcut</a> and <a href="https://support.apple.com/guide/shortcuts/share-actions-apdaf74d75a5/ios">passing files to sharing actions</a>.</p>

<h2>Check a shortcut before scheduling it</h2>
<ul>
<li>If an old shortcut shows <strong>Export Today’s Data</strong>, <strong>Export Today’s Outings</strong>, or <strong>Export Yesterday’s Data</strong>, rebuild that step with <strong>Export Data</strong> and set its dates, data kind, and format explicitly.</li>
<li>If a result is empty, check the date range and whether the app actually recorded the intended data. Read actions match visit arrival or outing start; a record overlapping the period can start outside it.</li>
<li>If a file was returned but is missing from Files, check the following Save File action and destination. Export Data does not itself configure a recurring vault sync.</li>
<li>For Whereday’s own folder scheduler, use the separate <a href="/docs/automatic-exports/">automatic export reference</a>. For missing journal entries, use the <a href="/blog/missing-location-history-iphone/">recording checklist</a>.</li>
</ul>
<p>Reviewed October 26, 2026 against the released Whereday 1.7.16 action definitions. Siri wording and the Shortcuts editor can vary by language and iOS version.</p>
"""

BODIES["apple-watch"] = """
<h2>Record on the Watch, then check the phone copy</h2>
<p>Whereday can record GPS points on Apple Watch without the iPhone nearby. The Watch keeps a local queue for transfer to the companion iPhone. From version 1.8, new users need an eligible trial, subscription, or Lifetime purchase to track. Existing users keep free tracking and prior purchase rights. Supported import and Export Preview are free. New users from 1.8 retain exports after tracking access expires; existing free users keep their previous export-unlock requirement.</p>
<p>Install the Watch app and allow its location request. The phone app is needed to review transferred records in its journal and use its export workflow. Controls can vary by version; if a control described below is missing, check the available app update.</p>

<h2>Before your first route</h2>
<ol>
<li>Open Whereday on the Watch. If it asks for location access, respond to the permission prompt. If access was denied, review the Watch's location settings.</li>
<li>Tap <strong>Start</strong> and confirm the status changes from standby to tracking. After an initial permission request, check the state rather than assuming a recording began.</li>
<li>Try a short route first. Look for the elapsed time and point count to change before relying on a longer session.</li>
<li>When finished, tap <strong>Stop</strong> on the Watch. Confirm it has left the tracking state.</li>
</ol>
<p>Recording away from the phone is separate from syncing to it. You do not need an active phone connection for the Watch to collect points, but the phone must be available later for transfer. GPS conditions, permission, battery, and watchOS behavior can affect the recording.</p>

<h2>Sync and verify the result</h2>
<ol>
<li>Bring the paired Watch and iPhone together and open Whereday on the iPhone.</li>
<li>Open Whereday on the Watch. If available in your version, tap <strong>Sync Now</strong> and read the status.</li>
<li>In the phone app, open Timeline for the day of the recording. Check the outing and use its map to inspect the expected points and times.</li>
<li>Check both route points and visits. A transferred route is not proof that every stop was detected or named correctly.</li>
</ol>
<table>
<caption>Read the sync state before retrying</caption>
<tr><th scope="col">Watch status</th><th scope="col">What to do next</th></tr>
<tr><th scope="row">Connecting to iPhone</th><td>Keep the paired devices available and open the phone app.</td></tr>
<tr><th scope="row">Sync queued</th><td>The request is queued. Check the phone result; this is not confirmation that every record is imported.</td></tr>
<tr><th scope="row">Synced with iPhone</th><td>The phone acknowledged the delivered data. Still check the intended day and route.</td></tr>
<tr><th scope="row">Nothing to sync</th><td>Check the phone's existing history and the selected day. An empty pending queue is not a full history report.</td></tr>
</table>
<p>Apple notes that <a href="https://developer.apple.com/documentation/watchconnectivity/wcsession">background transfers may not arrive immediately</a>. Check the saved phone records as well as the transfer status.</p>
<p>Completed transfers are acknowledged by the phone; points remain in the Watch queue until acknowledged. This helps retry delivery, but the queue is not a full backup of history already transferred. Do not uninstall the Watch app as a first troubleshooting step.</p>

<h2 id="checked-transfer">Checked queue example: 503 points, two batches</h2>
<p>We replayed an invented Watch queue through the queue logic and iPhone receiver included in Whereday 1.7.16 (46). The example contains 503 points, two completed visits, and one completed outing. Queue files and a separate phone database were saved to disk and reopened between deliveries.</p>
<div role="region" aria-label="Checked Watch queue and phone counts" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<caption>Queued points and saved phone points are different counts</caption>
<tr><th scope="col">Stage</th><th scope="col">Points still queued</th><th scope="col">Points saved on phone</th></tr>
<tr><th scope="row">Before delivery</th><td>503</td><td>0</td></tr>
<tr><th scope="row">First 500 saved, acknowledgement withheld</th><td>503</td><td>500</td></tr>
<tr><th scope="row">Same batch retried and acknowledged</th><td>3</td><td>500</td></tr>
<tr><th scope="row">Final three saved and acknowledged</th><td>0</td><td>503</td></tr>
<tr><th scope="row">Queue and phone store reopened</th><td>0</td><td>503</td></tr>
</table>
</div>
<p>The retry added zero points because that batch’s record IDs were already saved. The completed outing stayed queued until its final point batch was acknowledged. At the end, the phone retained all 503 point IDs and timestamps, two visits, and one outing. A delayed delivery of the first batch still left the phone at 503 points.</p>
<p>This explains why a pending queue can contain points already saved on the phone: the Watch may still be waiting for an acknowledgement. Conversely, an empty queue says nothing about whether you selected the right date or whether a route was recorded before transfer.</p>
<p><strong>What was checked:</strong> released queue batching, acknowledgements, receiver imports, and reopening temporary disk stores in an iOS 26.5 simulator. All 15 selected native tests passed on October 26, 2026. Delivery was supplied by the test with WatchConnectivity disabled. No physical Watch recording, Bluetooth or Wi-Fi delivery, background transfer, app-process restart, or Watch sync screen was newly verified.</p>

<h2>Export the phone copy as GPX</h2>
<p>Once the recording is saved on the iPhone, follow the <a href="/blog/record-iphone-route-gpx/#watch-to-gpx">Apple Watch to GPX walkthrough</a>. It covers selecting points or an outing, checking the saved file, and comparing a partial 500-point transfer with the complete 503-point synthetic example. A GPX that opens successfully can still be missing the last batch.</p>

<h2>If the route is missing or incomplete</h2>
<ul>
<li><strong>Nothing recorded:</strong> check the Watch's location permission, tracking state, and whether the point count advanced during the session.</li>
<li><strong>Watch has data, phone looks empty:</strong> check the recording date, open both apps, and read the Watch sync status before starting another route.</li>
<li><strong>Points arrived but visits differ:</strong> Watch visits are derived from recorded movement and are not identical to the iPhone's automatic visit detection. Check the route itself and correct places where needed.</li>
<li><strong>Older app version:</strong> an update can change sync behavior, but cannot recreate every record that is no longer retained. Keep existing data while diagnosing a gap.</li>
</ul>
<p>If a retry does not resolve it, contact support with the app versions, recording date, and exact sync message. You can describe the missing period without sending your private route.</p>

<h2>Daily counters and complications</h2>
<p>The Watch shows tracking state, elapsed time, coordinates, distance, points, and a movement-based visit count. Its visit counter is not a count of named places confirmed by the iPhone. <strong>Reset today</strong> resets the Watch's daily counters; it does not restore missing records or delete the iPhone's imported history.</p>
<p>Circular, rectangular, inline, and corner complications offer glanceable status. A complication is a snapshot, so open the Watch app to inspect the current recording and sync state.</p>

<h2 id="verify-phone">Check the phone copy before removing anything</h2>
<ul>
<li>Open the recording’s actual date in Timeline, not only Today. Check the outing’s start and end time.</li>
<li>Inspect the outing’s point count and map. A saved session title or timer alone does not prove that GPS points were collected.</li>
<li>Check the expected visits separately from the path. Watch movement-based visits can differ from iPhone visit detection.</li>
<li>Keep the Watch app and its pending data while resolving a transfer problem. Once the phone copy is checked, use supported exports if you need a separate archive.</li>
</ul>

<h2 id="questions">Common questions</h2>
<h3>Does Sync queued mean my route is missing from the phone?</h3>
<p>Not necessarily. In the checked example, 500 points were already saved while all 503 still remained queued because the acknowledgement had not been applied. Inspect the phone’s intended date before deciding what is missing.</p>
<h3>Will retrying add the same points twice?</h3>
<p>The checked replay of the same Watch batch added zero duplicate points, including after the phone store was reopened. This depends on those same record IDs. A repeated file import is a separate workflow. The <a href="/blog/back-up-iphone-location-history/#checked-import">historical backup import check in 1.7.16</a> added repeated points. Version 1.8 adds duplicate preview and skipping for supported imports; see the <a href="/blog/import-google-timeline-iphone/">current import walkthrough</a>.</p>
<h3>Does Nothing to sync prove I have a complete backup?</h3>
<p>No. It describes pending transfer data. It does not recreate unrecorded movement, establish the completeness of older phone history, or provide a separate archive. Verify the phone copy and retain supported exports for records you need to keep.</p>

<h2>Keep a portable copy after syncing</h2>
<p>Once the route is visible on the phone, follow <a href="/blog/record-iphone-route-gpx/">the GPX walkthrough</a> to open it in another route tool, or <a href="/blog/back-up-iphone-location-history/">back up supported visits and points</a> for later import. See <a href="/docs/timeline-outings/">Timeline and outings</a> for reviewing the transferred day.</p>
<p>If a recorded day looks incomplete, follow the <a href="/blog/missing-location-history-iphone/">missing visits and route points checklist</a> before deleting records or retrying an import.</p>

"""

# Country Days is present in verified public 1.7.16 build 46.
BODIES["timeline-outings"] += """
<h2>Country Days</h2>
<p>Timeline → <strong>Country Days</strong> counts local dates represented by visits, route points, and manual stays. Review this year, the last 365 days, or all time. A travel date can count in more than one country; gaps are not filled automatically. Viewing and manual stays are free; writing the country-date CSV follows the <a href="/#export-access">new-user and existing-user export rules</a>.</p>
<p>See <a href="/blog/track-days-in-country-iphone/">how to track days spent in each country</a> for timezone, network-geocoding, and counting details, including a <a href="/blog/track-days-in-country-iphone/#checked-example">checked four-row CSV example</a>.</p>
"""

BODIES["privacy-data"] = """
<h2>The short version</h2>
<p>Your location history — visits, routes, outings, saved places, photo matches — is stored in the app's on-device database and <strong>nowhere else</strong>. There is no Whereday account, no cloud sync, and no Whereday server holding your location data. The full legal text is the <a href="/privacy">Privacy Policy</a>; this guide is the plain-language version.</p>

<h2>Everything that can leave your device</h2>
<div role="region" aria-label="Data sharing and privacy controls" tabindex="0" style="overflow-x:auto;max-width:100%">
<table>
<caption>Data sharing and privacy controls</caption>
<tr><th scope="col">Where</th><th scope="col">What's sent</th><th scope="col">When</th><th scope="col">Off switch</th></tr>
<tr><td><strong>Apple geocoding</strong></td><td>Coordinates of a visit (to fetch its name/address)</td><td>When naming visits</td><td>Settings → Location names</td></tr>
<tr><td><strong>Apple Maps</strong></td><td>Search terms / route segment endpoints (place search, suggestions, road-matched lines, lock-screen map images)</td><td>When you use those features</td><td>Features are opt-in per use; road-matching toggle in map filters</td></tr>
<tr><td><strong>Earlier-version Whereday analytics</strong></td><td>Onboarding steps, permission choices, purchase outcomes, app version, and an installation-linked random ID — <strong>never</strong> coordinates, visits, routes, or anything you type</td><td>On by default in earlier versions; disabled in production version 1.8</td><td>Updating to 1.8 stops sending and discards unsent events; it does not erase received records</td></tr>
<tr><td><strong>Export scheduler</strong></td><td>Time zone, schedule times, push token, separate installation ID — <strong>timing/routing metadata only</strong>, no location or file contents</td><td>Only if you enable Auto Export</td><td>Turn Auto Export off to remove the active schedule after a successful sync; device registrations may remain</td></tr>
<tr><td><strong>Earlier-version myself.md purchase claims</strong></td><td>Signed purchase proof for eligibility; retained purchase reference and account linkage after redemption</td><td>Only after choosing to verify an eligible purchase; disabled in version 1.8</td><td>Do not initiate a claim; account deletion and limited retained purchase-protection records follow the <a href="/privacy">Privacy Policy</a></td></tr>
<tr><td><strong>Your webhook</strong></td><td>Location points and visits, in your chosen format</td><td>Only after you configure an endpoint</td><td>Disable webhooks (off by default)</td></tr>
</table>
</div>

<h2>Photos</h2>
<p>The photo feature reads your library on-device to match pictures to places. Photo files are never copied or uploaded; Whereday keeps only local identifiers and match metadata, and respects limited-access mode.</p>

<h2>Deleting your data</h2>
<ul>
<li><strong>Everything, in-app:</strong> Settings → Data → <strong>Clear all data</strong> permanently removes every visit, point, outing, photo match, and saved place.</li>
<li><strong>Uninstalling:</strong> removes the app's local journal, subject to backups/restoration. It does not delete server records or exported copies, and a Keychain-backed scheduling ID may remain. See the <a href="/privacy">Privacy Policy</a> for technical-record retention and privacy requests.</li>
<li><strong>Single items:</strong> delete any visit or outing from its detail page.</li>
</ul>

<h2>Backups are your job (by design)</h2>
<p>Because nothing is synced to a cloud, there is no server copy to fall back on. Export regularly — supported Whereday JSON files can restore visit and point records, and Auto Export can do it for you every day. See <a href="/docs/export-import">Export &amp; import</a>.</p>

<h2>Permissions Whereday requests</h2>
<table>
<tr><th>Permission</th><th>Why</th></tr>
<tr><td><strong>Location — Always</strong></td><td>Automatic visit detection in the background (While-Using still works for manual sessions).</td></tr>
<tr><td><strong>Photos</strong> (optional)</td><td>Matching your pictures to places; never modifies your library.</td></tr>
<tr><td><strong>Notifications</strong> (optional)</td><td>Auto Export status and retry notices.</td></tr>
</table>
"""

SHELL = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>__SEO_TITLE__ — Whereday Docs</title>
  <meta name="description" content="__DESC__">
  <meta name="robots" content="index,follow">
  <link rel="canonical" href="__CANONICAL__">
  <link rel="icon" href="/favicon.ico" sizes="any">
  <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
  <link rel="icon" type="image/png" sizes="16x16" href="/favicon-16x16.png">
  <link rel="icon" type="image/png" sizes="192x192" href="/favicon-192x192.png">
  <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
  <style>__DOCS_CSS__</style>
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
  <nav class="top" aria-label="Primary navigation">
    <div class="nav-inner">
      <a href="/" class="brand" aria-label="Whereday home">
        <img src="/app-icon-28.webp" srcset="/app-icon-28.webp 1x, /app-icon-84.webp 3x" alt="" width="26" height="26">
        <span class="brand-dot" aria-hidden="true"></span>
        WHEREDAY
      </a>
      <div class="nav-links">
        <a href="/#features">Features</a>
        <a href="/#pricing">Pricing</a>
        <a href="/docs/" aria-current="page">Docs</a>
        <a href="/terms">Terms</a>
        <a href="https://apps.apple.com/app/id6761960794" class="btn">Download</a>
      </div>
    </div>
  </nav>

<main id="main">
__BODY__
  </main>

  <footer>
    <div class="footer-inner">
      <div class="footer-brand">
        <span class="dot"></span>
        © 2026 WHEREDAY · BUILT WITH CARE
      </div>
      <div class="footer-links">
        <a href="/about">About</a>
        <a href="/contact">Contact</a>
        <a href="/">Home</a>
        <a href="/docs/">Docs</a>
        <a href="/privacy">Privacy</a>
        <a href="/terms">Terms</a>
      </div>
    </div>
  </footer>
<!-- Cloudflare Web Analytics -->
<script>
(function () {
  var productionHosts = {
    'instareply.isolated.tech': true,
    'voxboard.isolated.tech': true,
    'healthmd.isolated.tech': true,
    'whereday.com': true,
    'gitsyncmd.isolated.tech': true,
    'timemd.isolated.tech': true,
    'imghost.isolated.tech': true
  };

  if (!productionHosts[window.location.hostname]) {
    return;
  }

  function loadCloudflareBeacon() {
    if (document.querySelector('script[src="https://static.cloudflareinsights.com/beacon.min.js"]')) {
      return;
    }

    var script = document.createElement('script');
    script.defer = true;
    script.src = 'https://static.cloudflareinsights.com/beacon.min.js';
    script.setAttribute('data-cf-beacon', '{"token":"090f363070334cddad5c5cc1509b8807"}');
    document.head.appendChild(script);
  }

  fetch('https://img-host.costream.workers.dev/analytics-gate', {
    cache: 'no-store',
    mode: 'cors',
    credentials: 'omit'
  })
    .then(function (response) {
      return response.ok ? response.json() : { track: false };
    })
    .then(function (gate) {
      if (gate && gate.track) {
        loadCloudflareBeacon();
      }
    })
    .catch(function () {
      // Fail closed so excluded IPs are not accidentally tracked.
    });
})();
</script>
<!-- End Cloudflare Web Analytics -->
</body>
</html>
"""

SHELL = SHELL.replace("__DOCS_CSS__", (OUT / "docs.css").read_text())

GUIDE_PAGE = """
  <header class="doc-head">
    <span class="eyebrow">§ Docs · Guide __NUM__ of __TOTAL__</span>
    <h1>__TITLE__</h1>
    <p class="lede">__LEDE__</p>
    <div class="meta-strip">
      <div><div class="k">Platform</div><div class="v">__PLATFORM__</div></div>
      <div><div class="k">Updated</div><div class="v">__UPDATED__</div></div>
      <div><div class="k">Reading</div><div class="v">__MINUTES__ min</div></div>
      <div><div class="k">Back to</div><div class="v"><a href="/docs/">All docs</a></div></div>
    </div>
  </header>

  <div class="content">
    <div class="doc-card">
__CONTENT__
    </div>
    <nav class="pager" aria-label="Guides">
__PREV__
__NEXT__
    </nav>
  </div>
"""

INDEX_PAGE = """
  <header class="doc-head">
    <span class="eyebrow">§ Docs · Whereday handbook</span>
    <h1>Documentation</h1>
    <p class="lede">Practical guides for every corner of Whereday — tracking, timelines, exports, automation, the Watch app, and privacy. Written against the shipping app, updated with it.</p>
    <div class="meta-strip">
      <div><div class="k">Guides</div><div class="v">__TOTAL__</div></div>
      <div><div class="k">Updated</div><div class="v">__UPDATED__</div></div>
      <div><div class="k">Covers</div><div class="v">Whereday 1.7 · iOS 17+ · watchOS 10+</div></div>
      <div><div class="k">Questions</div><div class="v"><a href="mailto:cody.bontecou@gmail.com">Email us</a></div></div>
    </div>
  </header>

  <div class="content">
    <div class="guides">
__CARDS__
    </div>
  </div>
"""


def pager_link(direction, guide):
    if guide is None:
        return ""
    title = html.escape(guide[1])
    if direction == "prev":
        return (f'      <a class="prev" href="/docs/{guide[0]}/">'
                f'<span class="dir">← Previous</span><span class="title">{title}</span></a>')
    return (f'      <a class="next" href="/docs/{guide[0]}/">'
            f'<span class="dir">Next →</span><span class="title">{title}</span></a>')


def build():
    OUT.mkdir(parents=True, exist_ok=True)
    total = len(GUIDES)

    seo_titles = {
        "apple-watch": "Record Apple Watch GPS without your iPhone",
        "automatic-exports": "Auto-export iPhone location history",
        "places": "Save and edit places on iPhone",
        "siri-shortcuts": "Siri & Shortcuts for location tracking",
    }
    # Guide pages
    for i, (slug, title, short, lede, platform, minutes, tag) in enumerate(GUIDES):
        prev_g = GUIDES[i - 1] if i > 0 else None
        next_g = GUIDES[i + 1] if i < total - 1 else None
        body = GUIDE_PAGE
        body = body.replace("__NUM__", str(i + 1)).replace("__TOTAL__", str(total))
        body = body.replace("__TITLE__", html.escape(title))
        body = body.replace("__LEDE__", html.escape(lede))
        body = body.replace("__PLATFORM__", html.escape(platform))
        body = body.replace("__UPDATED__", "October 30, 2026" if slug in {"places", "automatic-exports", "apple-watch", "export-import", "webhooks", "timeline-outings"} else UPDATED if slug in REVIEWED else "August 22, 2026")
        body = body.replace("__MINUTES__", str(minutes))
        body = body.replace("__CONTENT__", BODIES[slug].strip())
        body = body.replace("__PREV__", pager_link("prev", prev_g))
        body = body.replace("__NEXT__", pager_link("next", next_g))

        page = (SHELL
                .replace("__TITLE__", html.escape(title))
                .replace("__DESC__", html.escape(short))
                .replace("__SEO_TITLE__", html.escape(seo_titles.get(slug, title)))
                .replace("__CANONICAL__", BASE + slug + "/")
                .replace("__BODY__", body))
        (OUT / slug).mkdir(exist_ok=True)
        (OUT / slug / "index.html").write_text(page)
        print(f"wrote docs/{slug}/index.html")

    # Index
    cards = []
    for i, (slug, title, short, lede, platform, minutes, tag) in enumerate(GUIDES):
        tag_html = f'<span class="tag{" pro" if tag == "PRO" else ""}">{"Pro" if tag == "PRO" else "Guide"}</span>'
        cards.append(
            f'      <a class="guide-card" href="/docs/{slug}/">\n'
            f'        {tag_html}\n'
            f'        <span class="num">{"%02d" % (i + 1)}</span>\n'
            f'        <h2>{html.escape(title)}</h2>\n'
            f'        <p>{html.escape(short)}</p>\n'
            f'      </a>')
    index = (SHELL
             .replace("__TITLE__", "Documentation")
             .replace("__SEO_TITLE__", "Documentation")
             .replace("__DESC__", "Practical guides for every corner of Whereday: tracking, timelines, exports, automation, the Apple Watch app, and privacy.")
             .replace("__CANONICAL__", BASE)
             .replace("__BODY__",
                      INDEX_PAGE
                      .replace("__TOTAL__", str(total))
                      .replace("__UPDATED__", UPDATED)
                      .replace("__CARDS__", "\n".join(cards))))
    (OUT / "index.html").write_text(index)
    print("wrote docs/index.html")

    print(f"done: {total} guides + index")


if __name__ == "__main__":
    build()
