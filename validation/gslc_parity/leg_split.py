"""Per-leg cross-chain split in PHASE (the spec's R4 purpose): which LEG carries the smooth surface?

For every GSLC map pixel the GSLC records the SOURCE (azimuth, range) index it was read from
(diag_azimuthIndex / diag_rangeIndex). Each GSLC leg is restored to the natural SLC phase by multiplying by
exp(+j * azimuthCarrierPhase) (the carrier-free leg had the deramp/demod model removed), and compared pixel
by pixel with the classical leg for the same ground pixel: the classical master band, and the classical
secondary band resampled onto the master grid. The pair products
        P_ref = restored_ref * conj(classical_mst)          P_sec = restored_sec * conj(classical_slv)
are summed into radar cells. If the GSLC and classical chains handle a leg identically its cell phase is ~0.
Model (annotation DC/FM) errors cancel identically in a restored leg, so a surface that survives here cannot
be a model error - it is a sampling / registration / bookkeeping difference in that leg.

  leg <gslc_stack> <gslc_diag_master> <classical_stack>
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
ML_AZ, ML_RG = 8, 30


def cell_index(az, rg, n_rows, n_cols, ml_az=ML_AZ, ml_rg=ML_RG):
    ny, nx = n_rows // ml_az, n_cols // ml_rg
    ok = np.isfinite(az) & np.isfinite(rg) & (az >= 0) & (rg >= 0) & (az < ny * ml_az) & (rg < nx * ml_rg)
    cell = np.full(az.shape, -1, np.int64)
    cell[ok] = np.floor(az[ok] / ml_az).astype(np.int64) * nx + np.floor(rg[ok] / ml_rg).astype(np.int64)
    return cell, ok, (ny, nx)


def accumulate(P, cell, ok, shape, acc_sum, acc_abs, acc_n):
    ny, nx = shape
    sel = ok & (P != 0) & np.isfinite(P.real) & np.isfinite(P.imag)
    c = cell[sel]
    p = P[sel]
    acc_sum += (np.bincount(c, weights=p.real, minlength=ny * nx) + 1j * np.bincount(c, weights=p.imag, minlength=ny * nx)).reshape(ny, nx)
    acc_abs += np.bincount(c, weights=np.abs(p), minlength=ny * nx).reshape(ny, nx)
    acc_n += np.bincount(c, minlength=ny * nx).reshape(ny, nx)


def leg_cells(ref_z, sec_z, m_ref, m_sec, az, rg, C_mst, C_slv, sign, n_rows, n_cols, accs=None, d_az=0, d_rg=0):
    """One block of map pixels -> updates the per-leg cell accumulators. Returns the accumulators.
    d_az / d_rg shift the CLASSICAL gather by whole lines / pixels (used to scan for a registration offset)."""
    cell, ok, shape = cell_index(az, rg, n_rows, n_cols)
    if accs is None:
        z = lambda: np.zeros(shape, complex)
        f = lambda: np.zeros(shape)
        i = lambda: np.zeros(shape, np.int64)
        accs = {"ref": [z(), f(), i()], "sec": [z(), f(), i()]}
    ai = np.clip(np.rint(np.where(ok, az, 0)).astype(np.int64) + d_az, 0, n_rows - 1)
    ri = np.clip(np.rint(np.where(ok, rg, 0)).astype(np.int64) + d_rg, 0, n_cols - 1)
    r_ref = ref_z * np.exp(1j * sign * m_ref)
    r_sec = sec_z * np.exp(1j * sign * m_sec)
    accumulate(r_ref * np.conj(C_mst[ai, ri]), cell, ok, shape, *accs["ref"])
    accumulate(r_sec * np.conj(C_slv[ai, ri]), cell, ok, shape, *accs["sec"])
    return accs


def summarise(acc, label, per=188):
    s, a, n = acc
    conc = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    valid = (n >= 0.6 * np.median(n[n > 0])) & (conc > 0)
    print(f"  {label}: cells {int(valid.sum())}, amplitude-weighted concentration median {np.median(conc[valid]):.3f}")
    return s, valid


def selftest():
    ok = True
    rng = np.random.default_rng(9)
    H, W = 128, 240                                  # 16 x 8 cells
    Cm = rng.normal(size=(H, W)) + 1j * rng.normal(size=(H, W))
    Cs = rng.normal(size=(H, W)) + 1j * rng.normal(size=(H, W))
    yy, xx = np.mgrid[0:H, 0:W]
    az, rg = yy.astype(float), xx.astype(float)
    m_ref = 1.5 * yy + 0.05 * xx                      # realistic carrier: radians per line, as in a TOPS burst
    m_sec = 1.4 * yy - 0.06 * xx
    delta = 0.02 * yy + 0.003 * xx                   # extra smooth phase in the SEC leg only
    for sign in (+1, -1):
        ref_z = Cm * np.exp(-1j * sign * m_ref)      # carrier-free legs
        sec_z = Cs * np.exp(-1j * sign * m_sec) * np.exp(1j * delta)
        accs = leg_cells(ref_z.ravel(), sec_z.ravel(), m_ref.ravel(), m_sec.ravel(), az.ravel(), rg.ravel(), Cm, Cs, sign, H, W)
        s_ref, v_ref = summarise(accs["ref"], f"sign {sign:+d} ref")
        s_sec, v_sec = summarise(accs["sec"], f"sign {sign:+d} sec")
        d_ref = np.angle(s_ref)[v_ref]
        # expected cell phase of sec = mean of delta over the cell (cell centre, smooth) -> compare at centres
        cy, cx = np.mgrid[0:H // ML_AZ, 0:W // ML_RG]
        exp_sec = 0.02 * (cy * ML_AZ + (ML_AZ - 1) / 2) + 0.003 * (cx * ML_RG + (ML_RG - 1) / 2)
        err = np.abs(np.angle(np.exp(1j * (np.angle(s_sec) - exp_sec))))[v_sec]
        print(f"     ref-leg |phase| max {np.abs(d_ref).max():.2e}; sec-leg recovered surface max error {err.max():.3f} rad")
        if not (np.abs(d_ref).max() < 1e-9 and err.max() < 0.05):
            print("  FAIL: leg split must isolate the sec-only surface")
            ok = False
    # wrong sign must NOT look identical: restore with the opposite sign on the master leg
    ref_z = Cm * np.exp(-1j * m_ref)
    accs = leg_cells(ref_z.ravel(), (Cs * np.exp(-1j * m_sec)).ravel(), m_ref.ravel(), m_sec.ravel(), az.ravel(), rg.ravel(), Cm, Cs, -1, H, W)
    s, a, n = accs["ref"]
    conc = float((np.abs(s).sum()) / a.sum())
    print(f"     wrong-sign restore -> master concentration {conc:.3f}")
    if conc > 0.5:
        print("  FAIL: wrong carrier sign must be visible")
        ok = False
    # scan convention: a leg that equals the classical one read 2 lines further on (G(i) = C(i + 2))
    # must peak at d_az = +2 and be much worse at 0
    sec_z = np.roll(Cs, -2, axis=0) * np.exp(-1j * m_sec)
    conc_by_shift = {}
    for d in range(-4, 5):
        acc = leg_cells(np.zeros(H * W, complex) + 1, sec_z.ravel(), m_ref.ravel(), m_sec.ravel(), az.ravel(), rg.ravel(),
                        Cm, Cs, +1, H, W, None, d_az=d)
        s_, a_, n_ = acc["sec"]
        conc_by_shift[d] = float(np.abs(s_).sum() / a_.sum())
    best = max(conc_by_shift, key=conc_by_shift.get)
    print(f"     scan: best shift {best:+d} (conc {conc_by_shift[best]:.2f}); at 0: {conc_by_shift[0]:.2f}")
    if not (best == 2 and conc_by_shift[best] > 0.9 and conc_by_shift[0] < 0.3):
        print("  FAIL: the registration scan must recover a 2-line offset with the documented sign")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def _bands(dim):
    import gslc_equivalence as ge
    return sorted(ge._declared_bands(dim))


def _arr(dim, name):
    from radar_domain import hdr_dtype
    import re
    data = Path(dim).with_suffix(".data")
    hdr = data / f"{name}.hdr"
    t = hdr.read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    return np.memmap(data / f"{name}.img", dtype=hdr_dtype(hdr), mode="r", shape=(h, w))


def run(gslc_stack, gslc_diag, classical_stack, out_npz):
    import gslc_equivalence as ge
    from run_r4 import pick_leg
    gb, cb = _bands(gslc_stack), _bands(classical_stack)
    gi_r, gq_r = pick_leg(gb, "ref")
    gi_s, gq_s = pick_leg(gb, "sec")
    ci_m, cq_m = pick_leg(cb, "ref")
    ci_s, cq_s = pick_leg(cb, "sec")
    car_r = [b for b in gb if b.startswith("azimuthCarrierPhase") and "_ref" in b][0]
    car_s = [b for b in gb if b.startswith("azimuthCarrierPhase") and "_sec" in b][0]
    print(f"# GSLC legs {gi_r} / {gi_s}; carriers {car_r} / {car_s}")
    print(f"# classical legs {ci_m} / {ci_s}")
    C_m = (np.asarray(_arr(classical_stack, ci_m), np.float32) + 1j * np.asarray(_arr(classical_stack, cq_m), np.float32)).astype(np.complex64)
    C_s = (np.asarray(_arr(classical_stack, ci_s), np.float32) + 1j * np.asarray(_arr(classical_stack, cq_s), np.float32)).astype(np.complex64)
    n_rows, n_cols = C_m.shape
    A = {n: _arr(gslc_stack, n) for n in (gi_r, gq_r, gi_s, gq_s, car_r, car_s)}
    rg, az = _arr(gslc_diag, "diag_rangeIndex"), _arr(gslc_diag, "diag_azimuthIndex")
    H = rg.shape[0]

    def block(r0, r1):
        ref = (np.asarray(A[gi_r][r0:r1], np.float64) + 1j * np.asarray(A[gq_r][r0:r1], np.float64)).ravel()
        sec = (np.asarray(A[gi_s][r0:r1], np.float64) + 1j * np.asarray(A[gq_s][r0:r1], np.float64)).ravel()
        return (ref, sec, np.asarray(A[car_r][r0:r1], np.float64).ravel(), np.asarray(A[car_s][r0:r1], np.float64).ravel(),
                np.asarray(az[r0:r1], np.float64).ravel(), np.asarray(rg[r0:r1], np.float64).ravel())

    # carrier sign: the right one makes the MASTER leg agree with the classical master (concentration ~ high)
    mid = H // 2
    best = None
    for sign in (+1, -1):
        accs = leg_cells(*block(mid, mid + 128), C_m, C_s, sign, n_rows, n_cols)
        s_, a_, n_ = accs["ref"]
        conc = float(np.abs(s_).sum() / max(a_.sum(), 1e-30))
        print(f"# carrier sign {sign:+d}: master-leg concentration {conc:.3f}")
        if best is None or conc > best[1]:
            best = (sign, conc)
    sign = best[0]
    print(f"# using sign {sign:+d}")
    accs = None
    for r0 in range(0, H, 256):
        accs = leg_cells(*block(r0, min(r0 + 256, H)), C_m, C_s, sign, n_rows, n_cols, accs)
    s_ref, v_ref = summarise(accs["ref"], "MASTER leg (GSLC restored vs classical mst)")
    s_sec, v_sec = summarise(accs["sec"], "SECONDARY leg (GSLC restored vs classical slv)")
    np.savez(out_npz, s_ref=accs["ref"][0], a_ref=accs["ref"][1], n_ref=accs["ref"][2],
             s_sec=accs["sec"][0], a_sec=accs["sec"][1], n_sec=accs["sec"][2])
    per = 188
    print()
    print("per-burst phase structure of each leg (cell-level lag-1 gradients, rad per 8-line / 30-column cell):")
    for name, s, v in (("master", s_ref, v_ref), ("secondary", s_sec, v_sec)):
        for b in range(3):
            sl = slice(b * per, (b + 1) * per)
            f = np.where(v[sl], s[sl], 0)
            vv = np.abs(f) > 0
            gx, gy = ge.median_lag1_gradients(f, vv)
            fc = ge.remove_plane(f, gx, gy)
            u = np.exp(1j * np.angle(f[vv]))
            uc = np.exp(1j * np.angle(fc[vv]))
            print(f"  {name:9s} burst {b + 1}: gradients az {gy:+.3f} rg {gx:+.4f} | phase-only conc raw {abs(u.mean()):.3f}, "
                  f"after its plane {abs(uc.mean()):.3f}")
    # registration scan: shift the classical gather by whole azimuth lines / range pixels and watch the cell
    # concentration of each leg. A leg sampled at the same positions by both chains peaks at shift 0.
    print()
    print("registration scan (cell-level concentration of each leg vs an integer shift of the classical gather)")
    band_rows = [(int(0.15 * H), int(0.15 * H) + 256), (int(0.45 * H), int(0.45 * H) + 256), (int(0.75 * H), int(0.75 * H) + 256)]
    for axis, shifts in (("azimuth lines", range(-6, 7)), ("range pixels", range(-3, 4))):
        table = {"ref": [], "sec": []}
        for sh in shifts:
            acc = None
            for r0, r1 in band_rows:
                acc = leg_cells(*block(r0, r1), C_m, C_s, sign, n_rows, n_cols, acc,
                                d_az=sh if axis.startswith("az") else 0, d_rg=sh if axis.startswith("range") else 0)
            for leg in ("ref", "sec"):
                s_, a_, n_ = acc[leg]
                table[leg].append(float(np.abs(s_).sum() / max(a_.sum(), 1e-30)))
        print(f"  shift ({axis}): " + " ".join(f"{sh:+d}" for sh in shifts))
        for leg, nm in (("ref", "master   "), ("sec", "secondary")):
            v = np.array(table[leg])
            k = int(np.argmax(v))
            pk = list(shifts)[k]
            if 0 < k < len(v) - 1:
                d = v[k - 1] - 2 * v[k] + v[k + 1]
                pk = pk + (0.5 * (v[k - 1] - v[k + 1]) / d if d != 0 else 0)
            print(f"    {nm}: " + " ".join(f"{x:.2f}" for x in v) + f"   -> peak at {pk:+.2f}")
    D = np.where(v_ref & v_sec, s_ref * np.conj(s_sec), 0)
    print()
    print("check: Delta_master - Delta_secondary vs the saved GSLC-minus-classical interferogram surface (ramp OFF):")
    try:
        z = np.load("C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_off.npz")
        Dsaved = np.where((np.abs(z["G"]) > 0) & (np.abs(z["T"]) > 0), z["G"] * np.conj(z["T"]), 0)
        m = (np.abs(D) > 0) & (np.abs(Dsaved) > 0)
        agree = np.exp(1j * (np.angle(D[m]) - np.angle(Dsaved[m])))
        print(f"  cells {int(m.sum())}: phase-only concentration of (leg-split D) * conj(saved D) = {abs(agree.mean()):.3f} "
              f"(1.0 = the leg split reproduces the interferogram-level difference)")
    except Exception as e:
        print("  saved cells not available:", e)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--selftest"]:
        raise SystemExit(selftest())
    a = sys.argv[1:]
    if len(a) == 4 and a[0] == "leg":
        run(a[1], a[2], a[3], a[4] if len(a) > 4 else "C:/Users/luis_/AppData/Local/Temp/leg_split_cells.npz")
        raise SystemExit(0)
    if len(a) == 5 and a[0] == "leg":
        run(a[1], a[2], a[3], a[4])
        raise SystemExit(0)
    print(__doc__)
