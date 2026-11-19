import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import {
  SCHENGEN, SCHENGEN_EXAMPLE, parseCountryDaysCSV, makeStay, buildIndex, countryTotals, summary, daysFor, maxWindow,
  schengenCheck, calendarYears, totalsCSV, schengenCSV, parseDay, formatDay, isCountryCode,
} from '../public/assets/country-days-core.mjs';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const sample = await read('../public/samples/synthetic-country-days.csv');
const article = await read('../content/country-days-calculator.html');
const d = parseDay;
const stay = (country, first, last) => makeStay(country, first, last).stay;
const totals = index => Object.fromEntries(countryTotals(index).map(row => [row.country, row.count]));

test('the synthetic Whereday export gives PT 2, ES 1, FR 1 and three Schengen days on 2026-10-03', () => {
  const parsed = parseCountryDaysCSV(sample);
  assert.equal(parsed.rejected.length, 0);
  assert.equal(parsed.hasSource, true);
  const index = buildIndex(parsed.records);
  assert.deepEqual(totals(index), { PT: 2, ES: 1, FR: 1 });
  assert.deepEqual(summary(index), { distinctDates: 3, countryDates: 4, countries: 3 });
  const check = schengenCheck(index, d('2026-10-03'));
  assert.equal(check.used, 3);
  assert.equal(check.remaining, 87);
  assert.deepEqual(check.countriesByDay.map(({ day, countries }) => [formatDay(day), countries]),
    [['2026-10-01', ['ES', 'PT']], ['2026-10-02', ['PT']], ['2026-10-03', ['FR']]]);
});

test('the embedded demo module is the published sample file', async () => {
  const { DEMO_COUNTRY_DAYS } = await import('../public/assets/country-days-demo.mjs');
  assert.equal(DEMO_COUNTRY_DAYS, sample);
});

test('CSV rows and manual stays are de-duplicated by country and date', () => {
  const parsed = parseCountryDaysCSV('country_code,local_date,source\nPT,2026-10-01,recorded\npt,2026-10-01,recorded\nPT,2026-10-02,manual\n');
  const index = buildIndex(parsed.records, [stay('PT', '2026-10-02', '2026-10-04'), stay('PT', '2026-10-03', '2026-10-03')]);
  assert.deepEqual(totals(index), { PT: 4 });
  const [row] = countryTotals(index);
  assert.deepEqual(row.sources[1], ['file: manual', 'manual stay']);
});

test('a travel day counts in both countries but once in the Schengen area', () => {
  const index = buildIndex([], [stay('FR', '2026-06-01', '2026-06-01'), stay('IT', '2026-06-01', '2026-06-02'), stay('GB', '2026-06-01', '2026-06-01')]);
  assert.deepEqual(totals(index), { IT: 2, FR: 1, GB: 1 });
  assert.equal(summary(index).distinctDates, 2);
  assert.equal(summary(index).countryDates, 4);
  assert.equal(schengenCheck(index, d('2026-06-02')).used, 2);
});

test('the 180-day window includes day 180 and excludes day 181', () => {
  const reference = d('2026-12-31');
  const day180 = formatDay(reference - 179), day181 = formatDay(reference - 180);
  assert.equal(day180, '2026-07-05');
  const inside = buildIndex([], [stay('DE', day180, day180)]);
  const outside = buildIndex([], [stay('DE', day181, day181)]);
  assert.equal(schengenCheck(inside, reference).used, 1);
  assert.equal(schengenCheck(inside, reference).windowStart, d(day180));
  assert.equal(schengenCheck(outside, reference).used, 0);
});

test('earliest full 90-day stay needs 90 clear days before it', () => {
  const index = buildIndex([], [stay('ES', '2026-01-01', '2026-03-31')]);
  assert.equal(formatDay(schengenCheck(index, d('2026-03-31')).earliestFull90), '2026-06-30');
  assert.equal(schengenCheck(index, d('2026-03-31')).used, 90);
  // With a gap already long enough, a full stay can start on the reference date itself.
  assert.equal(formatDay(schengenCheck(index, d('2026-07-15')).earliestFull90), '2026-07-15');
  assert.equal(formatDay(schengenCheck(buildIndex(), d('2026-07-15')).earliestFull90), '2026-07-15');
  // Dates after the reference date are reported, not used.
  const later = schengenCheck(index, d('2026-02-01'));
  assert.equal(later.laterDays, 58);
  assert.equal(formatDay(later.earliestFull90), '2026-05-03');
  // Over the limit.
  const over = schengenCheck(buildIndex([], [stay('ES', '2026-01-01', '2026-04-30')]), d('2026-04-30'));
  assert.equal(over.used, 120); assert.equal(over.remaining, 0); assert.equal(over.over, 30);
});

test('rejected rows are reported with line numbers and reasons, never dropped', () => {
  const csv = 'source,LOCAL_DATE,Country_Code\r\nrecorded,2026-01-01,FR\r\nmanual,2026-13-01,FR\r\n,2026-01-02,ZZ\r\n,,\r\n\r\nx,2026-01-03,\r\n"quoted, field",2026-01-04,"de"\r\n';
  const parsed = parseCountryDaysCSV(csv);
  assert.equal(parsed.rows, 7);
  assert.deepEqual(parsed.records.map(r => [r.country, formatDay(r.day), r.row, r.source]), [['FR', '2026-01-01', 2, 'recorded'], ['DE', '2026-01-04', 8, 'quoted, field']]);
  assert.deepEqual(parsed.rejected.map(r => r.row), [3, 4, 5, 6, 7]);
  assert.match(parsed.rejected[0].reason, /not a valid YYYY-MM-DD/);
  assert.match(parsed.rejected[1].reason, /ZZ.*not an ISO 3166-1/);
  assert.match(parsed.rejected[2].reason, /missing country_code; missing local_date/);
  assert.equal(parsed.rejected[3].reason, 'empty row');
  assert.match(parsed.rejected[4].reason, /^missing country_code$/);
  assert.equal(parsed.records.length + parsed.rejected.length, parsed.rows);
  assert.throws(() => parseCountryDaysCSV('country,date\nFR,2026-01-01\n'), /country_code and local_date/);
  assert.throws(() => parseCountryDaysCSV(''), /empty/);
  assert.equal(parseCountryDaysCSV('﻿country_code,local_date\nFR,2026-01-01').records.length, 1);
});

test('leap years: 29 February exists only in leap years and counts in windows', () => {
  assert.equal(parseDay('2024-02-29') !== null, true);
  assert.equal(parseDay('2026-02-29'), null);
  assert.equal(parseDay('1900-02-29'), null);
  assert.equal(parseDay('2000-02-29') !== null, true);
  assert.equal(parseDay('2026-1-05'), null);
  const index = buildIndex([], [stay('NL', '2024-02-01', '2024-03-31')]);
  assert.deepEqual(totals(index), { NL: 60 });
  assert.equal(calendarYears(index)[0].countries[0].count, 60);
  assert.equal(schengenCheck(index, d('2024-02-29')).used, 29);
  assert.equal(formatDay(d('2024-02-28') + 1), '2024-02-29');
});

test('a range that crosses a year boundary splits calendar years but not rolling windows', () => {
  const index = buildIndex([], [stay('TH', '2025-12-01', '2026-01-31')]);
  assert.deepEqual(calendarYears(index).map(y => [y.year, y.countries[0].count]), [[2026, 31], [2025, 31]]);
  assert.equal(countryTotals(index, d('2025-12-15'), d('2026-01-15'))[0].count, 32);
  const window = maxWindow(daysFor(index, ['TH']), 365, d('2026-01-01'), d('2026-12-31'));
  assert.equal(window.max, 62);
  assert.equal(formatDay(window.end), '2026-01-31');
  assert.equal(formatDay(window.start), '2025-02-01');
});

test('rolling window reports the earliest end date of the maximum and respects the selected range', () => {
  const days = daysFor(buildIndex([], [stay('ES', '2025-01-01', '2025-07-02'), stay('ES', '2026-03-01', '2026-03-10')]), ['ES']);
  assert.equal(days.length, 183 + 10);
  const all = maxWindow(days, 365, d('2025-01-01'), d('2026-12-31'));
  assert.equal(all.max, 183); assert.equal(formatDay(all.end), '2025-07-02');
  // A window that must end in 2026 still reaches back 365 days.
  const late = maxWindow(days, 365, d('2026-01-01'), d('2026-12-31'));
  assert.equal(formatDay(late.end), '2026-01-01');
  assert.equal(late.max, 182); // 2025-01-01 has left the window
  const later = maxWindow(days, 365, d('2026-08-01'), d('2026-12-31'));
  assert.equal(later.max, 10); assert.equal(formatDay(later.end), '2026-08-01');
  assert.equal(maxWindow([], 365, d('2026-01-01'), d('2026-01-31')).max, 0);
});

test('calendar-year view flags 183 or more', () => {
  const index = buildIndex([], [stay('PT', '2026-01-01', '2026-07-02'), stay('ES', '2026-07-03', '2026-12-31')]);
  const [year] = calendarYears(index);
  assert.deepEqual(year.countries.map(c => [c.country, c.count, c.atThreshold]), [['PT', 183, true], ['ES', 182, false]]);
});

test('manual stays are validated', () => {
  assert.match(makeStay('', '2026-01-01', '2026-01-02').error, /country/);
  assert.match(makeStay('FR', '2026-01-03', '2026-01-02').error, /on or after/);
  assert.match(makeStay('FR', '2026-02-30', '2026-03-02').error, /first date/);
  assert.match(makeStay('FR', '2000-01-01', '2026-01-01').error, /3,660/);
  assert.deepEqual(makeStay('fr', '2026-01-01', '2026-01-01').stay, { country: 'FR', first: d('2026-01-01'), last: d('2026-01-01') });
});

test('downloads are well-formed CSV', () => {
  const index = buildIndex(parseCountryDaysCSV(sample).records);
  assert.equal(totalsCSV(index, null, null),
    'period_first,period_last,country_code,country_name,days,first_date,last_date\r\n,,PT,PT,2,2026-10-01,2026-10-02\r\n,,ES,ES,1,2026-10-01,2026-10-01\r\n,,FR,FR,1,2026-10-03,2026-10-03\r\n');
  const csv = schengenCSV(schengenCheck(index, d('2026-10-03')));
  assert.ok(csv.startsWith('reference_date,window_first,window_last,days_used,days_remaining,earliest_full_90_day_stay,member_list_checked\r\n2026-10-03,2026-04-07,2026-10-03,3,87,2027-01-02,2026-11-18\r\n'));
  assert.ok(csv.includes('\r\n2026-10-01,ES PT\r\n'));
  assert.ok(totalsCSV(index, null, null, () => '=cmd').includes(",'=cmd,"));
});

test('the article’s worked Schengen example matches the calculator exactly', () => {
  const index = buildIndex([], SCHENGEN_EXAMPLE.stays.map(s => stay(s.country, s.first, s.last)));
  assert.deepEqual(totals(index), { FR: 60, IT: 30 });
  const table = article.slice(article.indexOf('id="schengen-example-results"'), article.indexOf('</table>', article.indexOf('id="schengen-example-results"')));
  const rows = [...table.matchAll(/<tr data-reference="([^"]+)" data-window-start="([^"]+)" data-used="(\d+)" data-remaining="(\d+)" data-earliest="([^"]+)"><th scope="row">([^<]+)<\/th><td>([^<]+)<\/td><td>(\d+)<\/td><td>(\d+)<\/td><td>([^<]+)<\/td><\/tr>/g)];
  assert.equal(rows.length, SCHENGEN_EXAMPLE.references.length);
  for (const [, reference, start, used, remaining, earliest, shownReference, shownWindow, shownUsed, shownRemaining, shownEarliest] of rows) {
    const result = schengenCheck(index, d(reference));
    assert.equal(formatDay(result.windowStart), start);
    assert.equal(result.used, Number(used));
    assert.equal(result.remaining, Number(remaining));
    assert.equal(formatDay(result.earliestFull90), earliest);
    assert.deepEqual([shownReference, shownWindow, shownUsed, shownRemaining, shownEarliest], [reference, `${start} to ${reference}`, used, remaining, earliest]);
  }
  assert.deepEqual(rows.map(r => r[1]), [...SCHENGEN_EXAMPLE.references]);
  // The prose states the same dates and counts.
  for (const text of ['5 January to 5 March 2026', '6 March to 14 April', '15 April to 14 May', '61 days are used and 29 remain', 'start on 13 August', '15 May to 12 August']) assert.ok(article.includes(text), text);
  const fr = SCHENGEN_EXAMPLE.stays[0], gapDays = d(SCHENGEN_EXAMPLE.stays[1].first) - d(fr.last) - 1;
  assert.equal(gapDays, 40);
  // 90 clear days after the last Schengen day, then the new stay starts.
  assert.equal(formatDay(d(SCHENGEN_EXAMPLE.stays[1].last) + 91), '2026-08-13');
  assert.equal(formatDay(d(SCHENGEN_EXAMPLE.stays[1].last) + 90), '2026-08-12');
});

test('the visible member list matches the dated constant', () => {
  assert.equal(SCHENGEN.members.length, 29);
  assert.ok(SCHENGEN.members.every(isCountryCode));
  assert.ok(!SCHENGEN.members.includes('CY') && !SCHENGEN.members.includes('IE'));
  assert.ok(article.includes(`data-schengen-checked="${SCHENGEN.checked}"`));
  const names = new Intl.DisplayNames(['en'], { type: 'region' });
  const visible = article.match(/<p class="cdc-members"[^>]*>([^<]+)\(29 states\)/)[1];
  for (const code of SCHENGEN.members) assert.ok(visible.includes(names.of(code)), `${code} ${names.of(code)}`);
  assert.equal(visible.split(/, | and /).filter(Boolean).length, 29);
  assert.ok(article.includes(SCHENGEN.source));
});

test('the limits note sits above the results, and the page loads no remote script', () => {
  assert.ok(article.indexOf('id="limits-summary"') < article.indexOf('id="cdc-results"'));
  assert.ok(!/<script[^>]+src="https?:/.test(article));
});

test('worker blocks network connections on every alias of the tool', async () => {
  const source = (await read('../worker.js')).replace('__PAGE_MARKDOWN__', '{}');
  const worker = (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;
  for (const path of ['/tools/country-days-calculator/', '/tools/country-days-calculator', '/tools/country-days-calculator/index.html']) {
    const response = await worker.fetch(new Request('https://whereday.com' + path), { ASSETS: { fetch: async () => new Response('Country days', { headers: { 'Content-Type': 'text/html' } }) } });
    assert.ok(response.headers.get('Content-Security-Policy').includes("connect-src 'none'"), path);
    assert.equal(response.headers.get('Referrer-Policy'), 'no-referrer');
  }
});

test('the generated page excludes analytics', async () => {
  const page = await read('../public/tools/country-days-calculator/index.html');
  assert.ok(!page.includes('acquisition.js'));
  assert.ok(!page.includes('cloudflareinsights'));
  assert.ok(page.includes('/assets/country-days.mjs'));
});
