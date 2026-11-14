"""Shared look for concept-page figures. Usage in a figure script:

    import figstyle
    fig, ax = figstyle.figure(5.6, 3.2)
    ...
    figstyle.save(fig, "geometry_1")   # -> concepts/images/geometry_1.png, prints its pixel size

Figures render at 100 dpi, so a 5.6 in wide figure is 560 px (the maximum page width).
"""
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs/concepts/images"
DPI = 100

# Okabe-Ito colour-blind-safe palette
COLORS = {
    "blue": "#0072B2", "orange": "#E69F00", "green": "#009E73", "red": "#D55E00",
    "purple": "#CC79A7", "sky": "#56B4E9", "yellow": "#F0E442", "black": "#000000",
    "grey": "#888888",
}

plt.rcParams.update({
    "font.family": "DejaVu Sans",
    "font.size": 10,
    "axes.titlesize": 11,
    "axes.labelsize": 10,
    "legend.fontsize": 9,
    "xtick.labelsize": 9,
    "ytick.labelsize": 9,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "figure.facecolor": "white",
    "axes.facecolor": "white",
    "savefig.facecolor": "white",
    "axes.prop_cycle": matplotlib.cycler(color=[COLORS[c] for c in
                                                ("blue", "orange", "green", "red", "purple", "sky")]),
})


def figure(width_in=5.6, height_in=3.2, **kw):
    if width_in > 5.6:
        raise ValueError("figures must be <= 5.6 in (560 px) wide")
    fig, ax = plt.subplots(figsize=(width_in, height_in), dpi=DPI, **kw)
    return fig, ax


def save(fig, name):
    """Save as concepts/images/<name>.png (no bbox cropping, so the size is exact). Returns (w, h) px."""
    if name != name.lower() or not name.replace("_", "").isalnum():
        raise ValueError("figure names are lower-case alnum + '_' (e.g. geometry_1)")
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.png"
    fig.tight_layout()
    fig.savefig(path, dpi=DPI)
    w, h = (int(round(v * DPI)) for v in fig.get_size_inches())
    plt.close(fig)
    print(f"{path.name}: {w}x{h}px")
    return w, h
