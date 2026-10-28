#!/usr/bin/env python3
"""Compose original screenshots on illustrated paper; never regenerate the UI."""
from pathlib import Path
import hashlib
import json
import zipfile

from PIL import Image, ImageDraw, ImageFont, ImageChops, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "marketing/design/app-store-ink-v1"
SOURCE = ROOT / "screenshots/localized/raw/en-US/iphone"
OUT = DESIGN / "en-US/iphone-1320x2868"
FONT = "/System/Library/Fonts/Supplemental/Baskerville.ttc"
SIZE = (1320, 2868)
SCREEN = (165, 610, 990, 2151)  # Exactly 75% of the original 1320 × 2868.
INK = "#20211e"


def text(draw, value, y, size, fill=INK):
    font = ImageFont.truetype(FONT, size)
    bounds = draw.textbbox((0, 0), value, font=font)
    assert bounds[2] - bounds[0] <= 1160, f"Text overflow: {value}"
    draw.text((660, y - bounds[1]), value, font=font, fill=fill, anchor="mt")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    background = Image.open(DESIGN / "assets/paper-landscape.png").convert("RGB").resize(SIZE, Image.Resampling.LANCZOS)
    # Keep the original paper texture but quiet the ink behind the headlines.
    paper = background.crop((0, 850, 1320, 1500)).resize(SIZE)
    quiet = Image.new("L", SIZE, 0)
    ImageDraw.Draw(quiet).rounded_rectangle((170, 130, 1150, 580), radius=70, fill=245)
    background.paste(paper, (0, 0), quiet.filter(ImageFilter.GaussianBlur(60)))
    entries = json.loads((DESIGN / "copy.json").read_text())
    manifest = []
    for entry in entries:
        canvas = background.copy()
        draw = ImageDraw.Draw(canvas)
        text(draw, "Whereday", 76, 52)
        for n, line in enumerate(entry["headline"]):
            text(draw, line, 185 + 132 * n, 128)
        for n, line in enumerate(entry["subtitle"]):
            text(draw, line, 460 + 48 * n, 43)
        if entry.get("note"):
            text(draw, entry["note"], 561, 28, "#55544c")
        source_path = SOURCE / (entry["source"] + ".png")
        original = Image.open(source_path).convert("RGB")
        assert original.size == SIZE
        x, y, w, h = SCREEN
        screen = original.resize((w, h), Image.Resampling.LANCZOS)
        # Device frame stays outside the source screen. Only physical corner
        # areas are masked; every remaining pixel is the resized original.
        draw.rounded_rectangle((x-15, y-15, x+w+14, y+h+14), radius=103, fill="#181818", outline="#55544c", width=3)
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, w-1, h-1), radius=88, fill=255)
        canvas.paste(screen, (x, y), mask)
        destination = OUT / (entry["source"] + ".png")
        canvas.save(destination)
        # Read the saved PNG back and compare every visible source pixel.
        saved = Image.open(destination).convert("RGB")
        region = saved.crop((x, y, x+w, y+h))
        difference = ImageChops.difference(region, screen)
        difference.paste((0, 0, 0), (0, 0), ImageChops.invert(mask))
        assert difference.getbbox() is None, f"UI pixel mismatch: {destination}"
        assert saved.size == SIZE and saved.mode == "RGB"
        manifest.append({"file": str(destination.relative_to(DESIGN)), "source": str(source_path.relative_to(ROOT)), "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(), "screen_rect": SCREEN, "scale": 0.75, "corner_radius": 88, "visible_ui_pixel_difference": 0})
    (DESIGN / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    # Contact sheet is for review, not upload.
    sheet = Image.new("RGB", (1740, 806), "#e9e5dc")
    for n, entry in enumerate(entries):
        thumbnail = Image.open(OUT / (entry["source"] + ".png")).resize((330, 717), Image.Resampling.LANCZOS)
        sheet.paste(thumbnail, (15+n*345, 16))
        label = ImageFont.truetype(FONT, 24)
        ImageDraw.Draw(sheet).text((180+n*345, 756), f"0{n+1}", font=label, fill=INK, anchor="mt")
    sheet.save(DESIGN / "contact-sheet.jpg", quality=95)
    with zipfile.ZipFile(DESIGN / "isome-app-store-en-US.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(OUT.glob("*.png")):
            archive.write(path, path.name)
    print(f"Exported {len(entries)} RGB PNGs at {SIZE}; all visible UI pixels verified against scaled originals.")


if __name__ == "__main__":
    main()
