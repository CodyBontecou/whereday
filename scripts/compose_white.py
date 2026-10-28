#!/usr/bin/env python3
"""
Whereday App Store Screenshot Composer — white bg / black text variant.

Ported from ~/.claude-personal/skills/aso-appstore-screenshots/compose.py
with text fill flipped to black, default background to white, and optional
per-locale font + RTL handling preserved.

Output: pixel-perfect 1290x2796 PNG matching the shipped v1.0 style.
"""

import argparse
import os
import sys
from PIL import Image, ImageDraw, ImageFont

CANVAS_W = 1290
CANVAS_H = 2796

DEVICE_W = 1030
BEZEL = 15
SCREEN_W = DEVICE_W - 2 * BEZEL
SCREEN_CORNER_R = 62

DEVICE_Y = 720

VERB_SIZE_MAX = 112
VERB_SIZE_MIN = 64
DESC_SIZE_MAX = 64
DESC_SIZE_MIN = 44
VERB_DESC_GAP = 20
DESC_LINE_GAP = 14
MAX_TEXT_W = int(CANVAS_W * 0.84)
MAX_VERB_W = int(CANVAS_W * 0.84)

DEFAULT_FONT = "/System/Library/Fonts/SFNSMono.ttf"
FRAME_PATH_DEFAULT = os.path.expanduser(
    "~/.claude-personal/skills/aso-appstore-screenshots/assets/device_frame.png"
)

FONT_OVERRIDES = {
    "ja": "/System/Library/Fonts/ヒラギノ角ゴシック W8.ttc",
    "zh-Hans": "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "hi": "/System/Library/Fonts/Kohinoor.ttc",
    "bn": "/System/Library/Fonts/KohinoorBangla.ttc",
    "ar": "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
}

FONT_TTC_INDEX = {
    "ja": 0,
    "zh-Hans": 2,
    "hi": 3,
    "bn": 3,
}


def shape_arabic(text):
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display
    except ImportError as error:
        raise RuntimeError("Arabic composition requires arabic-reshaper and python-bidi") from error

    reshaped = arabic_reshaper.reshape(text)
    return get_display(reshaped)


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i : i + 2], 16) for i in (0, 2, 4))


def load_font(font_path, size, ttc_index=0, variation=None):
    try:
        font = ImageFont.truetype(font_path, size, index=ttc_index, layout_engine=ImageFont.Layout.BASIC if font_path == FONT_OVERRIDES["ar"] else None)
    except (TypeError, OSError):
        font = ImageFont.truetype(font_path, size, layout_engine=ImageFont.Layout.BASIC if font_path == FONT_OVERRIDES["ar"] else None)
    if variation:
        try:
            font.set_variation_by_name(variation)
        except (OSError, ValueError):
            pass
    return font


def word_wrap(draw, text, font, max_w, transform=None):
    def rendered(value):
        return transform(value) if transform else value

    words = text.split()
    lines, cur = [], ""
    for w in words:
        if draw.textlength(rendered(w), font=font) > max_w:
            if cur:
                lines.append(cur)
                cur = ""
            chunk = ""
            for character in w:
                candidate = chunk + character
                if chunk and draw.textlength(rendered(candidate), font=font) > max_w:
                    lines.append(chunk)
                    chunk = character
                else:
                    chunk = candidate
            cur = chunk
            continue
        test = f"{cur} {w}".strip()
        if draw.textlength(rendered(test), font=font) <= max_w:
            cur = test
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def fit_wrapped_font(
    text,
    max_w,
    max_lines,
    size_max,
    size_min,
    font_path,
    ttc_index=0,
    variation=None,
    transform=None,
):
    dummy = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
    for size in range(size_max, size_min - 1, -4):
        font = load_font(font_path, size, ttc_index, variation)
        if len(word_wrap(dummy, text, font, max_w, transform)) <= max_lines:
            return font
    return load_font(font_path, size_min, ttc_index, variation)


def draw_centered(draw, y, text, font, fill, max_w=None, transform=None):
    lines = word_wrap(draw, text, font, max_w, transform) if max_w else [text]
    for line in lines:
        if transform:
            line = transform(line)
        bbox = draw.textbbox((0, 0), line, font=font)
        h = bbox[3] - bbox[1]
        draw.text(
            (CANVAS_W // 2, y - bbox[1]),
            line,
            fill=fill,
            font=font,
            anchor="mt",
        )
        y += h + DESC_LINE_GAP
    return y


def compose(
    verb,
    desc,
    screenshot_path,
    output_path,
    bg_hex="#FFFFFF",
    text_hex="#000000",
    font_path=DEFAULT_FONT,
    ttc_index=0,
    frame_path=FRAME_PATH_DEFAULT,
    locale=None,
    variation=None,
    preserve_case=False,
):
    bg = hex_to_rgb(bg_hex)
    text_color = hex_to_rgb(text_hex)

    canvas = Image.new("RGBA", (CANVAS_W, CANVAS_H), (*bg, 255))
    draw = ImageDraw.Draw(canvas)

    is_arabic = locale == "ar"
    is_cjk_or_indic = locale in {"ja", "zh-Hans", "hi", "bn"}
    # CJK + Indic scripts don't use uppercase; Arabic also has no case.
    def cased(t):
        return t if (preserve_case or is_arabic or is_cjk_or_indic) else t.upper()

    verb_text = cased(verb)
    desc_text = cased(desc)
    line_transform = shape_arabic if is_arabic else None

    verb_font = fit_wrapped_font(
        verb_text, MAX_VERB_W, 2, VERB_SIZE_MAX, VERB_SIZE_MIN, font_path, ttc_index, variation,
        line_transform,
    )
    desc_font = fit_wrapped_font(
        desc_text, MAX_TEXT_W, 3, DESC_SIZE_MAX, DESC_SIZE_MIN, font_path, ttc_index, variation,
        line_transform,
    )

    brand_font = load_font(DEFAULT_FONT, 48)
    draw_centered(draw, 92, "Whereday", brand_font, text_color)

    y = 190
    y = draw_centered(
        draw, y, verb_text, verb_font, text_color,
        max_w=MAX_VERB_W, transform=line_transform,
    )
    y += VERB_DESC_GAP
    draw_centered(
        draw, y, desc_text, desc_font, text_color,
        max_w=MAX_TEXT_W, transform=line_transform,
    )

    device_x = (CANVAS_W - DEVICE_W) // 2
    device_y = DEVICE_Y
    screen_x = device_x + BEZEL
    screen_y = device_y + BEZEL

    shot = Image.open(screenshot_path).convert("RGBA")
    scale = SCREEN_W / shot.width
    sc_w = SCREEN_W
    sc_h = int(shot.height * scale)
    shot = shot.resize((sc_w, sc_h), Image.LANCZOS)

    screen_h = CANVAS_H - screen_y + 500

    scr_mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(scr_mask).rounded_rectangle(
        [screen_x, screen_y, screen_x + SCREEN_W, screen_y + screen_h],
        radius=SCREEN_CORNER_R,
        fill=255,
    )

    scr_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(scr_layer).rounded_rectangle(
        [screen_x, screen_y, screen_x + SCREEN_W, screen_y + screen_h],
        radius=SCREEN_CORNER_R,
        fill=(0, 0, 0, 255),
    )
    scr_layer.paste(shot, (screen_x, screen_y))
    scr_layer.putalpha(scr_mask)

    canvas = Image.alpha_composite(canvas, scr_layer)

    frame_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    if frame_path and os.path.exists(frame_path):
        frame_template = Image.open(frame_path).convert("RGBA")
        frame_layer.paste(frame_template, (device_x, device_y))
    else:
        ImageDraw.Draw(frame_layer).rounded_rectangle(
            [device_x, device_y, device_x + DEVICE_W, CANVAS_H + 120],
            radius=78,
            outline=(18, 18, 18, 255),
            width=BEZEL,
        )
    canvas = Image.alpha_composite(canvas, frame_layer)

    canvas.convert("RGB").save(output_path, "PNG")
    print(f"wrote {output_path} ({CANVAS_W}x{CANVAS_H})", file=sys.stderr)


def main():
    p = argparse.ArgumentParser(description="Whereday App Store screenshot composer")
    p.add_argument("--verb", required=True)
    p.add_argument("--desc", required=True)
    p.add_argument("--screenshot", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--bg", default="#FFFFFF")
    p.add_argument("--text", default="#000000")
    p.add_argument("--locale")
    p.add_argument("--font")
    p.add_argument("--ttc-index", type=int, default=0)
    p.add_argument("--variation")
    p.add_argument("--preserve-case", action="store_true")
    p.add_argument("--frame", default=FRAME_PATH_DEFAULT)
    args = p.parse_args()

    font_path = args.font or FONT_OVERRIDES.get(args.locale, DEFAULT_FONT)
    ttc_index = args.ttc_index or FONT_TTC_INDEX.get(args.locale, 0)
    variation = args.variation

    compose(
        args.verb,
        args.desc,
        args.screenshot,
        args.output,
        bg_hex=args.bg,
        text_hex=args.text,
        font_path=font_path,
        ttc_index=ttc_index,
        frame_path=args.frame,
        locale=args.locale,
        variation=variation,
        preserve_case=args.preserve_case,
    )


if __name__ == "__main__":
    main()
