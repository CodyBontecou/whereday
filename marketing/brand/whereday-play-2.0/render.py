#!/usr/bin/env python3
"""Render the "Whereday — Google Play" design to PNGs, one folder per Play listing language.

The design lives in the claude.ai Design artifact "Whereday — Google Play". Download its
project/*.dc.html files and dc-runtime.js into --design, then run this script. Every visible
English string in the shots and in the Android screens they embed is replaced from
translations/LOCALE.json, so each language shows its own captions and its own app UI.

Usage: render.py --design DIR --out DIR [--locales es,ja,...] [--only phone,feature-graphic]
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
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "whereday-appstore-2.0"))
from render import CHROME, JS_STRING, TEXT_NODE, WEEKDAY_ARRAY  # noqa: E402

# (artboard, output folder, output name, width, height); names match the 2.0.0 en-US upload.
SHOTS = [
    ("FeatureGraphic", "feature-graphic", "feature-graphic", 1024, 500),
    *[(name, "phone", f"{n:02d}-{name}", 1080, 1920) for n, name in enumerate([
        "Phone01Today", "Phone02Trace", "Phone03Atlas", "PhoneWeek", "Phone04Place", "Phone05Archive",
        "Phone07Night", "Phone08Glance"], start=1)],
    *[(f"Tab7{name}", "tablet-7", f"{n:02d}-{name.lower()}", 1920, 1200) for n, name in
      enumerate(["Today", "Atlas", "Night"], start=1)],
    *[(f"Tab10{name}", "tablet-10", f"{n:02d}-{name.lower()}", 2560, 1600) for n, name in
      enumerate(["Today", "Atlas", "Night"], start=1)],
]
# Corrections to the design's English, applied to every language including en. New installs need a
# plan before they can use the app at all, so the store art must not promise anything free.
DESIGN_FIXES = {"Recording, imports and export previews are free.": "Every plan includes exports."}
TRANSLATIONS = HERE / "translations"
LOCALES = sorted(path.stem for path in TRANSLATIONS.glob("*.json"))
# BCP 47 language for <html lang>, which picks the right system fallback fonts.
HTML_LANG = {"ar": "ar", "bn": "bn", "es": "es", "fr": "fr", "hi": "hi", "ja": "ja", "pl": "pl", "pt-BR": "pt-BR",
             "ru": "ru", "zh-Hans": "zh-Hans", "en": "en"}
# The headlines are set for Latin words; CJK breaks cleanly at a smaller size.
HEADLINE_SCALE = {"ja": 0.8, "zh-Hans": 0.8}


@functools.lru_cache(maxsize=None)
def translation(locale: str) -> dict:
    return json.loads((TRANSLATIONS / f"{locale}.json").read_text(encoding="utf-8"))


def translate(source: str, locale: str) -> str:
    data = translation(locale)
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
    if locale in HEADLINE_SCALE:
        rest = re.sub(r"(<h1[^>]*font-size: )(\d+)px",
                      lambda m: f"{m.group(1)}{round(int(m.group(2)) * HEADLINE_SCALE[locale])}px", rest)
    if data.get("weekdays"):
        script = WEEKDAY_ARRAY.sub("[" + ", ".join(f"'{day}'" for day in data["weekdays"]) + "]", script)
    script = JS_STRING.sub(literal, script)
    head = re.sub(r'<html lang="[^"]*">', f'<html lang="{HTML_LANG[locale]}">', head, count=1)
    source = head + sep + helmet + rest + sep2 + script
    if locale == "ar":
        # IBM Plex Mono has no Arabic and its fallback draws letters unjoined; tracking pulls them apart too.
        source = re.sub(r"IBM Plex Mono('?)", r"IBM Plex Mono\1, Geeza Pro", source)
        source = re.sub(r"letter-spacing:\s*[^;\"'}]+", "letter-spacing: 0", source)
    return source


# Translations change text lengths, so after the page renders this pass keeps the layout intact:
# headlines stay within two lines, handwritten notes stay on the canvas, and a note that collides
# with the caption text first shrinks the headline, then moves below it. English is left as designed.
LAYOUT_FIX = """<script>
(function () {
  function tight(el) { var r = document.createRange(); r.selectNodeContents(el); return r.getBoundingClientRect(); }
  function hit(a, b) { return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom; }
  function fix() {
    var W = document.documentElement.clientWidth, H = document.documentElement.clientHeight, M = 20;
    var heads = Array.prototype.slice.call(document.querySelectorAll('h1'));
    heads.forEach(function (h) {
      if (!h.dataset.base) h.dataset.base = parseFloat(getComputedStyle(h).fontSize);
      var base = parseFloat(h.dataset.base), size = base;
      h.style.fontSize = base + 'px';
      var cs = getComputedStyle(h);
      var lh = parseFloat(cs.lineHeight) || size;
      while (h.getBoundingClientRect().height > lh * (size / base) * 2.4 && size > base * 0.6) {
        size *= 0.95; h.style.fontSize = size + 'px';
      }
    });
    var notes = Array.prototype.filter.call(document.querySelectorAll('span,div,p'), function (el) {
      var cs = getComputedStyle(el);
      return cs.position === 'absolute' && cs.fontFamily.indexOf('Caveat') >= 0 && el.textContent.trim();
    });
    notes.forEach(function (n) {
      n.style.translate = '0px 0px';
      if (!n.dataset.base) n.dataset.base = parseFloat(getComputedStyle(n).fontSize);
      n.style.fontSize = n.dataset.base + 'px';
      var r = n.getBoundingClientRect(), dx = 0, dy = 0;
      if (r.right > W - M) {  // shrink a long note into its space before moving it
        n.style.fontSize = (parseFloat(n.dataset.base) * Math.max(0.7, (W - M - r.left) / r.width)) + 'px';
        r = n.getBoundingClientRect();
      }
      if (r.right > W - M) dx = W - M - r.right;
      if (r.left + dx < M) dx = M - r.left;
      if (r.bottom > H - M) dy = H - M - r.bottom;
      if (r.top + dy < M) dy = M - r.top;
      n.style.translate = dx + 'px ' + dy + 'px';
      heads.forEach(function (h) {
        var texts = Array.prototype.slice.call(h.parentElement.children).filter(function (c) { return c.textContent.trim(); });
        var size = parseFloat(getComputedStyle(h).fontSize), base = parseFloat(h.dataset.base);
        while (hit(n.getBoundingClientRect(), tight(h)) && size > base * 0.6) { size *= 0.95; h.style.fontSize = size + 'px'; }
        texts.forEach(function (t) {
          var nr = n.getBoundingClientRect(), tr = tight(t);
          if (hit(nr, tr)) {
            dy += tr.bottom + 8 - nr.top;
            if (nr.bottom + (tr.bottom + 8 - nr.top) > H - M) dy -= (nr.bottom + (tr.bottom + 8 - nr.top)) - (H - M);
            n.style.translate = dx + 'px ' + dy + 'px';
          }
        });
      });
    });
  }
  window.addEventListener('load', function () {
    document.fonts.ready.then(function () { setTimeout(fix, 2500); setTimeout(fix, 6000); });
  });
})();
</script>"""


def prepare(design: Path, root: Path, locale: str) -> Path:
    folder = root / locale
    folder.mkdir(parents=True, exist_ok=True)
    shutil.copy(design / "dc-runtime.js", folder / "support.js")
    for path in design.glob("*.dc.html"):
        source = path.read_text().replace('src="./dc-runtime.js"', 'src="./support.js"')
        for old, new in DESIGN_FIXES.items():
            source = source.replace(old, new)
        if locale != "en":
            source = translate(source, locale)
            if path.name.removesuffix(".dc.html") in {name for name, *_ in SHOTS}:
                source = source.replace("</body>", LAYOUT_FIX + "\n</body>", 1)
        (folder / path.name).write_text(source)
    return folder


def render(url: str, destination: Path, width: int, height: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    command = [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
               f"--window-size={width},{height}", "--virtual-time-budget=12000", f"--screenshot={destination}", url]
    for attempt in range(4):  # headless Chrome sometimes exits early when several start at once
        result = subprocess.run(command, capture_output=True)
        if result.returncode == 0:
            return
        time.sleep(2 * (attempt + 1))
    result.check_returncode()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--design", type=Path, required=True, help="folder with *.dc.html and dc-runtime.js")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--work", type=Path, help="scratch folder for translated HTML (default: OUT/.work)")
    parser.add_argument("--locales", default=",".join(LOCALES))
    parser.add_argument("--only", help="comma-separated output folders, e.g. phone,feature-graphic")
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
        futures = {pool.submit(render, f"http://127.0.0.1:{port}/{locale}/{name}.dc.html",
                               args.out / locale / folder / f"{slug}.png", width, height): (locale, slug)
                   for locale, (name, folder, slug, width, height) in jobs}
        for future in concurrent.futures.as_completed(futures):
            future.result()
            print("rendered", *futures[future], flush=True)
    server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
