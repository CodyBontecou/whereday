import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

import verify_native_provenance as verifier


def elf(abi="arm64-v8a", body=b"original"):
    identity, machine = verifier.ABI_ELF[abi]
    header = bytearray(64)
    header[:6] = b"\x7fELF" + bytes([identity, 1])
    struct.pack_into("<H", header, 18, machine)
    return bytes(header) + body


class NativeProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.aar = self.root / "native.aar"
        self.pom = self.root / "published.pom"
        self.pom.write_text("<project>Original published metadata</project>")
        self.inputs = self.root / "inputs.json"
        self.lock = self.root / "lock.json"
        self.package = self.root / "app.apk"
        self.coordinate = "example:native:1"
        self.source()
        self.packaged()

    def source(self, entries=None):
        with zipfile.ZipFile(self.aar, "w") as archive:
            for name, raw in (entries or {"jni/arm64-v8a/libexample.so": elf(), "prefab/modules/example/libs/android.arm64-v8a/libexample.so": elf(), "inspector.jar": b"not-runtime-native"}).items():
                archive.writestr(name, raw)
        self.relock()

    def relock(self):
        self.inputs.write_text(json.dumps({"schemaVersion": 1, "components": [{"coordinate": self.coordinate, "pom": str(self.pom), "artifacts": [str(self.aar)]}]}))
        self.lock.write_text(json.dumps({"schemaVersion": 1, "components": {self.coordinate: {"pomSha256": verifier.file_digest(self.pom), "artifacts": [verifier.file_digest(self.aar)]}}}))

    def packaged(self, entries=None, bundle=False):
        prefix = "base/lib/" if bundle else "lib/"
        with zipfile.ZipFile(self.package, "w") as archive:
            if bundle:
                archive.writestr("base/manifest/AndroidManifest.xml", b"manifest")
            for name, raw in (entries or {prefix + "arm64-v8a/libexample.so": elf()}).items():
                archive.writestr(name, raw)

    def verify(self, strip=lambda _: b"wrong stripped bytes"):
        return verifier.verify(self.package, self.inputs, self.lock, strip)

    def test_apk_and_bundle_prove_exact_source_without_counting_prefab_or_inspector(self):
        for bundle in (False, True):
            with self.subTest(bundle=bundle):
                self.packaged(bundle=bundle)
                result = self.verify(lambda _: self.fail("Unchanged input must not invoke strip"))
                self.assertEqual(1, result["nativeComponentCount"])
                self.assertEqual(1, result["nativeFileCount"])
                self.assertEqual("unchanged", result["libraries"][0]["transformation"])
                self.assertEqual(self.coordinate, result["libraries"][0]["coordinate"])
                self.assertFalse(result["legalReviewComplete"])
                self.assertNotIn(str(self.root), json.dumps(result))

    def test_only_exact_strip_output_is_accepted(self):
        stripped = elf(body=b"stripped")
        self.packaged({"lib/arm64-v8a/libexample.so": stripped})
        calls = []
        def strip(raw):
            calls.append(raw)
            return stripped
        result = self.verify(strip)
        self.assertEqual([elf()], calls)
        self.assertEqual("ndk-strip-unneeded", result["libraries"][0]["transformation"])
        self.assertNotEqual(result["libraries"][0]["sourceSha256"], result["libraries"][0]["packagedSha256"])

    def test_same_name_with_changed_native_code_fails(self):
        self.packaged({"lib/arm64-v8a/libexample.so": elf(body=b"modified code")})
        with self.assertRaisesRegex(ValueError, "bytes do not match"):
            self.verify()

    def test_added_missing_and_unexpected_location_fail(self):
        for entries in ({"lib/arm64-v8a/libnew.so": elf()}, {"lib/arm64-v8a/libexample.so": elf(), "lib/x86/libnew.so": elf("x86")}, {"assets/libexample.so": elf()}):
            with self.subTest(entries=list(entries)):
                self.packaged(entries)
                with self.assertRaises(ValueError):
                    self.verify()

    def test_elf_identity_must_match_abi(self):
        self.packaged({"lib/arm64-v8a/libexample.so": elf("x86_64")})
        with self.assertRaisesRegex(ValueError, "ELF identity"):
            self.verify()

    def test_changed_artifact_and_pom_are_rejected_against_lock(self):
        self.pom.write_text("changed POM")
        with self.assertRaisesRegex(ValueError, "runtime lock"):
            self.verify()
        self.relock()
        with zipfile.ZipFile(self.aar, "a") as archive:
            archive.writestr("extra", b"changed artifact")
        with self.assertRaisesRegex(ValueError, "runtime lock"):
            self.verify()

    def test_missing_metadata_only_component_is_detected(self):
        lock = json.loads(self.lock.read_text())
        lock["components"]["example:bom:1"] = {"pomSha256": "a" * 64, "artifacts": []}
        self.lock.write_text(json.dumps(lock))
        with self.assertRaisesRegex(ValueError, "component set"):
            self.verify()

    def test_two_artifacts_cannot_claim_the_same_runtime_library(self):
        second = self.root / "second.aar"
        second.write_bytes(self.aar.read_bytes())
        inputs = json.loads(self.inputs.read_text())
        inputs["components"][0]["artifacts"].append(str(second))
        self.inputs.write_text(json.dumps(inputs))
        with self.assertRaisesRegex(ValueError, "Ambiguous native ownership"):
            self.verify()

    def test_duplicate_zip_entries_fail_even_when_bytes_match(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.package, "a") as archive:
                archive.writestr("lib/arm64-v8a/libexample.so", elf())
        with self.assertRaisesRegex(ValueError, "Duplicate ZIP"):
            self.verify()

    def test_size_bounds_reject_before_reading_uncompressed_payload(self):
        from unittest.mock import patch
        with patch.object(verifier, "MAX_LIBRARY", 64):
            with self.assertRaisesRegex(ValueError, "size bound"):
                self.verify()
        with patch.object(verifier, "MAX_NATIVE_TOTAL", 64):
            with self.assertRaisesRegex(ValueError, "aggregate size"):
                self.verify()

    def test_malformed_source_native_path_and_unsupported_abi_fail(self):
        for entry in ("jni/arm64-v8a/nested/libexample.so", "jni/mips/libexample.so"):
            self.source({entry: elf()})
            with self.assertRaises(ValueError):
                self.verify()

    def test_ndk_revision_and_version_catalog_are_pinned(self):
        (self.root / "source.properties").write_text("Pkg.Revision = 1.2.3\n")
        with self.assertRaisesRegex(ValueError, "NDK revision"):
            verifier.NdkStrip(self.root, "4.5.6")
        catalog = self.root / "versions.toml"
        catalog.write_text('[versions]\nndk = "27.0.12077973"\n')
        self.assertEqual("27.0.12077973", verifier.ndk_version(catalog))
        catalog.write_text('ndk = "latest"\n')
        with self.assertRaisesRegex(ValueError, "pinned NDK"):
            verifier.ndk_version(catalog)

    def test_failed_cli_verification_removes_an_old_passing_report(self):
        output=self.root/'report.json';output.write_text('{"oldPassingReport":true}')
        result=subprocess.run([sys.executable,str(Path(verifier.__file__)),str(self.package),str(self.inputs),str(self.lock),
                               '--ndk-root',str(self.root/'missing-ndk'),'--output',str(output)],
                              stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        self.assertNotEqual(0,result.returncode)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
