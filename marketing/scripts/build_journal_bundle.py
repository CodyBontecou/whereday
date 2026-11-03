#!/usr/bin/env python3
"""Package a repeatable, plugin-free Obsidian example without modifying its source export."""
from io import BytesIO
from pathlib import Path
import argparse
import zipfile

PUBLIC = Path(__file__).resolve().parent.parent / "public"
ROOT = "Whereday-travel-journal-example/"
FILES = {
    "Templates/Travel journal.md": "travel-journal-template.md",
    "Trip reflections.md": "synthetic-trip-reflections.md",
    "Location/synthetic-obsidian-export.md": "synthetic-obsidian-export.md",
}
README = """# Whereday travel journal example

All coordinates, dates and visits are invented. This is a documentation example, not personal history.

1. Extract this ZIP to a new folder. Keep its folder layout unchanged.
2. In desktop Obsidian, use Open folder as vault and choose Whereday-travel-journal-example.
3. Open Trip reflections and switch to Reading view. Its source link should open Location/synthetic-obsidian-export, showing two visits on January 1, 2000.
4. For your own journal, copy the contents of Templates/Travel journal to a new note with a different filename. Fill in the date and timezone context. Replace its link instruction by selecting an actual exported note in your vault.
5. Keep handwritten notes separate from generated exports. Compare files before replacing any export.

The blank template is ordinary Markdown with manual fields. It does not require Templates, Daily notes, community plugins or a Whereday account. No plugins or vault settings are bundled. No sync or map is configured.

The source table is the unchanged earlier native synthetic export. Its Export Date is the creation date, not the visit date. Its clock times were generated in UTC. This example does not verify mobile transfer or scheduled exports. None of these notes replaces a supported backup.

Guide: https://whereday.com/blog/iphone-location-journal-obsidian/#journal-template
"""


def bundle():
    output = BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        data = {name: (PUBLIC / "samples" / source).read_bytes() for name, source in FILES.items()}
        data["README.md"] = README.encode("utf-8")
        for name, content in sorted(data.items()):
            info = zipfile.ZipInfo(ROOT + name, date_time=(2026, 10, 30, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return output.getvalue()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    path = PUBLIC / "samples/whereday-travel-journal-example.zip"
    expected = bundle()
    if args.check:
        if not path.exists() or path.read_bytes() != expected:
            raise SystemExit("Journal example ZIP differs from its source notes; rebuild it.")
        print("Journal example ZIP matches its three source notes and instructions.")
    else:
        path.write_bytes(expected)
        print("Built journal example ZIP.")
