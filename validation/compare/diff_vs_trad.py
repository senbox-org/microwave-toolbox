"""
Per-pixel wrapped-phase difference between a GSLC interferogram and the traditional (classical
chain) interferogram terrain-corrected onto the SAME lattice.

This is the decisive diagnostic for "why does the GSLC ifg look different": if the GSLC is correct
up to the known cross-acquisition annotation ramp, the difference map is a single global plane plus
noise. Any structure beyond a plane (per-burst sawtooth, terrain-correlated residual, range banding)
is a real, localizable defect and shows up here directly.

Method notes, each learned the hard way in this project:
  * Both products MUST share one lattice (same step, standard-grid-snapped) so alignment is an
    INTEGER pixel offset -- resampling either product to compare would inject exactly the
    interpolation error being measured. The script refuses fractional offsets > 0.02 px.
  * The plane is estimated from lag-1 phase GRADIENTS (angle of the mean lag product), never from a
    least-squares fit to wrapped phase: wrapped-phase LS is meaningless across fringes, gradients
    are wrap-immune. Same estimator family as InterferogramOp.subtractResidualRamp.
  * Averages of phase use complex sums (angle of mean phasor), never the mean of angles.
  * Per-ROW mean phase profile (azimuth direction) is emitted: the per-burst quadratic carrier
    defect of 2026-07-25 was found exactly this way, so keep the probe.
  * Products are hundreds of megapixels; everything streams in row blocks.

    python diff_vs_trad.py <gslc_ifg.dim> <trad_tc.dim> <out_dir> [label]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

from render_2x2 import hdr_info, iq, png


def i2m(dim: Path):
    m = re.search(r"IMAGE_TO_MODEL_TRANSFORM>([^<]+)", dim.read_text(errors="replace"))
    if not m:
        raise RuntimeError(f"{dim.name}: no IMAGE_TO_MODEL_TRANSFORM")
    v = [float(x) for x in re.split(r"[,\s]+", m.group(1).strip()) if x]
    # m00, m10, m01, m11, x0, y0
    return v[0], v[3], v[4], v[5]


class Band:
    def __init__(self, dim: Path):
        self.dim = dim
        data = dim.with_suffix(".data")
        try:
            ib, qb = iq(data)
        except RuntimeError:
            # terrain-corrected ifgs often carry only a wrapped Phase_* band (SNAP TC converts
            # complex sources); treat it as unit phasors, exact 0.0 being the TC fill
            ph = sorted(data.glob("Phase_*.hdr"))
            if not ph:
                raise
            ib, qb = ph[0], None
        self.W, self.L, self.dt = hdr_info(ib)
        self.fi = open(ib.with_suffix(".img"), "rb")
        self.fq = open(qb.with_suffix(".img"), "rb") if qb is not None else None

    def rows(self, r0: int, nr: int, c0: int, nc: int) -> np.ndarray:
        out = np.empty((nr, nc), dtype=np.complex128)
        for k in range(nr):
            off = ((r0 + k) * self.W + c0) * self.dt.itemsize
            self.fi.seek(off)
            re_ = np.frombuffer(self.fi.read(nc * self.dt.itemsize), dtype=self.dt)
            if self.fq is None:
                a = re_.astype(np.float64)
                out[k] = np.where(a != 0, np.exp(1j * a), 0)
                continue
            self.fq.seek(off)
            im_ = np.frombuffer(self.fq.read(nc * self.dt.itemsize), dtype=self.dt)
            out[k] = re_ + 1j * im_
        return out


def main() -> int:
    g_dim, t_dim, outd = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    label = sys.argv[4] if len(sys.argv) > 4 else g_dim.stem
    outd.mkdir(parents=True, exist_ok=True)

    gsx, gsy, gx0, gy0 = i2m(g_dim)
    tsx, tsy, tx0, ty0 = i2m(t_dim)
    if abs(gsx - tsx) > 1e-9 * abs(tsx) or abs(gsy - tsy) > 1e-9 * abs(tsy):
        raise SystemExit(f"step mismatch: gslc ({gsx},{gsy}) vs trad ({tsx},{tsy}) -- not one lattice")
    ox, oy = (gx0 - tx0) / tsx, (gy0 - ty0) / tsy
    fx, fy = ox - round(ox), oy - round(oy)
    print(f"lattice offset gslc->trad: ({ox:.6f}, {oy:.6f}) px, fractional ({fx:+.6f}, {fy:+.6f})")
    if max(abs(fx), abs(fy)) > 0.02:
        raise SystemExit("fractional lattice offset > 0.02 px -- products are NOT co-lattice; "
                         "rebuild the trad TC with alignToStandardGrid=true at the same spacing")
    ox, oy = int(round(ox)), int(round(oy))

    G, T = Band(g_dim), Band(t_dim)
    # overlap in GSLC pixel coords
    c0 = max(0, -ox); c1 = min(G.W, T.W - ox)
    r0 = max(0, -oy); r1 = min(G.L, T.L - oy)
    if c1 - c0 < 100 or r1 - r0 < 100:
        raise SystemExit(f"overlap too small: cols {c0}..{c1} rows {r0}..{r1}")
    W, L = c1 - c0, r1 - r0
    print(f"overlap {W} x {L} px (gslc window row {r0} col {c0}; trad offset +{oy} +{ox})")

    BLK = 256
    n = 0
    C0 = 0j          # sum exp(j d)
    Cx = 0j          # sum d[:,1:] conj-lag-1 in x
    Cy = 0j          # sum lag-1 in y (needs previous block's last row)
    row_phasor = np.zeros(L, dtype=np.complex128)
    row_count = np.zeros(L, dtype=np.int64)
    dec = max(1, int(np.ceil(max(W, L) / 1400.0)))
    over = np.full(((L + dec - 1) // dec, (W + dec - 1) // dec), np.nan + 0j, dtype=np.complex128)
    prev_last = None  # (d_row, good_row) of the final row of the previous block

    for b0 in range(0, L, BLK):
        nb = min(BLK, L - b0)
        zg = G.rows(r0 + b0, nb, c0, W)
        zt = T.rows(r0 + b0 + oy, nb, c0 + ox, W)
        good = (np.isfinite(zg.real) & np.isfinite(zt.real) & (zg != 0) & (zt != 0))
        d = np.where(good, zg * np.conj(zt), 0)
        m = np.abs(d)
        u = np.where(m > 0, d / np.where(m > 0, m, 1), 0)  # unit phasors, 0 where invalid
        n += int(good.sum())
        C0 += u.sum()
        Cx += (u[:, 1:] * np.conj(u[:, :-1])).sum()
        if prev_last is not None:
            pu, _ = prev_last
            Cy += (u[0] * np.conj(pu)).sum()
        if nb > 1:
            Cy += (u[1:] * np.conj(u[:-1])).sum()
        prev_last = (u[-1].copy(), None)
        row_phasor[b0:b0 + nb] += u.sum(axis=1)
        row_count[b0:b0 + nb] += good.sum(axis=1)
        for k in range((b0 % dec == 0) and 0 or (dec - b0 % dec), nb, dec):
            over[(b0 + k) // dec] = np.where(good[k, ::dec], u[k, ::dec], np.nan + 0j)

    if n < 10000:
        raise SystemExit(f"only {n} valid overlap pixels")
    gxr, gyr = float(np.angle(Cx)), float(np.angle(Cy))
    conc = abs(C0) / n
    print(f"valid px {n}  |  raw concentration |mean e^jd| = {conc:.4f}")
    print(f"plane (lag-1 gradient): d(phi)/dx {gxr:+.6f} rad/px, d(phi)/dy {gyr:+.6f} rad/px "
          f"-> one fringe per ({2*np.pi/max(abs(gxr),1e-12):.0f}, {2*np.pi/max(abs(gyr),1e-12):.0f}) px")

    # pass 2: residual after plane removal (+ its own bias), streamed the same way
    C0r = 0j
    S1 = 0.0
    S2 = 0.0
    over_res = np.full_like(over, np.nan + 0j)
    for b0 in range(0, L, BLK):
        nb = min(BLK, L - b0)
        zg = G.rows(r0 + b0, nb, c0, W)
        zt = T.rows(r0 + b0 + oy, nb, c0 + ox, W)
        good = (np.isfinite(zg.real) & np.isfinite(zt.real) & (zg != 0) & (zt != 0))
        d = zg * np.conj(zt)
        xs = np.arange(W, dtype=np.float64)[None, :]
        ys = (b0 + np.arange(nb, dtype=np.float64))[:, None]
        rot = np.exp(-1j * (gxr * xs + gyr * ys))
        m = np.abs(d)
        u = np.where(good & (m > 0), d * rot / np.where(m > 0, m, 1), 0)
        C0r += u.sum()
        res = np.where(u != 0, np.angle(u), np.nan)
        S1 += np.nansum(res)
        S2 += np.nansum(res * res)
        for k in range((b0 % dec == 0) and 0 or (dec - b0 % dec), nb, dec):
            over_res[(b0 + k) // dec] = np.where(good[k, ::dec], u[k, ::dec], np.nan + 0j)
    concr = abs(C0r) / n
    mu = S1 / n
    rms = float(np.sqrt(max(S2 / n - mu * mu, 0.0)))
    print(f"after plane removal: concentration {concr:.4f}, RMS about mean {rms:.4f} rad "
          f"(mean {mu:+.4f})")

    png(np.where(np.isnan(over.real), np.nan + 0j, over), outd / f"{label}_diff_raw.png",
        f"{label}: GSLC minus TRAD (wrapped)", f"decimated 1/{dec}; raw difference, plane NOT removed")
    png(np.where(np.isnan(over_res.real), np.nan + 0j, over_res), outd / f"{label}_diff_deplaned.png",
        f"{label}: GSLC minus TRAD, plane removed",
        f"decimated 1/{dec}; plane ({gxr:+.4f},{gyr:+.4f}) rad/px removed, RMS {rms:.3f} rad")

    # native-resolution centre crop of the raw difference
    cc, cr = c0 + W // 2 - 600, r0 + L // 2 - 600
    zg = G.rows(cr, 1200, cc, 1200)
    zt = T.rows(cr + oy, 1200, cc + ox, 1200)
    good = (np.isfinite(zg.real) & np.isfinite(zt.real) & (zg != 0) & (zt != 0))
    png(np.where(good, zg * np.conj(zt), np.nan + 0j), outd / f"{label}_diff_crop.png",
        f"{label}: GSLC minus TRAD, native crop", "1200x1200 centre of overlap, no decimation")

    # per-row (azimuth) mean-phase profile of the deplaned residual: per-burst structure detector
    prof = np.where(row_count > 200, np.angle(row_phasor), np.nan)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(10, 4), dpi=130)
    ax.plot(prof, lw=0.6)
    ax.set_xlabel("map row (north->south)"); ax.set_ylabel("mean diff phase (rad)")
    ax.set_title(f"{label}: per-row mean of GSLC-minus-TRAD (raw, incl. plane)")
    ax.grid(alpha=0.3)
    fig.tight_layout(); fig.savefig(outd / f"{label}_row_profile.png"); plt.close(fig)
    np.savetxt(outd / f"{label}_row_profile.txt",
               np.column_stack([np.arange(L), prof, row_count]),
               fmt="%.0f %.6f %d", header="row mean_phase_rad n_valid")
    print(f"wrote {label}_diff_raw/deplaned/crop.png, {label}_row_profile.png/.txt in {outd}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
