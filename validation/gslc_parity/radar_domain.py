"""Radar-domain comparison helpers: lat/lon of every radar pixel from SNAP tie-point grids, and
resampling of a map-grid (GSLC) complex field onto that radar grid."""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inverse_geocode import _bilinear_complex, geo_to_index, _read_geo  # noqa: E402


def hdr_dtype(hdr: Path) -> np.dtype:
    """numpy dtype of an ENVI .hdr (float32/float64, honouring its byte order)."""
    t = Path(hdr).read_text()
    code = int(re.search(r"^data type\s*=\s*(\d+)", t, re.M).group(1))
    bo = int(re.search(r"^byte order\s*=\s*(\d)", t, re.M).group(1))
    return np.dtype({4: np.float32, 5: np.float64}[code]).newbyteorder(">" if bo == 1 else "<")


def read_tpg(dim_path: str, name: str):
    """(values[NROWS,NCOLS], offset_x, offset_y, step_x, step_y) of a tie-point grid."""
    dim = Path(dim_path)
    txt = dim.read_text(encoding="utf-8", errors="replace")
    blk = re.search(r"<Tie_Point_Grid_Info>(?:(?!</Tie_Point_Grid_Info>).)*?<TIE_POINT_GRID_NAME>"
                    + re.escape(name) + r"</TIE_POINT_GRID_NAME>.*?</Tie_Point_Grid_Info>", txt, re.S)
    if not blk:
        raise ValueError(f"tie-point grid {name!r} not declared in {dim.name}")
    b = blk.group(0)

    def num(tag):
        return float(re.search(rf"<{tag}>([^<]+)</{tag}>", b).group(1))

    nc, nr = int(num("NCOLS")), int(num("NROWS"))
    img = dim.with_suffix(".data") / "tie_point_grids" / f"{name}.img"
    hdr = dim.with_suffix(".data") / "tie_point_grids" / f"{name}.hdr"
    vals = np.fromfile(img, dtype=hdr_dtype(hdr))
    return vals.reshape(nr, nc).astype(np.float64), num("OFFSET_X"), num("OFFSET_Y"), num("STEP_X"), num("STEP_Y")


def tpg_at(grid, offx, offy, stepx, stepy, rows, cols):
    """Bilinear tie-point-grid value at IMAGE coordinates (x = col + 0.5, y = row + 0.5).
    SNAP evaluates tie-point grids at pixel-centre image coordinates, exactly as
    CrsGeoCoding.getPixelsOneByOne does; a corner-indexed lookup would be half a pixel off."""
    fx = np.clip((np.asarray(cols, float) + 0.5 - offx) / stepx, 0, grid.shape[1] - 1)
    fy = np.clip((np.asarray(rows, float) + 0.5 - offy) / stepy, 0, grid.shape[0] - 1)
    x0 = np.minimum(np.floor(fx).astype(int), grid.shape[1] - 2)
    y0 = np.minimum(np.floor(fy).astype(int), grid.shape[0] - 2)
    ax, ay = fx - x0, fy - y0
    return ((1 - ay) * ((1 - ax) * grid[y0, x0] + ax * grid[y0, x0 + 1])
            + ay * ((1 - ax) * grid[y0 + 1, x0] + ax * grid[y0 + 1, x0 + 1]))


def radar_latlon(dim_path: str, rows: np.ndarray, cols: np.ndarray):
    la = read_tpg(dim_path, "latitude")
    lo = read_tpg(dim_path, "longitude")
    return tpg_at(*la, rows, cols), tpg_at(*lo, rows, cols)


def gslc_to_radar(field_map: np.ndarray, map_dim: str, radar_dim: str,
                  r0: int, nr: int, c0: int, nc: int) -> np.ndarray:
    """Sample a complex map-grid field at the lat/lon of radar pixels rows r0..r0+nr, cols c0..c0+nc."""
    rows, cols = np.mgrid[r0:r0 + nr, c0:c0 + nc]
    lat, lon = radar_latlon(radar_dim, rows, cols)
    dx, dy, lon0, lat0 = _read_geo(map_dim)
    ri, ci = geo_to_index(lat, lon, dx, dy, lon0, lat0)
    return _bilinear_complex(field_map, ri, ci)


def _selftest() -> int:
    ok = True
    g = np.add.outer(np.arange(4) * 10.0, np.arange(5) * 1.0)       # linear in both axes
    v = tpg_at(g, 0.0, 0.0, 100.0, 200.0, np.array([199.5]), np.array([99.5]))   # image (100, 200) -> node (1, 1)
    print("tpg at node ->", float(v[0]), "(expect 11.0)")
    if abs(v[0] - 11.0) > 1e-9:
        print("  FAIL: pixel-centre convention"); ok = False
    v = tpg_at(g, 0.0, 0.0, 100.0, 200.0, np.array([-0.5]), np.array([-0.5]))    # image (0,0) -> node (0,0)
    if abs(v[0]) > 1e-9:
        print("  FAIL: origin"); ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
