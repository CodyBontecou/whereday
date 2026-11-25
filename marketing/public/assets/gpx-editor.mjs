import { loadGPX, editGPX, detectSpikes, trackPoints, makeProjection, zipFiles, safeBase, browserZone, isValidZone, formatInZone, toLocalInput, zonedToUtc, MAX_BYTES, DEFAULT_SPEED_KMH } from './gpx-editor-core.mjs';
import { EDITOR_SAMPLE } from './gpx-editor-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const count = (n, label, plural = label + 's') => `${number.format(n)} ${n === 1 ? label : plural}`;
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
const SVG = 'http://www.w3.org/2000/svg';
let doc = null, result = null, error = '', downloadURL = null, history = [], at = 0, projection = null, generation = 0, points = [];

// ---- Settings <-> controls ----------------------------------------------------------------------
const num = (id, fallback) => { const v = Number($(id).value); return $(id).value.trim() !== '' && Number.isFinite(v) ? v : fallback; };
function readSettings() {
  return {
    zone: $('ged-zone').value, start: num('ged-start', 1), end: num('ged-end', doc?.trackPoints || 1),
    spikes: $('ged-spikes').value, spikeRemove: $('ged-spike-remove').checked,
    hide: $('ged-hide').checked, lat: $('ged-lat').value, lon: $('ged-lon').value, radius: num('ged-radius', 300),
    split: document.querySelector('input[name="ged-split"]:checked').value, gap: $('ged-gap').value, gapAction: $('ged-gap-action').value,
    reverse: $('ged-reverse').checked, trackName: $('ged-track-name').value, fileName: $('ged-file-name').value,
  };
}
function writeSettings(s) {
  $('ged-zone').value = s.zone; $('ged-start').value = s.start; $('ged-end').value = s.end;
  $('ged-spikes').value = s.spikes; $('ged-spike-remove').checked = s.spikeRemove;
  $('ged-hide').checked = s.hide; $('ged-lat').value = s.lat; $('ged-lon').value = s.lon; $('ged-radius').value = s.radius;
  document.querySelector(`input[name="ged-split"][value="${s.split}"]`).checked = true;
  $('ged-gap').value = s.gap; $('ged-gap-action').value = s.gapAction;
  $('ged-reverse').checked = s.reverse; $('ged-track-name').value = s.trackName; $('ged-file-name').value = s.fileName;
}
const snapshot = () => JSON.stringify(readSettings());
function commit() {
  const now = snapshot();
  if (now === history[at]) return;
  history = history.slice(0, at + 1); history.push(now); at = history.length - 1;
  syncHistoryButtons();
}
function syncHistoryButtons() { $('ged-undo').disabled = at <= 0; $('ged-redo').disabled = at >= history.length - 1; }
function restore(i) {
  at = i; writeSettings(JSON.parse(history[at])); syncHistoryButtons(); update();
  $('ged-status').textContent = i === 0 ? 'Back at the original file, with no edits.' : 'Step restored. Your file has not changed; edits are applied only when you download.';
}

function zoneOptions() {
  const select = $('ged-zone'); select.replaceChildren();
  let zones = [];
  try { zones = Intl.supportedValuesOf('timeZone'); } catch { zones = []; }
  const mine = browserZone();
  for (const z of new Set(['UTC', mine, ...zones])) { const o = el('option', z === mine ? `${z} (this browser)` : z); o.value = z; select.append(o); }
  select.value = mine;
}

// ---- Rendering ----------------------------------------------------------------------------------
const utc = ms => new Date(ms).toISOString().slice(0, 16).replace('T', ' ');
const span = s => (s ? `${utc(s.first)} to ${utc(s.last)} UTC` : 'No timestamps');
function currentOptions() {
  const s = readSettings();
  const zone = isValidZone(s.zone) ? s.zone : 'UTC';
  const opts = { zoneName: zone, reverse: s.reverse, trackName: s.trackName, fileName: s.fileName };
  if (s.start > 1 || s.end < doc.trackPoints) opts.trim = { start: s.start, end: s.end };
  const kmh = Number(s.spikes);
  if (doc.timed && Number.isFinite(kmh) && kmh > 0) opts.spikes = { thresholdKmh: kmh, remove: s.spikeRemove };
  const lat = Number(s.lat), lon = Number(s.lon);
  if (s.hide && s.lat !== '' && s.lon !== '' && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) opts.zone = { lat, lon, radiusM: s.radius };
  opts.split = { mode: s.split, gapMinutes: Math.max(1, Number(s.gap) || 30), gapAction: s.gapAction };
  return opts;
}

function readouts() {
  const s = readSettings(), pts = trackPoints(doc), zone = isValidZone(s.zone) ? s.zone : 'UTC';
  const say = i => { const p = pts[i - 1]; return p ? `Point ${number.format(i)} of ${number.format(pts.length)} · ${p.time === null ? 'no timestamp' : `${formatInZone(p.time, zone)} (${zone})`}` : ''; };
  $('ged-start-out').textContent = `First point kept: ${say(s.start)}`;
  $('ged-end-out').textContent = `Last point kept: ${say(s.end)}`;
  $('ged-radius-out').textContent = `${number.format(s.radius)} m`;
}

function line(points) { const e = document.createElementNS(SVG, 'polyline'); e.setAttribute('points', points.map(p => p.map(n => n.toFixed(1)).join(',')).join(' ')); return e; }
function drawMap() {
  const svg = $('ged-plot'); svg.replaceChildren();
  const stride = Math.max(1, Math.ceil(points.length / 3000));
  for (const t of doc.tracks) for (const seg of t.segments) {
    const sel = seg.filter((_, i) => i % stride === 0 || i === seg.length - 1);
    if (sel.length > 1) { const e = line(sel.map(projection.project)); e.setAttribute('class', 'ged-orig'); svg.append(e); }
  }
  let i = 0;
  for (const t of result.kept) for (const seg of t.segments) {
    const sel = seg.filter((_, k) => k % stride === 0 || k === seg.length - 1).map(projection.project);
    const e = sel.length > 1 ? line(sel) : null;
    if (e) { e.setAttribute('class', i++ % 2 ? 'ged-out alt' : 'ged-out'); svg.append(e); }
    else if (sel.length === 1) { const c = document.createElementNS(SVG, 'circle'); c.setAttribute('cx', sel[0][0]); c.setAttribute('cy', sel[0][1]); c.setAttribute('r', 3); c.setAttribute('fill', 'currentColor'); svg.append(c); }
  }
  const o = currentOptions();
  if (o.zone) {
    const [x, y] = projection.project(o.zone), c = document.createElementNS(SVG, 'circle');
    c.setAttribute('cx', x); c.setAttribute('cy', y); c.setAttribute('r', Math.max(3, o.zone.radiusM * projection.pixelsPerMeter)); c.setAttribute('class', 'ged-zone'); svg.append(c);
  }
  for (const f of result.flagged.slice(0, 200)) {
    const [x, y] = projection.project(f), c = document.createElementNS(SVG, 'circle');
    c.setAttribute('cx', x); c.setAttribute('cy', y); c.setAttribute('r', 6); c.setAttribute('class', 'ged-flag'); svg.append(c);
  }
}

const delta = (a, b) => (a === b ? '' : ` (${b - a > 0 ? '+' : '−'}${number.format(Math.abs(b - a))})`);
function drawSummary() {
  const b = result.before, a = result.after, r = result.removed;
  const rows = [
    ['Track points', b.trackpoints, a.trackpoints], ['Segments', b.segments, a.segments], ['Tracks', b.tracks, a.tracks],
    ['Waypoints', b.waypoints, a.waypoints], ['Route points', b.routepoints, a.routepoints],
  ];
  const body = $('ged-table'); body.replaceChildren();
  for (const [name, x, y] of rows) { const tr = el('tr'); const th = el('th', name); th.scope = 'row'; tr.append(th, el('td', number.format(x)), el('td', number.format(y) + delta(x, y))); body.append(tr); }
  const tr = el('tr'); const th = el('th', 'Time span'); th.scope = 'row'; tr.append(th, el('td', span(b.span)), el('td', span(a.span))); body.append(tr);
  const files = el('tr'); const fh = el('th', 'Files to download'); fh.scope = 'row'; files.append(fh, el('td', '1 file'), el('td', a.files === 1 ? '1 GPX file' : `${a.files} GPX files in one ZIP`)); body.append(files);

  const notes = $('ged-notes'); notes.replaceChildren();
  const removed = [];
  if (r.trim) removed.push(`${count(r.trim, 'track point')} outside the trim range`);
  if (r.spikes) removed.push(`${count(r.spikes, 'flagged spike')}`);
  if (r.zone) removed.push(`${count(r.zone, 'track point')} inside the hidden zone`);
  if (r.zoneWaypoints) removed.push(`${count(r.zoneWaypoints, 'waypoint')} inside the hidden zone`);
  if (r.zoneRoutePoints) removed.push(`${count(r.zoneRoutePoints, 'route point')} inside the hidden zone`);
  notes.append(el('li', removed.length ? `Removed: ${removed.join('; ')}.` : 'Nothing is removed yet. Every point is kept.'));
  if (result.flagged.length && !readSettings().spikeRemove) notes.append(el('li', `${count(result.flagged.length, 'point')} flagged as possible spikes but kept. Tick “Remove the flagged points” to delete them.`));
  const m = result.meta;
  notes.append(el('li', `Metadata kept: ${m.kept.length ? m.kept.join(', ') : 'none (the file had no name or description)'}. Dropped: ${m.dropped.length ? m.dropped.join(', ') : 'nothing'}. The bounds are recalculated.`));
  notes.append(el('li', 'Times, elevations and vendor extensions on points, tracks and routes are copied exactly as they were. Times are never shifted.'));
  if (result.droppedFields) notes.append(el('li', `${count(result.droppedFields, 'field')} that GPX 1.1 does not allow in that place, or with an unusable value, left out.`));
  if (result.invalidCoordinates) notes.append(el('li', `${count(result.invalidCoordinates, 'point')} with missing or out-of-range coordinates left out.`));
  if (result.version === '1.0') notes.append(el('li', 'The input was GPX 1.0. The output is GPX 1.1.'));
  if (result.reversedTimes) notes.append(el('li', 'The track is reversed but each point keeps its own time, so times now run backwards along the line.'));
  for (const n of result.notes) if (n.kind === 'gap-segments') notes.append(el('li', n.cuts ? `${count(n.cuts, 'segment break')} added at gaps.` : 'No gap was long enough to start a new segment.'));
  if (a.files > 1) for (const f of result.files) notes.append(el('li', `${f.filename}: ${count(f.counts.trackpoints, 'track point')}, ${count(f.counts.segments, 'segment')}`));
}

function drawFlagged() {
  const list = $('ged-flagged'); list.replaceChildren();
  const zone = isValidZone($('ged-zone').value) ? $('ged-zone').value : 'UTC';
  $('ged-spike-summary').textContent = !doc.timed ? 'These points have no timestamps, so speed cannot be checked.' : result.flagged.length ? `${count(result.flagged.length, 'point')} flagged. They are circled in red on the map.` : 'No point is above the threshold.';
  for (const f of result.flagged.slice(0, 50)) list.append(el('li', `Point ${number.format(f.idx)} at ${formatInZone(f.time, zone)}: ${number.format(Math.round(f.kmh))} km/h, ${number.format(Math.round(f.meters))} m from point ${number.format(f.from)}.`));
  if (result.flagged.length > 50) list.append(el('li', `and ${number.format(result.flagged.length - 50)} more.`));
}

function update() {
  if (downloadURL) { URL.revokeObjectURL(downloadURL); downloadURL = null; }
  $('ged-editor').hidden = !doc;
  if (!doc) return;
  readouts();
  error = ''; result = null;
  try { result = editGPX(doc, currentOptions()); } catch (e) { error = e.message; }
  $('ged-error').textContent = error; $('ged-error').hidden = !error;
  $('ged-output').hidden = !result;
  if (!result) { $('ged-download').disabled = true; return; }
  drawMap(); drawSummary(); drawFlagged();
  const empty = !result.after.trackpoints && !result.after.waypoints && !result.after.routepoints;
  $('ged-download').disabled = empty;
  $('ged-download').textContent = result.files.length > 1 ? `Download ${result.files.length} files (ZIP)` : 'Download GPX';
  $('ged-filename').textContent = empty ? 'Nothing is left to download. Loosen an edit.' : result.files.length > 1 ? `${safeBase(result.files[0].filename.replace(/-\d{4}-\d\d-\d\d\.gpx$|-part-\d+\.gpx$/, ''))}.zip` : result.files[0].filename;
}

// ---- Loading ------------------------------------------------------------------------------------
function setup(loaded, defaults) {
  doc = loaded; points = trackPoints(doc);
  const all = [...points, ...doc.waypoints, ...doc.routes.flatMap(r => r.points)];
  const bad = new Set(detectSpikes(doc, DEFAULT_SPEED_KMH).map(f => f.idx));
  // Fit the map to the track without the default spikes, so one bad point does not shrink everything.
  projection = makeProjection(all.filter(p => !bad.has(p.idx)));
  const n = doc.trackPoints, first = points[0] || doc.waypoints[0] || doc.routes[0]?.points[0];
  for (const id of ['ged-start', 'ged-end']) { $(id).min = 1; $(id).max = Math.max(1, n); }
  $('ged-trim').disabled = !n; $('ged-spike-box').disabled = !doc.timed; $('ged-split-box').disabled = !n;
  document.querySelector('input[name="ged-split"][value="day"]').disabled = !doc.timed;
  document.querySelector('input[name="ged-split"][value="gap"]').disabled = !doc.timed;
  for (const id of ['ged-from', 'ged-to']) $(id).disabled = !doc.timed;
  $('ged-time-note').textContent = doc.timed ? '' : 'No point in this file has a timestamp, so trim by point number. Splitting and the speed check need timestamps.';
  const s = {
    zone: defaults.zone || browserZone(), start: 1, end: Math.max(1, n), spikes: String(DEFAULT_SPEED_KMH), spikeRemove: false,
    hide: false, lat: first ? String(first.lat) : '', lon: first ? String(first.lon) : '', radius: 300,
    split: 'none', gap: defaults.gap || '30', gapAction: 'segment', reverse: false, trackName: '', fileName: `${safeBase(doc.fileName)}-edited.gpx`,
  };
  writeSettings(s); history = [snapshot()]; at = 0; syncHistoryButtons();
  syncTimeInputs(); update();
}

function syncTimeInputs() {
  if (!doc?.timed) { $('ged-from').value = ''; $('ged-to').value = ''; return; }
  const s = readSettings(), zone = isValidZone(s.zone) ? s.zone : 'UTC', pts = trackPoints(doc);
  const at = (i, dir) => { for (let k = i - 1; k >= 0 && k < pts.length; k += dir) if (pts[k].time !== null) return pts[k].time; return null; };
  const a = at(s.start, 1), b = at(s.end, -1);
  $('ged-from').value = a === null ? '' : toLocalInput(a, zone); $('ged-to').value = b === null ? '' : toLocalInput(b, zone);
}

function trimByTime() {
  const zone = isValidZone($('ged-zone').value) ? $('ged-zone').value : 'UTC', pts = trackPoints(doc);
  const from = zonedToUtc($('ged-from').value, zone), to = zonedToUtc($('ged-to').value, zone);
  if (from !== null) { const p = pts.find(p => p.time !== null && p.time >= from); $('ged-start').value = p ? p.idx : pts.length; }
  if (to !== null) { const p = pts.findLast(p => p.time !== null && p.time <= to); $('ged-end').value = p ? p.idx : 1; }
}

async function addFile(file) {
  const token = ++generation;
  $('ged-status').textContent = `Reading ${file.name} on this device…`;
  let text;
  if (!/\.gpx$/i.test(file.name) && !/(gpx|xml)/i.test(file.type)) { $('ged-status').textContent = 'Only GPX files are accepted. For KML use the KML to GPX converter first.'; return; }
  if (file.size === 0) { $('ged-status').textContent = 'This file is empty. Export it again from the app that made it.'; return; }
  if (file.size > MAX_BYTES) { $('ged-status').textContent = 'This file is over 10 MiB. Export a shorter date range and try again.'; return; }
  try { text = await file.text(); if (token !== generation) return; setup(loadGPX(text, file.name), {}); }
  catch (e) { if (token !== generation) return; $('ged-status').textContent = e instanceof Error && e.message ? e.message : 'This file could not be read.'; return; }
  $('ged-status').textContent = `Loaded ${file.name}: ${count(doc.trackPoints, 'track point')}, ${count(doc.waypoints.length, 'waypoint')}, ${count(doc.routes.length, 'route')}. Everything was read on this device.`;
  $('ged-file').value = '';
}

function useSample() {
  generation++;
  setup(loadGPX(EDITOR_SAMPLE.text, EDITOR_SAMPLE.name), { zone: 'UTC', gap: '60' });
  $('ged-lat').value = '0'; $('ged-lon').value = '10'; commit();
  $('ged-status').textContent = 'Loaded the synthetic two-day walk (invented points, no personal data). Time zone set to UTC so the counts on this page match.';
}

// ---- Events -------------------------------------------------------------------------------------
$('ged-file').addEventListener('change', e => { if (e.target.files[0]) addFile(e.target.files[0]); });
const drop = $('ged-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, e => { e.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', e => { e.preventDefault(); if (e.dataTransfer?.files?.[0]) addFile(e.dataTransfer.files[0]); });
$('ged-demo').addEventListener('click', useSample);
$('ged-clear').addEventListener('click', () => { generation++; doc = null; result = null; history = []; at = 0; update(); $('ged-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
$('ged-undo').addEventListener('click', () => restore(at - 1));
$('ged-redo').addEventListener('click', () => restore(at + 1));
$('ged-reset').addEventListener('click', () => { restore(0); });

for (const id of ['ged-start', 'ged-end']) {
  $(id).addEventListener('input', () => {
    const s = readSettings();
    if (id === 'ged-start' && s.start > s.end) $('ged-end').value = s.start;
    if (id === 'ged-end' && s.end < s.start) $('ged-start').value = s.end;
    syncTimeInputs(); update();
  });
  $(id).addEventListener('change', commit);
}
for (const id of ['ged-from', 'ged-to']) $(id).addEventListener('change', () => { trimByTime(); const s = readSettings(); if (s.start > s.end) $('ged-end').value = s.start; syncTimeInputs(); update(); commit(); });
$('ged-zone').addEventListener('change', () => { syncTimeInputs(); update(); commit(); });
for (const id of ['ged-spikes', 'ged-spike-remove', 'ged-hide', 'ged-lat', 'ged-lon', 'ged-gap', 'ged-gap-action', 'ged-reverse']) $(id).addEventListener('change', () => { update(); commit(); });
for (const input of document.querySelectorAll('input[name="ged-split"]')) input.addEventListener('change', () => { update(); commit(); });
for (const id of ['ged-track-name', 'ged-file-name', 'ged-lat', 'ged-lon', 'ged-spikes', 'ged-gap']) $(id).addEventListener('input', update);
for (const id of ['ged-track-name', 'ged-file-name']) $(id).addEventListener('change', commit);
$('ged-radius').addEventListener('input', () => { $('ged-hide').checked = true; update(); });
$('ged-radius').addEventListener('change', commit);
$('ged-use-first').addEventListener('click', () => { const p = points[0]; if (p) { $('ged-lat').value = p.lat; $('ged-lon').value = p.lon; $('ged-hide').checked = true; update(); commit(); } });
$('ged-use-last').addEventListener('click', () => { const p = points.at(-1); if (p) { $('ged-lat').value = p.lat; $('ged-lon').value = p.lon; $('ged-hide').checked = true; update(); commit(); } });
$('ged-plot').addEventListener('click', event => {
  if (!doc || !projection) return;
  const svg = $('ged-plot'), box = svg.getBoundingClientRect();
  const c = projection.invert((event.clientX - box.left) * 800 / box.width, (event.clientY - box.top) * 420 / box.height);
  $('ged-lat').value = c.lat.toFixed(5); $('ged-lon').value = c.lon.toFixed(5); $('ged-hide').checked = true;
  update(); commit();
  $('ged-status').textContent = `Hidden zone centre set to ${c.lat.toFixed(5)}, ${c.lon.toFixed(5)}.`;
});
$('ged-download').addEventListener('click', () => {
  if (!result) return;
  const files = result.files;
  let blob, name;
  if (files.length === 1) { blob = new Blob([files[0].xml], { type: 'application/gpx+xml' }); name = files[0].filename; }
  else { blob = new Blob([zipFiles(files.map(f => ({ name: f.filename, text: f.xml })))], { type: 'application/zip' }); name = $('ged-filename').textContent; }
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = downloadURL; a.download = name; document.body.append(a); a.click(); a.remove();
  $('ged-status').textContent = `Saved ${name}. Keep your original file too; it was not changed.`;
});
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });

zoneOptions();
$('ged-tool').hidden = false;
update();
