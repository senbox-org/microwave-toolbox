"""tops_1: Sentinel-1 IW burst layout in three sub-swaths, with burst and sub-swath overlaps.

Timing and slant ranges are taken from the annotation of
S1A_IW_SLC__1SDV_20250403T135221_..._26E0 (relative orbit 71):
burst cycle 2.758 s, azimuth time interval 2.0556 ms, lines per burst 1497/1509/1515,
slant range IW1 800-850 km, IW2 846-905 km, IW3 902-959 km, burst start offsets
IW2 -1.819 s and IW3 -0.863 s relative to IW1, relative burst IDs 151217-151221.
"""
import matplotlib.patches as mpatches

import figstyle

C = figstyle.COLORS
ATI = 2.0556e-3          # azimuth time interval [s]
T_CYC = 2.758            # burst cycle time [s]
SWATHS = [               # name, near/far slant range [km], first-burst start [s], lines/burst, first ID
    ("IW1", 799.6, 849.6, 0.000, 1497, 151218),
    ("IW2", 846.1, 905.1, -1.819, 1509, 151217),
    ("IW3", 901.7, 958.7, -0.863, 1515, 151217),
]
COLS = [C["blue"], C["orange"], C["green"]]
Y0, Y1 = -0.4, 9.4

fig, ax = figstyle.figure(5.6, 3.9)

for (name, r0, r1, t0, lpb, id0), col in zip(SWATHS, COLS):
    dur = lpb * ATI
    for k in range(-1, 5):
        start = t0 + k * T_CYC
        if start + dur < Y0 or start > Y1:
            continue
        ax.add_patch(mpatches.Rectangle((r0, start), r1 - r0, dur, facecolor=col, alpha=0.28,
                                        edgecolor=col, lw=1.2))
        mid = start + dur / 2
        if Y0 + 0.3 < mid < Y1 - 0.3:
            ax.text((r0 + r1) / 2, mid, f"ID {id0 + k}", ha="center", va="center", fontsize=9)
    ax.text((r0 + r1) / 2, Y1 + 0.15, name, ha="center", va="bottom", fontsize=10, weight="bold", color=col)

# highlight one burst overlap in IW1 and the sub-swath overlap IW1/IW2
t_ovl0 = 0.0 + 1 * T_CYC
t_ovl1 = 0.0 + 1497 * ATI
ax.add_patch(mpatches.Rectangle((799.6, t_ovl0), 50.0, t_ovl1 - t_ovl0, facecolor="none",
                                edgecolor=C["red"], lw=1.6, hatch="////"))
ax.annotate("burst overlap\n~0.32 s, ~155 lines, ~2 km",
            xy=(812, (t_ovl0 + t_ovl1) / 2), xytext=(772, 5.2), fontsize=9, color=C["red"],
            arrowprops=dict(arrowstyle="->", color=C["red"], lw=1.0))
ax.annotate("sub-swath overlap\nin range", xy=(847.8, 7.6), xytext=(860, -0.25), fontsize=9,
            color=C["black"], ha="left", va="bottom",
            arrowprops=dict(arrowstyle="->", color=C["black"], lw=1.0))

ax.annotate("", xy=(965, 8.8), xytext=(965, 6.2),
            arrowprops=dict(arrowstyle="->", color=C["grey"], lw=1.5), annotation_clip=False)
ax.text(968, 7.5, "flight", rotation=90, va="center", fontsize=9, color=C["grey"])

ax.set_xlim(770, 975)
ax.set_ylim(Y0, Y1 + 0.8)
ax.set_xlabel("Slant range [km]")
ax.set_ylabel("Azimuth time [s]")
figstyle.save(fig, "tops_1")
