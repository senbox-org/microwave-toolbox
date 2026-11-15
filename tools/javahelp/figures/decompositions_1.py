"""decompositions_1: the Cloude-Pottier H-alpha plane.

Feasible-region boundaries (Cloude & Pottier 1997), computed from diagonal coherency matrices:
  Curve I   (lower):  T = diag(1, m, m),     0 <= m <= 1    -> from (H, alpha) = (0, 0) to (1, 60 deg)
  Curve II  (upper):  T = diag(0, 1, 2m),    0 <= m <= 0.5  -> alpha = 90 deg, H from 0 to log3(2)
  Curve III (upper):  T = diag(2m - 1, 1, 1), 0.5 <= m <= 1 -> from (log3(2), 90 deg) to (1, 60 deg)
For a diagonal T the eigenvectors are the Pauli unit vectors: the first has alpha = 0,
the other two alpha = 90 deg. Mean alpha = sum p_i alpha_i; H = -sum p_i log3 p_i.

Zone boundaries are the ones SNAP uses (HaAlphaDescriptor: H = 0.5 and 0.9;
alpha = 48/42 deg (H <= 0.5), 50/40 deg (0.5 < H <= 0.9), 55/40 deg (H > 0.9)). Zone numbers
follow SNAP's default numbering (zone 1 = low entropy, high alpha).
"""
import numpy as np

import figstyle

C = figstyle.COLORS


def h_alpha(eigs, alphas_deg):
    eigs = np.asarray(eigs, dtype=float)
    p = eigs / eigs.sum()
    nz = p > 0
    H = -np.sum(p[nz] * np.log(p[nz])) / np.log(3)
    a = np.sum(p * np.asarray(alphas_deg))
    return H, a


alph = [0.0, 90.0, 90.0]
m = np.linspace(0, 1, 400)
c1 = np.array([h_alpha([1, mm, mm], alph) for mm in m])
m2 = np.linspace(0, 0.5, 200)
c2 = np.array([h_alpha([0, 1, 2 * mm], alph) for mm in m2])
m3 = np.linspace(0.5, 1, 200)
c3 = np.array([h_alpha([2 * mm - 1, 1, 1], alph) for mm in m3])

fig, ax = figstyle.figure(5.6, 3.9)
upper = np.vstack([c2, c3])
ax.fill(np.concatenate([c1[:, 0], upper[::-1, 0]]), np.concatenate([c1[:, 1], upper[::-1, 1]]),
        color=C["sky"], alpha=0.18, lw=0)
ax.plot(c1[:, 0], c1[:, 1], color=C["black"], lw=1.6)
ax.plot(upper[:, 0], upper[:, 1], color=C["black"], lw=1.6)

# SNAP zone boundaries
zc = C["grey"]
ax.axvline(0.5, color=zc, lw=1, ls="--")
ax.axvline(0.9, color=zc, lw=1, ls="--")
for a in (48, 42):
    ax.plot([0, 0.5], [a, a], color=zc, lw=1, ls="--")
for a in (50, 40):
    ax.plot([0.5, 0.9], [a, a], color=zc, lw=1, ls="--")
for a in (55, 40):
    ax.plot([0.9, 1.0], [a, a], color=zc, lw=1, ls="--")

labels = [
    (0.22, 70, "Z1\nmultiple\n(dihedral)"), (0.13, 45, "Z2 dipole"), (0.30, 18, "Z3\nsurface"),
    (0.70, 70, "Z4\nmultiple"), (0.70, 45, "Z5\nvegetation"), (0.66, 25, "Z6\nsurface"),
    (0.95, 72, "Z7"), (0.95, 47.5, "Z8"), (0.94, 22, "Z9\n(not\nfeasible)"),
]
for x, y, t in labels:
    ax.text(x, y, t, ha="center", va="center", fontsize=8.5)

ax.annotate("fully random, T ∝ I\n(H = 1, ā = 60°)", xy=(1.0, 60), xytext=(0.58, 84),
            fontsize=8.5, arrowprops=dict(arrowstyle="-|>", lw=0.8, color=C["black"]))
# random cloud of thin dipoles, T3 = diag(2, 1, 1)/4
h_dip, a_dip = h_alpha([2, 1, 1], alph)
ax.plot([h_dip], [a_dip], "o", color=C["green"], ms=6)
ax.annotate("dipole cloud", xy=(h_dip, a_dip), xytext=(0.69, 34.5), fontsize=8.5, color=C["green"],
            arrowprops=dict(arrowstyle="-|>", lw=0.8, color=C["green"]))
ax.set_xlim(0, 1.0)
ax.set_ylim(0, 92)
ax.set_xlabel("entropy H")
ax.set_ylabel("mean alpha angle ā (deg)")
ax.set_yticks([0, 15, 30, 45, 60, 75, 90])
figstyle.save(fig, "decompositions_1")
