import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import {
  parseDay, formatDay, addMonths, addYears, makeTrip, parseTripsCSV, checkOverlaps, absenceStatus, windowFor,
  earliestFiling, naturalization, tripsCSV, summaryCSV, SAMPLE_CSV, SAMPLE_INPUT,
} from '../public/assets/npp-core.mjs';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const page = await read('../content/naturalization-physical-presence-calculator.html');
const D = parseDay, F = formatDay;
const trip = (a, b) => makeTrip(a, b).trip;
const run = (path, since, filing, pairs) => naturalization({ path, since: D(since), filing: D(filing), trips: pairs.map(([a, b]) => trip(a, b)) });

test('departure and return days count as present: full days away exclude both', () => {
  const r = run('general', '2015-01-01', '2026-06-01', [['2025-03-10', '2025-03-15']]);
  assert.equal(r.rows[0].fullDays, 4);
  assert.equal(r.abroad, 4);
  assert.equal(r.present, r.windowDays - 4);
});

test('same-day and next-day trips have no days away', () => {
  assert.equal(run('general', '2015-01-01', '2026-06-01', [['2025-03-10', '2025-03-10']]).abroad, 0);
  assert.equal(run('general', '2015-01-01', '2026-06-01', [['2025-03-10', '2025-03-11']]).abroad, 0);
});

test('6-month and 1-year thresholds follow the calendar (Ch. 3 example: 2010-01-01 to 2011-01-02 is exactly 1 year)', () => {
  assert.equal(absenceStatus(trip('2010-01-01', '2011-01-02')).status, 'break');
  assert.equal(absenceStatus(trip('2010-01-01', '2011-01-01')).status, 'presumed');
  assert.equal(absenceStatus(trip('2010-01-01', '2010-07-02')).status, 'ok');
  assert.equal(absenceStatus(trip('2010-01-01', '2010-07-03')).status, 'presumed');
  assert.equal(absenceStatus(trip('2010-01-01', '2010-03-01')).status, 'ok');
});

test('leap years: a leap day in the span does not push a 1-year absence under the line', () => {
  const t = trip('2023-03-01', '2024-03-02');
  assert.equal(absenceStatus(t).fullDays, 366);
  assert.equal(absenceStatus(t).status, 'break');
  assert.equal(absenceStatus(trip('2023-03-01', '2024-03-01')).status, 'presumed');
  assert.equal(F(addYears(D('2024-02-29'), 1)), '2025-02-28');
  assert.equal(F(addMonths(D('2024-08-31'), 6)), '2025-02-28');
  assert.equal(F(addMonths(D('2023-08-31'), 6)), '2024-02-29');
});

test('a 1-year break shows 4 years and 1 day and 4 years and 6 months after return', () => {
  const r = run('general', '2010-01-01', '2026-06-01', [['2023-01-01', '2024-01-02']]);
  assert.equal(r.breaks, 1);
  assert.equal(F(r.rows[0].wait.fourYearsOneDay), '2028-01-03');
  assert.equal(F(r.rows[0].wait.noPresumption), '2028-07-02');
  assert.equal(run('spouse', '2010-01-01', '2026-06-01', [['2023-01-01', '2024-01-02']]).rows[0].wait, undefined);
});

test('window clipping: only days inside the window count and the trip is marked clipped', () => {
  const r = run('general', '2015-01-01', '2026-06-01', [['2021-05-25', '2021-06-05'], ['2026-05-28', '2026-06-10'], ['2019-01-01', '2019-01-10']]);
  assert.equal(F(r.window.first), '2021-06-02');
  assert.equal(r.windowDays, 1826);
  const [a, b, c] = r.rows.sort((x, y) => x.dep - y.dep).map(x => x);
  assert.equal(a.outside, true);
  assert.equal(r.rows.find(x => x.dep === D('2021-05-25')).abroadInWindow, 3); // 2 to 4 June
  assert.equal(r.rows.find(x => x.dep === D('2026-05-28')).abroadInWindow, 4); // 29, 30, 31 May and 1 June (the filing date)
  assert.equal(r.clipped, 2);
  assert.equal(r.ignored, 1);
});

test('window is exactly 5 or 3 years ending on the filing date, leap-aware', () => {
  assert.equal(windowFor(D('2026-06-01'), 5).last - windowFor(D('2026-06-01'), 5).first + 1, 1826);
  const w = windowFor(D('2028-02-29'), 5);
  assert.equal(F(w.first), '2023-03-01');
  assert.equal(w.last - w.first + 1, 1827);
  assert.equal(run('spouse', '2015-01-01', '2026-06-01', []).windowDays, 1096);
});

test('physical presence: 913 on the 5-year path, 18 months counted in days on the 3-year path', () => {
  const r = run('general', '2015-01-01', '2026-06-01', [['2021-07-01', '2024-03-01']]);
  assert.equal(r.required, 913);
  assert.equal(r.enough, r.present >= 913);
  const exact = run('general', '2015-01-01', '2026-06-01', []);
  assert.equal(exact.diff, 1826 - 913);
  const s = run('spouse', '2015-01-01', '2026-06-01', []);
  assert.equal(s.required, 547); // 2024-12-01 to 2026-06-01
  assert.equal(s.requiredMonths, 18);
});

test('shortfall is reported as negative', () => {
  const r = run('general', '2015-01-01', '2026-06-01', [['2021-07-01', '2024-07-01']]);
  assert.ok(r.diff < 0 && !r.enough);
});

test('earliest filing date: 5 (or 3) years after resident since, minus 90 days', () => {
  assert.equal(F(earliestFiling(D('2021-02-15'), 'general').reached), '2026-02-15');
  assert.equal(F(earliestFiling(D('2021-02-15'), 'general').earliest), '2025-11-17');
  assert.equal(F(earliestFiling(D('2021-02-15'), 'spouse').earliest), '2023-11-17');
  assert.equal(F(earliestFiling(D('2020-02-29'), 'general').reached), '2025-02-28');
  const r = run('general', '2021-02-15', '2025-11-16', []);
  assert.equal(r.filingBeforeEarliest, true);
  assert.equal(run('general', '2021-02-15', '2025-11-17', []).filingBeforeEarliest, false);
});

test('validation: return before departure, bad dates and overlaps', () => {
  assert.ok(makeTrip('2025-02-02', '2025-02-01').error);
  assert.ok(makeTrip('2025-02-30', '2025-03-01').error);
  assert.equal(checkOverlaps([trip('2025-01-01', '2025-01-10'), trip('2025-01-10', '2025-01-20')]).length, 0);
  assert.equal(checkOverlaps([trip('2025-01-01', '2025-01-10'), trip('2025-01-09', '2025-01-20')]).length, 1);
});

test('CSV: accepts optional note, quotes, lists bad rows with line numbers', () => {
  const csv = 'departure_date,return_date,note\n2025-01-01,2025-01-10,"a, b"\n2025-02-01,2025-01-01,\nbad,2025-01-01,\n2025-03-01,2025-03-02\n';
  const p = parseTripsCSV(csv);
  assert.equal(p.trips.length, 2);
  assert.equal(p.trips[0].note, 'a, b');
  assert.deepEqual(p.rejected.map(r => r.row), [3, 4]);
  assert.throws(() => parseTripsCSV('a,b\n1,2'));
});

test('downloads: CSVs have the expected columns and neutralise formulas', () => {
  const r = run('general', '2021-02-15', '2026-06-01', [['2025-01-01', '2025-01-10']]);
  r.rows[0].note = '=HYPERLINK("x")';
  assert.match(tripsCSV(r), /^departure_date,return_date,full_days_away,days_away_in_window,in_window,clipped,continuous_residence,note\r\n/);
  assert.ok(tripsCSV(r).includes("'=HYPERLINK"));
  assert.match(summaryCSV(r, D('2021-02-15'), D('2026-06-01')), /earliest_filing_date,2025-11-17/);
});

test('the worked example matches the calculator exactly', () => {
  const parsed = parseTripsCSV(SAMPLE_CSV);
  assert.equal(parsed.trips.length, 4);
  const r = naturalization({ path: SAMPLE_INPUT.path, since: D(SAMPLE_INPUT.since), filing: D(SAMPLE_INPUT.filing), trips: parsed.trips });
  const cell = key => page.match(new RegExp(`data-key="${key}">([^<]+)<`))[1];
  assert.equal(cell('window'), `${F(r.window.first)} to ${F(r.window.last)}`);
  assert.equal(cell('abroad'), String(r.abroad));
  assert.equal(cell('present'), String(r.present));
  assert.equal(cell('diff'), String(r.diff));
  assert.equal(cell('presumed'), String(r.presumed));
  assert.equal(cell('earliest'), F(r.earliest));
  assert.equal(r.windowDays, 1826);
  assert.deepEqual(r.rows.map(x => x.fullDays), [13, 223, 20, 14]);
  assert.deepEqual(r.rows.map(x => x.status), ['ok', 'presumed', 'ok', 'ok']);
  for (const t of r.rows) assert.ok(page.includes(`<th scope="row">${F(t.dep)}</th><td>${F(t.ret)}</td><td>${t.fullDays}</td>`));
  assert.ok(page.includes('1,826 − 270 = 1,556') && page.includes('1,556 − 913'));
  assert.ok(SAMPLE_CSV.includes('Invented'));
});

test('page: limits above the tool, sources linked, out-of-scope list, no remote script', () => {
  assert.ok(page.indexOf('id="limits-summary"') < page.indexOf('id="npp-file"'));
  assert.ok(page.includes('not legal advice'));
  for (const id of ['calculator', 'worked-example', 'how-uscis-counts-days', 'long-absences', 'early-filing', 'where-your-dates-come-from', 'what-this-does-not-cover']) assert.ok(page.includes(`id="${id}"`), id);
  for (const url of ['policy-manual/volume-12-part-d-chapter-3', 'policy-manual/volume-12-part-d-chapter-4', 'policy-manual/volume-12-part-d-chapter-6', 'ecfr.gov/current/title-8', 'uscis.gov/n-400']) assert.ok(page.includes(url), url);
  for (const href of ['/tools/substantial-presence-test-calculator/', '/tools/country-days-calculator/', '/blog/find-your-travel-history/', '/blog/track-days-in-country-iphone/']) assert.ok(page.includes(`href="${href}"`), href);
  assert.ok(!/<script[^>]+src="https?:/.test(page));
});

test('the generated page excludes analytics, and the other pages link to it', async () => {
  const out = await read('../public/tools/naturalization-physical-presence-calculator/index.html');
  assert.ok(!out.includes('acquisition.js') && !out.includes('cloudflareinsights'));
  assert.ok(out.includes("connect-src 'none'"));
  assert.ok(out.includes('<title>US Citizenship Physical Presence Calculator (N-400) — Whereday</title>'));
  for (const p of ['../public/tools/index.html', '../public/blog/find-your-travel-history/index.html', '../public/tools/substantial-presence-test-calculator/index.html', '../public/blog/track-days-in-country-iphone/index.html']) {
    assert.ok((await read(p)).includes('/tools/naturalization-physical-presence-calculator/'), p);
  }
});
