"""
Silvershire ornate theme art (original): forged iron / steel / bronze pieces rendered with a
little lighting model, plus a tileable dark stone texture. Produces
  - Android: 9-patch PNGs (drawable-xxhdpi) and the stone tile (drawable-nodpi)
  - Web: plain PNGs (tools/theme-out/) and tools/theme-out/theme.css.txt with base64 data URLs
Run:  python tools/gen_theme_assets.py   (needs numpy + Pillow)
"""
import base64, io, os, math
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')
WEB = os.path.join(ROOT, 'tools', 'theme-out')
os.makedirs(WEB, exist_ok=True)
SS = 4  # supersampling
rng = np.random.default_rng(7)

METALS = {
    'iron':   dict(dark=(12, 12, 14),  mid=(52, 53, 58),  light=(108, 110, 116), spec=(186, 189, 196)),
    'steel':  dict(dark=(18, 20, 24),  mid=(84, 88, 96),  light=(158, 162, 170), spec=(226, 229, 234)),
    'bronze': dict(dark=(26, 16, 8),   mid=(110, 76, 38), light=(176, 132, 72),  spec=(236, 206, 150)),
    'gold':   dict(dark=(28, 20, 6),   mid=(120, 92, 36), light=(196, 160, 84),  spec=(246, 226, 160)),
}


def shade(m, b):
    """b in 0..1 -> RGB along dark -> mid -> light -> spec."""
    b = np.clip(b, 0, 1)[..., None]
    d, mi, l, s = (np.array(m[k], float) for k in ('dark', 'mid', 'light', 'spec'))
    out = np.where(b < 0.45, d + (mi - d) * (b / 0.45),
          np.where(b < 0.85, mi + (l - mi) * ((b - 0.45) / 0.4), l + (s - l) * ((b - 0.85) / 0.15)))
    return out


def value_noise(h, w, cells, seed, tile=True):
    r = np.random.default_rng(seed)
    gy, gx = cells
    g = r.random((gy + 1, gx + 1))
    if tile:
        g[-1, :] = g[0, :]; g[:, -1] = g[:, 0]
    ys = np.linspace(0, gy, h, endpoint=False); xs = np.linspace(0, gx, w, endpoint=False)
    yi = ys.astype(int); xi = xs.astype(int); ty = ys - yi; tx = xs - xi
    ty = ty * ty * (3 - 2 * ty); tx = tx * tx * (3 - 2 * tx)
    a = g[yi][:, xi]; b = g[yi][:, xi + 1]; c = g[yi + 1][:, xi]; d = g[yi + 1][:, xi + 1]
    return (a * (1 - tx) + b * tx) * (1 - ty[:, None]) + (c * (1 - tx) + d * tx) * ty[:, None]


def fbm(h, w, seed, base=4, octaves=5, tile=True):
    v = np.zeros((h, w)); amp = 1; tot = 0
    for o in range(octaves):
        c = base * 2 ** o
        v += amp * value_noise(h, w, (c, c), seed + o, tile); tot += amp; amp *= 0.5
    return v / tot


def rrect_sdf(h, w, x0, y0, x1, y1, rad):
    """Signed distance (px) to a rounded rect, negative inside. Coordinates in pixels."""
    ys, xs = np.mgrid[0:h, 0:w] + 0.5
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hx, hy = (x1 - x0) / 2 - rad, (y1 - y0) / 2 - rad
    qx = np.abs(xs - cx) - hx; qy = np.abs(ys - cy) - hy
    out = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0)
    return out - rad


def lit_band(sdf, inner, outer, metal, grit, light=(-0.55, -0.7, 0.9), relief=2.2):
    """Rounded metal bar where inner <= -sdf <= outer. Returns rgb, alpha."""
    d = -sdf
    t = np.clip((d - inner) / max(1e-6, outer - inner), 0, 1)
    gy, gx = np.gradient(d)
    n = np.hypot(gx, gy) + 1e-6; gx /= n; gy /= n
    dh = (1 - 2 * t) / np.sqrt(np.clip(1 - (2 * t - 1) ** 2, 0.02, 1))     # slope of a half cylinder
    Nx, Ny, Nz = -dh * gx * relief * 0.35, -dh * gy * relief * 0.35, np.ones_like(d)
    L = np.array(light, float); L /= np.linalg.norm(L)
    nn = np.sqrt(Nx ** 2 + Ny ** 2 + Nz ** 2)
    diff = np.clip((Nx * L[0] + Ny * L[1] + Nz * L[2]) / nn, 0, 1)
    # specular (Blinn)
    H = L + np.array([0, 0, 1.0]); H /= np.linalg.norm(H)
    spec = np.clip((Nx * H[0] + Ny * H[1] + Nz * H[2]) / nn, 0, 1) ** 28
    b = 0.12 + 0.72 * diff + 0.5 * spec
    b = b * (0.82 + 0.3 * grit)
    rgb = shade(metal, b)
    a = ((d >= inner) & (d <= outer)).astype(float)
    return rgb, a


def soft(mask_d, edge=1.0):
    return np.clip(mask_d / edge + 0.5, 0, 1)


def rivet(img, alpha, cx, cy, r, metal, grit):
    h, w = alpha.shape
    ys, xs = np.mgrid[0:h, 0:w] + 0.5
    dx, dy = (xs - cx) / r, (ys - cy) / r
    rr = dx * dx + dy * dy
    m = rr <= 1
    nz = np.sqrt(np.clip(1 - rr, 0, 1))
    L = np.array([-0.55, -0.7, 0.9]); L /= np.linalg.norm(L)
    diff = np.clip(dx * L[0] + dy * L[1] + nz * L[2], 0, 1)
    spec = diff ** 30
    b = 0.15 + 0.7 * diff + 0.6 * spec
    col = shade(metal, b * (0.85 + 0.25 * grit))
    # drop shadow
    sh = np.clip(1 - np.hypot(xs - cx - r * 0.35, ys - cy - r * 0.45) / (r * 1.5), 0, 1) * 0.6
    img[...] = img * (1 - sh[..., None] * (~m)[..., None])
    a = np.clip((1 - np.sqrt(rr)) * r * 0.9 + 0.5, 0, 1)
    img[...] = img * (1 - a[..., None]) + col * a[..., None]
    alpha[...] = np.maximum(alpha, np.maximum(a, sh * 0.8 * (alpha > 0)))


def over(dst_rgb, dst_a, src_rgb, src_a):
    out_a = src_a + dst_a * (1 - src_a)
    out = (src_rgb * src_a[..., None] + dst_rgb * dst_a[..., None] * (1 - src_a[..., None])) / np.maximum(out_a, 1e-6)[..., None]
    return out, out_a


def scratches(h, w, n, seed):
    r = np.random.default_rng(seed)
    m = np.zeros((h, w))
    for _ in range(n):
        x, y = r.random() * w, r.random() * h
        a = r.random() * math.pi; L = (0.05 + r.random() * 0.25) * min(h, w)
        for t in np.linspace(0, 1, int(L)):
            xi, yi = int(x + math.cos(a) * L * t) % w, int(y + math.sin(a) * L * t) % h
            m[yi, xi] = r.choice([-1, 1]) * (0.5 + r.random() * 0.5)
    return m


def finish(rgb, a, size):
    """Downsample from SS and return PIL RGBA."""
    arr = np.dstack([np.clip(rgb, 0, 255), np.clip(a, 0, 1) * 255]).astype(np.uint8)
    im = Image.fromarray(arr, 'RGBA')
    return im.resize(size, Image.LANCZOS)


def ninepatch(im, stretch_x, stretch_y, pad=None):
    """Wrap an RGBA image with the 1px 9-patch border. stretch_*: (start, end) in image px.
    pad: (left, top, right, bottom) content padding in px, None = no padding."""
    w, h = im.size
    out = Image.new('RGBA', (w + 2, h + 2), (0, 0, 0, 0))
    out.paste(im, (1, 1))
    px = out.load()
    for x in range(stretch_x[0], stretch_x[1]): px[x + 1, 0] = (0, 0, 0, 255)
    for y in range(stretch_y[0], stretch_y[1]): px[0, y + 1] = (0, 0, 0, 255)
    l, t, r, b = pad if pad else (0, 0, 0, 0)
    for x in range(l, w - r): px[x + 1, h + 1] = (0, 0, 0, 255)
    for y in range(t, h - b): px[w + 1, y + 1] = (0, 0, 0, 255)
    return out


def save_android(im9, name):
    d = os.path.join(RES, 'drawable-xxhdpi'); os.makedirs(d, exist_ok=True)
    im9.save(os.path.join(d, name + '.9.png'))


def save_web(im, name):
    im.save(os.path.join(WEB, name + '.png'))
    buf = io.BytesIO(); im.save(buf, 'PNG')
    return 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()


# ── stone tile (tileable) ──
def stone_tile(N=256):
    v = fbm(N, N, 11, base=4, octaves=6)
    v2 = fbm(N, N, 31, base=16, octaves=3)
    pits = (np.random.default_rng(5).random((N, N)) < 0.012) * -0.25
    b = 0.06 + 0.10 * v + 0.05 * v2 + pits
    base = np.array([20, 21, 24], float); hi = np.array([46, 47, 52], float)
    rgb = base + (hi - base) * np.clip(b / 0.22, 0, 1)[..., None]
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), 'RGB')


# ── framed panel ──
def panel(metal='iron', A=120, R=10, band=9, groove=2, lip=4, bracket=28):
    S = A * SS
    grit = fbm(S, S, 3, base=24, octaves=3, tile=False)
    sc = scratches(S, S, 26, 9) * 0.25
    g = grit + sc
    sdf = rrect_sdf(S, S, 0, 0, S, S, R * SS)
    rgb = np.zeros((S, S, 3)); a = np.zeros((S, S))
    # interior: very dark, inner shadow near the lip
    d = -sdf
    inside = d > (band + groove + lip) * SS
    ish = np.clip(1 - (d - (band + groove + lip) * SS) / (12 * SS), 0, 1)
    fill = np.array([18, 19, 22], float) * (1 - 0.45 * ish)[..., None]
    rgb = np.where(inside[..., None], fill, rgb); a = np.where(inside, 0.95, a)
    # groove
    gm = (d > band * SS) & (d <= (band + groove) * SS)
    rgb = np.where(gm[..., None], np.array([6, 6, 7], float), rgb); a = np.where(gm, 1, a)
    # outer bar + inner lip
    for inner, outer, met, rel in ((0, band, metal, 2.4), (band + groove, band + groove + lip, 'steel' if metal != 'steel' else 'iron', 1.6)):
        c, al = lit_band(sdf, inner * SS, outer * SS, METALS[met], g, relief=rel)
        rgb = np.where(al[..., None] > 0, c, rgb); a = np.maximum(a, al)
    # rust / grime specks on iron
    if metal == 'iron':
        rust = (fbm(S, S, 41, base=10, octaves=3, tile=False) > 0.68) & (d < band * SS)
        rgb = np.where(rust[..., None], rgb * 0.55 + np.array([70, 34, 16]) * 0.45, rgb)
    # corner brackets (L plates) with rivets
    B = bracket * SS; T = (band + 3) * SS
    for (fx, fy) in ((0, 0), (1, 0), (0, 1), (1, 1)):
        ys, xs = np.mgrid[0:S, 0:S] + 0.5
        X = xs if fx == 0 else S - xs; Y = ys if fy == 0 else S - ys
        # L shape: union of two bars
        q1 = np.maximum(X - B, Y - T); q2 = np.maximum(X - T, Y - B)
        plate = np.minimum(q1, q2)            # <0 inside (box sdf approx)
        plate = np.maximum(plate, -np.minimum(X, Y) + 2 * SS)   # inset from the outer edge
        L = plate < 0
        c, al = lit_band(plate, 0, 3.2 * SS, METALS['steel' if metal == 'iron' else metal], g, relief=3)
        flat = shade(METALS['steel' if metal == 'iron' else metal], 0.42 * (0.85 + 0.3 * g))
        col = np.where((-plate < 3.2 * SS)[..., None], c, flat)
        # shadow under the plate
        sh = (plate > 0) & (plate < 2.5 * SS) & (d > 0)
        rgb = np.where(sh[..., None], rgb * 0.45, rgb)
        rgb = np.where(L[..., None], col, rgb); a = np.where(L, 1, a)
        rx = (band * 0.62) * SS; cx = rx if fx == 0 else S - rx; cy = rx if fy == 0 else S - rx
        rivet(rgb, a, cx, cy, 2.6 * SS, METALS['steel'], g)
    # outer anti-aliased edge + dark outline
    edge = soft(-sdf, SS * 1.0)
    a = a * edge
    outline = (d >= 0) & (d < 0.9 * SS)
    rgb = np.where(outline[..., None], rgb * 0.35, rgb)
    return finish(rgb, a, (A, A))


# ── header / bottom bar ──
def bar(W=120, H=60, metal='iron'):
    S = SS; w, h = W * S, H * S
    ys, xs = np.mgrid[0:h, 0:w] + 0.5
    g = fbm(h, w, 13, base=20, octaves=3, tile=False) + scratches(h, w, 12, 3) * 0.2
    t = ys / h
    m = METALS[metal]
    b = 0.20 + 0.10 * (1 - t) + 0.05 * g
    rgb = shade(m, b * (0.9 + 0.2 * g))
    # top highlight line, bottom forged rim (6px) with bevel
    rim = 7 * S
    top = ys < 1.2 * S
    rgb = np.where(top[..., None], shade(m, 0.8), rgb)
    sdf = -(h - ys)  # distance to bottom edge, negative inside
    c, al = lit_band(np.where(ys > h - rim, -(h - ys) - 0, 1.0) * 1.0, 0, rim, m, g, relief=2.4)
    yy = (ys > h - rim)
    # simple bevel for the rim
    tt = (ys - (h - rim)) / rim
    bev = np.where(tt < 0.35, 0.75 - tt, 0.45 - (tt - 0.35) * 0.5)
    rgb = np.where(yy[..., None], shade(m, bev * (0.85 + 0.3 * g)), rgb)
    groove = (ys > h - rim - 1.2 * S) & (ys <= h - rim)
    rgb = np.where(groove[..., None], np.array([6, 6, 7], float), rgb)
    a = np.ones((h, w))
    for cx in (9 * S, w - 9 * S):
        rivet(rgb, a, cx, h - rim / 2, 2.2 * S, METALS['steel'], g)
    return finish(rgb, a, (W, H))


# ── button ──
def button(W=96, H=48, R=8, metal='iron', pressed=False):
    w, h = W * SS, H * SS
    g = fbm(h, w, 17, base=16, octaves=3, tile=False) + scratches(h, w, 8, 4) * 0.2
    sdf = rrect_sdf(h, w, 0, 0, w, h, R * SS)
    d = -sdf
    m = METALS[metal]
    ys = np.mgrid[0:h, 0:w][0] + 0.5
    face = shade(m, (0.30 if not pressed else 0.2) + 0.12 * (1 - ys / h) * (0 if pressed else 1) + 0.05 * g)
    rgb = face
    c, al = lit_band(sdf, 0, 3.5 * SS, m, g, relief=2.6 if not pressed else -2.0)
    rgb = np.where((d < 3.5 * SS)[..., None], c, rgb)
    rgb = np.where(((d >= 0) & (d < 0.8 * SS))[..., None], rgb * 0.3, rgb)
    a = soft(d, SS)
    return finish(rgb, a, (W, H))


# ── bubble rim (foreground overlay; interior transparent) ──
def rim(W=96, H=96, radii=(18, 18, 18, 5), metal='bronze', band=2.6):
    w, h = W * SS, H * SS
    g = fbm(h, w, 23, base=16, octaves=3, tile=False)
    ys, xs = np.mgrid[0:h, 0:w] + 0.5
    # per-corner radius: pick by quadrant (tl, tr, br, bl)
    tl, tr, br, bl = (r * SS for r in radii)
    left, top = xs < w / 2, ys < h / 2
    R = np.where(top, np.where(left, tl, tr), np.where(left, bl, br))
    cx, cy = w / 2, h / 2
    qx = np.abs(xs - cx) - (w / 2 - R); qy = np.abs(ys - cy) - (h / 2 - R)
    sdf = np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - R
    c, al = lit_band(sdf, 0, band * SS, METALS[metal], g, relief=2.4)
    d = -sdf
    a = np.where(d <= band * SS, soft(d, SS) * np.clip((band * SS - d) / SS + 0.5, 0, 1), 0)
    # faint inner glow line
    glow = (d > band * SS) & (d < (band + 1.2) * SS)
    rgb = np.where(glow[..., None], np.array(METALS[metal]['light'], float), c)
    a = np.where(glow, 0.18, a)
    return finish(rgb, a, (W, H))


def main():
    css = {}
    t = stone_tile()
    os.makedirs(os.path.join(RES, 'drawable-nodpi'), exist_ok=True)
    t.save(os.path.join(RES, 'drawable-nodpi', 'ornate_stone_tile.png'))
    css['stone'] = save_web(t.convert('RGBA'), 'stone')

    p = panel('iron'); save_android(ninepatch(p, (31, 89), (31, 89)), 'ornate_panel'); css['panel'] = save_web(p, 'panel')
    pg = panel('bronze'); save_android(ninepatch(pg, (31, 89), (31, 89)), 'ornate_panel_bronze'); css['panel_bronze'] = save_web(pg, 'panel_bronze')
    b = bar(); save_android(ninepatch(b, (22, 98), (6, 44)), 'ornate_bar'); css['bar'] = save_web(b, 'bar')
    bb = b.transpose(Image.FLIP_TOP_BOTTOM); save_android(ninepatch(bb, (22, 98), (16, 54)), 'ornate_bar_bottom')
    bn = button(); save_android(ninepatch(bn, (14, 82), (12, 36), pad=(36, 24, 36, 24)), 'ornate_button'); css['button'] = save_web(bn, 'button')
    bp = button(pressed=True); save_android(ninepatch(bp, (14, 82), (12, 36), pad=(36, 24, 36, 24)), 'ornate_button_pressed'); css['button_pressed'] = save_web(bp, 'button_pressed')
    # bubble rims at xxhdpi: radius 18dp -> 54px, small corner 5dp -> 15px; art 150px
    rs = rim(150, 150, (54, 54, 15, 54), 'bronze', 7.5); save_android(ninepatch(rs, (58, 92), (58, 92)), 'ornate_rim_sent'); css['rim_sent'] = save_web(rs, 'rim_sent')
    rr = rim(150, 150, (15, 54, 54, 54), 'steel', 7.5); save_android(ninepatch(rr, (58, 92), (58, 92)), 'ornate_rim_recv'); css['rim_recv'] = save_web(rr, 'rim_recv')
    with open(os.path.join(WEB, 'theme.css.txt'), 'w') as f:
        for k, v in css.items(): f.write(f'--orn-{k.replace("_", "-")}: url("{v}");\n')
    print('ok', {k: len(v) for k, v in css.items()})


if __name__ == '__main__':
    main()
