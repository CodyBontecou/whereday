#!/usr/bin/env python3
"""Generate the Whereday docs section (marketing/public/docs/*.html)."""
import pathlib, html

OUT = pathlib.Path(__file__).resolve().parent.parent / "public" / "docs"
BASE = "https://whereday.com/docs/"
UPDATED = "October 26, 2026"
REVIEWED = {"getting-started", "tracking", "export-import", "privacy-data", "apple-watch", "webhooks", "timeline-outings"}

GUIDES = [
    # (slug, title, short, lede, platform, minutes, tag)
    ("getting-started", "Getting started",
     "Install, onboarding, permissions, and your first tracking session.",
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
    ("places", "Places, corrections & manual entries",
     "Confirm or correct detected visits, save places, and add visits by hand.",
     "Automatic detection is a starting point, not a verdict. Confirm visits, fix names with nearby-business suggestions, save places that name themselves in the future, and add past visits manually.",
     "iOS 17+", 3, None),
    ("photos", "Photos on the map",
     "Optionally connect your Photos library to place pictures on your map and outings.",
     "An opt-in connection between your photo library and your location journal. Photo files never leave your library — Whereday only matches metadata.",
     "iOS 17+", 2, None),
    ("export-import", "Export and restore location history",
     "Eight export formats, filters, filename templates, and free import for restores.",
     "Export recorded visits, route points, and outings in eight formats. Preview is free; writing files requires Pro. Import supported Whereday visit and point files for free.",
     "iOS 17+", 5, "PRO"),
    ("automatic-exports", "Automatic exports",
     "Schedule exports once a day or every few hours, keeping one file per day.",
     "Auto Export writes your day to a folder you choose, on a schedule you choose — daily or as often as every hour — with rewrite and append modes that keep each day's file consistent.",
     "iOS 17+", 4, "PRO"),
    ("webhooks", "Webhooks & self-hosting",
     "Configure optional delivery and verify your receiver’s payload and authentication requirements.",
     "The optional webhook feature posts your data, in your chosen format, to a server you control. Off by default, fully configurable, and nothing is sent until you set it up.",
     "iOS 17+", 4, "PRO"),
    ("siri-shortcuts", "Siri & Shortcuts",
     "Nine actions for tracking, stats, renaming, and exports.",
     "Whereday exposes nine App Intents that work in the Shortcuts app, from Siri, and from automations like the Action Button — including exports handed straight to other apps.",
     "iOS 17+", 3, None),
    ("apple-watch", "Record Apple Watch GPS routes without your iPhone",
     "Independent watch tracking, offline sync, and complications.",
     "Start a route on Apple Watch, record without your iPhone nearby, then check the transfer back to your phone. Learn the recording controls, sync status, and recovery checks.",
     "watchOS 10+", 3, None),
    ("privacy-data", "Privacy & your data",
     "What stays on your device, what can leave it, and how deletion works.",
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
<table>
<tr><th>Setting</th><th>Options</th><th>Default</th><th>What it does</th></tr>
<tr><td><strong>Distance filter</strong></td><td>5 / 10 / 25 / 50 / 100 / 200 m</td><td>5 m</td><td>Movement threshold requested for location updates. Larger values may reduce point density and detail; high-accuracy GPS remains active.</td></tr>
<tr><td><strong>Stop after</strong></td><td>Never / 1 / 2 / 4 / 8 / 12 h</td><td>Never</td><td>Automatically ends tracking after this long, so a forgotten session can't run all day.</td></tr>
<tr><td><strong>Location names</strong></td><td>On / Off</td><td>On</td><td>Whether coordinates are sent to Apple's geocoding service to name visits.</td></tr>
<tr><td><strong>Live Activity</strong></td><td>On / Off</td><td>On</td><td>Lock screen / Dynamic Island status while tracking.</td></tr>
<tr><td><strong>Units</strong></td><td>Metric / US</td><td>Metric</td><td>Applies everywhere: app, widgets, Live Activity, watch.</td></tr>
</table>

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
<li><strong>Export</strong> saves this single outing in your choice of format (a Pro feature; the picker remembers your format).</li>
<li><strong>Delete outing</strong> removes the session and its GPS points — <strong>visits are kept</strong>. Deleting the active outing stops tracking first.</li>
</ul>

<h2>Route replay</h2>
<p>Both the map (for a whole day) and the outing detail page (for one outing) offer <strong>replay</strong>: a playhead walks the path in order while a scrubber, elapsed time, and running distance update. It's a quick way to answer “where was I around 3 pm?”</p>

<h2>Sorting</h2>
<p>Outing lists can be sorted by newest, oldest, longest duration, distance, or point count.</p>
<p>If a recorded day looks incomplete, follow the <a href="/blog/missing-location-history-iphone/">missing visits and route points checklist</a> before deleting records or retrying an import.</p>

"""

BODIES["places"] = """
<h2>The visit lifecycle</h2>
<p>Every visit carries a status: <strong>unconfirmed</strong> (as detected), <strong>confirmed</strong> (you approved it), or <strong>corrected</strong> (you changed the name, address, or location). Whereday never lets automatic geocoding overwrite a visit you've touched — your edits are final until you undo them.</p>

<h2>Confirming and correcting</h2>
<ul>
<li>Tap a visit on the map or in the timeline to open its detail sheet, then <strong>Confirm place</strong> to accept the detected name.</li>
<li>To fix a wrong name, edit it or pick from <strong>nearby business suggestions</strong> — real places found via Apple Maps around the visit's location, searchable in the sheet.</li>
<li><strong>Undo correction</strong> restores the originally detected name, address, and position.</li>
<li>Arrival and departure times are editable (with a “still here” toggle for ongoing visits), and notes can be attached to any visit.</li>
</ul>

<h2>Saved places</h2>
<p>Confirming or correcting a visit also <strong>saves the place</strong> — a name, position, and a match radius (default 150 m). From then on, any new visit inside that radius is automatically named from the saved place and marked confirmed. No more re-correcting “Home” every week.</p>
<ul>
<li>Saved places are manageable from the manual-entry sheet: select, swipe to delete, or create new ones with a radius of 50 / 100 / 150 / 250 m.</li>
<li>Places with the same name at nearly the same spot update each other instead of piling up.</li>
</ul>

<h2>Manual and past visits</h2>
<p>The <strong>+</strong> menu on the map offers three ways to add history by hand:</p>
<ul>
<li><strong>Save current place</strong> — names where you are right now.</li>
<li><strong>Add past visit</strong> — pick a place, an arrival, and a departure; the map then jumps to that day so you can see it in context.</li>
<li><strong>Add place manually</strong> — search Apple Maps, drop a pin, pick a saved place, or use your current location; optionally save it as a reusable place.</li>
</ul>
<p>Locations can be chosen by searching Apple Maps, dropping a pin on a map, reusing a saved place, or using your current GPS position. Past visits require a departure time and never silently fall back to “here”.</p>

<h2>The current-place suggestion</h2>
<p>With <strong>Settings → Map display → Visit suggestions</strong> enabled (off by default), the map can offer a small card when you arrive somewhere new — suggesting the nearest saved place or a nearby business, with one tap to save. Dismissing a suggestion also silences it for that spot, so it doesn't nag.</p>
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
<div class="callout pro"><p><strong>Free vs Pro.</strong> Building and previewing exports is free. Writing export files (from the app's Export tab or an outing's page) is the Pro unlock, along with automatic exports and webhooks. <strong>Importing a prior export is free.</strong></p></div>

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
<p>Choose any mix of formats in one export; each becomes its own file. OwnTracks and Overland formats carry route points (visits exports in those slots fall back to JSON).</p>

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

<h2>Import</h2>
<p>Settings → <strong>Import file</strong> accepts visit and point files exported from Whereday as JSON, CSV, or Markdown. Importing is free. A matching file extension alone does not make another app's file compatible: Google Timeline archives are not supported.</p>
<table>
<caption>Choose a file for the next task</caption>
<tr><th scope="col">Format</th><th scope="col">Useful for</th><th scope="col">Import into Whereday</th></tr>
<tr><th scope="row">Whereday JSON</th><td>Structured visit or point records</td><td>Supported visit and point exports</td></tr>
<tr><th scope="row">Whereday CSV / Markdown</th><td>Spreadsheets / readable records</td><td>Supported visit and point exports; required fields must remain</td></tr>
<tr><th scope="row">GPX / KML / GeoJSON</th><td>Mapping tools and route viewers</td><td>No</td></tr>
<tr><th scope="row">OwnTracks / Overland</th><td>Compatible location tools</td><td>No</td></tr>
</table>
<h2>Keep a copy and check it</h2>
<ol>
<li>Select visits or route points, JSON, and the dates you want to retain. Export each data type you need; keep all generated files.</li>
<li>Preview the result. Check that filters, field choices, and dates have not excluded required records.</li>
<li>Export to a folder you control and confirm the files exist in Files before deleting or replacing anything.</li>
<li>When importing later, keep the originals and inspect several dates, visits, and points after the import. Do not assume every setting, photo association, or edited outing is restored.</li>
</ol>
<p><a href="/docs/automatic-exports/">Automatic exports</a> can keep scheduled copies, subject to iOS background execution. For the recording workflow, see <a href="/blog/private-location-journal-iphone/">the private location journal guide</a>. For a route file, follow <a href="/blog/record-iphone-route-gpx/">the GPX recording walkthrough</a>. For restorable records, use <a href="/blog/back-up-iphone-location-history/">the backup and restore checklist</a>. Comparing apps? Read <a href="/compare/google-maps-timeline/">Whereday and Google Timeline</a>.</p>
<p>Import adds supported records; do not assume overlapping files are merged or deduplicated. Import each intended file once, then check the result before retrying. The <a href="/blog/back-up-iphone-location-history/#sample">synthetic JSON examples</a> show the visit and point structures without using private history.</p>
<p>For a readable note in a desktop vault, follow the <a href="/blog/iphone-location-journal-obsidian/">Markdown-to-Obsidian workflow</a>. It covers manual file placement and keeps generated notes separate from personal writing.</p>

"""

BODIES["automatic-exports"] = """
<div class="callout pro"><p><strong>Pro feature.</strong> Auto Export appears in the Export tab once you've chosen a default export folder and unlocked Pro.</p></div>

<h2>The idea</h2>
<p>You pick a folder, a format, and a schedule. Each run exports <strong>the current local day so far</strong> into <strong>one file for that day</strong> — always the same file name for the same day, no matter how many times it runs. Yesterday's file is never touched; tomorrow gets a new one.</p>

<h2>Schedules</h2>
<ul>
<li><strong>Once daily</strong> — at a time you choose (default 21:00).</li>
<li><strong>Every 1–23 hours</strong> — from a start time you choose. A 3-hour schedule starting at 09:00 fires at 09:00, 12:00, 15:00…; intervals that don't divide the day simply restart from the next day's start time.</li>
</ul>

<h2>Rewrite vs append</h2>
<table>
<tr><th>Mode</th><th>Behavior</th></tr>
<tr><td><strong>Rewrite</strong> (default)</td><td>Each run rewrites the day's file with everything recorded so far today. Simple and self-healing.</td></tr>
<tr><td><strong>Append</strong></td><td>Each run adds only records captured since the last successful run — no duplicates, tiny writes. (Once-daily schedules always rewrite.)</td></tr>
</table>
<p>Append works for CSV, JSON, Markdown, OwnTracks, Overland, and GeoJSON. GPX and KML always rewrite the full day, because valid XML files can't be naively concatenated.</p>

<h2>How the app wakes up</h2>
<p>iOS never guarantees background execution, so Auto Export layers its chances:</p>
<ul>
<li><strong>Background refresh</strong> — iOS schedules the app to wake near each run.</li>
<li><strong>A silent push</strong> — Whereday's scheduling service nudges the app at the fire time. The service only ever holds timing metadata (your time zone, the schedule, a push token) — never location records, file contents, or folder names.</li>
<li><strong>A fallback notification</strong> — if nothing ran by a minute after the scheduled time, you get a tappable “Daily Export Ready” notification; tapping runs it.</li>
<li><strong>Catch-up</strong> — opening the app runs any overdue occurrence automatically.</li>
</ul>
<p>If a run fails you'll get a notification with the reason and a retry; the schedule itself keeps going.</p>

<h2>Manual control</h2>
<p><strong>Run now</strong> in the Auto Export section performs an export immediately regardless of schedule — useful after a big day. The section also shows the last run time and format.</p>
"""

BODIES["webhooks"] = """
<div class="callout pro"><p><strong>Pro feature, off by default.</strong> Webhooks can send your location data off your device — to a server <em>you</em> choose. Nothing is sent until you configure an endpoint, and the settings screen leads with a prominent warning card.</p></div>

<h2>What it's for</h2>
<p>Webhooks deliver records to a receiver you choose, but the payload must match that receiver's API. An endpoint accepting HTTP POST is not enough to establish compatibility. Verify a dedicated test destination before enabling delivery of your history.</p>
<table>
<tr><th>Service</th><th>Endpoint shape</th><th>Format</th></tr>
<tr><td>Dawarich batch receiver</td><td><code>…/api/v1/overland/batches</code></td><td>Overland; verify against your instance before use</td></tr>
<tr><td>OwnTracks receiver</td><td>per your installation</td><td>Verify it accepts arrays of location messages</td></tr>
<tr><td>Other receiver</td><td>its documented HTTPS endpoint</td><td>Use its required schema and authentication</td></tr>
</table>

<p>Whereday encodes OwnTracks points as an array, including a single point. Dawarich's current OwnTracks receiver expects individual point fields, so do not assume those two shapes are interchangeable. Its Overland batch receiver is a different protocol. Check <a href="https://dawarich.app/docs/getting-started/track-your-location/">Dawarich's receiver documentation</a> and confirm actual imported points; a completed Whereday-to-Dawarich walkthrough is not yet verified here.</p>

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
<h2>Nine actions</h2>
<p>Every action runs without opening the app. Find them in the Shortcuts app under Whereday, or just talk to Siri.</p>
<table>
<tr><th>Action</th><th>What it does</th><th>Siri phrase example</th></tr>
<tr><td><strong>Start Tracking</strong></td><td>Begins a recording session.</td><td>“Start Whereday”</td></tr>
<tr><td><strong>Stop Tracking</strong></td><td>Ends the session — even if the app isn't running.</td><td>“Stop tracking with Whereday”</td></tr>
<tr><td><strong>Rename Current Outing</strong></td><td>Sets the active outing's name (parameter: name).</td><td>“Name my Whereday outing”</td></tr>
<tr><td><strong>Today's Distance</strong></td><td>Returns how far you've moved today.</td><td>“How far have I gone with Whereday?”</td></tr>
<tr><td><strong>Today's Tracking Duration</strong></td><td>Returns how long the session has run.</td><td>“Whereday tracking duration”</td></tr>
<tr><td><strong>Today's Visits</strong></td><td>Returns today's visit count.</td><td>“How many visits today in Whereday?”</td></tr>
<tr><td><strong>Export Today's Data</strong></td><td>Produces a file of today's visits and route (parameter: format, default JSON).</td><td>“Export today's data from Whereday”</td></tr>
<tr><td><strong>Export Today's Outings</strong></td><td>Same, for outings.</td><td>“Export today's Whereday outings”</td></tr>
<tr><td><strong>Export Yesterday's Data</strong></td><td>Yesterday's visits and route as a file.</td><td>“Export yesterday's Whereday tracks”</td></tr>
</table>

<h2>Automation ideas</h2>
<ul>
<li>Bind <strong>Start Tracking</strong> to the <strong>Action Button</strong> for one-press recording.</li>
<li>An automation that runs <strong>Export Today's Data</strong> every evening and hands the file to another shortcut — your own export pipeline.</li>
<li>Ask Siri for <strong>Today's Visits</strong> or <strong>Distance</strong> as a daily digest.</li>
<li>Rename an outing mid-hike: “Name my Whereday outing Ridge Trail.”</li>
</ul>
<p>Export actions produce a file in your chosen format that Shortcuts can save, message, or pass to other apps.</p>
"""

BODIES["apple-watch"] = """
<h2>Record on the Watch, then check the phone copy</h2>
<p>Whereday can record GPS points on Apple Watch without the iPhone nearby. The Watch keeps a local queue for transfer to the companion iPhone. Recording and reviewing history are free; writing export files on the phone requires the optional unlock.</p>
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
<p>Completed transfers are acknowledged by the phone; points remain in the Watch queue until acknowledged. This helps retry delivery, but the queue is not a full backup of history already transferred. Do not uninstall the Watch app as a first troubleshooting step.</p>

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

<h2>Keep a portable copy after syncing</h2>
<p>Once the route is visible on the phone, follow <a href="/blog/record-iphone-route-gpx/">the GPX walkthrough</a> to open it in another route tool, or <a href="/blog/back-up-iphone-location-history/">back up supported visits and points</a> for later import. See <a href="/docs/timeline-outings/">Timeline and outings</a> for reviewing the transferred day.</p>
<p>If a recorded day looks incomplete, follow the <a href="/blog/missing-location-history-iphone/">missing visits and route points checklist</a> before deleting records or retrying an import.</p>

"""

BODIES["privacy-data"] = """
<h2>The short version</h2>
<p>Your location history — visits, routes, outings, saved places, photo matches — is stored in the app's on-device database and <strong>nowhere else</strong>. There is no Whereday account, no cloud sync, and no Whereday server holding your location data. The full legal text is the <a href="/privacy">Privacy Policy</a>; this guide is the plain-language version.</p>

<h2>Everything that can leave your device</h2>
<table>
<tr><th>Where</th><th>What's sent</th><th>When</th><th>Off switch</th></tr>
<tr><td><strong>Apple geocoding</strong></td><td>Coordinates of a visit (to fetch its name/address)</td><td>When naming visits</td><td>Settings → Location names</td></tr>
<tr><td><strong>Apple Maps</strong></td><td>Search terms / route segment endpoints (place search, suggestions, road-matched lines, lock-screen map images)</td><td>When you use those features</td><td>Features are opt-in per use; road-matching toggle in map filters</td></tr>
<tr><td><strong>Whereday analytics</strong> (first-party, on by default)</td><td>Which onboarding steps were viewed, permission choices, purchase outcomes, app version, and a random install ID — <strong>never</strong> coordinates, visits, routes, or anything you type</td><td>During onboarding and purchases</td><td>None in-app today; deleting the app removes the install ID</td></tr>
<tr><td><strong>Export scheduler</strong></td><td>Time zone, schedule times, push token, random install ID — <strong>timing metadata only</strong>, no location or file contents</td><td>Only if you enable Auto Export</td><td>Turn Auto Export off</td></tr>
<tr><td><strong>Your webhook</strong></td><td>Location points and visits, in your chosen format</td><td>Only after you configure an endpoint</td><td>Disable webhooks (off by default)</td></tr>
</table>

<h2>Photos</h2>
<p>The photo feature reads your library on-device to match pictures to places. Photo files are never copied or uploaded; Whereday keeps only local identifiers and match metadata, and respects limited-access mode.</p>

<h2>Deleting your data</h2>
<ul>
<li><strong>Everything, in-app:</strong> Settings → Data → <strong>Clear all data</strong> permanently removes every visit, point, outing, photo match, and saved place.</li>
<li><strong>Everything, bluntly:</strong> deleting the app removes your entire journal and the local analytics install ID from that device.</li>
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
  <title>__TITLE__ — Whereday Docs</title>
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

    # Guide pages
    for i, (slug, title, short, lede, platform, minutes, tag) in enumerate(GUIDES):
        prev_g = GUIDES[i - 1] if i > 0 else None
        next_g = GUIDES[i + 1] if i < total - 1 else None
        body = GUIDE_PAGE
        body = body.replace("__NUM__", str(i + 1)).replace("__TOTAL__", str(total))
        body = body.replace("__TITLE__", html.escape(title))
        body = body.replace("__LEDE__", html.escape(lede))
        body = body.replace("__PLATFORM__", html.escape(platform))
        body = body.replace("__UPDATED__", UPDATED if slug in REVIEWED else "August 22, 2026")
        body = body.replace("__MINUTES__", str(minutes))
        body = body.replace("__CONTENT__", BODIES[slug].strip())
        body = body.replace("__PREV__", pager_link("prev", prev_g))
        body = body.replace("__NEXT__", pager_link("next", next_g))

        page = (SHELL
                .replace("__TITLE__", html.escape(title))
                .replace("__DESC__", html.escape(short))
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
