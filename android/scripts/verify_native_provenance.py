#!/usr/bin/env python3
"""Match every packaged native library to locked AAR bytes or their exact NDK strip output.

This verifies binary correspondence, not upstream license completeness or legal approval.
Only direct AAR jni/<abi>/*.so inputs are Android runtime native libraries; prefab and
nested inspection tools are not silently counted as packaged code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

MAX_LIBRARY = 128 * 1024 * 1024
MAX_NATIVE_TOTAL = 512 * 1024 * 1024
ABI_ELF = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def file_digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def ndk_version(catalog: Path) -> str:
    values = re.findall(r'^ndk\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$', catalog.read_text(), re.MULTILINE)
    if len(values) != 1:
        raise ValueError("Expected one pinned NDK version in the version catalog")
    return values[0]


class NdkStrip:
    def __init__(self, root: Path, version: str):
        revisions = re.findall(r"^Pkg.Revision\s*=\s*(\S+)\s*$", (root / "source.properties").read_text(), re.MULTILINE)
        if revisions != [version]:
            raise ValueError("NDK revision differs from the pinned build version")
        candidates = [p for p in root.glob("toolchains/llvm/prebuilt/*/bin/llvm-strip") if p.is_file()]
        if len(candidates) != 1:
            raise ValueError("Expected exactly one NDK llvm-strip executable")
        self.tool = candidates[0].resolve()
        self.provenance = {"ndkVersion": version, "stripToolSha256": file_digest(self.tool), "arguments": ["--strip-unneeded"]}

    def __call__(self, raw: bytes) -> bytes:
        with tempfile.TemporaryDirectory(prefix="whereday-native-") as temporary:
            path = Path(temporary) / "library.so"
            path.write_bytes(raw)
            # A trusted local NDK tool, fixed arguments and an owned temporary file only.
            result = subprocess.run([str(self.tool), "--strip-unneeded", str(path)],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            if result.returncode != 0 or not path.is_file() or path.stat().st_size > MAX_LIBRARY:
                raise ValueError("Pinned NDK failed to strip the native input")
            return path.read_bytes()


def native_bytes(archive: zipfile.ZipFile, info: zipfile.ZipInfo, abi: str) -> bytes:
    if info.file_size < 64 or info.file_size > MAX_LIBRARY:
        raise ValueError("Native library exceeds its size bound or has no ELF header")
    raw = archive.read(info)
    elf_class, machine = ABI_ELF[abi]
    if raw[:4] != b"\x7fELF" or raw[4] != elf_class or raw[5] != 1 or struct.unpack_from("<H", raw, 18)[0] != machine:
        raise ValueError("Native ELF identity does not match its ABI")
    return raw


def unique_entries(archive: zipfile.ZipFile) -> list[zipfile.ZipInfo]:
    entries = archive.infolist()
    names = [item.filename for item in entries]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate ZIP entry")
    return entries


def native_path(name: str, prefix: str) -> tuple[str, str]:
    parts = name.split("/")
    count = len(prefix.split("/")) - 1
    if not name.startswith(prefix) or len(parts) != count + 2:
        raise ValueError("Unexpected native library location")
    abi, library = parts[-2:]
    if abi not in ABI_ELF or not re.fullmatch(r"[A-Za-z0-9_.+-]+\.so", library):
        raise ValueError("Unsupported native ABI or filename")
    return abi, f"{abi}/{library}"


def verify(package: Path, inputs: Path, lock_path: Path, strip) -> dict:
    selected = json.loads(inputs.read_text())
    lock = json.loads(lock_path.read_text())
    if selected.get("schemaVersion") != 1 or lock.get("schemaVersion") != 1:
        raise ValueError("Unsupported runtime input/lock schema")
    expected = {}
    identities = {}
    total = 0
    for component in selected["components"]:
        coordinate = component["coordinate"]
        if coordinate in identities:
            raise ValueError("Duplicate selected component")
        paths = sorted({Path(value) for value in component["artifacts"]})
        hashes = {path: file_digest(path) for path in paths}
        identities[coordinate] = {"pomSha256": file_digest(Path(component["pom"])), "artifacts": sorted(set(hashes.values()))}
        if lock["components"].get(coordinate) != identities[coordinate]:
            raise ValueError("Selected component differs from the reviewed runtime lock")
        for path in paths:
            with zipfile.ZipFile(path) as archive:
                for info in unique_entries(archive):
                    if not info.filename.startswith("jni/") or not info.filename.endswith(".so"):
                        continue
                    abi, key = native_path(info.filename, "jni/")
                    if key in expected:
                        raise ValueError("Ambiguous native ownership: " + key)
                    total += info.file_size
                    if total > MAX_NATIVE_TOTAL:
                        raise ValueError("Selected native inputs exceed their aggregate size bound")
                    expected[key] = {"coordinate": coordinate, "artifactSha256": hashes[path],
                                     "entry": info.filename, "raw": native_bytes(archive, info, abi)}
    if identities != lock["components"]:
        raise ValueError("Selected component set differs from the reviewed runtime lock")
    actual = {}
    total = 0
    with zipfile.ZipFile(package) as archive:
        entries = unique_entries(archive)
        prefix = "base/lib/" if "base/manifest/AndroidManifest.xml" in archive.namelist() else "lib/"
        for info in entries:
            if not info.filename.endswith(".so"):
                continue
            abi, key = native_path(info.filename, prefix)
            total += info.file_size
            if total > MAX_NATIVE_TOTAL:
                raise ValueError("Packaged native libraries exceed their aggregate size bound")
            actual[key] = native_bytes(archive, info, abi)
    if not expected or set(expected) != set(actual):
        raise ValueError("Packaged native file set differs from selected direct AAR JNI inputs")
    libraries = []
    for key, source in sorted(expected.items()):
        original = source.pop("raw")
        packaged = actual[key]
        transform = "unchanged"
        if original != packaged:
            if strip(original) != packaged:
                raise ValueError("Packaged native bytes do not match selected input or pinned NDK strip: " + key)
            transform = "ndk-strip-unneeded"
        libraries.append({**source, "packagedPath": prefix + key, "sourceSha256": digest(original),
                          "packagedSha256": digest(packaged), "transformation": transform})
    return {"schemaVersion": 1, "packageSha256": file_digest(package), "runtimeLockSha256": file_digest(lock_path),
            "libraries": libraries, "nativeComponentCount": len({item["coordinate"] for item in libraries}),
            "nativeFileCount": len(libraries), "legalReviewComplete": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path, nargs="?")
    parser.add_argument("inputs", type=Path, nargs="?")
    parser.add_argument("lock", type=Path, nargs="?")
    parser.add_argument("--ndk-root", type=Path)
    parser.add_argument("--version-catalog", type=Path, default=Path(__file__).resolve().parents[1] / "gradle/libs.versions.toml")
    parser.add_argument("--print-ndk-version", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    version = ndk_version(args.version_catalog)
    if args.print_ndk_version:
        print(version)
        return
    if not all((args.package, args.inputs, args.lock, args.ndk_root)):
        parser.error("package, inputs, lock and --ndk-root are required")
    # Invalidate a previous passing report before doing new verification.
    if args.output:
        args.output.unlink(missing_ok=True)
    strip = NdkStrip(args.ndk_root, version)
    result = {**verify(args.package, args.inputs, args.lock, strip), "toolchain": strip.provenance}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("nativeFileCount", "nativeComponentCount", "legalReviewComplete")}))


if __name__ == "__main__":
    main()
