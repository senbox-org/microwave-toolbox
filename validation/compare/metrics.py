"""
Engine-agnostic metrics for cross-tool comparison.

Every function takes plain numpy arrays and knows nothing about SNAP, ISCE3 or any other engine.
That is what lets a new tool be added without touching this file.

Each metric records WHAT IT CANNOT SEE, because choosing a metric blind to the quantity under test
is the single easiest way to produce a confident null result. Measured example: an ETAD atmospheric
correction moved `circular_std_multilooked` by -8.3% while leaving `residue_density` and
`local_phase_coherence` unchanged — the first two are local-noise measures and a smooth
one-cycle-per-scene field is invisible to them.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

TWO_PI = 2.0 * math.pi


@dataclass(frozen=True)
class Metric:
    """A metric result plus the scope statement that must travel with the number."""
    name: str
    value: float
    unit: str
    blind_to: str
    detail: dict | None = None

    def __str__(self) -> str:
        return f"{self.name} = {self.value:.6g} {self.unit}"


def wrap(a: np.ndarray) -> np.ndarray:
    """Wrap to (-pi, pi]."""
    return np.angle(np.exp(1j * a))


def valid_mask(*arrays: np.ndarray) -> np.ndarray:
    """Finite and not the (0,0) geocoding fill, across every supplied array."""
    m = np.ones(arrays[0].shape, dtype=bool)
    for a in arrays:
        m &= np.isfinite(a)
    return m


def complex_valid(i: np.ndarray, q: np.ndarray) -> np.ndarray:
    """Geocoded products fill with (0,0); such samples must never enter a sum."""
    return valid_mask(i, q) & ~((i == 0) & (q == 0))


# --------------------------------------------------------------------------------------
# Agreement between two engines
# --------------------------------------------------------------------------------------

def phase_difference_smoothness(za: np.ndarray, zb: np.ndarray,
                                mask: np.ndarray | None = None) -> Metric:
    """
    Mean adjacent-pixel gradient of arg(za * conj(zb)), in rad/px.

    THE headline metric for "do two geocode-first implementations agree". Two products can differ by
    a large constant or a smooth ramp (different flattening reference, different absolute phase datum)
    and still be equivalent; what matters is that the difference is a SMOOTH FIELD rather than noise.
    A uniformly random difference gives ~pi/2 = 1.571 rad/px, so a value far below that indicates real
    agreement up to a smooth term.
    """
    d = np.angle(za * np.conj(zb))
    if mask is None:
        mask = np.isfinite(d)
    g = []
    for axis in (0, 1):
        dd = wrap(np.diff(d, axis=axis))
        mm = mask if axis is None else np.logical_and(
            np.take(mask, range(dd.shape[axis]), axis=axis),
            np.take(mask, range(1, dd.shape[axis] + 1), axis=axis))
        g.append(np.abs(dd[mm]))
    allg = np.concatenate([x for x in g if x.size])
    return Metric("phase_difference_smoothness", float(np.mean(allg)), "rad/px",
                  blind_to="a constant offset or a smooth ramp between the two products — which is "
                           "usually the intended tolerance, not an error",
                  detail={"random_reference": math.pi / 2,
                          "p50": float(np.percentile(allg, 50)),
                          "p95": float(np.percentile(allg, 95))})


def amplitude_ratio(a: np.ndarray, b: np.ndarray, mask: np.ndarray | None = None) -> Metric:
    """Median |a|/|b|. Tests geolocation and radiometry, says nothing about phase."""
    if mask is None:
        mask = valid_mask(a, b) & (np.abs(b) > 0)
    r = np.abs(a[mask]) / np.abs(b[mask])
    return Metric("amplitude_ratio", float(np.median(r)), "ratio",
                  blind_to="phase entirely; a phase-corrupted product can have a perfect ratio",
                  detail={"p5": float(np.percentile(r, 5)), "p95": float(np.percentile(r, 95))})


def rms_difference(a: np.ndarray, b: np.ndarray, mask: np.ndarray | None = None,
                   de_median: bool = True) -> Metric:
    """
    RMS of (a - b), optionally after removing the median offset.

    For unwrapped phase or displacement, de_median must stay True: the datum is arbitrary, so a
    constant offset is not an error.
    """
    if mask is None:
        mask = valid_mask(a, b)
    d = a[mask] - b[mask]
    if de_median:
        d = d - np.median(d)
    return Metric("rms_difference", float(np.sqrt(np.mean(d ** 2))), "input units",
                  blind_to="a constant offset when de_median is set (deliberately)",
                  detail={"de_median": de_median, "n": int(mask.sum())})


# --------------------------------------------------------------------------------------
# Interferogram quality
# --------------------------------------------------------------------------------------

def residue_density(phase: np.ndarray, mask: np.ndarray | None = None) -> Metric:
    """
    2x2 loop residues per 10^4 px — how unwrappable the field is.

    BLIND to smooth long-wavelength phase. Do not use it to judge an atmospheric correction.
    """
    d1 = wrap(phase[:-1, 1:] - phase[:-1, :-1])
    d2 = wrap(phase[1:, 1:] - phase[:-1, 1:])
    d3 = wrap(phase[1:, :-1] - phase[1:, 1:])
    d4 = wrap(phase[:-1, :-1] - phase[1:, :-1])
    r = np.rint((d1 + d2 + d3 + d4) / TWO_PI)
    if mask is None:
        m = np.ones(r.shape, dtype=bool)
    else:
        m = mask[:-1, :-1] & mask[1:, 1:] & mask[:-1, 1:] & mask[1:, :-1]
    if m.sum() == 0:
        return Metric("residue_density", float("nan"), "per 1e4 px", "no valid samples")
    return Metric("residue_density", float((np.abs(r)[m] > 0).mean() * 1e4), "per 1e4 px",
                  blind_to="smooth scene-scale phase; it measures LOCAL ambiguity only",
                  detail={"n": int(m.sum())})


def local_phase_coherence(z: np.ndarray, k: int = 5, mask: np.ndarray | None = None) -> Metric:
    """
    |mean(exp(j*phi))| over a k x k window — phase-only, so amplitude cannot flatter it.

    BLIND to smooth long-wavelength phase, same as residue_density.
    """
    from numpy.lib.stride_tricks import sliding_window_view
    if mask is None:
        mask = complex_valid(z.real, z.imag)
    u = np.where(mask & (np.abs(z) > 0), z / np.maximum(np.abs(z), 1e-12), 0)
    s = sliding_window_view(u, (k, k)).sum(axis=(-1, -2))
    n = sliding_window_view(mask.astype(float), (k, k)).sum(axis=(-1, -2))
    with np.errstate(invalid="ignore", divide="ignore"):
        c = np.abs(s) / np.maximum(n, 1)
    c = np.where(n >= k * k * 0.75, c, np.nan)
    return Metric("local_phase_coherence", float(np.nanmean(c)), "unitless",
                  blind_to="smooth scene-scale phase; and it is positively biased where the window "
                           "is truncated (scene edges, fill boundaries)",
                  detail={"window": k})


def circular_std_multilooked(z: np.ndarray, block: int = 32,
                             mask: np.ndarray | None = None) -> Metric:
    """
    Circular standard deviation of the heavily-multilooked phase.

    THE metric for long-wavelength phase — atmospheric corrections, orbital ramps, anything smooth.
    Averaging complex samples in `block` x `block` cells suppresses local noise, leaving the
    scene-scale field; a correction that removes real atmosphere lowers this.

    BLIND to local noise and to per-pixel errors, i.e. the exact complement of residue_density.
    """
    if mask is None:
        mask = complex_valid(z.real, z.imag)
    h, w = (z.shape[0] // block) * block, (z.shape[1] // block) * block
    zz = np.where(mask, z, 0)[:h, :w].reshape(h // block, block, w // block, block)
    cnt = mask[:h, :w].reshape(h // block, block, w // block, block).sum(axis=(1, 3))
    acc = zz.sum(axis=(1, 3))
    ok = cnt > block * block * 0.5
    if ok.sum() == 0:
        return Metric("circular_std_multilooked", float("nan"), "rad", "no valid blocks")
    ph = np.angle(acc[ok] / cnt[ok])
    R = float(np.abs(np.mean(np.exp(1j * ph))))
    return Metric("circular_std_multilooked", math.sqrt(-2.0 * math.log(max(R, 1e-12))), "rad",
                  blind_to="local noise and per-pixel errors — the complement of residue_density",
                  detail={"block": block, "concentration_R": R, "n_blocks": int(ok.sum())})


def safe_multilook_factor(z: np.ndarray, factors=(1, 2, 4, 8, 16, 32)) -> Metric:
    """
    Largest multilook factor that does not begin to smear real signal.

    Applies each factor, then measures the block-to-block phase step. That step FALLS while noise
    dominates and RISES once cells are large enough to average across genuine signal, so the minimum
    is the optimum. Measuring raw adjacent-pixel differences instead is misleading: at low coherence
    they are dominated by noise and suggest that any averaging aliases, which is wrong.
    """
    best, best_p95, table = None, float("inf"), {}
    for b in factors:
        h, w = (z.shape[0] // b) * b, (z.shape[1] // b) * b
        m = z[:h, :w].reshape(h // b, b, w // b, b).mean(axis=(1, 3))
        ph = np.angle(m)
        d = np.concatenate([np.abs(wrap(np.diff(ph, axis=a))).ravel() for a in (0, 1)])
        d = d[np.isfinite(d)]
        if d.size == 0:
            continue
        p95 = float(np.percentile(d, 95))
        table[b] = {"p50": float(np.percentile(d, 50)), "p95": p95}
        if p95 < best_p95:
            best_p95, best = p95, b
    return Metric("safe_multilook_factor", float(best if best else 1), "pixels",
                  blind_to="anisotropy — it pools both axes; check them separately if the scene has "
                           "a strongly oriented gradient",
                  detail=table)


ALL = {
    "phase_difference_smoothness": phase_difference_smoothness,
    "amplitude_ratio": amplitude_ratio,
    "rms_difference": rms_difference,
    "residue_density": residue_density,
    "local_phase_coherence": local_phase_coherence,
    "circular_std_multilooked": circular_std_multilooked,
    "safe_multilook_factor": safe_multilook_factor,
}
