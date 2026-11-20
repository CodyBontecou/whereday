- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Photos map on iPhone

Practical iPhone guides

# See your photos on a map on iPhone

How to see one photo’s location and all your photos on a map, why some are missing, and how to place photos that have no GPS.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 20, 2026

## Quick answer

To see where one photo was taken, open it in Photos and swipe up (or tap the Info button). To see all your photos on a map, open Photos, tap Library, tap the Sort and Filter button, then choose Show Map. Only photos with embedded location (GPS) data appear.

If photos are missing, [check the causes below](https://whereday.com/blog/iphone-photos-map/#missing). If they never had GPS, Whereday can place them on your map from your recorded route or visits.

In this guide
- [See where one photo was taken](https://whereday.com/blog/iphone-photos-map/#one-photo)
- [See all your photos on a map](https://whereday.com/blog/iphone-photos-map/#library-map)
- [Why some photos are missing from the map](https://whereday.com/blog/iphone-photos-map/#missing)
- [Add or change a photo’s location](https://whereday.com/blog/iphone-photos-map/#add-location)
- [Place photos that have no GPS with Whereday](https://whereday.com/blog/iphone-photos-map/#whereday)
- [Photos map vs Whereday at a glance](https://whereday.com/blog/iphone-photos-map/#compare)
- [FAQ](https://whereday.com/blog/iphone-photos-map/#faq)

## See where one photo was taken

- Open the photo or video in the Photos app.
- Swipe up, or tap the Info button (ⓘ).
- If the photo has a location, a small map and place name appear. Tap the map or the address link for more details.

Videos work the same way. Apple’s guide describes these steps in [Browse photos and videos by location](https://support.apple.com/guide/iphone/browse-photos-and-videos-by-location-iph390138909/ios). Where the details screen offers it, you can open the place in Maps or copy the address or coordinates; the options on offer vary by iOS version. If no map appears, the photo has no location. See [why below](https://whereday.com/blog/iphone-photos-map/#missing).

## See all your photos on a map

- Open Photos and tap Library.
- Tap the Sort and Filter button, then tap Show Map.
- Pinch and drag to explore the map. Tap a photo thumbnail to see everything taken at that location.

Apple’s guide says: “Only pictures and videos that have embedded location information (GPS data) are included.” This path is from the guide’s current version; Apple publishes separate versions for older iOS releases, so check the version selector on the page if your menus differ.

Can’t find it? Older blog posts and forum threads tell you to open Albums → Places. If you can’t find that album on your iPhone, use the Library steps above, or the version of Apple’s guide that matches your iOS release.

## Why some photos are missing from the map

The map shows only photos that carry GPS data. These are the common reasons a photo doesn’t.

Causes, how to tell, and what to do
| Cause | How to tell | Fix
| Camera location access is off | Recent photos from your own camera have no map in Info | Settings → Privacy & Security → Location Services → Camera → While Using the App. Precise Location is optional. This affects future photos only.
| Location Services is off entirely | The Location Services switch at the top of that screen is off | Turn it on, then check Camera again. Past photos aren’t changed.
| Screenshots and screen recordings | The item is in the Screenshots or Screen Recordings album | None needed; they don’t carry GPS. You can [add a location by hand](https://whereday.com/blog/iphone-photos-map/#add-location).
| Received in a chat app or downloaded from a social site | The photo wasn’t taken on your iPhone, and Info shows no location | Many apps remove location when they send or host a photo. Ask for the original, or add a location by hand.
| Scans, DSLR or other non-GPS camera imports | The photo came from a scanner or a camera with no GPS | Add a location by hand, or let Whereday [match it by time](https://whereday.com/blog/iphone-photos-map/#whereday) if you were recording that day.
| Shared with Location turned off | The copy someone received has no location | Your original keeps its location; only the copy you sent has it removed. Choose Options in the share sheet to control this.

Apple explains the Camera permission in [Location Services and privacy](https://support.apple.com/en-us/102515), and sharing with or without location in Apple’s iPhone User Guide.

## Add or change a photo’s location

Open the photo, swipe up or tap Info, then use Add Location (or Adjust if it already has one) and search for a place. Removing a location uses the same screen. Apple documents this in [its iPhone User Guide](https://support.apple.com/guide/iphone/browse-photos-and-videos-by-location-iph390138909/ios) (see the related “Change the date, time, or location” article linked there); the exact buttons depend on your iOS version, so check the guide for yours, including whether you can change several photos at once.

## Place photos that have no GPS with Whereday

Apple’s map can’t place a photo without embedded GPS. Whereday, an iPhone app that records your visits and routes on the device, can match a photo to your recorded day instead. With photo moments enabled, each photo uses the first source that works:

- The photo’s own GPS, placed exactly where it was taken.
- Your route: the nearest recorded GPS point within about 15 minutes of when the photo was taken.
- A visit: if the capture time falls inside a visit (with a little grace), the photo attaches to that place.

Photos that match none of these are skipped.

### Controls and privacy

- Automatic syncing and map markers are both off by default. Turn on markers with Photos in the map’s Layers sheet or in Settings.
- Markers can show as thumbnails, pins or dots, and nearby photos cluster together.
- Limited photo access is supported: only the photos you’ve selected for Whereday appear.
- Photo files never leave your Photos library. Whereday stores only each photo’s local identifier and match metadata, on the device.

A limit to know about: matching works only for days Whereday was recording. It can’t place old photos taken before you installed it. If a day has gaps, see [why records go missing](https://whereday.com/blog/missing-location-history-iphone/); to have a route for future photos, [record your routes](https://whereday.com/blog/record-iphone-route-gpx/).

![Whereday’s map Layers sheet with the Photos row, described as matched by time and place and never copied, switched off by default](https://whereday.com/screenshots/map-layers.png)

The Photos row in Whereday’s Layers sheet. It is off until you turn it on. Screenshot taken with invented data.

### Worked example (synthetic)

This is an invented day, not real data. Suppose Whereday recorded a route point at 10:06 and a café visit from 12:00 to 12:40. Nothing was recorded around 17:30.

Four invented photos and how each is matched
| Photo | Capture time | Own GPS? | Matched by | Result
| A | 09:30 | Yes | Its own GPS | Placed at its own coordinates
| B | 10:12 | No | Route point at 10:06 (6 minutes away) | Placed at that route point
| C | 12:20 | No | Café visit, 12:00–12:40 | Attached to the visit
| D | 17:30 | No | Nothing | Skipped

The full reference is on the [Photos on the map](https://whereday.com/docs/photos/) page.

[Download free for iPhone](https://apps.apple.com/app/id6761960794) Put the photos that have no GPS on your map.

## Photos map vs Whereday at a glance

What each one does
| Question | Photos map | Whereday
| Photos without GPS | Not shown | Placed from your route or visits, on days it was recording
| Coverage | Your whole library, back to the first geotagged photo | Days Whereday was recording
| Timeline of the day | No | Yes: visits, route and photos together
| Visits and places | Photo clusters by location | Recorded visits and saved places
| Routes | No | Recorded travel path
| Export | No location-list export in Photos | Visits and points as GPX, CSV or JSON (see [export and import](https://whereday.com/docs/export-import/)); not a photo-location list
| Where data lives | Photos library (and iCloud if you use it) | On your iPhone
| Cost | Free, built in | Free to download for iPhone

Photos is the right first stop for geotagged photos. Whereday is for the gaps. Looking for “where was I” rather than photos? Read [how to view your iPhone location history](https://whereday.com/blog/view-location-history-iphone/) and [why Apple’s Significant Locations can’t be exported](https://whereday.com/blog/significant-locations-iphone/). If you have a location file, the [visited countries map](https://whereday.com/tools/visited-countries-map/) turns it into a countries map in your browser.

## FAQ

### Can others see my photo locations when I share?

They can if the shared photo includes location. In the share sheet, tap Options to choose whether to include it. Your original keeps its location either way.

### Why does a photo show the wrong place?

Location can be inaccurate, for example if you took the photo indoors or with a weak GPS fix, or if someone edited it. You can [adjust it](https://whereday.com/blog/iphone-photos-map/#add-location) in the Info panel.

### Can I export a list of photo locations?

Apple doesn’t document a way to export one from the Photos map. Whereday exports visits and points, not a photo-location list.

## Put the photos that have no GPS on your map.

Record your days with Whereday, then turn on photo moments. It can’t place photos from days before you started recording.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

[Read the photo reference](https://whereday.com/docs/photos/)

Apple steps checked November 20, 2026 against Apple’s iPhone User Guide, “Browse photos and videos by location”. The add-location steps, Camera permission and share-sheet Options are described in general terms and linked to Apple’s pages; check them for your iOS version. Whereday behavior follows the published Photos on the map reference and the app source. The screenshot and example use invented data.

## Read next

- [Read the photo feature reference](https://whereday.com/docs/photos/)

Matching order, the 15-minute window, privacy and controls.

- [Looking for where you were?](https://whereday.com/blog/view-location-history-iphone/)

Check Apple Maps, Google Timeline and Whereday.

- [Gaps on a day?](https://whereday.com/blog/missing-location-history-iphone/)

Photos can only be matched on days with records; check why records are missing.

Source: https://whereday.com/blog/iphone-photos-map/
