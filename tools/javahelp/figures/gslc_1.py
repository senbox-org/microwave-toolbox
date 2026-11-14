"""gslc_1: classical (coregister in radar geometry, then geocode) versus geocode-first (GSLC) InSAR chain.

Schematic only. Operator names as in SNAP: Back-Geocoding + Enhanced-Spectral-Diversity (TOPS) or
Cross-Correlation + Warp (stripmap); GSLC-Terrain-Correction, CreateStack, Interferogram, Terrain-Correction.
Blue boxes: radar (slant-range) geometry. Orange boxes: map grid.
"""
from matplotlib.patches import FancyBboxPatch

import figstyle

C = figstyle.COLORS
fig, ax = figstyle.figure(5.6, 3.3)
ax.set_xlim(0, 100)
ax.set_ylim(-1.2, 60)
ax.axis("off")

RADAR = "#E8F1F8"   # light tint for radar-geometry steps
MAP = "#FDF1DC"     # light tint for map-grid steps


def box(x, y, w, h, text, on_map):
    face, edge = (MAP, C["orange"]) if on_map else (RADAR, C["blue"])
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.2",
                                fc=face, ec=edge, lw=1.3))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=9)


def arrow(x0, y0, x1, y1):
    ax.annotate("", xy=(x1, y1), xytext=(x0, y0),
                arrowprops=dict(arrowstyle="-|>", color=C["black"], lw=1.3, shrinkA=0, shrinkB=0))


# --- classical chain (top) ---
ax.text(0, 57, "Classical: coregister in radar geometry, geocode last", fontsize=10, fontweight="bold")
box(0.5, 43, 13, 9, "Reference\nSLC", False)
box(0.5, 31, 13, 9, "Secondary\nSLC", False)
box(18.5, 35, 26, 13, "Coregister the pair\nBack-Geocoding + ESD\nor CC + Warp", False)
box(49.5, 37, 17, 9, "Interferogram\n(radar grid)", False)
box(71.5, 37, 27.5, 9, "Terrain Correction\n(to the map grid)", True)
arrow(14, 47.5, 18, 44)
arrow(14, 35.5, 18, 39)
arrow(45, 41.5, 49, 41.5)
arrow(67, 41.5, 71, 41.5)

# --- geocode-first chain (bottom) ---
ax.text(0, 24, "Geocode-first: geocode each SLC, stack on the map grid", fontsize=10, fontweight="bold")
box(0.5, 11.5, 13, 9, "Reference\nSLC", False)
box(0.5, 0.5, 13, 9, "Secondary\nSLC", False)
box(18.5, 11.5, 26, 9, "GSLC Terrain Corr.\n(own standard grid)", True)
box(18.5, 0.0, 26, 10.5, "GSLC Terrain Corr.\n(same grid) or\ninside Create Stack", True)
box(48.5, 4, 19, 13, "Create Stack\n(lattice check,\nbias estimate)", True)
box(71.5, 6, 27.5, 9, "Interferogram\n(map grid, no TC)", True)
arrow(14, 16, 18, 16)
arrow(14, 5, 18, 5)
arrow(45, 16, 48, 13.5)
arrow(45, 5, 48, 8)
arrow(68, 10.5, 71, 10.5)

figstyle.save(fig, "gslc_1")
