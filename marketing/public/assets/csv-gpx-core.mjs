import { parseGPX, toCSV, plotPaths, MAX_BYTES } from './gpx-core.mjs';

export { MAX_BYTES, plotPaths };
export const MAX_ROWS = 50000;
export const DELIMITERS = { comma: ',', semicolon: ';', tab: '\t' };
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;

// Header names, in priority order. Matching ignores case and surrounding spaces.
export const ALIASES = {
  lat: ['lat', 'latitude', 'y'],
  lon: ['lon', 'lng', 'long', 'longitude', 'x'],
  time: ['time', 'timestamp', 'time_utc', 'date', 'datetime'],
  ele: ['ele', 'elevation', 'elevation_m', 'alt', 'altitude'],
  name: ['name', 'title', 'label'],
  group: ['group', 'segment', 'track'],
  kind: ['kind'],
};
export const REASONS = {
  missing: 'latitude or longitude is empty',
  nonNumeric: 'latitude or longitude is not a decimal number',
  outOfRange: 'latitude is outside −90 to 90 or longitude is outside −180 to 180',
};

const clean = s => String(s ?? '').replace(/^\uFEFF/, '').trim();

// ---- CSV reading (RFC 4180 quoting) ---------------------------------------------------------------
function fieldCounts(line, delimiter) {
  let n = 0, quoted = false;
  for (let i = 0; i < line.length; i++) {
    if (line[i] === '"') quoted = !quoted;
    else if (!quoted && line[i] === delimiter) n++;
  }
  return n;
}

export function detectDelimiter(text) {
  const lines = String(text).replace(/^\uFEFF/, '').split(/\r\n|\n|\r/).filter(l => l.trim() !== '').slice(0, 10);
  if (!lines.length) return ',';
  // A delimiter qualifies when every sampled line has the same, non-zero count of it.
  // Tab and semicolon win over comma because semicolon files often use commas as decimal marks.
  for (const d of ['\t', ';', ',']) {
    const counts = lines.map(l => fieldCounts(l, d));
    if (counts[0] > 0 && counts.every(c => c === counts[0])) return d;
  }
  let best = ',', most = 0;
  for (const d of [',', ';', '\t']) {
    const total = lines.reduce((n, l) => n + fieldCounts(l, d), 0);
    if (total > most) { best = d; most = total; }
  }
  return best;
}

export function parseCSV(text, delimiter = ',') {
  const s = String(text).replace(/^\uFEFF/, '');
  const rows = [];
  let row = [], field = '', quoted = false, started = false;
  const endField = () => { row.push(field); field = ''; started = false; };
  const endRow = () => {
    endField();
    // Blank lines are not records.
    if (!(row.length === 1 && row[0].trim() === '')) rows.push(row);
    row = [];
    if (rows.length > MAX_ROWS + 1) throw new Error('This file has more than 50,000 rows. Split it into smaller files and try again.');
  };
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (quoted) {
      if (c === '"') { if (s[i + 1] === '"') { field += '"'; i++; } else quoted = false; }
      else field += c;
    } else if (c === '"' && !started) { quoted = true; started = true; }
    else if (c === delimiter) endField();
    else if (c === '\r') { if (s[i + 1] === '\n') i++; endRow(); }
    else if (c === '\n') endRow();
    else { field += c; started = true; }
  }
  if (field !== '' || row.length || started) endRow();
  return rows;
}

export function detectColumns(headers) {
  const names = headers.map(h => clean(h).toLowerCase());
  const mapping = {};
  for (const [key, aliases] of Object.entries(ALIASES)) {
    mapping[key] = -1;
    for (const alias of aliases) { const i = names.indexOf(alias); if (i >= 0) { mapping[key] = i; break; } }
  }
  return mapping;
}

const looksNumeric = s => DECIMAL.test(clean(s).replace(/^([+-]?\d+),(\d+)$/, '$1.$2'));

// Reads text into a table: delimiter, header row (or not) and the automatic column mapping.
export function readCSV(text, { delimiter = 'auto', header = 'auto' } = {}) {
  if (new TextEncoder().encode(text).length > MAX_BYTES) throw new Error('Choose a CSV file of 10 MiB or less.');
  const d = delimiter === 'auto' ? detectDelimiter(text) : (DELIMITERS[delimiter] ?? delimiter);
  const records = parseCSV(text, d);
  if (!records.length) throw new Error('No rows were found. Check that the file is a CSV with one point per row.');
  const first = records[0];
  const byName = detectColumns(first);
  let hasHeader;
  if (header === 'yes') hasHeader = true;
  else if (header === 'no') hasHeader = false;
  else hasHeader = byName.lat >= 0 && byName.lon >= 0 ? true : !(first.filter(looksNumeric).length >= 2);
  const width = Math.max(...records.map(r => r.length));
  const headers = Array.from({ length: width }, (_, i) => (hasHeader && clean(first[i]) !== '' ? clean(first[i]) : `Column ${i + 1}`));
  const mapping = hasHeader ? detectColumns(first) : { lat: width > 1 ? 0 : -1, lon: width > 1 ? 1 : -1, time: -1, ele: -1, name: -1, group: -1, kind: -1 };
  const rows = hasHeader ? records.slice(1) : records;
  if (rows.length > MAX_ROWS) throw new Error('This file has more than 50,000 rows. Split it into smaller files and try again.');
  return { delimiter: d, hasHeader, headers, mapping, rows, firstRow: hasHeader ? 2 : 1 };
}

// ---- Values ---------------------------------------------------------------------------------------
function number(raw, decimalComma) {
  let s = clean(raw);
  if (decimalComma) s = s.replace(/^([+-]?\d+),(\d+)$/, '$1.$2');
  return DECIMAL.test(s) ? { text: s, value: Number(s) } : null;
}

// ISO 8601 / RFC 3339 date and time. Returns { ms, assumedUtc } or null.
export function parseTime(raw) {
  const m = /^(\d{4})-(\d\d)-(\d\d)[Tt ](\d\d):(\d\d)(?::(\d\d)(?:[.,](\d+))?)?\s*(Z|z|[+-]\d\d(?::?\d\d)?)?$/.exec(clean(raw));
  if (!m) return null;
  const [y, mo, d, h, mi] = [m[1], m[2], m[3], m[4], m[5]].map(Number), sec = Number(m[6] ?? 0);
  const leap = y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (mo < 1 || mo > 12 || d < 1 || d > days[mo - 1] || h > 23 || mi > 59 || sec > 59) return null;
  let offset = 0;
  const zone = m[8];
  if (zone && zone.toUpperCase() !== 'Z') {
    const digits = zone.slice(1).replace(':', '');
    const oh = Number(digits.slice(0, 2)), om = Number(digits.slice(2) || 0);
    if (oh > 23 || om > 59) return null;
    offset = (zone[0] === '-' ? -1 : 1) * (oh * 60 + om) * 60000;
  }
  const ms = Date.UTC(y, mo - 1, d, h, mi, sec, Math.round(Number(`0.${m[7] ?? 0}`) * 1000)) - offset;
  return Number.isFinite(ms) ? { ms, assumedUtc: !zone } : null;
}

const isoUTC = ms => new Date(ms).toISOString().replace(/\.000Z$/, 'Z');
// Characters that XML 1.0 cannot contain are dropped; the rest is escaped.
const xml = s => String(s).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '')
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');
// The GPX viewer's CSV export prefixes names that start with = + - @ with an apostrophe.
const unguard = s => s.replace(/^'(?=[\s]*[=+\-@])/, '');

export function defaultMode(mapping) {
  if (mapping.kind >= 0) return 'kinds';
  return mapping.time >= 0 ? 'track' : 'waypoints';
}

// Turns a table from readCSV into GPX text and a report.
export function convertCSV(table, { mapping = table.mapping, mode = defaultMode(mapping), fileName = 'points' } = {}) {
  if (!(mapping.lat >= 0) || !(mapping.lon >= 0)) throw new Error('Choose a latitude column and a longitude column.');
  if (mapping.lat === mapping.lon) throw new Error('Latitude and longitude need two different columns.');
  const decimalComma = table.delimiter !== ',';
  const cell = (row, i) => (i >= 0 ? clean(row[i]) : '');
  const report = {
    rowsRead: table.rows.length, written: 0, skipped: [], skippedByReason: { missing: 0, nonNumeric: 0, outOfRange: 0 },
    assumedUtc: 0, badTime: 0, noTime: 0, badElevation: 0, segments: 0, firstTime: null, lastTime: null, mode,
  };
  const points = [];
  for (const [index, row] of table.rows.entries()) {
    const number_ = table.firstRow + index;
    const lat = number(cell(row, mapping.lat), decimalComma), lon = number(cell(row, mapping.lon), decimalComma);
    let reason = null;
    if (cell(row, mapping.lat) === '' || cell(row, mapping.lon) === '') reason = 'missing';
    else if (!lat || !lon) reason = 'nonNumeric';
    else if (Math.abs(lat.value) > 90 || Math.abs(lon.value) > 180) reason = 'outOfRange';
    if (reason) { report.skippedByReason[reason]++; report.skipped.push({ row: number_, reason }); continue; }
    const point = { row: number_, lat: lat.text, lon: lon.value === 180 ? '-180' : lon.text, latN: lat.value, lonN: lon.value === 180 ? -180 : lon.value, time: null, ele: null, name: unguard(cell(row, mapping.name)) };
    if (mapping.time >= 0) {
      const raw = cell(row, mapping.time);
      if (raw === '') report.noTime++;
      else {
        const t = parseTime(raw);
        if (!t) report.badTime++;
        else { point.time = t.ms; if (t.assumedUtc) report.assumedUtc++; }
      }
    }
    if (mapping.ele >= 0) {
      const raw = cell(row, mapping.ele);
      const e = number(raw, decimalComma);
      if (e) point.ele = e.text; else if (raw !== '') report.badElevation++;
    }
    point.kind = mapping.kind >= 0 ? (['track', 'route', 'waypoint'].includes(cell(row, mapping.kind).toLowerCase()) ? cell(row, mapping.kind).toLowerCase() : 'track') : (mode === 'waypoints' ? 'waypoint' : 'track');
    if (mode === 'track') point.kind = 'track';
    if (mode === 'waypoints') point.kind = 'waypoint';
    point.group = mapping.group >= 0 ? cell(row, mapping.group) : '';
    points.push(point);
  }
  report.written = points.length;
  const timed = points.filter(p => p.time !== null).map(p => p.time);
  if (timed.length) { report.firstTime = isoUTC(Math.min(...timed)); report.lastTime = isoUTC(Math.max(...timed)); }

  const body = p => (p.ele !== null ? `<ele>${p.ele}</ele>` : '') + (p.time !== null ? `<time>${isoUTC(p.time)}</time>` : '');
  const at = (tag, p, inner) => `<${tag} lat="${p.lat}" lon="${p.lon}">${inner}</${tag}>`;
  // Consecutive points of one kind with the same group value share a segment (or route).
  const runs = [];
  for (const p of points.filter(p => p.kind !== 'waypoint')) {
    const last = runs.at(-1);
    if (last && last.kind === p.kind && last.group === p.group) last.points.push(p);
    else runs.push({ kind: p.kind, group: p.group, points: [p] });
  }
  const waypoints = points.filter(p => p.kind === 'waypoint');
  const lines = [];
  waypoints.forEach((p, i) => {
    const name = p.name || (mode === 'waypoints' ? `Point ${i + 1}` : '');
    lines.push('  ' + at('wpt', p, body(p) + (name ? `<name>${xml(name)}</name>` : '')));
  });
  for (const run of runs.filter(r => r.kind === 'route')) {
    lines.push('  <rte>', ...run.points.map(p => '    ' + at('rtept', p, body(p) + (p.name ? `<name>${xml(p.name)}</name>` : ''))), '  </rte>');
  }
  const trackRuns = runs.filter(r => r.kind === 'track');
  if (trackRuns.length) {
    lines.push('  <trk>', `    <name>${xml(fileName)}</name>`);
    for (const run of trackRuns) lines.push('    <trkseg>', ...run.points.map(p => '      ' + at('trkpt', p, body(p) + (p.name ? `<name>${xml(p.name)}</name>` : ''))), '    </trkseg>');
    lines.push('  </trk>');
  }
  report.segments = trackRuns.length;
  report.tracks = trackRuns.length ? 1 : 0;
  report.routes = runs.filter(r => r.kind === 'route').length;
  report.waypoints = waypoints.length;
  const gpx = `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="Whereday CSV to GPX (whereday.com/tools/csv-to-gpx/)" xmlns="http://www.topografix.com/GPX/1/1">\n  <metadata><name>${xml(fileName)}</name></metadata>\n${lines.join('\n')}\n</gpx>\n`;

  // The same shape the GPX viewer plots: groups of points, with waypoints on their own.
  const toPlot = p => ({ lat: p.latN, lon: p.lonN });
  const groups = [...waypoints.map(p => ({ points: [toPlot(p)] })), ...runs.map(r => ({ points: r.points.map(toPlot) }))];
  report.plot = plotPaths({ points: groups.flatMap(g => g.points), groups });
  report.skippedRows = report.skipped.length;
  return { gpx, report };
}

export function csvToGPX(text, options = {}) {
  const table = readCSV(text, options);
  return { table, ...convertCSV(table, options) };
}

export const looksLikeGPX = text => /^\s*(?:\uFEFF)?<(?:\?xml|gpx)\b/i.test(text);

export function gpxToCSV(text, Parser) {
  const model = parseGPX(text, Parser);
  return { model, csv: toCSV(model) };
}

export function safeBase(name) {
  const base = String(name || 'points').replace(/^.*[\\/]/, '').replace(/\.(csv|txt|gpx)$/i, '').replace(/[^A-Za-z0-9._ -]+/g, '-').replace(/^[.\s-]+|[.\s-]+$/g, '');
  return base.slice(0, 80) || 'points';
}
