#!/usr/bin/env python3
"""Build generated pages, maintain sitemap coverage, and validate the site."""
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

from check_site import BASE, PUBLIC, check, page_url

SCRIPTS = Path(__file__).resolve().parent
REVIEWED = "2026-10-26"
UPDATED_PATHS = {
    "/integrations/", "/integrations/dawarich/", "/zh-hans/",
    "/", "/blog/", "/blog/private-location-journal-iphone/", "/privacy",
    "/docs/", "/docs/getting-started/", "/docs/tracking/",
    "/docs/export-import/", "/docs/privacy-data/", "/docs/siri-shortcuts/", "/docs/places/",
    "/compare/", "/compare/google-maps-timeline/",
    "/blog/view-location-history-iphone/", "/blog/record-iphone-route-gpx/",
    "/blog/iphone-location-journal-obsidian/",
    "/blog/track-days-in-country-iphone/",
    "/blog/missing-location-history-iphone/",
    "/blog/iphone-location-tracking-battery/",
    "/blog/back-up-iphone-location-history/", "/docs/apple-watch/", "/docs/webhooks/", "/docs/timeline-outings/",
}


def build():
    for script in ("build_journal_bundle.py", "build_docs.py", "build_seo_pages.py", "build_agentic.py"):
        subprocess.run([sys.executable, str(SCRIPTS / script)], check=True)

    from build_seo_pages import PAGES
    local_file_paths = {page["path"] for page in PAGES if page.get("local_file_tool")}
    # Local-file tools exclude analytics; their Worker response also denies connections.
    # Include the gated acquisition listener on other public pages.
    acquisition = '<script defer src="/assets/acquisition.js"></script>'
    for page in PUBLIC.rglob('*.html'):
        content = page.read_text()
        if page_url(page).removeprefix(BASE) in local_file_paths:
            if acquisition in content:
                page.write_text(content.replace(acquisition, ""))
            continue
        if acquisition not in content:
            page.write_text(content.replace('</body>', acquisition + '\n</body>'))

    import json
    example = (PUBLIC / "samples/synthetic-app-export.gpx").read_text()
    (PUBLIC / "assets/gpx-demo.mjs").write_text("export const DEMO_GPX = " + json.dumps(example) + ";\n")
    examples = {key: (PUBLIC / path).read_text() for key, path in {
        "device": "samples/synthetic-google-timeline.json", "raw": "samples/synthetic-google-raw.json", "semantic": "samples/synthetic-google-semantic.json"
    }.items()}
    (PUBLIC / "assets/timeline-demo.mjs").write_text("export const TIMELINE_EXAMPLES = " + json.dumps(examples) + ";\n")
    from build_seo_pages import DRAFTS, PAGES
    revised = {page["path"]: page["modified"] for page in PAGES if "modified" in page}
    revised.update({path: "2026-10-30" for path in ("/", "/blog/", "/zh-hans/", "/docs/export-import/", "/about", "/blog/private-location-journal-iphone/", "/docs/places/", "/docs/automatic-exports/", "/docs/timeline-outings/", "/docs/apple-watch/", "/docs/getting-started/", "/docs/privacy-data/", "/docs/siri-shortcuts/", "/privacy")})
    for draft in DRAFTS:
        if (PUBLIC / draft['path'].strip('/') / 'index.html').exists():
            raise ValueError(f"Release-gated draft leaked into public/: {draft['path']}")

    namespace = "http://www.sitemaps.org/schemas/sitemap/0.9"
    ET.register_namespace("", namespace)
    sitemap_path = PUBLIC / "sitemap.xml"
    previous = ET.parse(sitemap_path)
    dates = {}
    for entry in previous.findall("{*}url"):
        loc, date = entry.find("{*}loc"), entry.find("{*}lastmod")
        if loc is not None and date is not None:
            dates[loc.text] = date.text
    sitemap = ET.Element(f"{{{namespace}}}urlset")
    for url in sorted(page_url(path) for path in PUBLIC.rglob("*.html") if path.name != "404.html"):
        entry = ET.SubElement(sitemap, f"{{{namespace}}}url")
        ET.SubElement(entry, f"{{{namespace}}}loc").text = url
        path = url.removeprefix(BASE)
        date = revised.get(path) or ("2026-10-29" if path in {"/", "/privacy", "/terms", "/docs/privacy-data/"} else (
            REVIEWED if path in UPDATED_PATHS else dates.get(url)
        ))
        if date:
            ET.SubElement(entry, f"{{{namespace}}}lastmod").text = date
    ET.indent(sitemap, space="  ")
    sitemap_path.write_bytes(ET.tostring(sitemap, encoding="utf-8", xml_declaration=True) + b"\n")
    subprocess.run([sys.executable, str(SCRIPTS / "check_format_samples.py")], check=True)
    check()


if __name__ == "__main__":
    build()
