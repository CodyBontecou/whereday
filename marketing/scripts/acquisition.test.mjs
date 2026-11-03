import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import vm from 'node:vm';

const source = (await readFile(new URL('../worker.js', import.meta.url), 'utf8'))
  .replace('__PAGE_MARKDOWN__', JSON.stringify({ '/': '/_markdown/index.md', '/blog/': '/_markdown/blog/index.md' }));
const worker = (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)).default;
const client = await readFile(new URL('../public/assets/acquisition.js', import.meta.url), 'utf8');
const valid = { event: 'app_store_clicked', page: '/', placement: 'inline', source: 'search' };
function request(body = valid, headers = {}, method = 'POST') {
  return new Request('https://whereday.com/api/v1/acquisition', {
    method, headers: { Origin: 'https://whereday.com', 'Content-Type': 'application/json', ...headers },
    ...(method === 'POST' ? { body: typeof body === 'string' ? body : JSON.stringify(body) } : {}),
  });
}
function database() {
  const calls = [];
  return { calls, SEO_DB: { prepare(sql) { return { bind(...args) { return { async run() { calls.push({ sql, args }); } }; } }; } } };
}

test('accepts only public dimensions and binds values into an aggregate update', async () => {
  const db = database();
  assert.equal((await worker.fetch(request(), db)).status, 204);
  assert.deepEqual(db.calls[0].args, ['app_store_clicked', '/', 'inline', 'search']);
  assert.match(db.calls[0].sql, /ON CONFLICT/);
  assert.equal((await worker.fetch(request({ ...valid, event: 'page_view', placement: 'page' }), db)).status, 204);
});

test('rejects private fields, unknown pages, queries, methods and cross-origin submissions', async () => {
  const db = database();
  for (const payload of [null, [], {}, { ...valid, latitude: 12 }, { ...valid, page: '/?secret=x' },
    { ...valid, page: '/unknown' }, { ...valid, source: 'https://private.example/path' },
    { ...valid, event: 'purchase' }, { ...valid, placement: 'page' }, '{']) {
    assert.equal((await worker.fetch(request(payload), db)).status, 400);
  }
  assert.equal((await worker.fetch(request(valid, { Origin: 'https://other.example' }), db)).status, 403);
  assert.equal((await worker.fetch(request(valid, { 'Content-Type': 'text/plain' }), db)).status, 415);
  assert.equal((await worker.fetch(request(valid, {}, 'GET'), db)).status, 405);
  assert.equal((await worker.fetch(request('x'.repeat(513)), db)).status, 413);
  assert.equal(db.calls.length, 0);
});

test('honors browser privacy headers and reports an unavailable collector', async () => {
  const db = database();
  for (const headers of [{ 'Sec-GPC': '1' }, { DNT: '1' }]) {
    assert.equal((await worker.fetch(request(valid, headers), db)).status, 204);
  }
  assert.equal(db.calls.length, 0);
  const result = await worker.fetch(request(), {});
  assert.equal(result.status, 503);
  assert.equal(result.headers.get('Cache-Control'), 'no-store');
});

async function browser({ track = true, fail = false, gpc = false, host = 'whereday.com' } = {}) {
  const calls = [];
  let click;
  const context = {
    location: { hostname: host, pathname: '/blog/' }, navigator: { globalPrivacyControl: gpc }, URL,
    document: { referrer: 'https://www.google.com/search?q=sensitive', addEventListener(event, handler) { click = handler; } },
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (fail) throw new Error('gate unavailable');
      return { ok: true, json: async () => ({ track }) };
    },
  };
  vm.runInNewContext(client, context);
  await new Promise(resolve => setImmediate(resolve));
  const link = { href: 'https://apps.apple.com/app/id6761960794', dataset: {}, closest: () => null };
  click?.({ target: { closest: () => link } });
  await new Promise(resolve => setImmediate(resolve));
  return calls;
}

test('client fails closed for exclusions, failed gate, GPC and previews', async () => {
  for (const settings of [{ track: false }, { fail: true }, { gpc: true }, { host: 'preview.pages.dev' }]) {
    const calls = await browser(settings);
    assert.equal(calls.filter(c => c.url === '/api/v1/acquisition').length, 0);
  }
});

test('client sends coarse page and click dimensions, never the search query or referrer', async () => {
  const calls = (await browser()).filter(c => c.url === '/api/v1/acquisition');
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map(c => JSON.parse(c.options.body)), [
    { event: 'page_view', page: '/blog/', placement: 'page', source: 'search' },
    { event: 'app_store_clicked', page: '/blog/', placement: 'inline', source: 'search' },
  ]);
  assert.ok(calls.every(c => c.options.credentials === 'omit' && c.options.referrerPolicy === 'no-referrer' && !c.options.body.includes('sensitive')));
});


test('legacy and www links redirect to Whereday without losing paths or queries', async () => {
  for (const host of ['isome.isolated.tech', 'www.whereday.com']) {
    const response = await worker.fetch(new Request(`https://${host}/docs/export-import/?format=gpx&x=%2F`), {});
    assert.equal(response.status, 301);
    assert.equal(response.headers.get('Location'), 'https://whereday.com/docs/export-import/?format=gpx&x=%2F');
  }
});

test('file tool denies network connections and referrers at the response boundary', async () => {
 const response=await worker.fetch(new Request('https://whereday.com/tools/gpx-viewer/'),{ASSETS:{fetch:async()=>new Response('<html>GPX tool</html>',{headers:{'Content-Type':'text/html'}})}});
 assert.ok(response.headers.get('Content-Security-Policy').includes("connect-src 'none'"));
 assert.ok(response.headers.get('Content-Security-Policy').includes("script-src 'self'"));
 assert.equal(response.headers.get('Referrer-Policy'),'no-referrer');
 assert.ok(response.headers.get('Cache-Control').includes('no-transform'));
});

test('GPX privacy headers also protect direct index and slashless aliases', async () => {
 for (const path of ['/tools/gpx-viewer','/tools/gpx-viewer/index.html']) {
  const response=await worker.fetch(new Request('https://whereday.com'+path),{ASSETS:{fetch:async()=>new Response('GPX')}});
  assert.ok(response.headers.get('Content-Security-Policy').includes("connect-src 'none'"));
 }
});

test('Timeline privacy boundary covers every URL alias', async () => {
 for (const path of ['/tools/google-timeline-inspector/','/tools/google-timeline-inspector','/tools/google-timeline-inspector/index.html']) {
  const response=await worker.fetch(new Request('https://whereday.com'+path),{ASSETS:{fetch:async()=>new Response('Timeline')}});
  assert.ok(response.headers.get('Content-Security-Policy').includes("connect-src 'none'"));
  assert.equal(response.headers.get('Referrer-Policy'),'no-referrer');
  assert.ok(response.headers.get('Cache-Control').includes('no-transform'));
 }
});
