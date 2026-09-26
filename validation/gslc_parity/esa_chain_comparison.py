"""ESA deliverable: GSLC vs classical chain - before/after the corrections, interferogram
difference, coherence and coherence difference.

Everything is computed on the CLASSICAL burst-geometry radar cells (ML_AZ x ML_RG, ~100 m) using
the terrain-aware bin link of run_r5b: each GSLC map pixel is placed in the radar cell it was
actually read from, via the diag_rangeIndex / diag_azimuthIndex bands the GSLC records. Neither
product is resampled and no geolocation is involved, so the difference maps below are not
contaminated by an interpolator.

Caveats that belong on any slide made from these figures:
  * The classical interferogram used is the UN-debursted, UN-filtered burst-geometry product, so
    the comparison is like-for-like against the unfiltered single-look GSLC.
  * Coherence is estimated by each chain in its own geometry (classical in radar, GSLC in map),
    both with cohWinSizeMeters=100, then averaged into the shared ~100 m cells. It is a fair
    comparison of the two chains' delivered coherence, not of a single estimator.
  * A cell is shown only where BOTH chains have data and the GSLC fills at least MIN_FILL of the
    median map-pixel count.

  python esa_chain_comparison.py [out_dir]
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                      # noqa: E402
from matplotlib.colors import TwoSlopeNorm           # noqa: E402

import gslc_equivalence as ge                        # noqa: E402
import run_r5b as r5b                                # noqa: E402

D = Path(r"E:\Output\parity\ven")
CLASSICAL = D / "ven_trad_ifg_burst.dim"             # burst geometry, unfiltered
GSLC_DIAG = D / "ven_etadD_gslc.dim"                 # carries diag_rangeIndex / diag_azimuthIndex
GSLC_BEFORE = D / "ven_etadD_ifg.dim"                # legacy carrier-difference sign (+1)
GSLC_AFTER = D / "ven_etadD_ifg_signfix.dim"         # corrected sign (-1), the shipping default

PAIR = "Sentinel-1A x S1C, Venezuela IW3, bursts 4-6, ETAD applied"


# ----------------------------------------------------------------- cell binning
def _coh_band(dim: Path) -> np.ndarray:
    hits = sorted(dim.with_suffix(".data").glob("coh_*.img"))
    if not hits:
        raise FileNotFoundError(f"no coh_*.img band in {dim}")
    return r5b._band(str(dim), hits[0].stem)


def bin_real(v: np.ndarray, az: np.ndarray, rg: np.ndarray, n_rows: int, n_cols: int,
             ml_az: int, ml_rg: int):
    """Sum a REAL map-domain field into radar cells by source index (companion to bin_to_cells)."""
    ny, nx = n_rows // ml_az, n_cols // ml_rg
    ok = (np.isfinite(az) & np.isfinite(rg) & np.isfinite(v) & (az >= 0) & (rg >= 0)
          & (az < ny * ml_az) & (rg < nx * ml_rg) & (v > 0))
    if not ok.any():
        return np.zeros((ny, nx)), np.zeros((ny, nx), np.int64)
    cell = (np.floor(az[ok] / ml_az).astype(np.int64) * nx
            + np.floor(rg[ok] / ml_rg).astype(np.int64))
    s = np.bincount(cell, weights=v[ok], minlength=ny * nx)
    c = np.bincount(cell, minlength=ny * nx)
    return s.reshape(ny, nx), c.reshape(ny, nx)


def real_block_mean(v: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    h, w = (v.shape[0] // ml_az) * ml_az, (v.shape[1] // ml_rg) * ml_rg
    vv = v[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg).astype(np.float64)
    bad = (~np.isfinite(vv) | (vv <= 0)).any(axis=(1, 3))
    return np.where(bad, np.nan, np.nan_to_num(vv).mean(axis=(1, 3)))


def gslc_cells(gslc_ifg: Path, th: int, tw: int):
    """(complex mean, coherence mean, count) of a GSLC product on the classical radar cells."""
    gi, gq, gw, gh = ge.load_complex_ifg(str(gslc_ifg))
    rgi = r5b._band(str(GSLC_DIAG), "diag_rangeIndex")
    azi = r5b._band(str(GSLC_DIAG), "diag_azimuthIndex")
    coh = _coh_band(gslc_ifg)
    ny, nx = th // r5b.ML_AZ, tw // r5b.ML_RG
    Zs, Zc = np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64)
    Cs, Cc = np.zeros((ny, nx)), np.zeros((ny, nx), np.int64)
    for r0 in range(0, gh, 512):
        sl = slice(r0, r0 + 512)
        z = np.asarray(gi[sl], np.float64) + 1j * np.asarray(gq[sl], np.float64)
        a = np.asarray(azi[sl], np.float64).ravel()
        r = np.asarray(rgi[sl], np.float64).ravel()
        s_, c_ = r5b.bin_to_cells(z.ravel(), a, r, th, tw, r5b.ML_AZ, r5b.ML_RG)
        Zs += s_
        Zc += c_
        s2, c2 = bin_real(np.asarray(coh[sl], np.float64).ravel(), a, r, th, tw, r5b.ML_AZ, r5b.ML_RG)
        Cs += s2
        Cc += c2
    return Zs, Zc, np.where(Cc > 0, Cs / np.maximum(Cc, 1), np.nan)


# ----------------------------------------------------------------- rendering
def _show(ax, data, title, cmap, vmin=None, vmax=None, norm=None, cbar_label=None):
    im = ax.imshow(data, cmap=cmap, vmin=vmin, vmax=vmax, norm=norm,
                   interpolation="nearest", aspect="auto", origin="upper")
    ax.set_title(title, fontsize=10)
    ax.set_xticks([])
    ax.set_yticks([])
    cb = plt.colorbar(im, ax=ax, fraction=0.046, pad=0.02)
    if cbar_label:
        cb.set_label(cbar_label, fontsize=8)
    cb.ax.tick_params(labelsize=7)
    return im


def _mask(a, fill):
    return np.where(fill, a, np.nan)


def circ_stats(d: np.ndarray):
    """(concentration, rms rad) of a wrapped difference field."""
    v = d[np.isfinite(d)]
    if v.size == 0:
        return float("nan"), float("nan")
    z = np.exp(1j * v)
    conc = float(np.abs(z.mean()))
    rms = float(np.sqrt(np.mean(np.angle(z * np.conj(z.mean() / max(abs(z.mean()), 1e-12))) ** 2)))
    return conc, rms


def main(out_dir: str = r"E:\Output\parity\figures\esa_comparison") -> int:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)

    # classical side
    ti, tq, tw, th = ge.load_complex_ifg(str(CLASSICAL))
    T = r5b.radar_block_mean(np.asarray(ti[:], np.float64) + 1j * np.asarray(tq[:], np.float64),
                             r5b.ML_AZ, r5b.ML_RG)
    T_coh = real_block_mean(np.asarray(_coh_band(CLASSICAL)), r5b.ML_AZ, r5b.ML_RG)

    # GSLC sides
    Zs_b, Zc_b, coh_b = gslc_cells(GSLC_BEFORE, th, tw)
    Zs_a, Zc_a, coh_a = gslc_cells(GSLC_AFTER, th, tw)
    Gb, Tm, fill_b = r5b.paired_cells(Zs_b, Zc_b, T)
    Ga, _Tm, fill_a = r5b.paired_cells(Zs_a, Zc_a, T)
    fill = fill_a & fill_b
    print(f"# shared cells {int(fill.sum())} of {fill.size} ({fill.mean():.1%})")

    ph_t = _mask(np.angle(Tm), fill)
    ph_b = _mask(np.angle(Gb), fill)
    ph_a = _mask(np.angle(Ga), fill)
    d_b = _mask(np.angle(Gb * np.conj(Tm)), fill)
    d_a = _mask(np.angle(Ga * np.conj(Tm)), fill)
    cb_, rb_ = circ_stats(d_b)
    ca_, ra_ = circ_stats(d_a)
    # the campaign's R5b gate removes ONE fitted plane first; report both so the gap between them
    # - which is exactly the residual scene-scale surface seen in the difference panel - is visible
    mb, ma = {}, {}
    import contextlib, io
    with contextlib.redirect_stdout(io.StringIO()):
        ge.compute_gates(Gb, Tm, None, None, metrics_out=mb)
        ge.compute_gates(Ga, Tm, None, None, metrics_out=ma)
    cb_dt = float(mb.get("phase-residual-conc", float("nan")))
    ca_dt = float(ma.get("phase-residual-conc", float("nan")))
    print(f"# phase difference vs classical: BEFORE conc {cb_:.3f} (detrended {cb_dt:.3f})  |  "
          f"AFTER conc {ca_:.3f} (detrended {ca_dt:.3f})")

    cyc = "twilight_shifted"
    # ---------------- Figure 1: before / after the corrections
    fig, axes = plt.subplots(2, 3, figsize=(15, 8), constrained_layout=True)
    _show(axes[0, 0], ph_t, "Classical chain (reference)", cyc, -np.pi, np.pi, cbar_label="rad")
    _show(axes[0, 1], ph_b, "GSLC BEFORE corrections", cyc, -np.pi, np.pi, cbar_label="rad")
    _show(axes[0, 2], ph_a, "GSLC AFTER corrections", cyc, -np.pi, np.pi, cbar_label="rad")
    axes[1, 0].axis("off")
    axes[1, 0].text(0.0, 0.5,
                    f"{PAIR}\n\nCells: {int(fill.sum())} shared "
                    f"({r5b.ML_AZ}x{r5b.ML_RG} radar px, ~100 m)\n\n"
                    f"Phase agreement with the classical chain\n"
                    f"            raw   one plane removed\n"
                    f"  BEFORE   {cb_:.3f}       {cb_dt:.3f}\n"
                    f"  AFTER    {ca_:.3f}       {ca_dt:.3f}\n\n"
                    "Concentration is |mean phasor| of the\n"
                    "difference: 1.0 is perfect agreement.\n\n"
                    "The raw-to-detrended gap is the residual\n"
                    "scene-scale surface still separating the\n"
                    "two chains.",
                    fontsize=9, va="center", family="monospace")
    _show(axes[1, 1], d_b, f"BEFORE - classical   (conc {cb_:.3f})", cyc, -np.pi, np.pi, cbar_label="rad")
    _show(axes[1, 2], d_a, f"AFTER - classical   (conc {ca_:.3f})", cyc, -np.pi, np.pi, cbar_label="rad")
    fig.suptitle("GSLC InSAR - effect of the corrections, against the classical chain", fontsize=13)
    fig.savefig(out / "01_before_after_corrections.png", dpi=160)
    plt.close(fig)

    # ---------------- Figure 2: interferogram difference, classical vs GSLC
    fig, axes = plt.subplots(1, 3, figsize=(15, 5), constrained_layout=True)
    _show(axes[0], ph_t, "Classical interferogram", cyc, -np.pi, np.pi, cbar_label="rad")
    _show(axes[1], ph_a, "GSLC interferogram", cyc, -np.pi, np.pi, cbar_label="rad")
    _show(axes[2], d_a, f"Difference  GSLC - classical   (conc {ca_:.3f}, "
                        f"{ca_dt:.3f} after one plane)", cyc, -np.pi, np.pi, cbar_label="rad")
    fig.suptitle(f"Interferometric phase - classical vs GSLC, and their difference\n{PAIR}",
                 fontsize=12)
    fig.savefig(out / "02_interferogram_difference.png", dpi=160)
    plt.close(fig)

    # ---------------- Figure 3: coherence and coherence difference
    ct = _mask(T_coh, fill)
    cg = _mask(coh_a, fill)
    dc = cg - ct
    v = dc[np.isfinite(dc)]
    lim = float(np.nanpercentile(np.abs(v), 98)) if v.size else 0.5
    fig, axes = plt.subplots(1, 4, figsize=(19, 4.6), constrained_layout=True)
    _show(axes[0], ct, f"Classical coherence  (mean {np.nanmean(ct):.3f})", "viridis", 0, 1)
    _show(axes[1], cg, f"GSLC coherence  (mean {np.nanmean(cg):.3f})", "viridis", 0, 1)
    _show(axes[2], dc, f"Difference  GSLC - classical  (mean {np.nanmean(dc):+.3f})",
          "RdBu_r", norm=TwoSlopeNorm(vcenter=0.0, vmin=-lim, vmax=lim))
    axes[3].hist(v, bins=80, color="#335E6E")
    axes[3].axvline(0, color="k", lw=0.8)
    axes[3].axvline(float(np.median(v)), color="#D13F42", lw=1.4,
                    label=f"median {np.median(v):+.3f}")
    axes[3].set_title("Coherence difference", fontsize=10)
    axes[3].set_xlabel("GSLC - classical")
    axes[3].legend(fontsize=8)
    axes[3].tick_params(labelsize=8)
    fig.suptitle(f"Interferometric coherence - classical vs GSLC, and their difference\n{PAIR}",
                 fontsize=12)
    fig.savefig(out / "03_coherence_difference.png", dpi=160)
    plt.close(fig)

    # ---------------- individual full-size panels, one per slide
    panels = out / "panels"
    panels.mkdir(exist_ok=True)

    def panel(name, data, title, cmap, vmin=None, vmax=None, norm=None, label=None):
        h, w = data.shape
        fig, ax = plt.subplots(figsize=(12, max(3.2, 12.0 * h / w + 0.9)), constrained_layout=True)
        _show(ax, data, title, cmap, vmin, vmax, norm, cbar_label=label)
        fig.savefig(panels / name, dpi=170)
        plt.close(fig)
        print("  panel", name)

    panel("ifg_classical.png", ph_t, "Classical chain - interferometric phase", cyc, -np.pi, np.pi, label="rad")
    panel("ifg_gslc.png", ph_a, "GSLC chain - interferometric phase", cyc, -np.pi, np.pi, label="rad")
    panel("ifg_difference.png", d_a,
          f"GSLC - classical   (concentration {ca_:.3f} raw, {ca_dt:.3f} after one plane)",
          cyc, -np.pi, np.pi, label="rad")
    panel("ifg_gslc_before.png", ph_b, "GSLC BEFORE the corrections - interferometric phase",
          cyc, -np.pi, np.pi, label="rad")
    panel("ifg_difference_before.png", d_b,
          f"GSLC BEFORE - classical   (concentration {cb_:.3f})", cyc, -np.pi, np.pi, label="rad")
    panel("coh_classical.png", ct, f"Classical chain - coherence   (mean {np.nanmean(ct):.3f})",
          "viridis", 0, 1, label="coherence")
    panel("coh_gslc.png", cg, f"GSLC chain - coherence   (mean {np.nanmean(cg):.3f})",
          "viridis", 0, 1, label="coherence")
    panel("coh_difference.png", dc,
          f"Coherence difference  GSLC - classical   (mean {np.nanmean(dc):+.3f}, "
          f"ratio {np.nanmean(cg) / np.nanmean(ct):.3f})",
          "RdBu_r", norm=TwoSlopeNorm(vcenter=0.0, vmin=-lim, vmax=lim), label="Dcoherence")

    fig, ax = plt.subplots(figsize=(9, 4.6), constrained_layout=True)
    ax.hist(v, bins=90, color="#335E6E")
    ax.axvline(0, color="k", lw=0.9)
    ax.axvline(float(np.median(v)), color="#D13F42", lw=1.6, label=f"median {np.median(v):+.4f}")
    ax.set_xlabel("coherence difference,  GSLC - classical")
    ax.set_ylabel("cells")
    ax.set_title("Coherence difference over the shared cells", fontsize=11)
    ax.legend()
    fig.savefig(panels / "coh_difference_hist.png", dpi=170)
    plt.close(fig)

    summary = (
        f"pair                : {PAIR}\n"
        f"cells               : {int(fill.sum())} shared, {r5b.ML_AZ}x{r5b.ML_RG} radar px (~100 m)\n"
        f"phase conc BEFORE   : {cb_:.4f} raw, {cb_dt:.4f} after removing one plane\n"
        f"phase conc AFTER    : {ca_:.4f} raw, {ca_dt:.4f} after removing one plane\n"
        f"coherence classical : mean {np.nanmean(ct):.4f}  median {np.nanmedian(ct):.4f}\n"
        f"coherence GSLC      : mean {np.nanmean(cg):.4f}  median {np.nanmedian(cg):.4f}\n"
        f"coherence difference: mean {np.nanmean(dc):+.4f}  median {np.nanmedian(dc):+.4f}  "
        f"ratio {np.nanmean(cg) / np.nanmean(ct):.4f}\n")
    (out / "summary.txt").write_text(summary)
    print(summary)
    print("wrote", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(*sys.argv[1:]))
