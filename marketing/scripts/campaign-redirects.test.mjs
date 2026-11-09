import assert from 'node:assert/strict';
import { test } from 'node:test';
import worker from '../campaign-redirects/worker.mjs';
import campaign from '../content/fastlane-nov-2026.json' with { type: 'json' };
async function run(path, { method = 'GET', headers = {}, broken = false } = {}) {
  const writes = [], pending = [];
  const DB = { prepare(sql) { return { bind(...values) { return { async run() { if (broken) throw Error('offline'); writes.push({ sql, values }); } }; } }; } };
  const response = await worker.fetch(new Request(`https://whereday.com${path}`, { method, headers }), { DB }, { waitUntil(p) { pending.push(p); } });
  await Promise.all(pending);
  return { response, writes };
}
test('every issued creative/platform link preserves app, provider and unique campaign token', async () => {
  const seen = new Set();
  for (const p of campaign.posts) for (const platform of campaign.platforms) {
    const token = `${platform}_${p.angle}_${p.sequence}`;
    assert.ok(token.length <= 30 && !seen.has(token)); seen.add(token);
    const { response, writes } = await run(`/v/${platform}-${p.angle}-${p.sequence}?ct=attacker&ppid=other-app`, { method: 'HEAD' });
    const target = new URL(response.headers.get('Location'));
    assert.equal(response.status, 302);
    assert.equal(target.hostname, 'apps.apple.com');
    assert.ok(target.pathname.endsWith('id6761960794'));
    assert.equal(target.searchParams.get('pt'), '126424970');
    assert.equal(target.searchParams.get('ct'), token);
    assert.equal(target.searchParams.get('mt'), '8');
    assert.equal(target.searchParams.get('ppid'), null);
    assert.equal(writes.length, 0);
  }
  assert.equal(seen.size, 28 * 7);
});
test('GET increments only aggregate public token, never request data', async () => {
  const { response, writes } = await run('/v/tt-route-001?private=do-not-store', { headers: { 'User-Agent': 'iPhone', Referer: 'https://private.example/query' } });
  assert.equal(response.status, 302);
  assert.deepEqual(writes[0].values, ['tt_route_001']);
  assert.match(writes[0].sql, /ON CONFLICT/);
  assert.equal(response.headers.get('Referrer-Policy'), 'no-referrer');
});
test('privacy headers skip counters and remove Apple campaign attribution', async () => {
  for (const headers of [{ DNT: '1' }, { 'Sec-GPC': '1' }]) {
    const { response, writes } = await run('/v/tt-route-001', { headers });
    assert.equal(writes.length, 0);
    assert.equal(new URL(response.headers.get('Location')).search, '');
  }
});
test('known preview crawlers, prefetches and HEAD do not count as clicks', async () => {
  for (const headers of [{ 'User-Agent': 'facebookexternalhit/1.1' }, { 'User-Agent': 'Twitterbot' }, { Purpose: 'prefetch' }]) {
    assert.equal((await run('/v/ig-route-001', { headers })).writes.length, 0);
  }
});
test('unknown and malformed links fail closed; there is no open redirect', async () => {
  for (const path of ['/v/tt-route-999', '/v/tt-watch-001', '/v/healthmd', '/v/tt-route-001/more', '/v/https://example.com']) {
    const { response, writes } = await run(path);
    assert.equal(response.status, 404); assert.equal(writes.length, 0);
  }
  assert.equal((await run('/v/tt-route-001', { method: 'POST' })).response.status, 405);
});
test('bio hub exposes the 28 post-specific links without counting the hub as an install or click', async () => {
  const { response, writes } = await run('/v/tt');
  const body = await response.text();
  assert.equal(response.status, 200); assert.equal(writes.length, 0);
  assert.equal((body.match(/href="\/v\/tt-/g) || []).length, 28);
  assert.ok(response.headers.get('Content-Security-Policy').includes("default-src 'none'"));
});
test('measurement failure never breaks the App Store handoff', async () => {
  assert.equal((await run('/v/yt-route-001', { broken: true })).response.status, 302);
});
