"""terrain_correction_2: horizontal geolocation error caused by a height error, dx = dh / tan(theta).

theta is the local incidence angle on flat terrain (here equal to the ellipsoid incidence angle).
Curves for 30, 40 and 45 degrees; grey reference lines mark 10 m and 20 m (one and two Sentinel-1 GRD pixel spacings).
"""
import numpy as np

import figstyle

C = figstyle.COLORS

dh = np.linspace(0, 50, 201)          # height error [m]
fig, ax = figstyle.figure(5.6, 3.1)
for theta, col in ((30, C["red"]), (40, C["orange"]), (45, C["blue"])):
    dx = dh / np.tan(np.radians(theta))
    ax.plot(dh, dx, color=col, lw=1.8, label=f"θ = {theta}°  (Δx = {1 / np.tan(np.radians(theta)):.2f} Δh)")

for ref in (10, 20):
    ax.axhline(ref, color=C["grey"], lw=0.8, ls="--")
ax.text(49.5, 10.8, "10 m", ha="right", fontsize=9, color="#555555")
ax.text(49.5, 20.8, "20 m", ha="right", fontsize=9, color="#555555")

ax.set_xlim(0, 50)
ax.set_ylim(0, 90)
ax.set_xlabel("Height error Δh [m]")
ax.set_ylabel("Ground-range shift Δx [m]")
ax.legend(loc="upper left", frameon=False)
ax.grid(alpha=0.25)
figstyle.save(fig, "terrain_correction_2")
