"""polarimetry_1: three canonical scatterers and their Pauli signatures.

Top row: schematic ray paths (not to scale). Bottom row: the fraction of the total power
(span) in each Pauli channel, computed from the scattering matrix (or, for the volume,
the coherency matrix) of the ideal scatterer:
  surface / trihedral  S = [[1, 0], [0, 1]]   -> all power in |HH+VV|^2/2 (blue)
  dihedral             S = [[1, 0], [0, -1]]  -> all power in |HH-VV|^2/2 (red)
  random dipole cloud  T3 = diag(2, 1, 1)/4   -> 1/2 blue, 1/4 red, 1/4 green
The bar colours follow the SNAP Pauli RGB: Pauli_r = |HH-VV|^2/2, Pauli_g = |HV+VH|^2/2,
Pauli_b = |HH+VV|^2/2.
"""
import numpy as np
import matplotlib.pyplot as plt

import figstyle

C = figstyle.COLORS


def pauli_fractions_from_S(S):
    hh, hv, vh, vv = S[0, 0], S[0, 1], S[1, 0], S[1, 1]
    b = abs(hh + vv) ** 2 / 2
    r = abs(hh - vv) ** 2 / 2
    g = abs(hv + vh) ** 2 / 2
    tot = b + r + g
    return np.array([r, g, b]) / tot


surface = pauli_fractions_from_S(np.array([[1, 0], [0, 1]], dtype=complex))
dihedral = pauli_fractions_from_S(np.array([[1, 0], [0, -1]], dtype=complex))
T3_vol = np.diag([2.0, 1.0, 1.0]) / 4.0            # random cloud of thin dipoles (Pauli basis)
volume = np.array([T3_vol[1, 1], T3_vol[2, 2], T3_vol[0, 0]]) / np.trace(T3_vol)  # R, G, B

cases = [("Surface\n(odd bounce)", surface),
         ("Dihedral\n(double bounce)", dihedral),
         ("Volume\n(random dipoles)", volume)]

fig = plt.figure(figsize=(5.6, 3.4), dpi=figstyle.DPI)
gs = fig.add_gridspec(2, 3, height_ratios=[1.0, 1.3], hspace=0.15, wspace=0.35)

arrow = dict(arrowstyle="-|>", lw=1.3, color=C["black"], mutation_scale=9)
for i, (title, frac) in enumerate(cases):
    ax = fig.add_subplot(gs[0, i])
    ax.set_xlim(0, 4)
    ax.set_ylim(0, 3)
    ax.set_aspect("equal")
    ax.axis("off")
    ax.set_title(title, fontsize=9.5)
    if i == 0:
        ax.plot([0.2, 3.8], [0.5, 0.5], color=C["grey"], lw=3)
        ax.annotate("", xy=(2.0, 0.55), xytext=(0.6, 2.6), arrowprops=arrow)
        ax.annotate("", xy=(0.9, 2.6), xytext=(2.1, 0.6),
                    arrowprops=dict(arrow, color=C["blue"]))
    elif i == 1:
        ax.plot([0.3, 3.0], [0.5, 0.5], color=C["grey"], lw=3)
        ax.plot([3.0, 3.0], [0.5, 2.6], color=C["grey"], lw=3)
        ax.annotate("", xy=(2.2, 0.55), xytext=(0.3, 2.3), arrowprops=arrow)
        ax.annotate("", xy=(2.95, 1.2), xytext=(2.2, 0.55),
                    arrowprops=dict(arrow, arrowstyle="-"))
        ax.annotate("", xy=(0.6, 2.7), xytext=(2.95, 1.2),
                    arrowprops=dict(arrow, color=C["red"]))
    else:
        rng = np.random.default_rng(3)
        for _ in range(22):
            x, y = rng.uniform(0.6, 3.4), rng.uniform(0.7, 2.6)
            a = rng.uniform(0, np.pi)
            dx, dy = 0.22 * np.cos(a), 0.22 * np.sin(a)
            ax.plot([x - dx, x + dx], [y - dy, y + dy], color=C["green"], lw=1.6)
        ax.plot([0.2, 3.8], [0.5, 0.5], color=C["grey"], lw=3)

    axb = fig.add_subplot(gs[1, i])
    axb.bar([0, 1, 2], frac, color=[C["red"], C["green"], C["blue"]], width=0.7)
    axb.set_xticks([0, 1, 2])
    axb.set_xticklabels(["R", "G", "B"])
    axb.set_ylim(0, 1.08)
    for k, v in enumerate(frac):
        axb.text(k, v + 0.03, f"{v:.2f}", ha="center", fontsize=9)
    if i == 0:
        axb.set_ylabel("fraction of span")
    else:
        axb.set_yticklabels([])

figstyle.save(fig, "polarimetry_1")
