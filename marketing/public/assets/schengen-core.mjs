// Schengen calculator: trips, 90/180 counting and the stay planner.
// The 180-day window, the 90-day limit and the member list come from
// country-days-core.mjs, so this page and the days-in-country calculator
// cannot drift apart. Dates are whole calendar days held as integers
// (days since 1970-01-01). Nothing here reads the clock, the network or the page.
import {
  SCHENGEN, MAX_STAY_DAYS, parseDay, formatDay, isSchengen, parseCountryDaysCSV, schengenWindow, csvRows,
} from './country-days-core.mjs';

export { SCHENGEN, parseDay, formatDay };

const { limit: LIMIT, window: WINDOW } = SCHENGEN;
export const MAX_PLANNED_STAY = LIMIT;
const SEARCH_DAYS = 3 * WINDOW;

/** Validate a trip. An empty exit date means the stay is ongoing. Returns { trip } or { error }. */
export function makeTrip(entryText, exitText) {
  const first = parseDay(entryText);
  if (first === null) return { error: 'Enter a valid entry date.' };
  if (String(exitText ?? '').trim() === '') return { trip: { first, last: null } };
  const last = parseDay(exitText);
  if (last === null) return { error: 'Enter a valid exit date, or leave it empty for a stay that is still going on.' };
  if (last < first) return { error: 'The exit date must be on or after the entry date.' };
  if (last - first + 1 > MAX_STAY_DAYS) return { error: 'A single trip can cover at most 3,660 days.' };
  return { trip: { first, last } };
}

/**
 * Every Schengen date, with where it came from.
 * Returns Map day → Set of labels. An ongoing trip (last = null) counts up to the
 * reference date; if it starts after the reference date it is not counted.
 * CSV records outside the Schengen member list are skipped and counted in `ignored`.
 */
export function buildDayMap({ trips = [], records = [] } = {}, reference) {
  const map = new Map();
  const add = (day, label) => { if (!map.has(day)) map.set(day, new Set()); map.get(day).add(label); };
  trips.forEach((trip, position) => {
    const last = trip.last === null ? reference : trip.last;
    for (let day = trip.first; day <= last; day++) add(day, `Trip ${position + 1}`);
  });
  let ignored = 0;
  for (const record of records) {
    if (!isSchengen(record.country)) { ignored++; continue; }
    add(record.day, record.source ? `file: ${record.source} (${record.country})` : `file (${record.country})`);
  }
  return { map, ignored };
}

/** Read a Country Days CSV for this tool: Schengen rows kept, the rest counted. */
export function readCountryDaysCSV(text) {
  const parsed = parseCountryDaysCSV(text);
  const records = parsed.records.filter(record => isSchengen(record.country));
  return { records, ignored: parsed.records.length - records.length, rejected: parsed.rejected, rows: parsed.rows };
}

export const sortedDays = map => [...map.keys()].sort((a, b) => a - b);

/** Days used on `reference`: distinct Schengen dates in the 180 days ending on it. */
export function usedOn(days, reference) {
  const { windowStart, used } = schengenWindow(days, reference);
  return { windowStart, used: used.length, remaining: Math.max(0, LIMIT - used.length), over: Math.max(0, used.length - LIMIT), dates: used };
}

/** Rows for the window table: each counted date, its sources and the first date it no longer counts. */
export function windowRows(map, reference) {
  return usedOn(sortedDays(map), reference).dates.map(day => ({
    day, sources: [...map.get(day)].sort(), fallsOut: day + WINDOW,
  }));
}

/**
 * Extend a stay one day at a time after `lastCounted` and report how far it can go.
 * Day d is allowed when the 180 days ending on d hold at most 90 distinct dates,
 * counting every existing date and the planned days up to d. Stops at the first
 * day that would make it 91. Every day of the stay is checked, not only the last.
 * `lastCounted` is the day before the first new day.
 */
export function extendStay(days, lastCounted, maxDays = SEARCH_DAYS) {
  const existing = new Set(days);
  const checks = [];
  let blocked = null;
  for (let day = lastCounted + 1; day <= lastCounted + maxDays; day++) {
    let used = 0;
    for (let d = day - WINDOW + 1; d <= day; d++) if (existing.has(d) || (d > lastCounted && d <= day)) used++;
    if (used > LIMIT) { blocked = { day, used }; break; }
    checks.push({ day, used });
  }
  return { last: checks.length ? checks.at(-1).day : lastCounted, length: checks.length, checks, blocked };
}

/** Ongoing stay: the last date you can be in the area. Needs `reference` to be a counted day. */
export function mustLeaveBy(days, reference) {
  const now = usedOn(days, reference);
  if (now.used > LIMIT) return { over: now.over, last: null, extraDays: 0 };
  const ext = extendStay(days, reference);
  return { over: 0, last: ext.last, extraDays: ext.length, blocked: ext.blocked };
}

/** Longest stay from a planned entry date (the entry day counts), up to 90 days. */
export function longestStay(days, entry) {
  const ext = extendStay(days, entry - 1, MAX_PLANNED_STAY);
  return { entry, length: ext.length, last: ext.length ? ext.last : null, checks: ext.checks, blocked: ext.blocked };
}

/** Does a stay of `length` days starting on `entry` keep every one of its days at 90 or fewer? */
export function stayFits(days, entry, length) {
  const ext = extendStay(days, entry - 1, length);
  return ext.length === length;
}

/**
 * Earliest entry date on or after `from` for a stay of `length` days where every day
 * of the stay stays within 90 days in its 180-day window. Searches 540 days ahead.
 */
export function earliestStart(days, from, length) {
  if (!Number.isInteger(length) || length < 1 || length > MAX_PLANNED_STAY) return { error: `Enter a length from 1 to ${MAX_PLANNED_STAY} days.` };
  for (let entry = from; entry < from + SEARCH_DAYS; entry++) {
    if (stayFits(days, entry, length)) return { entry, last: entry + length - 1, length };
  }
  return { entry: null, length };
}

/** CSV of the window: a summary row, then each counted date with its sources and fall-out date. */
export function windowCSV(map, reference) {
  const now = usedOn(sortedDays(map), reference);
  return csvRows([
    ['reference_date', 'window_first', 'window_last', 'days_used', 'days_remaining', 'member_list_checked'],
    [formatDay(reference), formatDay(now.windowStart), formatDay(reference), now.used, now.remaining, SCHENGEN.checked],
    [],
    ['local_date', 'sources', 'first_date_it_no_longer_counts'],
    ...windowRows(map, reference).map(row => [formatDay(row.day), row.sources.join('; '), formatDay(row.fallsOut)]),
  ]);
}

// The worked example and the page's "Try the synthetic example" button. Invented dates.
export const EXAMPLE = Object.freeze({
  trips: Object.freeze([
    Object.freeze({ first: '2026-01-05', last: '2026-02-03' }),
    Object.freeze({ first: '2026-04-01', last: '2026-04-25' }),
    Object.freeze({ first: '2026-06-20', last: null }),
  ]),
  // The third trip is still going on at the first reference date; it ended on `endedOn` at the later ones.
  endedOn: '2026-07-10',
  references: Object.freeze(['2026-07-10', '2026-08-15', '2027-01-10']),
  plannedEntry: '2026-09-01',
  plannedLength: 30,
});
