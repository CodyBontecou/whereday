import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {DOMParser} from '@xmldom/xmldom';
import {parseGPX} from '../public/assets/gpx-core.mjs';
import {geoJSONFeatureCollection,toGeoJSON} from '../public/assets/gpx-geojson.mjs';
const parse=s=>parseGPX(s,DOMParser);
const point=(lat,lon,body='')=>`<trkpt lat="${lat}" lon="${lon}">${body}</trkpt>`;
const track=s=>`<gpx version="1.1"><trk><trkseg>${s}</trkseg></trk></gpx>`;
const example=await readFile(new URL('../public/samples/synthetic-app-export.gpx',import.meta.url),'utf8');
test('GeoJSON keeps all five demo positions, two segments and aligned UTC/elevation fields',()=>{
 const m=parse(example),before=JSON.stringify(m),out=JSON.parse(toGeoJSON(m));
 assert.equal(out.type,'FeatureCollection');assert.deepEqual(out.features.map(f=>f.geometry.type),['LineString','LineString']);
 assert.deepEqual(out.features.map(f=>f.geometry.coordinates.length),[3,2]);
 assert.deepEqual(out.features[0].properties.times_utc,['2000-01-01T12:00:00.000Z','2000-01-01T12:01:00.000Z','2000-01-01T12:02:00.000Z']);
 assert.deepEqual(out.features[1].properties.elevations_m,[3,4]);assert.deepEqual(out.features[1].properties.point_numbers,[1,2]);assert.equal(JSON.stringify(m),before);
 assert.ok(out.features.flatMap(f=>f.geometry.coordinates).every(p=>p.length===2));
});
test('GeoJSON uses longitude first, preserves negative values and normalizes offset times',()=>{
 const f=geoJSONFeatureCollection(parse(track(point(-10,70,'<time>2000-01-01T08:00:00-04:00</time><ele>-3</ele>')))).features[0];
 assert.deepEqual(f.geometry,{type:'Point',coordinates:[70,-10]});assert.deepEqual(f.properties.times_utc,['2000-01-01T12:00:00.000Z']);assert.deepEqual(f.properties.original_times,['2000-01-01T08:00:00-04:00']);assert.deepEqual(f.properties.elevations_m,[-3]);
});
test('invalid-coordinate gaps create parts with original point numbering, never bridge a gap',()=>{
 const out=geoJSONFeatureCollection(parse(track(point(0,10)+point(91,10)+point(1,11)+point(2,12))));
 assert.deepEqual(out.features.map(f=>f.geometry.type),['Point','LineString']);assert.deepEqual(out.features.map(f=>f.properties.part),[1,2]);assert.deepEqual(out.features.map(f=>f.properties.point_numbers),[[1],[3,4]]);
 assert.deepEqual(out.features[1].geometry.coordinates,[[11,1],[12,2]]);
});
test('dateline crossings split both directions without invented vertices or time interpolation',()=>{
 const positions=[[0,178],[1,179],[2,-179],[3,-178],[4,179]];
 const out=geoJSONFeatureCollection(parse(track(positions.map(([a,b])=>point(a,b)).join(''))));
 assert.deepEqual(out.features.map(f=>f.geometry.type),['LineString','LineString','Point']);
 assert.deepEqual(out.features.flatMap(f=>f.geometry.type==='Point'?[f.geometry.coordinates]:f.geometry.coordinates),positions.map(([a,b])=>[b,a]));
});
test('routes, multiple tracks and independent waypoints remain separate',()=>{
 const m=parse('<gpx version="1.0"><trk><trkseg><trkpt lat="0" lon="10"/></trkseg></trk><trk><trkseg><trkpt lat="1" lon="11"/></trkseg></trk><rte><rtept lat="2" lon="12"/><rtept lat="3" lon="13"/></rte><wpt lat="4" lon="14"/><wpt lat="5" lon="15"/></gpx>');
 const f=geoJSONFeatureCollection(m).features;assert.deepEqual(f.map(x=>x.properties.kind),['track','track','route','waypoint','waypoint']);assert.deepEqual(f.map(x=>x.properties.group),[1,2,3,4,5]);assert.deepEqual(f.map(x=>x.geometry.type),['Point','Point','LineString','Point','Point']);
});
test('duplicates/order survive and missing or invalid optional values stay aligned as null',()=>{
 const f=geoJSONFeatureCollection(parse(track(point(0,10,'<time>not a time</time><ele>bad</ele>')+point(0,10)))).features[0];
 assert.equal(f.geometry.coordinates.length,2);assert.deepEqual(f.properties.times_utc,[null,null]);assert.deepEqual(f.properties.original_times,['not a time',null]);assert.deepEqual(f.properties.elevations_m,[null,null]);assert.deepEqual(f.properties.point_names,[null,null]);
});
test('user point names remain JSON data and extensions/track names do not enter output',()=>{
 const m=parse('<gpx version="1.1"><metadata><name>private metadata</name></metadata><trk><name>private track</name><trkseg>'+point(0,10,'<name>&lt;/script&gt; =1 &quot;quoted&quot;</name><extensions><speed>999</speed></extensions>')+'</trkseg></trk></gpx>');
 const text=toGeoJSON(m),out=JSON.parse(text);assert.equal(out.features[0].properties.point_names[0],'</script> =1 "quoted"');assert.ok(!text.includes('private metadata')&&!text.includes('private track')&&!text.includes('999'));assert.equal({}.polluted,undefined);
});
test('empty/invalid-only groups emit an empty collection and large arrays are not display-sampled',()=>{
 assert.deepEqual(geoJSONFeatureCollection(parse(track(point(91,10)))),{type:'FeatureCollection',features:[]});
 const out=geoJSONFeatureCollection(parse(track(point(0,10).repeat(50000))));assert.equal(out.features[0].geometry.coordinates.length,50000);assert.equal(out.features[0].properties.point_numbers.at(-1),50000);
});

test('public worked-example download matches the actual converter output exactly',async()=>{
 const saved=await readFile(new URL('../public/samples/synthetic-gpx-converted.geojson',import.meta.url),'utf8');assert.equal(saved,toGeoJSON(parse(example)));
});
