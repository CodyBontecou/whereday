// GPX editor logic. Everything runs in the page; nothing here touches the network.
// Parsing and serialisation of points reuse the merge tool (which reuses the GPX viewer's parser).
import { readGPX, distanceMeters, MAX_BYTES, MAX_POINTS } from './gpx-merge-core.mjs';
import { crc32 } from './kmz-zip.mjs';

export { distanceMeters, MAX_BYTES, MAX_POINTS };
export const DEFAULT_SPEED_KMH = 300;
export const ZONE_MIN_M = 100, ZONE_MAX_M = 1000;
const GPX11 = 'http://www.topografix.com/GPX/1/1';
const GPX_NS = ['', 'http://www.topografix.com/GPX/1/0', GPX11];
const escapeText = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const escapeAttr = s => escapeText(s).replaceAll('"', '&quot;');
const unescapeText = s => s.replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&');
const clean = s => s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F￾￿]/g, '');
const lowest = v => v.reduce((a, b) => (b < a ? b : a), Infinity);
const highest = v => v.reduce((a, b) => (b > a ? b : a), -Infinity);

// ---- Time zones -----------------------------------------------------------------------------
const formatters = new Map();
function zoneFormatter(zone) {
  if (!formatters.has(zone)) formatters.set(zone, new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }));
  return formatters.get(zone);
}
export function isValidZone(zone) { try { zoneFormatter(zone); return true; } catch { return false; } }
export function browserZone() { try { return new Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch { return 'UTC'; } }
function parts(ms, zone) {
  const o = {};
  for (const p of zoneFormatter(zone).formatToParts(new Date(ms))) if (p.type !== 'literal') o[p.type] = Number(p.value);
  return o;
}
const pad = (n, w = 2) => String(n).padStart(w, '0');
/** Local calendar date, YYYY-MM-DD, of an instant in a zone. */
export function dayKey(ms, zone) { const p = parts(ms, zone); return `${pad(p.year, 4)}-${pad(p.month)}-${pad(p.day)}`; }
/** Local date and time, YYYY-MM-DD HH:MM:SS, of an instant in a zone. */
export function formatInZone(ms, zone) { const p = parts(ms, zone); return `${pad(p.year, 4)}-${pad(p.month)}-${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}:${pad(p.second)}`; }
const asUtc = (ms, zone) => { const p = parts(ms, zone); return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second); };
/** Read "YYYY-MM-DDTHH:MM[:SS]" as wall-clock time in a zone and return the UTC instant, or null. */
export function zonedToUtc(text, zone) {
  const m = /^(\d{4})-(\d\d)-(\d\d)[T ](\d\d):(\d\d)(?::(\d\d))?$/.exec(text || '');
  if (!m) return null;
  const guess = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  let t = guess;
  for (let i = 0; i < 3; i++) t = guess - (asUtc(t, zone) - t);
  return t;
}
/** The same wall-clock text zonedToUtc reads, for filling a datetime-local input. */
export function toLocalInput(ms, zone) { return formatInZone(ms, zone).replace(' ', 'T'); }

// ---- Loading ----------------------------------------------------------------------------------
const headerName = header => { const h = header.find(x => x.startsWith('<name>')); const m = h && /^<name>([\s\S]*)<\/name>$/.exec(h); return m ? unescapeText(m[1]) : ''; };
const withName = (header, name) => [`<name>${escapeText(clean(name))}</name>`, ...header.filter(x => !x.startsWith('<name>'))];

function readMetadata(text, version, Parser) {
  const doc = new Parser().parseFromString(text, 'application/xml');
  const root = doc.documentElement;
  const kids = node => Array.from(node.childNodes).filter(n => n.nodeType === 1);
  const isGpx = n => GPX_NS.includes(n.namespaceURI || '');
  const container = version === '1.0' ? root : kids(root).find(n => n.localName === 'metadata' && isGpx(n));
  const out = { name: '', desc: '', kept: [], dropped: [] };
  const rootLevel = new Set(['wpt', 'rte', 'trk', 'metadata']);
  const vendor = [];
  if (container) for (const n of kids(container)) {
    if (version === '1.0' && rootLevel.has(n.localName)) continue;
    if (!isGpx(n)) { vendor.push(n); continue; }
    const value = n.textContent.trim();
    if (n.localName === 'name' && value) { out.name = value; out.kept.push('name'); }
    else if (n.localName === 'desc' && value) { out.desc = value; out.kept.push('description'); }
    else if (n.localName === 'bounds') out.dropped.push('bounds (recalculated from the points that remain)');
    else if (n.localName === 'extensions') out.dropped.push('metadata extensions');
    else out.dropped.push(n.localName === 'time' ? 'file creation time' : n.localName);
  }
  if (vendor.length) out.dropped.push('vendor elements in the file header');
  if (version !== '1.0' && kids(root).some(n => n.localName === 'extensions' && isGpx(n))) out.dropped.push('file-level extensions');
  return out;
}

/** Read a GPX file into an editable document. Throws a readable Error. */
export function loadGPX(text, fileName, { Parser = globalThis.DOMParser, Serializer = globalThis.XMLSerializer } = {}) {
  const rec = readGPX(text, fileName, { Parser, Serializer });
  const meta = readMetadata(text, rec.version, Parser);
  let n = 0;
  const tracks = rec.tracks.map(t => ({ header: t.header, segments: t.segments.map(s => s.map(p => ({ ...p, idx: ++n }))) }));
  const doc = { fileName, version: rec.version, meta, tracks, routes: rec.routes, waypoints: rec.waypoints, stats: rec.stats, sourcePoints: rec.sourcePoints, trackPoints: n };
  doc.timed = flatten(tracks).filter(p => p.time !== null).length;
  return doc;
}
const flatten = tracks => tracks.flatMap(t => t.segments.flat());
export const trackPoints = doc => flatten(doc.tracks);

// ---- Spike detection ---------------------------------------------------------------------------
/**
 * Flag track points whose implied speed is above the threshold. Speed is measured from the last point
 * that was not flagged, inside one segment, so one bad point is flagged once instead of also flagging
 * every real point after it. Points without a time, and pairs whose times are equal or run backwards,
 * cannot be checked.
 */
export function detectSpikes(doc, thresholdKmh = DEFAULT_SPEED_KMH) {
  const flagged = [];
  for (const track of doc.tracks) for (const segment of track.segments) {
    let anchor = null;
    for (const p of segment) {
      if (p.time === null) continue;
      if (anchor && p.time > anchor.time) {
        const meters = distanceMeters(anchor, p), kmh = meters / ((p.time - anchor.time) / 1000) * 3.6;
        if (kmh > thresholdKmh) { flagged.push({ idx: p.idx, lat: p.lat, lon: p.lon, time: p.time, kmh, meters, from: anchor.idx }); continue; }
      }
      anchor = p;
    }
  }
  return flagged;
}

// ---- Editing -----------------------------------------------------------------------------------
const spanOf = points => { const t = points.filter(p => p.time !== null).map(p => p.time); return t.length ? { first: lowest(t), last: highest(t) } : null; };
function counts(tracks, routes, waypoints) {
  const all = [...flatten(tracks), ...routes.flatMap(r => r.points), ...waypoints];
  return { tracks: tracks.length, segments: tracks.reduce((n, t) => n + t.segments.length, 0), trackpoints: flatten(tracks).length, routes: routes.length, routepoints: routes.reduce((n, r) => n + r.points.length, 0), waypoints: waypoints.length, span: spanOf(all) };
}

/** Group consecutive points of each segment by key, keeping track headers. Returns Map key -> tracks. */
function partition(tracks, keyOf) {
  const groups = new Map();
  for (const track of tracks) for (const segment of track.segments) {
    let run = null, runKey;
    for (const p of segment) {
      const key = keyOf(p);
      if (run === null || key !== runKey) {
        runKey = key; run = [];
        if (!groups.has(key)) groups.set(key, []);
        const list = groups.get(key);
        let entry = list.find(t => t.source === track);
        if (!entry) { entry = { source: track, header: track.header, segments: [] }; list.push(entry); }
        entry.segments.push(run);
      }
      run.push(p);
    }
  }
  return groups;
}

export function safeBase(name, fallback = 'track') {
  const base = String(name || '').replace(/\.gpx$/i, '').replace(/[^\p{L}\p{N}._ -]+/gu, '-').replace(/\s+/g, ' ').replace(/^[-. ]+|[-. ]+$/g, '').slice(0, 80);
  return base || fallback;
}

/**
 * Apply the edits in a fixed order: trim, remove flagged spikes, hide zone, reverse, split, rename.
 * The document is not changed. Returns the output files and a report for the "what changed" summary.
 */
export function editGPX(doc, opts = {}) {
  const { trim = null, spikes = null, zone = null, reverse = false, split = { mode: 'none' }, trackName = '', fileName = '', zoneName = 'UTC' } = opts;
  const before = counts(doc.tracks, doc.routes, doc.waypoints);
  const flagged = spikes ? detectSpikes(doc, spikes.thresholdKmh) : [];
  const spikeSet = new Set(spikes?.remove ? flagged.map(f => f.idx) : []);
  const inZone = p => zone && distanceMeters(zone, p) <= zone.radiusM;
  const removed = { trim: 0, spikes: 0, zone: 0, zoneWaypoints: 0, zoneRoutePoints: 0 };
  const start = trim ? Math.max(1, trim.start) : 1, end = trim ? Math.min(doc.trackPoints, trim.end) : doc.trackPoints;

  let tracks = [];
  for (const track of doc.tracks) {
    const segments = [];
    for (const segment of track.segments) {
      let run = [];
      const close = () => { if (run.length) segments.push(run); run = []; };
      for (const p of segment) {
        if (p.idx < start || p.idx > end) { removed.trim++; continue; }
        if (spikeSet.has(p.idx)) { removed.spikes++; continue; }
        // A removed zone point closes the segment, so no line is drawn across the zone.
        if (inZone(p)) { removed.zone++; close(); continue; }
        run.push(p);
      }
      close();
    }
    if (segments.length) tracks.push({ header: track.header, segments });
  }
  let routes = doc.routes.map(r => ({ header: r.header, points: r.points.filter(p => { if (inZone(p)) { removed.zoneRoutePoints++; return false; } return true; }) })).filter(r => r.points.length);
  let waypoints = doc.waypoints.filter(p => { if (inZone(p)) { removed.zoneWaypoints++; return false; } return true; });

  if (reverse) {
    tracks = tracks.map(t => ({ header: t.header, segments: t.segments.map(s => s.slice().reverse()).reverse() })).reverse();
    routes = routes.map(r => ({ header: r.header, points: r.points.slice().reverse() }));
  }

  // Split into output files.
  const notes = [];
  let groups;   // [{ label, tracks, waypoints, routes }]
  if (split.mode === 'day' || (split.mode === 'gap' && split.gapAction === 'file')) {
    let keyOf, labelOf;
    if (split.mode === 'day') {
      if (!tracks.some(t => t.segments.some(s => s.some(p => p.time !== null)))) throw new Error('Splitting by day needs timestamps, and these points have none.');
      const dated = flatten(tracks);
      let carry = null;
      const keys = new Map();
      for (const p of dated) { if (p.time !== null) carry = dayKey(p.time, zoneName); keys.set(p, carry); }
      const firstKey = dated.find(p => p.time !== null) && dayKey(dated.find(p => p.time !== null).time, zoneName);
      keyOf = p => keys.get(p) ?? firstKey;
      labelOf = key => key;
    } else {
      const gapMs = Math.max(1, split.gapMinutes) * 60000;
      let part = 1, last = null;
      const keys = new Map();
      for (const p of flatten(tracks)) {
        if (p.time !== null) { if (last !== null && Math.abs(p.time - last) > gapMs) part++; last = p.time; }
        keys.set(p, part);
      }
      keyOf = p => keys.get(p);
      labelOf = key => `part ${key}`;
    }
    const map = partition(tracks, keyOf);
    const keys = [...map.keys()].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
    groups = keys.map(key => ({ key, label: labelOf(key), tracks: map.get(key).map(({ header, segments }) => ({ header, segments })), waypoints: [], routes: [] }));
    for (const w of waypoints) {
      const target = split.mode === 'day' && w.time !== null ? groups.find(g => g.key === dayKey(w.time, zoneName)) : null;
      (target || groups[0]).waypoints.push(w);
    }
    if (groups.length) groups[0].routes = routes;
  } else {
    if (split.mode === 'gap') {
      const gapMs = Math.max(1, split.gapMinutes) * 60000;
      let last = null, cuts = 0;
      tracks = tracks.map(t => ({ header: t.header, segments: t.segments.flatMap(segment => {
        const runs = []; let run = [];
        for (const p of segment) {
          if (p.time !== null) { if (last !== null && Math.abs(p.time - last) > gapMs && run.length) { runs.push(run); run = []; cuts++; } last = p.time; }
          run.push(p);
        }
        if (run.length) runs.push(run);
        return runs;
      }) }));
      notes.push({ kind: 'gap-segments', cuts });
    }
    groups = [{ key: 'all', label: '', tracks, waypoints, routes }];
  }
  groups = groups.filter(g => g.tracks.length || g.waypoints.length || g.routes.length);

  // Rename tracks.
  const base = safeBase(fileName || `${safeBase(doc.fileName, 'track')}-edited`);
  const multi = groups.length > 1;
  if (trackName.trim()) for (const g of groups) g.tracks = g.tracks.map((t, i) => ({ ...t, header: withName(t.header, trackName.trim() + (g.tracks.length > 1 ? ` ${i + 1}` : '') + (multi ? ` (${g.label})` : '')) }));

  const width = String(groups.length).length;
  const files = groups.map((g, i) => {
    const suffix = !multi ? '' : split.mode === 'day' ? `-${g.key}` : `-part-${pad(g.key, width)}`;
    const all = [...flatten(g.tracks), ...g.waypoints, ...g.routes.flatMap(r => r.points)];
    const metaName = doc.meta.name ? doc.meta.name + (multi ? ` (${g.label})` : '') : '';
    const xml = renderFile(g, { ...doc.meta, name: metaName }, all);
    return { filename: `${base}${suffix}.gpx`, xml, label: g.label, counts: counts(g.tracks, g.routes, g.waypoints), tracknames: g.tracks.map(t => headerName(t.header)) };
  });
  const total = counts(groups.flatMap(g => g.tracks), groups.flatMap(g => g.routes), groups.flatMap(g => g.waypoints));
  const reversedTimes = reverse && flatten(groups.flatMap(g => g.tracks)).some(p => p.time !== null);
  return {
    files, flagged, removed, before, after: { ...total, files: files.length },
    meta: doc.meta, droppedFields: doc.stats.droppedFields, invalidCoordinates: doc.stats.invalidCoordinates,
    notes, reversedTimes, version: doc.version, kept: groups.flatMap(g => g.tracks),
  };
}

const renderPoint = (tag, p, indent) => p.body.length
  ? `${indent}<${tag} ${p.attrs}>\n${p.body.map(b => `${indent}  ${b}\n`).join('')}${indent}</${tag}>\n`
  : `${indent}<${tag} ${p.attrs}/>\n`;

function renderFile(group, meta, all) {
  const lats = all.map(p => p.lat), lons = all.map(p => p.lon);
  let xml = '<?xml version="1.0" encoding="UTF-8"?>\n' +
    `<gpx version="1.1" creator="Whereday GPX editor (whereday.com/tools/gpx-editor/)" xmlns="${GPX11}" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="${GPX11} https://www.topografix.com/GPX/1/1/gpx.xsd">\n  <metadata>\n` +
    (meta.name ? `    <name>${escapeText(clean(meta.name))}</name>\n` : '') + (meta.desc ? `    <desc>${escapeText(clean(meta.desc))}</desc>\n` : '') +
    (all.length ? `    <bounds minlat="${lowest(lats)}" minlon="${lowest(lons)}" maxlat="${highest(lats)}" maxlon="${highest(lons)}"/>\n` : '') + '  </metadata>\n';
  for (const w of group.waypoints) xml += renderPoint('wpt', w, '  ');
  for (const r of group.routes) xml += `  <rte>\n${r.header.map(h => `    ${h}\n`).join('')}${r.points.map(p => renderPoint('rtept', p, '    ')).join('')}  </rte>\n`;
  for (const t of group.tracks) {
    xml += `  <trk>\n${t.header.map(h => `    ${h}\n`).join('')}`;
    for (const s of t.segments) xml += `    <trkseg>\n${s.map(p => renderPoint('trkpt', p, '      ')).join('')}    </trkseg>\n`;
    xml += '  </trk>\n';
  }
  return xml + '</gpx>\n';
}

// ---- Map plot ----------------------------------------------------------------------------------
/** A flat local projection (equirectangular around the mean latitude) fitted into width x height. */
export function makeProjection(points, width = 800, height = 420, margin = 24) {
  const pts = points.length ? points : [{ lat: 0, lon: 0 }];
  const rad = Math.PI / 180;
  const lat0 = pts.reduce((n, p) => n + p.lat, 0) / pts.length;
  const lon0 = pts.reduce((n, p) => n + p.lon, 0) / pts.length;
  const k = Math.max(.01, Math.cos(lat0 * rad));
  const fx = p => (((p.lon - lon0 + 540) % 360) - 180) * k, fy = p => -p.lat;
  const xs = pts.map(fx), ys = pts.map(fy);
  const minX = lowest(xs), maxX = highest(xs), minY = lowest(ys), maxY = highest(ys);
  const scale = Math.min((width - 2 * margin) / (maxX - minX || 1e-5), (height - 2 * margin) / (maxY - minY || 1e-5));
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  return {
    project: p => [width / 2 + (fx(p) - cx) * scale, height / 2 + (fy(p) - cy) * scale],
    invert: (x, y) => ({ lat: -((y - height / 2) / scale + cy), lon: lon0 + ((x - width / 2) / scale + cx) / k }),
    pixelsPerMeter: scale / 111320,
  };
}

// ---- ZIP (stored, no compression) ----------------------------------------------------------------
/** Build a ZIP with stored entries from [{ name, text }]. Names are written as UTF-8. */
export function zipFiles(entries) {
  const enc = new TextEncoder(), chunks = [], central = [];
  let offset = 0;
  for (const e of entries) {
    const name = enc.encode(e.name), data = enc.encode(e.text), crc = crc32(data);
    const local = new DataView(new ArrayBuffer(30));
    [[0, 0x04034b50, 4], [4, 20, 2], [6, 0x0800, 2], [8, 0, 2], [10, 0, 2], [12, 0x21, 2], [14, crc, 4], [18, data.length, 4], [22, data.length, 4], [26, name.length, 2], [28, 0, 2]]
      .forEach(([o, v, w]) => (w === 4 ? local.setUint32(o, v, true) : local.setUint16(o, v, true)));
    chunks.push(new Uint8Array(local.buffer), name, data);
    const dir = new DataView(new ArrayBuffer(46));
    [[0, 0x02014b50, 4], [4, 20, 2], [6, 20, 2], [8, 0x0800, 2], [10, 0, 2], [12, 0, 2], [14, 0x21, 2], [16, crc, 4], [20, data.length, 4], [24, data.length, 4], [28, name.length, 2], [30, 0, 2], [32, 0, 2], [34, 0, 2], [36, 0, 2], [38, 0, 4], [42, offset, 4]]
      .forEach(([o, v, w]) => (w === 4 ? dir.setUint32(o, v, true) : dir.setUint16(o, v, true)));
    central.push(new Uint8Array(dir.buffer), name);
    offset += 30 + name.length + data.length;
  }
  const size = central.reduce((n, c) => n + c.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true); end.setUint32(12, size, true); end.setUint32(16, offset, true);
  const all = [...chunks, ...central, new Uint8Array(end.buffer)];
  const out = new Uint8Array(all.reduce((n, c) => n + c.length, 0));
  let at = 0;
  for (const c of all) { out.set(c, at); at += c.length; }
  return out;
}
