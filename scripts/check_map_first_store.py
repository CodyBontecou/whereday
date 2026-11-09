#!/usr/bin/env python3
"""Check local 2.0 Apple drafts without rendering or contacting either store.

Run with: python3 scripts/check_map_first_store.py
This validates structure, not translation quality or current App Store state.
"""

from collections import Counter
import importlib.util
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "marketing/brand/whereday-appstore-2.0"
LOCALES = {
    "ar-SA", "bn-BD", "en-US", "es-ES", "fr-FR", "hi", "ja", "pl",
    "pt-BR", "ru", "zh-Hans",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    # Loading the renderer reads its constants only; rendering lives in main().
    sys.path.insert(0, str(BRAND))
    spec = importlib.util.spec_from_file_location("store_render", BRAND / "render.py")
    renderer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)
    require(set(renderer.LOCALES) | {"en-US"} == LOCALES, "Artwork locale coverage")
    drafts = ROOT / "docs/release-drafts/2.0"
    notes = read_json(drafts / "whats-new.json")
    require(set(notes) == LOCALES, "What's New locale coverage")
    listings = ROOT / "metadata/whereday/version/2.0"
    require({p.stem for p in listings.glob("*.json")} == LOCALES, "Listing locale coverage")
    for locale in sorted(LOCALES):
        listing = read_json(listings / f"{locale}.json")
        app_info = read_json(ROOT / f"metadata/whereday/app-info/{locale}.json")
        for field, limit in {
            "description": 4000, "keywords": 100, "promotionalText": 170, "whatsNew": 4000,
        }.items():
            require(isinstance(listing[field], str) and 0 < len(listing[field]) <= limit,
                    f"{locale}: {field} must contain 1–{limit} characters")
        for field in ("name", "subtitle"):
            require(isinstance(app_info[field], str) and 0 < len(app_info[field]) <= 30,
                    f"{locale}: {field} must contain 1–30 characters")
        require(listing["whatsNew"] == notes[locale], f"{locale}: What's New mismatch")
        for link in ("https://whereday.com/privacy", "https://whereday.com/terms"):
            require(link in listing["description"], f"{locale}: missing {link}")
        if locale != "en-US":
            translated = renderer.screen_translation(locale)
            require(len(translated["weekdays"]) == 7, f"{locale}: weekday coverage")
            require(translated["strings"] and all(
                isinstance(value, str) and value for value in translated["strings"].values()
            ), f"{locale}: empty screen translations")
    require(Counter(shot[1] for shot in renderer.SHOTS) == {
        "iphone-69": 10, "ipad-13": 5, "duo-inner": 7, "duo-outer": 1, "header": 1,
    }, "Artwork set counts")
    require(len(renderer.WATCH_SLUGS) == 2, "Watch crop count")
    review = (drafts / "reviewer-notes.txt").read_text(encoding="utf-8")
    require(0 < len(review) <= 4000, "Reviewer notes must contain 1–4000 characters")
    print("PASS: 11 Apple metadata locales, field lengths, matching What's New, legal links, "
          "10 screen-translation maps, 24 artboards + 2 Watch crops; "
          f"reviewer notes fit ({len(review)} characters).")


if __name__ == "__main__":
    main()
