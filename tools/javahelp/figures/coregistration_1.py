"""coregistration_1: decision tree - which SNAP coregistration route for which input.

Routes follow the operator checks in the code:
- CreateStackOp refuses S-1 TOPS SLC still in bursts ("For S1 TOPS SLC products, TOPS Coregistration
  should be used").
- DEMAssistedCoregistrationOp refuses any Sentinel-1 SLC Reference (mission SENTINEL-1*, type SLC).
- BackGeocodingOp works on split TOPS bursts; ESD follows.
- CreateStack stacks GSLC products on the Reference lattice (auto bias estimation on by default).
- OffsetTrackingOp needs a coregistered stack; geometric (DEM-assisted) alignment keeps the motion signal.
"""
import figstyle

C = figstyle.COLORS

fig, ax = figstyle.figure(5.6, 4.2)
ax.set_xlim(0, 1)
ax.set_ylim(0, 1)
ax.axis("off")


def box(x, y, text, col, weight="normal", ha="center"):
    ax.text(x, y, text, ha=ha, va="center", fontsize=9, weight=weight, linespacing=1.3,
            bbox=dict(boxstyle="round,pad=0.35", facecolor="white", edgecolor=col, lw=1.5))


def arrow(x0, y0, x1, y1):
    ax.annotate("", xy=(x1, y1), xytext=(x0, y0),
                arrowprops=dict(arrowstyle="-|>", color=C["grey"], lw=1.1, mutation_scale=10,
                                shrinkA=0, shrinkB=0))


def label(x, y, text):
    ax.text(x, y, text, ha="center", va="center", fontsize=9, style="italic", color="#333333",
            bbox=dict(facecolor="white", edgecolor="none", pad=0.4))


ax.text(0.5, 0.975, "Which coregistration route?", ha="center", va="top", fontsize=10, weight="bold")

X_IN, X_SPLIT, X_LEAF = 0.15, 0.335, 0.47   # input box centre, branch point, leaf left edge
DY = 0.085

groups = (
    (0.76, "Sentinel-1 IW/EW\nSLC in bursts", C["blue"],
     ("radar grid", "TOPS Split → Back-Geocoding → ESD", C["blue"]),
     ("map grid", "GSLC Terrain Correction (each date)\n→ Create Stack", C["purple"])),
    (0.46, "Stripmap SLC\n(TSX, CSK, ALOS-2,\nERS/ASAR, S-1 SM)", C["orange"],
     ("default", "Create Stack → Cross-Correlation → Warp", C["orange"]),
     ("steep\nrelief", "DEM-Assisted Coregistration\n(+ XCorr variant; not for S-1 SLC)", C["red"])),
    (0.16, "Detected images\n(GRD, amplitude)", C["green"],
     ("change\ndetection", "Create Stack → Cross-Correlation → Warp", C["green"]),
     ("offset\ntracking", "DEM-Assisted Coregistration\n→ Offset Tracking", C["red"])),
)

for y, text, col, up, down in groups:
    box(X_IN, y, text, col, weight="bold")
    # trunk from the input box to the branch point
    ax.plot([0.265, X_SPLIT], [y, y], color=C["grey"], lw=1.1)
    for (lab, leaf, lcol), yy in ((up, y + DY), (down, y - DY)):
        ax.plot([X_SPLIT, X_SPLIT], [y, yy], color=C["grey"], lw=1.1)
        arrow(X_SPLIT, yy, X_LEAF - 0.01, yy)
        label((X_SPLIT + X_LEAF) / 2 - 0.005, yy + (0.035 if "\n" not in lab else 0.05), lab)
        box(X_LEAF + 0.005, yy, leaf, lcol, ha="left")

figstyle.save(fig, "coregistration_1")
