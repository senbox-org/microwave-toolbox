"""Side-by-side wrapped phase on the classical burst-geometry radar cells (8 x 30, ~100 m):
classical | GSLC before the sign fix (legacy +1) | GSLC after (default -1). Ramp off in all three.
Cells with GSLC coverage below 60% of the median, or no classical data, are masked white.

  plot_ifg_compare.py <out_dir> [ml_az ml_rg]   (default 8 30; 1 cell = 1 image pixel, no resampling)
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_r5b as r5b

D = Path(r"E:\Output\parity\ven")


def main(out, ml_az=8, ml_rg=30):
    r5b.ML_AZ, r5b.ML_RG = ml_az, ml_rg
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    cl = str(D / "ven_trad_ifg_burst.dim")
    diag = str(D / "ven_etadD_gslc.dim")
    res = {}
    for tag, ifg in (("before", "ven_etadD_ifg.dim"), ("after", "ven_etadD_ifg_signfix.dim")):
        cache = out / f"cells_{tag}_{ml_az}x{ml_rg}.npz"
        if cache.exists():
            z = np.load(cache)
            res[tag] = (z["G"], z["T"], z["fill"])
            continue
        G, T, fill, _, corr = r5b.cells_from_products(str(D / ifg), diag, cl)
        print(tag, "link corr", round(float(corr), 3))
        np.savez(cache, G=G, T=T, fill=fill)
        res[tag] = (G, T, fill)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fill = res["before"][2] & res["after"][2]
    panels = [("classical", res["before"][1]), ("GSLC before", res["before"][0]), ("GSLC after", res["after"][0])]
    for name, Z in panels:
        ph = np.where(fill & (np.abs(Z) > 0), np.angle(Z), np.nan)
        cmap = plt.get_cmap("twilight").copy()
        rgb = cmap((ph + np.pi) / (2 * np.pi))
        rgb[np.isnan(ph)] = (0.969, 0.965, 0.945, 1.0)
        plt.imsave(out / f"ifg_{name.replace(' ', '_')}_{ml_az}x{ml_rg}.png", rgb)
    print("done", sorted(p.name for p in out.glob("*.png")))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "ifg_png", *(int(a) for a in sys.argv[2:4]))
