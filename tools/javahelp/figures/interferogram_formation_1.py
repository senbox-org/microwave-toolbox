"""interferogram_formation_1: the range spectral (wavenumber) shift.

Left: schematic range spectra of the Reference and the Secondary, shifted against each other by
Delta f. Only the common band (shaded) carries correlated signal; common-band (range) filtering
keeps it and discards the two non-overlapping edges.

Right: fraction of the range bandwidth that does not overlap, Delta f / B_r, versus the
perpendicular baseline on flat terrain (zeta = 0), from Gatelli et al. (1994):

    Delta f = f0 * B_perp / (R * tan(theta - zeta))      (zeta: terrain slope; 0 here)

Mission inputs (centre-swath slant range R, incidence theta, carrier f0, range bandwidth B_r):
    ERS        5.300 GHz, 15.55 MHz, R = 853 km, theta = 23 deg
    S1 IW2     5.405 GHz, 48.2 MHz,  R = 865 km, theta = 39 deg
    TSX SM     9.65 GHz,  150 MHz,   R = 616 km, theta = 35 deg  (nominal; 100 MHz in far-range beams)
    NISAR L    1.229 GHz, 20 MHz,    R = 909 km, theta = 37 deg  (20 MHz modes are centred at 1229 MHz)
The values above 1 are clipped: the pair is then fully decorrelated (critical baseline).
"""
import math

import matplotlib.patches as mpatches
import numpy as np

import figstyle

C = figstyle.COLORS

MISSIONS = [
    ("ERS (C, 15.5 MHz)", 5.300e9, 15.55e6, 853e3, 23.0, C["red"]),
    ("Sentinel-1 IW2 (C, 48 MHz)", 5.405e9, 48.2e6, 865e3, 39.0, C["blue"]),
    ("TerraSAR-X SM (X, 150 MHz)", 9.65e9, 150e6, 616e3, 35.0, C["orange"]),
    ("NISAR (L, 20 MHz)", 1.229e9, 20e6, 909e3, 37.0, C["green"]),
]


def shift_fraction(bperp, f0, br, r, theta_deg, slope_deg=0.0):  # slope_deg = zeta
    return f0 * bperp / (r * math.tan(math.radians(theta_deg - slope_deg))) / br


fig, (ax1, ax2) = figstyle.figure(5.6, 2.9, ncols=2, gridspec_kw={"width_ratios": [1.0, 1.15]})

# --- left: schematic spectra -------------------------------------------------------------
B = 1.0
df = 0.3
f = np.linspace(-1.0, 1.3, 600)


def box(center):
    return np.where(np.abs(f - center) <= B / 2, 1.0, 0.0)


ax1.fill_between(f, 0, np.minimum(box(0.0), box(df)), color=C["grey"], alpha=0.35, lw=0)
ax1.plot(f, box(0.0), color=C["blue"], lw=1.6, label="Reference")
ax1.plot(f, box(df) * 0.93, color=C["orange"], lw=1.6, ls="--", label="Secondary")
ax1.annotate("", xy=(df, 1.12), xytext=(0.0, 1.12),
             arrowprops=dict(arrowstyle="<->", color=C["black"], lw=1.0))
ax1.text(df / 2, 1.17, "Δf", ha="center", va="bottom", fontsize=10)
ax1.annotate("", xy=(0.5, -0.12), xytext=(-0.5, -0.12),
             arrowprops=dict(arrowstyle="<->", color=C["black"], lw=1.0), annotation_clip=False)
ax1.text(0.0, -0.2, "B$_r$", ha="center", va="top", fontsize=10)
ax1.text(df / 2, 0.45, "common\nband", ha="center", va="center", fontsize=9)
ax1.set_xlim(-0.9, 1.2)
ax1.set_ylim(-0.35, 1.45)
ax1.set_yticks([])
ax1.set_xticks([])
ax1.spines["left"].set_visible(False)
ax1.set_xlabel("range frequency")
ax1.set_title("Range spectra", fontsize=10)
handles = [mpatches.Patch(color=C["blue"], label="Ref."),
           mpatches.Patch(color=C["orange"], label="Sec.")]
ax1.legend(handles=handles, loc="upper left", frameon=False, fontsize=9, handlelength=1.0)

# --- right: non-overlapping fraction versus B_perp ----------------------------------------
bp = np.linspace(0, 1000, 201)
for name, f0, br, r, th, col in MISSIONS:
    frac = np.array([shift_fraction(b, f0, br, r, th) for b in bp])
    ax2.plot(bp, 100 * np.clip(frac, 0, 1), color=col, lw=1.6, label=name)
    print(f"{name}: {100 * shift_fraction(100, f0, br, r, th):.1f} % per 100 m (flat)")
ax2.set_xlim(0, 1000)
ax2.set_ylim(0, 100)
ax2.set_xlabel("B$_\\perp$ (m), flat terrain")
ax2.set_ylabel("Δf / B$_r$ (%)")
ax2.grid(True, color="#DDDDDD", lw=0.6)
ax2.legend(loc="upper left", frameon=False, fontsize=9, handlelength=1.4)
ax2.set_title("Non-overlapping band", fontsize=10)

figstyle.save(fig, "interferogram_formation_1")
