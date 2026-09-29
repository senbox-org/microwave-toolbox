"""R4 per-leg registration: amplitude cross-correlation offsets between two co-gridded intensity images."""
from __future__ import annotations

import sys

import numpy as np

C_LIGHT = 299792458.0


def tile_shift(a: np.ndarray, b: np.ndarray) -> tuple[float, float]:
    """(d_row, d_col) such that b(r, c) ~= a(r - d_row, c - d_col); sub-pixel via a 3-point
    parabola on the FFT cross-correlation peak. Inputs are mean-removed and Hann-windowed."""
    if a.shape != b.shape or min(a.shape) < 8:
        raise ValueError("tiles must match and be at least 8x8")
    win = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1]))
    fa = np.fft.rfft2((a - a.mean()) * win)
    fb = np.fft.rfft2((b - b.mean()) * win)
    cc = np.fft.irfft2(fb * np.conj(fa), s=a.shape)
    pr, pc = np.unravel_index(int(np.argmax(cc)), cc.shape)
    h, w = cc.shape

    def refine(m1, p0, p1):
        d = m1 - 2.0 * p0 + p1
        return 0.0 if d == 0 else 0.5 * (m1 - p1) / d

    dr = refine(cc[(pr - 1) % h, pc], cc[pr, pc], cc[(pr + 1) % h, pc])
    dc = refine(cc[pr, (pc - 1) % w], cc[pr, pc], cc[pr, (pc + 1) % w])
    r = pr + dr
    c = pc + dc
    if r > h / 2:
        r -= h
    if c > w / 2:
        c -= w
    return float(r), float(c)


def median_offsets(a: np.ndarray, b: np.ndarray, tile: int = 256, stride: int = 512,
                   min_valid: float = 0.9) -> dict:
    """Median (d_row, d_col) over tiles where both images are >= min_valid non-zero/finite."""
    drs, dcs = [], []
    for r0 in range(0, a.shape[0] - tile + 1, stride):
        for c0 in range(0, a.shape[1] - tile + 1, stride):
            ta, tb = a[r0:r0 + tile, c0:c0 + tile], b[r0:r0 + tile, c0:c0 + tile]
            ok = np.isfinite(ta) & np.isfinite(tb) & (ta > 0) & (tb > 0)
            if ok.mean() < min_valid:
                continue
            # invalid pixels would poison the FFT with NaN: replace them by the tile mean (no signal)
            dr, dc = tile_shift(np.where(ok, ta, ta[ok].mean()), np.where(ok, tb, tb[ok].mean()))
            if np.isfinite(dr) and np.isfinite(dc):
                drs.append(dr)
                dcs.append(dc)
    if not drs:
        return {"n": 0, "d_row": float("nan"), "d_col": float("nan")}
    return {"n": len(drs), "d_row": float(np.median(drs)), "d_col": float(np.median(dcs))}


def bistatic_az_offset_px(slant_range_m, ref_range_m: float, line_interval_s: float):
    """Modelled azimuth offset (lines) of the GSLC bistatic residual: (R - R_ref)/c / dt."""
    return (np.asarray(slant_range_m, dtype=float) - ref_range_m) / C_LIGHT / line_interval_s


def _selftest() -> int:
    rng = np.random.default_rng(3)
    base = rng.gamma(2.0, 1.0, (600, 600))
    # smooth then shift by an exact fractional amount via Fourier shift
    ky = np.fft.fftfreq(600)[:, None]
    kx = np.fft.fftfreq(600)[None, :]
    sm = np.fft.ifft2(np.fft.fft2(base) * np.exp(-((ky ** 2 + kx ** 2) * 40.0))).real
    ok = True
    for (dr, dc) in [(0.0, 0.0), (1.0, -2.0), (0.3, 0.6), (-0.45, 0.25)]:
        shifted = np.fft.ifft2(np.fft.fft2(sm) * np.exp(-2j * np.pi * (ky * dr + kx * dc))).real
        sm_p = sm - sm.min() + 1.0
        sh_p = shifted - sm.min() + 1.0
        m = median_offsets(sm_p, sh_p, tile=256, stride=256)
        err = max(abs(m["d_row"] - dr), abs(m["d_col"] - dc))
        print(f"shift ({dr:+.2f},{dc:+.2f}) -> ({m['d_row']:+.3f},{m['d_col']:+.3f}) err {err:.3f} n={m['n']}")
        if err > 0.05:
            print("  FAIL: sub-pixel offset error > 0.05 px"); ok = False
    # a tile with a NaN hole (no-data / outside the geocoded footprint) must still give a shift
    hole = sm_p.copy()
    hole[100:130, 100:160] = np.nan
    mh = median_offsets(hole, np.fft.ifft2(np.fft.fft2(np.nan_to_num(hole, nan=1.0)) * np.exp(-2j * np.pi * (ky * 1.0 + kx * -2.0))).real,
                        tile=256, stride=256, min_valid=0.9)
    print(f"NaN-holed tiles -> ({mh['d_row']:+.3f},{mh['d_col']:+.3f}) n={mh['n']}")
    if not (mh["n"] > 0 and abs(mh["d_row"] - 1.0) < 0.1 and abs(mh["d_col"] + 2.0) < 0.1):
        print("  FAIL: NaN in a tile must not poison the shift estimate"); ok = False
    off = float(bistatic_az_offset_px(55000.0, 0.0, 0.002055556))
    print(f"bistatic offset over 55 km: {off:.3f} lines")
    if not (0.05 < off < 0.12):
        print("  FAIL: expected ~0.09 lines"); ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
