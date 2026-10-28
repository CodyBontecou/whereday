#!/usr/bin/env python3
"""Generate only synthetic public-coordinate photos for selected-photo-gallery QA.

Requires Pillow. No personal photographs or device library access. Push these exact
files to Downloads/whereday-public-photo-qa-{a,b}.jpg, scan those exact paths, and
remove only those staged files after the recorded flow has removed its references.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

out = Path(__file__).resolve().parents[1] / '.argent' / 'fixtures'
out.mkdir(parents=True, exist_ok=True)
for name, minute, color in [('a', '00', '#184d35'), ('b', '05', '#996a18')]:
    image = Image.new('RGB', (640, 480), '#f5f1e6')
    draw = ImageDraw.Draw(image)
    draw.rectangle((80, 70, 560, 410), outline=color, width=12)
    draw.ellipse((240, 130, 400, 290), fill=color)
    draw.text((130, 335), f'PUBLIC PHOTO QA {name.upper()}', fill=color,
              font=ImageFont.load_default(size=28))
    exif = Image.Exif()
    exif[34665] = {36867: f'2026:10:27 10:{minute}:00', 36881: '-04:00'}
    exif[34853] = {1: 'N', 2: (48.0, 51.0, 30.24), 3: 'E', 4: (2.0, 17.0, 39.84)}
    destination = out / f'public-photo-qa-{name}.jpg'
    image.save(destination, quality=92, exif=exif)
    print(destination)
