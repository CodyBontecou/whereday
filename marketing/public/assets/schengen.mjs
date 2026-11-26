import {
  SCHENGEN, EXAMPLE, MAX_PLANNED_STAY, makeTrip, buildDayMap, readCountryDaysCSV, sortedDays, usedOn, windowRows,
  mustLeaveBy, longestStay, earliestStart, windowCSV, parseDay, formatDay,
} from './schengen-core.mjs';
import { localDay } from './country-days-core.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const plural = (n, word) => `${number.format(n)} ${n === 1 ? word : word + 's'}`;
const state = { trips: [], records: [], fileName: '', ignored: 0, rejected: 0, downloadURL: null };

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
const setStatus = text => { $('sc-status').textContent = text; };
const tripText = trip => `${formatDay(trip.first)} to ${trip.last === null ? 'today (still there)' : formatDay(trip.last)}`;

function renderTrips() {
  const list = $('sc-trips');
  list.replaceChildren();
  $('sc-trips-empty').hidden = state.trips.length > 0;
  state.trips.forEach((trip, position) => {
    const item = node('li');
    const reference = parseDay($('sc-reference').value);
    const last = trip.last === null ? reference : trip.last;
    const days = last === null ? 0 : Math.max(0, last - trip.first + 1);
    item.append(node('span', `Trip ${position + 1}: ${tripText(trip)} · ${plural(days, 'day')}${trip.last === null ? ' so far' : ''}`));
    const remove = node('button', 'Remove');
    remove.type = 'button';
    remove.setAttribute('aria-label', `Remove trip ${position + 1}, ${tripText(trip)}`);
    remove.addEventListener('click', () => { state.trips.splice(position, 1); setStatus('Trip removed.'); render(); });
    item.append(' ', remove);
    list.append(item);
  });
}

function figure(list, term, value) {
  const pair = node('div');
  pair.append(node('dt', term), node('dd', value));
  list.append(pair);
}

function renderResults(reference) {
  const { map, ignored } = buildDayMap({ trips: state.trips, records: state.records }, reference);
  const days = sortedDays(map);
  const now = usedOn(days, reference);
  const output = $('sc-results');
  output.replaceChildren();
  const list = node('dl', undefined, 'cdc-figures');
  figure(list, 'Days used', `${now.used} of ${SCHENGEN.limit}`);
  figure(list, 'Days left', now.over ? `0 (${plural(now.over, 'day')} over)` : String(now.remaining));
  figure(list, '180-day window', `${formatDay(now.windowStart)} to ${formatDay(reference)}`);
  const ongoing = state.trips.some(trip => trip.last === null && trip.first <= reference);
  if (ongoing) {
    const leave = mustLeaveBy(days, reference);
    figure(list, 'Last day you can be there', leave.last === null ? 'Already over 90' : formatDay(leave.last));
  }
  output.append(list);
  const notes = [];
  if (ongoing) notes.push('Last day: staying every day from the reference date up to and including that date keeps every day at 90 or fewer. It can be later than days left suggests, because old days drop out of the window as you stay.');
  else notes.push('Leave the exit date empty for a stay that is still going on to see the last day you can be there.');
  const later = days.filter(day => day > reference).length;
  if (later) notes.push(`${plural(later, 'Schengen date')} after the reference date ${later === 1 ? 'is' : 'are'} not used for days used or left.`);
  const future = state.trips.filter(trip => trip.last === null && trip.first > reference).length;
  if (future) notes.push('An ongoing trip that starts after the reference date is not counted.');
  output.append(node('p', notes.join(' '), 'gpx-small'));

  const body = $('sc-window-rows');
  body.replaceChildren();
  for (const row of windowRows(map, reference)) {
    const tr = document.createElement('tr');
    cell(tr, formatDay(row.day), true); cell(tr, row.sources.join(', ')); cell(tr, formatDay(row.fallsOut));
    body.append(tr);
  }
  $('sc-window-note').textContent = now.used ? `${plural(now.used, 'date')} counted in this window.` : 'No Schengen dates in this window.';
  $('sc-download').disabled = false;
  return { map, days, ignored };
}

function renderPlanner(days, reference) {
  const out = $('sc-planner-results');
  out.replaceChildren();
  const body = $('sc-planner-rows');
  body.replaceChildren();
  const entry = parseDay($('sc-entry').value);
  if (entry === null) out.append(node('p', 'Enter a planned entry date.'));
  else {
    const longest = longestStay(days, entry);
    const list = node('dl', undefined, 'cdc-figures');
    figure(list, 'Longest stay from that entry', longest.length ? plural(longest.length, 'day') : '0 days');
    figure(list, 'Latest exit date', longest.last === null ? 'None: day 1 is already over 90' : formatDay(longest.last));
    out.append(list);
    out.append(node('p', longest.blocked
      ? `Every day from ${formatDay(entry)} is checked against its own 180-day window. ${formatDay(longest.blocked.day)} would be day ${longest.blocked.used} in its window, so the stay must end the day before.`
      : `Every day from ${formatDay(entry)} is checked against its own 180-day window. A single short-stay visit cannot pass ${MAX_PLANNED_STAY} days here, so the plan stops at ${plural(longest.length, 'day')}.`, 'gpx-small'));
    for (const check of longest.checks) {
      const tr = document.createElement('tr');
      cell(tr, formatDay(check.day), true); cell(tr, String(check.used)); cell(tr, String(SCHENGEN.limit - check.used));
      body.append(tr);
    }
  }
  const length = Number($('sc-length').value);
  const ongoing = state.trips.some(trip => trip.last === null && trip.first <= reference);
  const from = ongoing ? reference + 1 : reference;
  const start = earliestStart(days, from, length);
  const result = $('sc-start-result');
  if (start.error) result.textContent = start.error;
  else if (start.entry === null) result.textContent = `No start date within 540 days of ${formatDay(from)} fits ${plural(length, 'day')}.`;
  else result.replaceChildren(node('strong', `Earliest start for ${plural(length, 'day')}: ${formatDay(start.entry)}`),
    `, leaving on ${formatDay(start.last)}. Searched from ${formatDay(from)}${ongoing ? ' (the day after your ongoing stay is counted to)' : ', the reference date'}, and checked every day of the stay.`);
}

function render() {
  renderTrips();
  const reference = parseDay($('sc-reference').value);
  const has = state.trips.length > 0 || state.records.length > 0;
  $('sc-clear').disabled = !has && !state.fileName;
  if (reference === null) { $('sc-results').textContent = 'Enter a reference date.'; $('sc-download').disabled = true; return; }
  const { days } = renderResults(reference);
  renderPlanner(days, reference);
}

function loadCSV(text, name) {
  try {
    const parsed = readCountryDaysCSV(text);
    state.records = parsed.records; state.fileName = name; state.ignored = parsed.ignored; state.rejected = parsed.rejected.length;
    const distinct = new Set(parsed.records.map(r => r.day)).size;
    setStatus(`Read ${name}: ${plural(parsed.records.length, 'Schengen row')} used (${plural(distinct, 'distinct date')}), ${plural(parsed.ignored, 'row')} ignored because the country is not a Schengen state, ${plural(parsed.rejected.length, 'row')} rejected as invalid.`);
  } catch (error) {
    state.records = []; state.fileName = ''; state.ignored = 0; state.rejected = 0;
    setStatus(`${name} could not be used: ${error.message}`);
  }
  render();
}

function download(contents, filename) {
  if (state.downloadURL) URL.revokeObjectURL(state.downloadURL);
  state.downloadURL = URL.createObjectURL(new Blob([contents], { type: 'text/csv;charset=utf-8' }));
  const link = node('a'); link.href = state.downloadURL; link.download = filename; link.hidden = true;
  document.body.append(link); link.click(); link.remove();
}

function init() {
  const today = formatDay(localDay(new Date()));
  $('sc-reference').value = today;
  $('sc-entry').value = today;
  $('sc-tool').hidden = false;
  $('sc-trip-form').addEventListener('submit', event => {
    event.preventDefault();
    const result = makeTrip($('sc-first').value, $('sc-last').value);
    if (result.error) { $('sc-trip-error').textContent = result.error; return; }
    if (result.trip.last === null && state.trips.some(trip => trip.last === null)) { $('sc-trip-error').textContent = 'Only one trip can be ongoing. Give the other an exit date.'; return; }
    $('sc-trip-error').textContent = '';
    state.trips.push(result.trip);
    $('sc-trip-form').reset();
    setStatus(`Added trip ${state.trips.length}.`);
    render();
  });
  $('sc-file').addEventListener('change', event => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) { setStatus(`${file.name} is larger than 10 MiB.`); return; }
    const reader = new FileReader();
    reader.onload = () => loadCSV(String(reader.result), file.name);
    reader.onerror = () => setStatus(`${file.name} could not be read.`);
    reader.readAsText(file);
  });
  $('sc-demo').addEventListener('click', () => {
    state.trips = EXAMPLE.trips.map(t => ({ first: parseDay(t.first), last: t.last === null ? null : parseDay(t.last) }));
    state.records = []; state.fileName = '';
    $('sc-reference').value = EXAMPLE.references[0];
    $('sc-entry').value = EXAMPLE.plannedEntry;
    $('sc-length').value = String(EXAMPLE.plannedLength);
    setStatus(`Loaded three invented trips. The last one is still going on at the reference date ${EXAMPLE.references[0]}. Remove trip 3 and add it again with the exit date ${EXAMPLE.endedOn}, then try the reference date ${EXAMPLE.references[1]}.`);
    render();
  });
  $('sc-clear').addEventListener('click', () => {
    state.trips = []; state.records = []; state.fileName = '';
    $('sc-file').value = '';
    setStatus('Cleared. Nothing was saved; your original file is unchanged.');
    render();
  });
  for (const id of ['sc-reference', 'sc-entry']) $(id).addEventListener('change', render);
  $('sc-length').addEventListener('input', render);
  $('sc-download').addEventListener('click', () => {
    const reference = parseDay($('sc-reference').value);
    if (reference === null) return;
    download(windowCSV(buildDayMap({ trips: state.trips, records: state.records }, reference).map, reference), `schengen-window-${formatDay(reference)}.csv`);
  });
  render();
}

init();
