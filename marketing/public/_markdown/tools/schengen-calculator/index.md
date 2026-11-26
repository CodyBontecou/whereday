- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Schengen calculator

iPhone location journals

# Schengen 90/180 calculator and trip planner

Enter your trips to see days used and days left, the last day you can stay, and how long a planned trip can be.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 26, 2026

## Add your trips. They stay in this page.

This is a count, not a ruling. It is not legal or immigration advice. Border officials use official records, and it only knows the dates you give it. [Read the limits](https://whereday.com/tools/schengen-calculator/#limits).

JavaScript is needed to count dates in your browser. The explanations below work without it.

Your Schengen trips

Entry date

Exit date (inclusive; empty = still there)

Add trip

### Trips entered

None yet. A trip counts every date from its entry date to its exit date, both included, so 1 to 3 January is 3 days. Overlapping trips count each date once.

Reference date Count the 180 days ending on (defaults to today)

Try the synthetic exampleClear everything

Add a trip or try the example. The example uses invented dates.

## Days used and days left

## Plan a trip

Enter the date you plan to enter. The planner shows the longest stay allowed from that date, with every day of the stay checked against its own 180-day window, plus the earliest date a stay of a length you choose could start.

Planned entry date

Stay length to fit (days, 1 to 90)

Show the day-by-day check for the longest stay

| Date of the stay | Days in its 180-day window | Left

## The 180-day window

| Counted date | Where it came from | Stops counting on

“Stops counting on” is the first reference date whose 180-day window no longer includes that day.

Optional: load a Country Days CSV Choose a Whereday Country Days CSV (columns `country_code` and `local_date`; up to 10 MiB)

Only Schengen country codes are used. Other rows are ignored and the status line above says how many.

Download window (CSV)

## How the 90/180 rule works

Short stays in the Schengen area are limited to 90 days in any 180-day period ([Regulation (EU) 2016/399, Article 6](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0399)). The 180 days are a rolling window, not a calendar half-year. On any day you check, look back over the 180 days that end on that day, including it.

- Entry and exit days both count. A trip from 1 to 3 January is 3 days.
- A date in two Schengen states counts once. Overlapping trips do too.
- Every day of a stay has its own window. That is why the planner checks each day, not only the last one.
- Days drop out. The date a day stops counting is 180 days after it, so your allowance returns gradually and a stay can be longer than “90 minus days used”.

The European Commission’s [short-stay calculator](https://home-affairs.ec.europa.eu/policies/schengen/schengen-visas/short-stay-calculator_en) is the official reference for the same count. To count days in every country, or 183-day totals, use the [days-in-country calculator](https://whereday.com/tools/country-days-calculator/).

## A worked example with invented dates

Synthetic. Three made-up trips: 5 January to 3 February 2026 (30 days), 1 to 25 April (25 days) and a stay that began on 20 June 2026. Press Try the synthetic example above to load them. The figures below are the calculator’s own output.

Calculator output for the invented trips
| Reference date | Third trip | 180-day window | Days used | Days left | Last day you can be there
| 2026-07-10 | Still there | 2026-01-12 to 2026-07-10 | 69 | 21 | 2026-08-23
| 2026-08-15 | Ended 2026-07-10 | 2026-02-17 to 2026-08-15 | 46 | 44 | Not applicable
| 2027-01-10 | Ended 2026-07-10 | 2026-07-15 to 2027-01-10 | 0 | 90 | Not applicable

On 2026-07-10 the traveller is still in the area with 69 days used and 21 left. The last day they can be there is 2026-08-23, which is 44 days later, not 21: while they stay, the days from 12 January to 3 February fall out of the window. To reproduce the later rows, remove trip 3 and add it again with the exit date 2026-07-10.

By 2026-08-15 only 46 days count, and by 2027-01-10 everything has dropped out of the window, so all 90 days are available again.

### Planner result

With the trips closed as above and the reference date set to 2026-08-15, a planned entry on 2026-09-01 gives a longest stay of 69 days, with the latest exit on 2026-11-08. The next day, 2026-11-09, would be day 91 in its window. The earliest date a full 90-day stay could start is 2026-10-09.

## Which countries count

Austria, Belgium, Bulgaria, Croatia, Czechia, Denmark, Estonia, Finland, France, Germany, Greece, Hungary, Iceland, Italy, Latvia, Liechtenstein, Lithuania, Luxembourg, Malta, Netherlands, Norway, Poland, Portugal, Romania, Slovakia, Slovenia, Spain, Sweden and Switzerland (29 states). Cyprus and Ireland are not members.

List checked on 18 November 2026 against the [European Commission’s Schengen area page](https://home-affairs.ec.europa.eu/policies/schengen/schengen-area_en). The current list is applied to every date, so days in Bulgaria and Romania before they joined on 1 January 2025, or in Croatia before 1 January 2023, are counted as Schengen days.

## What doesn’t count towards the 90 days

Stays covered by a national long-stay visa or a residence permit are outside the short-stay count, and some nationalities have bilateral agreements with individual Schengen states. This calculator does not model either, so leave those stays out. Check with the state concerned, and see the European Commission’s [short-stay calculator page](https://home-affairs.ec.europa.eu/policies/schengen/schengen-visas/short-stay-calculator_en) and [Schengen area page](https://home-affairs.ec.europa.eu/policies/schengen/schengen-area_en).

## Official records win

At the Schengen external border, the [Entry/Exit System](https://home-affairs.ec.europa.eu/policies/schengen/smart-borders/entry-exit-system_en) records entries and exits of non-EU short-stay travellers and has replaced passport stamping since 10 April 2026. Where your record and an official record differ, the official record is what counts. Need past dates? See how to [find your travel history](https://whereday.com/blog/find-your-travel-history/) from official and personal records.

## Limits of this calculator

- Not legal advice. It does not decide your visa or status.
- It counts dates you enter. It does not know your visa type, and missing trips make the count too low.
- Overstays are judged by border authorities, not by this page.
- Planned stays check their own days. Trips you have already entered after the planned dates are counted, but are not re-checked.
- Not modelled: microstates and special territories, long-stay visas and residence permits, bilateral agreements, and member-state join dates.

## Your dates stay in this page

Counting, planning and the CSV download all happen in your browser. This page blocks network connections and does not include the website’s analytics. There is no account. The web server receives an ordinary request when you open the page and its scripts, but not your trips or results. Closing the tab removes them.

## Stop typing trips into calculators.

Whereday records the countries you visit on your iPhone and exports a Country Days CSV this page reads. Viewing Country Days and adding manual stays are free. From version 1.8, new users keep exports after tracking access expires; existing free users keep their previous export-unlock requirement. [See how Whereday records country days](https://whereday.com/blog/track-days-in-country-iphone/), or [count days in every country](https://whereday.com/tools/country-days-calculator/).

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Count days in every country](https://whereday.com/tools/country-days-calculator/)

Check days per country, any rolling window and 183-day totals.

- [Record the dates automatically](https://whereday.com/blog/track-days-in-country-iphone/)

See how Whereday records country days on your iPhone and exports the CSV this page reads.

- [Fill gaps from official records](https://whereday.com/blog/find-your-travel-history/)

Find past trips before you rely on a count.

Source: https://whereday.com/tools/schengen-calculator/
