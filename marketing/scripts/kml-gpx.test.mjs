import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { convert, detectFormat, baseName, MAX_POINTS } from '../public/assets/kml-gpx-core.mjs';
import { KML_GPX_SAMPLES } from '../public/assets/kml-gpx-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const run = (text, name = 'file.xml', keepTimestamps = true) => convert(text, name, { Parser: XMLParser, keepTimestamps });
const sample = name => readFile(new URL(`../public/samples/${name}`, import.meta.url), 'utf8');
const XSD = fileURLToPath(new URL('./schemas/gpx-1.1.xsd', import.meta.url));
const kml = (body, gx = true) => `<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"${gx ? ' xmlns:gx="http://www.google.com/kml/ext/2.2"' : ''}><Document>${body}</Document></kml>`;
const gpx = body => `<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="t">${body}</gpx>`;

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function lint(xml, ext, schema) {
  if (!xmllint) return;
  const dir = mkdtempSync(join(tmpdir(), 'kml-gpx-'));
  try {
    const file = join(dir, `out.${ext}`); writeFileSync(file, xml);
    execFileSync('xmllint', ['--noout', '--nonet', ...(schema ? ['--schema', schema] : []), file], { stdio: 'pipe' });
  } catch (error) { assert.fail(`${ext} output invalid: ${error.stderr?.toString() || error.message}`); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}

test('synthetic KML converts to exactly the committed GPX, which is valid GPX 1.1', async () => {
  const r = run(await sample('synthetic-kml-to-gpx.kml'), 'synthetic-kml-to-gpx.kml');
  assert.equal(r.text, await sample('synthetic-kml-to-gpx.expected.gpx'));
  assert.equal(r.filename, 'synthetic-kml-to-gpx.gpx');
  assert.deepEqual(r.input, { placemarks: 5, points: 2, lineStrings: 1, gxTracks: 1, positions: 10, withTime: 5, withElevation: 9 });
  assert.deepEqual(r.output, { waypoints: 2, tracks: 2, segments: 2, trackPoints: 8, withTime: 5, withElevation: 9 });
  assert.deepEqual(r.skipped.map(s => [s.key, s.count]), [['Polygon', 1]]);
  assert.equal(r.firstTime, '2000-01-01T12:00:00Z');
  assert.equal(r.lastTime, '2000-01-01T12:04:00Z');
  lint(r.text, 'gpx', XSD);
  assert.equal(run(await sample('synthetic-kml-to-gpx.kml'), 'synthetic-kml-to-gpx.kml').text, r.text, 'deterministic');
});

test('committed GPX converts to exactly the committed KML, with times as gx:Track', async () => {
  const r = run(await sample('synthetic-kml-to-gpx.expected.gpx'), 'synthetic-kml-to-gpx.expected.gpx');
  assert.equal(r.text, await sample('synthetic-kml-to-gpx.expected.kml'));
  assert.equal(r.output.gxTracks, 1);
  assert.equal(r.output.withTime, 5);
  lint(r.text, 'kml');
});

test('demo samples match the committed files', async () => {
  assert.equal(KML_GPX_SAMPLES.kml.text, await sample('synthetic-kml-to-gpx.kml'));
  assert.equal(KML_GPX_SAMPLES.gpx.text, await sample('synthetic-kml-to-gpx.expected.gpx'));
});

test('latitude and longitude are not swapped (asymmetric coordinate)', () => {
  const r = run(kml('<Placemark><name>P</name><Point><coordinates>12.5,-33.25,7</coordinates></Point></Placemark>'));
  assert.match(r.text, /<wpt lat="-33.25" lon="12.5"><ele>7<\/ele><name>P<\/name><\/wpt>/);
  const back = run(r.text, 'p.gpx');
  assert.match(back.text, /<coordinates>12.5,-33.25,7<\/coordinates>/);
  const gx = run(kml('<Placemark><gx:Track><when>2000-01-01T00:00:00Z</when><gx:coord>12.5 -33.25 7</gx:coord></gx:Track></Placemark>'));
  assert.match(gx.text, /<trkpt lat="-33.25" lon="12.5">/);
});

test('invalid coordinates are rejected and counted', () => {
  const r = run(kml('<Placemark><LineString><coordinates>10,0 10,91 181,0 x,y 11,1</coordinates></LineString></Placemark>'));
  assert.equal(r.output.trackPoints, 2);
  assert.equal(r.skipped.find(s => s.key === 'invalidCoordinates').count, 3);
  const g = run(gpx('<trk><trkseg><trkpt lat="0" lon="10"/><trkpt lat="95" lon="10"/><trkpt lat="1" lon="11"/></trkseg></trk>'));
  assert.equal(g.output.positions, 2);
  assert.equal(g.skipped.find(s => s.key === 'invalidCoordinates').count, 1);
});

test('longitude 180 is written as -180 so GPX 1.1 stays valid', () => {
  const r = run(kml('<Placemark><Point><coordinates>180,10</coordinates></Point></Placemark>'));
  assert.match(r.text, /lon="-180"/);
  lint(r.text, 'gpx', XSD);
});

test('MultiGeometry and gx:MultiTrack give several segments; polygons and other items are reported, not converted', () => {
  const r = run(kml(`<Style id="s"/><Folder><Placemark><name>M</name><ExtendedData><Data name="a"><value>1</value></Data></ExtendedData><MultiGeometry>
    <LineString><coordinates>0,0 1,1</coordinates></LineString><LineString><coordinates>2,2 3,3</coordinates></LineString>
    <Polygon><outerBoundaryIs><LinearRing><coordinates>0,0 1,0 1,1 0,0</coordinates></LinearRing></outerBoundaryIs></Polygon></MultiGeometry></Placemark>
    <Placemark><gx:MultiTrack><gx:Track><when>2000-01-01T00:00:00Z</when><when>2000-01-01T00:01:00+01:00</when><gx:coord>0 0 1</gx:coord><gx:coord>1 1 2</gx:coord></gx:Track><gx:Track><when>2000-01-02T00:00:00Z</when><gx:coord>2 2</gx:coord></gx:Track></gx:MultiTrack></Placemark></Folder>
    <NetworkLink><Link><href>http://example.com/x.kml</href></Link></NetworkLink>`));
  assert.equal(r.output.tracks, 2);
  assert.equal(r.output.segments, 4);
  assert.equal(r.output.trackPoints, 2 + 2 + 2 + 1);
  assert.doesNotMatch(r.text, /Polygon|example\.com/);
  const keys = Object.fromEntries(r.skipped.map(s => [s.key, s.count]));
  assert.deepEqual(keys, { Polygon: 1, NetworkLink: 1, Style: 1, ExtendedData: 1, Folder: 1 });
  assert.match(r.text, /<time>1999-12-31T23:01:00Z<\/time>/, 'offset times are written in UTC');
  lint(r.text, 'gpx', XSD);
});

test('untimed or unzoned gx:Track times are not guessed', () => {
  const r = run(kml('<Placemark><gx:Track><when>2000-01-01T00:00:00</when><when>2000-02-30T00:00:00Z</when><gx:coord>0 0</gx:coord><gx:coord>1 1</gx:coord></gx:Track></Placemark>'));
  assert.equal(r.output.withTime, 0);
  assert.equal(r.skipped.find(s => s.key === 'invalidTimes').count, 2);
});

test('round trip: gx:Track -> GPX -> gx:Track keeps times and altitude', async () => {
  const original = await sample('synthetic-kml-to-gpx.kml');
  const again = run(run(original).text, 'x.gpx');
  assert.match(again.text, /<gx:coord>10.020 0.030 20<\/gx:coord>/);
  assert.match(again.text, /<when>2000-01-01T12:04:00Z<\/when>/);
  const body = text => text.slice(text.indexOf('<wpt'));
  assert.equal(body(run(again.text, 'x.kml').text), body(run(original).text));
});

test('GPX to KML: timestamps option, mixed segments, routes, single-point segments, gx namespace only when used', () => {
  const body = '<wpt lat="1" lon="2"><name>W</name><time>2000-01-01T00:00:00Z</time></wpt>'
    + '<rte><name>R</name><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte>'
    + '<trk><name>T</name><trkseg><trkpt lat="1" lon="1"><time>2000-01-01T00:00:00Z</time></trkpt><trkpt lat="1" lon="2"><time>2000-01-01T00:01:00Z</time></trkpt></trkseg>'
    + '<trkseg><trkpt lat="3" lon="3"/><trkpt lat="3" lon="4"/></trkseg><trkseg><trkpt lat="5" lon="5"/></trkseg></trk>';
  const on = run(gpx(body), 'a.gpx');
  assert.equal(on.output.gxTracks, 1);
  assert.equal(on.output.lineStrings, 2);
  assert.match(on.text, /T \(segment 1\)/);
  assert.equal(on.skipped.find(s => s.key === 'shortSegments').count, 1);
  assert.match(on.text, /xmlns:gx=/);
  const off = run(gpx(body), 'a.gpx', false);
  assert.equal(off.output.gxTracks, 0);
  assert.doesNotMatch(off.text, /gx/);
  assert.match(off.notes[0], /off/);
  lint(on.text, 'kml'); lint(off.text, 'kml');
});

test('elevations are only written when a whole line has them', () => {
  const r = run(gpx('<trk><trkseg><trkpt lat="1" lon="1"><ele>5</ele></trkpt><trkpt lat="1" lon="2"/></trkseg></trk>'), 'a.gpx');
  assert.match(r.text, /<coordinates>1,1 2,1<\/coordinates>/);
  assert.equal(r.skipped.find(s => s.key === 'droppedElevations').count, 1);
});

test('GPX fields KML cannot hold are counted', () => {
  const r = run(gpx('<wpt lat="1" lon="2"><sym>Flag</sym><link href="http://x"/><extensions><a/></extensions></wpt>'), 'a.gpx');
  assert.deepEqual(r.skipped.map(s => s.count), [1, 1, 1]);
});

test('format is detected from content; KMZ, other XML and oversize input are refused', () => {
  assert.equal(detectFormat(kml('<Placemark/>'), XMLParser), 'kml');
  assert.equal(detectFormat(gpx(''), XMLParser), 'gpx');
  assert.throws(() => run('PK\u0003\u0004abc'), /KMZ/);
  assert.throws(() => run('<svg xmlns="http://www.w3.org/2000/svg"/>'), /KML or GPX/);
  assert.throws(() => run('not xml'), /well-formed/);
  assert.throws(() => run('<!DOCTYPE kml [<!ENTITY a "b">]><kml/>'), /DTD/);
  assert.throws(() => run(kml('<Placemark><Polygon/></Placemark>')), /Polygons/);
});

test('point limit and file naming', () => {
  const big = kml(`<Placemark><LineString><coordinates>${'1,1 '.repeat(MAX_POINTS + 1)}</coordinates></LineString></Placemark>`);
  assert.throws(() => run(big), /50,000/);
  assert.equal(baseName('My Trip.v2.kml'), 'My Trip.v2');
  assert.equal(baseName('C:\\x\\route.GPX'), 'route');
  assert.equal(run(kml('<Placemark><Point><coordinates>1,1</coordinates></Point></Placemark>'), 'Route one.kml').filename, 'Route one.gpx');
});

test('text is escaped', () => {
  const r = run(kml('<Placemark><name>A &amp; B &lt;x&gt;</name><description><![CDATA[<b>hi</b>]]></description><Point><coordinates>1,1</coordinates></Point></Placemark>'));
  assert.match(r.text, /<name>A &amp; B &lt;x&gt;<\/name><desc>&lt;b&gt;hi&lt;\/b&gt;<\/desc>/);
  lint(r.text, 'gpx', XSD);
});
