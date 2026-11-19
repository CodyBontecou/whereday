import {
  COUNTRY_CODES, SCHENGEN, SCHENGEN_EXAMPLE, MAX_BYTES, parseCountryDaysCSV, makeStay, buildIndex, countryTotals,
  summary, daysFor, maxWindow, schengenCheck, calendarYears, totalsCSV, schengenCSV, parseDay, formatDay, localDay, yearOf, isSchengen,
} from './country-days-core.mjs';
import { DEMO_COUNTRY_DAYS } from './country-days-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const plural = (n, word, many = /[^aeiou]y$/.test(word) ? word.slice(0, -1) + 'ies' : word + 's') => `${number.format(n)} ${n === 1 ? word : many}`;
let regionNames = null;
try { regionNames = new Intl.DisplayNames(['en'], { type: 'region', fallback: 'code' }); } catch { regionNames = null; }
const countryName = code => (code === 'XK' ? 'Kosovo' : regionNames?.of(code)) || code;
const label = code => `${countryName(code)} (${code})`;

const state = { file: null, fileName: '', stays: [], editing: -1, nextId: 1, downloadURL: null };
const today = () => localDay(new Date());

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function cell(row, text, header = false) {
  const element = node(header ? 'th' : 'td', text);
  if (header) element.scope = 'row';
  row.append(element);
  return element;
}

function setStatus(text) { $('cdc-status').textContent = text; }

function fillCountrySelect() {
  const select = $('cdc-country');
  const options = COUNTRY_CODES.map(code => ({ code, name: countryName(code) })).sort((a, b) => a.name.localeCompare(b.name, 'en'));
  select.replaceChildren(new Option('Choose a country', ''), ...options.map(({ code, name }) => new Option(`${name} (${code})`, code)));
}

function index() { return buildIndex(state.file?.records ?? [], state.stays); }

function allBounds(idx) {
  let first = null, last = null;
  for (const days of idx.values()) for (const day of days.keys()) {
    if (first === null || day < first) first = day;
    if (last === null || day > last) last = day;
  }
  return { first, last };
}

function selectedPeriod(idx) {
  const value = $('cdc-period').value;
  const bounds = allBounds(idx);
  if (value === 'rolling') { const end = today(); return { first: end - 364, last: end, text: `the last 365 days (${formatDay(end - 364)} to ${formatDay(end)})` }; }
  if (value === 'custom') {
    const first = parseDay($('cdc-from').value), last = parseDay($('cdc-to').value);
    if (first === null || last === null || last < first) return { first: null, last: null, invalid: true, text: 'a custom range' };
    return { first, last, text: `${formatDay(first)} to ${formatDay(last)}` };
  }
  if (/^\d{4}$/.test(value)) {
    const year = Number(value);
    return { first: parseDay(`${value}-01-01`), last: parseDay(`${value}-12-31`), text: `calendar year ${year}` };
  }
  return { first: bounds.first, last: bounds.last, text: bounds.first === null ? 'your dates' : `your dates (${formatDay(bounds.first)} to ${formatDay(bounds.last)})` };
}

function refreshPeriodOptions(idx) {
  const select = $('cdc-period');
  const current = select.value;
  const years = new Set([yearOf(today())]);
  for (const days of idx.values()) for (const day of days.keys()) years.add(yearOf(day));
  const options = [new Option('All dates', 'all'), ...[...years].sort((a, b) => b - a).map(year => new Option(`Calendar year ${year}`, String(year))),
    new Option('Last 365 days', 'rolling'), new Option('Custom range', 'custom')];
  select.replaceChildren(...options);
  select.value = [...select.options].some(option => option.value === current) ? current : 'all';
  $('cdc-custom').hidden = select.value !== 'custom';
}

function refreshGroupOptions(idx) {
  const select = $('cdc-group');
  const current = select.value;
  const countries = [...idx.keys()].sort((a, b) => countryName(a).localeCompare(countryName(b), 'en'));
  select.replaceChildren(new Option('Schengen area, combined', 'schengen'), ...countries.map(code => new Option(label(code), code)));
  select.value = [...select.options].some(option => option.value === current) ? current : (countries.length === 1 ? countries[0] : 'schengen');
}

function renderRejected() {
  const rejected = state.file?.rejected ?? [];
  $('cdc-rejected').hidden = !rejected.length;
  const body = $('cdc-rejected-rows');
  body.replaceChildren();
  $('cdc-rejected-note').textContent = rejected.length
    ? `${plural(rejected.length, 'row')} in ${state.fileName} could not be counted. Fix them in the file and load it again; they are not included anywhere below.`
    : '';
  for (const item of rejected.slice(0, 500)) {
    const row = document.createElement('tr');
    cell(row, String(item.row), true); cell(row, item.reason); cell(row, item.text);
    body.append(row);
  }
  if (rejected.length > 500) $('cdc-rejected-note').textContent += ` The first 500 are listed.`;
}

function renderStays() {
  const list = $('cdc-stays');
  list.replaceChildren();
  $('cdc-stays-empty').hidden = state.stays.length > 0;
  state.stays.forEach((stay, position) => {
    const item = node('li');
    const days = stay.last - stay.first + 1;
    item.append(node('span', `${label(stay.country)}: ${formatDay(stay.first)} to ${formatDay(stay.last)} · ${plural(days, 'day')}`));
    const edit = node('button', 'Edit'); edit.type = 'button';
    edit.setAttribute('aria-label', `Edit stay in ${countryName(stay.country)} from ${formatDay(stay.first)}`);
    edit.addEventListener('click', () => startEdit(position));
    const remove = node('button', 'Delete'); remove.type = 'button';
    remove.setAttribute('aria-label', `Delete stay in ${countryName(stay.country)} from ${formatDay(stay.first)}`);
    remove.addEventListener('click', () => {
      state.stays.splice(position, 1);
      if (state.editing === position) cancelEdit();
      else if (state.editing > position) state.editing--;
      setStatus('Stay deleted.');
      render();
    });
    item.append(' ', edit, ' ', remove);
    list.append(item);
  });
}

function renderTotals(idx, period) {
  const body = $('cdc-total-rows');
  body.replaceChildren();
  if (period.invalid) { $('cdc-total-note').textContent = 'Enter a first and last date for the custom range, with the last on or after the first.'; return; }
  const rows = countryTotals(idx, period.first, period.last);
  const totals = summary(idx, period.first, period.last);
  $('cdc-total-note').textContent = rows.length
    ? `${plural(totals.countryDates, 'country-date')} across ${plural(totals.distinctDates, 'distinct date')} in ${period.text}. A travel day can count in two countries, so the country totals can add up to more than the number of dates.`
    : `No dates in ${period.text}.`;
  for (const item of rows) {
    const row = document.createElement('tr');
    cell(row, label(item.country), true);
    cell(row, number.format(item.count));
    cell(row, `${formatDay(item.days[0])} to ${formatDay(item.days.at(-1))}`);
    const details = node('details');
    details.append(node('summary', `Show ${plural(item.count, 'date')}`));
    const list = node('ul', undefined, 'cdc-dates'); list.tabIndex = 0; list.setAttribute('aria-label', `Dates counted for ${countryName(item.country)}`);
    item.days.forEach((day, i) => list.append(node('li', `${formatDay(day)} · ${item.sources[i].join(', ')}`)));
    details.append(list);
    cell(row, '').append(details);
    body.append(row);
  }
}

function renderWindow(idx, period) {
  const output = $('cdc-window-result');
  const length = Number($('cdc-length').value), threshold = Number($('cdc-threshold').value);
  if (!Number.isInteger(length) || length < 1 || length > 3660) { output.textContent = 'Enter a window length from 1 to 3,660 days.'; return; }
  if (!Number.isInteger(threshold) || threshold < 1) { output.textContent = 'Enter a threshold of at least 1 day.'; return; }
  if (period.invalid || period.first === null) { output.textContent = 'Choose a period with dates to check.'; return; }
  const group = $('cdc-group').value;
  const days = group === 'schengen' ? daysFor(idx, SCHENGEN.members) : daysFor(idx, [group]);
  const name = group === 'schengen' ? 'the Schengen area (a date in two member states counts once)' : countryName(group);
  const result = maxWindow(days, length, period.first, period.last);
  const reached = result.max >= threshold;
  output.replaceChildren(
    node('strong', `${plural(result.max, 'day')} in ${name}`),
    ` is the most in any ${number.format(length)}-day window ending within ${period.text}. `,
    `That window runs ${formatDay(result.start)} to ${formatDay(result.end)}. `,
    node('span', reached ? `This reaches your threshold of ${plural(threshold, 'day')}.` : `This is below your threshold of ${plural(threshold, 'day')} by ${plural(threshold - result.max, 'day')}.`, reached ? 'cdc-flag' : ''),
  );
}

function renderSchengen(idx) {
  const reference = parseDay($('cdc-reference').value);
  const output = $('cdc-schengen-result');
  const body = $('cdc-schengen-rows');
  body.replaceChildren();
  if (reference === null) { output.textContent = 'Enter a reference date.'; $('cdc-download-schengen').disabled = true; return null; }
  const result = schengenCheck(idx, reference);
  output.replaceChildren();
  const list = node('dl', undefined, 'cdc-figures');
  const figure = (term, value) => { const pair = node('div'); pair.append(node('dt', term), node('dd', value)); list.append(pair); };
  figure('Days used', `${result.used} of 90`);
  figure('Days remaining', result.over ? `0 (${plural(result.over, 'day')} over 90)` : String(result.remaining));
  figure('180-day window', `${formatDay(result.windowStart)} to ${formatDay(result.reference)}`);
  figure('Earliest start for a full 90 days', formatDay(result.earliestFull90));
  output.append(list);
  const notes = [`The earliest full-90 date assumes no Schengen days after ${formatDay(reference)}: a full 90-day stay needs 90 days with no Schengen presence before it.`];
  if (result.laterDays) notes.push(`${plural(result.laterDays, 'Schengen date')} after the reference date ${result.laterDays === 1 ? 'is' : 'are'} not used for this check.`);
  output.append(node('p', notes.join(' '), 'gpx-small'));
  for (const { day, countries } of result.countriesByDay) {
    const row = document.createElement('tr');
    cell(row, formatDay(day), true); cell(row, countries.map(label).join(', '));
    body.append(row);
  }
  $('cdc-schengen-detail-note').textContent = result.used ? `${plural(result.used, 'date')} counted in this window.` : 'No Schengen dates in this window.';
  $('cdc-download-schengen').disabled = false;
  return result;
}

function renderYears(idx) {
  const body = $('cdc-year-rows');
  body.replaceChildren();
  for (const { year, countries } of calendarYears(idx, 183)) for (const item of countries) {
    const row = document.createElement('tr');
    if (item.atThreshold) row.className = 'cdc-highlight';
    cell(row, String(year), true); cell(row, label(item.country)); cell(row, number.format(item.count));
    cell(row, item.atThreshold ? '183 or more' : `${183 - item.count} below 183`);
    body.append(row);
  }
}

function render() {
  const idx = index();
  renderRejected();
  renderStays();
  const hasData = idx.size > 0;
  $('cdc-results').hidden = !hasData;
  $('cdc-download-totals').disabled = !hasData;
  $('cdc-clear').disabled = !hasData && !state.file;
  if (!hasData) { $('cdc-download-schengen').disabled = true; return; }
  refreshPeriodOptions(idx);
  refreshGroupOptions(idx);
  const period = selectedPeriod(idx);
  renderTotals(idx, period);
  renderWindow(idx, period);
  renderSchengen(idx);
  renderYears(idx);
  const totals = summary(idx);
  const schengenCountries = [...idx.keys()].filter(isSchengen).length;
  $('cdc-summary').textContent = `${plural(idx.size, 'country')} · ${plural(totals.distinctDates, 'distinct date')} · ${plural(totals.countryDates, 'country-date')} · ${plural(schengenCountries, 'Schengen country')}. Sources: ${[state.file ? `${state.fileName} (${plural(state.file.records.length, 'accepted row')})` : null, state.stays.length ? plural(state.stays.length, 'manual stay') : null].filter(Boolean).join(' and ')}.`;
}

function loadCSV(text, name) {
  try {
    const parsed = parseCountryDaysCSV(text);
    state.file = parsed; state.fileName = name;
    const duplicates = parsed.records.length - [...buildIndex(parsed.records).values()].reduce((n, days) => n + days.size, 0);
    setStatus(`Read ${name}: ${plural(parsed.records.length, 'row')} accepted, ${plural(parsed.rejected.length, 'row')} rejected${duplicates ? `, ${plural(duplicates, 'repeated country/date row')} counted once` : ''}.${parsed.hasSource ? '' : ' No source column, so dates are labelled “file”.'}`);
  } catch (error) {
    state.file = null; state.fileName = '';
    setStatus(`${name} could not be used: ${error.message}`);
  }
  render();
}

function startEdit(position) {
  const stay = state.stays[position];
  state.editing = position;
  $('cdc-country').value = stay.country;
  $('cdc-first').value = formatDay(stay.first);
  $('cdc-last').value = formatDay(stay.last);
  $('cdc-add').textContent = 'Save changes';
  $('cdc-cancel').hidden = false;
  $('cdc-country').focus();
}

function cancelEdit() {
  state.editing = -1;
  $('cdc-stay-form').reset();
  $('cdc-add').textContent = 'Add stay';
  $('cdc-cancel').hidden = true;
}

function download(contents, filename) {
  if (state.downloadURL) URL.revokeObjectURL(state.downloadURL);
  state.downloadURL = URL.createObjectURL(new Blob([contents], { type: 'text/csv;charset=utf-8' }));
  const link = node('a'); link.href = state.downloadURL; link.download = filename; link.hidden = true;
  document.body.append(link); link.click(); link.remove();
}

function init() {
  fillCountrySelect();
  $('cdc-reference').value = formatDay(today());
  $('cdc-tool').hidden = false;
  $('cdc-file').addEventListener('change', event => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > MAX_BYTES) { setStatus(`${file.name} is larger than 10 MiB.`); return; }
    const reader = new FileReader();
    reader.onload = () => loadCSV(String(reader.result), file.name);
    reader.onerror = () => setStatus(`${file.name} could not be read.`);
    reader.readAsText(file);
  });
  $('cdc-stay-form').addEventListener('submit', event => {
    event.preventDefault();
    const result = makeStay($('cdc-country').value, $('cdc-first').value, $('cdc-last').value);
    if (result.error) { $('cdc-stay-error').textContent = result.error; return; }
    $('cdc-stay-error').textContent = '';
    if (state.editing >= 0) { state.stays[state.editing] = result.stay; setStatus('Stay updated.'); }
    else { state.stays.push(result.stay); setStatus(`Added ${plural(result.stay.last - result.stay.first + 1, 'day')} in ${countryName(result.stay.country)}.`); }
    cancelEdit();
    render();
  });
  $('cdc-cancel').addEventListener('click', () => { cancelEdit(); setStatus('Edit cancelled.'); });
  $('cdc-demo').addEventListener('click', () => {
    $('cdc-reference').value = '2026-10-03';
    loadCSV(DEMO_COUNTRY_DAYS, 'synthetic-country-days.csv (invented example)');
    $('cdc-status').textContent += ' Schengen reference date set to 2026-10-03.';
  });
  $('cdc-demo-schengen').addEventListener('click', () => {
    state.stays.push(...SCHENGEN_EXAMPLE.stays.map(stay => makeStay(stay.country, stay.first, stay.last).stay));
    $('cdc-reference').value = SCHENGEN_EXAMPLE.references[0];
    setStatus(`Added the invented worked example as two manual stays. Schengen reference date set to ${SCHENGEN_EXAMPLE.references[0]}; try ${SCHENGEN_EXAMPLE.references[1]} next.`);
    render();
  });
  $('cdc-clear').addEventListener('click', () => {
    state.file = null; state.fileName = ''; state.stays = []; cancelEdit();
    $('cdc-file').value = '';
    setStatus('Cleared. Nothing was saved; your original file is unchanged.');
    render();
  });
  for (const id of ['cdc-period', 'cdc-from', 'cdc-to', 'cdc-group', 'cdc-length', 'cdc-threshold', 'cdc-reference']) {
    $(id).addEventListener(id === 'cdc-length' || id === 'cdc-threshold' ? 'input' : 'change', render);
  }
  $('cdc-download-totals').addEventListener('click', () => {
    const idx = index(); const period = selectedPeriod(idx);
    if (period.invalid) { setStatus('Fix the custom range before downloading.'); return; }
    download(totalsCSV(idx, period.first, period.last, countryName), 'country-day-totals.csv');
  });
  $('cdc-download-schengen').addEventListener('click', () => {
    const reference = parseDay($('cdc-reference').value);
    if (reference !== null) download(schengenCSV(schengenCheck(index(), reference)), `schengen-window-${formatDay(reference)}.csv`);
  });
  render();
}

init();
