"""speckle_1: probability density of L-look intensity over a homogeneous area (fully developed speckle).

p(I) = (L/mu)^L I^(L-1) exp(-L I / mu) / Gamma(L); L = 1 is the exponential distribution.
Plotted against the normalised intensity I/mu; the standard deviation / mean is 1/sqrt(L).
"""
import numpy as np
from scipy.stats import gamma

import figstyle

C = figstyle.COLORS
x = np.linspace(1e-4, 3.5, 600)
fig, ax = figstyle.figure(5.6, 2.9)
for L, col in ((1, C["blue"]), (4, C["orange"]), (16, C["green"])):
    pdf = gamma.pdf(x, a=L, scale=1.0 / L)
    ax.plot(x, pdf, color=col, lw=2,
            label=f"L = {L}:  std/mean = {1 / np.sqrt(L):.2f}")
ax.axvline(1.0, color=C["grey"], lw=0.8, ls="--")
ax.text(1.03, 0.04, "mean", fontsize=9, color=C["grey"])
ax.set_xlim(0, 3.5)
ax.set_ylim(0, 1.75)
ax.set_xlabel(r"Normalised intensity $I/\mu$ (linear)")
ax.set_ylabel("Probability density")
ax.legend(frameon=False, loc="upper right")
figstyle.save(fig, "speckle_1")
