import campaign from '../content/fastlane-nov-2026.json' with { type: 'json' };

const platforms = new Set(campaign.platforms);
const links = new Map(campaign.posts.flatMap(post => campaign.platforms.map(platform => {
  const slug = `${platform}-${post.angle}-${post.sequence}`;
  return [slug, { ...post, platform, token: `${platform}_${post.angle}_${post.sequence}` }];
})));
const headers = {
  'Cache-Control': 'no-store, max-age=0', 'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff', 'X-Robots-Tag': 'noindex, nofollow',
};
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export function appStoreURL(link, { privacy = false } = {}) {
  const target = new URL(`https://apps.apple.com/app/apple-store/id${campaign.appId}`);
  if (!privacy) {
    target.searchParams.set('pt', campaign.providerToken);
    target.searchParams.set('ct', link.token);
    target.searchParams.set('mt', '8');
    const page = campaign.customProductPages[link.angle];
    // Only publish app-specific approved product pages into this manifest.
    if (page?.state === 'APPROVED' && page.ppid) target.searchParams.set('ppid', page.ppid);
  }
  return target.href;
}
function html(body, method) {
  return new Response(method === 'HEAD' ? null : `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Whereday — Remember the Route</title><style>body{font:18px/1.5 system-ui;max-width:44rem;margin:3rem auto;padding:0 1.5rem;color:#1d2a44}a{color:#943b25}li{margin:1rem 0}small{color:#475569}</style><main>${body}</main></html>`, {
    headers: { ...headers, 'Content-Type': 'text/html; charset=utf-8',
      'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'" },
  });
}
export default {
  async fetch(request, env, ctx) {
    if (!['GET', 'HEAD'].includes(request.method)) return new Response(null, { status: 405, headers: { ...headers, Allow: 'GET, HEAD' } });
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/$/, '');
    const slug = path.startsWith('/v/') ? path.slice(3) : '';
    if (platforms.has(slug)) {
      // A selectable bio link preserves the post identity. A shared bio link alone cannot identify a video.
      return html(`<h1>Remember the route</h1><p>Find the post you watched, then open Whereday on the App Store.</p><ol>${campaign.posts.map(post => `<li><a href="/v/${slug}-${post.angle}-${post.sequence}">Post ${post.day}: ${escape(post.title)}</a></li>`).join('')}</ol><p><a href="https://whereday.com/">About Whereday</a> · <a href="https://whereday.com/privacy">Privacy</a></p><small>Whereday is for iPhone and Apple Watch. New-user tracking requires eligible access. Recording can have gaps.</small>`, request.method);
    }
    const link = links.get(slug);
    if (!link) return new Response('Unknown Whereday campaign link.', { status: 404, headers });
    const privacy = request.headers.get('Sec-GPC') === '1' || request.headers.get('DNT') === '1';
    const ua = request.headers.get('User-Agent') || '';
    const preview = /bot|crawler|spider|preview|facebookexternalhit|Facebot|Slack|Discord|Telegram|WhatsApp/i.test(ua) || /prefetch|preview/i.test(`${request.headers.get('Purpose') || ''} ${request.headers.get('Sec-Purpose') || ''}`);
    if (request.method === 'GET' && !privacy && !preview) {
      // Aggregate only. Never store request IDs, IPs, user agents, referrers, or private location data.
      ctx.waitUntil((async () => {
        try {
          if (!env.DB) throw new Error('No database');
          await env.DB.prepare(`INSERT INTO campaign_redirect_daily (day,campaign_token,destination,count)
            VALUES (date('now'),?,'app_store',1)
            ON CONFLICT(day,campaign_token,destination) DO UPDATE SET count=count+1`).bind(link.token).run();
        } catch { console.error('Campaign counter unavailable'); }
      })());
    }
    return new Response(null, { status: 302, headers: { ...headers, Location: appStoreURL(link, { privacy }) } });
  },
};
