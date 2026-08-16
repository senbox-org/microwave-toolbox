"""Burst-seam phase steps measured AT THE TRUE SEAM LOCATIONS.

The blind spike meter (seam_steps.py) cannot tell a burst seam from a steep coherent fringe
system — on a coseismic scene it flags the earthquake. This version locates the seams exactly:
the GSLC's azimuthCarrierPhase band is piecewise per burst, so along any column its row-derivative
spikes precisely at the seams. The interferogram's phase step is then measured across those exact
rows (complex means over [-45,-10] vs [+10,+45] row bands), per column strip, per seam.

    python seam_steps_guided.py <stack_with_carrier.dim> <ifg.dim> [<ifg2.dim> ...]
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_2x2 import hdr_info, iq

stack = Path(sys.argv[1])
ifgs = [Path(p) for p in sys.argv[2:]]

carr = None
for h in (stack.with_suffix(".data")).glob("azimuthCarrierPhase*ref*.hdr"):
    carr = h
    break
assert carr is not None, "no reference azimuthCarrierPhase band in the stack"
CW, CL, CDT = hdr_info(carr)
print(f"carrier band: {carr.stem}  {CW} x {CL}")

STRIPS = 6
SW = 512          # strip width for carrier seam detection and ifg step measurement
GAP, BAND = 10, 35  # measure mean phase over rows [seam-GAP-BAND, seam-GAP] vs [seam+GAP, seam+GAP+BAND]

fc = open(carr.with_suffix(".img"), "rb")


def carrier_col_strip(x0):
    out = np.empty((CL, SW))
    for r in range(CL):
        fc.seek((r * CW + x0) * CDT.itemsize)
        out[r] = np.frombuffer(fc.read(SW * CDT.itemsize), dtype=CDT)
    return out


def seams_for_strip(x0):
    # median over a NARROW 64-col core: the seam tilts ~0.2 row/col, so a wide strip smears the
    # carrier jump over ~100 rows and the derivative spikes multiple times per seam
    c = carrier_col_strip(x0)[:, SW // 2 - 32: SW // 2 + 32]
    prof = np.nanmedian(np.where(c != 0, c, np.nan), axis=1)
    d = np.abs(np.diff(prof))
    d[~np.isfinite(d)] = 0
    idx = np.argsort(d)[::-1]
    seams = []
    for i in idx:
        if d[i] < 5.0:   # carrier jumps at seams are tens-to-hundreds of rad; within-burst < 1
            break
        if all(abs(i - s) > 400 for s in seams):   # one seam per burst interval (~1450 rows)
            seams.append(int(i))
        if len(seams) >= 10:
            break
    return sorted(seams)


def ifg_step(ifg, x0, seam):
    """Seam DISCONTINUITY, local-trend removed: unwrap the per-row mean-phasor phase profile on
    both sides, fit a line to each side, extrapolate both fits to the seam row and difference
    them. A smooth fringe gradient (however steep) extrapolates identically from both sides and
    cancels; only a genuine jump survives. Result wrapped to (-pi, pi]."""
    ib, qb = iq(ifg.with_suffix(".data"))
    W, L, dt = hdr_info(ib)
    fi = open(ib.with_suffix(".img"), "rb")
    fq = open(qb.with_suffix(".img"), "rb")

    HALF, GAP2 = 70, 8
    r0, r1 = seam - HALF, seam + HALF
    if r0 < 0 or r1 >= L:
        return None
    prof = np.zeros(2 * HALF, dtype=np.complex128)
    for k, r in enumerate(range(r0, r1)):
        off = (r * W + x0) * dt.itemsize
        fi.seek(off)
        a = np.frombuffer(fi.read(SW * dt.itemsize), dtype=dt).astype(np.float64)
        fq.seek(off)
        b = np.frombuffer(fq.read(SW * dt.itemsize), dtype=dt).astype(np.float64)
        z = a + 1j * b
        m = np.abs(z)
        u = np.where(m > 0, z / np.where(m > 0, m, 1), 0)
        good = int((m > 0).sum())
        prof[k] = u.sum() / good if good > SW // 4 else np.nan
    if np.isnan(prof.real).sum() > HALF // 2:
        return None
    ph = np.unwrap(np.angle(np.where(np.isnan(prof.real), 1, prof)))
    y = np.arange(2 * HALF) - HALF   # 0 = seam row
    left = slice(0, HALF - GAP2)
    right = slice(HALF + GAP2, 2 * HALF)
    try:
        cl = np.polyfit(y[left], ph[left], 1)
        cr = np.polyfit(y[right], ph[right], 1)
    except Exception:
        return None
    step = np.polyval(cr, 0.0) - np.polyval(cl, 0.0)
    return float(np.angle(np.exp(1j * step)))


print("locating seams from the carrier band...")
strip_x = [int((CW - SW) * (k + 0.5) / STRIPS) for k in range(STRIPS)]
strip_seams = {x0: seams_for_strip(x0) for x0 in strip_x}
for x0, s in strip_seams.items():
    print(f"  strip @col {x0}: seams at rows {s}")

for ifg in ifgs:
    print(f"=== {ifg.name} ===")
    worst = 0.0
    all_steps = []
    for x0 in strip_x:
        parts = []
        for seam in strip_seams[x0]:
            st = ifg_step(ifg, x0, seam)
            if st is None:
                continue
            all_steps.append(abs(st))
            if abs(st) > abs(worst):
                worst = st
            parts.append(f"r{seam}:{st:+.2f}")
        print(f"  col {x0}: " + "  ".join(parts))
    if all_steps:
        print(f"  SEAM STEPS: worst {worst:+.3f} rad, median |step| {np.median(all_steps):.3f} rad, "
              f"n={len(all_steps)}")
