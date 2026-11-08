#!/usr/bin/env python3
"""Render the "One Long Day" App Store design to PNGs, one folder per App Store locale.

The design lives in the claude.ai Design artifact "Whereday — App Store: One Long Day"
(https://claude.ai/artifact/RC1KseV3uhjZ27tA7n8jVJ). Download its project/*.dc.html files
and artifact-type/dc-runtime.js into --design, then run this script. Overlay copy is
translated from translations.py, and the app screens inside the frames from
screen-translations/LOCALE.json.

Usage: render.py --design DIR --out DIR [--locales en-US,ja,...]
"""

import argparse
import concurrent.futures
import functools
import html
import http.server
import json
import re
import shutil
import subprocess
import threading
from pathlib import Path

from translations import EXPORT_SENTENCE, KICKER_TOKENS, LOCALES, TEXT

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# (artboard, output folder, output name, width, height)
SHOTS = [
    # Store order. Map-first: 01 map home, 02 map replay, 04 the week on one map; the Specimen jar shot is out.
    *[(artboard, "iphone-69", f"{n:02d}-{slug}", 1320, 2868) for n, (artboard, slug) in enumerate([
        ("ShotB01Today", "map-today"), ("ShotB02Trace", "replay"), ("ShotB03Atlas", "atlas"), ("ShotBWeek", "week"),
        ("ShotB04Place", "place"), ("ShotB05Recipe", "export"), ("ShotB07Night", "night"), ("ShotB08Lock", "lock-screen"),
        ("ShotB09Glance", "glance"), ("ShotB10Recap", "month-recap")], start=1)],
    *[(f"iPadShot{n:02d}{name}", "ipad-13", f"{n:02d}-{name.lower()}", 2752, 2064) for n, name in [
        (1, "Today"), (2, "Atlas"), (3, "Places"), (4, "Archive"), (5, "Private")]],
    *[(f"DuoShot{n:02d}{name}", "duo-inner", f"{n:02d}-{name.lower()}", 2853, 2007) for n, name in [
        (1, "Today"), (2, "Tabletop"), (3, "Atlas"), (4, "Places"), (5, "Archive"), (6, "Private"), (7, "Cover")]],
    ("DuoOuter01Cover", "duo-outer", "01-cover", 1398, 2034),
    ("StoreHeaderB", "header", "01-header", 3840, 1646),
]

HTML_LANG = {"ar-SA": "ar", "es-ES": "es", "fr-FR": "fr", "hi": "hi", "ja": "ja", "pt-BR": "pt-BR", "ru": "ru",
             "zh-Hans": "zh-Hans", "en-US": "en", "bn-BD": "bn", "pl": "pl"}
# Headlines are set for ~7 Latin words per line; CJK needs a smaller size to break cleanly.
HEADLINE_SCALE = {"ja": 0.74, "zh-Hans": 0.74}
# Fixes to the design itself, applied to every locale: the header's "home 23:30" note started
# behind the second phone, so its first letters were hidden even in English.
DESIGN_FIXES = {
    "StoreHeaderB": [("left: 3420px; top: 1380px;", "right: 70px; top: 1380px; z-index: 5;")],
}
# The Apple Watch slot uses the two watch screens from the Watch board, without bezel or corner
# rounding, scaled to the 416x496 Series 10 size.
WATCH_SCREEN = ("border-radius: 44px; background: #000; box-shadow: 0 0 0 10px #2A2A2A, 0 12px 30px rgba(0,0,0,0.25);",
                "border-radius: 0; background: #000; box-shadow: none;")
WATCH_SLUGS = ["01-day-dial", "02-route"]
WATCH_SCALE = 4
TEXT_NODE = re.compile(r">([^<>]+)<")
UNDERLINE = re.compile(r'<span style="(text-decoration: underline;[^"]*)">')

# The app screens drawn inside the frames. Their labels, dates and durations are translated from
# screen-translations/LOCALE.json, so each locale shows the app as it looks in that language.
# Place, street and format names stay as drawn.
SCREENS = {"Atlas", "DuoArchive", "DuoAtlas", "DuoCoverMap", "DuoMapHome", "DuoPlaces", "DuoSettings",
           "DuoTabletopMap", "LockScreen", "MapHome", "MonthRecap", "Place", "Recipe", "Watch", "Widgets"}
SCREEN_TRANSLATIONS = Path(__file__).parent / "screen-translations"
JS_STRING = re.compile(r"'((?:[^'\\\n]|\\.)*)'")
WEEKDAY_ARRAY = re.compile(r"\['M',\s*'T',\s*'W',\s*'T',\s*'F',\s*'S',\s*'S'\]")


@functools.lru_cache(maxsize=None)
def screen_translation(locale: str) -> dict:
    return json.loads((SCREEN_TRANSLATIONS / f"{locale}.json").read_text(encoding="utf-8"))


def translate_screen(source: str, locale: str) -> str:
    data = screen_translation(locale)
    strings = data["strings"]

    def node(match: re.Match) -> str:
        raw = match.group(1)
        text = html.unescape(raw.strip())
        if text not in strings:
            return match.group(0)
        lead = raw[:len(raw) - len(raw.lstrip())]
        trail = raw[len(raw.rstrip()):]
        return ">" + lead + html.escape(strings[text], quote=False) + trail + "<"

    def literal(match: re.Match) -> str:
        text = match.group(1)
        if text not in strings:
            return match.group(0)
        return "'" + strings[text].replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    head, sep, tail = source.partition("<x-dc>")
    body, sep2, script = tail.partition("</x-dc>")
    helmet_end = body.find("</helmet>")
    helmet, rest = (body[:helmet_end], body[helmet_end:]) if helmet_end >= 0 else ("", body)
    rest = TEXT_NODE.sub(node, rest)
    weekdays = "[" + ", ".join("'" + day + "'" for day in data["weekdays"]) + "]"
    script = WEEKDAY_ARRAY.sub(weekdays, script)
    script = JS_STRING.sub(literal, script)
    head = head.replace('<html lang="en">', f'<html lang="{HTML_LANG[locale]}">', 1)
    return head + sep + helmet + rest + sep2 + script


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
        if locale != "en-US" and path.name.removesuffix(".dc.html") in SCREENS:
            source = translate_screen(source, locale)
        if locale == "ar-SA":
            # IBM Plex Mono has no Arabic, and the monospace fallback draws Arabic letters unjoined. Geeza Pro
            # (the system Arabic face) picks up the Arabic; tracking would pull the letters apart again.
            source = re.sub(r"IBM Plex Mono('?)", r"IBM Plex Mono\1, Geeza Pro", source)
            source = re.sub(r"letter-spacing:\s*[^;\"'}]+", "letter-spacing: 0", source)
        (folder / path.name).write_text(source)
        if path.name == "Watch.dc.html":
            assert source.count(WATCH_SCREEN[0]) == 2
            (folder / "WatchExport.dc.html").write_text(source.replace(*WATCH_SCREEN))
    return folder


def render(url: str, destination: Path, width: int, height: int, scale: int = 1) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    command = [
        CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--force-device-scale-factor={scale}",
        f"--window-size={width},{height}", "--virtual-time-budget=12000", f"--screenshot={destination}", url,
    ]
    # Headless Chrome occasionally exits 2 when several instances start at once; a retry renders fine.
    for attempt in range(3):
        result = subprocess.run(command, capture_output=True)
        if result.returncode == 0:
            return
    result.check_returncode()


def render_watch(url: str, folder: Path) -> None:
    """Crop each pure-black watch screen out of the board and scale it to the App Store size."""
    from PIL import Image

    board_path = folder / ".board.png"
    render(url, board_path, 520, 400, WATCH_SCALE)
    board = Image.open(board_path).convert("RGB")
    board_path.unlink()
    mask = board.convert("L").point(lambda v: 255 if v < 6 else 0)
    half = board.width // 2
    for (left, right), slug in zip([(0, half), (half, board.width)], WATCH_SLUGS):
        x0, y0, x1, y1 = mask.crop((left, 0, right, board.height)).getbbox()
        assert (x1 - x0, y1 - y0) == (198 * WATCH_SCALE, 242 * WATCH_SCALE), (slug, x1 - x0, y1 - y0)
        screen = board.crop((left + x0, y0, left + x1, y1)).resize((416, 496), Image.LANCZOS)
        screen.save(folder / f"{slug}.png")


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
        if not only or "watch" in only:
            futures.update({pool.submit(render_watch, f"http://127.0.0.1:{port}/{locale}/WatchExport.dc.html",
                                        args.out / locale / "watch"): (locale, "watch") for locale in locales})
        for future in concurrent.futures.as_completed(futures):
            future.result()
            print("rendered", *futures[future], flush=True)
    server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
