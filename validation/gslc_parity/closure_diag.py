"""Coherence-stratified R3 closure diagnostic (streaming: memory stays small).

The pre-registered R3 gate is over ALL valid cells. Two of the three pairs span 6-7 days over tropical
terrain, so many cells are nearly incoherent and their closure phase is random. This stratifies the
closure phase by the WEAKEST pair coherence in each cell: a chain that is self-consistent shows a
closure that collapses as coherence rises; a convention or registration defect does not.
It does not replace the recorded gates."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                       # noqa: E402
from closure import cell_size_m, colattice_offset   # noqa: E402


def cells(dims):
    ifg = [ge.load_complex_ifg(d) for d in dims]
    coh = [ge.load_real_band(ge.find_coherence_band(d, None))[0] for d in dims]
    geos = [ge.geotransform(d) for d in dims]
    offs = [colattice_offset(geos[0], g) for g in geos]
    r0 = max(o[0] for o in offs)
    c0 = max(o[1] for o in offs)
    r1 = min(o[0] + f[3] for o, f in zip(offs, ifg))
    c1 = min(o[1] + f[2] for o, f in zip(offs, ifg))
    dx_m, dy_m = cell_size_m(geos[0])
    mc, mr = max(1, round(100.0 / dx_m)), max(1, round(100.0 / dy_m))
    ny, nx = (r1 - r0) // mr, (c1 - c0) // mc
    Z = np.zeros((3, ny, nx), complex)
    C = np.zeros((3, ny, nx))
    BLK = mr * 32
    for b0 in range(0, ny * mr, BLK):
        nrow = min(BLK, ny * mr - b0)
        for k in range(3):
            i, q, _, _ = ifg[k]
            ro, co = offs[k]
            rs = slice(r0 - ro + b0, r0 - ro + b0 + nrow)
            cs = slice(c0 - co, c0 - co + nx * mc)
            z = np.asarray(i[rs, cs], np.float64) + 1j * np.asarray(q[rs, cs], np.float64)
            c = np.asarray(coh[k][rs, cs], np.float64)
            zz = z.reshape(nrow // mr, mr, nx, mc)
            bad = (zz == 0).any(axis=(1, 3))
            Z[k, b0 // mr:(b0 + nrow) // mr] = np.where(bad, 0, zz.sum(axis=(1, 3)))
            cc = c.reshape(nrow // mr, mr, nx, mc)
            C[k, b0 // mr:(b0 + nrow) // mr] = np.where(bad, 0, cc.mean(axis=(1, 3)))
    return Z, C, (mr, mc)


def main(dims):
    Z, C, ml = cells(dims)
    print(f"# cells {Z.shape[1:]} multilook {ml}")
    names = ("AC (1 d)", "CD (6 d)", "AD (7 d)")
    valid = (np.abs(Z) > 0).all(axis=0)
    for k in range(3):
        v = np.abs(Z[k]) > 0
        print(f"pair {names[k]}: valid {v.mean():.2f}, mean coherence {C[k][v].mean():.3f}, median {np.median(C[k][v]):.3f}")
    clo = Z[0] * Z[1] * np.conj(Z[2])
    minc = C.min(axis=0)
    print(f"{'min coh >=':>10} {'cells':>9} {'rms rad':>8} {'rms centred':>11} {'|mean phasor|':>13}")
    for thr in (0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7):
        m = valid & (minc >= thr)
        if m.sum() < 50:
            print(f"{thr:10.1f} {int(m.sum()):9d}  (too few)")
            continue
        ph = np.angle(clo[m])
        mean = np.angle(np.exp(1j * ph).mean())
        cen = np.angle(np.exp(1j * (ph - mean)))
        conc = abs(clo[m].sum()) / np.abs(clo[m]).sum()
        print(f"{thr:10.1f} {int(m.sum()):9d} {np.sqrt(np.mean(ph**2)):8.3f} {np.sqrt(np.mean(cen**2)):11.3f} {conc:13.3f}")
    # sign/convention check on the well-coherent subset: wrong-sign variants must be much worse
    m = valid & (minc >= 0.5)
    if m.sum() >= 50:
        for label, cl in (("as formed  AC*CD*conj(AD)", clo), ("flip AD      AC*CD*AD", Z[0] * Z[1] * Z[2]),
                          ("flip CD      AC*conj(CD)*conj(AD)", Z[0] * np.conj(Z[1]) * np.conj(Z[2]))):
            print(f"convention check (min coh>=0.5): {label:32s} rms {np.sqrt(np.mean(np.angle(cl[m])**2)):.3f}")


if __name__ == "__main__":
    D = "E:/Output/parity/ven/"
    main([D + "clos_AC_ifg.dim", D + "clos_CD_ifg.dim", D + "clos_AD_ifg.dim"])
