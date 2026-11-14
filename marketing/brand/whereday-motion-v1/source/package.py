"""Package the two App Store placements, posters, and verification manifest."""
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / 'exports'
manifest = json.loads((output / 'manifest.json').read_text())
archive = output / 'whereday-app-store-assets.zip'
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
    for asset in manifest['assets']:
        if asset['file'].startswith(('whereday-header-', 'whereday-search-')):
            bundle.write(output / asset['file'], asset['file'])
    bundle.write(output / 'manifest.json', 'manifest.json')
    bundle.write(root / 'README.md', 'README.md')
print(f'App Store bundle: {archive} ({archive.stat().st_size / 1_000_000:.2f} MB)')
