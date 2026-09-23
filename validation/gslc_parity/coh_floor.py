"""Assert that an interferogram is actually coherent - the data-side proof a chain worked.

WHY THIS EXISTS. A classical control can complete "successfully" having registered nothing:
WarpOp logs a WARNING when its complex polynomial model fails and silently falls back to the
GCP polynomial, and a warp that found no usable GCPs still writes a full-size product. Log
assertions are weak here - on the Napa run the driver's `RMS|GCP` pattern matched the text of
the FAILURE warning and passed. Coherence is the measurement that cannot be faked: a chain that
registered nothing produces noise, and noise has a coherence near the estimator's own floor.

Usage:
  python coh_floor.py <product.dim> [--min-mean 0.15] [--min-frac 0 --above 0.30]
  python coh_floor.py --selftest

Exit code 0 = passed, 1 = below floor, 2 = usage/read error. Prints the measured values either
way so the number lands in the driver log rather than only the verdict.

THRESHOLD DESIGN. The MEAN is the only criterion on by default. A chain that registered nothing
gives the estimator's noise floor - 1/sqrt(looks), about 0.04-0.07 for the ~700 looks a 100 m
window carries here - while a real but badly decorrelated pair sits near 0.16-0.20 (the Venezuela
closure triple measured 0.28 / 0.18 / 0.16 and those chains were sound). 0.15 separates them.

A "fraction above 0.30" criterion is available via --min-frac but defaults to 0 (off), because it
CANNOT do this job: a real field with mean 0.18 and a tight spread has under 1% of pixels above
0.30, so any fraction threshold high enough to be meaningful also rejects real weak pairs. Two
drafts of this gate got that wrong (0.25, then 0.02) and the selftest caught both.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from chain_compare import _band, _memmap          # noqa: E402  (same-package helpers)

MAX_ROWS = 2000     # decimate to at most this many rows; a floor check needs no more


def measure(dim_path: str) -> dict:
    """Mean/median coherence and the valid fraction, over a decimated sample of the product."""
    coh, w, h = _memmap(_band(dim_path, r"^coh_"))
    ib, _, _ = _memmap(_band(dim_path, r"^i_ifg"))
    step = max(1, h // MAX_ROWS)
    c = np.asarray(coh[::step, ::step], dtype=np.float64)
    a = np.asarray(ib[::step, ::step], dtype=np.float64)
    # A zero complex sample is BEAM-DIMAP no-data, not a coherence of zero; counting it would
    # drag the mean down in proportion to how much empty map corner the product carries.
    valid = np.isfinite(c) & (a != 0)
    n = int(valid.sum())
    if n == 0:
        return {"n": 0, "mean": float("nan"), "median": float("nan"), "frac": float("nan"),
                "width": w, "height": h}
    cv = c[valid]
    return {"n": n, "mean": float(cv.mean()), "median": float(np.median(cv)),
            "valid_frac": float(valid.mean()), "width": w, "height": h, "_cv": cv}


def check(dim_path: str, min_mean: float, min_frac: float, above: float) -> int:
    m = measure(dim_path)
    if m["n"] == 0:
        print(f"FAIL {Path(dim_path).name}: no valid samples at all")
        return 1
    frac = float((m["_cv"] > above).mean())
    print(f"{Path(dim_path).name}: {m['width']}x{m['height']} "
          f"valid {m['valid_frac'] * 100:.1f}%  mean coh {m['mean']:.3f}  "
          f"median {m['median']:.3f}  frac>{above:.2f} {frac:.3f}")
    bad = []
    if m["mean"] < min_mean:
        bad.append(f"mean {m['mean']:.3f} < {min_mean}")
    if frac < min_frac:
        bad.append(f"frac>{above:.2f} {frac:.3f} < {min_frac}")
    if bad:
        print("FAIL coherence floor: " + "; ".join(bad)
              + "  -- the chain may have registered nothing")
        return 1
    print("PASS coherence floor")
    return 0


def _selftest() -> int:
    """The decision logic, on synthetic coherence fields - no product needed."""
    ok = True

    def verdict(cv, min_mean=0.15, min_frac=0.0, above=0.30):
        frac = float((cv > above).mean())
        return not (cv.mean() < min_mean or frac < min_frac)

    rng = np.random.default_rng(0)
    # a working chain: broad distribution centred well above the floor
    good = np.clip(rng.normal(0.40, 0.15, 100000), 0, 1)
    # a degenerate chain: noise coherence, the estimator's own floor for ~200 looks
    noise = np.clip(rng.normal(0.07, 0.03, 100000), 0, 1)
    # the dangerous middle: decorrelated but real - the Venezuela closure pairs lived here
    weak = np.clip(rng.normal(0.18, 0.05, 100000), 0, 1)   # tight: <1% above 0.30
    vzla = np.clip(rng.normal(0.16, 0.08, 100000), 0, 1)
    for name, cv, want in (("working", good, True), ("degenerate", noise, False),
                           ("weak-but-real", weak, True), ("venezuela-like", vzla, True)):
        got = verdict(cv)
        print(f"  {name:14s} mean {cv.mean():.3f} frac>0.3 {(cv > 0.3).mean():.3f} -> "
              f"{'PASS' if got else 'FAIL'}")
        if got != want:
            print(f"    SELFTEST FAIL: expected {'PASS' if want else 'FAIL'}")
            ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def main(argv) -> int:
    if "--selftest" in argv:
        return _selftest()
    if len(argv) < 2:
        print(__doc__)
        return 2
    dim = argv[1]

    def opt(name, default):
        return float(argv[argv.index(name) + 1]) if name in argv else default

    try:
        return check(dim, opt("--min-mean", 0.15), opt("--min-frac", 0.0), opt("--above", 0.30))
    except Exception as e:                                    # noqa: BLE001
        print(f"FAIL reading {dim}: {e}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
