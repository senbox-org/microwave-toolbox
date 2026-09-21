"""R5b - GSLC vs classical in the RADAR domain with a TERRAIN-AWARE link (replaces the tie-point-grid
inverse geocoding of run_r5.py, which ignored terrain and produced an invalid comparison).

Every map pixel of a GSLC built with -Dgslc.diagGeometry=true carries the SOURCE range and azimuth
index it was read from (diag_rangeIndex, diag_azimuthIndex - float64, in the reference SLC's burst-
stacked raster). Binning the map pixels of the GSLC interferogram into radar multilook cells by those
indices puts the GSLC product on the classical BURST-GEOMETRY grid (the coregistered stack and its
un-debursted interferogram share the master SLC's row/column indexing) with no resampling and no
geolocation at all. Both sides are then multilooked over the same (ml_az x ml_rg) radar cells.

Burst overlaps: the classical interferogram holds an overlap twice (once per burst); the GSLC picked
one burst per map pixel, so a cell is compared only where the GSLC has data - and then against the
very same source rows.

VALIDITY CHECK BUILT IN: the binned GSLC amplitude and the classical amplitude are correlated over the
shared cells. Below LINK_MIN_CORR the link is not trusted and every recorded row is marked INVALID.

  r5b <gslc_ifg.dim> <gslc_master_diag.dim> <classical_burst_ifg.dim> <floor_conc> [rung_label]
"""
from __future__ import annotations

import contextlib
import io
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                       # noqa: E402
from budget import record                           # noqa: E402
from radar_domain import hdr_dtype                  # noqa: E402

ML_AZ, ML_RG = 8, 30          # 8 divides the 1504-line burst; 30 range px ~ 100 m on the ground
LINK_MIN_CORR = 0.8           # PROVISIONAL: chosen before measurement
MIN_FILL = 0.6                # a cell needs >= this fraction of the median map-pixel count
SRC = "venezuela"
PROV_PROV = "PROVISIONAL: chosen before measurement, no prior data"


def bin_to_cells(z: np.ndarray, az: np.ndarray, rg: np.ndarray, n_rows: int, n_cols: int,
                 ml_az: int, ml_rg: int):
    """Sum complex map pixels into radar cells by their source (az, rg) index.
    Returns (sum[n_rows//ml_az, n_cols//ml_rg], count[...]). Pixels with a non-finite or negative
    index, an index outside the raster, or z == 0 (no data) are ignored."""
    ny, nx = n_rows // ml_az, n_cols // ml_rg
    ok = np.isfinite(az) & np.isfinite(rg) & (az >= 0) & (rg >= 0) & (az < ny * ml_az) & (rg < nx * ml_rg) & (z != 0)
    if not ok.any():
        return np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64)
    cell = (np.floor(az[ok] / ml_az).astype(np.int64) * nx + np.floor(rg[ok] / ml_rg).astype(np.int64))
    zz = z[ok]
    re = np.bincount(cell, weights=zz.real, minlength=ny * nx)
    im = np.bincount(cell, weights=zz.imag, minlength=ny * nx)
    cnt = np.bincount(cell, minlength=ny * nx)
    return (re + 1j * im).reshape(ny, nx), cnt.reshape(ny, nx)


def radar_block_mean(z: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    """Mean over ml_az x ml_rg blocks of the radar-geometry field; a block containing a zero
    (no-data) sample or a NaN is set to 0."""
    h, w = (z.shape[0] // ml_az) * ml_az, (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    bad = (~np.isfinite(zz.real) | ~np.isfinite(zz.imag) | (zz == 0)).any(axis=(1, 3))
    return np.where(bad, 0, np.nan_to_num(zz, nan=0.0).mean(axis=(1, 3)))


def paired_cells(G_sum, G_cnt, T_mean, min_fill: float = MIN_FILL):
    """Cells valid on both sides -> (G_mean, T_mean) with zeros elsewhere, plus the fill mask."""
    med = np.median(G_cnt[G_cnt > 0]) if (G_cnt > 0).any() else 0
    fill = (G_cnt >= min_fill * med) & (np.abs(T_mean) > 0)
    G_mean = np.where(fill, G_sum / np.maximum(G_cnt, 1), 0)
    return G_mean, np.where(fill, T_mean, 0), fill


def link_correlation(G_mean: np.ndarray, T_mean: np.ndarray, fill: np.ndarray, min_cells: int = 100) -> float:
    """Correlation of log-amplitude over the shared cells (phase independent)."""
    if fill.sum() < min_cells:
        return float("nan")
    a, b = np.log(np.abs(G_mean[fill])), np.log(np.abs(T_mean[fill]))
    return float(np.corrcoef(a, b)[0, 1])


def gate_metrics(G, T) -> dict:
    m: dict = {}
    with contextlib.redirect_stdout(io.StringIO()):
        ge.compute_gates(G, T, None, None, metrics_out=m)
    return m


def verdict(m: dict, floor_conc: float, corr: float) -> list[tuple]:
    """(gate, value, threshold, provenance, passed). If the link check fails, passed is None
    (INVALID) for every gate - a comparison through an untrusted link is not a result."""
    valid = bool(np.isfinite(corr) and corr >= LINK_MIN_CORR)
    conc = m.get("phase-residual-conc", float("nan"))
    ratio = conc / floor_conc if floor_conc and floor_conc > 0 else float("nan")

    def p(x):
        return bool(x) if valid else None
    rows = [("link-amplitude-corr", corr, LINK_MIN_CORR, PROV_PROV, bool(valid)),
            ("conc-over-floor", ratio, 0.90, PROV_PROV, p(ratio >= 0.90))]
    for g in ("gx-median-ratio", "gy-median-ratio"):
        v = m.get(g, float("nan"))
        rows.append((g, v, 2.0, "spec section 7 (R5)", p(v <= 2.0)))
    return rows


def _band(dim: str, name: str) -> np.ndarray:
    import re
    data = Path(dim).with_suffix(".data")
    hdr = data / f"{name}.hdr"
    t = hdr.read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    return np.memmap(data / f"{name}.img", dtype=hdr_dtype(hdr), mode="r", shape=(h, w))


def cells_from_products(gslc_ifg: str, gslc_diag: str, classical_ifg: str):
    """(G_mean, T_mean, fill, G_count, link_corr) on the classical burst-geometry radar cells."""
    ti, tq, tw, th = ge.load_complex_ifg(classical_ifg)
    T = radar_block_mean(np.asarray(ti[:], np.float64) + 1j * np.asarray(tq[:], np.float64), ML_AZ, ML_RG)
    gi, gq, gw, gh = ge.load_complex_ifg(gslc_ifg)
    rgi, azi = _band(gslc_diag, "diag_rangeIndex"), _band(gslc_diag, "diag_azimuthIndex")
    if rgi.shape != (gh, gw):
        raise ValueError(f"diag bands {rgi.shape} do not match the interferogram {(gh, gw)} - the master GSLC "
                         f"and the interferogram must share one lattice")
    sub_rg, sub_az = rgi[::50, ::50], azi[::50, ::50]
    print(f"# classical burst-geometry ifg {tw}x{th}; GSLC ifg {gw}x{gh}; source-index ranges: "
          f"rg {float(np.nanmin(sub_rg[sub_rg > 0])):.0f}..{float(np.nanmax(sub_rg)):.0f}, "
          f"az {float(np.nanmin(sub_az[sub_az > 0])):.0f}..{float(np.nanmax(sub_az)):.0f}")
    ny, nx = th // ML_AZ, tw // ML_RG
    Gs, Gc = np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64)
    for r0 in range(0, gh, 512):
        z = np.asarray(gi[r0:r0 + 512], np.float64) + 1j * np.asarray(gq[r0:r0 + 512], np.float64)
        s_, c_ = bin_to_cells(z.ravel(), np.asarray(azi[r0:r0 + 512], np.float64).ravel(),
                              np.asarray(rgi[r0:r0 + 512], np.float64).ravel(), th, tw, ML_AZ, ML_RG)
        Gs += s_
        Gc += c_
    Gm, Tm, fill = paired_cells(Gs, Gc, T)
    print(f"# cells {ny}x{nx}; shared {int(fill.sum())} ({fill.mean():.1%}); median map pixels per cell {np.median(Gc[Gc > 0]):.0f}")
    return Gm, Tm, fill, Gc, link_correlation(Gm, Tm, fill)


def run(gslc_ifg: str, gslc_diag: str, classical_ifg: str, floor_conc: float, rung: str = "R5b") -> int:
    Gm, Tm, fill, _Gc, corr = cells_from_products(gslc_ifg, gslc_diag, classical_ifg)
    m = gate_metrics(Gm, Tm)
    print("R5b raw metrics:", m, "| link amplitude corr", round(corr, 3))
    ok = True
    for gate, v, thr, prov, passed in verdict(m, floor_conc, corr):
        tag = "INVALID" if passed is None else ("PASS" if passed else "FAIL")
        print(f"GATE {rung.lower()}-{gate} {tag} {v:.6g} {thr:g}")
        record(rung, SRC, gate, v, thr, prov, passed,
               "terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5" +
               ("" if passed is not None else "; LINK CHECK FAILED - not a result"))
        ok &= bool(passed)
    record(rung, SRC, "phase-residual-conc", m["phase-residual-conc"], None, "measured", None, f"link corr {corr:.3f}")
    record(rung, SRC, "residual-rms-rad", m["residual-rms-rad"], None, "measured", None, f"link corr {corr:.3f}")
    return 0 if ok else 1


def _selftest() -> int:
    ok = True
    rng = np.random.default_rng(6)
    H, W = 64, 120                                    # radar raster: 8x30 cells -> 8 x 4
    Z = np.exp(1j * np.add.outer(np.linspace(0, 3, H), np.linspace(0, 9, W))) * (1 + 0.5 * rng.normal(size=(H, W)))
    # a map that samples every radar pixel exactly 4 times (2x oversampling both ways)
    mi, mj = np.mgrid[0:2 * H, 0:2 * W]
    az, rg = (mi // 2).astype(float), (mj // 2).astype(float)
    Zmap = Z[mi // 2, mj // 2]
    s, c = bin_to_cells(Zmap.ravel(), az.ravel(), rg.ravel(), H, W, 8, 30)
    Tm = radar_block_mean(Z, 8, 30)
    Gm, Tm2, fill = paired_cells(s, c, Tm)
    err = float(np.max(np.abs(Gm[fill] - Tm2[fill])))
    print(f"bin vs block mean: max err {err:.2e}, cells {fill.sum()}/{fill.size}, count per cell {int(c[0, 0])}")
    if not (err < 1e-12 and fill.all() and c[0, 0] == 8 * 30 * 4):
        print("  FAIL: binning must reproduce the radar block mean exactly")
        ok = False
    # no-data and invalid indices are ignored
    Zb = Zmap.copy()
    Zb[:2, :] = 0
    azb = az.copy()
    azb[10, :] = np.nan
    s2, c2 = bin_to_cells(Zb.ravel(), azb.ravel(), rg.ravel(), H, W, 8, 30)
    if c2.sum() != c.sum() - 2 * (2 * W) - (2 * W):
        print(f"  FAIL: no-data / NaN index handling ({c2.sum()} vs {c.sum() - 6 * W})")
        ok = False
    # the link check: identical amplitude structure correlates ~1; a shifted one does not
    corr_ok = link_correlation(Gm, Tm2, fill, min_cells=10)
    Gshift = np.roll(Gm, 3, axis=1)
    corr_bad = link_correlation(Gshift, Tm2, fill, min_cells=10)
    print(f"link correlation: aligned {corr_ok:.3f}, misregistered {corr_bad:.3f}")
    if not (corr_ok > 0.99 and corr_bad < 0.5):
        print("  FAIL: link check must separate aligned from misregistered")
        ok = False
    # verdicts: a failing link makes every gate INVALID (None), never a FAIL or a PASS
    good = {"phase-residual-conc": 0.99, "gx-median-ratio": 1.1, "gy-median-ratio": 1.2}
    v = {r[0]: r[4] for r in verdict(good, 0.999, 0.95)}
    if v != {"link-amplitude-corr": True, "conc-over-floor": True, "gx-median-ratio": True, "gy-median-ratio": True}:
        print("  FAIL: verdict (valid link)", v)
        ok = False
    v = {r[0]: r[4] for r in verdict(good, 0.999, 0.3)}
    if v["link-amplitude-corr"] is not False or any(v[k] is not None for k in ("conc-over-floor", "gx-median-ratio", "gy-median-ratio")):
        print("  FAIL: an untrusted link must make the gates INVALID", v)
        ok = False
    m = gate_metrics(Gm, Tm2)
    if not m["phase-residual-conc"] > 0.95:
        print("  FAIL: identical fields must concentrate", m)
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(a) in (5, 6) and a[0] == "r5b":          # optional 6th arg: the rung label to record under
        raise SystemExit(run(a[1], a[2], a[3], float(a[4]), *(a[5:] or [])))
    print(__doc__)
    raise SystemExit(2)
