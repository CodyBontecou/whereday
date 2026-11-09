#!/usr/bin/env python3
"""Run full current SwiftData importer admission scenarios without app or personal data."""
from pathlib import Path
import argparse,hashlib,json,platform,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
assert platform.system()=='Darwin','Requires actual macOS SwiftData'
sources=[ROOT/x for x in ['IsoMe/Models/Visit.swift','IsoMe/Models/LocationPoint.swift','IsoMe/Utilities/ImportService.swift','IsoMe/Utilities/GoogleTimelineImportService.swift','IsoMe/Utilities/TimelineImportService.swift',
    'IsoMe/Utilities/WheredayBackupService.swift', 'IsoMe/Models/RecordingSession.swift',
    'IsoMe/Models/PhotoMoment.swift', 'IsoMe/Models/CountryStay.swift', 'Shared/DistanceFormatter.swift']]
formats=ROOT/'IsoMe/Utilities/ExportService.swift';harness=ROOT/'android/scripts/fixtures/TimelineAdmission.swift'
text=formats.read_text();enum=text[text.index('enum ExportFormat:'):text.index('\nstruct ExportService {')]
with tempfile.TemporaryDirectory(prefix='whereday-admission-swift-') as tmp:
 work=Path(tmp);seam=work/'Formats.swift';seam.write_text('import Foundation\n'+enum);binary=work/'acceptance';arch='arm64' if platform.machine()=='arm64' else 'x86_64'
 subprocess.run(['swiftc','-parse-as-library','-target',f'{arch}-apple-macosx14.0','-module-cache-path',str(work/'modules'),*map(str,sources),str(seam),str(harness),'-o',str(binary)],check=True)
 raw=subprocess.check_output([str(binary)]);cases=json.loads(raw)
report={'scope':'Complete current Swift models, parsing, preview, fresh commit and SwiftData; only UIKit-dependent ExportFormat declaration extracted','cases':cases,'sourceSha256':{str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in sources+[formats,harness,Path(__file__).resolve()]}}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n');print('Passed',len(cases),'actual SwiftData admission scenarios')
