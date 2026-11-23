// US substantial presence test: pure counting logic shared by the page and its tests.
// Source: IRS, Substantial Presence Test (irs.gov/individuals/international-taxpayers/substantial-presence-test).
// Nothing here reads the clock, the network or the page.
import { parseCountryDaysCSV, makeStay, buildIndex, parseDay, formatDay, yearOf } from './country-days-core.mjs';

export { parseCountryDaysCSV, makeStay, buildIndex, parseDay, formatDay, yearOf };

export const US = 'US';
export const US_TERRITORIES = Object.freeze(['PR', 'GU', 'VI', 'AS', 'MP']);
export const MIN_DAYS_CURRENT = 31;
export const THRESHOLD = 183;
// Weighted days are held in sixths so 1/3 and 1/6 stay exact: 6 = current year, 2 = first prior year, 1 = second prior year.
export const WEIGHTS = Object.freeze({ current: 6, prior1: 2, prior2: 1 });
const THRESHOLD_SIXTHS = THRESHOLD * 6;

export const daysInYear = year => (year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 366 : 365);

/** Distinct US dates per year: Map year → sorted day numbers. Non-US codes are ignored. */
export function usDaysByYear(index) {
  const years = new Map();
  for (const day of index.get(US)?.keys() ?? []) {
    const year = yearOf(day);
    if (!years.has(year)) years.set(year, []);
    years.get(year).push(day);
  }
  for (const list of years.values()) list.sort((a, b) => a - b);
  return years;
}

/** Distinct country-dates ignored because they are not in the United States. */
export function ignoredCounts(index) {
  let territories = 0, other = 0;
  for (const [country, days] of index) {
    if (country === US) continue;
    if (US_TERRITORIES.includes(country)) territories += days.size; else other += days.size;
  }
  return { territories, other };
}

const format2 = sixths => (sixths / 6).toFixed(2);

/**
 * Apply the test for `year`.
 * input.days[y]: days of presence for y (already decided: dates or typed total).
 * input.excluded[y]: days the IRS does not count; subtracted, never below zero.
 * today: day number; the year is "in progress" when it is the current year or later.
 */
export function substantialPresence(year, input, today) {
  const rows = [[year, 'current'], [year - 1, 'prior1'], [year - 2, 'prior2']].map(([y, key]) => {
    const present = input.days?.[y] ?? 0;
    const excluded = input.excluded?.[y] ?? 0;
    const counted = Math.max(0, present - excluded);
    const sixths = counted * WEIGHTS[key];
    return { year: y, key, present, excluded, counted, weight: ['1', '1/3', '1/6'][['current', 'prior1', 'prior2'].indexOf(key)], sixths, weighted: format2(sixths) };
  });
  const totalSixths = rows.reduce((sum, row) => sum + row.sixths, 0);
  const currentDays = rows[0].counted;
  const enoughCurrent = currentDays >= MIN_DAYS_CURRENT;
  const enoughTotal = totalSixths >= THRESHOLD_SIXTHS;
  const met = enoughCurrent && enoughTotal;
  const inProgress = !!(today !== undefined && today !== null && year >= yearOf(today));
  let result = met ? 'met' : (inProgress ? 'not met yet' : 'not met');
  let moreDays = 0;
  if (!met && inProgress) {
    const deficit = THRESHOLD_SIXTHS - totalSixths;
    moreDays = Math.max(0, MIN_DAYS_CURRENT - currentDays, deficit > 0 ? Math.ceil(deficit / 6) : 0);
  }
  let daysLeftInYear = null;
  if (inProgress) daysLeftInYear = year > yearOf(today) ? daysInYear(year) : Math.max(0, parseDay(`${year}-12-31`) - today);
  return { year, rows, total: format2(totalSixths), totalSixths, enoughCurrent, enoughTotal, met, inProgress, result, moreDays, daysLeftInYear };
}

/** Parse an optional whole-number field. Returns { value } (undefined when blank) or { error }. */
export function parseCount(text, year) {
  const trimmed = String(text ?? '').trim();
  if (trimmed === '') return { value: undefined };
  if (!/^\d{1,3}$/.test(trimmed)) return { error: 'Enter a whole number of days.' };
  const value = Number(trimmed);
  if (value > daysInYear(year)) return { error: `${year} has only ${daysInYear(year)} days.` };
  return { value };
}

// Invented examples. Each is mirrored in the worked-examples table on the page.
export const IRS_EXAMPLE = Object.freeze({ year: 2025, typed: Object.freeze({ 2023: 120, 2024: 120, 2025: 120 }) });
export const SYNTHETIC_STAYS = Object.freeze([
  { country: 'US', first: '2024-01-01', last: '2024-03-30' },
  { country: 'US', first: '2025-01-01', last: '2025-04-30' },
  { country: 'US', first: '2026-01-01', last: '2026-05-30' },
  { country: 'PR', first: '2026-07-01', last: '2026-07-10' },
  { country: 'CA', first: '2026-08-01', last: '2026-08-05' },
]);
export const SYNTHETIC_YEAR = 2026;
