#!/usr/bin/env python3
"""Render articles and comparisons as static, crawlable HTML."""
from html import escape
from datetime import date
import json
from pathlib import Path

MARKETING = Path(__file__).resolve().parent.parent
PUBLIC = MARKETING / "public"
BASE = "https://whereday.com"
APP = "https://apps.apple.com/app/id6761960794"
REVIEWED = "2026-10-26"
PAGES = [
    {
        "path": "/integrations/",
        "section": "Integrations", "section_path": "/integrations/",
        "title": "Use Your Whereday Location History in Other Tools",
        "headline": "Use your location records in another tool",
        "description": "Choose a checked workflow for Whereday records: Markdown notes in Obsidian or optional Overland webhook batches to a Dawarich receiver.",
        "lede": "Choose where your records go, check the result, and keep the original journal. File handoff and live delivery have different requirements.",
        "source": "integrations.html",
    },
    {
        "path": "/integrations/dawarich/",
        "section": "Integrations", "section_path": "/integrations/", "breadcrumb": "Dawarich",
        "type": "BlogPosting",
        "title": "Send iPhone Location Points to Dawarich — Whereday",
        "headline": "Send Whereday location points to Dawarich",
        "description": "Configure Whereday’s Overland webhook for Dawarich, check authentication and saved points, and understand why Test Connection does not prove storage.",
        "lede": "Pair the Overland payload with Dawarich’s batch endpoint, then verify the points at the destination. Webhooks are optional and off by default; access follows Whereday’s export rules for new and existing users.",
        "image": "/screenshots/dawarich-synthetic-points.png",
        "image_alt": "Dawarich 1.18.0 displaying three invented points sent by Whereday’s native webhook code",
        "source": "dawarich.html",
    },
    {
        "path": "/blog/track-days-in-country-iphone/",
        "section": "Guides", "section_path": "/blog/", "breadcrumb": "Country days",
        "type": "BlogPosting",
        "title": "Track Days Spent in Each Country on iPhone — Whereday",
        "headline": "How to track days spent in each country on iPhone",
        "description": "Track country dates from visits, GPS points, and manual stays in Whereday. Inspect a checked CSV example for travel days, timezones, overlaps, and gaps.",
        "lede": "Review country totals and the dates behind them, add missing stays, and keep a personal record without a Whereday account.",
        "source": "track-days-in-country-iphone.html",
    },
    {
        "path": "/compare/",
        "title": "Compare iPhone Location Journals — Whereday",
        "headline": "Choose how you keep your location history.",
        "description": "Compare Whereday with Google Timeline on iPhone: accounts, recording, backups, exports, and the limits of moving an existing archive.",
        "lede": "Understand the account, recording, backup, and export choices before changing the way you keep your own visits and routes.",
        "content": '<section class="card"><h2><a href="/compare/location-history-apps-iphone/">Location history apps for iPhone</a></h2><p>Apple’s built-in features, Whereday, Arc Timeline 4, Google Maps Timeline, self-hosted Dawarich and family sharing apps, compared on routes, storage, exports and what you can see if you stop paying.</p><a href="/compare/location-history-apps-iphone/">Compare location history apps</a></section><section class="card"><h2><a href="/compare/travel-journal-apps-iphone/">Travel journal apps for iPhone</a></h2><p>Polarsteps, FindPenguins, Journi, Day One, Apple Journal and Whereday, compared on automatic recording, where the journal lives, export formats and price.</p><a href="/compare/travel-journal-apps-iphone/">Compare travel journal apps</a></section><section class="card"><h2><a href="/compare/google-maps-timeline/">Whereday and Google Timeline</a></h2><p>A comparison of recording controls, device storage, backup, export formats, and payment. Learn where each fits and why your existing Google archive needs to be kept separately.</p><a href="/compare/google-maps-timeline/">Read the Google Timeline comparison</a></section><section><h2>Moving an existing Google archive?</h2><p>Choose the <a href="/blog/import-google-timeline-iphone/">Google Timeline import walkthrough</a> if you already have a supported JSON file and want its visits and timed points in Whereday. Version 1.8 previews new records, approximate duplicates, and skipped entries before saving. Keep your original archive: importing selected records does not reproduce every Google activity or replace Google’s device-transfer backup.</p><p>For records already in Whereday, use the <a href="/blog/back-up-iphone-location-history/">backup and restore guide</a>. A GPX route for a viewer and a restorable journal copy are different outputs.</p></section><section><h2>New to recording your own history?</h2><p>Start with <a href="/blog/private-location-journal-iphone/">the private location journal guide</a>, or check <a href="/docs/export-import/">which files Whereday can export and restore</a>.</p></section>',
    },
    {
        "path": "/compare/google-maps-timeline/",
        "title": "Google Timeline Alternative for iPhone: Whereday Compared",
        "headline": "Whereday and Google Timeline on iPhone",
        "description": "Compare Whereday and Google Timeline for iPhone: accounts, local history, encrypted backup, GPX and CSV exports, pricing, and import limitations.",
        "lede": "Choose a location journal by the way you record, review, and keep a copy of your own history.",
        "source": "google-maps-timeline.html",
    },
    {"path": "/blog/find-your-travel-history/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Find your travel history", "type": "BlogPosting", "title": "Find Your Travel History: Every Past Trip and Date — Whereday", "headline": "How to find your travel history, every past trip and date", "description": "Where to find past trips and dates: U.S. CBP I-94 travel history, a CBP records request, passports, email bookings and phone records, then merge them into one checked list.", "lede": "Official records and personal records each miss something. Combine them, check the dates, and keep the list going.", "published": "2026-11-22", "modified": "2026-11-22", "source": "find-your-travel-history.html"},
    {"path": "/blog/gpx-to-google-maps/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Open a GPX file in Google Maps", "type": "BlogPosting", "title": "Open a GPX file in Google Maps (via My Maps) — Whereday", "headline": "How to open a GPX file in Google Maps, via My Maps", "description": "Google Maps has no open-GPX button. Import the file into Google My Maps on a computer, then view it in the Google Maps app on iPhone. Limits and fixes included.", "lede": "The documented route, the import limits, what the iPhone app can and cannot do, and what to check when it fails.", "published": "2026-11-22", "modified": "2026-11-22", "source": "gpx-to-google-maps.html"},
    {"path": "/blog/what-is-a-gpx-file/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "What is a GPX file", "type": "BlogPosting", "title": "What Is a GPX File? How to Open One on iPhone — Whereday", "headline": "What is a GPX file, and how do you open one on iPhone?", "description": "A GPX file is a text file of GPS points. See what is inside, how tracks, routes and waypoints differ, and how to open one on iPhone, Mac or Windows.", "lede": "What GPX is, what it holds, which kind you have, and how to open it without uploading it.", "published": "2026-11-22", "modified": "2026-11-22", "source": "what-is-a-gpx-file.html"},
    {
        "path": "/blog/view-location-history-iphone/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "View location history",
        "type": "BlogPosting",
        "title": "How to See Your Location History on iPhone — Whereday",
        "headline": "How to see your location history on iPhone",
        "description": "Find iPhone location history in Apple Maps, Google Timeline or Whereday. Choose a download, check its dates and records, and keep the original before moving.",
        "modified": "2026-11-05",
        "lede": "Find the app and date that hold your saved visits, distinguish iPhone location settings from recorded history, and check existing copies before changing your setup.",
        "source": "view-location-history-iphone.html",
    },
    {
        "path": "/blog/record-iphone-route-gpx/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "Record and export GPX",
        "type": "BlogPosting",
        "title": "How to Record an iPhone Route and Export GPX — Whereday",
        "headline": "How to record an iPhone route and export a GPX file",
        "description": "Record a route in Whereday and export GPX to Files. Includes a checked export-and-viewer example with synthetic points, previews, and free and paid limits.",
        "lede": "Start tracking in Whereday, stop after your trip, then select Points and GPX in Export. Tracking depends on your plan and existing access; Export Preview is free and saving files requires export access.",
        "image": "/screenshots/export.png",
        "image_alt": "Whereday export interface with data types and file format choices",
        "source": "record-iphone-route-gpx.html",
    },
    {
        "path": "/blog/back-up-iphone-location-history/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "Back up and restore history",
        "type": "BlogPosting",
        "title": "Back Up and Restore iPhone Location History — Whereday",
        "headline": "How to back up and restore your Whereday location history",
        "description": "Back up Whereday visits and GPS points as JSON. Check imported records after an app restart and review restore limits before changing iPhones.",
        "lede": "Export Visits and Points separately as Whereday JSON, check the saved files, and keep the originals when importing on another iPhone.",
        "image": "/screenshots/export.png",
        "image_alt": "Whereday export interface with data and file format controls",
        "source": "back-up-iphone-location-history.html",
    },
    {
        "path": "/blog/iphone-location-tracking-battery/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "Location tracking and battery",
        "type": "BlogPosting",
        "title": "iPhone GPS Tracking and Battery Use: Whereday Settings",
        "headline": "How to manage battery use while recording iPhone routes",
        "description": "Check iPhone battery activity, choose Whereday distance filters and auto-stop settings, and compare route detail without assuming a guaranteed battery saving.",
        "lede": "Check whether recording is still running, review iPhone’s battery report, and compare one setting at a time. A larger distance filter still uses high-accuracy GPS.",
        "source": "iphone-location-tracking-battery.html",
    },
    {
        "path": "/blog/missing-location-history-iphone/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "Missing visits and route points",
        "type": "BlogPosting",
        "title": "Missing Visits or GPS Points in Whereday? iPhone Checks",
        "headline": "What to check when visits or route points are missing in Whereday",
        "description": "Check missing Whereday visits, GPS points, Watch sync, and export filters. Includes an empty-outing example and steps that preserve your journal.",
        "lede": "Check the date and recording state, then separate missing points, missing visits, pending Watch transfers, and export filters. Keep existing records while you investigate.",
        "source": "missing-location-history-iphone.html",
    },
    {
        "path": "/blog/iphone-location-journal-obsidian/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "Markdown journal in Obsidian",
        "type": "BlogPosting",
        "title": "Export an iPhone Location Journal to Obsidian — Whereday",
        "headline": "Read your iPhone location journal in Obsidian",
        "description": "Export Whereday visits to Obsidian. Use a free travel journal template, a linked example vault and checked Markdown notes, with timezone and sync limits.",
        "lede": "Export visits as a Markdown file, copy it into your desktop vault, and check the rendered table. Keep your own writing separate from generated location notes.",
        "source": "iphone-location-journal-obsidian.html",
    },
]


# Access/privacy copy reviewed against public version 1.8; examples retain their test dates.
for page in PAGES:
    if page.get("source") in {
        "google-maps-timeline.html", "record-iphone-route-gpx.html",
        "back-up-iphone-location-history.html",
        "iphone-location-tracking-battery.html", "missing-location-history-iphone.html",
        "iphone-location-journal-obsidian.html", "dawarich.html",
        "track-days-in-country-iphone.html",
    }:
        page["modified"] = "2026-10-30"

for page in PAGES:
    if page["path"] == "/compare/":
        page["modified"] = "2026-10-30"

PAGES.append({
    "path": "/blog/import-google-timeline-iphone/", "section": "Guides", "section_path": "/blog/",
    "breadcrumb": "Import Google Timeline", "type": "BlogPosting",
    "title": "Import Google Timeline on iPhone — Whereday",
    "headline": "How to import Google Timeline into Whereday on iPhone",
    "description": "Import Google Timeline JSON into Whereday on iPhone. Preview visits, points, duplicates, and skipped entries. Includes a checked synthetic example.",
    "lede": "Save a Google Timeline JSON to Files, preview it in Whereday, and choose which visits and timed points to add.",
    "published": "2026-10-30", "modified": "2026-10-30",
    "source": "import-google-timeline-iphone.html",
    "image": "/screenshots/google-import-preview-1-8.png",
    "image_alt": "Whereday 1.8 preview of an invented Google Timeline file",
})
PAGES.append({
    "path": "/compare/location-history-apps-iphone/", "section": "Compare", "section_path": "/compare/",
    "breadcrumb": "Location history apps", "type": "BlogPosting",
    "title": "Location History Apps for iPhone Compared — Whereday",
    "headline": "Location history apps for iPhone, compared",
    "description": "Compare iPhone location history apps on routes, export formats and what you can see if you stop paying: Whereday, Arc Timeline 4, Google, Dawarich.",
    "lede": "Choose an app to record your own history from now on, judged on the same criteria. Whereday publishes this page and is one of the apps compared.",
    "published": "2026-11-20", "modified": "2026-11-20",
    "source": "location-history-apps-iphone.html",
    "image": "/screenshots/map-home.png",
    "image_alt": "Whereday’s map home with today’s route coloured by place, using an invented journal",
})
PAGES.append({
    "path": "/compare/travel-journal-apps-iphone/", "section": "Compare", "section_path": "/compare/",
    "breadcrumb": "Travel journal apps", "type": "BlogPosting",
    "title": "Travel Journal Apps for iPhone Compared — Whereday",
    "headline": "Travel journal apps for iPhone, compared",
    "description": "Compare travel journal apps for iPhone on recording, storage, exports and price: Polarsteps, FindPenguins, Journi, Day One, Apple Journal and Whereday.",
    "lede": "Choose a travel journal by the questions that matter: does it record for you, where does it live, can you export it, and what does it cost. Whereday publishes this page and is one of the apps compared.",
    "published": "2026-11-24", "modified": "2026-11-24",
    "source": "travel-journal-apps-iphone.html",
    "image": "/screenshots/map-home.png",
    "image_alt": "Whereday’s map home with today’s route coloured by place, using an invented journal",
})
DRAFTS = []



# Keep next steps specific to the task on each page.
PAGES.extend([
    {"path": "/tools/", "section": "Tools", "section_path": "/tools/", "title": "Free Location File Tools — Whereday", "headline": "Make sense of your location files", "description": "Free browser tools for your location files: map visited countries, convert KML and GPX, view KML and KMZ, merge or inspect GPX, check Google Timeline JSON, or count days in each country. Nothing is uploaded.", "lede": "Practical tools for checking your own files before importing or converting them.", "modified": "2026-11-20", "content": '<section class="card"><h2><a href="/tools/visited-countries-map/">Visited countries map</a></h2><p>Turn a GPX, KML, GeoJSON, Google Timeline JSON or Country Days CSV file into a shaded world map with a count and a per-country list of first and last dates. Add or remove countries by hand, then save the map as SVG or PNG and the table as CSV.</p><p>Borders and coastlines are simplified, so points near them are listed rather than guessed. Your files stay in your browser.</p></section><section class="card"><h2><a href="/tools/merge-gpx/">Merge GPX files</a></h2><p>Combine several GPX files into one, such as a week of daily exports. Keep tracks separate, join them as segments or make one continuous line, drop exact duplicate points, and check the summary and preview before you download.</p><p>Then <a href="/tools/gpx-viewer/">open the result in the GPX viewer</a>. Your files stay in your browser.</p></section><section class="card"><h2><a href="/tools/kml-viewer/">KML and KMZ viewer</a></h2><p>Open a KML or KMZ file and see its placemarks, lines, polygons and timed gx:Track points, plus a list of what the viewer skips. Your file stays in your browser.</p><p>Then <a href="/tools/kml-to-gpx/">convert it to GPX</a> or <a href="/tools/gpx-viewer/">check a GPX file</a>.</p></section><section class="card"><h2><a href="/tools/kml-to-gpx/">KML ⇄ GPX converter</a></h2><p>Convert KML to GPX or GPX to KML, including Google Earth <code>gx:Track</code> timestamps, with a report of what was kept and skipped before you download. Your file stays in your browser.</p><p>Then <a href="/tools/gpx-viewer/">check the result in the GPX viewer</a>.</p></section><section class="card"><h2><a href="/tools/country-days-calculator/">Days-in-country calculator</a></h2><p>Count the days you spent in each country from a Whereday Country Days CSV or stays you type in. Check the Schengen 90/180 rule, any rolling window and calendar-year 183-day totals.</p><p>Expand each count to see its dates. <a href="/blog/track-days-in-country-iphone/">See how Whereday records country days</a>.</p></section><section class="card"><h2><a href="/tools/substantial-presence-test-calculator/">Substantial presence test calculator</a></h2><p>Apply the IRS weighted three-year formula (31 days this year, 183 weighted days) to a Country Days CSV, US stays you type in or yearly counts. See the weight for each year, the total and which dates were counted. This is a count, not tax advice. Your file stays in your browser.</p><p>Then <a href="/tools/country-days-calculator/">count days in other countries</a>.</p></section><section class="card"><h2><a href="/tools/gpx-viewer/">GPX viewer and file inspector</a></h2><p>Plot tracks and routes, check timestamps and invalid coordinates, and save CSV or GeoJSON with documented field limits. Your chosen file stays in your browser.</p><p><a href="/blog/check-gpx-file/">Follow the worked inspection tutorial</a> with an invented five-point example.</p><p>Have a different file type? <a href="/blog/gpx-kml-geojson-location-history/">Compare GPX, KML, GeoJSON and CSV</a> before choosing a destination. The current viewer accepts GPX; renaming a file does not convert it.</p></section><section class="card"><h2><a href="/tools/google-timeline-inspector/">Google Timeline JSON inspector</a></h2><p>Identify supported raw, semantic and device Timeline structures. Check coordinates and times, inspect findings and try invented examples before opening your own archive.</p><p><a href="/blog/google-timeline-json-formats/">Learn which Timeline JSON file you have</a> and why browser counts can differ from a native import preview. Your selected file stays in the tool page.</p></section>'},
    {"path": "/tools/gpx-viewer/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "GPX viewer", "title": "Free GPX Viewer and File Inspector — Whereday", "headline": "See your route. Understand your GPX.", "description": "View GPX files locally, check tracks and timestamps, and convert GPX to CSV or GeoJSON. Keep segments separate without uploading your location file.", "lede": "A free GPX viewer with clear file checks and a local route plot. Your file stays on your device.", "modified": "2026-10-30", "local_file_tool": True, "source": "gpx-viewer.html"},
    {"path": "/blog/check-gpx-file/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Check a GPX file", "type": "BlogPosting", "title": "Check a GPX File Before Importing It — Whereday", "headline": "Check your GPX before you import it", "description": "Inspect GPX points, segments and timestamps locally. Follow a five-point example, understand warnings, and check documented CSV and GeoJSON downloads.", "lede": "Start with a known example, check your file’s actual records, and keep the original before making changes.", "published": "2026-10-30", "modified": "2026-11-01", "source": "check-gpx-file.html"}
])

PAGES.append({
    "path": "/blog/gpx-kml-geojson-location-history/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Choose a file format", "type": "BlogPosting",
    "title": "GPX vs KML vs GeoJSON: Choose a Location File — Whereday",
    "headline": "GPX, KML or GeoJSON: choose a file you can use",
    "description": "Compare GPX, KML, GeoJSON and CSV for location history. Download the same invented trip in four formats and check coordinates, times and segment boundaries.",
    "lede": "Choose for your destination, check what the file preserves, and keep an original you can return to.",
    "published": "2026-10-30", "modified": "2026-11-03", "source": "gpx-kml-geojson-location-history.html"
})

PAGES.extend([
    {"path": "/tools/google-timeline-inspector/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "Timeline JSON inspector", "title": "Google Timeline JSON Inspector and GPX Converter — Whereday", "headline": "Inspect Timeline JSON and convert supported records to GPX", "description": "Inspect Google Timeline JSON locally and convert supported records to GPX. Check coordinates, times, visits and path segments without uploading your file.", "lede": "Check your export, review supported records, and save GPX locally while keeping your original JSON.", "modified": "2026-10-30", "local_file_tool": True, "source": "google-timeline-inspector.html"},
    {"path": "/blog/google-timeline-json-formats/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Timeline JSON formats", "type": "BlogPosting", "title": "Which Google Timeline JSON File Do You Have? — Whereday", "headline": "Identify your Google Timeline JSON file", "description": "Identify raw, semantic and device Google Timeline JSON with worked examples. Check coordinates, times and unsupported records before importing.", "lede": "Look inside the export, compare a known example, and separate a format check from an app’s import preview.", "published": "2026-10-30", "modified": "2026-10-30", "source": "google-timeline-json-formats.html"}
])

PAGES.append({
    "path": "/tools/country-days-calculator/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "Days in country",
    "title": "Days in Country Calculator: Schengen 90/180 and 183 Days — Whereday",
    "headline": "Count your days in each country",
    "description": "Count days per country from a CSV or typed stays. Check the Schengen 90/180 rule and 183-day totals in your browser, with every date shown. Nothing is uploaded.",
    "lede": "Count days per country, check Schengen 90/180, and see 183-day totals, all in your browser.",
    "published": "2026-11-18", "modified": "2026-11-18", "local_file_tool": True, "source": "country-days-calculator.html",
})

PAGES.append({
    "path": "/blog/significant-locations-iphone/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Significant Locations",
    "type": "BlogPosting",
    "title": "Significant Locations and Visited Places on iPhone — Whereday",
    "headline": "Significant Locations and Visited Places on iPhone",
    "description": "Find Significant Locations and Apple Maps Visited Places on iPhone, see why they show little, and what to do since neither can be exported.",
    "lede": "Where each feature lives in iOS 26, why it often shows only a few places, how to clear or turn it off, and how to keep a history you can export from now on.",
    "published": "2026-11-19", "modified": "2026-11-19",
    "source": "significant-locations-iphone.html",
    "image": "/screenshots/map-home.png",
    "image_alt": "Whereday’s map home, with an invented day’s route coloured by place",
})

PAGES.append({
    "path": "/blog/google-maps-timeline-iphone/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Google Maps Timeline",
    "type": "BlogPosting",
    "title": "Google Maps Timeline on iPhone: Find, Back Up, Export — Whereday",
    "headline": "Google Maps Timeline on iPhone: where it is and how to keep it",
    "description": "Find Google Maps Timeline on iPhone, see why it is empty, back it up, move it to a new phone and export a copy you control.",
    "lede": "Where Timeline is on iPhone now, why it looks empty, and how to back it up, move it and export it.",
    "published": "2026-11-22", "modified": "2026-11-22",
    "source": "google-maps-timeline-iphone.html",
    "image": "/screenshots/map-home.png",
    "image_alt": "Whereday map of a day’s route",
})

PAGES.append({
    "path": "/blog/iphone-photos-map/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Photos map on iPhone",
    "type": "BlogPosting",
    "title": "See Your Photos on a Map on iPhone — Whereday",
    "headline": "See your photos on a map on iPhone",
    "description": "See where a photo was taken, view all photos on a map, fix photos missing from it, and place photos with no GPS using Whereday.",
    "lede": "How to see one photo’s location and all your photos on a map, why some are missing, and how to place photos that have no GPS.",
    "published": "2026-11-22", "modified": "2026-11-22",
    "source": "iphone-photos-map.html",
    "image": "/screenshots/map-layers.png",
    "image_alt": "Whereday’s Layers sheet showing the Photos row, off by default",
})

PAGES.append({
    "path": "/blog/find-my-location-history/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Find My history",
    "type": "BlogPosting",
    "title": "Does Find My Show Location History on iPhone? — Whereday",
    "headline": "Does Find My show location history?",
    "description": "No. Find My shows only a current or last known location. See what Find My, iCloud.com and Send Last Location keep, and how to keep your own history.",
    "lede": "What Find My, iCloud.com and Send Last Location actually keep, what you can see when a phone is lost, how consent works, and how to keep a history of your own from now on.",
    "published": "2026-11-19", "modified": "2026-11-19",
    "source": "find-my-location-history.html",
    "image": "/screenshots/map-home.png",
    "image_alt": "Whereday’s map home, with an invented day’s route coloured by place",
})

PAGES.append({
    "path": "/blog/iphone-location-history-by-date/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Location history by date",
    "type": "BlogPosting",
    "title": "See where you were on a specific date on iPhone — Whereday",
    "headline": "See where you were on a specific date on iPhone",
    "description": "Find where you were on a given date: Apple Maps Visited Places, Google Timeline, photos, workouts and receipts, and why changing the Date & Time won’t work.",
    "lede": "Which iPhone sources can jump to a date, how to rebuild a day when none were on, and how to make the next date answerable.",
    "published": "2026-11-22", "modified": "2026-11-22",
    "source": "iphone-location-history-by-date.html",
    "image": "/screenshots/map-home.png",
    "image_alt": "Whereday’s map home, with an invented day’s route coloured by place",
})

PAGES.append({
    "path": "/tools/visited-countries-map/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "Visited countries map",
    "title": "Visited Countries Map From Your Location File — Whereday",
    "headline": "Map the countries you’ve visited from your own location file",
    "description": "Turn a GPX, KML, GeoJSON or Google Timeline file into a shaded map of visited countries, with dates for each. Nothing is uploaded; it runs in your browser.",
    "lede": "Drop in a file you already have. The map fills itself in from your points, and every country shows the dates behind it, so you can check each one.",
    "published": "2026-11-20", "modified": "2026-11-20", "local_file_tool": True, "source": "visited-countries-map.html",
})

PAGES.append({
    "path": "/tools/merge-gpx/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "Merge GPX",
    "title": "Merge GPX Files Online, Free and Private — Whereday",
    "headline": "Merge GPX files in your browser. Nothing is uploaded.",
    "description": "Combine GPX files into one in your browser. Keep tracks separate or join segments, drop duplicate points and check gaps before you download. Nothing is uploaded.",
    "lede": "Join a recording split across days, devices or app restarts, or turn daily exports into one trip file. Choose what the merge does and check the result first.",
    "published": "2026-11-19", "modified": "2026-11-19", "local_file_tool": True, "source": "merge-gpx.html",
})

PAGES.append({
    "path": "/tools/kml-to-gpx/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "KML ⇄ GPX",
    "title": "KML to GPX Converter (and GPX to KML), Free and Private — Whereday",
    "headline": "Convert KML to GPX, or GPX to KML, in your browser",
    "description": "Convert KML to GPX or GPX to KML in your browser. Keeps gx:Track times and elevation, reports what was skipped, and nothing is uploaded. Free, no account.",
    "lede": "See what was kept and what was dropped before you download. Google Earth gx:Track timestamps become GPX times, and GPX times come back out as gx:Track.",
    "published": "2026-11-22", "modified": "2026-11-22", "local_file_tool": True, "source": "kml-to-gpx.html",
})

PAGES.append({
    "path": "/tools/kml-viewer/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "KML viewer",
    "title": "KML & KMZ Viewer, Free and Private (Nothing Uploaded) — Whereday",
    "headline": "Open a KML or KMZ file in your browser",
    "description": "Open a KML or KMZ file in your browser: see placemarks, paths and gx:Track times, and what was skipped. Works with KMZ. Nothing is uploaded. Free, no account.",
    "lede": "See what is inside a KML or KMZ file without installing Google Earth or uploading it, including a plain list of what the viewer does not draw.",
    "published": "2026-11-23", "modified": "2026-11-23", "local_file_tool": True, "source": "kml-viewer.html",
})

PAGES.append({
    "path": "/tools/substantial-presence-test-calculator/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "Substantial presence test",
    "title": "Substantial Presence Test Calculator (IRS 183-Day Rule) — Whereday",
    "headline": "Substantial presence test calculator",
    "description": "Work out the IRS substantial presence test from your dates: weighted days for three years, the 31-day check and what is left this year. Nothing is uploaded.",
    "lede": "Count your US days, apply the IRS weights and see every step, in your browser. A count, not tax advice.",
    "published": "2026-11-23", "modified": "2026-11-23", "local_file_tool": True, "source": "substantial-presence-test-calculator.html",
})

RELATED = {
 '/tools/kml-viewer/': [
     ('/tools/kml-to-gpx/', 'Convert this to GPX',
      'Turn KML into GPX locally, keeping gx:Track times.'),
     ('/tools/gpx-viewer/', 'Check a GPX file',
      'View a GPX route, points and timestamps without uploading it.'),
     ('/blog/gpx-kml-geojson-location-history/', 'Choose between GPX, KML, GeoJSON and CSV',
      'See what each format keeps, with the same invented trip.')],
 '/tools/kml-to-gpx/': [
     ('/tools/gpx-viewer/', 'Check the converted GPX',
      'Open it in the GPX viewer and compare points, segments and times.'),
     ('/tools/merge-gpx/', 'Merge several converted files',
      'Combine GPX files into one, without uploading them.'),
     ('/blog/gpx-kml-geojson-location-history/', 'Choose between GPX, KML, GeoJSON and CSV',
      'See what each format keeps, with the same invented trip.'),
     ('/blog/gpx-to-google-maps/', 'Put it on Google Maps', 'Import the GPX into My Maps on a computer, then view it on iPhone.')],
 '/blog/google-maps-timeline-iphone/': [
     ('/blog/import-google-timeline-iphone/', 'Import a saved Google JSON', 'Preview visits and points before saving anything.'),
     ('/tools/google-timeline-inspector/', 'Check what is inside your export', 'Inspect a Timeline JSON locally in your browser.'),
     ('/compare/google-maps-timeline/', 'Compare Whereday and Google Timeline', 'Recording, storage, backup and export side by side.')],
 '/blog/iphone-photos-map/': [
     ('/docs/photos/', 'Read the photo feature reference',
      'Matching order, the 15-minute window, privacy and controls.'),
     ('/blog/view-location-history-iphone/', 'Looking for where you were?',
      'Check Apple Maps, Google Timeline and Whereday.'),
     ('/blog/missing-location-history-iphone/', 'Gaps on a day?',
      'Photos can only be matched on days with records; check why records are missing.')],
 '/tools/visited-countries-map/': [
     ('/tools/country-days-calculator/', 'Count days instead of countries',
      'Check Schengen 90/180, rolling windows and 183-day totals from a Country Days CSV.'),
     ('/blog/track-days-in-country-iphone/', 'Record the countries automatically',
      'See how Whereday records country days on your iPhone.'),
     ('/tools/merge-gpx/', 'Merge several files first',
      'Combine daily GPX files into one before you map them.')],
 '/tools/merge-gpx/': [
     ('/tools/kml-to-gpx/', 'Have KML? Convert it to GPX first',
      'Convert KML to GPX in your browser and see what was kept.'),
     ('/tools/gpx-viewer/', 'Check the merged file',
      'Open it in the GPX viewer and compare points, segments and times.'),
     ('/blog/check-gpx-file/', 'Understand GPX warnings',
      'Follow a worked example of gaps, missing times and segments.'),
     ('/blog/record-iphone-route-gpx/', 'Record your next route',
      'Record in Whereday and export points as GPX.')],
 '/tools/gpx-viewer/': [
     ('/tools/kml-to-gpx/', 'Have a KML file? Convert it first',
      'Convert KML to GPX locally, with a report of what was kept.'),
     ('/tools/merge-gpx/', 'Have several files? Merge them first',
      'Combine daily or split GPX files into one, without uploading them.'),
     ('/blog/check-gpx-file/', 'Follow the worked GPX check',
      'Expected counts and warnings for an invented five-point file.')],
 '/blog/find-your-travel-history/': [
     ('/blog/track-days-in-country-iphone/', 'Record the dates automatically',
      'See how Whereday records country days and adds a missing stay.'),
     ('/tools/country-days-calculator/', 'Count the days',
      'Count days per country from a Country Days CSV.'),
     ('/tools/visited-countries-map/', 'Map the countries',
      'Turn a location file into a list of countries visited.')],
 '/blog/gpx-to-google-maps/': [
     ('/tools/gpx-viewer/', 'View it without uploading',
      'See the route, point counts and dates locally in your browser.'),
     ('/tools/merge-gpx/', 'Combine several files first',
      'Merge daily GPX files into one trip file.'),
     ('/blog/record-iphone-route-gpx/', 'Record and export your own route',
      'Record in Whereday and export Points as GPX.')],
 '/blog/what-is-a-gpx-file/': [
     ('/tools/gpx-viewer/', 'Open your GPX file',
      'See the route, point counts and dates locally in your browser.'),
     ('/blog/check-gpx-file/', 'Check it before importing',
      'Follow a worked example of warnings, gaps and segments.'),
     ('/blog/record-iphone-route-gpx/', 'Record your own route',
      'Record in Whereday and export Points as GPX.'),
     ('/blog/gpx-to-google-maps/', 'Put it on Google Maps', 'Import the GPX into My Maps on a computer, then view it on iPhone.')],
 '/blog/check-gpx-file/': [
     ('/tools/kml-to-gpx/', 'Convert KML to GPX or back',
      'Keep gx:Track times and see what was dropped.'),
     ('/tools/merge-gpx/', 'Merge several GPX files into one',
      'Keep segments separate so viewers don’t draw lines across gaps.'),
     ('/blog/gpx-kml-geojson-location-history/', 'Choose another file format',
      'Compare GPX, KML, GeoJSON and CSV with the same invented trip.')],
 '/blog/iphone-location-history-by-date/': [
     ('/blog/view-location-history-iphone/', 'Check every kind of iPhone history', 'Apple Maps, Google Timeline and Whereday, by source.'),
     ('/blog/iphone-photos-map/', 'See your photos on a map', 'Use photos as dated evidence of where you were.'),
     ('/docs/timeline-outings/', 'Review a day in Whereday', 'Date chips, Pick a day and outings.')],
 '/blog/find-my-location-history/': [
     ('/blog/iphone-location-history-by-date/', 'Looking for one specific day?', 'Which sources can jump to a date, and how to rebuild a day without them.'),
     ('/blog/significant-locations-iphone/', 'Check Significant Locations and Visited Places',
      'See the two Apple features that do remember places, and why neither exports.'),
     ('/blog/view-location-history-iphone/', 'Find any iPhone location history',
      'Check Apple Maps, Google Timeline and Whereday, and choose the right download.')],
 '/blog/import-google-timeline-iphone/': [
     ('/blog/back-up-iphone-location-history/', 'Keep a restorable Whereday copy', 'Choose supported visit and point exports after checking the import.'),
     ('/compare/google-maps-timeline/', 'Compare recording workflows', 'Review access, backups, privacy, and portability before switching.')],
 '/integrations/dawarich/': [('/docs/webhooks/', 'Review send modes and queue limits', 'Check realtime, batching, retries, and manual-send behavior.'), ('/blog/back-up-iphone-location-history/', 'Keep a separate backup', 'A receiver copy is different from a restorable Whereday journal.')],
 '/tools/substantial-presence-test-calculator/': [
     ('/tools/country-days-calculator/', 'Count days in other countries',
      'Check Schengen 90/180, any rolling window and calendar-year totals.'),
     ('/blog/find-your-travel-history/', 'Check your dates against official records',
      'Find past trips to fill gaps before you rely on a count.'),
     ('/blog/track-days-in-country-iphone/', 'Record the dates automatically',
      'See how Whereday records country days and exports the CSV.')],
 '/tools/country-days-calculator/': [
     ('/tools/substantial-presence-test-calculator/', 'Apply the US substantial presence test',
      'Use the same CSV with the IRS weighted three-year formula.'),
     ('/tools/visited-countries-map/', 'Map the countries instead of counting days',
      'Shade the countries in a location file and see the dates behind each one.'),
     ('/blog/track-days-in-country-iphone/', 'Record the dates automatically',
      'See how Whereday records country days, adds a missing stay and analyses older records.'),
     ('/blog/back-up-iphone-location-history/', 'Keep the records behind the count',
      'A country-date CSV is a summary, not a backup of your visits and points.')],
 '/blog/track-days-in-country-iphone/': [
     ('/blog/private-location-journal-iphone/', 'Record your next trip',
      'Start a local journal before you leave.'),
     ('/blog/back-up-iphone-location-history/', 'Keep the underlying records',
      'A country-date summary is not a complete backup.')],
 '/blog/significant-locations-iphone/': [
     ('/blog/find-my-location-history/', 'Looking in Find My instead?',
      'Find My keeps no history, only a current or last known location.'),
     ('/blog/view-location-history-iphone/', 'Find any iPhone location history',
      'Check Apple Maps, Google Timeline and Whereday, and choose the right download.'),
     ('/blog/back-up-iphone-location-history/', 'Keep a copy of the history you record',
      'Export visits and points as Whereday JSON and check the files.'),
     ('/blog/iphone-photos-map/', 'See your photos on a map',
      'Use the Photos map and place photos that have no GPS.')],
 '/blog/view-location-history-iphone/': [('/blog/significant-locations-iphone/',
                                          'Understand Significant Locations and Visited Places',
                                          'See where each Apple feature lives, why it shows '
                                          'little, and why neither exports.'),
                                         ('/blog/missing-location-history-iphone/',
                                          'Check missing Whereday records',
                                          'Diagnose recording gaps, hidden points, Watch '
                                          'transfers, and export filters.'),
                                         ('/blog/private-location-journal-iphone/',
                                          'Start a journal for future trips',
                                          'Set up recording before the next trip you want to '
                                          'remember.'),
                                         ('/blog/iphone-photos-map/',
                                          'See your photos on a map',
                                          'Use the Photos map and place photos that have no '
                                          'GPS.')],
 '/blog/record-iphone-route-gpx/': [('/blog/back-up-iphone-location-history/',
                                     'Keep a restorable copy',
                                     'GPX is a map file; retain supported visits and points '
                                     'separately for import.'),
                                    ('/blog/missing-location-history-iphone/',
                                     'Investigate an incomplete route',
                                     'Check recording state and filters before repeating the '
                                     'export.')],
 '/blog/back-up-iphone-location-history/': [('/blog/significant-locations-iphone/',
                                             'Why Apple’s location history can’t be backed up',
                                             'Significant Locations and Visited Places have no '
                                             'export; record your own from now on.'),
                                            ('/blog/iphone-location-journal-obsidian/',
                                             'Make a readable Markdown note',
                                             'Copy a visit table into a desktop Obsidian vault.'),
                                            ('/docs/automatic-exports/',
                                             'Check scheduled copies',
                                             'Understand current-day exports, rewrite behavior, '
                                             'and background limits.')],
 '/blog/iphone-location-tracking-battery/': [('/blog/missing-location-history-iphone/',
                                              'Check a gap in your recording',
                                              'Separate missing records from hidden glitches and '
                                              'incomplete transfers.'),
                                             ('/blog/record-iphone-route-gpx/',
                                              'Save the route you recorded',
                                              'Preview points and write a portable GPX file.')],
 '/blog/missing-location-history-iphone/': [('/blog/back-up-iphone-location-history/',
                                             'Preserve the records you have',
                                             'Keep supported visit and point files before changing '
                                             'your setup.'),
                                            ('/docs/apple-watch/',
                                             'Check Watch recording and sync',
                                             'Read the status messages and verify the transferred '
                                             'day on your phone.')],
 '/blog/iphone-location-journal-obsidian/': [('/blog/back-up-iphone-location-history/',
                                              'Keep a separate restore copy',
                                              'A readable note with omitted fields may not be '
                                              'suitable for import.'),
                                             ('/docs/automatic-exports/',
                                              'Review automatic export behavior',
                                              'Check what a scheduled run writes and which files '
                                              'it can replace.')],
 '/compare/travel-journal-apps-iphone/': [
     ('/blog/iphone-location-journal-obsidian/', 'Turn your visits into journal notes', 'Export Markdown and copy it into a vault you control.'),
     ('/blog/record-iphone-route-gpx/', 'Record a route as GPX', 'Check recording access and preview the route before you export.')],
 '/compare/location-history-apps-iphone/': [
     ('/blog/view-location-history-iphone/', 'Find your existing history', 'Check Apple Maps, Google Timeline and Whereday before choosing an app.'),
     ('/docs/export-import/', 'Check the export formats', 'See the files Whereday writes and the files it can import.')],
 '/compare/google-maps-timeline/': [('/blog/view-location-history-iphone/',
                                     'Find your existing records',
                                     'Check the service that recorded them before changing apps.'),
                                    ('/blog/record-iphone-route-gpx/',
                                     'Try the route export workflow',
                                     'Check recording access and preview steps before writing a '
                                     'GPX file.')]}


def related_navigation(path):
    items = RELATED.get(path, [])
    if not items:
        return ""
    links = "".join(f'<li><a href="{escape(url, quote=True)}">{escape(label)}</a><p>{escape(reason)}</p></li>' for url, label, reason in items)
    return f'<nav class="article-toc" aria-labelledby="read-next-title"><h2 id="read-next-title">Read next</h2><ul>{links}</ul></nav>'


def render(page):
    path = page["path"]
    url = BASE + path
    content = (MARKETING / "content" / page["source"]).read_text() if "source" in page else page["content"]
    section = page.get("section", "Compare")
    section_path = page.get("section_path", "/compare/")
    is_article = page.get("type") == "BlogPosting"
    image = BASE + page.get("image", "/screenshots/map-home.png")
    image_alt = page.get("image_alt", "Whereday’s map home, with today’s route coloured by place")
    crumbs = [("Whereday", "/"), (section, section_path)]
    if path != section_path:
        crumbs.append((page.get("breadcrumb", "Google Timeline"), path))
    breadcrumb = {
        "@type": "BreadcrumbList",
        "itemListElement": [{"@type": "ListItem", "position": i, "name": name, "item": BASE + target} for i, (name, target) in enumerate(crumbs, 1)],
    }
    webpage = {"@type": "WebPage", "@id": url, "url": url, "name": page["title"], "description": page["description"], "inLanguage": "en", "dateModified": page.get("modified", REVIEWED), "author": {"@type": "Person", "name": "Cody Bontecou", "url": "https://isolated.tech"}}
    if is_article:
        webpage.update({"@type": "BlogPosting", "headline": page["headline"], "datePublished": page.get("published", REVIEWED), "mainEntityOfPage": url, "image": image, "publisher": {"@type": "Organization", "name": "isolated.tech", "url": "https://isolated.tech"}})
    # Apple public US lookup confirmed Whereday 1.8 on October 30, 2026.
    store_notice = ""
    modified = page.get("modified", REVIEWED)
    reviewed_date = date.fromisoformat(modified)
    reviewed_label = f"{reviewed_date:%B} {reviewed_date.day}, {reviewed_date.year}"
    schema = json.dumps({"@context": "https://schema.org", "@graph": [webpage, breadcrumb]}, ensure_ascii=False, indent=2)
    nav_crumbs = "".join(f'<li>{escape(name)}</li>' if target == path else f'<li><a href="{target}">{escape(name)}</a></li>' for name, target in crumbs)
    # Preserve the site's existing production-host and analytics-gate behavior.
    home = (PUBLIC / "index.html").read_text()
    analytics = home[home.index("<!-- Cloudflare Web Analytics -->"):home.index("<!-- End Cloudflare Web Analytics -->") + len("<!-- End Cloudflare Web Analytics -->")]
    local_head = ""
    if page.get("local_file_tool"):
        analytics = ""
        local_head = ("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'none'; base-uri 'none'; form-action 'none'; object-src 'none'\">\n"
                      "  <meta name=\"referrer\" content=\"no-referrer\">\n  ")
    return f'''<!DOCTYPE html>
<html lang="en">
<head>
  {local_head}<meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>{escape(page["title"])}</title>
  <meta name="description" content="{escape(page["description"], quote=True)}">
  <meta name="robots" content="{page.get('robots', 'index,follow')}">
  <link rel="canonical" href="{url}">
  <meta name="theme-color" content="#F3EFE6" media="(prefers-color-scheme: light)">
  <meta name="theme-color" content="#0F1626" media="(prefers-color-scheme: dark)">
  <meta property="og:title" content="{escape(page["title"], quote=True)}">
  <meta property="og:description" content="{escape(page["description"], quote=True)}">
  <meta property="og:type" content="{'article' if is_article else 'website'}">
  <meta property="og:url" content="{url}">
  <meta property="og:image" content="{image}">
  <meta property="og:image:alt" content="{escape(image_alt, quote=True)}">
  <meta name="twitter:card" content="summary_large_image">
  <meta name="twitter:title" content="{escape(page['title'], quote=True)}">
  <meta name="twitter:description" content="{escape(page['description'], quote=True)}">
  <meta name="twitter:image" content="{image}">
  <link rel="icon" href="/favicon.ico" sizes="any">
  <link rel="apple-touch-icon" href="/apple-touch-icon.png">
  <style>{(PUBLIC / 'assets' / 'seo.css').read_text()}</style>
  <script type="application/ld+json">{schema}</script>
</head>
<body>
  <a class="skip-link" href="#main">Skip to content</a>
  <nav class="site-nav" aria-label="Primary navigation">
    <a class="brand" href="/"><img src="/app-icon-28.webp" srcset="/app-icon-28.webp 1x, /app-icon-84.webp 3x" width="28" height="28" alt="">Whereday</a>
    <div class="nav-links"><a href="/blog/"{' aria-current="true"' if section == 'Guides' else ''}>Guides</a><a href="/compare/"{' aria-current="page"' if path == '/compare/' else ' aria-current="true"' if section == 'Compare' else ''}>Compare</a><a href="/docs/">Docs</a><a href="/#pricing">Pricing</a><a class="button" href="{APP}">Download free</a></div>
  </nav>
  <main id="main">
    <nav class="breadcrumbs" aria-label="Breadcrumb"><ol>{nav_crumbs}</ol></nav>
    {'<article aria-labelledby="article-title">' if is_article else ''}
    <header><p class="eyebrow">{'Practical iPhone guides' if is_article else 'iPhone location journals'}</p><h1 id="article-title">{escape(page["headline"])}</h1><p class="lede">{escape(page["lede"])}</p><p class="byline">By <a href="https://isolated.tech">Cody Bontecou</a>, Whereday’s developer · Reviewed <time datetime="{modified}">{reviewed_label}</time></p>{store_notice}</header>
    {content}
    {'</article>' if is_article else ''}
    {related_navigation(path)}
  </main>
  <footer class="site-footer"><a class="brand" href="/">Whereday · isolated.tech</a><div class="footer-links"><a href="/blog/">Guides</a><a href="/compare/">Compare</a><a href="/docs/">Docs</a><a href="/about">About</a><a href="/contact">Contact</a><a href="/privacy">Privacy</a><a href="/terms">Terms</a><a href="mailto:cody.bontecou@gmail.com">Support</a></div></footer>
  {analytics}
</body>
</html>
'''


def build():
    for page in PAGES:
        output = PUBLIC / page["path"].strip("/") / "index.html"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(render(page))
        print(f"wrote {output.relative_to(PUBLIC)}")
    from blog_inventory import sync
    sync(PUBLIC)


if __name__ == "__main__":
    build()
