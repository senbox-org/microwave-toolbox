"""displacement_1: LOS geometry and sensitivity of the LOS to east, north and up motion.

Left: vertical cross-section across track (right-looking SAR, incidence angle theta).
Right: components of the unit vector pointing from the ground to the satellite,
for Sentinel-1-like ascending (heading -12 deg) and descending (heading 192 deg)
passes at theta = 39 deg.  A positive SNAP displacement is motion along this vector.

    e_E = -sin(theta) cos(alpha),  e_N = +sin(theta) sin(alpha),  e_U = cos(theta)
"""
import math

import matplotlib.pyplot as plt
import numpy as np

import figstyle

C = figstyle.COLORS
fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(5.6, 3.0), dpi=figstyle.DPI,
                               gridspec_kw={"width_ratios": [1.05, 1.0]})

# ---- left: cross-section -------------------------------------------------
theta = math.radians(39.0)
gx, gy = 0.0, 0.0                       # ground target
L = 3.0
sx, sy = gx - L * math.sin(theta), gy + L * math.cos(theta)   # satellite (to the west for ascending)
ax1.plot([-2.6, 1.4], [0, 0], color=C["black"], lw=1.2)
ax1.fill_between([-2.6, 1.4], [-0.25, -0.25], [0, 0], color="#E8E8E8")
ax1.plot([gx, gx], [0, 2.2], color=C["grey"], lw=0.8, ls="--")
ax1.annotate("", xy=(sx, sy), xytext=(gx, gy),
             arrowprops=dict(arrowstyle="-", color=C["blue"], lw=1.2, ls="--"))
ax1.plot(sx, sy, marker="s", ms=9, color=C["black"])
ax1.text(sx + 0.15, sy + 0.05, "SAR", fontsize=9, va="center")
# LOS unit vector (towards satellite)
u = np.array([-math.sin(theta), math.cos(theta)])
ax1.annotate("", xy=(gx + 1.2 * u[0], gy + 1.2 * u[1]), xytext=(gx, gy),
             arrowprops=dict(arrowstyle="-|>", color=C["red"], lw=2.0))
ax1.text(-0.85, 0.45, "+LOS", color=C["red"], fontsize=10, ha="right", va="center")
# incidence angle arc
arc = np.linspace(math.pi / 2, math.pi / 2 + theta, 30)
ax1.plot(gx + 0.7 * np.cos(arc), gy + 0.7 * np.sin(arc), color=C["black"], lw=0.8)
ax1.text(gx - 0.18, gy + 0.85, r"$\theta$", fontsize=11)
# up and east arrows
ax1.annotate("", xy=(0.95, 0.9), xytext=(0.95, 0.15),
             arrowprops=dict(arrowstyle="-|>", color=C["green"], lw=1.4))
ax1.text(1.02, 0.55, "Up", color=C["green"], fontsize=9)
ax1.annotate("", xy=(1.35, 0.15), xytext=(0.95, 0.15),
             arrowprops=dict(arrowstyle="-|>", color=C["orange"], lw=1.4))
ax1.text(1.0, -0.15, "East", color=C["orange"], fontsize=9, va="top")
ax1.plot(gx, gy, marker="o", ms=5, color=C["black"])
ax1.text(-2.5, -0.55, "Cross-section, ascending pass\n(satellite heads north, looks east)",
         fontsize=9, va="top")
ax1.set_xlim(-2.7, 1.8)
ax1.set_ylim(-1.15, 2.7)
ax1.set_aspect("equal")
ax1.axis("off")

# ---- right: sensitivity bars ---------------------------------------------
def los(theta_deg, heading_deg):
    t, a = math.radians(theta_deg), math.radians(heading_deg)
    return (-math.sin(t) * math.cos(a), math.sin(t) * math.sin(a), math.cos(t))


asc = los(39.0, -12.0)
dsc = los(39.0, 192.0)
y = np.arange(3)[::-1]
h = 0.36
ax2.barh(y + h / 2, asc, h, color=C["blue"], label="ascending")
ax2.barh(y - h / 2, dsc, h, color=C["orange"], label="descending")
ax2.axvline(0, color=C["black"], lw=0.8)
ax2.set_yticks(y)
ax2.set_yticklabels(["East", "North", "Up"])
ax2.set_xlim(-1.7, 1.2)
ax2.set_xlabel("LOS unit-vector component")
ax2.set_title("Sensitivity at " + r"$\theta$" + " = 39\u00b0", fontsize=10)
ax2.legend(loc="lower left", frameon=False, fontsize=9, borderaxespad=0.1)
for yy, vals in ((y + h / 2, asc), (y - h / 2, dsc)):
    for yi, v in zip(yy, vals):
        ax2.text(v + (0.03 if v >= 0 else -0.03), yi, f"{v:+.2f}", va="center",
                 ha="left" if v >= 0 else "right", fontsize=9)
ax2.spines["left"].set_visible(False)
ax2.tick_params(axis="y", length=0)

figstyle.save(fig, "displacement_1")
