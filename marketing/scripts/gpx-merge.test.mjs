import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { DOMParser, XMLSerializer } from '@xmldom/xmldom';
import { parseGPX } from '../public/assets/gpx-core.mjs';
import { readGPX, mergeGPX, orderFiles, MODES } from '../public/assets/gpx-merge-core.mjs';
import { MERGE_SAMPLES } from '../public/assets/gpx-merge-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const read = (text, name = 'file.gpx') => readGPX(text, name, { Parser: XMLParser, Serializer: XMLSerializer });
const reparse = xml => parseGPX(xml, XMLParser);
const tags = (xml, name) => new XMLParser().parseFromString(xml, 'application/xml').getElementsByTagName(name);
const XSD = fileURLToPath(new URL('./schemas/gpx-1.1.xsd', import.meta.url));
const samples = await Promise.all([1, 2, 3].map(async d => read(await readFile(new URL(`../public/samples/synthetic-walk-day-${d}.gpx`, import.meta.url), 'utf8'), `synthetic-walk-day-${d}.gpx`)));
const gpx = (body, attrs = '') => `<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test"${attrs}>${body}</gpx>`;
const trk = (...segments) => `<trk>${segments.map(s => `<trkseg>${s}</trkseg>`).join('')}</trk>`;
const pt = (lat, lon, time, extra = '') => `<trkpt lat="${lat}" lon="${lon}">${extra}${time ? `<time>${time}</time>` : ''}</trkpt>`;

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function assertSchemaValid(xml, label) {
  const dir = mkdtempSync(join(tmpdir(), 'gpx-merge-'));
  try {
    const file = join(dir, 'merged.gpx'); writeFileSync(file, xml);
    execFileSync('xmllint', ['--noout', '--nonet', '--schema', XSD, file], { stdio: 'pipe' });
  } catch (error) {
    assert.fail(`${label} is not valid GPX 1.1: ${error.stderr?.toString() || error.message}`);
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

test('synthetic samples: documented counts for every merge mode', () => {
  const expected = { separate: [3, 5], segments: [1, 5], continuous: [1, 1] };
  for (const mode of MODES) {
    const r = mergeGPX(samples, { mode });
    const s = r.summary;
    assert.deepEqual([s.files, s.tracks, s.segments, s.trackpoints, s.waypoints, s.routes, s.routePoints], [3, ...expected[mode], 15, 2, 1, 3], mode);
    assert.equal(s.duplicatesRemoved, 1); assert.equal(s.duplicateWaypoints, 1); assert.equal(s.duplicateRoutes, 0);
    assert.equal(s.firstTime, '2000-01-01T21:00:00.000Z'); assert.equal(s.lastTime, '2000-01-03T07:03:00.000Z');
    assert.equal(r.filename, 'merged-2000-01-01-to-2000-01-03.gpx');
    // The viewer's own parser reads the merged file back with the same counts.
    const m = reparse(r.xml);
    assert.deepEqual([m.tracks, m.segments, m.points.filter(p => p.kind === 'track').length, m.waypoints, m.routes], [...expected[mode], 15, 2, 1], mode);
    assert.equal(tags(r.xml, 'gpx').length, 1);
    const off = mergeGPX(samples, { mode, dedupe: false }).summary;
    assert.deepEqual([off.tracks, off.segments, off.trackpoints, off.duplicatesRemoved], [...expected[mode], 16, 0]);
  }
});

test('all merge modes produce GPX 1.1 that passes the official XSD', { skip: !xmllint && 'xmllint is not installed' }, () => {
  for (const mode of MODES) for (const dedupe of [true, false]) assertSchemaValid(mergeGPX(samples, { mode, dedupe }).xml, `${mode} dedupe=${dedupe}`);
});

test('times, elevation, extensions, waypoints and routes are preserved', () => {
  const r = mergeGPX(samples, { mode: 'continuous', dedupe: false });
  const inputs = samples.flatMap(f => f.tracks.flatMap(t => t.segments.flat()));
  const out = tags(r.xml, 'trkpt');
  assert.equal(out.length, inputs.length);
  const ordered = orderFiles(samples).flatMap(f => f.tracks.flatMap(t => t.segments.flat()));
  for (const [i, p] of ordered.entries()) {
    assert.equal(Date.parse(out[i].getElementsByTagName('time')[0].textContent), p.time);
    assert.ok(out[i].getElementsByTagName('ele')[0].textContent);
  }
  assert.match(r.xml, /<extensions><synthetic:note xmlns:synthetic="https:\/\/whereday.com\/samples\/synthetic-extension">Invented extension value<\/synthetic:note><\/extensions>/);
  assert.deepEqual(Array.from(tags(r.xml, 'wpt')).map(w => w.getElementsByTagName('name')[0].textContent), ['Start (synthetic)', 'Camp (synthetic)']);
  assert.equal(tags(r.xml, 'rtept').length, 3);
  assert.match(r.xml, /<metadata>\n {4}<name>Merged GPX, 2000-01-01 to 2000-01-03<\/name>/);
  assert.match(r.xml, /Timed points run from 2000-01-01T21:00:00Z to 2000-01-03T07:03:00Z \(UTC\)/);
  assert.match(r.xml, /creator="Whereday GPX merge tool/);
  // GPX 1.1 order: metadata, wpt, rte, trk.
  assert.ok(r.xml.indexOf('<wpt') < r.xml.indexOf('<rte>') && r.xml.indexOf('<rte>') < r.xml.indexOf('<trk>'));
});

test('largest gap, straight-line warning and gap warning', () => {
  const continuous = mergeGPX(samples, { mode: 'continuous' });
  const gap = continuous.summary.gap;
  assert.equal(gap.ms, (23 * 60 + 58) * 60000);
  assert.equal(Math.round(gap.meters), 124);
  assert.equal(new Date(gap.from).toISOString(), '2000-01-02T07:02:00.000Z');
  assert.deepEqual(continuous.warnings.map(w => w.kind), ['straight-lines']);
  assert.equal(continuous.warnings[0].joins, 4);
  for (const mode of ['separate', 'segments']) assert.deepEqual(mergeGPX(samples, { mode }).warnings.map(w => w.kind), ['gap']);
});

test('sorting by time, manual order and the overlap warning', () => {
  const reversed = samples.slice().reverse();
  assert.deepEqual(mergeGPX(reversed).order, samples.map(f => f.name));
  const manual = mergeGPX(reversed, { sortByTime: false });
  assert.deepEqual(manual.order, reversed.map(f => f.name));
  assert.deepEqual(manual.warnings.filter(w => w.kind === 'order').map(w => w.b), ['synthetic-walk-day-2.gpx', 'synthetic-walk-day-1.gpx']);
  const a = read(gpx(trk(pt(0, 0, '2000-01-01T10:00:00Z') + pt(0, 0.001, '2000-01-01T12:00:00Z'))), 'a.gpx');
  const b = read(gpx(trk(pt(0, 0.002, '2000-01-01T11:00:00Z') + pt(0, 0.003, '2000-01-01T13:00:00Z'))), 'b.gpx');
  const overlap = mergeGPX([b, a]);
  assert.deepEqual(overlap.order, ['a.gpx', 'b.gpx']);
  assert.deepEqual(overlap.warnings.filter(w => w.kind === 'overlap'), [{ kind: 'overlap', a: 'a.gpx', b: 'b.gpx' }]);
  // Touching end and start times (a shared boundary point) are not an overlap.
  assert.ok(!mergeGPX(samples).warnings.some(w => w.kind === 'overlap'));
});

test('files without timestamps keep upload order after timed files', () => {
  const plain1 = read(gpx(trk(pt(1, 1) + pt(1, 1.001))), 'plain-1.gpx');
  const plain2 = read(gpx(trk(pt(2, 2) + pt(2, 2.001))), 'plain-2.gpx');
  const r = mergeGPX([plain1, samples[2], plain2, samples[0]]);
  assert.deepEqual(r.order, ['synthetic-walk-day-1.gpx', 'synthetic-walk-day-3.gpx', 'plain-1.gpx', 'plain-2.gpx']);
  assert.deepEqual(r.warnings.find(w => w.kind === 'untimed'), { kind: 'untimed', files: ['plain-1.gpx', 'plain-2.gpx'], sorted: true });
  const untimed = mergeGPX([plain2, plain1], { mode: 'continuous' });
  assert.deepEqual(untimed.order, ['plain-2.gpx', 'plain-1.gpx']);
  assert.equal(untimed.filename, 'merged.gpx');
  assert.equal(untimed.summary.gap.ms, null); assert.ok(untimed.summary.gap.meters > 100000);
  // Points without a time are never treated as duplicates.
  assert.equal(mergeGPX([plain1, plain1]).summary.trackpoints, 4);
  assert.match(untimed.xml, /The files contain no usable timestamps/);
});

test('invalid, empty, non-GPX and oversized files throw clear errors', () => {
  const cases = [['', /well-formed XML/], ['{"locations":[]}', /well-formed XML|not JSON/], ['<kml xmlns="http://www.opengis.net/kml/2.2"/>', /not JSON, KML/],
    ['<gpx version="1.1"><trk></gpx>', /well-formed XML/], [gpx(''), /may be empty/], [' '.repeat(10 * 1024 * 1024 + 1), /10 MiB/]];
  for (const [text, message] of cases) assert.throws(() => read(text), message);
  // A rejected file is simply not passed to the merge; the rest still merges.
  assert.equal(mergeGPX(samples.slice(0, 2)).summary.trackpoints, 11);
  assert.throws(() => mergeGPX([]), /at least one/);
});

test('GPX 1.0, invalid values and foreign elements become valid GPX 1.1 with counts', { skip: !xmllint && 'xmllint is not installed' }, () => {
  const old = read(`<gpx version="1.0" creator="old" xmlns="http://www.topografix.com/GPX/1/0" xmlns:x="https://example.com/x"><trk><name>Old &amp; track</name><url>https://example.com/t</url><urlname>Link</urlname><trkseg>
    <trkpt lat="1" lon="2"><ele>5</ele><time>2001-01-01T00:00:00Z</time><course>12</course><speed>1.5</speed><fix>3d</fix><sat>7</sat><x:hr>90</x:hr></trkpt>
    <trkpt lat="1.001" lon="2"><ele>high</ele><time>2001-01-01T00:01:00</time><fix>bad</fix></trkpt>
    <trkpt lat="91" lon="2"/></trkseg></trk></gpx>`, 'old.gpx');
  const r = mergeGPX([old, samples[0]]);
  assertSchemaValid(r.xml, 'GPX 1.0 input');
  // course, speed, unusable ele, timezone-free time and bad fix are dropped; the foreign element is kept.
  assert.equal(r.summary.droppedFields, 5); assert.equal(r.summary.invalidCoordinates, 1);
  assert.match(r.xml, /<link href="https:\/\/example.com\/t"><text>Link<\/text><\/link>/);
  assert.match(r.xml, /<extensions><x:hr xmlns:x="https:\/\/example.com\/x">90<\/x:hr><\/extensions>/);
  assert.match(r.xml, /<name>Old &amp; track<\/name>/);
  const ext = read(gpx(`<wpt lat="0" lon="0"><name>a</name><extensions><bad>no namespace</bad></extensions></wpt>` + trk(pt(0, 0, '2000-01-01T00:00:00+02:00'))));
  const e = mergeGPX([ext]);
  assertSchemaValid(e.xml, 'unqualified extension');
  assert.equal(e.summary.droppedFields, 1);
  assert.match(e.xml, /<time>2000-01-01T00:00:00\+02:00<\/time>/);
});

test('large merges avoid argument-limit failures', () => {
  const points = Array.from({ length: 200000 }, (_, i) => ({ lat: (i % 900) / 10, lon: 10, time: 946684800000 + i * 1000, attrs: `lat="${(i % 900) / 10}" lon="10"`, body: [] }));
  const file = { name: 'big.gpx', firstTime: points[0].time, lastTime: points.at(-1).time, tracks: [{ header: [], segments: [points] }], routes: [], waypoints: [], stats: { droppedFields: 0, invalidCoordinates: 0 } };
  const r = mergeGPX([file], { mode: 'continuous' });
  assert.equal(r.summary.trackpoints, 200000);
  assert.match(r.xml, /<bounds minlat="0" minlon="10" maxlat="89.9" maxlon="10"\/>/);
});

test('demo module ships the exact downloadable sample files', async () => {
  assert.deepEqual(MERGE_SAMPLES.map(s => s.name), [1, 2, 3].map(d => `synthetic-walk-day-${d}.gpx`));
  for (const [i, sample] of MERGE_SAMPLES.entries()) {
    assert.equal(sample.text, await readFile(new URL(`../public/samples/synthetic-walk-day-${i + 1}.gpx`, import.meta.url), 'utf8'));
    assert.match(sample.text, /Invented points .* Not a real recording/);
  }
});
