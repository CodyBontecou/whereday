import {inspectTimeline,TIMELINE_MAX_BYTES} from './timeline-core.mjs?v=20261030-gpx';
import {TIMELINE_EXAMPLES} from './timeline-demo.mjs';
import {plotPaths} from './gpx-core.mjs';
import {timelineToGPX} from './timeline-gpx.mjs?v=20261030-gpx';
const $=id=>document.getElementById(id);
let generation=0,model=null,converted=null;
const number=new Intl.NumberFormat('en-US');
const count=(n,label)=>`${number.format(n)} ${label}${n===1?'':'s'}`;
function clear(){
 model=null;converted=null;$('timeline-download').disabled=true;$('timeline-gpx-summary').textContent='';$('timeline-file').value='';$('timeline-results').hidden=true;
 for(const id of ['timeline-name','timeline-family','timeline-counts','timeline-dates','timeline-plot-note','timeline-preview-note'])$(id).textContent='';
 for(const id of ['timeline-findings','timeline-plot','timeline-rows'])$(id).replaceChildren();
}
function text(tag,value){const node=document.createElement(tag);node.textContent=value;return node;}
function open(json,label){
 clear();
 try{
  model=inspectTimeline(json);
  converted=timelineToGPX(model);
  $('timeline-download').disabled=!(converted.trackPoints+converted.visitWaypoints+converted.endpointWaypoints);
  $('timeline-gpx-summary').textContent=`GPX output: ${count(converted.trackPoints,'track point')} in ${count(converted.segments,'segment')} · ${count(converted.visitWaypoints,'visit waypoint')} · ${count(converted.endpointWaypoints,'activity endpoint waypoint')}. Unsupported or unusable records are omitted. Check the conversion rules below before sharing.`;
  $('timeline-name').textContent=label;
  $('timeline-family').textContent=`Matches ${model.family} shape · ${model.container}`;
  $('timeline-counts').textContent=`${number.format(model.sourceEntries)} source entries · ${number.format(model.visits.length)} visit record${model.visits.length===1?'':'s'} with usable coordinates/times · ${number.format(model.points.length)} timed point record${model.points.length===1?'':'s'}`;
  $('timeline-dates').textContent=model.firstTime?`Checked records span ${model.firstTime} to ${model.lastTime} (UTC). This is not proof that the archive covers every day in between.`:'No usable supported coordinate/time records were found. A recognized container can still be empty or contain unsupported fields.';
  const labels={nonObjectRows:'Non-object entries or path components',invalidIntervals:'Missing, invalid or reversed intervals',invalidCoordinates:'Components with unusable coordinates',missingPointTime:'Coordinate points without a time',invalidPointTime:'Coordinate points with unusable time fields',outsideInterval:'Path points outside their parent interval',unsupportedRows:'Unsupported rows or empty activities',invalidContainers:'Unexpected nested container types',offsetTimes:'Usable point times derived from explicit minute offsets'};
  for(const [key,label] of Object.entries(labels))if(model.findings[key])$('timeline-findings').append(text('li',`${label}: ${number.format(model.findings[key])}.`));
  if(!$('timeline-findings').children.length)$('timeline-findings').append(text('li','No findings in the fields checked. This is not a complete schema or native-import validation.'));
  const plotModel={points:model.plotPoints,groups:model.plotPoints.map(p=>({points:[p]}))};
  const svg=$('timeline-plot');
  for(const path of plotPaths(plotModel))for(const p of path){const dot=document.createElementNS(svg.namespaceURI,'circle');dot.setAttribute('cx',p[0]);dot.setAttribute('cy',p[1]);dot.setAttribute('r','3.5');dot.setAttribute('class','gpx-dot');svg.append(dot);}
  $('timeline-plot-note').textContent=model.plotPoints.length?'North is up. Up to 2,000 locations are drawn without streets or map tiles. Repeated coordinates overlap; counts above refer to records. Dots are not connected into an inferred route.':'No usable supported locations to plot.';
  const records=[...model.visits.map(v=>({...v,kind:'Visit',time:v.start})),...model.points.map(p=>({...p,kind:'Point'}))].sort((a,b)=>a.time-b.time).slice(0,20);
  for(const r of records){const row=document.createElement('tr');for(const value of [r.source,r.kind,`${r.lat}, ${r.lon}`,new Date(r.time).toISOString(),r.end===undefined?'—':new Date(r.end).toISOString()])row.append(text('td',value));$('timeline-rows').append(row);}
  $('timeline-preview-note').textContent=`First ${records.length} checked records ordered by start or point time. Source entry is a 1-based index in the detected array, not a line number. Repeated records are retained.`;
  $('timeline-results').hidden=false;$('timeline-status').textContent=`Opened ${label} locally. File contents were not uploaded.`;
 }catch(error){clear();$('timeline-status').textContent=error.message;}
}
$('timeline-file').addEventListener('change',async e=>{
 const file=e.target.files[0];if(!file)return;const token=++generation;clear();$('timeline-status').textContent='Reading JSON on this device…';
 if(file.size>TIMELINE_MAX_BYTES){$('timeline-status').textContent='Choose an extracted JSON file of 10 MiB or less. ZIP archives are not supported.';return;}
 try{const json=await file.text();if(token===generation)open(json,file.name);}catch{if(token===generation)$('timeline-status').textContent='The file could not be read. Try an extracted local copy first.';}
});
$('timeline-demo').addEventListener('click',()=>{generation++;const key=$('timeline-example').value;open(TIMELINE_EXAMPLES[key],`Synthetic ${key} example`);});
$('timeline-clear').addEventListener('click',()=>{generation++;clear();$('timeline-status').textContent='Cleared the file and its results from this page. Your original file is unchanged.';});
$('timeline-download').addEventListener('click',()=>{
 if(!converted || $('timeline-download').disabled)return;
 const url=URL.createObjectURL(new Blob([converted.xml],{type:'application/gpx+xml'}));
 const a=document.createElement('a');a.href=url;a.download='timeline-converted.gpx';a.click();
 setTimeout(()=>URL.revokeObjectURL(url),1000);
 $('timeline-status').textContent='GPX download prepared locally. Keep the original JSON; the GPX is a partial coordinate/time conversion, not a complete Timeline archive.';
});
