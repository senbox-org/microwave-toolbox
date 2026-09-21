"""R4 - per-leg cross-chain registration on Venezuela.

For each leg (reference, secondary) the classical chain's DEBURSTED coregistered intensity and the
GSLC leg inverse-geocoded onto that same radar grid are cross-correlated per tile; the median offset
is the leg's registration error in pixels. Amplitude only, so the phase conventions of the two
chains (deramp, carrier) cannot leak in.

BISTATIC ASYMMETRY (spec C4 / section 7 R4). GSLCGeocodingOp.java:2266-2267 adds
(R - R_ref)/c to the azimuth time (R_ref = slant_range_to_first_pixel) when the bistatic residual is
applied, so the GSLC leg reads a LATER source line than the classical leg by delta = (R-R_ref)/c/dt
lines. In offset_map's convention (b(r) ~ a(r - d)) with a = classical and b = GSLC-on-radar that
shows up as d_row = -delta. The residual after removing the model is therefore d_row + delta. The
GSLC suppresses the term when ETAD azimuth was applied (etad_azimuth_applied=1), so nothing is
modelled then. The derived sign is CHECKED against the data: if the opposite sign fits better, the
gate fails and asks for the derivation to be reviewed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                                          # noqa: E402
from budget import record                                              # noqa: E402
from inverse_geocode import _bilinear_complex, _read_geo, geo_to_index  # noqa: E402
from offset_map import C_LIGHT, median_offsets                         # noqa: E402
from radar_domain import hdr_dtype, radar_latlon                       # noqa: E402
from ven_checks import attr, etad_azimuth_applied                      # noqa: E402

SRC = "venezuela"
STRIP = 504
GATE_PX = 0.1


def pick_leg(bands: list[str], leg: str) -> tuple[str, str]:
    """(i_band, q_band) of one leg from a stack's declared band names. The reference leg is tagged
    _ref or _mst, the secondary _sec<N> or _slv<N>; interferogram/phase-term bands never match."""
    tag = r"_(ref|mst)(?=_|$)" if leg == "ref" else r"_(sec|slv)\d*(?=_|$)"
    ib = [b for b in bands if b.startswith("i_") and "ifg" not in b and re.search(tag, b)]
    qb = [b for b in bands if b.startswith("q_") and "ifg" not in b and re.search(tag, b)]
    if len(ib) != 1 or len(qb) != 1:
        raise ValueError(f"cannot pick the {leg} leg unambiguously: i={ib} q={qb} from {bands}")
    return ib[0], qb[0]


def r4_leg_verdict(d_row: float, d_col: float, delta_model_px: float, bistatic_applied: bool) -> dict:
    resid_row = d_row + delta_model_px if bistatic_applied else d_row
    alt_row = d_row - delta_model_px if bistatic_applied else None
    value = max(abs(resid_row), abs(d_col))
    sign_ok = (not bistatic_applied) or abs(alt_row) >= abs(resid_row) - 0.02
    return {"value_px": value, "resid_row": resid_row, "alt_row": alt_row, "sign_ok": bool(sign_ok),
            "passed": bool(value < GATE_PX and sign_ok)}


def _band_array(dim: str, name: str) -> np.ndarray:
    data = Path(dim).with_suffix(".data")
    hdr = data / f"{name}.hdr"
    t = hdr.read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    return np.memmap(data / f"{name}.img", dtype=hdr_dtype(hdr), mode="r", shape=(h, w))


def _amplitude(dim: str, ib: str, qb: str) -> np.ndarray:
    i, q = _band_array(dim, ib), _band_array(dim, qb)
    out = np.empty(i.shape, np.float32)
    for r0 in range(0, i.shape[0], 1024):
        a = np.asarray(i[r0:r0 + 1024], np.float64)
        b = np.asarray(q[r0:r0 + 1024], np.float64)
        out[r0:r0 + 1024] = np.sqrt(a * a + b * b)      # AMPLITUDE: speckled intensity is dominated by bright points
    return out


def gslc_leg_on_radar(gslc_stack: str, ib: str, qb: str, radar_dim: str, h: int, w: int) -> np.ndarray:
    inten = _amplitude(gslc_stack, ib, qb).astype(np.float64)
    dx, dy, lon0, lat0 = _read_geo(gslc_stack)
    out = np.full((h, w), np.nan, np.float32)
    for r0 in range(0, h - STRIP + 1, STRIP):
        rows, cols = np.mgrid[r0:r0 + STRIP, 0:w]
        lat, lon = radar_latlon(radar_dim, rows, cols)
        ri, ci = geo_to_index(lat, lon, dx, dy, lon0, lat0)
        out[r0:r0 + STRIP] = _bilinear_complex(inten.astype(np.complex128), ri, ci).real
    return out


def run(gslc_stack: str, trad_stack_deb: str, radar_dim: str, gslc_ref_dim: str) -> int:
    declared_g = sorted(ge._declared_bands(gslc_stack))
    declared_t = sorted(ge._declared_bands(trad_stack_deb))
    applied = not etad_azimuth_applied(gslc_ref_dim)          # bistatic residual is ON unless ETAD az applied
    dt = float(attr(trad_stack_deb, "line_time_interval"))
    rs = float(attr(gslc_ref_dim, "range_spacing"))
    ok = True
    for leg in ("ref", "sec"):
        gi, gq = pick_leg(declared_g, leg)
        ti, tq = pick_leg(declared_t, leg)
        A = _amplitude(trad_stack_deb, ti, tq)
        h, w = A.shape
        B = gslc_leg_on_radar(gslc_stack, gi, gq, radar_dim, h, w)
        bands = np.array_split(np.arange(0, h - STRIP + 1, STRIP), 3)
        per_burst = []
        for b in bands:
            r0, r1 = int(b[0]), int(b[-1]) + STRIP
            m = median_offsets(A[r0:r1], B[r0:r1])
            per_burst.append(m)
        allm = median_offsets(A[:(h // STRIP) * STRIP], B[:(h // STRIP) * STRIP])
        delta = (0.5 * w * rs) / C_LIGHT / dt if applied else 0.0
        v = r4_leg_verdict(allm["d_row"], allm["d_col"], delta, applied)
        print(f"R4 {leg}: raw d_row {allm['d_row']:+.3f} d_col {allm['d_col']:+.3f} (n={allm['n']}); "
              f"bistatic model {delta:.3f} lines; residual {v['value_px']:.3f} px; sign_ok={v['sign_ok']}")
        for k, m in enumerate(per_burst):
            print(f"    burst-band {k + 1}: d_row {m['d_row']:+.3f} d_col {m['d_col']:+.3f} n={m['n']}")
        ge.emit_gate(f"r4-{leg}-offset-px", v["passed"], v["value_px"], GATE_PX)
        record("R4", SRC, f"{leg}-offset-px", v["value_px"], GATE_PX, "spec section 7 (R4: 0.1 px)", v["passed"],
               f"raw d_row {allm['d_row']:+.3f}, bistatic model {delta:.3f}, applied={applied}, sign_ok={v['sign_ok']}")
        ok &= v["passed"]
    return 0 if ok else 1


def _selftest() -> int:
    ok = True
    bands = ["i_IW3_VV_ref_23Jun2026", "q_IW3_VV_ref_23Jun2026", "i_IW3_VV_sec1_24Jun2026",
             "q_IW3_VV_sec1_24Jun2026", "i_ifg_IW3_VV_23Jun2026_24Jun2026", "azimuthCarrierPhase_ref"]
    if pick_leg(bands, "ref") != ("i_IW3_VV_ref_23Jun2026", "q_IW3_VV_ref_23Jun2026"):
        print("  FAIL: ref leg")
        ok = False
    if pick_leg(bands, "sec") != ("i_IW3_VV_sec1_24Jun2026", "q_IW3_VV_sec1_24Jun2026"):
        print("  FAIL: sec leg")
        ok = False
    try:
        pick_leg(bands + ["i_IW3_VH_ref_23Jun2026", "q_IW3_VH_ref_23Jun2026"], "ref")
        print("  FAIL: dual-pol must be ambiguous")
        ok = False
    except ValueError:
        pass
    # GSLC leg reads later lines: measured d_row = -delta; the residual after the model is ~0
    v = r4_leg_verdict(-0.045, 0.02, 0.045, True)
    if not (v["passed"] and v["sign_ok"] and abs(v["resid_row"]) < 1e-12):
        print("  FAIL: modelled asymmetry must cancel", v)
        ok = False
    # the OPPOSITE sign in the data must be caught, not silently accepted at the 0.1 px gate
    v = r4_leg_verdict(+0.045, 0.0, 0.045, True)
    if v["sign_ok"] or v["passed"]:
        print("  FAIL: a flipped sign must fail", v)
        ok = False
    v = r4_leg_verdict(0.03, -0.04, 0.0, False)          # ETAD az applied: nothing modelled
    if not (v["passed"] and v["alt_row"] is None):
        print("  FAIL: unmodelled case", v)
        ok = False
    if r4_leg_verdict(0.3, 0.0, 0.0, False)["passed"]:
        print("  FAIL: 0.3 px must fail the 0.1 px gate")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if sys.argv[1:2] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(sys.argv) == 5:
        raise SystemExit(run(*sys.argv[1:5]))
    print("usage: run_r4.py <gslc_stack.dim> <classical_stack_deb.dim> <classical_ifg_deb.dim (radar TPGs)> "
          "<reference_slc.dim>   |  --selftest")
    raise SystemExit(2)
