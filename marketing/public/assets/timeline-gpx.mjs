// Convert only records accepted by inspectTimeline. Source path membership
// survives inspection so rejected components cannot be bridged by conversion.
export function timelineToGPX(model) {
 const streams=new Map(),endpoints=[];
 for(const p of model.points){
  if(p.stream===null){endpoints.push(p);continue;}
  if(!streams.has(p.stream))streams.set(p.stream,[]);
  streams.get(p.stream).push(p);
 }
 const segments=[];
 const longitude=n=>n===180?-180:n;
 for(const points of streams.values()){
  let part=[],previous=null;
  for(const p of points){
   const split=previous && (p.position!==previous.position+1 || p.time<previous.time || p.time-previous.time>600000 || (Math.abs(p.lon-previous.lon)>180 || Math.abs(longitude(p.lon)-longitude(previous.lon))>180) || new Date(p.time).toISOString().slice(0,10)!==new Date(previous.time).toISOString().slice(0,10));
   if(split){segments.push(part);part=[];}
   part.push(p);previous=p;
  }
  if(part.length)segments.push(part);
 }
 const iso=n=>new Date(n).toISOString();
 // GPX coordinates use decimal notation, not JavaScript exponent notation.
 const formatter=new Intl.NumberFormat('en-US',{useGrouping:false,maximumFractionDigits:15});
 const decimal=n=>formatter.format(n);
 const position=p=>`lat="${decimal(p.lat)}" lon="${decimal(longitude(p.lon))}"`;
 const waypoints=[
  ...model.visits.map(v=>`  <wpt ${position(v)}><time>${iso(v.start)}</time><name>Visit, source entry ${v.source}</name><desc>Visit end: ${iso(v.end)}. Not a route point.</desc><type>Visit</type></wpt>`),
  ...endpoints.map(p=>`  <wpt ${position(p)}><time>${iso(p.time)}</time><name>Activity endpoint, source entry ${p.source}</name><desc>Only an endpoint; no connecting path inferred.</desc><type>Activity endpoint</type></wpt>`)
 ];
 const tracks=segments.length?[
  '  <trk><name>Supported Timeline path points</name>',
  ...segments.map(part=>'    <trkseg>\n'+part.map(p=>`      <trkpt ${position(p)}><time>${iso(p.time)}</time></trkpt>`).join('\n')+'\n    </trkseg>'),
  '  </trk>'
 ]:[];
 return {
  xml:['<?xml version="1.0" encoding="UTF-8"?>','<gpx version="1.1" creator="Whereday local Timeline converter" xmlns="http://www.topografix.com/GPX/1/1">',
   '  <metadata><desc>Supported coordinate/time records only. Point order within each source path and duplicates retained. Path breaks at rejected components, backwards time, UTC day changes, gaps over ten minutes and longitude jumps over 180 degrees. Visits and activity endpoints are waypoints. No road matching or missing-path reconstruction.</desc></metadata>',
   ...waypoints,...tracks,'</gpx>',''].join('\n'),
  trackPoints:segments.reduce((sum,p)=>sum+p.length,0),segments:segments.length,
  visitWaypoints:model.visits.length,endpointWaypoints:endpoints.length
 };
}
