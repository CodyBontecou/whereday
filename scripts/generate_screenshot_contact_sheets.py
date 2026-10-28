#!/usr/bin/env python3
"""Generate one review contact sheet per captured locale."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "screenshots/whereday/ready"
OUTPUT = ROOT / "screenshots/whereday/contact-sheets"
FONT = "/System/Library/Fonts/SFNSMono.ttf"


def paste_thumbnail(canvas: Image.Image, path: Path, box: tuple[int, int, int, int]) -> None:
    image = Image.open(path).convert("RGB")
    image.thumbnail((box[2], box[3]), Image.Resampling.LANCZOS)
    x = box[0] + (box[2] - image.width) // 2
    y = box[1] + (box[3] - image.height) // 2
    canvas.paste(image, (x, y))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--locales", help="comma-separated locales; defaults to captured directories")
    args = parser.parse_args()
    locales = (
        [part.strip() for part in args.locales.split(",") if part.strip()]
        if args.locales else sorted(path.name for path in SOURCE.iterdir() if path.is_dir())
    )
    OUTPUT.mkdir(parents=True, exist_ok=True)
    font = ImageFont.truetype(FONT, 30)
    label_font = ImageFont.truetype(FONT, 18)

    for locale in locales:
        root = SOURCE / locale
        canvas = Image.new("RGB", (1500, 1120), "#EAE7DF")
        draw = ImageDraw.Draw(canvas)
        draw.text((30, 20), f"Whereday localization QA — {locale}", fill="#202020", font=font)

        for index, path in enumerate(sorted((root / "iphone-67").glob("*.png"))):
            paste_thumbnail(canvas, path, (25 + index * 290, 75, 270, 585))
        for index, path in enumerate(sorted((root / "ipad-129").glob("*.png"))):
            paste_thumbnail(canvas, path, (25 + index * 335, 680, 315, 360))
        watch_paths = sorted((root / "watch-series-10").glob("*.png"))
        if watch_paths:
            paste_thumbnail(canvas, watch_paths[0], (1370, 760, 110, 180))
        draw.text((1370, 950), "Watch", fill="#202020", font=label_font)

        destination = OUTPUT / f"{locale}.jpg"
        canvas.save(destination, quality=90)
        print(destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
