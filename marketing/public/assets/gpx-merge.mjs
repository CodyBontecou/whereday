import { plotPaths } from './gpx-core.mjs';
import { readGPX, mergeGPX, orderFiles, MAX_BYTES, MAX_FILES, MAX_TOTAL_BYTES, MAX_TOTAL_POINTS } from './gpx-merge-core.mjs';
import { MERGE_SAMPLES } from './gpx-merge-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const count = (n, label, plural = label + 's') => `${number.format(n)} ${n === 1 ? label : plural}`;
let entries = [], nextId = 1, generation = 0, result = null, downloadURL = null;

function el(tag, text, className) { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; }
function duration(ms) {
  const minutes = Math.round(ms / 60000);
  if (minutes < 1) return `${Math.round(ms / 1000)} s`;
  const d = Math.floor(minutes / 1440), h = Math.floor((minutes % 1440) / 60), m = minutes % 60;
  return [d && `${d} d`, h && `${h} h`, m && `${m} min`].filter(Boolean).join(' ') || '0 min';
}
const distance = m => m < 1000 ? `${number.format(Math.round(m))} m` : `${(m / 1000).toFixed(m < 10000 ? 2 : 1)} km`;
const utc = ms => new Date(ms).toISOString().slice(0, 16).replace('T', ' ');
const options = () => ({ mode: document.querySelector('input[name="merge-mode"]:checked').value, dedupe: $('merge-dedupe').checked, sortByTime: $('merge-sort').checked });
const valid = () => entries.filter(e => e.file);
const totals = () => valid().reduce((t, e) => ({ bytes: t.bytes + e.file.bytes, points: t.points + e.file.sourcePoints }), { bytes: 0, points: 0 });

function displayed() {
  const ok = valid();
  const order = $('merge-sort').checked ? orderFiles(ok.map(e => e.file)) : ok.map(e => e.file);
  return [...order.map(f => ok.find(e => e.file === f)), ...entries.filter(e => !e.file)];
}

function renderList(focus) {
  const list = $('merge-list'); list.replaceChildren();
  const shown = displayed();
  const movable = shown.filter(e => e.file);
  for (const entry of shown) {
    const li = el('li'); li.dataset.id = entry.id;
    li.append(el('span', entry.name, 'merge-file-name'));
    if (entry.file) {
      const f = entry.file, c = f.counts;
      const time = f.firstTime === null ? 'no timestamps' : `${utc(f.firstTime)} to ${utc(f.lastTime)} UTC`;
      li.append(el('span', `${count(c.tracks, 'track')} · ${count(c.segments, 'segment')} · ${count(c.trackpoints, 'track point')} · ${count(c.waypoints, 'waypoint')} · ${count(c.routes, 'route')} · ${time}`, 'gpx-small'));
    } else {
      li.classList.add('merge-rejected');
      li.append(el('span', `Not merged: ${entry.error}`, 'merge-error'));
    }
    const actions = el('span', undefined, 'merge-file-actions');
    const index = movable.indexOf(entry);
    if (entry.file) {
      for (const [dir, label] of [[-1, 'Up'], [1, 'Down']]) {
        const b = el('button', label); b.type = 'button'; b.dataset.action = dir === -1 ? 'up' : 'down';
        b.setAttribute('aria-label', `Move ${entry.name} ${dir === -1 ? 'up' : 'down'}`);
        b.disabled = index + dir < 0 || index + dir >= movable.length;
        b.addEventListener('click', () => move(entry.id, dir, b.dataset.action));
        actions.append(b);
      }
    }
    const remove = el('button', 'Remove'); remove.type = 'button'; remove.dataset.action = 'remove';
    remove.setAttribute('aria-label', `Remove ${entry.name}`);
    remove.addEventListener('click', () => removeEntry(entry.id));
    actions.append(remove); li.append(actions); list.append(li);
  }
  $('merge-list-section').hidden = !entries.length;
  if (focus) {
    const target = list.querySelector(`li[data-id="${focus.id}"] button[data-action="${focus.action}"]`);
    (target && !target.disabled ? target : list.querySelector(`li[data-id="${focus.id}"] button:not(:disabled)`) || $('merge-file')).focus();
  }
}

function move(id, dir, action) {
  // Moving a file means the person wants their own order, so time sorting is switched off.
  const order = displayed().filter(e => e.file);
  if ($('merge-sort').checked) { $('merge-sort').checked = false; }
  const i = order.findIndex(e => e.id === id), j = i + dir;
  if (j < 0 || j >= order.length) return;
  [order[i], order[j]] = [order[j], order[i]];
  entries = [...order, ...entries.filter(e => !e.file)];
  update({ id, action });
  $('merge-status').textContent = `Moved ${order[j].name} to position ${j + 1} of ${order.length}. Sorting by time is off.`;
}

function removeEntry(id) {
  const shown = displayed(), i = shown.findIndex(e => e.id === id), name = shown[i]?.name;
  entries = entries.filter(e => e.id !== id);
  const neighbour = displayed()[Math.min(i, entries.length - 1)];
  update(neighbour ? { id: neighbour.id, action: 'remove' } : null);
  if (!neighbour) $('merge-file').focus();
  $('merge-status').textContent = `Removed ${name} from this page. The original file has not changed.`;
}

function clearResult() {
  result = null; $('merge-results').hidden = true; $('merge-download').disabled = true;
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = null;
}

function warningText(w) {
  if (w.kind === 'overlap') return `Times overlap: ${w.b} starts before ${w.a} ends. Check that they are not two copies of the same recording. In one continuous segment the line will jump back in time.`;
  if (w.kind === 'order') return `${w.b} starts earlier than ${w.a}, which is placed before it. With sorting off, the merged file is not in time order.`;
  if (w.kind === 'untimed') return `${count(w.files.length, 'file has', 'files have')} no timestamps (${w.files.join(', ')}). ${w.sorted ? 'They are placed after the timed files, in the order you added them.' : 'They stay where you put them.'}`;
  const g = w.gap, gapText = g ? (g.ms !== null ? `${duration(g.ms)} and ${distance(g.meters)} (${utc(g.from)} to ${utc(g.to)} UTC)` : `${distance(g.meters)}`) : '';
  if (w.kind === 'straight-lines') return `One continuous segment joins ${count(w.joins + 1, 'separate piece')} into one line. Most map viewers, and apps that work out distance or moving time, will draw a straight line across each of the ${count(w.joins, 'join')}.${gapText ? ` The largest gap is ${gapText}.` : ''} Choose “Keep separate” or “One track, separate segments” to avoid this.`;
  if (w.kind === 'gap') return `Largest gap between consecutive track points: ${gapText}. This mode keeps segments separate, so a gap between segments is not drawn as a line.`;
  return '';
}

function drawPlot(plot) {
  const svg = $('merge-plot'); svg.replaceChildren();
  for (const [index, path] of plotPaths(plot).entries()) {
    if (path.length > 1) { const line = document.createElementNS(svg.namespaceURI, 'polyline'); line.setAttribute('points', path.map(p => p.map(n => n.toFixed(2)).join(',')).join(' ')); line.setAttribute('class', index % 2 ? 'gpx-line alternate' : 'gpx-line'); svg.append(line); }
    for (const point of [path[0], ...(path.length > 1 ? [path.at(-1)] : [])]) {
      const dot = document.createElementNS(svg.namespaceURI, 'circle'); dot.setAttribute('cx', point[0]); dot.setAttribute('cy', point[1]); dot.setAttribute('r', '4'); dot.setAttribute('class', 'gpx-dot'); svg.append(dot);
    }
  }
}

function update(focus) {
  renderList(focus);
  clearResult();
  if (!valid().length) return;
  const opts = options();
  try { result = mergeGPX(valid().map(e => e.file), opts); }
  catch (error) { $('merge-status').textContent = error.message; return; }
  const s = result.summary;
  const rows = [
    ['Files merged', number.format(s.files)], ['Tracks', number.format(s.tracks)], ['Track segments', number.format(s.segments)],
    ['Track points', number.format(s.trackpoints)], ['Waypoints', number.format(s.waypoints)], ['Routes', s.routes ? `${number.format(s.routes)} (${count(s.routePoints, 'point')})` : '0'],
    ['First time (UTC)', s.firstTime ? utc(Date.parse(s.firstTime)) : 'None'], ['Last time (UTC)', s.lastTime ? utc(Date.parse(s.lastTime)) : 'None'],
    ['Duplicates removed', opts.dedupe ? number.format(s.duplicatesRemoved) : 'Off'],
    ['Largest gap', s.gap ? (s.gap.ms !== null ? `${duration(s.gap.ms)}, ${distance(s.gap.meters)}` : distance(s.gap.meters)) : 'None'],
  ];
  const dl = $('merge-summary'); dl.replaceChildren();
  for (const [term, value] of rows) { const div = el('div'); div.append(el('dt', term), el('dd', value)); dl.append(div); }
  const list = $('merge-warnings'); list.replaceChildren();
  for (const w of result.warnings) { const li = el('li', warningText(w)); if (w.kind === 'straight-lines' || w.kind === 'overlap' || w.kind === 'order') li.className = 'merge-warning'; list.append(li); }
  if (s.duplicateWaypoints || s.duplicateRoutes) list.append(el('li', `Exact duplicates left out: ${count(s.duplicateWaypoints, 'waypoint')} and ${count(s.duplicateRoutes, 'route')}.`));
  if (s.invalidCoordinates) list.append(el('li', `${count(s.invalidCoordinates, 'point')} with missing or out-of-range coordinates left out.`));
  if (s.droppedFields) list.append(el('li', `${count(s.droppedFields, 'field')} that GPX 1.1 does not allow in that place, or with an unusable value, left out (for example GPX 1.0 speed and course).`));
  if (!list.children.length) list.append(el('li', 'No warnings. Times are in order, with no overlaps.'));
  drawPlot(result.plot);
  $('merge-plot-note').textContent = `Preview of the merged file. North is up; a local coordinate plot without street tiles. ${opts.mode === 'continuous' ? 'Straight lines across gaps are what most viewers will draw.' : 'Separate segments and tracks are drawn separately.'} Routes and waypoints are drawn too. Up to 2,000 points are drawn; the download has all ${count(s.trackpoints, 'track point')}.`;
  $('merge-filename').textContent = `File name: ${result.filename}`;
  $('merge-results').hidden = false; $('merge-download').disabled = !s.trackpoints && !s.waypoints && !s.routes;
}

async function addFiles(fileList) {
  const token = ++generation;
  const files = Array.from(fileList);
  if (!files.length) return;
  $('merge-status').textContent = `Reading ${count(files.length, 'file')} on this device…`;
  let added = 0, rejected = 0;
  for (const file of files) {
    const entry = { id: nextId++, name: file.name };
    const t = totals();
    if (valid().length >= MAX_FILES) entry.error = `This tool merges up to ${MAX_FILES} files at a time.`;
    else if (!/\.gpx$/i.test(file.name) && !/(gpx|xml)/i.test(file.type)) entry.error = 'Only GPX files are accepted. For Google Timeline JSON use the Timeline inspector; for KML or GeoJSON see the formats guide.';
    else if (file.size === 0) entry.error = 'This file is empty. Export it again from the app that made it.';
    else if (file.size > MAX_BYTES) entry.error = 'This file is over 10 MiB. Export a shorter date range and try again.';
    else if (t.bytes + file.size > MAX_TOTAL_BYTES) entry.error = 'Adding this file would go over 50 MiB in total. Merge in smaller batches.';
    else {
      try {
        const text = await file.text();
        if (token !== generation) return;
        entry.file = readGPX(text, file.name);
        if (t.points + entry.file.sourcePoints > MAX_TOTAL_POINTS) { delete entry.file; entry.error = `Adding this file would go over ${number.format(MAX_TOTAL_POINTS)} points in total. Merge in smaller batches.`; }
      } catch (error) { entry.error = error instanceof Error && error.message ? error.message : 'This file could not be read. Save a local copy in Files first.'; }
    }
    if (token !== generation) return;
    entries.push(entry);
    if (entry.file) added++; else rejected++;
  }
  $('merge-file').value = '';
  update();
  $('merge-status').textContent = `Added ${count(added, 'file')}${rejected ? `; ${count(rejected, 'file')} could not be merged (see the list)` : ''}. Everything was read on this device.`;
}

$('merge-file').addEventListener('change', event => addFiles(event.target.files));
const drop = $('merge-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); if (event.dataTransfer?.files?.length) addFiles(event.dataTransfer.files); });
$('merge-demo').addEventListener('click', () => {
  generation++;
  entries = MERGE_SAMPLES.map(sample => ({ id: nextId++, name: sample.name, file: readGPX(sample.text, sample.name) }));
  $('merge-sort').checked = true;
  update();
  $('merge-status').textContent = 'Loaded three synthetic daily files (an invented three-day walk). No personal data.';
});
$('merge-clear').addEventListener('click', () => {
  generation++; entries = []; update();
  $('merge-status').textContent = 'Cleared from this page. Your original files have not changed.';
});
for (const input of document.querySelectorAll('input[name="merge-mode"], #merge-dedupe, #merge-sort')) input.addEventListener('change', () => update());
$('merge-download').addEventListener('click', () => {
  if (!result) return;
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([result.xml], { type: 'application/gpx+xml' }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = result.filename; document.body.append(a); a.click(); a.remove();
  $('merge-status').textContent = `Saved ${result.filename}. Keep your original files too.`;
});
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
$('merge-tool').hidden = false;
update();
