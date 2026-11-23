import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { deflateRawSync } from 'node:zlib';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { parseKML, plotShapes, formatBounds } from '../public/assets/kml-viewer-core.mjs';
import { readKmz, listEntries, chooseKml, isZip, crc32 } from '../public/assets/kmz-zip.mjs';
import { KML_VIEWER_SAMPLES } from '../public/assets/kml-viewer-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const parse = text => parseKML(text, { Parser: XMLParser });
const sample = name => readFile(new URL(`../public/samples/${name}`, import.meta.url));
const sampleText = async name => (await sample(name)).toString('utf8');
const kml = body => `<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>${body}</Document></kml>`;

// Build a ZIP in memory. entries: [{name, data, method, flags, zip64}]
function zip(entries, { eocdFields = {}, locator = false } = {}) {
  const parts = [], central = []; let offset = 0;
  const u16 = n => Buffer.from([n & 255, (n >> 8) & 255]);
  const u32 = n => { const b = Buffer.alloc(4); b.writeUInt32LE(n >>> 0); return b; };
  for (const e of entries) {
    const data = Buffer.from(e.data), method = e.method ?? 8, flags = e.flags ?? 0;
    const body = method === 8 ? deflateRawSync(data) : data, name = Buffer.from(e.name), crc = crc32(data);
    const local = Buffer.concat([u32(0x04034b50), u16(20), u16(flags), u16(method), u16(0), u16(0x21), u32(crc), u32(body.length), u32(data.length), u16(name.length), u16(0), name, body]);
    central.push(Buffer.concat([u32(0x02014b50), u16(20), u16(20), u16(flags), u16(method), u16(0), u16(0x21), u32(crc), u32(body.length), u32(data.length), u16(name.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset), name]));
    parts.push(local); offset += local.length;
  }
  const dir = Buffer.concat(central);
  const f = { disk: 0, total: entries.length, size: dir.length, offset, ...eocdFields };
  const tail = Buffer.concat([...(locator ? [u32(0x07064b50), Buffer.alloc(16)] : []), u32(0x06054b50), u16(0), u16(f.disk), u16(f.total), u16(f.total), u32(f.size), u32(f.offset), u16(0)]);
  return new Uint8Array(Buffer.concat([...parts, dir, tail]));
}

test('synthetic KML reports exactly the fixture counts', async () => {
  const m = parse(await sampleText('synthetic-kml-viewer.kml'));
  assert.deepEqual(m.counts, { documents: 1, folders: 1, placemarks: 5, points: 2, lineStrings: 1, linearRings: 0, polygons: 1, innerRings: 1, multiGeometries: 0, gxTracks: 1, gxMultiTracks: 0, withTimes: 5, positions: 2 + 3 + 5 + 5 + 5, invalidPositions: 0 });
  assert.deepEqual(m.skipped, []);
  assert.equal(m.name, 'Synthetic KML viewer example');
  const track = m.placemarks.find(p => p.name === 'Timed track');
  assert.equal(track.type, 'gx:Track'); assert.equal(track.coordinateCount, 5); assert.equal(track.timeCount, 5);
  assert.equal(track.firstTime, '2000-01-01T12:00:00Z'); assert.equal(track.lastTime, '2000-01-01T12:04:00Z');
  assert.equal(formatBounds(track.bounds), '10.02,0.03 to 10.025,0.036');
  const field = m.placemarks.find(p => p.name === 'Field with a pond');
  assert.equal(field.parts.length, 2); assert.equal(field.parts.filter(p => p.inner).length, 1); assert.equal(field.coordinateCount, 10);
  assert.deepEqual(m.tree.children[0].children[0].children.map(c => c.type), ['Placemark', 'Placemark', 'Placemark', 'Placemark', 'Placemark']);
  assert.deepEqual(m.placemarks[0].folderPath, ['Synthetic KML viewer example', 'Synthetic folder']);
});

test('coordinates are longitude first and keep altitude', () => {
  const m = parse(kml('<Placemark><name>P</name><Point><coordinates>12.5,-33.25,7</coordinates></Point></Placemark>'));
  assert.deepEqual(m.placemarks[0].parts[0].coords, [[12.5, -33.25, 7]]);
  assert.deepEqual(m.bounds, { west: 12.5, south: -33.25, east: 12.5, north: -33.25 });
});

test('MultiGeometry, MultiTrack, stand-alone LinearRing and invalid coordinates are counted', () => {
  const m = parse(kml(`<Placemark><MultiGeometry><Point><coordinates>1,2</coordinates></Point><LineString><coordinates>1,2 3,4 x,5 200,1</coordinates></LineString></MultiGeometry></Placemark>
    <Placemark><gx:MultiTrack><gx:Track><when>2020-01-01T00:00:00Z</when><gx:coord>1 2</gx:coord></gx:Track><gx:Track><when>2020-01-01T00:00:00</when><gx:coord>3 4</gx:coord></gx:Track></gx:MultiTrack></Placemark>
    <Placemark><LinearRing><coordinates>0,0 1,0 1,1 0,0</coordinates></LinearRing></Placemark><Placemark><name>Empty</name></Placemark>`));
  assert.equal(m.counts.multiGeometries, 1); assert.equal(m.counts.gxMultiTracks, 1); assert.equal(m.counts.gxTracks, 2); assert.equal(m.counts.linearRings, 1);
  assert.equal(m.counts.invalidPositions, 2);
  assert.equal(m.counts.withTimes, 1, 'a time without a zone is not guessed');
  assert.equal(m.placemarks[0].type, 'MultiGeometry (Point, LineString)');
  assert.equal(m.placemarks[3].type, 'No drawable geometry');
  assert.deepEqual(m.skipped.map(s => [s.label, s.count]), [['Placemarks with nothing to draw', 1], ['Invalid coordinates', 2]]);
});

test('skipped elements are named with counts, wherever they appear', () => {
  const m = parse(kml(`<Style id="a"><IconStyle><Icon><href>x.png</href></Icon></IconStyle></Style><StyleMap id="b"/>
    <NetworkLink><Link><href>http://example.invalid/x.kml</href></Link></NetworkLink><GroundOverlay><Icon><href>o.png</href></Icon></GroundOverlay><PhotoOverlay/><ScreenOverlay/>
    <Folder><Placemark><Model/><Region/><TimeStamp><when>2020-01-01T00:00:00Z</when></TimeStamp><Point><coordinates>1,2</coordinates></Point><ExtendedData/></Placemark></Folder><gx:Tour/>`));
  const got = Object.fromEntries(m.skipped.map(s => [s.label, s.count]));
  assert.deepEqual(got, { NetworkLink: 1, GroundOverlay: 1, PhotoOverlay: 1, ScreenOverlay: 1, 'Model (3D)': 1, Region: 1, Style: 1, StyleMap: 1, Icon: 2, TimeStamp: 1, ExtendedData: 1, 'gx:Tour': 1 });
  assert.equal(m.counts.placemarks, 1);
});

test('descriptions stay text: markup is returned as a string for textContent', () => {
  const m = parse(kml('<Placemark><name>&lt;img src=x onerror=alert(1)&gt;</name><description><![CDATA[<script>alert(1)</script><b>hi</b>]]></description><Point><coordinates>1,2</coordinates></Point></Placemark>'));
  assert.equal(m.placemarks[0].description, '<script>alert(1)</script><b>hi</b>');
  assert.equal(m.placemarks[0].name, '<img src=x onerror=alert(1)>');
});

test('non-KML, malformed, DOCTYPE and oversize input are refused', () => {
  assert.throws(() => parse('<gpx/>'), /root element is not <kml>/);
  assert.throws(() => parse('<kml><Document></kml>'), /not well-formed/);
  assert.throws(() => parse('<!DOCTYPE kml [<!ENTITY a "b">]><kml/>'), /DTD/);
  assert.throws(() => parse(' '.repeat(10 * 1024 * 1024 + 1)), /10 MiB/);
  const many = Array.from({ length: 50001 }, (_, i) => `${i % 180},1`).join(' ');
  assert.throws(() => parse(kml(`<Placemark><LineString><coordinates>${many}</coordinates></LineString></Placemark>`)), /50,000/);
});

test('plot shapes fit the box, keep north up and separate rings', async () => {
  const m = parse(await sampleText('synthetic-kml-viewer.kml'));
  const shapes = plotShapes(m);
  assert.equal(shapes.length, 2 + 1 + 2 + 1);
  assert.deepEqual(shapes.map(s => s.kind).sort(), ['line', 'line', 'point', 'point', 'polygon', 'polygon']);
  assert.equal(shapes.filter(s => s.inner).length, 1);
  for (const s of shapes) for (const [x, y] of s.points) { assert.ok(x >= 0 && x <= 800 && y >= 0 && y <= 360); }
  const start = shapes.find(s => s.id === 0).points[0], field = shapes.find(s => s.id === 3).points[0];
  assert.ok(field[1] < start[1], 'higher latitude is higher on the plot');
  assert.deepEqual(plotShapes(parse(kml(''))), []);
  const single = plotShapes(parse(kml('<Placemark><Point><coordinates>5,5</coordinates></Point></Placemark>')));
  assert.equal(single.length, 1); assert.ok(Number.isFinite(single[0].points[0][0]));
});

test('demo samples match the committed files', async () => {
  assert.equal(KML_VIEWER_SAMPLES.kml.text, await sampleText('synthetic-kml-viewer.kml'));
  assert.equal(Buffer.from(KML_VIEWER_SAMPLES.kmz.base64, 'base64').compare(await sample('synthetic-kml-viewer.kmz')), 0);
  assert.equal(Buffer.from(KML_VIEWER_SAMPLES.kmzStored.base64, 'base64').compare(await sample('synthetic-kml-viewer-stored.kmz')), 0);
});

test('the committed deflate and stored KMZs open and match the KML', async () => {
  const text = await sampleText('synthetic-kml-viewer.kml');
  for (const [name, method] of [['synthetic-kml-viewer.kmz', 8], ['synthetic-kml-viewer-stored.kmz', 0]]) {
    const bytes = new Uint8Array(await sample(name));
    assert.ok(isZip(bytes));
    assert.deepEqual(listEntries(bytes).map(e => e.method), [method, method]);
    const kmz = await readKmz(bytes);
    assert.equal(kmz.kmlName, 'doc.kml'); assert.equal(kmz.kmlText, text);
    assert.deepEqual(kmz.others.map(o => o.name), ['files/legend.png']); assert.ok(kmz.others[0].size > 0);
    assert.deepEqual(parse(kmz.kmlText).counts, parse(text).counts);
  }
});

test('KMZ entry choice: doc.kml first, otherwise the first root .kml; nested and non-kml ignored', async () => {
  const k = kml('<Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>');
  const pick = async names => (await readKmz(zip(names.map(name => ({ name, data: name === 'doc.kml' ? k : `<!-- ${name} -->` + k }))))).kmlName;
  assert.equal(await pick(['a.kml', 'doc.kml']), 'doc.kml');
  assert.equal(await pick(['sub/inner.kml', 'z.KML', 'b.kml']), 'z.KML');
  assert.equal(await pick(['b.kml', 'a.kml']), 'b.kml');
  await assert.rejects(() => readKmz(zip([{ name: 'sub/doc.kml', data: k }, { name: 'x.png', data: 'png' }])), /no doc\.kml/);
  assert.equal(chooseKml([{ name: 'files/', directory: true }]), null);
});

test('stored and deflate entries mixed in one KMZ both read', async () => {
  const k = kml('<Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>');
  const kmz = await readKmz(zip([{ name: 'doc.kml', data: k, method: 8 }, { name: 'a.txt', data: 'abc', method: 0 }]));
  assert.equal(kmz.kmlText, k); assert.deepEqual(kmz.others, [{ name: 'a.txt', size: 3 }]);
});

test('encrypted, ZIP64, split, unsupported-method, damaged and bomb zips give clear errors', async () => {
  const k = kml('<Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>');
  await assert.rejects(() => readKmz(zip([{ name: 'doc.kml', data: k, flags: 1 }])), /encrypted/);
  await assert.rejects(() => readKmz(zip([{ name: 'doc.kml', data: k, method: 12 }])), /unsupported compression method \(12\)/);
  await assert.rejects(() => readKmz(zip([{ name: 'doc.kml', data: k }], { eocdFields: { total: 0xFFFF } })), /ZIP64/);
  await assert.rejects(() => readKmz(zip([{ name: 'doc.kml', data: k }], { eocdFields: { offset: 0xFFFFFFFF } })), /ZIP64/);
  await assert.rejects(() => readKmz(zip([{ name: 'doc.kml', data: k }], { locator: true })), /ZIP64/);
  await assert.rejects(() => readKmz(zip([{ name: 'doc.kml', data: k }], { eocdFields: { disk: 1 } })), /split/);
  await assert.rejects(() => readKmz(new Uint8Array([0x50, 0x4b, 3, 4, 1, 2, 3])), /directory could not be found/);
  const good = zip([{ name: 'doc.kml', data: k, method: 0 }]);
  const flipped = good.slice(); flipped[good.indexOf(0x3c)] ^= 1;
  await assert.rejects(() => readKmz(flipped), /checksum/);
  await assert.rejects(() => readKmz(good.slice(0, good.length - 40)), /directory|cut short|damaged/);
  const huge = zip([{ name: 'doc.kml', data: Buffer.alloc(10 * 1024 * 1024 + 1, 32) }]);
  await assert.rejects(() => readKmz(huge), /larger than 10 MiB/);
});

test('a deflate stream that lies about its size is refused', async () => {
  const bytes = zip([{ name: 'doc.kml', data: kml('') }]);
  const view = new DataView(bytes.buffer, bytes.byteOffset);
  const eocd = bytes.length - 22, dir = view.getUint32(eocd + 16, true);
  view.setUint32(dir + 24, 5, true);
  await assert.rejects(() => readKmz(bytes), /wrong size/);
});
