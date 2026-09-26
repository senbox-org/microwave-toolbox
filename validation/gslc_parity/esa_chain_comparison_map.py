"""ESA deliverable, MAP-domain variant: classical vs GSLC interferogram and coherence, plus both
differences, for any site whose classical control is terrain-corrected.

Companion to esa_chain_comparison.py, which uses the burst-geometry bin link and therefore only
applies to TOPS sources whose GSLC carries diag_rangeIndex / diag_azimuthIndex. Stripmap sites
(Napa, Etna, Bam) have neither bursts nor diag bands, so the comparison is made on the CLASSICAL
product's own lat/lon grid: the classical product is never resampled, the GSLC is bilinearly
sampled onto it (real and imaginary parts independently, never wrapped phase).

Caveats to carry onto any slide:
  * The GSLC side IS resampled here, unlike the Venezuela figures. Bilinear sampling attenuates
    dense fringes, so it flatters whichever arm has the smoother fringe field.
  * Where the only terrain-corrected classical available is Goldstein-FILTERED, the comparison is
    floor-limited: a filtered reference against an unfiltered single-look GSLC cannot reach
    concentration 1 even if the two chains agree perfectly. Such runs are marked in the summary.

  python esa_chain_comparison_map.py --classical <tc.dim> --gslc <ifg.dim> [--gslc-before <ifg.dim>]
                                     --label "<site>" --out <dir> [--decim 4]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt                       # noqa: E402
from matplotlib.colors import TwoSlopeNorm            # noqa: E402

import chain_compare as cc                            # noqa: E402

CYC = "twilight_shifted"


def sample_real(dim_path: str, band_pat: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Bilinear sample of a REAL band at lat/lon (companion to chain_compare.sample_gslc)."""
    arr, w, h = cc._memmap(cc._band(dim_path, band_pat))
    dx, dy, lon0, lat0 = cc._geo(dim_path)
    out = np.full(lats.shape, np.nan)
    for y0 in range(0, lats.shape[0], cc.CHUNK):
        y1 = min(y0 + cc.CHUNK, lats.shape[0])
        rows = (lat0 - lats[y0:y1]) / dy - 0.5
        cols = (lons[y0:y1] - lon0) / dx - 0.5
        r0 = np.floor(rows).astype(np.int64)
        c0 = np.floor(cols).astype(np.int64)
        inside = (r0 >= 0) & (c0 >= 0) & (r0 + 1 < h) & (c0 + 1 < w)
        if not inside.any():
            continue
        rlo = int(max(0, r0[inside].min()))
        rhi = int(min(h - 1, r0[inside].max() + 1))
        slab = arr[rlo:rhi + 1].astype(np.float64)
        rr = np.clip(r0 - rlo, 0, slab.shape[0] - 2)
        rc = np.clip(c0, 0, w - 2)
        fr, fc = rows - r0, cols - c0
        p00, p01 = slab[rr, rc], slab[rr, rc + 1]
        p10, p11 = slab[rr + 1, rc], slab[rr + 1, rc + 1]
        good = inside & (p00 > 0) & (p01 > 0) & (p10 > 0) & (p11 > 0)
        v = ((1 - fr) * ((1 - fc) * p00 + fc * p01) + fr * ((1 - fc) * p10 + fc * p11))
        out[y0:y1] = np.where(good, v, np.nan)
        del slab
    return out


def _show(ax, data, title, cmap, vmin=None, vmax=None, norm=None, label=None):
    im = ax.imshow(data, cmap=cmap, vmin=vmin, vmax=vmax, norm=norm,
                   interpolation="nearest", aspect="equal", origin="upper")
    ax.set_title(title, fontsize=10)
    ax.set_xticks([]); ax.set_yticks([])
    cb = plt.colorbar(im, ax=ax, fraction=0.046, pad=0.02)
    if label:
        cb.set_label(label, fontsize=8)
    cb.ax.tick_params(labelsize=7)


def is_filtered(dim_path: str) -> bool:
    """Goldstein-filtered? From the product's own processing graph when it records one, else from the
    name ('_flt' / SNAP's default '_Flt', case-insensitive)."""
    p = Path(dim_path)
    try:
        if "GoldsteinPhaseFiltering" in p.read_text(encoding="utf-8", errors="replace"):
            return True
    except OSError:
        pass
    return "_flt" in p.stem.lower()


def conc(d: np.ndarray) -> float:
    v = d[np.isfinite(d)]
    return float(np.abs(np.exp(1j * v).mean())) if v.size else float("nan")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--classical", required=True)
    ap.add_argument("--gslc", required=True)
    ap.add_argument("--gslc-before", dest="before")
    ap.add_argument("--label", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--decim", type=int, default=4)
    a = ap.parse_args(argv)

    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    panels = out / "panels"; panels.mkdir(exist_ok=True)
    # floor-limited whenever the filtering is MISMATCHED, in either direction. Case-insensitive: SNAP's own
    # GoldsteinFilterOp suffix is "_Flt", so a default-named product was never flagged by "_flt".
    cls_flt, gslc_flt = is_filtered(a.classical), is_filtered(a.gslc)
    filtered = cls_flt != gslc_flt
    flt_note = ("" if not filtered else
                f"   [MISMATCHED filtering: classical {'filtered' if cls_flt else 'unfiltered'}, GSLC "
                f"{'filtered' if gslc_flt else 'unfiltered'} - agreement is floor-limited]")

    ph, w, h, how = cc.ref_phase(a.classical)
    coh_ref, _, _ = cc._memmap(cc._band(a.classical, r"^coh_"))
    dx, dy, lon0, lat0 = cc._geo(a.classical)
    ys = np.arange(0, h, a.decim)
    xs = np.arange(0, w, a.decim)
    lats = lat0 - (ys[:, None] + 0.5) * dy + 0.0 * xs[None, :]
    lons = lon0 + (xs[None, :] + 0.5) * dx + 0.0 * ys[:, None]
    ri = np.asarray(ph[0][np.ix_(ys, xs)], np.float64)
    rq = np.asarray(ph[1][np.ix_(ys, xs)], np.float64)
    T = ri + 1j * rq
    ct = np.asarray(coh_ref[np.ix_(ys, xs)], np.float64)
    ok_ref = (ri != 0) & np.isfinite(ct) & (ct > 0)
    print(f"# reference {Path(a.classical).name}: {w}x{h} -> {lats.shape[1]}x{lats.shape[0]} "
          f"(decim {a.decim}), phase {how}")

    G = cc.sample_gslc(a.gslc, lats, lons)
    cg = sample_real(a.gslc, r"^coh_", lats, lons)
    fill = ok_ref & np.isfinite(G.real) & (G != 0) & np.isfinite(cg)
    print(f"# shared samples {int(fill.sum())} of {fill.size} ({fill.mean():.1%})")

    m = lambda x: np.where(fill, x, np.nan)          # noqa: E731
    ph_t, ph_a = m(np.angle(T)), m(np.angle(G))
    d_a = m(np.angle(G * np.conj(T)))
    ca = conc(d_a)
    ct_m, cg_m = m(ct), m(cg)
    dc = cg_m - ct_m
    v = dc[np.isfinite(dc)]
    lim = float(np.nanpercentile(np.abs(v), 98)) if v.size else 0.5

    ph_b = d_b = None
    cb_ = float("nan")
    if a.before:
        B = cc.sample_gslc(a.before, lats, lons)
        fb = fill & np.isfinite(B.real) & (B != 0)
        ph_b = np.where(fb, np.angle(B), np.nan)
        d_b = np.where(fb, np.angle(B * np.conj(T)), np.nan)
        cb_ = conc(d_b)

    def panel(name, data, title, cmap, vmin=None, vmax=None, norm=None, label=None):
        hh, ww = data.shape
        fig, ax = plt.subplots(figsize=(11, max(3.4, 11.0 * hh / ww + 1.0)), constrained_layout=True)
        _show(ax, data, title, cmap, vmin, vmax, norm, label)
        fig.savefig(panels / name, dpi=170)
        plt.close(fig)

    panel("ifg_classical.png", ph_t, f"{a.label} - classical interferometric phase",
          CYC, -np.pi, np.pi, label="rad")
    panel("ifg_gslc.png", ph_a, f"{a.label} - GSLC interferometric phase",
          CYC, -np.pi, np.pi, label="rad")
    panel("ifg_difference.png", d_a, f"{a.label} - GSLC minus classical (concentration {ca:.3f})",
          CYC, -np.pi, np.pi, label="rad")
    panel("coh_classical.png", ct_m, f"{a.label} - classical coherence (mean {np.nanmean(ct_m):.3f})",
          "viridis", 0, 1, label="coherence")
    panel("coh_gslc.png", cg_m, f"{a.label} - GSLC coherence (mean {np.nanmean(cg_m):.3f})",
          "viridis", 0, 1, label="coherence")
    panel("coh_difference.png", dc,
          f"{a.label} - coherence difference GSLC minus classical (mean {np.nanmean(dc):+.3f})",
          "RdBu_r", norm=TwoSlopeNorm(vcenter=0.0, vmin=-lim, vmax=lim), label="Dcoherence")
    if ph_b is not None:
        panel("ifg_gslc_before.png", ph_b, f"{a.label} - GSLC BEFORE corrections",
              CYC, -np.pi, np.pi, label="rad")
        panel("ifg_difference_before.png", d_b,
              f"{a.label} - GSLC BEFORE minus classical (concentration {cb_:.3f})",
              CYC, -np.pi, np.pi, label="rad")

    n = 3 if ph_b is None else 5
    fig, ax = plt.subplots(1, n, figsize=(5.2 * n, 5.0), constrained_layout=True)
    _show(ax[0], ph_t, "Classical interferogram", CYC, -np.pi, np.pi, label="rad")
    _show(ax[1], ph_a, "GSLC interferogram", CYC, -np.pi, np.pi, label="rad")
    _show(ax[2], d_a, f"Difference (conc {ca:.3f})", CYC, -np.pi, np.pi, label="rad")
    if ph_b is not None:
        _show(ax[3], ph_b, "GSLC BEFORE corrections", CYC, -np.pi, np.pi, label="rad")
        _show(ax[4], d_b, f"BEFORE - classical (conc {cb_:.3f})", CYC, -np.pi, np.pi, label="rad")
    fig.suptitle(f"Interferometric phase - {a.label}", fontsize=12)
    fig.savefig(out / "02_interferogram_difference.png", dpi=160)
    plt.close(fig)

    fig, ax = plt.subplots(1, 4, figsize=(20, 5.0), constrained_layout=True)
    _show(ax[0], ct_m, f"Classical coherence (mean {np.nanmean(ct_m):.3f})", "viridis", 0, 1)
    _show(ax[1], cg_m, f"GSLC coherence (mean {np.nanmean(cg_m):.3f})", "viridis", 0, 1)
    _show(ax[2], dc, f"Difference (mean {np.nanmean(dc):+.3f})", "RdBu_r",
          norm=TwoSlopeNorm(vcenter=0.0, vmin=-lim, vmax=lim))
    ax[3].hist(v, bins=80, color="#335E6E")
    ax[3].axvline(0, color="k", lw=0.8)
    ax[3].axvline(float(np.median(v)), color="#D13F42", lw=1.4, label=f"median {np.median(v):+.3f}")
    ax[3].set_title("Coherence difference", fontsize=10)
    ax[3].set_xlabel("GSLC - classical"); ax[3].legend(fontsize=8)
    fig.suptitle(f"Interferometric coherence - {a.label}", fontsize=12)
    fig.savefig(out / "03_coherence_difference.png", dpi=160)
    plt.close(fig)

    s = (f"site                : {a.label}\n"
         f"method              : map domain, GSLC sampled onto the classical grid (decim {a.decim})\n"
         f"classical reference : {Path(a.classical).name}{flt_note}\n"
         f"GSLC product        : {Path(a.gslc).name}\n"
         f"shared samples      : {int(fill.sum())} ({fill.mean():.1%})\n"
         f"phase conc AFTER    : {ca:.4f}\n"
         + (f"phase conc BEFORE   : {cb_:.4f}\n" if a.before else "")
         + f"coherence classical : mean {np.nanmean(ct_m):.4f}  median {np.nanmedian(ct_m):.4f}\n"
           f"coherence GSLC      : mean {np.nanmean(cg_m):.4f}  median {np.nanmedian(cg_m):.4f}\n"
           f"coherence difference: mean {np.nanmean(dc):+.4f}  median {np.nanmedian(dc):+.4f}  "
           f"ratio {np.nanmean(cg_m) / np.nanmean(ct_m):.4f}\n")
    (out / "summary.txt").write_text(s)
    print(s)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
