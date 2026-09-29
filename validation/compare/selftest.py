"""
Self-tests for the metric library. No engines, no containers, no SAR data.

Each metric is checked against a synthetic field whose answer is known analytically, and — more
importantly — each is checked to be BLIND to what it claims to be blind to. A metric that silently
responds to the wrong quantity is worse than no metric, because it produces a confident wrong
conclusion. Run: python -m compare.selftest
"""
from __future__ import annotations

import math
import sys

import numpy as np

from . import metrics as M

FAILURES: list[str] = []


def check(cond: bool, msg: str) -> None:
    if cond:
        print(f"  ok   {msg}")
    else:
        print(f"  FAIL {msg}")
        FAILURES.append(msg)


def synth(n: int = 256, coh: float = 1.0, ramp_cycles: float = 0.0, seed: int = 0) -> np.ndarray:
    """Unit-amplitude complex field: a smooth ramp of `ramp_cycles` across the scene, plus noise."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:n, 0:n]
    phase = M.TWO_PI * ramp_cycles * (x / n)
    z = np.exp(1j * phase)
    if coh < 1.0:
        noise = rng.normal(size=(n, n)) + 1j * rng.normal(size=(n, n))
        z = coh * z + math.sqrt(max(1.0 - coh * coh, 0.0)) * noise / math.sqrt(2)
    return z.astype(np.complex128)


def main() -> int:
    print("phase_difference_smoothness")
    a = synth(ramp_cycles=1.0)
    check(M.phase_difference_smoothness(a, a).value < 1e-9,
          "identical products give zero difference gradient")
    # a pure constant offset must NOT be reported as disagreement
    check(M.phase_difference_smoothness(a, a * np.exp(1j * 1.234)).value < 1e-9,
          "a constant phase offset reads as agreement (it is a datum difference)")
    # a smooth ramp between them is still smooth, i.e. far below the random reference
    b = a * np.exp(1j * M.TWO_PI * 0.5 * (np.mgrid[0:256, 0:256][1] / 256))
    smooth = M.phase_difference_smoothness(a, b).value
    rnd = M.phase_difference_smoothness(a, synth(coh=0.0, seed=7)).value
    check(smooth < 0.05, f"a smooth ramp difference stays low ({smooth:.4f} rad/px)")
    check(rnd > 1.0, f"an unrelated product reads near the random reference ({rnd:.3f} rad/px)")
    check(smooth < rnd / 10, "smooth and random are separated by an order of magnitude")

    print("residue_density")
    clean = synth(coh=1.0, ramp_cycles=2.0)
    noisy = synth(coh=0.2, seed=3)
    r_clean = M.residue_density(np.angle(clean)).value
    r_noisy = M.residue_density(np.angle(noisy)).value
    check(r_clean < 1.0, f"noise-free field has ~no residues ({r_clean:.2f}/1e4)")
    check(r_noisy > 100.0, f"low-coherence field is residue-rich ({r_noisy:.0f}/1e4)")
    # the documented blindness: a smooth ramp must not register
    r_ramp = M.residue_density(np.angle(synth(coh=1.0, ramp_cycles=8.0))).value
    check(r_ramp < 1.0, "BLIND as documented: a smooth 8-cycle ramp adds no residues")

    print("local_phase_coherence")
    check(M.local_phase_coherence(clean).value > 0.99, "coherent field reads ~1")
    check(M.local_phase_coherence(noisy).value < 0.6, "noisy field reads low")
    lc_ramp = M.local_phase_coherence(synth(coh=1.0, ramp_cycles=8.0)).value
    check(lc_ramp > 0.9, "BLIND as documented: a smooth ramp barely affects it")

    print("circular_std_multilooked")
    flat = M.circular_std_multilooked(synth(coh=1.0, ramp_cycles=0.0)).value
    ramped = M.circular_std_multilooked(synth(coh=1.0, ramp_cycles=3.0)).value
    check(flat < ramped, f"SEES long-wavelength phase: flat {flat:.3f} < ramped {ramped:.3f} rad")
    # complement of residue_density: local noise should move it far less than a real ramp
    noisy_std = M.circular_std_multilooked(synth(coh=0.2, seed=11)).value
    check(abs(noisy_std - flat) < abs(ramped - flat),
          "local noise moves it less than a genuine smooth field (it is the complement)")

    print("safe_multilook_factor")
    # noise-dominated at full resolution, smooth underneath => optimum must be > 1
    f = M.safe_multilook_factor(synth(coh=0.25, ramp_cycles=1.0, seed=5)).value
    check(f > 1, f"a noisy scene wants averaging (optimum {int(f)}x)")
    # a steep ramp must NOT recommend heavy averaging
    f_steep = M.safe_multilook_factor(synth(coh=1.0, ramp_cycles=60.0, seed=5)).value
    check(f_steep <= f, f"a steep clean field wants less averaging ({int(f_steep)}x vs {int(f)}x)")

    print("amplitude_ratio / rms_difference")
    check(abs(M.amplitude_ratio(a * 2.0, a).value - 2.0) < 1e-9, "ratio recovers a known scaling")
    check(M.rms_difference(np.angle(a) + 0.7, np.angle(a)).value < 1e-9,
          "de-median removes a constant offset (the datum is arbitrary)")

    print("complex_valid")
    z = synth(64)
    z[0, :] = 0
    check(M.complex_valid(z.real, z.imag)[0].sum() == 0, "(0,0) geocoding fill is excluded")

    print()
    if FAILURES:
        print(f"{len(FAILURES)} FAILURE(S):")
        for f in FAILURES:
            print("  -", f)
        return 1
    print("all metric self-tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
