"""Are the GSLC carrier model and the classical deramp model the SAME function of the ground point?

Inputs: the GSLC stack with the carrier bands (azimuthCarrierPhase_ref / _sec), the GSLC master with the
diagnostic index bands, and the classical Back-Geocoding stack written with reramp DISABLED and the model
phase output (derampDemodPhase for both legs). In that classical product
  * the master model phase sits on the master grid at integer positions;
  * the secondary model phase is the SECONDARY's model interpolated to the secondary's own fractional source
    position of each master pixel (Back-Geocoding resamples the phase with the same kernel as the data);
so each is the same physical quantity as the GSLC carrier band at the corresponding map pixel.

For every GSLC map pixel: interpolate the classical model phase (bilinear, inside the burst) to the pixel's
fractional MASTER index and compare with the GSLC carrier value:  d_ref = m_ref - phi_mst,  d_sec = m_sec - phi_slv.
Also compare the baseband LEGS: GSLC carrier-free leg vs the classical deramped leg (complex bilinear).
Everything is summed into 8-line x 30-column radar cells as unit phasors (the cell phase is the cell's mean d).

  legs2 <gslc_stack> <gslc_diag_master> <bg_deramped_stack>
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
ML_AZ, ML_RG = 8, 30
BURST = 1504


def gather_bilinear(arr, az, rg, n_rows, n_cols, burst=BURST):
    """Bilinear sample of arr[row, col] at fractional (az, rg), never across a burst boundary.
    Returns (values, valid). Works for real (phase) and complex (baseband data) arrays."""
    a0 = np.floor(az).astype(np.int64)
    r0 = np.floor(rg).astype(np.int64)
    fa = az - a0
    fr = rg - r0
    ok = (np.isfinite(az) & np.isfinite(rg) & (a0 >= 0) & (r0 >= 0) & (a0 + 1 < n_rows) & (r0 + 1 < n_cols)
          & ((a0 // burst) == ((a0 + 1) // burst)))
    a0c = np.clip(a0, 0, n_rows - 2)
    r0c = np.clip(r0, 0, n_cols - 2)
    v00, v01 = arr[a0c, r0c], arr[a0c, r0c + 1]
    v10, v11 = arr[a0c + 1, r0c], arr[a0c + 1, r0c + 1]
    val = (1 - fa) * ((1 - fr) * v00 + fr * v01) + fa * ((1 - fr) * v10 + fr * v11)
    if np.isrealobj(val):
        ok &= np.isfinite(val)
    return val, ok


def add_phasor(acc, cell, sel, ph):
    s, n = acc
    ny, nx = s.shape
    c = cell[sel]
    u = np.exp(1j * ph[sel])
    s += (np.bincount(c, weights=u.real, minlength=ny * nx) + 1j * np.bincount(c, weights=u.imag, minlength=ny * nx)).reshape(ny, nx)
    n += np.bincount(c, minlength=ny * nx).reshape(ny, nx)


def _selftest():
    ok = True
    rng = np.random.default_rng(3)
    R, C = 3008, 200                                        # two bursts
    yy, xx = np.mgrid[0:R, 0:C]
    ta = (yy % BURST) * 0.002
    phi = -np.pi * 1500.0 * (ta - 1.5) ** 2 + 0.03 * xx      # smooth quadratic in time, linear in range
    az = rng.uniform(5, R - 5, 20000)
    rg = rng.uniform(1, C - 2, 20000)
    v, valid = gather_bilinear(phi, az, rg, R, C)
    exact = -np.pi * 1500.0 * (((az % BURST) * 0.002) - 1.5) ** 2 + 0.03 * rg
    err = np.abs(v - exact)[valid]
    print(f"phase gather: valid {valid.mean():.3f}, max |error| {err.max():.4f} rad (bilinear on a smooth quadratic)")
    if not (err.max() < 0.15):
        print("  FAIL: interpolation of a smooth model phase")
        ok = False
    # samples whose 2x2 neighbourhood straddles the burst boundary must be rejected
    v2, ok2 = gather_bilinear(phi, np.array([BURST - 0.5]), np.array([10.0]), R, C)
    if ok2[0]:
        print("  FAIL: a gather across a burst boundary must be invalid")
        ok = False
    # complex baseband data
    z = rng.normal(size=(50, 60)) + 1j * rng.normal(size=(50, 60))
    vz, okz = gather_bilinear(z, np.array([10.0]), np.array([20.0]), 50, 60)
    if not (okz[0] and abs(vz[0] - z[10, 20]) < 1e-12):
        print("  FAIL: integer position must return the sample")
        ok = False
    # phasor accumulation: cell phase = mean d
    s = np.zeros((2, 2), complex)
    n = np.zeros((2, 2), np.int64)
    cell = np.array([0, 0, 3, 3])
    add_phasor((s, n), cell, np.ones(4, bool), np.array([0.2, 0.4, -1.0, -1.2]))
    if not (abs(np.angle(s[0, 0]) - 0.3) < 1e-9 and abs(np.angle(s[1, 1]) + 1.1) < 1e-9 and n[0, 0] == 2):
        print("  FAIL: phasor accumulation")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def _bands(dim):
    import gslc_equivalence as ge
    return sorted(ge._declared_bands(dim))


def _arr(dim, name):
    from radar_domain import hdr_dtype
    data = Path(dim).with_suffix(".data")
    hdr = data / f"{name}.hdr"
    t = hdr.read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    return np.memmap(data / f"{name}.img", dtype=hdr_dtype(hdr), mode="r", shape=(h, w))


def run(gslc_stack, gslc_diag, bg_stack, out_npz):
    import gslc_equivalence as ge
    from run_r4 import pick_leg
    gb, bb = _bands(gslc_stack), _bands(bg_stack)
    print("# classical deramped stack bands:", [b for b in bb][:12])
    gi_r, gq_r = pick_leg(gb, "ref")
    gi_s, gq_s = pick_leg(gb, "sec")
    bi_m, bq_m = pick_leg(bb, "ref")
    bi_s, bq_s = pick_leg(bb, "sec")
    car_r = [b for b in gb if b.startswith("azimuthCarrierPhase") and "_ref" in b][0]
    car_s = [b for b in gb if b.startswith("azimuthCarrierPhase") and "_sec" in b][0]
    ph_m = [b for b in bb if b.startswith("derampDemodPhase") and ("_ref" in b or "_mst" in b)][0]
    ph_s = [b for b in bb if b.startswith("derampDemodPhase") and ("_sec" in b or "_slv" in b)][0]
    print(f"# GSLC: {gi_r}, {gi_s}, {car_r}, {car_s}\n# classical deramped: {bi_m}, {bi_s}, {ph_m}, {ph_s}")
    PHm = np.asarray(_arr(bg_stack, ph_m), np.float64)
    PHs = np.asarray(_arr(bg_stack, ph_s), np.float64)
    Dm = np.asarray(_arr(bg_stack, bi_m), np.float32) + 1j * np.asarray(_arr(bg_stack, bq_m), np.float32)
    Ds = np.asarray(_arr(bg_stack, bi_s), np.float32) + 1j * np.asarray(_arr(bg_stack, bq_s), np.float32)
    n_rows, n_cols = PHm.shape
    A = {n: _arr(gslc_stack, n) for n in (gi_r, gq_r, gi_s, gq_s, car_r, car_s)}
    rg, az = _arr(gslc_diag, "diag_rangeIndex"), _arr(gslc_diag, "diag_azimuthIndex")
    H = rg.shape[0]
    ny, nx = n_rows // ML_AZ, n_cols // ML_RG
    mk = lambda: (np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64))
    acc = {k: mk() for k in ("d_ref", "d_sec", "d_diff", "leg_ref", "leg_sec")}
    raw = {"d_ref": [], "d_sec": []}
    for r0 in range(0, H, 256):
        r1 = min(r0 + 256, H)
        azb = np.asarray(az[r0:r1], np.float64).ravel()
        rgb = np.asarray(rg[r0:r1], np.float64).ravel()
        mr = np.asarray(A[car_r][r0:r1], np.float64).ravel()
        ms = np.asarray(A[car_s][r0:r1], np.float64).ravel()
        fr = np.asarray(A[gi_r][r0:r1], np.float64).ravel() + 1j * np.asarray(A[gq_r][r0:r1], np.float64).ravel()
        fs = np.asarray(A[gi_s][r0:r1], np.float64).ravel() + 1j * np.asarray(A[gq_s][r0:r1], np.float64).ravel()
        okc = np.isfinite(azb) & np.isfinite(rgb) & (azb >= 0) & (rgb >= 0) & (azb < ny * ML_AZ) & (rgb < nx * ML_RG)
        cell = np.full(azb.shape, -1, np.int64)
        cell[okc] = np.floor(azb[okc] / ML_AZ).astype(np.int64) * nx + np.floor(rgb[okc] / ML_RG).astype(np.int64)
        pm, ok_m = gather_bilinear(PHm, azb, rgb, n_rows, n_cols)
        ps, ok_s = gather_bilinear(PHs, azb, rgb, n_rows, n_cols)
        dref = mr - pm
        dsec = ms - ps
        selm = okc & ok_m & np.isfinite(dref) & (fr != 0)
        sels = okc & ok_s & np.isfinite(dsec) & (fs != 0)
        add_phasor(acc["d_ref"], cell, selm, dref)
        add_phasor(acc["d_sec"], cell, sels, dsec)
        add_phasor(acc["d_diff"], cell, selm & sels, dref - dsec)
        raw["d_ref"].append(dref[selm][::50])
        raw["d_sec"].append(dsec[sels][::50])
        # baseband legs: free GSLC leg vs classical deramped leg, complex bilinear at the same position
        zm, okzm = gather_bilinear(Dm, azb, rgb, n_rows, n_cols)
        zs, okzs = gather_bilinear(Ds, azb, rgb, n_rows, n_cols)
        for key, gz, cz, okz in (("leg_ref", fr, zm, okzm), ("leg_sec", fs, zs, okzs)):
            sel = okc & okz & (gz != 0) & (cz != 0)
            P = gz * np.conj(cz)
            add_phasor(acc[key], cell, sel, np.angle(P))
    np.savez(out_npz, **{k + "_s": v[0] for k, v in acc.items()}, **{k + "_n": v[1] for k, v in acc.items()})
    for k, dv in raw.items():
        d = np.concatenate(dv)
        print(f"# {k}: pixel-level difference (GSLC carrier - classical model): median {np.median(d):+.4f} rad, "
              f"mean |d| {np.mean(np.abs(d)):.4f}, max |d| {np.max(np.abs(d)):.3f} (n={len(d)} sampled)")
    per = 188
    for key, label in (("d_ref", "master carrier   d_ref"), ("d_sec", "secondary carrier d_sec"),
                       ("d_diff", "d_ref - d_sec (enters the ifg)"), ("leg_ref", "master baseband leg"),
                       ("leg_sec", "secondary baseband leg")):
        s, n = acc[key]
        med = np.median(n[n > 0]) if (n > 0).any() else 0
        v = (n >= 0.6 * med) & (np.abs(s) > 0)
        conc = np.where(n > 0, np.abs(s) / np.maximum(n, 1), 0)
        print(f"\n{label}: cells {int(v.sum())}, phasor concentration median {np.median(conc[v]):.3f}")
        for b in range(3):
            sl = slice(b * per, (b + 1) * per)
            f = np.where(v[sl], s[sl], 0)
            vv = np.abs(f) > 0
            gx, gy = ge.median_lag1_gradients(f, vv)
            u = np.exp(1j * np.angle(f[vv]))
            print(f"   burst {b + 1}: mean phase {np.angle(u.mean()):+.4f} rad, spread (rms about mean) "
                  f"{np.sqrt(np.mean(np.angle(u * np.exp(-1j * np.angle(u.mean()))) ** 2)):.4f}; "
                  f"gradient az {gy:+.5f} rg {gx:+.6f} rad/cell")


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(a) in (4, 5) and a[0] == "legs2":
        run(a[1], a[2], a[3], a[4] if len(a) > 4 else "C:/Users/luis_/AppData/Local/Temp/legs2_cells.npz")
        raise SystemExit(0)
    print(__doc__)
