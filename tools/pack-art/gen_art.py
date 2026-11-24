#!/usr/bin/env python3
"""Generates the easyeconomy resource-pack art into src/main/resources/pack/.

Everything is drawn pixel by pixel here so the pack ships real, crafted
textures instead of runtime-generated shapes. Re-run after edits; the
output is committed to the repo.
"""
import math
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "pack")

# ---------------------------------------------------------------- palette
# goattech house style: black and white, dark mode default. gold shows up
# only where money itself is pictured (coins, the title coin motif).
BG_TOP = (20, 20, 23)
BG_BOT = (13, 13, 16)
FRAME = (52, 52, 60)
FRAME_HI = (66, 66, 76)
TITLE_BG = (27, 27, 32)
DIVIDER = (46, 46, 54)
CELL_BORDER = (38, 38, 45)
CELL_FILL = (11, 11, 14)
CELL_SHADOW = (7, 7, 9)
CELL_HI = (24, 24, 29)
PILL = (168, 168, 174)
PILL_EDGE = (104, 104, 112)
GOLD = (216, 160, 60)
GOLD_DARK = (138, 100, 32)
GOLD_HI = (248, 217, 140)


def rgba(c, a=255):
    return (c[0], c[1], c[2], a)


# ---------------------------------------------------------------- gui skins
def slot_cells(rows):
    """Yields (x, y) of every 18x18 cell's top-left corner, item at +1,+1.

    Layout matches the vanilla generic container screen: chest grid at
    (7, 17), player inventory section starts at y = 18 + 18*rows.
    """
    cells = []
    for j in range(rows):
        for k in range(9):
            cells.append((7 + 18 * k, 17 + 18 * j))
    inv_y = 18 + 18 * rows + 14  # item top-left of first inventory row
    for j in range(3):
        for k in range(9):
            cells.append((7 + 18 * k, inv_y - 1 + 18 * j))
    hot_y = inv_y + 58
    for k in range(9):
        cells.append((7 + 18 * k, hot_y - 1))
    return cells


def gen_gui(rows):
    height = 114 + 18 * rows
    img = Image.new("RGBA", (176, height), (0, 0, 0, 0))
    px = img.load()

    # body: vertical gradient, rounded 2px corners over a 1px frame
    for y in range(height):
        t = y / max(1, height - 1)
        base = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
        for x in range(176):
            px[x, y] = rgba(base)
    # corners
    corner = {(0, 0), (1, 0), (0, 1), (175, 0), (174, 0), (175, 1),
              (0, height - 1), (1, height - 1), (0, height - 2),
              (175, height - 1), (174, height - 1), (175, height - 2)}
    for (x, y) in corner:
        px[x, y] = (0, 0, 0, 0)
    # frame
    for x in range(176):
        px[x, 0] = rgba(FRAME_HI) if x > 1 and x < 174 else px[x, 0]
        px[x, height - 1] = rgba(FRAME)
        px[x, 1] = px[x, 1] if x < 2 or x > 173 else rgba(FRAME)
    for y in range(height):
        px[0, y] = rgba(FRAME_HI) if y > 1 and y < height - 2 else px[0, y]
        px[175, y] = rgba(FRAME)
        if 1 < y < height - 2:
            px[1, y] = rgba(FRAME)
            px[174, y] = rgba(FRAME)
    px[0, 0] = px[175, 0] = (0, 0, 0, 0)
    px[0, height - 1] = px[175, height - 1] = (0, 0, 0, 0)

    # title bar: 17px tall, lighter fill, divider under it
    for y in range(2, 16):
        for x in range(2, 174):
            px[x, y] = rgba(TITLE_BG)
    for x in range(2, 174):
        px[x, 16] = rgba(DIVIDER)
    # panel behind the chest grid groups it visually
    for y in range(18, 17 + 18 * rows):
        for x in range(5, 171):
            if px[x, y][3] and (x < 7 or x > 168 or True):
                pass
            px[x, y] = rgba((17, 17, 21))
    # panel behind the chest grid groups it visually (cells stay clear)
    for y in range(18, 17 + 18 * rows):
        for x in range(5, 171):
            px[x, y] = rgba((17, 17, 21))
    # small coin motif, top right corner of the title bar
    coin_motif(px, 161, 5)
    coin_motif(px, 167, 6)

    # slot cells stay fully transparent: the client draws menu titles over
    # items, so an opaque cell would hide them, and the pack's dark
    # generic_54.png (the vanilla container texture, reskinned to match)
    # provides the slot visuals behind the transparent cutouts
    for (cx, cy) in slot_cells(rows):
        for dy in range(18):
            for dx in range(18):
                px[cx + dx, cy + dy] = (0, 0, 0, 0)

    # light pill behind the hard-coded dark "Inventory" label so it stays
    # readable on the dark skin (label text top-left is at (8, 20+18*rows))
    label_y = 20 + 18 * rows
    pill_rect(px, 5, label_y - 2, 56, 11, PILL, PILL_EDGE)
    return img


def pill_rect(px, x0, y0, w, h, fill, edge):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            corner = (x in (x0, x0 + w - 1)) and (y in (y0, y0 + h - 1))
            if corner:
                continue
            if x in (x0, x0 + w - 1) or y in (y0, y0 + h - 1):
                px[x, y] = rgba(edge)
            else:
                px[x, y] = rgba(fill)


def paint_cell(px, cx, cy):
    for dy in range(18):
        for dx in range(18):
            x, y = cx + dx, cy + dy
            if dx == 0 or dy == 0 or dx == 17 or dy == 17:
                px[x, y] = rgba(CELL_BORDER)
            else:
                px[x, y] = rgba(CELL_FILL)
    for i in range(1, 17):
        px[cx + i, cy + 1] = rgba(CELL_SHADOW)
        px[cx + 1, cy + i] = rgba(CELL_SHADOW)
        px[cx + i, cy + 16] = rgba(CELL_HI)
        px[cx + 16, cy + i] = rgba(CELL_HI)
    px[cx + 1, cy + 1] = rgba(CELL_SHADOW)
    px[cx + 16, cy + 16] = rgba(CELL_HI)


def gen_vanilla_container():
    """Dark reskin of vanilla's generic_54.png (256x256): what shows
    through the transparent slot cutouts of the gui skin, and what plain
    chests/barrels draw while the pack is on. Same coordinates as the
    vanilla texture."""
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    px = img.load()
    for y in range(223):
        t = y / 222
        base = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
        for x in range(176):
            px[x, y] = rgba(base)
    # title strip + divider
    for y in range(1, 16):
        for x in range(1, 175):
            px[x, y] = rgba(TITLE_BG)
    for x in range(1, 175):
        px[x, 16] = rgba(DIVIDER)
    # chest cells: 6 rows at (7, 17)
    for j in range(6):
        for k in range(9):
            paint_cell(px, 7 + 18 * k, 17 + 18 * j)
    # divider between chest rows and the inventory section
    for x in range(1, 175):
        px[x, 125] = rgba(DIVIDER)
    # inventory cells at (7, 139), hotbar at (7, 197)
    for j in range(3):
        for k in range(9):
            paint_cell(px, 7 + 18 * k, 139 + 18 * j)
    for k in range(9):
        paint_cell(px, 7 + 18 * k, 197)
    # light pill behind the dark "Inventory" label (label top-left (8, 128))
    pill_rect(px, 5, 126, 56, 11, PILL, PILL_EDGE)
    # section frame at the bottom of the inventory region
    for x in range(1, 175):
        px[x, 221] = rgba(FRAME)
    return img


def coin_motif(px, cx, cy):
    # 7px coin disc with a second implied behind, gold on dark
    disc = [
        ".XXX.",
        "XHHBX",
        "XHBBX",
        "XBBDX",
        "XDDX.",
    ]
    pal = {"X": GOLD_DARK, "H": GOLD_HI, "B": GOLD, "D": GOLD_DARK}
    for dy, row in enumerate(disc):
        for dx, ch in enumerate(row):
            if ch in pal:
                px[cx + dx, cy + dy] = rgba(pal[ch])


# ---------------------------------------------------------------- coins
def coin(px, size, face, rim, emboss, hi, outline, symbol=None, sym_color=None):
    c = (size - 1) / 2.0
    r_out = size / 2 - 0.6
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c)
            if d > r_out:
                continue
            ang = math.degrees(math.atan2(y - c, x - c))  # +y is down
            if d > r_out - 0.9:
                col = outline
            elif d > r_out - 2.0:
                # rim, lit top-left, shaded bottom-right
                if -170 < ang < -20:
                    col = hi
                elif 40 < ang < 160:
                    col = rim
                else:
                    col = face
            elif abs(d - (r_out - 3.1)) < 0.55:
                col = emboss
            else:
                col = face
            px[x, y] = rgba(col)
    if symbol:
        w = len(symbol[0])
        h = len(symbol)
        ox = int(round(c - w / 2 + 0.5))
        oy = int(round(c - h / 2 + 0.5))
        for dy, row in enumerate(symbol):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[ox + dx, oy + dy] = rgba(sym_color or emboss)
    # sparkle
    px[int(c) - 2, int(c) - 3] = rgba(hi)
    px[int(c) - 3, int(c) - 2] = rgba(hi)


def gem(px, rows_map, pal):
    """rows_map: list of strings; chars index into pal."""
    for dy, row in enumerate(rows_map):
        for dx, ch in enumerate(row):
            if ch in pal:
                px[dx, dy] = rgba(pal[ch])


def gen_coins():
    specs = {
        0: dict(face=(201, 133, 74), rim=(156, 99, 48), emboss=(168, 111, 56),
                hi=(236, 184, 135), outline=(51, 32, 15),
                symbol=[" ## ", " ## ", "    "][:2]),
        1: dict(face=(201, 204, 212), rim=(155, 160, 171), emboss=(168, 173, 184),
                hi=(238, 241, 246), outline=(38, 40, 46),
                symbol=["####", "    "][:1]),
        2: dict(face=(227, 176, 74), rim=(181, 133, 46), emboss=(193, 146, 58),
                hi=(248, 217, 140), outline=(58, 42, 12),
                symbol=["# #", " # ", "# #"]),
        5: dict(face=(176, 124, 232), rim=(125, 79, 179), emboss=(140, 95, 196),
                hi=(220, 196, 248), outline=(46, 26, 71),
                symbol=[" ##", "## ", " ##"]),
        6: dict(face=(74, 74, 82), rim=(50, 50, 58), emboss=(61, 61, 69),
                hi=(150, 150, 162), outline=(22, 22, 26),
                symbol=["# #", " # ", "# #"]),
        7: dict(face=(240, 190, 90), rim=(196, 148, 56), emboss=(212, 164, 70),
                hi=(255, 232, 170), outline=(66, 48, 14),
                symbol=["# #", " # ", "# #"]),
    }
    for tier, s in specs.items():
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        coin(img.load(), 16, s["face"], s["rim"], s["emboss"], s["hi"], s["outline"],
             symbol=s.get("symbol"), sym_color=s["emboss"])
        save(img, f"textures/item/coin_tier{tier}.png")

    # tier 3: emerald gem (hexagon)
    emerald = [
        "....XXXXX.......",
        "...XHHHHHX......",
        "..XHHFFFFX X....".replace(" ", ""),
        "..XHFFFFFFX.....",
        ".XHFFFFFFFFX....",
        ".XFFFFFFFFFX....",
        "XFFFFFFFFFFX....",
        "XFFFFDFFFFFX....",
        "XFFDDDDDFFFX....",
        ".XDDDDDDDDX.....",
        ".XDDDDDDDX......",
        "..XDDDDDX.......",
        "...XXXXX........",
    ]
    gen_gem_file(emerald, {"X": (13, 58, 34), "H": (157, 240, 192), "F": (52, 201, 110),
                           "D": (30, 138, 74)}, "textures/item/coin_tier3.png")
    # tier 4: diamond gem
    diamond = [
        "..XXXXXXXXXX....",
        ".XHHHHHHHHHHX...",
        "XHHFFFFFFFFFFX..",
        "XHFFFFFFFFFFFDX.".replace(" ", ""),
        ".XFFFFFFFFFFDX..",
        "..XFFFFFFFFDX...",
        "...XFFFFFFDX....",
        "....XFFFFDX.....",
        ".....XFFDX......",
        "......XDX.......",
        ".......X........",
    ]
    gen_gem_file(diamond, {"X": (18, 61, 71), "H": (200, 244, 252), "F": (95, 212, 232),
                           "D": (47, 157, 179)}, "textures/item/coin_tier4.png")


def gen_gem_file(rows, pal, rel):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    gem(img.load(), rows, pal)
    save(img, rel)


# ---------------------------------------------------------------- icons
WHITE = (236, 236, 240)
GRAY = (154, 154, 164)


def icon(rows, pal, rel):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch in pal:
                px[dx, dy] = rgba(pal[ch])
    save(img, rel)


def gen_icons():
    w = {"W": WHITE, "G": GRAY, "Y": GOLD, "y": GOLD_DARK, "h": GOLD_HI}
    icon([  # back: slim left arrow
        "................",
        "................",
        "................",
        "....WW..........",
        "...WWW..........",
        "..WWWWWWWWWW....",
        ".WWWWWWWWWWWW...",
        "..WWWWWWWWWW....",
        "...WWW..........",
        "....WW..........",
        "................",
        "................",
    ], w, "textures/item/gui/back.png")
    icon([
        "................",
        "................",
        ".....WW.........",
        "....WWW.........",
        "...WWW..........",
        "..WWW...........",
        "..WWW...........",
        "...WWW..........",
        "....WWW.........",
        ".....WW.........",
        "................",
    ], w, "textures/item/gui/nav_prev.png")
    icon([
        "................",
        "................",
        ".........WW.....",
        ".........WWW....",
        "..........WWW...",
        "...........WWW..",
        "...........WWW..",
        "..........WWW...",
        ".........WWW....",
        ".........WW.....",
        "................",
    ], w, "textures/item/gui/nav_next.png")
    icon([  # all listings: three-line list
        "................",
        "..WW..WWWWWW....",
        "..WW..WWWWWW....",
        "................",
        "..WW..WWWWWW....",
        "..WW..WWWWWW....",
        "................",
        "..WW..WWWWWW....",
        "..WW..WWWWWW....",
        "................",
    ], w, "textures/item/gui/listings_all.png")
    icon([  # my listings: price tag
        "................",
        "................",
        "...WWWWWWW......",
        "..WWWWWWWWW.....",
        "..WGWWWWWWWW....",
        "..WWWWWWWWWWW...",
        "...WWWWWWWWWW...",
        "....WWWWWWWWW...",
        ".....WWWWWWWW...",
        "......WWWWWWW...",
        ".......WWWWW....",
        "................",
    ], w, "textures/item/gui/listings_mine.png")
    icon([  # balance: two stacked coins
        "................",
        "................",
        ".....yyyyyy.....",
        "....yYYYYYYy....",
        "....yYHHYYYy....",
        "....yYYYYYYy....",
        ".....yyyyyy.....",
        "..yyyyyyyyyy....",
        ".yYYYYYYYYYYy...",
        ".yYHHYYYYYYYy...",
        ".yYYYYYYYYYYy...",
        "..yyyyyyyyyy....",
        "................",
    ], w, "textures/item/gui/balance.png")


# ---------------------------------------------------------------- badge
def gen_badge():
    img = Image.new("RGBA", (8, 8), (0, 0, 0, 0))
    coin(img.load(), 8, GOLD, GOLD_DARK, (193, 146, 58), GOLD_HI, (46, 32, 8))
    save(img, "textures/font/gui_title.png")


# ---------------------------------------------------------------- io
def save(img, rel):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", rel, img.size)


def main():
    for rows in range(1, 7):
        save(gen_gui(rows), f"textures/font/gui_bg_{rows}.png")
    save(gen_vanilla_container(), "minecraft/textures/gui/container/generic_54.png")
    gen_coins()
    gen_icons()
    gen_badge()


if __name__ == "__main__":
    main()
