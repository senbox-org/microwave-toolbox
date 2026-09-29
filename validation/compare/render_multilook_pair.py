"""Full-scene side-by-side of two geocoded ifgs after COMPLEX MULTILOOK to ~equal ground cells.

Complex averaging (mean of i+jq per block) reduces phase noise by sqrt(N) while preserving fringes
-- unlike decimation, which ALIASES, and unlike Goldstein, which can hallucinate continuity. Block
sizes are chosen per product from its pixel size so both panels end up on ~the same ground cell.
The right product is cropped to the left's geographic bbox via its own geotransform.

    python render_multilook_pair.py <left.dim> <ltitle> <right.dim> <rtitle> <cell_m> <out.png>
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_2x2 import hdr_info
from render_side_by_side import bands_of
from diff_vs_trad import i2m

M_PER_DEG = 111320.0


def multilook(dim: Path, r0: int, c0: int, nr: int, nc: int, my: int, mx: int) -> np.ndarray:
    ib, qb = bands_of(dim.with_suffix(".data"))
    W, L, d = hdr_info(ib)
    r0, c0 = max(0, r0), max(0, c0)
    nr, nc = min(nr, L - r0), min(nc, W - c0)
    NR, NC = nr // my, nc // mx
    out = np.zeros((NR, NC), dtype=np.complex128)
    cnt = np.zeros((NR, NC), dtype=np.int64)
    fi = open(ib.with_suffix(".img"), "rb")
    fq = open((qb or ib).with_suffix(".img"), "rb") if qb is not None else None
    for R in range(NR):
        acc = np.zeros(NC, dtype=np.complex128)
        n = np.zeros(NC, dtype=np.int64)
        for k in range(my):
            off = ((r0 + R * my + k) * W + c0) * d.itemsize
            fi.seek(off)
            a = np.frombuffer(fi.read(nc * d.itemsize), dtype=d).astype(np.float64)
            if fq is None:
                z = np.where(a != 0, np.exp(1j * a), 0)
            else:
                fq.seek(off)
                b = np.frombuffer(fq.read(nc * d.itemsize), dtype=d).astype(np.float64)
                z = a + 1j * b
            z = z[:NC * mx].reshape(NC, mx)
            good = z != 0
            acc += z.sum(axis=1)
            n += good.sum(axis=1)
        out[R] = acc
        cnt[R] = n
    # require half the block valid, else mark invalid
    return np.where(cnt >= (my * mx) // 2, out, 0)


def main() -> int:
    ldim, ltitle, rdim, rtitle, cell, out = (Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3]),
                                             sys.argv[4], float(sys.argv[5]), Path(sys.argv[6]))
    lsx, lsy, lx0, ly0 = i2m(ldim)
    rsx, rsy, rx0, ry0 = i2m(rdim)
    lib, _ = bands_of(ldim.with_suffix(".data"))
    LW, LL, _ = hdr_info(lib)
    c0 = int(np.floor((lx0 - rx0) / rsx))
    r0 = int(np.floor((ly0 - ry0) / rsy))
    ncR = int(np.ceil(LW * lsx / rsx))
    nrR = int(np.ceil(LL * lsy / rsy))

    panels = []
    for dim, title, (r, c, nr, nc), (sx, sy) in (
            (ldim, ltitle, (0, 0, LL, LW), (lsx, lsy)),
            (rdim, rtitle, (r0, c0, nrR, ncR), (rsx, rsy))):
        mx = max(1, int(round(cell / (abs(sx) * M_PER_DEG))))
        my = max(1, int(round(cell / (abs(sy) * M_PER_DEG))))
        z = multilook(dim, r, c, nr, nc, my, mx)
        aspect = abs(sy * my) / abs(sx * mx)
        panels.append((z, title, my, mx, aspect))
        print(f"{dim.name}: ML {my}x{mx} -> {z.shape[1]}x{z.shape[0]}")

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(16, 9), dpi=140)
    for ax, (z, title, my, mx, aspect) in zip(axes, panels):
        good = np.isfinite(z.real) & (z != 0)
        ph = np.where(good, np.angle(z), np.nan)
        cmap = matplotlib.colormaps["hsv"].copy()
        cmap.set_bad("#222222")
        im = ax.imshow(np.ma.masked_invalid(ph), cmap=cmap, vmin=-np.pi, vmax=np.pi,
                       interpolation="nearest", aspect=aspect)
        ax.set_title(f"{title}\ncomplex multilook {my}x{mx} (~{sys.argv[5]} m cells)", fontsize=10)
        ax.set_xticks([]); ax.set_yticks([])
    cb = fig.colorbar(im, ax=axes, fraction=0.025, pad=0.01, ticks=[-np.pi, 0, np.pi])
    cb.ax.set_yticklabels(["-pi", "0", "+pi"])
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, facecolor="#2b2b2b")
    plt.close(fig)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
