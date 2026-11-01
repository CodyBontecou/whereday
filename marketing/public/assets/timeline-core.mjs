export const TIMELINE_MAX_BYTES = 10 * 1024 * 1024;
export const TIMELINE_MAX_RECORDS = 50000;
const object = x => x !== null && typeof x === 'object' && !Array.isArray(x);
const owns = (x, key) => Object.hasOwn(x, key);
const numeric = x => {
  if (typeof x !== 'number' && (typeof x !== 'string' || !/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(x.trim()))) return null;
  const n = Number(x); return Number.isFinite(n) ? n : null;
};
function date(text) {
  if (typeof text !== 'string') return null;
  const m = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.\d+)?(?:Z|[+-]\d\d:\d\d)$/.exec(text);
  if (!m) return null;
  const [, y, month, day, h, minute, second] = m.map(Number);
  const days = [31,y%4===0 && (y%100!==0 || y%400===0)?29:28,31,30,31,30,31,31,30,31,30,31];
  if (month<1 || month>12 || day<1 || day>days[month-1] || h>23 || minute>59 || second>59) return null;
  const n=Date.parse(text); if(!Number.isFinite(n))return null;
  const year=new Date(n).getUTCFullYear();return year>=1 && year<=9999 ? n : null;
}
function milliseconds(value) {
  const n=numeric(value); if(n===null || !Number.isFinite(new Date(n).getTime())) return null;
  const year=new Date(n).getUTCFullYear(); return year>=1 && year<=9999 ? n : null;
}
function coordinate(value) {
  if(typeof value==='string') value={latLng:value};
  if(!object(value)) return null;
  let lat,lon;
  const a=numeric(value.latitudeE7 ?? value.latE7), b=numeric(value.longitudeE7 ?? value.lngE7);
  if(a!==null && b!==null) {lat=a/1e7;lon=b/1e7;}
  else {
    const text=value.latLng ?? value.point;
    if(typeof text!=='string') return null;
    const m=/^\s*(?:geo:)?([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*°?\s*,\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+))\s*°?\s*$/.exec(text);
    if(!m)return null; lat=Number(m[1]);lon=Number(m[2]);
  }
  return Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180 ? {lat,lon}:null;
}

export function inspectTimeline(text) {
  if(new TextEncoder().encode(text).length>TIMELINE_MAX_BYTES)throw new Error('Choose an extracted JSON file of 10 MiB or less. ZIP archives are not supported.');
  let root;try{root=JSON.parse(text.replace(/^\uFEFF/,''));}catch{throw new Error('This is not readable JSON. Keep the original; extract a .json file from any ZIP archive first.');}
  const families={locations:'Raw location history',timelineObjects:'Semantic Timeline',semanticSegments:'Device Timeline'};
  let key,rows,rootArray=false;
  if(Array.isArray(root)) {
    rootArray=true;
    if(!root.some(r=>object(r)&&owns(r,'startTime')&&owns(r,'endTime')&&['visit','activity','timelinePath'].some(k=>owns(r,k))))throw new Error('This array does not contain a recognizable device Timeline record. An empty or unrelated array cannot identify an export.');
    key='semanticSegments';rows=root;
  } else if(object(root)) {
    const keys=Object.keys(families).filter(k=>owns(root,k));
    if(keys.length>1)throw new Error('More than one known record container is present. Mixed export structures are not supported by this inspector. Keep the original files separately.');
    if(!keys.length)throw new Error(owns(root,'visits')||owns(root,'points') ? 'This resembles a Whereday archive rather than one of the Google Timeline shapes inspected here.' : 'No recognized locations, timelineObjects or semanticSegments container was found. This may be another format; keep the original.');
    key=keys[0];rows=root[key];if(!Array.isArray(rows))throw new Error(`The ${key} container is not an array. Keep the original and check which file you selected.`);
  } else throw new Error('Choose a JSON object with a known record container or a recognizable device Timeline array.');
  if(rows.length>TIMELINE_MAX_RECORDS)throw new Error('This file exceeds 50,000 source entries. Choose a smaller original export.');
  const visits=[],points=[];
  const findings={nonObjectRows:0,invalidIntervals:0,invalidCoordinates:0,missingPointTime:0,invalidPointTime:0,outsideInterval:0,unsupportedRows:0,invalidContainers:0,offsetTimes:0};
  let examined=0;
  function budget(){if(++examined>TIMELINE_MAX_RECORDS)throw new Error('This file expands beyond 50,000 inspected records/components. Choose a smaller original export.');}
  function interval(start,end){if(start===null||end===null||end<start){findings.invalidIntervals++;return null;}return {start,end};}
  function visit(location,span,index){budget();if(!span)return;const c=coordinate(location);if(!c){findings.invalidCoordinates++;return;}visits.push({...c,start:span.start,end:span.end,source:index});}
  function point(location,time,index,span=null,timeProvided=false,stream=null,position=null){
    budget();const c=coordinate(location);
    if(!c){findings.invalidCoordinates++;return;}
    if(time===null){findings[timeProvided?'invalidPointTime':'missingPointTime']++;return;}
    if(span && (time<span.start||time>span.end)){findings.outsideInterval++;return;}
    points.push({...c,time,source:index,stream,position});
  }
  function path(list,span,index,kind){
    if(!Array.isArray(list)){findings.invalidContainers++;return;}
    if(list.length>TIMELINE_MAX_RECORDS)throw new Error('A nested path exceeds 50,000 entries. Choose a smaller original export.');
    for(const [position,p] of list.entries()){
      if(!object(p)){budget();findings.nonObjectRows++;continue;}
      let time=date(p.time)??date(p.timestamp)??milliseconds(p.timestampMs), offsetUsed=false;
      if(time===null && owns(p,'durationMinutesOffsetFromStartTime')) {
        const offset=numeric(p.durationMinutesOffsetFromStartTime);
        if(offset!==null && offset>=0){const n=span.start+offset*60000;if(Number.isFinite(new Date(n).getTime())){time=n;offsetUsed=true;}}
      }
      const before=points.length;
      point(p,time,index,span,['time','timestamp','timestampMs','durationMinutesOffsetFromStartTime'].some(k=>owns(p,k)),`${index}:${kind}`,position);
      if(offsetUsed&&points.length>before)findings.offsetTimes++;
    }
  }
  for(const [i,row] of rows.entries()){
    if(!object(row)){budget();findings.nonObjectRows++;continue;}
    const index=i+1;
    if(key==='locations'){
      point(row,milliseconds(row.timestampMs)??date(row.timestamp),index,null,owns(row,'timestampMs')||owns(row,'timestamp'),'raw',i);continue;
    }
    if(key==='timelineObjects'){
      if(owns(row,'placeVisit')){
        const v=row.placeVisit;
        if(!object(v)){budget();findings.invalidContainers++;continue;}
        const d=object(v.duration)?v.duration:{};
        visit(v.location,interval(date(d.startTimestamp)??milliseconds(d.startTimestampMs),date(d.endTimestamp)??milliseconds(d.endTimestampMs)),index);
      }else if(owns(row,'activitySegment')){
        const a=row.activitySegment;if(!object(a)){budget();findings.invalidContainers++;continue;}
        const d=object(a.duration)?a.duration:{};
        const span=interval(date(d.startTimestamp)??milliseconds(d.startTimestampMs),date(d.endTimestamp)??milliseconds(d.endTimestampMs));
        if(!span){budget();continue;}
        if(a.startLocation!=null)point(a.startLocation,span.start,index);
        if(a.endLocation!=null)point(a.endLocation,span.end,index);
        if(owns(a,'simplifiedRawPath'))path(a.simplifiedRawPath?.points,span,index,'simplifiedRawPath');
        if(owns(a,'waypointPath'))path(a.waypointPath?.waypoints,span,index,'waypointPath');
        if(!owns(a,'startLocation')&&!owns(a,'endLocation')&&!owns(a,'simplifiedRawPath')&&!owns(a,'waypointPath')){budget();findings.unsupportedRows++;}
      }else{budget();findings.unsupportedRows++;}
      continue;
    }
    const span=interval(date(row.startTime),date(row.endTime));
    if(!span){budget();continue;}
    let found=false;
    if(owns(row,'visit')){
      found=true;const v=row.visit;
      if(!object(v)){budget();findings.invalidContainers++;}
      else{const candidate=object(v.topCandidate)?v.topCandidate:{};visit(candidate.placeLocation,span,index);}
    }
    if(owns(row,'activity')){
      found=true;const a=row.activity;
      if(!object(a)){budget();findings.invalidContainers++;}
      else {if(a.start!=null)point(a.start,span.start,index);if(a.end!=null)point(a.end,span.end,index);if(a.start==null&&a.end==null){budget();findings.unsupportedRows++;}}
    }
    if(owns(row,'timelinePath')){found=true;path(row.timelinePath,span,index,'timelinePath');}
    if(!found){budget();findings.unsupportedRows++;}
  }
  const instants=[...visits.flatMap(v=>[v.start,v.end]),...points.map(p=>p.time)];
  let first=null,last=null;for(const n of instants){first=first===null?n:Math.min(first,n);last=last===null?n:Math.max(last,n);}
  return {family:families[key],container:rootArray?'top-level device-style array':key,sourceEntries:rows.length,visits,points,findings,
    firstTime:first===null?null:new Date(first).toISOString(),lastTime:last===null?null:new Date(last).toISOString(),
    plotPoints:[...visits.map(v=>({lat:v.lat,lon:v.lon})),...points.map(p=>({lat:p.lat,lon:p.lon}))]};
}
