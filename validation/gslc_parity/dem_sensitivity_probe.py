"""Is the terrain-correlated GSLC-vs-classical difference actually DEM sensitivity?

The ESA comparison figures show a coherence difference (GSLC minus classical) whose structure
follows the terrain. The obvious reading is "geocoded-domain InSAR is sensitive to DEM error".
That reading makes a testable prediction, and it predicts the WRONG SIGN:

  DEM error in the geocoding displaces each leg on the ground. The displacement is common mode to
  both acquisitions to first order; the DIFFERENTIAL part scales with the look-angle difference,
  i.e. with B_perp / R. For S1 at B_perp ~ 100 m, R ~ 800 km, theta ~ 44 deg, a 5 m height error
  gives a differential ground shift of order a millimetre - far too small to decorrelate anything.
  Whatever DEM error does survive would DEGRADE the GSLC, i.e. push the coherence difference
  NEGATIVE on slopes.

This probe bins diag_height into the same radar cells as the comparison, derives a local slope,
and reports the coherence difference and the phase agreement per slope bin. If the difference goes
more negative with slope, DEM sensitivity is real and worth engineering. If it goes POSITIVE with
slope, the terrain correlation is a geometry/estimator effect (foreshortening changes the number of
independent looks per cell), not a DEM-error defect.

  python dem_sensitivity_probe.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import gslc_equivalence as ge                       # noqa: E402
import run_r5b as r5b                               # noqa: E402
import esa_chain_comparison as ec                   # noqa: E402

CELL_M = 100.0          # approximate ground size of one 8 x 30 radar cell


def main(argv=None) -> int:
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--gslc", default=str(ec.GSLC_AFTER), help="GSLC interferogram to score")
    ap.add_argument("--diag", default=str(ec.GSLC_DIAG), help="GSLC master carrying the diag bands")
    ap.add_argument("--label", default="Copernicus 30 m", help="what DEM this GSLC arm used")
    a = ap.parse_args(argv)
    ec.GSLC_DIAG = Path(a.diag)                 # gslc_cells reads this module global
    print(f"# GSLC arm: {a.label}   ifg={Path(a.gslc).name}   diag={Path(a.diag).name}")

    ti, tq, tw, th = ge.load_complex_ifg(str(ec.CLASSICAL))
    T = r5b.radar_block_mean(np.asarray(ti[:], np.float64) + 1j * np.asarray(tq[:], np.float64),
                             r5b.ML_AZ, r5b.ML_RG)
    T_coh = ec.real_block_mean(np.asarray(ec._coh_band(ec.CLASSICAL)), r5b.ML_AZ, r5b.ML_RG)

    Zs, Zc, coh_g = ec.gslc_cells(Path(a.gslc), th, tw)
    G, Tm, fill = r5b.paired_cells(Zs, Zc, T)

    # terrain height on the same cells, from the GSLC's own diag_height band
    rgi = r5b._band(a.diag, "diag_rangeIndex")
    azi = r5b._band(a.diag, "diag_azimuthIndex")
    hgt = r5b._band(a.diag, "diag_height")
    ny, nx = th // r5b.ML_AZ, tw // r5b.ML_RG
    Hs, Hc = np.zeros((ny, nx)), np.zeros((ny, nx), np.int64)
    for r0 in range(0, hgt.shape[0], 512):
        sl = slice(r0, r0 + 512)
        s_, c_ = ec.bin_real(np.asarray(hgt[sl], np.float64).ravel(),
                             np.asarray(azi[sl], np.float64).ravel(),
                             np.asarray(rgi[sl], np.float64).ravel(),
                             th, tw, r5b.ML_AZ, r5b.ML_RG)
        Hs += s_
        Hc += c_
    H = np.where(Hc > 0, Hs / np.maximum(Hc, 1), np.nan)

    gy, gx = np.gradient(np.where(np.isfinite(H), H, np.nan))
    slope = np.degrees(np.arctan(np.hypot(gx, gy) / CELL_M))

    ct = np.where(fill, T_coh, np.nan)
    cg = np.where(fill, coh_g, np.nan)
    dc = cg - ct
    dphi = np.where(fill, np.angle(G * np.conj(Tm)), np.nan)

    ok = np.isfinite(dc) & np.isfinite(slope) & np.isfinite(dphi)
    print(f"# cells with slope + coherence + phase: {int(ok.sum())}")
    print(f"# height range {np.nanmin(H):.0f}..{np.nanmax(H):.0f} m, "
          f"median slope {np.nanmedian(slope[ok]):.1f} deg\n")

    edges = [0, 2, 5, 10, 15, 20, 30, 90]
    print(f"{'slope bin':>14} {'cells':>9} {'coh classical':>14} {'coh GSLC':>10} "
          f"{'difference':>11} {'phase conc':>11}")
    for lo, hi in zip(edges[:-1], edges[1:]):
        m = ok & (slope >= lo) & (slope < hi)
        n = int(m.sum())
        if n < 200:
            continue
        conc = float(np.abs(np.exp(1j * dphi[m]).mean()))
        print(f"{lo:>6}-{hi:<7} {n:>9d} {np.nanmean(ct[m]):>14.4f} {np.nanmean(cg[m]):>10.4f} "
              f"{np.nanmean(dc[m]):>+11.4f} {conc:>11.4f}")

    lo_m = ok & (slope < 5)
    hi_m = ok & (slope >= 15)
    print(f"\nflat (<5 deg)   : difference {np.nanmean(dc[lo_m]):+.4f}  "
          f"phase conc {np.abs(np.exp(1j * dphi[lo_m]).mean()):.4f}  n={int(lo_m.sum())}")
    print(f"steep (>=15 deg): difference {np.nanmean(dc[hi_m]):+.4f}  "
          f"phase conc {np.abs(np.exp(1j * dphi[hi_m]).mean()):.4f}  n={int(hi_m.sum())}")
    trend = np.nanmean(dc[hi_m]) - np.nanmean(dc[lo_m])
    print(f"\nsteep minus flat coherence difference: {trend:+.4f}")
    print("  negative => GSLC degrades on slopes => DEM sensitivity is real and worth engineering")
    print("  positive => the terrain correlation is a geometry/estimator effect, not DEM error")

    # ---- the confounder: phase concentration falls with slope partly BECAUSE coherence falls.
    # Hold coherence fixed and ask whether slope still costs agreement. Only a residual trend at
    # MATCHED coherence is evidence of a terrain-correlated phase error (the DEM candidate).
    print()
    print("# phase concentration at MATCHED classical coherence")
    print(f"{'coh band':>12} " + "".join(f"{f'{lo}-{hi}deg':>12}" for lo, hi in
                                         ((0, 5), (5, 15), (15, 90))))
    for clo, chi in ((0.15, 0.25), (0.25, 0.35), (0.35, 0.50), (0.50, 1.01)):
        cells = ok & (ct >= clo) & (ct < chi)
        row, counts = [], []
        for slo, shi in ((0, 5), (5, 15), (15, 90)):
            m = cells & (slope >= slo) & (slope < shi)
            n = int(m.sum())
            counts.append(n)
            row.append(float(np.abs(np.exp(1j * dphi[m]).mean())) if n >= 300 else float("nan"))
        if max(counts) < 300:
            continue
        print(f"{clo:.2f}-{chi:.2f}  " + "".join(f"{v:>12.4f}" for v in row)
              + "   n=" + ",".join(str(c) for c in counts))
    print()
    print("Read down a ROW: if concentration still falls left-to-right at fixed coherence,")
    print("slope costs phase agreement for a reason other than decorrelation.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
