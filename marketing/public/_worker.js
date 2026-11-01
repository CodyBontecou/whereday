// The site builder replaces this token with the public-page Markdown manifest.
const pages = {
  "/about": "/_markdown/about.md",
  "/blog/back-up-iphone-location-history/": "/_markdown/blog/back-up-iphone-location-history/index.md",
  "/blog/check-gpx-file/": "/_markdown/blog/check-gpx-file/index.md",
  "/blog/google-timeline-json-formats/": "/_markdown/blog/google-timeline-json-formats/index.md",
  "/blog/gpx-kml-geojson-location-history/": "/_markdown/blog/gpx-kml-geojson-location-history/index.md",
  "/blog/import-google-timeline-iphone/": "/_markdown/blog/import-google-timeline-iphone/index.md",
  "/blog/": "/_markdown/blog/index.md",
  "/blog/iphone-location-journal-obsidian/": "/_markdown/blog/iphone-location-journal-obsidian/index.md",
  "/blog/iphone-location-tracking-battery/": "/_markdown/blog/iphone-location-tracking-battery/index.md",
  "/blog/missing-location-history-iphone/": "/_markdown/blog/missing-location-history-iphone/index.md",
  "/blog/private-location-journal-iphone/": "/_markdown/blog/private-location-journal-iphone/index.md",
  "/blog/record-iphone-route-gpx/": "/_markdown/blog/record-iphone-route-gpx/index.md",
  "/blog/track-days-in-country-iphone/": "/_markdown/blog/track-days-in-country-iphone/index.md",
  "/blog/view-location-history-iphone/": "/_markdown/blog/view-location-history-iphone/index.md",
  "/compare/google-maps-timeline/": "/_markdown/compare/google-maps-timeline/index.md",
  "/compare/": "/_markdown/compare/index.md",
  "/contact": "/_markdown/contact.md",
  "/docs/apple-watch/": "/_markdown/docs/apple-watch/index.md",
  "/docs/automatic-exports/": "/_markdown/docs/automatic-exports/index.md",
  "/docs/export-import/": "/_markdown/docs/export-import/index.md",
  "/docs/getting-started/": "/_markdown/docs/getting-started/index.md",
  "/docs/": "/_markdown/docs/index.md",
  "/docs/photos/": "/_markdown/docs/photos/index.md",
  "/docs/places/": "/_markdown/docs/places/index.md",
  "/docs/privacy-data/": "/_markdown/docs/privacy-data/index.md",
  "/docs/siri-shortcuts/": "/_markdown/docs/siri-shortcuts/index.md",
  "/docs/timeline-outings/": "/_markdown/docs/timeline-outings/index.md",
  "/docs/tracking/": "/_markdown/docs/tracking/index.md",
  "/docs/webhooks/": "/_markdown/docs/webhooks/index.md",
  "/": "/_markdown/index.md",
  "/integrations/dawarich/": "/_markdown/integrations/dawarich/index.md",
  "/integrations/": "/_markdown/integrations/index.md",
  "/privacy": "/_markdown/privacy.md",
  "/terms": "/_markdown/terms.md",
  "/tools/google-timeline-inspector/": "/_markdown/tools/google-timeline-inspector/index.md",
  "/tools/gpx-viewer/": "/_markdown/tools/gpx-viewer/index.md",
  "/tools/": "/_markdown/tools/index.md",
  "/zh-hans/": "/_markdown/zh-hans/index.md"
};

async function acquisition(request, env) {
  const headers = { 'Cache-Control': 'no-store' };
  const reply = (message, status) => new Response(message, { status, headers });
  if (request.method !== 'POST') return new Response(null, { status: 405, headers: { ...headers, Allow: 'POST' } });
  const url = new URL(request.url);
  // Reject cross-origin/form submissions. Preview deployments don't collect counts.
  if (request.headers.get('Origin') !== url.origin ||
      !(url.hostname === 'whereday.com' || ['127.0.0.1', 'localhost'].includes(url.hostname))) return reply('Forbidden', 403);
  if (request.headers.get('Content-Type')?.split(';')[0].trim() !== 'application/json') return reply('JSON required', 415);
  if (request.headers.get('Sec-GPC') === '1' || request.headers.get('DNT') === '1') return reply(null, 204);
  if (!env.SEO_DB) return reply('Measurement unavailable', 503);
  if (Number(request.headers.get('Content-Length')) > 512) return reply('Payload too large', 413);
  let size = 0;
  const chunks = [];
  const reader = request.body?.getReader();
  if (!reader) return reply('Invalid event', 400);
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > 512) { await reader.cancel(); return reply('Payload too large', 413); }
    chunks.push(value);
  }
  let event;
  try {
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    event = JSON.parse(new TextDecoder().decode(bytes));
  } catch { return reply('Invalid event', 400); }
  if (!event || Array.isArray(event) || Object.keys(event).sort().join(',') !== 'event,page,placement,source' ||
      !['page_view', 'app_store_clicked'].includes(event.event) ||
      !Object.hasOwn(pages, event.page) ||
      !['page', 'header', 'inline', 'footer', 'qr'].includes(event.placement) ||
      !['direct', 'internal', 'search', 'other'].includes(event.source) ||
      (event.event === 'page_view') !== (event.placement === 'page')) return reply('Invalid event', 400);
  try {
    await env.SEO_DB.prepare(`INSERT INTO website_acquisition_daily (day,event,page,placement,source,count)
      VALUES (date('now'),?,?,?,?,1) ON CONFLICT(day,event,page,placement,source)
      DO UPDATE SET count=count+1`).bind(event.event, event.page, event.placement, event.source).run();
  } catch { return reply('Measurement unavailable', 503); }
  return reply(null, 204);
}

function quality(accept, type) {
  for (const entry of accept.toLowerCase().split(',')) {
    const [media, ...parameters] = entry.trim().split(';');
    if (media.trim() !== type) continue;
    const q = parameters.find(parameter => parameter.trim().startsWith('q='));
    const value = q ? Number(q.trim().slice(2)) : 1;
    return Number.isFinite(value) && value >= 0 && value <= 1 ? value : 0;
  }
  return 0;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // Preserve existing bookmarks and indexed URLs when moving the product site.
    if (['isome.isolated.tech', 'www.whereday.com'].includes(url.hostname)) {
      url.protocol = 'https:';
      url.hostname = 'whereday.com';
      url.port = '';
      return Response.redirect(url.toString(), 301);
    }
    if (url.pathname === '/api/v1/acquisition') return acquisition(request, env);
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      return new Response(JSON.stringify({
        type: 'about:blank', title: 'Method not allowed', status: 405,
        detail: 'This website supports read-only GET and HEAD requests.',
        resolution: 'Use GET to retrieve the resource.',
      }), { status: 405, headers: { Allow: 'GET, HEAD', 'Content-Type': 'application/problem+json' } });
    }
    if (url.pathname === '/api/v1/product') {
      const assetURL = new URL('/api/v1/product.json', url);
      const asset = await env.ASSETS.fetch(new Request(assetURL, { method: request.method }));
      const response = new Response(asset.body, asset);
      response.headers.set('Content-Type', 'application/json; charset=utf-8');
      response.headers.set('Cache-Control', 'public, max-age=300');
      response.headers.set('Link', '</openapi.json>; rel="service-desc"; type="application/vnd.oai.openapi+json"');
      return response;
    }
    const accept = request.headers.get('Accept') || '';
    const wantsMarkdown = quality(accept, 'text/markdown') > 0 &&
      quality(accept, 'text/markdown') >= quality(accept, 'text/html');
    const markdown = Object.hasOwn(pages, url.pathname) ? pages[url.pathname] : null;
    let response;
    if (wantsMarkdown && markdown) {
      const assetURL = new URL(markdown, url);
      response = await env.ASSETS.fetch(new Request(assetURL, { method: request.method }));
      response = new Response(response.body, response);
      response.headers.set('Content-Type', 'text/markdown; charset=utf-8');
      response.headers.set('Link', `<${url.origin}${url.pathname}>; rel="canonical"`);
    } else {
      response = await env.ASSETS.fetch(request);
      response = new Response(response.body, response);
    }
    if (url.pathname === '/.well-known/api-catalog' && response.ok) {
      response.headers.set('Content-Type', 'application/linkset+json');
    }
    if (response.status === 404) {
      const isJSON = url.pathname.startsWith('/api/') || quality(accept, 'application/json') > 0;
      if (isJSON && !wantsMarkdown) {
        response = new Response(request.method === 'HEAD' ? null : JSON.stringify({
          type: 'about:blank', title: 'Not found', status: 404,
          detail: 'This resource does not exist. The website provides public product metadata, not private journal access.',
          instance: url.pathname,
          resolution: 'Read https://whereday.com/docs/ for exports, webhooks, and Shortcuts.',
        }), { status: 404, headers: { 'Content-Type': 'application/problem+json; charset=utf-8' } });
      } else if (wantsMarkdown) {
        response = new Response(request.method === 'HEAD' ? null :
          '# Page not found\n\nThis resource does not exist on the Whereday website.\n\n' +
          'Try the [documentation](https://whereday.com/docs/), ' +
          '[site map](https://whereday.com/sitemap.xml), or ' +
          '[agent guide](https://whereday.com/llms.txt).\n',
          { status: 404, headers: { 'Content-Type': 'text/markdown; charset=utf-8' } });
      }
    }
    if (markdown || response.status === 404) {
      const vary = response.headers.get('Vary');
      if (!vary?.split(',').some(value => value.trim().toLowerCase() === 'accept')) {
        response.headers.set('Vary', vary ? `${vary}, Accept` : 'Accept');
      }
      // Pages caches don't use arbitrary Vary values as cache keys.
      response.headers.set('Cache-Control', 'no-store');
      if (markdown) {
        response.headers.append('Link', `<${url.origin}${url.pathname}>; rel="alternate"; type="text/markdown"`);
      }
    }
    if (['/tools/gpx-viewer', '/tools/gpx-viewer/', '/tools/gpx-viewer/index.html', '/tools/google-timeline-inspector', '/tools/google-timeline-inspector/', '/tools/google-timeline-inspector/index.html'].includes(url.pathname) && response.ok) {
      response.headers.set('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; object-src 'none'");
      response.headers.set('Referrer-Policy', 'no-referrer');
      response.headers.set('X-Content-Type-Options', 'nosniff');
      response.headers.set('Cache-Control', 'no-store, no-transform');
    }
    return response;
  },
};
