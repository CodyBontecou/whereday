- [Whereday](https://whereday.com/)
- [Guides](https://whereday.com/blog/)
- Find your travel history

Practical iPhone guides

# How to find your travel history, every past trip and date

Official records and personal records each miss something. Combine them, check the dates, and keep the list going.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 22, 2026

## The short answer: no single record is complete

There is no one place that lists every trip you have taken. To list past trips and dates, combine official records (U.S. CBP travel history, passport stamps, a records request to CBP) with personal records (bookings in your email, a Google Timeline export, photo locations). Then check the dates against each other and keep one list.

This guide is not legal or immigration advice. Whoever asks for your travel history, such as a visa, residence, citizenship or tax authority, decides what it accepts.

In this guide
- [U.S. CBP I-94 travel history](https://whereday.com/blog/find-your-travel-history/#cbp-i94)
- [Request your records from CBP (FOIA)](https://whereday.com/blog/find-your-travel-history/#foia)
- [Passports and booking records](https://whereday.com/blog/find-your-travel-history/#passport-email)
- [Records on your phone and Google account](https://whereday.com/blog/find-your-travel-history/#phone-records)
- [Build one checked trip list](https://whereday.com/blog/find-your-travel-history/#build-list)
- [Keep it automatically from now on](https://whereday.com/blog/find-your-travel-history/#keep-going)
- [Limits](https://whereday.com/blog/find-your-travel-history/#limits)

## 1. U.S. CBP I-94 travel history

The I-94 website is run by U.S. Customs and Border Protection (CBP): [i94.cbp.dhs.gov](https://i94.cbp.dhs.gov/). In a May 2014 release, CBP said it had added a five-year travel history for non-immigrant visitors to the site ([CBP release, archived](https://www.cbp.gov/newsroom/national-media-release/cbp-announces-added-functionality-i-94-website)). The release also says travelers may need this history when seeking benefits from U.S. Citizenship and Immigration Services or another agency.

To look for it, open the I-94 site and choose View Travel History. Follow the prompts on the official site, which may change; this guide does not reproduce its screens or fields. USA.gov also has a page on the [Form I-94 arrival-departure record](https://www.usa.gov/).

What it leaves out. DHS’s privacy impact assessment for I-94 automation says the record covers only trips where an I-94 was issued, and that departures appear only when they were confirmed ([DHS privacy impact assessment](https://dhs.gov/sites/default/files/publications/privacy-pia-cbp016-i94automation-september2017.pdf)). A missing trip or a missing departure on the page does not by itself mean it did not happen. Official sources describe this tool in terms of non-immigrant visitors; check the I-94 site itself to see whether it applies to you.

## 2. Request your records from CBP (FOIA)

CBP’s [FOIA records page](https://www.cbp.gov/site-policy-notices/foia/records) lists “your own records of international travel to/from the U.S.” among the records you can request. Requests can be submitted through the FOIA SecureRelease portal, which requires you to create an account first.

For records about yourself, the page asks for your full name, address and date of birth, plus a signed Certification of Identity form, or a perjury statement with your signature or a notary. The page also says CBP has no records of the entry and exit of persons before 1982.

Read the CBP page for the current process before you start. This guide does not give processing times or fees.

## 3. Passports and booking records

- Passport stamps. Useful, but incomplete: many borders do not stamp, and stamps can be faint or hard to read. Check every passport you have held, including expired ones.
- Email. Search your inboxes for “booking confirmation”, “e-ticket”, “itinerary”, “boarding pass” and the names of airlines, rail operators and hotels. Note the date on the booking and the dates of travel. A booking shows a plan, not that you travelled.
- Airline and travel accounts. Some airline accounts list past flights. What they keep and for how long differs by company, so check each one you have used.

## 4. Records on your phone and Google account

First [check your iPhone location history](https://whereday.com/blog/view-location-history-iphone/), then look at these sources.

- Google Maps Timeline. If you used it, your history may be on your device or exportable. See [Google Maps Timeline on iPhone](https://whereday.com/blog/google-maps-timeline-iphone/), and check an export with the [Google Timeline inspector](https://whereday.com/tools/google-timeline-inspector/).
- Photo locations. Photos carry a date and often a place. See [your photos on a map](https://whereday.com/blog/iphone-photos-map/).
- Significant Locations. Apple’s feature has limits and does not export. See [what it keeps](https://whereday.com/blog/significant-locations-iphone/).
- Specific days. To check one date, see [where you were on a specific date](https://whereday.com/blog/iphone-location-history-by-date/).

If you have a location file (GPX, KML, GeoJSON or Google Timeline JSON), the [visited countries map](https://whereday.com/tools/visited-countries-map/) turns it into a list of countries with first and last dates. It runs in your browser.

## 5. Build one checked trip list

Put every source into one table, then compare. Use these columns:

Template
| Country | Arrived | Left | Source(s) | Notes
|   |   |   |   |

Worked example. Synthetic data: three invented trips, not a real person.
| Country | Arrived | Left | Source(s) | Notes
| Canada | 2024-03-02 | 2024-03-09 | CBP travel history, email booking | Both agree on the dates.
| Portugal | 2024-06-14 | 2024-06-18 | Photos only | No booking found and no official record. Dates are the first and last photo; mark as unconfirmed.
| France | 2024-09-10 | 2024-09-12 | Email booking | Travel day 2024-09-12 also counts in Spain.
| Spain | 2024-09-12 | 2024-09-20 | Email booking, photos | Shares 2024-09-12 with France.

The France and Spain rows are one trip with a border crossing, so together the example holds three trips. On the crossing day, you were in both countries, so that date appears in both rows. Totals by country can therefore add up to more than the number of distinct dates.

Count each date carefully. A late flight can land on a different calendar day than it left, and time zones can move a date. Decide which local date you use and use it for every row. To count days from a CSV, use the [days-in-country calculator](https://whereday.com/tools/country-days-calculator/).

## 6. Keep it automatically from now on

In Whereday, open Timeline → Country Days. It counts the local dates covered by your recorded visits, route points and manual stays. If a past trip is missing, use Add stay to enter the country and dates. A manual stay records your account of a trip; it does not create a GPS path. Once you have the dates, the [Schengen calculator](https://whereday.com/tools/schengen-calculator/) turns past trips into days used and days left.

You can save a CSV with `country_code`, `local_date` and `source` columns. Viewing the counts and adding manual stays are free. The CSV export follows the export access rules, which differ for new users and existing users; see [current pricing](https://whereday.com/#pricing). For details, read [how Whereday tracks days in each country](https://whereday.com/blog/track-days-in-country-iphone/) and [how to back up your location history](https://whereday.com/blog/back-up-iphone-location-history/).

## Limits

- Not advice. This is not legal, tax or immigration advice. Official forms and authorities decide what counts.
- Official records may differ from yours. Where they conflict, ask the authority which one it uses.
- GPS shows where a phone was, not where a person was.
- Missing records make counts too low. Dates nobody recorded are not filled in.
- Check eligibility yourself. Whether the I-94 travel history tool applies to you is a question for the official I-94 site.

## Start your travel record today

Record trips as you take them, so the next list is a review rather than a search.

Want to keep a journal of future trips? See [travel journal apps for iPhone compared](https://whereday.com/compare/travel-journal-apps-iphone/).

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Record the dates automatically](https://whereday.com/blog/track-days-in-country-iphone/)

See how Whereday records country days and adds a missing stay.

- [Count the days](https://whereday.com/tools/country-days-calculator/)

Count days per country from a Country Days CSV.

- [Check the Schengen 90/180 rule](https://whereday.com/tools/schengen-calculator/)

Enter past trips to see days used and days left.

- [Map the countries](https://whereday.com/tools/visited-countries-map/)

Turn a location file into a list of countries visited.

Source: https://whereday.com/blog/find-your-travel-history/
