// Minimal local ZIP reader for KMZ files. Stored (0) and deflate (8) entries only; no library, no network.
// Reusable: readKmz(bytes) returns the chosen KML text plus the other entries' names and sizes.
export const MAX_ENTRY_BYTES = 10 * 1024 * 1024;
const u16 = (v, o) => v.getUint16(o, true);
const u32 = (v, o) => v.getUint32(o, true);

export function isZip(bytes) {
  return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && (bytes[2] === 3 || bytes[2] === 5) && (bytes[3] === 4 || bytes[3] === 6);
}

let table;
export function crc32(bytes) {
  if (!table) { table = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; table[n] = c >>> 0; } }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) crc = table[(crc ^ bytes[i]) & 255] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

// Reads the central directory (PKWARE APPNOTE 4.3.12). Throws plain-language errors.
export function listEntries(bytes) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) if (u32(view, i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('This looks like a zip file, but its directory could not be found. It may be damaged or cut short.');
  if (eocd >= 20 && u32(view, eocd - 20) === 0x07064b50) throw new Error('This KMZ uses ZIP64, which this viewer does not support. Re-save it as a standard KMZ, or unzip it and open the doc.kml inside.');
  const disk = u16(view, eocd + 4), startDisk = u16(view, eocd + 6), total = u16(view, eocd + 10), size = u32(view, eocd + 12), offset = u32(view, eocd + 16);
  if (total === 0xFFFF || size === 0xFFFFFFFF || offset === 0xFFFFFFFF) throw new Error('This KMZ uses ZIP64, which this viewer does not support. Re-save it as a standard KMZ, or unzip it and open the doc.kml inside.');
  if (disk !== 0 || startDisk !== 0) throw new Error('This zip is split across several files, which this viewer does not support.');
  const entries = [];
  let p = offset;
  for (let n = 0; n < total; n++) {
    if (p + 46 > bytes.length || u32(view, p) !== 0x02014b50) throw new Error('This zip’s directory is damaged. Export the KMZ again.');
    const flags = u16(view, p + 8), method = u16(view, p + 10), crc = u32(view, p + 16), compressed = u32(view, p + 20), size = u32(view, p + 24);
    const nameLength = u16(view, p + 28), extraLength = u16(view, p + 30), commentLength = u16(view, p + 32), local = u32(view, p + 42);
    if (p + 46 + nameLength > bytes.length) throw new Error('This zip’s directory is damaged. Export the KMZ again.');
    const name = new TextDecoder('utf-8').decode(bytes.subarray(p + 46, p + 46 + nameLength));
    entries.push({ name, flags, method, crc, compressed, size, local, encrypted: Boolean(flags & 1) || Boolean(flags & 0x40), directory: name.endsWith('/') });
    p += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

// Google: use doc.kml if present, otherwise the first .kml at the root of the archive.
export function chooseKml(entries) {
  const files = entries.filter(e => !e.directory);
  return files.find(e => e.name === 'doc.kml') || files.find(e => !e.name.includes('/') && e.name.toLowerCase().endsWith('.kml')) || null;
}

async function inflateRaw(data, limit) {
  if (typeof DecompressionStream !== 'function') throw new Error('This browser cannot unzip files (no DecompressionStream). Try a current Safari, Chrome, Edge or Firefox, or unzip the KMZ and open the doc.kml.');
  const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  const reader = stream.getReader();
  const chunks = []; let length = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.length;
    if (length > limit) { await reader.cancel(); throw new Error('A file inside this KMZ is larger than 10 MiB uncompressed.'); }
    chunks.push(value);
  }
  const out = new Uint8Array(length); let at = 0;
  for (const chunk of chunks) { out.set(chunk, at); at += chunk.length; }
  return out;
}

export async function extractEntry(bytes, entry) {
  if (entry.encrypted) throw new Error('This KMZ is encrypted (password-protected), which this viewer does not support.');
  if (entry.method !== 0 && entry.method !== 8) throw new Error(`This KMZ uses an unsupported compression method (${entry.method}). Only stored and deflate are supported.`);
  if (entry.compressed === 0xFFFFFFFF || entry.size === 0xFFFFFFFF || entry.local === 0xFFFFFFFF) throw new Error('This KMZ uses ZIP64, which this viewer does not support. Re-save it as a standard KMZ, or unzip it and open the doc.kml inside.');
  if (entry.size > MAX_ENTRY_BYTES) throw new Error('A file inside this KMZ is larger than 10 MiB uncompressed.');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const at = entry.local;
  if (at + 30 > bytes.length || u32(view, at) !== 0x04034b50) throw new Error('This zip is damaged: an entry header is missing. Export the KMZ again.');
  const start = at + 30 + u16(view, at + 26) + u16(view, at + 28);
  if (start + entry.compressed > bytes.length) throw new Error('This zip is cut short. Export the KMZ again.');
  const raw = bytes.subarray(start, start + entry.compressed);
  const data = entry.method === 0 ? raw.slice() : await inflateRaw(raw, MAX_ENTRY_BYTES);
  if (data.length !== entry.size) throw new Error('This zip is damaged: an entry has the wrong size. Export the KMZ again.');
  if (crc32(data) !== entry.crc) throw new Error('This zip is damaged: an entry failed its checksum. Export the KMZ again.');
  return data;
}

export async function readKmz(bytes) {
  const entries = listEntries(bytes);
  const files = entries.filter(e => !e.directory);
  const kml = chooseKml(entries);
  if (!kml) throw new Error('This KMZ has no doc.kml and no .kml file at its top level, so there is nothing to open.');
  const text = new TextDecoder('utf-8').decode(await extractEntry(bytes, kml));
  return { kmlName: kml.name, kmlText: text, kmlCount: files.filter(e => e.name.toLowerCase().endsWith('.kml')).length, others: files.filter(e => e !== kml).map(e => ({ name: e.name, size: e.size })) };
}
