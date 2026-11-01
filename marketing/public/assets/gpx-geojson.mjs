// Selected GPX point fields only. Geometry is 2D; time/elevation arrays are custom properties.
export function geoJSONFeatureCollection(model) {
  const features = [];
  for (const group of model.groups) {
    let chunk = [], part = 0;
    function flush() {
      if (!chunk.length) return;
      const coordinates = chunk.map(p => [p.lon, p.lat]);
      features.push({
        type: 'Feature',
        geometry: { type: chunk.length === 1 ? 'Point' : 'LineString', coordinates: chunk.length === 1 ? coordinates[0] : coordinates },
        properties: {
          kind: group.kind, group: group.index, part: ++part,
          point_numbers: chunk.map(p => p.index),
          times_utc: chunk.map(p => p.time === null ? null : new Date(p.time).toISOString()),
          original_times: chunk.map(p => p.rawTime || null),
          elevations_m: chunk.map(p => p.elevation),
          point_names: chunk.map(p => p.name || null),
        },
      });
      chunk = [];
    }
    for (const p of group.points) {
      if (!p) { flush(); continue; }
      // Split at the antimeridian without inventing boundary coordinates or a connection across the map.
      if (chunk.length && Math.abs(p.lon - chunk.at(-1).lon) > 180) flush();
      chunk.push(p);
    }
    flush();
  }
  return { type: 'FeatureCollection', features };
}

export const toGeoJSON = model => JSON.stringify(geoJSONFeatureCollection(model), null, 2) + '\n';
