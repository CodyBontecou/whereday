#!/usr/bin/env python3
"""Build offline notice assets from the resolved inventory and pinned source material.

The lock protects correspondence with reviewed inputs, not legal approval. Native bundled
dependencies and release review remain explicitly separate from this packaging coverage.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile

SHA = re.compile(r"[a-f0-9]{64}")
APACHE_URLS = {"http://www.apache.org/licenses/LICENSE-2.0.txt",
               "https://www.apache.org/licenses/LICENSE-2.0.txt"}


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def verified(root: Path, relative: str, expected: str, size: int | None = None) -> bytes:
    path = Path(relative)
    if path.is_absolute() or ".." in path.parts or not SHA.fullmatch(expected):
        raise ValueError("Invalid notice provenance path or digest")
    source = (root / path).resolve()
    if root.resolve() not in source.parents:
        raise ValueError("Notice provenance path escapes its root")
    raw = source.read_bytes()
    if len(raw) > 4 * 1024 * 1024 or sha(raw) != expected or (size is not None and len(raw) != size):
        raise ValueError("Notice provenance changed or exceeds its bound")
    return raw


def identity(component: dict) -> dict:
    return {"pomSha256": component["pom"]["sha256"],
            "artifacts": sorted({item["sha256"] for item in component["artifacts"]})}


def package(inventory: Path, source_root: Path, output: Path) -> dict:
    report = json.loads(inventory.read_text())
    lock = json.loads((source_root / "runtime-lock.json").read_text())
    sources = json.loads((source_root / "sources.json").read_text())
    if report.get("schemaVersion") != 1 or lock.get("schemaVersion") != 1 or sources.get("schemaVersion") != 1:
        raise ValueError("Unsupported notice packaging schema")
    components = report["components"]
    selected = {item["coordinate"]: identity(item) for item in components}
    if len(selected) != len(components) or selected != lock["components"]:
        raise ValueError("Runtime notice inputs changed; review the selected POMs/artifacts and update the notice lock")
    pinned = {name: verified(source_root / "upstream", name, item["sha256"], item["bytes"])
              for name, item in sources["files"].items()}
    output.mkdir(parents=True, exist_ok=True)
    destination = output / "software-notices"
    # Build in a private sibling: a failed generation cannot expose a partial asset set.
    with tempfile.TemporaryDirectory(dir=output, prefix=".software-notices-") as temporary:
        stage = Path(temporary)
        documents = stage / "documents"
        documents.mkdir()
        entries = []
        preserved = []

        def document(raw: bytes, title: str, allow_empty: bool = False) -> dict:
            raw.decode("utf-8", errors="strict")
            if (not raw and not allow_empty) or len(raw) > 4 * 1024 * 1024:
                raise ValueError("Empty or oversized software notice")
            digest = sha(raw)
            (documents / (digest + ".txt")).write_bytes(raw)
            return {"sha256": digest, "bytes": len(raw), "title": title}

        for component in components:
            if not component["artifacts"]:
                continue
            coordinate = component["coordinate"]
            refs = []
            declared = component["pom"]["licensesDeclaredHere"]
            if any(item["url"] in APACHE_URLS for item in declared):
                refs.append(document(pinned["apache-2.0.txt"], "Apache License, Version 2.0"))
            for artifact in component["artifacts"]:
                for embedded in artifact["embeddedNotices"]:
                    raw = verified(inventory.parent, embedded["document"], embedded["sha256"], embedded["bytes"])
                    basename = embedded["entry"].rsplit("/", 1)[-1]
                    ref = document(raw, basename, allow_empty=True)
                    preserved.append({"coordinate": coordinate, "artifactSha256": artifact["sha256"],
                                      "entry": embedded["entry"], **ref})
                    # Google's JSON is an offset index, not a license. Keep it for provenance;
                    # the complete companion text, including text outside index slices, is readable.
                    if raw and basename != "third_party_licenses.json":
                        refs.append(ref)
            if coordinate == "org.maplibre.gl:android-sdk:13.4.1":
                refs += [document(pinned["maplibre-android-13.4.1.txt"], "MapLibre Native Android"),
                         document(pinned["maplibre-native-13.4.1.txt"], "MapLibre Native")]
                # Exact release CMake/vendor attribution sources designate these originals;
                # the Android aggregate omits them. Keep each complete text separately.
                for filename, title in (("maplibre-harfbuzz.txt", "HarfBuzz"),
                                        ("maplibre-unordered-dense.txt", "unordered_dense"),
                                        ("maplibre-pmtiles.txt", "PMTiles"),
                                        ("maplibre-icu.txt", "ICU and bundled third-party notices"),
                                        ("maplibre-jni-hpp.txt", "jni.hpp"),
                                        ("maplibre-tile-format.txt", "MapLibre Tile Format"),
                                        ("maplibre-vulkan-headers.txt", "Vulkan Headers"),
                                        ("maplibre-vulkan-memory-allocator.txt", "Vulkan Memory Allocator"),
                                        ("maplibre-glslang.txt", "glslang and SPIRV bundled notices")):
                    refs.append(document(pinned[filename], title))
                # The selected AAR declares FLAVOR=vulkan. Headers' designated notice
                # links out to full terms; preserve it and supply the Apache option offline.
                refs.append(document(pinned["apache-2.0.txt"], "Apache License, Version 2.0 (Vulkan Headers)"))
            if coordinate == "org.maplibre.gl:maplibre-android-gestures:0.0.4":
                refs.append(document(pinned["maplibre-gestures-0.0.4.txt"], "MapLibre Android Gestures"))
            if coordinate == "com.google.guava:listenablefuture:1.0":
                # Explicit reviewed parent evidence; never infer a license from the publisher.
                from runtime_notices import published_metadata
                parent = published_metadata(source_root / "upstream/guava-parent-26.0-android.pom")
                if not any(item["url"] in APACHE_URLS for item in parent["licensesDeclaredHere"]):
                    raise ValueError("Guava parent license evidence changed")
                refs.append(document(pinned["apache-2.0.txt"], "Apache License, Version 2.0"))
            refs = list({item["sha256"]: item for item in refs}.values())
            if not refs:
                raise ValueError("No offline notice document for " + coordinate)
            name = component["pom"]["name"]
            if not name or "${" in name:
                name = coordinate.split(":")[1]
            entries.append({"id": sha(coordinate.encode()), "name": name,
                            "coordinate": coordinate, "documents": refs})
        catalog = {"schemaVersion": 1, "entries": sorted(entries, key=lambda item: (item["name"].casefold(), item["coordinate"]))}
        (stage / "catalog.json").write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
        provenance = {"schemaVersion": 1, "inventorySha256": sha(inventory.read_bytes()),
                      "sourceManifestSha256": sha((source_root / "sources.json").read_bytes()),
                      "runtimeLockSha256": sha((source_root / "runtime-lock.json").read_bytes()),
                      "legalReviewComplete": False, "preservedEmbedded": preserved}
        (stage / "provenance.json").write_text(json.dumps(provenance, indent=2, ensure_ascii=False) + "\n")
        if destination.exists():
            shutil.rmtree(destination)
        stage.rename(destination)
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("sources", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    value = package(args.inventory, args.sources, args.output)
    print(json.dumps({"softwareEntries": len(value["entries"]), "legalReviewComplete": False}))


if __name__ == "__main__":
    main()
