#!/usr/bin/env python3
"""Render the "One Long Day" App Store design to PNGs, one folder per App Store locale.

The design lives in the claude.ai Design artifact "Whereday — App Store: One Long Day"
(https://claude.ai/artifact/RC1KseV3uhjZ27tA7n8jVJ). Download its project/*.dc.html files
and artifact-type/dc-runtime.js into --design, then run this script. Overlay copy is
translated from translations.py; the mock app screens inside the frames stay as designed.

Usage: render.py --design DIR --out DIR [--locales en-US,ja,...]
"""

import argparse
import concurrent.futures
import functools
import html
import http.server
import re
import shutil
import subprocess
import threading
from pathlib import Path

from translations import EXPORT_SENTENCE, KICKER_TOKENS, LOCALES, TEXT

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# (artboard, output folder, output name, width, height)
SHOTS = [
    *[(f"ShotB{n:02d}{name}", "iphone-69", f"{n:02d}-{slug}", 1320, 2868) for n, name, slug in [
        (1, "Today", "today"), (2, "Trace", "trace"), (3, "Atlas", "atlas"), (4, "Place", "place"),
        (5, "Recipe", "export"), (6, "Private", "private"), (7, "Night", "night"), (8, "Lock", "lock-screen"),
        (9, "Glance", "glance"), (10, "Recap", "month-recap")]],
    *[(f"iPadShot{n:02d}{name}", "ipad-13", f"{n:02d}-{name.lower()}", 2752, 2064) for n, name in [
        (1, "Today"), (2, "Atlas"), (3, "Places"), (4, "Archive"), (5, "Private")]],
    *[(f"DuoShot{n:02d}{name}", "duo-inner", f"{n:02d}-{name.lower()}", 2853, 2007) for n, name in [
        (1, "Today"), (2, "Tabletop"), (3, "Atlas"), (4, "Places"), (5, "Archive"), (6, "Private"), (7, "Cover")]],
    ("DuoOuter01Cover", "duo-outer", "01-cover", 1398, 2034),
    ("StoreHeaderB", "header", "01-header", 3840, 1646),
]

HTML_LANG = {"ar-SA": "ar", "es-ES": "es", "fr-FR": "fr", "hi": "hi", "ja": "ja", "pt-BR": "pt-BR", "ru": "ru",
             "zh-Hans": "zh-Hans", "en-US": "en"}
# Headlines are set for ~7 Latin words per line; CJK needs a smaller size to break cleanly.
HEADLINE_SCALE = {"ja": 0.74, "zh-Hans": 0.74}
# Fixes to the design itself, applied to every locale: the header's "home 23:30" note started
# behind the second phone, so its first letters were hidden even in English.
DESIGN_FIXES = {
    "StoreHeaderB": [("left: 3420px; top: 1380px;", "right: 70px; top: 1380px; z-index: 5;")],
}
TEXT_NODE = re.compile(r">([^<>]+)<")
UNDERLINE = re.compile(r'<span style="(text-decoration: underline;[^"]*)">')


def strip_blob_fonts(source: str) -> str:
    """The design's own Inter/Newsreader faces point at artifact-only /_blob URLs; the Google Fonts link covers them."""
    source = re.sub(r"/\*[^*]*\*/\s*@font-face\s*\{[^}]*/_blob/[^}]*\}", "", source)
    return re.sub(r"@font-face\s*\{[^}]*/_blob/[^}]*\}", "", source)


def translate_shot(source: str, locale: str) -> str:
    tokens = sorted(KICKER_TOKENS[locale].items(), key=lambda item: -len(item[0]))

    def node(match: re.Match) -> str:
        raw = match.group(1)
        text = html.unescape(raw.strip())
        if not text:
            return match.group(0)
        if text in TEXT:
            return ">" + html.escape(TEXT[text][locale], quote=False) + "<"
        if re.fullmatch(r"[0-9A-Z :·.→]+", text) and any(key in text for key, _ in tokens):
            for key, value in tokens:
                text = re.sub(rf"(?<![A-Z]){re.escape(key)}(?![A-Z])", value, text)
            return ">" + html.escape(text, quote=False) + "<"
        return match.group(0)

    head, sep, tail = source.partition("<x-dc>")
    body, sep2, script = tail.partition("</x-dc>")

    export = re.search(r"(<h1[^>]*>)(Export my .*?)(</h1>)", body, re.S)
    if export:
        style = UNDERLINE.search(export.group(2)).group(1)
        template, a, b, c = EXPORT_SENTENCE[locale]
        parts = {key: f'<span style="{style}">{html.escape(value)}</span>' for key, value in
                 (("a", a), ("b", b), ("c", c))}
        body = body[:export.start(2)] + html.escape(template).format(**parts) + body[export.end(2):]

    # Leave the <helmet> styles alone; translate only rendered text.
    helmet_end = body.find("</helmet>")
    helmet, rest = (body[:helmet_end], body[helmet_end:]) if helmet_end >= 0 else ("", body)
    rest = TEXT_NODE.sub(node, rest)
    rest = style_headline(rest, locale)
    head = head.replace('<html lang="en">', f'<html lang="{HTML_LANG[locale]}">', 1)
    return head + sep + helmet + rest + sep2 + script


def style_headline(body: str, locale: str) -> str:
    """Only the overlay headline block changes: the mock app screens keep their own layout."""
    h1 = body.find("<h1")
    if h1 < 0:
        return body
    if locale in HEADLINE_SCALE:
        end = body.find(">", h1)
        tag = re.sub(r"font-size: (\d+)px",
                      lambda m: f"font-size: {round(int(m.group(1)) * HEADLINE_SCALE[locale])}px; line-break: strict",
                      body[h1:end], count=1)
        body = body[:h1] + tag + body[end:]
    if locale == "ar-SA":
        # Right-to-left, but left-aligned like the design, so it never runs into artwork on the right.
        container = body.rfind('<div style="', 0, h1) + len('<div style="')
        body = body[:container] + "direction: rtl; text-align: left; " + body[container:]
    return body


def prepare(design: Path, root: Path, locale: str) -> Path:
    folder = root / locale
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copy(design / "dc-runtime.js", folder / "support.js")
    shots = {name for name, *_ in SHOTS}
    for path in design.glob("*.dc.html"):
        source = strip_blob_fonts(path.read_text())
        for old, new in DESIGN_FIXES.get(path.name.removesuffix(".dc.html"), []):
            assert old in source, (path.name, old)
            source = source.replace(old, new)
        if locale != "en-US" and path.name.removesuffix(".dc.html") in shots:
            source = translate_shot(source, locale)
        (folder / path.name).write_text(source)
    return folder


def render(url: str, destination: Path, width: int, height: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
        f"--window-size={width},{height}", "--virtual-time-budget=12000", f"--screenshot={destination}", url,
    ], check=True, capture_output=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--design", type=Path, required=True, help="folder with *.dc.html and dc-runtime.js")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--work", type=Path, help="scratch folder for translated HTML (default: OUT/.work)")
    parser.add_argument("--locales", default=",".join(["en-US", *LOCALES]))
    parser.add_argument("--only", help="comma-separated output folders to render, e.g. header,iphone-69")
    args = parser.parse_args()

    work = args.work or args.out / ".work"
    locales = [locale.strip() for locale in args.locales.split(",") if locale.strip()]
    for locale in locales:
        prepare(args.design, work, locale)

    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(work))
    http.server.SimpleHTTPRequestHandler.log_message = lambda *_: None
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_address[1]

    only = set(args.only.split(",")) if args.only else None
    jobs = [(locale, shot) for locale in locales for shot in SHOTS if not only or shot[1] in only]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = {
            pool.submit(render, f"http://127.0.0.1:{port}/{locale}/{name}.dc.html",
                        args.out / locale / folder / f"{slug}.png", width, height): (locale, slug)
            for locale, (name, folder, slug, width, height) in jobs
        }
        for future in concurrent.futures.as_completed(futures):
            future.result()
            print("rendered", *futures[future], flush=True)
    server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
