"""Does the per-burst smooth GSLC - classical surface track terrain? (companion to surface_diag2.py)

For each burst (unwrapped on its own), fit  U = poly(range, azimuth; degree d) + k * height  and report k,
the implied perpendicular-baseline mismatch and how much the residual falls when height is allowed.
Heights are block averages of the staged Copernicus DEM (3x3 samples per 8x8-cell block) at lat/lon taken
from the classical product's tie-point grids - good enough for km-scale relief."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from radar_domain import radar_latlon, read_tpg, tpg_at     # noqa: E402
from surface_diag2 import B, ML_AZ, ML_RG, PER, unwrap_burst  # noqa: E402

RADAR = "E:/Output/parity/ven/ven_trad_ifg_burst.dim"
DEM = "E:/Output/parity/ven/../../../TestData/dem/copernicus30_venezuela_orbit106.tif"
DEM = "E:/TestData/dem/copernicus30_venezuela_orbit106.tif"
WAVELENGTH = 0.05546576


def block_heights(shape):
    import rasterio
    ny, nx = shape
    hs = np.full((ny, nx), np.nan)
    offs = np.array([-0.3, 0.0, 0.3])
    with rasterio.open(DEM) as ds:
        rr, cc = np.meshgrid(np.arange(ny), np.arange(nx), indexing="ij")
        acc = np.zeros((ny, nx))
        cnt = np.zeros((ny, nx))
        for oy in offs:
            for ox in offs:
                rows = ((rr + 0.5 + oy) * B) * ML_AZ
                cols = ((cc + 0.5 + ox) * B) * ML_RG
                lat, lon = radar_latlon(RADAR, rows, cols)
                v = np.array([p[0] for p in ds.sample(list(zip(lon.ravel(), lat.ravel())))], float).reshape(ny, nx)
                ok = v > -100
                acc += np.where(ok, v, 0)
                cnt += ok
        hs = np.where(cnt > 0, acc / np.maximum(cnt, 1), np.nan)
    return hs


def design(x, y, deg, extra=None):
    cols = [(x ** i) * (y ** j) for i in range(deg + 1) for j in range(deg + 1 - i)]
    if extra is not None:
        cols.append(extra)
    return np.stack(cols, axis=1)


def wls(A, u, w):
    sw = np.sqrt(w)
    coef, *_ = np.linalg.lstsq(A * sw[:, None], u * sw, rcond=None)
    res = u - A @ coef
    return coef, np.sqrt(np.average(res ** 2, weights=w)), res


def main(path):
    z = np.load(path)
    G, T = z["G"], z["T"]
    valid = (np.abs(G) > 0) & (np.abs(T) > 0)
    D = np.where(valid, G * np.conj(T), 0)
    ny, nx = (D.shape[0] // B) * B, (D.shape[1] // B) * B
    blk = D[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    H = block_heights(s.shape)
    edges = [0, int(round(PER / B)), int(round(2 * PER / B)), s.shape[0]]
    print(f"# DEM block heights {np.nanmin(H):.0f}..{np.nanmax(H):.0f} m (std {np.nanstd(H):.0f} m)")
    # sensitivity of the topographic phase to a baseline error: dphi/dh per metre of B_perp
    for b in range(3):
        rows = slice(edges[b], edges[b + 1])
        U, ok = unwrap_burst(s, q, n, rows)
        iy, ix = np.where(ok)
        h = H[rows][ok]
        good = np.isfinite(h)
        iy, ix, h, u = iy[good], ix[good], h[good], U[ok][good]
        w = q[rows][ok][good] ** 2
        yy = (iy + 0.5) / (edges[b + 1] - edges[b])
        xx = (ix + 0.5) / s.shape[1]
        rr = np.arange(len(u))
        R = tpg_at(*read_tpg(RADAR, "slant_range_time"), (edges[b] + iy + 0.5) * B * ML_AZ, (ix + 0.5) * B * ML_RG) * 1e-9 * 299792458.0 / 2
        th = np.radians(tpg_at(*read_tpg(RADAR, "incident_angle"), (edges[b] + iy + 0.5) * B * ML_AZ, (ix + 0.5) * B * ML_RG))
        sens = 4 * np.pi / (WAVELENGTH * R * np.sin(th))             # rad per metre of height per metre of B_perp
        print(f"\nburst {b + 1}: {len(u)} blocks, surface std {np.std(u):.1f} rad, height std {np.std(h):.0f} m")
        print(f"   topographic sensitivity {sens.mean():.5f} rad per metre of height per metre of B_perp (1 m of baseline error over 400 m of relief = {sens.mean() * 400:.2f} rad)")
        for deg in (1, 2, 3):
            c0, r0, _ = wls(design(xx, yy, deg), u, w)
            c1, r1, _ = wls(design(xx, yy, deg, h), u, w)
            k = c1[-1]
            dB = k / sens.mean()
            # uncertainty of k from the weighted LS covariance (assumes independent residuals; blocks are not, so optimistic)
            A = design(xx, yy, deg, h) * np.sqrt(w)[:, None]
            cov = np.linalg.inv(A.T @ A) * (r1 ** 2)
            se = np.sqrt(cov[-1, -1])
            print(f"   degree {deg}: residual {r0:.2f} -> {r1:.2f} rad with height; k = {k * 1000:+.3f} +/- {se * 1000:.3f} rad per 1000 m "
                  f"-> implied perpendicular-baseline mismatch {dB:+.2f} m (t = {k / se:+.1f}, optimistic)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_on.npz")
