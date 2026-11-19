// Days-in-country calculator: pure counting logic shared by the page and its tests.
// Dates are whole local calendar days, held as integers (days since 1970-01-01).
// Nothing here reads the clock, the network or the page.

export const MAX_BYTES = 10 * 1024 * 1024;
export const MAX_ROWS = 200000;
export const MAX_STAY_DAYS = 3660;
export const MIN_YEAR = 1900;
export const MAX_YEAR = 2100;

// Schengen area membership, checked against the European Commission's
// "Schengen Area" page on the date below. 29 states: 25 EU members plus
// Iceland, Liechtenstein, Norway and Switzerland. Bulgaria and Romania have
// been full members since 1 January 2025. Cyprus and Ireland are not members.
// The list applies to every date; join dates are not modelled.
export const SCHENGEN = Object.freeze({
  checked: '2026-11-18',
  source: 'https://home-affairs.ec.europa.eu/policies/schengen/schengen-area_en',
  limit: 90,
  window: 180,
  members: Object.freeze(['AT', 'BE', 'BG', 'CH', 'CZ', 'DE', 'DK', 'EE', 'ES', 'FI', 'FR', 'GR', 'HR', 'HU', 'IS', 'IT', 'LI', 'LT', 'LU', 'LV', 'MT', 'NL', 'NO', 'PL', 'PT', 'RO', 'SE', 'SI', 'SK']),
});

// ISO 3166-1 alpha-2 officially assigned codes, plus XK (Kosovo), which
// Apple's geocoder can return and Whereday can therefore export.
const ISO_CODES = new Set(('AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ ' +
  'CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR ' +
  'GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP ' +
  'KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT ' +
  'MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW ' +
  'SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG ' +
  'UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW XK').split(' '));

export const COUNTRY_CODES = Object.freeze([...ISO_CODES].sort());
export const isCountryCode = code => ISO_CODES.has(code);
export const isSchengen = code => SCHENGEN.members.includes(code);

const MS_PER_DAY = 86400000;

/** Parse a strict YYYY-MM-DD calendar date. Returns a day number or null. */
export function parseDay(text) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(text ?? '').trim());
  if (!match) return null;
  const [year, month, day] = match.slice(1).map(Number);
  if (year < MIN_YEAR || year > MAX_YEAR) return null;
  const time = Date.UTC(year, month - 1, day);
  const date = new Date(time);
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return time / MS_PER_DAY;
}

/** Format a day number as YYYY-MM-DD. */
export function formatDay(day) {
  return new Date(day * MS_PER_DAY).toISOString().slice(0, 10);
}

export const yearOf = day => new Date(day * MS_PER_DAY).getUTCFullYear();

/** The local calendar date of a JavaScript Date, as a day number. */
export function localDay(date) {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / MS_PER_DAY;
}

function splitCSVLine(line) {
  const fields = [];
  let field = '', quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quoted) {
      if (c === '"' && line[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') quoted = false;
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ',') { fields.push(field); field = ''; }
    else field += c;
  }
  fields.push(field);
  return fields;
}

/**
 * Read a country-days CSV. Required columns: country_code and local_date
 * (any order, any letter case). Optional: source. Every data row is either
 * accepted or listed in `rejected` with its line number; none is dropped silently.
 */
export function parseCountryDaysCSV(text) {
  if (typeof text !== 'string') throw new Error('The file could not be read as text.');
  if (text.length > MAX_BYTES) throw new Error('The file is larger than 10 MiB.');
  const lines = text.replace(/^﻿/, '').split(/\r\n|\n|\r/);
  while (lines.length && lines.at(-1).trim() === '') lines.pop();
  if (!lines.length) throw new Error('The file is empty.');
  const header = splitCSVLine(lines[0]).map(name => name.trim().toLowerCase());
  const countryColumn = header.indexOf('country_code');
  const dateColumn = header.indexOf('local_date');
  const sourceColumn = header.indexOf('source');
  if (countryColumn < 0 || dateColumn < 0) {
    throw new Error('The first row must name the columns, including country_code and local_date.');
  }
  if (lines.length - 1 > MAX_ROWS) throw new Error('The file has more than 200,000 rows.');
  const records = [], rejected = [];
  let blank = 0;
  for (let index = 1; index < lines.length; index++) {
    const row = index + 1;
    const line = lines[index];
    if (line.trim() === '') { blank++; rejected.push({ row, reason: 'empty row', text: '' }); continue; }
    const fields = splitCSVLine(line);
    const code = (fields[countryColumn] ?? '').trim().toUpperCase();
    const dateText = (fields[dateColumn] ?? '').trim();
    const source = sourceColumn >= 0 ? (fields[sourceColumn] ?? '').trim() : '';
    const reasons = [];
    if (!code) reasons.push('missing country_code');
    else if (!isCountryCode(code)) reasons.push(`“${code.slice(0, 12)}” is not an ISO 3166-1 alpha-2 country code`);
    if (!dateText) reasons.push('missing local_date');
    else if (parseDay(dateText) === null) reasons.push(`“${dateText.slice(0, 20)}” is not a valid YYYY-MM-DD date between ${MIN_YEAR} and ${MAX_YEAR}`);
    if (reasons.length) { rejected.push({ row, reason: reasons.join('; '), text: line.slice(0, 120) }); continue; }
    records.push({ country: code, day: parseDay(dateText), source, row });
  }
  return { records, rejected, hasSource: sourceColumn >= 0, rows: lines.length - 1, blank };
}

/** Validate a manual stay. Returns { stay } or { error }. Dates are inclusive. */
export function makeStay(country, firstText, lastText) {
  const code = String(country ?? '').trim().toUpperCase();
  if (!isCountryCode(code)) return { error: 'Choose a country.' };
  const first = parseDay(firstText), last = parseDay(lastText);
  if (first === null) return { error: 'Enter a valid first date.' };
  if (last === null) return { error: 'Enter a valid last date.' };
  if (last < first) return { error: 'The last date must be on or after the first date.' };
  if (last - first + 1 > MAX_STAY_DAYS) return { error: 'A single stay can cover at most 3,660 days.' };
  return { stay: { country: code, first, last } };
}

/**
 * Combine CSV records and manual stays into country → date → sources.
 * The same country and date counts once, however many inputs mention it.
 */
export function buildIndex(records = [], stays = []) {
  const index = new Map();
  const add = (country, day, label) => {
    if (!index.has(country)) index.set(country, new Map());
    const days = index.get(country);
    if (!days.has(day)) days.set(day, new Set());
    days.get(day).add(label);
  };
  for (const record of records) add(record.country, record.day, record.source ? `file: ${record.source}` : 'file');
  for (const stay of stays) for (let day = stay.first; day <= stay.last; day++) add(stay.country, day, 'manual stay');
  return index;
}

const inRange = (day, first, last) => (first === null || day >= first) && (last === null || day <= last);

/** Sorted day numbers for one country, or the union for a group of countries. */
export function daysFor(index, countries) {
  const set = new Set();
  for (const country of countries) for (const day of index.get(country)?.keys() ?? []) set.add(day);
  return [...set].sort((a, b) => a - b);
}

/** Per-country distinct dates in an inclusive range (null = open), most days first. */
export function countryTotals(index, first = null, last = null) {
  const rows = [];
  for (const [country, days] of index) {
    const list = [...days.keys()].filter(day => inRange(day, first, last)).sort((a, b) => a - b);
    if (list.length) rows.push({ country, count: list.length, days: list, sources: list.map(day => [...days.get(day)].sort()) });
  }
  return rows.sort((a, b) => b.count - a.count || a.country.localeCompare(b.country));
}

/** Distinct dates across the whole index in a range, and the total of country-dates. */
export function summary(index, first = null, last = null) {
  const all = new Set();
  let countryDates = 0;
  for (const days of index.values()) for (const day of days.keys()) if (inRange(day, first, last)) { all.add(day); countryDates++; }
  return { distinctDates: all.size, countryDates, countries: countryTotals(index, first, last).length };
}

/** Count of sorted days that fall in [from, to]. */
function countBetween(sorted, from, to) {
  let lo = 0, hi = sorted.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (sorted[mid] < from) lo = mid + 1; else hi = mid; }
  let start = lo; hi = sorted.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (sorted[mid] <= to) lo = mid + 1; else hi = mid; }
  return lo - start;
}

/**
 * Largest number of dates in any `length`-day window (inclusive of its end date)
 * whose end date lies in [rangeFirst, rangeLast]. A window ending on E covers
 * E-length+1 … E. The reported end is the range start or a recorded date: the
 * earliest such candidate that reaches the maximum. An earlier, unrecorded end
 * date can hold the same count; the count itself is always the maximum.
 */
export function maxWindow(sortedDays, length, rangeFirst, rangeLast) {
  if (!(length >= 1) || rangeFirst === null || rangeLast === null || rangeLast < rangeFirst) return { max: 0, end: null };
  // The maximum is always reached at the range start or on a recorded date inside the range.
  const candidates = [rangeFirst, ...sortedDays.filter(day => day > rangeFirst && day <= rangeLast)];
  let best = { max: -1, end: null };
  for (const end of candidates) {
    const count = countBetween(sortedDays, end - length + 1, end);
    if (count > best.max) best = { max: count, end };
  }
  return { max: best.max, end: best.end, start: best.end - length + 1 };
}

/**
 * Schengen 90/180 check on a reference date. The 180-day window ends on and
 * includes the reference date: it covers reference-179 … reference. A date spent
 * in two member states counts once.
 * `earliestFull90` is the earliest date on or after the reference date that
 * begins a full 90-day stay, assuming no further Schengen days after the
 * reference date: it needs 90 clear days before it.
 */
export function schengenCheck(index, reference) {
  const days = daysFor(index, SCHENGEN.members);
  const windowStart = reference - SCHENGEN.window + 1;
  const used = days.filter(day => day >= windowStart && day <= reference);
  const before = days.filter(day => day <= reference);
  const latest = before.length ? before.at(-1) : null;
  const earliestFull90 = latest === null ? reference : Math.max(reference, latest + SCHENGEN.limit + 1);
  const countriesByDay = used.map(day => ({ day, countries: SCHENGEN.members.filter(code => index.get(code)?.has(day)) }));
  return {
    reference, windowStart, used: used.length, remaining: Math.max(0, SCHENGEN.limit - used.length),
    over: Math.max(0, used.length - SCHENGEN.limit), earliestFull90, laterDays: days.length - before.length, countriesByDay,
  };
}

/** Days per country in each calendar year, most recent year first. */
export function calendarYears(index, threshold = 183) {
  const byYear = new Map();
  for (const [country, days] of index) for (const day of days.keys()) {
    const year = yearOf(day);
    if (!byYear.has(year)) byYear.set(year, new Map());
    const counts = byYear.get(year);
    counts.set(country, (counts.get(country) ?? 0) + 1);
  }
  return [...byYear].sort((a, b) => b[0] - a[0]).map(([year, counts]) => ({
    year,
    countries: [...counts].map(([country, count]) => ({ country, count, atThreshold: count >= threshold }))
      .sort((a, b) => b.count - a.count || a.country.localeCompare(b.country)),
  }));
}

function csvField(value) {
  const text = String(value ?? '');
  const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text;
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}
const csvRows = rows => rows.map(row => row.map(csvField).join(',')).join('\r\n') + '\r\n';

/** CSV of per-country totals for a period. */
export function totalsCSV(index, first, last, name = code => code) {
  const rows = [['period_first', 'period_last', 'country_code', 'country_name', 'days', 'first_date', 'last_date']];
  for (const row of countryTotals(index, first, last)) {
    rows.push([first === null ? '' : formatDay(first), last === null ? '' : formatDay(last), row.country, name(row.country), row.count, formatDay(row.days[0]), formatDay(row.days.at(-1))]);
  }
  return csvRows(rows);
}

/** CSV of the Schengen window: a summary block, then one row per counted date. */
export function schengenCSV(result) {
  const rows = [
    ['reference_date', 'window_first', 'window_last', 'days_used', 'days_remaining', 'earliest_full_90_day_stay', 'member_list_checked'],
    [formatDay(result.reference), formatDay(result.windowStart), formatDay(result.reference), result.used, result.remaining, formatDay(result.earliestFull90), SCHENGEN.checked],
    [],
    ['local_date', 'schengen_countries'],
    ...result.countriesByDay.map(({ day, countries }) => [formatDay(day), countries.join(' ')]),
  ];
  return csvRows(rows);
}

// The worked example in the page's #schengen section. Invented dates.
export const SCHENGEN_EXAMPLE = Object.freeze({
  stays: Object.freeze([
    Object.freeze({ country: 'FR', first: '2026-01-05', last: '2026-03-05' }),
    Object.freeze({ country: 'IT', first: '2026-04-15', last: '2026-05-14' }),
  ]),
  references: Object.freeze(['2026-05-14', '2026-08-01']),
});
