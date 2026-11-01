import assert from 'node:assert/strict';
import {test} from 'node:test';
import {readFile} from 'node:fs/promises';
import {DOMParser} from '@xmldom/xmldom';
import {inspectTimeline} from '../public/assets/timeline-core.mjs';
import {timelineToGPX} from '../public/assets/timeline-gpx.mjs';
const convert=root=>timelineToGPX(inspectTimeline(JSON.stringify(root)));
const raw=(lon,seconds=0)=>({latitudeE7:1,longitudeE7:lon*1e7,timestampMs:946728000000+seconds*1000});
const span={startTime:'2000-01-01T12:00:00Z',endTime:'2000-01-01T13:00:00Z'};
function xml(result){return new DOMParser().parseFromString(result.xml,'application/xml');}
test('three worked downloads match deterministic output and GPX element counts',async()=>{
 for(const [input,name,expected] of [['raw','raw',[2,1,0,0]],['semantic','semantic',[1,1,1,2]],['timeline','device',[4,4,2,0]]]){
  const text=await readFile(new URL(`../public/samples/synthetic-google-${input}.json`,import.meta.url),'utf8');
  const result=timelineToGPX(inspectTimeline(text)),d=xml(result);
  assert.deepEqual([result.trackPoints,result.segments,result.visitWaypoints,result.endpointWaypoints],expected);
  assert.equal(d.getElementsByTagName('trkpt').length,expected[0]);assert.equal(d.getElementsByTagName('trkseg').length,expected[1]);assert.equal(d.getElementsByTagName('wpt').length,expected[2]+expected[3]);
  assert.equal(result.xml,await readFile(new URL(`../public/samples/synthetic-google-${name}-converted.gpx`,import.meta.url),'utf8'));
 }
});
test('a rejected raw row or path component cannot connect its neighbors',()=>{
 const a=convert({locations:[raw(10),null,raw(10.002,2)]});assert.equal(a.segments,2);
 for(const bad of [null,{point:'geo:91,10',time:'2000-01-01T12:00:01Z'},{point:'geo:0,10'}, {point:'geo:0,10',time:'2000-01-01T14:00:00Z'}]){
  const b=convert([{...span,timelinePath:[{point:'geo:0,10',time:span.startTime},bad,{point:'geo:0,10.002',time:'2000-01-01T12:00:02Z'}]}]);assert.equal(b.trackPoints,2);assert.equal(b.segments,2);
 }
});
test('source paths, records and endpoints never acquire invented connections',()=>{
 const row={...span,activity:{start:'geo:0,10',end:'geo:0,11'},timelinePath:[{point:'geo:0,10.01',time:'2000-01-01T12:01:00Z'}]};
 const r=convert([row,row]);assert.equal(r.trackPoints,2);assert.equal(r.segments,2);assert.equal(r.endpointWaypoints,4);
 const s=convert({timelineObjects:[{activitySegment:{duration:{startTimestamp:span.startTime,endTimestamp:span.endTime},simplifiedRawPath:{points:[{latE7:0,lngE7:100000000,timestampMs:946728000000}]},waypointPath:{waypoints:[{latE7:0,lngE7:100000000,timestampMs:946728000000}]}}}]});assert.equal(s.segments,2);
});
test('time gap, backwards time, UTC date change and date line split segments',()=>{
 for(const locations of [[raw(10),raw(10,601)],[raw(10,1),raw(10,0)],[raw(179.9),raw(-179.9,1)],[raw(179.9),raw(180,1)],[raw(10,43199),raw(10,43201)]])assert.equal(convert({locations}).segments,2);
 assert.equal(convert({locations:[raw(10),raw(10,600)]}).segments,1);
});
test('source order, duplicates, offset times and visit end survive without private labels',()=>{
 const r=convert([{...span,visit:{topCandidate:{placeLocation:'geo:0,10',placeName:'<private & place>'}},timelinePath:[{point:'geo:0,10',durationMinutesOffsetFromStartTime:2},{point:'geo:0,10',durationMinutesOffsetFromStartTime:1},{point:'geo:0,10',durationMinutesOffsetFromStartTime:1}]}]);
 const d=xml(r);assert.equal(r.trackPoints,3);assert.equal(r.segments,2);assert.equal(r.visitWaypoints,1);assert.equal(d.getElementsByTagName('trkpt')[0].getElementsByTagName('time')[0].textContent,'2000-01-01T12:02:00.000Z');assert.match(r.xml,/Visit end: 2000-01-01T13:00:00.000Z/);assert.ok(!r.xml.includes('private'));
});
test('small coordinates use decimal notation, longitude boundary normalizes and invalid calendar years stay omitted',()=>{
 const r=convert({locations:[raw(180)]}),p=xml(r).getElementsByTagName('trkpt')[0];assert.equal(p.getAttribute('lat'),'0.0000001');assert.equal(p.getAttribute('lon'),'-180');
 assert.equal(convert({locations:[{...raw(1),timestampMs:undefined,timestamp:'0000-01-01T00:00:00Z'}]}).trackPoints,0);
 assert.equal(convert([{startTime:'9999-12-31T23:00:00-02:00',endTime:'9999-12-31T23:01:00-02:00',visit:{}}]).visitWaypoints,0);
});
test('empty or unusable recognized data produces no invented point',()=>{
 const r=convert({locations:[null]});assert.equal(r.trackPoints+r.visitWaypoints+r.endpointWaypoints,0);assert.equal(xml(r).getElementsByTagName('trk').length,0);
});
test('all 50,000 accepted points reach GPX, independent of plot and preview caps',()=>{
 const r=convert({locations:Array.from({length:50000},(_,i)=>raw(10,i))});assert.equal(r.trackPoints,50000);assert.equal((r.xml.match(/<trkpt /g)||[]).length,50000);
});
