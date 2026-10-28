// The site builder replaces this token with the public-page Markdown manifest.
const pages = {
  "/about": "/_markdown/about.md",
  "/blog/back-up-iphone-location-history/": "/_markdown/blog/back-up-iphone-location-history/index.md",
  "/blog/": "/_markdown/blog/index.md",
  "/blog/iphone-location-journal-obsidian/": "/_markdown/blog/iphone-location-journal-obsidian/index.md",
  "/blog/iphone-location-tracking-battery/": "/_markdown/blog/iphone-location-tracking-battery/index.md",
  "/blog/missing-location-history-iphone/": "/_markdown/blog/missing-location-history-iphone/index.md",
  "/blog/private-location-journal-iphone/": "/_markdown/blog/private-location-journal-iphone/index.md",
  "/blog/record-iphone-route-gpx/": "/_markdown/blog/record-iphone-route-gpx/index.md",
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
  "/privacy": "/_markdown/privacy.md",
  "/terms": "/_markdown/terms.md",
  "/zh-hans/": "/_markdown/zh-hans/index.md"
};

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
    // Keep bookmarks, legal links and search results working after the rebrand.
    if (['isome.isolated.tech', 'www.whereday.com'].includes(url.hostname)) {
      url.protocol = 'https:';
      url.hostname = 'whereday.com';
      url.port = '';
      return Response.redirect(url.toString(), 301);
    }
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
    return response;
  },
};
