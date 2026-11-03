// Developer-only generator. The normal site build verifies saved samples with Python.
import { readFile, writeFile } from 'node:fs/promises';
import { DOMParser } from '@xmldom/xmldom';
import { parseGPX, toCSV } from '../public/assets/gpx-core.mjs';
const directory = new URL('../public/samples/', import.meta.url);
const source = await readFile(new URL('synthetic-format-trip.gpx', directory), 'utf8');
const model = parseGPX(source, DOMParser);
if (model.points.length !== 5 || model.segments !== 2) throw new Error('Expected the fixed five-point/two-segment example.');
const groups = model.groups.filter(g => g.kind === 'track');
const geojson = { type: 'FeatureCollection', features: groups.map(g => ({ type: 'Feature',
 properties: { synthetic: true, segment: g.index, times: g.points.map(p => p.rawTime) },
 geometry: { type: 'LineString', coordinates: g.points.map(p => [p.lon, p.lat]) }
})) };
const tracks = groups.map(g => '      <gx:Track>\n' +
 g.points.map(p => `        <when>${p.rawTime}</when>\n`).join('') +
 g.points.map(p => `        <gx:coord>${p.lon} ${p.lat} 0</gx:coord>\n`).join('') +
 '      </gx:Track>\n').join('');
const kml = '<?xml version="1.0" encoding="UTF-8"?>\n' +
 '<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">\n' +
 '  <Document><name>Invented five-point format comparison</name>\n' +
 '    <description>Guide example, not a native-app export. Ground-clamped; coordinate altitude zeros are placeholders, not measured elevation.</description>\n' +
 '    <Placemark><name>Invented trip</name><gx:MultiTrack>\n' +
 '      <altitudeMode>clampToGround</altitudeMode><gx:interpolate>0</gx:interpolate>\n' + tracks +
 '    </gx:MultiTrack></Placemark>\n  </Document>\n</kml>\n';
for (const [name, body] of [['synthetic-format-trip.geojson', JSON.stringify(geojson, null, 2) + '\n'], ['synthetic-format-trip.kml', kml], ['synthetic-format-trip.csv', toCSV(model)]]) {
 const url = new URL(name, directory);
 if (process.argv.includes('--check')) {
  if (await readFile(url, 'utf8') !== body) throw new Error(`Sample drift: ${name}`);
 } else await writeFile(url, body);
}
console.log(process.argv.includes('--check') ? 'Format samples match their generator and browser CSV output.' : 'Wrote three derived synthetic format samples.');
