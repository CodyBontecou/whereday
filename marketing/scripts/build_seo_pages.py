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
        "content": '<section class="card"><h2><a href="/compare/google-maps-timeline/">Whereday and Google Timeline</a></h2><p>A comparison of recording controls, device storage, backup, export formats, and payment. Learn where each fits and why your existing Google archive needs to be kept separately.</p><a href="/compare/google-maps-timeline/">Read the Google Timeline comparison</a></section><section><h2>Moving an existing Google archive?</h2><p>Choose the <a href="/blog/import-google-timeline-iphone/">Google Timeline import walkthrough</a> if you already have a supported JSON file and want its visits and timed points in Whereday. Version 1.8 previews new records, approximate duplicates, and skipped entries before saving. Keep your original archive: importing selected records does not reproduce every Google activity or replace Google’s device-transfer backup.</p><p>For records already in Whereday, use the <a href="/blog/back-up-iphone-location-history/">backup and restore guide</a>. A GPX route for a viewer and a restorable journal copy are different outputs.</p></section><section><h2>New to recording your own history?</h2><p>Start with <a href="/blog/private-location-journal-iphone/">the private location journal guide</a>, or check <a href="/docs/export-import/">which files Whereday can export and restore</a>.</p></section>',
    },
    {
        "path": "/compare/google-maps-timeline/",
        "title": "Google Timeline Alternative for iPhone: Whereday Compared",
        "headline": "Whereday and Google Timeline on iPhone",
        "description": "Compare Whereday and Google Timeline for iPhone: accounts, local history, encrypted backup, GPX and CSV exports, pricing, and import limitations.",
        "lede": "Choose a location journal by the way you record, review, and keep a copy of your own history.",
        "source": "google-maps-timeline.html",
    },
    {
        "path": "/blog/view-location-history-iphone/",
        "section": "Guides",
        "section_path": "/blog/",
        "breadcrumb": "View location history",
        "type": "BlogPosting",
        "title": "How to See Your Location History on iPhone — Whereday",
        "headline": "How to see your location history on iPhone",
        "description": "Find iPhone location history in Apple Maps, Google Timeline or Whereday. Choose a download, check its dates and records, and keep the original before moving.",
        "modified": "2026-10-31",
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
DRAFTS = []



# Keep next steps specific to the task on each page.
PAGES.extend([
    {"path": "/tools/", "section": "Tools", "section_path": "/tools/", "title": "Free Location File Tools — Whereday", "headline": "Make sense of your location files", "description": "Inspect GPX routes and Google Timeline JSON locally with free browser tools. Check coordinates, timestamps and known structures without uploading your files.", "lede": "Practical tools for checking your own files before importing or converting them.", "modified": "2026-10-30", "content": '<section class="card"><h2><a href="/tools/gpx-viewer/">GPX viewer and file inspector</a></h2><p>Plot tracks and routes, check timestamps and invalid coordinates, and save CSV or GeoJSON with documented field limits. Your chosen file stays in your browser.</p><p><a href="/blog/check-gpx-file/">Follow the worked inspection tutorial</a> with an invented five-point example.</p><p>Have a different file type? <a href="/blog/gpx-kml-geojson-location-history/">Compare GPX, KML, GeoJSON and CSV</a> before choosing a destination. The current viewer accepts GPX; renaming a file does not convert it.</p></section><section class="card"><h2><a href="/tools/google-timeline-inspector/">Google Timeline JSON inspector</a></h2><p>Identify supported raw, semantic and device Timeline structures. Check coordinates and times, inspect findings and try invented examples before opening your own archive.</p><p><a href="/blog/google-timeline-json-formats/">Learn which Timeline JSON file you have</a> and why browser counts can differ from a native import preview. Your selected file stays in the tool page.</p></section>'},
    {"path": "/tools/gpx-viewer/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "GPX viewer", "title": "Free GPX Viewer and File Inspector — Whereday", "headline": "See your route. Understand your GPX.", "description": "View GPX files locally, check tracks and timestamps, and convert GPX to CSV or GeoJSON. Keep segments separate without uploading your location file.", "lede": "A free GPX viewer with clear file checks and a local route plot. Your file stays on your device.", "modified": "2026-10-30", "local_file_tool": True, "source": "gpx-viewer.html"},
    {"path": "/blog/check-gpx-file/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Check a GPX file", "type": "BlogPosting", "title": "Check a GPX File Before Importing It — Whereday", "headline": "Check your GPX before you import it", "description": "Inspect GPX points, segments and timestamps locally. Follow a five-point example, understand warnings, and check documented CSV and GeoJSON downloads.", "lede": "Start with a known example, check your file’s actual records, and keep the original before making changes.", "published": "2026-10-30", "modified": "2026-11-01", "source": "check-gpx-file.html"}
])

PAGES.append({
    "path": "/blog/gpx-kml-geojson-location-history/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Choose a file format", "type": "BlogPosting",
    "title": "GPX vs KML vs GeoJSON: Choose a Location File — Whereday",
    "headline": "GPX, KML or GeoJSON: choose a file you can use",
    "description": "Compare GPX, KML, GeoJSON and CSV for location history. Download the same invented trip in four formats and check coordinates, times and segment boundaries.",
    "lede": "Choose for your destination, check what the file preserves, and keep an original you can return to.",
    "published": "2026-10-30", "modified": "2026-10-30", "source": "gpx-kml-geojson-location-history.html"
})

PAGES.extend([
    {"path": "/tools/google-timeline-inspector/", "section": "Tools", "section_path": "/tools/", "breadcrumb": "Timeline JSON inspector", "title": "Google Timeline JSON Inspector and GPX Converter — Whereday", "headline": "Inspect Timeline JSON and convert supported records to GPX", "description": "Inspect Google Timeline JSON locally and convert supported records to GPX. Check coordinates, times, visits and path segments without uploading your file.", "lede": "Check your export, review supported records, and save GPX locally while keeping your original JSON.", "modified": "2026-10-30", "local_file_tool": True, "source": "google-timeline-inspector.html"},
    {"path": "/blog/google-timeline-json-formats/", "section": "Guides", "section_path": "/blog/", "breadcrumb": "Timeline JSON formats", "type": "BlogPosting", "title": "Which Google Timeline JSON File Do You Have? — Whereday", "headline": "Identify your Google Timeline JSON file", "description": "Identify raw, semantic and device Google Timeline JSON with worked examples. Check coordinates, times and unsupported records before importing.", "lede": "Look inside the export, compare a known example, and separate a format check from an app’s import preview.", "published": "2026-10-30", "modified": "2026-10-30", "source": "google-timeline-json-formats.html"}
])

RELATED = {
 '/blog/import-google-timeline-iphone/': [
     ('/blog/back-up-iphone-location-history/', 'Keep a restorable Whereday copy', 'Choose supported visit and point exports after checking the import.'),
     ('/compare/google-maps-timeline/', 'Compare recording workflows', 'Review access, backups, privacy, and portability before switching.')],
 '/integrations/dawarich/': [('/docs/webhooks/', 'Review send modes and queue limits', 'Check realtime, batching, retries, and manual-send behavior.'), ('/blog/back-up-iphone-location-history/', 'Keep a separate backup', 'A receiver copy is different from a restorable Whereday journal.')],
 '/blog/track-days-in-country-iphone/': [
     ('/blog/private-location-journal-iphone/', 'Record your next trip',
      'Start a local journal before you leave.'),
     ('/blog/back-up-iphone-location-history/', 'Keep the underlying records',
      'A country-date summary is not a complete backup.')],
 '/blog/view-location-history-iphone/': [('/blog/missing-location-history-iphone/',
                                          'Check missing Whereday records',
                                          'Diagnose recording gaps, hidden points, Watch '
                                          'transfers, and export filters.'),
                                         ('/blog/private-location-journal-iphone/',
                                          'Start a journal for future trips',
                                          'Set up recording before the next trip you want to '
                                          'remember.')],
 '/blog/record-iphone-route-gpx/': [('/blog/back-up-iphone-location-history/',
                                     'Keep a restorable copy',
                                     'GPX is a map file; retain supported visits and points '
                                     'separately for import.'),
                                    ('/blog/missing-location-history-iphone/',
                                     'Investigate an incomplete route',
                                     'Check recording state and filters before repeating the '
                                     'export.')],
 '/blog/back-up-iphone-location-history/': [('/blog/iphone-location-journal-obsidian/',
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
    image = BASE + page.get("image", "/screenshots/map.png")
    image_alt = page.get("image_alt", "Whereday map interface showing visits and route points")
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
    if page.get("local_file_tool"):
        analytics = ""
    return f'''<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
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
