"""terrain_correction_1: backward geocoding flow of Range-Doppler Terrain Correction, per output map pixel.

Steps follow RangeDopplerGeocodingOp.computeTileStack / getPosition and SARGeocoding:
map pixel -> (lat, lon) -> DEM height (EGM96 added) -> ECEF point -> zero-Doppler time (bisection over the
per-line orbit positions, final linear interpolation) -> slant range -> bistatic shift R/c -> (optional APD)
-> range/azimuth index -> image resampling -> output value.
"""
import figstyle
from matplotlib.patches import FancyBboxPatch

C = figstyle.COLORS

fig, ax = figstyle.figure(5.6, 4.2)
ax.axis("off")
ax.set_xlim(0, 10)
ax.set_ylim(0, 10.6)

STEPS = [
    ("Output map pixel (x, y)", "map grid: CRS + pixel spacing", C["blue"]),
    ("Latitude, longitude", "from the map geocoding", C["blue"]),
    ("Height h from the DEM", "geoid heights + EGM96 = ellipsoidal h", C["green"]),
    ("Earth-fixed point P", "WGS84 Cartesian (x, y, z)", C["green"]),
    ("Zero-Doppler time t₀", "(S(t) − P)·V(t) = 0 on the orbit", C["orange"]),
    ("Slant range R = |S(t) − P|", "+ bistatic shift, + optional path delay", C["orange"]),
    ("Source pixel position", "azimuth index from t, range index from R", C["red"]),
    ("Resample source image", "nearest, bilinear, cubic, sinc ...", C["red"]),
]

n = len(STEPS)
top, step_h, box_h = 10.2, 1.3, 0.95
for i, (title, note, col) in enumerate(STEPS):
    y = top - i * step_h - box_h
    box = FancyBboxPatch((0.1, y), 4.7, box_h, boxstyle="round,pad=0.02,rounding_size=0.15",
                         fc="white", ec=col, lw=1.6)
    ax.add_patch(box)
    ax.text(2.45, y + box_h / 2, title, ha="center", va="center", fontsize=9.5)
    if note:
        ax.text(5.05, y + box_h / 2, note, ha="left", va="center", fontsize=9, color="#333333")
    if i < n - 1:
        ax.annotate("", xy=(2.45, y - (step_h - box_h) + 0.02), xytext=(2.45, y - 0.02),
                    arrowprops=dict(arrowstyle="-|>", color=C["black"], lw=1.0))

figstyle.save(fig, "terrain_correction_1")
