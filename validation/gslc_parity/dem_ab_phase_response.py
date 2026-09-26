"""DEM A/B: measure the phase response of a GSLC interferogram to DEM error, directly.

Two GSLC arms of the SAME pair differ only in the external DEM:
  30 m  : copernicus30_venezuela_orbit106.tif           -> ven_etadD_ifg_signfix
  90 m eq: the same tile block-averaged 3x3 back onto the same grid -> ven_dem90_ifg
Everything else - orbits, ETAD, grid, burst lock, coherence window (cohWinRg=43), the exact
deramp-model subtraction - is identical, and both products land on one lattice, so the two
interferograms can be differenced per pixel with no resampling and no classical reference.

This is the channel that actually carries DEM error into a geocoded interferogram: PHASE, not
registration. The prediction is quantitative. Topographic phase is 2*pi*h / h_amb, so a height
difference dh between the two arms should produce

    dphi = -2*pi*dh / h_amb        (sign per the flattening convention)

and the regression of the measured dphi on the measured dh should recover 2*pi/h_amb. For this
pair (B_perp 79.0 m, R 9.29e5 m, theta 43.88 deg, lambda 0.0554657 m) h_amb ~= 226 m, i.e.
0.0278 rad/m. Recovering that slope is a positive identification of the DEM channel; recovering
~0 means the DEM difference is not reaching the interferometric phase.

dh is read from each arm's own diag_height band, so it is the height each arm ACTUALLY used.

  python dem_ab_phase_response.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

D = Path(r"E:\Output\parity\ven")
ARM30_IFG, ARM30_GSLC = D / "ven_etadD_ifg_signfix.dim", D / "ven_etadD_gslc.dim"
ARM90_IFG, ARM90_GSLC = D / "ven_dem90_ifg.dim", D / "ven_dem90_gslc.dim"
H_AMB = 226.0            # m, derived from the pair's own baseline metadata
ROWS = 256               # slab height; keeps peak memory near 200 MB


def band(dim: Path, pattern: str):
    d = dim.with_suffix(".data")
    hits = [p for p in sorted(d.glob("*.img")) if re.match(pattern, p.stem)]
    if not hits:
        raise FileNotFoundError(f"no band matching /{pattern}/ in {d}")
    t = hits[0].with_suffix(".hdr").read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    bo = int(re.search(r"^byte order\s*=\s*(\d+)", t, re.M).group(1))
    code = int(re.search(r"^data type\s*=\s*(\d+)", t, re.M).group(1))
    dt = (">" if bo == 1 else "<") + {4: "f4", 5: "f8"}[code]
    return np.memmap(hits[0], dtype=dt, mode="r", shape=(h, w)), w, h


def main() -> int:
    i30, w, h = band(ARM30_IFG, r"^i_ifg")
    q30, _, _ = band(ARM30_IFG, r"^q_ifg")
    i90, w2, h2 = band(ARM90_IFG, r"^i_ifg")
    q90, _, _ = band(ARM90_IFG, r"^q_ifg")
    if (w, h) != (w2, h2):
        raise SystemExit(f"arms are not on one lattice: {(w,h)} vs {(w2,h2)}")
    h30, _, _ = band(ARM30_GSLC, r"^diag_height")
    h90, _, _ = band(ARM90_GSLC, r"^diag_height")
    print(f"# lattice {w} x {h}; h_amb {H_AMB:.0f} m -> predicted {2*np.pi/H_AMB:.4f} rad per metre")

    # streaming accumulators for the regression of dphi on dh
    n = 0
    sx = sy = sxx = sxy = 0.0
    sum_sq = 0.0
    cs = 0.0 + 0.0j
    dh_abs = []
    dphi_abs = []
    for r0 in range(0, h, ROWS):
        sl = slice(r0, min(r0 + ROWS, h))
        a = np.asarray(i30[sl], np.float64) + 1j * np.asarray(q30[sl], np.float64)
        b = np.asarray(i90[sl], np.float64) + 1j * np.asarray(q90[sl], np.float64)
        x30 = np.asarray(h30[sl], np.float64)
        x90 = np.asarray(h90[sl], np.float64)
        ok = (a != 0) & (b != 0) & np.isfinite(x30) & np.isfinite(x90) & (x30 != 0) & (x90 != 0)
        if not ok.any():
            continue
        d = np.angle(b[ok] * np.conj(a[ok]))          # phase change caused by the DEM change
        dh = x90[ok] - x30[ok]
        n += d.size
        sx += dh.sum(); sy += d.sum(); sxx += (dh * dh).sum(); sxy += (dh * d).sum()
        sum_sq += float((d * d).sum())
        cs += np.exp(1j * d).sum()
        if len(dh_abs) < 40:                           # keep a bounded sample for percentiles
            k = max(1, d.size // 20000)
            dh_abs.append(dh[::k]); dphi_abs.append(d[::k])

    if n == 0:
        raise SystemExit("no jointly valid pixels")
    dh_s = np.concatenate(dh_abs); dphi_s = np.concatenate(dphi_abs)
    slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    rms = np.sqrt(sum_sq / n)
    conc = abs(cs) / n

    print(f"# jointly valid pixels {n/1e6:.1f} M")
    print()
    print("DEM difference actually used by the two arms")
    print(f"  rms {np.sqrt((dh_s**2).mean()):8.3f} m     p99 |dh| {np.percentile(np.abs(dh_s),99):7.2f} m"
          f"     max |dh| {np.abs(dh_s).max():7.1f} m")
    print()
    print("Interferometric phase response (90 m arm minus 30 m arm)")
    print(f"  rms                {rms:8.4f} rad")
    print(f"  concentration      {conc:8.4f}        (1.0 = the DEM change did nothing)")
    print(f"  p99 |dphi|         {np.percentile(np.abs(dphi_s),99):8.4f} rad")
    print()
    print("Transfer function dphi / dh")
    print(f"  measured slope     {slope:+8.5f} rad/m")
    print(f"  predicted 2pi/h_amb {2*np.pi/H_AMB:+8.5f} rad/m   (or negative, per flattening sign)")
    print(f"  ratio measured/predicted  {abs(slope)/(2*np.pi/H_AMB):.3f}")
    print()
    if abs(slope) > 0.3 * (2 * np.pi / H_AMB):
        print("=> The DEM difference DOES reach the interferometric phase at close to the")
        print("   topographic-phase rate. The DEM channel is live and is a real sensitivity.")
    else:
        print("=> The DEM difference does NOT propagate to the phase at the topographic rate;")
        print("   the geocoded chain is largely insensitive to a DEM change of this size.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
