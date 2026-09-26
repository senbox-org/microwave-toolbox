"""DEM A/B, isolated to the topographic-phase channel.

Two Interferogram runs over ONE stack (ven_etadD_stack), one build, identical parameters, differing
only in the external DEM used for topographic-phase removal:

    ven_dem30topo_ifg   Copernicus 30 m
    ven_dem90topo_ifg   the same tile block-averaged 3x3 back onto the same grid (90 m equivalent)

Because the stack is not rebuilt, this is free of the ~0.73 rad run-to-run reproducibility floor
that a full-chain A/B carries, and because both arms come from the same build there is no
version confound. The only difference is the DEM.

PREDICTION. Topographic phase is 2*pi*h/h_amb, so a height difference dh between the two arms
should appear as dphi = -2*pi*dh/h_amb (sign per the flattening convention). For this pair
(B_perp 79.0 m, R 9.29e5 m, theta 43.88 deg, lambda 0.0554657 m) h_amb ~= 226 m, i.e.
0.0278 rad/m. The injected DEM difference has rms 6.55 m, so a live channel gives ~0.18 rad rms
and a regression slope near 0.0278 rad/m. A slope near zero means the DEM change is not reaching
the interferometric phase.

dh is taken from the two DEM rasters themselves, sampled at each interferogram pixel's lat/lon
through the product's own geotransform - not from any band, so nothing is assumed about what the
operator recorded.

  python dem_ab_topo_response.py
"""
from __future__ import annotations

import re
from pathlib import Path

import numpy as np
import rasterio

D = Path(r"E:\Output\parity\ven")
ARM30 = D / "ven_dem30topo_ifg.dim"
ARM90 = D / "ven_dem90topo_ifg.dim"
DEM30 = r"E:\TestData\dem\copernicus30_venezuela_orbit106.tif"
DEM90 = r"E:\TestData\dem\copernicus90equiv_venezuela_orbit106.tif"
H_AMB = 226.0
ROWS = 256


def band(dim: Path, pattern: str):
    d = dim.with_suffix(".data")
    hits = [p for p in sorted(d.glob("*.img")) if re.match(pattern, p.stem)]
    if not hits:
        raise FileNotFoundError(f"no /{pattern}/ band in {d}")
    t = hits[0].with_suffix(".hdr").read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    bo = int(re.search(r"^byte order\s*=\s*(\d+)", t, re.M).group(1))
    code = int(re.search(r"^data type\s*=\s*(\d+)", t, re.M).group(1))
    dt = (">" if bo == 1 else "<") + {4: "f4", 5: "f8"}[code]
    return np.memmap(hits[0], dtype=dt, mode="r", shape=(h, w)), w, h


def geo(dim: Path):
    txt = dim.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"<IMAGE_TO_MODEL_TRANSFORM>([^<]*)</IMAGE_TO_MODEL_TRANSFORM>", txt)
    tr = [float(v) for v in m.group(1).split(",")]
    return tr[0], -tr[3], tr[4], tr[5]          # dx, dy(+south), lon0, lat0


def main() -> int:
    i30, w, h = band(ARM30, r"^i_ifg")
    q30, _, _ = band(ARM30, r"^q_ifg")
    i90, w2, h2 = band(ARM90, r"^i_ifg")
    q90, _, _ = band(ARM90, r"^q_ifg")
    if (w, h) != (w2, h2):
        raise SystemExit(f"arms differ in size: {(w,h)} vs {(w2,h2)}")
    dx, dy, lon0, lat0 = geo(ARM30)
    print(f"# lattice {w} x {h}; h_amb {H_AMB:.0f} m -> predicted {2*np.pi/H_AMB:.4f} rad/m")

    with rasterio.open(DEM30) as a, rasterio.open(DEM90) as b:
        if a.transform != b.transform or a.shape != b.shape:
            raise SystemExit("the two DEMs are not on one grid - dh would be meaningless")
        dh_ras = (b.read(1).astype(np.float32) - a.read(1).astype(np.float32))
        gt, dh_h, dh_w = a.transform, a.height, a.width
    print(f"# DEM difference raster {dh_w} x {dh_h}: rms {float(np.sqrt((dh_ras**2).mean())):.3f} m, "
          f"max |dh| {float(np.abs(dh_ras).max()):.1f} m")
    if float(np.abs(dh_ras).max()) == 0.0:
        raise SystemExit("the two DEMs are identical - there is no A/B to measure")

    n = 0
    sx = sy = sxx = sxy = 0.0
    sq = 0.0
    cs = 0.0 + 0.0j
    keep_dh, keep_dp = [], []
    for r0 in range(0, h, ROWS):
        sl = slice(r0, min(r0 + ROWS, h))
        za = np.asarray(i30[sl], np.float64) + 1j * np.asarray(q30[sl], np.float64)
        zb = np.asarray(i90[sl], np.float64) + 1j * np.asarray(q90[sl], np.float64)
        ok = (za != 0) & (zb != 0)
        if not ok.any():
            continue
        rows = np.arange(sl.start, sl.stop)[:, None]
        cols = np.arange(w)[None, :]
        lat = lat0 - (rows + 0.5) * dy
        lon = lon0 + (cols + 0.5) * dx
        rr = ((lat - gt.f) / gt.e).astype(np.int64)      # gt.e is negative (north-up)
        cc = ((lon - gt.c) / gt.a).astype(np.int64)
        rr = np.broadcast_to(rr, ok.shape)
        cc = np.broadcast_to(cc, ok.shape)
        inside = ok & (rr >= 0) & (rr < dh_h) & (cc >= 0) & (cc < dh_w)
        if not inside.any():
            continue
        dphi = np.angle(zb[inside] * np.conj(za[inside]))
        dh = dh_ras[rr[inside], cc[inside]].astype(np.float64)
        n += dphi.size
        sx += dh.sum(); sy += dphi.sum(); sxx += (dh * dh).sum(); sxy += (dh * dphi).sum()
        sq += float((dphi * dphi).sum())
        cs += np.exp(1j * dphi).sum()
        if len(keep_dh) < 40:
            k = max(1, dphi.size // 20000)
            keep_dh.append(dh[::k]); keep_dp.append(dphi[::k])

    if n == 0:
        raise SystemExit("no jointly valid pixels")
    dh_s = np.concatenate(keep_dh); dp_s = np.concatenate(keep_dp)
    slope = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    pred = 2 * np.pi / H_AMB
    print(f"# jointly valid pixels {n/1e6:.1f} M\n")
    print("DEM difference seen by the interferogram pixels")
    print(f"  rms {np.sqrt((dh_s**2).mean()):7.3f} m    p99 |dh| {np.percentile(np.abs(dh_s),99):6.2f} m")
    print("\nPhase response (90 m arm minus 30 m arm)")
    print(f"  rms                 {np.sqrt(sq/n):8.4f} rad")
    print(f"  concentration       {abs(cs)/n:8.4f}      (1.0 = the DEM change did nothing)")
    print(f"  p99 |dphi|          {np.percentile(np.abs(dp_s),99):8.4f} rad")
    print(f"  predicted rms       {pred*np.sqrt((dh_s**2).mean()):8.4f} rad  (slope x dh rms)")
    print("\nTransfer function")
    print(f"  measured  {slope:+9.5f} rad/m")
    print(f"  predicted {pred:+9.5f} rad/m")
    print(f"  |measured| / predicted   {abs(slope)/pred:.3f}")
    print()
    if abs(slope) > 0.5 * pred:
        print("=> DEM error reaches the interferometric phase at close to the topographic rate.")
        print("   Geocoded-domain InSAR IS DEM-sensitive through this channel; the mitigation")
        print("   (30 m or finer DEM, flattening carried as a separable layer) is load-bearing.")
    elif abs(slope) > 0.1 * pred:
        print("=> Partial propagation: the DEM change reaches the phase, but well below the")
        print("   topographic rate. Attribution needs the flattening convention examined.")
    else:
        print("=> The DEM change does NOT reach the interferometric phase at the topographic rate.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
