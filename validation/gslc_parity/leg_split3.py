"""Where does the smooth surface enter: in the raw interferogram, or in the reference-phase removal?

  raw_G  = restored GSLC ref * conj(restored GSLC sec) per map pixel, summed into radar cells
           (restored = carrier-free leg * exp(-j * carrier model): the classical Back-Geocoding reramp convention)
  raw_T  = classical reramped master * conj(classical reramped slave), block-summed over the same cells
  G, T   = the finished interferograms (flat-earth + topographic phase removed) from the saved R5b cells

  raw_G vs raw_T  -> do the chains agree BEFORE any reference phase is removed?
  ref_G = raw_G * conj(G),  ref_T = raw_T * conj(T)  -> the reference phase each chain removed, per cell.
The GSLC-minus-classical surface is then (ref_T - ref_G) plus the raw disagreement.

  raw <gslc_stack> <gslc_diag_master> <classical_reramped_stack> <cells_npz>
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from leg_split2 import _arr, _bands, ML_AZ, ML_RG                # noqa: E402


def block_sum(z, ml_az, ml_rg):
    h, w = (z.shape[0] // ml_az) * ml_az, (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    bad = (zz == 0).any(axis=(1, 3))
    return np.where(bad, 0, zz.sum(axis=(1, 3))), np.where(bad, 0, ml_az * ml_rg)


def plane_stats(F, label, per=188):
    import gslc_equivalence as ge
    print(f"\n{label}")
    for b in range(3):
        sl = slice(b * per, (b + 1) * per)
        f = np.where(np.abs(F[sl]) > 0, F[sl], 0)
        v = np.abs(f) > 0
        if v.sum() < 500:
            print(f"   burst {b + 1}: too few cells")
            continue
        gx, gy = ge.median_lag1_gradients(f, v)
        fc = ge.remove_plane(f, gx, gy)
        u = np.exp(1j * np.angle(f[v]))
        uc = np.exp(1j * np.angle(fc[v]))
        print(f"   burst {b + 1}: cells {int(v.sum()):6d} | lag-1 gradients az {gy:+.4f} rg {gx:+.5f} rad/cell | phase-only concentration raw {abs(u.mean()):.3f}, after its plane {abs(uc.mean()):.3f}")


def main(gslc_stack, gslc_diag, cl_stack, cells_npz, out_npz):
    from run_r4 import pick_leg
    gb, cb = _bands(gslc_stack), _bands(cl_stack)
    gi_r, gq_r = pick_leg(gb, "ref")
    gi_s, gq_s = pick_leg(gb, "sec")
    ci_m, cq_m = pick_leg(cb, "ref")
    ci_s, cq_s = pick_leg(cb, "sec")
    car_r = [b for b in gb if b.startswith("azimuthCarrierPhase") and "_ref" in b][0]
    car_s = [b for b in gb if b.startswith("azimuthCarrierPhase") and "_sec" in b][0]
    Cm = np.asarray(_arr(cl_stack, ci_m), np.float32) + 1j * np.asarray(_arr(cl_stack, cq_m), np.float32)
    Cs = np.asarray(_arr(cl_stack, ci_s), np.float32) + 1j * np.asarray(_arr(cl_stack, cq_s), np.float32)
    n_rows, n_cols = Cm.shape
    rawT, _ = block_sum((Cm * np.conj(Cs)).astype(np.complex64), ML_AZ, ML_RG)
    ny, nx = n_rows // ML_AZ, n_cols // ML_RG
    A = {n: _arr(gslc_stack, n) for n in (gi_r, gq_r, gi_s, gq_s, car_r, car_s)}
    rg, az = _arr(gslc_diag, "diag_rangeIndex"), _arr(gslc_diag, "diag_azimuthIndex")
    H = rg.shape[0]
    res = {}
    for name, sgn in (("exp(-j(m_ref - m_sec))", -1), ("exp(+j(m_ref - m_sec))", +1)):
        res[name] = [np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64), sgn]
    for r0 in range(0, H, 256):
        r1 = min(r0 + 256, H)
        azb = np.asarray(az[r0:r1], np.float64).ravel()
        rgb = np.asarray(rg[r0:r1], np.float64).ravel()
        fr = np.asarray(A[gi_r][r0:r1], np.float64).ravel() + 1j * np.asarray(A[gq_r][r0:r1], np.float64).ravel()
        fs = np.asarray(A[gi_s][r0:r1], np.float64).ravel() + 1j * np.asarray(A[gq_s][r0:r1], np.float64).ravel()
        mr = np.asarray(A[car_r][r0:r1], np.float64).ravel()
        ms = np.asarray(A[car_s][r0:r1], np.float64).ravel()
        ok = np.isfinite(azb) & np.isfinite(rgb) & (azb >= 0) & (rgb >= 0) & (azb < ny * ML_AZ) & (rgb < nx * ML_RG) & (fr != 0) & (fs != 0)
        cell = np.floor(azb[ok] / ML_AZ).astype(np.int64) * nx + np.floor(rgb[ok] / ML_RG).astype(np.int64)
        base = fr[ok] * np.conj(fs[ok])
        dm = (mr - ms)[ok]
        for name, (S, N, sgn) in res.items():
            P = base * np.exp(1j * sgn * dm)
            S += (np.bincount(cell, weights=P.real, minlength=ny * nx) + 1j * np.bincount(cell, weights=P.imag, minlength=ny * nx)).reshape(ny, nx)
            N += np.bincount(cell, minlength=ny * nx).reshape(ny, nx)
    z = np.load(cells_npz)
    G, T = z["G"], z["T"]
    # ---- the proposed fix: swap the sign of the carrier-difference add-back in the finished GSLC interferogram
    Sm, Nm, _ = res["exp(-j(m_ref - m_sec))"]
    Sp, Np, _ = res["exp(+j(m_ref - m_sec))"]
    med = np.median(Nm[Nm > 0])
    fill0 = (Nm >= 0.6 * med) & (np.abs(rawT) > 0) & (np.abs(G) > 0) & (np.abs(T) > 0)
    # G_saved carries the operator's own add-back; ref(+1) = raw(+1)*conj(G) is the reference phase it removed IF the
    # operator's sign is +1. Correct sign: raw(-1) with that same reference phase removed.
    G_fix = np.where(fill0, (Sm / np.maximum(Nm, 1)) * np.conj((Sp / np.maximum(Np, 1)) * np.conj(G)), 0)
    Tn = np.where(fill0, T, 0)
    print()
    print("================ PROPOSED FIX: operator's carrier-difference add-back with the sign swapped ================")
    for label, F in (("finished GSLC interferogram as produced (ramp OFF)", np.where(fill0, G, 0)),
                     ("GSLC interferogram with the add-back sign swapped", G_fix)):
        u = np.exp(1j * np.angle(F[fill0] * np.conj(Tn[fill0])))
        print(f"{label}: phase-only concentration of F * conj(classical) over {int(fill0.sum())} cells = {abs(u.mean()):.3f}")
        plane_stats(np.where(fill0, F * np.conj(Tn), 0), "   per burst (F * conj(classical)):")
    import gslc_equivalence as ge
    for label, F in (("as produced", np.where(fill0, G, 0)), ("sign swapped", G_fix)):
        m = {}
        import contextlib, io
        with contextlib.redirect_stdout(io.StringIO()):
            ge.compute_gates(F, Tn, None, None, metrics_out=m)
        print(f"   shared R5b metrics, {label}: conc {m['phase-residual-conc']:.3f}, rms {m['residual-rms-rad']:.3f} rad, gx ratio {m['gx-median-ratio']:.2f}, gy ratio {m['gy-median-ratio']:.2f}")
    np.savez(out_npz.replace('.npz', '_fix.npz'), G_fix=G_fix, G=np.where(fill0, G, 0), T=Tn)
    for name, (S, N, sgn) in res.items():
        med = np.median(N[N > 0])
        fill = (N >= 0.6 * med) & (np.abs(rawT) > 0)
        rawG = np.where(fill, S / np.maximum(N, 1), 0)
        rT = np.where(fill, rawT / (ML_AZ * ML_RG), 0)
        both = fill & (np.abs(G) > 0) & (np.abs(T) > 0)
        Draw = np.where(fill, rawG * np.conj(rT), 0)
        print(f"\n================ carrier add-back {name} ================")
        u = np.exp(1j * np.angle(Draw[fill]))
        print(f"raw_G vs raw_T over {int(fill.sum())} cells: phase-only concentration {abs(u.mean()):.3f}")
        plane_stats(Draw, "raw_G * conj(raw_T): the disagreement BEFORE any reference phase is removed")
        refG = np.where(both, rawG * np.conj(G), 0)
        refT = np.where(both, rT * np.conj(T), 0)
        plane_stats(refG, "reference phase removed by the GSLC chain:  raw_G * conj(G)")
        plane_stats(refT, "reference phase removed by the classical chain: raw_T * conj(T)")
        plane_stats(np.where(both, refG * np.conj(refT), 0), "difference of the two reference phases:  ref_G * conj(ref_T)")
        if sgn == -1:
            np.savez(out_npz, rawG=rawG, rawT=rT, refG=refG, refT=refT, fill=fill, both=both)


if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) == 4 and a[0] == "raw":
        pass
    if len(a) >= 5 and a[0] == "raw":
        main(a[1], a[2], a[3], a[4], a[5] if len(a) > 5 else "C:/Users/luis_/AppData/Local/Temp/rawsplit_cells.npz")
        raise SystemExit(0)
    print(__doc__)
