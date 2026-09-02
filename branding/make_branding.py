"""Builds the EpicYSM banner, logo and icons from character.png (an in-game
screenshot of a YSM model cut out from its background).
Run from this folder: python3 make_branding.py"""
import math
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

BOLD = "/usr/share/fonts/truetype/google-fonts/Poppins-Bold.ttf"
MED = "/usr/share/fonts/truetype/google-fonts/Poppins-Medium.ttf"
CJK = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"

BG_TOP = (22, 16, 40)
BG_BOT = (58, 22, 60)
RED = (232, 62, 72)
PINK = (255, 190, 214)
WHITE = (250, 246, 250)
LINE = (30, 16, 34)


def gradient(w, h, top, bot):
    img = Image.new("RGB", (w, h), top)
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img


def rays(w, h, cx, cy, alpha=22, count=36, width=4):
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = w + h
    for i in range(count):
        a0 = math.radians(i * 360 / count)
        a1 = math.radians(i * 360 / count + width)
        d.polygon([(cx, cy), (cx + math.cos(a0) * r, cy + math.sin(a0) * r), (cx + math.cos(a1) * r, cy + math.sin(a1) * r)],
                  fill=(255, 255, 255, alpha))
    return layer


def fade_x(w, h, x_from, x_to):
    """L mask: 0 left of x_from, 255 right of x_to."""
    m = Image.new("L", (w, h), 0)
    px = m.load()
    for x in range(w):
        v = int(255 * min(1.0, max(0.0, (x - x_from) / max(1, x_to - x_from))))
        for y in range(h):
            px[x, y] = v
    return m


def glow(w, h, box, color, blur):
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse(box, fill=color)
    return layer.filter(ImageFilter.GaussianBlur(blur))


def character(height):
    c = Image.open("character.png").convert("RGBA")
    scale = height / c.height
    return c.resize((int(c.width * scale), height), Image.LANCZOS)


def with_shadow(sprite, offset=(0, 10), blur=14, alpha=150):
    pad = blur * 3
    out = Image.new("RGBA", (sprite.width + pad * 2, sprite.height + pad * 2), (0, 0, 0, 0))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    shadow.paste((0, 0, 0, alpha), (pad + offset[0], pad + offset[1]), sprite)
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    out.alpha_composite(shadow)
    out.alpha_composite(sprite, (pad, pad))
    return out, pad


def title(d, x, y, px):
    f = ImageFont.truetype(BOLD, px)
    o = max(2, px // 40)
    d.text((x, y), "Epic", font=f, fill=RED, stroke_width=o, stroke_fill=LINE)
    w = d.textlength("Epic", font=f)
    d.text((x + w, y), "YSM", font=f, fill=WHITE, stroke_width=o, stroke_fill=LINE)
    return w + d.textlength("YSM", font=f)


def banner(w=1280, h=640):
    img = gradient(w, h, BG_TOP, BG_BOT).convert("RGBA")
    cxb, cyb = int(w * 0.74), int(h * 0.50)
    r = rays(w, h, cxb, cyb)
    r.putalpha(ImageChops.multiply(r.getchannel("A"), fade_x(w, h, int(w * 0.30), int(w * 0.62))))
    img.alpha_composite(r)
    img.alpha_composite(glow(w, h, [cxb - 250, cyb - 250, cxb + 250, cyb + 250], RED + (110,), 80))
    sprite, pad = with_shadow(character(int(h * 0.90)))
    img.alpha_composite(sprite, (cxb - sprite.width // 2 + 10, h - sprite.height + pad - 6))
    d = ImageDraw.Draw(img)
    x = int(w * 0.06)
    title(d, x, int(h * 0.17), 150)
    sub = ImageFont.truetype(MED, 38)
    small = ImageFont.truetype(MED, 26)
    ty = int(h * 0.17) + 190
    for line, dy in (("Yes Steve Model characters", 0), ("fighting with Epic Fight animations", 48)):
        d.text((x + 6, ty + dy), line, font=sub, fill=WHITE, stroke_width=2, stroke_fill=BG_TOP)
    try:
        d.text((x + 8, ty + 108), "モデルはそのまま、戦い方はエピック", font=ImageFont.truetype(CJK, 30), fill=PINK)
    except Exception:
        pass
    d.text((x + 8, ty + 158), "Minecraft 1.21.1  ·  NeoForge  ·  client side", font=small, fill=(200, 185, 210))
    return img


def tile(size):
    img = gradient(size, size, BG_TOP, BG_BOT).convert("RGBA")
    cx, cy = int(size * 0.5), int(size * 0.42)
    img.alpha_composite(rays(size, size, cx, cy, alpha=18, count=24, width=5))
    img.alpha_composite(glow(size, size, [cx - size * 0.4, cy - size * 0.4, cx + size * 0.4, cy + size * 0.4], RED + (120,), size * 0.12))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.2, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def icon(size):
    """Head and shoulders of the character on the tile."""
    out = tile(size)
    c = Image.open("character.png").convert("RGBA")
    fw = int(c.width * 0.78)
    left = int(c.width * 0.32)
    top = int(c.height * 0.10)
    crop = c.crop((left, top, left + fw, top + fw))
    crop = crop.resize((int(size * 1.04), int(size * 1.04)), Image.LANCZOS)
    sprite, pad = with_shadow(crop, offset=(0, 6), blur=max(4, size // 50), alpha=140)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer.alpha_composite(sprite, (int(-size * 0.02) - pad, int(size * 0.06) - pad))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.2, fill=255)
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), mask))
    out.alpha_composite(layer)
    return out


def logo(w=1200, h=420):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    img.alpha_composite(icon(380), (20, 20))
    d = ImageDraw.Draw(img)
    title(d, 430, 100, 170)
    return img


if __name__ == "__main__":
    icon(512).save("icon-512.png")
    icon(128).save("icon-128.png")
    icon(256).save("../src/main/resources/icon.png")
    banner().save("banner.png")
    logo().save("logo.png")
    print("done")
