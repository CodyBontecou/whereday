#!/usr/bin/env python3
"""Run Lighthouse sequentially for every page, preserving reports and scores."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from check_site import PUBLIC, page_url, BASE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=BASE)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--device', choices=['mobile', 'desktop', 'both'], default='both')
    parser.add_argument('--path', action='append', help='Audit only this URL path (repeatable)')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    paths = args.path or sorted(page_url(p).removeprefix(BASE) for p in PUBLIC.rglob('*.html') if p.name != '404.html')
    devices = ['mobile', 'desktop'] if args.device == 'both' else [args.device]
    results = []
    for device in devices:
        for path in paths:
            name = (path.strip('/').replace('/', '-') or 'home') + '-' + device
            report = args.output / name
            command = ['npx', '--yes', 'lighthouse@13.5.0', args.base_url.rstrip('/') + path,
                       '--chrome-flags=--headless=new',
                       '--only-categories=performance,accessibility,best-practices,seo',
                       '--output=json', '--output=html', '--output-path=' + str(report), '--quiet']
            if device == 'desktop':
                command.append('--preset=desktop')
            subprocess.run(command, check=True)
            data = json.loads(report.with_suffix('.report.json').read_text())
            if data.get('runtimeError'):
                raise RuntimeError(data['runtimeError'])
            scores = {k: round(v['score'] * 100) for k, v in data['categories'].items()}
            results.append({'path': path, 'device': device, 'scores': scores,
                            'metrics': {k: data['audits'][k].get('numericValue') for k in
                                        ['first-contentful-paint', 'largest-contentful-paint',
                                         'total-blocking-time', 'cumulative-layout-shift', 'speed-index']}})
            (args.output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
            print(device, path, scores, flush=True)
    return 0 if all(all(s == 100 for s in r['scores'].values()) for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
