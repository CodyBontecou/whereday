import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import {
  EXAMPLE, makeTrip, buildDayMap, readCountryDaysCSV, sortedDays, usedOn, windowRows, mustLeaveBy, longestStay, earliestStart,
  stayFits, windowCSV, parseDay as d, formatDay,
} from '../public/assets/schengen-core.mjs';
import { schengenCheck, buildIndex, makeStay } from '../public/assets/country-days-core.mjs';

const page = await readFile(new URL('../content/schengen-calculator.html', import.meta.url), 'utf8');
const trip = (a, b = '') => makeTrip(a, b).trip;
const daysOf = (trips, ref, records = []) => sortedDays(buildDayMap({ trips, records }, d(ref)).map);

test('inclusive counting: 1 to 3 January is 3 days; overlaps and same-day entries count once', () => {
  assert.equal(usedOn(daysOf([trip('2026-01-01', '2026-01-03')], '2026-01-03'), d('2026-01-03')).used, 3);
  assert.equal(usedOn(daysOf([trip('2026-01-01', '2026-01-05'), trip('2026-01-04', '2026-01-08')], '2026-01-08'), d('2026-01-08')).used, 8);
  const csv = 'country_code,local_date,source\nFR,2026-01-01,recorded\nIT,2026-01-01,recorded\nUS,2026-01-02,recorded\n';
  const parsed = readCountryDaysCSV(csv);
  assert.equal(parsed.ignored, 1);
  assert.equal(usedOn(daysOf([], '2026-01-05', parsed.records), d('2026-01-05')).used, 1);
});

test('trip validation', () => {
  assert.ok(makeTrip('', '2026-01-01').error);
  assert.ok(makeTrip('2026-01-05', '2026-01-01').error);
  assert.equal(makeTrip('2026-01-05', '').trip.last, null);
});

test('days used and left on a reference date use a 180-day window that includes the reference date', () => {
  const days = daysOf([trip('2026-01-01', '2026-03-31')], '2026-06-29'); // 90 days
  assert.equal(usedOn(days, d('2026-03-31')).used, 90);
  assert.equal(usedOn(days, d('2026-03-31')).remaining, 0);
  // window ending 2026-06-29 starts 2026-01-01 (180 days inclusive); one day later 1 January drops out
  assert.equal(formatDay(usedOn(days, d('2026-06-29')).windowStart), '2026-01-01');
  assert.equal(usedOn(days, d('2026-06-29')).used, 90);
  assert.equal(usedOn(days, d('2026-06-30')).used, 89);
});

test('agrees with the days-in-country calculator on the same dates', () => {
  const stays = [makeStay('FR', '2026-01-05', '2026-03-05').stay, makeStay('IT', '2026-03-05', '2026-04-01').stay];
  const old = schengenCheck(buildIndex([], stays), d('2026-05-01'));
  const mine = usedOn(daysOf([trip('2026-01-05', '2026-04-01')], '2026-05-01'), d('2026-05-01'));
  assert.equal(mine.used, old.used);
  assert.equal(mine.remaining, old.remaining);
});

test('must-leave date for an ongoing stay', () => {
  const trips = [trip('2026-01-01', '2026-01-30'), trip('2026-05-01')];
  const days = daysOf(trips, '2026-05-10');
  const now = usedOn(days, d('2026-05-10'));
  assert.equal(now.used, 40);
  const leave = mustLeaveBy(days, d('2026-05-10'));
  // staying to the last day keeps 90 in the window ending that day
  const full = [...days, ...Array.from({ length: leave.extraDays }, (_, i) => d('2026-05-10') + 1 + i)];
  assert.ok(usedOn(full, leave.last).used <= 90);
  assert.equal(usedOn([...full, leave.last + 1], leave.last + 1).used > 90, true);
});

test('an ongoing stay that is already over 90 has no last day', () => {
  const days = daysOf([trip('2026-01-01')], '2026-05-01');
  assert.equal(usedOn(days, d('2026-05-01')).over, 31);
  assert.equal(mustLeaveBy(days, d('2026-05-01')).last, null);
});

test('planner longest stay checks every day, not only the last', () => {
  // A trip in the middle of a window: a late, short stay is fine at the end but fails mid-stay.
  const days = daysOf([trip('2026-01-01', '2026-02-15')], '2026-12-31'); // 46 days
  const longest = longestStay(days, d('2026-03-01'));
  assert.equal(longest.length, 44);
  assert.equal(formatDay(longest.last), '2026-04-13');
  assert.equal(longest.checks.at(-1).used, 90);
  assert.ok(longest.checks.every(c => c.used <= 90));
  assert.equal(longest.blocked.used, 91);
  assert.equal(stayFits(days, d('2026-03-01'), 44), true);
  assert.equal(stayFits(days, d('2026-03-01'), 45), false);
});

test('days falling out of the window mid-trip lengthen the planned stay', () => {
  const days = daysOf([trip('2026-01-01', '2026-03-21')], '2026-12-31'); // 80 days; 1 January stops counting on 2026-06-30
  // Entering 2026-06-10 hits 90 on 2026-06-19, before anything drops out.
  const early = longestStay(days, d('2026-06-10'));
  assert.equal(early.length, 10);
  assert.equal(formatDay(early.last), '2026-06-19');
  // Entering 2026-06-20 reaches 90 on 2026-06-29; from 06-30 one old day drops out for each new day, so the stay runs the full 90 days.
  const late = longestStay(days, d('2026-06-20'));
  assert.equal(late.length, 90);
  assert.equal(late.blocked, null);
  assert.ok(late.checks.every(c => c.used <= 90));
  assert.equal(late.checks.find(c => formatDay(c.day) === '2026-06-29').used, 90);
  assert.equal(late.checks.find(c => formatDay(c.day) === '2026-06-30').used, 90);
});

test('earliest start for N days', () => {
  const days = daysOf([trip('2026-01-01', '2026-03-31')], '2026-12-31'); // 90 days
  // 1 January stops counting on 2026-06-30, so a stay can start then and still keep every day at 90 or fewer.
  const full = earliestStart(days, d('2026-04-01'), 90);
  assert.equal(formatDay(full.entry), '2026-06-30');
  assert.equal(formatDay(full.last), '2026-09-27');
  assert.equal(stayFits(days, full.entry, 90), true);
  assert.equal(stayFits(days, full.entry - 1, 90), false);
  assert.equal(formatDay(earliestStart(days, d('2026-04-01'), 1).entry), '2026-06-30');
  assert.ok(earliestStart(days, d('2026-04-01'), 91).error);
  assert.equal(formatDay(earliestStart([], d('2026-02-01'), 90).entry), '2026-02-01');
});

test('Schengen member rows come from the shared list and CSV ignored counts are reported', () => {
  const parsed = readCountryDaysCSV('country_code,local_date\nES,2026-01-01\nGB,2026-01-02\nIE,2026-01-03\nCY,2026-01-04\nXX,2026-01-05\n');
  assert.equal(parsed.records.length, 1);
  assert.equal(parsed.ignored, 3);
  assert.equal(parsed.rejected.length, 1);
});

test('window rows and CSV list each counted date with its trip and its fall-out date', () => {
  const ref = d('2026-02-01');
  const { map } = buildDayMap({ trips: [trip('2026-01-30', '2026-02-01')] }, ref);
  const rows = windowRows(map, ref);
  assert.deepEqual(rows.map(r => [formatDay(r.day), r.sources, formatDay(r.fallsOut)]),
    [['2026-01-30', ['Trip 1'], '2026-07-29'], ['2026-01-31', ['Trip 1'], '2026-07-30'], ['2026-02-01', ['Trip 1'], '2026-07-31']]);
  assert.match(windowCSV(map, ref), /local_date,sources,first_date_it_no_longer_counts\r\n2026-01-30,Trip 1,2026-07-29/);
});

test('an ongoing trip counts up to the reference date and not beyond', () => {
  assert.equal(usedOn(daysOf([trip('2026-05-01')], '2026-05-10'), d('2026-05-10')).used, 10);
  assert.equal(buildDayMap({ trips: [trip('2026-05-20')] }, d('2026-05-10')).map.size, 0);
});

test('the worked example on the page matches the calculator output', () => {
  const rows = [...page.matchAll(/<tr data-reference="([^"]+)" data-ongoing="(\d)" data-window-start="([^"]+)" data-used="(\d+)" data-remaining="(\d+)"( data-last="([^"]+)")?>/g)];
  assert.equal(rows.length, EXAMPLE.references.length);
  rows.forEach(([, ref, ongoing, windowStart, used, remaining, , last], i) => {
    assert.equal(ref, EXAMPLE.references[i]);
    const trips = EXAMPLE.trips.map(t => ({ first: d(t.first), last: t.last === null ? (ongoing === '1' ? null : d(EXAMPLE.endedOn)) : d(t.last) }));
    const days = sortedDays(buildDayMap({ trips }, d(ref)).map);
    const now = usedOn(days, d(ref));
    assert.equal(formatDay(now.windowStart), windowStart);
    assert.equal(String(now.used), used);
    assert.equal(String(now.remaining), remaining);
    if (ongoing === '1') assert.equal(formatDay(mustLeaveBy(days, d(ref)).last), last);
  });
  // Trip lengths quoted in the prose
  assert.equal(usedOn(daysOf([trip('2026-01-05', '2026-02-03')], '2026-02-03'), d('2026-02-03')).used, 30);
  assert.equal(usedOn(daysOf([trip('2026-04-01', '2026-04-25')], '2026-04-25'), d('2026-04-25')).used, 25);
  // Planner paragraph
  const closed = EXAMPLE.trips.map(t => ({ first: d(t.first), last: t.last === null ? d(EXAMPLE.endedOn) : d(t.last) }));
  const days = sortedDays(buildDayMap({ trips: closed }, d(EXAMPLE.references[1])).map);
  const longest = longestStay(days, d(EXAMPLE.plannedEntry));
  const text = page.replace(/<[^>]+>/g, '');
  assert.ok(text.includes(`longest stay of ${longest.length} days, with the latest exit on ${formatDay(longest.last)}`));
  assert.ok(text.includes(`${formatDay(longest.blocked.day)}, would be day 91`));
  assert.ok(text.includes(`full 90-day stay could start is ${formatDay(earliestStart(days, d(EXAMPLE.references[1]), 90).entry)}`));
});

test('the page names the required sections and sources', () => {
  for (const id of ['how-it-works', 'worked-example', 'members', 'what-doesnt-count', 'ees', 'limits', 'privacy']) assert.match(page, new RegExp(`<h2 id="${id}">`));
  for (const url of ['eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0399', 'schengen-area_en', 'short-stay-calculator_en', 'entry-exit-system_en', 'apps.apple.com/app/id6761960794']) assert.ok(page.includes(url), url);
  assert.ok(!/fetch\(|XMLHttpRequest|sendBeacon/.test(page));
});
