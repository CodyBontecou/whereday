"""Upload approved exports using Apple's documented Asset Library API."""
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RECEIPT = ROOT / 'upload-receipt.json'

def api(method, route, body=None):
    command = ['asc', 'api', method, route, '--allow-unknown-path']
    if body is not None:
        command += ['--confirm', '--body-file', '-']
    result = subprocess.run(command, input=json.dumps(body) if body else None,
                            capture_output=True, text=True, check=True)
    return json.loads(result.stdout)

def run():
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    library = api('GET', '/v1/apps/6761960794/assetLibrary')['data']['id']
    catalog = api('GET', '/v1/appAssetLibraryRefData')['data'][0]['attributes']
    mapping = next(p for p in catalog['placementTypes'] if p['placementTypeId'] == 'APP_SCREENSHOT')
    specs = next(p for p in mapping['specMappings'] if p['placementGroupId'] == 'IPHONE_DUO_PROFILE')['specs']
    receipt = json.loads(RECEIPT.read_text()) if RECEIPT.exists() else {
        'appId': '6761960794', 'libraryId': library, 'placementGroup': 'IPHONE_DUO_PROFILE',
        'locale': 'en-US', 'placedOnVersion': False, 'assets': []}
    for card in manifest['cards']:
        file = ROOT / card['file']
        binary = file.read_bytes()
        assert hashlib.sha256(binary).hexdigest() == card['sha256']
        item = next((x for x in receipt['assets'] if x['fileName'] == file.name), None)
        if item:
            resource = api('GET', '/v1/appAssetLibraryImages/' + item['id'])['data']
        else:
            resource = api('POST', '/v1/appAssetLibraryImages', {'data': {
                'type': 'appAssetLibraryImages', 'attributes': {
                    'fileName': file.name, 'fileSize': len(binary),
                    'category': 'APP_SCREENSHOTS_AND_PREVIEWS',
                    'referenceName': 'Whereday Duo 2026-10-30 / ' + card['name']},
                'relationships': {'assetLibrary': {'data': {'type': 'appAssetLibraries', 'id': library}}}
            }})['data']
            item = {'fileName': file.name, 'id': resource['id'], 'sha256': card['sha256']}
            receipt['assets'].append(item)
            RECEIPT.write_text(json.dumps(receipt, indent=2) + '\n')
        attrs = resource['attributes']
        if attrs['state'] == 'AWAITING_UPLOAD':
            for op in attrs['uploadOperations']:
                content = binary[op['offset']:op['offset'] + op['length']]
                headers = {h['name']:h['value'] for h in op['requestHeaders']}
                request = urllib.request.Request(op['url'], data=content, headers=headers, method=op['method'])
                with urllib.request.urlopen(request, timeout=120) as response:
                    assert 200 <= response.status < 300
            resource = api('PATCH', '/v1/appAssetLibraryImages/' + resource['id'], {'data': {
                'type': 'appAssetLibraryImages', 'id': resource['id'], 'attributes': {'uploaded': True}}})['data']
        item['state'] = resource['attributes']['state']
        item['specId'] = resource['attributes'].get('specId')
        item['imageAsset'] = resource['attributes'].get('imageAsset')
        if item['specId']:
            assert item['specId'] in specs
        RECEIPT.write_text(json.dumps(receipt, indent=2) + '\n')
        print(file.name, item['id'], item['state'], flush=True)

if __name__ == '__main__':
    run()
