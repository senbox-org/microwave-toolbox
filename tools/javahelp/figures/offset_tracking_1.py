"""offset_tracking_1: velocity noise floor of offset tracking vs time interval.

sigma_v = sigma_px * dx / dt, with a matching error sigma_px = 0.1 pixel: the conservative end of the
1/10 to 1/20 pixel range given on the page (Strozzi et al. 2002 report about 1/20 pixel on ERS, which
halves every line), and pixel spacings dx of 2.3 m (Sentinel-1 IW SLC slant range), 10 m
(Sentinel-1 IW GRD) and 14 m (Sentinel-1 IW SLC azimuth). A velocity is measurable only well above
its line; the matching error itself grows with the interval as the surface changes (not modelled).
"""
import numpy as np

import figstyle

C = figstyle.COLORS
SIGMA_PX = 0.1
dt = np.logspace(0, np.log10(365), 200)   # days

fig, ax = figstyle.figure(5.6, 3.4)

cases = ((14.0, "14 m (S-1 IW SLC azimuth)", C["red"]),
         (10.0, "10 m (S-1 IW GRD)", C["blue"]),
         (2.3, "2.3 m (S-1 IW SLC slant range)", C["green"]))
for dx, lab, col in cases:
    ax.loglog(dt, SIGMA_PX * dx / dt, color=col, lw=2, label=lab)

for d in (6, 12):
    ax.axvline(d, color=C["grey"], lw=0.8, ls=":")
    ax.text(d * 1.05, 2.2, f"{d} d", fontsize=9, color="#555555", va="top")

# example: 10 m pixels, 12 days
v = SIGMA_PX * 10.0 / 12.0
ax.plot([12], [v], "o", color=C["blue"], ms=5)
ax.annotate(f"{v:.3f} m/day", xy=(12, v), xytext=(25, v * 2.6), fontsize=9,
            arrowprops=dict(arrowstyle="-", color="#555555", lw=0.8))

ax.set_xlim(1, 365)
ax.set_ylim(1e-3, 3)
ax.set_xlabel("Time interval between acquisitions [days]")
ax.set_ylabel("Velocity noise floor [m/day]")
ax.set_title("Matching error 0.1 pixel", fontsize=10)
ax.legend(loc="lower left", frameon=False)
ax.grid(True, which="major", color="#dddddd", lw=0.6)

figstyle.save(fig, "offset_tracking_1")
