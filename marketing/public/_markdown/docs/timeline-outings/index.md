§ Docs · Guide 3 of 11

# Timeline & outings

The map’s day sheet merges visits and movement into one day view. Learn the difference between recorded and inferred outings, how replay works, and what deletion actually removes.

Platform

iOS 17+

Updated

November 5, 2026

Reading

4 min

Back to

[All docs](https://whereday.com/docs/)

## The day view

Whereday opens on a map of today. Each leg of the route takes the colour of the place it led to, and each visit shows its name and how long you stayed. Swipe up the day card at the bottom for the day sheet: the day dial, three stats (moved, away, and places), and the day so far, your stays and the moves between them in order. Tap a stay to focus it on the map and open it. Pick a day in the sheet opens an earlier date.

The chips under the map’s header choose what the map shows: Today, Yesterday, 7D, 30D, All, or the calendar for a custom range. For more than one day, each place shows the number of days you visited it, and a row of days below the map lets you highlight one day’s route.

## Map layers

The Layers button on the map’s right edge chooses what is drawn: visits, the travel path (with Match roads and Show straight gaps), GPS points, start and end markers, the live session, photos, and Are you here? suggestions. Tap a GPS point for its time and accuracy. In dark mode the whole map switches to its night colours.

## Recorded vs inferred outings

A movement session (outing) is recorded when you pressed Start, and inferred when Whereday notices a stretch of GPS points that clearly forms a trip — including older data recorded before outings existed, or imported from your Watch. Inferred outings wear an `INFERRED` badge; the live one wears `LIVE`.

Inference is configurable in Settings → Inferred outings:

| Setting | Options | Default
| Auto-generate | On / Off | On
| Split after (quiet gap) | 15 m / 30 m / 1 h / 2 h / 4 h | 30 minutes
| Ignore shorter than | None / 5 m / 15 m / 30 m / 1 h | None
| Minimum GPS points | 1 / 2 / 3 / 5 / 10 | 1

## Outing detail

Tap any outing for its full record: a road-matched mini map, start and end times, duration, distance, point count, GPS-glitch count, and average speed. Recorded outings can be renamed and given notes (inferred ones can't — edit the points' day instead). Linked visits and photos appear below.

- Show on map focuses the map on this outing's path.
- Export saves this single outing in your choice of format (subject to your export access; the picker remembers your format).
- Delete outing removes the session and its GPS points — visits are kept. Deleting the active outing stops tracking first.

## Route replay

Tap the small day dial at the top right of the map to replay the day: a playhead walks the path in order while the clock, a scrubber, and the running distance update. Play at 1×, 10×, or 60×, or drag the scrubber; Done returns to the map. The outing detail page offers the same replay for one outing. It's a quick way to answer “where was I around 3 pm?”

## Sorting

Outing lists can be sorted by newest, oldest, longest duration, distance, or point count.

If a recorded day looks incomplete, follow the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) before deleting records or retrying an import.

## Country Days

Timeline → Country Days counts local dates represented by visits, route points, and manual stays. Review this year, the last 365 days, or all time. A travel date can count in more than one country; gaps are not filled automatically. Viewing and manual stays are free; writing the country-date CSV follows the [new-user and existing-user export rules](https://whereday.com/#export-access).

See [how to track days spent in each country](https://whereday.com/blog/track-days-in-country-iphone/) for timezone, network-geocoding, and counting details, including a [checked four-row CSV example](https://whereday.com/blog/track-days-in-country-iphone/#checked-example).

[← PreviousVisits, routes & battery](https://whereday.com/docs/tracking/) [Next →Save and edit places in your iPhone location journal](https://whereday.com/docs/places/)

Source: https://whereday.com/docs/timeline-outings/
