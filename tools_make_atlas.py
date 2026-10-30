#!/usr/bin/env python3
"""Gambar region judul stage baru (stagetitle7/8/9 = "8"/"9"/"10")
ke ruang kosong atlas menu_button yang SUDAH diverifikasi bebas
(tidak tumpang tindih piksel maupun rect region lain, kanvas tetap
1024x1024), lalu tambahkan ketiga region ke menu_button.pack.

Gaya digit meniru bawaan: goresan serif tebal sangat gelap
(cokelat-marun ke hitam) dengan sorotan tipis di tepi atas.

Pakai: tools_make_atlas.py <menu_button.png> <menu_button.pack>
       <out.png> <out.pack>
"""
import sys

from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"

# name -> (x, y, w, h, text)
SLOTS = {
    "stagetitle7": (662, 0, 44, 52, "8"),
    "stagetitle8": (706, 0, 40, 48, "9"),
    "stagetitle9": (902, 0, 78, 64, "10"),
}


def draw_digit(img: Image.Image, box, text: str) -> None:
    x, y, w, h = box
    # cari ukuran font agar tinggi teks ~ 92% tinggi box
    size = 10
    while size < 200:
        f = ImageFont.truetype(FONT, size)
        d = ImageDraw.Draw(Image.new("RGBA", (4, 4)))
        bb = d.textbbox((0, 0), text, font=f)
        if bb[3] - bb[1] >= h * 0.92:
            break
        size += 2
    f = ImageFont.truetype(FONT, size)
    d = ImageDraw.Draw(img)
    bb = d.textbbox((0, 0), text, font=f)
    tw = bb[2] - bb[0]
    th = bb[3] - bb[1]
    ox = x + (w - tw) // 2 - bb[0]
    oy = y + (h - th) // 2 - bb[1]
    # sorotan tepi atas (ukiran)
    d.text((ox, oy - 2), text, font=f, fill=(168, 150, 110, 160))
    # goresan utama: gradasi vertikal cokelat sangat gelap -> hitam
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.text((ox, oy), text, font=f, fill=(255, 255, 255, 255))
    grad = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for gy in range(y, y + h):
        t = (gy - y) / max(1, h - 1)
        r = int(46 * (1 - t) + 4 * t)
        g = int(18 * (1 - t) + 2 * t)
        b = int(10 * (1 - t) + 2 * t)
        gd.line([(x, gy), (x + w, gy)], fill=(r, g, b, 255))
    img.alpha_composite(Image.composite(grad, layer, layer.split()[3]))
    # garis tepi gelap
    d = ImageDraw.Draw(img)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        d.text((ox + dx, oy + dy), text, font=f, fill=(0, 0, 0, 90))


def main() -> int:
    png_in, pack_in, png_out, pack_out = sys.argv[1:5]
    img = Image.open(png_in).convert("RGBA")
    assert img.size == (1024, 1024), img.size
    for name, (x, y, w, h, text) in SLOTS.items():
        # verifikasi slot benar-benar kosong
        region = img.crop((x, y, x + w, y + h))
        assert max(a for *_rgb, a in region.getdata()) <= 10, f"slot {name} tidak kosong"
        draw_digit(img, (x, y, w, h), text)
    img.save(png_out)

    pack = open(pack_in, encoding="utf-8").read()
    for name, (x, y, w, h, _t) in SLOTS.items():
        assert ("\n" + name + "\n") not in ("\n" + pack), name
        pack += (
            f"{name}\n"
            f"  rotate: false\n"
            f"  xy: {x}, {y}\n"
            f"  size: {w}, {h}\n"
            f"  orig: {w}, {h}\n"
            f"  offset: 0, 0\n"
            f"  index: -1\n"
        )
    open(pack_out, "w", encoding="utf-8").write(pack)
    print("atlas: stagetitle7/8/9 digambar + pack ditambah 3 region")
    return 0


if __name__ == "__main__":
    sys.exit(main())
