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
    "/", "/blog/", "/blog/private-location-journal-iphone/", "/privacy",
    "/docs/", "/docs/getting-started/", "/docs/tracking/",
    "/docs/export-import/", "/docs/privacy-data/",
    "/compare/", "/compare/google-maps-timeline/",
    "/blog/view-location-history-iphone/", "/blog/record-iphone-route-gpx/",
    "/blog/iphone-location-journal-obsidian/",
    "/blog/missing-location-history-iphone/",
    "/blog/iphone-location-tracking-battery/",
    "/blog/back-up-iphone-location-history/", "/docs/apple-watch/", "/docs/webhooks/", "/docs/timeline-outings/",
}


def build():
    for script in ("build_docs.py", "build_seo_pages.py", "build_agentic.py"):
        subprocess.run([sys.executable, str(SCRIPTS / script)], check=True)

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
        date = REVIEWED if url.removeprefix(BASE) in UPDATED_PATHS else dates.get(url)
        if date:
            ET.SubElement(entry, f"{{{namespace}}}lastmod").text = date
    ET.indent(sitemap, space="  ")
    sitemap_path.write_bytes(ET.tostring(sitemap, encoding="utf-8", xml_declaration=True) + b"\n")
    check()


if __name__ == "__main__":
    build()
