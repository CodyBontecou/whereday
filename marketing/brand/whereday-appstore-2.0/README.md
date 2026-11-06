# Whereday 2.0 App Store artwork — "One Long Day"

The 2.0 store screenshots and product page header come from the claude.ai Design artifact
**Whereday — App Store: One Long Day** (https://claude.ai/artifact/RC1KseV3uhjZ27tA7n8jVJ). It follows
one fictional San Francisco day, 08:12 → 23:30, across:

| Set | Artboards | Size | App Store slot |
| --- | --- | --- | --- |
| iPhone | `ShotB01Today` … `ShotB10Recap` (10) | 1320×2868 | 6.9" (`APP_IPHONE_67`) |
| iPad | `iPadShot01Today` … `iPadShot05Private` (5) | 2752×2064 | 13" (`APP_IPAD_PRO_3GEN_129`) |
| iPhone Duo | `DuoShot01Today` … `DuoShot07Cover` (7) + `DuoOuter01Cover` | 2853×2007, 1398×2034 | `IPHONE_DUO_PROFILE` (Asset Library) |
| Header | `StoreHeaderB` | 3840×1646 | `PRODUCT_PAGE_HEADER_ASSET` (Asset Library) |

The Apple Watch slot isn't in the design; it uses the simulator capture from
`scripts/capture_localized_screenshots.py --devices watch`.

## Rebuild

1. Download the artifact's `project/*.dc.html` and `artifact-type/dc-runtime.js` into one folder.
2. `render.py --design FOLDER --out OUT` renders every artboard in en-US plus the eight translated
   locales, using headless Chrome. About 3 minutes for all 216 images.
3. Convert to opaque RGB before upload; App Store Connect rejects alpha.
4. iPhone, iPad and Watch go up with `asc screenshots upload … --replace --confirm`.
   `place-assets.py` puts the Duo set and the header on the version through the Asset Library API.

## Translation

`translations.py` holds the overlay copy (kicker line, two-part headline, handwritten notes) for
ar-SA, es-ES, fr-FR, hi, ja, pt-BR, ru and zh-Hans. The export headline is a whole-sentence
template per language, because word order changes. Lines were shortened where a translation
wrapped into the artwork. The copy was written by Claude, not native speakers; it needs a native
read before submission.

The mock app screens inside the frames stay in English in every locale. They are drawings with
sample data, and the app's string catalog covers only about 30 of their ~225 labels.

## Changes to the design

- Map-first (November 6, 2026): `MapHome.dc.html` draws the map-first home (`range` = `day` | `week`,
  `night`). Shot 01 and the header's two phones show it; shot 02 is now "A week, one map." (the 7-day
  range) in place of the Trace replay. Its new copy is in `translations.py`.
- The old Today, night Today, Trace and Duo Today/half-open/cover screens are gone. Shot 07 uses the dark
  map; iPad 01 and Duo 01 use `DuoMapHome`; Duo 02 is "A week, on the table." on `DuoTabletopMap`; both
  Duo cover shots use `DuoCoverMap`; the app preview poster and storyboard follow the map. Tab bars and
  rails across the remaining screens say Map, not Today.

- `render.py` strips the artifact-only `/_blob/` font faces; Google Fonts supplies the same
  Newsreader, Inter, IBM Plex Mono and Caveat. Non-Latin scripts fall back to system fonts.
- Japanese and Chinese headlines are set at 74% so they break cleanly. Arabic headlines run
  right-to-left but stay left-aligned like the design, so they never collide with the artwork.
- The header's "home 23:30" note started behind the second phone and was clipped even in English;
  it is now anchored to the right edge.
