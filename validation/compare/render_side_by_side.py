"""Side-by-side wrapped-phase panels of two geocoded interferograms over the SAME ground.

The products need not share a lattice: the right product is cropped to the left product's bounding
box via its OWN geotransform, each panel is decimated per axis, and each panel's display aspect is
set from its pixel size so the ground is shown undistorted even for anisotropic grids (a 2.35x14 m
GSLC next to a 14 m-square TC). Panels share the cyclic colour map and fixed [-pi, pi] range.
Decimation is annotated because a decimated view of a dense-fringe product ALIASES -- these panels
are for pattern comparison, not fringe counting.

    python render_side_by_side.py <left.dim> <left-title> <right.dim> <right-title> <out.png>
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_2x2 import hdr_info, iq
from diff_vs_trad import i2m


def bands_of(data_dir: Path):
    """(i_hdr, q_hdr) when the product is complex, else (phase_hdr, None). A terrain-corrected ifg
    often carries only a Phase_* band (SNAP TC converts complex sources), so accept both forms."""
    try:
        return iq(data_dir)
    except RuntimeError:
        ph = sorted(data_dir.glob("Phase_*.hdr"))
        if not ph:
            raise
        return ph[0], None


def read_dec(dim: Path, r0: int, c0: int, nr: int, nc: int, decy: int, decx: int) -> np.ndarray:
    ib, qb = bands_of(dim.with_suffix(".data"))
    W, L, d = hdr_info(ib)
    r0, c0 = max(0, r0), max(0, c0)
    nr, nc = min(nr, L - r0), min(nc, W - c0)
    rows = range(0, nr, decy)
    out = np.empty((len(rows), (nc + decx - 1) // decx), dtype=np.complex128)
    with open(ib.with_suffix(".img"), "rb") as fi, \
         open((qb or ib).with_suffix(".img"), "rb") as fq:
        for k, r in enumerate(rows):
            off = ((r0 + r) * W + c0) * d.itemsize
            fi.seek(off)
            a = np.frombuffer(fi.read(nc * d.itemsize), dtype=d)[::decx]
            if qb is None:
                # wrapped-phase band: unit phasors; exact 0.0 is the TC fill, mask it
                out[k] = np.where(a != 0, np.exp(1j * a.astype(np.float64)), 0)
                continue
            fq.seek(off)
            b = np.frombuffer(fq.read(nc * d.itemsize), dtype=d)[::decx]
            out[k] = a + 1j * b
    return out


def main() -> int:
    ldim, ltitle, rdim, rtitle, out = (Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3]),
                                       sys.argv[4], Path(sys.argv[5]))
    lsx, lsy, lx0, ly0 = i2m(ldim)
    rsx, rsy, rx0, ry0 = i2m(rdim)
    lib, _ = bands_of(ldim.with_suffix(".data"))
    LW, LL, _ = hdr_info(lib)
    # right-product pixel window covering the left product's geographic bbox
    c0 = int(np.floor((lx0 - rx0) / rsx))
    r0 = int(np.floor((ly0 - ry0) / rsy))
    ncR = int(np.ceil(LW * lsx / rsx))
    nrR = int(np.ceil(LL * lsy / rsy))  # both sy negative -> positive count

    panels = []
    for dim, title, (r, c, nr, nc), (sx, sy) in (
            (ldim, ltitle, (0, 0, LL, LW), (lsx, lsy)),
            (rdim, rtitle, (r0, c0, nrR, ncR), (rsx, rsy))):
        decx = max(1, int(np.ceil(nc / 1200.0)))
        decy = max(1, int(np.ceil(nr / 1200.0)))
        z = read_dec(dim, r, c, nr, nc, decy, decx)
        aspect = abs(sy * decy) / abs(sx * decx)
        panels.append((z, title, decy, decx, aspect))

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(16, 9), dpi=140)
    for ax, (z, title, decy, decx, aspect) in zip(axes, panels):
        good = np.isfinite(z.real) & (z != 0)
        ph = np.where(good, np.angle(z), np.nan)
        cmap = matplotlib.colormaps["hsv"].copy()
        cmap.set_bad("#222222")
        im = ax.imshow(np.ma.masked_invalid(ph), cmap=cmap, vmin=-np.pi, vmax=np.pi,
                       interpolation="nearest", aspect=aspect)
        ax.set_title(f"{title}\n(decimated y 1/{decy}, x 1/{decx}: view ALIASES dense fringes)",
                     fontsize=10)
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
