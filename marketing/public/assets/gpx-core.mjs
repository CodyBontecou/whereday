export const MAX_BYTES = 10 * 1024 * 1024;
export const MAX_POINTS = 50000;
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
const GPX_NAMESPACES = ['', 'http://www.topografix.com/GPX/1/0', 'http://www.topografix.com/GPX/1/1'];

function instant(value) {
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.exec(value);
  if (!m) return null;
  const [, y, month, day, h, min, sec] = m.map(Number);
  const days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || h > 23 || min > 59 || sec > 59) return null;
  const time = Date.parse(value);
  return Number.isFinite(time) ? time : null;
}

export function parseGPX(text, Parser = globalThis.DOMParser) {
  if (new TextEncoder().encode(text).length > MAX_BYTES) throw new Error('Choose a GPX file of 10 MiB or less.');
  if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(text)) throw new Error('DTD and entity declarations are not supported. Keep the original and use a standard GPX export.');
  let doc;
  try { doc = new Parser().parseFromString(text, 'application/xml'); }
  catch { throw new Error('This file is not well-formed XML. Keep the original and export a fresh GPX copy.'); }
  const root = doc.documentElement;
  if (!root || doc.getElementsByTagName('parsererror').length) throw new Error('This file is not well-formed XML. Keep the original and export a fresh GPX copy.');
  const namespace = root.namespaceURI || '';
  if (root.localName !== 'gpx' || !GPX_NAMESPACES.includes(namespace)) throw new Error('Choose a GPX 1.0 or 1.1 XML file, not JSON, KML, ZIP or another format.');
  if (!['1.0', '1.1'].includes(root.getAttribute('version'))) throw new Error('Only GPX versions 1.0 and 1.1 are supported.');
  const children = (node, name) => Array.from(node.childNodes).filter(e => e.nodeType === 1 && e.localName === name && (e.namespaceURI || '') === namespace);
  const value = (node, name) => children(node, name)[0]?.textContent.trim() || '';
  const groups = [], points = [];
  const issues = { invalidCoordinates: 0, missingTime: 0, invalidTime: 0, invalidElevation: 0, duplicatePositions: 0, timeGaps: 0, reversedTime: 0 };
  let sourcePoints = 0;
  function group(node, kind, pointTag, label) {
    const entry = { kind, index: groups.length + 1, name: label, points: [] };
    let previous = null;
    for (const [index, element] of children(node, pointTag).entries()) {
      if (++sourcePoints > MAX_POINTS) throw new Error('This file exceeds 50,000 points. Export a smaller date range and try again.');
      const latText = element.getAttribute('lat'), lonText = element.getAttribute('lon');
      const lat = Number(latText), lon = Number(lonText);
      if (!DECIMAL.test(latText?.trim() || '') || !DECIMAL.test(lonText?.trim() || '') || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
        issues.invalidCoordinates++; entry.points.push(null); previous = null; continue;
      }
      const rawTime = value(element, 'time'), time = rawTime ? instant(rawTime) : null;
      if (!rawTime) issues.missingTime++;
      else if (time === null) issues.invalidTime++;
      const rawElevation = value(element, 'ele');
      const elevation = DECIMAL.test(rawElevation) && Number.isFinite(Number(rawElevation)) ? Number(rawElevation) : null;
      if (rawElevation && elevation === null) issues.invalidElevation++;
      const point = { lat, lon, time, rawTime, elevation, group: entry.index, kind, index: index + 1, name: value(element, 'name') };
      if (previous) {
        if (previous.lat === lat && previous.lon === lon) issues.duplicatePositions++;
        if (previous.time !== null && time !== null) {
          const delta = time - previous.time;
          if (delta > 600000) issues.timeGaps++;
          if (delta < 0) issues.reversedTime++;
        }
      }
      entry.points.push(point); points.push(point); previous = point;
    }
    groups.push(entry);
  }
  const tracks = children(root, 'trk'), routes = children(root, 'rte'), waypoints = children(root, 'wpt');
  for (const track of tracks) for (const segment of children(track, 'trkseg')) group(segment, 'track', 'trkpt', value(track, 'name'));
  for (const route of routes) group(route, 'route', 'rtept', value(route, 'name'));
  // A waypoint is independent; never infer a line or time gap between waypoints.
  for (const waypoint of waypoints) group({ childNodes: [waypoint] }, 'waypoint', 'wpt', value(waypoint, 'name'));
  if (!sourcePoints) throw new Error('No track points, route points or waypoints were found. This GPX may be empty.');
  const timed = points.filter(p => p.time !== null).map(p => p.time);
  return { groups, points, issues, sourcePoints, tracks: tracks.length, routes: routes.length,
    segments: groups.filter(g => g.kind === 'track').length, waypoints: waypoints.length,
    firstTime: timed.length ? new Date(Math.min(...timed)).toISOString() : null,
    lastTime: timed.length ? new Date(Math.max(...timed)).toISOString() : null,
    namespaceMissing: !namespace };
}

export function toCSV(model) {
  const cell = value => {
    let s = String(value ?? '');
    // User text and original timestamps can become spreadsheet formulas.
    if (/^[\s]*[=+\-@]/.test(s) || /^[\t\r\n]/.test(s)) s = "'" + s;
    return '"' + s.replaceAll('"', '""') + '"';
  };
  const header = 'kind,group,point,latitude,longitude,time_utc,elevation_m,name,original_time';
  return header + '\r\n' + model.points.map(p => [p.kind, p.group, p.index, p.lat, p.lon,
    p.time === null ? '' : new Date(p.time).toISOString(), p.elevation ?? '', p.name, p.rawTime].map((v, i) =>
      // These values are computed numbers, not untrusted spreadsheet text.
      [1, 2, 3, 4, 6].includes(i) ? String(v) : cell(v)).join(',')).join('\r\n') + '\r\n';
}

export function plotPaths(model, budget = 2000) {
  if (!model.points.length) return [];
  const radians = Math.PI / 180;
  const center = Math.atan2(model.points.reduce((n, p) => n + Math.sin(p.lon * radians), 0), model.points.reduce((n, p) => n + Math.cos(p.lon * radians), 0)) / radians;
  const meanLat = model.points.reduce((n, p) => n + p.lat, 0) / model.points.length;
  const flatten = p => [(((p.lon - center + 540) % 360) - 180) * Math.max(.01, Math.cos(meanLat * radians)), -p.lat];
  const all = model.points.map(flatten);
  const xs = all.map(p => p[0]), ys = all.map(p => p[1]);
  const minX = Math.min(...xs), minY = Math.min(...ys), maxX = Math.max(...xs), maxY = Math.max(...ys);
  const scale = Math.min(720 / (maxX - minX || 1e-6), 300 / (maxY - minY || 1e-6));
  const xy = p => { const [x, y] = flatten(p); return [400 + (x - (maxX + minX) / 2) * scale, 180 + (y - (maxY + minY) / 2) * scale]; };
  const paths = []; let remaining = budget;
  const step = Math.max(1, Math.ceil(model.points.length / budget));
  for (const group of model.groups) {
    let chunk = [];
    const flush = () => { if (chunk.length && remaining) { const selected = chunk.filter((_, i) => i % step === 0 || i === chunk.length - 1).slice(0, remaining); paths.push(selected.map(xy)); remaining -= selected.length; } chunk = []; };
    for (const p of group.points) { if (p) chunk.push(p); else flush(); }
    flush();
    if (!remaining) break;
  }
  return paths;
}
