// Read a GeoJSON document locally and convert it to GPX 1.1 or KML 2.2. Pure functions: no DOM, no network.
// Coordinates are [longitude, latitude(, altitude)] in WGS 84 (RFC 7946 section 3.1.1). Nothing is guessed or swapped.
import { MAX_BYTES, MAX_POINTS } from './gpx-core.mjs';
import { instant } from './kml-gpx-core.mjs';

export { MAX_BYTES, MAX_POINTS };
export { plotShapes, formatBounds, formatCoordinate } from './kml-viewer-core.mjs';

export const GEOMETRY_TYPES = ['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon', 'GeometryCollection'];
const MAX_DEPTH = 8;
const TIME_KEYS = ['time', 'timestamp', 'datetime'];
const TIME_ARRAY_KEYS = ['coordtimes', 'times'];
const CREATOR = 'Whereday GeoJSON viewer, https://whereday.com/tools/geojson-viewer/';
const GX = 'http://www.google.com/kml/ext/2.2';

// Things left out of the picture, in display order. [key, label, reason]
const SKIP = [
  ['badFeature', 'Entries that are not Features', 'A FeatureCollection entry that is not an object, or whose "type" is missing or not "Feature". Left out.'],
  ['noGeometry', 'Features with a null or missing geometry', 'Nothing to draw. The feature is still counted and listed.'],
  ['badGeometry', 'Geometries with a missing or unknown type', 'The "type" is missing, not a string, or not one of the seven GeoJSON geometry types. Left out.'],
  ['noCoordinates', 'Geometries without a coordinates array', 'A "coordinates" (or "geometries") array is required. Left out.'],
  ['emptyGeometry', 'Empty geometries', 'The coordinates array is empty, so there is nothing to draw.'],
  ['invalid', 'Invalid positions', 'Not two or three finite numbers, or outside longitude ±180 or latitude ±90. Left out; nothing is corrected.'],
  ['swapped', 'Invalid positions that fit latitude-first', 'Longitude is first in GeoJSON. These would be in range if the two numbers were swapped, so the file may be latitude-first. The viewer does not swap them.'],
  ['projected', 'Positions with values beyond ±180', 'Numbers this large often mean a projected coordinate system (metres) rather than WGS 84 degrees. They are left out, not converted.'],
  ['shortLine', 'Lines with fewer than two valid positions', 'A LineString needs two positions. Left out.'],
  ['shortRing', 'Polygon rings with fewer than three valid positions', 'Left out. If the outer ring is lost, the whole polygon is left out.'],
  ['deep', 'GeometryCollections nested too deeply', 'Nesting beyond 8 levels is not read.'],
  ['extra', 'Positions with more than three numbers', 'Only longitude, latitude and the third number (altitude) are read. The rest is ignored.'],
  ['altitude', 'Positions with an altitude (3D)', 'Drawn in 2D; altitude is not plotted. It is kept in GPX <ele> and in KML coordinates where every position in a line has one.'],
  ['crs', '"crs" members', 'RFC 7946 removed "crs". Coordinates are read as WGS 84 longitude, latitude, whatever the member says. No reprojection.'],
  ['timeUnreadable', 'Time values that could not be read', 'Not a date-time with a time zone (for example a number, or a date without a zone). Shown as unknown; nothing is guessed.'],
  ['timeMismatch', 'Time arrays that do not match the positions', 'The array length differs from the number of positions. Its readable times still set the first and last time, but none is attached to a position.'],
];

const isObject = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const clean = s => String(s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '').trim();
const iso = ms => new Date(ms).toISOString().replace('.000Z', 'Z');
const flatten = list => list.flatMap(v => (Array.isArray(v) ? flatten(v) : [v]));

function readRoots(text) {
  try { return { roots: [JSON.parse(text)], format: 'JSON' }; } catch { /* maybe newline-delimited */ }
  const lines = text.split(/\r?\n/).map(l => l.replace(/^\u001e/, '').trim()).filter(Boolean);
  if (lines.length < 2) throw new Error('This is not valid JSON. Keep the original and export a fresh copy.');
  const roots = lines.map((line, i) => {
    try { return JSON.parse(line); } catch { throw new Error(`Line ${i + 1} is not valid JSON, so this is neither a JSON file nor newline-delimited GeoJSON.`); }
  });
  return { roots, format: 'newline-delimited JSON' };
}

function bounds(coords) {
  if (!coords.length) return null;
  let [w, s, e, n] = [Infinity, Infinity, -Infinity, -Infinity];
  for (const [lon, lat] of coords) { w = Math.min(w, lon); e = Math.max(e, lon); s = Math.min(s, lat); n = Math.max(n, lat); }
  return { west: w, south: s, east: e, north: n };
}

export function parseGeoJSON(text) {
  if (typeof text !== 'string') throw new Error('This file could not be read as text.');
  const bare = text.replace(/^\uFEFF/, '');
  if (new TextEncoder().encode(bare).length > MAX_BYTES) throw new Error('Choose a file of 10 MiB or less.');
  if (!bare.trim()) throw new Error('There is nothing to read: the text is empty.');
  const { roots, format } = readRoots(bare);

  const counts = {
    features: 0, withGeometry: 0, noGeometry: 0, positions: 0, withTimes: 0, timeValues: 0, antimeridianSplits: 0, antimeridianPolygons: 0,
    byType: Object.fromEntries(GEOMETRY_TYPES.map(t => [t, 0])),
  };
  const tally = Object.fromEntries(SKIP.map(s => [s[0], 0]));
  const features = [];
  const rootKinds = new Set();
  let raw = 0;

  function position(value, state) {
    if (++raw > MAX_POINTS) throw new Error('This file has more than 50,000 positions. Open a smaller file.');
    const index = state.index++;
    const ok = Array.isArray(value) && value.length >= 2 && value.every((n, i) => i >= 3 || (typeof n === 'number' && Number.isFinite(n)));
    if (!ok) { tally.invalid++; return null; }
    const [lon, lat, alt] = value;
    if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      tally.invalid++;
      if (Math.abs(lon) <= 90 && Math.abs(lat) <= 180) tally.swapped++;
      if (Math.abs(lon) > 180 || Math.abs(lat) > 180) tally.projected++;
      return null;
    }
    if (value.length > 3) tally.extra++;
    counts.positions++;
    if (alt !== undefined) tally.altitude++;
    return { c: alt === undefined ? [lon, lat] : [lon, lat, alt], i: index, t: null };
  }

  function line(list, acc, state) {
    if (!Array.isArray(list)) { tally.invalid++; return; }
    if (!list.length) { tally.emptyGeometry++; return; }
    const valid = list.map(v => position(v, state)).filter(Boolean);
    if (valid.length < 2) { tally.shortLine++; return; }
    // Cut where consecutive longitudes jump more than 180 degrees, without inventing a boundary point.
    let piece = [valid[0]];
    for (let i = 1; i < valid.length; i++) {
      if (Math.abs(valid[i].c[0] - valid[i - 1].c[0]) > 180) { acc.lines.push(piece); piece = []; counts.antimeridianSplits++; }
      piece.push(valid[i]);
    }
    acc.lines.push(piece);
  }

  function polygon(rings, acc, state) {
    if (!Array.isArray(rings)) { tally.invalid++; return; }
    if (!rings.length) { tally.emptyGeometry++; return; }
    const out = [];
    let outerLost = false, crosses = false;
    rings.forEach((ring, k) => {
      if (!Array.isArray(ring)) { tally.invalid++; if (k === 0) outerLost = true; return; }
      const valid = ring.map(v => position(v, state)).filter(Boolean);
      if (valid.length < 3) { tally.shortRing++; if (k === 0) outerLost = true; return; }
      if (valid.some((p, i) => i && Math.abs(p.c[0] - valid[i - 1].c[0]) > 180)) crosses = true;
      out.push(valid);
    });
    if (outerLost || !out.length) return;
    if (crosses) counts.antimeridianPolygons++;
    acc.polygons.push(out);
  }

  // Returns the geometry's label, or null when there is no object to read.
  function geometry(g, acc, state, depth) {
    if (!isObject(g) || typeof g.type !== 'string' || !GEOMETRY_TYPES.includes(g.type)) { tally.badGeometry++; return 'Unreadable geometry'; }
    const type = g.type;
    if (type === 'GeometryCollection') {
      if (!Array.isArray(g.geometries)) { tally.noCoordinates++; return type; }
      if (depth >= MAX_DEPTH) { tally.deep++; return type; }
      const inner = [];
      for (const member of g.geometries) { const t = geometry(member, acc, state, depth + 1); if (t) inner.push(t); }
      acc.members.push(...inner);
      return type;
    }
    const c = g.coordinates;
    if (!Array.isArray(c)) { tally.noCoordinates++; return type; }
    if (!c.length) { tally.emptyGeometry++; return type; }
    if (type === 'Point') { const p = position(c, state); if (p) acc.points.push(p); }
    else if (type === 'MultiPoint') { for (const v of c) { const p = position(v, state); if (p) acc.points.push(p); } }
    else if (type === 'LineString') line(c, acc, state);
    else if (type === 'MultiLineString') for (const l of c) line(l, acc, state);
    else if (type === 'Polygon') polygon(c, acc, state);
    else for (const poly of c) polygon(poly, acc, state);
    return type;
  }

  // Time properties. Only strict date-times with a zone are read; anything else is "unknown".
  function readTimes(props, total) {
    const lower = new Map(Object.keys(props).map(k => [k.toLowerCase(), props[k]]));
    const values = [];
    let scalar = null, array = null, mismatch = false;
    for (const key of TIME_KEYS) {
      if (!lower.has(key) || lower.get(key) === null) continue;
      const v = lower.get(key), ms = typeof v === 'string' ? instant(v.trim()) : null;
      if (ms === null) tally.timeUnreadable++; else { values.push(ms); if (scalar === null) scalar = ms; }
    }
    const candidates = TIME_ARRAY_KEYS.map(k => lower.get(k)).concat([isObject(props.coordinateProperties) ? props.coordinateProperties.times : undefined]);
    const found = candidates.find(Array.isArray);
    if (found) {
      const flat = flatten(found);
      array = flat.map(v => (typeof v === 'string' ? instant(v.trim()) : null));
      for (let i = 0; i < flat.length; i++) { if (array[i] === null && flat[i] !== null) tally.timeUnreadable++; else if (array[i] !== null) values.push(array[i]); }
      if (array.length !== total) { mismatch = true; tally.timeMismatch++; }
    }
    return { scalar, array: mismatch ? null : array, values };
  }

  function addFeature(f, bare) {
    if (!isObject(f) || (!bare && f.type !== 'Feature')) { tally.badFeature++; return; }
    counts.features++;
    if ('crs' in f) tally.crs++;
    const props = isObject(f.properties) ? f.properties : {};
    const acc = { points: [], lines: [], polygons: [], members: [] };
    const state = { index: 0 };
    let type;
    if (f.geometry === null || f.geometry === undefined) { tally.noGeometry++; counts.noGeometry++; type = 'No geometry'; }
    else {
      const top = geometry(f.geometry, acc, state, 0);
      counts.withGeometry++;
      if (GEOMETRY_TYPES.includes(top)) counts.byType[top]++;
      type = top === 'GeometryCollection' && acc.members.length ? `GeometryCollection (${[...new Set(acc.members)].join(', ')})` : top;
    }
    const { scalar, array, values } = readTimes(props, state.index);
    const all = [...acc.points, ...acc.lines.flat(), ...acc.polygons.flat(2)];
    for (const p of all) {
      if (array) p.t = array[p.i] ?? null;
      else if (f.geometry?.type === 'Point' && scalar !== null) p.t = scalar;
    }
    if (values.length) counts.withTimes++;
    counts.timeValues += values.length;
    const parts = [
      ...acc.points.map(p => ({ kind: 'point', coords: [p.c] })),
      ...acc.lines.map(l => ({ kind: l.length > 1 ? 'line' : 'point', coords: l.map(p => p.c) })),
      ...acc.polygons.flatMap(rings => rings.map((ring, k) => ({ kind: 'ring', inner: k > 0, coords: ring.map(p => p.c) }))),
    ];
    const coords = parts.flatMap(p => p.coords);
    const known = new Set([...TIME_KEYS, ...TIME_ARRAY_KEYS, 'name']);
    features.push({
      id: features.length, name: typeof props.name === 'string' ? clean(props.name) : '', type, parts, coordinateCount: coords.length,
      firstTime: values.length ? iso(Math.min(...values)) : '', lastTime: values.length ? iso(Math.max(...values)) : '', timeCount: values.length,
      bounds: bounds(coords), properties: props, otherProperties: Object.keys(props).filter(k => !known.has(k.toLowerCase())),
      points: acc.points, lines: acc.lines, polygons: acc.polygons,
    });
  }

  for (const [n, root] of roots.entries()) {
    const where = roots.length > 1 ? `Line ${n + 1}: ` : '';
    if (!isObject(root) || typeof root.type !== 'string') {
      throw new Error(`${where}This ${format === 'JSON' ? 'JSON' : 'line'} has no GeoJSON "type", so it is not GeoJSON. If it is a Google Timeline export, use the Google Timeline JSON inspector.`);
    }
    if ('crs' in root) tally.crs++;
    if (root.type === 'FeatureCollection') {
      if (!Array.isArray(root.features)) throw new Error(`${where}This FeatureCollection has no "features" array.`);
      rootKinds.add('FeatureCollection');
      for (const f of root.features) addFeature(f, false);
    } else if (root.type === 'Feature') { rootKinds.add('Feature'); addFeature(root, false); }
    else if (GEOMETRY_TYPES.includes(root.type)) { rootKinds.add('bare geometry'); addFeature({ type: 'Feature', geometry: root, properties: null }, true); }
    else throw new Error(`${where}The "type" is "${String(root.type).slice(0, 40)}", which is not a GeoJSON object type (FeatureCollection, Feature or a geometry).`);
  }

  const skipped = SKIP.filter(s => tally[s[0]]).map(([key, label, reason]) => ({ key, label, reason, count: tally[key] }));
  const notes = [];
  if (counts.antimeridianSplits) notes.push(`${counts.antimeridianSplits} line${counts.antimeridianSplits === 1 ? '' : 's'} jumped more than 180° in longitude and were cut into separate pieces there (RFC 7946 section 3.1.9). No boundary point was added, so the pieces stop short of the antimeridian.`);
  if (counts.antimeridianPolygons) notes.push(`${counts.antimeridianPolygons} polygon${counts.antimeridianPolygons === 1 ? '' : 's'} cross the antimeridian and are drawn as written, so they may look stretched across the plot.`);
  const all = features.flatMap(f => f.parts.flatMap(p => p.coords));
  const times = features.filter(f => f.firstTime).flatMap(f => [Date.parse(f.firstTime), Date.parse(f.lastTime)]);
  return {
    format, rootKinds: [...rootKinds], counts, features, skipped, notes, bounds: bounds(all),
    firstTime: times.length ? iso(Math.min(...times)) : '', lastTime: times.length ? iso(Math.max(...times)) : '',
  };
}

// What each export can carry, so the UI can disable a button instead of making an empty file.
export function exportable(model) {
  return {
    gpx: model.features.some(f => f.points.length || f.lines.length),
    kml: model.features.some(f => f.points.length || f.lines.some(l => l.length > 1) || f.polygons.length),
  };
}

const escapeText = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
// xsd:decimal has no exponent form, so 1e-7 is written out in full.
const dec = n => { let s = String(n); if (/e/i.test(s)) s = n.toFixed(12).replace(/\.?0+$/, ''); return s === '-0' ? '0' : s; };
const gpxLon = lon => (lon === 180 ? -180 : lon); // GPX 1.1 longitude is [-180, 180); 180 and -180 are the same meridian.
const tag = (name, value) => (value ? `<${name}>${escapeText(value)}</${name}>` : '');

function summarize(model, written, extra) {
  const dropped = [...extra];
  const withoutGeometry = model.features.filter(f => !f.points.length && !f.lines.length && !f.polygons.length).length;
  if (withoutGeometry) dropped.push({ label: 'Features with no usable geometry', count: withoutGeometry, reason: 'Nothing to write. They are counted in the summary above.' });
  const lostTimes = model.features.reduce((n, f) => n + f.timeCount, 0) - written;
  if (lostTimes > 0) dropped.push({ label: 'Times not written', count: lostTimes, reason: 'Only a time on a single Point, or an array that matches every position, is attached to a position. Other times stay in the summary.' });
  const props = model.features.filter(f => f.otherProperties.length);
  if (props.length) {
    const names = [...new Set(props.flatMap(f => f.otherProperties))];
    dropped.push({ label: 'Features with other properties', count: props.length, reason: `Only name and time are written. Dropped: ${names.slice(0, 12).join(', ')}${names.length > 12 ? ', …' : ''}.` });
  }
  return dropped;
}

export function toGPX(model, name = 'geojson') {
  const wpts = [], trks = [];
  const out = { waypoints: 0, tracks: 0, segments: 0, trackPoints: 0, withTime: 0, withElevation: 0, positions: 0 };
  let polygons = 0;
  const pt = (indent, el, p, label) => {
    out.positions++;
    if (p.c[2] !== undefined) out.withElevation++;
    if (p.t !== null) out.withTime++;
    return `${indent}<${el} lat="${dec(p.c[1])}" lon="${dec(gpxLon(p.c[0]))}">${p.c[2] !== undefined ? `<ele>${dec(p.c[2])}</ele>` : ''}${p.t !== null ? `<time>${iso(p.t)}</time>` : ''}${label ? tag('name', label) : ''}</${el}>`;
  };
  for (const f of model.features) {
    polygons += f.polygons.length;
    for (const p of f.points) { out.waypoints++; wpts.push(pt('  ', 'wpt', p, f.name)); }
    if (f.lines.length) {
      out.tracks++;
      trks.push('  <trk>', ...(f.name ? [`    ${tag('name', f.name)}`] : []));
      for (const piece of f.lines) {
        out.segments++; out.trackPoints += piece.length;
        trks.push('    <trkseg>', ...piece.map(p => pt('      ', 'trkpt', p)), '    </trkseg>');
      }
      trks.push('  </trk>');
    }
  }
  if (!out.waypoints && !out.tracks) throw new Error('Nothing to write to GPX: this file has no points or lines (GPX has no polygons).');
  const extra = [];
  if (polygons) extra.push({ label: 'Polygons', count: polygons, reason: 'GPX 1.1 has waypoints, routes and tracks but no area shape, so polygons are not exported. They appear here only as a count.' });
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>',
    `<gpx version="1.1" creator="${escapeText(CREATOR)}" xmlns="http://www.topografix.com/GPX/1/1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">`,
    `  <metadata>${tag('name', name)}</metadata>`, ...wpts, ...trks, '</gpx>', ''];
  const notes = [];
  if (out.tracks) notes.push('Each LineString is one track with one segment. A MultiLineString is one track with one segment per part, and a line cut at the antimeridian gets one segment per piece.');
  return { text: lines.join('\n'), counts: out, dropped: summarize(model, out.withTime, extra), notes, mime: 'application/gpx+xml' };
}

export function toKML(model, name = 'geojson') {
  const placemarks = [];
  const out = { placemarks: 0, points: 0, lineStrings: 0, gxTracks: 0, polygons: 0, positions: 0, withTime: 0, withElevation: 0 };
  let usedGx = false, shortPieces = 0, droppedAltitudes = 0;
  const withAlt = ps => ps.every(p => p.c[2] !== undefined);
  const coords = ps => { const alt = withAlt(ps); if (!alt) droppedAltitudes += ps.filter(p => p.c[2] !== undefined).length; else out.withElevation += ps.length; out.positions += ps.length; return ps.map(p => `${dec(p.c[0])},${dec(p.c[1])}${alt ? `,${dec(p.c[2])}` : ''}`).join(' '); };
  const gxTrack = (ps, indent) => {
    usedGx = true; out.gxTracks++; out.withTime += ps.length;
    const alt = withAlt(ps); if (alt) out.withElevation += ps.length; else droppedAltitudes += ps.filter(p => p.c[2] !== undefined).length;
    out.positions += ps.length;
    return [`${indent}<gx:Track>`, ...ps.map(p => `${indent}  <when>${iso(p.t)}</when>`), ...ps.map(p => `${indent}  <gx:coord>${dec(p.c[0])} ${dec(p.c[1])}${alt ? ` ${dec(p.c[2])}` : ''}</gx:coord>`), `${indent}</gx:Track>`].join('\n');
  };
  for (const f of model.features) {
    const pieces = f.lines.filter(l => l.length > 1);
    shortPieces += f.lines.length - pieces.length;
    const onlyLines = !f.points.length && !f.polygons.length && pieces.length > 0;
    const timed = onlyLines && pieces.length === f.lines.length && pieces.every(l => l.every(p => p.t !== null));
    const parts = [];
    let stamp = '';
    if (timed) {
      parts.push(...pieces.map(l => gxTrack(l, pieces.length > 1 ? '        ' : '      ')));
      if (pieces.length > 1) { parts.splice(0, parts.length, ['      <gx:MultiTrack>', ...parts, '      </gx:MultiTrack>'].join('\n')); }
    } else {
      const items = [];
      for (const p of f.points) { out.points++; out.positions++; if (p.c[2] !== undefined) out.withElevation++; items.push(`<Point><coordinates>${dec(p.c[0])},${dec(p.c[1])}${p.c[2] !== undefined ? `,${dec(p.c[2])}` : ''}</coordinates></Point>`); }
      for (const l of pieces) { out.lineStrings++; items.push(`<LineString><coordinates>${coords(l)}</coordinates></LineString>`); }
      for (const rings of f.polygons) {
        out.polygons++;
        const alt = rings.every(withAlt);
        if (!alt) droppedAltitudes += rings.flat().filter(p => p.c[2] !== undefined).length; else out.withElevation += rings.flat().length;
        out.positions += rings.flat().length;
        const text = ring => ring.map(p => `${dec(p.c[0])},${dec(p.c[1])}${alt ? `,${dec(p.c[2])}` : ''}`).join(' ');
        items.push(`<Polygon><outerBoundaryIs><LinearRing><coordinates>${text(rings[0])}</coordinates></LinearRing></outerBoundaryIs>${rings.slice(1).map(r => `<innerBoundaryIs><LinearRing><coordinates>${text(r)}</coordinates></LinearRing></innerBoundaryIs>`).join('')}</Polygon>`);
      }
      if (!items.length) continue;
      if (f.points.length === 1 && items.length === 1 && f.points[0].t !== null) { stamp = `      <TimeStamp><when>${iso(f.points[0].t)}</when></TimeStamp>`; out.withTime++; }
      parts.push(items.length === 1 ? `      ${items[0]}` : ['      <MultiGeometry>', ...items.map(i => `        ${i}`), '      </MultiGeometry>'].join('\n'));
    }
    if (!parts.length) continue;
    out.placemarks++;
    placemarks.push(['    <Placemark>', f.name ? `      ${tag('name', f.name)}` : null, stamp || null, ...parts, '    </Placemark>'].filter(Boolean).join('\n'));
  }
  if (!placemarks.length) throw new Error('Nothing to write to KML: this file has no points, lines of two or more positions, or polygons.');
  const extra = [];
  if (shortPieces) extra.push({ label: 'Single-position line pieces', count: shortPieces, reason: 'A KML LineString needs two positions. These pieces, left by a cut at the antimeridian, are not exported.' });
  if (droppedAltitudes) extra.push({ label: 'Altitudes not written', count: droppedAltitudes, reason: 'A line, ring set or gx:Track only gets altitudes when every position in it has one, so a value is never invented.' });
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>',
    `<kml xmlns="http://www.opengis.net/kml/2.2"${usedGx ? ` xmlns:gx="${GX}"` : ''}>`, '  <Document>', `    <name>${escapeText(name)}</name>`, ...placemarks, '  </Document>', '</kml>', ''];
  const notes = ['A GeometryCollection or multi-part feature becomes one Placemark with a MultiGeometry. Lines where every position has a time become gx:Track (Google’s extension), and a single timed Point gets a TimeStamp.'];
  return { text: lines.join('\n'), counts: out, dropped: summarize(model, out.withTime, extra), notes, mime: 'application/vnd.google-earth.kml+xml' };
}
