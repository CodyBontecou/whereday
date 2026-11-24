// UI for the GeoJSON viewer. Styled by kml-viewer.css, so the element ids and classes keep its kv- prefix.
import { parseGeoJSON, toGPX, toKML, exportable, plotShapes, formatBounds, formatCoordinate, MAX_BYTES } from './geojson-viewer-core.mjs';
import { baseName } from './kml-gpx-core.mjs';
import { GEOJSON_VIEWER_SAMPLE } from './geojson-viewer-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
const SVG = 'http://www.w3.org/2000/svg';
const MAX_ROWS = 500, MAX_SHAPES = 5000;
const TYPE_LABELS = [['Point', 'Points'], ['MultiPoint', 'MultiPoints'], ['LineString', 'LineStrings'], ['MultiLineString', 'MultiLineStrings'], ['Polygon', 'Polygons'], ['MultiPolygon', 'MultiPolygons'], ['GeometryCollection', 'GeometryCollections']];
let model = null, generation = 0, selected = null, fileName = 'geojson', downloadURL = null;

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
  const f = model.features[selected];
  detail.append(el('h3', f.name || '(unnamed feature)'), el('p', `${f.type}. ${number.format(f.coordinateCount)} positions, shown as longitude,latitude[,altitude].`, 'gpx-small'));
  detail.append(el('p', f.firstTime ? `Time: ${f.firstTime} → ${f.lastTime} (UTC)` : 'Time: unknown', 'gpx-small'));
  let props = '';
  try { props = JSON.stringify(f.properties, null, 2); } catch { props = ''; }
  if (props && props !== '{}') { const pre = el('pre', props.length > 2000 ? `${props.slice(0, 2000)}\n… cut short` : props, 'kv-coords'); pre.tabIndex = 0; pre.setAttribute('aria-label', 'Properties of the selected feature, shown as text'); detail.append(el('p', 'Properties (text only):', 'gpx-small'), pre); }
  const all = f.parts.flatMap(part => part.coords);
  if (all.length) { const pre = el('pre', all.slice(0, 50).map(formatCoordinate).join('\n') + (all.length > 50 ? `\n… ${number.format(all.length - 50)} more` : ''), 'kv-coords'); pre.tabIndex = 0; pre.setAttribute('aria-label', 'Positions of the selected feature'); detail.append(pre); }
}

function pickButton(f) {
  const b = el('button', f.name || `(unnamed ${f.id + 1})`, 'kv-pick'); b.type = 'button'; b.dataset.id = f.id; b.setAttribute('aria-pressed', 'false');
  b.addEventListener('click', () => select(f.id)); return b;
}

function listDropped(target, result) {
  target.replaceChildren();
  for (const d of result.dropped) { const li = el('li'); li.append(el('strong', `${number.format(d.count)} × ${d.label}`), document.createTextNode(`: ${d.reason}`)); target.append(li); }
  for (const n of result.notes) target.append(el('li', n));
  if (!target.children.length) target.append(el('li', 'Nothing is dropped.'));
}

function render(info) {
  $('kv-results').hidden = !model;
  $('kv-clear').disabled = !model;
  $('kv-plot').replaceChildren(); $('kv-rows').replaceChildren();
  selected = null; $('kv-detail').hidden = true;
  $('kv-gpx').disabled = true; $('kv-kml').disabled = true;
  for (const id of ['kv-gpx-summary', 'kv-kml-summary']) $(id).replaceChildren();
  if (!model) return;
  $('kv-name').textContent = info.name;
  $('kv-source').textContent = info.source;
  const counts = $('kv-counts'); counts.replaceChildren();
  const add = (label, value) => { const row = el('div'); row.append(el('dt', label), el('dd', value)); counts.append(row); };
  add('Features', number.format(model.counts.features));
  for (const [key, label] of TYPE_LABELS) if (model.counts.byType[key]) add(label, number.format(model.counts.byType[key]));
  if (model.counts.noGeometry) add('No geometry', number.format(model.counts.noGeometry));
  add('Positions drawn', number.format(model.counts.positions));
  add('Features with a time', number.format(model.counts.withTimes));
  $('kv-times').textContent = model.firstTime ? `First time: ${model.firstTime} · last time: ${model.lastTime} (UTC)` : 'Time: unknown. No readable time was found in time, timestamp, datetime, coordTimes or times.';
  const svg = $('kv-plot');
  const all = plotShapes({ bounds: model.bounds, placemarks: model.features });
  const shapes = all.slice(0, MAX_SHAPES);
  for (const s of shapes) {
    let node;
    if (s.kind === 'point') { node = document.createElementNS(SVG, 'circle'); node.setAttribute('cx', s.points[0][0].toFixed(2)); node.setAttribute('cy', s.points[0][1].toFixed(2)); node.setAttribute('r', '5'); node.setAttribute('class', 'kv-dot'); }
    else { node = document.createElementNS(SVG, s.kind === 'polygon' ? 'polygon' : 'polyline'); node.setAttribute('points', s.points.map(p => p.map(n => n.toFixed(2)).join(',')).join(' ')); node.setAttribute('class', s.kind === 'polygon' ? (s.inner ? 'kv-poly inner' : 'kv-poly') : 'kv-line'); }
    node.dataset.id = s.id;
    node.addEventListener('click', () => select(s.id));
    svg.append(node);
  }
  $('kv-plot-note').textContent = !all.length ? 'Nothing in this file could be drawn.' : `North is up. Points are dots, lines are polylines, polygon rings are outlined and holes are dashed. No map tiles or streets; click a shape or a name to highlight it.${all.length > shapes.length ? ` Only the first ${number.format(MAX_SHAPES)} shapes are drawn.` : ''}`;
  for (const f of model.features.slice(0, MAX_ROWS)) {
    const row = el('tr'); row.dataset.id = f.id;
    const th = el('th'); th.scope = 'row'; th.append(pickButton(f));
    row.append(th, el('td', f.type), el('td', number.format(f.coordinateCount)), el('td', f.firstTime ? `${f.firstTime} → ${f.lastTime}` : 'unknown'), el('td', formatBounds(f.bounds)));
    $('kv-rows').append(row);
  }
  $('kv-rows-note').textContent = model.features.length > MAX_ROWS ? `The first ${number.format(MAX_ROWS)} of ${number.format(model.features.length)} features are listed.` : '';
  const sk = $('kv-skipped'); sk.replaceChildren();
  for (const s of model.skipped) { const li = el('li'); li.append(el('strong', `${number.format(s.count)} × ${s.label}`), document.createTextNode(`: ${s.reason}`)); sk.append(li); }
  if (!model.skipped.length) sk.append(el('li', 'Nothing was skipped.'));
  const notes = $('kv-notes'); notes.replaceChildren(); for (const n of model.notes) notes.append(el('li', n)); notes.hidden = !model.notes.length;
  const can = exportable(model);
  $('kv-gpx').disabled = !can.gpx; $('kv-kml').disabled = !can.kml;
  for (const [kind, fn] of [['gpx', toGPX], ['kml', toKML]]) {
    const box = $(`kv-${kind}-summary`);
    if (!can[kind]) { box.append(el('li', `Nothing to export to ${kind.toUpperCase()}: this file has no ${kind === 'gpx' ? 'points or lines' : 'points, lines of two or more positions, or polygons'}.`)); continue; }
    const result = fn(model, 'preview');
    const c = result.counts;
    box.append(el('li', kind === 'gpx' ? `Would write ${number.format(c.waypoints)} waypoints, ${number.format(c.tracks)} tracks, ${number.format(c.segments)} segments and ${number.format(c.trackPoints)} track points (${number.format(c.withTime)} with a time).` : `Would write ${number.format(c.placemarks)} Placemarks: ${number.format(c.points)} Points, ${number.format(c.lineStrings)} LineStrings, ${number.format(c.gxTracks)} gx:Tracks and ${number.format(c.polygons)} Polygons (${number.format(c.withTime)} positions or points with a time).`));
    const dropped = el('ul'); listDropped(dropped, result); box.append(el('li', 'Dropped or changed:'), dropped);
  }
}

function show(text, info, status) {
  try {
    model = parseGeoJSON(text);
    fileName = baseName(info.name);
    render({ ...info, source: `${info.source} · ${model.format}, ${model.rootKinds.join(' and ')}` });
    $('kv-status').textContent = status;
  } catch (error) { model = null; render({}); $('kv-status').textContent = error instanceof Error && error.message ? error.message : 'This file could not be read.'; }
}

async function load(file) {
  const token = ++generation;
  if (file.size === 0) { model = null; render({}); $('kv-status').textContent = 'This file is empty. Export it again from the app that made it.'; return; }
  if (file.size > MAX_BYTES) { model = null; render({}); $('kv-status').textContent = 'This file is over 10 MiB. Open a smaller file.'; return; }
  $('kv-status').textContent = 'Reading the file on this device…';
  try {
    const text = await file.text();
    if (token === generation) show(text, { name: file.name, source: file.name }, `Opened ${file.name}. Nothing left this device.`);
  } catch { if (token === generation) { model = null; render({}); $('kv-status').textContent = 'This file could not be read.'; } }
}

function download(kind) {
  if (!model) return;
  let result;
  try { result = (kind === 'gpx' ? toGPX : toKML)(model, fileName); } catch (error) { $('kv-status').textContent = error.message; return; }
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([result.text], { type: `${result.mime};charset=utf-8` }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = `${fileName}.${kind}`; document.body.append(a); a.click(); a.remove();
  $('kv-status').textContent = `Saved ${fileName}.${kind} from this page. Check the “dropped” list above.`;
}

$('kv-file').addEventListener('change', async event => { const file = event.target.files[0]; if (file) await load(file); $('kv-file').value = ''; });
const drop = $('kv-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); const file = event.dataTransfer?.files?.[0]; if (file) load(file); });
$('kv-open-text').addEventListener('click', () => {
  generation++;
  const text = $('kv-paste').value;
  if (new TextEncoder().encode(text).length > MAX_BYTES) { model = null; render({}); $('kv-status').textContent = 'This text is over 10 MiB. Open a smaller file.'; return; }
  show(text, { name: 'pasted-geojson', source: 'Pasted text' }, 'Opened the pasted text. Nothing left this device.');
});
$('kv-demo').addEventListener('click', () => { generation++; show(GEOJSON_VIEWER_SAMPLE.text, { name: GEOJSON_VIEWER_SAMPLE.name, source: `${GEOJSON_VIEWER_SAMPLE.name} (synthetic)` }, 'Loaded the synthetic example. No personal data.'); });
$('kv-gpx').addEventListener('click', () => download('gpx'));
$('kv-kml').addEventListener('click', () => download('kml'));
$('kv-clear').addEventListener('click', () => { generation++; model = null; render({}); $('kv-paste').value = ''; $('kv-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('kv-tool').hidden = false;
render({});
