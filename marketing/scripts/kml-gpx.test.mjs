import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { convertFile, outputName, isoUTC, unzipKML } from '../public/assets/kml-gpx.mjs';
import { parseGPX } from '../public/assets/gpx-core.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const bytes = s => new Uint8Array(Buffer.from(s, 'utf8'));
const run = (s, opts = {}) => convertFile(typeof s === 'string' ? bytes(s) : s, { Parser: XMLParser, ...opts });
const sample = name => readFileSync(fileURLToPath(new URL(`../public/samples/${name}`, import.meta.url)), 'utf8');
const XSD = fileURLToPath(new URL('./schemas/gpx-1.1.xsd', import.meta.url));
const kml = (body, gx = true) => `<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"${gx ? ' xmlns:gx="http://www.google.com/kml/ext/2.2"' : ''}><Document>${body}</Document></kml>`;
const gpx = body => `<?xml version="1.0"?><gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">${body}</gpx>`;

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function assertValidGPX(xml, label) {
  const dir = mkdtempSync(join(tmpdir(), 'kml-gpx-'));
  try {
    const file = join(dir, 'out.gpx'); writeFileSync(file, xml);
    execFileSync('xmllint', ['--noout', '--nonet', '--schema', XSD, file], { stdio: 'pipe' });
  } catch (error) { assert.fail(`${label} is not valid GPX 1.1: ${error.stderr?.toString() || error.message}`); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}
const wellFormed = xml => new XMLParser().parseFromString(xml, 'application/xml');

test('synthetic KML converts to exactly the committed GPX, with documented counts', async () => {
  const r = await run(sample('synthetic-kml-example.kml'));
  assert.equal(r.direction, 'kml-to-gpx');
  assert.equal(r.output, sample('synthetic-kml-example-expected.gpx'));
  assert.deepEqual(r.report.input, { placemarks: 5, points: 2, lineStrings: 1, gxTracks: 1, timedPoints: 5, elevationPoints: 6 });
  assert.deepEqual(r.report.output, { waypoints: 2, tracks: 2, segments: 2, trackPoints: 8, timedPoints: 5, elevationPoints: 6 });
  assert.deepEqual(r.report.skipped.map(s => [s.item, s.count]), [['Polygons', 1]]);
  assert.equal(r.report.firstTime, '2000-01-01T12:00:00.000Z');
  assert.equal(r.report.lastTime, '2000-01-01T12:04:00.000Z');
});

test('the committed GPX converts back to exactly the committed KML, with gx:Track', async () => {
  const r = await run(sample('synthetic-kml-example-expected.gpx'));
  assert.equal(r.direction, 'gpx-to-kml');
  assert.equal(r.output, sample('synthetic-kml-example-expected-back.kml'));
  assert.equal(r.usesGx, true);
  assert.deepEqual(r.report.output, { placemarks: 4, waypoints: 2, routes: 0, tracks: 2, segments: 2, timedPoints: 5, elevationPoints: 6 });
});

test('output is deterministic', async () => {
  for (const name of ['synthetic-kml-example.kml', 'synthetic-kml-example-expected.gpx']) {
    assert.equal((await run(sample(name))).output, (await run(sample(name))).output);
  }
});

test('KML lon,lat is written to GPX lat/lon the right way round', async () => {
  const r = await run(kml('<Placemark><name>A</name><Point><coordinates>123.5,45.25,7</coordinates></Point></Placemark><Placemark><LineString><coordinates>100.5,13.25 101.5,14.75</coordinates></LineString></Placemark>'));
  assert.match(r.output, /<wpt lat="45.25" lon="123.5"><ele>7<\/ele>/);
  assert.match(r.output, /<trkpt lat="13.25" lon="100.5">/);
  assert.match(r.output, /<trkpt lat="14.75" lon="101.5">/);
  const g = await run(gpx('<wpt lat="45.25" lon="123.5"/>'));
  assert.match(g.output, /<coordinates>123.5,45.25<\/coordinates>/);
  const t = await run(gpx('<trk><trkseg><trkpt lat="45.25" lon="123.5"><time>2000-01-01T00:00:00Z</time></trkpt></trkseg></trk>'));
  assert.match(t.output, /<gx:coord>123.5 45.25<\/gx:coord>/);
});

test('gx:Track times and altitudes become trkpt time and ele; a gx:MultiTrack becomes segments', async () => {
  const r = await run(kml(`<Placemark><name>Multi</name><gx:MultiTrack><gx:Track><altitudeMode>absolute</altitudeMode>
    <when>2000-01-01T12:00:00+02:00</when><when>2000-01-01T12:00:30.5Z</when><gx:coord>10 1 5.5</gx:coord><gx:coord>10.1 1.1 6</gx:coord></gx:Track>
    <gx:Track><when>2000-01-02T00:00:00Z</when><gx:coord>11 2</gx:coord></gx:Track></gx:MultiTrack></Placemark>`));
  assert.equal(r.report.output.tracks, 1); assert.equal(r.report.output.segments, 2);
  assert.match(r.output, /<trkpt lat="1" lon="10"><ele>5.5<\/ele><time>2000-01-01T10:00:00Z<\/time>/);
  assert.match(r.output, /<trkpt lat="1.1" lon="10.1"><ele>6<\/ele><time>2000-01-01T12:00:30.500Z<\/time>/);
  assert.match(r.output, /<trkpt lat="2" lon="11"><time>2000-01-02T00:00:00Z<\/time><\/trkpt>/);
  assert.equal(r.output.match(/<trkseg>/g).length, 2);
});

test('MultiGeometry lines become one track with several segments; points become waypoints', async () => {
  const r = await run(kml('<Placemark><name>Both</name><MultiGeometry><LineString><coordinates>1,2 3,4</coordinates></LineString><Point><coordinates>5,6</coordinates></Point><LineString><coordinates>7,8 9,10</coordinates></LineString></MultiGeometry></Placemark>'));
  assert.deepEqual([r.report.output.tracks, r.report.output.segments, r.report.output.waypoints], [1, 2, 1]);
  assert.match(r.output, /<wpt lat="6" lon="5"><name>Both<\/name>/);
});

test('invalid coordinates are rejected and counted, never written', async () => {
  const r = await run(kml('<Placemark><LineString><coordinates>10,95 181,0 abc,1 5 1,2,x 3,4</coordinates></LineString></Placemark><Placemark><gx:Track><when>2000-01-01T00:00:00Z</when><when>2000-01-01T00:01:00Z</when><gx:coord>0 91</gx:coord><gx:coord>1 2</gx:coord></gx:Track></Placemark>'));
  // 5 invalid line tokens (95 lat, 181 lon, abc, a single number, the "1,2,x" altitude keeps its point), plus one invalid gx:coord.
  assert.equal(r.report.rejected, 4 + 1);
  assert.equal(r.report.output.trackPoints, 3);
  assert.equal(r.report.output.elevationPoints, 0);
  assert.match(r.report.notes.join(' '), /1 altitude value was not a number/);
  assert.doesNotMatch(r.output, /lat="95"|lon="181"/);
  await assert.rejects(run(kml('<Placemark><Point><coordinates>200,0</coordinates></Point></Placemark>')), /No Point, LineString or gx:Track with valid coordinates/);
  await assert.rejects(run(kml('<Placemark><Point><coordinates>200,0</coordinates></Point></Placemark>')), /1 coordinate was invalid/);
});

test('mismatched gx:Track when/coord counts are rejected as unpaired', async () => {
  const r = await run(kml('<Placemark><gx:Track><when>2000-01-01T00:00:00Z</when><when>2000-01-01T00:01:00Z</when><gx:coord>1 2</gx:coord></gx:Track></Placemark>'));
  assert.equal(r.report.rejected, 1); assert.equal(r.report.output.trackPoints, 1);
});

test('times without a zone or with impossible dates are not written', () => {
  assert.equal(isoUTC('2000-01-01T12:00:00'), null);
  assert.equal(isoUTC('2000-02-30T12:00:00Z'), null);
  assert.equal(isoUTC('2000-01-01'), null);
  assert.equal(isoUTC('2000-01-01T12:00:00Z'), '2000-01-01T12:00:00Z');
});

test('skipped elements are listed with counts and never converted', async () => {
  const r = await run(kml(`<Style id="s"><IconStyle/></Style><StyleMap id="m"/><Folder><NetworkLink><Link><href>http://example.invalid/a.kml</href></Link></NetworkLink>
    <Placemark><name>Area</name><Polygon><outerBoundaryIs><LinearRing><coordinates>0,0 1,0 1,1 0,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
    <Placemark><name>Data</name><ExtendedData><Data name="a"><value>1</value></Data></ExtendedData><Point><coordinates>3,4</coordinates></Point></Placemark>
    <Placemark><name>Nothing</name></Placemark><GroundOverlay/>
    <Placemark><TimeSpan><begin>2000-01-01T00:00:00Z</begin></TimeSpan><LineString><coordinates>1,1 2,2</coordinates></LineString></Placemark></Folder>`));
  const found = Object.fromEntries(r.report.skipped.map(s => [s.item, s.count]));
  assert.deepEqual(found, { 'Styles and icons': 2, 'NetworkLinks': 1, 'Image overlays': 1, 'Polygons': 1, 'ExtendedData': 1, 'Placemarks without a geometry': 1, 'TimeStamp / TimeSpan on lines': 1 });
  assert.equal(r.report.output.tracks, 1); assert.equal(r.report.output.waypoints, 1);
  assert.doesNotMatch(r.output, /lat="1" lon="0"/);
});

test('altitudes in a non-absolute altitudeMode are copied but flagged', async () => {
  const r = await run(kml('<Placemark><LineString><altitudeMode>clampToGround</altitudeMode><coordinates>1,2,0 3,4,0</coordinates></LineString></Placemark>'));
  assert.equal(r.report.output.elevationPoints, 2);
  assert.match(r.report.notes[0], /2 altitude values have an altitudeMode other than absolute/);
});

test('GPX -> KML: waypoints, routes, segments, gx:Track only when every point has a time', async () => {
  const body = `<wpt lat="1" lon="2"><ele>3</ele><time>2000-01-01T00:00:00Z</time><name>W &amp; X</name><desc>d</desc><sym>Flag</sym></wpt>
    <rte><name>R</name><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte>
    <trk><name>T</name>
      <trkseg><trkpt lat="1" lon="1"><time>2000-01-01T00:00:00Z</time></trkpt><trkpt lat="1" lon="2"><time>2000-01-01T00:01:00Z</time></trkpt></trkseg>
      <trkseg><trkpt lat="3" lon="3"><time>2000-01-01T01:00:00Z</time></trkpt><trkpt lat="3" lon="4"/></trkseg>
    </trk>`;
  const on = await run(gpx(body));
  assert.match(on.output, /xmlns:gx=/);
  assert.match(on.output, /<Placemark><name>W &amp; X<\/name><description>d<\/description><TimeStamp><when>2000-01-01T00:00:00Z<\/when><\/TimeStamp><Point><altitudeMode>absolute<\/altitudeMode><coordinates>2,1,3<\/coordinates>/);
  assert.match(on.output, /<name>R<\/name><LineString><tessellate>1<\/tessellate><coordinates>1,1 2,2<\/coordinates>/);
  // The first segment is fully timed (gx:Track); the second has a point with no time (LineString).
  assert.match(on.output, /<MultiGeometry><gx:Track>.*<\/gx:Track><LineString>.*<\/LineString><\/MultiGeometry>/);
  assert.deepEqual(on.report.skipped.map(s => [s.item, s.count]), [['Timestamps', 1], ['Other GPX fields', 1]]);
  assert.equal(on.report.output.timedPoints, 3);
  wellFormed(on.output);
  const off = await run(gpx(body), { keepTimestamps: false });
  assert.doesNotMatch(off.output, /gx:/);
  assert.doesNotMatch(off.output, /<when>/);
  assert.equal(off.report.output.timedPoints, 0);
  assert.equal(off.report.skipped.find(s => s.item === 'Timestamps').count, 4);
  wellFormed(off.output);
});

test('the gx namespace is declared only when used', async () => {
  const plain = await run(gpx('<trk><trkseg><trkpt lat="1" lon="1"/><trkpt lat="2" lon="2"/></trkseg></trk>'));
  assert.doesNotMatch(plain.output, /xmlns:gx/);
  assert.match(plain.output, /^<\?xml version="1.0" encoding="UTF-8"\?>\n<kml xmlns="http:\/\/www.opengis.net\/kml\/2.2">/);
  const timed = await run(gpx('<trk><trkseg><trkpt lat="1" lon="1"><time>2000-01-01T00:00:00Z</time></trkpt></trkseg></trk>'));
  assert.match(timed.output, /xmlns:gx/);
  const multi = await run(gpx('<trk><trkseg><trkpt lat="1" lon="1"><time>2000-01-01T00:00:00Z</time></trkpt></trkseg><trkseg><trkpt lat="2" lon="2"><time>2000-01-01T00:01:00Z</time></trkpt></trkseg></trk>'));
  assert.match(multi.output, /<gx:MultiTrack><gx:interpolate>0<\/gx:interpolate><gx:Track>/);
});

test('GPX invalid coordinates are rejected and counted; a single-point segment becomes a Point', async () => {
  const r = await run(gpx('<trk><trkseg><trkpt lat="91" lon="0"/><trkpt lat="1" lon="1"/></trkseg></trk>'));
  assert.equal(r.report.rejected, 1);
  assert.match(r.output, /<Point><coordinates>1,1<\/coordinates><\/Point>/);
  assert.equal(r.report.skipped[0].item, 'Single-point segments');
  await assert.rejects(run(gpx('<wpt lat="95" lon="0"/>')), /No waypoints, routes or tracks with valid coordinates/);
});

test('round trip: GPX -> KML -> GPX keeps coordinates, times and elevations', async () => {
  const start = sample('synthetic-kml-example-expected.gpx');
  const kmlOut = await run(start);
  const back = await run(kmlOut.output);
  const a = parseGPX(start, XMLParser), b = parseGPX(back.output, XMLParser);
  const key = p => [p.lat, p.lon, p.time, p.elevation, p.kind === 'waypoint'].join('|');
  assert.deepEqual(b.points.map(key).sort(), a.points.map(key).sort());
  assert.equal(b.firstTime, a.firstTime); assert.equal(b.lastTime, a.lastTime);
  assert.deepEqual([b.segments, b.waypoints], [a.segments, a.waypoints]);
});

test('format is detected from content, not the file name or extension', async () => {
  assert.equal((await run(sample('synthetic-kml-example.kml'))).sourceFormat, 'KML');
  assert.equal((await run(sample('synthetic-kml-example-expected.gpx'))).sourceFormat, 'GPX');
  assert.equal((await run('﻿' + kml('<Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>', false))).direction, 'kml-to-gpx');
  assert.equal(outputName('Trip 2024.final.kml', 'gpx'), 'Trip 2024.final.gpx');
  assert.equal(outputName('C:\\x\\walk.GPX', 'kml'), 'walk.kml');
  assert.equal(outputName('', 'kml'), 'converted.kml');
});

test('bad inputs fail with plain messages', async () => {
  await assert.rejects(run(''), /empty/);
  await assert.rejects(run('{"type":"FeatureCollection"}'), /Choose a KML, KMZ or GPX file/);
  await assert.rejects(run('<html></html>'), /Choose a KML, KMZ or GPX file/);
  await assert.rejects(run('<kml><Document>'), /not well-formed XML/);
  await assert.rejects(run('<!DOCTYPE kml [<!ENTITY a "b">]><kml/>'), /DTD and entity/);
  await assert.rejects(run(kml('<Placemark><Polygon/></Placemark>')), /1 Polygons/);
  await assert.rejects(run(new Uint8Array(10 * 1024 * 1024 + 1)), /10 MiB/);
  const many = kml(`<Placemark><LineString><coordinates>${Array.from({ length: 50001 }, (_, i) => `1,${(i % 80) / 10}`).join(' ')}</coordinates></LineString></Placemark>`);
  await assert.rejects(run(many), /more than 50,000 positions/);
});

// A minimal zip writer for KMZ tests: one entry per file, stored or deflated.
function zip(files, method = 8) {
  const parts = [], central = []; let offset = 0;
  for (const [name, data] of files) {
    const raw = Buffer.from(data), packed = method === 8 ? deflateRawSync(raw) : raw, nameBuf = Buffer.from(name);
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(method, 8);
    local.writeUInt32LE(packed.length, 18); local.writeUInt32LE(raw.length, 22); local.writeUInt16LE(nameBuf.length, 26);
    const head = Buffer.alloc(46); head.writeUInt32LE(0x02014b50, 0); head.writeUInt16LE(20, 4); head.writeUInt16LE(20, 6); head.writeUInt16LE(method, 10);
    head.writeUInt32LE(packed.length, 20); head.writeUInt32LE(raw.length, 24); head.writeUInt16LE(nameBuf.length, 28); head.writeUInt32LE(offset, 42);
    parts.push(local, nameBuf, packed); central.push(head, nameBuf);
    offset += 30 + nameBuf.length + packed.length;
  }
  const cd = Buffer.concat(central), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10); end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return new Uint8Array(Buffer.concat([...parts, cd, end]));
}

test('KMZ: doc.kml is unzipped in the browser (deflated or stored), other files are ignored', async () => {
  const source = sample('synthetic-kml-example.kml');
  for (const method of [8, 0]) {
    const r = await run(zip([['files/icon.png', 'not an image'], ['doc.kml', source]], method));
    assert.equal(r.sourceFormat, 'KMZ');
    assert.equal(r.output, sample('synthetic-kml-example-expected.gpx'));
  }
  const nested = await run(zip([['folder/other.kml', source]]));
  assert.equal(nested.report.output.trackPoints, 8);
  await assert.rejects(run(zip([['a.png', 'x']])), /No \.kml file/);
  await assert.rejects(run(new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4])), /not a readable zip/);
  await assert.rejects(run(zip([['doc.kml', sample('synthetic-kml-example-expected.gpx')]])), /Choose a KML, KMZ or GPX file/);
  assert.equal(new TextDecoder().decode(await unzipKML(zip([['doc.kml', 'hi']]))), 'hi');
});

test('KMZ: a zip bomb larger than 10 MiB once unzipped is refused', async () => {
  const big = '<kml>' + ' '.repeat(11 * 1024 * 1024) + '</kml>';
  const archive = zip([['doc.kml', big]]);
  assert.ok(archive.length < 10 * 1024 * 1024);
  await assert.rejects(run(archive), /larger than 10 MiB/);
});

// Schema validation needs xmllint. Without it this test is reported as skipped, never as passed.
test('every GPX output validates against the GPX 1.1 XSD', { skip: !xmllint && 'xmllint is not installed' }, async () => {
  const inputs = [
    sample('synthetic-kml-example.kml'),
    kml('<Placemark><name>Multi</name><gx:MultiTrack><gx:Track><altitudeMode>absolute</altitudeMode><when>2000-01-01T12:00:00Z</when><gx:coord>10 1 5.5</gx:coord></gx:Track><gx:Track><when>2000-01-02T00:00:00Z</when><gx:coord>11 2</gx:coord></gx:Track></gx:MultiTrack></Placemark><Placemark><name>A &amp; B</name><Point><coordinates>1,2</coordinates></Point></Placemark>'),
    zip([['doc.kml', sample('synthetic-kml-example.kml')]]),
  ];
  for (const input of inputs) assertValidGPX((await run(input)).output, 'GPX output');
  const back = await run((await run(sample('synthetic-kml-example-expected.gpx'))).output);
  assertValidGPX(back.output, 'round-trip GPX');
});
