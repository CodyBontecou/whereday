import { parseKML, plotShapes, formatBounds, formatCoordinate, MAX_BYTES } from './kml-viewer-core.mjs';
import { isZip, readKmz } from './kmz-zip.mjs';
import { KML_VIEWER_SAMPLES } from './kml-viewer-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
const SVG = 'http://www.w3.org/2000/svg';
const COUNT_LABELS = [['placemarks', 'Placemarks'], ['points', 'Points'], ['lineStrings', 'LineStrings'], ['polygons', 'Polygons'], ['innerRings', 'Inner rings (holes)'], ['linearRings', 'Stand-alone LinearRings'], ['multiGeometries', 'MultiGeometry'], ['gxTracks', 'gx:Tracks'], ['gxMultiTracks', 'gx:MultiTracks'], ['withTimes', 'gx:Track times'], ['folders', 'Folders'], ['documents', 'Documents']];
let model = null, generation = 0, selected = null;

function size(bytes) { return bytes < 1024 ? `${bytes} B` : bytes < 1048576 ? `${(bytes / 1024).toFixed(1)} KiB` : `${(bytes / 1048576).toFixed(1)} MiB`; }

function select(id) {
  selected = selected === id ? null : id;
  for (const node of document.querySelectorAll('#kv-plot [data-id]')) {
    const on = Number(node.dataset.id) === selected;
    node.classList.toggle('active', on); node.classList.toggle('dim', selected !== null && !on);
  }
  for (const b of document.querySelectorAll('.kv-pick')) b.setAttribute('aria-pressed', String(Number(b.dataset.id) === selected));
  for (const row of $('kv-rows').children) row.classList.toggle('kv-selected', Number(row.dataset.id) === selected);
  const detail = $('kv-detail'); detail.replaceChildren();
  detail.hidden = selected === null;
  if (selected === null) return;
  const p = model.placemarks[selected];
  detail.append(el('h3', p.name || '(unnamed placemark)'), el('p', `${p.type}. ${number.format(p.coordinateCount)} coordinates, shown as longitude,latitude[,altitude].`, 'gpx-small'));
  if (p.folderPath.length) detail.append(el('p', `In: ${p.folderPath.join(' › ')}`, 'gpx-small'));
  if (p.description) detail.append(el('p', p.description, 'kv-desc'));
  const all = p.parts.flatMap(part => part.coords);
  const shown = all.slice(0, 50).map(formatCoordinate).join('\n') + (all.length > 50 ? `\n… ${number.format(all.length - 50)} more` : '');
  if (all.length) { const pre = el('pre', shown, 'kv-coords'); pre.tabIndex = 0; pre.setAttribute('aria-label', 'Coordinates of the selected placemark'); detail.append(pre); }
}

function pickButton(p) {
  const b = el('button', p.name || '(unnamed)', 'kv-pick'); b.type = 'button'; b.dataset.id = p.id; b.setAttribute('aria-pressed', 'false');
  b.addEventListener('click', () => select(p.id)); return b;
}

function renderTree(node, list) {
  for (const child of node.children) {
    const li = el('li');
    if (child.type === 'Placemark') { const p = model.placemarks[child.id]; li.append(pickButton(p), document.createTextNode(` ${p.type}`)); }
    else {
      li.append(el('strong', `${child.type}: ${child.name || '(unnamed)'}`));
      if (child.description) li.append(el('p', child.description, 'kv-desc'));
      const sub = el('ul'); renderTree(child, sub); if (sub.children.length) li.append(sub);
    }
    list.append(li);
  }
}

function render(info) {
  $('kv-results').hidden = !model;
  $('kv-clear').disabled = !model;
  $('kv-plot').replaceChildren(); $('kv-rows').replaceChildren(); $('kv-tree').replaceChildren();
  selected = null; $('kv-detail').hidden = true;
  if (!model) return;
  $('kv-name').textContent = model.name || info.name;
  $('kv-source').textContent = info.source;
  const counts = $('kv-counts'); counts.replaceChildren();
  for (const [key, label] of COUNT_LABELS) { if (!model.counts[key] && !['placemarks', 'points', 'lineStrings', 'polygons', 'gxTracks', 'folders'].includes(key)) continue; const row = el('div'); row.append(el('dt', label), el('dd', number.format(model.counts[key]))); counts.append(row); }
  const svg = $('kv-plot');
  const shapes = plotShapes(model);
  for (const s of shapes) {
    const path = s.points.map(p => p.map(n => n.toFixed(2)).join(',')).join(' ');
    let node;
    if (s.kind === 'point') { node = document.createElementNS(SVG, 'circle'); node.setAttribute('cx', s.points[0][0].toFixed(2)); node.setAttribute('cy', s.points[0][1].toFixed(2)); node.setAttribute('r', '5'); node.setAttribute('class', 'kv-dot'); }
    else { node = document.createElementNS(SVG, s.kind === 'polygon' ? 'polygon' : 'polyline'); node.setAttribute('points', path); node.setAttribute('class', s.kind === 'polygon' ? (s.inner ? 'kv-poly inner' : 'kv-poly') : 'kv-line'); }
    node.dataset.id = s.id;
    node.addEventListener('click', () => select(s.id));
    svg.append(node);
  }
  $('kv-plot-note').textContent = shapes.length ? 'North is up. Points are dots, lines are polylines, polygons are outlined and holes are dashed. No map tiles or streets; click a shape or a name to highlight it.' : 'Nothing in this file could be drawn.';
  renderTree(model.tree, $('kv-tree'));
  for (const p of model.placemarks) {
    const row = el('tr'); row.dataset.id = p.id;
    const th = el('th'); th.scope = 'row'; th.append(pickButton(p));
    const times = p.firstTime ? `${p.firstTime} → ${p.lastTime}` : '';
    row.append(th, el('td', p.type), el('td', number.format(p.coordinateCount)), el('td', times), el('td', formatBounds(p.bounds)));
    $('kv-rows').append(row);
  }
  const sk = $('kv-skipped'); sk.replaceChildren();
  for (const s of model.skipped) { const li = el('li'); li.append(el('strong', `${number.format(s.count)} × ${s.label}`), document.createTextNode(`: ${s.reason}`)); sk.append(li); }
  if (!model.skipped.length) sk.append(el('li', 'Nothing was skipped.'));
  const notes = $('kv-notes'); notes.replaceChildren(); for (const n of model.notes) notes.append(el('li', n)); notes.hidden = !model.notes.length;
  const entries = $('kv-entries'); entries.replaceChildren();
  $('kv-entries-box').hidden = !info.others;
  for (const o of info.others || []) entries.append(el('li', `${o.name} (${size(o.size)}), not drawn`));
  if (info.others && !info.others.length) entries.append(el('li', 'No other files inside.'));
}

function show(text, info, status) {
  try {
    model = parseKML(text);
    render(info);
    $('kv-status').textContent = status;
  } catch (error) { model = null; render({}); $('kv-status').textContent = error instanceof Error && error.message ? error.message : 'This file could not be read.'; }
}

function fromBase64(b64) { const bin = atob(b64); const out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out; }

async function open(bytes, name, token) {
  try {
    if (isZip(bytes)) {
      const kmz = await readKmz(bytes);
      if (token !== generation) return;
      const note = kmz.kmlCount > 1 ? ` The archive holds ${kmz.kmlCount} KML files; only ${kmz.kmlName} is opened.` : '';
      show(kmz.kmlText, { name, source: `${name} → ${kmz.kmlName} (KMZ)`, others: kmz.others }, `Opened ${kmz.kmlName} from ${name}. Nothing left this device.${note}`);
    } else {
      const text = new TextDecoder('utf-8').decode(bytes);
      if (token !== generation) return;
      show(text, { name, source: `${name} (KML)` }, `Opened ${name}. Nothing left this device.`);
    }
  } catch (error) { if (token !== generation) return; model = null; render({}); $('kv-status').textContent = error instanceof Error && error.message ? error.message : 'This file could not be read.'; }
}

async function load(file) {
  const token = ++generation;
  if (file.size === 0) { model = null; render({}); $('kv-status').textContent = 'This file is empty. Export it again from the app that made it.'; return; }
  if (file.size > MAX_BYTES) { model = null; render({}); $('kv-status').textContent = 'This file is over 10 MiB. Open a smaller file.'; return; }
  $('kv-status').textContent = 'Reading the file on this device…';
  try { await open(new Uint8Array(await file.arrayBuffer()), file.name, token); }
  catch { if (token === generation) { model = null; render({}); $('kv-status').textContent = 'This file could not be read.'; } }
}

$('kv-file').addEventListener('change', async event => { const file = event.target.files[0]; if (file) await load(file); $('kv-file').value = ''; });
const drop = $('kv-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); const file = event.dataTransfer?.files?.[0]; if (file) load(file); });
for (const [id, key] of [['kv-demo-kml', 'kml'], ['kv-demo-kmz', 'kmz'], ['kv-demo-stored', 'kmzStored']]) {
  $(id).addEventListener('click', () => {
    const token = ++generation, sample = KML_VIEWER_SAMPLES[key];
    if (key === 'kml') show(sample.text, { name: sample.name, source: `${sample.name} (synthetic KML)` }, 'Loaded the synthetic KML example. No personal data.');
    else open(fromBase64(sample.base64), sample.name, token).then(() => { if (model && token === generation) $('kv-status').textContent = `Loaded the synthetic ${key === 'kmz' ? 'deflate' : 'stored'} KMZ example. No personal data.`; });
  });
}
$('kv-clear').addEventListener('click', () => { generation++; model = null; render({}); $('kv-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
$('kv-tool').hidden = false;
render({});
