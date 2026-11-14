#!/usr/bin/env python3
"""Record source drift against the frozen baseline; this does not approve parity."""
import argparse
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASELINE=ROOT/'android/ios-baseline.json'
OUTPUT=ROOT/'android/ios-source-observation.json'

def observe():
    baseline=json.loads(BASELINE.read_text());old={item['path']:item['sha256'] for item in baseline['files']}
    paths=set(old)
    for folder in ['IsoMe','IsoMeWatch','IsoMeWidget','IsoMeWatchWidget','Shared','IsoMeTests']:
        paths.update(str(p.relative_to(ROOT)) for p in (ROOT/folder).rglob('*.swift'))
    rows=[]
    for name in sorted(paths):
        path=ROOT/name;current=hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        previous=old.get(name)
        state='missing' if current is None else 'added' if previous is None else 'unchanged' if current==previous else 'changed'
        rows.append({'path':name,'baselineSha256':previous,'observedSha256':current,'state':state})
    return {'scope':'Frozen baseline files plus current phone/watch/widget/shared/test Swift files; observation only, no behavior approval','baselineSha256':hashlib.sha256(BASELINE.read_bytes()).hexdigest(),'baselineHead':baseline['head'],'counts':{state:sum(r['state']==state for r in rows) for state in ['unchanged','changed','added','missing']},'files':rows}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args();value=observe()
    if args.check:
        if not OUTPUT.exists() or json.loads(OUTPUT.read_text())!=value:raise SystemExit('iOS source observation changed; inspect current files before recording another observation. This is not a parity verdict.')
    else:OUTPUT.write_text(json.dumps(value,indent=2)+'\n')
    print(json.dumps({'observationMatches':True,'parityApproved':False,'counts':value['counts']}))
if __name__=='__main__':main()
