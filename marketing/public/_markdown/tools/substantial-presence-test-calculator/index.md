- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Substantial presence test

iPhone location journals

# Substantial presence test calculator

Count your US days, apply the IRS weights and see every step, in your browser. A count, not tax advice.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 23, 2026

## Calculate the substantial presence test

This is a count, not a ruling. It is not tax or immigration advice and does not say whether you are a US tax resident. It applies one IRS formula to the days you give it. Missing dates make the count too low, and exceptions such as the closer connection exception or a tax treaty can change the outcome. Take the count to a qualified adviser. [Read what the test does not decide](https://whereday.com/tools/substantial-presence-test-calculator/#meeting-the-test-is-not-the-end).

Load a Country Days CSV, add US stays by hand, or type a number of days for a year. Pick the year to test and the page shows the days, the weights, the total and the result. No account, upload or analytics.

JavaScript is needed to read files and count dates in your browser. The explanations and worked examples below work without it.

Load a CSV Choose a Country Days CSV (columns `country_code` and `local_date`; up to 10 MiB)

Add a US stay by hand

First date

Last date (inclusive)

Add stay

### Your US stays

None yet. A stay counts every date from its first to its last date.

Try the IRS exampleTry a second synthetic exampleClear everything

Choose a file, add a stay or type yearly days. The examples use invented dates.

### Rows that were not counted

| File line | Problem | Row text

### Your working

Year to test

A typed number replaces the counted dates for that year, so nothing is added twice. Excluded days are subtracted per year and never take a year below zero. Exempt-individual and medical-condition days can only be excluded if you file Form 8843 ([IRS, About Form 8843](https://www.irs.gov/forms-pubs/about-form-8843)). Leave blank if none apply.

| Year | From dates | Typed days (optional) | Excluded days (optional) | Days used | Weight | Weighted days

### Result

### Dates counted

## How the test works

You meet the substantial presence test for a year if both conditions are true ([IRS, Substantial Presence Test](https://www.irs.gov/individuals/international-taxpayers/substantial-presence-test)):

- You were physically present in the United States on at least 31 days in the current year.
- Your weighted days over the 3-year period that includes the current year and the 2 years before it come to at least 183.

The weights
| Year | Count
| Current year | All days
| First year before | 1/3 of the days
| Second year before | 1/6 of the days

The IRS does not say to round, so this page shows the exact decimal: 100 days at one third is 33.33.

Any part of a day counts. The IRS treats you as present if you were in the United States at any time during the day (see [Publication 519](https://www.irs.gov/publications/p519)). Whereday also counts any record on a local date as a full day, so the two rules line up.

For this test, the United States means the 50 states, the District of Columbia and US territorial waters. The IRS says it does not include US territories, so dates in Puerto Rico, Guam, the US Virgin Islands, American Samoa and the Northern Mariana Islands (`PR`, `GU`, `VI`, `AS`, `MP`) are ignored here, as is every other code except `US`.

## Worked examples

The first row is the IRS’s own example. The other two are invented for this page. The calculator’s output is checked against this table.

Three examples (rows 2 and 3 are invented)
| Example | Year tested | Days in that year | Year before | Two years before | Weighted total | Result
| IRS example ([IRS](https://www.irs.gov/individuals/international-taxpayers/substantial-presence-test)) | 2025 | 120 | 120 (÷3 = 40) | 120 (÷6 = 20) | 120 + 40 + 20 = 180.00 | Not met: 180 is below 183
| Invented: enough days | 2026 | 150 | 120 (÷3 = 40) | 90 (÷6 = 15) | 150 + 40 + 15 = 205.00 | Met
| Invented: under 31 days | 2025 | 30 | 365 (÷3 = 121.67) | 365 (÷6 = 60.83) | 30 + 121.67 + 60.83 = 212.50 | Not met: fewer than 31 days in 2025, whatever the total

Press Try the IRS example to load the first row, and Try a second synthetic example to load the second as dated stays. The synthetic stays also include a few days in Puerto Rico and Canada, which are ignored. The third row is not a realistic itinerary; it only shows that the 31-day condition is checked separately.

## Days that are not counted

The IRS lists five kinds of day you do not count:

- Days you commute to work in the United States from a residence in Canada or Mexico, if you regularly commute from there.
- Days you are in the United States for less than 24 hours while in transit between two places outside the United States.
- Days you work as a crew member of a foreign vessel.
- Days you could not leave because of a medical condition that developed while you were in the United States.
- Days you are an exempt individual.

“Exempt individual” is a tax term for certain visa holders and athletes, not people exempt from US tax. It covers foreign government-related individuals on A or G visas (except A-3 and G-5), teachers or trainees on J or Q visas, students on F, J, M or Q visas (teachers, trainees and students must substantially comply with their visa requirements), and professional athletes in the United States temporarily to compete in a charitable sports event.

To exclude exempt-individual or medical-condition days you attach [Form 8843](https://www.irs.gov/forms-pubs/about-form-8843) to your return, or send it by the return due date if you do not have to file one. If you do not file it on time you cannot exclude those days, unless you show by clear and convincing evidence that you took reasonable actions to learn the filing requirements and significant steps to comply. Enter these days in the excluded-days field; this page cannot tell which of your days qualify.

## Meeting the test is not the end

Meeting the test does not settle your status. Someone who meets it may still be treated as a nonresident under the [closer connection exception](https://www.irs.gov/individuals/international-taxpayers/closer-connection-exception-to-the-substantial-presence-test). Among other conditions, you must have been in the United States fewer than 183 days in the year, kept a tax home in a foreign country for the whole year and had a closer connection to that country, and you must not have taken steps toward a green card. You claim it by filing [Form 8840](https://www.irs.gov/forms-pubs/about-form-8840).

A tax treaty can also affect your position. The IRS form for a treaty-based return position is [Form 8833](https://www.irs.gov/forms-pubs/about-form-8833). A green card is a separate route to US tax residency, and this test does not look at it.

This page makes no statement about any person’s outcome. Take the count and its dates to a qualified tax or immigration adviser.

## Where your dates come from

The page reads the Whereday Country Days CSV, with columns `country_code` and `local_date`. The [CSV format](https://whereday.com/tools/country-days-calculator/#csv-format) and its row checks are described on the days-in-country calculator, and this page uses the same rules: bad rows are listed with their line numbers, and a repeated country and date counts once.

Recorded days only cover the time the app was recording, so gaps make the count too low. Add missing stays by hand, and check the result against your I-94 travel history; [find your travel history](https://whereday.com/blog/find-your-travel-history/) explains where to look. [How Whereday records country days](https://whereday.com/blog/track-days-in-country-iphone/) covers travel days and time zones. For other day counts, use the [days-in-country calculator](https://whereday.com/tools/country-days-calculator/), or see all [tools](https://whereday.com/tools/).

Everything happens in your browser. This page blocks network connections and includes no analytics.

## Questions

### Does a layover count?

Days in the United States for less than 24 hours while in transit between two places outside the United States are not counted. A longer stay on a layover is a different case; see the [IRS page](https://www.irs.gov/individuals/international-taxpayers/substantial-presence-test).

### Do partial days count?

Yes. Any day you are physically in the United States counts, even briefly, unless one of the exclusions applies.

### Does Puerto Rico count?

No. The IRS says the United States for this test does not include US territories, so this calculator ignores `PR` and the other territory codes.

### What if I’m on an F-1 visa?

Students on F, J, M or Q visas who substantially comply with their visa requirements are exempt individuals, and their days are not counted, but you claim this with Form 8843. Enter those days as excluded days. Whether you qualify is for you and your adviser to decide.

## Keep the dates behind your count.

Whereday records the countries you’re in and keeps the dates on your iPhone. Export Country Days as CSV whenever you need a count.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Count days in other countries](https://whereday.com/tools/country-days-calculator/)

Check Schengen 90/180, any rolling window and calendar-year totals.

- [Schengen only?](https://whereday.com/tools/schengen-calculator/)

Count days used and days left and plan a stay.

- [Check your dates against official records](https://whereday.com/blog/find-your-travel-history/)

Find past trips to fill gaps before you rely on a count.

- [Record the dates automatically](https://whereday.com/blog/track-days-in-country-iphone/)

See how Whereday records country days and exports the CSV.

Source: https://whereday.com/tools/substantial-presence-test-calculator/
