import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from runtime_notices import archive_inventory, collect, digest, published_metadata


def archive(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as value:
        for name, raw in entries.items():
            value.writestr(name, raw)
    return stream.getvalue()


class RuntimeNoticesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.documents = self.root / "documents"
        self.documents.mkdir()

    def write(self, name, raw):
        path = self.root / name
        path.write_bytes(raw)
        return path

    def test_nested_aar_notices_keep_exact_bytes_and_container_identity(self):
        notice = b"Copyright synthetic fixture\r\nNo normalization.\r\n"
        jar = archive({"META-INF/NOTICE.txt": notice, "example/Notice.class": b"not a notice"})
        path = self.write("fixture.aar", archive({"classes.jar": jar,
                         "third_party_licenses.json": b'{"sample":true}',
                         "jni/arm64-v8a/libsample.so": b"synthetic"}))
        result = archive_inventory(path, self.documents)
        self.assertEqual(["jni/arm64-v8a/libsample.so"], result["nativeLibraries"])
        self.assertEqual(2, len(result["embeddedNotices"]))
        entry = next(item for item in result["embeddedNotices"] if item["entry"].startswith("classes.jar!/"))
        self.assertEqual("classes.jar!/META-INF/NOTICE.txt", entry["entry"])
        self.assertEqual(notice, (self.root / entry["document"]).read_bytes())
        self.assertEqual(digest(notice), entry["sha256"])

    def test_archive_entry_paths_cannot_escape_content_addressed_output(self):
        path = self.write("paths.jar", archive({"../../LICENSE.txt": b"fixture", "/NOTICE": b"fixture"}))
        result = archive_inventory(path, self.documents)
        self.assertEqual(2, len(result["embeddedNotices"]))
        self.assertEqual(1, len(list(self.documents.iterdir())))
        self.assertFalse((self.root / "LICENSE.txt").exists())
        self.assertTrue(all(item.name == digest(b"fixture") + ".txt" for item in self.documents.iterdir()))

    def test_binary_notices_are_preserved_and_marked_for_review(self):
        path = self.write("binary.jar", archive({"LICENSE": b"\xff\xfe"}))
        notice = archive_inventory(path, self.documents)["embeddedNotices"][0]
        self.assertEqual("unreviewed-binary", notice["encoding"])
        self.assertEqual(b"\xff\xfe", (self.root / notice["document"]).read_bytes())

    def test_oversized_notice_and_nested_jar_are_rejected(self):
        path = self.write("large.jar", archive({"NOTICE": b"12345"}))
        with patch("runtime_notices.MAX_NOTICE", 4), self.assertRaises(ValueError):
            archive_inventory(path, self.documents)
        path = self.write("large.aar", archive({"classes.jar": b"12345"}))
        with patch("runtime_notices.MAX_NESTED_JAR", 4), self.assertRaises(ValueError):
            archive_inventory(path, self.documents)

    def test_aggregate_content_and_entry_count_are_bounded(self):
        path = self.write("many.jar", archive({"NOTICE.txt": b"123", "LICENSE.txt": b"456"}))
        with patch("runtime_notices.MAX_NOTICE_TOTAL", 5), self.assertRaises(ValueError):
            archive_inventory(path, self.documents)
        with patch("runtime_notices.MAX_ENTRIES", 1), self.assertRaises(ValueError):
            archive_inventory(path, self.documents)

    def test_pom_parent_is_recorded_without_inventing_inherited_license(self):
        path = self.write("parent.pom", b'<project xmlns="http://maven.apache.org/POM/4.0.0">'
                          b'<parent><groupId>example</groupId><artifactId>parent</artifactId>'
                          b'<version>1</version></parent></project>')
        result = published_metadata(path)
        self.assertEqual([], result["licensesDeclaredHere"])
        self.assertEqual({"groupId": "example", "artifactId": "parent", "version": "1"}, result["parent"])

    def test_entity_bearing_pom_is_rejected_without_external_reads(self):
        xml = '<!DOCTYPE project [<!ENTITY secret SYSTEM "file:///etc/passwd">]>' \
              '<project><name>&secret;</name></project>'
        for encoding in ["utf-8", "utf-16"]:
            with self.subTest(encoding=encoding):
                path = self.write("unsafe.pom", xml.encode(encoding))
                with self.assertRaises(ValueError):
                    published_metadata(path)

    def inputs(self, artifact=True):
        pom = self.write("selected.pom", b'<project><name>Selected fixture</name><licenses><license>'
                         b'<name>Fixture License</name><url>https://example.invalid/do-not-fetch</url>'
                         b'</license></licenses></project>')
        jar = self.write("selected.jar", archive({"lib/Code.class": b"synthetic"}))
        self.write("unused-cache-file.jar", archive({"LICENSE": b"must not be included"}))
        value = {"schemaVersion": 1, "configuration": "releaseRuntimeClasspath", "components": [
            {"coordinate": "example:selected:1.0", "pom": str(pom), "artifacts": [str(jar)] if artifact else []}]}
        path = self.root / "inputs.json"
        path.write_text(json.dumps(value))
        return path

    def test_only_selected_modules_are_reported_without_host_paths_or_legal_approval(self):
        inputs = self.inputs()
        result = collect(inputs, self.root / "output")
        self.assertFalse(result["reviewComplete"])
        self.assertEqual(["example:selected:1.0"], [item["coordinate"] for item in result["components"]])
        self.assertEqual(["no-embedded-notice-found"], result["components"][0]["reviewFindings"])
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertEqual([], list((self.root / "output/documents").iterdir()))
        pom = result["components"][0]["pom"]
        preserved = (self.root / "output" / pom["document"]).read_bytes()
        self.assertEqual((self.root / "selected.pom").read_bytes(), preserved)
        self.assertEqual(digest(preserved), pom["sha256"])
        self.assertEqual(result, collect(inputs, self.root / "again"))

    def test_metadata_only_modules_are_retained_and_duplicate_coordinates_rejected(self):
        inputs = self.inputs(artifact=False)
        result = collect(inputs, self.root / "output")
        self.assertEqual([], result["components"][0]["artifacts"])
        raw = json.loads(inputs.read_text())
        raw["components"] *= 2
        inputs.write_text(json.dumps(raw))
        with self.assertRaises(ValueError):
            collect(inputs, self.root / "duplicate")

    def test_failed_refresh_cannot_leave_an_old_inventory_looking_current(self):
        inputs = self.inputs()
        destination = self.root / "output"
        collect(inputs, destination)
        self.assertTrue((destination / "inventory.json").exists())
        inputs.write_text('{"schemaVersion":999}')
        with self.assertRaises(ValueError):
            collect(inputs, destination)
        self.assertFalse((destination / "inventory.json").exists())

    def test_the_same_selected_file_exposed_by_two_variants_is_counted_once(self):
        inputs = self.inputs()
        raw = json.loads(inputs.read_text())
        raw["components"][0]["artifacts"] *= 2
        inputs.write_text(json.dumps(raw))
        result = collect(inputs, self.root / "output")
        self.assertEqual(1, len(result["components"][0]["artifacts"]))


if __name__ == "__main__":
    unittest.main()
