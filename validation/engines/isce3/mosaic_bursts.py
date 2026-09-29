"""
Mosaic per-burst COMPASS CSLC grids into one raster on a common lattice.

WHY
SNAP geocodes a whole split subswath into ONE raster; OPERA CSLC is per-burst (10 grids for IW1
here). To diff fringes the two must be brought onto one grid. Mosaicking the COMPASS side is the
right direction: it leaves the SNAP product -- the artifact under test -- untouched.

THE KEY PRECONDITION, CHECKED NOT ASSUMED
Every burst geogrid must be CO-LATTICE: identical spacing, and origins separated by a WHOLE number
of pixels. COMPASS honours the x_snap/y_snap the adapter sets, so this holds in practice. If it did
not, placing bursts by integer index would misregister them, and resampling to force a fit would
introduce exactly the interpolation error the comparison is trying to measure. So a fractional offset
is a hard failure here, never a silent resample.

OVERLAP POLICY
TOPS bursts overlap in azimuth (~10%). This does NOT blend: it takes the first valid contribution and
leaves it. Complex-averaging two bursts would let any residual phase difference between them cancel
destructively, quietly manufacturing low-amplitude seams that look like decorrelation.

Instead the overlap is used as a FREE SELF-CHECK: where two bursts both have valid data they describe
the same ground, so after carrier removal their phases should agree. The reported circular mean of
|dphi| across overlaps is therefore a direct test of the carrier removal. A large value means the
carrier was not correctly removed, and it is reported prominently rather than buried.

    python mosaic_bursts.py <product_dir_or_h5s...> --out mosaic.tif [--pol VV]
"""
from __future__ import annotations

import argparse
import glob
import sys
from pathlib import Path

import h5py
import numpy as np

CARRIER = "azimuth_carrier_phase"


def die(msg: str) -> "None":
    print(f"MOSAIC: {msg}", file=sys.stderr)
    raise SystemExit(3)


class Burst:
    """One geocoded burst: complex grid plus the map coordinates of its lattice."""

    def __init__(self, path: Path, name: str, data: np.ndarray, x0: float, y0: float,
                 dx: float, dy: float, epsg: "int | None"):
        self.path, self.name, self.data = path, name, data
        self.x0, self.y0, self.dx, self.dy, self.epsg = x0, y0, dx, dy, epsg

    def __repr__(self) -> str:
        return (f"{self.path.name}:{self.name} {self.data.shape} "
                f"origin=({self.x0:.1f},{self.y0:.1f})")


def load_bursts(h5s: "list[Path]", pol: str) -> "list[Burst]":
    out: list[Burst] = []
    for p in h5s:
        with h5py.File(p, "r") as f:
            grids: list[tuple[str, h5py.Dataset]] = []

            def visit(name: str, obj) -> None:
                if isinstance(obj, h5py.Dataset) and obj.ndim == 2 and np.iscomplexobj(obj.dtype.type(0)):
                    grids.append((name, obj))

            f.visititems(visit)
            if pol:
                sel = [g for g in grids if g[0].rsplit("/", 1)[-1].upper() == pol.upper()] or grids
            else:
                sel = grids
            for name, ds in sel:
                grp = name.rsplit("/", 1)[0]
                # x_coordinates / y_coordinates are pixel-CENTRE arrays in the OPERA CSLC layout.
                # Read them rather than reconstructing from attributes: they are the authority on
                # where this burst actually sits.
                try:
                    xs = f[f"{grp}/x_coordinates"][:]
                    ys = f[f"{grp}/y_coordinates"][:]
                except KeyError:
                    die(f"{p.name}: no x/y_coordinates beside '{name}' -- cannot place this burst "
                        f"without inventing a geotransform")
                if len(xs) < 2 or len(ys) < 2:
                    die(f"{p.name}: degenerate coordinate arrays for '{name}'")
                epsg = None
                for k in ("projection", "epsg", "epsg_code"):
                    if k in f[grp]:
                        try:
                            epsg = int(np.asarray(f[f"{grp}/{k}"]).ravel()[0])
                        except Exception:
                            pass
                out.append(Burst(p, name, ds[:], float(xs[0]), float(ys[0]),
                                 float(xs[1] - xs[0]), float(ys[1] - ys[0]), epsg))
    if not out:
        die("no 2-D complex datasets found in the given files")
    return out


def check_colattice(bursts: "list[Burst]") -> "tuple[float, float]":
    dx, dy = bursts[0].dx, bursts[0].dy
    for b in bursts[1:]:
        if not (np.isclose(b.dx, dx, rtol=0, atol=1e-6) and np.isclose(b.dy, dy, rtol=0, atol=1e-6)):
            die(f"spacing mismatch: {bursts[0]} has ({dx},{dy}), {b} has ({b.dx},{b.dy}). "
                f"A mosaic across different postings would require resampling.")
    x_ref, y_ref = bursts[0].x0, bursts[0].y0
    worst = 0.0
    for b in bursts:
        fx = abs((b.x0 - x_ref) / dx); fy = abs((b.y0 - y_ref) / dy)
        rx = abs(fx - round(fx)); ry = abs(fy - round(fy))
        worst = max(worst, rx, ry)
    # 0.01 px matches MAX_GEOCODED_SUBPIXEL_RESIDUAL used elsewhere in this work.
    if worst > 0.01:
        die(f"bursts are NOT co-lattice: worst fractional origin offset {worst:.4f} px (> 0.01). "
            f"Placing them by integer index would misregister; resampling would inject the very "
            f"interpolation error this comparison measures. Set x_snap/y_snap in the runconfig.")
    print(f"MOSAIC: co-lattice OK (worst fractional origin offset {worst:.2e} px)")
    return dx, dy


def build(bursts: "list[Burst]", dx: float, dy: float) -> "tuple[np.ndarray, float, float, dict]":
    xs0 = min(b.x0 for b in bursts)
    ys0 = max(b.y0 for b in bursts) if dy < 0 else min(b.y0 for b in bursts)

    def index_of(b: Burst) -> "tuple[int, int]":
        return int(round((b.y0 - ys0) / dy)), int(round((b.x0 - xs0) / dx))

    h = max(index_of(b)[0] + b.data.shape[0] for b in bursts)
    w = max(index_of(b)[1] + b.data.shape[1] for b in bursts)
    mosaic = np.full((h, w), np.nan + 1j * np.nan, dtype=np.complex64)
    filled = np.zeros((h, w), dtype=bool)

    overlap_px = 0
    dphi: list[np.ndarray] = []
    for b in sorted(bursts, key=lambda b: (index_of(b)[0], index_of(b)[1])):
        r, c = index_of(b)
        win = (slice(r, r + b.data.shape[0]), slice(c, c + b.data.shape[1]))
        valid = np.isfinite(b.data.real) & np.isfinite(b.data.imag)
        clash = filled[win] & valid
        n = int(clash.sum())
        if n:
            # Free self-check: same ground, so after carrier removal the phases must agree.
            overlap_px += n
            dphi.append(np.angle(b.data[clash] * np.conj(mosaic[win][clash])))
        take = valid & ~filled[win]
        tgt = mosaic[win]; tgt[take] = b.data[take]; mosaic[win] = tgt
        f = filled[win]; f[take] = True; filled[win] = f

    stats = {"height": h, "width": w,
             "valid_px": int(filled.sum()), "total_px": h * w,
             "overlap_px": overlap_px}
    if dphi:
        d = np.concatenate(dphi)
        d = d[np.isfinite(d)]
        if d.size:
            # circular mean of |dphi|, plus resultant length: R near 1 means tight agreement
            stats["overlap_abs_dphi_mean_rad"] = float(np.mean(np.abs(d)))
            stats["overlap_circular_R"] = float(np.abs(np.mean(np.exp(1j * d))))
    return mosaic, xs0, ys0, stats


def write_tif(path: Path, mosaic: np.ndarray, x0: float, y0: float, dx: float, dy: float,
              epsg: "int | None") -> None:
    from osgeo import gdal, osr
    gdal.UseExceptions()
    drv = gdal.GetDriverByName("GTiff")
    ds = drv.Create(str(path), mosaic.shape[1], mosaic.shape[0], 1, gdal.GDT_CFloat32,
                    options=["COMPRESS=DEFLATE", "TILED=YES", "BIGTIFF=IF_SAFER"])
    # x0/y0 are pixel CENTRES; a GDAL geotransform wants the outer EDGE of the first pixel.
    ds.SetGeoTransform([x0 - dx / 2.0, dx, 0.0, y0 - dy / 2.0, 0.0, dy])
    if epsg:
        srs = osr.SpatialReference(); srs.ImportFromEPSG(int(epsg))
        ds.SetProjection(srs.ExportToWkt())
    ds.GetRasterBand(1).WriteArray(mosaic)
    ds.FlushCache()
    ds = None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+", help="CSLC .h5 files, or a directory to search")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--pol", default="VV")
    a = ap.parse_args()

    h5s: list[Path] = []
    for i in a.inputs:
        p = Path(i)
        h5s.extend(sorted(Path(x) for x in glob.glob(str(p / "**" / "*.h5"), recursive=True))
                   if p.is_dir() else [p])
    h5s = [p for p in h5s if p.exists()]
    if not h5s:
        die(f"no .h5 found under {a.inputs}")
    print(f"MOSAIC: {len(h5s)} CSLC file(s)")

    bursts = load_bursts(h5s, a.pol)
    print(f"MOSAIC: {len(bursts)} burst grid(s), pol={a.pol}")
    dx, dy = check_colattice(bursts)
    mosaic, x0, y0, stats = build(bursts, dx, dy)

    pct = 100.0 * stats["valid_px"] / stats["total_px"]
    print(f"MOSAIC: {stats['width']} x {stats['height']} px, spacing ({dx}, {dy})")
    print(f"MOSAIC: valid {stats['valid_px']} / {stats['total_px']} px ({pct:.1f}%)")
    print(f"MOSAIC: burst overlap {stats['overlap_px']} px")
    if "overlap_abs_dphi_mean_rad" in stats:
        m = stats["overlap_abs_dphi_mean_rad"]; R = stats["overlap_circular_R"]
        verdict = "consistent" if m < 0.30 else "INCONSISTENT -- suspect carrier removal"
        print(f"MOSAIC: overlap phase agreement mean|dphi| = {m:.4f} rad, R = {R:.4f}  [{verdict}]")
    else:
        print("MOSAIC: no burst overlap sampled -- carrier-removal self-check NOT exercised")

    if pct == 0.0:
        die("mosaic is 0% valid -- refusing to report success on an empty raster")

    epsg = next((b.epsg for b in bursts if b.epsg), None)
    if epsg is None:
        print("MOSAIC: WARNING no EPSG found in the CSLC; writing without a projection")
    write_tif(a.out, mosaic, x0, y0, dx, dy, epsg)
    print(f"MOSAIC: wrote {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
