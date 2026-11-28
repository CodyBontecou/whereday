- [Whereday](https://whereday.com/)
- [Tools](https://whereday.com/tools/)
- Citizenship physical presence

iPhone location journals

# US citizenship physical presence calculator (N-400)

List your trips and see days present, absences that risk continuous residence and the 90-day early filing date, with the working. A count, not legal advice.

By [Cody Bontecou](https://isolated.tech), Whereday’s developer · Reviewed November 28, 2026

## Three numbers for your N-400: days present, absences that matter and the earliest filing date

List your trips abroad and this calculator gives you three results, each with its working and a link to the USCIS or eCFR source: your physical presence in the statutory window (at least 913 days on the general 5-year path), any absence that breaks or risks breaking continuous residence, and your earliest filing date under the 90-day early filing rule. Choose the 5-year path or the 3-year path for spouses of US citizens. Nothing is uploaded.

## Calculate physical presence and continuous residence

This is a count, not legal advice; USCIS decides. It applies the counting rules below to the dates you enter. Missing or wrong trips change every result. It does not assess good moral character, the marital union test (see [Policy Manual Vol. 12, Part G, Ch. 2](https://www.uscis.gov/policy-manual/volume-12-part-g-chapter-2)), military or other special paths, Form N-470 or the 3-month state residence. [Read what is out of scope](https://whereday.com/tools/naturalization-physical-presence-calculator/#what-this-does-not-cover).

JavaScript is needed to count dates in your browser. The explanations and the worked example below work without it.

Your path and dates Path General path, 5 years (INA 316(a))Spouse of a US citizen, 3 years (INA 319(a))

Resident since (date on your green card)

Intended filing date (today by default)

Add a trip abroad

Departure date

Return date

Note (optional)

Add trip

### Your trips

None yet. A trip that returns on the day it left counts no days away.

Or load a CSV Choose a CSV (columns `departure_date`, `return_date`, optional `note`; dates as YYYY-MM-DD; up to 1 MiB)

Try the invented exampleDownload an invented sample CSVClear trips

The sample CSV and the example contain invented dates, not anyone’s real trips.

Add trips or load a CSV. Trips that overlap are refused; trips that cross the edge of the window are clipped.

### Rows that were not used

| File line | Problem | Row text

### Result

Download per-trip counts (CSV)Download summary (CSV)

## Worked example (invented)

An invented applicant on the general path has been a permanent resident since 2021-02-15 and plans to file on 2026-06-01. The four trips are invented too, and the calculator’s output is checked against this table.

Four invented trips
| Departure | Return | Full days away | Continuous residence
| 2022-07-01 | 2022-07-15 | 13 | No flag
| 2023-05-20 | 2023-12-30 | 223 | Presumed break, can be rebutted with evidence
| 2024-08-03 | 2024-08-24 | 20 | No flag
| 2025-12-20 | 2026-01-04 | 14 | No flag

Results for the invented example
| Result | Value | Working
| Window | 2021-06-02 to 2026-06-01 | The 5 years ending on the filing date: 1,826 days
| Days abroad | 270 | 13 + 223 + 20 + 14
| Physical presence total | 1556 | 1,826 − 270 = 1,556 days present, against 913 required
| Surplus | 643 | 1,556 − 913
| Presumed-break flag | 1 | The 2023 trip: 223 full days, more than 6 months and less than 1 year
| Earliest filing date | 2025-11-17 | 5 years reached 2026-02-15, minus 90 days

Press Try the invented example to load it. Physical presence is comfortably met here, but the 2023 trip still needs evidence that residence was not broken.

## How USCIS counts days

USCIS counts the day you depart and the day you return as days of physical presence ([Policy Manual Vol. 12, Part D, Ch. 4](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-4)). For continuous residence, USCIS does not count the dates of travel among the dates spent outside the United States ([Ch. 3](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-3)). Both rules point the same way: only the full days between departure and return are days away. This tool shows the two views side by side.

One trip, two views (depart 2023-05-20, return 2023-12-30)
| Question | Rule | Count
| Days present on the trip’s span | Departure and return days count as present (Ch. 4) | 2 (20 May and 30 Dec)
| Days away | Travel dates are not counted as days outside the US (Ch. 3) | 223 full days, 21 May to 29 Dec

The tool measures the length of each absence from your unclipped dates, and the days inside the window after clipping. 6 months and 1 year are calendar periods from the departure date: [Ch. 3](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-3)’s own example of leaving 1 January 2010 and returning 2 January 2011 is exactly 1 year. Leap years are handled by the calendar, not by a fixed 365 days.

The window is the 3 or 5 years ending on the filing date, both ends included. The requirement is at least 30 months (913 days) on the 5-year path ([Ch. 4](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-4)) and 18 months on the 3-year path ([8 CFR 319.1](https://www.ecfr.gov/current/title-8/chapter-I/subchapter-C)). USCIS gives no day count for 18 months, so the tool counts the days from 18 months before the filing date to the filing date, and shows that number.

## Absences of 6 to 12 months and of 1 year or more

More than 6 months and less than 1 year: an absence is presumed to break the continuity of residence. You can rebut the presumption with evidence. [Ch. 3](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-3) says evidence may include, but is not limited to, documentation that during the absence you did not terminate your US employment, kept your immediate family in the US, or retained full access to, or continued to own or lease, a home in the US. This tool only flags the trip; it cannot judge the evidence.

1 year or more: the absence breaks continuous residence automatically, unless you have an approved Form N-470 (Application to Preserve Residence for Naturalization Purposes). On the 5-year path, Ch. 3 says you must then wait at least 4 years and 1 day after returning, and still rebut the presumption, because the absence is within the past 5 years. If you apply at least 4 years and 6 months after re-establishing residence, the 6-month presumption no longer applies. The tool shows both dates from your return date for the general path.

Two caveats: the tool does not model N-470, and a break found in one absence does not tell you what other rules, such as abandonment of residence, would decide.

## The 90-day early filing rule

You may file up to 90 days before you first meet the required 5-year period (or 3-year period for spouses). The rule is in INA 334 and [8 CFR 334.2(b)](https://www.ecfr.gov/current/title-8/chapter-I/subchapter-C), and [Policy Manual Vol. 12, Part D, Ch. 6](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-6) explains it. The tool takes your resident-since date, adds 5 (or 3) years and subtracts 90 days.

Filing early does not make you eligible sooner: you must still complete the residence period. Physical presence still has to add up, and the 3-month state residence is a separate check. Ch. 6 says that when the three months overlap with early filing, jurisdiction is based on the three months before the examination. This tool does not compute state residence.

## Where to get your trip dates

Form N-400 asks about trips outside the United States (see the [Form N-400 page](https://www.uscis.gov/n-400)). [Find your travel history](https://whereday.com/blog/find-your-travel-history/) explains how to get your CBP I-94 history, and how to check it against passports and booking records. Keeping your own record as you travel avoids rebuilding it from memory later.

The [substantial presence test calculator](https://whereday.com/tools/substantial-presence-test-calculator/) is for a different question: the IRS tax test has its own 183-day weighted formula and is unrelated to these immigration counts. To count days in each country, use the [days-in-country calculator](https://whereday.com/tools/country-days-calculator/), and see [how Whereday records country days](https://whereday.com/blog/track-days-in-country-iphone/).

Everything happens in your browser. This page blocks network connections and includes no analytics.

## What this does not cover

- Good moral character.
- The marital union test for the 3-year path, beyond this reminder that it exists ([Policy Manual Vol. 12, Part G, Ch. 2](https://www.uscis.gov/policy-manual/volume-12-part-g-chapter-2)).
- Military and other special paths, and Form N-470.
- The 3-month state residence, which is named here and not computed.

Sources for the counting rules: [Ch. 3](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-3), [Ch. 4](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-4), [Ch. 6](https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-6) and 8 CFR [316.2, 316.5, 319.1 and 334.2](https://www.ecfr.gov/current/title-8/chapter-I/subchapter-C).

## Stop rebuilding trips from passport stamps.

Whereday records the countries you’re in on your iPhone and shows the dates behind each country total. Its Country Days export lists country codes and local dates, not departure and return pairs, so you still read your trips from those dates.

[Download free for iPhone](https://apps.apple.com/app/id6761960794)

## Read next

- [Asking about US tax instead?](https://whereday.com/tools/substantial-presence-test-calculator/)

The IRS substantial presence test is a different 183-day weighted rule.

- [Count days in other countries](https://whereday.com/tools/country-days-calculator/)

Check Schengen 90/180, any rolling window and calendar-year totals.

- [Find the dates for your trips](https://whereday.com/blog/find-your-travel-history/)

Get your I-94 travel history and check it against other records.

- [Record the dates automatically](https://whereday.com/blog/track-days-in-country-iphone/)

See how Whereday records country days instead of rebuilding them later.

Source: https://whereday.com/tools/naturalization-physical-presence-calculator/
