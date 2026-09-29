"""
Render wrapped-phase fringe maps as PNGs so two engines' interferograms can be compared visually.

Runs INSIDE the isce3 container (GDAL + matplotlib live there, and /work exposes both engines' output).

Reader functions are IMPORTED from diff_fringes rather than reimplemented: if the two disagreed about
how to read a product, the picture and the numbers would describe different data, which is worse than
having no picture.

Rendering choices that matter for honesty:
  * A CYCLIC colormap (twilight_shifted). Wrapped phase is periodic -- a linear colormap invents a
    discontinuity at +/-pi that looks like a fringe and hides the real ones.
  * Fixed range [-pi, pi], never autoscaled, so the two panels are directly comparable.
  * Invalid pixels are rendered TRANSPARENT, not as phase 0. Zero-filled nodata reads as a real dark
    fringe and has previously been mistaken for signal in this work.
  * Decimation is by STRIDED SUBSAMPLING, not averaging. Averaging wrapped phase across a 20x block
    is meaningless (it collapses toward zero wherever a fringe crosses the block).

    python render_fringes.py --snap <ifg.data> --isce3 <ifg.tif> --outdir <dir> [--max-px 1400]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from diff_fringes import align, read_envi_pair, read_gtiff  # noqa: E402  (shared readers, on purpose)


def phase_png(z: np.ndarray, out: Path, title: str, max_px: int) -> "tuple[int, int, float]":
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    step = max(1, int(np.ceil(max(z.shape) / max_px)))
    zz = z[::step, ::step]                      # strided, NOT averaged: see module docstring
    valid = np.isfinite(zz.real) & np.isfinite(zz.imag) & (zz != 0)
    ph = np.where(valid, np.angle(zz), np.nan)
    pct = 100.0 * valid.sum() / valid.size

    fig, ax = plt.subplots(figsize=(zz.shape[1] / 150.0 + 1.2, zz.shape[0] / 150.0 + 1.0), dpi=150)
    cmap = matplotlib.colormaps["twilight_shifted"].copy()
    cmap.set_bad(alpha=0.0)                     # invalid stays transparent, never phase 0
    im = ax.imshow(np.ma.masked_invalid(ph), cmap=cmap, vmin=-np.pi, vmax=np.pi,
                   interpolation="nearest")
    ax.set_title(f"{title}\n{zz.shape[1]}x{zz.shape[0]} shown (1/{step}), {pct:.1f}% valid",
                 fontsize=9)
    ax.set_xticks([]); ax.set_yticks([])
    cb = fig.colorbar(im, ax=ax, fraction=0.035, pad=0.02,
                      ticks=[-np.pi, 0, np.pi])
    cb.ax.set_yticklabels(["-pi", "0", "+pi"], fontsize=8)
    fig.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, transparent=True)
    plt.close(fig)
    print(f"RENDER: {out}  ({zz.shape[1]}x{zz.shape[0]}, 1/{step}, {pct:.1f}% valid)")
    return zz.shape[1], zz.shape[0], pct


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--snap", type=Path, required=True)
    ap.add_argument("--isce3", type=Path, required=True)
    ap.add_argument("--outdir", type=Path, required=True)
    ap.add_argument("--max-px", type=int, default=1400)
    a = ap.parse_args()

    zs, gs = read_envi_pair(a.snap)
    zi, gi = read_gtiff(a.isce3)
    # Crop to the common extent FIRST, so the two panels show the same ground. Rendering full
    # extents side by side would put different areas next to each other and invite false conclusions.
    A, B = align(zs, gs, zi, gi)

    phase_png(A, a.outdir / "snap_fringes.png", "SNAP (Microwave Toolbox)", a.max_px)
    phase_png(B, a.outdir / "isce3_fringes.png", "ISCE3 / COMPASS", a.max_px)

    good = (np.isfinite(A.real) & np.isfinite(A.imag) & (A != 0)
            & np.isfinite(B.real) & np.isfinite(B.imag) & (B != 0))
    if good.any():
        d = np.angle(A * np.conj(B))
        bias = float(np.angle(np.mean(np.exp(1j * d[good]))))
        # Show the residual with the constant offset removed: a constant is not a defect, and leaving
        # it in would colour the whole panel uniformly and mask the spatial structure that matters.
        resid = np.where(good, np.exp(1j * (d - bias)), np.nan + 0j)
        phase_png(resid, a.outdir / "residual_fringes.png",
                  f"Residual: SNAP - ISCE3 (constant {bias:+.3f} rad removed)", a.max_px)
    return 0


if __name__ == "__main__":
    sys.exit(main())
