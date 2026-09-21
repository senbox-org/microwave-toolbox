"""P7 / P8 / R5 for Venezuela, in the RADAR domain.

  p7  <gslc_ifg.dim>                         inverse-geocode phase-error floor on the real GSLC fringes
  p8  <classical_alt.dim> <classical.dim>    classical-vs-classical reproducibility floor
  r5  <gslc_ifg.dim> <classical.dim> <floor_conc>   GSLC vs classical, judged against that floor

Both products are complex (i/q) interferograms with flat-earth and topographic phase removed and
NO residual-ramp option. Fields are multilooked to ~100 m ground cells (7 azimuth x 30 range: the
window Interferogram.cohWinSizeMeters=100 produced on this IW3 pair, incidence 43.9 deg) BEFORE the gates run: a
single-look GSLC-vs-classical comparison measures speckle decorrelation, not agreement.
"""
from __future__ import annotations

import contextlib
import io
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                                    # noqa: E402
from budget import record                                        # noqa: E402
from closure import multilook                                    # noqa: E402
from inverse_geocode import _bilinear_complex, roundtrip_floor   # noqa: E402
from radar_domain import gslc_to_radar                           # noqa: E402

ML_AZ, ML_RG = 7, 30
STRIP = ML_AZ * 72                    # 504 radar rows per strip
SRC = "venezuela"
PROV_SPEC = "spec section 7 (R5)"
PROV_PROV = "PROVISIONAL: chosen before measurement, no prior data"


def gate_metrics(G, T, cohG=None, cohT=None) -> dict:
    """Run the shared gate computation and return the raw numbers; its own printed GATE lines
    use the withdrawn 0.85 priors, so they are swallowed."""
    m: dict = {}
    with contextlib.redirect_stdout(io.StringIO()):
        ge.compute_gates(G, T, cohG, cohT, metrics_out=m)
    return m


def r5_verdict(m: dict, floor_conc: float) -> list[tuple]:
    """(gate, value, threshold, provenance, passed) rows. R5 claims GSLC agrees with the classical
    chain as closely as the classical chain agrees with itself: concentration ratio to the floor."""
    rows = []
    conc = m.get("phase-residual-conc", float("nan"))
    ratio = conc / floor_conc if floor_conc and floor_conc > 0 else float("nan")
    rows.append(("conc-over-floor", ratio, 0.90, PROV_PROV, bool(ratio >= 0.90)))
    for g in ("gx-median-ratio", "gy-median-ratio"):
        v = m.get(g, float("nan"))
        rows.append((g, v, 2.0, PROV_SPEC, bool(v <= 2.0)))
    if "coherence-parity" in m:
        v = abs(m["coherence-parity"])
        rows.append(("coherence-parity-abs", v, 0.05, PROV_PROV, bool(v <= 0.05)))
    return rows


def _load(dim):
    i, q, w, h = ge.load_complex_ifg(dim)
    return i, q, w, h


def _strip_ml(zi, zq, r0, nr):
    z = np.asarray(zi[r0:r0 + nr], np.float64) + 1j * np.asarray(zq[r0:r0 + nr], np.float64)
    return multilook(z, ML_AZ, ML_RG)


def radar_pair_ml(dim_g, dim_t, gslc_is_map: bool):
    """Multilooked (G, T) on the classical radar grid. dim_t is always a radar-geometry ifg;
    dim_g is either a map-grid GSLC ifg (resampled onto the radar grid) or a second radar ifg."""
    ti, tq, tw, th = _load(dim_t)
    if gslc_is_map:
        gi, gq, gw, gh = _load(dim_g)
        field = np.asarray(gi[:], np.float64) + 1j * np.asarray(gq[:], np.float64)
    else:
        gi, gq, gw, gh = _load(dim_g)
        if (gw, gh) != (tw, th):
            raise ValueError(f"radar grids differ: {(gw, gh)} vs {(tw, th)}")
    Gs, Ts = [], []
    for r0 in range(0, th - STRIP + 1, STRIP):
        Ts.append(_strip_ml(ti, tq, r0, STRIP))
        if gslc_is_map:
            z = gslc_to_radar(field, dim_g, dim_t, r0, STRIP, 0, tw)
            Gs.append(multilook(np.nan_to_num(z, nan=0.0), ML_AZ, ML_RG))
        else:
            Gs.append(_strip_ml(gi, gq, r0, STRIP))
    return np.vstack(Gs), np.vstack(Ts)


MAP_ML = (7, 43)      # ~100 m x ~100 m in map cells of 14.1 m (N) x 2.34 m (E) - the R5 scale on the map grid


def cmd_p7(gslc_dim):
    """P7. Two floors, because they answer different questions:
      single-look: what one half-pixel bilinear round trip costs a SPECKLED interferogram pixel
                   (~1 rad rms on real data - it scrambles speckle, it does not damage fringes);
      multilooked: the same round trip followed by the ~100 m multilook that R4/R5 apply before any
                   gate runs. THIS is the floor R4/R5 numbers are judged against."""
    single = roundtrip_floor(gslc_dim)
    looked = roundtrip_floor(gslc_dim, ml=MAP_ML)
    print("P7 single-look round trip :", single)
    print(f"P7 multilooked {MAP_ML} round trip:", looked)
    src = "measured (this sampler on real fringes)"
    record("P7", SRC, "roundtrip-conc-single", single["conc"], None, src, None, f"n={single['n']}")
    record("P7", SRC, "roundtrip-rms-rad-single", single["rms_rad"], None, src, None,
           "per-pixel speckle cost of bilinear; NOT the floor R5 is judged against")
    record("P7", SRC, "roundtrip-conc-ml", looked["conc"], None, src, None, f"ML {MAP_ML}, n={looked['n']}")
    record("P7", SRC, "roundtrip-rms-rad-ml", looked["rms_rad"], None, src, None,
           f"the floor R4/R5 are judged against (ML {MAP_ML} map cells ~ 100 m)")
    return 0


def cmd_p8(alt_dim, base_dim):
    G, T = radar_pair_ml(alt_dim, base_dim, gslc_is_map=False)
    m = gate_metrics(G, T)
    print("P8 classical reproducibility floor:", m)
    record("P8", SRC, "classical-floor-conc", m["phase-residual-conc"], None,
           "measured (Back-Geocoding BICUBIC vs BISINC)", None,
           "OPTIMISTIC: same speckle and geometry, only the resampling kernel differs")
    record("P8", SRC, "classical-floor-rms-rad", m["residual-rms-rad"], None, "measured", None)
    return 0


def cmd_r5(gslc_dim, classical_dim, floor_conc):
    G, T = radar_pair_ml(gslc_dim, classical_dim, gslc_is_map=True)
    m = gate_metrics(G, T)
    print("R5 raw metrics:", m)
    ok = True
    for gate, v, thr, prov, passed in r5_verdict(m, floor_conc):
        ge.emit_gate(f"r5-{gate}", passed, v, thr)
        record("R5", SRC, gate, v, thr, prov, passed)
        ok &= passed
    record("R5", SRC, "phase-residual-conc", m["phase-residual-conc"], None, "measured", None)
    record("R5", SRC, "residual-rms-rad", m["residual-rms-rad"], None, "measured", None)
    return 0 if ok else 1


def _selftest() -> int:
    ok = True
    rng = np.random.default_rng(4)
    H, W = 168, 216
    ph = np.add.outer(np.linspace(0, 8, H), np.linspace(0, 12, W))
    T = np.exp(1j * ph) * (1 + 0.1 * rng.normal(size=(H, W)))
    G = T * np.exp(1j * 0.05 * rng.normal(size=(H, W)))
    m = gate_metrics(G, T)
    print({k: round(v, 4) for k, v in m.items()})
    if not m["phase-residual-conc"] > 0.95:
        print("  FAIL: near-identical fields must concentrate")
        ok = False
    rows = r5_verdict({"phase-residual-conc": 0.95, "gx-median-ratio": 1.2, "gy-median-ratio": 2.5,
                       "coherence-parity": -0.02}, floor_conc=0.99)
    got = {r[0]: r[4] for r in rows}
    print(got)
    if got != {"conc-over-floor": True, "gx-median-ratio": True, "gy-median-ratio": False,
               "coherence-parity-abs": True}:
        print("  FAIL: verdict logic")
        ok = False
    if r5_verdict({"phase-residual-conc": 0.5}, 0.99)[0][4]:
        print("  FAIL: 0.5/0.99 must fail")
        ok = False
    if r5_verdict({"phase-residual-conc": 0.5}, 0.0)[0][4]:
        print("  FAIL: a zero floor must not pass")
        ok = False
    z = np.ones((STRIP, 60), complex)
    if multilook(z, ML_AZ, ML_RG).shape != (72, 2):
        print("  FAIL: strip multilook shape")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(a) == 2 and a[0] == "p7":
        raise SystemExit(cmd_p7(a[1]))
    if len(a) == 3 and a[0] == "p8":
        raise SystemExit(cmd_p8(a[1], a[2]))
    if len(a) == 4 and a[0] == "r5":
        raise SystemExit(cmd_r5(a[1], a[2], float(a[3])))
    print(__doc__)
    raise SystemExit(2)
