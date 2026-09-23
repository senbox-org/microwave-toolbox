"""Map-grid -> radar-grid complex resampling, and its own phase-error floor.

WHY THIS EXISTS. The parity campaign compares in RADAR geometry so the classical
reference is never resampled. The cost is that the GSLC interferogram - the measurand -
is resampled instead. compare/diff_vs_trad.py records the rule this breaks:

    "Both products MUST share one lattice ... resampling either product to compare would
     inject exactly the interpolation error being measured."

and enforces it by refusing fractional offsets above 0.02 px. Inverse-geocoding is an
arbitrary-fractional resample at every pixel, so that guard cannot apply. The error is
not eliminated, only relocated - therefore it must be MEASURED, and every gate derived
from it must be expressed relative to that floor. roundtrip_floor() is that measurement.

FLOOR STATUS: not yet measured on a GSLC product - no GSLC interferogram exists at the
time this module landed. Plan 2 measures it on the first Venezuela GSLC ifg, and no R4
or R5 number may be quoted before that measurement is recorded here.
"""
from __future__ import annotations

import numpy as np


def _bilinear_complex(field: np.ndarray, rows: np.ndarray, cols: np.ndarray) -> np.ndarray:
    """Bilinear interpolation of a complex field at fractional (row, col) positions.

    Interpolates the REAL and IMAGINARY parts independently - never amplitude/phase,
    which would need unwrapping and is exactly how wrapped-phase resampling goes wrong.
    Positions outside the grid, and cells where any of the four contributors is exactly
    zero (BEAM-DIMAP's no-data fill), return NaN rather than a clamped or partial value.
    """
    h, w = field.shape
    r0 = np.floor(rows).astype(np.int64)
    c0 = np.floor(cols).astype(np.int64)
    fr = rows - r0
    fc = cols - c0
    inside = (r0 >= 0) & (c0 >= 0) & (r0 + 1 < h) & (c0 + 1 < w)
    r0c = np.clip(r0, 0, h - 2)
    c0c = np.clip(c0, 0, w - 2)
    p00 = field[r0c, c0c]
    p01 = field[r0c, c0c + 1]
    p10 = field[r0c + 1, c0c]
    p11 = field[r0c + 1, c0c + 1]
    good = inside & (p00 != 0) & (p01 != 0) & (p10 != 0) & (p11 != 0)
    out = ((1 - fr) * ((1 - fc) * p00 + fc * p01)
           + fr * ((1 - fc) * p10 + fc * p11))
    return np.where(good, out, np.nan + 1j * np.nan)


def _read_geo(dim_path: str):
    """(dx, dy, lon0, lat0) in degrees, dy positive south-increasing, from the .dim affine."""
    import re
    txt = open(dim_path, encoding="utf-8", errors="replace").read()
    m = re.search(r"<IMAGE_TO_MODEL_TRANSFORM>([^<]*)</IMAGE_TO_MODEL_TRANSFORM>", txt)
    if not m:
        raise ValueError(f"no IMAGE_TO_MODEL_TRANSFORM in {dim_path}")
    tr = [float(v) for v in m.group(1).split(",")]
    return tr[0], -tr[3], tr[4], tr[5]


def _load_complex(dim_path: str):
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "compare"))
    from render_2x2 import iq, hdr_info
    data = Path(dim_path[:-4] + ".data")
    ib, qb = iq(data)
    w, h, dt = hdr_info(ib)
    a = np.memmap(ib.with_suffix(".img"), dtype=dt, mode="r", shape=(h, w))
    b = np.memmap(qb.with_suffix(".img"), dtype=dt, mode="r", shape=(h, w))
    return a.astype(np.float64) + 1j * b.astype(np.float64), w, h


def geo_to_index(lats, lons, dx, dy, lon0, lat0):
    """Map lat/lon to fractional ARRAY indices (row, col), integer = pixel CENTRE.

    SNAP's IMAGE_TO_MODEL_TRANSFORM maps image coordinates in which integers are pixel
    CORNERS (CrsGeoCoding.createImageToMapTransform; getPixelsOneByOne evaluates pixel
    (x, y) at image coordinate (x + 0.5, y + 0.5)). _bilinear_complex indexes the array
    with integers at pixel centres, so the affine result must be shifted by -0.5 on both
    axes. Omitting this samples every position half a pixel off.
    """
    cols = (np.asarray(lons) - lon0) / dx - 0.5
    rows = (lat0 - np.asarray(lats)) / dy - 0.5
    return rows, cols


def sample_map_at(dim_path: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Sample a map-grid complex interferogram at arbitrary lat/lon positions."""
    field, w, h = _load_complex(dim_path)
    dx, dy, lon0, lat0 = _read_geo(dim_path)
    rows, cols = geo_to_index(lats, lons, dx, dy, lon0, lat0)
    return _bilinear_complex(field, rows, cols)


def _block_sum(z: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    """Complex block sum; a block containing a NaN or a zero (no-data) sample becomes 0."""
    h, w = (z.shape[0] // ml_az) * ml_az, (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    bad = (~np.isfinite(zz.real) | ~np.isfinite(zz.imag) | (zz == 0)).any(axis=(1, 3))
    return np.where(bad, 0, np.nan_to_num(zz, nan=0.0).sum(axis=(1, 3)))


def roundtrip_stats(sub: np.ndarray, shift=(0.5, 0.5), ml=None) -> dict:
    """Forward-backward resample `sub` by `shift` and compare with the untouched original.

    ml=None compares single looks (the per-pixel cost of bilinear on speckle). ml=(az, rg) sums both
    fields over az x rg blocks BEFORE comparing - the scale R5 actually judges at, because the
    inverse-geocoded interferogram is multilooked to ~100 m cells before any gate runs.
    Returns concentration and RMS-about-the-mean."""
    dr, dc = shift
    bh, bw = sub.shape
    rows, cols = np.mgrid[0:bh, 0:bw].astype(np.float64)
    once = _bilinear_complex(sub, rows + dr, cols + dc)
    twice = _bilinear_complex(once, rows - dr, cols - dc)
    inner = (slice(2, bh - 2), slice(2, bw - 2))    # edge cells have no neighbour to interpolate from
    a, b = sub[inner], twice[inner]
    if ml is not None:
        a, b = _block_sum(a, *ml), _block_sum(b, *ml)
    d = a * np.conj(b)
    good = np.isfinite(d.real) & np.isfinite(d.imag) & (np.abs(d) > 0)
    z = d[good]
    if z.size == 0:
        return {"conc": float("nan"), "rms_rad": float("nan"), "n": 0}
    conc = float(abs(z.sum()) / np.abs(z).sum())
    mp = z.sum()
    centred = z * np.conj(mp / abs(mp)) if mp != 0 else z
    return {"conc": conc, "rms_rad": float(np.sqrt(np.mean(np.angle(centred) ** 2))), "n": int(z.size)}


def roundtrip_floor(dim_path: str, shift: tuple[float, float] = (0.5, 0.5),
                    block: int = 2048, ml=None) -> dict:
    """Measure this sampler's own phase cost on the product's REAL wrapped fringes.

    A central block (not the whole raster) keeps memory bounded on a 23665 x 13582 product while
    still sampling real fringes. (0.5, 0.5) is the worst case for bilinear. Pass ml=(az, rg) for the
    multilooked floor that R4/R5 are actually judged against; ml=None is the single-look cost.
    """
    field, w, h = _load_complex(dim_path)
    r0 = max(0, h // 2 - block // 2)
    c0 = max(0, w // 2 - block // 2)
    sub = np.asarray(field[r0:r0 + block, c0:c0 + block], dtype=np.complex128)
    return roundtrip_stats(sub, shift, ml)


def _selftest() -> int:
    ok = True

    # Case 1: exact-integer sampling of a known analytic field must be exact.
    nyq, nxq = 64, 64
    yy, xx = np.mgrid[0:nyq, 0:nxq]
    field = np.exp(1j * (0.05 * xx + 0.03 * yy))
    got = _bilinear_complex(field, yy.astype(float), xx.astype(float))
    err = np.nanmax(np.abs(np.angle(got * np.conj(field))))
    print(f"integer-position max phase error: {err:.3e} rad")
    if not (err < 1e-12):
        print("  FAIL: integer sampling must be exact"); ok = False

    # Case 2: half-sample bilinear on a smooth ramp must stay well under the 0.05 rad
    # R1 gate, so the sampler itself is not the dominant term there.
    got = _bilinear_complex(field, yy + 0.5, xx + 0.5)
    ref = np.exp(1j * (0.05 * (xx + 0.5) + 0.03 * (yy + 0.5)))
    inner = (slice(0, nyq - 1), slice(0, nxq - 1))
    err = np.nanmax(np.abs(np.angle(got[inner] * np.conj(ref[inner]))))
    print(f"half-sample max phase error on a smooth ramp: {err:.3e} rad")
    if not (err < 5e-3):
        print("  FAIL: half-sample error too large"); ok = False

    # Case 3: out-of-grid positions must be NaN, never silently clamped.
    got = _bilinear_complex(field, np.array([[-1.0, 999.0]]), np.array([[0.0, 0.0]]))
    print("out-of-grid ->", got)
    if not np.all(np.isnan(got)):
        print("  FAIL: out-of-grid must be NaN"); ok = False

    # Case 4: exact closed form with UNEQUAL row/col fractions. Real = y^2, imag = 3 x^2, so the
    # linear interpolant between the floor neighbours is y0^2 + fr(2 y0 + 1) (resp. 3(...)).
    # A row/col swap, floor->round, or a wrong weight all break this; cases 1-2 cannot see them.
    q = np.mgrid[0:16, 0:16]
    qf = (q[0] ** 2).astype(float) + 1j * 3.0 * (q[1] ** 2)
    r, c = np.array([[5.3, 7.9]]), np.array([[9.7, 2.1]])
    got = _bilinear_complex(qf, r, c)
    r0, c0 = np.floor(r), np.floor(c)
    exp = (r0 ** 2 + (r - r0) * (2 * r0 + 1)) + 1j * 3.0 * (c0 ** 2 + (c - c0) * (2 * c0 + 1))
    err = float(np.max(np.abs(got - exp)))
    print(f"unequal-fraction closed-form error: {err:.3e}")
    if not (err < 1e-12):
        print("  FAIL: bilinear weights/orientation wrong"); ok = False

    # Case 5: a zero contributor (BEAM-DIMAP no-data fill) must give NaN, not a partial value.
    z = qf.copy()
    z[6, 10] = 0
    got = _bilinear_complex(z, np.array([[5.3]]), np.array([[9.7]]))
    print("zero-neighbour ->", got)
    if not np.all(np.isnan(got)):
        print("  FAIL: zero-fill contributor must give NaN"); ok = False

    # Case 6: pixel-centre convention. Pixel (row 3, col 5) has its centre at image coordinate
    # (5.5, 3.5) under SNAP's corner-origin affine; it must map back to integer indices (3, 5).
    dx_, dy_, lon0_, lat0_ = 1.25e-4, 1.25e-4, -69.2, 11.4
    lon_c = lon0_ + (5 + 0.5) * dx_
    lat_c = lat0_ - (3 + 0.5) * dy_
    r_, c_ = geo_to_index(np.array([lat_c]), np.array([lon_c]), dx_, dy_, lon0_, lat0_)
    print(f"pixel-centre -> index: ({float(r_[0]):.6f}, {float(c_[0]):.6f})")
    if not (abs(r_[0] - 3) < 1e-6 and abs(c_[0] - 5) < 1e-6):
        print("  FAIL: pixel centre must map to integer array indices"); ok = False

    # Case 7: the round trip on a COHERENT-PLUS-SPECKLE field (signal 1 + unit complex noise, i.e.
    # coherence^2 ~ 0.33, typical of the real pair). Per pixel the half-pixel double-bilinear smears
    # the phase (rms ~ 1 rad); after a 7x30 multilook both fields agree to ~0.02 rad - the scale R5
    # judges at. A zero-mean speckle field is the wrong test: its multilooked phase is pure noise.
    rng = np.random.default_rng(11)
    ny7, nx7 = 210, 600
    fr = np.exp(1j * np.add.outer(np.linspace(0, 6, ny7), np.linspace(0, 18, nx7)))
    sp = fr * (1.0 + rng.normal(size=(ny7, nx7)) + 1j * rng.normal(size=(ny7, nx7)))
    single = roundtrip_stats(sp, (0.5, 0.5))
    looked = roundtrip_stats(sp, (0.5, 0.5), ml=(7, 30))
    print(f"speckle round trip: single-look rms {single['rms_rad']:.3f} rad, 7x30 multilook rms {looked['rms_rad']:.3f} rad")
    if not (single["rms_rad"] > 0.5 and looked["rms_rad"] < 0.05 and looked["conc"] > 0.99):
        print("  FAIL: single-look floor must be large and the multilooked floor small"); ok = False

    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
