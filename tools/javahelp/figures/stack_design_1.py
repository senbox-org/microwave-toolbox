"""stack_design_1: decorrelation models against baseline.

(a) Geometric (range spectral shift) term 1 - B_perp/B_perp,crit, flat terrain unless stated.
    B_perp,crit = lambda R tan(theta - alpha) B_r / c with
      Sentinel-1 IW2: lambda 5.547 cm, R 865 km, theta 39 deg, B_r 48.2 MHz   -> 6.2 km
      same on a 20 deg slope facing the radar (theta - alpha = 19 deg)       -> 2.7 km
      NISAR L-band 20 MHz: lambda 0.24 m, R 909 km, theta 37 deg            -> 11.0 km
    and the C-band constant of SNAP's InSAR Stack Overview model (1200 m, InSARStackOverview.Band.C).
(b) Temporal term: SNAP's linear model 1 - B_t/T_crit (C 1095 d, L 2920 d) against an illustrative
    exponential decay to a floor, gamma = g_inf + (1 - g_inf) exp(-B_t / tau), tau 24 d, g_inf 0.25.
    The exponential parameters are an example only, not a fit to any data set.
"""
import numpy as np

import figstyle

C = figstyle.COLORS
c0 = 299792458.0


def bcrit(lam, r, theta_deg, br):
    return lam * r * np.tan(np.radians(theta_deg)) * br / c0


s1 = bcrit(0.055466, 865e3, 39.0, 48.2e6)
s1_slope = bcrit(0.055466, 865e3, 19.0, 48.2e6)
nisar = bcrit(0.24, 909e3, 37.0, 20e6)

fig, (ax1, ax2) = figstyle.plt.subplots(1, 2, figsize=(5.6, 3.0), dpi=figstyle.DPI)

bp = np.linspace(0.0, 3000.0, 301)


def geo(b, bc):
    return np.clip(1.0 - b / bc, 0.0, 1.0)


ax1.axvspan(0, 200, color="#EEEEEE", zorder=0)
ax1.plot(bp, geo(bp, nisar), color=C["green"], lw=1.6, label="NISAR 20 MHz")
ax1.plot(bp, geo(bp, s1), color=C["blue"], lw=1.6, label="S-1 IW2")
ax1.plot(bp, geo(bp, s1_slope), color=C["sky"], lw=1.6, label="S-1 IW2, 20\u00b0 slope")
ax1.plot(bp, geo(bp, 1200.0), color=C["red"], lw=1.6, ls="--", label="SNAP model (C)")
ax1.set_xlim(0, 3000)
ax1.set_ylim(0, 1.02)
ax1.set_xlabel(r"$B_\perp$ (m)")
ax1.set_ylabel("coherence factor")
ax1.set_title("(a) geometric term", fontsize=10)
ax1.text(1500, 0.90, "NISAR 20 MHz", color=C["green"], fontsize=9)
ax1.text(2000, 0.56, "S-1 IW2", color=C["blue"], fontsize=9)
ax1.text(1900, 0.36, "S-1 IW2,\n20\u00b0 slope", color=C["sky"], fontsize=9)
ax1.text(110, 0.02, "SNAP\nmodel (C)", color=C["red"], fontsize=9)

bt = np.linspace(0.0, 730.0, 366)
ax2.plot(bt, 1.0 - bt / 2920.0, color=C["green"], lw=1.6, ls="--", label="SNAP model (L)")
ax2.plot(bt, 1.0 - bt / 1095.0, color=C["red"], lw=1.6, ls="--", label="SNAP model (C)")
ax2.plot(bt, 0.25 + 0.75 * np.exp(-bt / 24.0), color=C["orange"], lw=1.6, label="example decay")
ax2.set_xlim(0, 730)
ax2.set_ylim(0, 1.02)
ax2.set_xlabel(r"$B_t$ (days)")
ax2.set_title("(b) temporal term", fontsize=10)
ax2.legend(loc="lower left", frameon=False, fontsize=9, bbox_to_anchor=(0.1, 0.28), borderaxespad=0.1)

figstyle.save(fig, "stack_design_1")
print(f"Bcrit S1 IW2 {s1:.0f} m, slope {s1_slope:.0f} m, NISAR {nisar:.0f} m")
