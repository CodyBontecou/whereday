- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Days in country

iPhone location journals

# Count your days in each country

Count days per country, check Schengen 90/180, and see 183-day totals, all in your browser.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 18, 2026

## Add your dates. They stay in this page.

This is a count, not a ruling. It is not legal, tax or immigration advice. Border officials use official records, such as passport stamps and the EU Entry/Exit System, and tax authorities apply their own tests. Dates missing from your input are not filled in, so a missing stay makes every count too low. [Read the limits](https://whereday.com/tools/country-days-calculator/#limits).

Load a CSV of country dates, add stays by hand, or both. The calculator counts distinct dates per country, checks any rolling window, runs the Schengen 90/180 check and shows calendar-year totals. No account, upload or analytics.

JavaScript is needed to read files and count dates in your browser. The explanations and sample download below work without it.

Load a CSV Choose a Country Days CSV (columns `country_code` and `local_date`; up to 10 MiB)    Add a stay by hand

Country

First date

Last date (inclusive)

Add stayCancel edit

### Your manual stays

None yet. A stay counts every date from its first to its last date.

Try the synthetic exampleAdd the Schengen worked exampleClear everything

Choose a file or add a stay. The examples use invented dates.

### Rows that were not counted

| File line | Problem | Row text

## Your results

### Days per country

Period

From

To (inclusive)

| Country | Days | First to last date | Dates

### Rolling-window check

The most dates in any window of N days that ends inside the period above. The window includes its end date.

Country or group

Window length (days)

Threshold (days)

### Schengen 90/180 check

Reference date

| Date | Schengen countries on that date

### Calendar-year totals and the 183-day mark

Rows at 183 days or more are highlighted. This is one common reading of “183 days”. Many countries use other tests, and the US uses a weighted three-year test; see [what 183 days can mean](https://whereday.com/tools/country-days-calculator/#183-days).

| Year | Country | Days | 183-day mark

### Download your results

Both files are made in your browser. The totals use the period selected above; the Schengen file uses the reference date.

Download country totals (CSV)Download Schengen window (CSV)

### Schengen member states used

Austria, Belgium, Bulgaria, Croatia, Czechia, Denmark, Estonia, Finland, France, Germany, Greece, Hungary, Iceland, Italy, Latvia, Liechtenstein, Lithuania, Luxembourg, Malta, Netherlands, Norway, Poland, Portugal, Romania, Slovakia, Slovenia, Spain, Sweden and Switzerland (29 states). Cyprus and Ireland are not members.

List checked on 18 November 2026 against the [European Commission’s Schengen area page](https://home-affairs.ec.europa.eu/policies/schengen/schengen-area_en). The current list is applied to every date, so days in Bulgaria and Romania before they joined on 1 January 2025, or in Croatia before 1 January 2023, are counted as Schengen days.

## How the calculator counts a day

These rules match Whereday’s own Country Days screen.

- Any presence on a local date is a full day in that country. One record is enough.
- A travel day can count in two countries. The Schengen Borders Code also treats the day you enter and the day you leave as days of stay, so the Schengen check matches it.
- Gaps are not filled in. If a stay is missing from your record, every count that includes it will be too low. Add the stay by hand.
- Dates are taken as written. The calculator uses the `local_date` in your file. Whereday writes the local date of the place where each record was made, not your phone’s current timezone.

Common situations
| Situation | What is counted
| Many records in the same country on one date | One day for that country.
| Lisbon in the morning, Madrid in the evening | One day in Portugal and one day in Spain. The country totals add up to more than the number of dates.
| A week with no records | Nothing. The calculator does not assume where you were.
| A CSV row and a manual stay for the same country and date | One day. The expanded date list shows both sources.
| France and Italy on the same date | One day each in the per-country table, but one Schengen day.

## How the Schengen 90/180 rule works

Short stays in the Schengen area are limited to 90 days in any 180-day period ([Regulation (EU) 2016/399, Article 6](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0399)). The 180 days are not a fixed calendar half-year. On any day you check, look back over the 180 days ending on that day, including it. Every date you spent in any member state in that window counts once, and the total must not be more than 90.

Days drop out of the window as time passes, so your allowance comes back gradually. To be sure of another full 90-day stay, you need 90 days in a row with no Schengen presence before it. The calculator shows that date as the earliest start for a full 90 days, assuming you spend no more Schengen days after the reference date. The European Commission’s [short-stay calculator](https://home-affairs.ec.europa.eu/policies/schengen/schengen-visas/short-stay-calculator_en) uses the same rolling count.

### A worked example with invented dates

This trip is made up. A traveller spends 60 days in France (5 January to 5 March 2026), leaves the Schengen area for 40 days (6 March to 14 April), then spends 30 days in Italy (15 April to 14 May). Press Add the Schengen worked example above to load the same two stays.

Calculator output for the invented trip
| Reference date | 180-day window | Days used | Days remaining | Earliest start for a full 90 days
| 2026-05-14 | 2025-11-16 to 2026-05-14 | 90 | 0 | 2026-08-13
| 2026-08-01 | 2026-02-03 to 2026-08-01 | 61 | 29 | 2026-08-13

On 14 May the window reaches back to November 2025 and holds all 90 days, so none remain: the traveller must leave. By 1 August, the 31 days from 5 January to 2 February have dropped out of the window. The 31 days from 3 February to 5 March and the 30 days in Italy still count, so 61 days are used and 29 remain. The last day in Italy is 14 May; the 90 days after it, 15 May to 12 August, have no Schengen presence, so a full 90-day stay can start on 13 August.

## What “183 days” can mean

“183 days” is a common shorthand for spending more than half a year somewhere. It is used in different ways:

- A calendar year. Some domestic rules count days from 1 January to 31 December. The calendar-year table above shows this count.
- Any 12-month period. Many tax treaties follow the OECD Model Tax Convention, whose Article 15(2) refers to 183 days “in any twelve month period commencing or ending in the fiscal year concerned” ([OECD Model Tax Convention](https://www.oecd-ilibrary.org/taxation/model-tax-convention-on-income-and-on-capital-2017-full-version_ee524f5e-en)). Use the rolling-window check with a 365-day window and a threshold of 183.
- The US substantial presence test. It is weighted: all days in the current year, one third of the days in the year before and one sixth of the days in the year before that, with at least 31 days in the current year ([IRS, Substantial Presence Test](https://www.irs.gov/individuals/international-taxpayers/substantial-presence-test); [Publication 519](https://www.irs.gov/publications/p519)). This calculator does not compute it.

Reaching 183 days does not make you tax resident, and staying under it does not make you non-resident. Residency rules also look at your home, family, work and other ties, and each country defines its own days and exceptions. Use the count as evidence to discuss with a qualified adviser.

## The CSV format

The first row must name the columns. Two are required and the others are ignored:

- `country_code`: a two-letter ISO 3166-1 alpha-2 code, such as `PT` or `FR`. Lowercase is accepted.
- `local_date`: a calendar date written `YYYY-MM-DD`.
- `source` (optional): shown next to each date when present. Whereday writes `recorded`, `manual` or `recorded+manual`.

Rows with a missing value, an unknown code or an impossible date (such as `2026-02-29`) are listed with their line numbers and left out of every count. Repeated rows for the same country and date count once.

The synthetic example file (invented dates)
| country_code | local_date | source
| ES | 2026-10-01 | recorded
| FR | 2026-10-03 | recorded
| PT | 2026-10-01 | recorded+manual
| PT | 2026-10-02 | manual

The calculator gives Portugal 2 days, Spain 1 and France 1: four country-dates over three distinct dates. All three are Schengen states, so on 3 October 2026 the Schengen check counts 3 days used, because 1 October counts once. [Download the synthetic CSV](https://whereday.com/samples/synthetic-country-days.csv).

### Export the file from Whereday

- Open Timeline, then Country Days.
- Choose All time if you want every date. The export contains the period that is selected.
- Tap Export and save the CSV to Files, then choose it here.

Viewing Country Days and adding manual stays are free. From version 1.8, new users keep exports after tracking access expires; existing free users keep their previous export-unlock requirement. See [how Whereday records country days](https://whereday.com/blog/track-days-in-country-iphone/) for travel days, timezones, adding a missing stay and finding countries in older records.

A country-date CSV is a summary. It is not a backup of your visits, route points or manual stays; [keep a restorable copy](https://whereday.com/blog/back-up-iphone-location-history/) as well. More about the screen is in the [Timeline documentation](https://whereday.com/docs/timeline-outings/).

## Limits of this calculator

- Not advice. It is not legal, tax or immigration advice and does not decide your visa or tax status.
- Official records count. At the Schengen external border, the [Entry/Exit System](https://home-affairs.ec.europa.eu/policies/schengen/smart-borders/entry-exit-system_en) records entries and exits of non-EU short-stay travellers and has replaced passport stamping since 10 April 2026. Where your record and an official record differ, the official record is what counts.
- Only the dates you give it. Missing records make counts too low. Wrong local dates make them wrong.
- Not modelled: microstates and special territories (such as Monaco, San Marino, Vatican City, and overseas territories of member states), bilateral visa-waiver agreements, residence permits and long-stay visas, which do not count towards the 90 days, and member-state join dates.
- Not a tax test. The US weighted test, treaty tie-breaker rules and country-specific day definitions are not built in.

## Your dates stay in this page

Reading the file, counting and making the downloads all happen in your browser. This page blocks network connections and does not include the website’s analytics. The web server receives an ordinary request when you open the page and its scripts, but not your file, your dates or your results. Clearing the page or closing the tab removes them; your original file is never changed. Try the [GPX viewer](https://whereday.com/tools/gpx-viewer/) for route files.

## Stop typing trips into calculators.

Whereday records the countries you visit on your iPhone and exports this CSV. Viewing Country Days and adding manual stays are free.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Record the dates automatically](https://whereday.com/blog/track-days-in-country-iphone/)

See how Whereday records country days, adds a missing stay and analyses older records.

- [Keep the records behind the count](https://whereday.com/blog/back-up-iphone-location-history/)

A country-date CSV is a summary, not a backup of your visits and points.

Source: https://whereday.com/tools/country-days-calculator/
