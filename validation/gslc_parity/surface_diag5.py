"""Estimate the per-burst smooth surface from the GSLC (ramp-ON) interferogram ALONE, by unwrapped-phase
fits per burst, and score the corrected GSLC against the classical chain (the classical data are used only
for scoring). Answers: would per-burst range terms in the GSLC ramp estimator help, and do they absorb
genuine signal?

  A  shared range terms (u, u^2) across the bursts + per-burst azimuth terms (v, v^2) + per-burst constants
  B  per-burst quadratic (u, v, u^2, v^2, uv)
  C  per-burst cubic
  U  upper bound: per-burst cubic fitted to the unwrapped GSLC-minus-classical difference (uses T)
  T  the classical interferogram's OWN per-burst cubic (how large is the real large-scale signal?)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from surface_diag2 import B, PER, eval_poly, fit_poly, unwrap_burst   # noqa: E402

CELLS = "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_on.npz"


def blocks(F):
    ny, nx = (F.shape[0] // B) * B, (F.shape[1] // B) * B
    blk = F[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    return s, q, n


def surface_per_burst(F, deg, shared_range=False):
    """Per-burst polynomial surfaces (evaluated at every cell) fitted to the unwrapped phase of F."""
    s, q, n = blocks(F)
    edges = [0, int(round(PER / B)), int(round(2 * PER / B)), s.shape[0]]
    W = F.shape[1]
    surf = np.zeros(F.shape)
    data = []
    for b in range(3):
        rows = slice(edges[b], edges[b + 1])
        U, ok = unwrap_burst(s, q, n, rows)
        iy, ix = np.where(ok)
        data.append(((iy + 0.5) / (edges[b + 1] - edges[b]) - 0.5, (ix + 0.5) / s.shape[1] - 0.5, U[ok], q[rows][ok] ** 2))
    if shared_range:
        # unknowns: shared (u, u^2), per burst (v, v^2, const)
        rowsA, yA, wA = [], [], []
        for b, (v, u, val, w) in enumerate(data):
            A = np.zeros((len(val), 2 + 3 * 3))
            A[:, 0], A[:, 1] = u, u ** 2
            A[:, 2 + 3 * b], A[:, 3 + 3 * b], A[:, 4 + 3 * b] = v, v ** 2, 1.0
            rowsA.append(A)
            yA.append(val)
            wA.append(w)
        A, y, w = np.vstack(rowsA), np.concatenate(yA), np.concatenate(wA)
        coef, *_ = np.linalg.lstsq(A * np.sqrt(w)[:, None], y * np.sqrt(w), rcond=None)
        for b in range(3):
            r0, r1 = b * PER, (b + 1) * PER
            V, Uu = np.meshgrid((np.arange(PER) + 0.5) / PER - 0.5, (np.arange(W) + 0.5) / W - 0.5, indexing="ij")
            surf[r0:r1] = coef[0] * Uu + coef[1] * Uu ** 2 + coef[2 + 3 * b] * V + coef[3 + 3 * b] * V ** 2
        return surf
    for b, (v, u, val, w) in enumerate(data):
        coef, _ = fit_poly(u, v, val, w, deg)
        r0, r1 = b * PER, (b + 1) * PER
        V, Uu = np.meshgrid((np.arange(PER) + 0.5) / PER - 0.5, (np.arange(W) + 0.5) / W - 0.5, indexing="ij")
        surf[r0:r1] = eval_poly(coef, Uu.ravel(), V.ravel(), deg).reshape(PER, W)
    return surf


def score(G, T, label):
    out, allu = [], []
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        d = G[sl] * np.conj(T[sl])
        v = (np.abs(G[sl]) > 0) & (np.abs(T[sl]) > 0)
        u = np.exp(1j * np.angle(d[v]))
        m = u.mean()
        out.append(abs(m))
        allu.append(u * np.exp(-1j * np.angle(m)))
    a = np.concatenate(allu)
    rms = np.sqrt(np.mean(np.angle(a * np.exp(-1j * np.angle(a.mean()))) ** 2))
    print(f"   {label:62s} conc per burst {out[0]:.3f} {out[1]:.3f} {out[2]:.3f} | all (constants aligned) {abs(a.mean()):.3f}, rms {rms:.2f} rad")
    return abs(a.mean()), rms


def main():
    z = np.load(CELLS)
    G, T = z["G"], z["T"]
    print(f"# ramp-ON cells {G.shape}")
    score(G, T, "no further correction (per-burst constants free)")
    res = {}
    for name, kw in (("A  shared range terms + per-burst azimuth  (estimator's structure)", dict(deg=2, shared_range=True)),
                     ("B1 per-burst plane", dict(deg=1)), ("B2 per-burst quadratic", dict(deg=2)), ("C  per-burst cubic", dict(deg=3))):
        surf = surface_per_burst(G, **kw)
        res[name[:2]] = score(G * np.exp(-1j * surf), T, name + " [from GSLC alone]")
    # absorbed signal: how much of the CLASSICAL interferogram's own phase would the same per-burst cubic remove?
    surfT = surface_per_burst(T, 3)
    print(f"   classical's own per-burst cubic: std of the removed surface {np.std(surfT):.1f} rad (the GSLC-alone cubic above removes "
          f"{np.std(surface_per_burst(G, 3)):.1f} rad)")
    d = G * np.conj(T)
    surfU = surface_per_burst(d, 3)
    score(G * np.exp(-1j * surfU), T, "U  upper bound: cubic fitted to the difference with T")
    # what is the correlation between the GSLC-alone surface and the true difference surface?
    surfC = surface_per_burst(G, 3)
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        a_, b_ = surfC[sl].ravel(), surfU[sl].ravel()
        print(f"   burst {b + 1}: GSLC-alone cubic surface vs difference-with-T cubic surface: corr {np.corrcoef(a_, b_)[0, 1]:+.3f}, "
              f"rms of (alone - difference) {np.sqrt(np.mean(((a_ - a_.mean()) - (b_ - b_.mean())) ** 2)):.2f} rad")


if __name__ == "__main__":
    main()
