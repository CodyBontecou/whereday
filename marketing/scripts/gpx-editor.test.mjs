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
import { loadGPX, editGPX, detectSpikes, dayKey, zonedToUtc, formatInZone, zipFiles, makeProjection, safeBase, trackPoints } from '../public/assets/gpx-editor-core.mjs';
import { EDITOR_SAMPLE } from '../public/assets/gpx-editor-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const load = (text, name = 'file.gpx') => loadGPX(text, name, { Parser: XMLParser, Serializer: XMLSerializer });
const reparse = xml => parseGPX(xml, XMLParser);
const XSD = fileURLToPath(new URL('./schemas/gpx-1.1.xsd', import.meta.url));
const sampleText = await readFile(new URL('../public/samples/synthetic-two-day-walk.gpx', import.meta.url), 'utf8');
const sample = load(sampleText, 'synthetic-two-day-walk.gpx');
const HOME = { lat: 0, lon: 10, radiusM: 300 };
const gpx = body => `<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test">${body}</gpx>`;
const pt = (lat, lon, time, extra = '') => `<trkpt lat="${lat}" lon="${lon}">${extra}${time ? `<time>${time}</time>` : ''}</trkpt>`;
const trk = (...segs) => `<trk>${segs.map(s => `<trkseg>${s}</trkseg>`).join('')}</trk>`;
const sizes = r => r.files.map(f => f.counts.trackpoints);

let xmllint = true;
try { execFileSync('xmllint', ['--version'], { stdio: 'ignore' }); } catch { xmllint = false; }
function assertValid(xml, label) {
  if (!xmllint) return;
  const dir = mkdtempSync(join(tmpdir(), 'gpx-editor-'));
  try { const file = join(dir, 'out.gpx'); writeFileSync(file, xml); execFileSync('xmllint', ['--noout', '--nonet', '--schema', XSD, file], { stdio: 'pipe' }); }
  catch (e) { assert.fail(`${label} is not valid GPX 1.1: ${e.stderr?.toString() || e.message}`); }
  finally { rmSync(dir, { recursive: true, force: true }); }
}

test('the page sample matches the file and its documented contents', () => {
  assert.equal(EDITOR_SAMPLE.text, sampleText);
  assert.deepEqual([sample.trackPoints, sample.tracks.length, sample.tracks[0].segments.length, sample.waypoints.length, sample.routes.length, sample.routes[0].points.length], [140, 1, 3, 1, 1, 3]);
  const r = editGPX(sample, { zoneName: 'UTC' });
  assert.deepEqual([r.after.trackpoints, r.after.segments, r.after.waypoints, r.after.routepoints, r.after.files], [140, 3, 1, 3, 1]);
  assert.equal(new Date(r.after.span.first).toISOString(), '2000-01-01T09:00:00.000Z');
  assert.equal(new Date(r.after.span.last).toISOString(), '2000-01-02T17:59:00.000Z');
});

test('hide zone: 140 -> 114 track points, waypoint and one route point removed, segments cut not joined', () => {
  const r = editGPX(sample, { zone: HOME, zoneName: 'UTC' });
  assert.deepEqual([r.removed.zone, r.after.trackpoints, r.after.waypoints, r.after.routepoints, r.after.segments], [26, 114, 0, 2, 3]);
  assert.equal(r.removed.zoneWaypoints, 1); assert.equal(r.removed.zoneRoutePoints, 1);
  const out = reparse(r.files[0].xml);
  assert.ok(out.points.every(p => !(Math.hypot(p.lat, p.lon - 10) < 0.002)), 'no output point near home');
  // Larger radius removes more; a radius that leaves nothing near is unchanged.
  assert.ok(editGPX(sample, { zone: { ...HOME, radiusM: 1000 }, zoneName: 'UTC' }).after.trackpoints < 114);
  assert.equal(editGPX(sample, { zone: { lat: 45, lon: 45, radiusM: 100 }, zoneName: 'UTC' }).after.trackpoints, 140);
  // A track that passes through the zone is cut in two.
  const through = load(gpx(trk(pt(0, 0, '2000-01-01T00:00:00Z') + pt(0, 0.01, '2000-01-01T00:01:00Z') + pt(0, 0.02, '2000-01-01T00:02:00Z'))));
  const cut = editGPX(through, { zone: { lat: 0, lon: 0.01, radiusM: 100 }, zoneName: 'UTC' });
  assert.deepEqual([cut.after.trackpoints, cut.after.segments], [2, 2]);
});

test('speed check flags exactly the one planted spike and nothing is removed until asked', () => {
  const flagged = detectSpikes(sample, 300);
  assert.equal(flagged.length, 1);
  assert.equal(flagged[0].idx, 31); assert.equal(new Date(flagged[0].time).toISOString(), '2000-01-01T09:30:00.000Z');
  assert.ok(flagged[0].kmh > 1000 && flagged[0].meters > 30000);
  assert.equal(detectSpikes(sample, 100000).length, 0);
  const keep = editGPX(sample, { spikes: { thresholdKmh: 300, remove: false }, zoneName: 'UTC' });
  assert.equal(keep.flagged.length, 1); assert.equal(keep.after.trackpoints, 140);
  const drop = editGPX(sample, { spikes: { thresholdKmh: 300, remove: true }, zoneName: 'UTC' });
  assert.deepEqual([drop.removed.spikes, drop.after.trackpoints, drop.after.segments], [1, 139, 3]);
  // Points after a spike are not also flagged, and untimed points or reversed times are skipped.
  const odd = load(gpx(trk(pt(0, 0, '2000-01-01T00:00:00Z') + pt(0, 0.0001, '') + pt(1, 1, '2000-01-01T00:01:00Z') + pt(0, 0.0002, '2000-01-01T00:02:00Z') + pt(0, 0.0003, '2000-01-01T00:02:00Z'))));
  assert.deepEqual(detectSpikes(odd, 300).map(f => f.idx), [3]);
});

test('split by day: 2 files in UTC with the midnight segment cut, 3 in Brisbane; zone helpers', () => {
  const utc = editGPX(sample, { split: { mode: 'day' }, zoneName: 'UTC', fileName: 'walk.gpx' });
  assert.deepEqual(sizes(utc), [70, 70]);
  assert.deepEqual(utc.files.map(f => f.filename), ['walk-2000-01-01.gpx', 'walk-2000-01-02.gpx']);
  assert.deepEqual(utc.files.map(f => f.counts.segments), [2, 2]);
  const lastOfDay1 = trackPoints(load(utc.files[0].xml)).at(-1), firstOfDay2 = trackPoints(load(utc.files[1].xml))[0];
  assert.equal(new Date(lastOfDay1.time).toISOString(), '2000-01-01T23:59:00.000Z'); assert.equal(new Date(firstOfDay2.time).toISOString(), '2000-01-02T00:00:00.000Z');
  const bris = editGPX(sample, { split: { mode: 'day' }, zoneName: 'Australia/Brisbane' });
  assert.deepEqual(sizes(bris), [60, 20, 60]);
  assert.equal(dayKey(Date.parse('2000-01-01T23:59:00Z'), 'Australia/Brisbane'), '2000-01-02');
  assert.equal(formatInZone(Date.parse('2000-01-01T09:00:00Z'), 'Australia/Brisbane'), '2000-01-01 19:00:00');
  assert.equal(zonedToUtc('2000-01-01T19:00', 'Australia/Brisbane'), Date.parse('2000-01-01T09:00:00Z'));
  assert.equal(zonedToUtc('2000-07-01T12:00:00', 'America/New_York'), Date.parse('2000-07-01T16:00:00Z'));
  assert.equal(zonedToUtc('nonsense', 'UTC'), null);
  assert.throws(() => editGPX(load(gpx(trk(pt(0, 0, '') + pt(0, 1, '')))), { split: { mode: 'day' } }), /timestamps/);
  for (const f of utc.files) assertValid(f.xml, f.filename);
});

test('split by gap into files or segments', () => {
  const files = editGPX(sample, { split: { mode: 'gap', gapMinutes: 60, gapAction: 'file' } });
  assert.deepEqual(sizes(files), [60, 20, 60]);
  assert.deepEqual(files.files.map(f => f.filename.replace(/^.*-part-/, '')), ['1.gpx', '2.gpx', '3.gpx']);
  const none = editGPX(sample, { split: { mode: 'gap', gapMinutes: 60, gapAction: 'segment' } });
  assert.deepEqual([none.files.length, none.after.segments, none.after.trackpoints], [1, 3, 140]);
  const short = load(gpx(trk(pt(0, 0, '2000-01-01T00:00:00Z') + pt(0, 0.001, '2000-01-01T00:01:00Z') + pt(0, 0.002, '2000-01-01T00:20:00Z') + pt(0, 0.003, '2000-01-01T00:21:00Z'))));
  const seg = editGPX(short, { split: { mode: 'gap', gapMinutes: 10, gapAction: 'segment' } });
  assert.deepEqual([seg.after.segments, seg.after.trackpoints, seg.files.length], [2, 4, 1]);
  assert.deepEqual(sizes(editGPX(short, { split: { mode: 'gap', gapMinutes: 10, gapAction: 'file' } })), [2, 2]);
});

test('trim by point range, reverse and rename', () => {
  const t = editGPX(sample, { trim: { start: 11, end: 130 }, zoneName: 'UTC' });
  assert.deepEqual([t.removed.trim, t.after.trackpoints], [20, 120]);
  assert.equal(editGPX(sample, { trim: { start: 61, end: 80 } }).after.segments, 1);
  const rev = editGPX(sample, { reverse: true });
  const before = trackPoints(sample), after = reparse(rev.files[0].xml).points.filter(p => p.kind === 'track');
  assert.equal(after.length, 140);
  assert.equal(after[0].rawTime, '2000-01-02T17:59:00Z'); assert.equal(after.at(-1).rawTime, '2000-01-01T09:00:00Z');
  assert.equal(after[0].lat, before.at(-1).lat);
  assert.ok(rev.reversedTimes);
  const named = editGPX(sample, { trackName: 'Morning <walk> & co', fileName: 'My Walk?.gpx' });
  assert.equal(named.files[0].filename, 'My Walk.gpx');
  assert.match(named.files[0].xml, /<trk>\n {4}<name>Morning &lt;walk&gt; &amp; co<\/name>/);
  assert.equal(safeBase('../etc/passwd.gpx'), 'etc-passwd'); assert.equal(safeBase('  '), 'track'); assert.equal(safeBase('Café 2.GPX'), 'Café 2');
  const many = editGPX(sample, { trackName: 'Walk', split: { mode: 'day' }, zoneName: 'UTC' });
  assert.deepEqual(many.files.map(f => f.tracknames[0]), ['Walk (2000-01-01)', 'Walk (2000-01-02)']);
});

test('output keeps time, ele, extensions and the metadata name; output is valid GPX 1.1', () => {
  const r = editGPX(sample, { zone: HOME, spikes: { thresholdKmh: 300, remove: true }, reverse: true, trim: { start: 5, end: 135 }, zoneName: 'UTC' });
  const xml = r.files[0].xml;
  assert.match(xml, /<metadata>\n {4}<name>Synthetic two-day walk<\/name>\n {4}<desc>Invented points/);
  assert.match(xml, /<extensions><synthetic:note xmlns:synthetic="https:\/\/whereday.com\/samples\/synthetic-extension">Invented extension value<\/synthetic:note><\/extensions>/);
  assert.equal((xml.match(/<trkpt /g) || []).length, (xml.match(/<time>/g) || []).length);
  assert.equal((xml.match(/<trkpt /g) || []).length, (xml.match(/<ele>/g) || []).length - (xml.match(/<wpt /g) || []).length);
  assert.match(xml, /creator="Whereday GPX editor/);
  assert.ok(xml.indexOf('<wpt') < 0 || xml.indexOf('<wpt') < xml.indexOf('<trk>'));
  assert.deepEqual(r.meta.kept, ['name', 'description']);
  assertValid(xml, 'combined edits'); assertValid(editGPX(sample, {}).files[0].xml, 'no edits');
  assertValid(editGPX(sample, { zone: HOME }).files[0].xml, 'zone');
  const parsed = reparse(editGPX(sample, {}).files[0].xml);
  assert.deepEqual([parsed.tracks, parsed.segments, parsed.waypoints, parsed.routes], [1, 3, 1, 1]);
});

test('metadata that is dropped is reported; GPX 1.0 is read and written as 1.1', () => {
  const rich = load(`<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="t"><metadata><name>N</name><author><name>A</name></author><keywords>k</keywords><time>2000-01-01T00:00:00Z</time></metadata><extensions><a xmlns="urn:x">1</a></extensions>${trk(pt(1, 1, '2000-01-01T00:00:00Z'))}</gpx>`);
  assert.deepEqual(rich.meta.kept, ['name']);
  assert.deepEqual(rich.meta.dropped, ['author', 'keywords', 'file creation time', 'file-level extensions']);
  const v10 = load('<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/0" version="1.0" creator="t"><name>Old</name><desc>D</desc><trk><name>T</name><trkseg><trkpt lat="1" lon="1"><ele>5</ele><time>2000-01-01T00:00:00Z</time><speed>3</speed></trkpt></trkseg></trk></gpx>');
  const r = editGPX(v10, {});
  assert.equal(r.version, '1.0'); assert.ok(r.droppedFields >= 1);
  assert.match(r.files[0].xml, /<gpx version="1\.1"/); assert.match(r.files[0].xml, /<name>Old<\/name>/);
  assertValid(r.files[0].xml, 'gpx 1.0 input');
});

test('files without timestamps: trim by point works; day split and speed check do not apply', () => {
  const none = load(gpx(trk(pt(0, 0, '') + pt(0, 0.001, '') + pt(0, 0.002, ''))));
  assert.equal(none.timed, 0);
  assert.equal(editGPX(none, { trim: { start: 2, end: 3 } }).after.trackpoints, 2);
  assert.equal(editGPX(none, { spikes: { thresholdKmh: 300, remove: true } }).flagged.length, 0);
  assert.equal(editGPX(none, {}).after.span, null);
});

test('ZIP output has stored entries that unzip cleanly', { skip: !process.env.PATH }, () => {
  const bytes = zipFiles([{ name: 'a-2000-01-01.gpx', text: '<gpx/>\n' }, { name: 'b.gpx', text: 'x'.repeat(5000) }]);
  const dir = mkdtempSync(join(tmpdir(), 'gpx-editor-zip-'));
  try {
    const file = join(dir, 'x.zip'); writeFileSync(file, bytes);
    const out = execFileSync('python3', ['-I', '-c', 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1]); print(z.testzip(), [(i.filename, i.file_size) for i in z.infolist()], z.read("a-2000-01-01.gpx"))', file]).toString();
    assert.match(out, /^None \[\('a-2000-01-01\.gpx', 7\), \('b\.gpx', 5000\)\] b'<gpx\/>\\n'/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('projection round-trips and a 50,000-point file edits within a reasonable time', () => {
  const proj = makeProjection([{ lat: 10, lon: 20 }, { lat: 10.5, lon: 20.7 }]);
  const [x, y] = proj.project({ lat: 10.2, lon: 20.3 }), back = proj.invert(x, y);
  assert.ok(Math.abs(back.lat - 10.2) < 1e-9 && Math.abs(back.lon - 20.3) < 1e-9);
  let body = '';
  for (let i = 0; i < 50000; i++) body += pt((i % 1000) * 1e-4, Math.floor(i / 1000) * 1e-3, new Date(Date.UTC(2000, 0, 1) + i * 30000).toISOString().replace('.000Z', 'Z'), '<ele>1</ele>');
  const big = load(gpx(trk(body)));
  const started = Date.now();
  assert.equal(big.trackPoints, 50000);
  const r = editGPX(big, { trim: { start: 100, end: 49000 }, spikes: { thresholdKmh: 300, remove: true }, zone: { lat: 0, lon: 0, radiusM: 500 }, reverse: true, split: { mode: 'day' }, zoneName: 'America/New_York' });
  assert.ok(r.files.length >= 2 && r.after.trackpoints > 40000);
  assert.ok(Date.now() - started < 10000);
  assertValid(r.files[0].xml, 'big file');
});
