"""Plot the interferogram phase profile across STRICTLY-detected burst seams (carrier jump >= 50
rad, ~1450-row spacing prior), for two products side by side. The eye decides: a seam defect is a
sharp step at row 0; deformation is smooth curvature through it."""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, r"E:\ESA\microwave-toolbox\validation\compare")
from render_2x2 import hdr_info, iq

stack = Path(sys.argv[1])
ifgA, ifgB = Path(sys.argv[2]), Path(sys.argv[3])
out = Path(sys.argv[4])

carr = next((stack.with_suffix(".data")).glob("azimuthCarrierPhase*ref*.hdr"))
CW, CL, CDT = hdr_info(carr)
fc = open(carr.with_suffix(".img"), "rb")

SW = 512
HALF = 100


def carrier_profile(x0):
    prof = np.empty(CL)
    for r in range(CL):
        fc.seek((r * CW + x0 + SW // 2 - 16) * CDT.itemsize)
        v = np.frombuffer(fc.read(32 * CDT.itemsize), dtype=CDT)
        v = v[v != 0]
        prof[r] = np.median(v) if v.size else np.nan
    return prof


def strict_seams(x0):
    p = carrier_profile(x0)
    d = np.abs(np.diff(p))
    d[~np.isfinite(d)] = 0
    seams = []
    for i in np.argsort(d)[::-1]:
        if d[i] < 50:
            break
        if all(abs(i - s) > 700 for s in seams):
            seams.append(int(i))
    return sorted(seams), p


def phase_profile(ifg, x0, seam):
    ib, qb = iq(ifg.with_suffix(".data"))
    W, L, dt = hdr_info(ib)
    fi = open(ib.with_suffix(".img"), "rb")
    fq = open(qb.with_suffix(".img"), "rb")
    rows = range(max(0, seam - HALF), min(L, seam + HALF))
    prof = np.full(2 * HALF, np.nan, dtype=np.complex128)
    for k, r in enumerate(rows):
        off = (r * W + x0) * dt.itemsize
        fi.seek(off)
        a = np.frombuffer(fi.read(SW * dt.itemsize), dtype=dt).astype(np.float64)
        fq.seek(off)
        b = np.frombuffer(fq.read(SW * dt.itemsize), dtype=dt).astype(np.float64)
        z = a + 1j * b
        m = np.abs(z)
        u = np.where(m > 0, z / np.where(m > 0, m, 1), 0)
        n = int((m > 0).sum())
        if n > SW // 4:
            prof[k] = u.sum() / n
    return prof


x0s = [12310, 20517, 28724, 36931]
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

rowsel = []
for x0 in x0s:
    seams, _ = strict_seams(x0)
    mid = [s for s in seams if 3000 < s < 12000]
    if mid:
        rowsel.append((x0, mid[len(mid) // 2]))
print("selected seam crossings:", rowsel)

fig, axes = plt.subplots(len(rowsel), 2, figsize=(14, 3 * len(rowsel)), dpi=130, squeeze=False)
for i, (x0, seam) in enumerate(rowsel):
    for j, (ifg, name) in enumerate([(ifgA, "carrier-diff ifg"), (ifgB, "presented ifg")]):
        p = phase_profile(ifg, x0, seam)
        ph = np.unwrap(np.angle(np.where(np.isnan(p.real), 1, p)))
        y = np.arange(-HALF, HALF)
        ax = axes[i][j]
        ax.plot(y, ph, lw=0.9)
        ax.axvline(0, color="red", lw=0.8, ls="--")
        ax.set_title(f"{name} — col {x0}, seam row {seam}", fontsize=9)
        ax.grid(alpha=0.3)
        ax.set_xlabel("rows from seam")
fig.tight_layout()
fig.savefig(out)
print("wrote", out)
