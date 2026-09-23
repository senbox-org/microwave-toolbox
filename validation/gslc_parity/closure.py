"""R3 phase closure over three pairwise interferograms on co-lattice grids."""
from __future__ import annotations

import sys

import numpy as np


def colattice_offset(geo_a, geo_b, tol_px: float = 0.02) -> tuple[int, int]:
    """Integer (row, col) offset of grid B's origin relative to grid A's, from
    (dx, dy, lon0, lat0) tuples. Refuses a fractional remainder above tol_px: resampling
    would inject exactly the interpolation error the closure is meant to expose."""
    dxa, dya, lon_a, lat_a = geo_a
    dxb, dyb, lon_b, lat_b = geo_b
    if abs(dxa - dxb) > 1e-9 * abs(dxa) or abs(dya - dyb) > 1e-9 * abs(dya):
        raise ValueError(f"step mismatch: ({dxa},{dya}) vs ({dxb},{dyb})")
    oc = (lon_b - lon_a) / dxa
    orow = (lat_a - lat_b) / dya
    if max(abs(oc - round(oc)), abs(orow - round(orow))) > tol_px:
        raise ValueError(f"grids are not co-lattice: offset ({orow:.4f}, {oc:.4f}) px")
    return int(round(orow)), int(round(oc))


def cell_size_m(geo) -> tuple[float, float]:
    """(east, north) cell size in metres from a (dx, dy, x0, y0) tuple. A geographic grid (steps in
    degrees, which are always < 0.1) is converted at the grid's own latitude; a projected grid is
    already in metres. The GSLC pair grids are WGS84(DD): the CreateStack auto path locks the secondary
    in degrees and cannot lock it to a projected master."""
    import math
    dx, dy, _x0, y0 = geo
    if abs(dx) < 0.1:
        return abs(dx) * 111320.0 * math.cos(math.radians(y0)), abs(dy) * 110574.0
    return abs(dx), abs(dy)


def crop_to_common(fields, geos):
    """Crop complex fields to their common window; fields[0] is the reference lattice."""
    offs = [colattice_offset(geos[0], g) for g in geos]        # (row, col) of each origin in ref px
    r0 = max(o[0] for o in offs)
    c0 = max(o[1] for o in offs)
    r1 = min(o[0] + f.shape[0] for o, f in zip(offs, fields))
    c1 = min(o[1] + f.shape[1] for o, f in zip(offs, fields))
    if r1 <= r0 or c1 <= c0:
        raise ValueError("no common window")
    return [f[r0 - o[0]:r1 - o[0], c0 - o[1]:c1 - o[1]] for o, f in zip(offs, fields)]


def multilook(z: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    """Complex block sum; a block containing any zero (no-data) sample is set to 0."""
    h = (z.shape[0] // ml_az) * ml_az
    w = (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    s = zz.sum(axis=(1, 3))
    bad = (zz == 0).any(axis=(1, 3))
    return np.where(bad, 0, s)


def closure_stats(z_ab: np.ndarray, z_bc: np.ndarray, z_ac: np.ndarray,
                  coh_min_amp: float = 0.0) -> dict:
    """Closure phasor z_ab * z_bc * conj(z_ac) (all defined first*conj(second)).
    Returns RMS about zero, RMS about the mean, the mean, and the per-row-band means."""
    c = z_ab * z_bc * np.conj(z_ac)
    valid = (np.abs(c) > coh_min_amp) & np.isfinite(c.real) & (z_ab != 0) & (z_bc != 0) & (z_ac != 0)
    if not valid.any():
        return {"n": 0, "rms_rad": float("nan"), "rms_centred_rad": float("nan"),
                "mean_rad": float("nan"), "band_means_rad": []}
    ph = np.angle(c[valid])
    mean = float(np.angle(np.exp(1j * ph).mean()))
    centred = np.angle(np.exp(1j * (ph - mean)))
    rows = np.where(valid.any(axis=1))[0]
    bands = np.array_split(rows, 6)
    bm = []
    for b in bands:
        m = valid[b[0]:b[-1] + 1]
        if m.any():
            bm.append(float(np.angle(c[b[0]:b[-1] + 1][m].sum())))
    return {"n": int(valid.sum()), "rms_rad": float(np.sqrt(np.mean(ph ** 2))),
            "rms_centred_rad": float(np.sqrt(np.mean(centred ** 2))),
            "mean_rad": mean, "band_means_rad": bm}


def run(dim_ac: str, dim_cd: str, dim_ad: str) -> int:
    """R3 on three GSLC interferograms, each formed first*conj(second): AC, CD, AD. Closure is
    z_AC * z_CD * conj(z_AD). All three sit on the standard grid, so they share a lattice up to an
    integer offset; anything else is refused rather than resampled."""
    import sys as _s
    from pathlib import Path as _P
    _s.path.insert(0, str(_P(__file__).resolve().parents[1]))
    import gslc_equivalence as ge
    from budget import record
    fields, geos = [], []
    for d in (dim_ac, dim_cd, dim_ad):
        i, q, w, h = ge.load_complex_ifg(d)
        fields.append(np.asarray(i[:], np.float64) + 1j * np.asarray(q[:], np.float64))
        geos.append(ge.geotransform(d))
    ac, cd, ad = crop_to_common(fields, geos)
    dx_m, dy_m = cell_size_m(geos[0])
    ml_col, ml_row = max(1, round(100.0 / dx_m)), max(1, round(100.0 / dy_m))
    print(f"# common window {ac.shape}, multilook {ml_row} rows x {ml_col} cols (~100 m)")
    s = closure_stats(multilook(ac, ml_row, ml_col), multilook(cd, ml_row, ml_col), multilook(ad, ml_row, ml_col))
    print(s)
    prov = "PROVISIONAL: chosen before measurement, no prior data"
    worst_band = max((abs(b) for b in s["band_means_rad"]), default=float("nan"))
    ok = True
    for name, val, thr in (("closure-rms-rad", s["rms_rad"], 0.30), ("closure-worst-band-mean-rad", worst_band, 0.10)):
        passed = bool(val <= thr)
        ge.emit_gate(name, passed, val, thr)
        record("R3", "venezuela", name, val, thr, prov, passed, "ETAD-off on all three legs (no S1D ETAD exists)")
        ok &= passed
    record("R3", "venezuela", "closure-rms-centred-rad", s["rms_centred_rad"], None, "measured", None)
    record("R3", "venezuela", "closure-mean-rad", s["mean_rad"], None, "measured", None)
    return 0 if ok else 1


def _selftest() -> int:
    rng = np.random.default_rng(5)
    ok = True
    H, W = 240, 320
    a = np.exp(1j * rng.uniform(-np.pi, np.pi, (H, W)))
    c = np.exp(1j * rng.uniform(-np.pi, np.pi, (H, W)))
    d = np.exp(1j * rng.uniform(-np.pi, np.pi, (H, W)))
    # perfectly consistent triple -> closure exactly zero
    s = closure_stats(a * np.conj(c), c * np.conj(d), a * np.conj(d))
    print(f"consistent triple: rms {s['rms_rad']:.2e}")
    if not s["rms_rad"] < 1e-6:
        print("  FAIL: consistent triple must close"); ok = False
    # inject a 0.4 rad constant into one pair -> mean 0.4, centred rms ~0
    s = closure_stats(a * np.conj(c) * np.exp(0.4j), c * np.conj(d), a * np.conj(d))
    print(f"0.4 rad offset: mean {s['mean_rad']:.3f} centred {s['rms_centred_rad']:.2e} raw {s['rms_rad']:.3f}")
    if not (abs(s["mean_rad"] - 0.4) < 1e-6 and s["rms_centred_rad"] < 1e-6 and abs(s["rms_rad"] - 0.4) < 1e-6):
        print("  FAIL: constant offset must show in raw rms and mean, not centred"); ok = False
    # a wrong-sign pair (conj) must NOT close -> catches a convention mismatch
    s = closure_stats(np.conj(a * np.conj(c)), c * np.conj(d), a * np.conj(d))
    print(f"wrong-sign pair: rms {s['rms_rad']:.3f}")
    if s["rms_rad"] < 0.5:
        print("  FAIL: sign mismatch must be visible"); ok = False
    # zero (no-data) blocks are excluded
    z = a * np.conj(c)
    z[:20] = 0
    s = closure_stats(z, c * np.conj(d), a * np.conj(d))
    if s["n"] != (H - 20) * W:
        print(f"  FAIL: no-data excluded, n={s['n']}"); ok = False
    # lattice offsets
    g = (1e-4, 1e-4, -69.0, 11.0)
    if colattice_offset(g, (1e-4, 1e-4, -69.0 + 5e-4, 11.0 - 3e-4)) != (3, 5):
        print("  FAIL: integer offset"); ok = False
    try:
        colattice_offset(g, (1e-4, 1e-4, -69.0 + 5.5e-4, 11.0))
        print("  FAIL: fractional offset must raise"); ok = False
    except ValueError:
        print("fractional offset rejected")
    f = crop_to_common([np.ones((10, 12)), np.ones((10, 12))], [g, (1e-4, 1e-4, -69.0 + 2e-4, 11.0 - 1e-4)])
    if f[0].shape != (9, 10) or f[1].shape != (9, 10):
        print(f"  FAIL: crop shapes {f[0].shape} {f[1].shape}"); ok = False
    ex, ny = cell_size_m((2.1333673657863983e-5, 1.2664129273964875e-4, -69.0, 10.44))
    if not (abs(ex - 2.34) < 0.03 and abs(ny - 14.0) < 0.15) or cell_size_m((2.35, 14.0, 5e5, 1.1e6)) != (2.35, 14.0):
        print(f"  FAIL: cell_size_m {ex:.3f} x {ny:.3f}")
        ok = False
    m = multilook(np.ones((8, 12), complex), 4, 6)
    if m.shape != (2, 2) or abs(m[0, 0] - 24) > 1e-9:
        print("  FAIL: multilook"); ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if len(sys.argv) == 5 and sys.argv[1] == "run":
        raise SystemExit(run(sys.argv[2], sys.argv[3], sys.argv[4]))
    print("usage: closure.py run <ifg_AC.dim> <ifg_CD.dim> <ifg_AD.dim>  |  --selftest")
    raise SystemExit(2)
