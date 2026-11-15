"""Ellipsoid, geoid and terrain heights: h = H + N (schematic; vertical scale exaggerated)."""
import numpy as np

import figstyle

C = figstyle.COLORS

fig, ax = figstyle.figure(5.6, 3.0)
x = np.linspace(0, 10, 400)
N = 32 + 8 * np.sin(0.5 * x) + 3 * np.sin(1.3 * x)         # geoid undulation above the ellipsoid (m)
land = x > 3.0
ramp = 1 - np.exp(-np.clip(x - 3.0, 0, None) / 1.2)
Hterr = np.where(land, 100 * ramp + 22 * np.sin(1.7 * (x - 3.0)) * ramp, 0.0)
Hterr = np.clip(Hterr, 0, None)
surf = N + Hterr                                                   # terrain or sea surface, ellipsoidal height

ax.axhline(0, color=C["black"], lw=1.2)
ax.plot(x, N, color=C["blue"], lw=1.5, ls="--")
ax.plot(x, surf, color=C["green"], lw=1.8)
ax.fill_between(x, 0, surf, where=~land, color=C["sky"], alpha=0.35, lw=0)

ax.text(0.15, 3, "ellipsoid (WGS84), h = 0", fontsize=9, ha="left", va="bottom")
ax.text(3.7, 15, "geoid (EGM96 / EGM2008)", fontsize=9, color=C["blue"], ha="left", va="center")
ax.text(1.4, N[60] + 3, "sea surface ≈ geoid", fontsize=9, ha="center", va="bottom")
ax.text(4.0, 122, "terrain", fontsize=9, color=C["green"], ha="center", va="bottom")

# height arrows at one point P
i = np.searchsorted(x, 7.6)
xp = x[i]
ax.plot(xp, surf[i], marker="o", color=C["black"], ms=5)
ax.text(xp + 0.1, surf[i] + 4, "P", fontsize=10, ha="left", va="bottom")
arrow = dict(arrowstyle="<|-|>", lw=1.1, shrinkA=0, shrinkB=0)
ax.annotate("", xy=(xp - 0.55, surf[i]), xytext=(xp - 0.55, 0), arrowprops=dict(arrow, color=C["red"]))
ax.text(xp - 0.65, 0.5 * surf[i], "h", fontsize=11, color=C["red"], ha="right", va="center")
ax.annotate("", xy=(xp + 0.35, N[i]), xytext=(xp + 0.35, 0), arrowprops=dict(arrow, color=C["blue"]))
ax.text(xp + 0.45, 0.5 * N[i], "N", fontsize=11, color=C["blue"], ha="left", va="center")
ax.annotate("", xy=(xp + 0.35, surf[i]), xytext=(xp + 0.35, N[i]), arrowprops=dict(arrow, color=C["green"]))
ax.text(xp + 0.45, 0.5 * (N[i] + surf[i]), "H", fontsize=11, color=C["green"], ha="left", va="center")
ax.plot([xp - 0.7, xp + 0.5], [N[i], N[i]], color=C["blue"], lw=0.6, ls=":")

ax.text(0.2, 150, "h = H + N", fontsize=12, ha="left", va="center",
        bbox=dict(boxstyle="round,pad=0.3", fc="white", ec=C["grey"]))

ax.set_xlim(0, 10)
ax.set_ylim(-8, 175)
ax.set_xlabel("distance along a profile (schematic)")
ax.set_ylabel("height above ellipsoid (m)")
ax.set_xticks([])
figstyle.save(fig, "height_references_1")
