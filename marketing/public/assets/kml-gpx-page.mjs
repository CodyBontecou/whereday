import { convertFile, outputName, COUNT_LABELS, MAX_BYTES } from './kml-gpx.mjs';
import { KML_EXAMPLE } from './kml-gpx-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
let generation = 0, current = null, result = null, downloadURL = null;

function el(tag, text, className) { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; }
const utc = iso => iso ? iso.slice(0, 19).replace('T', ' ') + ' UTC' : 'None';

function table(caption, counts) {
  const wrap = el('div', undefined, 'table-scroll'); wrap.setAttribute('role', 'region'); wrap.setAttribute('aria-label', caption); wrap.tabIndex = 0;
  const t = el('table', undefined, 'merge-counts');
  const head = el('thead'), tr = el('tr'); const th = el('th', caption); th.scope = 'col'; const n = el('th', 'Count'); n.scope = 'col'; tr.append(th, n); head.append(tr); t.append(head);
  const body = el('tbody');
  for (const [key, value] of Object.entries(counts)) { const row = el('tr'); const h = el('th', COUNT_LABELS[key] || key); h.scope = 'row'; row.append(h, el('td', number.format(value))); body.append(row); }
  t.append(body); wrap.append(t); return wrap;
}

function render(r, name) {
  const rep = r.report, toGPX = rep.direction === 'kml-to-gpx';
  $('kg-direction').textContent = `${r.sourceFormat} → ${toGPX ? 'GPX' : 'KML'}${r.sourceFormat === 'KMZ' ? ' (the KML inside the KMZ)' : ''}`;
  const tables = $('kg-tables'); tables.replaceChildren(
    table(`In your ${r.sourceFormat === 'KMZ' ? 'KMZ' : r.sourceFormat}`, rep.input), table(`In the ${toGPX ? 'GPX' : 'KML'} you download`, rep.output));
  $('kg-times').textContent = rep.firstTime ? `First timestamp ${utc(rep.firstTime)}; last timestamp ${utc(rep.lastTime)}.` : 'No timestamps in this file.';
  const notes = $('kg-notes'); notes.replaceChildren();
  if (rep.rejected) notes.append(el('li', `${number.format(rep.rejected)} coordinate${rep.rejected === 1 ? '' : 's'} rejected (missing, not a number, out of range, or unpaired in a gx:Track) and not written.`, 'merge-warning'));
  for (const n of rep.notes) notes.append(el('li', n, 'merge-warning'));
  if (!notes.children.length) notes.append(el('li', 'No rejected coordinates and no warnings.'));
  const skipped = $('kg-skipped'); skipped.replaceChildren();
  for (const s of rep.skipped) { const li = el('li'); li.append(el('strong', `${number.format(s.count)} × ${s.item}`), document.createTextNode(`: ${s.reason}`)); skipped.append(li); }
  if (!rep.skipped.length) skipped.append(el('li', 'Nothing was skipped.'));
  $('kg-filename').textContent = `File name: ${outputName(name, r.extension)}`;
  $('kg-viewer').hidden = !toGPX;
  $('kg-results').hidden = false; $('kg-download').disabled = false;
}

function clearResult() {
  result = null; $('kg-results').hidden = true; $('kg-download').disabled = true; $('kg-filename').textContent = '';
}

async function convert() {
  if (!current) return;
  const token = ++generation;
  try {
    const r = await convertFile(current.bytes, { keepTimestamps: $('kg-gx').checked });
    if (token !== generation) return;
    result = r; render(r, current.name);
    $('kg-status').textContent = `Converted ${current.name} (${r.sourceFormat} → ${r.extension.toUpperCase()}) on this device. Check the report, then download.`;
  } catch (error) {
    if (token !== generation) return;
    clearResult(); $('kg-status').textContent = error instanceof Error && error.message ? error.message : 'This file could not be converted.';
  }
}

async function load(file) {
  const token = ++generation; clearResult(); current = null;
  if (file.size > MAX_BYTES) { $('kg-status').textContent = 'Choose a file of 10 MiB or less.'; return; }
  $('kg-status').textContent = 'Reading the file on this device…';
  try { const bytes = new Uint8Array(await file.arrayBuffer()); if (token !== generation) return; current = { name: file.name, bytes }; await convert(); }
  catch { if (token === generation) $('kg-status').textContent = 'This file could not be read. Try saving a local copy in Files first.'; }
}

$('kg-file').addEventListener('change', event => { const f = event.target.files[0]; if (f) load(f); });
const drop = $('kg-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); const f = event.dataTransfer?.files?.[0]; if (f) load(f); });
$('kg-demo').addEventListener('click', async () => {
  generation++; $('kg-file').value = '';
  current = { name: KML_EXAMPLE.name, bytes: new TextEncoder().encode(KML_EXAMPLE.text) };
  await convert();
  if (result) $('kg-status').textContent = 'Converted the synthetic KML example (invented points, no personal data). Check the report, then download.';
});
$('kg-clear').addEventListener('click', () => {
  generation++; current = null; clearResult(); $('kg-file').value = '';
  $('kg-status').textContent = 'Cleared from this page. Your original file has not changed.';
});
$('kg-gx').addEventListener('change', () => convert());
$('kg-download').addEventListener('click', () => {
  if (!result) return;
  const name = outputName(current.name, result.extension);
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([result.output], { type: `${result.mime};charset=utf-8` }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = name; document.body.append(a); a.click(); a.remove();
  $('kg-status').textContent = `Saved ${name}. Keep your original file too.`;
});
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('kg-tool').hidden = false;
