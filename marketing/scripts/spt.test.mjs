import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import {
  parseCountryDaysCSV, makeStay, buildIndex, parseDay, usDaysByYear, ignoredCounts, substantialPresence, parseCount,
  IRS_EXAMPLE, SYNTHETIC_STAYS, SYNTHETIC_YEAR,
} from '../public/assets/spt-core.mjs';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const page = await read('../content/substantial-presence-test-calculator.html');
const TODAY = parseDay('2026-11-23');
const run = (year, days, excluded = {}) => substantialPresence(year, { days, excluded }, TODAY);

test('IRS example: 120 + 40 + 20 = 180.00, not met', () => {
  const r = run(2025, IRS_EXAMPLE.typed);
  assert.equal(r.total, '180.00');
  assert.equal(r.result, 'not met');
});

test('weights are exact and fractions are not rounded up', () => {
  const r = run(2025, { 2025: 31, 2024: 100, 2023: 0 });
  assert.equal(r.rows[1].weighted, '33.33');
  assert.equal(r.total, '64.33');
  // 182.83 days must not pass.
  const close = run(2025, { 2025: 150, 2024: 98, 2023: 0 });
  assert.equal(close.total, '182.67');
  assert.equal(close.met, false);
  assert.equal(run(2025, { 2025: 150, 2024: 99, 2023: 0 }).met, true);
});

test('under 31 days is never met, whatever the total', () => {
  const r = run(2025, { 2025: 30, 2024: 365, 2023: 365 });
  assert.equal(r.total, '212.50');
  assert.equal(r.met, false);
  assert.equal(r.result, 'not met');
});

test('synthetic stays give 205.00 and met, and ignore non-US and territories', () => {
  const index = buildIndex([], SYNTHETIC_STAYS.map(s => makeStay(s.country, s.first, s.last).stay));
  const byYear = usDaysByYear(index);
  const days = Object.fromEntries([...byYear].map(([y, l]) => [y, l.length]));
  assert.deepEqual(days, { 2024: 90, 2025: 120, 2026: 150 });
  const r = run(SYNTHETIC_YEAR, days);
  assert.equal(r.total, '205.00');
  assert.equal(r.result, 'met');
  assert.deepEqual(ignoredCounts(index), { territories: 10, other: 5 });
});

test('excluded days are subtracted and never go below zero', () => {
  const r = run(2025, { 2025: 100, 2024: 50, 2023: 10 }, { 2025: 40, 2024: 80 });
  assert.equal(r.rows[0].counted, 60);
  assert.equal(r.rows[1].counted, 0);
  assert.equal(r.total, (60 + 10 / 6).toFixed(2));
});

test('year in progress: not met yet, with days still needed', () => {
  const r = run(2026, { 2026: 100, 2025: 120, 2024: 90 });
  assert.equal(r.result, 'not met yet');
  assert.equal(r.total, (100 + 40 + 15).toFixed(2));
  assert.equal(r.moreDays, 28);
  const after = run(2026, { 2026: 100 + r.moreDays, 2025: 120, 2024: 90 });
  assert.equal(after.met, true);
  assert.equal(run(2026, { 2026: 100 + r.moreDays - 1, 2025: 120, 2024: 90 }).met, false);
  assert.equal(run(2026, { 2026: 5 }).moreDays, 178);
  assert.equal(run(2026, { 2026: 5, 2025: 365, 2024: 365 }).moreDays, 26);
});

test('CSV: only US counts, duplicates once, bad rows listed with line numbers', () => {
  const csv = 'country_code,local_date\nUS,2026-01-01\nus,2026-01-01\nUS,2026-01-02\nPR,2026-01-03\nFR,2026-01-04\nXX,2026-01-05\nUS,2026-02-30\n';
  const parsed = parseCountryDaysCSV(csv);
  assert.deepEqual(parsed.rejected.map(r => r.row), [7, 8]);
  const index = buildIndex(parsed.records);
  assert.equal(usDaysByYear(index).get(2026).length, 2);
  assert.deepEqual(ignoredCounts(index), { territories: 1, other: 1 });
});

test('typed counts are validated', () => {
  assert.deepEqual(parseCount('', 2026), { value: undefined });
  assert.equal(parseCount('120', 2026).value, 120);
  assert.ok(parseCount('12.5', 2026).error);
  assert.ok(parseCount('367', 2024).error);
  assert.ok(parseCount('366', 2025).error);
});

test('the worked-examples table matches the calculator', () => {
  const rows = [...page.matchAll(/<tr data-year="(\d+)" data-days="([\d,]+)" data-total="([\d.]+)" data-result="([^"]+)">/g)];
  assert.equal(rows.length, 3);
  for (const [, year, days, total, result] of rows) {
    const [a, b, c] = days.split(',').map(Number);
    const r = run(Number(year), { [year]: a, [year - 1]: b, [year - 2]: c });
    assert.equal(r.total, total);
    assert.equal(r.result, result);
  }
});

test('page states the limits above the tool, cites sources, and loads no remote script', () => {
  assert.ok(page.indexOf('id="limits-summary"') < page.indexOf('id="spt-file"'));
  for (const id of ['calculator', 'how-the-test-works', 'worked-examples', 'days-not-counted', 'meeting-the-test-is-not-the-end', 'where-your-dates-come-from', 'faq']) assert.ok(page.includes(`id="${id}"`), id);
  assert.ok(!/<script[^>]+src="https?:/.test(page));
  assert.ok(page.includes('not tax or immigration advice'));
});

test('the generated page excludes analytics', async () => {
  const out = await read('../public/tools/substantial-presence-test-calculator/index.html');
  assert.ok(!out.includes('acquisition.js') && !out.includes('cloudflareinsights'));
  assert.ok(out.includes("connect-src 'none'"));
});
