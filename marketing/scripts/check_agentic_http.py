#!/usr/bin/env python3
"""Verify representations, errors, methods, and assets against a running site."""
import argparse
from hashlib import sha256
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from check_site import BASE, PUBLIC, page_url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=BASE)
    args = parser.parse_args()

    def fetch(path, accept='text/html', method='GET'):
        request = Request(args.base_url.rstrip('/') + path, headers={'Accept': accept, 'User-Agent': 'Whereday-site-verification/1.0'}, method=method)
        try:
            response = urlopen(request, timeout=30)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    pages = [p for p in sorted(PUBLIC.rglob('*.html')) if p.name != '404.html']
    for page in pages:
        path = page_url(page).removeprefix(BASE)
        for accept, media in [('text/html', 'text/html'), ('text/markdown', 'text/markdown')]:
            status, headers, body = fetch(path, accept)
            assert status == 200, (path, accept, status)
            assert headers.get_content_type() == media, (path, accept, headers)
            assert len(body) > 100, (path, accept, 'empty representation')
            assert 'accept' in [v.strip().lower() for v in headers.get('Vary', '').split(',')], (path, 'Vary')
            assert 'no-store' in headers.get('Cache-Control', ''), (path, 'cache separation')
            if media == 'text/markdown':
                assert b'<html' not in body and b'<script' not in body, path
                assert b'Source: https://whereday.com' in body, path
    for accept, media in [('text/html', 'text/html'), ('text/markdown', 'text/markdown'), ('application/json', 'application/problem+json')]:
        status, headers, body = fetch('/a-page-that-does-not-exist-agentic-check', accept)
        assert status == 404 and headers.get_content_type() == media, (accept, status, headers)
        assert len(body) > 20 and b'/docs/' in body, (accept, 'missing recovery instructions')
        status, _, body = fetch('/a-page-that-does-not-exist-agentic-check', accept, 'HEAD')
        assert status == 404 and not body, (accept, 'HEAD error body')
    for path in ['/api/unknown', '/api/openapi.yaml', '/swagger.json']:
        status, _, _ = fetch(path)
        assert status == 404, (path, 'soft 404 or false API discovery', status)
    for accept, expected in [('text/markdown;q=0, text/html', 'text/html'),
                             ('text/markdown;q=0.2, text/html;q=1', 'text/html'),
                             ('text/html;q=0, text/markdown;q=1', 'text/markdown'),
                             ('text/markdown;q=0.8, text/html;q=0.5', 'text/markdown'),
                             ('*/*', 'text/html')]:
        status, headers, _ = fetch('/', accept)
        assert status == 200 and headers.get_content_type() == expected, (accept, expected, headers)
    for accept in ['text/html', 'text/markdown']:
        status, _, body = fetch('/', accept, 'HEAD')
        assert status == 200 and not body, ('HEAD', accept, status)
    import json
    status, headers, body = fetch('/api/v1/product', 'application/json')
    assert status == 200 and headers.get_content_type() == 'application/json', 'product metadata API'
    product = json.loads(body)
    assert product == json.loads((PUBLIC / 'api/v1/product.json').read_text()), 'product representation drift'
    assert product['privateJournalAPI'] is False and product['documentation'], 'public/private API boundary'
    status, _, body = fetch('/openapi.json', 'application/json')
    assert status == 200, 'missing OpenAPI'
    contract = json.loads(body)
    operation = contract['paths']['/api/v1/product']['get']
    assert operation['operationId'] == 'getIsoMeProduct', 'operation identity'
    assert set(contract['components']['schemas']['Product']['required']) == set(product), 'schema drift'
    status, headers, body = fetch('/api/v1/product', 'application/json', 'POST')
    assert status == 405 and headers.get_content_type() == 'application/problem+json', 'read-only API'
    assert json.loads(body)['status'] == 405, 'structured API error'
    status, headers, _ = fetch('/', method='POST')
    assert status == 405 and headers['Allow'] == 'GET, HEAD', ('unsupported method', status)
    status, _, body = fetch('/app-icon-84.webp', '*/*')
    assert status == 200 and sha256(body).digest() == sha256((PUBLIC / 'app-icon-84.webp').read_bytes()).digest(), 'asset changed'
    print(f'Passed: {len(pages)} HTML/Markdown pairs, cache separation, 404 recovery, API probes, product metadata/contract, Accept preference, HEAD, methods, and asset integrity.')


if __name__ == '__main__':
    main()
