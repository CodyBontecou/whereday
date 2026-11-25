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


def build_kmz_samples():
    """Write the synthetic KMZ examples next to the KML they wrap. Stored is byte-for-byte reproducible;
    the deflate file is only rewritten when its doc.kml no longer matches, so zlib versions don't churn it."""
    import io, struct, zipfile, zlib
    kml = (PUBLIC / "samples/synthetic-kml-viewer.kml").read_bytes()
    chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\x00\xc8\xc8\xc8")) + chunk(b"IEND", b"")
    for name, method in (("synthetic-kml-viewer.kmz", zipfile.ZIP_DEFLATED), ("synthetic-kml-viewer-stored.kmz", zipfile.ZIP_STORED)):
        target = PUBLIC / "samples" / name
        if target.exists():
            with zipfile.ZipFile(target) as z:
                if z.read("doc.kml") == kml and [i.compress_type for i in z.infolist()][0] == method and "files/legend.png" in z.namelist():
                    continue
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as z:
            for entry, data in (("doc.kml", kml), ("files/legend.png", png)):
                info = zipfile.ZipInfo(entry, (2000, 1, 1, 0, 0, 0))
                info.compress_type = method
                info.external_attr = 0o644 << 16
                z.writestr(info, data)
        target.write_bytes(buffer.getvalue())


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
    country_days = (PUBLIC / "samples/synthetic-country-days.csv").read_bytes().decode("utf-8")
    merge_samples = [{"name": f"synthetic-walk-day-{day}.gpx", "text": (PUBLIC / f"samples/synthetic-walk-day-{day}.gpx").read_text()} for day in (1, 2, 3)]
    (PUBLIC / "assets/gpx-merge-demo.mjs").write_text("export const MERGE_SAMPLES = " + json.dumps(merge_samples) + ";\n")
    editor_sample = {"name": "synthetic-two-day-walk.gpx", "text": (PUBLIC / "samples/synthetic-two-day-walk.gpx").read_text()}
    (PUBLIC / "assets/gpx-editor-demo.mjs").write_text("export const EDITOR_SAMPLE = " + json.dumps(editor_sample) + ";\n")
    csv_gpx = {key: {"name": name, "text": (PUBLIC / "samples" / name).read_bytes().decode("utf-8")} for key, name in (("csv", "synthetic-format-trip.csv"), ("gpx", "synthetic-format-trip.gpx"))}
    (PUBLIC / "assets/csv-gpx-demo.mjs").write_text("export const CSV_GPX_SAMPLES = " + json.dumps(csv_gpx) + ";\n")
    kml_gpx = {key: {"name": name, "text": (PUBLIC / "samples" / name).read_text()} for key, name in (("kml", "synthetic-kml-to-gpx.kml"), ("gpx", "synthetic-kml-to-gpx.expected.gpx"))}
    (PUBLIC / "assets/kml-gpx-demo.mjs").write_text("export const KML_GPX_SAMPLES = " + json.dumps(kml_gpx) + ";\n")
    build_kmz_samples()
    import base64
    viewer_samples = {"kml": {"name": "synthetic-kml-viewer.kml", "text": (PUBLIC / "samples/synthetic-kml-viewer.kml").read_text()}}
    for key, name in (("kmz", "synthetic-kml-viewer.kmz"), ("kmzStored", "synthetic-kml-viewer-stored.kmz")):
        viewer_samples[key] = {"name": name, "base64": base64.b64encode((PUBLIC / "samples" / name).read_bytes()).decode("ascii")}
    (PUBLIC / "assets/kml-viewer-demo.mjs").write_text("export const KML_VIEWER_SAMPLES = " + json.dumps(viewer_samples) + ";\n")
    geojson_sample = {"name": "synthetic-geojson-viewer.geojson", "text": (PUBLIC / "samples/synthetic-geojson-viewer.geojson").read_text()}
    (PUBLIC / "assets/geojson-viewer-demo.mjs").write_text("export const GEOJSON_VIEWER_SAMPLE = " + json.dumps(geojson_sample) + ";\n")
    countries_trip = (PUBLIC / "samples/synthetic-countries-trip.gpx").read_text()
    (PUBLIC / "assets/visited-countries-demo.mjs").write_text("export const VISITED_DEMO = " + json.dumps({"name": "synthetic-countries-trip.gpx", "text": countries_trip}) + ";\n")
    (PUBLIC / "assets/country-days-demo.mjs").write_text("export const DEMO_COUNTRY_DAYS = " + json.dumps(country_days) + ";\n")
    (PUBLIC / "assets/timeline-demo.mjs").write_text("export const TIMELINE_EXAMPLES = " + json.dumps(examples) + ";\n")
    from build_seo_pages import DRAFTS, PAGES
    revised = {page["path"]: page["modified"] for page in PAGES if "modified" in page}
    revised.update({path: "2026-10-30" for path in ("/", "/blog/", "/zh-hans/", "/docs/export-import/", "/about", "/blog/private-location-journal-iphone/", "/docs/places/", "/docs/automatic-exports/", "/docs/timeline-outings/", "/docs/apple-watch/", "/docs/getting-started/", "/docs/privacy-data/", "/docs/siri-shortcuts/", "/privacy")})
    # Map-first home: pages that now describe or show the map as the app's home screen.
    revised.update({path: "2026-11-05" for path in ("/", "/zh-hans/", "/docs/getting-started/", "/docs/tracking/", "/docs/timeline-outings/", "/docs/places/")})
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
