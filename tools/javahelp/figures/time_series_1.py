"""time_series_1: interferogram networks in the time / perpendicular-baseline plane.

Synthetic Sentinel-1-like stack: 20 acquisitions every 12 days, B_perp drawn within +-120 m.
(a) single-reference network used by PS analysis (StaMPS export): every secondary paired with one reference.
(b) small-baseline network used by SBAS: pairs with dt <= 36 days and |dB_perp| <= 150 m.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
rng = np.random.default_rng(7)
n = 20
t = np.arange(n) * 12.0
bp = rng.uniform(-120.0, 120.0, n)
bp -= bp[n // 2]                     # B_perp relative to the median epoch
ref = n // 2

fig, (ax1, ax2) = figstyle.plt.subplots(1, 2, figsize=(5.6, 2.9), dpi=figstyle.DPI, sharey=True)

for i in range(n):
    if i != ref:
        ax1.plot([t[ref], t[i]], [bp[ref], bp[i]], color=C["sky"], lw=1.0, zorder=1)
ax1.scatter(t, bp, s=22, color=C["blue"], zorder=2)
ax1.scatter([t[ref]], [bp[ref]], s=60, marker="*", color=C["red"], zorder=3, label="reference")
ax1.set_title("(a) single reference (PS)", fontsize=10)
ax1.set_xlabel("Time (days)")
ax1.set_ylabel(r"$B_\perp$ (m)")
ax1.legend(loc="lower left", frameon=False, fontsize=9, borderaxespad=0.1)

pairs = [(i, j) for i in range(n) for j in range(i + 1, n)
         if t[j] - t[i] <= 36.0 and abs(bp[j] - bp[i]) <= 150.0]
for i, j in pairs:
    ax2.plot([t[i], t[j]], [bp[i], bp[j]], color=C["green"], lw=1.0, zorder=1)
ax2.scatter(t, bp, s=22, color=C["blue"], zorder=2)
ax2.set_title("(b) small baselines (SBAS)", fontsize=10)
ax2.set_xlabel("Time (days)")
ax2.text(0.98, 0.02, f"{len(pairs)} pairs, {n} epochs", transform=ax2.transAxes,
         ha="right", va="bottom", fontsize=9)

ax1.set_ylim(-260, 200)
figstyle.save(fig, "time_series_1")
print("pairs:", len(pairs))
