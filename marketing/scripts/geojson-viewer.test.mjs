import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { DOMParser } from '@xmldom/xmldom';
import { parseGeoJSON, toGPX, toKML, exportable, plotShapes, formatBounds } from '../public/assets/geojson-viewer-core.mjs';
import { parseGPX } from '../public/assets/gpx-core.mjs';
import { parseKML } from '../public/assets/kml-viewer-core.mjs';
import { GEOJSON_VIEWER_SAMPLE } from '../public/assets/geojson-viewer-demo.mjs';

class XMLParser extends DOMParser { constructor() { super({ onError: () => { throw new Error('Malformed XML'); } }); } }
const sampleText = () => readFile(new URL('../public/samples/synthetic-geojson-viewer.geojson', import.meta.url), 'utf8');
const fc = (...geometries) => JSON.stringify({ type: 'FeatureCollection', features: geometries.map((geometry, i) => ({ type: 'Feature', properties: { name: `f${i}` }, geometry })) });
const skippedOf = m => Object.fromEntries(m.skipped.map(s => [s.key, s.count]));

test('synthetic example reports exactly the documented counts', async () => {
  const m = parseGeoJSON(await sampleText());
  assert.deepEqual(m.rootKinds, ['FeatureCollection']);
  assert.equal(m.counts.features, 4);
  assert.deepEqual(m.counts.byType, { Point: 2, MultiPoint: 1, LineString: 0, MultiLineString: 1, Polygon: 0, MultiPolygon: 0, GeometryCollection: 0 });
  assert.equal(m.counts.positions, 10);
  assert.equal(m.counts.withTimes, 2);
  assert.equal(m.counts.timeValues, 6);
  assert.equal(m.firstTime, '2000-01-01T12:00:00Z');
  assert.equal(m.lastTime, '2000-01-01T12:21:00Z');
  assert.equal(formatBounds(m.bounds), '10.001,0.002 to 10.022,0.032');
  assert.deepEqual(skippedOf(m), { altitude: 1 });
  const camp = m.features.find(f => f.name === 'Camp');
  assert.equal(camp.firstTime, '', 'a missing time stays unknown');
  assert.deepEqual(m.notes, []);
});

test('synthetic example GPX has the documented counts and parses in the GPX viewer', async () => {
  const m = parseGeoJSON(await sampleText());
  const gpx = toGPX(m, 'synthetic');
  assert.deepEqual(gpx.counts, { waypoints: 5, tracks: 1, segments: 2, trackPoints: 5, withTime: 6, withElevation: 1, positions: 10 });
  const g = parseGPX(gpx.text, XMLParser);
  assert.equal(g.waypoints, 5); assert.equal(g.tracks, 1); assert.equal(g.segments, 2);
  assert.equal(g.points.length, 10); assert.equal(g.points.filter(p => p.time !== null).length, 6);
  assert.deepEqual(g.issues.invalidCoordinates, 0);
  assert.equal(g.firstTime, '2000-01-01T12:00:00.000Z'); assert.equal(g.lastTime, '2000-01-01T12:21:00.000Z');
  assert.deepEqual(gpx.dropped.map(d => [d.label, d.count]), [['Features with other properties', 1]]);
  assert.match(gpx.dropped[0].reason, /note/);
  assert.match(gpx.text, /<wpt lat="0.002" lon="10.001"><ele>12<\/ele><time>2000-01-01T12:00:00Z<\/time><name>Start<\/name><\/wpt>/);
});

test('synthetic example KML has the documented counts and parses in the KML viewer', async () => {
  const m = parseGeoJSON(await sampleText());
  const kml = toKML(m, 'synthetic');
  assert.deepEqual(kml.counts, { placemarks: 4, points: 5, lineStrings: 0, gxTracks: 2, polygons: 0, positions: 10, withTime: 6, withElevation: 1 });
  const k = parseKML(kml.text, { Parser: XMLParser });
  assert.equal(k.counts.placemarks, 4); assert.equal(k.counts.points, 5); assert.equal(k.counts.gxTracks, 2); assert.equal(k.counts.gxMultiTracks, 1);
  assert.equal(k.counts.multiGeometries, 1); assert.equal(k.counts.withTimes, 5);
  assert.deepEqual(k.skipped.map(s => s.label), ['TimeStamp']);
});

test('the demo module matches the committed sample', async () => {
  assert.equal(GEOJSON_VIEWER_SAMPLE.text, await sampleText());
});

test('FeatureCollection, a single Feature and a bare Geometry all open', () => {
  const geometry = { type: 'Point', coordinates: [1, 2] };
  assert.deepEqual(parseGeoJSON(fc(geometry)).rootKinds, ['FeatureCollection']);
  const f = parseGeoJSON(JSON.stringify({ type: 'Feature', properties: { name: 'One' }, geometry }));
  assert.deepEqual(f.rootKinds, ['Feature']); assert.equal(f.counts.features, 1); assert.equal(f.features[0].name, 'One');
  const g = parseGeoJSON(JSON.stringify(geometry));
  assert.deepEqual(g.rootKinds, ['bare geometry']); assert.equal(g.counts.features, 1); assert.equal(g.counts.byType.Point, 1);
});

test('all seven geometry types and a GeometryCollection are read, longitude first', () => {
  const ring = [[0, 0], [1, 0], [1, 1], [0, 0]], hole = [[0.2, 0.2], [0.4, 0.2], [0.4, 0.4], [0.2, 0.2]];
  const m = parseGeoJSON(fc(
    { type: 'Point', coordinates: [12.5, -33.25] },
    { type: 'MultiPoint', coordinates: [[1, 2], [3, 4]] },
    { type: 'LineString', coordinates: [[0, 0], [1, 1]] },
    { type: 'MultiLineString', coordinates: [[[0, 0], [1, 1]], [[2, 2], [3, 3]]] },
    { type: 'Polygon', coordinates: [ring, hole] },
    { type: 'MultiPolygon', coordinates: [[ring], [ring, hole]] },
    { type: 'GeometryCollection', geometries: [{ type: 'Point', coordinates: [5, 6] }, { type: 'LineString', coordinates: [[5, 6], [7, 8]] }] },
  ));
  assert.deepEqual(m.counts.byType, { Point: 1, MultiPoint: 1, LineString: 1, MultiLineString: 1, Polygon: 1, MultiPolygon: 1, GeometryCollection: 1 });
  assert.equal(m.counts.positions, 1 + 2 + 2 + 4 + 8 + (4 + 8) + 1 + 2);
  assert.deepEqual(m.features[0].parts[0].coords, [[12.5, -33.25]], 'longitude first');
  assert.equal(m.features[6].type, 'GeometryCollection (Point, LineString)');
  assert.deepEqual(m.features[4].parts.map(p => p.inner), [false, true]);
  assert.deepEqual(m.skipped, []);
  const shapes = plotShapes({ bounds: m.bounds, placemarks: m.features });
  for (const s of shapes) for (const [x, y] of s.points) assert.ok(x >= 0 && x <= 800 && y >= 0 && y <= 360);
  assert.ok(shapes.some(s => s.kind === 'polygon' && s.inner));
});

test('invalid and out-of-range positions are skipped and counted, with reasons', () => {
  const m = parseGeoJSON(fc({ type: 'LineString', coordinates: [[0, 0], [200, 1], ['a', 1], [1], [2, 2], [1, 95], [3, 3, 'x']] }, { type: 'Point', coordinates: [30, 120] }));
  assert.equal(m.counts.positions, 2);
  assert.equal(skippedOf(m).invalid, 6);
  assert.equal(skippedOf(m).swapped, 2, '[30,120] and [1,95] fit latitude-first');
  assert.equal(skippedOf(m).projected, 1);
  assert.equal(m.features[0].coordinateCount, 2);
  assert.ok(m.skipped.every(s => s.reason && s.count > 0));
});

test('projected-looking coordinates are reported, not converted', () => {
  const m = parseGeoJSON(fc({ type: 'Point', coordinates: [500000, 4649776] }));
  assert.equal(m.counts.positions, 0); assert.equal(skippedOf(m).invalid, 1); assert.equal(skippedOf(m).projected, 1);
  assert.equal(m.bounds, null);
  assert.deepEqual(plotShapes({ bounds: m.bounds, placemarks: m.features }), []);
});

test('null geometry, bad type, bad geometry and empty geometry are counted, never silent', () => {
  const m = parseGeoJSON(JSON.stringify({ type: 'FeatureCollection', crs: { type: 'name' }, features: [
    { type: 'Feature', properties: null, geometry: null },
    { type: 'Feature', properties: {} },
    { properties: {}, geometry: { type: 'Point', coordinates: [1, 2] } },
    'text',
    { type: 'Feature', properties: {}, geometry: { type: 'Circle', coordinates: [1, 2] } },
    { type: 'Feature', properties: {}, geometry: { type: 'LineString' } },
    { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [] } },
    { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [[[0, 0], [1, 1]]] } },
    { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: [[1, 1]] } },
    { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [1, 2, 3, 4] } },
  ] }));
  assert.equal(m.counts.features, 8);
  assert.deepEqual(skippedOf(m), { badFeature: 2, noGeometry: 2, badGeometry: 1, noCoordinates: 1, emptyGeometry: 1, shortLine: 1, shortRing: 1, extra: 1, altitude: 1, crs: 1 });
  assert.equal(m.features.length, 8);
  assert.equal(m.features[0].type, 'No geometry');
  assert.equal(exportable(m).gpx, true);
});

test('time is read from time, timestamp and coordTimes; unreadable stays unknown', () => {
  const feature = (properties, geometry) => ({ type: 'Feature', properties, geometry });
  const m = parseGeoJSON(JSON.stringify({ type: 'FeatureCollection', features: [
    feature({ Timestamp: '2020-05-01T10:00:00+02:00' }, { type: 'Point', coordinates: [1, 2] }),
    feature({ time: 1588327200000 }, { type: 'Point', coordinates: [1, 2] }),
    feature({ time: '2020-05-01 10:00' }, { type: 'Point', coordinates: [1, 2] }),
    feature({ times: ['2020-05-02T00:00:00Z', '2020-05-02T00:05:00Z'] }, { type: 'LineString', coordinates: [[0, 0], [1, 1]] }),
    feature({ coordinateProperties: { times: ['2020-05-03T00:00:00Z'] } }, { type: 'LineString', coordinates: [[0, 0], [1, 1]] }),
  ] }));
  assert.equal(m.features[0].firstTime, '2020-05-01T08:00:00Z');
  assert.equal(m.features[1].firstTime, ''); assert.equal(m.features[2].firstTime, '');
  assert.equal(m.features[3].lastTime, '2020-05-02T00:05:00Z');
  assert.equal(m.features[4].firstTime, '2020-05-03T00:00:00Z', 'a mismatched array still gives first and last');
  assert.equal(skippedOf(m).timeUnreadable, 2); assert.equal(skippedOf(m).timeMismatch, 1);
  assert.equal(m.firstTime, '2020-05-01T08:00:00Z'); assert.equal(m.lastTime, '2020-05-03T00:00:00Z');
  const gpx = toGPX(m);
  assert.equal(gpx.counts.withTime, 3, 'one point time and the two matching line times');
  assert.deepEqual(gpx.dropped.find(d => d.label === 'Times not written').count, 1);
});

test('a synthetic antimeridian-crossing line is split without a made-up point', () => {
  const m = parseGeoJSON(fc({ type: 'LineString', coordinates: [[178, 0], [179.5, 1], [-179.5, 2], [-178, 3]] }));
  assert.equal(m.counts.antimeridianSplits, 1);
  assert.equal(m.notes.length, 1);
  assert.deepEqual(m.features[0].parts.map(p => p.coords.length), [2, 2]);
  const gpx = toGPX(m);
  assert.equal(gpx.counts.tracks, 1); assert.equal(gpx.counts.segments, 2); assert.equal(gpx.counts.trackPoints, 4);
  const kml = toKML(m);
  assert.equal(kml.counts.lineStrings, 2); assert.match(kml.text, /<MultiGeometry>/);
  // A two-point line that crosses leaves single-position pieces: kept in GPX, reported as left out of KML.
  const tiny = parseGeoJSON(fc({ type: 'LineString', coordinates: [[179, 0], [-179, 1]] }));
  assert.equal(toGPX(tiny).counts.segments, 2);
  assert.equal(exportable(tiny).kml, false);
  assert.throws(() => toKML(tiny), /Nothing to write to KML/);
  const mixed = parseGeoJSON(fc({ type: 'LineString', coordinates: [[179, 0], [-179, 1], [-178, 2]] }));
  assert.deepEqual(toKML(mixed).dropped.map(d => [d.label, d.count]), [['Single-position line pieces', 1]]);
});

test('GPX exports points as waypoints and lines as tracks; polygons are only a count', () => {
  const ring = [[0, 0], [1, 0], [1, 1], [0, 0]];
  const m = parseGeoJSON(fc({ type: 'MultiPoint', coordinates: [[1, 2], [3, 4]] }, { type: 'MultiLineString', coordinates: [[[0, 0], [1, 1]], [[2, 2], [3, 3]]] }, { type: 'Polygon', coordinates: [ring] }, { type: 'MultiPolygon', coordinates: [[ring], [ring]] }));
  const gpx = toGPX(m);
  assert.equal(gpx.counts.waypoints, 2); assert.equal(gpx.counts.tracks, 1); assert.equal(gpx.counts.segments, 2); assert.equal(gpx.counts.trackPoints, 4);
  assert.deepEqual(gpx.dropped.find(d => d.label === 'Polygons'), { label: 'Polygons', count: 3, reason: gpx.dropped[0].reason });
  assert.match(gpx.dropped[0].reason, /no area shape/);
  assert.deepEqual(gpx.dropped.map(d => d.label), ['Polygons', 'Features with no usable geometry'].slice(0, 1));
  const polygonOnly = parseGeoJSON(fc({ type: 'Polygon', coordinates: [ring] }));
  assert.equal(exportable(polygonOnly).gpx, false);
  assert.throws(() => toGPX(polygonOnly), /no polygons/);
});

test('KML exports polygons with holes, MultiGeometry and escapes names', () => {
  const ring = [[0, 0], [4, 0], [4, 4], [0, 0]], hole = [[1, 1], [2, 1], [2, 2], [1, 1]];
  const m = parseGeoJSON(JSON.stringify({ type: 'Feature', properties: { name: 'A & <b>' }, geometry: { type: 'GeometryCollection', geometries: [{ type: 'Point', coordinates: [1, 2, 3] }, { type: 'Polygon', coordinates: [ring, hole] }] } }));
  const kml = toKML(m);
  assert.equal(kml.counts.polygons, 1); assert.equal(kml.counts.points, 1);
  const k = parseKML(kml.text, { Parser: XMLParser });
  assert.equal(k.counts.innerRings, 1); assert.equal(k.counts.multiGeometries, 1); assert.equal(k.placemarks[0].name, 'A & <b>');
  assert.equal(parseKML(toKML(parseGeoJSON(fc({ type: 'Polygon', coordinates: [ring, hole] }))).text, { Parser: XMLParser }).counts.polygons, 1);
});

test('exports write plain decimals, never exponents, and 180 as -180 in GPX', () => {
  const m = parseGeoJSON(fc({ type: 'Point', coordinates: [180, 1e-7] }));
  const gpx = toGPX(m);
  assert.match(gpx.text, /lat="0.0000001" lon="-180"/);
  assert.equal(parseGPX(gpx.text, XMLParser).issues.invalidCoordinates, 0);
});

test('newline-delimited GeoJSON is read; invalid JSON and non-GeoJSON are refused', () => {
  const m = parseGeoJSON('{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[1,2]}}\n\n{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[3,4]}}\n');
  assert.equal(m.format, 'newline-delimited JSON'); assert.equal(m.counts.features, 2);
  assert.throws(() => parseGeoJSON('{"type":'), /not valid JSON/);
  assert.throws(() => parseGeoJSON('{"type":"Feature"}\n{oops'), /Line 2/);
  assert.throws(() => parseGeoJSON('{"locations":[]}'), /no GeoJSON "type".*Timeline/);
  assert.throws(() => parseGeoJSON('[1,2]'), /no GeoJSON "type"/);
  assert.throws(() => parseGeoJSON('{"type":"Topology"}'), /not a GeoJSON object type/);
  assert.throws(() => parseGeoJSON('{"type":"FeatureCollection"}'), /no "features" array/);
  assert.throws(() => parseGeoJSON('  '), /empty/);
  assert.throws(() => parseGeoJSON(' '.repeat(10 * 1024 * 1024 + 1)), /10 MiB/);
  const many = Array.from({ length: 50001 }, (_, i) => [i % 180, 1]);
  assert.throws(() => parseGeoJSON(fc({ type: 'LineString', coordinates: many })), /50,000/);
});

test('property values stay text: markup in a name is returned as a string', () => {
  const m = parseGeoJSON(JSON.stringify({ type: 'Feature', properties: { name: '<img src=x onerror=alert(1)>' }, geometry: { type: 'Point', coordinates: [1, 2] } }));
  assert.equal(m.features[0].name, '<img src=x onerror=alert(1)>');
});
