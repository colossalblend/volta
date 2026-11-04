#!/usr/bin/env python3
"""Builds the Google Play pictures from the raw screens StoreShots.kt draws.

    python tools/store/compose.py --raw <volta.shots>/raw --out store/play

<raw>/<lang>/*.png are screens of the real app (392 x 665 dp at 2x for the phone,
1280 x 720 dp at 1.5x for the TV, the disc alone). For every language there:

    <out>/<lang>/phone-01-….png   1080 x 1920, caption above the screen in a flat frame
    <out>/<lang>/tv-01-….png      1920 x 1080, the TV screen as it is
    <out>/<lang>/feature-graphic.png  1024 x 500

The screens are pasted pixel for pixel, never scaled, so the app's own text stays sharp.
Fonts are the app's own (app/src/main/res/font): Unbounded for the caption, Onest for the
line under it.
"""

import argparse
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONTS = ROOT / "app/src/main/res/font"

# The app's palette (ui/theme/Color.kt).
BG = (16, 19, 22)             # VoltaBackground
BODY = (27, 32, 36)           # between Surface and SurfaceHigh
CREAM = (238, 232, 223)       # VoltaCream
MUTED = (163, 171, 177)       # VoltaMuted, a step lighter to read at thumbnail size
COPPER = (208, 131, 80)       # VoltaCopper

W, H = 1080, 1920
SS = 4  # supersampling for the frame's curves

# The phone: screen 784 x 1330 (392 x 665 dp at 2x) under a 48 px status strip.
STATUS = 48
BEZEL = 14
RIM = 3
BODY_RADIUS = 64
SCREEN_RADIUS = 50
BOTTOM = 84
TEXT_TOP = 128
TITLE_SIZE = 60
LINE_SIZE = 34

# Order, raw screen and caption of each phone picture: a short headline (two lines at
# most) and one line under it. compose.py stops if either does not fit.
PHONE = {
    "ru": [
        ("home", "home-on", "Нажмите на диск",
         "Volta сама выберет самый быстрый сервер"),
        ("network", "home-lte", "Wi-Fi или LTE — Volta переключится сама",
         "Проверяет сеть и выбирает нужную группу"),
        ("servers", "servers", "Все серверы с откликом",
         "По странам, в группах Home и LTE"),
        ("add", "import", "Вставьте ссылку — Volta разберётся",
         "Подписки, ключи AmneziaVPN и WireGuard"),
        ("apps", "perapp", "Банки и доставка мимо VPN",
         "Готовые наборы приложений — одним нажатием"),
        ("routing", "routing", "Фильтры рекламы и торрентов",
         "Строгий режим убирает ещё и баннеры cookie"),
        ("traffic", "home-details", "Скорость и трафик на виду",
         "Сегодня, за месяц и по дням за две недели"),
        ("look", "settings-look", "Темы, 15 языков и Android TV",
         "Тёмные, светлая, Material You и пульт"),
    ],
    "en": [
        ("home", "home-on", "Tap the disc",
         "Volta picks the fastest server by itself"),
        ("network", "home-lte", "Wi-Fi or LTE, Volta switches by itself",
         "It checks the network and picks the right group"),
        ("servers", "servers", "Every server with its ping",
         "By country, in Home and LTE groups"),
        ("add", "import", "Paste a link, Volta sorts it out",
         "Subscriptions, AmneziaVPN keys, WireGuard"),
        ("apps", "perapp", "Banking apps can skip the VPN",
         "Ready-made app sets turn on with one tap"),
        ("routing", "routing", "Filters for ads and torrents",
         "Strict mode also hides cookie banners"),
        ("traffic", "home-details", "Speed and traffic at a glance",
         "Today, this month and per day for two weeks"),
        ("look", "settings-look", "Themes, languages and Android TV",
         "15 languages, Material You, a remote on TV"),
    ],
}

TV = [("home", "tv-home"), ("servers", "tv-servers")]

FEATURE = {
    "ru": ["VPN-клиент", "для ваших подписок"],
    "en": ["A VPN client", "for your subscriptions"],
}


def font(name, size, weight):
    f = ImageFont.truetype(str(FONTS / f"{name}.ttf"), size)
    f.set_variation_by_axes([weight])
    return f


def wrap(draw, text, fnt, width):
    """Lines of about equal length (no lone word on the second line)."""
    words = text.split(" ")
    if draw.textlength(text, font=fnt) <= width:
        return [text]
    best = None
    for i in range(1, len(words)):
        a, b = " ".join(words[:i]), " ".join(words[i:])
        if b.startswith("—"):
            continue  # a dash stays at the end of its line
        widest = max(draw.textlength(a, font=fnt), draw.textlength(b, font=fnt))
        if widest <= width and (best is None or widest < best[0]):
            best = (widest, [a, b])
    return best[1] if best else [text, ""] * 2


def fit(draw, text, name, weight, sizes, width, max_lines):
    for size in sizes:
        fnt = font(name, size, weight)
        lines = wrap(draw, text, fnt, width)
        if len(lines) <= max_lines and all(draw.textlength(l, font=fnt) <= width for l in lines):
            return fnt, size, lines
    raise SystemExit(f"caption does not fit: {text!r}")


def rounded_mask(size, radius, inset=0):
    """An anti-aliased rounded-rectangle mask, drawn large and scaled down."""
    w, h = size
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (inset * SS, inset * SS, (w - inset) * SS - 1, (h - inset) * SS - 1),
        radius=radius * SS, fill=255)
    return big.resize(size, Image.LANCZOS)


def status_strip(width, color, ink):
    """Android's status bar, quiet: the time, the camera hole, the battery."""
    strip = Image.new("RGB", (width, STATUS), color)
    d = ImageDraw.Draw(strip)
    d.text((34, STATUS // 2 + 1), "12:30", font=font("onest", 22, 600), fill=ink, anchor="lm")
    big = Image.new("L", (width * SS, STATUS * SS), 0)
    bd = ImageDraw.Draw(big)
    cx, cy, r = width // 2, STATUS // 2, 11
    bd.ellipse(((cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS), fill=255)
    hole = big.resize((width, STATUS), Image.LANCZOS)
    strip.paste((6, 7, 8), (0, 0), hole)
    # Battery: an outline, three quarters full, and its cap.
    bx, by, bw, bh = width - 34 - 30, STATUS // 2 - 7, 26, 14
    d.rounded_rectangle((bx, by, bx + bw, by + bh), radius=3, outline=ink, width=2)
    d.rectangle((bx + 4, by + 4, bx + 4 + round((bw - 8) * 0.75), by + bh - 4), fill=ink)
    d.rectangle((bx + bw + 1, by + 4, bx + bw + 3, by + bh - 4), fill=ink)
    return strip


def phone(raw_png, title, line):
    screen = Image.open(raw_png).convert("RGB")
    sw, sh = screen.size
    canvas = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(canvas)

    dw, dh = sw + 2 * BEZEL, sh + STATUS + 2 * BEZEL
    x0, y0 = (W - dw) // 2, H - BOTTOM - dh

    # Caption, left-aligned with the phone's edge: the headline in two lines at most, the
    # line under it in one.
    # One size for the whole set, so the pictures read as a series.
    tf, ts, tlines = fit(draw, title, "unbounded", 700, [TITLE_SIZE], dw, 2)
    sf, ss_, slines = fit(draw, line, "onest", 450, [LINE_SIZE], dw, 1)
    lead = round(ts * 1.18)
    y = TEXT_TOP + round(ts * 0.8)
    for l in tlines:
        draw.text((x0, y), l, font=tf, fill=CREAM, anchor="ls")
        y += lead
    y += round(ss_ * 0.5) - round(ts * 0.42)
    for l in slines:
        draw.text((x0, y), l, font=sf, fill=MUTED, anchor="ls")
        y += round(ss_ * 1.32)
    if y > y0 - 40:
        raise SystemExit(f"caption runs into the phone: {title!r}")

    # The phone, flat: a thin copper rim, a zinc body, the screen.
    canvas.paste(COPPER, (x0, y0, x0 + dw, y0 + dh), rounded_mask((dw, dh), BODY_RADIUS))
    canvas.paste(BODY, (x0, y0, x0 + dw, y0 + dh), rounded_mask((dw, dh), BODY_RADIUS - RIM, RIM))

    top = screen.getpixel((6, 6))
    full = Image.new("RGB", (sw, sh + STATUS), top)
    full.paste(status_strip(sw, top, CREAM), (0, 0))
    full.paste(screen, (0, STATUS))
    canvas.paste(full, (x0 + BEZEL, y0 + BEZEL), rounded_mask(full.size, SCREEN_RADIUS))
    return canvas


def feature(disc_png, tagline):
    """The name and what it is on the left, the disc whole on the right."""
    fw, fh = 1024, 500
    x = 72  # the margin, on the left and right alike
    canvas = Image.new("RGB", (fw, fh), BG)
    draw = ImageDraw.Draw(canvas)
    disc = Image.open(disc_png).convert("RGB")
    # The raw picture is 400 dp square on BG, so it sits in the picture without an edge;
    # the copper disc in its middle is 210 dp across, its rings about 300. The disc is
    # drawn DISC px across.
    DISC = 330
    d = round(DISC * 400 / 210)
    disc = disc.resize((d, d), Image.LANCZOS)
    cx, cy = fw - x - DISC // 2, fh // 2
    left = cx - d // 2
    canvas.paste(disc, (left, cy - d // 2))
    name = font("unbounded", 112, 700)
    draw.text((x, 240), "Volta", font=name, fill=CREAM, anchor="ls")
    tf = font("onest", 38, 500)
    widest = max(draw.textlength(l, font=tf) for l in tagline)
    # Clear of the disc and of the rings around it.
    if x + max(widest, draw.textlength("Volta", font=name)) > cx - round(DISC * 300 / 210) // 2 - 24:
        raise SystemExit(f"tagline runs into the disc: {tagline!r}")
    y = 306
    for l in tagline:
        draw.text((x, y), l, font=tf, fill=MUTED, anchor="ls")
        y += 48
    return canvas


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    done = 0
    for lang, shots in PHONE.items():
        raw = args.raw / lang
        if not raw.is_dir():
            continue
        out = args.out / lang
        out.mkdir(parents=True, exist_ok=True)
        for i, (slug, name, title, line) in enumerate(shots, 1):
            src = raw / f"{name}.png"
            if not src.exists():
                print(f"missing {src}", file=sys.stderr)
                continue
            phone(src, title, line).save(out / f"phone-{i:02d}-{slug}.png", optimize=True)
            done += 1
        for i, (slug, name) in enumerate(TV, 1):
            src = raw / f"{name}.png"
            if src.exists():
                img = Image.open(src).convert("RGB")
                if img.size != (1920, 1080):
                    img = img.resize((1920, 1080), Image.LANCZOS)
                img.save(out / f"tv-{i:02d}-{slug}.png", optimize=True)
                done += 1
        if (raw / "disc-on.png").exists():
            feature(raw / "disc-on.png", FEATURE[lang]).save(out / "feature-graphic.png", optimize=True)
            done += 1
    print(f"{done} pictures")
    if done == 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
