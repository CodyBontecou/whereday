# Whereday 2.0 Google Play artwork and listing

The Play screenshots, tablet shots and feature graphic come from the claude.ai Design artifact
**Whereday — Google Play**. It reuses the "One Long Day" story from the App Store set, drawn with
Android screens.

| Set | Artboards | Size | Play slot |
| --- | --- | --- | --- |
| Feature graphic | `FeatureGraphic` | 1024×500 | Feature graphic |
| Phone | `Phone01Today` … `Phone08Glance` (8) | 1080×1920 | Phone screenshots |
| 7-inch tablet | `Tab7Today`, `Tab7Atlas`, `Tab7Night` | 1920×1200 | 7-inch tablet screenshots |
| 10-inch tablet | `Tab10Today`, `Tab10Atlas`, `Tab10Night` | 2560×1600 | 10-inch tablet screenshots |

## Rebuild

1. Download the artifact's `project/*.dc.html` and `dc-runtime.js` into one folder.
2. `render.py --design FOLDER --out OUT [--locales es,ja] [--only phone]` renders every board for each
   language in `translations/`, using headless Chrome. `--locales en` renders the untranslated design,
   which is pixel-identical to the 2.0.0 en-US upload.
3. Convert to opaque RGB before upload.

## Translation

`translations/LOCALE.json` maps every visible English string in the shots and in the Android screens
they embed (captions, headline halves, handwritten notes, screen labels, dates, the export-sentence
fragments) to the language, plus the weekday initials. The wording comes, in order, from the App
Store captions (`../whereday-appstore-2.0/translations.py`), the Android app's own resources
(`android/localization/`), and the App Store screen translations; the rest was translated fresh.
Code values in the screens' scripts (range modes, flags, file extensions, CSS) are never touched.

Translated layouts get a small pass after render (`LAYOUT_FIX` in `render.py`): headlines shrink to
stay within two lines, handwritten notes shrink or move to stay on the canvas, and a note that
collides with the caption first shrinks the headline, then moves below it. Arabic swaps IBM Plex
Mono for Geeza Pro without tracking, because the mono fallback draws Arabic letters unjoined. The
screens are not mirrored for Arabic.

`listing/LOCALE.json` holds the Play title, short and full description and 2.0 release notes per
language; the store files are in `metadata/whereday/play/<play-locale>/`. Spanish goes to both es-ES
and es-419. All copy was written by Claude, not native speakers, and needs a native read.
