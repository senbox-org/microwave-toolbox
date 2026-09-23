"""R7: our GSLC against JPL's NISAR L2 GSLC - the campaign's only external reference.

WHAT MAKES THIS RUNG DIFFERENT. Every other rung compares us against our own classical chain, so
a shared mistake would cancel. Here the reference is a third-party implementation (ISCE3), which
is the only way to catch a convention we have wrong everywhere.

THE LATTICE IS NOT SHARED, AND THAT IS ITSELF A RESULT. Both products are EPSG:32611 at 10.0 m
(east) x 5.0 m (north), but the grids are offset by exactly HALF A CELL on both axes: our pixel
CENTRES land on multiples of the spacing, JPL's cell EDGES do. The campaign's own rule - "both
products must share one lattice; resampling either injects exactly the interpolation error being
measured" - therefore cannot be honoured, so:

  * ours is bilinearly resampled onto JPL's centres (real and imaginary independently - never
    amplitude/phase, which would need unwrapping), and
  * the cost of that resampling is MEASURED on our own product (shift by the same half pixel and
    back) and reported beside the result, so no agreement figure is quoted without its floor.

NO-DATA DIFFERS TOO: JPL marks it NaN, SNAP marks it exact zero. Both are excluded.

Usage:
  python nisar_r7_compare.py <ours.dim> <jpl_l2_gslc.h5> [--decim 8]
  python nisar_r7_compare.py --selftest
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from chain_compare import _band, _memmap          # noqa: E402

HH = "science/LSAR/GSLC/grids/frequencyA/HH"
XC = "science/LSAR/GSLC/grids/frequencyA/xCoordinates"
YC = "science/LSAR/GSLC/grids/frequencyA/yCoordinates"


def _transform(dim_path: str):
    """(dx, dy, x0corner, y0corner) from the DIMAP affine; dy positive southward."""
    import re
    txt = Path(dim_path).read_text(encoding="utf-8", errors="replace")
    m = re.search(r"<IMAGE_TO_MODEL_TRANSFORM>([^<]*)</IMAGE_TO_MODEL_TRANSFORM>", txt)
    if not m:
        raise ValueError("no IMAGE_TO_MODEL_TRANSFORM")
    t = [float(v) for v in m.group(1).split(",")]
    return t[0], -t[3], t[4], t[5]


def bilinear_complex(re_arr, im_arr, rows, cols):
    """Bilinear on real and imaginary parts independently; NaN where any contributor is no-data."""
    h, w = re_arr.shape
    r0 = np.floor(rows).astype(np.int64)
    c0 = np.floor(cols).astype(np.int64)
    inside = (r0 >= 0) & (c0 >= 0) & (r0 + 1 < h) & (c0 + 1 < w)
    rc, cc = np.clip(r0, 0, h - 2), np.clip(c0, 0, w - 2)
    fr, fc = rows - r0, cols - c0
    out = np.full(rows.shape, np.nan + 1j * np.nan, dtype=np.complex128)
    p = {}
    for dr in (0, 1):
        for dc in (0, 1):
            p[(dr, dc)] = (np.asarray(re_arr[rc + dr, cc + dc], dtype=np.float64)
                           + 1j * np.asarray(im_arr[rc + dr, cc + dc], dtype=np.float64))
    good = inside.copy()
    for v in p.values():
        good &= (v != 0) & np.isfinite(v.real) & np.isfinite(v.imag)
    val = ((1 - fr) * ((1 - fc) * p[(0, 0)] + fc * p[(0, 1)])
           + fr * ((1 - fc) * p[(1, 0)] + fc * p[(1, 1)]))
    return np.where(good, val, np.nan + 1j * np.nan)


def concentration(z: np.ndarray) -> float:
    z = z[np.isfinite(z.real) & np.isfinite(z.imag) & (np.abs(z) > 0)]
    return float(abs(z.sum()) / np.abs(z).sum()) if z.size else float("nan")


def halfpixel_floor(re_arr, im_arr, rows, cols) -> float:
    """What a half-pixel bilinear shift costs on OUR OWN data: shift there and back, compare."""
    once = bilinear_complex(re_arr, im_arr, rows + 0.5, cols + 0.5)
    back = np.full(rows.shape, np.nan + 1j * np.nan, dtype=np.complex128)
    # shift back using the same sampler on the interpolated field, on the interior only
    h, w = once.shape
    if h < 4 or w < 4:
        return float("nan")
    r = np.arange(1, h - 1)[:, None] * np.ones((1, w - 2))
    c = np.ones((h - 2, 1)) * np.arange(1, w - 1)[None, :]
    back = bilinear_complex(once.real, once.imag, r - 0.5, c - 0.5)
    orig = np.full(back.shape, np.nan + 1j * np.nan, dtype=np.complex128)
    orig = bilinear_complex(re_arr, im_arr, r, c)
    d = orig * np.conj(back)
    return concentration(d)


def _selftest() -> int:
    ok = True
    rng = np.random.default_rng(0)
    n = 256
    # a smooth fringe field: bilinear should reproduce it well; speckle: it should not
    y, x = np.mgrid[0:n, 0:n]
    smooth = np.exp(1j * (0.05 * x + 0.03 * y))
    rows = np.arange(2, n - 2)[:, None] * np.ones((1, n - 4))
    cols = np.ones((n - 4, 1)) * np.arange(2, n - 2)[None, :]
    got = bilinear_complex(smooth.real, smooth.imag, rows, cols)
    exact = smooth[2:n - 2, 2:n - 2]
    c = concentration(got * np.conj(exact))
    print(f"  identity sampling on a smooth field: concentration {c:.6f}")
    if c < 0.9999:
        print("    FAIL: sampling at integer positions must be exact"); ok = False

    shifted = bilinear_complex(smooth.real, smooth.imag, rows + 0.5, cols + 0.5)
    truth = np.exp(1j * (0.05 * (cols + 0.5) + 0.03 * (rows + 0.5)))
    c2 = concentration(shifted * np.conj(truth))
    print(f"  half-pixel shift on a smooth field:  concentration {c2:.6f}")
    if c2 < 0.99:
        print("    FAIL: a gentle fringe field must survive a half-pixel bilinear shift"); ok = False

    speckle = (rng.normal(size=(n, n)) + 1j * rng.normal(size=(n, n)))
    c3 = halfpixel_floor(speckle.real, speckle.imag, rows, cols)
    print(f"  half-pixel round trip on SPECKLE:    concentration {c3:.4f}  (the floor to report)")
    if not (0.0 < c3 < 0.999):
        print("    FAIL: speckle must lose something to a half-pixel round trip"); ok = False

    nan_field = smooth.copy(); nan_field[10:20, 10:20] = np.nan
    got2 = bilinear_complex(nan_field.real, nan_field.imag, rows, cols)
    if np.isfinite(got2[8:22, 8:22]).all():
        print("    FAIL: NaN no-data must propagate"); ok = False
    else:
        print("  NaN no-data propagates")
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def main(argv) -> int:
    if "--selftest" in argv:
        return _selftest()
    if len(argv) < 3:
        print(__doc__)
        return 2
    import h5py
    ours_dim, jpl_h5 = argv[1], argv[2]
    decim = int(argv[argv.index("--decim") + 1]) if "--decim" in argv else 8

    dx, dy, x0c, y0c = _transform(ours_dim)
    ix, wo, ho = _memmap(_band(ours_dim, r"^i_HH"))
    qx, _, _ = _memmap(_band(ours_dim, r"^q_HH"))
    ours_x0 = x0c + dx / 2.0        # first pixel CENTRE
    ours_y0 = y0c - dy / 2.0

    f = h5py.File(jpl_h5, "r")
    xc = f[XC][()]
    yc = f[YC][()]
    hh = f[HH]
    hj, wj = hh.shape
    print(f"ours {wo} x {ho}  centres from ({ours_x0:.1f}, {ours_y0:.1f}) step ({dx}, -{dy})")
    print(f"JPL  {wj} x {hj}  centres from ({xc[0]:.1f}, {yc[0]:.1f}) step "
          f"({xc[1] - xc[0]}, {yc[1] - yc[0]})")
    offx = (xc[0] - ours_x0) / dx
    offy = (ours_y0 - yc[0]) / dy
    print(f"lattice offset in pixels: x {offx:+.3f}  y {offy:+.3f}  "
          f"(fractional {offx - round(offx):+.3f}, {offy - round(offy):+.3f})")

    js = np.arange(0, hj, decim)
    is_ = np.arange(0, wj, decim)
    # our fractional row/col for each sampled JPL centre
    rows = (ours_y0 - yc[js])[:, None] / dy + 0.0 * is_[None, :]
    cols = (xc[is_] - ours_x0)[None, :] / dx + 0.0 * js[:, None]
    keep_r = (rows[:, 0] >= 1) & (rows[:, 0] < ho - 2)
    keep_c = (cols[0, :] >= 1) & (cols[0, :] < wo - 2)
    js, is_ = js[keep_r], is_[keep_c]
    rows, cols = rows[np.ix_(keep_r, keep_c)], cols[np.ix_(keep_r, keep_c)]
    print(f"overlap sampled: {len(is_)} x {len(js)} points (decimation {decim})")

    # h5py allows fancy indexing on ONE axis only, so read row by row (each row is one slab)
    jz = np.empty((len(js), len(is_)), dtype=np.complex128)
    for k, jrow in enumerate(js):
        jz[k] = np.asarray(hh[int(jrow), :])[is_].astype(np.complex128)
    jz = np.where(np.isfinite(jz.real) & np.isfinite(jz.imag) & (jz != 0), jz, np.nan)
    oz = bilinear_complex(ix, qx, rows, cols)
    m = np.isfinite(jz) & np.isfinite(oz)
    print(f"  valid in both: {int(m.sum()):,} ({m.mean() * 100:.1f}%)")
    if m.sum() < 1000:
        print("FAIL: not enough overlapping valid samples")
        return 1

    a, b = np.abs(oz[m]), np.abs(jz[m])
    print(f"\namplitude")
    print(f"  ours  median |z| {np.median(a):.4g}    JPL median |z| {np.median(b):.4g}"
          f"    scale ratio {np.median(a) / np.median(b):.4g}")
    la, lb = np.log10(a + 1e-30), np.log10(b + 1e-30)
    print(f"  log-amplitude correlation: {np.corrcoef(la, lb)[0, 1]:.4f}")

    d = oz[m] * np.conj(jz[m])
    print(f"\nphase")
    print(f"  concentration (single look): {concentration(d):.4f}")
    # block-average to see past speckle
    full = np.where(m, oz * np.conj(jz), 0)
    B = 10
    hh2 = (full.shape[0] // B) * B
    ww2 = (full.shape[1] // B) * B
    blk = full[:hh2, :ww2].reshape(hh2 // B, B, ww2 // B, B).sum(axis=(1, 3))
    print(f"  concentration ({B}x{B} blocks): {concentration(blk.ravel()):.4f}")
    # is the phase difference a smooth ramp (a flattening-convention difference) or flat?
    ang = np.angle(blk)
    yy, xx = np.mgrid[0:blk.shape[0], 0:blk.shape[1]]
    good = np.abs(blk) > 0
    if good.sum() > 100:
        A = np.column_stack([np.ones(good.sum()), xx[good].ravel(), yy[good].ravel()])
        coef, *_ = np.linalg.lstsq(A, np.unwrap(ang[good].ravel()), rcond=None)
        print(f"  block-phase plane fit: {coef[1]:+.4f} rad/block east, {coef[2]:+.4f} rad/block north")
        print(f"    (a flattening-convention difference shows as a strong RANGE ramp)")

    floor = halfpixel_floor(ix, qx,
                            np.arange(1, 402)[:, None] * np.ones((1, 400)) + ho // 2,
                            np.ones((401, 1)) * np.arange(1, 401)[None, :] + wo // 2)
    print(f"\nhalf-pixel resampling floor on our own product: concentration {floor:.4f}")
    print("  (the lattices are half a cell apart, so this shift is unavoidable here)")
    f.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
