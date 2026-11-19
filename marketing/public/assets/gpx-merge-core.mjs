// Merge GPX files locally. Parsing and validation reuse the GPX viewer's parser.
import { parseGPX, MAX_BYTES, MAX_POINTS } from './gpx-core.mjs';

export { MAX_BYTES, MAX_POINTS };
export const MAX_FILES = 50;
export const MAX_TOTAL_BYTES = 50 * 1024 * 1024;
export const MAX_TOTAL_POINTS = 250000;
export const MODES = ['separate', 'segments', 'continuous'];
const GPX10 = 'http://www.topografix.com/GPX/1/0', GPX11 = 'http://www.topografix.com/GPX/1/1';
// Spreading 250,000 values into Math.min can exceed the engine's argument limit.
const lowest = values => values.reduce((a, b) => (b < a ? b : a), Infinity);
const highest = values => values.reduce((a, b) => (b > a ? b : a), -Infinity);
const GPX_NAMESPACES = ['', GPX10, GPX11];
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
const UINT = /^\+?\d+$/;
const FIX = ['none', '2d', '3d', 'dgps', 'pps'];
const GAP_WARNING_MS = 600000;

const escapeText = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const escapeAttr = s => escapeText(s).replaceAll('"', '&quot;');
const elementsOf = node => Array.from(node.childNodes).filter(n => n.nodeType === 1);
// XML 1.0 forbids most control characters; drop them rather than write an unreadable file.
const clean = s => s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '');

// Ordered GPX 1.1 child elements. Order matters for schema validity.
const POINT_FIELDS = [['ele', 'ele'], ['time', 'time'], ['magvar', 'degrees'], ['geoidheight', 'decimal'], ['name', 'string'], ['cmt', 'string'], ['desc', 'string'], ['src', 'string'], ['link', 'link'], ['sym', 'string'], ['type', 'string'], ['fix', 'fix'], ['sat', 'uint'], ['hdop', 'decimal'], ['vdop', 'decimal'], ['pdop', 'decimal'], ['ageofdgpsdata', 'decimal'], ['dgpsid', 'dgpsid'], ['extensions', 'extensions']];
const GROUP_FIELDS = [['name', 'string'], ['cmt', 'string'], ['desc', 'string'], ['src', 'string'], ['link', 'link'], ['number', 'uint'], ['type', 'string'], ['extensions', 'extensions']];
const VALID = {
  decimal: v => DECIMAL.test(v),
  degrees: v => DECIMAL.test(v) && Number(v) >= 0 && Number(v) <= 360,
  uint: v => UINT.test(v),
  dgpsid: v => UINT.test(v) && Number(v) <= 1023,
  fix: v => FIX.includes(v),
};

// Serialize the children of a GPX element as GPX 1.1, keeping only fields the schema allows.
// Anything else is counted in stats.droppedFields so the page can say so.
function fields(element, spec, ns, legacy, stats, serialize, point = null) {
  const own = elementsOf(element);
  const gpx = own.filter(n => (n.namespaceURI || '') === ns);
  const foreign = own.filter(n => !GPX_NAMESPACES.includes(n.namespaceURI || ''));
  const used = new Set();
  const take = name => { const n = gpx.find(e => e.localName === name && !used.has(e)); if (n) used.add(n); return n; };
  const out = [];
  for (const [name, kind] of spec) {
    if (kind === 'ele') { const n = take('ele'); if (n && point.elevation !== null) out.push(`<ele>${escapeText(n.textContent.trim())}</ele>`); else if (n) stats.droppedFields++; continue; }
    if (kind === 'time') { const n = take('time'); if (n && point.time !== null) out.push(`<time>${escapeText(point.rawTime)}</time>`); else if (n) stats.droppedFields++; continue; }
    if (kind === 'link') {
      if (legacy) {
        const url = take('url'), label = take('urlname');
        if (url?.textContent.trim()) out.push(`<link href="${escapeAttr(clean(url.textContent.trim()))}">${label?.textContent.trim() ? `<text>${escapeText(clean(label.textContent.trim()))}</text>` : ''}</link>`);
        else if (label) stats.droppedFields++;
        continue;
      }
      for (let n = take('link'); n; n = take('link')) {
        const href = n.getAttribute('href')?.trim();
        if (!href) { stats.droppedFields++; continue; }
        const part = tag => elementsOf(n).find(e => e.localName === tag && (e.namespaceURI || '') === ns)?.textContent.trim();
        const text = part('text'), type = part('type');
        out.push(`<link href="${escapeAttr(clean(href))}">${text ? `<text>${escapeText(clean(text))}</text>` : ''}${type ? `<type>${escapeText(clean(type))}</type>` : ''}</link>`);
      }
      continue;
    }
    if (kind === 'extensions') {
      // GPX 1.1 keeps vendor data inside <extensions>; GPX 1.0 allows it directly on the element.
      const sources = legacy ? foreign : elementsOf(take('extensions') || { childNodes: [] });
      const kept = sources.filter(n => !GPX_NAMESPACES.includes(n.namespaceURI || ''));
      stats.droppedFields += sources.length - kept.length;
      if (legacy) for (const n of foreign) used.add(n);
      if (kept.length) out.push(`<extensions>${kept.map(n => serialize(n)).join('')}</extensions>`);
      continue;
    }
    const n = take(name);
    if (!n) continue;
    const text = n.textContent.trim();
    if (kind === 'string' ? text !== '' : VALID[kind](text)) out.push(`<${name}>${escapeText(clean(text))}</${name}>`);
    else stats.droppedFields++;
  }
  stats.droppedFields += own.filter(n => !used.has(n) && !(point === null && ['trkseg', 'rtept'].includes(n.localName) && (n.namespaceURI || '') === ns)).length;
  return out;
}

function pointRecord(p, ns, legacy, stats, serialize) {
  const body = fields(p.element, POINT_FIELDS, ns, legacy, stats, serialize, p);
  const latText = p.element.getAttribute('lat').trim(), lonText = p.element.getAttribute('lon').trim();
  return { lat: p.lat, lon: p.lon, time: p.time, attrs: `lat="${escapeAttr(latText)}" lon="${escapeAttr(lonText)}"`, body };
}

/** Parse one GPX file into a compact, DOM-free record for merging. Throws a readable Error. */
export function readGPX(text, name, { Parser = globalThis.DOMParser, Serializer = globalThis.XMLSerializer } = {}) {
  const model = parseGPX(text, Parser);
  const ns = model.namespace, legacy = model.version === '1.0';
  const serializer = new Serializer();
  const serialize = node => serializer.serializeToString(node);
  const stats = { droppedFields: 0, invalidCoordinates: model.issues.invalidCoordinates };
  const tracks = [], routes = [], waypoints = [];
  let currentTrack = null, currentSource = null;
  for (const group of model.groups) {
    const points = group.points.filter(Boolean).map(p => pointRecord(p, ns, legacy, stats, serialize));
    if (group.kind === 'track') {
      if (group.source !== currentSource) {
        currentSource = group.source;
        currentTrack = { header: fields(group.source, GROUP_FIELDS, ns, legacy, stats, serialize), segments: [] };
        tracks.push(currentTrack);
      }
      currentTrack.segments.push(points);
    } else if (group.kind === 'route') {
      routes.push({ header: fields(group.source, GROUP_FIELDS, ns, legacy, stats, serialize), points });
    } else if (points.length) waypoints.push(points[0]);
  }
  const keptTracks = tracks.map(t => ({ ...t, segments: t.segments.filter(s => s.length) })).filter(t => t.segments.length);
  return {
    name, bytes: new TextEncoder().encode(text).length, sourcePoints: model.sourcePoints,
    firstTime: model.firstTime ? Date.parse(model.firstTime) : null,
    lastTime: model.lastTime ? Date.parse(model.lastTime) : null,
    tracks: keptTracks, routes, waypoints, stats, version: model.version,
    counts: { tracks: model.tracks, segments: model.segments, trackpoints: model.groups.filter(g => g.kind === 'track').reduce((n, g) => n + g.points.filter(Boolean).length, 0), waypoints: model.waypoints, routes: model.routes },
  };
}

/** Timed files by first timestamp; files without any timestamp keep their upload order after them. */
export function orderFiles(files, sortByTime = true) {
  if (!sortByTime) return files.slice();
  const timed = files.filter(f => f.firstTime !== null).sort((a, b) => a.firstTime - b.firstTime || a.lastTime - b.lastTime);
  return [...timed, ...files.filter(f => f.firstTime === null)];
}

const R = 6371008.8;
export function distanceMeters(a, b) {
  const rad = Math.PI / 180, dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

const isoDate = ms => new Date(ms).toISOString().slice(0, 10);
const iso = ms => new Date(ms).toISOString().replace('.000Z', 'Z');
function span(first, last) {
  if (first === null) return '';
  const a = isoDate(first), b = isoDate(last);
  return a === b ? a : `${a} to ${b}`;
}

/**
 * Merge parsed files. mode: 'separate' keeps each input <trk>; 'segments' makes one <trk> keeping every
 * source <trkseg>; 'continuous' puts every track point into one <trkseg>.
 */
export function mergeGPX(inputFiles, { mode = 'separate', dedupe = true, sortByTime = true } = {}) {
  if (!MODES.includes(mode)) throw new Error(`Unknown merge mode: ${mode}`);
  if (!inputFiles.length) throw new Error('Add at least one GPX file to merge.');
  const files = orderFiles(inputFiles, sortByTime);
  const seen = new Set();
  let duplicatesRemoved = 0;
  const keep = p => {
    if (!dedupe || p.time === null) return true;
    const key = `${p.lat}|${p.lon}|${p.time}`;
    if (seen.has(key)) { duplicatesRemoved++; return false; }
    seen.add(key); return true;
  };
  // Tracks in file order; within a file, source order.
  const tracks = [];
  for (const f of files) for (const t of f.tracks) {
    const segments = t.segments.map(s => s.filter(keep)).filter(s => s.length);
    if (segments.length) tracks.push({ header: t.header, segments });
  }
  const sourceSegments = tracks.reduce((n, t) => n + t.segments.length, 0);
  const allSegments = tracks.flatMap(t => t.segments);
  const flat = allSegments.flat();
  const timedInputs = files.filter(f => f.firstTime !== null);
  const first = timedInputs.length ? lowest(timedInputs.map(f => f.firstTime)) : null;
  const last = first === null ? null : highest(timedInputs.map(f => f.lastTime));
  const label = span(first, last);
  const trackName = `Merged track${label ? `, ${label}` : ''}`;
  let outTracks;
  if (mode === 'separate') outTracks = tracks;
  else if (mode === 'segments') outTracks = flat.length ? [{ header: [`<name>${escapeText(trackName)}</name>`], segments: allSegments }] : [];
  else outTracks = flat.length ? [{ header: [`<name>${escapeText(trackName)}</name>`], segments: [flat] }] : [];

  const unique = (items, render) => { const keys = new Set(); let dropped = 0; const kept = []; for (const item of items) { const s = render(item); if (keys.has(s)) { dropped++; continue; } keys.add(s); kept.push(s); } return { kept, dropped }; };
  const renderPoint = (tag, p, indent) => p.body.length
    ? `${indent}<${tag} ${p.attrs}>\n${p.body.map(b => `${indent}  ${b}\n`).join('')}${indent}</${tag}>\n`
    : `${indent}<${tag} ${p.attrs}/>\n`;
  const wpts = unique(files.flatMap(f => f.waypoints), p => renderPoint('wpt', p, '  '));
  const rtes = unique(files.flatMap(f => f.routes), r => `  <rte>\n${r.header.map(h => `    ${h}\n`).join('')}${r.points.map(p => renderPoint('rtept', p, '    ')).join('')}  </rte>\n`);

  // Largest gap between consecutive track points in output order (time if both are timed, else distance).
  let gap = null;
  for (let i = 1; i < flat.length; i++) {
    const a = flat[i - 1], b = flat[i];
    const ms = a.time !== null && b.time !== null ? b.time - a.time : null;
    const meters = distanceMeters(a, b);
    const better = gap === null || (ms !== null && (gap.ms === null || ms > gap.ms)) || (ms === null && gap.ms === null && meters > gap.meters);
    if (better) gap = { ms, meters, from: a.time, to: b.time };
  }

  const warnings = [];
  // Overlap: a file starts before an earlier file in the merge has ended. Order: it starts before an earlier one started.
  let latest = null, latestName = '', previousStart = null, previousName = '';
  for (const f of timedInputs) {
    if (previousStart !== null && f.firstTime < previousStart) warnings.push({ kind: 'order', a: previousName, b: f.name });
    else if (latest !== null && f.firstTime < latest) warnings.push({ kind: 'overlap', a: latestName, b: f.name });
    if (latest === null || f.lastTime > latest) { latest = f.lastTime; latestName = f.name; }
    previousStart = f.firstTime; previousName = f.name;
  }
  const untimed = files.filter(f => f.firstTime === null).map(f => f.name);
  if (untimed.length && files.length > 1) warnings.push({ kind: 'untimed', files: untimed, sorted: sortByTime });
  if (mode === 'continuous' && sourceSegments > 1) warnings.push({ kind: 'straight-lines', joins: sourceSegments - 1, gap });
  else if (gap?.ms !== null && gap?.ms > GAP_WARNING_MS) warnings.push({ kind: 'gap', gap });

  const all = [...flat, ...files.flatMap(f => f.waypoints), ...files.flatMap(f => f.routes.flatMap(r => r.points))];
  const lats = all.map(p => p.lat), lons = all.map(p => p.lon);
  const timesOut = all.filter(p => p.time !== null).map(p => p.time);
  const firstOut = timesOut.length ? lowest(timesOut) : null, lastOut = timesOut.length ? highest(timesOut) : null;
  const name = `Merged GPX${label ? `, ${label}` : ''}`;
  const desc = `Merged locally from ${files.length} GPX file${files.length === 1 ? '' : 's'} with the Whereday GPX merge tool.` +
    (firstOut !== null ? ` Timed points run from ${iso(firstOut)} to ${iso(lastOut)} (UTC).` : ' The files contain no usable timestamps.');
  let xml = '<?xml version="1.0" encoding="UTF-8"?>\n' +
    `<gpx version="1.1" creator="Whereday GPX merge tool (whereday.com/tools/merge-gpx/)" xmlns="${GPX11}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="${GPX11} https://www.topografix.com/GPX/1/1/gpx.xsd">\n` +
    `  <metadata>\n    <name>${escapeText(name)}</name>\n    <desc>${escapeText(desc)}</desc>\n` +
    (all.length ? `    <bounds minlat="${lowest(lats)}" minlon="${lowest(lons)}" maxlat="${highest(lats)}" maxlon="${highest(lons)}"/>\n` : '') +
    '  </metadata>\n' + wpts.kept.join('') + rtes.kept.join('');
  for (const t of outTracks) {
    xml += `  <trk>\n${t.header.map(h => `    ${h}\n`).join('')}`;
    for (const s of t.segments) xml += `    <trkseg>\n${s.map(p => renderPoint('trkpt', p, '      ')).join('')}    </trkseg>\n`;
    xml += '  </trk>\n';
  }
  xml += '</gpx>\n';

  const fileDates = firstOut === null ? '' : isoDate(firstOut) === isoDate(lastOut) ? isoDate(firstOut) : `${isoDate(firstOut)}-to-${isoDate(lastOut)}`;
  // Groups in plotPaths' shape, so the viewer's plot draws exactly what the file contains.
  const plot = { points: all, groups: [...outTracks.flatMap(t => t.segments.map(points => ({ points }))), ...files.flatMap(f => f.routes.map(r => ({ points: r.points }))), ...files.flatMap(f => f.waypoints.map(p => ({ points: [p] })))] };
  return {
    xml, filename: `merged${fileDates ? `-${fileDates}` : ''}.gpx`, order: files.map(f => f.name), plot, warnings,
    summary: {
      files: files.length, tracks: outTracks.length, segments: outTracks.reduce((n, t) => n + t.segments.length, 0),
      trackpoints: flat.length, waypoints: wpts.kept.length, routes: rtes.kept.length,
      routePoints: files.reduce((n, f) => n + f.routes.reduce((m, r) => m + r.points.length, 0), 0),
      firstTime: firstOut === null ? null : new Date(firstOut).toISOString(), lastTime: lastOut === null ? null : new Date(lastOut).toISOString(),
      duplicatesRemoved, duplicateWaypoints: wpts.dropped, duplicateRoutes: rtes.dropped, gap,
      droppedFields: files.reduce((n, f) => n + f.stats.droppedFields, 0), invalidCoordinates: files.reduce((n, f) => n + f.stats.invalidCoordinates, 0),
    },
  };
}
