import { parseGPX, toCSV, plotPaths, MAX_BYTES } from './gpx-core.mjs';
import { geoJSONFeatureCollection } from './gpx-geojson.mjs';
import { DEMO_GPX } from './gpx-demo.mjs';
const $ = id => document.getElementById(id);
let generation = 0, model = null, geojson = null, downloadURL = null;
const number = new Intl.NumberFormat('en-US');
const count = (n, label) => `${number.format(n)} ${label}${n === 1 ? '' : 's'}`;
function reset() {
  model = null; geojson = null; $('gpx-download-geojson').disabled = true; $('gpx-results').hidden = true; $('gpx-download').disabled = true;
  $('gpx-file').value = ''; $('gpx-plot').replaceChildren(); $('gpx-rows').replaceChildren();
  for (const id of ['gpx-name', 'gpx-counts', 'gpx-dates', 'gpx-plot-note', 'gpx-preview-note', 'gpx-geojson-summary']) $(id).textContent = '';
  $('gpx-issues').replaceChildren();
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = null;
}
function node(tag, text) { const e = document.createElement(tag); e.textContent = text; return e; }
function show(xml, label) {
  reset();
  try {
    model = parseGPX(xml);
    $('gpx-name').textContent = label;
    $('gpx-counts').textContent = `${count(model.points.length, 'valid point')} · ${count(model.tracks, 'track')} · ${count(model.segments, 'track segment')} · ${count(model.routes, 'route')} · ${count(model.waypoints, 'waypoint')}`;
    $('gpx-dates').textContent = model.firstTime ? `Timed points: ${model.firstTime} to ${model.lastTime} (UTC). This is a time span, not moving time.` : 'No usable timestamps. Dates and elapsed time cannot be determined.';
    const issues = $('gpx-issues'); issues.replaceChildren();
    const labels = {
      invalidCoordinates: 'points omitted because coordinates are missing, nonnumeric or out of range',
      missingTime: 'valid points without a timestamp', invalidTime: 'valid points with an unusable timestamp',
      invalidElevation: 'valid points with unusable elevation', duplicatePositions: 'consecutive point pairs at identical coordinates',
      timeGaps: 'consecutive point pairs more than 10 minutes apart within the same segment or route', reversedTime: 'consecutive point pairs whose timestamps run backwards'
    };
    for (const [key, label] of Object.entries(labels)) if (model.issues[key]) { const wording = model.issues[key] === 1 ? label.replace('points', 'point').replace('pairs', 'pair') : label; issues.append(node('li', `${number.format(model.issues[key])} ${wording}.`)); }
    if (model.namespaceMissing) issues.append(node('li', 'The GPX namespace is missing. The known GPX elements were interpreted without a namespace.'));
    if (!issues.children.length) issues.append(node('li', 'No issues found in these checks. This is not a complete GPX schema or recording-accuracy validation.'));
    const paths = plotPaths(model);
    const svg = $('gpx-plot');
    for (const [index, path] of paths.entries()) {
      if (path.length > 1) { const line = document.createElementNS(svg.namespaceURI, 'polyline'); line.setAttribute('points', path.map(p => p.map(n => n.toFixed(2)).join(',')).join(' ')); line.setAttribute('class', index % 2 ? 'gpx-line alternate' : 'gpx-line'); svg.append(line); }
      for (const point of [path[0], ...(path.length > 1 ? [path.at(-1)] : [])]) {
        const dot = document.createElementNS(svg.namespaceURI, 'circle'); dot.setAttribute('cx', point[0]); dot.setAttribute('cy', point[1]); dot.setAttribute('r', '4'); dot.setAttribute('class', 'gpx-dot'); svg.append(dot);
      }
    }
    $('gpx-plot-note').textContent = model.points.length ? `North is up. Separate segments stay separate; invalid coordinates break the line. A local coordinate plot, without streets or map tiles. Up to 2,000 points are drawn; CSV and GeoJSON include ${count(model.points.length, 'valid point')}.` : 'No valid coordinates to draw. Review the checks and keep the original file.';
    for (const p of model.points.slice(0, 25)) {
      const row = document.createElement('tr');
      for (const text of [p.kind, `${p.group} / ${p.index}`, `${p.lat}, ${p.lon}`, p.time === null ? 'Unavailable' : new Date(p.time).toISOString()]) row.append(node('td', text));
      $('gpx-rows').append(row);
    }
    $('gpx-preview-note').textContent = `First ${count(Math.min(25, model.points.length), 'valid point')} shown. Group / point retains original point numbering within each segment, route or waypoint.`;
    geojson = geoJSONFeatureCollection(model);
    $('gpx-geojson-summary').textContent = `GeoJSON download: ${count(model.points.length, 'coordinate')} in ${count(geojson.features.length, 'feature')}. Separate groups, invalid-coordinate gaps and longitude jumps over 180° stay disconnected. Time and elevation use custom property arrays.`;
    $('gpx-download-geojson').disabled = !model.points.length;
    $('gpx-results').hidden = false; $('gpx-download').disabled = !model.points.length;
    $('gpx-status').textContent = `Opened ${label}. ${count(model.points.length, 'valid point')} inspected locally.`;
  } catch (error) { reset(); $('gpx-status').textContent = error.message; }
}
$('gpx-file').addEventListener('change', async event => {
  const file = event.target.files[0]; if (!file) return;
  const token = ++generation; reset(); $('gpx-status').textContent = 'Reading the file on this device…';
  if (file.size > MAX_BYTES) { $('gpx-status').textContent = 'Choose a GPX file of 10 MiB or less.'; return; }
  try { const text = await file.text(); if (token === generation) show(text, file.name); }
  catch { if (token === generation) $('gpx-status').textContent = 'This file could not be read. Try saving a local copy in Files first.'; }
});
$('gpx-demo').addEventListener('click', () => { generation++; show(DEMO_GPX, 'Synthetic five-point example'); });
$('gpx-clear').addEventListener('click', () => { generation++; reset(); $('gpx-status').textContent = 'Cleared from this page. Your original file has not changed.'; });
function download(contents, type, filename) {
  if (downloadURL) URL.revokeObjectURL(downloadURL);
  downloadURL = URL.createObjectURL(new Blob([contents], { type }));
  const a = document.createElement('a'); a.href = downloadURL; a.download = filename; document.body.append(a); a.click(); a.remove();
}
$('gpx-download').addEventListener('click', () => { if (model?.points.length) download(toCSV(model), 'text/csv;charset=utf-8', 'gpx-points.csv'); });
$('gpx-download-geojson').addEventListener('click', () => { if (model?.points.length && geojson) download(JSON.stringify(geojson, null, 2) + '\n', 'application/geo+json', 'gpx-points.geojson'); });
window.addEventListener('pagehide', () => { if (downloadURL) URL.revokeObjectURL(downloadURL); });
