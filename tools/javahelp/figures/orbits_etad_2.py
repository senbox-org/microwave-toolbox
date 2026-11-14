"""orbits_etad_2: schematic order of magnitude of the Sentinel-1 ETAD timing corrections, in metres.

Values from Gisinger et al. (2022), IEEE TGRS 60, 5232622, Section II-B:
troposphere 2-4 m slant range; ionosphere up to ~1 m (C-band); solid Earth tides up to 20 cm
peak-to-peak slant range within one data take; Doppler-induced range shift +/-0.4 m across a burst;
bistatic azimuth shift 2-4 m (range dependent); FM-rate mismatch a few metres at burst edges in
mountainous terrain. ETAD 1-sigma accuracy specification: 0.2 m range, 0.1 m azimuth.
"""
import matplotlib.patches as mpatches

import figstyle

C = figstyle.COLORS
ROWS = [  # label, low, high, direction, note
    ("Troposphere", 2.0, 4.0, "rg", "2-4 m"),
    ("Ionosphere", 0.0, 1.0, "rg", "up to ~1 m"),
    ("Solid Earth tides", 0.0, 0.2, "rg", "up to 0.2 m p-p"),
    ("Doppler range shift", 0.0, 0.4, "rg", "±0.4 m"),
    ("Bistatic azimuth", 2.0, 4.0, "az", "2-4 m"),
    ("FM-rate mismatch", 0.0, 3.0, "az", "a few m (relief)"),
]

fig, ax = figstyle.figure(5.6, 3.0)
for i, (lab, lo, hi, d, note) in enumerate(ROWS):
    y = len(ROWS) - 1 - i
    col = C["blue"] if d == "rg" else C["orange"]
    hatch = "///" if lab.startswith("FM") else None
    ax.add_patch(mpatches.Rectangle((lo, y - 0.32), hi - lo, 0.64, facecolor=col, alpha=0.75,
                                    hatch=hatch, edgecolor="white" if hatch else col))
    ax.text(hi + 0.08, y, note, va="center", fontsize=9)
# ETAD 1-sigma specification: 0.2 m over the range rows, 0.1 m over the azimuth rows
ax.plot([0.2, 0.2], [1.5, len(ROWS) - 0.5], color=C["black"], ls="--", lw=1)
ax.plot([0.1, 0.1], [-0.5, 1.5], color=C["black"], ls="--", lw=1)
ax.text(0.27, len(ROWS) - 0.42, "ETAD spec: 0.2 m (range), 0.1 m (azimuth)", fontsize=9, va="bottom")
ax.text(5.15, len(ROWS) - 0.42, "schematic", ha="right", va="bottom", fontsize=9, style="italic", color="#555555")
ax.set_yticks(range(len(ROWS)))
ax.set_yticklabels([r[0] for r in ROWS][::-1])
ax.set_xlim(0, 5.2)
ax.set_ylim(-0.6, len(ROWS) + 0.2)
ax.set_xlabel("Correction magnitude [m]")
ax.legend(handles=[mpatches.Patch(color=C["blue"], alpha=0.75, label="slant range"),
                   mpatches.Patch(color=C["orange"], alpha=0.75, label="azimuth")],
          loc="center right", bbox_to_anchor=(1.0, 0.55), frameon=False)
figstyle.save(fig, "orbits_etad_2")
