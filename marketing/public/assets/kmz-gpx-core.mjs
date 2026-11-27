// KMZ to GPX and GPX to KMZ, locally. Glue only: ZIP reading is kmz-zip.mjs, XML conversion is kml-gpx-core.mjs,
// KMZ writing is kml-csv-core.mjs. The page picks the direction from the bytes, never from the file name.
import { readKmz, isZip } from './kmz-zip.mjs';
import { kmlToGPX, gpxToKML, detectFormat, baseName, MAX_BYTES, MAX_POINTS } from './kml-gpx-core.mjs';
import { buildKmz } from './kml-csv-core.mjs';

export { MAX_BYTES, MAX_POINTS, isZip };
const decode = bytes => new TextDecoder('utf-8').decode(bytes);
const IMAGE = /\.(png|jpe?g|gif|webp|bmp|tiff?|svg|ico)$/i;

// What was in the KMZ besides the KML that was read.
export function describeEntries(kmlName, others) {
  const kmls = others.filter(o => /\.kml$/i.test(o.name));
  const images = others.filter(o => IMAGE.test(o.name));
  const rest = others.filter(o => !kmls.includes(o) && !images.includes(o));
  return { kmlUsed: kmlName, otherKmls: kmls.length, images: images.length, otherFiles: rest.length };
}

// Skipped-list entries for the archive, in the same shape as the converter's own skipped list.
export function archiveSkipped(files) {
  const out = [];
  if (files.otherKmls) out.push({ key: 'otherKmls', label: 'Other KML files inside the KMZ', count: files.otherKmls, reason: `Only ${files.kmlUsed} was converted. Google’s rule is doc.kml, or else the first .kml at the top level.` });
  if (files.images) out.push({ key: 'images', label: 'Images inside the KMZ', count: files.images, reason: 'Icons, overlays and photos are not read; GPX has no place for them.' });
  if (files.otherFiles) out.push({ key: 'otherFiles', label: 'Other files inside the KMZ', count: files.otherFiles, reason: 'Not read.' });
  return out;
}

// ZIP signature means KMZ; otherwise the XML root element decides (kml or gpx). The file name is never used.
export function detectBytes(bytes, Parser = globalThis.DOMParser) {
  return isZip(bytes) ? 'kmz' : detectFormat(decode(bytes), Parser);
}

// Returns { direction, source, files?, input, output, skipped, notes, firstTime, lastTime, outputs: [{ kind, filename, mime, bytes|text }] }.
export async function convertBytes(bytes, name, { Parser = globalThis.DOMParser, keepTimestamps = true } = {}) {
  if (!bytes.length) throw new Error('This file is empty. Export it again from the app that made it.');
  if (bytes.length > MAX_BYTES) throw new Error('This file is over 10 MiB. Export a smaller file and try again.');
  const base = baseName(name);
  const kind = detectBytes(bytes, Parser);
  if (kind === 'kmz') {
    const kmz = await readKmz(bytes);
    const files = describeEntries(kmz.kmlName, kmz.others);
    const r = kmlToGPX(kmz.kmlText, Parser);
    return {
      ...r, direction: 'kmz-to-gpx', source: 'KMZ', files, skipped: [...archiveSkipped(files), ...r.skipped],
      outputs: [{ kind: 'gpx', filename: `${base}.gpx`, mime: r.mime, text: r.text }],
    };
  }
  const text = decode(bytes);
  if (kind === 'gpx') {
    const r = gpxToKML(text, Parser, base, keepTimestamps);
    return {
      ...r, direction: 'gpx-to-kmz', source: 'GPX',
      outputs: [
        { kind: 'kmz', filename: `${base}.kmz`, mime: 'application/vnd.google-earth.kmz', bytes: buildKmz(r.text) },
        { kind: 'kml', filename: `${base}.kml`, mime: 'application/vnd.google-earth.kml+xml', text: r.text },
      ],
    };
  }
  const r = kmlToGPX(text, Parser);
  return { ...r, direction: 'kml-to-gpx', source: 'KML', outputs: [{ kind: 'gpx', filename: `${base}.gpx`, mime: r.mime, text: r.text }] };
}
