"""Render a single engine's interferogram. Separate entry point from render_fringes.py, which
requires BOTH engines and crops to their common extent -- that is right for a comparison but wrong
when only one side exists yet, since it would refuse rather than show what is available."""
import sys, re
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
from diff_fringes import read_envi_pair, read_gtiff
from render_fringes import phase_png

src, out, title = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
MAX_PX = 1400
# Decimate ON READ, not after: a full-swath geocoded product is ~500 Mpixels, and materialising
# i, q and the complex array at full size needs ~16 GB, which gets OOM-killed (observed: 137).
z, gt = (read_envi_pair(src, max_px=MAX_PX) if src.is_dir() else read_gtiff(src))
good = np.isfinite(z.real) & np.isfinite(z.imag) & (z != 0)
print(f"shape {z.shape}  valid {100.0*good.sum()/z.size:.1f}%")
ph = np.angle(z[good])
print(f"phase mean {ph.mean():+.4f} rad  circular R {abs(np.mean(np.exp(1j*ph))):.4f}")
print(f"geotransform {gt}")
if src.is_dir():
    for h in src.glob("coh_*.hdr"):
        t = h.read_text(errors="replace")
        g = lambda k: int(re.search(rf"^{k}\s*=\s*(-?\d+)", t, re.I | re.M).group(1))
        W, L, bo = g("samples"), g("lines"), g("byte order")
        d = np.dtype(np.float32).newbyteorder(">" if bo == 1 else "<")
        c = np.fromfile(h.with_suffix(".img"), dtype=d).reshape(L, W)
        cv = c[(c > 0) & np.isfinite(c)]
        if cv.size:
            print(f"coherence mean {cv.mean():.4f} median {np.median(cv):.4f} "
                  f">0.3 {100.0*(cv>0.3).sum()/cv.size:.1f}%")
        break
phase_png(z, out, title, 1400)
