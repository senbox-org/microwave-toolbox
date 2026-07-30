"""Render a NATIVE-RESOLUTION crop of an interferogram.

Necessary because strided whole-scene rendering ALIASES: with the flat-earth term retained the fringe
rate can approach the sampling limit, and a 1/18 stride then turns a perfectly good fringe pattern
into uniform noise. Judging an interferogram from a decimated view is therefore unsafe -- a crop at
step=1 is the only way to see whether fringes exist.
"""
import re, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_fringes import phase_png

D, out, title = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
N = int(sys.argv[4]) if len(sys.argv) > 4 else 1200

def band(pat):
    h = next(p for p in sorted(D.glob("*.hdr")) if re.match(pat, p.stem))
    t = h.read_text(errors="replace")
    g = lambda k: int(re.search(rf"^{k}\s*=\s*(-?\d+)", t, re.I | re.M).group(1))
    W, L, dt, bo = g("samples"), g("lines"), g("data type"), g("byte order")
    d = np.dtype({4: np.float32, 5: np.float64}[dt]).newbyteorder(">" if bo == 1 else "<")
    return h.with_suffix(".img"), W, L, d

ip, W, L, d = band(r"^i_")
qp, _, _, _ = band(r"^q_")
# Centre the window: the scene centre is inside the swath, while a corner of a geocoded
# parallelogram is mostly nodata and would show nothing.
r0, c0 = max(0, L // 2 - N // 2), max(0, W // 2 - N // 2)
n_r, n_c = min(N, L - r0), min(N, W - c0)

def read(p):
    a = np.empty((n_r, n_c), dtype=np.float64)
    with open(p, "rb") as f:
        for k in range(n_r):
            f.seek(((r0 + k) * W + c0) * d.itemsize)
            a[k] = np.frombuffer(f.read(n_c * d.itemsize), dtype=d)
    return a

z = read(ip) + 1j * read(qp)
good = np.isfinite(z.real) & np.isfinite(z.imag) & (z != 0)
print(f"crop rows {r0}..{r0+n_r} cols {c0}..{c0+n_c}  valid {100.0*good.sum()/z.size:.1f}%")
if good.any():
    ph = np.angle(z[good])
    # Circular R over a windowed region is NOT a noise test: a coherent ramp cycles through every
    # phase value, so R -> 0 for dense fringes just as it does for noise. Reported for completeness
    # only. The discriminator below is the adjacent-pixel step: for uniform noise mean|dphi| is
    # pi/2 ~= 1.571 rad, while a smooth ramp of P pixels per fringe gives 2*pi/P.
    print(f"crop phase circular R {abs(np.mean(np.exp(1j*ph))):.4f} "
          f"(NOT a noise test -- see mean|dphi| below)")
    # fringe rate: mean |d(phase)| between adjacent columns, a direct aliasing check
    w = np.where(good, np.angle(z), np.nan)
    dph = np.abs(np.angle(np.exp(1j * (w[:, 1:] - w[:, :-1]))))
    m = np.nanmean(dph)
    print(f"adjacent-pixel |dphi| mean {m:.4f} rad  -> ~{2*np.pi/max(m,1e-9):.1f} px per fringe")
phase_png(z, out, title, max(n_r, n_c))
