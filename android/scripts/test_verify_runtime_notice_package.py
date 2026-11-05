import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_runtime_notice_package import verify


class VerifyRuntimeNoticePackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name);self.generated=self.root/'generated';self.assets=self.generated/'software-notices'
        (self.assets/'documents').mkdir(parents=True)
        raw='Exact original © fixture\r\n'.encode();digest=hashlib.sha256(raw).hexdigest()
        self.document='documents/'+digest+'.txt'
        (self.assets/self.document).write_bytes(raw)
        ref={'sha256':digest,'bytes':len(raw),'title':'Fixture'}
        (self.assets/'catalog.json').write_text(json.dumps({'schemaVersion':1,'entries':[{'documents':[ref]}]}))
        (self.assets/'provenance.json').write_text(json.dumps({'schemaVersion':1,'preservedEmbedded':[ref]}))

    def archive(self,bundle=False,mutation=None):
        path=self.root/('app.aab' if bundle else 'app.apk')
        prefix='base/assets/software-notices/' if bundle else 'assets/software-notices/'
        with zipfile.ZipFile(path,'w') as zipped:
            zipped.writestr('base/manifest/AndroidManifest.xml' if bundle else 'AndroidManifest.xml',b'fixture')
            for p in self.assets.rglob('*'):
                if p.is_file():
                    name=str(p.relative_to(self.assets));raw=p.read_bytes()
                    if mutation=='missing' and name==self.document:continue
                    zipped.writestr(prefix+name,b'changed' if mutation=='changed' and name==self.document else raw)
            if mutation=='extra':zipped.writestr(prefix+'stale.txt',b'stale')
        return path

    def test_apk_and_bundle_both_preserve_the_exact_generated_set(self):
        for bundle in [False,True]:
            with self.subTest(bundle=bundle):
                result=verify(self.archive(bundle),self.generated)
                self.assertEqual(1,result['softwareEntries']);self.assertEqual(3,result['exactPackagedFiles'])
                self.assertFalse(result['legalReviewComplete'])

    def test_changed_notice_bytes_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'bytes differ'):verify(self.archive(mutation='changed'),self.generated)

    def test_missing_and_stale_packaged_files_are_rejected(self):
        for mutation in ['missing','extra']:
            with self.subTest(mutation=mutation),self.assertRaisesRegex(ValueError,'file set differs'):
                verify(self.archive(mutation=mutation),self.generated)

    def test_corrupt_generated_provenance_cannot_validate_a_matching_bad_archive(self):
        (self.assets/self.document).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError,'provenance does not match'):verify(self.archive(),self.generated)


if __name__=='__main__':unittest.main()
