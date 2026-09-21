"""Compact inline-SVG phase panels for the slide deck (the slide format allows no raster files).
Cells are circular-mean block-averaged, phase quantised to NCOL twilight colours, run-length merged per colour.
  svg_panels.py <cells_dir> <out_dir> [factor] [ncol]
"""
import sys
from pathlib import Path

import numpy as np


def panel_svg(Z, fill, factor, ncol, w=530, h=379):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    ny, nx = (Z.shape[0] // factor), (Z.shape[1] // factor)
    zz = np.where(fill & (np.abs(Z) > 0), Z / np.maximum(np.abs(Z), 1e-12), 0)[:ny * factor, :nx * factor]
    m = zz.reshape(ny, factor, nx, factor).sum(axis=(1, 3))
    ph = np.angle(m)
    valid = np.abs(m) > 0.25 * factor * factor * 0.5
    q = np.floor((ph + np.pi) / (2 * np.pi) * ncol).astype(int) % ncol
    cmap = plt.get_cmap("twilight")
    cols = ["#%02x%02x%02x" % tuple(int(255 * c) for c in cmap((k + 0.5) / ncol)[:3]) for k in range(ncol)]
    paths = [[] for _ in range(ncol)]
    for y in range(ny):
        x = 0
        while x < nx:
            if not valid[y, x]:
                x += 1
                continue
            k, x0 = q[y, x], x
            while x < nx and valid[y, x] and q[y, x] == k:
                x += 1
            paths[k].append(f"M{x0} {y}h{x - x0}v1h-{x - x0}z")
    body = "".join(f'<path fill="{cols[k]}" d="{"".join(p)}"/>' for k, p in enumerate(paths) if p)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{nx}" height="{ny}" viewBox="0 0 {nx} {ny}" '
            f'shape-rendering="crispEdges" aria-label="wrapped interferogram phase">{body}</svg>'), nx, ny


if __name__ == "__main__":
    cd, od = Path(sys.argv[1]), Path(sys.argv[2])
    f = int(sys.argv[3]) if len(sys.argv) > 3 else 6
    nc = int(sys.argv[4]) if len(sys.argv) > 4 else 10
    b, a = np.load(cd / "cells_before.npz"), np.load(cd / "cells_after.npz")
    fill = b["fill"] & a["fill"]
    od.mkdir(parents=True, exist_ok=True)
    for name, Z in (("classical", b["T"]), ("before", b["G"]), ("after", a["G"])):
        s, nx, ny = panel_svg(Z, fill, f, nc)
        (od / f"{name}.svg").write_text(s, encoding="utf-8")
        print(name, nx, ny, len(s.encode()), "bytes")
