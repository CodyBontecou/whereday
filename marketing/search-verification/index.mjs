// Public ownership proofs, retained for periodic search-console re-verification.
const proofs = Object.freeze({
  "/BingSiteAuth.xml": {
    type: "application/xml; charset=utf-8",
    body: '<?xml version="1.0"?>\n<users>\n\t<user>A7D7AA3116AFD2D80633B78ED07DDEC6</user>\n</users>',
  },
  "/yandex_c33e9b984dcbf487.html": {
    type: "text/html; charset=utf-8",
    body: '<html>\n    <head>\n        <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">\n    </head>\n    <body>Verification: c33e9b984dcbf487</body>\n</html>',
  },
});

export default {
  fetch(request) {
    const url = new URL(request.url);
    const proof = Object.hasOwn(proofs, url.pathname) ? proofs[url.pathname] : undefined;
    if (url.origin !== "https://whereday.com" || !proof) {
      return new Response("Not found", { status: 404 });
    }
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405, headers: { Allow: "GET, HEAD" } });
    }
    return new Response(request.method === "HEAD" ? null : proof.body, {
      headers: {
        "Content-Type": proof.type,
        "Cache-Control": "public, max-age=300",
        "X-Content-Type-Options": "nosniff",
      },
    });
  },
};
