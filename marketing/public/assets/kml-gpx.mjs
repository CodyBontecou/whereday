import { convert, detectFormat, LABELS, MAX_BYTES } from './kml-gpx-core.mjs';
import { KML_GPX_SAMPLES } from './kml-gpx-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
let source = null, result = null, generation = 0, downloadURL = null;

function figures(target, values) {
  target.replaceChildren();
  for (const [key, value] of Object.entries(values)) {
    const row = el('div'); row.append(el('dt', LABELS[key] || key), el('dd', number.format(value))); target.append(row);
  }
}

function render() {
  $('kg-results').hidden = !result;
  $('kg-download').disabled = !result;
  if (!result) { $('kg-filename').textContent = ''; return; }
  const toGPX = result.direction === 'kml-to-gpx';
  $('kg-direction').textContent = toGPX ? 'Detected KML. Converted to GPX 1.1.' : 'Detected GPX. Converted to KML 2.2.';
  figures($('kg-in'), result.input);
  figures($('kg-out'), result.output);
  $('kg-times').textContent = result.firstTime ? `Timestamps in the result run from ${result.firstTime} to ${result.lastTime} (UTC).` : 'The result has no timestamps.';
  const list = $('kg-skipped'); list.replaceChildren();
  for (const s of result.skipped) { const li = el('li'); li.append(el('strong', `${number.format(s.count)} × ${s.label}`), document.createTextNode(`: ${s.reason}`)); list.append(li); }
  if (!result.skipped.length) list.append(el('li', 'Nothing was skipped.'));
  const notes = $('kg-notes'); notes.replaceChildren();
  for (const n of result.notes) notes.append(el('li', n));
  notes.hidden = !result.notes.length;
  $('kg-viewer').hidden = !toGPX;
  $('kg-filename').textContent = `File name: ${result.filename}`;
}

function run(announce) {
  result = null;
  if (!source) { render(); return; }
  try {
    result = convert(source.text, source.name, { keepTimestamps: $('kg-keep').checked });
    $('kg-status').textContent = announce || `Converted ${source.name}. Nothing left this device.`;
  } catch (error) {
    $('kg-status').textContent = error instanceof Error && error.message ? error.message : 'This file could not be converted.';
  }
  render();
}

async function load(file) {
  const token = ++generation;
  source = null;
  if (file.size === 0) { run(); $('kg-status').textContent = 'This file is empty. Export it again from the app that made it.'; return; }
  if (file.size > MAX_BYTES) { run(); $('kg-status').textContent = 'This file is over 10 MiB. Export a shorter date range and try again.'; return; }
  $('kg-status').textContent = 'Reading the file on this device…';
  try {
    const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
    if (head[0] === 0x50 && head[1] === 0x4b) { run(); $('kg-status').textContent = 'This looks like a KMZ (zip) file, which the converter does not read. Have a KMZ? Open it in the KML/KMZ viewer at /tools/kml-viewer/.'; return; }
    const text = await file.text();
    if (token !== generation) return;
    detectFormat(text);
    source = { text, name: file.name };
    run();
  } catch (error) { if (token === generation) { run(); $('kg-status').textContent = error instanceof Error && error.message ? error.message : 'This file could not be read.'; } }
}

$('kg-file').addEventListener('change', async event => { const file = event.target.files[0]; if (file) await load(file); $('kg-file').value = ''; });
const drop = $('kg-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); const file = event.dataTransfer?.files?.[0]; if (file) load(file); });
for (const [id, key] of [['kg-demo-kml', 'kml'], ['kg-demo-gpx', 'gpx']]) {
  $(id).addEventListener('click', () => { generation++; source = KML_GPX_SAMPLES[key]; run(`Loaded the synthetic ${key.toUpperCase()} example. No personal data.`); });
}
$('kg-clear').addEventListener('click', () => { generation++; source = null; result = null; render(); $('kg-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
$('kg-keep').addEventListener('change', () => run());
$('kg-download').addEventListener('click', () => {
  if (!result) return;
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([result.text], { type: result.mime }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = result.filename; document.body.append(a); a.click(); a.remove();
  $('kg-status').textContent = `Saved ${result.filename}. Keep your original file too.`;
});
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('kg-tool').hidden = false;
render();
