import {
  parseCountryDaysCSV, makeStay, buildIndex, formatDay, yearOf, parseDay, usDaysByYear, ignoredCounts,
  substantialPresence, parseCount, IRS_EXAMPLE, SYNTHETIC_STAYS, SYNTHETIC_YEAR, US_TERRITORIES,
} from './spt-core.mjs';
import { localDay, MAX_BYTES } from './country-days-core.mjs';

const $ = id => document.getElementById(id);
const plural = (n, word) => `${n.toLocaleString('en-US')} ${n === 1 ? word : word + 's'}`;
const state = { file: null, fileName: '', stays: [], typed: {}, excluded: {}, year: null };
const today = () => localDay(new Date());

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text !== undefined) el.textContent = text;
  if (className) el.className = className;
  return el;
}
const setStatus = text => { $('spt-status').textContent = text; };
const index = () => buildIndex(state.file?.records ?? [], state.stays);

function addCell(row, text, header = false) {
  const el = node(header ? 'th' : 'td', text);
  if (header) el.scope = 'row';
  row.append(el);
  return el;
}

function fieldCell(row, id, label, store, year, hint) {
  const td = node('td');
  const input = node('input'); input.id = id; input.type = 'text'; input.inputMode = 'numeric'; input.maxLength = 3; input.size = 4;
  input.value = store[year] ?? '';
  input.setAttribute('aria-label', label);
  input.addEventListener('input', () => { store[year] = input.value; render(false); });
  td.append(input);
  const err = node('span', '', 'cdc-error'); err.id = id + '-error'; err.setAttribute('role', 'alert');
  input.setAttribute('aria-describedby', err.id);
  td.append(err);
  row.append(td);
  return { input, err };
}

function renderRejected() {
  const rejected = state.file?.rejected ?? [];
  $('spt-rejected').hidden = !rejected.length;
  const body = $('spt-rejected-rows');
  body.replaceChildren();
  $('spt-rejected-note').textContent = rejected.length
    ? `${plural(rejected.length, 'row')} in ${state.fileName} could not be counted. Fix them in the file and load it again; they are not included anywhere below.${rejected.length > 500 ? ' The first 500 are listed.' : ''}`
    : '';
  for (const item of rejected.slice(0, 500)) {
    const row = document.createElement('tr');
    addCell(row, String(item.row), true); addCell(row, item.reason); addCell(row, item.text);
    body.append(row);
  }
}

function renderStays() {
  const list = $('spt-stays');
  list.replaceChildren();
  $('spt-stays-empty').hidden = state.stays.length > 0;
  state.stays.forEach((stay, position) => {
    const item = node('li');
    item.append(node('span', `${stay.country}: ${formatDay(stay.first)} to ${formatDay(stay.last)} · ${plural(stay.last - stay.first + 1, 'day')}`));
    const remove = node('button', 'Delete'); remove.type = 'button';
    remove.setAttribute('aria-label', `Delete stay from ${formatDay(stay.first)}`);
    remove.addEventListener('click', () => { state.stays.splice(position, 1); setStatus('Stay deleted.'); render(); });
    item.append(' ', remove);
    list.append(item);
  });
}

function yearList(byYear) {
  const now = yearOf(today());
  const years = new Set([now, now - 1, now - 2, state.year ?? now]);
  for (const y of byYear.keys()) years.add(y);
  return [...years].sort((a, b) => b - a);
}

function render(rebuildInputs = true) {
  const idx = index();
  const byYear = usDaysByYear(idx);
  if (state.year === null) state.year = byYear.size ? Math.max(...byYear.keys()) : yearOf(today());
  const years = yearList(byYear);
  const select = $('spt-year');
  if (rebuildInputs || select.options.length !== years.length) {
    select.replaceChildren(...years.map(y => new Option(String(y), String(y))));
  }
  select.value = String(state.year);
  const ignored = ignoredCounts(idx);
  $('spt-ignored').textContent = (idx.size || state.file)
    ? `Only dates with country code US are counted. Ignored: ${plural(ignored.territories, 'date')} in US territories (${US_TERRITORIES.join(', ')}) and ${plural(ignored.other, 'date')} in other countries. Repeated dates count once.`
    : 'Only dates with country code US are counted. Other codes, including the US territories (PR, GU, VI, AS, MP), are ignored.';
  renderRejected();
  renderStays();
  $('spt-clear').disabled = !state.file && !state.stays.length && !Object.values(state.typed).some(v => v !== '' && v !== undefined) && !Object.values(state.excluded).some(v => v !== '' && v !== undefined);

  const Y = state.year;
  const body = $('spt-rows');
  const focused = document.activeElement?.id;
  const input = { days: {}, excluded: {} };
  let invalid = false;
  const rows = [Y, Y - 1, Y - 2];
  if (rebuildInputs || !body.children.length || body.dataset.year !== String(Y)) {
    body.replaceChildren();
    body.dataset.year = String(Y);
    for (const y of rows) {
      const tr = document.createElement('tr'); tr.dataset.year = String(y);
      addCell(tr, String(y), true);
      addCell(tr, '').className = 'spt-dates';
      fieldCell(tr, `spt-typed-${y}`, `Typed US days for ${y}, replaces the counted dates`, state.typed, y);
      fieldCell(tr, `spt-excl-${y}`, `Excluded days for ${y}`, state.excluded, y);
      addCell(tr, ''); addCell(tr, ''); addCell(tr, '');
      body.append(tr);
    }
    if (focused && $(focused)) $(focused).focus();
  }
  for (const y of rows) {
    const tr = body.querySelector(`tr[data-year="${y}"]`);
    const counted = byYear.get(y)?.length ?? 0;
    const typed = parseCount(state.typed[y], y), excl = parseCount(state.excluded[y], y);
    $(`spt-typed-${y}-error`).textContent = typed.error ?? ''; $(`spt-excl-${y}-error`).textContent = excl.error ?? '';
    if (typed.error || excl.error) invalid = true;
    input.days[y] = typed.value ?? counted;
    input.excluded[y] = excl.value ?? 0;
    tr.cells[1].textContent = plural(counted, 'US date');
    tr.cells[4].textContent = String(Math.max(0, input.days[y] - input.excluded[y]));
  }
  const out = $('spt-result');
  const detail = $('spt-detail');
  if (invalid) { out.textContent = 'Fix the highlighted fields to see the result.'; detail.hidden = true; return; }
  const r = substantialPresence(Y, input, today());
  const labels = ['1', '1/3', '1/6'];
  r.rows.forEach((row, i) => {
    const tr = body.querySelector(`tr[data-year="${row.year}"]`);
    tr.cells[5].textContent = labels[i]; tr.cells[6].textContent = row.weighted;
  });
  out.replaceChildren();
  const list = node('dl', undefined, 'cdc-figures');
  const fig = (t, v) => { const d = node('div'); d.append(node('dt', t), node('dd', v)); list.append(d); };
  fig('Weighted total', `${r.total} days (needs 183)`);
  fig(`Days in ${Y}`, `${r.rows[0].counted} (needs at least 31): ${r.enoughCurrent ? 'yes' : 'no'}`);
  fig('183-day check', r.enoughTotal ? 'reached' : 'not reached');
  fig(`Test for ${Y}`, r.result);
  out.append(list);
  out.append(node('p', `On these days the test is ${r.result} for ${Y}.`));
  if (!r.met && r.inProgress) {
    const note = `${Y} is not over. About ${plural(r.moreDays, 'more US day')} in ${Y} would bring the weighted total to 183 and the days in ${Y} to at least 31, assuming nothing else changes.`;
    out.append(node('p', r.daysLeftInYear !== null && r.moreDays > r.daysLeftInYear ? `${note} Only ${plural(r.daysLeftInYear, 'day')} remain after today, so it cannot be reached in ${Y}.` : note));
  }
  out.append(node('p', 'This is a count, not tax advice. Other rules, such as the closer connection exception or a tax treaty, can change the outcome.', 'gpx-small'));
  // Dates behind each year's count.
  detail.hidden = false;
  const dl = $('spt-date-lists');
  dl.replaceChildren();
  for (const y of rows) {
    const days = byYear.get(y) ?? [];
    const details = node('details');
    details.append(node('summary', `${y}: ${plural(days.length, 'US date')} from your data${state.typed[y] ? ' (replaced by your typed number)' : ''}`));
    const ul = node('ul', undefined, 'cdc-dates'); ul.tabIndex = 0; ul.setAttribute('aria-label', `US dates counted for ${y}`);
    for (const day of days) ul.append(node('li', formatDay(day)));
    details.append(ul);
    dl.append(details);
  }
}

function loadCSV(text, name) {
  try {
    const parsed = parseCountryDaysCSV(text);
    state.file = parsed; state.fileName = name;
    const distinct = [...buildIndex(parsed.records).values()].reduce((n, d) => n + d.size, 0);
    setStatus(`Read ${name}: ${plural(parsed.records.length, 'row')} accepted, ${plural(parsed.rejected.length, 'row')} rejected${parsed.records.length > distinct ? `, repeated country/date rows counted once` : ''}.`);
  } catch (error) {
    state.file = null; state.fileName = '';
    setStatus(`${name} could not be used: ${error.message}`);
  }
  state.year = null;
  render();
}

function reset() {
  state.file = null; state.fileName = ''; state.stays = []; state.typed = {}; state.excluded = {}; state.year = null; 
  $('spt-file').value = '';
}

function init() {
  $('spt-tool').hidden = false;
  $('spt-file').addEventListener('change', event => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > MAX_BYTES) { setStatus(`${file.name} is larger than 10 MiB.`); return; }
    const reader = new FileReader();
    reader.onload = () => loadCSV(String(reader.result), file.name);
    reader.onerror = () => setStatus(`${file.name} could not be read.`);
    reader.readAsText(file);
  });
  $('spt-stay-form').addEventListener('submit', event => {
    event.preventDefault();
    const result = makeStay('US', $('spt-first').value, $('spt-last').value);
    if (result.error) { $('spt-stay-error').textContent = result.error; return; }
    $('spt-stay-error').textContent = '';
    state.stays.push(result.stay);
    state.year = null;
    setStatus(`Added ${plural(result.stay.last - result.stay.first + 1, 'US day')}.`);
    $('spt-stay-form').reset();
    render();
  });
  $('spt-year').addEventListener('change', () => { state.year = Number($('spt-year').value); render(true); });
  $('spt-irs').addEventListener('click', () => {
    reset();
    state.typed = Object.fromEntries(Object.entries(IRS_EXAMPLE.typed).map(([y, v]) => [y, String(v)]));
    state.year = IRS_EXAMPLE.year;
    setStatus('Loaded the IRS example: 120 typed US days in each of 2023, 2024 and 2025.');
    render(true);
  });
  $('spt-synthetic').addEventListener('click', () => {
    reset();
    state.stays = SYNTHETIC_STAYS.map(s => makeStay(s.country, s.first, s.last).stay);
    state.year = SYNTHETIC_YEAR;
    setStatus('Loaded an invented example as manual stays: 90 days in 2024, 120 in 2025 and 150 in 2026, plus a few non-US days that are ignored.');
    render(true);
  });
  $('spt-clear').addEventListener('click', () => { reset(); setStatus('Cleared. Nothing was saved; your original file is unchanged.'); render(true); });
  render(true);
}

init();
