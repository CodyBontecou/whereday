import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { gzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import {
  boundaries, locate, readLocationFile, analyze, summarize, emptyManual, toggleManual, thin, tableCSV, mapSVG,
  pngSize, dayFromText, sortRows, BORDER_KM,
} from '../public/assets/visited-countries-core.mjs';
import { VISITED_DEMO } from '../public/assets/visited-countries-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const read = (text, name) => readLocationFile(text, name, { Parser: XMLParser });
const table = (...files) => summarize(files.map(f => analyze(read(...f))));
const sample = name => readFile(new URL(`../public/samples/${name}`, import.meta.url), 'utf8');
const b = boundaries();
const brief = s => s.rows.map(r => [r.code, r.first, r.last, r.dates, r.source].join('|'));

test('the bundled boundary set is the size the page says', async () => {
  assert.equal(b.list.length, 240, 'the page says 240 outlines');
  const data = await readFile(new URL('../public/assets/visited-countries-data.mjs', import.meta.url));
  assert.ok(data.length < 450 * 1024, 'about 420 KB on disk');
  const gz = gzipSync(data).length;
  assert.ok(gz < 160 * 1024 && gz < 1024 * 1024, `about 150 KB gzipped, got ${gz}`);
  const page = await readFile(new URL('../content/visited-countries-map.html', import.meta.url), 'utf8');
  assert.match(page, /version 5\.1\.2/);
  assert.match(page, /about 420 KB, about 150 KB gzipped, with 240 country/);
});

test('known points fall inside the right countries', () => {
  const cases = [[40.4, -3.7, 'ES'], [48.85, 2.35, 'FR'], [35.7, 139.7, 'JP'], [-33.87, 151.21, 'AU'], [-15.8, -47.9, 'BR'], [51.6, -0.1, 'GB'], [1.35, 103.82, 'SG']];
  for (const [lat, lon, code] of cases) assert.equal(locate(lat, lon, b).code, code, `${lat},${lon}`);
  // Lesotho is a hole in South Africa's outline, so holes must be respected.
  assert.equal(locate(-29.6, 28.2, b).code, 'LS');
  assert.equal(locate(-30.5, 25.5, b).code, 'ZA');
});

test('open ocean is unmatched, and invalid coordinates are rejected', () => {
  assert.deepEqual(locate(0, -30, b), { code: null, nearCoast: null });
  assert.deepEqual(locate(-40, -120, b), { code: null, nearCoast: null });
  for (const [lat, lon] of [[91, 0], [NaN, 0], [0, Infinity], [-90.5, 0]]) assert.deepEqual(locate(lat, lon, b), { invalid: true });
  const result = analyze({ points: [{ lat: 0, lon: -30, day: 10957 }], records: [] });
  assert.equal(result.stats.unmatched, 1);
  assert.equal(result.stats.matched, 0);
  assert.equal(summarize([result]).count, 0);
});

test('a point within 10 km of a border is flagged, not counted', () => {
  assert.equal(BORDER_KM, 10);
  // Near Basel: in France, a few km from both Germany and Switzerland.
  const found = locate(47.6, 7.59, b);
  assert.equal(found.code, 'FR');
  assert.ok(['DE', 'CH'].includes(found.near), `near ${found.near}`);
  const result = analyze({ points: [{ lat: 47.6, lon: 7.59, day: 10957 }, { lat: 48.85, lon: 2.35, day: 10958 }], records: [] });
  assert.equal(result.stats.near, 1);
  assert.equal(result.stats.matched, 1);
  const s = summarize([result]);
  assert.deepEqual([...s.codes], ['FR']);
  // France is counted from the Paris point; a border-only country is listed but not counted.
  const only = summarize([analyze({ points: [{ lat: 47.6, lon: 7.59, day: 10957 }], records: [] })]);
  assert.equal(only.count, 0);
  assert.deepEqual(only.nearOnly.map(n => [n.code, n.points, n.nearTo.length > 0]), [['FR', 1, true]]);
  // Deep inside a country is not flagged.
  assert.equal(locate(46.6, 2.5, b).near, null);
  // A point just off a coast is unmatched but names the country that is near.
  assert.equal(locate(40.71, -74.0, b).nearCoast, 'US');
});

test('antimeridian and pole safety', () => {
  // 180 and -180 are the same meridian; outlines are split there.
  assert.deepEqual(locate(65.5, 180, b), locate(65.5, -180, b));
  assert.equal(locate(65.5, 179.5, b).code, 'RU');
  assert.equal(locate(65.5, -179.5, b).code, 'RU');
  assert.deepEqual(locate(0, 190, b), locate(0, -170, b));
  assert.equal(locate(-16.6, 179.5, b).code, 'FJ');
  assert.equal(locate(-16.6, -179.5, b).code, locate(-16.6, 180.5, b).code);
  // Poles: no throw, no NaN, a definite answer.
  assert.deepEqual(locate(90, 0, b), { code: null, nearCoast: null });
  assert.deepEqual(locate(90, 123, b), { code: null, nearCoast: null });
  for (const lon of [-180, -90, 0, 90, 180]) {
    const south = locate(-90, lon, b);
    assert.ok(south.code === 'AQ' || south.code === null, `south pole at ${lon}`);
  }
  assert.equal(locate(-89.9, 10, b).code, 'AQ');
  assert.equal(thin([{ lat: 90, lon: 180, day: 1 }, { lat: 90, lon: -180, day: 1 }]).length, 1);
});

test('thinning keeps one point per 0.01 degree cell per date', () => {
  const pts = [{ lat: 40.001, lon: -3.001, day: 5 }, { lat: 40.002, lon: -3.002, day: 5 }, { lat: 40.002, lon: -3.002, day: 6 }, { lat: 40.5, lon: -3.5, day: 5 }, { lat: 40.5, lon: -3.5, day: null }, { lat: 40.5, lon: -3.5, day: null }];
  assert.equal(thin(pts).length, 4);
});

test('dates use UTC unless the timestamp has an offset', () => {
  assert.deepEqual(dayFromText('2000-01-01T23:30:00Z'), { day: 10957, rule: 'utc' });
  assert.deepEqual(dayFromText('2000-01-01T01:00:00+14:00'), { day: 10957, rule: 'offset' }); // UTC date would be 1999-12-31
  assert.deepEqual(dayFromText('2000-01-01'), { day: 10957, rule: 'date' });
  assert.equal(dayFromText('2000-01-01T10:00:00'), null);
  assert.equal(dayFromText('2000-13-01T10:00:00Z'), null);
  assert.equal(dayFromText('yesterday'), null);
});

test('a GPX file produces the expected table', async () => {
  const gpx = read(await sample('synthetic-countries-trip.gpx'), 'trip.gpx');
  assert.equal(gpx.kind, 'gpx');
  assert.equal(gpx.rule, 'utc');
  const s = summarize([analyze(gpx)]);
  assert.equal(s.count, 3);
  assert.deepEqual(brief(s), ['FR|10962|10963|2|file', 'PT|10957|10957|1|file', 'ES|10959|10960|2|file'].sort((x, y) => b.byCode.get(x.slice(0, 2)).name.localeCompare(b.byCode.get(y.slice(0, 2)).name)));
  assert.deepEqual(s.rows.map(r => [r.code, r.name, r.first, r.last, r.dates]).map(([c, n, f, l, d]) => `${c} ${n} ${d}`), ['FR France 2', 'PT Portugal 1', 'ES Spain 2']);
  assert.equal(s.stats.matched, 5);
  assert.equal(VISITED_DEMO.text, await sample('synthetic-countries-trip.gpx'));
  // A GPX with an offset keeps the written date.
  const offset = read('<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><wpt lat="40.4" lon="-3.7"><time>2000-01-01T01:00:00+14:00</time></wpt></gpx>', 'o.gpx');
  assert.equal(offset.rule, 'offset');
  assert.equal(analyze(offset).countries.get('ES').days.has(10957), true);
});

test('a Google Timeline JSON file produces the expected table', () => {
  const json = JSON.stringify([
    { startTime: '2000-02-01T10:00:00Z', endTime: '2000-02-02T12:00:00Z', visit: { topCandidate: { placeLocation: { latLng: '35.68°, 139.69°' } } } },
    { startTime: '2000-03-05T08:00:00Z', endTime: '2000-03-05T20:00:00Z', timelinePath: [{ point: '-15.8°, -47.9°', durationMinutesOffsetFromStartTime: '60' }] },
  ]);
  const file = read(json, 'Timeline.json');
  assert.equal(file.kind, 'timeline');
  const s = summarize([analyze(file)]);
  assert.deepEqual(s.rows.map(r => `${r.code} ${r.first}-${r.last} ${r.dates}`), ['BR 11021-11021 1', 'JP 10988-10989 2']);
  assert.equal(s.count, 2);
});

test('a Whereday Country Days CSV produces the expected table', async () => {
  const file = read(await sample('synthetic-country-days.csv'), 'country-days.csv');
  assert.equal(file.kind, 'csv');
  const s = summarize([analyze(file)]);
  assert.deepEqual(s.rows.map(r => `${r.code} ${r.dates} ${r.source}`), ['FR 1 file', 'PT 2 file', 'ES 1 file']);
  assert.equal(s.stats.csvRows, 4);
  assert.deepEqual(s.undrawn, []);
});

test('GeoJSON and KML points are read, other shapes are reported', () => {
  const geo = read(JSON.stringify({ type: 'FeatureCollection', features: [
    { type: 'Feature', properties: { time: '2000-04-01T12:00:00Z' }, geometry: { type: 'Point', coordinates: [139.69, 35.68] } },
    { type: 'Feature', properties: { times: ['2000-04-02T12:00:00Z', '2000-04-03T12:00:00Z'] }, geometry: { type: 'LineString', coordinates: [[-3.7, 40.4], [-3.71, 40.41]] } },
    { type: 'Feature', properties: {}, geometry: { type: 'MultiLineString', coordinates: [[[2.35, 48.85], [2.36, 48.86]], [[200, 0]]] } },
    { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [] } },
  ] }), 'a.geojson');
  assert.equal(geo.points.length, 5);
  assert.equal(geo.skipped, 1);
  assert.match(geo.notes[0], /Polygon/);
  assert.deepEqual(summarize([analyze(geo)]).rows.map(r => `${r.code} ${r.dates}`), ['FR 0', 'JP 1', 'ES 2']);
  const kml = read('<?xml version="1.0"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><TimeStamp><when>2000-05-01T00:00:00Z</when></TimeStamp><Point><coordinates>139.69,35.68,0</coordinates></Point></Placemark><Placemark><LineString><coordinates>-3.7,40.4,0 -3.71,40.41,0 bad</coordinates></LineString></Placemark></Document></kml>', 'a.kml');
  assert.equal(kml.kind, 'kml');
  assert.equal(kml.points.length, 3);
  assert.equal(kml.skipped, 1);
  assert.deepEqual(summarize([analyze(kml)]).rows.map(r => `${r.code} ${r.dates}`), ['JP 1', 'ES 0']);
});

test('unusable files give plain errors', () => {
  assert.throws(() => read('', 'x'), /empty/);
  assert.throws(() => read('hello', 'x'), /Not recognized/);
  assert.throws(() => read('<html></html>', 'x'), /GPX, KML/);
  assert.throws(() => read('{"a":1}', 'x'), /recognized|container/);
  assert.throws(() => read('{', 'x'), /JSON/);
  assert.throws(() => read('<!DOCTYPE x><kml/>', 'x'), /DTD/);
  assert.throws(() => read('x'.repeat(10 * 1024 * 1024 + 1), 'x'), /10 MiB/);
});

test('manual add and remove are reflected in the CSV export', async () => {
  const base = summarize([analyze(read(await sample('synthetic-countries-trip.gpx'), 'trip.gpx'))]);
  let manual = emptyManual(), step;
  step = toggleManual(manual, 'IT', base); manual = step.manual; assert.equal(step.action, 'added');
  step = toggleManual(manual, 'FR', base); manual = step.manual; assert.equal(step.action, 'removed');
  const analyses = [analyze(read(await sample('synthetic-countries-trip.gpx'), 'trip.gpx'))];
  const s = summarize(analyses, manual);
  assert.equal(s.count, 3); // PT, ES and manual IT; France removed
  assert.deepEqual([...s.codes].sort(), ['ES', 'IT', 'PT']);
  assert.equal(tableCSV(s), [
    'country_code,country,first_date,last_date,distinct_dates,points,source',
    'ES,Spain,2000-01-03,2000-01-04,2,2,file',
    'FR,France,2000-01-06,2000-01-07,2,2,removed manually',
    'IT,Italy,,,0,0,manual',
    'PT,Portugal,2000-01-01,2000-01-01,1,1,file', ''].join('\r\n'));
  assert.ok(!mapSVG(s.codes).includes('fill="#A8381F" stroke="#F7F4EC" stroke-width="0.3" stroke-linejoin="round"><title>France'));
  // Clicking again undoes each change.
  step = toggleManual(manual, 'IT', s); assert.equal(step.action, 'removed-manual');
  step = toggleManual(step.manual, 'FR', s); assert.equal(step.action, 'restored');
  assert.equal(summarize(analyses, step.manual).count, 3 - 1 + 1 - 1 + 1);
  assert.equal(summarize(analyses, step.manual).rows.every(r => r.source === 'file'), true);
});

test('country names starting with formula characters cannot be exported as formulas', () => {
  const s = { rows: [{ code: 'XX', name: '=HYPERLINK("x")', first: null, last: null, dates: 0, points: 0, source: 'manual' }] };
  assert.match(tableCSV(s), /XX,"'=HYPERLINK\(""x""\)",/);
});

test('sorting keys order the table', async () => {
  const s = summarize([analyze(read(await sample('synthetic-countries-trip.gpx'), 'trip.gpx'))]);
  assert.deepEqual(sortRows(s.rows, 'first').map(r => r.code), ['PT', 'ES', 'FR']);
  assert.deepEqual(sortRows(s.rows, 'last').map(r => r.code), ['FR', 'ES', 'PT']);
  assert.deepEqual(sortRows(s.rows, 'dates').map(r => r.code), ['FR', 'ES', 'PT']);
});

test('the synthetic demo downloads match the expected output', async () => {
  const s = summarize([analyze(read(VISITED_DEMO.text, VISITED_DEMO.name))]);
  const csv = tableCSV(s), svg = mapSVG(s.codes, { count: s.count });
  assert.equal(csv, 'country_code,country,first_date,last_date,distinct_dates,points,source\r\nES,Spain,2000-01-03,2000-01-04,2,2,file\r\nFR,France,2000-01-06,2000-01-07,2,2,file\r\nPT,Portugal,2000-01-01,2000-01-01,1,1,file\r\n');
  assert.equal(createHash('sha256').update(svg).digest('hex'), '9e55af03ea499942c0782e9593c6d74448b549eaec7f436d06944156129c27e5');
  // The PNG is this SVG drawn at a fixed 2:1 size, so its dimensions are checked here.
  assert.deepEqual(pngSize(), { width: 2400, height: 1200 });
  assert.match(svg, /width="1440" height="720"/);
  assert.equal((svg.match(/fill="#A8381F"/g) || []).length, 3);
  assert.ok(!/<text|watermark/i.test(svg));
  assert.ok(!/https?:\/\/(?!www\.w3\.org)/.test(svg.replace(/https?:\/\/www\.w3\.org[^"]*/g, '')), 'no external references');
});

test('the PNG export source is a well-formed SVG at the PNG size', () => {
  // The page draws this exact SVG onto a canvas. Node has no canvas, so the
  // check is that the source parses and has the PNG's size and aspect ratio.
  const s = summarize([analyze(read(VISITED_DEMO.text, VISITED_DEMO.name))]);
  const { width, height } = pngSize();
  const doc = new XMLParser().parseFromString(mapSVG(s.codes, { count: s.count, width }), 'image/svg+xml');
  const root = doc.documentElement;
  assert.equal(root.localName, 'svg');
  assert.equal(root.getAttribute('width'), String(width));
  assert.equal(root.getAttribute('height'), String(height));
  assert.equal(root.getAttribute('viewBox'), '0 0 720 360');
  // Two tiny outlines collapse below three points at map precision and are not drawn.
  assert.equal(doc.getElementsByTagName('path').length, 238);
});

test('visited-countries-data.mjs is the only boundary asset', async () => {
  const { readdir } = await import('node:fs/promises');
  const names = await readdir(new URL('../public/assets/', import.meta.url));
  assert.deepEqual(names.filter(n => /boundar|countries-data|\.geojson|\.topojson/i.test(n)), ['visited-countries-data.mjs']);
});
