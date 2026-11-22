// Convert KML to GPX 1.1 and GPX to KML 2.2 locally. GPX reading reuses the GPX viewer's parser.
import { parseGPX, MAX_BYTES, MAX_POINTS } from './gpx-core.mjs';

export { MAX_BYTES, MAX_POINTS };
const GX = 'http://www.google.com/kml/ext/2.2';
const KML_NAMESPACES = ['', 'http://earth.google.com/kml/2.0', 'http://earth.google.com/kml/2.1', 'http://earth.google.com/kml/2.2', 'http://www.opengis.net/kml/2.2'];
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
const CREATOR = 'Whereday KML-GPX converter, https://whereday.com/tools/kml-to-gpx/';

export const LABELS = {
  placemarks: 'Placemarks', points: 'Points', lineStrings: 'LineStrings', gxTracks: 'gx:Tracks', positions: 'Positions read',
  waypoints: 'Waypoints', routes: 'Routes', tracks: 'Tracks', segments: 'Track segments', trackPoints: 'Track points', routePoints: 'Route points',
  withTime: 'Positions with a time', withElevation: 'Positions with elevation',
};

const escapeText = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const escapeAttr = s => escapeText(s).replaceAll('"', '&quot;');
// XML 1.0 forbids most control characters; drop them rather than write an unreadable file.
const clean = s => s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '').trim();
const elementsOf = node => Array.from(node.childNodes).filter(n => n.nodeType === 1);
const kmlName = n => (KML_NAMESPACES.includes(n.namespaceURI || '') ? n.localName : null);
const gxName = n => (n.namespaceURI === GX ? n.localName : null);
const childText = (node, name) => { const n = elementsOf(node).find(e => kmlName(e) === name); return n ? clean(n.textContent) : ''; };

// Strict RFC 3339 / xsd:dateTime with a time zone. Returns UTC milliseconds, or null (nothing is guessed).
function instant(value) {
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.exec(value);
  if (!m) return null;
  const [, y, month, day, h, min, sec] = m.map(Number);
  const days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || h > 23 || min > 59 || sec > 59) return null;
  const time = Date.parse(value);
  return Number.isFinite(time) ? time : null;
}
const iso = ms => new Date(ms).toISOString().replace('.000Z', 'Z');

function validCoordinates(lonText, latText) {
  if (!DECIMAL.test(lonText ?? '') || !DECIMAL.test(latText ?? '')) return false;
  return Math.abs(Number(latText)) <= 90 && Math.abs(Number(lonText)) <= 180;
}
// GPX 1.1 longitude is [-180, 180); KML allows 180. They are the same meridian.
const gpxLon = text => (Number(text) === 180 ? '-180' : text);

export function baseName(name) {
  const base = String(name || '').replace(/^.*[\\/]/, '').replace(/\.[^.]*$/, '').replace(/[\u0000-\u001F<>:"|?*]/g, '').trim();
  return base || 'converted';
}

function parseXML(text, Parser) {
  if (typeof text !== 'string') throw new Error('This file could not be read as text.');
  const bare = text.replace(/^\uFEFF/, '');
  if (bare.startsWith('PK\u0003\u0004')) throw new Error('This looks like a KMZ (zip) file. KMZ is not supported here: unzip it and choose the doc.kml inside.');
  if (new TextEncoder().encode(bare).length > MAX_BYTES) throw new Error('Choose a file of 10 MiB or less.');
  if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(bare)) throw new Error('DTD and entity declarations are not supported. Keep the original and use a standard export.');
  let doc;
  try { doc = new Parser().parseFromString(bare, 'application/xml'); }
  catch { throw new Error('This file is not well-formed XML. Keep the original and export a fresh copy.'); }
  const root = doc.documentElement;
  if (!root || doc.getElementsByTagName('parsererror').length) throw new Error('This file is not well-formed XML. Keep the original and export a fresh copy.');
  return { doc, root, text: bare };
}

// Detect from content, never from the file name: 'kml' or 'gpx'.
export function detectFormat(text, Parser = globalThis.DOMParser) {
  const { root } = parseXML(text, Parser);
  if (root.localName === 'kml') return 'kml';
  if (root.localName === 'gpx') return 'gpx';
  throw new Error('Choose a KML or GPX file. KMZ, GeoJSON, FIT, TCX and other formats are not supported.');
}

export function convert(text, name, { Parser = globalThis.DOMParser, keepTimestamps = true } = {}) {
  const format = detectFormat(text, Parser);
  const result = format === 'kml' ? kmlToGPX(text, Parser) : gpxToKML(text, Parser, baseName(name), keepTimestamps);
  const base = baseName(name);
  return { ...result, format, filename: `${base}.${format === 'kml' ? 'gpx' : 'kml'}` };
}

// ---------- KML -> GPX ----------

function tally(root) {
  const counts = new Map();
  const bump = key => counts.set(key, (counts.get(key) || 0) + 1);
  (function visit(node, inPolygon) {
    for (const child of elementsOf(node)) {
      const k = kmlName(child), g = gxName(child);
      if (k === 'LinearRing' && !inPolygon) bump('LinearRing');
      else if (k && ['Polygon', 'Model', 'NetworkLink', 'GroundOverlay', 'ScreenOverlay', 'PhotoOverlay', 'Style', 'StyleMap', 'ExtendedData', 'TimeStamp', 'TimeSpan', 'Folder'].includes(k)) bump(k);
      else if (g === 'Tour') bump('Tour');
      visit(child, inPolygon || k === 'Polygon');
    }
  })(root, false);
  return counts;
}

function parseAltitude(text) { return text !== undefined && DECIMAL.test(text) ? text : null; }

export function kmlToGPX(text, Parser = globalThis.DOMParser) {
  const { root } = parseXML(text, Parser);
  if (root.localName !== 'kml') throw new Error('Choose a KML file.');
  const input = { placemarks: 0, points: 0, lineStrings: 0, gxTracks: 0, positions: 0, withTime: 0, withElevation: 0 };
  const issues = { invalidCoordinates: 0, invalidTimes: 0, invalidElevations: 0, unpairedTrackValues: 0 };
  let read = 0;
  const count = () => { if (++read > MAX_POINTS) throw new Error('This file has more than 50,000 positions. Export a smaller date range and try again.'); };

  // Returns {lon, lat, ele} with the original decimal text, or null for an unusable position.
  function position(lonText, latText, altText) {
    count();
    if (!validCoordinates(lonText, latText)) { issues.invalidCoordinates++; return null; }
    let ele = null;
    if (altText !== undefined) { ele = parseAltitude(altText); if (ele === null) issues.invalidElevations++; }
    input.positions++;
    if (ele !== null) input.withElevation++;
    return { lon: gpxLon(lonText), lat: latText, ele, time: null };
  }
  const fromKmlTuple = token => { const [lon, lat, alt] = token.split(','); return position(lon, lat, alt); };
  const tuples = el => clean(childText(el, 'coordinates')).split(/\s+/).filter(Boolean);

  function track(el) {
    input.gxTracks++;
    const kids = elementsOf(el);
    const whens = kids.filter(n => gxName(n) === null && kmlName(n) === 'when').map(n => clean(n.textContent));
    const coords = kids.filter(n => gxName(n) === 'coord').map(n => clean(n.textContent));
    const out = [];
    const paired = Math.min(whens.length, coords.length);
    issues.unpairedTrackValues += Math.abs(whens.length - coords.length);
    // A gx:Track may have fewer <when> values than coordinates only through malformed files; extras are dropped and counted.
    for (let i = 0; i < paired; i++) {
      const [lon, lat, alt] = coords[i].split(/\s+/);
      const p = position(lon, lat, alt);
      if (!p) continue;
      if (whens[i]) {
        const ms = instant(whens[i]);
        if (ms === null) issues.invalidTimes++; else { p.time = iso(ms); p.ms = ms; input.withTime++; }
      }
      out.push(p);
    }
    return out;
  }

  const waypoints = [], tracks = [];
  function geometry(el, acc) {
    const k = kmlName(el), g = gxName(el);
    if (k === 'Point') {
      input.points++;
      const token = tuples(el)[0];
      const p = token ? fromKmlTuple(token) : (issues.invalidCoordinates++, null);
      if (p) acc.points.push(p);
    } else if (k === 'LineString') {
      input.lineStrings++;
      acc.segments.push(tuples(el).map(fromKmlTuple).filter(Boolean));
    } else if (g === 'Track') acc.segments.push(track(el));
    else if (g === 'MultiTrack' || k === 'MultiGeometry') for (const child of elementsOf(el)) geometry(child, acc);
  }
  (function placemarks(node) {
    for (const child of elementsOf(node)) {
      if (kmlName(child) === 'Placemark') {
        input.placemarks++;
        const acc = { points: [], segments: [] };
        for (const g of elementsOf(child)) geometry(g, acc);
        const label = { name: childText(child, 'name'), desc: childText(child, 'description') };
        for (const p of acc.points) waypoints.push({ ...label, ...p });
        const segments = acc.segments.filter(s => s.length);
        if (segments.length) tracks.push({ ...label, segments });
      } else if (kmlName(child) !== 'NetworkLink') placemarks(child);
    }
  })(root);
  if (!input.placemarks) throw new Error('No Placemark elements were found in this KML.');
  if (!waypoints.length && !tracks.length) throw new Error('No Point, LineString or gx:Track positions were found. Polygons, overlays and other shapes cannot be converted to GPX.');

  const all = [...waypoints, ...tracks.flatMap(t => t.segments.flat())];
  const times = tracks.flatMap(t => t.segments.flat()).filter(p => p.time).map(p => p.ms);
  const output = {
    waypoints: waypoints.length, tracks: tracks.length, segments: tracks.reduce((n, t) => n + t.segments.length, 0),
    trackPoints: tracks.reduce((n, t) => n + t.segments.reduce((m, s) => m + s.length, 0), 0),
    withTime: times.length, withElevation: all.filter(p => p.ele !== null).length,
  };

  const tag = (name, value) => (value ? `<${name}>${escapeText(value)}</${name}>` : '');
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>',
    `<gpx version="1.1" creator="${escapeAttr(CREATOR)}" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">`];
  const docName = (() => { const d = elementsOf(root).find(n => kmlName(n) === 'Document'); return d ? childText(d, 'name') : ''; })();
  if (docName) lines.push(`  <metadata><name>${escapeText(docName)}</name></metadata>`);
  const pt = (indent, tagName, p) => `${indent}<${tagName} lat="${escapeAttr(p.lat)}" lon="${escapeAttr(p.lon)}">${p.ele !== null ? `<ele>${escapeText(p.ele)}</ele>` : ''}${p.time ? `<time>${p.time}</time>` : ''}</${tagName}>`;
  for (const w of waypoints) {
    lines.push(`  <wpt lat="${escapeAttr(w.lat)}" lon="${escapeAttr(w.lon)}">${w.ele !== null ? `<ele>${escapeText(w.ele)}</ele>` : ''}${tag('name', w.name)}${tag('desc', w.desc)}</wpt>`);
  }
  for (const t of tracks) {
    lines.push('  <trk>');
    if (t.name) lines.push(`    ${tag('name', t.name)}`);
    if (t.desc) lines.push(`    ${tag('desc', t.desc)}`);
    for (const s of t.segments) { lines.push('    <trkseg>'); for (const p of s) lines.push(pt('      ', 'trkpt', p)); lines.push('    </trkseg>'); }
    lines.push('  </trk>');
  }
  lines.push('</gpx>', '');

  const tallies = tally(root);
  const skipped = [];
  const add = (key, label, reason) => { if (tallies.get(key)) skipped.push({ key, label, count: tallies.get(key), reason }); };
  add('Polygon', 'Polygons', 'GPX has no area shape. They are not turned into tracks.');
  add('LinearRing', 'LinearRings', 'A ring outside a Polygon is a closed outline, not a track.');
  add('Model', 'Models', 'GPX cannot hold 3D models.');
  add('NetworkLink', 'NetworkLinks', 'They load another file from the internet when Google Earth opens them. This page makes no network requests and does not follow them.');
  add('GroundOverlay', 'Ground overlays', 'Images are not part of GPX.');
  add('ScreenOverlay', 'Screen overlays', 'Images are not part of GPX.');
  add('PhotoOverlay', 'Photo overlays', 'Images are not part of GPX.');
  add('Style', 'Styles and icons', 'Colours, line widths and icons are not carried over.');
  add('StyleMap', 'Style maps', 'Colours, line widths and icons are not carried over.');
  add('ExtendedData', 'ExtendedData blocks', 'Custom fields have no standard place in GPX and are not copied.');
  add('TimeStamp', 'TimeStamps on features', 'Only the times inside a gx:Track become GPX times.');
  add('TimeSpan', 'TimeSpans on features', 'Only the times inside a gx:Track become GPX times.');
  add('Folder', 'Folders', 'GPX has no folders; their contents are converted but the grouping and folder names are lost.');
  add('Tour', 'Tours', 'Google Earth tours are not part of GPX.');
  if (issues.invalidCoordinates) skipped.push({ key: 'invalidCoordinates', label: 'Positions with invalid coordinates', count: issues.invalidCoordinates, reason: 'Missing, non-numeric or out of range (latitude ±90, longitude ±180). Left out.' });
  if (issues.invalidTimes) skipped.push({ key: 'invalidTimes', label: 'Times that are not valid date-times with a time zone', count: issues.invalidTimes, reason: 'The position was kept without a time; no time was guessed.' });
  if (issues.invalidElevations) skipped.push({ key: 'invalidElevations', label: 'Unusable altitude values', count: issues.invalidElevations, reason: 'The position was kept without an elevation.' });
  if (issues.unpairedTrackValues) skipped.push({ key: 'unpairedTrackValues', label: 'gx:Track values without a partner', count: issues.unpairedTrackValues, reason: 'A time with no gx:coord, or the reverse. They are left out.' });

  const notes = [];
  if (all.some(p => p.ele !== null)) notes.push('Altitudes are copied as written. KML altitude modes (clamp to ground, relative to ground) are not interpreted, so an altitude is not always a height above sea level.');
  return {
    direction: 'kml-to-gpx', text: lines.join('\n'), input, output, skipped, notes,
    firstTime: times.length ? iso(times.reduce((a, b) => Math.min(a, b))) : null,
    lastTime: times.length ? iso(times.reduce((a, b) => Math.max(a, b))) : null,
    mime: 'application/gpx+xml',
  };
}

// ---------- GPX -> KML ----------

const GPX_OTHER = {
  'Extensions': ['extensions'], 'Links': ['link', 'url', 'urlname'],
  'Comments, sources, symbols, types and fix or accuracy fields': ['cmt', 'src', 'sym', 'type', 'fix', 'sat', 'hdop', 'vdop', 'pdop', 'magvar', 'geoidheight', 'ageofdgpsdata', 'dgpsid', 'number'],
};

export function gpxToKML(text, Parser = globalThis.DOMParser, name = 'converted', keepTimestamps = true) {
  const model = parseGPX(text.replace(/^\uFEFF/, ''), Parser);
  const input = { waypoints: 0, routes: model.routes, tracks: model.tracks, segments: model.segments, trackPoints: 0, routePoints: 0, positions: 0, withTime: 0, withElevation: 0 };
  const field = (node, tagName) => { const n = elementsOf(node).find(e => e.localName === tagName); return n ? clean(n.textContent) : ''; };
  // Keep the coordinate text exactly as written so no precision is lost.
  const prepare = points => points.filter(Boolean).map(p => ({
    lat: p.element.getAttribute('lat').trim(), lon: p.element.getAttribute('lon').trim(),
    ele: p.elevation !== null ? field(p.element, 'ele') : null, ms: p.time, time: p.time !== null ? iso(p.time) : null,
  }));

  // Waypoints, then routes, then tracks (one entry per trk), as in the file.
  const waypointPoints = [], routeLines = [], trackEntries = [], trackByElement = new Map();
  for (const g of model.groups) {
    const pts = prepare(g.points);
    input.positions += pts.length;
    for (const p of pts) { if (p.time) input.withTime++; if (p.ele !== null) input.withElevation++; }
    if (g.kind === 'waypoint') {
      input.waypoints++;
      if (pts.length) waypointPoints.push({ ...pts[0], name: clean(g.name), desc: field(g.source, 'desc') });
    } else if (g.kind === 'route') {
      input.routePoints += pts.length;
      routeLines.push({ name: clean(g.name), desc: field(g.source, 'desc'), points: pts });
    } else {
      input.trackPoints += pts.length;
      let entry = trackByElement.get(g.source);
      if (!entry) { entry = { name: clean(g.name), desc: field(g.source, 'desc'), segments: [] }; trackByElement.set(g.source, entry); trackEntries.push(entry); }
      entry.segments.push(pts);
    }
  }
  const tooShort = { segments: 0, routes: 0 };
  const output = { placemarks: 0, points: 0, lineStrings: 0, gxTracks: 0, positions: 0, withTime: 0, withElevation: 0 };
  let droppedElevations = 0, usedGx = false;
  const allTimes = [];

  // KML tuples carry elevation only when every position in the line has it, so no value is invented.
  const withEle = pts => pts.every(p => p.ele !== null);
  const countElevation = pts => { if (withEle(pts)) output.withElevation += pts.length; else droppedElevations += pts.filter(p => p.ele !== null).length; };
  const lineString = (pts, indent) => {
    const ele = withEle(pts);
    countElevation(pts);
    output.lineStrings++; output.positions += pts.length;
    return `${indent}<LineString><coordinates>${pts.map(p => `${p.lon},${p.lat}${ele ? `,${p.ele}` : ''}`).join(' ')}</coordinates></LineString>`;
  };
  const gxTrack = (pts, indent) => {
    const ele = withEle(pts);
    countElevation(pts); usedGx = true;
    output.gxTracks++; output.positions += pts.length; output.withTime += pts.length;
    for (const p of pts) allTimes.push(p.ms);
    return [`${indent}<gx:Track>`, ...pts.map(p => `${indent}  <when>${p.time}</when>`), ...pts.map(p => `${indent}  <gx:coord>${p.lon} ${p.lat}${ele ? ` ${p.ele}` : ''}</gx:coord>`), `${indent}</gx:Track>`].join('\n');
  };
  const timed = pts => keepTimestamps && pts.every(p => p.time);
  const tag = (n, v) => (v ? `      <${n}>${escapeText(v)}</${n}>` : null);
  const placemark = (label, body) => {
    output.placemarks++;
    return ['    <Placemark>', tag('name', label.name), tag('description', label.desc), body, '    </Placemark>'].filter(Boolean).join('\n');
  };

  const features = [];
  for (const w of waypointPoints) {
    output.points++; output.positions++;
    if (w.ele !== null) output.withElevation++;
    features.push(placemark(w, `      <Point><coordinates>${w.lon},${w.lat}${w.ele !== null ? `,${w.ele}` : ''}</coordinates></Point>`));
  }
  for (const r of routeLines) {
    if (r.points.length < 2) { tooShort.routes++; continue; }
    features.push(placemark(r, lineString(r.points, '      ')));
  }
  for (const t of trackEntries) {
    const usable = [];
    for (const s of t.segments) { if (s.length >= 2) usable.push(s); else if (s.length) tooShort.segments++; }
    if (!usable.length) continue;
    const kinds = new Set(usable.map(s => (timed(s) ? 'gx' : 'line')));
    const render = (s, indent) => (timed(s) ? gxTrack(s, indent) : lineString(s, indent));
    if (usable.length === 1) features.push(placemark(t, render(usable[0], '      ')));
    else if (kinds.size === 1) {
      const wrapper = kinds.has('gx') ? 'gx:MultiTrack' : 'MultiGeometry';
      features.push(placemark(t, [`      <${wrapper}>`, ...usable.map(s => render(s, '        ')), `      </${wrapper}>`].join('\n')));
    } else {
      // Plain and timed segments cannot share one geometry, so each gets its own Placemark.
      for (const [i, s] of usable.entries()) features.push(placemark({ ...t, name: t.name ? `${t.name} (segment ${i + 1})` : `Segment ${i + 1}` }, render(s, '      ')));
    }
  }
  if (!features.length) throw new Error('No waypoints, routes or track segments with at least two points were found in this GPX.');

  const lines = ['<?xml version="1.0" encoding="UTF-8"?>',
    `<kml xmlns="http://www.opengis.net/kml/2.2"${usedGx ? ` xmlns:gx="${GX}"` : ''}>`, '  <Document>', `    <name>${escapeText(name)}</name>`, ...features, '  </Document>', '</kml>', ''];

  // Fields KML has no standard place for, counted inside waypoints, routes and tracks.
  const other = new Map();
  (function visit(node) {
    for (const child of elementsOf(node)) {
      const hit = Object.entries(GPX_OTHER).find(([, names]) => names.includes(child.localName));
      if (hit) other.set(hit[0], (other.get(hit[0]) || 0) + 1);
      else visit(child);
    }
  })({ childNodes: [...new Set(model.groups.map(g => g.source))] });
  const skipped = [];
  for (const [label, count] of other) skipped.push({ key: label, label, count, reason: 'KML has no matching standard element here, so the field is not copied.' });
  if (tooShort.segments) skipped.push({ key: 'shortSegments', label: 'Track segments with a single point', count: tooShort.segments, reason: 'A KML LineString needs at least two positions, so these are left out.' });
  if (tooShort.routes) skipped.push({ key: 'shortRoutes', label: 'Routes with a single point', count: tooShort.routes, reason: 'A KML LineString needs at least two positions, so these are left out.' });
  if (model.issues.invalidCoordinates) skipped.push({ key: 'invalidCoordinates', label: 'Points with invalid coordinates', count: model.issues.invalidCoordinates, reason: 'Missing, non-numeric or out of range. Left out.' });
  if (model.issues.invalidTime) skipped.push({ key: 'invalidTimes', label: 'Points with an unusable time', count: model.issues.invalidTime, reason: 'No time was guessed; the position is kept without one.' });
  if (model.issues.invalidElevation) skipped.push({ key: 'invalidElevations', label: 'Points with an unusable elevation', count: model.issues.invalidElevation, reason: 'The position is kept without an elevation.' });
  if (droppedElevations) skipped.push({ key: 'droppedElevations', label: 'Elevations left out', count: droppedElevations, reason: 'A line only gets altitudes when every position in it has one, so a value is never invented.' });

  const notes = [];
  const lostTimes = input.withTime - output.withTime;
  if (lostTimes > 0) notes.push(keepTimestamps
    ? `${lostTimes} timestamps were not written: plain LineStrings and Placemark points cannot carry time. Only segments where every point has a time become gx:Track.`
    : `${lostTimes} timestamps were not written because "Keep timestamps (gx:Track)" is off.`);
  return {
    direction: 'gpx-to-kml', text: lines.join('\n'), input, output, skipped, notes,
    firstTime: allTimes.length ? iso(allTimes.reduce((a, b) => Math.min(a, b))) : null,
    lastTime: allTimes.length ? iso(allTimes.reduce((a, b) => Math.max(a, b))) : null,
    mime: 'application/vnd.google-earth.kml+xml',
  };
}
