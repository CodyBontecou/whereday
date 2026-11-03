import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { parseGPX, toCSV, plotPaths, MAX_BYTES, MAX_POINTS } from '../public/assets/gpx-core.mjs';
class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const parse = text => parseGPX(text, XMLParser);
const gpx = points => `<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1"><trk><trkseg>${points}</trkseg></trk></gpx>`;
const point = (lat, lon, contents = '') => `<trkpt lat="${lat}" lon="${lon}">${contents}</trkpt>`;
const sample = await readFile(new URL('../public/samples/synthetic-app-export.gpx', import.meta.url), 'utf8');
test('actual five-point export preserves two segments and the 14-minute span', () => {
 const m = parse(sample); assert.equal(m.points.length,5); assert.equal(m.tracks,1); assert.equal(m.segments,2);
 assert.equal(m.firstTime,'2000-01-01T12:00:00.000Z'); assert.equal(m.lastTime,'2000-01-01T12:14:00.000Z');
 assert.equal(m.issues.timeGaps,0); assert.equal(plotPaths(m).length,2);
 const rows=toCSV(m).trim().split('\r\n'); assert.equal(rows.length,6); assert.deepEqual(rows.slice(1).map(r=>r.split(',')[1]),['1','1','1','2','2']);
});
test('missing and impossible or timezone-free dates do not become usable times', () => {
 const m=parse(gpx(point(0,0)+point(1,1,'<time>2026-02-31T12:00:00Z</time>')+point(2,2,'<time>2026-01-01T12:00:00</time>')));
 assert.equal(m.points.length,3); assert.equal(m.issues.missingTime,1); assert.equal(m.issues.invalidTime,2); assert.equal(m.firstTime,null);
});
test('bad coordinates break the plot and are omitted from CSV', () => {
 const m=parse(gpx(point(0,0)+point(91,0)+point(1,1)+'<trkpt lon="2"/>'+point(2,2)));
 assert.equal(m.issues.invalidCoordinates,2); assert.equal(m.points.length,3); assert.equal(plotPaths(m).length,3); assert.equal(toCSV(m).trim().split('\r\n').length,4);
});
test('GPX prefixes work and extension points are not mistaken for track points', () => {
 assert.equal(parse('<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1" xmlns:x="https://example.com" version="1.1"><g:trk><g:trkseg><g:trkpt lat="0" lon="0"/><x:trkpt lat="1" lon="1"/></g:trkseg></g:trk></g:gpx>').points.length,1);
});
test('routes and independent waypoints stay separate', () => {
 const m=parse('<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"><rte><rtept lat="0" lon="0"/><rtept lat="1" lon="1"/></rte><wpt lat="2" lon="2"/><wpt lat="3" lon="3"/></gpx>');
 assert.equal(m.routes,1); assert.equal(m.waypoints,2); assert.equal(m.tracks,0); assert.equal(plotPaths(m).length,3);
});
test('CSV escapes spreadsheet formulas and quotes, and normalizes UTC offsets', () => {
 const m=parse(gpx(point(-1,-2,'<name>=HYPERLINK("bad")</name><time>2000-01-01T08:00:00-04:00</time><ele>bad</ele>')));
 const csv=toCSV(m); assert.ok(csv.includes('"\'=HYPERLINK(""bad"")"')); assert.ok(csv.includes('2000-01-01T12:00:00.000Z')); assert.ok(csv.includes(',-1,-2,')); assert.equal(m.issues.invalidElevation,1);
});
test('duplicates, time gaps and backwards time are reported without deleting points', () => {
 const m=parse(gpx(point(0,0,'<time>2000-01-01T12:00:00Z</time>')+point(0,0,'<time>2000-01-01T12:11:00Z</time>')+point(1,1,'<time>2000-01-01T12:10:00Z</time>')));
 assert.equal(m.points.length,3); assert.equal(m.issues.duplicatePositions,1); assert.equal(m.issues.timeGaps,1); assert.equal(m.issues.reversedTime,1);
});
test('rejects empty, malformed, foreign-format and entity-bearing files', () => {
 for(const xml of ['', '{"locations":[]}', '<gpx version="1.1"><trk></gpx>', '<kml/>', '<!DOCTYPE gpx [<!ENTITY x "test">]><gpx version="1.1"/>', '<gpx version="1.1"/>']) assert.throws(()=>parse(xml));
});
test('file-size and point caps bound memory use', () => {
 assert.throws(()=>parse(' '.repeat(MAX_BYTES+1)),/10 MiB/); assert.throws(()=>parse(gpx(point(0,0).repeat(MAX_POINTS+1))),/50,000/);
});
test('plot sampling is bounded and handles dateline and single points', () => {
 const m=parse(gpx(Array.from({length:120},(_,i)=>point(i/100,i%2?-179.9:179.9)).join(''))); const paths=plotPaths(m,10);
 assert.ok(paths.flat().length<=10); assert.ok(paths.flat().every(p=>p.every(Number.isFinite))); assert.deepEqual(plotPaths(parse(gpx(point(0,0)))),[[[400,180]]]);
});

test('numeric-looking hexadecimal is not a GPX decimal coordinate', () => {
 const m=parse(gpx(point('0x10',0)+point(0,0))); assert.equal(m.points.length,1); assert.equal(m.issues.invalidCoordinates,1);
});

test('published format-guide CSV matches actual browser exporter output', async () => {
 const source=await readFile(new URL('../public/samples/synthetic-format-trip.gpx',import.meta.url),'utf8');
 const csv=await readFile(new URL('../public/samples/synthetic-format-trip.csv',import.meta.url),'utf8');
 assert.equal(toCSV(parse(source)),csv);
});
