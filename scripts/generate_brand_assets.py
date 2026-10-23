"""Render Boxlet's vector parcel and platform icons from one dimetric projection.

Run from any directory: python scripts/generate_brand_assets.py
Requires Pillow. Does not launch, install, or interact with any application.
"""
from pathlib import Path
import json
import math
from PIL import Image, ImageDraw, ImageChops

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "design/brand"
SPEC = json.loads((BRAND / "parcel.json").read_text(encoding="utf-8"))
PALETTE = SPEC["palette"]
# Launcher art keeps one margin on every platform: the mark is scaled about its own
# bounding box and re-centred, so tiles never look cramped as they do unfitted.
ICON_SCALE = SPEC["iconScale"]
MARK_CENTER = (SPEC["top"][0] + (SPEC["axisU"][0] + SPEC["axisV"][0]) / 2,
               SPEC["top"][1] + (SPEC["axisU"][1] + SPEC["axisV"][1]) / 2 + SPEC["height"] / 2)


def project(u, v, z=0):
    return (SPEC["top"][0] + u * SPEC["axisU"][0] + v * SPEC["axisV"][0],
            SPEC["top"][1] + u * SPEC["axisU"][1] + v * SPEC["axisV"][1] - z * SPEC["height"])


def faces(closed=1):
    angle = math.radians(SPEC["openAngle"] * (1 - closed))
    c, z = math.cos(angle) / 2, math.sin(angle) / 2
    lo, hi = .5 - SPEC["tapeHalfWidth"], .5 + SPEC["tapeHalfWidth"]
    return {
        "left": [project(0, 1), project(1, 1), project(1, 1, -1), project(0, 1, -1)],
        "right": [project(1, 1), project(1, 0), project(1, 0, -1), project(1, 1, -1)],
        "interior": [project(0, 0), project(1, 0), project(1, 1), project(0, 1)],
        "rearLid": [project(0, 0), project(1, 0), project(1, c, z), project(0, c, z)],
        "frontLid": [project(0, 1), project(1, 1), project(1, 1-c, z), project(0, 1-c, z)],
        "topTape": [project(lo, 0), project(hi, 0), project(hi, 1), project(lo, 1)],
        "frontTape": [project(lo, 1), project(hi, 1), project(hi, 1, -.38), project(lo, 1, -.38)],
        "outline": [project(0, 0), project(1, 0), project(1, 0, -1), project(1, 1, -1), project(0, 1, -1), project(0, 1)],
    }


def rounded(points, cut=.012):
    """Quadratic corner segments, also used when rasterizing to keep SVG/PNG alike."""
    result = []
    for i, p in enumerate(points):
        prev, nxt = points[i-1], points[(i+1) % len(points)]
        def toward(q):
            length = math.dist(p, q)
            t = min(cut / length, .45) if length else 0
            return (p[0] + (q[0]-p[0])*t, p[1] + (q[1]-p[1])*t)
        result.append((toward(prev), p, toward(nxt)))
    return result


def path_data(points, cut=.012):
    def coord(p): return f"{p[0]*108:.4f},{p[1]*108:.4f}"
    segs = rounded(points, cut)
    return "M" + coord(segs[0][0]) + " " + " ".join(
        ("L" + coord(a) if i else "") + "Q" + coord(b) + " " + coord(c)
        for i, (a, b, c) in enumerate(segs)) + "Z"


def icon_group():
    """Scale the mark about its bounding box and centre it inside the 108 unit tile."""
    return (f'<g transform="translate(54 54) scale({ICON_SCALE:g}) '
            f'translate(-54 {-MARK_CENTER[1]*108:.4f})">')


def layers(closed=1):
    f = faces(closed)
    return [("left", "left", "leftShade"), ("right", "right", "rightShade"),
            ("interior", "interior", "interior"), ("rearLid", "top", "topShade"),
            ("frontLid", "top", "topShade")] + (
                [("topTape", "tape", "tape"), ("frontTape", "tape", "tape")] if closed == 1 else [])


def svg(closed=1, tile=False):
    f = faces(closed)
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">',
             '<title>Boxlet parcel</title>', '<desc>Dimetric parcel with parallel edges and a single white sealing strip.</desc>', '<defs>']
    for name, light, shade in layers(closed):
        parts.append(f'<linearGradient id="{name}" x2="0.7" y2="1"><stop stop-color="{PALETTE[light]}"/><stop offset="1" stop-color="{PALETTE[shade]}"/></linearGradient>')
    parts.append(f'<clipPath id="body"><path d="{path_data(f["outline"])}"/></clipPath>')
    parts.append('</defs>')
    if tile:
        parts.append(f'<rect width="108" height="108" rx="24" fill="{PALETTE["background"]}"/>')
        parts.append(icon_group())
    for name, _, _ in layers(closed):
        clip = ' clip-path="url(#body)"' if closed == 1 or name in ['left', 'right', 'interior'] else ''
        cut = .004 if 'Tape' in name else (.0 if clip else .008)
        parts.append(f'<path d="{path_data(f[name], cut)}" fill="url(#{name})"{clip}/>')
    if tile: parts.append('</g>')
    parts.append('</svg>')
    return "\n".join(parts) + "\n"


def bitmap(closed=1, tile=False, pixels=512):
    scale = 3
    side = pixels * scale
    im = Image.new("RGBA", (side, side))
    if tile:
        ImageDraw.Draw(im).rounded_rectangle((0, 0, side-1, side-1), radius=side*24/108, fill=PALETTE["background"])
    f = faces(closed)
    inset = ICON_SCALE if tile else 1
    def place(x, y):
        return ((.5 + inset * (x - MARK_CENTER[0])) * side, (.5 + inset * (y - MARK_CENTER[1])) * side)
    def polygon_mask(points, cut):
        mask = Image.new('L', im.size)
        pts = []
        for a, b, c in rounded(points, cut):
            pts.append(place(*a))
            for step in range(1, 9):
                t = step / 8
                pts.append(place(a[0]*(1-t)**2 + 2*b[0]*t*(1-t) + c[0]*t*t,
                                 a[1]*(1-t)**2 + 2*b[1]*t*(1-t) + c[1]*t*t))
        ImageDraw.Draw(mask).polygon(pts, fill=255)
        return mask
    outline = polygon_mask(f['outline'], .012)
    for name, light, shade in layers(closed):
        clipped = closed == 1 or name in ['left', 'right', 'interior']
        cut = .004 if 'Tape' in name else (.0 if clipped else .008)
        mask = polygon_mask(f[name], cut)
        if clipped: mask = ImageChops.multiply(mask, outline)
        rgb = lambda key: tuple(bytes.fromhex(PALETTE[key][1:]))
        a, b = rgb(light), rgb(shade)
        gradient = Image.new("RGBA", (1, side))
        gradient.putdata([tuple(round(a[i] + (b[i]-a[i])*y/(side-1)) for i in range(3)) + (255,) for y in range(side)])
        im.paste(gradient.resize(im.size), (0, 0), mask)
    return im.resize((pixels, pixels), Image.Resampling.LANCZOS)


def android_vector(closed=1, background=False, foreground=False, monochrome=False, scale=None):
    f = faces(closed)
    ns = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'
    out = [f'<vector {ns} android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    if background:
        out.append(f'<path android:fillColor="{PALETTE["background"]}" android:pathData="M24,0H84Q108,0 108,24V84Q108,108 84,108H24Q0,108 0,84V24Q0,0 24,0Z"/>')
    # Same inset as the exported tiles; the group keeps the mark centred on the tile.
    inset = scale if scale is not None else (ICON_SCALE if background or foreground else 1)
    if inset != 1:
        out.append(f'<group android:pivotX="54" android:pivotY="{MARK_CENTER[1]*108:.2f}" '
                   f'android:scaleX="{inset:g}" android:scaleY="{inset:g}" '
                   f'android:translateY="{54 - MARK_CENTER[1]*108:.2f}">')
    if monochrome:
        tape = [f["topTape"][0], f["topTape"][1], f["topTape"][2], f["frontTape"][2], f["frontTape"][3], f["topTape"][3]]
        out.append(f'<path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{path_data(f["outline"])} {path_data(tape, .004)}"/>')
    else:
        for name, light, shade in layers(closed):
            clipped = closed == 1 or name in ['left', 'right', 'interior']
            if clipped: out.append(f'<group><clip-path android:pathData="{path_data(f["outline"])}"/>')
            cut = .004 if 'Tape' in name else (.0 if clipped else .008)
            out.append(f'<path android:pathData="{path_data(f[name], cut)}"><aapt:attr name="android:fillColor"><gradient android:startX="20" android:startY="22" android:endX="80" android:endY="92" android:type="linear"><item android:offset="0" android:color="{PALETTE[light]}"/><item android:offset="1" android:color="{PALETTE[shade]}"/></gradient></aapt:attr></path>')
            if clipped: out.append('</group>')
    if inset != 1: out.append('</group>')
    out.append('</vector>')
    return '\n'.join(out) + '\n'


def kotlin_geometry():
    def points(ps): return 'listOf(' + ', '.join(f'Point({x:.6f}f, {y:.6f}f)' for x, y in ps) + ')'
    f = faces()
    text = '''package dev.boxlet.shared.brand

import kotlin.math.cos
import kotlin.math.sin

// Generated by scripts/generate_brand_assets.py from design/brand/parcel.json.
// Coordinates are dimensionless; all faces use the same dimetric projection.
object ParcelGeometry {
    data class Point(val x: Float, val y: Float)
'''
    for name, color in PALETTE.items():
        text += f'    const val {name[0].upper()+name[1:]}Color: Long = 0xFF{color[1:]}\n'
    text += f'''    /** Icon surfaces draw the mark centred and inset, matching the exported launcher art. */
    const val IconScale: Float = {ICON_SCALE}f
    val IconCenter = Point({MARK_CENTER[0]:.6f}f, {MARK_CENTER[1]:.6f}f)
'''
    for name in ['left', 'right', 'interior', 'topTape', 'frontTape', 'outline']:
        text += f'    val {name} = {points(f[name])}\n'
    text += f'''
    fun project(u: Float, v: Float, z: Float = 0f): Point = Point(
        {SPEC['top'][0]}f + {SPEC['axisU'][0]}f * u + ({SPEC['axisV'][0]}f) * v,
        {SPEC['top'][1]}f + {SPEC['axisU'][1]}f * u + {SPEC['axisV'][1]}f * v - {SPEC['height']}f * z,
    )

    fun lids(closed: Float): Pair<List<Point>, List<Point>> {{
        val angle = (1f - closed.coerceIn(0f, 1f)) * {math.radians(SPEC['openAngle']):.9f}f
        val depth = cos(angle) * .5f
        val lift = sin(angle) * .5f
        val rear = listOf(project(0f, 0f), project(1f, 0f), project(1f, depth, lift), project(0f, depth, lift))
        val front = listOf(project(0f, 1f), project(1f, 1f), project(1f, 1f-depth, lift), project(0f, 1f-depth, lift))
        return rear to front
    }}
}}
'''
    return text


def write(path, text):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main():
    write("app-shared/src/jvmSharedMain/kotlin/dev/boxlet/shared/brand/ParcelGeometry.kt", kotlin_geometry())
    for name, closed, tile in [('boxlet-mark', 1, False), ('boxlet-open-box', 0, False), ('boxlet-icon', 1, True)]:
        write(f'design/brand/{name}.svg', svg(closed, tile))
        bitmap(closed, tile).save(BRAND / f'{name}.png')
    bitmap(tile=True, pixels=256).save(BRAND / 'boxlet-icon-preview.png')
    resources = 'androidApp/src/main/res'
    write(f'{resources}/drawable/ic_boxlet.xml', android_vector(background=True))
    write(f'{resources}/drawable/ic_boxlet_foreground.xml', android_vector(foreground=True))
    write(f'{resources}/drawable/ic_boxlet_monochrome.xml', android_vector(foreground=True, monochrome=True))
    # Status-bar glyphs fill their 24dp slot, so they bypass the launcher inset.
    notification = android_vector(foreground=True, monochrome=True, scale=1.4)
    notification = notification.replace('android:width="108dp" android:height="108dp"', 'android:width="24dp" android:height="24dp"')
    write(f'{resources}/drawable/ic_boxlet_notification.xml', notification)
    write(f'{resources}/values/brand.xml', '<resources><color name="boxlet_icon_background">#FAFCFF</color></resources>\n')
    for version in ['v26', 'v33']:
        write(f'{resources}/mipmap-anydpi-{version}/ic_launcher.xml',
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '    <background android:drawable="@color/boxlet_icon_background"/>\n'
              '    <foreground android:drawable="@drawable/ic_boxlet_foreground"/>\n' +
              ('    <monochrome android:drawable="@drawable/ic_boxlet_monochrome"/>\n' if version == 'v33' else '') +
              '</adaptive-icon>\n')
    write(f'{resources}/mipmap-anydpi/ic_launcher.xml', android_vector(background=True))
    icon = bitmap(tile=True, pixels=512)
    assets = ROOT / 'windowsApp/Assets'
    icon.save(assets / 'AppIcon.ico', sizes=[(n, n) for n in [16, 24, 32, 48, 64, 128, 256]])
    for name, side in [('StoreLogo.png', 50), ('Square44x44Logo.scale-200.png', 88), ('Square150x150Logo.scale-200.png', 300),
                       ('Square44x44Logo.targetsize-24_altform-unplated.png', 24),
                       ('Square44x44Logo.targetsize-48_altform-lightunplated.png', 48)]:
        source = bitmap(pixels=512) if 'unplated' in name else icon
        source.resize((side, side), Image.Resampling.LANCZOS).save(assets / name)
    # Windows badge/lock-screen marks use a white glyph with transparent tape cutout.
    badge = bitmap(pixels=512)
    alpha = badge.getchannel('A')
    tape = Image.new('L', badge.size)
    for name in ['topTape', 'frontTape']:
        ImageDraw.Draw(tape).polygon([(x*512, y*512) for x,y in faces()[name]], fill=255)
    alpha = ImageChops.subtract(alpha, tape)
    badge = Image.new('RGBA', badge.size, 'white')
    badge.putalpha(alpha)
    badge.resize((48,48), Image.Resampling.LANCZOS).save(assets / 'LockScreenLogo.scale-200.png')
    for name, dims in [('Wide310x150Logo.scale-200.png', (620, 300)), ('SplashScreen.scale-200.png', (1240, 600))]:
        canvas = Image.new('RGBA', dims, PALETTE['background'])
        mark = bitmap(pixels=min(dims))
        canvas.alpha_composite(mark, ((dims[0]-mark.width)//2, (dims[1]-mark.height)//2))
        canvas.save(assets / name)
    icon.save(BRAND / 'boxlet.ico', sizes=[(n, n) for n in [16, 24, 32, 48, 64, 128, 256]])
    write('webApp/src/wasmJsMain/resources/favicon.svg', svg(tile=True))
    # Local artwork sheet, independent of app/device testing.
    review = Image.new('RGB', (1024, 620), '#F1F4F8')
    for i, source in enumerate([bitmap(closed=0), bitmap(closed=1), bitmap(tile=True), bitmap(tile=True)]):
        side = 80 if i == 3 else 240
        art = source.resize((side, side), Image.Resampling.LANCZOS)
        review.paste(art, (i*256+(256-side)//2, (380-side)//2), art)
    # Taskbar strip: icon surfaces at their real pixel sizes, with nearest-neighbour blow-ups.
    strip = Image.new('RGB', (1024, 240), '#20242B')
    x = 48
    for px in [64, 48, 32, 24, 16]:
        art = bitmap(tile=True, pixels=px)
        strip.paste(art, (x, (240-px)//2), art)
        x += px + 28
    x = 620
    for px in [32, 16]:
        art = bitmap(tile=True, pixels=px)
        art = art.resize((px*6, px*6), Image.Resampling.NEAREST)
        strip.paste(art, (x, 16), art)
        x += art.width + 24
    review.paste(strip, (0, 380))
    review.save(BRAND / 'boxlet-brand-review.png')
    print('Generated shared geometry, SVG/PNG masters, Android launcher/notification, Windows icons and web favicon.')


if __name__ == '__main__':
    main()
