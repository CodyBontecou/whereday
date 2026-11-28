// US naturalization (N-400) physical presence and continuous residence: pure counting logic shared by the page and its tests.
// Sources: USCIS Policy Manual Vol. 12, Part D, Ch. 3 (continuous residence), Ch. 4 (physical presence), Ch. 6 (early filing);
// INA 316(a), 319(a), 334; 8 CFR 316.2, 319.1, 334.2. Nothing here reads the clock, the network or the page.
import { parseDay, formatDay } from './country-days-core.mjs';

export { parseDay, formatDay };

export const MS_PER_DAY = 86400000;
export const MAX_BYTES = 1024 * 1024;
export const MAX_TRIPS = 2000;
export const EARLY_DAYS = 90;
export const PATHS = Object.freeze({
  general: Object.freeze({ id: 'general', label: 'General path, 5 years (INA 316(a))', years: 5, requiredMonths: 30, requiredDays: 913 }),
  spouse: Object.freeze({ id: 'spouse', label: 'Spouse of a US citizen, 3 years (INA 319(a))', years: 3, requiredMonths: 18, requiredDays: null }),
});

const utc = day => new Date(day * MS_PER_DAY);
const fromParts = (y, m, d) => Date.UTC(y, m, d) / MS_PER_DAY;
const daysInMonth = (y, m) => new Date(Date.UTC(y, m + 1, 0)).getUTCDate();

/** Add calendar months; a day past the end of the target month moves to that month's last day (31 Aug + 6 months = 28/29 Feb). */
export function addMonths(day, months) {
  const d = utc(day);
  const total = d.getUTCFullYear() * 12 + d.getUTCMonth() + months;
  const y = Math.floor(total / 12), m = ((total % 12) + 12) % 12;
  return fromParts(y, m, Math.min(d.getUTCDate(), daysInMonth(y, m)));
}
export const addYears = (day, years) => addMonths(day, years * 12);

/** The statutory window: the `years` years ending on the filing date, both ends inclusive. */
export function windowFor(filing, years) {
  return { first: addYears(filing, -years) + 1, last: filing };
}

export function requiredDays(path, filing) {
  const p = PATHS[path];
  return p.requiredDays ?? filing - addMonths(filing, -p.requiredMonths);
}

/** Earliest filing date: the date the residence period is reached, minus 90 days. */
export function earliestFiling(since, path) {
  const reached = addYears(since, PATHS[path].years);
  return { reached, earliest: reached - EARLY_DAYS };
}

export function makeTrip(depText, retText, note = '') {
  const dep = parseDay(depText), ret = parseDay(retText);
  if (dep === null) return { error: `Departure date “${String(depText).trim()}” is not a valid YYYY-MM-DD date.` };
  if (ret === null) return { error: `Return date “${String(retText).trim()}” is not a valid YYYY-MM-DD date.` };
  if (ret < dep) return { error: 'The return date is before the departure date.' };
  return { trip: { dep, ret, note: String(note ?? '').trim().slice(0, 200) } };
}

function splitCSVLine(line) {
  const out = []; let cur = '', quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) { if (c === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else quoted = false; } else cur += c; }
    else if (c === '"') quoted = true;
    else if (c === ',') { out.push(cur); cur = ''; }
    else cur += c;
  }
  out.push(cur);
  return out;
}

/** Parse columns departure_date,return_date and optional note. Bad rows are listed with their line numbers. */
export function parseTripsCSV(text) {
  const lines = String(text).replace(/^﻿/, '').split(/\r\n|\n|\r/);
  const header = splitCSVLine(lines[0] ?? '').map(h => h.trim().toLowerCase());
  const di = header.indexOf('departure_date'), ri = header.indexOf('return_date'), ni = header.indexOf('note');
  if (di < 0 || ri < 0) throw new Error('The first line must name the columns departure_date and return_date.');
  const trips = [], rejected = [];
  for (let i = 1; i < lines.length; i++) {
    if (!lines[i].trim()) continue;
    if (trips.length + rejected.length >= MAX_TRIPS) { rejected.push({ row: i + 1, reason: `More than ${MAX_TRIPS} rows; the rest were not read.`, text: '' }); break; }
    const cells = splitCSVLine(lines[i]);
    const r = makeTrip(cells[di] ?? '', cells[ri] ?? '', ni >= 0 ? cells[ni] : '');
    if (r.error) rejected.push({ row: i + 1, reason: r.error, text: lines[i].slice(0, 120) }); else trips.push(r.trip);
  }
  return { trips, rejected };
}

/** Overlap problems between trips (touching is fine: return on the day of the next departure). */
export function checkOverlaps(trips) {
  const sorted = [...trips].sort((a, b) => a.dep - b.dep || a.ret - b.ret);
  const problems = [];
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i].dep < sorted[i - 1].ret) problems.push(`${formatDay(sorted[i - 1].dep)} to ${formatDay(sorted[i - 1].ret)} overlaps ${formatDay(sorted[i].dep)} to ${formatDay(sorted[i].ret)}.`);
  }
  return problems;
}

/**
 * Continuous residence status for one absence, from its unclipped dates.
 * Length is the number of full days away (travel dates not counted). 6 months and 1 year are calendar periods from
 * the departure date, as in Ch. 3's example (depart 1 Jan 2010, return 2 Jan 2011 = exactly 1 year).
 */
export function absenceStatus(trip) {
  const fullDays = Math.max(0, trip.ret - trip.dep - 1);
  const exactlySix = addMonths(trip.dep, 6) + 1, exactlyYear = addYears(trip.dep, 1) + 1;
  if (trip.ret >= exactlyYear) return { fullDays, status: 'break' };
  if (trip.ret > exactlySix) return { fullDays, status: 'presumed' };
  return { fullDays, status: 'ok' };
}

export const STATUS_TEXT = Object.freeze({
  ok: 'No flag',
  presumed: 'Presumed break, can be rebutted with evidence',
  break: 'Breaks continuous residence (unless an approved N-470)',
});

/**
 * Run every check. `trips` must already be valid (see checkOverlaps). Trips wholly outside the window are listed, not counted.
 */
export function naturalization({ path, since, filing, trips }) {
  const p = PATHS[path];
  const win = windowFor(filing, p.years);
  const windowDays = win.last - win.first + 1;
  const rows = [];
  let abroad = 0;
  for (const t of [...trips].sort((a, b) => a.dep - b.dep || a.ret - b.ret)) {
    const st = absenceStatus(t);
    const outside = t.ret < win.first || t.dep > win.last;
    // Full days away are the dates strictly between departure and return.
    const lo = Math.max(t.dep + 1, win.first), hi = Math.min(t.ret - 1, win.last);
    const inWindow = outside ? 0 : Math.max(0, hi - lo + 1);
    const clipped = !outside && (t.dep < win.first || t.ret > win.last);
    abroad += inWindow;
    const row = { ...t, ...st, outside, clipped, abroadInWindow: inWindow };
    if (st.status === 'break' && path === 'general') {
      row.wait = { fourYearsOneDay: addYears(t.ret, 4) + 1, noPresumption: addMonths(t.ret, 54) };
    }
    rows.push(row);
  }
  const present = windowDays - abroad;
  const need = requiredDays(path, filing);
  const { reached, earliest } = earliestFiling(since, path);
  const counted = rows.filter(r => !r.outside);
  return {
    path, years: p.years, window: win, windowDays, abroad, present, required: need, requiredMonths: p.requiredMonths,
    diff: present - need, enough: present >= need,
    rows, ignored: rows.filter(r => r.outside).length, clipped: rows.filter(r => r.clipped).length,
    presumed: counted.filter(r => r.status === 'presumed').length, breaks: counted.filter(r => r.status === 'break').length,
    reached, earliest, filingBeforeEarliest: filing < earliest, filingBeforeReached: filing < reached,
  };
}

function csvField(v) {
  const s = String(v ?? '');
  const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}
const csv = rows => rows.map(r => r.map(csvField).join(',')).join('\r\n') + '\r\n';

export function tripsCSV(r) {
  return csv([
    ['departure_date', 'return_date', 'full_days_away', 'days_away_in_window', 'in_window', 'clipped', 'continuous_residence', 'note'],
    ...r.rows.map(t => [formatDay(t.dep), formatDay(t.ret), t.fullDays, t.abroadInWindow, t.outside ? 'no' : 'yes', t.clipped ? 'yes' : 'no', STATUS_TEXT[t.status], t.note]),
  ]);
}

export function summaryCSV(r, since, filing) {
  const rows = [
    ['item', 'value'],
    ['path', PATHS[r.path].label], ['resident_since', formatDay(since)], ['filing_date', formatDay(filing)],
    ['window_first_day', formatDay(r.window.first)], ['window_last_day', formatDay(r.window.last)],
    ['days_in_window', r.windowDays], ['days_abroad_in_window', r.abroad], ['days_present', r.present],
    ['days_required', r.required], ['surplus_or_shortfall', r.diff],
    ['absences_presumed_break', r.presumed], ['absences_break', r.breaks],
    ['residence_period_reached', formatDay(r.reached)], ['earliest_filing_date', formatDay(r.earliest)],
  ];
  return csv(rows);
}

// Invented data. Not a real person's trips.
export const SAMPLE_TRIPS = Object.freeze([
  ['2022-07-01', '2022-07-15', 'Invented: two-week holiday'],
  ['2023-05-20', '2023-12-30', 'Invented: seven months abroad caring for a relative'],
  ['2024-08-03', '2024-08-24', 'Invented: three weeks'],
  ['2025-12-20', '2026-01-04', 'Invented: winter break'],
]);
export const SAMPLE_CSV = csv([['departure_date', 'return_date', 'note'], ...SAMPLE_TRIPS]);
export const SAMPLE_INPUT = Object.freeze({ path: 'general', since: '2021-02-15', filing: '2026-06-01' });
