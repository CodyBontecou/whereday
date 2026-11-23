// Read a KML document locally: counts, folder tree, placemark table and plot geometry. No styles, no network.
import { MAX_BYTES, MAX_POINTS } from './gpx-core.mjs';
import { instant } from './kml-gpx-core.mjs';

export { MAX_BYTES, MAX_POINTS };
const GX = 'http://www.google.com/kml/ext/2.2';
const KML_NAMESPACES = ['', 'http://earth.google.com/kml/2.0', 'http://earth.google.com/kml/2.1', 'http://earth.google.com/kml/2.2', 'http://www.opengis.net/kml/2.2'];
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
const MAX_DEPTH = 64;

// Elements the viewer does not draw, reported with counts. [local name, namespace key, label, reason]
export const SKIPPED_ELEMENTS = [
  ['NetworkLink', 'kml', 'NetworkLink', 'Not followed: this page makes no network requests, so linked content is not loaded.'],
  ['GroundOverlay', 'kml', 'GroundOverlay', 'Image overlays are not drawn.'],
  ['PhotoOverlay', 'kml', 'PhotoOverlay', 'Not drawn.'],
  ['ScreenOverlay', 'kml', 'ScreenOverlay', 'Not drawn.'],
  ['Model', 'kml', 'Model (3D)', 'Not drawn.'],
  ['Region', 'kml', 'Region', 'Region-based loading is not applied; everything in the file is read.'],
  ['Style', 'kml', 'Style', 'Colors and line widths are ignored.'],
  ['StyleMap', 'kml', 'StyleMap', 'Ignored.'],
  ['Icon', 'kml', 'Icon', 'Icons and images are not loaded or drawn.'],
  ['TimeStamp', 'kml', 'TimeStamp', 'Times on a feature are not shown; only gx:Track times are.'],
  ['TimeSpan', 'kml', 'TimeSpan', 'Times on a feature are not shown; only gx:Track times are.'],
  ['Tour', 'gx', 'gx:Tour', 'Not played.'],
  ['ExtendedData', 'kml', 'ExtendedData', 'Custom data fields are not shown.'],
];

const elementsOf = node => Array.from(node.childNodes).filter(n => n.nodeType === 1);
const kmlName = n => (KML_NAMESPACES.includes(n.namespaceURI || '') ? n.localName : null);
const gxName = n => (n.namespaceURI === GX ? n.localName : null);
const clean = s => s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '').trim();
const childText = (node, name) => { const n = elementsOf(node).find(e => kmlName(e) === name); return n ? clean(n.textContent) : ''; };
const iso = ms => new Date(ms).toISOString().replace('.000Z', 'Z');

function validPosition(parts) {
  if (parts.length < 2 || parts.length > 3 || !parts.every(p => DECIMAL.test(p))) return null;
  const [lon, lat, alt] = parts.map(Number);
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  return alt === undefined ? [lon, lat] : [lon, lat, alt];
}

export function parseXML(text, Parser = globalThis.DOMParser) {
  if (typeof text !== 'string') throw new Error('This file could not be read as text.');
  const bare = text.replace(/^\uFEFF/, '');
  if (new TextEncoder().encode(bare).length > MAX_BYTES) throw new Error('Choose a file of 10 MiB or less.');
  if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(bare)) throw new Error('DTD and entity declarations are not supported. Keep the original and use a standard export.');
  let doc;
  try { doc = new Parser().parseFromString(bare, 'application/xml'); }
  catch { throw new Error('This file is not well-formed XML. Keep the original and export a fresh copy.'); }
  const root = doc.documentElement;
  if (!root || doc.getElementsByTagName('parsererror').length) throw new Error('This file is not well-formed XML. Keep the original and export a fresh copy.');
  if (root.localName !== 'kml') throw new Error('Choose a KML or KMZ file. This XML file’s root element is not <kml>.');
  return root;
}

function bounds(coords) {
  if (!coords.length) return null;
  let [w, s, e, n] = [Infinity, Infinity, -Infinity, -Infinity];
  for (const [lon, lat] of coords) { w = Math.min(w, lon); e = Math.max(e, lon); s = Math.min(s, lat); n = Math.max(n, lat); }
  return { west: w, south: s, east: e, north: n };
}

export function parseKML(text, { Parser = globalThis.DOMParser } = {}) {
  const root = parseXML(text, Parser);
  const counts = { documents: 0, folders: 0, placemarks: 0, points: 0, lineStrings: 0, linearRings: 0, polygons: 0, innerRings: 0, multiGeometries: 0, gxTracks: 0, gxMultiTracks: 0, withTimes: 0, positions: 0, invalidPositions: 0 };
  const placemarks = [];
  const notes = [];
  let positions = 0;

  const addPositions = list => { positions += list.length; if (positions > MAX_POINTS) throw new Error('This file has more than 50,000 coordinates. Open a smaller file.'); };
  const parseList = value => {
    const out = [];
    for (const token of value.split(/\s+/).filter(Boolean)) { const p = validPosition(token.split(',')); if (p) out.push(p); else counts.invalidPositions++; }
    addPositions(out); counts.positions += out.length; return out;
  };

  // Returns {type, parts:[{kind, coords, times}]}; kind is point | line | ring.
  function geometry(el, parts) {
    const kml = kmlName(el), gx = gxName(el);
    if (kml === 'Point') {
      counts.points++;
      const c = elementsOf(el).find(e => kmlName(e) === 'coordinates');
      const coords = c ? parseList(clean(c.textContent)) : [];
      if (coords.length) parts.push({ kind: 'point', coords: [coords[0]] });
      return 'Point';
    }
    if (kml === 'LineString' || kml === 'LinearRing') {
      counts[kml === 'LineString' ? 'lineStrings' : 'linearRings']++;
      const c = elementsOf(el).find(e => kmlName(e) === 'coordinates');
      const coords = c ? parseList(clean(c.textContent)) : [];
      if (coords.length) parts.push({ kind: kml === 'LineString' ? 'line' : 'ring', coords });
      return kml;
    }
    if (kml === 'Polygon') {
      counts.polygons++;
      for (const side of elementsOf(el)) {
        const which = kmlName(side);
        if (which !== 'outerBoundaryIs' && which !== 'innerBoundaryIs') continue;
        if (which === 'innerBoundaryIs') counts.innerRings++;
        for (const ring of elementsOf(side).filter(e => kmlName(e) === 'LinearRing')) {
          const c = elementsOf(ring).find(e => kmlName(e) === 'coordinates');
          const coords = c ? parseList(clean(c.textContent)) : [];
          if (coords.length) parts.push({ kind: 'ring', inner: which === 'innerBoundaryIs', coords });
        }
      }
      return 'Polygon';
    }
    if (kml === 'MultiGeometry') {
      counts.multiGeometries++;
      const types = [];
      for (const child of elementsOf(el)) { const t = geometry(child, parts); if (t) types.push(t); }
      return types.length ? `MultiGeometry (${[...new Set(types)].join(', ')})` : 'MultiGeometry';
    }
    if (gx === 'Track') {
      counts.gxTracks++;
      const kids = elementsOf(el);
      const whens = kids.filter(e => kmlName(e) === 'when').map(e => clean(e.textContent));
      const coords = [], times = [];
      kids.filter(e => gxName(e) === 'coord').forEach((e, index) => {
        const p = validPosition(clean(e.textContent).split(/\s+/));
        if (!p) { counts.invalidPositions++; return; }
        coords.push(p);
        const t = index < whens.length ? instant(whens[index]) : null;
        times.push(t);
      });
      addPositions(coords); counts.positions += coords.length;
      const timed = times.filter(t => t !== null).length;
      counts.withTimes += timed;
      if (whens.length !== kids.filter(e => gxName(e) === 'coord').length) notes.push('A gx:Track has a different number of <when> times and <gx:coord> positions; times are matched by order and the extras are ignored.');
      if (coords.length) parts.push({ kind: 'line', coords, times });
      return 'gx:Track';
    }
    if (gx === 'MultiTrack') {
      counts.gxMultiTracks++;
      for (const child of elementsOf(el)) if (gxName(child) === 'Track') geometry(child, parts);
      return 'gx:MultiTrack';
    }
    return null;
  }

  function placemark(el, path) {
    counts.placemarks++;
    const parts = [], types = [];
    for (const child of elementsOf(el)) { const t = geometry(child, parts); if (t) types.push(t); }
    const coords = parts.flatMap(p => p.coords);
    const times = parts.flatMap(p => p.times || []).filter(t => t !== null);
    const item = {
      id: placemarks.length, name: childText(el, 'name'), description: childText(el, 'description'), folderPath: path,
      type: types.length ? [...new Set(types)].join(' + ') : 'No drawable geometry', parts, coordinateCount: coords.length,
      firstTime: times.length ? iso(Math.min(...times)) : '', lastTime: times.length ? iso(Math.max(...times)) : '', timeCount: times.length, bounds: bounds(coords),
    };
    placemarks.push(item);
    return item;
  }

  function container(el, node, path, depth) {
    if (depth > MAX_DEPTH) { notes.push('Folders nested more than 64 levels deep were not read.'); return; }
    for (const child of elementsOf(el)) {
      const name = kmlName(child);
      if (name === 'Placemark') node.children.push({ type: 'Placemark', id: placemark(child, path).id });
      else if (name === 'Folder' || name === 'Document') {
        counts[name === 'Folder' ? 'folders' : 'documents']++;
        const sub = { type: name, name: childText(child, 'name'), description: childText(child, 'description'), children: [] };
        node.children.push(sub);
        container(child, sub, [...path, sub.name || name], depth + 1);
      }
    }
  }

  const tree = { type: 'kml', name: '', description: '', children: [] };
  container(root, tree, [], 0);

  // Count every skipped element anywhere in the file, including inside folders and placemarks.
  const found = new Map();
  for (const el of root.getElementsByTagName('*')) {
    for (const [local, ns, label, reason] of SKIPPED_ELEMENTS) {
      if (el.localName === local && (ns === 'gx' ? el.namespaceURI === GX : KML_NAMESPACES.includes(el.namespaceURI || ''))) {
        const entry = found.get(label) || { label, reason, count: 0 }; entry.count++; found.set(label, entry);
      }
    }
  }
  const skipped = SKIPPED_ELEMENTS.map(s => found.get(s[2])).filter(Boolean);
  const noGeometry = placemarks.filter(p => !p.coordinateCount).length;
  if (noGeometry) skipped.push({ label: 'Placemarks with nothing to draw', count: noGeometry, reason: 'No usable Point, LineString, LinearRing, Polygon or gx:Track coordinates.' });
  if (counts.invalidPositions) skipped.push({ label: 'Invalid coordinates', count: counts.invalidPositions, reason: 'Not numbers, or outside longitude ±180 or latitude ±90. Left out.' });

  const all = placemarks.flatMap(p => p.parts.flatMap(part => part.coords));
  return { counts, tree, placemarks, skipped, notes: [...new Set(notes)], bounds: bounds(all), name: childText(root, 'name') || tree.children.find(c => c.type === 'Document')?.name || '' };
}

export function formatCoordinate(c) { return c.join(','); }
export function formatBounds(b) { return b ? `${b.west},${b.south} to ${b.east},${b.north}` : ''; }

// Project to a local plane (equirectangular scaled by cos of the middle latitude). North is up.
export function plotShapes(model, width = 800, height = 360, pad = 24, maxVertices = 2000) {
  const b = model.bounds;
  if (!b) return [];
  const mid = (b.north + b.south) / 2, scale = Math.cos(mid * Math.PI / 180) || 1;
  const w = Math.max((b.east - b.west) * scale, 1e-9), h = Math.max(b.north - b.south, 1e-9);
  const k = Math.min((width - 2 * pad) / w, (height - 2 * pad) / h);
  const ox = (width - w * k) / 2, oy = (height - h * k) / 2;
  const project = ([lon, lat]) => [ox + (lon - b.west) * scale * k, oy + (b.north - lat) * k];
  const thin = coords => { if (coords.length <= maxVertices) return coords; const step = (coords.length - 1) / (maxVertices - 1); return Array.from({ length: maxVertices }, (_, i) => coords[Math.round(i * step)]); };
  const shapes = [];
  for (const p of model.placemarks) for (const part of p.parts) shapes.push({ id: p.id, kind: part.kind === 'ring' ? 'polygon' : part.kind === 'point' ? 'point' : 'line', inner: Boolean(part.inner), points: thin(part.coords).map(project) });
  return shapes;
}
