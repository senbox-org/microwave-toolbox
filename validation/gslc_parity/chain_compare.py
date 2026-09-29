"""Score GSLC interferograms against a terrain-corrected CLASSICAL interferogram of the same pair.

Cross-chain agreement in the MAP domain: every product is compared on the classical product's
own lat/lon grid. The classical product is never resampled; the GSLC products are sampled
identically, so a comparison BETWEEN GSLC variants is unaffected by the sampler.

Written for the ERS Etna residual-ramp A/B (docs/gslc-parity/etna-ramp-ab.md); it takes any
number of labelled GSLC products, so it also serves cross-chain equivalence (rung R5) on any
source whose classical control has been terrain-corrected.

CAVEAT that applies to every use: the classical reference is usually Goldstein-filtered and
terrain-corrected while a GSLC interferogram is unfiltered single look, so the ABSOLUTE
concentration is floor-limited. Numbers are comparable across products scored against the SAME
reference, not across sources.

Reported per GSLC product:

  conc_tile    median over tiles of |sum exp(j*d)| / N within the tile, d = GSLC - classical
               phase. Local agreement, blind to any smooth offset between tiles.
  spread_tile  circular standard deviation (rad) of the per-tile MEAN of d. A SMALL value is
               strong evidence that no scene-scale surface separates the two chains. A LARGE
               value is much weaker evidence than it looks, for three reasons: the statistic is
               spatially blind (it cannot tell a smooth ramp from tile-to-tile noise), each
               tile mean is normalised to unit length so a noisy tile votes as loudly as a
               clean one, and it SATURATES - once the tile means wrap over the full circle it
               stops growing. `spread_sat` is the value a uniform scatter of this many tiles
               would give (an rms expectation, so only tight for large tile counts); at or
               above it the MAGNITUDE is not recoverable, and a large ordered surface and no
               agreement at all both land in the same place.
  conc_global  the same concentration over every valid sample at once (local agreement AND
               the smooth surface together).

A ramp option that is still earning its place must lower spread_tile without lowering
conc_tile. One that lowers conc_tile is absorbing signal.

The sampler is bilinear at the reference posting, which attenuates and aliases dense fringes -
so it flatters whichever arm has the SMOOTHER fringe field. That makes the comparison
conservative when the smoother arm loses, and it is not, as an earlier version of this
docstring claimed, neutral: bilinear interpolation is not linear in phase.

Usage:
  python etna_ramp_ab.py <classical_TC.dim> <label>=<gslc.dim> [<label>=<gslc.dim> ...]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

DECIM = 4          # decimation of the classical grid (20 m -> 80 m posting)
TILE = 32          # tiles of TILE x TILE decimated pixels (~2.5 km at 80 m)
COH_MIN = 0.3      # classical coherence gate
CHUNK = 64         # classical rows per slab


def _hdr(img: Path):
    """(width, height, numpy dtype) from an ENVI .hdr beside the .img."""
    txt = img.with_suffix(".hdr").read_text(errors="replace")

    def val(key):
        m = re.search(rf"^{key}\s*=\s*(\S+)", txt, re.M | re.I)
        if not m:
            raise ValueError(f"{key} missing in {img.with_suffix('.hdr')}")
        return m.group(1)

    w, h = int(val("samples")), int(val("lines"))
    code = int(val("data type"))
    order = int(val("byte order"))
    base = {1: "u1", 2: "i2", 3: "i4", 4: "f4", 5: "f8", 12: "u2"}[code]
    return w, h, np.dtype((">" if order == 1 else "<") + base)


def _band(dim_path: str, pattern: str) -> Path:
    """The .img of the first band in <product>.data whose name matches `pattern`."""
    data = Path(dim_path[:-4] + ".data")
    hits = [p for p in sorted(data.glob("*.img")) if re.search(pattern, p.stem)]
    if not hits:
        raise FileNotFoundError(f"no band matching /{pattern}/ in {data}")
    return hits[0]


def _geo(dim_path: str):
    """(dx, dy, lon0, lat0); dy positive south-increasing."""
    txt = Path(dim_path).read_text(encoding="utf-8", errors="replace")
    m = re.search(r"<IMAGE_TO_MODEL_TRANSFORM>([^<]*)</IMAGE_TO_MODEL_TRANSFORM>", txt)
    if not m:
        raise ValueError(f"no IMAGE_TO_MODEL_TRANSFORM in {dim_path}")
    tr = [float(v) for v in m.group(1).split(",")]
    return tr[0], -tr[3], tr[4], tr[5]


def _memmap(img: Path):
    w, h, dt = _hdr(img)
    return np.memmap(img, dtype=dt, mode="r", shape=(h, w)), w, h


def sample_gslc(dim_path: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Bilinear complex sample of a GSLC interferogram at lat/lon, slab by slab.

    Real and imaginary parts are interpolated independently - never amplitude/phase, which
    would need unwrapping. A cell with a zero contributor (BEAM-DIMAP no-data) yields NaN.
    Rows are processed in slabs so a 19059 x 30978 product never has to be resident.
    """
    ib, qb = _band(dim_path, r"^i_ifg"), _band(dim_path, r"^q_ifg")
    ia, w, h = _memmap(ib)
    qa, _, _ = _memmap(qb)
    dx, dy, lon0, lat0 = _geo(dim_path)

    out = np.full(lats.shape, np.nan + 1j * np.nan, dtype=np.complex128)
    for y0 in range(0, lats.shape[0], CHUNK):
        y1 = min(y0 + CHUNK, lats.shape[0])
        la, lo = lats[y0:y1], lons[y0:y1]
        rows = (lat0 - la) / dy - 0.5            # integer = pixel centre (corner-origin affine)
        cols = (lo - lon0) / dx - 0.5
        r0 = np.floor(rows).astype(np.int64)
        c0 = np.floor(cols).astype(np.int64)
        inside = (r0 >= 0) & (c0 >= 0) & (r0 + 1 < h) & (c0 + 1 < w)
        if not inside.any():
            continue
        rlo = int(max(0, r0[inside].min()))
        rhi = int(min(h - 1, r0[inside].max() + 1))
        slab = (ia[rlo:rhi + 1].astype(np.float64)
                + 1j * qa[rlo:rhi + 1].astype(np.float64))
        rr = np.clip(r0 - rlo, 0, slab.shape[0] - 2)
        cc = np.clip(c0, 0, w - 2)
        fr, fc = rows - r0, cols - c0
        p00, p01 = slab[rr, cc], slab[rr, cc + 1]
        p10, p11 = slab[rr + 1, cc], slab[rr + 1, cc + 1]
        good = inside & (p00 != 0) & (p01 != 0) & (p10 != 0) & (p11 != 0)
        v = ((1 - fr) * ((1 - fc) * p00 + fc * p01) + fr * ((1 - fc) * p10 + fc * p11))
        out[y0:y1] = np.where(good, v, np.nan + 1j * np.nan)
        del slab
    return out


def ref_phase(dim_path: str):
    """Reference wrapped phase, preferring the COMPLEX bands over a Phase raster.

    A terrain-corrected reference may carry Phase only as a VIRTUAL band (atan2(q, i)) with no
    raster on disk - which is the right construction, because terrain correction resamples, and
    interpolating a wrapped phase raster mixes values across the +-pi cut. Where i/q exist we
    rebuild the phase from them; only if they are absent do we fall back to a materialised Phase
    band, which was necessarily produced by interpolating wrapped phase and carries artefacts at
    every fringe boundary.
    """
    try:
        ib, qb = _band(dim_path, r"^i_ifg"), _band(dim_path, r"^q_ifg")
    except FileNotFoundError:
        ph, w, h = _memmap(_band(dim_path, r"^Phase_ifg"))
        return ph, w, h, "Phase raster (interpolated wrapped phase)"
    ia, w, h = _memmap(ib)
    qa, _, _ = _memmap(qb)
    return (ia, qa), w, h, "rebuilt from i/q"


def tile_stats(d: np.ndarray, valid: np.ndarray, tile: int = TILE) -> dict:
    """Per-tile concentration and the circular spread of the per-tile means."""
    h = (d.shape[0] // tile) * tile
    w = (d.shape[1] // tile) * tile
    z = np.where(valid, np.exp(1j * d), 0.0)[:h, :w]
    n = valid[:h, :w].astype(np.float64)
    zb = z.reshape(h // tile, tile, w // tile, tile).sum(axis=(1, 3))
    nb = n.reshape(h // tile, tile, w // tile, tile).sum(axis=(1, 3))
    keep = nb >= 0.25 * tile * tile
    if not keep.any():
        return {"conc_tile": float("nan"), "spread_tile": float("nan"), "tiles": 0}
    conc = np.abs(zb[keep]) / nb[keep]
    means = zb[keep] / np.abs(zb[keep])
    n = means.size
    # clamp: for a perfectly concentrated set |sum|/n can exceed 1 by a float epsilon, and
    # sqrt(-2*log(r)) is then NaN rather than 0
    r = min(1.0, float(abs(means.sum()) / n))                      # mean resultant length
    spread = float(np.sqrt(-2.0 * np.log(max(r, 1e-12))))          # circular std, rad
    # Saturation reference: n unit vectors scattered uniformly give |sum|/n ~ 1/sqrt(n) (rms), so
    # any spread at or above this is indistinguishable from "no agreement at all" and its
    # MAGNITUDE means nothing. Reported so the number cannot be quoted as a surface amplitude.
    spread_sat = float(np.sqrt(-2.0 * np.log(1.0 / np.sqrt(n))))
    return {"conc_tile": float(np.median(conc)), "spread_tile": spread,
            "spread_sat": spread_sat, "saturated": spread >= 0.95 * spread_sat, "tiles": int(n)}


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    ref_dim = argv[1]
    products = [a.split("=", 1) for a in argv[2:]]

    ph, w, h, how = ref_phase(ref_dim)
    coh, _, _ = _memmap(_band(ref_dim, r"^coh_"))
    dx, dy, lon0, lat0 = _geo(ref_dim)

    ys = np.arange(0, h, DECIM)
    xs = np.arange(0, w, DECIM)
    lats = lat0 - (ys[:, None] + 0.5) * dy + 0.0 * xs[None, :]
    lons = lon0 + (xs[None, :] + 0.5) * dx + 0.0 * ys[:, None]
    if isinstance(ph, tuple):
        ref_ph = np.angle(np.asarray(ph[0][np.ix_(ys, xs)], dtype=np.float64)
                          + 1j * np.asarray(ph[1][np.ix_(ys, xs)], dtype=np.float64))
        ref_nonzero = (np.asarray(ph[0][np.ix_(ys, xs)], dtype=np.float64) != 0)
    else:
        ref_ph = np.asarray(ph[np.ix_(ys, xs)], dtype=np.float64)
        ref_nonzero = (ref_ph != 0)
    ref_coh = np.asarray(coh[np.ix_(ys, xs)], dtype=np.float64)
    ref_ok = np.isfinite(ref_ph) & ref_nonzero & (ref_coh >= COH_MIN)
    print(f"reference {Path(ref_dim).name}: {w} x {h}, decimated {lats.shape[1]} x {lats.shape[0]}, "
          f"{int(ref_ok.sum())} samples with coherence >= {COH_MIN}  [phase {how}]")

    for label, dim in products:
        z = sample_gslc(dim, lats, lons)
        valid = ref_ok & np.isfinite(z.real) & np.isfinite(z.imag) & (z != 0)
        d = np.angle(z) - ref_ph
        st = tile_stats(d, valid)
        zz = np.where(valid, np.exp(1j * d), 0.0)
        cg = float(abs(zz.sum()) / max(int(valid.sum()), 1))
        # a global convention flip shows up as the conjugate agreeing instead
        dflip = -np.angle(z) - ref_ph
        stf = tile_stats(dflip, valid)
        print(f"\n[{label}] {Path(dim).name}")
        print(f"  valid samples   {int(valid.sum())}")
        print(f"  conc_tile       {st['conc_tile']:.4f}   (median over {st['tiles']} tiles)")
        print(f"  spread_tile     {st['spread_tile']:.4f} rad"
              + (f"   SATURATED (uniform scatter of {st['tiles']} tiles = {st['spread_sat']:.4f})"
                 f" - magnitude not recoverable, read only as 'large'"
                 if st['saturated'] else f"   (saturates at {st['spread_sat']:.4f})"))
        print(f"  conc_global     {cg:.4f}")
        print(f"  conc_tile(conj) {stf['conc_tile']:.4f}   (convention check: must be lower)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
