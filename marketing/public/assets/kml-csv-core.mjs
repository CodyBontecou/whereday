// KML/KMZ to CSV and CSV to KML, locally. Pure functions; the page only reads files and offers downloads.
// Reuses the KML viewer's XML guard and helpers, the KML to GPX time parser and the CSV to GPX table reader.
import { MAX_BYTES, MAX_POINTS } from './gpx-core.mjs';
import { parseXML, KML_NAMESPACES, GX, elementsOf, kmlName, gxName, clean, childText } from './kml-viewer-core.mjs';
import { instant } from './kml-gpx-core.mjs';
import { crc32, isZip } from './kmz-zip.mjs';
import { detectColumns, parseNumber, parseTime, unguard, escapeXML, isoUTC, REASONS } from './csv-gpx-core.mjs';
import { plotPaths } from './gpx-core.mjs';

export { MAX_BYTES, MAX_POINTS, REASONS };
export const FIXED_COLUMNS = ['placemark', 'name', 'description', 'folder', 'geometry', 'part', 'vertex', 'vertex_count', 'latitude', 'longitude', 'altitude_m', 'time_utc', 'original_time'];
// Columns whose text comes from the file and could be read as a spreadsheet formula.
const TEXT_COLUMNS = new Set(['name', 'description', 'folder', 'original_time']);
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
const MAX_DEPTH = 64;
const FOLDER_SEPARATOR = ' / ';

// Elements that are not written to the CSV, counted wherever they appear. [local name, namespace key, label, reason]
export const SKIPPED_ELEMENTS = [
  ['NetworkLink', 'kml', 'NetworkLink', 'Not followed: this page makes no network requests, so linked content is not loaded.'],
  ['GroundOverlay', 'kml', 'GroundOverlay', 'Image overlays have no row to become, so they are not written.'],
  ['PhotoOverlay', 'kml', 'PhotoOverlay', 'Not written.'],
  ['ScreenOverlay', 'kml', 'ScreenOverlay', 'Not written.'],
  ['Model', 'kml', 'Model (3D)', 'Not written.'],
  ['Tour', 'gx', 'gx:Tour', 'Not written.'],
  ['TimeSpan', 'kml', 'TimeSpan', 'Only TimeStamp and gx:Track times are written to the CSV.'],
  ['Style', 'kml', 'Style', 'Colors, icons and line widths are not carried into a CSV.'],
  ['StyleMap', 'kml', 'StyleMap', 'Not carried into a CSV.'],
  ['innerBoundaryIs', 'kml', 'Polygon holes (inner boundaries)', 'Only the outer boundary of a polygon is written.'],
];

// ---- Input detection and decoding -----------------------------------------------------------------
// The type comes from the content: ZIP signature, then XML with a kml root, otherwise text for the CSV reader.
export function detectInput(bytes) {
  if (isZip(bytes)) return 'kmz';
  const head = decodeText(bytes.subarray(0, 4096)).replace(/^﻿/, '').trimStart();
  if (/^<(?:\?xml|!--|kml\b|[A-Za-z][\w.-]*:kml\b)/i.test(head)) return 'kml';
  return 'csv';
}

// UTF-8 when valid; otherwise Windows-1252, which is what many spreadsheets write for "CSV".
export function decodeText(bytes) {
  try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
  catch { return new TextDecoder('windows-1252').decode(bytes); }
}

// ---- KML reading ----------------------------------------------------------------------------------
function position(parts) {
  const p = parts.map(s => s.trim());
  if (p.length < 2 || p.length > 3 || !p.every(s => DECIMAL.test(s))) return null;
  if (Math.abs(Number(p[1])) > 90 || Math.abs(Number(p[0])) > 180) return null;
  return { lon: p[0], lat: p[1], alt: p[2] ?? '', when: '' };
}

export function htmlToText(html) {
  if (!/<\/?[A-Za-z!][^>]*>|&[#A-Za-z0-9]+;/.test(html)) return html.trim();
  const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
  return html
    .replace(/<(script|style)\b[\s\S]*?<\/\1\s*>/gi, '')
    .replace(/<br\s*\/?>|<\/(?:p|div|li|tr|h[1-6]|table|ul|ol)\s*>/gi, '\n')
    .replace(/<\/(?:td|th)\s*>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<\/?[A-Za-z][^>]*>/g, '')
    .replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
      if (e[0] === '#') { const code = e[1].toLowerCase() === 'x' ? parseInt(e.slice(2), 16) : Number(e.slice(1)); return code > 0 && code < 0x110000 && !(code >= 0xD800 && code <= 0xDFFF) ? String.fromCodePoint(code) : ''; }
      return named[e.toLowerCase()] ?? m;
    })
    .split('\n').map(line => line.replace(/[ \t ]+/g, ' ').trim()).join('\n').replace(/\n{3,}/g, '\n\n').trim();
}

// Reads every Placemark with its geometry parts, folder path, ExtendedData and time. No styles, no network.
export function parseKmlForCsv(text, { Parser = globalThis.DOMParser } = {}) {
  const root = parseXML(text, Parser);
  const counts = { placemarks: 0, noGeometry: 0, invalidPositions: 0, multiGeometries: 0 };
  const placemarks = [], extNames = [], notes = [];
  let positions = 0;
  const add = list => { positions += list.length; if (positions > MAX_POINTS) throw new Error('This file has more than 50,000 coordinates. Open a smaller file.'); };
  const tuples = value => {
    const out = [];
    for (const token of value.split(/\s+/).filter(Boolean)) { const p = position(token.split(',')); if (p) out.push(p); else counts.invalidPositions++; }
    add(out); return out;
  };
  const coordinatesOf = el => { const c = elementsOf(el).find(e => kmlName(e) === 'coordinates'); return c ? tuples(clean(c.textContent)) : []; };

  function geometry(el, pm) {
    const kml = kmlName(el), gx = gxName(el);
    if (kml === 'Point') { const c = coordinatesOf(el); if (c.length) pm.parts.push({ geometry: 'Point', coords: [c[0]] }); }
    else if (kml === 'LineString' || kml === 'LinearRing') { const c = coordinatesOf(el); if (c.length) pm.parts.push({ geometry: kml, coords: c }); }
    else if (kml === 'Polygon') {
      const outer = elementsOf(el).find(e => kmlName(e) === 'outerBoundaryIs');
      const ring = outer && elementsOf(outer).find(e => kmlName(e) === 'LinearRing');
      const c = ring ? coordinatesOf(ring) : [];
      if (c.length) pm.parts.push({ geometry: 'Polygon', coords: c });
    } else if (kml === 'MultiGeometry') { counts.multiGeometries++; pm.multi = true; for (const child of elementsOf(el)) geometry(child, pm); }
    else if (gx === 'Track') {
      const kids = elementsOf(el);
      const whens = kids.filter(e => kmlName(e) === 'when').map(e => clean(e.textContent));
      const coords = [];
      const raw = kids.filter(e => gxName(e) === 'coord');
      raw.forEach((e, i) => { const p = position(clean(e.textContent).split(/\s+/)); if (!p) { counts.invalidPositions++; return; } p.when = i < whens.length ? whens[i] : ''; coords.push(p); });
      add(coords);
      if (whens.length !== raw.length) notes.push('A gx:Track has a different number of <when> times and <gx:coord> positions; times are matched by order and the extras are ignored.');
      if (coords.length) pm.parts.push({ geometry: 'gx:Track', coords });
    } else if (gx === 'MultiTrack') { pm.multi = true; for (const child of elementsOf(el)) if (gxName(child) === 'Track') geometry(child, pm); }
  }

  function extendedData(el, pm) {
    for (const ed of elementsOf(el).filter(e => kmlName(e) === 'ExtendedData')) {
      const put = (name, value) => { if (!name || name in pm.ext) return; pm.ext[name] = value; if (!extNames.includes(name)) extNames.push(name); };
      for (const child of elementsOf(ed)) {
        if (kmlName(child) === 'Data') put(child.getAttribute('name')?.trim(), childText(child, 'value'));
        else if (kmlName(child) === 'SchemaData') for (const d of elementsOf(child)) if (kmlName(d) === 'SimpleData') put(d.getAttribute('name')?.trim(), clean(d.textContent));
      }
    }
  }

  function placemark(el, folder) {
    counts.placemarks++;
    const pm = { index: counts.placemarks, name: childText(el, 'name'), description: childText(el, 'description'), folder, when: '', ext: {}, parts: [], multi: false };
    const stamp = elementsOf(el).find(e => kmlName(e) === 'TimeStamp');
    if (stamp) pm.when = childText(stamp, 'when');
    extendedData(el, pm);
    for (const child of elementsOf(el)) geometry(child, pm);
    if (!pm.parts.length) counts.noGeometry++;
    placemarks.push(pm);
  }

  function container(el, path, depth) {
    if (depth > MAX_DEPTH) { notes.push('Folders nested more than 64 levels deep were not read.'); return; }
    for (const child of elementsOf(el)) {
      const name = kmlName(child);
      if (name === 'Placemark') placemark(child, path.join(FOLDER_SEPARATOR));
      else if (name === 'Folder' || name === 'Document') {
        const label = childText(child, 'name');
        // The outermost Document is the file's title; folder paths start below it.
        const next = name === 'Document' && depth === 0 ? path : label ? [...path, label] : path;
        container(child, next, depth + 1);
      }
    }
  }
  container(root, [], 0);

  const found = new Map();
  for (const el of root.getElementsByTagName('*')) {
    for (const [local, ns, label, reason] of SKIPPED_ELEMENTS) {
      if (el.localName === local && (ns === 'gx' ? el.namespaceURI === GX : KML_NAMESPACES.includes(el.namespaceURI || ''))) {
        const entry = found.get(label) || { label, reason, count: 0 }; entry.count++; found.set(label, entry);
      }
    }
  }
  const skipped = SKIPPED_ELEMENTS.map(s => found.get(s[2])).filter(Boolean);
  if (counts.noGeometry) skipped.push({ label: 'Placemarks with no usable geometry', count: counts.noGeometry, reason: 'No Point, LineString, LinearRing, Polygon or gx:Track coordinates, so there is no row to write.' });
  if (counts.invalidPositions) skipped.push({ label: 'Invalid coordinates', count: counts.invalidPositions, reason: 'Not numbers, or outside longitude ±180 or latitude ±90. Left out.' });
  return { placemarks, extNames, counts, skipped, notes: [...new Set(notes)], name: childText(root, 'name') || elementsOf(root).filter(e => kmlName(e) === 'Document').map(e => childText(e, 'name')).find(Boolean) || '' };
}

// What was inside a KMZ besides the KML that was read. Images and other files are not written to a CSV.
export function describeKmzEntries(others) {
  const images = others.filter(o => /\.(png|jpe?g|gif|webp|bmp|tiff?|svg)$/i.test(o.name));
  const kmls = others.filter(o => /\.kml$/i.test(o.name));
  const rest = others.filter(o => !images.includes(o) && !kmls.includes(o));
  const out = [];
  if (images.length) out.push({ label: 'Images inside the KMZ', count: images.length, reason: 'Icons, overlays and photos are not read; a CSV has no place for them.' });
  if (kmls.length) out.push({ label: 'Other KML files inside the KMZ', count: kmls.length, reason: 'Only doc.kml (or the first top-level .kml) is converted.' });
  if (rest.length) out.push({ label: 'Other files inside the KMZ', count: rest.length, reason: 'Not read.' });
  return out;
}

// ---- KML to CSV rows ------------------------------------------------------------------------------
const iso = ms => new Date(ms).toISOString().replace('.000Z', 'Z');
function timeCells(raw) {
  if (!raw) return { utc: '', original: '' };
  const ms = instant(raw);
  if (ms === null) return { utc: '', original: raw };
  const utc = iso(ms);
  return { utc, original: raw === utc ? '' : raw };
}

// mode: 'vertex' (one row per vertex, the default) or 'placemark' (one row per geometry part, with its first vertex and vertex_count).
export function buildRows(model, { mode = 'vertex', keepHtml = false } = {}) {
  const taken = new Set(FIXED_COLUMNS);
  const extColumns = model.extNames.map(name => ({ name, column: taken.has(name) ? `${name} (data)` : name }));
  const columns = [...FIXED_COLUMNS, ...extColumns.map(e => e.column)];
  const rows = [];
  const geometries = {};
  let parts = 0, polygons = 0, withTimes = 0, first = null, last = null;
  const plotGroups = [];
  for (const pm of model.placemarks) {
    const description = keepHtml ? pm.description : htmlToText(pm.description);
    pm.parts.forEach((part, partIndex) => {
      parts++;
      geometries[part.geometry] = (geometries[part.geometry] || 0) + 1;
      if (part.geometry === 'Polygon') polygons++;
      const n = part.coords.length;
      const picked = mode === 'placemark' ? [0] : part.coords.map((_, i) => i);
      plotGroups.push({ points: part.coords.map(c => ({ lat: Number(c.lat), lon: Number(c.lon) })) });
      for (const i of picked) {
        const c = part.coords[i];
        const t = timeCells(c.when || pm.when);
        if (t.utc) { withTimes++; const ms = Date.parse(t.utc); if (first === null || ms < first) first = ms; if (last === null || ms > last) last = ms; }
        const row = {
          placemark: String(pm.index), name: pm.name, description, folder: pm.folder, geometry: part.geometry,
          part: pm.multi ? String(partIndex + 1) : '', vertex: String(i + 1), vertex_count: String(n),
          latitude: c.lat, longitude: c.lon, altitude_m: c.alt, time_utc: t.utc, original_time: t.original,
        };
        for (const e of extColumns) row[e.column] = pm.ext[e.name] ?? '';
        rows.push(row);
      }
    });
  }
  const report = {
    mode, keepHtml, placemarks: model.counts.placemarks, placemarksWithGeometry: model.counts.placemarks - model.counts.noGeometry,
    geometryParts: parts, geometries, multiGeometries: model.counts.multiGeometries, polygons,
    rows: rows.length, verticesExpanded: mode === 'vertex' ? rows.length - parts : 0, extColumns: extColumns.map(e => e.column),
    withTimes, firstTime: first === null ? null : iso(first), lastTime: last === null ? null : iso(last),
    skipped: model.skipped.slice(), notes: model.notes.slice(),
    plot: plotPaths({ points: plotGroups.flatMap(g => g.points), groups: plotGroups }),
  };
  if (polygons) report.notes.push(mode === 'vertex' ? `${polygons === 1 ? 'The polygon is' : `${polygons} polygons are`} written as its outer-boundary vertices, one row each, ending with the closing vertex that repeats the first.` : `${polygons === 1 ? 'The polygon is' : `${polygons} polygons are`} written as one row with the first outer-boundary vertex and its vertex_count.`);
  return { columns, rows, report };
}

// ---- CSV text (RFC 4180) --------------------------------------------------------------------------
// Text that a spreadsheet could run as a formula gets a leading apostrophe (OWASP CSV injection guidance).
// Plain decimal numbers are left alone so that "-5" stays a number.
export function guardCell(value) {
  const s = String(value ?? '');
  return (/^[\s]*[=+\-@]/.test(s) || /^[\t\r\n]/.test(s)) && !DECIMAL.test(s) ? "'" + s : s;
}
export function csvField(value) {
  const s = String(value ?? '');
  return /[",\r\n]|^\s|\s$/.test(s) ? '"' + s.replaceAll('"', '""') + '"' : s;
}
export function toCSVText({ columns, rows }, { bom = false } = {}) {
  const ext = new Set(columns.filter(c => !FIXED_COLUMNS.includes(c)));
  const line = cells => cells.map(csvField).join(',');
  const body = rows.map(row => line(columns.map(c => (TEXT_COLUMNS.has(c) || ext.has(c) ? guardCell(row[c]) : row[c]))));
  return (bom ? '﻿' : '') + [line(columns), ...body].join('\r\n') + '\r\n';
}

export function kmlToCSV(text, { Parser, mode = 'vertex', keepHtml = false, bom = false } = {}) {
  const model = parseKmlForCsv(text, { Parser });
  const table = buildRows(model, { mode, keepHtml });
  return { model, ...table, csv: toCSVText(table, { bom }) };
}

// ---- CSV to KML -----------------------------------------------------------------------------------
const lower = s => String(s ?? '').replace(/^﻿/, '').trim().toLowerCase();
const DESCRIPTION_ALIASES = ['description', 'desc', 'notes', 'comment'];

export function kmlMapping(table) {
  const mapping = { ...detectColumns(table.headers) };
  const names = table.headers.map(lower);
  const find = list => { for (const a of list) { const i = names.indexOf(a); if (i >= 0) return i; } return -1; };
  if (!table.hasHeader) return { ...table.mapping, description: -1 };
  if (mapping.ele < 0) mapping.ele = find(['altitude_m']);
  mapping.description = find(DESCRIPTION_ALIASES);
  return mapping;
}
// A CSV written by this tool has these headers; they let the rows come back as the same placemarks.
export function isOwnExport(table) {
  if (!table.hasHeader) return false;
  const names = table.headers.map(lower);
  return ['placemark', 'geometry', 'vertex', 'latitude', 'longitude'].every(h => names.includes(h));
}
export function defaultKmlMode(table) { return isOwnExport(table) ? 'placemarks' : 'points'; }

const coordText = r => `${r.lon},${r.lat}${r.alt !== '' ? ',' + r.alt : ''}`;
function trackXML(rows, indent) {
  return `${indent}<gx:Track>\n${rows.map(r => `${indent}  <when>${escapeXML(r.when)}</when>`).join('\n')}\n${rows.map(r => `${indent}  <gx:coord>${r.lon} ${r.lat}${r.alt !== '' ? ' ' + r.alt : ''}</gx:coord>`).join('\n')}\n${indent}</gx:Track>`;
}
function ringXML(rows, indent) {
  return `${indent}<Polygon><outerBoundaryIs><LinearRing><coordinates>${rows.map(coordText).join(' ')}</coordinates></LinearRing></outerBoundaryIs></Polygon>`;
}

// Turns a table from readCSV into KML text and a report.
// mode: 'points' (one Placemark per row), 'line' (one line in row order) or 'placemarks' (a CSV exported by this tool).
export function convertCSVToKML(table, { mapping = kmlMapping(table), mode = defaultKmlMode(table), fileName = 'points' } = {}) {
  if (!(mapping.lat >= 0) || !(mapping.lon >= 0)) throw new Error('Choose a latitude column and a longitude column.');
  if (mapping.lat === mapping.lon) throw new Error('Latitude and longitude need two different columns.');
  if (mode === 'placemarks' && !isOwnExport(table)) throw new Error('This CSV was not written by this tool, so rows cannot be grouped back into placemarks. Choose points or a line.');
  const decimalComma = table.delimiter !== ',';
  const cell = (row, i) => (i >= 0 ? String(row[i] ?? '').replace(/^﻿/, '').trim() : '');
  const names = table.headers.map(lower);
  const col = h => names.indexOf(h);
  const own = mode === 'placemarks';
  const report = {
    mode, rowsRead: table.rows.length, written: 0, placemarks: 0, skipped: [], skippedByReason: { missing: 0, nonNumeric: 0, outOfRange: 0 },
    assumedUtc: 0, badTime: 0, extColumns: [], notes: [], kmz: false,
  };
  const used = new Set([mapping.lat, mapping.lon, mapping.time, mapping.ele, mapping.name, mapping.description].filter(i => i >= 0));
  if (own) for (const h of FIXED_COLUMNS) if (col(h) >= 0) used.add(col(h));
  const ext = table.headers.map((h, i) => [h, i]).filter(([, i]) => !used.has(i));
  report.extColumns = ext.map(([h]) => h);
  const timeIndex = own && col('time_utc') >= 0 ? col('time_utc') : mapping.time;

  const rows = [];
  for (const [index, row] of table.rows.entries()) {
    const lat = parseNumber(cell(row, mapping.lat), decimalComma), lon = parseNumber(cell(row, mapping.lon), decimalComma);
    let reason = null;
    if (cell(row, mapping.lat) === '' || cell(row, mapping.lon) === '') reason = 'missing';
    else if (!lat || !lon) reason = 'nonNumeric';
    else if (Math.abs(lat.value) > 90 || Math.abs(lon.value) > 180) reason = 'outOfRange';
    if (reason) { report.skippedByReason[reason]++; report.skipped.push({ row: table.firstRow + index, reason }); continue; }
    const alt = parseNumber(cell(row, mapping.ele), decimalComma);
    let stamp = '';
    const rawTime = cell(row, timeIndex);
    if (rawTime) {
      const t = parseTime(rawTime);
      if (t) { stamp = isoUTC(t.ms); if (t.assumedUtc) report.assumedUtc++; }
      else if (own && /^\d{4}(?:-\d\d(?:-\d\d)?)?$/.test(rawTime)) stamp = rawTime;
      else report.badTime++;
    } else if (own && /^\d{4}(?:-\d\d(?:-\d\d)?)?$/.test(cell(row, col('original_time')))) stamp = cell(row, col('original_time'));
    rows.push({
      lat: lat.text, lon: lon.text, alt: alt ? alt.text : '', when: stamp, row,
      name: unguard(cell(row, mapping.name)), description: unguard(cell(row, mapping.description)),
      ext: ext.map(([h, i]) => [h, unguard(cell(row, i))]).filter(([, v]) => v !== ''),
    });
  }
  report.written = rows.length;
  report.skippedRows = report.skipped.length;
  const text = (tag, v) => (v ? `<${tag}>${escapeXML(v)}</${tag}>` : '');
  const extXML = pairs => (pairs.length ? `<ExtendedData>${pairs.map(([k, v]) => `<Data name="${escapeXML(k)}"><value>${escapeXML(v)}</value></Data>`).join('')}</ExtendedData>` : '');
  const stampXML = w => (w ? `<TimeStamp><when>${escapeXML(w)}</when></TimeStamp>` : '');
  let body = [];
  let plotGroups = [];
  const pt = r => ({ lat: Number(r.lat), lon: Number(r.lon) });

  if (mode === 'points') {
    rows.forEach((r, i) => {
      body.push(`    <Placemark>${text('name', r.name || `Point ${i + 1}`)}${text('description', r.description)}${stampXML(r.when)}${extXML(r.ext)}<Point><coordinates>${coordText(r)}</coordinates></Point></Placemark>`);
      plotGroups.push({ points: [pt(r)] });
    });
    report.placemarks = rows.length;
  } else if (mode === 'line') {
    if (rows.length < 2) throw new Error('A line needs at least two rows with usable coordinates.');
    const timed = rows.every(r => r.when && r.when.includes('T'));
    const geometryXML = timed ? trackXML(rows, '      ') : `      <LineString><coordinates>${rows.map(coordText).join(' ')}</coordinates></LineString>`;
    if (!timed && rows.some(r => r.when)) report.notes.push('A plain KML LineString cannot carry times, and not every row has one, so the times were left out.');
    if (timed) report.notes.push('Every row has a time, so the line is written as a Google Earth gx:Track, which keeps the times.');
    body.push(`    <Placemark>${text('name', fileName)}\n${geometryXML}\n    </Placemark>`);
    plotGroups = [{ points: rows.map(pt) }];
    report.placemarks = 1;
    report.lineKind = timed ? 'gx:Track' : 'LineString';
  } else if (mode === 'placemarks') {
    const key = h => col(h);
    const idCol = key('placemark'), geoCol = key('geometry'), partCol = key('part'), folderCol = key('folder');
    const groups = [];
    for (const r of rows) {
      const id = cell(r.row, idCol);
      const last = groups.at(-1);
      if (last && last.id === id) last.rows.push(r); else groups.push({ id, rows: [r] });
    }
    const tree = { children: [], folders: new Map() };
    let collapsed = 0, dropped = 0;
    for (const g of groups) {
      const head = g.rows[0];
      // Consecutive rows with the same part value form one geometry.
      const parts = [];
      for (const r of g.rows) {
        const p = cell(r.row, partCol), geo = cell(r.row, geoCol) || 'Point';
        const last = parts.at(-1);
        if (last && last.part === p) last.rows.push(r); else parts.push({ part: p, geo, rows: [r] });
      }
      const geometries = parts.map(p => {
        const single = p.rows.length === 1;
        if (p.geo === 'Point' || single) {
          if (!single && p.geo === 'Point') collapsed++;
          if (single && p.geo !== 'Point') collapsed++;
          return { kind: 'Point', xml: `<Point><coordinates>${coordText(p.rows[0])}</coordinates></Point>`, rows: p.rows };
        }
        if (p.geo === 'gx:Track' && p.rows.every(r => r.when && r.when.includes('T'))) return { kind: 'gx:Track', rows: p.rows };
        if (p.geo === 'Polygon') return { kind: 'Polygon', xml: ringXML(p.rows, ''), rows: p.rows };
        if (p.geo === 'gx:Track') dropped++;
        return { kind: 'LineString', xml: `<LineString><coordinates>${p.rows.map(coordText).join(' ')}</coordinates></LineString>`, rows: p.rows };
      });
      let geometry;
      if (geometries.length === 1) geometry = geometries[0].kind === 'gx:Track' ? '\n' + trackXML(geometries[0].rows, '      ') + '\n    ' : geometries[0].xml;
      else if (geometries.every(x => x.kind === 'gx:Track')) geometry = `<gx:MultiTrack>\n${geometries.map(x => trackXML(x.rows, '        ')).join('\n')}\n      </gx:MultiTrack>`;
      else geometry = `<MultiGeometry>${geometries.map(x => x.kind === 'gx:Track' ? `<LineString><coordinates>${x.rows.map(coordText).join(' ')}</coordinates></LineString>` : x.xml).join('')}</MultiGeometry>`;
      if (geometries.length > 1 && geometries.some(x => x.kind === 'gx:Track') && !geometries.every(x => x.kind === 'gx:Track')) dropped++;
      const w = geometries.some(x => x.kind === 'gx:Track') ? '' : head.when;
      const xml = `<Placemark>${text('name', head.name)}${text('description', head.description)}${stampXML(w)}${extXML(head.ext)}${geometry}</Placemark>`;
      let node = tree;
      for (const seg of folderCol >= 0 ? cell(head.row, folderCol).split(FOLDER_SEPARATOR).filter(Boolean) : []) {
        if (!node.folders) node.folders = new Map();
        let next = node.folders.get(seg);
        if (!next) { next = { name: seg, children: [], folders: new Map() }; node.folders.set(seg, next); node.children.push(next); }
        node = next;
      }
      node.children.push(xml);
      for (const x of geometries) plotGroups.push({ points: x.rows.map(pt) });
    }
    const render = (node, depth) => node.children.map(c => (typeof c === 'string' ? '    '.repeat(depth) + c : `${'    '.repeat(depth)}<Folder><name>${escapeXML(c.name)}</name>\n${render(c, depth + 1)}\n${'    '.repeat(depth)}</Folder>`)).join('\n');
    body = [render(tree, 1)];
    report.placemarks = groups.length;
    if (collapsed) report.notes.push(`${collapsed} placemark ${collapsed === 1 ? 'part had' : 'parts had'} a single row for a line or polygon, so ${collapsed === 1 ? 'it was' : 'they were'} written as a Point. Export the CSV in one-row-per-vertex mode to keep shapes.`);
    if (dropped) report.notes.push(`${dropped} gx:Track ${dropped === 1 ? 'part was' : 'parts were'} written as a plain LineString because a row had no time or the part sits in a mixed MultiGeometry.`);
  } else throw new Error('Unknown mode.');

  const kml = `<?xml version="1.0" encoding="UTF-8"?>\n<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">\n  <Document>\n    <name>${escapeXML(fileName)}</name>\n${body.join('\n')}\n  </Document>\n</kml>\n`;
  report.plot = plotPaths({ points: plotGroups.flatMap(g => g.points), groups: plotGroups });
  return { kml, report };
}

// ---- KMZ writing (stored, no compression) ---------------------------------------------------------
// One entry, doc.kml, as Google's KMZ guide describes. The stored method needs no library.
export function buildKmz(kmlText) {
  const data = new TextEncoder().encode(kmlText);
  const name = new TextEncoder().encode('doc.kml');
  const crc = crc32(data);
  const local = new Uint8Array(30 + name.length);
  const lv = new DataView(local.buffer);
  lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, 0x0800, true); lv.setUint16(8, 0, true);
  lv.setUint16(10, 0, true); lv.setUint16(12, 0x21, true); // 1980-01-01 00:00:00
  lv.setUint32(14, crc, true); lv.setUint32(18, data.length, true); lv.setUint32(22, data.length, true); lv.setUint16(26, name.length, true); lv.setUint16(28, 0, true);
  local.set(name, 30);
  const central = new Uint8Array(46 + name.length);
  const cv = new DataView(central.buffer);
  cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true); cv.setUint16(8, 0x0800, true); cv.setUint16(10, 0, true);
  cv.setUint16(12, 0, true); cv.setUint16(14, 0x21, true); cv.setUint32(16, crc, true); cv.setUint32(20, data.length, true); cv.setUint32(24, data.length, true);
  cv.setUint16(28, name.length, true); cv.setUint32(42, 0, true);
  central.set(name, 46);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, 1, true); ev.setUint16(10, 1, true); ev.setUint32(12, central.length, true); ev.setUint32(16, local.length + data.length, true);
  const out = new Uint8Array(local.length + data.length + central.length + end.length);
  out.set(local, 0); out.set(data, local.length); out.set(central, local.length + data.length); out.set(end, local.length + data.length + central.length);
  return out;
}
