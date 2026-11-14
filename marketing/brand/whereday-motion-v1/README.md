# Whereday · A day, drawn together

An original folded-city-map illustration in the existing Whereday field-notes palette: ivory `#F8F5EB`, indigo `#203557`, and rust `#A95138`. A route connects home, a café, and a lookout. The artwork contains no text and can accompany every listing localization.

This is creative for **App Store search results and the product page header**, separate from traditional in-app preview footage. It is a new illustration inspired by the reference video's single-subject animation, rather than a reproduction of that video's designs. No website or App Store listing is changed by building these files.

## Preview and rebuild

Requires Node.js, Python 3, ffmpeg, Playwright Chromium, and sharp. Install the package dependencies with `npm install` and, if needed, install Chromium with `npx playwright install chromium`. In the Codex desktop environment, the scripts can also use the bundled Node dependencies without installing anything. Set `CODEX_NODE_MODULES` to override that package location.

From this directory:

```sh
npm run preview
```

Open <http://127.0.0.1:8863/> for the review page. In another terminal:

```sh
npm run build
```

`WHEREDAY_PREVIEW_URL` overrides the render server URL. `node source/render.mjs --stills` renders just the PNGs and verification contact sheets. Full-resolution outputs are local generated files under `exports/`; review captures are under `review/`.

## Deliverables

| File | Placement / use | Size | Motion |
| --- | --- | --- | --- |
| `whereday-search-1920x1280.png` | Search result graphic | 1920 × 1280 | Opaque RGB poster |
| `whereday-header-3840x1646.png` | Header poster | 3840 × 1646 | Opaque RGB poster |
| `whereday-header-3840x1646.mp4` | Product page header | 3840 × 1646 | 16 s, 30 fps |
| `whereday-search-1920x1280.mp4` | Search result video | 1920 × 1280 | 16 s, 30 fps |
| `whereday-x.mp4` | Social / review copy | 1920 × 822 | 16 s, 30 fps |
| `whereday-loop.gif` | Wide website / review loop | 960 × 412 | 16 s, 12 fps |
| `whereday-800x500.gif` | Compact review loop | 800 × 500 | 16 s, 12 fps |

The App Store MP4s are silent H.264 with yuv420p, BT.709 metadata, and fast-start playback. The 30 fps delivery follows Apple's supported format. The source illustration is 1536 × 1024; the native-size final compositions combine it with resolution-independent SVG route and markers.

`whereday-app-store-assets.zip` packages both App Store videos, both PNG posters, the manifest, and these notes.

Apple specifications checked October 29, 2026:

- [Creative assets specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/creative-assets-specifications)
- [Asset best practices](https://developer.apple.com/app-store/asset-best-practices/)

## Choreography

The illustrated folded map remains fixed throughout. The home marker arrives over 0.85 seconds; the route begins at 1.6 seconds, reaches the café at 5.7 seconds, pauses, and reaches the lookout at 10.2 seconds. Each visit marker arrives as the route reaches it. The complete journey rests until 13.8 seconds, dissolves through 15.5 seconds, then holds its initial state until the 16-second seam. The SVG edge mask blends the header's wider paper margins into the illustration. There is no camera movement.

The still is the settled complete route at 12 seconds. Reduced-motion viewers see that still. Every animated value is derived from an explicit timestamp through `window.__seek`, so frame capture is deterministic. No real-time screen recording is involved.

## Verification and provenance

The renderer checks exact first/end frame equality, the empty reset beat, the settled frame against the PNG still, and unchanged pixels outside the animated route's padded bounds at eight points in the cycle. It also saves contact sheets and rejects page errors. `exports/manifest.json` records checks, dimensions, sizes, and upload status.

This is a new composition, so fidelity PSNR against the reference video is not applicable. The reference provides the concept of a focused animated illustration; existing Whereday artwork provides the palette and pen-drawing style. The settled animation and its own still frame must match exactly.

`art/folded-city-map.png` was generated using the built-in image_gen tool, with the existing `whereday-field-notes-v1/art/city-panorama.png` as a style reference. The complete generation prompt is saved in `source/illustration-prompt.json`. The SVG route follows the illustrated streets. It is invented demonstration artwork, not a map of the user's location history. Route and marker layers are authored in `source/scene.html` and `source/scene.mjs`.

The review page shows simplified placement studies, not a captured or authoritative App Store UI. App Store Connect's preview remains the final placement check. All four App Store assets were uploaded and submitted on October 29, 2026 at 1:30 PM AST. Submission `4a603099-2ec7-4f60-93aa-a249073854cf` is Waiting for Review. Live header/search placement has not been changed; the assets need approval before they can be selected on the live listing. The existing 1.7.18 app submission was left intact. See `app-store-upload-2026-10-29.json` for asset IDs and checksums.
