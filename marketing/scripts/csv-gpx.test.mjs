import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { parseGPX, toCSV } from '../public/assets/gpx-core.mjs';
import { readCSV, convertCSV, csvToGPX, detectDelimiter, detectColumns, parseCSV, parseTime, defaultMode, gpxToCSV, looksLikeGPX, safeBase, MAX_ROWS } from '../public/assets/csv-gpx-core.mjs';
import { CSV_GPX_SAMPLES } from '../public/assets/csv-gpx-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const XSD = fileURLToPath(new URL('./schemas/gpx-1.1.xsd', import.meta.url));
const csvText = await readFile(new URL('../public/samples/synthetic-format-trip.csv', import.meta.url), 'utf8');
const gpxText = await readFile(new URL('../public/samples/synthetic-format-trip.gpx', import.meta.url), 'utf8');

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function assertValid(xml, label) {
  if (!xmllint) return;
  const dir = mkdtempSync(join(tmpdir(), 'csv-gpx-'));
  try { const file = join(dir, 'out.gpx'); writeFileSync(file, xml); execFileSync('xmllint', ['--noout', '--nonet', '--schema', XSD, file], { stdio: 'pipe' }); }
  catch (e) { assert.fail(`${label} is not valid GPX 1.1: ${e.stderr?.toString() || e.message}`); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}
const run = (text, options) => { const r = csvToGPX(text, options); assertValid(r.gpx, 'output'); return r; };
const reparse = xml => parseGPX(xml, XMLParser);

test('the page sample matches the sample files', () => {
  assert.equal(CSV_GPX_SAMPLES.csv.text, csvText);
  assert.equal(CSV_GPX_SAMPLES.gpx.text, gpxText);
});

test('delimiters: comma, semicolon and tab, with quotes respected', () => {
  assert.equal(detectDelimiter('lat,lon\n1,2\n3,4\n'), ',');
  assert.equal(detectDelimiter('lat;lon\n1,5;2,5\n3,5;4,5\n'), ';');
  assert.equal(detectDelimiter('lat\tlon\n1\t2\n'), '\t');
  assert.equal(detectDelimiter('name,lat,lon\n"a;b",1,2\n"c;d",3,4\n'), ',');
  assert.deepEqual(parseCSV('a,"b ""q"", c",d\r\n"x\ny",2,3\r\n\r\n'), [['a', 'b "q", c', 'd'], ['x\ny', '2', '3']]);
  assert.deepEqual(parseCSV('﻿a,b'), [['a', 'b']]);
});

test('header aliases are matched case-insensitively and in the documented order', () => {
  const cases = [
    [['Lat', 'Lng', 'Timestamp', 'Altitude', 'Title', 'Segment'], { lat: 0, lon: 1, time: 2, ele: 3, name: 4, group: 5 }],
    [['y', 'x', 'datetime', 'elevation_m', 'label', 'track'], { lat: 0, lon: 1, time: 2, ele: 3, name: 4, group: 5 }],
    [[' LATITUDE ', 'Long', 'time_utc', 'ele', 'name', 'group'], { lat: 0, lon: 1, time: 2, ele: 3, name: 4, group: 5 }],
    [['latitude', 'longitude'], { lat: 0, lon: 1, time: -1, ele: -1, name: -1, group: -1 }],
  ];
  for (const [headers, expected] of cases) assert.deepEqual({ ...detectColumns(headers), kind: undefined }, { ...expected, kind: undefined });
  const t = readCSV('id,lon,lat\n1,10,0\n');
  assert.deepEqual([t.hasHeader, t.mapping.lat, t.mapping.lon], [true, 2, 1]);
});

test('a file without a header is read as latitude, longitude', () => {
  const r = run('52.5,13.4\n52.6,13.5\n');
  assert.equal(r.table.hasHeader, false);
  assert.equal(r.report.written, 2);
  assert.match(r.gpx, /<wpt lat="52.5" lon="13.4">/);
});

test('rows that cannot be used are skipped with a reason and a row number', () => {
  const text = 'lat,lon\n1,2\n,3\nabc,4\n95,4\n5,181\n6,7\n';
  const r = run(text);
  assert.equal(r.report.rowsRead, 6);
  assert.equal(r.report.written, 2);
  assert.deepEqual(r.report.skipped, [{ row: 3, reason: 'missing' }, { row: 4, reason: 'nonNumeric' }, { row: 5, reason: 'outOfRange' }, { row: 6, reason: 'outOfRange' }]);
  assert.deepEqual(r.report.skippedByReason, { missing: 1, nonNumeric: 1, outOfRange: 2 });
});

test('semicolon files may use decimal commas; comma files may not', () => {
  const r = run('lat;lon\n52,5;13,25\n');
  assert.match(r.gpx, /<wpt lat="52.5" lon="13.25">/);
  const c = run('lat,lon\n"52,5",13\n');
  assert.equal(c.report.written, 0);
  assert.equal(c.report.skippedByReason.nonNumeric, 1);
});

test('times: UTC, offsets and zone-less values; unparseable times keep the point', () => {
  assert.equal(parseTime('2000-01-01T12:00:00Z').ms, Date.UTC(2000, 0, 1, 12));
  assert.equal(parseTime('2000-01-01T12:00:00+02:00').ms, Date.UTC(2000, 0, 1, 10));
  assert.equal(parseTime('2000-01-01T12:00:00-0530').ms, Date.UTC(2000, 0, 1, 17, 30));
  assert.equal(parseTime('2000-01-01 12:00:00.5').ms, Date.UTC(2000, 0, 1, 12, 0, 0, 500));
  assert.equal(parseTime('2000-01-01T12:00:00').assumedUtc, true);
  assert.equal(parseTime('2000-01-01T12:00:00Z').assumedUtc, false);
  for (const bad of ['2000-02-30T00:00:00Z', 'yesterday', '12:00', '2000-01-01', '1/2/2000 10:00']) assert.equal(parseTime(bad), null, bad);
  const r = run('lat,lon,time\n0,10,2000-01-01T12:00:00Z\n0,11,2000-01-01T14:00:00+02:00\n0,12,2000-01-01T13:00:00\n0,13,soon\n0,14,\n');
  assert.equal(r.report.written, 5);
  assert.deepEqual([r.report.assumedUtc, r.report.badTime, r.report.noTime], [1, 1, 1]);
  assert.equal(r.report.firstTime, '2000-01-01T12:00:00Z');
  assert.equal(r.report.lastTime, '2000-01-01T13:00:00Z');
  const model = reparse(r.gpx);
  assert.equal(model.points.length, 5);
  assert.deepEqual(model.points.map(p => p.rawTime), ['2000-01-01T12:00:00Z', '2000-01-01T12:00:00Z', '2000-01-01T13:00:00Z', '', '']);
});

test('track mode writes one segment per run of the group column; waypoint mode writes wpt with names', () => {
  const text = 'lat,lon,time,group,name\n0,10,2000-01-01T12:00:00Z,a,One\n0,11,2000-01-01T12:01:00Z,a,Two\n0,12,2000-01-01T12:02:00Z,b,Three\n0,13,2000-01-01T12:03:00Z,a,Four\n';
  assert.equal(defaultMode(readCSV(text).mapping), 'track');
  const t = run(text);
  assert.deepEqual([t.report.tracks, t.report.segments, t.report.waypoints], [1, 3, 0]);
  const model = reparse(t.gpx);
  assert.deepEqual(model.groups.map(g => g.points.length), [2, 1, 1]);
  assert.deepEqual(model.points.map(p => p.name), ['One', 'Two', 'Three', 'Four']);
  const noGroup = run('lat,lon,time\n0,10,2000-01-01T12:00:00Z\n1,10,2000-01-01T12:01:00Z\n');
  assert.equal(noGroup.report.segments, 1);

  const plain = 'lat,lon,name\n1,2,Café & <Bar> "x"\n3,4,\n';
  assert.equal(defaultMode(readCSV(plain).mapping), 'waypoints');
  const w = run(plain);
  assert.deepEqual([w.report.tracks, w.report.waypoints], [0, 2]);
  assert.match(w.gpx, /<name>Café &amp; &lt;Bar&gt; &quot;x&quot;<\/name>/);
  assert.match(w.gpx, /<name>Point 2<\/name>/);
  assert.equal(reparse(w.gpx).waypoints, 2);
  const forced = run(plain, { mode: 'track' });
  assert.deepEqual([forced.report.tracks, forced.report.segments, forced.report.waypoints], [1, 1, 0]);
  const asWpt = run(csvText, { mode: 'waypoints' });
  assert.equal(asWpt.report.waypoints, 5);
});

test('control characters in names are dropped and elevation is validated', () => {
  const r = run('lat,lon,ele,name\n1,2,10.5,"a\u0001b"\n3,4,high,ok\n5,6,,\n');
  assert.match(r.gpx, /<name>ab<\/name>/);
  assert.match(r.gpx, /<ele>10.5<\/ele>/);
  assert.equal(r.report.badElevation, 1);
  assert.equal(r.report.written, 3);
});

test('longitude 180 is written as -180 so the output stays schema-valid', () => {
  const r = run('lat,lon\n0,180\n90,-180\n');
  assert.match(r.gpx, /lon="-180"/);
  assert.equal(r.report.written, 2);
});

test('the synthetic example gives 5 points, 1 track and 2 segments that validate', () => {
  const r = run(csvText, { fileName: 'synthetic-format-trip' });
  assert.equal(r.table.mapping.kind, 0);
  assert.equal(r.report.mode, 'kinds');
  assert.deepEqual([r.report.rowsRead, r.report.written, r.report.skipped.length, r.report.tracks, r.report.segments, r.report.waypoints], [5, 5, 0, 1, 2, 0]);
  assert.deepEqual([r.report.firstTime, r.report.lastTime], ['2000-01-01T12:00:00Z', '2000-01-01T12:14:00Z']);
  assert.match(r.gpx, /<trkpt lat="0" lon="10"><time>2000-01-01T12:00:00Z<\/time><\/trkpt>/);
  assert.ok(r.report.plot.length === 2);
  // Same conversion with the track mode chosen by hand.
  const t = run(csvText, { mode: 'track' });
  assert.equal(t.report.segments, 2);
  // And the same points as the source GPX.
  const a = reparse(r.gpx), b = reparse(gpxText);
  assert.deepEqual(a.points.map(p => [p.lat, p.lon, p.time]), b.points.map(p => [p.lat, p.lon, p.time]));
});

test('round trip: GPX viewer CSV export -> this tool keeps points, order, segments and kinds', () => {
  const original = parseGPX(`<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="t">
    <wpt lat="1" lon="2"><ele>5</ele><time>2000-01-01T00:00:00Z</time><name>Cafe &amp; bar</name></wpt>
    <rte><name>R</name><rtept lat="3" lon="4"/><rtept lat="3.5" lon="4.5"/></rte>
    <trk><trkseg><trkpt lat="0" lon="10"><ele>1.5</ele><time>2000-01-01T12:00:00Z</time></trkpt><trkpt lat="0.1" lon="10.1"><time>2000-01-01T12:01:00+01:00</time></trkpt></trkseg>
    <trkseg><trkpt lat="0.2" lon="10.2"><time>2000-01-01T12:13:00Z</time></trkpt></trkseg></trk>
    <trk><trkseg><trkpt lat="0.3" lon="10.3"><time>2000-01-01T12:20:00Z</time></trkpt></trkseg></trk></gpx>`, XMLParser);
  const csv = toCSV(original);
  const r = run(csv);
  assert.equal(r.report.mode, 'kinds');
  assert.equal(r.report.skipped.length, 0);
  const back = reparse(r.gpx);
  const key = m => ['track', 'route', 'waypoint'].flatMap(kind => m.groups.filter(g => g.kind === kind).map(g => g.points.map(p => [p.lat, p.lon, p.time, p.elevation, p.name]))).flat();
  assert.deepEqual(key(back), key(original));
  assert.deepEqual(back.groups.filter(g => g.kind === 'track').map(g => g.points.length), [2, 1, 1]);
  assert.equal(back.groups.filter(g => g.kind === 'route').length, 1);
  assert.equal(back.waypoints, 1);
  // The same CSV is also valid input for plain track mode.
  assert.equal(run(csv, { mode: 'track' }).report.written, 7);
});

test('GPX to CSV uses the viewer layout and guards spreadsheet formulas', () => {
  const text = `<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="t"><wpt lat="1" lon="2"><name>=HYPERLINK("x")</name></wpt><wpt lat="3" lon="4"><name>@SUM(A1)</name></wpt></gpx>`;
  const { csv, model } = gpxToCSV(text, XMLParser);
  assert.equal(csv, toCSV(model));
  const lines = csv.split('\r\n');
  assert.equal(lines[0], 'kind,group,point,latitude,longitude,time_utc,elevation_m,name,original_time');
  assert.ok(lines[1].includes(`"'=HYPERLINK(""x"")"`));
  assert.ok(lines[2].includes(`"'@SUM(A1)"`));
  const sample = gpxToCSV(gpxText, XMLParser).csv;
  assert.equal(sample, csvText);
  // A name that carries the guard apostrophe imports without it.
  const back = run(csv);
  assert.deepEqual(reparse(back.gpx).points.map(p => p.name), ['=HYPERLINK("x")', '@SUM(A1)']);
});

test('limits and errors', () => {
  assert.throws(() => readCSV('lat,lon\n' + '1,2\n'.repeat(MAX_ROWS + 1)), /50,000 rows/);
  assert.equal(readCSV('lat,lon\n' + '1,2\n'.repeat(MAX_ROWS)).rows.length, MAX_ROWS);
  assert.throws(() => readCSV('x'.repeat(10 * 1024 * 1024 + 1)), /10 MiB/);
  assert.throws(() => readCSV('   \n'), /No rows/);
  assert.throws(() => csvToGPX('name,city\na,b\n'), /latitude column/);
  assert.throws(() => convertCSV(readCSV('lat,lon\n1,2\n'), { mapping: { lat: 0, lon: 0, time: -1, ele: -1, name: -1, group: -1, kind: -1 } }), /different columns/);
  const none = run('lat,lon\nx,y\n');
  assert.equal(none.report.written, 0);
  assert.equal(looksLikeGPX(gpxText), true);
  assert.equal(looksLikeGPX(csvText), false);
  assert.equal(safeBase('My trip (1).csv'), 'My trip -1');
});

test('a 50,000-row file converts and validates', () => {
  let text = 'lat,lon,time\n';
  for (let i = 0; i < MAX_ROWS; i++) text += `${(i % 1000) / 1000},${10 + i / 100000},2000-01-01T00:00:00Z\n`;
  const r = run(text);
  assert.equal(r.report.written, MAX_ROWS);
});
