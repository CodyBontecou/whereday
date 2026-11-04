# Search-console ownership proofs

This Worker serves only the two public verification files on `whereday.com`.
Keep them deployed so Bing and Yandex can periodically recheck ownership.
The exact routes avoid deploying unrelated marketing changes when adding proofs.
They do not contain account credentials.

Deploy from the repository root:

```sh
wrangler deploy --config marketing/search-verification/wrangler.jsonc
```

The October 30, 2026 deployment is version
`b97ee761-934b-4ec1-a641-d121ab50d63c`.
See `docs/qa/search-consoles-2026-10-30.md` for verification and sitemap status.
