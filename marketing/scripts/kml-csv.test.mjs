import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { readKmz } from '../public/assets/kmz-zip.mjs';
import { readCSV, parseCSV } from '../public/assets/csv-gpx-core.mjs';
import { parseKmlForCsv, buildRows, toCSVText, kmlToCSV, convertCSVToKML, detectInput, decodeText, htmlToText, guardCell, csvField, buildKmz, describeKmzEntries, isOwnExport, FIXED_COLUMNS } from '../public/assets/kml-csv-core.mjs';
import { KML_CSV_SAMPLES } from '../public/assets/kml-csv-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const sample = name => readFile(new URL(`../public/samples/${name}`, import.meta.url));
const kmlText = (await sample('synthetic-kml-to-csv.kml')).toString('utf8');
const expectedCsv = (await sample('synthetic-kml-to-csv.expected.csv')).toString('utf8');
const kml = body => `<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>${body}</Document></kml>`;
const convert = (text, options = {}) => kmlToCSV(text, { Parser: XMLParser, ...options });
const records = csv => { const [header, ...rows] = parseCSV(csv); return rows.map(r => Object.fromEntries(header.map((h, i) => [h, r[i]]))); };

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function assertWellFormed(xml) {
  if (!xmllint) return;
  const dir = mkdtempSync(join(tmpdir(), 'kml-csv-'));
  try { const file = join(dir, 'out.kml'); writeFileSync(file, xml); execFileSync('xmllint', ['--noout', '--nonet', file], { stdio: 'pipe' }); }
  catch (e) { assert.fail(`output is not well-formed XML: ${e.stderr?.toString() || e.message}`); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}

test('the page samples match the sample files', async () => {
  assert.equal(KML_CSV_SAMPLES.kml.text, kmlText);
  assert.equal(KML_CSV_SAMPLES.csv.text, expectedCsv);
  for (const [key, name] of [['kmz', 'synthetic-kml-to-csv.kmz'], ['kmzStored', 'synthetic-kml-to-csv-stored.kmz']]) {
    assert.equal(KML_CSV_SAMPLES[key].base64, (await sample(name)).toString('base64'));
  }
});

test('the expected CSV is what the converter writes for the sample', () => {
  assert.equal(convert(kmlText).csv, expectedCsv);
});

test('type detection uses content, not the extension', async () => {
  const bytes = s => new TextEncoder().encode(s);
  assert.equal(detectInput(new Uint8Array(await sample('synthetic-kml-to-csv.kmz'))), 'kmz');
  assert.equal(detectInput(new Uint8Array(await sample('synthetic-kml-to-csv-stored.kmz'))), 'kmz');
  assert.equal(detectInput(bytes(kmlText)), 'kml');
  assert.equal(detectInput(bytes('﻿  <kml xmlns="http://www.opengis.net/kml/2.2"/>')), 'kml');
  assert.equal(detectInput(bytes('lat,lon\n1,2\n')), 'csv');
  assert.equal(detectInput(bytes('name,latitude,longitude\n<b>,1,2\n')), 'csv');
  assert.throws(() => parseKmlForCsv('<gpx version="1.1"/>', { Parser: XMLParser }), /root element is not <kml>/);
});

test('KMZ, deflate and stored, gives the same CSV as the KML', async () => {
  for (const name of ['synthetic-kml-to-csv.kmz', 'synthetic-kml-to-csv-stored.kmz']) {
    const kmz = await readKmz(new Uint8Array(await sample(name)));
    assert.equal(kmz.kmlName, 'doc.kml');
    assert.equal(convert(kmz.kmlText).csv, expectedCsv);
    assert.deepEqual(describeKmzEntries(kmz.others).map(s => [s.label, s.count]), [['Images inside the KMZ', 1]]);
  }
});

test('longitude,latitude,altitude becomes latitude, longitude, altitude_m and never swaps', () => {
  const r = convert(kml('<Placemark><name>A</name><Point><coordinates>10.5,-33.25,120</coordinates></Point></Placemark>'));
  const [row] = records(r.csv);
  assert.equal(row.latitude, '-33.25');
  assert.equal(row.longitude, '10.5');
  assert.equal(row.altitude_m, '120');
  assert.deepEqual(r.columns.slice(0, FIXED_COLUMNS.length), FIXED_COLUMNS);
  // Latitude 91 is impossible, so a swapped pair is rejected rather than written.
  const bad = convert(kml('<Placemark><Point><coordinates>10,91</coordinates></Point></Placemark>'));
  assert.equal(bad.rows.length, 0);
  assert.equal(bad.report.skipped.find(s => s.label === 'Invalid coordinates').count, 1);
});

test('vertex mode writes a row per vertex; placemark mode writes the first vertex and a count', () => {
  const v = convert(kmlText);
  assert.equal(v.report.placemarks, 7);
  assert.equal(v.report.rows, 17);
  assert.equal(v.report.verticesExpanded, 9);
  assert.deepEqual(v.report.geometries, { Point: 4, LineString: 2, Polygon: 1, 'gx:Track': 1 });
  const loop = records(v.csv).filter(r => r.name === 'Made-up loop path');
  assert.deepEqual(loop.map(r => [r.vertex, r.vertex_count, r.latitude, r.longitude]), [['1', '3', '40.7829', '-73.9654'], ['2', '3', '40.7832', '-73.9658'], ['3', '3', '40.7834', '-73.9663']]);
  const p = convert(kmlText, { mode: 'placemark' });
  const rows = records(p.csv);
  assert.equal(rows.length, 8);
  assert.equal(p.report.verticesExpanded, 0);
  const line = rows.find(r => r.name === 'Made-up loop path');
  assert.deepEqual([line.vertex, line.vertex_count, line.latitude, line.longitude], ['1', '3', '40.7829', '-73.9654']);
  const poly = rows.find(r => r.geometry === 'Polygon');
  assert.equal(poly.vertex_count, '5');
});

test('polygons use the outer boundary and the report says so; holes are counted as skipped', () => {
  const r = convert(kmlText);
  assert.equal(r.report.polygons, 1);
  assert.ok(r.report.notes.some(n => /outer-boundary vertices/.test(n)));
  assert.deepEqual(r.report.skipped.find(s => s.label.startsWith('Polygon holes')), { label: 'Polygon holes (inner boundaries)', reason: 'Only the outer boundary of a polygon is written.', count: 1 });
  assert.equal(records(r.csv).filter(x => x.geometry === 'Polygon').length, 5);
});

test('MultiGeometry parts are numbered in the part column', () => {
  const rows = records(convert(kmlText).csv).filter(r => r.name === 'Invented gate and trail');
  assert.deepEqual(rows.map(r => [r.part, r.geometry, r.vertex]), [['1', 'Point', '1'], ['2', 'LineString', '1'], ['2', 'LineString', '2']]);
  const single = records(convert(kmlText).csv).find(r => r.name === 'Made-up fountain');
  assert.equal(single.part, '');
  const tracks = convert(kml('<Placemark><name>T</name><gx:MultiTrack><gx:Track><when>2000-01-01T00:00:00Z</when><gx:coord>1 2</gx:coord></gx:Track><gx:Track><when>2000-01-01T00:01:00Z</when><gx:coord>3 4</gx:coord></gx:Track></gx:MultiTrack></Placemark>'));
  assert.deepEqual(records(tracks.csv).map(r => [r.part, r.latitude, r.longitude, r.time_utc]), [['1', '2', '1', '2000-01-01T00:00:00Z'], ['2', '4', '3', '2000-01-01T00:01:00Z']]);
});

test('ExtendedData Data and SimpleData become columns', () => {
  const r = convert(kmlText);
  assert.deepEqual(r.report.extColumns, ['category', 'visitors']);
  const rows = records(r.csv);
  assert.deepEqual(rows.filter(x => x.placemark <= 2).map(x => [x.category, x.visitors]), [['landmark', '120'], ['seating', '8']]);
  // A data name that collides with a fixed column is renamed rather than overwriting it.
  const clash = convert(kml('<Placemark><name>N</name><ExtendedData><Data name="name"><value>other</value></Data></ExtendedData><Point><coordinates>1,2</coordinates></Point></Placemark>'));
  assert.ok(clash.columns.includes('name (data)'));
  assert.equal(records(clash.csv)[0].name, 'N');
});

test('times: TimeStamp and gx:Track are converted to UTC and the original is kept', () => {
  const rows = records(convert(kmlText).csv);
  const fountain = rows.find(r => r.name === 'Made-up fountain');
  assert.deepEqual([fountain.time_utc, fountain.original_time], ['2000-01-01T14:30:00Z', '2000-01-01T09:30:00-05:00']);
  const walk = rows.filter(r => r.name === 'Timed invented walk');
  assert.deepEqual(walk.map(r => [r.time_utc, r.original_time]), [['2000-01-01T14:00:00Z', ''], ['2000-01-01T14:01:00Z', ''], ['2000-01-01T14:02:00Z', '']]);
  const dateOnly = records(convert(kml('<Placemark><TimeStamp><when>2000-01-01</when></TimeStamp><Point><coordinates>1,2</coordinates></Point></Placemark>')).csv)[0];
  assert.deepEqual([dateOnly.time_utc, dateOnly.original_time], ['', '2000-01-01']);
  assert.equal(convert(kmlText).report.firstTime, '2000-01-01T14:00:00Z');
});

test('skipped items are reported with a reason', () => {
  const labels = convert(kmlText).report.skipped.map(s => s.label);
  for (const expected of ['NetworkLink', 'GroundOverlay', 'Style']) assert.ok(labels.includes(expected), expected);
  const empty = convert(kml('<Placemark><name>No shape</name></Placemark>'));
  assert.equal(empty.rows.length, 0);
  assert.equal(empty.report.skipped[0].count, 1);
  assert.ok(empty.report.skipped.every(s => s.reason));
});

test('descriptions: HTML becomes text, or is kept on request', () => {
  assert.equal(htmlToText('<b>Invented</b> landmark<br/>Water &amp; <i>more</i>&nbsp;here<script>x()</script>'), 'Invented landmark\nWater & more here');
  assert.equal(htmlToText('a < b and 5 > 3'), 'a < b and 5 > 3');
  assert.equal(htmlToText('<p>One</p><p>Two</p>'), 'One\nTwo');
  const text = records(convert(kmlText).csv)[0].description;
  assert.equal(text, 'Invented landmark\nWater is off in winter & spring');
  const kept = records(convert(kmlText, { keepHtml: true }).csv)[0].description;
  assert.equal(kept, '<b>Invented</b> landmark<br/>Water is <i>off</i> in winter &amp; spring');
});

test('CSV quoting follows RFC 4180 and formula-like cells get an apostrophe', () => {
  assert.equal(csvField('plain'), 'plain');
  assert.equal(csvField('a,b'), '"a,b"');
  assert.equal(csvField('say "hi"'), '"say ""hi"""');
  assert.equal(csvField('line\nbreak'), '"line\nbreak"');
  for (const lead of ['=', '+', '-', '@']) assert.equal(guardCell(`${lead}cmd`), `'${lead}cmd`);
  assert.equal(guardCell('-5'), '-5');
  assert.equal(guardCell('safe'), 'safe');
  const r = convert(kmlText);
  assert.ok(r.csv.includes('3,\'=SUM(1+1) invented formula,'));
  assert.ok(r.csv.includes('"Bench, ""north"" side"'));
  assert.ok(r.csv.endsWith('\r\n') && r.csv.split('\r\n').length === 19);
  // Negative coordinates and negative plain numbers are not prefixed.
  assert.ok(r.csv.includes(',40.7829,-73.9654,15,'));
  const neg = convert(kml('<Placemark><name>N</name><ExtendedData><Data name="delta"><value>-5</value></Data><Data name="note"><value>@home</value></Data></ExtendedData><Point><coordinates>-1.5,-2.5</coordinates></Point></Placemark>'));
  const [row] = records(neg.csv);
  assert.deepEqual([row.delta, row.note, row.latitude, row.longitude], ['-5', "'@home", '-2.5', '-1.5']);
});

test('the BOM is optional', () => {
  const table = buildRows(parseKmlForCsv(kmlText, { Parser: XMLParser }));
  assert.ok(!toCSVText(table).startsWith('﻿'));
  assert.ok(toCSVText(table, { bom: true }).startsWith('﻿placemark,'));
  assert.equal(parseCSV(toCSVText(table, { bom: true }))[0][0], 'placemark');
});

test('invalid input is refused with a plain message', () => {
  assert.throws(() => parseKmlForCsv('<kml><Document>', { Parser: XMLParser }), /not well-formed|Malformed/);
  assert.throws(() => parseKmlForCsv('<!DOCTYPE x [<!ENTITY a "b">]><kml/>', { Parser: XMLParser }), /DTD and entity/);
  assert.equal(decodeText(Uint8Array.from([0x63, 0x61, 0x66, 0xe9])), 'café');
  assert.equal(decodeText(new TextEncoder().encode('café')), 'café');
});

test('CSV to KML writes longitude,latitude and keeps other columns as ExtendedData', () => {
  const table = readCSV('name,lat,lon,alt,color\nFirst,-33.25,10.5,120,red\nSecond,1.5,2.5,,\n');
  const { kml: out, report } = convertCSVToKML(table, { fileName: 'demo' });
  assertWellFormed(out);
  assert.equal(report.mode, 'points');
  assert.ok(out.includes('<coordinates>10.5,-33.25,120</coordinates>'));
  assert.ok(out.includes('<coordinates>2.5,1.5</coordinates>'));
  assert.ok(out.includes('<Data name="color"><value>red</value></Data>'));
  assert.ok(out.includes('<name>First</name>'));
  assert.ok(out.includes('<kml xmlns="http://www.opengis.net/kml/2.2"'));
  assert.deepEqual([report.rowsRead, report.written, report.placemarks, report.extColumns], [2, 2, 2, ['color']]);
  const back = parseKmlForCsv(out, { Parser: XMLParser });
  assert.deepEqual(back.placemarks.map(p => [p.name, p.parts[0].coords[0].lat, p.parts[0].coords[0].lon]), [['First', '-33.25', '10.5'], ['Second', '1.5', '2.5']]);
});

test('CSV to KML: one line in row order, and a gx:Track when every row has a time', () => {
  const plain = convertCSVToKML(readCSV('lat,lon\n1,2\n3,4\n5,6\n'), { mode: 'line', fileName: 'path' });
  assertWellFormed(plain.kml);
  assert.ok(plain.kml.includes('<LineString><coordinates>2,1 4,3 6,5</coordinates></LineString>'));
  const timed = convertCSVToKML(readCSV('lat,lon,time\n1,2,2000-01-01T00:00:00Z\n3,4,2000-01-01T00:01:00Z\n'), { mode: 'line' });
  assertWellFormed(timed.kml);
  assert.ok(timed.kml.includes('<gx:coord>2 1</gx:coord>') && timed.kml.includes('<when>2000-01-01T00:01:00Z</when>'));
  assert.equal(timed.report.lineKind, 'gx:Track');
  assert.throws(() => convertCSVToKML(readCSV('lat,lon\n1,2\n'), { mode: 'line' }), /at least two/);
});

test('CSV to KML: bad rows are listed by row number with a reason', () => {
  const { report } = convertCSVToKML(readCSV('name,lat,lon\nok,1,2\nempty,,3\ndms,52°31′N,13\nrange,95,3\nok2,4,5\n'));
  assert.equal(report.written, 2);
  assert.deepEqual(report.skipped, [{ row: 3, reason: 'missing' }, { row: 4, reason: 'nonNumeric' }, { row: 5, reason: 'outOfRange' }]);
  assert.throws(() => convertCSVToKML(readCSV('a,b\n1,2\n'), { mapping: { lat: -1, lon: -1 } }), /latitude column/);
  assert.throws(() => convertCSVToKML(readCSV('lat,lon\n1,2\n'), { mode: 'placemarks' }), /not written by this tool/);
  const semi = convertCSVToKML(readCSV('lat;lon\n1,5;2,5\n'));
  assert.ok(semi.kml.includes('<coordinates>2.5,1.5</coordinates>'));
});

test('round trip: KML to CSV to KML keeps names, coordinates and ExtendedData', () => {
  const first = parseKmlForCsv(kmlText, { Parser: XMLParser });
  const exported = kmlToCSV(kmlText, { Parser: XMLParser });
  const table = readCSV(exported.csv);
  assert.ok(isOwnExport(table));
  const back = convertCSVToKML(table, { fileName: 'round-trip' });
  assertWellFormed(back.kml);
  assert.equal(back.report.mode, 'placemarks');
  const again = parseKmlForCsv(back.kml, { Parser: XMLParser });
  const shape = pm => ({
    name: pm.name, folder: pm.folder, ext: pm.ext, multi: pm.multi,
    parts: pm.parts.map(p => ({ geometry: p.geometry, coords: p.coords.map(c => [c.lat, c.lon, c.alt]) })),
  });
  // Apart from the description's HTML, which the CSV flattened to text, placemarks come back equal.
  assert.deepEqual(again.placemarks.map(shape), first.placemarks.map(shape));
  assert.equal(again.placemarks[0].description, 'Invented landmark\nWater is off in winter & spring');
  assert.equal(again.placemarks[0].when, '2000-01-01T14:30:00Z');
  assert.equal(again.placemarks[6].parts[0].coords[1].when, '2000-01-01T14:01:00Z');
  assert.equal(again.placemarks[2].name, '=SUM(1+1) invented formula');
  // And the second export is identical to the first, except that the original time text was already converted to UTC.
  assert.equal(kmlToCSV(back.kml, { Parser: XMLParser }).csv, exported.csv.replace(',2000-01-01T09:30:00-05:00,', ',,'));
});

test('round trip from a one-row-per-placemark CSV says shapes became points', () => {
  const exported = convert(kmlText, { mode: 'placemark' });
  const back = convertCSVToKML(readCSV(exported.csv));
  assert.ok(back.report.notes.some(n => /written as a Point/.test(n)));
  assert.equal(back.report.placemarks, 7);
});

test('KMZ output is a stored zip with doc.kml that the reader opens', async () => {
  const { kml: out } = convertCSVToKML(readCSV('name,lat,lon\nA,1,2\nB,3,4\n'), { fileName: 'zip' });
  const bytes = buildKmz(out);
  assert.equal(detectInput(bytes), 'kmz');
  const kmz = await readKmz(bytes);
  assert.equal(kmz.kmlName, 'doc.kml');
  assert.equal(kmz.kmlText, out);
  assert.equal(kmz.others.length, 0);
  if (xmllint) {
    const dir = mkdtempSync(join(tmpdir(), 'kml-csv-zip-'));
    try { const file = join(dir, 'out.kmz'); writeFileSync(file, bytes); execFileSync('python3', ['-I', '-c', 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1]); assert z.testzip() is None and z.namelist() == ["doc.kml"]', file]); }
    finally { rmSync(dir, { recursive: true, force: true }); }
  }
});

test('limits: more than 50,000 coordinates is refused', () => {
  const coords = Array.from({ length: 50001 }, (_, i) => `${(i % 360) - 180},${(i % 180) - 90}`).join(' ');
  assert.throws(() => parseKmlForCsv(kml(`<Placemark><LineString><coordinates>${coords}</coordinates></LineString></Placemark>`), { Parser: XMLParser }), /more than 50,000/);
});
