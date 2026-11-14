"""tops_2: azimuth time-frequency support of one focused S-1 IW1 burst, before and after deramping.

Numbers from the IW1 annotation of S1A_IW_SLC__1SDV_20250403T135221_..._26E0: Doppler-centroid rate k_t = k_a k_s / (k_a - k_s) ~ 1780 Hz/s
(k_a ~ -2330 Hz/s, steering rate 1.59 deg/s), burst length 1497 lines x 2.0556 ms = 3.08 s,
azimuth processing bandwidth 327 Hz, azimuth sampling rate 1/2.0556 ms = 486 Hz.
"""
import matplotlib.pyplot as plt
import numpy as np

import figstyle

C = figstyle.COLORS
KT = 1780.0
T = 1497 * 2.0556e-3
BW = 327.0
FAZ = 1.0 / 2.0556e-3

t = np.linspace(-T / 2, T / 2, 200)
fig, axes = plt.subplots(1, 2, figsize=(5.6, 3.2), dpi=figstyle.DPI, sharey=True)

for ax, deramped in zip(axes, (False, True)):
    fc = np.zeros_like(t) if deramped else KT * t
    ax.axhspan(-FAZ / 2, FAZ / 2, color=C["grey"], alpha=0.18, lw=0)
    ax.fill_between(t, fc - BW / 2, fc + BW / 2, color=C["blue"], alpha=0.75, lw=0)
    ax.set_xlim(-T / 2, T / 2)
    ax.set_ylim(-3000, 3000)
    ax.set_xlabel("Azimuth time in burst [s]")
    ax.set_title("after deramping" if deramped else "focused burst (SLC)")

axes[0].set_ylabel("Azimuth frequency [Hz]")
axes[0].annotate(f"Doppler centroid sweep\n~{KT * T / 1000:.1f} kHz per burst", xy=(0.8, KT * 0.8),
                 xytext=(-1.45, 1700), fontsize=9,
                 arrowprops=dict(arrowstyle="->", color=C["black"], lw=0.9))
axes[0].text(1.5, -FAZ / 2 - 80, f"sampled band\n±{FAZ / 2:.0f} Hz", ha="right", va="top", fontsize=9,
             color="#555555")
axes[1].text(0, BW / 2 + 120, f"{BW:.0f} Hz at baseband", ha="center", va="bottom", fontsize=9)
axes[1].text(0, -1500, "safe to interpolate,\nfilter, resample", ha="center", va="center", fontsize=9)
figstyle.save(fig, "tops_2")
