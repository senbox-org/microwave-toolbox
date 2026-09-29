"""Would a per-burst RANGE model in the GSLC ramp estimator close the gap to the classical chain?

Emulates the estimator on the saved ramp-OFF cells, using ONLY the GSLC interferogram (lag-1 phase gradients,
robust weighted least squares, curl-free by construction), then measures the agreement with the classical
chain. The classical data are used for scoring, never for estimation.

  A  estimator as implemented: range terms (u, u^2) SHARED by all bursts, azimuth terms (v, v^2) per burst
  B  per-burst quadratic incl. cross term: u, u^2, v, v^2, uv  for each burst
  C  per-burst cubic
  U  upper bound: per-burst cubic fitted to the phase difference with the classical chain (uses T)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
PER = 188
CELLS = "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_off.npz"


def mono(deg, total=True, drop_const=True):
    ms = [(i, j) for i in range(deg + 1) for j in range(deg + 1) if (i + j <= deg if total else True)]
    return [m for m in ms if not (drop_const and m == (0, 0))]


def grad_rows(G, mons, W, Hb):
    """Design rows (per burst) for the lag-1 phase gradients of the wrapped field G (rad per cell)."""
    v = np.abs(G) > 0
    # range gradient: between columns j and j+1 ; azimuth gradient: between rows i and i+1
    gx = np.angle(G[:, 1:] * np.conj(G[:, :-1]))
    vx = v[:, 1:] & v[:, :-1]
    wx = np.minimum(np.abs(G[:, 1:]), np.abs(G[:, :-1]))
    gy = np.angle(G[1:, :] * np.conj(G[:-1, :]))
    vy = v[1:, :] & v[:-1, :]
    wy = np.minimum(np.abs(G[1:, :]), np.abs(G[:-1, :]))
    rows, cols = G.shape
    jx = (np.arange(cols - 1) + 1.0) / W - 0.5
    ix = (np.arange(rows) + 0.5) / Hb - 0.5
    U, V = np.meshgrid(jx, ix)                              # range gradient at (i, j+1/2)
    jy = (np.arange(cols) + 0.5) / W - 0.5
    iy = (np.arange(rows - 1) + 1.0) / Hb - 0.5
    U2, V2 = np.meshgrid(jy, iy)                            # azimuth gradient at (i+1/2, j)
    dx = np.stack([(i * U ** max(i - 1, 0) * V ** j if i > 0 else 0 * U) / W for (i, j) in mons], axis=-1)
    dy = np.stack([(j * U2 ** i * V2 ** max(j - 1, 0) if j > 0 else 0 * U2) / Hb for (i, j) in mons], axis=-1)
    return (dx[vx], gx[vx], wx[vx]), (dy[vy], gy[vy], wy[vy])


def robust_ls(A, y, w, passes=3, clip=1.0):
    keep = np.ones(len(y), bool)
    for _ in range(passes):
        sw = np.sqrt(w[keep])
        coef, *_ = np.linalg.lstsq(A[keep] * sw[:, None], y[keep] * sw, rcond=None)
        res = y - A @ coef
        keep = np.abs(res) < clip
    return coef


def phase_surface(coef, mons, shape, W, Hb):
    rows, cols = shape
    u = (np.arange(cols) + 0.5) / W - 0.5
    v = (np.arange(rows) + 0.5) / Hb - 0.5
    U, V = np.meshgrid(u, v)
    return sum(c * U ** i * V ** j for c, (i, j) in zip(coef, mons))


def score(G, T, label):
    """Per-burst and overall phase-only concentration of G*conj(T); per-burst constants are free."""
    out = []
    allu = []
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        d = G[sl] * np.conj(T[sl])
        v = (np.abs(G[sl]) > 0) & (np.abs(T[sl]) > 0)
        u = np.exp(1j * np.angle(d[v]))
        m = u.mean()
        out.append(abs(m))
        allu.append(u * np.exp(-1j * np.angle(m)))             # align the burst's constant offset
    a = np.concatenate(allu)
    rms = np.sqrt(np.mean(np.angle(a * np.exp(-1j * np.angle(a.mean()))) ** 2))
    print(f"   {label:58s} conc per burst {out[0]:.3f} {out[1]:.3f} {out[2]:.3f} | all bursts (constants aligned) {abs(a.mean()):.3f}, rms {rms:.2f} rad")
    return out


def main():
    z = np.load(CELLS)
    G, T = z["G"], z["T"]
    W = G.shape[1]
    print(f"# ramp-OFF cells {G.shape}: {G.shape[0] // PER} bursts of {PER} cells x {W}")
    score(G, T, "no correction (per-burst constants free)")

    # ---- A: shared range terms (u, u^2), per-burst azimuth terms (v, v^2)
    ma_rg = [(1, 0), (2, 0)]
    ma_az = [(0, 1), (0, 2)]
    nA = 2 + 3 * 2
    rowsA, yA, wA = [], [], []
    for b in range(3):
        Gb = G[b * PER:(b + 1) * PER]
        (dx, gx, wx), (dy, gy, wy) = grad_rows(Gb, ma_rg + ma_az, W, PER)
        for D_, g_, w_ in ((dx, gx, wx), (dy, gy, wy)):
            A = np.zeros((len(g_), nA))
            A[:, 0:2] = D_[:, 0:2]                                # shared range terms
            A[:, 2 + 2 * b:4 + 2 * b] = D_[:, 2:4]                # this burst's azimuth terms
            rowsA.append(A)
            yA.append(g_)
            wA.append(w_)
    cA = robust_ls(np.vstack(rowsA), np.concatenate(yA), np.concatenate(wA))
    GA = G.copy()
    for b in range(3):
        coef = np.concatenate([cA[0:2], cA[2 + 2 * b:4 + 2 * b]])
        GA[b * PER:(b + 1) * PER] = G[b * PER:(b + 1) * PER] * np.exp(-1j * phase_surface(coef, ma_rg + ma_az, (PER, W), W, PER))
    print(f"   A coefficients: shared range (u,u^2) = {cA[0]:+.1f} {cA[1]:+.1f} rad; azimuth per burst (v,v^2) = "
          f"{cA[2]:+.1f} {cA[3]:+.1f} | {cA[4]:+.1f} {cA[5]:+.1f} | {cA[6]:+.1f} {cA[7]:+.1f}")
    score(GA, T, "A  shared range terms + per-burst azimuth (as implemented)")

    # ---- B, C: independent per-burst polynomials
    for name, mons in (("B  per-burst quadratic incl. cross term", mono(2)), ("C  per-burst cubic", mono(3))):
        Gc = G.copy()
        for b in range(3):
            Gb = G[b * PER:(b + 1) * PER]
            (dx, gx, wx), (dy, gy, wy) = grad_rows(Gb, mons, W, PER)
            coef = robust_ls(np.vstack([dx, dy]), np.concatenate([gx, gy]), np.concatenate([wx, wy]))
            Gc[b * PER:(b + 1) * PER] = Gb * np.exp(-1j * phase_surface(coef, mons, (PER, W), W, PER))
            if b == 0:
                print(f"   {name[:1]} burst-1 coefficients: " + " ".join(f"{m}:{c:+.1f}" for m, c in zip(mons, coef)))
        score(Gc, T, name)

    # ---- U: upper bound using the classical chain (per-burst cubic on the wrapped difference, by gradients)
    mons = mono(3)
    Gu = G.copy()
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        Db = G[sl] * np.conj(T[sl])
        (dx, gx, wx), (dy, gy, wy) = grad_rows(Db, mons, W, PER)
        coef = robust_ls(np.vstack([dx, dy]), np.concatenate([gx, gy]), np.concatenate([wx, wy]))
        Gu[sl] = G[sl] * np.exp(-1j * phase_surface(coef, mons, (PER, W), W, PER))
    score(Gu, T, "U  upper bound: per-burst cubic fitted TO THE DIFFERENCE with T")


if __name__ == "__main__":
    main()
