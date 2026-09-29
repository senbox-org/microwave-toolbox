"""Find the smooth phase surface between the GSLC and classical interferograms (R5b cells).

Input: the binned cells saved by the R5b diagnostics (G = GSLC mean, T = classical mean, on 8-line x 30-col
radar cells of the classical burst-geometry grid). D = G * conj(T) is the phase difference between the chains.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                               # noqa: E402
from radar_domain import read_tpg, tpg_at, radar_latlon     # noqa: E402

CELLS = sys.argv[1] if len(sys.argv) > 1 else "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_on.npz"
RADAR = "E:/Output/parity/ven/ven_trad_ifg_burst.dim"
DEM = "E:/TestData/dem/copernicus30_venezuela_orbit106.tif"
ML_AZ, ML_RG = 8, 30
B = 8                                  # coarse block: 8x8 cells ~ 0.9 km x 0.8 km
WAVELENGTH = 0.05546576                # C-band, metres


def coarse(D, B):
    ny, nx = (D.shape[0] // B) * B, (D.shape[1] // B) * B
    blk = D[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    return s, q, n


def polyfit2d(x, y, z, w, degx, degy, total=None):
    cols, names = [], []
    for i in range(degx + 1):
        for j in range(degy + 1):
            if total is not None and i + j > total:
                continue
            cols.append((x ** i) * (y ** j))
            names.append((i, j))
    A = np.stack(cols, axis=1)
    sw = np.sqrt(w)
    coef, *_ = np.linalg.lstsq(A * sw[:, None], z * sw, rcond=None)
    return coef, names, A @ coef


def main():
    z = np.load(CELLS)
    G, T = z["G"], z["T"]
    valid = (np.abs(G) > 0) & (np.abs(T) > 0)
    D = np.where(valid, G * np.conj(T), 0)
    s, q, n = coarse(D, B)
    ok = (q > 0.45) & (n > 0.6 * B * B)
    print(f"# coarse grid {s.shape}; usable blocks {int(ok.sum())} ({ok.mean():.0%}); mean quality {q[ok].mean():.2f}")
    from skimage.restoration import unwrap_phase
    ph = np.angle(np.where(ok, s, 1.0))
    U = unwrap_phase(np.ma.masked_array(ph, mask=~ok))
    U = np.ma.filled(U, np.nan)
    ok &= np.isfinite(U)
    U -= np.nanmedian(U[ok])
    print(f"# unwrapped surface: range {np.nanmin(U[ok]):+.1f} .. {np.nanmax(U[ok]):+.1f} rad, std {np.nanstd(U[ok]):.2f} rad")

    iy, ix = np.where(ok)
    y = (iy + 0.5) / s.shape[0]            # azimuth 0..1 over the 3 bursts
    x = (ix + 0.5) / s.shape[1]            # range 0..1
    u = U[ok]
    w = q[ok] ** 2
    tot = np.sum(w * (u - np.average(u, weights=w)) ** 2)

    def r2(pred):
        return 1 - np.sum(w * (u - pred) ** 2) / tot

    print("\n-- how much of the unwrapped surface is explained by")
    for name, (dx, dy, t) in {"range only, degree 1": (1, 0, None), "range only, degree 2": (2, 0, None),
                              "range only, degree 4": (4, 0, None), "azimuth only, degree 2": (0, 2, None),
                              "plane (range+azimuth)": (1, 1, 1), "quadratic 2-D": (2, 2, 2),
                              "cubic 2-D": (3, 3, 3), "quartic 2-D": (4, 4, 4)}.items():
        c, nm, pred = polyfit2d(x, y, u, w, dx, dy, t)
        res = u - pred
        print(f"   {name:26s} R2 {r2(pred):6.3f}   residual rms {np.sqrt(np.average(res ** 2, weights=w)):5.2f} rad")

    # per-burst range polynomials (bursts are 1/3 of the rows each)
    print("\n-- range-polynomial (degree 2) fitted separately per burst:")
    burst = np.minimum((y * 3).astype(int), 2)
    pred_all = np.zeros_like(u)
    for b in range(3):
        m = burst == b
        c, nm, p = polyfit2d(x[m], y[m], u[m], w[m], 2, 0)
        pred_all[m] = p
        print(f"   burst {b + 1}: coef (const, range, range^2) = {c[0]:+.2f} {c[1]:+.2f} {c[2]:+.2f} rad; n {int(m.sum())}")
    print(f"   per-burst range-quadratic: R2 {r2(pred_all):.3f}")

    # smooth 2-D low-order surface and what is left
    c, nm, pred = polyfit2d(x, y, u, w, 3, 3, 3)
    res = u - pred
    print(f"\n-- after a cubic 2-D surface the residual is {np.sqrt(np.average(res ** 2, weights=w)):.2f} rad rms "
          f"(vs {np.sqrt(np.average((u - np.average(u, weights=w)) ** 2, weights=w)):.2f} rad before)")

    # terrain and geometry at the block centres
    rows = (iy * B + B / 2) * ML_AZ
    cols = (ix * B + B / 2) * ML_RG
    lat, lon = radar_latlon(RADAR, rows, cols)
    import rasterio
    with rasterio.open(DEM) as ds:
        h = np.array([v[0] for v in ds.sample(list(zip(lon, lat)))], float)
    h[h < -100] = np.nan
    la, *_ = read_tpg(RADAR, "latitude")
    inc = tpg_at(*read_tpg(RADAR, "incident_angle"), rows, cols)
    srt = tpg_at(*read_tpg(RADAR, "slant_range_time"), rows, cols)      # ns, two-way
    R = srt * 1e-9 * 299792458.0 / 2
    okh = np.isfinite(h)
    print(f"\n-- terrain: DEM height at the blocks {np.nanmin(h):.0f}..{np.nanmax(h):.0f} m, std {np.nanstd(h):.0f} m; "
          f"incidence {inc.min():.1f}..{inc.max():.1f} deg; slant range {R.min() / 1e3:.0f}..{R.max() / 1e3:.0f} km")
    # regress the RESIDUAL of a low-order surface (so a smooth range/azimuth trend is not mistaken for terrain)
    for label, deg in (("after removing a plane", (1, 1, 1)), ("after removing a quadratic", (2, 2, 2)),
                       ("after removing a cubic", (3, 3, 3))):
        c, nm, pred = polyfit2d(x, y, u, w, *deg)
        res = u - pred
        m = okh
        # remove the same-order surface from the height too, so both are 'local' quantities
        ch, _, ph_ = polyfit2d(x[m], y[m], h[m], w[m], *deg)
        hl = h[m] - ph_
        k = np.sum(w[m] * hl * res[m]) / np.sum(w[m] * hl * hl)                  # rad per metre
        rr = np.corrcoef(hl, res[m])[0, 1]
        th = np.radians(inc[m]).mean()
        Rm = R[m].mean()
        dB = k * WAVELENGTH * Rm * np.sin(th) / (4 * np.pi)
        print(f"   {label:28s}: corr(local height, residual) {rr:+.3f}; slope {k * 1000:+.3f} rad per 1000 m of height "
              f"-> implied perpendicular-baseline mismatch {dB:+.1f} m")
    # raw correlation with height and with range
    print(f"   raw corr(height, unwrapped surface) {np.corrcoef(h[okh], u[okh])[0, 1]:+.3f}; corr(range x, surface) {np.corrcoef(x, u)[0, 1]:+.3f}; corr(azimuth y, surface) {np.corrcoef(y, u)[0, 1]:+.3f}")

    # save arrays and a figure for the morning
    outd = Path("E:/Output/parity/figures")
    outd.mkdir(parents=True, exist_ok=True)
    np.savez(outd / "surface_diag.npz", U=U, ok=ok, q=q)
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(1, 3, figsize=(15, 5.2), dpi=110)
        Um = np.ma.masked_array(U, mask=~np.isfinite(U))
        im = ax[0].imshow(Um, cmap="RdBu_r", aspect=B * ML_AZ / (B * ML_RG) * 0 + 0.55)
        ax[0].set_title("unwrapped GSLC - classical phase (rad)\nblocks of 8x8 cells (~0.9 x 0.8 km)")
        plt.colorbar(im, ax=ax[0], fraction=0.046)
        R2map = np.full(U.shape, np.nan)
        R2map[iy, ix] = res
        im = ax[1].imshow(np.ma.masked_invalid(R2map), cmap="RdBu_r", vmin=-2, vmax=2, aspect=0.55)
        ax[1].set_title("residual after a cubic 2-D surface (rad)")
        plt.colorbar(im, ax=ax[1], fraction=0.046)
        Hm = np.full(U.shape, np.nan)
        Hm[iy, ix] = h
        im = ax[2].imshow(np.ma.masked_invalid(Hm), cmap="terrain", aspect=0.55)
        ax[2].set_title("DEM height at the block centres (m)")
        plt.colorbar(im, ax=ax[2], fraction=0.046)
        for a in ax:
            a.set_xlabel("range block ->")
            a.set_ylabel("azimuth block (3 bursts)")
        fig.tight_layout()
        fig.savefig(outd / "surface_diag.png")
        print("\nfigure:", outd / "surface_diag.png")
    except Exception as e:
        print("figure skipped:", e)


if __name__ == "__main__":
    main()
