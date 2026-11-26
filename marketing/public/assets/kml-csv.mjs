import { parseKmlForCsv, buildRows, toCSVText, describeKmzEntries, detectInput, decodeText, convertCSVToKML, kmlMapping, isOwnExport, defaultKmlMode, buildKmz, REASONS, MAX_BYTES } from './kml-csv-core.mjs';
import { readCSV } from './csv-gpx-core.mjs';
import { baseName } from './kml-gpx-core.mjs';
import { readKmz } from './kmz-zip.mjs';
import { KML_CSV_SAMPLES } from './kml-csv-demo.mjs';

const $ = id => document.getElementById(id);
const fmt = new Intl.NumberFormat('en-US');
const count = (n, label, plural = label + 's') => `${fmt.format(n)} ${n === 1 ? label : plural}`;
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
const SVG = 'http://www.w3.org/2000/svg';
const COLUMNS = ['lat', 'lon', 'name', 'ele', 'time'];
const PREVIEW_ROWS = 8, SKIPPED_ROWS_SHOWN = 20;
let source = null, model = null, kmlOut = null, table = null, csvOut = null, downloadURL = null, generation = 0;

function reset() {
  source = null; model = null; kmlOut = null; table = null; csvOut = null;
  $('kc-kml').hidden = true; $('kc-csv').hidden = true; $('kc-error').hidden = true;
  if (downloadURL) { URL.revokeObjectURL(downloadURL); downloadURL = null; }
}
function fail(message) {
  reset();
  $('kc-error').textContent = message; $('kc-error').hidden = false; $('kc-status').textContent = '';
}
const messageOf = (e, fallback) => (e instanceof Error && e.message ? e.message : fallback);

function drawPlot(svg, paths) {
  svg.replaceChildren();
  paths.forEach((path, i) => {
    const cls = i % 2 ? 'alt' : '';
    if (path.length > 1) { const p = document.createElementNS(SVG, 'polyline'); p.setAttribute('points', path.map(pt => pt.map(n => n.toFixed(1)).join(',')).join(' ')); if (cls) p.setAttribute('class', cls); svg.append(p); }
    else if (path.length === 1) { const c = document.createElementNS(SVG, 'circle'); c.setAttribute('cx', path[0][0].toFixed(1)); c.setAttribute('cy', path[0][1].toFixed(1)); c.setAttribute('r', 4); if (cls) c.setAttribute('class', cls); svg.append(c); }
  });
}
function fillTable(body, rows) {
  body.replaceChildren();
  for (const [k, v] of rows) { const tr = el('tr'); const th = el('th', k); th.scope = 'row'; tr.append(th, el('td', v)); body.append(tr); }
}
function save(contents, type, filename) {
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([contents], { type }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = filename; document.body.append(a); a.click(); a.remove();
}

// ---- KML / KMZ to CSV ----
function showKml() {
  const mode = document.querySelector('input[name="kc-mode"]:checked').value;
  kmlOut = buildRows(model, { mode, keepHtml: $('kc-html').checked });
  const r = kmlOut.report;
  r.skipped.push(...source.kmzSkipped);
  const geo = Object.entries(r.geometries).map(([k, n]) => `${fmt.format(n)} ${k}`).join(', ') || 'none';
  fillTable($('kc-kml-table'), [
    ['Placemarks read', `${fmt.format(r.placemarks)} (${fmt.format(r.placemarksWithGeometry)} with geometry)`],
    ['Geometries', geo],
    ['MultiGeometry', fmt.format(r.multiGeometries)],
    ['Polygons written', fmt.format(r.polygons)],
    ['Rows written', fmt.format(r.rows)],
    ['Line vertices expanded', mode === 'vertex' ? fmt.format(r.verticesExpanded) : 'not in this mode'],
    ['ExtendedData columns', r.extColumns.length ? `${fmt.format(r.extColumns.length)}: ${r.extColumns.join(', ')}` : '0'],
    ['First time (UTC)', r.firstTime || 'none'], ['Last time (UTC)', r.lastTime || 'none'],
  ]);
  const notes = $('kc-kml-notes'); notes.replaceChildren();
  for (const n of r.notes) notes.append(el('li', n));
  const sk = $('kc-kml-skipped'); sk.replaceChildren();
  for (const s of r.skipped) { const li = el('li'); li.append(el('strong', `${fmt.format(s.count)} × ${s.label}`), document.createTextNode(`: ${s.reason}`)); sk.append(li); }
  if (!r.skipped.length) sk.append(el('li', 'Nothing was skipped.'));
  const head = $('kc-kml-head'); head.replaceChildren(); const tr = el('tr');
  for (const c of kmlOut.columns) { const th = el('th', c); th.scope = 'col'; tr.append(th); }
  head.append(tr);
  const body = $('kc-kml-rows'); body.replaceChildren();
  for (const row of kmlOut.rows.slice(0, PREVIEW_ROWS)) { const t = el('tr'); for (const c of kmlOut.columns) t.append(el('td', row[c])); body.append(t); }
  drawPlot($('kc-kml-plot'), r.plot);
  $('kc-kml-download').disabled = !r.rows;
  const name = `${baseName(source.name)}.csv`;
  $('kc-kml-filename').textContent = r.rows ? name : 'Nothing to download yet.';
  $('kc-kml').hidden = false;
  $('kc-status').textContent = `${source.name}: ${count(r.rows, 'row')} ready from ${count(r.placemarks, 'placemark')}. Everything was read on this device.`;
}

// ---- CSV to KML ----
function fillSelect(id, headers, selected, optional) {
  const select = $(id); select.replaceChildren();
  if (optional) { const none = el('option', '(none)'); none.value = '-1'; select.append(none); }
  headers.forEach((h, i) => { const o = el('option', h); o.value = String(i); select.append(o); });
  select.value = String(selected);
}
function loadCsv(text, name) {
  source = { text, name, kind: 'csv' };
  try { table = readCSV(text, { delimiter: $('kc-delimiter').value, header: $('kc-header').value }); }
  catch (e) { fail(messageOf(e, 'This text could not be read.')); return; }
  const mapping = kmlMapping(table);
  for (const key of COLUMNS) fillSelect(`kc-col-${key}`, table.headers, mapping[key], key !== 'lat' && key !== 'lon');
  const own = isOwnExport(table);
  $('kc-own-label').hidden = !own;
  document.querySelector('input[name="kc-kmode"][value="placemarks"]').disabled = !own;
  document.querySelector(`input[name="kc-kmode"][value="${defaultKmlMode(table)}"]`).checked = true;
  $('kc-name').value = baseName(name);
  $('kc-csv').hidden = false;
  updateCsv();
}
function currentMapping() {
  const m = { ...kmlMapping(table) };
  for (const key of COLUMNS) m[key] = Number($(`kc-col-${key}`).value);
  return m;
}
function updateCsv() {
  if (!table) return;
  const mode = document.querySelector('input[name="kc-kmode"]:checked').value;
  const warnings = $('kc-csv-warnings'); warnings.replaceChildren();
  $('kc-error').hidden = true;
  try { csvOut = convertCSVToKML(table, { mapping: currentMapping(), mode, fileName: $('kc-name').value || 'points' }); }
  catch (e) { csvOut = null; $('kc-csv-output').hidden = true; $('kc-error').textContent = messageOf(e, 'This CSV could not be converted.'); $('kc-error').hidden = false; $('kc-csv-download').disabled = true; $('kc-csv-download-kmz').disabled = true; return; }
  const r = csvOut.report;
  $('kc-csv-output').hidden = false;
  if (r.assumedUtc) warnings.append(el('p', `${count(r.assumedUtc, 'time')} had no time zone, so ${r.assumedUtc === 1 ? 'it was' : 'they were'} treated as UTC.`, 'csvgpx-warn'));
  if (r.badTime) warnings.append(el('p', `${count(r.badTime, 'time')} could not be read as an ISO 8601 date and time (for example 2000-01-01T12:00:00Z) and ${r.badTime === 1 ? 'was' : 'were'} left out.`, 'csvgpx-warn'));
  fillTable($('kc-csv-table'), [
    ['Rows read', fmt.format(r.rowsRead)], ['Rows written', fmt.format(r.written)],
    ['Rows skipped', fmt.format(r.skipped.length)], ['Placemarks written', fmt.format(r.placemarks)],
    ['ExtendedData columns', r.extColumns.length ? `${fmt.format(r.extColumns.length)}: ${r.extColumns.join(', ')}` : '0'],
  ]);
  const notes = $('kc-csv-notes'); notes.replaceChildren();
  for (const [key, n] of Object.entries(r.skippedByReason)) if (n) notes.append(el('li', `${count(n, 'row')} skipped: ${REASONS[key]}.`));
  if (r.skipped.length) {
    const shown = r.skipped.slice(0, SKIPPED_ROWS_SHOWN).map(s => s.row).join(', ');
    notes.append(el('li', `Skipped row numbers (the header is row 1): ${shown}${r.skipped.length > SKIPPED_ROWS_SHOWN ? `, and ${fmt.format(r.skipped.length - SKIPPED_ROWS_SHOWN)} more` : ''}.`));
  }
  for (const n of r.notes) notes.append(el('li', n));
  if (!r.written) notes.append(el('li', 'No row had usable coordinates. Check the latitude and longitude columns above.'));
  drawPlot($('kc-csv-plot'), r.plot);
  $('kc-csv-download').disabled = !r.written; $('kc-csv-download-kmz').disabled = !r.written;
  $('kc-csv-filename').textContent = r.written ? `${baseName($('kc-name').value || source.name)}.kml or .kmz` : 'Nothing to download yet.';
  $('kc-status').textContent = `${source.name}: ${count(r.written, 'row')} ready, ${count(r.skipped.length, 'row')} skipped. Everything was read on this device.`;
}

// ---- Loading ----
async function openBytes(bytes, name, token) {
  const type = detectInput(bytes);
  if (type === 'csv') { const text = decodeText(bytes); if (token !== generation) return; loadCsv(text, name); return; }
  let text, kmzSkipped = [], where = `${name} (KML)`, extra = '';
  if (type === 'kmz') {
    const kmz = await readKmz(bytes);
    if (token !== generation) return;
    text = kmz.kmlText; kmzSkipped = describeKmzEntries(kmz.others); where = `${name} → ${kmz.kmlName} (KMZ)`;
    if (kmz.kmlCount > 1) extra = ` The archive holds ${kmz.kmlCount} KML files; only ${kmz.kmlName} is converted.`;
  } else text = decodeText(bytes);
  const parsed = parseKmlForCsv(text);
  source = { name, kind: 'kml', kmzSkipped, where };
  model = parsed;
  $('kc-source').textContent = `${where}. Detected from the file’s content.${extra}`;
  showKml();
}
async function addBytes(bytes, name) {
  const token = ++generation;
  reset();
  if (bytes.length === 0) { fail('This file is empty. Export it again from the app that made it.'); return; }
  if (bytes.length > MAX_BYTES) { fail('This file is over 10 MiB. Split it into smaller files and try again.'); return; }
  $('kc-status').textContent = `Reading ${name} on this device…`;
  try { await openBytes(bytes, name, token); }
  catch (e) { if (token === generation) fail(messageOf(e, 'This file could not be read.')); }
}
async function addFile(file) {
  if (file.size > MAX_BYTES) { ++generation; fail('This file is over 10 MiB. Split it into smaller files and try again.'); return; }
  try { await addBytes(new Uint8Array(await file.arrayBuffer()), file.name); }
  catch { fail('This file could not be read. Try saving a local copy first.'); }
}
const fromBase64 = b64 => { const bin = atob(b64); const out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out; };
const sample = (key, binary) => addBytes(binary ? fromBase64(KML_CSV_SAMPLES[key].base64) : new TextEncoder().encode(KML_CSV_SAMPLES[key].text), KML_CSV_SAMPLES[key].name);

$('kc-file').addEventListener('change', e => { if (e.target.files[0]) addFile(e.target.files[0]); e.target.value = ''; });
const drop = $('kc-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, e => { e.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', e => { e.preventDefault(); if (e.dataTransfer?.files?.[0]) addFile(e.dataTransfer.files[0]); });
$('kc-demo-kml').addEventListener('click', () => sample('kml', false));
$('kc-demo-kmz').addEventListener('click', () => sample('kmz', true));
$('kc-demo-stored').addEventListener('click', () => sample('kmzStored', true));
$('kc-demo-csv').addEventListener('click', () => sample('csv', false));
$('kc-clear').addEventListener('click', () => { generation++; reset(); $('kc-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
for (const input of document.querySelectorAll('input[name="kc-mode"], #kc-html')) input.addEventListener('change', () => { if (model) showKml(); });
const reload = () => { if (source?.kind === 'csv') { const { text, name } = source; reset(); loadCsv(text, name); } };
$('kc-delimiter').addEventListener('change', reload);
$('kc-header').addEventListener('change', reload);
for (const key of COLUMNS) $(`kc-col-${key}`).addEventListener('change', updateCsv);
for (const input of document.querySelectorAll('input[name="kc-kmode"]')) input.addEventListener('change', updateCsv);
$('kc-name').addEventListener('input', updateCsv);
$('kc-kml-download').addEventListener('click', () => {
  if (!kmlOut || !kmlOut.rows.length) return;
  const name = `${baseName(source.name)}.csv`;
  save(toCSVText(kmlOut, { bom: $('kc-bom').checked }), 'text/csv;charset=utf-8', name);
  $('kc-status').textContent = `Saved ${name}. Keep your original file too; it was not changed.`;
});
$('kc-csv-download').addEventListener('click', () => {
  if (!csvOut || !csvOut.report.written) return;
  const name = `${baseName($('kc-name').value || source.name)}.kml`;
  save(csvOut.kml, 'application/vnd.google-earth.kml+xml', name);
  $('kc-status').textContent = `Saved ${name}. Keep your original file too; it was not changed.`;
});
$('kc-csv-download-kmz').addEventListener('click', () => {
  if (!csvOut || !csvOut.report.written) return;
  const name = `${baseName($('kc-name').value || source.name)}.kmz`;
  save(buildKmz(csvOut.kml), 'application/vnd.google-earth.kmz', name);
  $('kc-status').textContent = `Saved ${name}, a KMZ with one file, doc.kml. Keep your original file too.`;
});
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('kc-tool').hidden = false;
