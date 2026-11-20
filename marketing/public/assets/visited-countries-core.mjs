// Visited-countries map: pure logic shared by the page and its tests.
// Points are matched to bundled Natural Earth boundaries entirely offline.
// Nothing here reads the clock, the network or the page.
import { BOUNDARY_DATA, BOUNDARY_SCALE, BOUNDARY_VERSION } from './visited-countries-data.mjs';
import { parseGPX } from './gpx-core.mjs';
import { inspectTimeline } from './timeline-core.mjs';
import { parseCountryDaysCSV, parseDay, formatDay } from './country-days-core.mjs';

export { BOUNDARY_VERSION };
export const MAX_BYTES = 10 * 1024 * 1024;
export const MAX_POINTS = 50000;
export const CELL_DEGREES = 0.01;
export const BORDER_KM = 10;
export const BOUNDARY_SOURCE = 'Natural Earth 1:50m Admin 0 – Countries';

// ---------- boundaries ----------

let cache = null;

/** Decode the bundled data into absolute degrees. Cached. */
export function boundaries(data = BOUNDARY_DATA, scale = BOUNDARY_SCALE) {
  if (data === BOUNDARY_DATA && cache) return cache;
  const list = data.map(([code, name, polys]) => {
    let minX = 181, minY = 91, maxX = -181, maxY = -91;
    const decoded = polys.map(rings => rings.map(flat => {
      const ring = new Float64Array(flat.length);
      let x = 0, y = 0;
      for (let i = 0; i < flat.length; i += 2) {
        x += flat[i]; y += flat[i + 1];
        ring[i] = x / scale; ring[i + 1] = y / scale;
        if (ring[i] < minX) minX = ring[i]; if (ring[i] > maxX) maxX = ring[i];
        if (ring[i + 1] < minY) minY = ring[i + 1]; if (ring[i + 1] > maxY) maxY = ring[i + 1];
      }
      return ring;
    }));
    return { code, name, bbox: [minX, minY, maxX, maxY], polys: decoded };
  });
  const result = { list, byCode: new Map(list.map(c => [c.code, c])) };
  if (data === BOUNDARY_DATA) cache = result;
  return result;
}

/** Even-odd ray casting over every ring of one polygon, so holes are respected. */
function insidePolygon(lat, lon, rings) {
  let inside = false;
  for (const ring of rings) {
    for (let i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
      const xi = ring[i], yi = ring[i + 1], xj = ring[j], yj = ring[j + 1];
      if ((yi > lat) !== (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside;
    }
  }
  return inside;
}

const wrap = lon => ((((lon + 180) % 360) + 360) % 360) - 180;

/** Distance in km from a point to the nearest edge of the rings (local flat approximation). */
function edgeKm(lat, lon, rings) {
  const kx = 111.32 * Math.max(0.01, Math.cos(lat * Math.PI / 180)), ky = 110.57;
  let best = Infinity;
  for (const ring of rings) {
    for (let i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
      const ax = wrap(ring[j] - lon) * kx, ay = (ring[j + 1] - lat) * ky;
      const bx = wrap(ring[i] - lon) * kx, by = (ring[i + 1] - lat) * ky;
      const dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
      const t = len === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
      const d = Math.hypot(ax + t * dx, ay + t * dy);
      if (d < best) best = d;
    }
  }
  return best;
}

const inBox = (lat, lon, [x0, y0, x1, y1], margin = 0) => {
  if (lat < y0 - margin || lat > y1 + margin) return false;
  const m = margin / Math.max(0.01, Math.cos(lat * Math.PI / 180));
  return (lon >= x0 - m && lon <= x1 + m) || (lon + 360 >= x0 - m && lon + 360 <= x1 + m) || (lon - 360 >= x0 - m && lon - 360 <= x1 + m);
};

/**
 * Find the country for a point. Returns
 *   { code, name, near }   near = code of another country within BORDER_KM, or null
 *   { code: null, nearCoast } the point is in no polygon (water or no coverage);
 *                          nearCoast is the code of a country within BORDER_KM, or null
 *   { invalid: true }      the coordinates are not usable
 * Longitudes are wrapped to [-180, 180); 180 and -180 are the same meridian.
 */
export function locate(lat, lon, b = boundaries()) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90) return { invalid: true };
  lon = wrap(lon);
  let hit = null;
  for (const c of b.list) {
    if (!inBox(lat, lon, c.bbox) || !c.polys.some(rings => insidePolygon(lat, lon, rings))) continue;
    hit = c; break;
  }
  let near = null, nearKm = Infinity;
  for (const c of b.list) {
    if (c === hit || !inBox(lat, lon, c.bbox, 0.2)) continue;
    for (const rings of c.polys) {
      const km = insidePolygon(lat, lon, rings) ? 0 : edgeKm(lat, lon, rings);
      if (km <= BORDER_KM && km < nearKm) { near = c.code; nearKm = km; }
    }
  }
  // A point in no polygon stays unmatched. If it is within BORDER_KM of a country's
  // edge (a coastal city on a simplified coastline), that country is reported as nearCoast.
  if (!hit) return { code: null, nearCoast: near };
  return { code: hit.code, name: hit.name, near };
}

// ---------- dates ----------

const ISO = /^(\d{4})-(\d\d)-(\d\d)(?:[T ](\d\d):(\d\d)(?::(\d\d)(?:\.\d+)?)?(Z|[+-]\d\d:?\d\d)?)?$/;

/**
 * A calendar date for a timestamp text. A timestamp with a UTC offset keeps the
 * date as written (its local date); one with Z uses its UTC date; a bare date
 * is used as is. Anything else has no date. Returns { day, rule } or null.
 */
export function dayFromText(text) {
  if (typeof text !== 'string') return null;
  const m = ISO.exec(text.trim());
  if (!m) return null;
  const [, y, mo, d, h = '0', mi = '0', s = '0', zone] = m;
  if (Number(h) > 23 || Number(mi) > 59 || Number(s) > 59) return null;
  const day = parseDay(`${y}-${mo}-${d}`);
  if (day === null) return null;
  if (m[4] === undefined) return { day, rule: 'date' };
  if (!zone) return null; // a clock time with no zone is ambiguous
  return { day, rule: zone === 'Z' ? 'utc' : 'offset' };
}

/** UTC date of a millisecond timestamp. */
export function dayFromMs(ms) {
  if (!Number.isFinite(ms)) return null;
  const day = Math.floor(ms / 86400000);
  return parseDay(formatDay(day)) === null ? null : { day, rule: 'utc' };
}

const RULE_TEXT = {
  none: 'No dates found',
  utc: 'UTC date of each timestamp',
  offset: 'Date as written, because every timestamp has a UTC offset',
  date: 'Date as written in the file',
  mixed: 'UTC date, or the date as written where a timestamp has an offset',
};
const combineRules = rules => {
  const set = new Set(rules);
  if (!set.size) return 'none';
  if (set.size === 1) return [...set][0];
  return 'mixed';
};

// ---------- reading files ----------

const TOO_BIG = 'This file is over 10 MiB. Export a shorter date range and try again.';
const point = (lat, lon, dated) => ({ lat, lon, day: dated ? dated.day : null, rule: dated ? dated.rule : null });
const finish = (name, kind, points, extra = {}) => ({
  name, kind, points, records: [], skipped: 0, notes: [],
  rule: combineRules(points.map(p => p.rule).filter(Boolean)), ...extra,
});

function readGPXFile(text, name, Parser) {
  const model = parseGPX(text, Parser);
  const points = model.points.map(p => point(p.lat, p.lon, /^\d{4}-\d\d-\d\d/.test(p.rawTime) && p.time !== null ? dayFromText(p.rawTime) : null));
  return finish(name, 'gpx', points, { skipped: model.issues.invalidCoordinates });
}

function readTimelineFile(text, name) {
  const model = inspectTimeline(text);
  const points = [];
  for (const p of model.points) points.push(point(p.lat, p.lon, dayFromMs(p.time)));
  const cap = 366;
  let capped = 0;
  for (const v of model.visits) {
    const first = Math.floor(v.start / 86400000), last = Math.floor(v.end / 86400000);
    if (last - first + 1 > cap) capped++;
    for (let day = first; day <= Math.min(last, first + cap - 1); day++) points.push({ lat: v.lat, lon: v.lon, day, rule: 'utc' });
  }
  const f = model.findings;
  const skipped = f.invalidCoordinates + f.missingPointTime + f.invalidPointTime + f.outsideInterval;
  const notes = [`Google Timeline JSON (${model.family.toLowerCase()}). Visits count every UTC date from start to end.`];
  if (capped) notes.push(`${capped} visit(s) longer than 366 days were cut to their first 366 dates.`);
  return finish(name, 'timeline', points, { skipped, notes });
}

function* positions(geometry, out) {
  if (!geometry || typeof geometry !== 'object') return;
  const { type, coordinates } = geometry;
  if (type === 'Point') yield [coordinates, 0];
  else if (type === 'LineString' && Array.isArray(coordinates)) for (const [i, c] of coordinates.entries()) yield [c, i];
  else if (type === 'MultiLineString' && Array.isArray(coordinates)) { for (const line of coordinates) if (Array.isArray(line)) for (const [i, c] of line.entries()) yield [c, i]; }
  else if (type === 'GeometryCollection' && Array.isArray(geometry.geometries)) for (const g of geometry.geometries) yield* positions(g, out);
  else if (type) out.unsupported.add(type);
}

function readGeoJSONFile(root, name) {
  const out = { unsupported: new Set() };
  const points = [];
  let skipped = 0;
  const features = root.type === 'FeatureCollection' ? (Array.isArray(root.features) ? root.features : [])
    : root.type === 'Feature' ? [root] : [{ type: 'Feature', geometry: root, properties: {} }];
  for (const feature of features) {
    if (!feature || typeof feature !== 'object') { skipped++; continue; }
    const props = feature.properties && typeof feature.properties === 'object' ? feature.properties : {};
    const whole = ['time', 'timestamp', 'datetime', 'date', 'start'].map(k => dayFromText(props[k])).find(Boolean) ?? null;
    const times = props.coordTimes ?? props.times;
    const flat = Array.isArray(times) ? times.flat(1) : [];
    let index = 0;
    for (const [c, i] of positions(feature.geometry, out)) {
      if (points.length + skipped >= MAX_POINTS) throw new Error('This file has more than 50,000 positions. Export a smaller date range and try again.');
      const lon = Array.isArray(c) ? c[0] : NaN, lat = Array.isArray(c) ? c[1] : NaN;
      if (typeof lon !== 'number' || typeof lat !== 'number' || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) { skipped++; index++; continue; }
      const own = flat.length ? dayFromText(flat[index] ?? times?.[i]) : null;
      points.push(point(lat, lon, own ?? whole));
      index++;
    }
  }
  if (!points.length && !skipped) throw new Error('No Point, LineString or MultiLineString positions were found in this GeoJSON.');
  const notes = out.unsupported.size ? [`Geometry types not used: ${[...out.unsupported].join(', ')}.`] : [];
  return finish(name, 'geojson', points, { skipped, notes });
}

function readKMLFile(text, name, Parser) {
  if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(text)) throw new Error('DTD and entity declarations are not supported. Keep the original and use a standard KML export.');
  let doc;
  try { doc = new Parser().parseFromString(text, 'application/xml'); }
  catch { throw new Error('This file is not well-formed XML. Keep the original and export a fresh copy.'); }
  const root = doc.documentElement;
  if (!root || doc.getElementsByTagName('parsererror').length) throw new Error('This file is not well-formed XML. Keep the original and export a fresh copy.');
  if (root.localName !== 'kml') throw new Error('Choose a GPX, KML, GeoJSON, Google Timeline JSON or Country Days CSV file.');
  const descendants = (node, tag) => Array.from(node.getElementsByTagName(tag));
  const first = (node, tag) => descendants(node, tag)[0]?.textContent.trim() ?? '';
  const points = [];
  let skipped = 0;
  for (const placemark of descendants(doc, 'Placemark')) {
    const dated = dayFromText(first(placemark, 'when')) ?? dayFromText(first(placemark, 'begin'));
    for (const tag of ['Point', 'LineString']) {
      for (const geometry of descendants(placemark, tag)) {
        for (const token of first(geometry, 'coordinates').split(/\s+/).filter(Boolean)) {
          if (points.length + skipped >= MAX_POINTS) throw new Error('This file has more than 50,000 positions. Export a smaller date range and try again.');
          const [lonText, latText] = token.split(',');
          const decimal = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
          const lon = Number(lonText), lat = Number(latText);
          if (!decimal.test(lonText ?? '') || !decimal.test(latText ?? '') || Math.abs(lat) > 90 || Math.abs(lon) > 180) { skipped++; continue; }
          points.push(point(lat, lon, dated));
        }
      }
    }
  }
  if (!points.length && !skipped) throw new Error('No Point or LineString coordinates were found in this KML.');
  return finish(name, 'kml', points, { skipped });
}

function readCSVFile(text, name) {
  const header = text.replace(/^﻿/, '').split(/\r\n|\n|\r/, 1)[0].toLowerCase();
  if (!/\bcountry_code\b/.test(header) || !/\blocal_date\b/.test(header)) {
    throw new Error('Not recognized. Choose a GPX, KML, GeoJSON, Google Timeline JSON or Whereday Country Days CSV file (columns country_code and local_date).');
  }
  const parsed = parseCountryDaysCSV(text);
  const records = parsed.records.map(r => ({ country: r.country, day: r.day }));
  return finish(name, 'csv', [], {
    records, skipped: parsed.rejected.filter(r => r.reason !== 'empty row').length,
    rule: records.length ? 'date' : 'none', notes: ['Country Days CSV: countries are named in the file, so no map lookup was needed.'],
  });
}

/**
 * Read one location file. The format is detected from the content, not the
 * file name. Throws an Error with a plain-language message when unusable.
 */
export function readLocationFile(text, name = 'file', { Parser = globalThis.DOMParser } = {}) {
  if (typeof text !== 'string') throw new Error('The file could not be read as text.');
  if (new TextEncoder().encode(text).length > MAX_BYTES) throw new Error(TOO_BIG);
  const body = text.replace(/^﻿/, '').trimStart();
  if (!body) throw new Error('This file is empty. Export it again from the app that made it.');
  if (body[0] === '<') {
    const m = /<(?!\?|!)\s*(?:[A-Za-z_][\w.-]*:)?([A-Za-z_][\w.-]*)/.exec(body);
    if (m && m[1] === 'kml') return readKMLFile(text, name, Parser);
    if (m && m[1] === 'gpx') return readGPXFile(text, name, Parser);
    throw new Error('Choose a GPX, KML, GeoJSON, Google Timeline JSON or Country Days CSV file. ZIP archives and other formats are not supported.');
  }
  if (body[0] === '{' || body[0] === '[') {
    let root;
    try { root = JSON.parse(body); } catch { throw new Error('This is not readable JSON. Keep the original; extract a .json file from any ZIP archive first.'); }
    const geo = ['Feature', 'FeatureCollection', 'Point', 'LineString', 'MultiLineString', 'MultiPoint', 'Polygon', 'MultiPolygon', 'GeometryCollection'];
    if (root && !Array.isArray(root) && geo.includes(root.type)) return readGeoJSONFile(root, name);
    return readTimelineFile(text, name);
  }
  return readCSVFile(text, name);
}

// ---------- analysis ----------

/** Keep one point per CELL_DEGREES cell per date. Dateless points share one bucket. */
export function thin(points) {
  const seen = new Set(), kept = [];
  for (const p of points) {
    const key = `${Math.floor(p.lat / CELL_DEGREES)}:${Math.floor(wrap(p.lon) / CELL_DEGREES)}:${p.day ?? 'x'}`;
    if (seen.has(key)) continue;
    seen.add(key); kept.push(p);
  }
  return kept;
}

/**
 * Match a file's points to countries.
 * countries: code → { days:Set, undated, solid, near, nearTo:Set }.
 * A point near another country's border is counted as "near", not as a visit.
 */
export function analyze(file, b = boundaries()) {
  const countries = new Map();
  const entry = code => {
    if (!countries.has(code)) countries.set(code, { name: b.byCode.get(code)?.name ?? code, days: new Set(), undated: 0, solid: 0, near: 0, nearTo: new Set() });
    return countries.get(code);
  };
  const stats = { read: file.points.length, thinned: 0, matched: 0, near: 0, unmatched: 0, nearCoast: 0, csvRows: file.records.length };
  const kept = thin(file.points);
  stats.thinned = kept.length;
  for (const p of kept) {
    const found = locate(p.lat, p.lon, b);
    if (found.invalid) { stats.unmatched++; continue; }
    if (found.code === null) {
      stats.unmatched++;
      if (found.nearCoast) { stats.nearCoast++; const c = entry(found.nearCoast); c.near++; c.nearTo.add('coast'); }
      continue;
    }
    const c = entry(found.code);
    if (found.near) { stats.near++; c.near++; c.nearTo.add(found.near); continue; }
    stats.matched++; c.solid++;
    if (p.day === null) c.undated++; else c.days.add(p.day);
  }
  for (const r of file.records) { const c = entry(r.country); c.solid++; c.days.add(r.day); }
  return { countries, stats };
}

export const emptyManual = () => ({ added: new Set(), removed: new Set() });

/** Click behaviour: removes a file country, restores it, adds a new one, or undoes a manual add. */
export function toggleManual(manual, code, summary) {
  const next = { added: new Set(manual.added), removed: new Set(manual.removed) };
  let action;
  if (next.added.has(code)) { next.added.delete(code); action = 'removed-manual'; }
  else if (next.removed.has(code)) { next.removed.delete(code); action = 'restored'; }
  else if (summary.rows.some(r => r.code === code && r.source === 'file')) { next.removed.add(code); action = 'removed'; }
  else { next.added.add(code); action = 'added'; }
  return { manual: next, action };
}

let regionNames = null;
export function countryName(code, b = boundaries()) {
  const known = b.byCode.get(code)?.name;
  if (known) return known;
  try { regionNames ??= new Intl.DisplayNames(['en'], { type: 'region' }); return regionNames.of(code) ?? code; }
  catch { return code; }
}

/** Combine analyzed files and manual changes into the table. */
export function summarize(analyses, manual = emptyManual(), b = boundaries()) {
  const rows = new Map(), nearOnly = new Map();
  const stats = { read: 0, thinned: 0, matched: 0, near: 0, unmatched: 0, nearCoast: 0, csvRows: 0 };
  for (const a of analyses) {
    for (const k of Object.keys(stats)) stats[k] += a.stats[k];
    for (const [code, c] of a.countries) {
      if (c.solid > 0) {
        const row = rows.get(code) ?? { code, name: countryName(code, b), days: new Set(), points: 0, source: 'file' };
        for (const d of c.days) row.days.add(d);
        row.points += c.solid; rows.set(code, row);
      } else if (c.near > 0) {
        const n = nearOnly.get(code) ?? { code, name: countryName(code, b), points: 0, nearTo: new Set() };
        n.points += c.near; for (const t of c.nearTo) n.nearTo.add(t); nearOnly.set(code, n);
      }
    }
  }
  for (const code of manual.removed) if (rows.has(code)) rows.get(code).source = 'removed manually';
  for (const code of manual.added) if (!rows.has(code)) rows.set(code, { code, name: countryName(code, b), days: new Set(), points: 0, source: 'manual' });
  const all = [...rows.values()].map(r => {
    const days = [...r.days].sort((x, y) => x - y);
    return { code: r.code, name: r.name, first: days.length ? days[0] : null, last: days.length ? days.at(-1) : null, dates: days.length, points: r.points, source: r.source, counted: r.source !== 'removed manually' };
  }).sort((x, y) => x.name.localeCompare(y.name));
  const counted = all.filter(r => r.counted);
  for (const code of rows.keys()) nearOnly.delete(code);
  return {
    rows: all, count: counted.length, codes: new Set(counted.map(r => r.code)),
    undrawn: counted.filter(r => !b.byCode.has(r.code)).map(r => r.code),
    nearOnly: [...nearOnly.values()].map(n => ({ code: n.code, name: n.name, points: n.points, nearTo: [...n.nearTo].sort() })).sort((x, y) => x.name.localeCompare(y.name)),
    stats,
  };
}

const SORTS = {
  name: (x, y) => x.name.localeCompare(y.name),
  first: (x, y) => (x.first ?? Infinity) - (y.first ?? Infinity) || x.name.localeCompare(y.name),
  last: (x, y) => (y.last ?? -Infinity) - (x.last ?? -Infinity) || x.name.localeCompare(y.name),
  dates: (x, y) => y.dates - x.dates || x.name.localeCompare(y.name),
  source: (x, y) => x.source.localeCompare(y.source) || x.name.localeCompare(y.name),
};
export const SORT_KEYS = Object.keys(SORTS);
export const sortRows = (rows, key = 'name') => [...rows].sort(SORTS[key] ?? SORTS.name);

export const ruleText = rule => RULE_TEXT[rule] ?? RULE_TEXT.none;
export const dayText = day => day === null || day === undefined ? '' : formatDay(day);

// ---------- exports ----------

const cell = value => {
  let s = String(value ?? '');
  // Country names and file text can become spreadsheet formulas.
  if (/^\s*[=+\-@]/.test(s) || /^[\t\r\n]/.test(s)) s = "'" + s;
  return /[",\r\n]/.test(s) ? '"' + s.replaceAll('"', '""') + '"' : s;
};

/** The table as CSV, ordered by country code. Removed countries stay listed as such. */
export function tableCSV(summary) {
  const lines = ['country_code,country,first_date,last_date,distinct_dates,points,source'];
  for (const r of [...summary.rows].sort((x, y) => x.code.localeCompare(y.code))) {
    lines.push([r.code, r.name, dayText(r.first), dayText(r.last), r.dates, r.points, r.source].map(cell).join(','));
  }
  return lines.join('\r\n') + '\r\n';
}

// ---------- map ----------

const WIDTH = 720, HEIGHT = 360;
const COLORS = { sea: '#F3EFE6', land: '#DDD7C8', visited: '#A8381F', stroke: '#F7F4EC' };
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
let pathCache = null;

function paths(b) {
  if (pathCache) return pathCache;
  const num = v => String(Math.round(v * 10) / 10);
  pathCache = new Map(b.list.map(c => {
    let d = '';
    for (const polygon of c.polys) for (const ring of polygon) {
      const out = [];
      let lastX = null, lastY = null;
      for (let i = 0; i < ring.length; i += 2) {
        const x = num((ring[i] + 180) * 2), y = num((90 - ring[i + 1]) * 2);
        if (x === lastX && y === lastY) continue;
        lastX = x; lastY = y; out.push(x + ' ' + y);
      }
      if (out.length >= 3) d += 'M' + out.join(' ') + 'Z';
    }
    return [c.code, d];
  }));
  return pathCache;
}

/**
 * A standalone world map as SVG text (equirectangular, no watermark).
 * Deterministic: the same visited set always gives the same text.
 */
export function mapSVG(visited, { width = WIDTH * 2, b = boundaries(), count = visited.size } = {}) {
  const p = paths(b);
  const height = width / 2;
  const body = b.list.filter(c => p.get(c.code)).map(c => {
    const on = visited.has(c.code);
    return `<path data-code="${esc(c.code)}" d="${p.get(c.code)}" fill="${on ? COLORS.visited : COLORS.land}" stroke="${COLORS.stroke}" stroke-width="0.3" stroke-linejoin="round"><title>${esc(c.name)}${on ? ' (visited)' : ''}</title></path>`;
  }).join('\n');
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${WIDTH} ${HEIGHT}" width="${width}" height="${height}" role="img" aria-labelledby="map-title map-desc">\n` +
    `<title id="map-title">World map: ${count} ${count === 1 ? 'country' : 'countries'} visited</title>\n` +
    `<desc id="map-desc">Visited countries are shaded dark red. Boundaries: ${BOUNDARY_SOURCE}, version ${BOUNDARY_VERSION}, simplified.</desc>\n` +
    `<rect width="${WIDTH}" height="${HEIGHT}" fill="${COLORS.sea}"/>\n${body}\n</svg>\n`;
}

/** Pixel size of the PNG export for a given width. The map is always 2:1. */
export const pngSize = (width = 2400) => ({ width, height: width / 2 });
