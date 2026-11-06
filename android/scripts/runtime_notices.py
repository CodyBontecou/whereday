#!/usr/bin/env python3
"""Inventory published metadata and exact embedded notices; never infer legal clearance.

Gradle supplies the selected runtime graph. This collector deliberately does not discover
libraries by walking a cache, fetch license URLs, follow POM parents, or treat missing
notices as proof that no notice is required. Native bundled dependencies need upstream review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import tempfile
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

MAX_NOTICE = 8 * 1024 * 1024
MAX_NESTED_JAR = 128 * 1024 * 1024
MAX_ENTRIES = 30_000
MAX_NOTICE_TOTAL = 64 * 1024 * 1024
NOTICE_NAME = re.compile(
    r"(?:licen[cs]es?|notice|copying|copyright)(?:[._-].*)?"
    r"|third[_-]party[_-](?:licenses?|notices?)(?:[._-].*)?"
    r"|AL2\.0|LGPL2\.1", re.IGNORECASE
)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def published_metadata(path: Path) -> dict:
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Oversized POM")
    raw = path.read_bytes()
    if (len(raw) > 2 * 1024 * 1024 or b"\x00" in raw
            or re.search(rb"<!\s*(?:DOCTYPE|ENTITY)", raw, re.IGNORECASE)):
        raise ValueError("Oversized or entity-bearing POM")
    root = ET.fromstring(raw)
    # Maven uses a default namespace; strip it only for field lookup, never rewrite the source.
    for node in root.iter():
        node.tag = node.tag.rsplit("}", 1)[-1]

    def text(node: ET.Element, name: str) -> str | None:
        value = node.findtext(name)
        return value.strip() if value and value.strip() else None

    parent = root.find("parent")
    return {
        "sha256": digest(raw),
        "name": text(root, "name"),
        "projectUrl": text(root, "url"),
        "licensesDeclaredHere": [
            {"name": text(item, "name"), "url": text(item, "url"),
             "distribution": text(item, "distribution")}
            for item in root.findall("licenses/license")
        ],
        "parent": None if parent is None else {
            key: text(parent, key) for key in ("groupId", "artifactId", "version")
        },
    }


def archive_inventory(path: Path, documents: Path) -> dict:
    notices: list[dict] = []
    native: list[str] = []
    total = 0

    def read_bounded(archive: zipfile.ZipFile, entry: zipfile.ZipInfo, limit: int) -> bytes:
        if entry.file_size > limit:
            raise ValueError(f"Archive entry exceeds inventory bound: {entry.filename}")
        with archive.open(entry) as stream:
            value = stream.read(limit + 1)
        if len(value) > limit:
            raise ValueError("Archive entry exceeds inventory bound")
        return value

    def visit(archive: zipfile.ZipFile, prefix: str = "", nested: bool = False) -> None:
        nonlocal total
        entries = archive.infolist()
        if len(entries) > MAX_ENTRIES:
            raise ValueError("Too many archive entries")
        for entry in sorted(entries, key=lambda item: item.filename):
            if entry.is_dir():
                continue
            name = prefix + entry.filename
            if entry.filename.endswith(".so"):
                native.append(name)
            basename = entry.filename.rsplit("/", 1)[-1]
            is_notice = NOTICE_NAME.fullmatch(basename) and not basename.lower().endswith(
                (".class", ".dex", ".so", ".jar", ".aar", ".kotlin_module")
            )
            if is_notice:
                raw = read_bounded(archive, entry, MAX_NOTICE)
                total += len(raw)
                if total > MAX_NOTICE_TOTAL:
                    raise ValueError("Aggregate notice content exceeds inventory bound")
                sha = digest(raw)
                try:
                    raw.decode("utf-8")
                    encoding, suffix = "utf-8", ".txt"
                except UnicodeDecodeError:
                    encoding, suffix = "unreviewed-binary", ".bin"
                filename = sha + suffix
                # Archive paths are metadata only. They never become filesystem paths.
                (documents / filename).write_bytes(raw)
                notices.append({"entry": name, "sha256": sha, "bytes": len(raw),
                                "encoding": encoding, "document": "documents/" + filename})
            elif not nested and entry.filename.endswith(".jar"):
                if entry.file_size > MAX_NESTED_JAR:
                    raise ValueError("Nested JAR exceeds inventory bound")
                # material-icons-extended already has a 37 MB classes.jar. Spool larger
                # archives to disk instead of retaining that entire payload in memory.
                with tempfile.TemporaryFile() as spool:
                    copied = 0
                    with archive.open(entry) as source:
                        for block in iter(lambda: source.read(1024 * 1024), b""):
                            copied += len(block)
                            if copied > MAX_NESTED_JAR:
                                raise ValueError("Nested JAR exceeds inventory bound")
                            spool.write(block)
                    spool.seek(0)
                    with zipfile.ZipFile(spool) as jar:
                        visit(jar, name + "!/", nested=True)

    with zipfile.ZipFile(path) as archive:
        visit(archive)
    return {"filename": path.name, "sha256": file_digest(path), "bytes": path.stat().st_size,
            "embeddedNotices": notices, "nativeLibraries": sorted(native)}


def collect(inputs: Path, output: Path) -> dict:
    output.mkdir(parents=True, exist_ok=True)
    inventory = output / "inventory.json"
    # A failed refresh must not leave an earlier successful graph looking current.
    inventory.unlink(missing_ok=True)
    selected = json.loads(inputs.read_text())
    if selected.get("schemaVersion") != 1 or not isinstance(selected.get("components"), list):
        raise ValueError("Unsupported resolved-input schema")
    documents = output / "documents"
    documents.mkdir(parents=True, exist_ok=True)
    poms = output / "poms"
    poms.mkdir(parents=True, exist_ok=True)
    modules = []
    coordinates = set()
    for item in sorted(selected["components"], key=lambda value: value["coordinate"]):
        coordinate = item["coordinate"]
        if not re.fullmatch(r"[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+:[A-Za-z0-9_.+-]+", coordinate):
            raise ValueError("Unsupported module coordinate")
        if coordinate in coordinates:
            raise ValueError("Duplicate selected module")
        coordinates.add(coordinate)
        pom = published_metadata(Path(item["pom"]))
        raw_pom = Path(item["pom"]).read_bytes()
        if digest(raw_pom) != pom["sha256"]:
            raise ValueError("Published POM changed during inventory")
        pom["document"] = "poms/" + pom["sha256"] + ".pom"
        (output / pom["document"]).write_bytes(raw_pom)
        artifacts = [archive_inventory(Path(path), documents) for path in sorted(set(item["artifacts"]))]
        findings = []
        if not pom["licensesDeclaredHere"]:
            findings.append("no-license-declared-in-this-pom")
        if artifacts and not any(artifact["embeddedNotices"] for artifact in artifacts):
            findings.append("no-embedded-notice-found")
        if any(artifact["nativeLibraries"] for artifact in artifacts):
            findings.append("native-bundled-dependencies-require-upstream-review")
        if any(notice["encoding"] != "utf-8" for artifact in artifacts for notice in artifact["embeddedNotices"]):
            findings.append("non-utf8-notice-requires-review")
        modules.append({"coordinate": coordinate, "pom": pom, "artifacts": artifacts,
                        "reviewFindings": findings})
    report = {"schemaVersion": 1, "configuration": selected["configuration"],
              "reviewComplete": False,
              "scope": "Selected external runtime modules; declared POM metadata and exact embedded notices. "
                       "Not a legal approval or a complete inventory of statically bundled native dependencies.",
              "components": modules}
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output,
                                         prefix=".inventory-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
        temporary.replace(inventory)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    report = collect(args.inputs, args.output)
    print(json.dumps({"components": len(report["components"]), "reviewComplete": False,
                      "componentsWithFindings": sum(bool(item["reviewFindings"]) for item in report["components"])}))


if __name__ == "__main__":
    main()
