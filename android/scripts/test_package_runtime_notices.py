import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from package_runtime_notices import identity, package, sha


class PackageRuntimeNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.addCleanup(self.temporary.cleanup)
        self.root=Path(self.temporary.name);self.sources=self.root/'sources';self.upstream=self.sources/'upstream'
        self.upstream.mkdir(parents=True);self.report=self.root/'collected';self.report.mkdir()
        self.output=self.root/'assets'
        apache=b'SYNTHETIC LICENSE\r\nExact fixture.\r\n'
        (self.upstream/'apache-2.0.txt').write_bytes(apache)
        (self.sources/'sources.json').write_text(json.dumps({'schemaVersion':1,'files':{
            'apache-2.0.txt':{'sha256':sha(apache),'bytes':len(apache),'url':'https://example.invalid/not-fetched'}}}))
        self.component={'coordinate':'example:library:1.0','pom':{'sha256':'a'*64,'name':'Fixture library',
            'licensesDeclaredHere':[{'url':'https://www.apache.org/licenses/LICENSE-2.0.txt'}]},
            'artifacts':[{'sha256':'b'*64,'embeddedNotices':[]}]}
        self.inventory=self.report/'inventory.json'
        self.save()

    def save(self,update_lock=True):
        self.inventory.write_text(json.dumps({'schemaVersion':1,'components':[self.component]}))
        if update_lock:
            (self.sources/'runtime-lock.json').write_text(json.dumps({'schemaVersion':1,
                'components':{self.component['coordinate']:identity(self.component)}}))

    def embedded(self,name,raw):
        digest=sha(raw);path='documents/'+digest+'.txt';(self.report/'documents').mkdir(exist_ok=True)
        (self.report/path).write_bytes(raw)
        self.component['artifacts'][0]['embeddedNotices'].append({'entry':name,'document':path,'sha256':digest,'bytes':len(raw)})
        self.save()

    def test_original_bytes_are_packaged_and_metadata_indexes_are_not_shown_as_licenses(self):
        raw='Copyright © fixture\r\nExact body\r\n'.encode()
        self.embedded('third_party_licenses.txt',raw)
        self.embedded('third_party_licenses.json',b'{"index":true}')
        with patch('urllib.request.urlopen',side_effect=AssertionError('Must remain offline')):
            catalog=package(self.inventory,self.sources,self.output)
        docs=catalog['entries'][0]['documents'];self.assertEqual(2,len(docs))
        self.assertEqual(raw,(self.output/'software-notices/documents'/ (sha(raw)+'.txt')).read_bytes())
        self.assertNotIn('third_party_licenses.json',[d['title'] for d in docs])
        proof=json.loads((self.output/'software-notices/provenance.json').read_text())
        self.assertFalse(proof['legalReviewComplete']);self.assertEqual(2,len(proof['preservedEmbedded']))
        self.assertNotIn(str(self.root),json.dumps(catalog))

    def test_empty_embedded_payload_is_preserved_without_a_blank_reader_entry(self):
        self.embedded('third_party_licenses.txt',b'')
        catalog=package(self.inventory,self.sources,self.output)
        self.assertEqual(1,len(catalog['entries'][0]['documents']))
        self.assertTrue((self.output/'software-notices/documents'/(sha(b'')+'.txt')).exists())

    def test_changed_artifact_or_pom_requires_a_notice_lock_review(self):
        for field in ['artifact','pom']:
            with self.subTest(field=field):
                self.save()
                if field=='artifact':self.component['artifacts'][0]['sha256']='c'*64
                else:self.component['pom']['sha256']='d'*64
                self.save(update_lock=False)
                with self.assertRaisesRegex(ValueError,'inputs changed'):package(self.inventory,self.sources,self.output)

    def test_missing_offline_document_is_a_build_error(self):
        self.component['pom']['licensesDeclaredHere']=[];self.save()
        with self.assertRaisesRegex(ValueError,'No offline notice'):package(self.inventory,self.sources,self.output)

    def test_maplibre_native_vendor_originals_are_not_lost_in_the_aggregate(self):
        names = ['maplibre-android-13.4.1.txt', 'maplibre-native-13.4.1.txt',
                 'maplibre-harfbuzz.txt', 'maplibre-unordered-dense.txt',
                 'maplibre-pmtiles.txt', 'maplibre-icu.txt', 'maplibre-jni-hpp.txt', 'maplibre-tile-format.txt',
                 'maplibre-vulkan-headers.txt', 'maplibre-vulkan-memory-allocator.txt', 'maplibre-glslang.txt']
        manifest=json.loads((self.sources/'sources.json').read_text())
        originals={name:('Original '+name+'\r\nCopyright © fixture.\r\n').encode() for name in names}
        for name,raw in originals.items():
            (self.upstream/name).write_bytes(raw)
            manifest['files'][name]={'sha256':sha(raw),'bytes':len(raw),'url':'https://example.invalid/not-fetched'}
        (self.sources/'sources.json').write_text(json.dumps(manifest))
        self.component['coordinate']='org.maplibre.gl:android-sdk:13.4.1'
        self.component['pom']['licensesDeclaredHere']=[];self.save()
        catalog=package(self.inventory,self.sources,self.output)
        documents=catalog['entries'][0]['documents']
        apache=(self.upstream/'apache-2.0.txt').read_bytes()
        self.assertEqual({sha(raw) for raw in originals.values()}|{sha(apache)},{d['sha256'] for d in documents})
        for raw in originals.values():self.assertEqual(raw,(self.output/'software-notices/documents'/(sha(raw)+'.txt')).read_bytes())

    def test_corrupt_embedded_or_pinned_source_bytes_are_rejected(self):
        self.embedded('NOTICE',b'original')
        document=self.component['artifacts'][0]['embeddedNotices'][0]['document']
        (self.report/document).write_bytes(b'changed')
        with self.assertRaises(ValueError):package(self.inventory,self.sources,self.output)
        (self.report/document).write_bytes(b'original')
        (self.upstream/'apache-2.0.txt').write_bytes(b'changed')
        with self.assertRaises(ValueError):package(self.inventory,self.sources,self.output)

    def test_provenance_paths_cannot_escape_the_collected_root(self):
        self.embedded('NOTICE',b'outside')
        outside=self.root/'outside';outside.write_bytes(b'outside')
        self.component['artifacts'][0]['embeddedNotices'][0]['document']='../outside';self.save()
        with self.assertRaises(ValueError):package(self.inventory,self.sources,self.output)

    def test_symlink_source_cannot_escape_the_pinned_source_root(self):
        outside=self.root/'outside';outside.write_bytes((self.upstream/'apache-2.0.txt').read_bytes())
        source=self.upstream/'apache-2.0.txt';source.unlink();source.symlink_to(outside)
        with self.assertRaises(ValueError):package(self.inventory,self.sources,self.output)

    def test_failed_generation_preserves_the_previous_complete_asset_set(self):
        package(self.inventory,self.sources,self.output)
        previous=(self.output/'software-notices/catalog.json').read_bytes()
        self.embedded('NOTICE',b'original');ref=self.component['artifacts'][0]['embeddedNotices'][0]
        (self.report/ref['document']).write_bytes(b'corrupt')
        with self.assertRaises(ValueError):package(self.inventory,self.sources,self.output)
        self.assertEqual(previous,(self.output/'software-notices/catalog.json').read_bytes())
        self.assertEqual(['software-notices'],[p.name for p in self.output.iterdir()])

    def test_repeated_generation_is_byte_identical_and_keeps_no_stale_documents(self):
        package(self.inventory,self.sources,self.output)
        root=self.output/'software-notices'
        previous={str(p.relative_to(root)):p.read_bytes() for p in root.rglob('*') if p.is_file()}
        (root/'documents/stale.txt').write_bytes(b'stale fixture')
        package(self.inventory,self.sources,self.output)
        self.assertEqual(previous,{str(p.relative_to(root)):p.read_bytes() for p in root.rglob('*') if p.is_file()})


if __name__=='__main__':unittest.main()
