import { convertBytes, MAX_BYTES } from './kmz-gpx-core.mjs';
import { LABELS } from './kml-gpx-core.mjs';
import { KMZ_GPX_SAMPLES } from './kmz-gpx-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
const DIRECTIONS = {
  'kmz-to-gpx': 'Detected KMZ. Converted the KML inside to GPX 1.1.',
  'kml-to-gpx': 'Detected a plain KML (not zipped). Converted to GPX 1.1.',
  'gpx-to-kmz': 'Detected GPX. Converted to KMZ and KML 2.2.',
};
const BUTTONS = { gpx: 'Download GPX', kmz: 'Download KMZ', kml: 'Download KML' };
let source = null, result = null, generation = 0, downloadURL = null;

function figures(target, values) {
  target.replaceChildren();
  for (const [key, value] of Object.entries(values)) {
    const row = el('div'); row.append(el('dt', LABELS[key] || key), el('dd', number.format(value))); target.append(row);
  }
}

function render() {
  $('kz-results').hidden = !result;
  const buttons = $('kz-buttons'); buttons.replaceChildren();
  $('kz-viewer').hidden = true; $('kz-kmz-note').hidden = true;
  if (!result) { $('kz-filename').textContent = ''; return; }
  $('kz-direction').textContent = DIRECTIONS[result.direction];
  const f = result.files;
  const files = $('kz-files'); files.replaceChildren();
  if (f) {
    files.append(el('li', `KML file read: ${f.kmlUsed}`));
    files.append(el('li', `Other KML files ignored: ${number.format(f.otherKmls)}`));
    files.append(el('li', `Images ignored: ${number.format(f.images)}`));
    files.append(el('li', `Other files ignored: ${number.format(f.otherFiles)}`));
  }
  $('kz-files-box').hidden = !f;
  figures($('kz-in'), result.input);
  figures($('kz-out'), result.output);
  $('kz-times').textContent = result.firstTime ? `Timestamps in the result run from ${result.firstTime} to ${result.lastTime} (UTC).` : 'The result has no timestamps.';
  const list = $('kz-skipped'); list.replaceChildren();
  for (const s of result.skipped) { const li = el('li'); li.append(el('strong', `${number.format(s.count)} × ${s.label}`), document.createTextNode(`: ${s.reason}`)); list.append(li); }
  if (!result.skipped.length) list.append(el('li', 'Nothing was skipped.'));
  const notes = $('kz-notes'); notes.replaceChildren();
  for (const n of result.notes) notes.append(el('li', n));
  notes.hidden = !result.notes.length;
  for (const out of result.outputs) {
    const b = el('button', BUTTONS[out.kind]); b.type = 'button';
    b.addEventListener('click', () => download(out));
    buttons.append(b);
  }
  $('kz-viewer').hidden = result.direction === 'gpx-to-kmz';
  $('kz-kmz-note').hidden = result.direction !== 'gpx-to-kmz';
  $('kz-filename').textContent = `File name: ${result.outputs.map(o => o.filename).join(' or ')}`;
}

function download(out) {
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([out.bytes || out.text], { type: out.mime }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = out.filename; document.body.append(a); a.click(); a.remove();
  $('kz-status').textContent = `Saved ${out.filename}. Keep your original file too.`;
}

function show(message, isError) {
  const box = $('kz-error');
  box.textContent = isError ? message : ''; box.hidden = !isError;
  $('kz-status').textContent = isError ? '' : message;
}

async function run(announce) {
  const token = ++generation;
  result = null;
  if (!source) { render(); return; }
  try {
    const r = await convertBytes(source.bytes, source.name, { keepTimestamps: $('kz-keep').checked });
    if (token !== generation) return;
    result = r;
    show(announce || `Converted ${source.name}. Nothing left this device.`);
  } catch (error) {
    if (token !== generation) return;
    show(error instanceof Error && error.message ? error.message : 'This file could not be converted.', true);
  }
  render();
}

async function load(file) {
  generation++;
  source = null;
  if (file.size > MAX_BYTES) { show('This file is over 10 MiB. Export a smaller file and try again.', true); result = null; render(); return; }
  show('Reading the file on this device…');
  try { source = { bytes: new Uint8Array(await file.arrayBuffer()), name: file.name }; }
  catch { show('This file could not be read.', true); result = null; render(); return; }
  await run();
}

$('kz-file').addEventListener('change', async event => { const file = event.target.files[0]; if (file) await load(file); $('kz-file').value = ''; });
const drop = $('kz-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); const file = event.dataTransfer?.files?.[0]; if (file) load(file); });
$('kz-demo-kmz').addEventListener('click', () => {
  const s = KMZ_GPX_SAMPLES.kmz;
  source = { name: s.name, bytes: Uint8Array.from(atob(s.base64), c => c.charCodeAt(0)) };
  run('Loaded the synthetic KMZ example. No personal data.');
});
$('kz-demo-gpx').addEventListener('click', () => {
  const s = KMZ_GPX_SAMPLES.gpx;
  source = { name: s.name, bytes: new TextEncoder().encode(s.text) };
  run('Loaded the synthetic GPX example. No personal data.');
});
$('kz-clear').addEventListener('click', () => { generation++; source = null; result = null; show('Cleared from this page. Your original file has not changed.'); render(); });
$('kz-keep').addEventListener('change', () => { if (source) run(); });
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('kz-tool').hidden = false;
render();
