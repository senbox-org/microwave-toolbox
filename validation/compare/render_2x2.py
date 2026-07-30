"""
Colourised PNGs for every arm of the 2x2, plus the double differences that isolate each variable.

TWO VIEWS PER INTERFEROGRAM, deliberately:
  * an OVERVIEW, decimated, labelled with its own sampling so it cannot be mistaken for the truth.
    Measured on this scene the fringe period is ~23 m, i.e. ~1.7 px per fringe on a 14 m grid --
    BELOW Nyquist. Any decimated view therefore aliases, and reading fringe density off one is
    unsafe. An earlier reading in this work nearly condemned a good interferogram for that reason.
  * a NATIVE-RESOLUTION CROP at the scene centre, where fringes are actually resolvable.

Colour map is CYCLIC (hsv) so wrapped phase has no false discontinuity at +/-pi, which a linear map
invents and which looks exactly like a fringe. Fixed [-pi, pi] range, never autoscaled, so panels are
comparable to each other. Invalid pixels are transparent, never phase 0 -- a zero-filled nodata reads
as a genuine dark fringe.

    python render_2x2.py <exp_dir> <out_dir>
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

CYCLIC = "hsv"


def hdr_info(h: Path):
    t = h.read_text(errors="replace")

    def g(k):
        m = re.search(rf"^{k}\s*=\s*(-?\d+)", t, re.I | re.M)
        if not m:
            raise RuntimeError(f"{h.name}: missing {k}")
        return int(m.group(1))

    W, L, dt, bo = g("samples"), g("lines"), g("data type"), g("byte order")
    npdt = {4: np.float32, 5: np.float64}.get(dt)
    if npdt is None:
        raise RuntimeError(f"{h.name}: unsupported ENVI type {dt}")
    return W, L, np.dtype(npdt).newbyteorder(">" if bo == 1 else "<")


def read_window(img: Path, W: int, d, r0: int, nr: int, c0: int, nc: int) -> np.ndarray:
    """Row-by-row windowed read: these products are hundreds of megapixels and loading one whole
    band as float64 has been OOM-killed (SIGKILL/137) on this machine."""
    a = np.empty((nr, nc), dtype=np.float64)
    with open(img, "rb") as f:
        for k in range(nr):
            f.seek(((r0 + k) * W + c0) * d.itemsize)
            a[k] = np.frombuffer(f.read(nc * d.itemsize), dtype=d)
    return a


def read_strided(img: Path, W: int, L: int, d, step: int) -> np.ndarray:
    rows = list(range(0, L, step))
    a = np.empty((len(rows), (W + step - 1) // step), dtype=np.float64)
    with open(img, "rb") as f:
        for k, r in enumerate(rows):
            f.seek(r * W * d.itemsize)
            a[k] = np.frombuffer(f.read(W * d.itemsize), dtype=d)[::step]
    return a


def iq(data_dir: Path):
    hs = sorted(data_dir.glob("*.hdr"))
    ib = next((h for h in hs if re.match(r"^i_", h.stem)), None)
    qb = next((h for h in hs if re.match(r"^q_", h.stem)), None)
    if not ib or not qb:
        raise RuntimeError(f"no i_/q_ pair in {data_dir}")
    return ib, qb


def png(z: np.ndarray, out: Path, title: str, sub: str) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    good = np.isfinite(z.real) & np.isfinite(z.imag) & (z != 0)
    ph = np.where(good, np.angle(z), np.nan)
    h, w = ph.shape
    fig, ax = plt.subplots(figsize=(min(14, w / 110 + 1.5), min(16, h / 110 + 1.5)), dpi=140)
    cmap = matplotlib.colormaps[CYCLIC].copy()
    cmap.set_bad(alpha=0.0)
    im = ax.imshow(np.ma.masked_invalid(ph), cmap=cmap, vmin=-np.pi, vmax=np.pi,
                   interpolation="nearest", aspect="auto")
    pct = 100.0 * good.sum() / good.size
    ax.set_title(f"{title}\n{sub}  |  {w}x{h}, {pct:.1f}% valid", fontsize=9)
    ax.set_xticks([]); ax.set_yticks([])
    cb = fig.colorbar(im, ax=ax, fraction=0.03, pad=0.02, ticks=[-np.pi, 0, np.pi])
    cb.ax.set_yticklabels(["-pi", "0", "+pi"], fontsize=8)
    fig.tight_layout()
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, transparent=True)
    plt.close(fig)
    print(f"  PNG {out.name}  ({w}x{h}, {pct:.1f}% valid)")


def fringe_stats(z: np.ndarray, dx_m: float) -> str:
    good = np.isfinite(z.real) & (z != 0)
    if good.sum() < 1000:
        return "insufficient valid pixels"
    ph = np.where(good, np.angle(z), np.nan)
    d = np.angle(np.exp(1j * (ph[:, 1:] - ph[:, :-1])))
    m = float(np.nanmedian(np.abs(d)))
    per = 2 * np.pi / max(m, 1e-9)
    # pi/2 (~1.571) is the mean |dphi| of uniform noise; well below it implies real structure
    return (f"median |dphi/dx| {m:.4f} rad/px -> {per:.1f} px/fringe = {per*dx_m:.1f} m "
            f"(noise floor would be ~1.571 rad/px)")


def load(dim: Path, crop: int = 0, max_px: int = 1500):
    data = dim.with_suffix(".data")
    ib, qb = iq(data)
    W, L, d = hdr_info(ib)
    dx = 1.0
    m = re.search(r"IMAGE_TO_MODEL_TRANSFORM>([^<]+)", dim.read_text(errors="replace"))
    if m:
        v = [float(x) for x in re.split(r"[,\s]+", m.group(1).strip()) if x]
        dx = abs(v[0])
    if crop:
        nr, nc = min(crop, L), min(crop, W)
        r0, c0 = max(0, L // 2 - nr // 2), max(0, W // 2 - nc // 2)
        z = (read_window(ib.with_suffix(".img"), W, d, r0, nr, c0, nc)
             + 1j * read_window(qb.with_suffix(".img"), W, d, r0, nr, c0, nc))
        return z, dx, 1, (W, L)
    step = max(1, int(np.ceil(max(W, L) / float(max_px))))
    z = (read_strided(ib.with_suffix(".img"), W, L, d, step)
         + 1j * read_strided(qb.with_suffix(".img"), W, L, d, step))
    return z, dx, step, (W, L)


ARMS = {
    "A_sq_noetad":  "A  square grid, no ETAD",
    "B_nat_noetad": "B  native anisotropic, no ETAD",
    "C_sq_etad":    "C  square grid, ETAD",
    "D_nat_etad":   "D  native anisotropic, ETAD",
    "E_nat_noetad_ramp": "E  native anisotropic, no ETAD, residual ramp removed",
    "F_trad_lattice": "F  TRAD_TC lattice, ETAD, ramp removed",
    "G_trad_lattice_noetad": "G  TRAD_TC lattice, no ETAD, ramp removed",
}
# Each pair changes exactly ONE variable, so the double difference is attributable.
DIFFS = [("D_nat_etad", "B_nat_noetad", "ETAD effect at NATIVE sampling"),
         ("C_sq_etad", "A_sq_noetad", "ETAD effect at SQUARE sampling"),
         ("B_nat_noetad", "A_sq_noetad", "GRID effect with no ETAD"),
         # The decisive one for the original question: how much of the GSLC excess fringing is the
         # known deramp-mismatch residual ramp rather than deformation.
         ("B_nat_noetad", "E_nat_noetad_ramp", "RESIDUAL RAMP removed by subtractResidualRamp"),
         ("F_trad_lattice", "G_trad_lattice_noetad", "ETAD effect on the TRAD_TC lattice")]


def main() -> int:
    exp, outd = Path(sys.argv[1]), Path(sys.argv[2])
    got = {}
    for arm, label in ARMS.items():
        dim = exp / f"{arm}_ifg.dim"
        if not dim.exists():
            print(f"MISSING {arm} ({dim.name}) -- arm did not complete")
            continue
        try:
            zo, dx, step, (W, L) = load(dim, crop=0)
            png(zo, outd / f"{arm}_overview.png", label,
                f"OVERVIEW decimated 1/{step} at {dx:.2f} m -> {dx*step:.0f} m/px: ALIASES, "
                f"do not read fringe density here")
            zc, dx, _, _ = load(dim, crop=1000)
            print(f"  {arm}: {fringe_stats(zc, dx)}")
            png(zc, outd / f"{arm}_crop.png", label,
                f"NATIVE 1000x1000 crop, {dx:.2f} m/px east")
            got[arm] = dim
        except Exception as e:
            print(f"ERROR {arm}: {e}")

    for a, b, why in DIFFS:
        if a not in got or b not in got:
            print(f"SKIP diff {a} - {b} ({why}): an arm is missing")
            continue
        try:
            za, dxa, _, wla = load(got[a], crop=1000)
            zb, dxb, _, wlb = load(got[b], crop=1000)
            if za.shape != zb.shape or abs(dxa - dxb) > 1e-9 * abs(dxb) or wla != wlb:
                # A 1000x1000 centre crop of two DIFFERENT grids is different ground even though the
                # crop shapes match -- compare steps and full sizes, not crop shapes (learned when a
                # B-vs-A "diff" rendered as pure noise: it was 2.35 m pixels against 14 m pixels).
                print(f"SKIP diff {a} - {b} ({why}): steps {dxa} vs {dxb}, sizes {wla} vs {wlb} -- "
                      f"not one lattice, a centre crop is not the same ground.")
                continue
            good = (np.isfinite(za.real) & (za != 0) & np.isfinite(zb.real) & (zb != 0))
            d = np.angle(za * np.conj(zb))
            bias = float(np.angle(np.mean(np.exp(1j * d[good]))))
            res = np.angle(np.exp(1j * (d - bias)))
            rms = float(np.sqrt(np.mean(res[good] ** 2)))
            print(f"  DIFF {a} - {b}: constant {bias:+.4f} rad removed, RMS residual {rms:.4f} rad "
                  f"({why})")
            png(np.where(good, np.exp(1j * res), np.nan + 0j),
                outd / f"diff_{a}_minus_{b}.png", why,
                f"double difference, constant {bias:+.3f} rad removed, RMS {rms:.3f} rad")
        except Exception as e:
            print(f"ERROR diff {a}-{b}: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
