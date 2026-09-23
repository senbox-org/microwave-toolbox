"""Crop-validity gate (spec section 5, Rule 3): does the 3-burst classical fixture reproduce the
gate values of the full-scene classical control over the same ground?

Both inputs are DEBURSTED, RADAR-GEOMETRY interferograms carrying i/q (never the _TC product).
The fixture's rows are located inside the full scene by azimuth TIME, not by index:
row = (t_fixture - t_full) / line_time_interval. The full scene's burst 10 has no secondary data,
so a window that reaches into the last burst's rows is refused.

THRESHOLDS ARE PROVISIONAL: chosen before any measurement, with no prior data behind them. They
are recorded as such in the scorecard.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                       # noqa: E402
from closure import multilook                       # noqa: E402
from ven_checks import attr, snap_time              # noqa: E402

TOL_COH = 0.03            # |delta mean coherence|
TOL_GRAD = 0.02           # |delta median lag-1 gradient|, rad per multilooked cell
ML = 4                    # multilook before gradient statistics
BURST_ROWS = 1358         # debursted rows per burst (13582 / 10), used to fence off burst 10


def row_offset(fix_dim, full_dim, tol: float = 0.1) -> int:
    dt_ = float(attr(full_dim, "line_time_interval"))
    d = (snap_time(attr(fix_dim, "first_line_time")) - snap_time(attr(full_dim, "first_line_time"))) / dt_
    r = round(d)
    if abs(d - r) > tol:
        raise ValueError(f"fixture start is {d:.3f} lines into the full scene - not an integer row; "
                         f"the two products do not share a line lattice")
    return int(r)


def compare_arrays(z_fix, z_full, coh_fix=None, coh_full=None) -> dict:
    """Statistics on two co-registered complex windows. Returns the measured deltas."""
    v = (np.abs(z_fix) > 0) & (np.abs(z_full) > 0)
    out = {"n_valid": int(v.sum())}
    d = z_fix * np.conj(z_full)
    dv = d[v]
    out["pixel_conc"] = float(abs(dv.sum()) / np.abs(dv).sum()) if dv.size else float("nan")
    mf, mu = multilook(z_fix, ML, ML), multilook(z_full, ML, ML)
    vf, vu = np.abs(mf) > 0, np.abs(mu) > 0
    gxf, gyf = ge.median_lag1_gradients(mf, vf)
    gxu, gyu = ge.median_lag1_gradients(mu, vu)
    out["dgx"], out["dgy"] = float(gxf - gxu), float(gyf - gyu)
    if coh_fix is not None and coh_full is not None:
        cf = np.isfinite(coh_fix) & (coh_fix > 0)
        cu = np.isfinite(coh_full) & (coh_full > 0)
        out["coh_fix"] = float(coh_fix[cf].mean())
        out["coh_full"] = float(coh_full[cu].mean())
        out["dcoh"] = out["coh_fix"] - out["coh_full"]
    return out


def gates(m: dict) -> bool:
    ok = True
    if "dcoh" in m:
        ok &= _g("crop-coherence-delta", abs(m["dcoh"]), TOL_COH)
    else:
        ge.emit_skip("crop-coherence-delta", "(coherence band unavailable)")
        ok = False
    ok &= _g("crop-gx-delta", abs(m["dgx"]), TOL_GRAD)
    ok &= _g("crop-gy-delta", abs(m["dgy"]), TOL_GRAD)
    print(f"# informational: pixelwise |mean e^jd| fixture vs full = {m['pixel_conc']:.4f} "
          f"(n={m['n_valid']}); provisional thresholds, no prior data")
    return bool(ok)


def _g(name: str, value: float, tol: float) -> bool:
    passed = bool(value <= tol)
    ge.emit_gate(name, passed, value, tol)
    return passed


def run(fix_dim: str, full_dim: str) -> int:
    fi, fq, fw, fh = ge.load_complex_ifg(fix_dim)
    ui, uq, uw, uh = ge.load_complex_ifg(full_dim)
    if fw != uw:
        print(f"FAIL: widths differ ({fw} vs {uw}) - the fixture is not full range width")
        return 1
    off = row_offset(fix_dim, full_dim)
    if off < 0 or off + fh > uh - BURST_ROWS:
        print(f"FAIL: window rows {off}..{off + fh} of {uh} reach outside the scene or into burst 10")
        return 1
    z_fix = np.asarray(fi[:], np.float64) + 1j * np.asarray(fq[:], np.float64)
    z_full = (np.asarray(ui[off:off + fh], np.float64) + 1j * np.asarray(uq[off:off + fh], np.float64))
    cfp = ge.find_coherence_band(fix_dim, None)
    cup = ge.find_coherence_band(full_dim, None)
    coh_f = coh_u = None
    if cfp and cup:
        coh_f = np.asarray(ge.load_real_band(cfp)[0][:], np.float64)
        coh_u = np.asarray(ge.load_real_band(cup)[0][off:off + fh], np.float64)
    print(f"# fixture {fix_dim}\n# full    {full_dim}\n# rows {off}..{off + fh} of {uh}")
    return 0 if gates(compare_arrays(z_fix, z_full, coh_f, coh_u)) else 1


def _selftest() -> int:
    import tempfile
    ok = True
    with tempfile.TemporaryDirectory() as d:
        def dim(name, t):
            p = Path(d) / name
            p.write_text(f'<MDATTR name="first_line_time" type="utc">{t}</MDATTR>'
                         '<MDATTR name="line_time_interval" type="float64">0.002055556299999998</MDATTR>')
            return p
        full = dim("full.dim", "23-JUN-2026 22:50:52.310630")
        # 3000 lines later: 3000 * 0.0020555563 = 6.1666689 s
        fix = dim("fix.dim", "23-JUN-2026 22:50:58.477299")
        r = row_offset(fix, full)
        print("row offset", r)
        if r != 3000:
            print("  FAIL: row offset")
            ok = False
        bad = dim("bad.dim", "23-JUN-2026 22:50:58.478")
        try:
            row_offset(bad, full)
            print("  FAIL: non-integer row must raise")
            ok = False
        except ValueError:
            pass
    rng = np.random.default_rng(2)
    ph = np.add.outer(np.linspace(0, 5, 128), np.linspace(0, 9, 160))
    z = np.exp(1j * ph)
    coh = np.full(z.shape, 0.5)
    m = compare_arrays(z, z, coh, coh)
    if not (m["pixel_conc"] > 0.999999 and abs(m["dgx"]) < 1e-12 and abs(m["dcoh"]) < 1e-12):
        print("  FAIL: identical windows must agree", m)
        ok = False
    m2 = compare_arrays(z, z * np.exp(1j * 0.05 * np.arange(160))[None, :], coh, coh * 0.9)
    print(f"perturbed: dgx {m2['dgx']:+.4f} dcoh {m2['dcoh']:+.3f}")
    # delta = fixture - full: the full window carries the extra 0.05 rad/px ramp (0.2 rad per
    # 4-px cell) so dgx = -0.2, and its coherence is 10% lower so dcoh = +0.05
    if not (abs(m2["dgx"] + 0.05 * ML) < 5e-3 and abs(m2["dcoh"] - 0.05) < 1e-9):
        print("  FAIL: expected dgx -0.2 and dcoh +0.05")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if len(sys.argv) != 3:
        print("usage: crop_gate.py <fixture_ifg.dim> <full_scene_ifg.dim>   |  --selftest")
        raise SystemExit(2)
    raise SystemExit(run(sys.argv[1], sys.argv[2]))
