import { readCSV, convertCSV, gpxToCSV, looksLikeGPX, defaultMode, safeBase, REASONS, MAX_BYTES } from './csv-gpx-core.mjs';
import { CSV_GPX_SAMPLES } from './csv-gpx-demo.mjs';

const $ = id => document.getElementById(id);
const fmt = new Intl.NumberFormat('en-US');
const count = (n, label, plural = label + 's') => `${fmt.format(n)} ${n === 1 ? label : plural}`;
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
const SVG = 'http://www.w3.org/2000/svg';
const COLUMNS = ['lat', 'lon', 'time', 'ele', 'name', 'group'];
const SKIPPED_ROWS_SHOWN = 20;
let source = null, table = null, out = null, gpxOut = null, downloadURL = null, generation = 0;

function reset() {
  source = null; table = null; out = null; gpxOut = null;
  $('cg-csv').hidden = true; $('cg-gpx').hidden = true;
  if (downloadURL) { URL.revokeObjectURL(downloadURL); downloadURL = null; }
}

function fillSelect(id, headers, selected, optional) {
  const select = $(id); select.replaceChildren();
  if (optional) { const none = el('option', '(none)'); none.value = '-1'; select.append(none); }
  headers.forEach((h, i) => { const o = el('option', h); o.value = String(i); select.append(o); });
  select.value = String(selected);
}

function load(text, name) {
  generation++;
  reset();
  $('cg-error').hidden = true;
  if (looksLikeGPX(text)) { source = { text, name, kind: 'gpx' }; showGPX(); return; }
  source = { text, name, kind: 'csv' };
  try { table = readCSV(text, { delimiter: $('cg-delimiter').value, header: $('cg-header').value }); }
  catch (e) { fail(e); return; }
  $('cg-delimiter').dataset.detected = table.delimiter;
  for (const key of COLUMNS) fillSelect(`cg-col-${key}`, table.headers, table.mapping[key], key !== 'lat' && key !== 'lon');
  const kinds = table.mapping.kind >= 0;
  document.querySelector('input[name="cg-mode"][value="kinds"]').disabled = !kinds;
  $('cg-kinds-label').hidden = !kinds;
  document.querySelector(`input[name="cg-mode"][value="${defaultMode(table.mapping)}"]`).checked = true;
  $('cg-name').value = safeBase(name);
  $('cg-csv').hidden = false;
  update();
}

function fail(e) {
  reset();
  $('cg-error').textContent = e instanceof Error && e.message ? e.message : 'This text could not be read.'; $('cg-error').hidden = false;
  $('cg-status').textContent = '';
}

function currentMapping() {
  const m = { kind: table.mapping.kind };
  for (const key of COLUMNS) m[key] = Number($(`cg-col-${key}`).value);
  return m;
}

function drawPlot(paths) {
  const svg = $('cg-plot'); svg.replaceChildren();
  paths.forEach((path, i) => {
    const cls = i % 2 ? 'alt' : '';
    if (path.length > 1) { const p = document.createElementNS(SVG, 'polyline'); p.setAttribute('points', path.map(pt => pt.map(n => n.toFixed(1)).join(',')).join(' ')); if (cls) p.setAttribute('class', cls); svg.append(p); }
    else if (path.length === 1) { const c = document.createElementNS(SVG, 'circle'); c.setAttribute('cx', path[0][0].toFixed(1)); c.setAttribute('cy', path[0][1].toFixed(1)); c.setAttribute('r', 4); if (cls) c.setAttribute('class', cls); svg.append(c); }
  });
}

function update() {
  if (!table) return;
  const mode = document.querySelector('input[name="cg-mode"]:checked').value;
  const warnings = $('cg-warnings'); warnings.replaceChildren();
  $('cg-error').hidden = true;
  try { out = convertCSV(table, { mapping: currentMapping(), mode, fileName: $('cg-name').value || 'points' }); }
  catch (e) { out = null; $('cg-output').hidden = true; $('cg-error').textContent = e.message; $('cg-error').hidden = false; $('cg-download').disabled = true; return; }
  const r = out.report;
  $('cg-output').hidden = false;
  if (r.assumedUtc) warnings.append(el('p', `${count(r.assumedUtc, 'time')} had no time zone, so ${r.assumedUtc === 1 ? 'it was' : 'they were'} treated as UTC. If your logger wrote local time, the times in the GPX are shifted by your offset from UTC. Add a Z or an offset such as +02:00 to the CSV and convert again.`, 'csvgpx-warn'));
  if (r.badTime) warnings.append(el('p', `${count(r.badTime, 'time')} could not be read as an ISO 8601 date and time (for example 2000-01-01T12:00:00Z), so ${r.badTime === 1 ? 'that point was' : 'those points were'} written without a time.`, 'csvgpx-warn'));
  if (r.badElevation) warnings.append(el('p', `${count(r.badElevation, 'elevation')} could not be read as a number and ${r.badElevation === 1 ? 'was' : 'were'} left out.`, 'csvgpx-warn'));

  const rows = [
    ['Rows read', fmt.format(r.rowsRead)], ['Points written', fmt.format(r.written)],
    ['Rows skipped', r.skipped.length ? fmt.format(r.skipped.length) : '0'],
    ['Output', `${count(r.tracks, 'track')}, ${count(r.segments, 'segment')}, ${count(r.routes, 'route')}, ${count(r.waypoints, 'waypoint')}`],
    ['First time (UTC)', r.firstTime || 'none'], ['Last time (UTC)', r.lastTime || 'none'],
  ];
  const body = $('cg-table'); body.replaceChildren();
  for (const [k, v] of rows) { const tr = el('tr'); const th = el('th', k); th.scope = 'row'; tr.append(th, el('td', v)); body.append(tr); }
  const notes = $('cg-notes'); notes.replaceChildren();
  for (const [key, n] of Object.entries(r.skippedByReason)) if (n) notes.append(el('li', `${count(n, 'row')} skipped: ${REASONS[key]}.`));
  if (r.skipped.length) {
    const shown = r.skipped.slice(0, SKIPPED_ROWS_SHOWN).map(s => s.row).join(', ');
    notes.append(el('li', `Skipped row numbers (the header is row 1): ${shown}${r.skipped.length > SKIPPED_ROWS_SHOWN ? `, and ${fmt.format(r.skipped.length - SKIPPED_ROWS_SHOWN)} more` : ''}.`));
  }
  if (r.noTime) notes.append(el('li', `${count(r.noTime, 'point')} with an empty time cell, written without a time.`));
  if (!r.written) notes.append(el('li', 'No row had usable coordinates. Check the latitude and longitude columns above.'));
  drawPlot(r.plot);
  $('cg-download').disabled = !r.written;
  $('cg-filename').textContent = r.written ? `${safeBase($('cg-name').value || source.name)}.gpx` : 'Nothing to download yet.';
  $('cg-status').textContent = `${source.name}: ${count(r.written, 'point')} ready, ${count(r.skipped.length, 'row')} skipped. Everything was read on this device.`;
}

function showGPX() {
  try { gpxOut = gpxToCSV(source.text); }
  catch (e) { fail(e); return; }
  const m = gpxOut.model;
  $('cg-gpx').hidden = false;
  $('cg-gpx-summary').textContent = `${source.name}: ${count(m.points.length, 'point')} with usable coordinates in ${count(m.tracks, 'track')}, ${count(m.segments, 'segment')}, ${count(m.routes, 'route')} and ${count(m.waypoints, 'waypoint')}.${m.issues.invalidCoordinates ? ` ${count(m.issues.invalidCoordinates, 'point')} with unusable coordinates left out.` : ''}`;
  $('cg-gpx-download').disabled = !m.points.length;
  $('cg-status').textContent = 'This is a GPX file, so the CSV download is for GPX to CSV. Everything was read on this device.';
}

function save(contents, type, filename) {
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([contents], { type }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = filename; document.body.append(a); a.click(); a.remove();
}

async function addFile(file) {
  const token = ++generation;
  if (file.size === 0) { fail(new Error('This file is empty. Export it again from the app that made it.')); return; }
  if (file.size > MAX_BYTES) { fail(new Error('This file is over 10 MiB. Split it into smaller files and try again.')); return; }
  if (/\.(zip|gz|xlsx|xls|kml|kmz|json|geojson)$/i.test(file.name)) { fail(new Error('Choose a .csv, .txt or .gpx file. In a spreadsheet, use Save as CSV first. For KML use the KML to GPX converter.')); return; }
  $('cg-status').textContent = `Reading ${file.name} on this device…`;
  try { const text = await file.text(); if (token !== generation) return; $('cg-paste').value = ''; load(text, file.name); }
  catch { if (token === generation) fail(new Error('This file could not be read. Try saving a local copy first.')); }
}

$('cg-file').addEventListener('change', e => { if (e.target.files[0]) addFile(e.target.files[0]); e.target.value = ''; });
const drop = $('cg-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, e => { e.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', e => { e.preventDefault(); if (e.dataTransfer?.files?.[0]) addFile(e.dataTransfer.files[0]); });
let timer = 0;
$('cg-paste').addEventListener('input', () => {
  clearTimeout(timer);
  timer = setTimeout(() => { const text = $('cg-paste').value; if (text.trim()) load(text, 'pasted-points.csv'); else { generation++; reset(); $('cg-status').textContent = ''; } }, 250);
});
$('cg-demo').addEventListener('click', () => { $('cg-paste').value = ''; load(CSV_GPX_SAMPLES.csv.text, CSV_GPX_SAMPLES.csv.name); });
$('cg-demo-gpx').addEventListener('click', () => { $('cg-paste').value = ''; load(CSV_GPX_SAMPLES.gpx.text, CSV_GPX_SAMPLES.gpx.name); });
$('cg-clear').addEventListener('click', () => { generation++; reset(); $('cg-paste').value = ''; $('cg-error').hidden = true; $('cg-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
const reload = () => { if (source?.kind === 'csv') load(source.text, source.name); };
$('cg-delimiter').addEventListener('change', reload);
$('cg-header').addEventListener('change', reload);
for (const key of COLUMNS) $(`cg-col-${key}`).addEventListener('change', update);
for (const input of document.querySelectorAll('input[name="cg-mode"]')) input.addEventListener('change', update);
$('cg-name').addEventListener('input', update);
$('cg-download').addEventListener('click', () => {
  if (!out || !out.report.written) return;
  const name = `${safeBase($('cg-name').value || source.name)}.gpx`;
  save(out.gpx, 'application/gpx+xml', name);
  $('cg-status').textContent = `Saved ${name}. Keep your original file too; it was not changed.`;
});
$('cg-gpx-download').addEventListener('click', () => {
  if (!gpxOut) return;
  const name = `${safeBase(source.name)}.csv`;
  save(gpxOut.csv, 'text/csv;charset=utf-8', name);
  $('cg-status').textContent = `Saved ${name}. Open it as text first if your spreadsheet changes the time columns.`;
});
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('cg-tool').hidden = false;
