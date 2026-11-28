import {
  PATHS, parseDay, formatDay, makeTrip, parseTripsCSV, checkOverlaps, naturalization, tripsCSV, summaryCSV,
  SAMPLE_CSV, SAMPLE_INPUT, STATUS_TEXT, MAX_BYTES, MAX_TRIPS,
} from './npp-core.mjs';
import { localDay } from './country-days-core.mjs';

const $ = id => document.getElementById(id);
const plural = (n, word) => `${n.toLocaleString('en-US')} ${n === 1 ? word : word + 's'}`;
const state = { trips: [], rejected: [], fileName: '', url: null };
const SOURCES = {
  continuous: 'https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-3',
  physical: 'https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-4',
  early: 'https://www.uscis.gov/policy-manual/volume-12-part-d-chapter-6',
};

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text !== undefined) el.textContent = text;
  if (className) el.className = className;
  return el;
}
function addCell(row, text, header = false) {
  const el = node(header ? 'th' : 'td', text);
  if (header) el.scope = 'row';
  row.append(el);
  return el;
}
function link(text, href) { const a = node('a', text); a.href = href; return a; }
const setStatus = text => { $('npp-status').textContent = text; };

function inputs() {
  const since = parseDay($('npp-since').value);
  const filing = parseDay($('npp-filing').value);
  return { path: $('npp-path').value, since, filing };
}

function download(contents, filename) {
  if (state.url) URL.revokeObjectURL(state.url);
  state.url = URL.createObjectURL(new Blob([contents], { type: 'text/csv;charset=utf-8' }));
  const a = node('a'); a.href = state.url; a.download = filename; a.hidden = true;
  document.body.append(a); a.click(); a.remove();
}

function renderTrips() {
  const list = $('npp-trips');
  list.replaceChildren();
  $('npp-trips-empty').hidden = state.trips.length > 0;
  [...state.trips].sort((a, b) => a.dep - b.dep).forEach(trip => {
    const item = node('li');
    item.append(`${formatDay(trip.dep)} to ${formatDay(trip.ret)}${trip.note ? ` · ${trip.note}` : ''} `);
    const remove = node('button', 'Delete'); remove.type = 'button';
    remove.setAttribute('aria-label', `Delete trip from ${formatDay(trip.dep)}`);
    remove.addEventListener('click', () => { state.trips.splice(state.trips.indexOf(trip), 1); setStatus('Trip deleted.'); render(); });
    item.append(remove);
    list.append(item);
  });
  $('npp-clear').disabled = !state.trips.length && !state.rejected.length;
}

function renderRejected() {
  $('npp-rejected').hidden = !state.rejected.length;
  const body = $('npp-rejected-rows');
  body.replaceChildren();
  for (const item of state.rejected.slice(0, 500)) {
    const row = document.createElement('tr');
    addCell(row, String(item.row), true); addCell(row, item.reason); addCell(row, item.text);
    body.append(row);
  }
}

function fig(list, title, value) { const d = node('div'); d.append(node('dt', title), node('dd', value)); list.append(d); }

function render() {
  renderTrips();
  renderRejected();
  const out = $('npp-result');
  out.replaceChildren();
  const { path, since, filing } = inputs();
  $('npp-download-trips').disabled = true; $('npp-download-summary').disabled = true;
  if (since === null || filing === null) { out.textContent = 'Enter the resident-since date and a filing date to see the result.'; return; }
  const overlaps = checkOverlaps(state.trips);
  if (overlaps.length) {
    out.append(node('p', 'Fix these overlapping trips before the count can be trusted:', 'cdc-error'));
    const ul = node('ul'); overlaps.forEach(o => ul.append(node('li', o))); out.append(ul);
    return;
  }
  const r = naturalization({ path, since, filing, trips: state.trips });
  $('npp-download-trips').disabled = false; $('npp-download-summary').disabled = false;
  state.last = { r, since, filing };
  const p = PATHS[path];
  const first = formatDay(r.window.first), last = formatDay(r.window.last);

  out.append(node('h4', '1. Physical presence'));
  const pp = node('dl', undefined, 'cdc-figures');
  fig(pp, 'Window', `${first} to ${last} (${plural(r.windowDays, 'day')}, the ${p.years} years ending on the filing date)`);
  fig(pp, 'Days abroad in window', `${r.abroad} (full days between departure and return, clipped to the window)`);
  fig(pp, 'Days present', `${r.windowDays} − ${r.abroad} = ${r.present}`);
  fig(pp, 'Required', path === 'general' ? `${r.requiredMonths} months (913 days)` : `${r.requiredMonths} months (${r.required} days by this tool’s count)`);
  fig(pp, r.diff >= 0 ? 'Surplus' : 'Shortfall', `${Math.abs(r.diff)} ${Math.abs(r.diff) === 1 ? 'day' : 'days'}: ${r.enough ? 'enough days present' : 'not enough days present'}`);
  out.append(pp);
  const src = node('p', 'Departure and return days count as days present. ', 'gpx-small');
  src.append(link('USCIS Policy Manual, Vol. 12, Part D, Ch. 4', SOURCES.physical), '. ');
  if (path === 'spouse') src.append('USCIS gives no day count for 18 months, so the tool counts the days from 18 months before the filing date to the filing date. 8 CFR 319.1 sets the 18 months.');
  out.append(src);
  if (r.clipped) out.append(node('p', `${plural(r.clipped, 'trip')} cross the edge of the window, so only the days inside it are counted above.`));
  if (r.ignored) out.append(node('p', `${plural(r.ignored, 'trip')} fall wholly outside the window and are not counted.`));

  out.append(node('h4', '2. Continuous residence'));
  const flagged = r.rows.filter(t => !t.outside && t.status !== 'ok');
  if (!flagged.length) out.append(node('p', 'No absence in the window is longer than 6 months.'));
  for (const t of flagged) {
    const p2 = node('p');
    p2.append(node('strong', `${formatDay(t.dep)} to ${formatDay(t.ret)} (${plural(t.fullDays, 'full day')} away): `), STATUS_TEXT[t.status] + '.');
    if (t.wait) p2.append(` After this return, 4 years and 1 day is ${formatDay(t.wait.fourYearsOneDay)}; 4 years and 6 months, which avoids the presumption, is ${formatDay(t.wait.noPresumption)}.`);
    out.append(p2);
  }
  const cr = node('p', 'The count of full days leaves out the travel dates. 6 months and 1 year are calendar periods from the departure date. ', 'gpx-small');
  cr.append(link('USCIS Policy Manual, Vol. 12, Part D, Ch. 3', SOURCES.continuous), '.');
  out.append(cr);

  out.append(node('h4', '3. Earliest filing date'));
  const ef = node('dl', undefined, 'cdc-figures');
  fig(ef, `${p.years} years reached`, formatDay(r.reached));
  fig(ef, 'Earliest filing date', `${formatDay(r.reached)} − 90 days = ${formatDay(r.earliest)}`);
  fig(ef, 'Your filing date', r.filingBeforeEarliest ? `${formatDay(filing)} is before the earliest date` : `${formatDay(filing)} is on or after the earliest date`);
  out.append(ef);
  const es = node('p', 'Physical presence and the 3-month state residence are separate checks; filing early does not shorten either. ', 'gpx-small');
  es.append(link('USCIS Policy Manual, Vol. 12, Part D, Ch. 6', SOURCES.early), '.');
  out.append(es);

  out.append(node('h4', 'Per-trip working'));
  const wrap = node('div', undefined, 'table-scroll'); wrap.tabIndex = 0; wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', 'Per-trip working');
  const table = node('table');
  const head = node('tr');
  for (const h of ['Departure', 'Return', 'Full days away', 'Days away in window', 'Continuous residence']) head.append(Object.assign(node('th', h), { scope: 'col' }));
  const thead = node('thead'); thead.append(head); table.append(thead);
  const tbody = node('tbody');
  for (const t of r.rows) {
    const tr = node('tr');
    addCell(tr, formatDay(t.dep), true); addCell(tr, formatDay(t.ret)); addCell(tr, String(t.fullDays));
    addCell(tr, t.outside ? '0 (outside window)' : `${t.abroadInWindow}${t.clipped ? ' (clipped)' : ''}`);
    addCell(tr, STATUS_TEXT[t.status]);
    tbody.append(tr);
  }
  table.append(tbody); wrap.append(table); out.append(wrap);
}

function loadCSV(text, name) {
  try {
    const parsed = parseTripsCSV(text);
    state.trips.push(...parsed.trips); state.rejected = parsed.rejected; state.fileName = name;
    setStatus(`Read ${name}: ${plural(parsed.trips.length, 'trip')} added, ${plural(parsed.rejected.length, 'row')} not used.`);
  } catch (error) { setStatus(`${name} could not be used: ${error.message}`); }
  render();
}

function init() {
  $('npp-tool').hidden = false;
  $('npp-filing').value = formatDay(localDay(new Date()));
  for (const id of ['npp-path', 'npp-since', 'npp-filing']) $(id).addEventListener('input', render);
  $('npp-file').addEventListener('change', event => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > MAX_BYTES) { setStatus(`${file.name} is larger than 1 MiB.`); return; }
    const reader = new FileReader();
    reader.onload = () => loadCSV(String(reader.result), file.name);
    reader.onerror = () => setStatus(`${file.name} could not be read.`);
    reader.readAsText(file);
    event.target.value = '';
  });
  $('npp-trip-form').addEventListener('submit', event => {
    event.preventDefault();
    const r = makeTrip($('npp-dep').value, $('npp-ret').value, $('npp-note').value);
    if (r.error) { $('npp-trip-error').textContent = r.error; return; }
    if (state.trips.length >= MAX_TRIPS) { $('npp-trip-error').textContent = `At most ${MAX_TRIPS} trips.`; return; }
    $('npp-trip-error').textContent = '';
    state.trips.push(r.trip);
    setStatus('Trip added.');
    $('npp-trip-form').reset();
    render();
  });
  $('npp-sample').addEventListener('click', () => download(SAMPLE_CSV, 'invented-trips-sample.csv'));
  $('npp-example').addEventListener('click', () => {
    state.trips = []; state.rejected = [];
    $('npp-path').value = SAMPLE_INPUT.path; $('npp-since').value = SAMPLE_INPUT.since; $('npp-filing').value = SAMPLE_INPUT.filing;
    loadCSV(SAMPLE_CSV, 'the invented example');
  });
  $('npp-download-trips').addEventListener('click', () => state.last && download(tripsCSV(state.last.r), 'trips-counts.csv'));
  $('npp-download-summary').addEventListener('click', () => state.last && download(summaryCSV(state.last.r, state.last.since, state.last.filing), 'naturalization-summary.csv'));
  $('npp-clear').addEventListener('click', () => { state.trips = []; state.rejected = []; setStatus('Cleared. Nothing was saved.'); render(); });
  render();
}

init();
