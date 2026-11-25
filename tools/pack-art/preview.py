#!/usr/bin/env python3
"""Renders a faithful preview of an easyeconomy menu: the pack's gui skin,
items in slots, title text in the vanilla font, and the Inventory label -
composited the way the client layers them. For design review only."""
import os
import sys

from PIL import Image

HERE = os.path.dirname(__file__)
PACK = os.path.join(HERE, "..", "..", "src", "main", "resources", "resourcepacks", "gui", "assets")
VAN = "/tmp/mcassets/assets/minecraft"
SCALE = 3

ASCII = Image.open(os.path.join(VAN, "textures/font/ascii.png")).convert("RGBA")


def draw_text(img, x, y, text, color, bold=False):
    for ch in text:
        code = ord(ch)
        if 32 <= code < 127:
            gx, gy = (code % 16) * 8, (code // 16) * 8
            glyph = ASCII.crop((gx, gy, gx + 8, gy + 8))
            # trim to 6px advance like vanilla default font approx
            tinted = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
            src = glyph.load()
            dst = tinted.load()
            for yy in range(8):
                for xx in range(8):
                    r, g, b, a = src[xx, yy]
                    if a > 0 and (r + g + b) > 0:
                        dst[xx, yy] = color + (a,)
            img.alpha_composite(tinted, (x, y))
            if bold:
                img.alpha_composite(tinted, (x + 1, y))
        x += 6
    return x


def slot_items(rows):
    """Sample menu contents: chest grid then player inventory."""
    items = {}
    grid = [(7 + 18 * k, 17 + 18 * j) for j in range(rows) for k in range(9)]
    picks = ["gold_ingot", "diamond", "emerald", "iron_ingot", "paper",
             "gold_nugget", "book", "name_tag", "arrow"]
    for i, cell in enumerate(grid):
        if i < len(picks):
            items[cell] = picks[i]
    inv_y = 18 + 18 * rows + 14
    inv = [(7 + 18 * k, inv_y - 1 + 18 * j) for j in range(3) for k in range(9)]
    inv += [(7 + 18 * k, inv_y + 58 - 1) for k in range(9)]
    for i, cell in enumerate(inv):
        if i % 3 == 0:
            items[cell] = picks[(i + 3) % len(picks)]
    return items


def render(rows, title, out):
    bg = Image.open(os.path.join(PACK, f"easyeconomy/textures/font/gui_bg_{rows}.png")).convert("RGBA")
    vanilla = Image.open(os.path.join(PACK, "minecraft/textures/gui/container/generic_54.png")).convert("RGBA")
    w, h = bg.size
    canvas = Image.new("RGBA", (w + 80, h + 80), (9, 9, 12, 255))
    # what the client draws first: the (reskinned) vanilla container texture
    top = vanilla.crop((0, 0, 176, 17 + 18 * rows))
    bottom = vanilla.crop((0, 126, 176, 222))
    canvas.alpha_composite(top, (40, 40))
    canvas.alpha_composite(bottom, (40, 40 + 17 + 18 * rows + 1))
    # then items in slots, then the title's background glyph over everything
    for (cx, cy), name in slot_items(rows).items():
        path = os.path.join(VAN, f"textures/item/{name}.png")
        if os.path.exists(path):
            item = Image.open(path).convert("RGBA").resize((16, 16), Image.NEAREST)
            canvas.alpha_composite(item, (40 + cx + 1, 40 + cy + 1))
    canvas.alpha_composite(bg, (40, 40))

    # themed title is dark (#404040) on the vanilla-light skin, matching
    # MenuGui.styledTitle; white was for the old dark skin.
    draw_text(canvas, 40 + 8, 40 + 6, title, (64, 64, 64), bold=True)
    draw_text(canvas, 40 + 8, 40 + 20 + 18 * rows, "Inventory", (64, 64, 64))

    canvas = canvas.resize((canvas.width * SCALE, canvas.height * SCALE), Image.NEAREST)
    canvas.save(out)
    print("wrote", out, canvas.size)


if __name__ == "__main__":
    rows = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    title = sys.argv[2] if len(sys.argv) > 2 else "Shop"
    render(rows, title, sys.argv[3] if len(sys.argv) > 3 else "/tmp/preview.png")
