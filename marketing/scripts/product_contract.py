"""Public product metadata and its read-only, typed API contract."""
from check_site import BASE, PUBLIC
import json


def build_product_contract():
    docs = [
        ('Getting started', '/docs/getting-started/'),
        ('Tracking', '/docs/tracking/'),
        ('Export and import', '/docs/export-import/'),
        ('Automatic exports', '/docs/automatic-exports/'),
        ('Webhooks', '/docs/webhooks/'),
        ('Siri and Shortcuts', '/docs/siri-shortcuts/'),
        ('Apple Watch', '/docs/apple-watch/'),
        ('Privacy and data', '/docs/privacy-data/'),
    ]
    product = {
        'name': 'Whereday',
        'summary': 'An open source location journal for iPhone and Apple Watch, with on-device visits, GPS routes, and optional portable export.',
        'homepage': BASE + '/',
        'appStore': 'https://apps.apple.com/app/id6761960794',
        'sourceRepository': 'https://github.com/CodyBontecou/whereday',
        'platforms': ['iOS 17 or later', 'watchOS 10 or later'],
        'capabilities': ['Visit detection during tracking', 'GPS route recording', 'Timeline and outings', 'Independent Apple Watch recording', 'Portable export', 'Supported Whereday file import', 'Google Timeline JSON import with preview and duplicate checks (1.8)', 'Country Days and manual stays', 'Siri and Shortcuts', 'Optional outbound webhooks'],
        'exportFormats': ['JSON', 'CSV', 'Markdown', 'GeoJSON', 'GPX', 'KML', 'OwnTracks', 'Overland'],
        'pricingURL': BASE + '/#pricing',
        'privacyURL': BASE + '/privacy',
        'privateJournalAPI': False,
        'documentation': [{'title': title, 'url': BASE + path} for title, path in docs],
    }
    string = {'type': 'string'}
    uri = {'type': 'string', 'format': 'uri'}
    properties = {
        'name': {**string, 'description': 'Native app name.'},
        'summary': {**string, 'description': 'What the native app does.'},
        'homepage': {**uri, 'description': 'Canonical marketing homepage.'},
        'appStore': {**uri, 'description': 'Official download link.'},
        'sourceRepository': {**uri, 'description': 'Public source repository.'},
        'platforms': {'type': 'array', 'items': string, 'description': 'Supported native platforms and minimum versions.'},
        'capabilities': {'type': 'array', 'items': string, 'description': 'Product capabilities; features may require a purchase or explicit device permission.'},
        'exportFormats': {'type': 'array', 'items': string, 'description': 'Native export choices; content depends on record type. OwnTracks and Overland carry GPS points. Export access follows the new-user and existing-user rules at pricingURL; not every export format can be imported.'},
        'pricingURL': {**uri, 'description': 'Pricing details; regional App Store purchase sheets are authoritative.'},
        'privacyURL': {**uri, 'description': 'Data-handling policy.'},
        'privateJournalAPI': {'type': 'boolean', 'const': False, 'description': 'False: the site cannot read a user\'s private journal.'},
        'documentation': {'type': 'array', 'description': 'Task-specific native app documentation.', 'items': {'type': 'object', 'additionalProperties': False, 'required': ['title', 'url'], 'properties': {'title': string, 'url': uri}}},
    }
    spec = {
        'openapi': '3.1.0',
        'info': {'title': 'Whereday Website API', 'version': '1.0.0', 'description': 'Read-only public product metadata and a validated browser measurement endpoint for daily aggregate counts. No account, credentials, personal locations, exports, or journal records are accessible through this API.', 'contact': {'name': 'Cody Bontecou', 'email': 'cody.bontecou@gmail.com', 'url': BASE + '/contact'}},
        'servers': [{'url': BASE}],
        'security': [],
        'paths': {'/api/v1/product': {'get': {
            'operationId': 'getIsoMeProduct',
            'summary': 'Read Whereday capabilities and documentation links',
            'description': 'Retrieve public metadata about the native app. No parameters or authentication are required. Responses are cacheable for five minutes. There is no fixed advertised application quota; Cloudflare protections may apply. The /v1 response can gain optional fields; incompatible changes use a new major path. No writes or private journal access are supported.',
            'responses': {
                '200': {'description': 'Public product metadata.', 'content': {'application/json': {'schema': {'$ref': '#/components/schemas/Product'}, 'example': product}}},
                '405': {'description': 'Unsupported method. Use GET or HEAD.', 'content': {'application/problem+json': {'schema': {'$ref': '#/components/schemas/Problem'}}}},
            },
        }}},
        'components': {'schemas': {
            'Product': {'type': 'object', 'additionalProperties': False, 'required': list(properties), 'properties': properties},
            'Problem': {'type': 'object', 'required': ['type', 'title', 'status', 'detail', 'resolution'], 'properties': {'type': uri, 'title': string, 'status': {'type': 'integer'}, 'detail': string, 'resolution': string}},
        }},
    }
    spec['paths']['/api/v1/acquisition'] = {'post': {
        'operationId': 'countWebsiteAcquisition',
        'summary': 'Count a gated website page load or App Store link activation',
        'description': 'Internal browser measurement endpoint. Same-origin requests only, after the analytics exclusion gate. Accepts public page paths and coarse dimensions; no visitor identifiers or journal data. Updates daily aggregate counters. Does not measure installs or purchases.',
        'requestBody': {'required': True, 'content': {'application/json': {'schema': {
            'type': 'object', 'additionalProperties': False,
            'required': ['event', 'page', 'placement', 'source'],
            'properties': {
                'event': {'type': 'string', 'enum': ['page_view', 'app_store_clicked']},
                'page': {'type': 'string', 'description': 'An exact known public page path without a query or fragment.'},
                'placement': {'type': 'string', 'enum': ['page', 'header', 'inline', 'footer', 'qr']},
                'source': {'type': 'string', 'enum': ['direct', 'internal', 'search', 'other']},
            },
        }}}},
        'responses': {str(code): {'description': description} for code, description in [
            (204, 'Count accepted, or privacy preference honored without storing.'),
            (400, 'Invalid or unknown dimensions.'), (403, 'Origin or host rejected.'),
            (405, 'Use POST.'), (413, 'Payload exceeds 512 bytes.'),
            (415, 'Use application/json.'), (503, 'Measurement unavailable; no success claimed.'),
        ]},
    }}
    target = PUBLIC / 'api/v1/product.json'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(product, indent=2) + '\n')
    (PUBLIC / 'openapi.json').write_text(json.dumps(spec, indent=2) + '\n')
    well_known = PUBLIC / '.well-known'
    well_known.mkdir(exist_ok=True)
    (well_known / 'api-catalog').write_text(json.dumps({'linkset': [{'anchor': BASE + '/', 'service-desc': [{'href': BASE + '/openapi.json', 'type': 'application/vnd.oai.openapi+json', 'title': 'Whereday public product metadata API'}], 'service-doc': [{'href': BASE + '/docs/', 'type': 'text/html', 'title': 'Native app documentation'}]}]}, indent=2) + '\n')
