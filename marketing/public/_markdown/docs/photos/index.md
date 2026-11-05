§ Docs · Guide 5 of 11

# Photos on the map

An opt-in connection between your photo library and your location journal. Photo files never leave your library — Whereday only matches metadata.

Platform

iOS 17+

Updated

August 22, 2026

Reading

2 min

Back to

[All docs](https://whereday.com/docs/)

## What it does

With photo moments enabled, Whereday can show geotagged photos from your library as markers on the map — clustered when they're close together — and attach them to the outings and visits they belong to. It's an entirely optional layer: off until you turn it on.

## How photos are placed

Each photo is matched to a location using the first source that works:

- The photo's own GPS — geotagged photos are placed exactly where they were taken.
- Your route — otherwise Whereday looks for the nearest recorded GPS point within about 15 minutes of the capture time.
- A visit — otherwise, if the capture time falls inside a visit (with a little grace), the photo attaches to that place.

Photos that match none of these are simply skipped.

## Privacy mechanics

Photo files never leave your Photos library. Whereday stores only each photo's local identifier and match metadata on-device. Nothing is uploaded, and limited-access mode is fully supported — only the photos you've selected for Whereday will appear.

## Controls

- Automatic syncing (off by default) re-scans your library in the background, at most every 10 minutes, and re-syncs whenever your library changes.
- Map markers (off by default) — enable with Photos in the map's Layers sheet or in Settings; nearby photos group into stacked clusters with a count badge.
- Images, Pins, or Dots — the Photos row in Layers chooses thumbnails, plain pins, or small dots for the markers.
- Tap any marker for a quick view, or open it full-screen to swipe through the day's photos.

If a photo disappears from your library (with full access), Whereday cleans up its record automatically.

[← PreviousSave and edit places in your iPhone location journal](https://whereday.com/docs/places/) [Next →Export and restore location history](https://whereday.com/docs/export-import/)

Source: https://whereday.com/docs/photos/
