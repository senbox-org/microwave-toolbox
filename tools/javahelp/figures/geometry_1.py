"""Slant range vs ground range: equal slant-range cells map to unequal ground-range cells (flat Earth)."""
import numpy as np

import figstyle

C = figstyle.COLORS

fig, ax = figstyle.figure(5.6, 3.0)
ax.set_aspect("equal")
ax.axis("off")

H = 1.0                        # platform height (schematic units)
xs = 0.0                       # platform ground position (nadir)
x_near, x_far = 0.45, 2.3      # swath edges on the ground
r_near, r_far = np.hypot(x_near, H), np.hypot(x_far, H)
n = 6
r = np.linspace(r_near, r_far, n + 1)   # equal slant-range steps
xg = np.sqrt(r ** 2 - H ** 2)           # where each range sphere meets flat ground

# ground and platform
ax.plot([-0.15, 2.55], [0, 0], color=C["black"], lw=1.2)
ax.plot(xs, H, marker="s", ms=8, color=C["black"])
ax.text(xs - 0.06, H + 0.04, "SAR", fontsize=9, ha="right", va="bottom")
ax.plot([xs, xs], [0, H], color=C["grey"], lw=0.8, ls=":")
ax.text(xs + 0.03, 0.5 * H, "H", fontsize=10, ha="left", va="center")

# equal slant-range arcs, drawn near the ground
for i, ri in enumerate(r):
    ang_g = np.arctan2(-H, xg[i])
    t = np.linspace(ang_g - 0.06 / ri, ang_g + 0.22 / ri, 30)
    ax.plot(xs + ri * np.cos(t), H + ri * np.sin(t), color=C["blue"], lw=1.0)
    ax.plot([xg[i], xg[i]], [-0.03, 0.03], color=C["black"], lw=1.0)

# rays to near and far edge
for xe in (x_near, x_far):
    ax.plot([xs, xe], [H, 0], color=C["orange"], lw=0.9)

# alternate shading of ground-range cells
for i in range(n):
    ax.fill_between([xg[i], xg[i + 1]], -0.09, -0.04,
                    color=C["green"] if i % 2 == 0 else C["sky"], lw=0)
ax.text(0.5 * (xg[0] + xg[-1]), -0.13, "ground-range cells: same slant-range width, wider at near range",
        fontsize=9, ha="center", va="top")
ax.text(0.95, 0.42, "equal slant-range\nintervals Δr", fontsize=9, color=C["blue"],
        ha="left", va="bottom")

# incidence angle at the far edge
xe = x_far
ax.plot([xe, xe], [0, 0.42], color=C["grey"], lw=0.8, ls="--")
look = np.array([xs - xe, H]) / np.hypot(xs - xe, H)
a0, a1 = np.pi / 2, np.arctan2(look[1], look[0])
t = np.linspace(a0, a1, 20)
ax.plot(xe + 0.22 * np.cos(t), 0.22 * np.sin(t), color=C["red"], lw=1.0)
ax.text(xe - 0.13, 0.27, "θ", fontsize=11, color=C["red"], ha="center", va="bottom")
ax.text(x_near - 0.02, -0.02, "near", fontsize=9, ha="right", va="top")
ax.text(x_far + 0.02, -0.02, "far", fontsize=9, ha="left", va="top")

ax.set_xlim(-0.35, 2.65)
ax.set_ylim(-0.32, 1.15)
figstyle.save(fig, "geometry_1")
