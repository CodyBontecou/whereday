import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
import warnings
import zipfile

from release_bundle import fingerprint, fetch_bundletool, password_file, validate_manifest

MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.bontecou.whereday" android:versionCode="7" android:versionName="0.1.0-qa">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
<application android:allowBackup="false" android:fullBackupContent="false" android:dataExtractionRules="@xml/data_extraction_rules">
<service android:name="com.bontecou.whereday.platform.RecordingService" android:foregroundServiceType="location" android:exported="false" android:stopWithTask="false"/>
</application></manifest>'''


class ReleaseManifestTest(unittest.TestCase):
    def test_expected_release_identity(self):
        self.assertEqual(validate_manifest(MANIFEST, 7, "0.1.0-qa")["targetSdk"], 36)

    def test_compiled_location_type_flags(self):
        for value in ['0x00000008', '8', '0x00000009', 'location|dataSync']:
            validate_manifest(MANIFEST.replace('foregroundServiceType="location"', f'foregroundServiceType="{value}"'), 7, '0.1.0-qa')
        for value in ['0x00000001', '1', 'garbage']:
            with self.assertRaises(ValueError):
                validate_manifest(MANIFEST.replace('foregroundServiceType="location"', f'foregroundServiceType="{value}"'), 7, '0.1.0-qa')

    def test_rejects_qa_version_sdk_and_debug_manifest(self):
        for original, replacement in [("com.bontecou.whereday\"", "com.bontecou.whereday.mapqa\""),
                                      ('versionCode="7"', 'versionCode="8"'),
                                      ('targetSdkVersion="36"', 'targetSdkVersion="35"'),
                                      ('minSdkVersion="29"', 'minSdkVersion="28"'),
                                      ('<application ', '<application android:debuggable="true" '),
                                      ('<application ', '<application android:testOnly="true" '),
                                      ('allowBackup="false"', 'allowBackup="true"'),
                                      ('fullBackupContent="false"', 'fullBackupContent="true"'),
                                      (' android:dataExtractionRules="@xml/data_extraction_rules"', ''),
                                      ('foregroundServiceType="location"', 'foregroundServiceType="dataSync"'),
                                      ('android:exported="false"', 'android:exported="true"'),
                                      ('android:stopWithTask="false"', 'android:stopWithTask="true"'),
                                      ('com.bontecou.whereday.platform.RecordingService', 'unrelated.LocationService'),
                                      ('android.permission.FOREGROUND_SERVICE_LOCATION', 'android.permission.INTERNET')]:
            with self.subTest(original=original), self.assertRaises(ValueError):
                validate_manifest(MANIFEST.replace(original, replacement), 7, "0.1.0-qa")

    def test_rejects_diagnostic_provider_and_test_components(self):
        for node in ['<provider android:name=".DebugDiagnosticsProvider"/>',
                     '<provider android:authorities="com.bontecou.whereday.diagnostics"/>',
                     '<activity android:name="androidx.compose.ui.tooling.PreviewActivity"/>',
                     '<activity android:name=".TestActivity"/>']:
            with self.subTest(node=node), self.assertRaises(ValueError):
                validate_manifest(MANIFEST.replace('</application>', node + '</application>'), 7, "0.1.0-qa")

    def test_fingerprint_format(self):
        self.assertEqual(fingerprint(':'.join(['AB'] * 32)), 'ab' * 32)
        for bad in ['ab', 'g' * 64, '0' * 64 + '\n']:
            with self.assertRaises(ValueError): fingerprint(bad)

    def test_existing_tool_requires_exact_pinned_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'bundletool.jar'; path.write_bytes(b'not the pinned tool')
            with self.assertRaises(ValueError): fetch_bundletool(path)
            self.assertEqual(path.read_bytes(), b'not the pinned tool')

    def test_password_file_is_private_exclusive_and_single_line(self):
        with tempfile.TemporaryDirectory() as directory:
            path = password_file(directory, 'password', 'disposable value')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.read_text(), 'disposable value\n')
            with self.assertRaises(FileExistsError): password_file(directory, 'password', 'replacement')
            for value in ['', 'value\nsecond line', 'value\r', 'value\0']:
                with self.assertRaises(ValueError): password_file(directory, 'invalid', value)


class BundleSignatureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='whereday-signature-test-')
        cls.root = Path(cls.temporary.name)
        cls.env = dict(os.environ, WHEREDAY_TEST_PASS=secrets.token_urlsafe(24))
        cls.helper = Path(__file__).with_name('VerifyBundleSignature.java').resolve()
        cls.certs = {}
        for alias, subject in [('upload', 'CN=Whereday Disposable QA'), ('debug', 'CN=Android Debug,O=Android,C=US')]:
            store = cls.root / f'{alias}.jks'; cert = cls.root / f'{alias}.der'
            cls.run_tool(['keytool', '-genkeypair', '-alias', alias, '-dname', subject, '-keyalg', 'RSA', '-keysize', '2048',
                          '-validity', '2', '-keystore', str(store), '-storepass:env', 'WHEREDAY_TEST_PASS', '-keypass:env', 'WHEREDAY_TEST_PASS'])
            cls.run_tool(['keytool', '-exportcert', '-alias', alias, '-keystore', str(store), '-storepass:env', 'WHEREDAY_TEST_PASS', '-file', str(cert)])
            cls.certs[alias] = hashlib.sha256(cert.read_bytes()).hexdigest()
        cls.unsigned = cls.root / 'unsigned.aab'
        with zipfile.ZipFile(cls.unsigned, 'w') as archive:
            archive.writestr('base/dex/classes.dex', b'synthetic payload')
            archive.writestr('META-INF/license.txt', b'signed metadata payload')
        for alias in cls.certs:
            cls.run_tool(['jarsigner', '-keystore', str(cls.root / f'{alias}.jks'), '-storepass:env', 'WHEREDAY_TEST_PASS',
                          '-keypass:env', 'WHEREDAY_TEST_PASS', '-signedjar', str(cls.root / f'{alias}.aab'), str(cls.unsigned), alias])

    @classmethod
    def run_tool(cls, command):
        result = subprocess.run(command, env=cls.env, capture_output=True, timeout=60)
        if result.returncode: raise AssertionError('Disposable signing fixture command failed')
        return result

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def verify(self, path, fingerprint=None):
        return subprocess.run(['java', str(self.helper), str(path), fingerprint or self.certs['upload']], capture_output=True, text=True, timeout=60)

    def test_every_payload_signed_by_expected_certificate(self):
        result = self.verify(self.root / 'upload.aab')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '2')

    def test_unsigned_and_wrong_certificate_are_rejected(self):
        self.assertNotEqual(self.verify(self.unsigned).returncode, 0)
        self.assertNotEqual(self.verify(self.root / 'upload.aab', '0' * 64).returncode, 0)

    def test_debug_certificate_rejected_even_when_fingerprint_matches(self):
        self.assertNotEqual(self.verify(self.root / 'debug.aab', self.certs['debug']).returncode, 0)

    def test_tampered_signed_payload_is_rejected(self):
        changed = self.root / 'tampered.aab'
        with zipfile.ZipFile(self.root / 'upload.aab') as source, zipfile.ZipFile(changed, 'w') as destination:
            for entry in source.infolist():
                destination.writestr(entry, b'tampered' if entry.filename.endswith('classes.dex') else source.read(entry))
        self.assertNotEqual(self.verify(changed).returncode, 0)

    def test_unsigned_payload_added_after_signing_is_rejected_including_meta_inf(self):
        for name in ['base/assets/injected.txt', 'META-INF/injected.txt']:
            changed = self.root / 'injected.aab'
            changed.write_bytes((self.root / 'upload.aab').read_bytes())
            with zipfile.ZipFile(changed, 'a') as archive: archive.writestr(name, b'unsigned')
            self.assertNotEqual(self.verify(changed).returncode, 0)

    def test_duplicate_entries_rejected(self):
        changed = self.root / 'duplicate.aab'
        changed.write_bytes((self.root / 'upload.aab').read_bytes())
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(changed, 'a') as archive: archive.writestr('base/dex/classes.dex', b'synthetic payload')
        self.assertNotEqual(self.verify(changed).returncode, 0)


if __name__ == '__main__':
    unittest.main()
