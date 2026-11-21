// Convert KML (and KMZ) to GPX 1.1 and GPX to KML 2.2 locally. No network, no dependencies.
// The GPX reader is the GPX viewer's parser, so limits and validation match the other tools.
import { parseGPX, MAX_BYTES, MAX_POINTS } from './gpx-core.mjs';

export { MAX_BYTES, MAX_POINTS };
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
const GX = 'http://www.google.com/kml/ext/2.2';
const GPX11 = 'http://www.topografix.com/GPX/1/1';
const KML22 = 'http://www.opengis.net/kml/2.2';
const CREATOR = 'Whereday KML-GPX converter (https://whereday.com/tools/kml-to-gpx/)';

const escapeText = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
// XML 1.0 forbids most control characters; drop them rather than write an unreadable file.
const clean = s => String(s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '');
const text = s => escapeText(clean(s));
const elementsOf = node => Array.from(node.childNodes).filter(n => n.nodeType === 1);
const childrenNamed = (node, name, ns) => elementsOf(node).filter(e => e.localName === name && (ns === undefined || (e.namespaceURI || '') === ns));
const firstText = (node, name) => { const e = childrenNamed(node, name, KML22).concat(childrenNamed(node, name, ''))[0]; return e ? clean(e.textContent.trim()) : ''; };
const isGx = e => (e.namespaceURI || '') === GX;

/** The point where a time becomes ISO 8601 UTC. Returns null for anything but a full date-time with a zone. */
export function isoUTC(value) {
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(\.\d+)?(Z|[+-]\d\d:\d\d)$/.exec((value || '').trim());
  if (!m) return null;
  const [y, month, day, h, min, sec] = m.slice(1, 7).map(Number);
  const days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || h > 23 || min > 59 || sec > 59) return null;
  const ms = Date.parse(m[0]);
  if (!Number.isFinite(ms)) return null;
  const iso = new Date(ms).toISOString();
  return iso.endsWith('.000Z') ? iso.slice(0, -5) + 'Z' : iso;
}

function parseXML(source, Parser, what) {
  if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(source)) throw new Error('DTD and entity declarations are not supported. Keep the original and use a standard export.');
  let doc;
  try { doc = new Parser().parseFromString(source, 'application/xml'); }
  catch { throw new Error(`This file is not well-formed XML. Keep the original and export a fresh ${what} copy.`); }
  if (!doc.documentElement || doc.getElementsByTagName('parsererror').length) throw new Error(`This file is not well-formed XML. Keep the original and export a fresh ${what} copy.`);
  return doc;
}

// ---------------------------------------------------------------- KMZ (a zip with doc.kml)
async function inflateRaw(bytes, limit) {
  if (typeof DecompressionStream === 'undefined') throw new Error('This browser cannot open KMZ files. Unzip the KMZ, then choose the doc.kml file inside.');
  const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
  const chunks = []; let total = 0;
  for (;;) {
    let step;
    try { step = await reader.read(); } catch { throw new Error('This KMZ could not be unzipped. Keep the original and export a fresh copy.'); }
    if (step.done) break;
    total += step.value.length;
    if (total > limit) { await reader.cancel(); throw new Error('The KML inside this KMZ is larger than 10 MiB.'); }
    chunks.push(step.value);
  }
  const out = new Uint8Array(total); let at = 0;
  for (const c of chunks) { out.set(c, at); at += c.length; }
  return out;
}

const BAD_ZIP = 'This KMZ is not a readable zip archive. Keep the original and export a fresh copy.';
/** Read the KML document out of a KMZ archive (stored or deflated entries). Images and other files are ignored. */
export async function unzipKML(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error(BAD_ZIP);
  const count = view.getUint16(eocd + 10, true);
  let at = view.getUint32(eocd + 16, true);
  const entries = [];
  for (let n = 0; n < count; n++) {
    if (at + 46 > bytes.length || view.getUint32(at, true) !== 0x02014b50) throw new Error(BAD_ZIP);
    const nameLen = view.getUint16(at + 28, true), extraLen = view.getUint16(at + 30, true), commentLen = view.getUint16(at + 32, true);
    entries.push({
      flags: view.getUint16(at + 8, true), method: view.getUint16(at + 10, true), compressed: view.getUint32(at + 20, true), size: view.getUint32(at + 24, true),
      offset: view.getUint32(at + 42, true), name: new TextDecoder().decode(bytes.subarray(at + 46, at + 46 + nameLen)),
    });
    at += 46 + nameLen + extraLen + commentLen;
  }
  const kmls = entries.filter(e => /\.kml$/i.test(e.name));
  const entry = kmls.find(e => e.name.toLowerCase() === 'doc.kml') || kmls.sort((a, b) => a.name.split('/').length - b.name.split('/').length)[0];
  if (!entry) throw new Error('No .kml file was found inside this KMZ.');
  if (entry.flags & 1) throw new Error('This KMZ is encrypted, which is not supported.');
  if (entry.size > MAX_BYTES) throw new Error('The KML inside this KMZ is larger than 10 MiB.');
  const o = entry.offset;
  if (o + 30 > bytes.length || view.getUint32(o, true) !== 0x04034b50) throw new Error(BAD_ZIP);
  const start = o + 30 + view.getUint16(o + 26, true) + view.getUint16(o + 28, true);
  if (start + entry.compressed > bytes.length) throw new Error(BAD_ZIP);
  const data = bytes.subarray(start, start + entry.compressed);
  if (entry.method === 0) return data;
  if (entry.method !== 8) throw new Error('This KMZ uses a compression method that is not supported.');
  return inflateRaw(data, MAX_BYTES);
}

// ---------------------------------------------------------------- KML -> GPX
const STYLE_TAGS = new Set(['Style', 'StyleMap']);
const OVERLAYS = new Set(['GroundOverlay', 'PhotoOverlay', 'ScreenOverlay']);
const tooMany = () => new Error('This file has more than 50,000 positions. Export a smaller date range and try again.');

function lonLat(token) {
  const parts = token.split(',');
  if (parts.length < 2 || parts.length > 3 || !DECIMAL.test(parts[0]) || !DECIMAL.test(parts[1])) return null;
  return place(parts[0], parts[1], parts[2]);
}
function place(lon, lat, alt) {
  if (Math.abs(Number(lat)) > 90 || Math.abs(Number(lon)) > 180) return null;
  const ele = alt === undefined || alt === '' ? null : (DECIMAL.test(alt) ? alt : undefined);
  return { lat, lon, ele: ele ?? null, badEle: ele === undefined };
}

function readKML(doc) {
  const root = doc.documentElement;
  const out = { waypoints: [], tracks: [], placemarks: 0, lineStrings: 0, gxTracks: 0, points: 0, rejected: 0, badElevation: 0, altitudeModeNotes: 0, seen: 0, name: '' };
  const skipped = new Map();
  const skip = (key, n = 1) => skipped.set(key, (skipped.get(key) || 0) + n);
  let first = Infinity, last = -Infinity;
  const stamp = iso => { const ms = Date.parse(iso); if (ms < first) first = ms; if (ms > last) last = ms; return iso; };
  const budget = () => { if (++out.seen > MAX_POINTS) throw tooMany(); };
  const modeOf = (el, inherited) => {
    const m = childrenNamed(el, 'altitudeMode').map(e => e.textContent.trim())[0];
    return m || inherited || '';
  };
  const addPoint = (list, c, time, mode) => {
    if (c.badEle) out.badElevation++;
    const point = { lat: c.lat, lon: c.lon, ele: c.badEle ? null : c.ele, time: time || null };
    if (point.ele !== null && mode && mode !== 'absolute') out.altitudeModeNotes++;
    list.push(point);
  };

  const all = []; const stack = [root];
  while (stack.length) { const n = stack.pop(); all.push(n); const kids = elementsOf(n); for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]); }
  out.name = (() => { const doc1 = elementsOf(root).find(e => ['Document', 'Folder', 'Placemark'].includes(e.localName)); return doc1 ? firstText(doc1, 'name') : ''; })();

  for (const el of all) {
    const tag = el.localName, gx = isGx(el);
    if (!gx && tag === 'Polygon') skip('Polygon');
    else if (!gx && tag === 'LinearRing' && !['outerBoundaryIs', 'innerBoundaryIs'].includes(el.parentNode?.localName)) skip('LinearRing');
    else if (!gx && tag === 'NetworkLink') skip('NetworkLink');
    else if (!gx && tag === 'Model') skip('Model');
    else if (!gx && OVERLAYS.has(tag)) skip('Overlay');
    else if (!gx && STYLE_TAGS.has(tag)) skip('Style');
    else if (gx && tag === 'angles') skip('Angles');
  }

  for (const placemark of all.filter(e => e.localName === 'Placemark' && !isGx(e))) {
    out.placemarks++;
    const name = firstText(placemark, 'name'), desc = firstText(placemark, 'description');
    if (childrenNamed(placemark, 'ExtendedData').length) skip('ExtendedData');
    // Collect geometries in document order, looking inside MultiGeometry and gx:MultiTrack.
    const geoms = []; const todo = elementsOf(placemark).reverse().map(e => ({ e, mode: '' }));
    while (todo.length) {
      const { e, mode } = todo.pop();
      const kids = elementsOf(e).reverse();
      if (e.localName === 'MultiGeometry' || (isGx(e) && e.localName === 'MultiTrack')) { const m = modeOf(e, mode); for (const k of kids) todo.push({ e: k, mode: m }); }
      else if (['Point', 'LineString'].includes(e.localName) && !isGx(e)) geoms.push({ e, mode: modeOf(e, mode) });
      else if (isGx(e) && e.localName === 'Track') geoms.push({ e, mode: modeOf(e, mode) });
    }
    const timestamp = (() => { const ts = childrenNamed(placemark, 'TimeStamp')[0]; return ts ? firstText(ts, 'when') : ''; })();
    const hasTimePrimitive = childrenNamed(placemark, 'TimeStamp').length + childrenNamed(placemark, 'TimeSpan').length > 0;
    const segments = [];
    let linePlacemark = false;
    for (const { e, mode } of geoms) {
      if (e.localName === 'Point') {
        out.points++; budget();
        const c = lonLat(firstText(e, 'coordinates'));
        if (!c) { out.rejected++; continue; }
        const time = isoUTC(timestamp);
        const list = []; addPoint(list, c, time && stamp(time), mode);
        out.waypoints.push({ ...list[0], name, desc });
      } else if (e.localName === 'LineString') {
        out.lineStrings++; linePlacemark = true;
        const list = [];
        for (const token of firstText(e, 'coordinates').split(/\s+/).filter(Boolean)) {
          budget();
          const c = lonLat(token);
          if (!c) { out.rejected++; continue; }
          addPoint(list, c, null, mode);
        }
        if (list.length) segments.push(list);
      } else {
        out.gxTracks++;
        const whens = childrenNamed(e, 'when').map(w => w.textContent.trim());
        const coords = childrenNamed(e, 'coord', GX).map(c => c.textContent.trim());
        const list = [];
        // A gx:Track pairs the nth <when> with the nth <gx:coord>; anything unpaired is rejected.
        out.rejected += Math.abs(whens.length - coords.length);
        for (let i = 0; i < Math.min(whens.length, coords.length); i++) {
          budget();
          const parts = coords[i].split(/\s+/);
          const c = parts.length >= 2 && parts.length <= 3 && parts.every(v => DECIMAL.test(v)) ? place(parts[0], parts[1], parts[2]) : null;
          if (!c) { out.rejected++; continue; }
          const time = isoUTC(whens[i]);
          addPoint(list, c, time && stamp(time), mode);
        }
        if (list.length) segments.push(list);
      }
    }
    if (linePlacemark && hasTimePrimitive) skip('LineTime');
    if (segments.length) out.tracks.push({ name, desc, segments });
    if (!geoms.length && !['Polygon', 'LinearRing', 'Model'].some(t => placemark.getElementsByTagName(t).length)) skip('NoGeometry');
  }
  out.firstTime = Number.isFinite(first) ? new Date(first).toISOString() : null;
  out.lastTime = Number.isFinite(last) ? new Date(last).toISOString() : null;
  out.skipped = skipped;
  return out;
}

const SKIP_REASONS = {
  Polygon: ['Polygons', 'GPX has no area type. They are not turned into tracks, so no border is mistaken for a route.'],
  LinearRing: ['LinearRings', 'A closed ring outside a Polygon is an area outline, which GPX cannot hold.'],
  NetworkLink: ['NetworkLinks', 'Links to other files are never followed, so nothing is fetched. Open the linked KML separately.'],
  Model: ['3D models', 'GPX has no model type.'],
  Overlay: ['Image overlays', 'GroundOverlay, PhotoOverlay and ScreenOverlay images are not converted.'],
  Style: ['Styles and icons', 'Line colours, widths and icons have no GPX equivalent. Style and StyleMap definitions were dropped.'],
  ExtendedData: ['ExtendedData', 'Custom fields and data tables on Placemarks are not copied.'],
  Angles: ['gx:angles', 'Heading, tilt and roll values on gx:Track are not copied.'],
  LineTime: ['TimeStamp / TimeSpan on lines', 'A plain LineString has one time for the whole line, not one per point, so it cannot become track-point times.'],
  NoGeometry: ['Placemarks without a geometry', 'Nothing to convert.'],
};
const skippedList = map => [...map.entries()].map(([key, count]) => ({ item: SKIP_REASONS[key][0], count, reason: SKIP_REASONS[key][1] }));

const gpxPoint = (tag, p, name, desc) => `<${tag} lat="${p.lat}" lon="${p.lon}">${p.ele !== null ? `<ele>${p.ele}</ele>` : ''}${p.time ? `<time>${p.time}</time>` : ''}${name ? `<name>${text(name)}</name>` : ''}${desc ? `<desc>${text(desc)}</desc>` : ''}</${tag}>`;

function kmlToGPX(source, Parser) {
  const doc = parseXML(source, Parser, 'KML');
  const k = readKML(doc);
  const trackPoints = k.tracks.reduce((n, t) => n + t.segments.reduce((m, s) => m + s.length, 0), 0);
  const all = [...k.waypoints, ...k.tracks.flatMap(t => t.segments.flat())];
  const counts = { timed: all.filter(p => p.time).length, elevation: all.filter(p => p.ele !== null).length };
  const skipped = skippedList(k.skipped);
  if (!k.waypoints.length && !k.tracks.length) {
    const why = [k.rejected ? `${k.rejected} coordinate${k.rejected === 1 ? ' was' : 's were'} invalid` : '', ...skipped.map(s => `${s.count} ${s.item}`)].filter(Boolean).join('; ');
    throw new Error(`No Point, LineString or gx:Track with valid coordinates was found in this KML.${why ? ` Found: ${why}.` : ''}`);
  }
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>', `<gpx version="1.1" creator="${escapeText(CREATOR)}" xmlns="${GPX11}">`];
  if (k.name) lines.push(`  <metadata><name>${text(k.name)}</name></metadata>`);
  for (const w of k.waypoints) lines.push('  ' + gpxPoint('wpt', w, w.name, w.desc));
  for (const t of k.tracks) {
    lines.push('  <trk>');
    if (t.name) lines.push(`    <name>${text(t.name)}</name>`);
    if (t.desc) lines.push(`    <desc>${text(t.desc)}</desc>`);
    for (const s of t.segments) {
      lines.push('    <trkseg>');
      for (const p of s) lines.push('      ' + gpxPoint('trkpt', p));
      lines.push('    </trkseg>');
    }
    lines.push('  </trk>');
  }
  lines.push('</gpx>', '');
  const notes = [];
  if (k.badElevation) notes.push(`${k.badElevation} altitude value${k.badElevation === 1 ? ' was' : 's were'} not a number and left out; the points were kept.`);
  if (k.altitudeModeNotes) notes.push(`${k.altitudeModeNotes} altitude value${k.altitudeModeNotes === 1 ? ' has' : 's have'} an altitudeMode other than absolute (KML’s default is clampToGround). The numbers were copied to <ele> as they are, but may not be metres above sea level. Check them before relying on elevation.`);
  const segments = k.tracks.reduce((n, t) => n + t.segments.length, 0);
  return {
    direction: 'kml-to-gpx', output: lines.join('\n'), extension: 'gpx', mime: 'application/gpx+xml',
    report: {
      direction: 'kml-to-gpx',
      input: { placemarks: k.placemarks, points: k.points, lineStrings: k.lineStrings, gxTracks: k.gxTracks, timedPoints: counts.timed, elevationPoints: counts.elevation },
      output: { waypoints: k.waypoints.length, tracks: k.tracks.length, segments, trackPoints, timedPoints: counts.timed, elevationPoints: counts.elevation },
      rejected: k.rejected, skipped, notes, firstTime: k.firstTime, lastTime: k.lastTime,
    },
  };
}

// ---------------------------------------------------------------- GPX -> KML
const fmt = ms => { const iso = new Date(ms).toISOString(); return iso.endsWith('.000Z') ? iso.slice(0, -5) + 'Z' : iso; };

function gpxToKML(source, Parser, keepTimestamps) {
  const model = parseGPX(source, Parser);
  const ns = model.namespace || '';
  const raw = (el, name) => (childrenNamed(el, name, ns)[0]?.textContent.trim()) || '';
  const other = new Map();
  const tally = (el, own = ['ele', 'time', 'name', 'desc']) => { for (const c of elementsOf(el)) { const n = c.localName; if (!own.includes(n)) other.set(n, (other.get(n) || 0) + 1); } };
  const prep = p => {
    tally(p.element);
    return { lon: p.element.getAttribute('lon').trim(), lat: p.element.getAttribute('lat').trim(), ele: p.elevation !== null ? raw(p.element, 'ele') : null, time: p.time !== null ? fmt(p.time) : null, name: raw(p.element, 'name'), desc: raw(p.element, 'desc') };
  };
  const coord = p => `${p.lon},${p.lat}${p.ele !== null ? ',' + p.ele : ''}`;
  const mode = pts => pts.some(p => p.ele !== null) ? '<altitudeMode>absolute</altitudeMode>' : '';
  const stats = { timedIn: model.points.filter(p => p.time !== null).length, elevationIn: model.points.filter(p => p.elevation !== null).length, timedOut: 0, elevationOut: 0, singles: 0, droppedTimes: 0 };
  let usesGx = false;
  const geometry = (pts, allowTimed) => {
    const timed = allowTimed && keepTimestamps && pts.every(p => p.time !== null);
    stats.elevationOut += pts.filter(p => p.ele !== null).length;
    if (timed) {
      usesGx = true; stats.timedOut += pts.length;
      return { timed, xml: `<gx:Track>${mode(pts)}${pts.map(p => `<when>${p.time}</when>`).join('')}${pts.map(p => `<gx:coord>${p.lon} ${p.lat}${p.ele !== null ? ' ' + p.ele : ''}</gx:coord>`).join('')}</gx:Track>` };
    }
    stats.droppedTimes += pts.filter(p => p.time !== null).length;
    if (pts.length === 1) { stats.singles++; return { timed: false, xml: `<Point>${mode(pts)}<coordinates>${coord(pts[0])}</coordinates></Point>` }; }
    return { timed: false, xml: `<LineString><tessellate>1</tessellate>${mode(pts)}<coordinates>${pts.map(coord).join(' ')}</coordinates></LineString>` };
  };
  const placemark = (name, desc, body, time) => `    <Placemark>${name ? `<name>${text(name)}</name>` : ''}${desc ? `<description>${text(desc)}</description>` : ''}${time ? `<TimeStamp><when>${time}</when></TimeStamp>` : ''}${body}</Placemark>`;
  const line = (el, groups) => {
    const geoms = groups.filter(g => g.length).map(g => geometry(g, true));
    if (!geoms.length) return null;
    let body;
    if (geoms.length === 1) body = geoms[0].xml;
    else if (geoms.every(g => g.timed)) body = `<gx:MultiTrack><gx:interpolate>0</gx:interpolate>${geoms.map(g => g.xml).join('')}</gx:MultiTrack>`;
    else body = `<MultiGeometry>${geoms.map(g => g.xml).join('')}</MultiGeometry>`;
    tally(el, ['name', 'desc', 'trkseg', 'rtept']);
    return placemark(raw(el, 'name'), raw(el, 'desc'), body);
  };

  const wpts = [], routes = [], tracks = new Map();
  for (const g of model.groups) {
    const pts = g.points.filter(Boolean).map(prep);
    if (g.kind === 'waypoint') { if (pts.length) wpts.push(pts[0]); }
    else if (g.kind === 'route') routes.push({ el: g.source, groups: [pts] });
    else { if (!tracks.has(g.source)) tracks.set(g.source, { el: g.source, groups: [] }); tracks.get(g.source).groups.push(pts); }
  }
  const placemarks = [];
  let waypointOut = 0, routeOut = 0, trackOut = 0, segmentOut = 0;
  for (const w of wpts) {
    waypointOut++; stats.elevationOut += w.ele !== null ? 1 : 0;
    // A waypoint time goes in a TimeStamp, which a plain Point can carry.
    const stampTime = keepTimestamps && w.time ? w.time : null;
    if (stampTime) stats.timedOut++; else if (w.time) stats.droppedTimes++;
    placemarks.push(placemark(w.name, w.desc, `<Point>${mode([w])}<coordinates>${coord(w)}</coordinates></Point>`, stampTime));
  }
  for (const r of routes) { const pm = line(r.el, r.groups); if (pm) { routeOut++; segmentOut++; placemarks.push(pm); } }
  for (const t of tracks.values()) { const pm = line(t.el, t.groups); if (pm) { trackOut++; segmentOut += t.groups.filter(g => g.length).length; placemarks.push(pm); } }
  if (!placemarks.length) throw new Error('No waypoints, routes or tracks with valid coordinates were found in this GPX.');

  const root = model.groups[0]?.source?.ownerDocument?.documentElement;
  const meta = root ? childrenNamed(root, 'metadata', ns)[0] : null;
  const docName = meta ? raw(meta, 'name') : '';
  const lines = ['<?xml version="1.0" encoding="UTF-8"?>', `<kml xmlns="${KML22}"${usesGx ? ` xmlns:gx="${GX}"` : ''}>`, '  <Document>'];
  if (docName) lines.push(`    <name>${text(docName)}</name>`);
  lines.push(...placemarks, '  </Document>', '</kml>', '');

  const skipped = [];
  if (stats.droppedTimes) skipped.push({ item: 'Timestamps', count: stats.droppedTimes, reason: keepTimestamps
    ? 'These points are in a segment where not every point has a time, so it became a plain LineString, which cannot carry times.'
    : 'You turned off “keep timestamps (gx:Track)”, so lines were written as plain LineStrings, which cannot carry times.' });
  if (other.size) skipped.push({ item: 'Other GPX fields', count: [...other.values()].reduce((a, b) => a + b, 0), reason: `Not copied to KML: ${[...other.keys()].sort().join(', ')}.` });
  if (stats.singles) skipped.push({ item: 'Single-point segments', count: stats.singles, reason: 'A LineString needs two points, so a segment with one point was written as a Point.' });
  const notes = [];
  if (root && !model.namespace) notes.push('The GPX namespace is missing. The known GPX elements were read without a namespace.');
  return {
    direction: 'gpx-to-kml', output: lines.join('\n'), extension: 'kml', mime: 'application/vnd.google-earth.kml+xml', usesGx,
    report: {
      direction: 'gpx-to-kml',
      input: { waypoints: model.waypoints, routes: model.routes, tracks: model.tracks, segments: model.segments, points: model.points.length, timedPoints: stats.timedIn, elevationPoints: stats.elevationIn },
      output: { placemarks: placemarks.length, waypoints: waypointOut, routes: routeOut, tracks: trackOut, segments: segmentOut, timedPoints: stats.timedOut, elevationPoints: stats.elevationOut },
      rejected: model.issues.invalidCoordinates, skipped, notes, firstTime: model.firstTime, lastTime: model.lastTime,
    },
  };
}

// ---------------------------------------------------------------- entry points
/** The download name: the input's base name with the new extension. */
export function outputName(inputName, extension) {
  const base = String(inputName || '').replace(/^.*[\\/]/, '').replace(/\.[^.]*$/, '').replace(/[\u0000-\u001F<>:"|?*]/g, '').trim();
  return `${base || 'converted'}.${extension}`;
}

/**
 * Convert one file's bytes. The format is detected from the content, not the file name:
 * a zip is read as KMZ, otherwise the XML root decides between KML and GPX.
 */
export async function convertFile(bytes, { Parser = globalThis.DOMParser, keepTimestamps = true } = {}) {
  if (!(bytes instanceof Uint8Array)) throw new Error('The file could not be read.');
  if (bytes.length > MAX_BYTES) throw new Error('Choose a file of 10 MiB or less.');
  if (!bytes.length) throw new Error('This file is empty. Export it again from the app that made it.');
  let source, sourceFormat;
  if (bytes.length > 3 && bytes[0] === 0x50 && bytes[1] === 0x4b && (bytes[2] === 0x03 || bytes[2] === 0x05)) {
    source = new TextDecoder().decode(await unzipKML(bytes)); sourceFormat = 'KMZ';
  } else source = new TextDecoder().decode(bytes);
  const body = source.replace(/^﻿/, '').trimStart();
  const root = body[0] === '<' ? /<(?!\?|!)\s*(?:[A-Za-z_][\w.-]*:)?([A-Za-z_][\w.-]*)/.exec(body)?.[1] : null;
  if (root === 'kml') return { ...kmlToGPX(body, Parser), sourceFormat: sourceFormat || 'KML' };
  if (root === 'gpx' && !sourceFormat) return { ...gpxToKML(body, Parser, keepTimestamps), sourceFormat: 'GPX' };
  throw new Error('Choose a KML, KMZ or GPX file. Google Timeline JSON, GeoJSON and CSV are not converted here.');
}

export const COUNT_LABELS = {
  placemarks: 'Placemarks', points: 'Points', lineStrings: 'LineStrings', gxTracks: 'gx:Tracks', waypoints: 'Waypoints', routes: 'Routes', tracks: 'Tracks',
  segments: 'Track segments', trackPoints: 'Track points', timedPoints: 'Points with a time', elevationPoints: 'Points with elevation',
};
