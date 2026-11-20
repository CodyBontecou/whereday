import {
  MAX_BYTES, boundaries, readLocationFile, analyze, summarize, emptyManual, toggleManual,
  sortRows, tableCSV, mapSVG, pngSize, ruleText, dayText, countryName,
} from './visited-countries-core.mjs';
import { VISITED_DEMO } from './visited-countries-demo.mjs';

const $ = id => document.getElementById(id);
const number = new Intl.NumberFormat('en-US');
const plural = (n, one, many = one + 's') => `${number.format(n)} ${n === 1 ? one : many}`;
const el = (tag, text, className) => { const e = document.createElement(tag); if (text !== undefined) e.textContent = text; if (className) e.className = className; return e; };
let files = [], manual = emptyManual(), nextId = 1, generation = 0, sortKey = 'name', summary = null, urls = [];
const b = boundaries();

function update(announce) {
  const ok = files.filter(f => f.analysis);
  summary = summarize(ok.map(f => f.analysis), manual, b);
  const any = ok.length || manual.added.size || manual.removed.size;
  $('vc-results').hidden = !any && !files.length;
  renderFiles();
  if (!any) { $('vc-results').hidden = true; setDownloads(false); renderAdd(); if (announce) $('vc-status').textContent = announce; return; }
  $('vc-results').hidden = false;
  $('vc-count').textContent = plural(summary.count, 'country', 'countries');
  $('vc-map').innerHTML = mapSVG(summary.codes, { b, count: summary.count });
  const svg = $('vc-map').firstElementChild;
  svg.removeAttribute('width'); svg.removeAttribute('height');
  renderStats(); renderTable(); renderNear(); renderAdd(); setDownloads(true);
  if (announce) $('vc-status').textContent = announce;
}

function setDownloads(on) { for (const id of ['vc-svg', 'vc-png', 'vc-csv']) $(id).disabled = !on; }

function renderFiles() {
  const list = $('vc-files'); list.replaceChildren();
  for (const f of files) {
    const li = el('li'); li.append(el('span', f.name, 'vc-file-name'));
    if (f.analysis) {
      const s = f.analysis.stats, parts = [f.label];
      if (f.file.kind === 'csv') parts.push(`${plural(s.csvRows, 'country-date row')}`);
      else parts.push(`${plural(s.read, 'point')} read, ${plural(s.thinned, 'point')} after thinning, ${number.format(s.matched)} matched, ${number.format(s.near)} near a border, ${number.format(s.unmatched)} unmatched`);
      if (f.file.skipped) parts.push(`${plural(f.file.skipped, 'entry', 'entries')} skipped as unusable`);
      parts.push(`Dates: ${ruleText(f.file.rule)}`);
      li.append(el('span', parts.join(' · '), 'gpx-small'));
      for (const note of f.file.notes) li.append(el('span', note, 'gpx-small'));
    } else { li.classList.add('vc-rejected'); li.append(el('span', `Not used: ${f.error}`, 'vc-error')); }
    const remove = el('button', 'Remove'); remove.type = 'button';
    remove.setAttribute('aria-label', `Remove ${f.name}`);
    remove.addEventListener('click', () => { files = files.filter(x => x !== f); update(`Removed ${f.name}.`); $('vc-file').focus(); });
    li.append(remove); list.append(li);
  }
  $('vc-files-section').hidden = !files.length;
}

function renderStats() {
  const s = summary.stats, dl = $('vc-stats'); dl.replaceChildren();
  const rows = [['Points read', s.read], ['After thinning', s.thinned], ['Matched to a country', s.matched], ['Near a border (≤ 10 km)', s.near], ['Unmatched (water or outside)', s.unmatched], ['Unmatched but near a coast', s.nearCoast]];
  if (s.csvRows) rows.push(['Country-date rows', s.csvRows]);
  for (const [k, v] of rows) { const d = el('div'); d.append(el('dt', k), el('dd', number.format(v))); dl.append(d); }
  const rules = [...new Set(files.filter(f => f.analysis).map(f => ruleText(f.file.rule)))];
  $('vc-rule').textContent = `Date rule: ${rules.length ? rules.join('; ') : 'no files'}.`;
  $('vc-undrawn').textContent = summary.undrawn.length ? `Counted but not drawn (no boundary of their own in this set): ${summary.undrawn.join(', ')}.` : '';
}

const HEADS = [['name', 'Country'], ['first', 'First date'], ['last', 'Last date'], ['dates', 'Distinct dates'], ['source', 'Source']];
function renderTable() {
  const table = $('vc-table'); table.replaceChildren();
  const caption = el('caption', `Countries from your files and manual changes. ${plural(summary.count, 'country', 'countries')} counted. Sorted by ${HEADS.find(h => h[0] === sortKey)[1].toLowerCase()}.`);
  const head = el('tr');
  for (const [key, label] of HEADS) {
    const th = el('th'); th.scope = 'col';
    th.setAttribute('aria-sort', key === sortKey ? 'ascending' : 'none');
    const button = el('button', label); button.type = 'button'; button.className = 'vc-sort'; button.dataset.sort = key;
    button.addEventListener('click', () => { sortKey = key; renderTable(); $('vc-table').querySelector(`button[data-sort="${key}"]`).focus(); });
    th.append(button); head.append(th);
  }
  head.append(Object.assign(el('th', 'Change'), { scope: 'col' }));
  table.append(caption, Object.assign(el('thead'), {}), el('tbody'));
  table.tHead.append(head);
  for (const r of sortRows(summary.rows, sortKey)) {
    const tr = el('tr'); if (!r.counted) tr.className = 'vc-removed';
    tr.dataset.code = r.code;
    const name = el('th', `${r.name} (${r.code})`); name.scope = 'row';
    tr.append(name, el('td', dayText(r.first) || '–'), el('td', dayText(r.last) || '–'), el('td', String(r.dates)), el('td', r.source));
    const action = el('td'), button = el('button', r.source === 'removed manually' ? 'Add back' : r.source === 'manual' ? 'Undo add' : 'Remove');
    button.type = 'button'; button.setAttribute('aria-label', `${button.textContent} ${r.name}`);
    button.dataset.code = r.code;
    button.addEventListener('click', () => toggle(r.code));
    action.append(button); tr.append(action); table.tBodies[0].append(tr);
  }
}

function renderNear() {
  const list = $('vc-near'); list.replaceChildren();
  for (const n of summary.nearOnly) {
    const li = el('li'), why = n.nearTo.map(t => t === 'coast' ? 'a coastline' : countryName(t, b)).join(', ');
    li.append(el('span', `${n.name} (${n.code}): ${plural(n.points, 'point')} near ${why}, not counted. `));
    const add = el('button', 'Add by hand'); add.type = 'button'; add.setAttribute('aria-label', `Add ${n.name} by hand`);
    add.addEventListener('click', () => toggle(n.code)); li.append(add); list.append(li);
  }
  $('vc-near-section').hidden = !summary.nearOnly.length;
}

function renderAdd() {
  const select = $('vc-country'), current = select.value;
  select.replaceChildren(Object.assign(el('option', 'Choose a country'), { value: '' }));
  const counted = summary ? summary.codes : new Set();
  for (const c of [...b.list].sort((x, y) => x.name.localeCompare(y.name))) if (!counted.has(c.code)) select.append(Object.assign(el('option', `${c.name} (${c.code})`), { value: c.code }));
  select.value = [...select.options].some(o => o.value === current) ? current : '';
}

function toggle(code) {
  const result = toggleManual(manual, code, summary);
  manual = result.manual;
  const name = countryName(code, b);
  const message = { added: `Added ${name} by hand.`, removed: `Removed ${name} by hand.`, restored: `Added ${name} back.`, 'removed-manual': `Undid the manual add of ${name}.` }[result.action];
  update(`${message} Now ${plural(summarize(files.filter(f => f.analysis).map(f => f.analysis), manual, b).count, 'country', 'countries')}.`);
  const again = [...document.querySelectorAll('#vc-table td button[data-code]')].find(x => x.dataset.code === code);
  (again || $('vc-country')).focus();
}

async function addFiles(list) {
  const token = ++generation, picked = Array.from(list);
  if (!picked.length) return;
  $('vc-status').textContent = `Reading ${plural(picked.length, 'file')} on this device…`;
  let added = 0, rejected = 0;
  for (const file of picked) {
    const entry = { id: nextId++, name: file.name };
    try {
      if (file.size === 0) throw new Error('This file is empty. Export it again from the app that made it.');
      if (file.size > MAX_BYTES) throw new Error('This file is over 10 MiB. Export a shorter date range and try again.');
      const text = await file.text();
      if (token !== generation) return;
      entry.file = readLocationFile(text, file.name);
      entry.label = { gpx: 'GPX', timeline: 'Google Timeline JSON', geojson: 'GeoJSON', kml: 'KML', csv: 'Country Days CSV' }[entry.file.kind];
      entry.analysis = analyze(entry.file, b);
      added++;
    } catch (error) { entry.error = error instanceof Error && error.message ? error.message : 'This file could not be read.'; rejected++; }
    if (token !== generation) return;
    files.push(entry);
  }
  $('vc-file').value = '';
  update(`Read ${plural(added, 'file')}${rejected ? `; ${plural(rejected, 'file')} could not be used (see the list)` : ''}. Everything was read on this device.`);
}

function download(content, type, filename) {
  const url = URL.createObjectURL(new Blob([content], { type })); urls.push(url);
  const a = document.createElement('a'); a.href = url; a.download = filename; document.body.append(a); a.click(); a.remove();
}
const stamp = () => `visited-countries-${summary.count}`;

$('vc-file').addEventListener('change', event => addFiles(event.target.files));
const drop = $('vc-drop');
for (const type of ['dragenter', 'dragover']) drop.addEventListener(type, event => { event.preventDefault(); drop.classList.add('dragging'); });
for (const type of ['dragleave', 'drop']) drop.addEventListener(type, () => drop.classList.remove('dragging'));
drop.addEventListener('drop', event => { event.preventDefault(); if (event.dataTransfer?.files?.length) addFiles(event.dataTransfer.files); });
$('vc-demo').addEventListener('click', () => {
  generation++; manual = emptyManual();
  const file = readLocationFile(VISITED_DEMO.text, VISITED_DEMO.name);
  files = [{ id: nextId++, name: VISITED_DEMO.name + ' (synthetic example)', file, label: 'GPX', analysis: analyze(file, b) }];
  update('Loaded a synthetic five-point trip through Portugal, Spain and France. Invented points, not a real recording.');
});
$('vc-clear').addEventListener('click', () => { generation++; files = []; manual = emptyManual(); update('Cleared everything from this page. Your original files have not changed.'); });
$('vc-reset').addEventListener('click', () => { manual = emptyManual(); update('Manual changes reset. The table shows only what your files contain.'); });
$('vc-add').addEventListener('click', () => {
  const code = $('vc-country').value;
  if (!code) { $('vc-status').textContent = 'Choose a country to add first.'; return; }
  toggle(code); $('vc-country').focus();
});
$('vc-map').addEventListener('click', event => {
  const code = event.target.closest?.('path[data-code]')?.dataset.code;
  if (code && summary) toggle(code);
});
$('vc-csv').addEventListener('click', () => { download(tableCSV(summary), 'text/csv;charset=utf-8', `${stamp()}.csv`); $('vc-status').textContent = 'Saved the table as CSV.'; });
$('vc-svg').addEventListener('click', () => { download(mapSVG(summary.codes, { b, count: summary.count }), 'image/svg+xml', `${stamp()}.svg`); $('vc-status').textContent = 'Saved the map as SVG.'; });
$('vc-png').addEventListener('click', () => {
  const { width, height } = pngSize();
  const image = new Image();
  image.onload = () => {
    const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height;
    canvas.getContext('2d').drawImage(image, 0, 0, width, height);
    canvas.toBlob(blob => { if (blob) { download(blob, 'image/png', `${stamp()}.png`); $('vc-status').textContent = 'Saved the map as PNG.'; } else $('vc-status').textContent = 'This browser could not make a PNG. Save the SVG instead.'; }, 'image/png');
  };
  image.onerror = () => { $('vc-status').textContent = 'This browser could not make a PNG. Save the SVG instead.'; };
  const svg = mapSVG(summary.codes, { b, count: summary.count, width });
  image.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
});
window.addEventListener('pagehide', () => urls.forEach(u => URL.revokeObjectURL(u)));
$('vc-tool').hidden = false;
update();
