import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {inspectTimeline,TIMELINE_MAX_BYTES,TIMELINE_MAX_RECORDS} from '../public/assets/timeline-core.mjs';
const fixture=async name=>readFile(new URL('../public/samples/'+name,import.meta.url),'utf8');
const device=await fixture('synthetic-google-timeline.json');
const raw=await fixture('synthetic-google-raw.json');
const semantic=await fixture('synthetic-google-semantic.json');
const span={startTime:'2020-01-01T00:00:00Z',endTime:'2020-01-01T01:00:00Z'};
const inspect=root=>inspectTimeline(JSON.stringify(root));
test('original device example keeps raw duplicates, provided offsets and invalid interval',()=>{
 const m=inspectTimeline(device);assert.equal(m.family,'Device Timeline');assert.equal(m.sourceEntries,3);assert.equal(m.visits.length,2);assert.equal(m.points.length,4);assert.equal(m.findings.invalidIntervals,1);assert.equal(m.findings.offsetTimes,2);
 assert.equal(m.firstTime,'2020-01-01T00:00:00.000Z');assert.equal(m.lastTime,'2020-01-01T01:00:00.000Z');assert.equal(m.points[1].time,Date.parse('2020-01-01T00:30:00Z'));
});
test('raw example converts E7 and milliseconds, counts invalid coordinate',()=>{
 const m=inspectTimeline(raw);assert.equal(m.visits.length,0);assert.equal(m.points.length,2);assert.deepEqual([m.points[0].lat,m.points[0].lon],[0,10]);assert.equal(m.firstTime,'2000-01-01T12:00:00.000Z');assert.equal(m.findings.invalidCoordinates,1);
});
test('semantic example keeps endpoints/timed path and rejects an untimed waypoint',()=>{
 const m=inspectTimeline(semantic);assert.equal(m.visits.length,1);assert.equal(m.points.length,3);assert.equal(m.findings.missingPointTime,1);assert.equal(m.findings.unsupportedRows,1);assert.equal(m.lastTime,'2000-01-01T12:20:00.000Z');
});
test('device array needs recognizable record evidence',()=>{
 const rows=JSON.parse(device).semanticSegments;assert.equal(inspect(rows).container,'top-level device-style array');
 for(const root of [[],[{}],[1,2,3],[{startTime:'a',endTime:'b'}]])assert.throws(()=>inspect(root),/recognizable/);
});
test('known empty containers are identified without implying available history',()=>{
 const m=inspect({locations:[]});assert.equal(m.family,'Raw location history');assert.equal(m.firstTime,null);assert.equal(m.points.length,0);
});
test('mixed containers, wrong types, Whereday archives and unrelated JSON are explicit failures',()=>{
 for(const root of [{locations:[],semanticSegments:[]},{locations:{}},{visits:[]},{type:'FeatureCollection',features:[]},null])assert.throws(()=>inspect(root));
 assert.throws(()=>inspectTimeline('{'),/not readable JSON/);
});
test('provided minute offsets are used only within a valid parent interval',()=>{
 const m=inspect([{...span,timelinePath:[{point:'geo:0,10',durationMinutesOffsetFromStartTime:'5'},{point:'geo:0,10',durationMinutesOffsetFromStartTime:65},{point:'geo:0,10',durationMinutesOffsetFromStartTime:-1},{point:'geo:0,10',durationMinutesOffsetFromStartTime:true}]}]);
 assert.equal(m.points.length,1);assert.equal(m.findings.offsetTimes,1);assert.equal(m.findings.outsideInterval,1);assert.equal(m.findings.invalidPointTime,2);
});
test('boolean numeric fields and out-of-range coordinates are not positions or dates',()=>{
 const m=inspect({locations:[{latitudeE7:true,longitudeE7:0,timestampMs:1},{latitudeE7:0,longitudeE7:0,timestampMs:true},{latitudeE7:910000000,longitudeE7:0,timestampMs:1}]});
 assert.equal(m.points.length,0);assert.equal(m.findings.invalidCoordinates,2);assert.equal(m.findings.invalidPointTime,1);
});
test('string coordinates, degree signs, milliseconds and timezone offsets work',()=>{
 const m=inspect([{startTime:'2020-01-01T00:00:00-04:00',endTime:'2020-01-01T01:00:00-04:00',visit:{topCandidate:{placeLocation:'0°, 10°'}},timelinePath:[{point:'geo:0.001,10.001',timestampMs:Date.parse('2020-01-01T04:30:00Z')}]}]);
 assert.equal(m.visits.length,1);assert.equal(m.points.length,1);assert.equal(m.firstTime,'2020-01-01T04:00:00.000Z');assert.equal(m.lastTime,'2020-01-01T05:00:00.000Z');
});
test('invalid/reversed intervals and impossible calendar dates do not create records',()=>{
 const m=inspect([{...span,startTime:'2020-02-31T00:00:00Z',visit:{}},{...span,endTime:'2019-01-01T00:00:00Z',timelinePath:[]}]);assert.equal(m.findings.invalidIntervals,2);assert.equal(m.plotPoints.length,0);
});
test('unsupported rows and malformed nested arrays are counted without guessing',()=>{
 const m=inspect({semanticSegments:[{...span,timelinePath:{}},{...span,unrecognized:true},null]});assert.equal(m.findings.invalidContainers,1);assert.equal(m.findings.unsupportedRows,1);assert.equal(m.findings.nonObjectRows,1);
});
test('byte, source-entry and nested component limits are enforced',()=>{
 assert.throws(()=>inspectTimeline(' '.repeat(TIMELINE_MAX_BYTES+1)),/10 MiB/);
 assert.throws(()=>inspect({locations:Array(TIMELINE_MAX_RECORDS+1).fill(null)}),/50,000/);
 assert.throws(()=>inspect([{...span,timelinePath:Array(TIMELINE_MAX_RECORDS+1).fill({point:'geo:0,10',durationMinutesOffsetFromStartTime:1})}]),/50,000/);
});
test('a JSON prototype key is data, not an object-prototype change',()=>{
 inspectTimeline('{"locations":[],"__proto__":{"timelinePolluted":true}}');assert.equal({}.timelinePolluted,undefined);
});
