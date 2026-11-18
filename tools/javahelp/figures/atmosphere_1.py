"""atmosphere_1: dispersive (ionosphere) versus non-dispersive (troposphere) delay against radar frequency.

Ionosphere: one-way slant path change per 1 TECU (1e16 electrons/m^2) of differential TEC,
    dR = K * TEC / f^2,  K = 40.28 m^3 s^-2      (LOS-equivalent displacement, scales as 1/f^2)
    interferometric phase = 4*pi*dR/lambda = 4*pi*K*TEC / (c*f)   (scales as 1/f)
Troposphere: frequency-independent path change; 2 cm shown as a typical differential delay
    phase = 4*pi*dR*f/c   (scales as f)
"""
import numpy as np

import figstyle

C = figstyle.COLORS
K = 40.28            # m^3 s^-2
TECU = 1e16          # electrons / m^2
c = 299792458.0
TROPO = 0.02         # m, typical differential tropospheric delay

bands = [("P", 0.435e9), ("L", 1.2575e9), ("S", 3.2e9), ("C", 5.405e9), ("X", 9.65e9)]
f = np.logspace(np.log10(0.35e9), np.log10(12e9), 200)

fig, (ax1, ax2) = figstyle.plt.subplots(1, 2, figsize=(5.6, 2.9), dpi=figstyle.DPI)

# Left: LOS-equivalent path change (m)
ax1.loglog(f / 1e9, K * TECU / f ** 2, color=C["blue"], lw=2, label="iono, 1 TECU")
ax1.loglog(f / 1e9, np.full_like(f, TROPO), color=C["orange"], lw=2, label="tropo, 2 cm")
for name, fb in bands:
    ax1.axvline(fb / 1e9, color=C["grey"], lw=0.6, ls=":")
    ax1.text(fb / 1e9, 6.0, name, ha="center", va="bottom", fontsize=9)
ax1.set_ylim(2e-3, 6.0)
ax1.set_yticks([0.003, 0.01, 0.1, 1])
ax1.set_yticklabels(["0.003", "0.01", "0.1", "1"])
ax1.set_xlabel("Radar frequency (GHz)")
ax1.set_ylabel("LOS-equivalent shift (m)")
ax1.set_title("Path change", fontsize=10, pad=16)
ax1.legend(loc="upper right", bbox_to_anchor=(1.0, 0.85), frameon=False, fontsize=9, borderaxespad=0.1)

# Right: interferometric phase (rad)
ax2.loglog(f / 1e9, 4 * np.pi * K * TECU / (c * f), color=C["blue"], lw=2, label="iono, 1 TECU")
ax2.loglog(f / 1e9, 4 * np.pi * TROPO * f / c, color=C["orange"], lw=2, label="tropo, 2 cm")
for name, fb in bands:
    ax2.axvline(fb / 1e9, color=C["grey"], lw=0.6, ls=":")
    ax2.text(fb / 1e9, 60.0, name, ha="center", va="bottom", fontsize=9)
ax2.set_ylim(0.2, 60.0)
ax2.set_yticks([0.3, 1, 3, 10, 30])
ax2.set_yticklabels(["0.3", "1", "3", "10", "30"])
ax2.set_xlabel("Radar frequency (GHz)")
ax2.set_ylabel("Interferometric phase (rad)")
ax2.set_title("Phase", fontsize=10, pad=16)

for ax in (ax1, ax2):
    ax.set_xticks([0.5, 1, 2, 5, 10])
    ax.set_xticklabels(["0.5", "1", "2", "5", "10"])
    ax.minorticks_off()

figstyle.save(fig, "atmosphere_1")
