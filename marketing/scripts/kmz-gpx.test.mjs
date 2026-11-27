import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { deflateRawSync } from 'node:zlib';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { convertBytes, detectBytes, describeEntries, MAX_BYTES } from '../public/assets/kmz-gpx-core.mjs';
import { readKmz, crc32 } from '../public/assets/kmz-zip.mjs';
import { parseKML } from '../public/assets/kml-viewer-core.mjs';
import { KMZ_GPX_SAMPLES } from '../public/assets/kmz-gpx-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const opts = { Parser: XMLParser };
const sample = name => readFile(new URL(`../public/samples/${name}`, import.meta.url));
const XSD = fileURLToPath(new URL('./schemas/gpx-1.1.xsd', import.meta.url));
const bytes = s => new TextEncoder().encode(s);
const kml = body => `<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document>${body}</Document></kml>`;

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function lint(data, ext, schema) {
  if (!xmllint) return;
  const dir = mkdtempSync(join(tmpdir(), 'kmz-gpx-'));
  try {
    const file = join(dir, `out.${ext}`); writeFileSync(file, data);
    execFileSync('xmllint', ['--noout', '--nonet', ...(schema ? ['--schema', schema] : []), file], { stdio: 'pipe' });
  } catch (error) { assert.fail(`${ext} output invalid: ${error.stderr?.toString() || error.message}`); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}

function zip(entries, { eocdFields = {}, locator = false } = {}) {
  const parts = [], central = []; let offset = 0;
  const u16 = n => Buffer.from([n & 255, (n >> 8) & 255]);
  const u32 = n => { const b = Buffer.alloc(4); b.writeUInt32LE(n >>> 0); return b; };
  for (const e of entries) {
    const data = Buffer.from(e.data), method = e.method ?? 8;
    const body = method === 8 ? deflateRawSync(data) : data, name = Buffer.from(e.name), crc = crc32(data);
    const local = Buffer.concat([u32(0x04034b50), u16(20), u16(0), u16(method), u16(0), u16(0x21), u32(crc), u32(body.length), u32(data.length), u16(name.length), u16(0), name, body]);
    central.push(Buffer.concat([u32(0x02014b50), u16(20), u16(20), u16(0), u16(method), u16(0), u16(0x21), u32(crc), u32(body.length), u32(data.length), u16(name.length), u16(0), u16(0), u16(0), u16(0), u32(0), u32(offset), name]));
    parts.push(local); offset += local.length;
  }
  const dir = Buffer.concat(central);
  const f = { disk: 0, total: entries.length, size: dir.length, offset, ...eocdFields };
  const tail = Buffer.concat([...(locator ? [u32(0x07064b50), Buffer.alloc(16)] : []), u32(0x06054b50), u16(0), u16(f.disk), u16(f.total), u16(f.total), u32(f.size), u32(f.offset), u16(0)]);
  return new Uint8Array(Buffer.concat([...parts, dir, tail]));
}

test('page samples match the committed sample files', async () => {
  assert.equal(Buffer.from(KMZ_GPX_SAMPLES.kmz.base64, 'base64').compare(await sample('synthetic-kmz-to-gpx.kmz')), 0);
  assert.equal(KMZ_GPX_SAMPLES.gpx.text, (await sample('synthetic-gpx-to-kmz.gpx')).toString('utf8'));
});

test('synthetic KMZ: the report shows the counts printed on the page', async () => {
  const r = await convertBytes(new Uint8Array(await sample('synthetic-kmz-to-gpx.kmz')), 'synthetic-kmz-to-gpx.kmz', opts);
  assert.equal(r.direction, 'kmz-to-gpx');
  assert.deepEqual(r.files, { kmlUsed: 'doc.kml', otherKmls: 0, images: 1, otherFiles: 0 });
  assert.deepEqual(r.input, { placemarks: 4, points: 2, lineStrings: 1, gxTracks: 1, positions: 9, withTime: 4, withElevation: 8 });
  assert.deepEqual(r.output, { waypoints: 2, tracks: 2, segments: 2, trackPoints: 7, withTime: 4, withElevation: 8 });
  assert.deepEqual(r.skipped.map(s => [s.key, s.count]), [['images', 1]]);
  assert.equal(r.firstTime, '2000-01-01T12:00:00Z');
  assert.equal(r.lastTime, '2000-01-01T12:03:00Z');
  assert.equal(r.outputs.length, 1);
  assert.equal(r.outputs[0].filename, 'synthetic-kmz-to-gpx.gpx');
  lint(r.outputs[0].text, 'gpx', XSD);
  assert.match(r.outputs[0].text, /<wpt lat="0.002" lon="10.001"><ele>12<\/ele><name>Trailhead<\/name>/);
  assert.equal((r.outputs[0].text.match(/<time>/g) || []).length, 4);
});

test('the same KML on its own converts to the same GPX (plain KML path)', async () => {
  const fromKmz = await convertBytes(new Uint8Array(await sample('synthetic-kmz-to-gpx.kmz')), 'x.kmz', opts);
  const plain = await convertBytes(new Uint8Array(await sample('synthetic-kmz-to-gpx.kml')), 'x.kml', opts);
  assert.equal(plain.direction, 'kml-to-gpx');
  assert.equal(plain.outputs[0].text, fromKmz.outputs[0].text);
  assert.deepEqual(plain.skipped, []);
});

test('synthetic GPX: KMZ plus KML, valid, and the KMZ reopens with the same counts', async () => {
  const r = await convertBytes(new Uint8Array(await sample('synthetic-gpx-to-kmz.gpx')), 'synthetic-gpx-to-kmz.gpx', opts);
  assert.equal(r.direction, 'gpx-to-kmz');
  assert.deepEqual(r.outputs.map(o => o.filename), ['synthetic-gpx-to-kmz.kmz', 'synthetic-gpx-to-kmz.kml']);
  assert.deepEqual(r.output, { placemarks: 3, points: 2, lineStrings: 0, gxTracks: 2, positions: 7, withTime: 5, withElevation: 6 });
  assert.match(r.outputs[1].text, /<gx:MultiTrack>/);
  lint(r.outputs[1].text, 'kml');
  const kmz = await readKmz(r.outputs[0].bytes);
  assert.equal(kmz.kmlName, 'doc.kml');
  assert.deepEqual(kmz.others, []);
  assert.equal(kmz.kmlText, r.outputs[1].text);
  const counts = parseKML(kmz.kmlText, opts).counts;
  assert.equal(counts.placemarks, 3); assert.equal(counts.points, 2); assert.equal(counts.gxTracks, 2); assert.equal(counts.gxMultiTracks, 1);
  assert.equal(counts.lineStrings, 0); assert.equal(counts.withTimes, 5);
  // KMZ -> GPX again keeps the segments and times.
  const back = await convertBytes(r.outputs[0].bytes, 'back.kmz', opts);
  assert.deepEqual(back.output, { waypoints: 2, tracks: 1, segments: 2, trackPoints: 5, withTime: 5, withElevation: 6 });
  lint(back.outputs[0].text, 'gpx', XSD);
});

test('without timestamps, GPX segments become one LineString each', async () => {
  const r = await convertBytes(new Uint8Array(await sample('synthetic-gpx-to-kmz.gpx')), 'a.gpx', { ...opts, keepTimestamps: false });
  assert.equal(r.output.lineStrings, 2); assert.equal(r.output.gxTracks, 0); assert.equal(r.output.withTime, 0);
  const counts = parseKML((await readKmz(r.outputs[0].bytes)).kmlText, opts).counts;
  assert.equal(counts.lineStrings, 2); assert.equal(counts.multiGeometries, 1);
});

test('direction comes from content, not the file name', async () => {
  assert.equal(detectBytes(new Uint8Array(await sample('synthetic-kmz-to-gpx.kmz')), XMLParser), 'kmz');
  assert.equal(detectBytes(bytes(KMZ_GPX_SAMPLES.gpx.text), XMLParser), 'gpx');
  assert.equal((await convertBytes(new Uint8Array(await sample('synthetic-kmz-to-gpx.kmz')), 'renamed.txt', opts)).direction, 'kmz-to-gpx');
  assert.equal((await convertBytes(bytes(KMZ_GPX_SAMPLES.gpx.text), 'looks-like.kmz.kml', opts)).direction, 'gpx-to-kmz');
  await assert.rejects(() => convertBytes(bytes('<geojson/>'), 'a.kml', opts), /KML or GPX/);
});

test('extra KMLs, images and other files are counted; doc.kml wins; skipped KML elements are reported', async () => {
  const k = kml('<Placemark><name>A</name><Point><coordinates>1,2</coordinates></Point></Placemark><Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>0,0 1,0 1,1 0,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark><NetworkLink><Link><href>http://example.invalid/x.kml</href></Link></NetworkLink><GroundOverlay><name>o</name></GroundOverlay>');
  const bytesIn = zip([{ name: 'other.kml', data: kml('') }, { name: 'doc.kml', data: k }, { name: 'files/a.png', data: 'x' }, { name: 'files/b.JPG', data: 'x' }, { name: 'notes.txt', data: 'x', method: 0 }, { name: 'sub/c.kml', data: kml('') }]);
  const r = await convertBytes(bytesIn, 'multi.kmz', opts);
  assert.deepEqual(r.files, { kmlUsed: 'doc.kml', otherKmls: 2, images: 2, otherFiles: 1 });
  assert.deepEqual(r.skipped.map(s => [s.key, s.count]), [['otherKmls', 2], ['images', 2], ['otherFiles', 1], ['Polygon', 1], ['NetworkLink', 1], ['GroundOverlay', 1]]);
  assert.deepEqual(r.output, { waypoints: 1, tracks: 0, segments: 0, trackPoints: 0, withTime: 0, withElevation: 0 });
  assert.deepEqual(describeEntries('doc.kml', [{ name: 'a.kml' }, { name: 'i.png' }, { name: 'x' }]), { kmlUsed: 'doc.kml', otherKmls: 1, images: 1, otherFiles: 1 });
});

test('without doc.kml the first top-level KML is used; with none there is a clear error', async () => {
  const r = await convertBytes(zip([{ name: 'map.kml', data: kml('<Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>') }]), 'm.kmz', opts);
  assert.equal(r.files.kmlUsed, 'map.kml');
  await assert.rejects(() => convertBytes(zip([{ name: 'sub/doc.kml', data: kml('') }]), 'm.kmz', opts), /no doc\.kml/);
});

test('ZIP64, empty, oversize and missing-DecompressionStream cases give clear errors', async () => {
  const k = kml('<Placemark><Point><coordinates>1,2</coordinates></Point></Placemark>');
  await assert.rejects(() => convertBytes(zip([{ name: 'doc.kml', data: k }], { eocdFields: { total: 0xFFFF } }), 'z.kmz', opts), /ZIP64/);
  await assert.rejects(() => convertBytes(zip([{ name: 'doc.kml', data: k }], { locator: true }), 'z.kmz', opts), /ZIP64/);
  await assert.rejects(() => convertBytes(new Uint8Array(0), 'e.kmz', opts), /empty/);
  await assert.rejects(() => convertBytes(new Uint8Array(MAX_BYTES + 1), 'big.kmz', opts), /10 MiB/);
  const saved = globalThis.DecompressionStream;
  try {
    globalThis.DecompressionStream = undefined;
    await assert.rejects(() => convertBytes(zip([{ name: 'doc.kml', data: k }]), 'n.kmz', opts), /DecompressionStream/);
    assert.equal((await convertBytes(zip([{ name: 'doc.kml', data: k, method: 0 }]), 'n.kmz', opts)).output.waypoints, 1, 'stored entries need no DecompressionStream');
  } finally { globalThis.DecompressionStream = saved; }
});

test('the 50,000 coordinate limit applies to a KMZ', async () => {
  const coords = Array.from({ length: 50001 }, (_, i) => `${(i % 360) - 180},${(i % 160) - 80}`).join(' ');
  await assert.rejects(() => convertBytes(zip([{ name: 'doc.kml', data: kml(`<Placemark><LineString><coordinates>${coords}</coordinates></LineString></Placemark>`) }]), 'big.kmz', opts), /50,000/);
});
