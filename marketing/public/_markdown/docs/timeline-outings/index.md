§ Docs · Guide 3 of 11

# Timeline & outings

The Timeline tab merges visits and movement into one day view. Learn the difference between recorded and inferred outings, how replay works, and what deletion actually removes.

Platform

iOS 17+

Updated

October 30, 2026

Reading

4 min

Back to

[All docs](https://whereday.com/docs/)

## The day view

The Timeline tab shows one day at a time: visits and movement sessions interleaved in order, with a day summary of moves, visits, and total distance. Step through days with the arrows, jump straight to Today, or pick any date. If today is empty, Whereday automatically opens your most recent day with data.

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

- Show on map focuses the Map tab on this outing's path.
- Export saves this single outing in your choice of format (subject to your export access; the picker remembers your format).
- Delete outing removes the session and its GPS points — visits are kept. Deleting the active outing stops tracking first.

## Route replay

Both the map (for a whole day) and the outing detail page (for one outing) offer replay: a playhead walks the path in order while a scrubber, elapsed time, and running distance update. It's a quick way to answer “where was I around 3 pm?”

## Sorting

Outing lists can be sorted by newest, oldest, longest duration, distance, or point count.

If a recorded day looks incomplete, follow the [missing visits and route points checklist](https://whereday.com/blog/missing-location-history-iphone/) before deleting records or retrying an import.

## Country Days

Timeline → Country Days counts local dates represented by visits, route points, and manual stays. Review this year, the last 365 days, or all time. A travel date can count in more than one country; gaps are not filled automatically. Viewing and manual stays are free; writing the country-date CSV follows the [new-user and existing-user export rules](https://whereday.com/#export-access).

See [how to track days spent in each country](https://whereday.com/blog/track-days-in-country-iphone/) for timezone, network-geocoding, and counting details, including a [checked four-row CSV example](https://whereday.com/blog/track-days-in-country-iphone/#checked-example).

[← PreviousVisits, routes & battery](https://whereday.com/docs/tracking/) [Next →Save and edit places in your iPhone location journal](https://whereday.com/docs/places/)

Source: https://whereday.com/docs/timeline-outings/
