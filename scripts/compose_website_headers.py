#!/usr/bin/env python3
"""Build matching website header assets and a live-text preview."""
from pathlib import Path
from html import escape
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "marketing/design/website-headers-v1"
PUBLIC = ROOT / "marketing/public/assets/product-headers-v1"
FONT = "/System/Library/Fonts/Supplemental/Baskerville.ttc"
COPY = [
    ("overview", "Your days,\nremembered.", "A private journal of the places you go."),
    ("routes", "Take the long\nway home.", "Record your routes. Remember the journey."),
    ("privacy", "Your history.\nYours to keep.", "Your location journal stays on your iPhone."),
    ("export", "Your journal,\nbeyond the app.", "Export your history in a format you can use."),
]


def line(draw, position, value, size):
    font = ImageFont.truetype(FONT, size)
    draw.text(position, value, fill="#20211e", font=font, anchor="lt")


def main():
    PUBLIC.mkdir(parents=True, exist_ok=True)
    cards = []
    for name, heading, subtitle in COPY:
        original = Image.open(DESIGN / "originals" / f"{name}.png").convert("RGB")
        assert original.width == original.height * 3
        for width in (768, 1280, 1920):
            original.resize((width, width//3), Image.Resampling.LANCZOS).save(PUBLIC / f"{name}-{width}.webp", quality=88, method=6)
        banner = original.resize((1920, 640), Image.Resampling.LANCZOS)
        draw = ImageDraw.Draw(banner)
        line(draw, (96, 64), "Whereday", 36)
        for n, value in enumerate(heading.splitlines()):
            line(draw, (96, 160 + n*104), value, 96)
        line(draw, (96, 400), subtitle, 32)
        if name == "export":
            line(draw, (96, 464), "Export is available with a one-time purchase.", 24)
        banner.save(DESIGN / f"{name}-desktop.png")
        # Mobile: typography sits above a full-width landscape, without cropping it.
        mobile = Image.new("RGB", (768, 960))
        paper = original.crop((20, 20, 740, 300)).resize((768, 960), Image.Resampling.LANCZOS)
        mobile.paste(paper)
        mobile.paste(original.resize((768, 256), Image.Resampling.LANCZOS), (0, 704))
        draw = ImageDraw.Draw(mobile)
        line(draw, (48, 48), "Whereday", 32)
        for n, value in enumerate(heading.splitlines()):
            line(draw, (48, 152+n*88), value, 80)
        # Supporting text is wrapped explicitly for mobile legibility.
        words, current, lines = subtitle.split(), "", []
        f = ImageFont.truetype(FONT, 30)
        for word in words:
            candidate = (current + " " + word).strip()
            if draw.textlength(candidate, font=f) > 660:
                lines.append(current)
                current = word
            else:
                current = candidate
        lines.append(current)
        for n, value in enumerate(lines):
            line(draw, (48, 376+n*40), value, 30)
        if name == "export":
            line(draw, (48, 496), "Export requires a one-time purchase.", 24)
        mobile.save(DESIGN / f"{name}-mobile.png")
        note = '<small>Export requires a one-time purchase.</small>' if name == "export" else ""
        cards.append(f'''<section class="header" aria-labelledby="{name}-title">
  <picture><source media="(max-width: 640px)" srcset="../../public/assets/product-headers-v1/{name}-768.webp"><img src="../../public/assets/product-headers-v1/{name}-1920.webp" srcset="../../public/assets/product-headers-v1/{name}-768.webp 768w, ../../public/assets/product-headers-v1/{name}-1280.webp 1280w, ../../public/assets/product-headers-v1/{name}-1920.webp 1920w" sizes="(max-width: 640px) 100vw, 1200px" width="1920" height="640" alt="" {'loading="lazy"' if name != 'overview' else 'fetchpriority="high"'}></picture>
  <div class="copy"><p class="brand">Whereday</p><h2 id="{name}-title">{escape(heading).replace(chr(10), '<br>')}</h2><p>{escape(subtitle)}</p>{note}</div>
</section>''')
    html = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex"><title>Whereday — illustrated product headers</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#e9e5dc;color:#20211e;font-family:Baskerville,Georgia,serif}main{max-width:1296px;margin:auto;padding:48px}h1{font-size:36px;font-weight:400;margin:0 0 16px}.intro{font-size:18px;margin:0 0 32px}.header{position:relative;isolation:isolate;margin-bottom:32px;background:#f8f6ef;overflow:hidden}.header img{display:block;width:100%;height:auto}.copy{position:absolute;inset:0;padding:32px 48px}.brand{font-size:24px;margin:0 0 24px}h2{font-size:60px;line-height:1;margin:0 0 24px;font-weight:400;text-wrap:balance}.copy>p:not(.brand){font-size:20px;line-height:28px;margin:0;max-width:50%}small{display:block;font-size:14px;margin-top:16px}@media(min-width:641px) and (max-width:1000px){main{padding:24px}.copy{padding:24px 32px}.brand{font-size:18px;margin-bottom:16px}h2{font-size:36px;margin-bottom:16px}.copy>p:not(.brand){font-size:16px;line-height:24px}small{margin-top:8px;font-size:12px}}@media(max-width:640px){main{padding:16px}.header{display:flex;flex-direction:column}.copy{position:relative;order:-1;padding:32px 24px 48px}.brand{margin-bottom:32px}h2{font-size:48px}.copy>p:not(.brand){max-width:100%;font-size:18px}.header picture{display:block}.intro{line-height:28px}}
</style><main><h1>Whereday, on paper.</h1><p class="intro">Four matching product headers. Text remains selectable and reflows on mobile.</p>''' + "\n".join(cards) + "</main></html>"
    (DESIGN / "preview.html").write_text(html)
    (DESIGN / "copy.json").write_text(json.dumps([dict(name=n, headline=h, subtitle=s) for n,h,s in COPY], indent=2)+"\n")
    sheet = Image.new("RGB", (1200, 1728), "#e9e5dc")
    for n, (name, _, _) in enumerate(COPY):
        sheet.paste(Image.open(DESIGN / f"{name}-desktop.png").resize((1200,400), Image.Resampling.LANCZOS), (0,n*432))
    sheet.save(DESIGN / "contact-sheet.jpg", quality=94)
    with zipfile.ZipFile(DESIGN / "isome-website-headers.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(DESIGN.glob("*-desktop.png")) + sorted(DESIGN.glob("*-mobile.png")) + sorted(PUBLIC.glob("*.webp")):
            archive.write(path, path.name)
    print("Created four desktop headers, four mobile headers, 12 text-free WebPs, a contact sheet, and responsive HTML preview.")


if __name__ == "__main__":
    main()
