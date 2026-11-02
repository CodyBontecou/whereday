#!/usr/bin/env python3
"""Verify 64-bit ELF LOAD segments and uncompressed APK native ZIP offsets for 16 KB pages."""
import argparse
from pathlib import Path
import struct
import zipfile


def verify(apk: Path) -> None:
    failures = []
    checked = 0
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as source:
        for info in archive.infolist():
            if not info.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not info.filename.endswith(".so"):
                continue
            checked += 1
            data = archive.read(info)
            if data[:6] != b"\x7fELF\x02\x01":
                failures.append(f"{info.filename}: expected little-endian ELF64")
                continue
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phsize, phcount = struct.unpack_from("<HH", data, 54)
            loads = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(phcount)]
            loads = [segment for segment in loads if segment[0] == 1]
            if not loads or any(segment[7] < 16384 or segment[2] % 16384 != segment[3] % 16384 for segment in loads):
                failures.append(f"{info.filename}: incompatible LOAD alignment")
            source.seek(info.header_offset + 26)
            name_length, extra_length = struct.unpack("<HH", source.read(4))
            offset = info.header_offset + 30 + name_length + extra_length
            if info.compress_type == zipfile.ZIP_STORED and offset % 16384:
                failures.append(f"{info.filename}: unaligned ZIP offset {offset}")
            print(f"{info.filename}: LOAD alignments {[segment[7] for segment in loads]}, ZIP offset {offset}")
    if not checked:
        raise SystemExit("No 64-bit native libraries were checked")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"PASS: {checked} native libraries support 16 KB page alignment in {apk.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify(parser.parse_args().apk)
