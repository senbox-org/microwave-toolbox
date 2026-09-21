"""Score the synthetic pairs (R1, R2-lite). The truth is the range-only Gaussian lobe that
synth.write_second_date injected as B = A * exp(-j*lobe(col)), so a chain's interferogram
(master * conj(secondary)) should equal +lobe(col).

GSLC: the lobe is evaluated at each MAP pixel through the master GSLC's diag_rangeIndex band
(the SOURCE range column that pixel was read from; produced with -Dgslc.diagGeometry=true), so
no inverse geocoding is involved and the truth is exact.
Classical: the debursted radar ifg has range column == raster column.

  gslc <ifg.dim> <master_gslc.dim> <name> [zero]       classical <ifg.dim> <name> [zero]
The lobe constants live in synth.py so the generator and this scorer cannot drift apart.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                                # noqa: E402
from budget import record                                    # noqa: E402
from synth import AMP, CENTRE, SIGMA, _hdr_dtype, lobe_phase, recovery_stats   # noqa: E402

SRC = "venezuela-synth"
BLOCK = 512


def lobe(cols):
    return lobe_phase(cols, AMP, CENTRE, SIGMA)


def stats_from_arrays(z: np.ndarray, range_index: np.ndarray, amp: float = AMP) -> dict:
    """Recovery statistics of a complex interferogram window against the lobe at range_index.
    Pixels with a non-finite or non-positive range index (outside the swath) or zero z are dropped."""
    valid = (np.abs(z) > 0) & np.isfinite(range_index) & (range_index > 0)
    true = lobe_phase(np.where(valid, range_index, 0.0), amp, CENTRE, SIGMA)
    return recovery_stats(np.angle(z), true, valid)


def _accumulate(parts: list[dict]) -> dict:
    n = sum(p["n"] for p in parts)
    w = lambda k: sum(p[k] * p["n"] for p in parts) / n
    return {"n": n, "rms_rad": float(np.sqrt(sum(p["rms_rad"] ** 2 * p["n"] for p in parts) / n)),
            "rms_centred_rad": float(np.sqrt(sum(p["rms_centred_rad"] ** 2 * p["n"] for p in parts) / n)),
            "mean_rad": w("mean_rad"), "retention": w("retention")}


def eval_gslc(ifg_dim: str, master_gslc_dim: str, amp: float = AMP) -> dict:
    i, q, w, h = ge.load_complex_ifg(ifg_dim)
    data = Path(master_gslc_dim).with_suffix(".data")
    hdr = data / "diag_rangeIndex.hdr"
    if not hdr.exists():
        raise FileNotFoundError(f"{hdr} - rebuild the master GSLC with -Dgslc.diagGeometry=true")
    idx = np.memmap(data / "diag_rangeIndex.img", dtype=_hdr_dtype(hdr), mode="r", shape=(h, w))
    parts = []
    for r0 in range(0, h, BLOCK):
        z = np.asarray(i[r0:r0 + BLOCK], np.float64) + 1j * np.asarray(q[r0:r0 + BLOCK], np.float64)
        parts.append(stats_from_arrays(z, np.asarray(idx[r0:r0 + BLOCK], np.float64), amp))
    parts = [p for p in parts if p["n"] > 0]
    return _accumulate(parts)


def eval_classical(ifg_dim: str, amp: float = AMP) -> dict:
    i, q, w, h = ge.load_complex_ifg(ifg_dim)
    cols = np.broadcast_to(np.arange(w, dtype=np.float64) + 1.0, (BLOCK, w))   # +1: index must be > 0
    parts = []
    for r0 in range(0, h, BLOCK):
        z = np.asarray(i[r0:r0 + BLOCK], np.float64) + 1j * np.asarray(q[r0:r0 + BLOCK], np.float64)
        parts.append(stats_from_arrays(z, cols[:z.shape[0]], amp))
    return _accumulate([p for p in parts if p["n"] > 0])


def _report(name: str, s: dict) -> None:
    print(f"{name}: n={s['n']} rms={s['rms_rad']:.4f} centred={s['rms_centred_rad']:.4f} "
          f"mean={s['mean_rad']:+.4f} retention={s['retention']:.4f}")


def _selftest() -> int:
    ok = True
    h, w = 40, 4000
    cols = np.broadcast_to(np.arange(w, dtype=float) + 1.0, (h, w))
    z = np.exp(1j * lobe(cols))
    s = stats_from_arrays(z, cols)
    print(s)
    if not (s["rms_rad"] < 1e-12 and abs(s["retention"] - 1) < 1e-9):
        print("  FAIL: exact lobe must be recovered")
        ok = False
    s = stats_from_arrays(np.exp(1j * 0.5 * lobe(cols)), cols)
    if abs(s["retention"] - 0.5) > 1e-6:
        print("  FAIL: half a lobe must read 0.5 retention", s["retention"])
        ok = False
    z2 = z.copy()
    z2[:, :100] = 0
    if stats_from_arrays(z2, cols)["n"] != h * (w - 100):
        print("  FAIL: zero (no-data) pixels must be dropped")
        ok = False
    bad = cols.copy()
    bad[:, :50] = np.nan
    if stats_from_arrays(z, bad)["n"] != h * (w - 50):
        print("  FAIL: non-finite range index must be dropped")
        ok = False
    acc = _accumulate([stats_from_arrays(z, cols), stats_from_arrays(np.exp(1j * (lobe(cols) + 0.1)), cols)])
    if abs(acc["mean_rad"] - 0.05) > 1e-9:          # (0 + 0.1) / 2 pooled by pixel count
        print("  FAIL: accumulate mean", acc["mean_rad"])
        ok = False
    zero_field = np.ones((h, w), complex)
    on_lobe = cols + 9000.0                    # columns 9001-13000 sit on the lobe (centre 11800)
    if stats_from_arrays(zero_field, on_lobe, 0.0)["rms_rad"] > 1e-12 or stats_from_arrays(zero_field, on_lobe)["rms_rad"] < 0.5:
        print("  FAIL: a zero-phase field scores 0 against zero truth but large against the lobe")
        ok = False
    if stats_from_arrays(zero_field * 0, cols)["n"] != 0:
        print("  FAIL: an all-no-data block must return n=0 without warnings")
        ok = False
    if gate_threshold(None)[0] != 0.05 or gate_threshold(0.0)[0] != 0.05 or abs(gate_threshold(0.04)[0] - 0.08) > 1e-12:
        print("  FAIL: gate_threshold")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


def gate_threshold(floor: float | None) -> tuple[float, str]:
    """R1 gates at the spec's 0.05 rad. R2 gates at 2x the measured generator floor - but the floor
    measured on 2026-09-20 was exactly 0.0, which makes 2x floor unsatisfiable, so the gate is
    max(2*floor, 0.05 rad). That rule was fixed BEFORE any lobe result existed."""
    if floor is None:
        return 0.05, "spec section 7 (R1: 0.05 rad)"
    return max(2.0 * floor, 0.05), ("PROVISIONAL: max(2 x measured generator floor, 0.05 rad); the floor was exactly 0, "
                                     "so 2x floor alone would be unsatisfiable")


def _gates(rung: str, name: str, s: dict, floor: float | None) -> int:
    _report(name, s)
    thr, prov = gate_threshold(floor)
    record(rung, SRC, f"{name}-rms-rad", s["rms_rad"], thr, prov, bool(s["rms_rad"] < thr))
    record(rung, SRC, f"{name}-retention", s["retention"], None, "measured (no gate: first error bar)", None)
    return 0


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    # trailing "zero" = the pair was generated with phi = 0, so the truth is 0, not the lobe. This is
    # how the GENERATOR FLOOR is measured; scoring a zero pair against the lobe just measures the lobe.
    if len(a) in (4, 5) and a[0] == "gslc":
        _report(a[3], eval_gslc(a[1], a[2], 0.0 if a[4:] == ["zero"] else AMP))
        raise SystemExit(0)
    if len(a) in (3, 4) and a[0] == "classical":
        _report(a[2], eval_classical(a[1], 0.0 if a[3:] == ["zero"] else AMP))
        raise SystemExit(0)
    if len(a) == 6 and a[0] == "score-gslc":       # score-gslc <ifg> <master_gslc> <rung> <name> <floor|none>
        fl = None if a[5] == "none" else float(a[5])
        raise SystemExit(_gates(a[3], a[4], eval_gslc(a[1], a[2]), fl))
    if len(a) == 5 and a[0] == "score-classical":  # score-classical <ifg> <rung> <name> <floor|none>
        fl = None if a[4] == "none" else float(a[4])
        raise SystemExit(_gates(a[2], a[3], eval_classical(a[1]), fl))
    print(__doc__)
    raise SystemExit(2)
