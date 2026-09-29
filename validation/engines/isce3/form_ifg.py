"""
Form an interferogram from two geocoded mosaics: ifg = ref * conj(sec).

Both mosaics come off the same COMPASS lattice (same CRS, posting and snapped origins), but they do
NOT cover the same extent -- S1A contributed 10 IW1 bursts here and S1C only 9, and the frames are
offset along track. So the interferogram is formed on the INTERSECTION, located by integer pixel
offset. As in mosaic_bursts.py, a fractional offset is a hard failure: shifting by a partial pixel
would require resampling, which injects the interpolation error the comparison exists to measure.

    python form_ifg.py ref_mosaic.tif sec_mosaic.tif --out ifg.tif
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from osgeo import gdal, osr

gdal.UseExceptions()


def die(msg: str) -> "None":
    print(f"FORM-IFG: {msg}", file=sys.stderr)
    raise SystemExit(3)


def read(path: Path) -> "tuple[np.ndarray, list[float], str]":
    ds = gdal.Open(str(path))
    if ds is None:
        die(f"cannot open {path}")
    a = ds.GetRasterBand(1).ReadAsArray()
    gt = list(ds.GetGeoTransform())
    wkt = ds.GetProjection()
    ds = None
    if not np.iscomplexobj(a):
        die(f"{path} is not complex -- an interferogram needs complex SLCs")
    return a, gt, wkt


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref", type=Path)
    ap.add_argument("sec", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()

    for p in (a.ref, a.sec):
        if not p.exists():
            die(f"{p} not found")

    zr, gr, wkt = read(a.ref)
    zs, gs, _ = read(a.sec)

    dx, dy = gr[1], gr[5]
    if not (np.isclose(gs[1], dx, atol=1e-9) and np.isclose(gs[5], dy, atol=1e-9)):
        die(f"posting mismatch: ref ({gr[1]},{gr[5]}) vs sec ({gs[1]},{gs[5]})")

    # integer pixel offset of sec relative to ref
    ox = (gs[0] - gr[0]) / dx
    oy = (gs[3] - gr[3]) / dy
    if max(abs(ox - round(ox)), abs(oy - round(oy))) > 0.01:
        die(f"mosaics are NOT co-lattice: fractional offset ({ox - round(ox):.4f}, "
            f"{oy - round(oy):.4f}) px. Resampling here would inject interpolation error.")
    ox, oy = int(round(ox)), int(round(oy))

    # intersection in ref pixel coords
    x0, y0 = max(0, ox), max(0, oy)
    x1 = min(zr.shape[1], ox + zs.shape[1])
    y1 = min(zr.shape[0], oy + zs.shape[0])
    if x1 <= x0 or y1 <= y0:
        die("the two mosaics do not overlap at all")

    R = zr[y0:y1, x0:x1]
    S = zs[y0 - oy:y1 - oy, x0 - ox:x1 - ox]
    ifg = (R * np.conj(S)).astype(np.complex64)

    valid = np.isfinite(ifg.real) & np.isfinite(ifg.imag) & (ifg != 0)
    pct = 100.0 * valid.sum() / ifg.size
    print(f"FORM-IFG: ref {zr.shape}  sec {zs.shape}  offset ({ox},{oy})")
    print(f"FORM-IFG: intersection {ifg.shape[1]} x {ifg.shape[0]} px, valid {pct:.1f}%")
    if pct == 0.0:
        die("interferogram is 0% valid -- refusing to report success on an empty product")
    ph = np.angle(ifg[valid])
    print(f"FORM-IFG: phase mean {np.mean(ph):+.4f} rad, circular R "
          f"{np.abs(np.mean(np.exp(1j * ph))):.4f}")

    drv = gdal.GetDriverByName("GTiff")
    ds = drv.Create(str(a.out), ifg.shape[1], ifg.shape[0], 1, gdal.GDT_CFloat32,
                    options=["COMPRESS=DEFLATE", "TILED=YES", "BIGTIFF=IF_SAFER"])
    ds.SetGeoTransform([gr[0] + x0 * dx, dx, 0.0, gr[3] + y0 * dy, 0.0, dy])
    if wkt:
        ds.SetProjection(wkt)
    ds.GetRasterBand(1).WriteArray(ifg)
    ds.FlushCache()
    ds = None
    print(f"FORM-IFG: wrote {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
