"""Stage the uploaded Duo assets on the editable English 1.8.2 draft."""
import json
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]
api = runpy.run_path(str(Path(__file__).with_name('upload-library.py')))['api']
receipt_path = ROOT / 'upload-receipt.json'
receipt = json.loads(receipt_path.read_text())
version = json.loads(Path('/private/tmp/whereday-duo-draft-version.json').read_text())
localizations = json.loads(Path('/private/tmp/whereday-duo-draft-localizations.json').read_text())
localization = next(x['id'] for x in localizations['data'] if x['attributes']['locale'] == 'en-US')
existing = api('GET', f'/v1/appStoreVersionLocalizations/{localization}/placements')['data']
duo = [p for p in existing if p['attributes']['placementGroup'] == 'IPHONE_DUO_PROFILE']
allowed_ids = {p['placementId'] for p in receipt['assets'] if 'placementId' in p}
assert all(p['id'] in allowed_ids for p in duo), 'Unexpected existing Duo placements; preserve them.'
for item in receipt['assets']:
    image = api('GET', '/v1/appAssetLibraryImages/' + item['id'])['data']
    item['state'] = image['attributes']['state']
    item['specId'] = image['attributes'].get('specId')
    item['imageAsset'] = image['attributes'].get('imageAsset')
    assert item['state'] in ('UPLOAD_COMPLETE', 'PREPARE_FOR_SUBMISSION', 'APPROVED'), item
    if 'placementId' not in item:
        placement = api('POST', '/v1/appAssetLibraryPlacements', {'data': {
            'type': 'appAssetLibraryPlacements',
            'attributes': {'placementType': 'APP_SCREENSHOT', 'placementGroup': 'IPHONE_DUO_PROFILE'},
            'relationships': {
                'image': {'data': {'type': 'appAssetLibraryImages', 'id': item['id']}},
                'appStoreVersionLocalization': {'data': {'type': 'appStoreVersionLocalizations', 'id': localization}}
            }}})['data']
        item['placementId'] = placement['id']
        item['placementState'] = placement['attributes']['state']
        receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')
    print(item['fileName'], item['state'], item['placementId'], flush=True)
api('POST', '/v1/appAssetLibraryPlacementOrderingRequests', {'data': {
    'type': 'appAssetLibraryPlacementOrderingRequests',
    'attributes': {'placementGroup': 'IPHONE_DUO_PROFILE'},
    'relationships': {
        'orderedPlacements': {'data': [{'type': 'appAssetLibraryPlacements', 'id': item['placementId']} for item in receipt['assets']]},
        'appStoreVersionLocalization': {'data': {'type': 'appStoreVersionLocalizations', 'id': localization}}
    }}})
receipt.update({'placedOnVersion': '1.8.2', 'versionId': version['id'], 'localizationId': localization,
                'submittedForReview': False, 'releaseType': 'MANUAL'})
receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')
