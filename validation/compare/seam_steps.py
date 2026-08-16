"""Burst-seam phase-step meter for a geocoded TOPS interferogram.

ESA reports visible phase jumps between bursts. This measures them directly on the product:
within each column block (seams are TILTED in map space, so a full-width row average smears the
step across ~hundreds of rows — column blocks keep the seam locally horizontal), compute the
per-row mean lag-1 azimuth phasor of the interferogram; a burst seam appears as an isolated spike
in that profile, and the spike's angle IS the phase step across the seam. Reports every step above
threshold, per block, plus the largest step found.

    python seam_steps.py <ifg.dim> [blocks=8] [thresh_rad=0.3]
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_2x2 import hdr_info, iq

dim = Path(sys.argv[1])
nblocks = int(sys.argv[2]) if len(sys.argv) > 2 else 8
thresh = float(sys.argv[3]) if len(sys.argv) > 3 else 0.3

ib, qb = iq(dim.with_suffix(".data"))
W, L, dt = hdr_info(ib)
print(f"{dim.name}: {W} x {L}, {nblocks} column blocks, step threshold {thresh} rad")

fi = open(ib.with_suffix(".img"), "rb")
fq = open(qb.with_suffix(".img"), "rb")


def row(r, c0, nc):
    off = (r * W + c0) * dt.itemsize
    fi.seek(off)
    a = np.frombuffer(fi.read(nc * dt.itemsize), dtype=dt).astype(np.float64)
    fq.seek(off)
    b = np.frombuffer(fq.read(nc * dt.itemsize), dtype=dt).astype(np.float64)
    z = a + 1j * b
    m = np.abs(z)
    return np.where((m > 0) & np.isfinite(m), z / np.where(m > 0, m, 1), 0)


bw = W // nblocks
worst = (0.0, -1, -1)
for blk in range(nblocks):
    c0 = blk * bw
    steps = []
    prev = None
    # per-row mean lag-1 azimuth phasor within this block, subsampled 1-row lag every 2 rows
    prof = np.zeros(L, dtype=np.complex128)
    for r in range(0, L - 1, 2):
        u0 = row(r, c0, bw)
        u1 = row(r + 1, c0, bw)
        p = u1 * np.conj(u0)
        v = p[p != 0]
        if v.size > bw // 8:
            prof[r] = v.sum() / v.size
    valid = np.nonzero(np.abs(prof) > 0)[0]
    if valid.size < 100:
        print(f"block {blk} (cols {c0}-{c0+bw}): insufficient valid rows")
        continue
    ang = np.angle(prof[valid])
    mag = np.abs(prof[valid])
    med = np.median(np.abs(ang))
    hits = []
    for i, r in enumerate(valid):
        # a seam is an isolated strong step with decent coherence in the lag product
        if abs(ang[i]) > max(thresh, 6 * med) and mag[i] > 0.05:
            hits.append((int(r), float(ang[i])))
            if abs(ang[i]) > abs(worst[0]):
                worst = (float(ang[i]), int(r), blk)
    merged = []
    for r, a in hits:   # merge hits within 20 rows (one seam smeared over a few rows)
        if merged and r - merged[-1][0] <= 20:
            if abs(a) > abs(merged[-1][1]):
                merged[-1] = (r, a)
        else:
            merged.append((r, a))
    desc = "  ".join(f"row {r}: {a:+.2f} rad" for r, a in merged) if merged else "none above threshold"
    print(f"block {blk} (cols {c0}-{c0+bw}): median|dphi/dy| {med:.4f} | seam steps: {desc}")

if worst[1] >= 0:
    print(f"WORST seam step: {worst[0]:+.3f} rad at row {worst[1]} (block {worst[2]})")
else:
    print("no seam steps above threshold anywhere")
