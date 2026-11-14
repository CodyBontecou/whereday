#!/usr/bin/env python3
"""Verify an APK/AAB contains the exact generated offline notice set; never certify legal review."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def verify(archive: Path, generated: Path) -> dict:
    root=generated/'software-notices'
    expected={str(p.relative_to(root)):p.read_bytes() for p in root.rglob('*') if p.is_file()}
    catalog=json.loads(expected['catalog.json']);provenance=json.loads(expected['provenance.json'])
    if catalog.get('schemaVersion')!=1 or provenance.get('schemaVersion')!=1:
        raise ValueError('Unsupported packaged notice schema')
    references=[doc for entry in catalog['entries'] for doc in entry['documents']]+provenance['preservedEmbedded']
    for doc in references:
        if not re.fullmatch('[a-f0-9]{64}',doc['sha256']):raise ValueError('Invalid notice digest')
        raw=expected['documents/'+doc['sha256']+'.txt']
        if len(raw)!=doc['bytes'] or hashlib.sha256(raw).hexdigest()!=doc['sha256']:
            raise ValueError('Generated notice provenance does not match its document')
    with zipfile.ZipFile(archive) as zipped:
        prefix='base/assets/software-notices/' if 'base/manifest/AndroidManifest.xml' in zipped.namelist() else 'assets/software-notices/'
        actual=[name for name in zipped.namelist() if name.startswith(prefix) and not name.endswith('/')]
        if len(actual)!=len(set(actual)) or set(actual)!={prefix+name for name in expected}:
            raise ValueError('Packaged notice file set differs from the generated set')
        for name,raw in expected.items():
            if zipped.read(prefix+name)!=raw:raise ValueError('Packaged notice bytes differ: '+name)
    return {'softwareEntries':len(catalog['entries']),'preservedEmbeddedEntries':len(provenance['preservedEmbedded']),
            'exactPackagedFiles':len(expected),'legalReviewComplete':False}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive',type=Path);parser.add_argument('generated',type=Path)
    args=parser.parse_args();print(json.dumps(verify(args.archive,args.generated)))


if __name__=='__main__':main()
