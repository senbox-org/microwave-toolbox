"""Compare two line-of-sight DISPLACEMENT fields on a common map grid (rung R6).

Both products must be map-projected (a radar-geometry chain is terrain-corrected first). The
reference grid is the first product's; the second is bilinearly sampled onto it - displacement is
a smooth scalar field, so interpolating it is legitimate, unlike a wrapped phase raster.

THE ARBITRARY CONSTANT. Phase unwrapping fixes the field only up to an additive constant: each
snaphu run picks its own reference point, so two chains of the SAME pair differ by an offset that
carries no physical meaning. Every statistic here is therefore computed AFTER removing the median
difference, and that offset is reported separately so it cannot hide a real disagreement.

Usage:
  python disp_compare.py <reference.dim> <other.dim> [--band-a NAME --band-b NAME]
  python disp_compare.py --selftest
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from chain_compare import _band, _memmap, _geo          # noqa: E402

DECIM = 2


def _sample(dim_path: str, band_pat: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Bilinear sample of a scalar band at lat/lon; NaN outside and at no-data (exact 0)."""
    arr, w, h = _memmap(_band(dim_path, band_pat))
    dx, dy, lon0, lat0 = _geo(dim_path)
    rows = (lat0 - lats) / dy - 0.5
    cols = (lons - lon0) / dx - 0.5
    r0 = np.floor(rows).astype(np.int64)
    c0 = np.floor(cols).astype(np.int64)
    inside = (r0 >= 0) & (c0 >= 0) & (r0 + 1 < h) & (c0 + 1 < w)
    rc = np.clip(r0, 0, h - 2)
    cc = np.clip(c0, 0, w - 2)
    fr, fc = rows - r0, cols - c0
    p00 = np.asarray(arr[rc, cc], dtype=np.float64)
    p01 = np.asarray(arr[rc, cc + 1], dtype=np.float64)
    p10 = np.asarray(arr[rc + 1, cc], dtype=np.float64)
    p11 = np.asarray(arr[rc + 1, cc + 1], dtype=np.float64)
    good = inside & (p00 != 0) & (p01 != 0) & (p10 != 0) & (p11 != 0)
    v = (1 - fr) * ((1 - fc) * p00 + fc * p01) + fr * ((1 - fc) * p10 + fc * p11)
    return np.where(good, v, np.nan)


def compare(ref_dim: str, other_dim: str, band_a: str = r"^displacement",
            band_b: str = r"^displacement") -> dict:
    a_arr, w, h = _memmap(_band(ref_dim, band_a))
    dx, dy, lon0, lat0 = _geo(ref_dim)
    ys = np.arange(0, h, DECIM)
    xs = np.arange(0, w, DECIM)
    lats = lat0 - (ys[:, None] + 0.5) * dy + 0.0 * xs[None, :]
    lons = lon0 + (xs[None, :] + 0.5) * dx + 0.0 * ys[:, None]
    a = np.asarray(a_arr[np.ix_(ys, xs)], dtype=np.float64)
    a = np.where(a == 0, np.nan, a)
    b = _sample(other_dim, band_b, lats, lons)
    m = np.isfinite(a) & np.isfinite(b)
    if m.sum() < 100:
        return {"n": int(m.sum())}
    d = b[m] - a[m]
    offset = float(np.median(d))
    dc = d - offset
    denom = np.std(a[m])
    return {
        "n": int(m.sum()),
        "offset_m": offset,
        "rms_m": float(np.sqrt(np.mean(dc ** 2))),
        "mad_m": float(np.median(np.abs(dc))),
        "p95_m": float(np.percentile(np.abs(dc), 95)),
        "corr": float(np.corrcoef(a[m], b[m])[0, 1]),
        "ref_ptp_m": float(np.percentile(a[m], 99.5) - np.percentile(a[m], 0.5)),
        "oth_ptp_m": float(np.percentile(b[m], 99.5) - np.percentile(b[m], 0.5)),
        "ref_std_m": float(denom),
        "rms_over_signal": float(np.sqrt(np.mean(dc ** 2)) / denom) if denom > 0 else float("nan"),
    }


def _selftest() -> int:
    ok = True
    rng = np.random.default_rng(0)
    y, x = np.mgrid[0:200, 0:200]
    signal = 0.05 * np.exp(-((x - 100) ** 2 + (y - 100) ** 2) / (2 * 40.0 ** 2))   # 5 cm lobe

    def stats(a, b):
        d = b - a
        off = np.median(d)
        dc = d - off
        return off, float(np.sqrt(np.mean(dc ** 2))), float(np.corrcoef(a.ravel(), b.ravel())[0, 1])

    off, rms, corr = stats(signal, signal + 0.37)
    print(f"  pure offset      offset {off:+.3f} m  rms {rms:.5f} m  corr {corr:.4f}")
    if not (abs(off - 0.37) < 1e-9 and rms < 1e-9 and corr > 0.999):
        print("    FAIL: an arbitrary constant must be removed entirely"); ok = False

    noisy = signal + 0.37 + rng.normal(0, 0.004, signal.shape)
    off, rms, corr = stats(signal, noisy)
    print(f"  offset + 4 mm    offset {off:+.3f} m  rms {rms:.5f} m  corr {corr:.4f}")
    if not (abs(rms - 0.004) < 0.0005):
        print("    FAIL: noise must survive offset removal"); ok = False

    off, rms, corr = stats(signal, -signal)
    print(f"  sign flipped     offset {off:+.3f} m  rms {rms:.5f} m  corr {corr:.4f}")
    if corr > -0.9:
        print("    FAIL: a sign flip must show as anticorrelation"); ok = False

    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def main(argv) -> int:
    if "--selftest" in argv:
        return _selftest()
    if len(argv) < 3:
        print(__doc__)
        return 2
    ba = argv[argv.index("--band-a") + 1] if "--band-a" in argv else r"^displacement"
    bb = argv[argv.index("--band-b") + 1] if "--band-b" in argv else r"^displacement"
    r = compare(argv[1], argv[2], ba, bb)
    if r.get("n", 0) < 100:
        print(f"FAIL: only {r.get('n', 0)} overlapping valid samples")
        return 1
    print(f"reference {Path(argv[1]).name}  vs  {Path(argv[2]).name}")
    print(f"  overlapping samples   {r['n']:,}")
    print(f"  arbitrary offset      {r['offset_m']:+.4f} m   (unwrapping reference, not a disagreement)")
    print(f"  RMS difference        {r['rms_m'] * 100:.2f} cm")
    print(f"  median abs difference {r['mad_m'] * 100:.2f} cm")
    print(f"  95th percentile       {r['p95_m'] * 100:.2f} cm")
    print(f"  correlation           {r['corr']:.4f}")
    print(f"  LOS range (0.5-99.5%) reference {r['ref_ptp_m'] * 100:.1f} cm, other {r['oth_ptp_m'] * 100:.1f} cm")
    print(f"  RMS / signal std      {r['rms_over_signal']:.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
