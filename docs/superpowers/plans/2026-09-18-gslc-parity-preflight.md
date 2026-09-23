# GSLC Parity Campaign — Plan 1: Pre-flight Harness Fixes

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the validation harness produce trustworthy numbers, then measure where we actually stand across the source matrix — so that no later rung is built on a metric that is silently wrong.

**Architecture:** Ten pre-flight fixes (§13 P1–P10 of the spec), each landed with its own test, followed by the R0 screening sweep and the crop-validity gate. Python harness work lands in `validation/`; two Java changes land in `sar-op-insar` and `sar-op-sar-processing`. Nothing in the rung ladder runs until this plan is green.

**Tech Stack:** Python 3.13 (numpy, memmapped big-endian ENVI + `.dim` XML parsing, no SNAP/JVM dependency), Java 11 (JUnit 4, `LongTestRunner` + `-Denable.long.tests=true`), gpt graphs driven through `mvn exec:java`.

**Spec:** `docs/superpowers/specs/2026-09-18-gslc-insar-parity-campaign-design.md`

## Global Constraints

- **No `git commit` / `git push` by agents.** The user reviews and commits. Every task ends by handing off a described change set, not by committing.
- **Maven runs MUST start from `E:\ESA\microwave-toolbox`** or `-pl` fails with "project not in reactor".
- **A fix in `sar-op-insar` is invisible** to a `sar-op-sar-processing` test until `mvn -pl sar-op-insar install -DskipTests`. Always gate on a log line proving the new code ran.
- **Targeted tests:** `mvn -pl <module> test -Dtest=<X>` runs once (the second surefire execution now lives in a profile activated by `!test`). No workaround needed.
- **File-gated tests** must `assumeTrue(fixture.exists())` so CI without fixtures skips rather than fails.
- **Long-running tests** use `@RunWith(LongTestRunner.class)` + `-Denable.long.tests=true`.
- **No `SubsetOp` in any TOPS graph.** TOPS fixtures come only from `TOPSAR-Split` with subswath + whole bursts.
- **Stripmap azimuth crops must retain ≥ 1539 lines** or `estimateFdcFromData` silently returns null.
- **Per-source teardown:** stage products are deleted once gate values are recorded. ~294 GB free against a ~360 GB campaign.
- Python self-tests follow the existing repo convention: a `--selftest` flag inside the script, not a separate pytest tree.

## File Structure

| File | Responsibility | Status |
|---|---|---|
| `validation/gslc_equivalence.py` | Gate computation + `--selftest` | modify (P1) |
| `validation/compare/render_2x2.py` | Shared ENVI/`.dim` readers, `iq()` band pairing | modify (P4) |
| `validation/compare/seam_steps_guided.py` | Carrier-guided seam step measurement | modify (P2) |
| `validation/gslc_parity/inverse_geocode.py` | Map-grid → radar-grid complex resampling | **create** (P7) |
| `validation/graphs/trad_s1_control.xml` | S1 TOPS classical control chain | modify (P5) |
| `validation/drivers/r0_screening.ps1` | R0 sweep driver | **create** |
| `validation/drivers/crop_validity.ps1` | 3-burst vs full-scene gate | **create** |
| `sar-op-insar/.../InterferogramOp.java` | Coherence window sizing | modify (P3) |
| `sar-op-insar/.../GSLCEquivalenceLongTest.java` | Harness JUnit wrapper | modify (P6) |
| `sar-op-sar-processing/.../GSLCTopsInSarLongTest.java` | TOPS faithful-phase contract | modify (P10) |

---

## Task 1: P1 — `residual-rms-rad` about the mean

The harness documents an absolute phase offset as an allowed datum ambiguity, then fails on it. `wrapped_rms` takes RMS about zero; the sibling `diff_vs_trad.py:168` correctly takes it about the mean.

**Files:**
- Modify: `validation/gslc_equivalence.py:322-326`
- Modify: `validation/README-equivalence.md` (gate table row for `residual-rms-rad`)

**Interfaces:**
- Produces: `wrapped_rms(field, valid) -> float` — RMS of the wrapped residual **about its circular mean**. Signature unchanged; semantics corrected. Consumed by `compute_gates`.

- [ ] **Step 1: Write the failing self-test**

Add to `validation/gslc_equivalence.py`, immediately before `def selftest(`:

```python
def _selftest_constant_offset() -> bool:
    """A constant phase offset is an explicitly-allowed datum ambiguity (see module
    docstring). It must not fail residual-rms-rad."""
    print("\n=== selftest case 4: constant 1.5 rad datum offset (expect ALL gates PASS) ===")
    rng = np.random.default_rng(4)
    ny, nx = 300, 400
    base = np.exp(1j * rng.uniform(-np.pi, np.pi, size=(ny, nx)))
    field = base * np.exp(1j * 1.5) * np.conj(base)   # == exp(1.5j) everywhere
    valid = np.ones((ny, nx), dtype=bool)
    rms = wrapped_rms(field, valid)
    print(f"case 4 wrapped_rms = {rms:.6f} rad (threshold 1.0)")
    ok = rms < 1.0
    print("case 4 result:", "PASS" if ok else "FAIL")
    return ok
```

and register it inside `selftest()` by changing the final return to include it (find the existing `return 0 if (ok1 and not ok2 and conc3_ok) else 1` and replace with):

```python
    ok4 = _selftest_constant_offset()
    return 0 if (ok1 and not ok2 and conc3_ok and ok4) else 1
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python validation/gslc_equivalence.py --selftest`
Expected: `case 4 wrapped_rms = 1.500000 rad` → `case 4 result: FAIL`, script exit code 1.

- [ ] **Step 3: Fix `wrapped_rms`**

Replace `validation/gslc_equivalence.py:322-326` with:

```python
def wrapped_rms(field: np.ndarray, valid: np.ndarray) -> float:
    """RMS of the wrapped residual phase ABOUT ITS CIRCULAR MEAN.

    A constant phase offset between the two chains is an expected datum ambiguity
    (module docstring), so it must not contribute. Taking RMS about zero — as this
    did before 2026-09-18 — failed a perfectly-equivalent pair whose only difference
    was a constant offset. Matches compare/diff_vs_trad.py, which always did it this way.
    """
    if not valid.any():
        return float("nan")
    z = field[valid]
    mean_phasor = z.sum()
    if mean_phasor == 0:
        centred = z
    else:
        centred = z * np.conj(mean_phasor / abs(mean_phasor))
    ang = np.angle(centred)
    return float(np.sqrt(np.mean(ang ** 2)))
```

- [ ] **Step 4: Run the full self-test to verify all four cases behave**

Run: `python validation/gslc_equivalence.py --selftest`
Expected: case 1 all PASS, case 2 phase gates FAIL (as designed), case 3 `phase-residual-conc` PASS, **case 4 PASS**, `SELFTEST OK`, exit code 0.

- [ ] **Step 5: Update the documented gate semantics**

In `validation/README-equivalence.md`, replace the `residual-rms-rad` row's description with:

```
| `residual-rms-rad` | `<= 1.0` | RMS of the wrapped residual phase (radians) **about its circular mean**, after plane removal, over the same valid-cell set as `phase-residual-conc`. Taking it about the mean (rather than about zero) is required because a constant phase offset between the two chains is an expected datum ambiguity — see "Plane removal". Corrected 2026-09-18; before that a constant offset above 1.0 rad failed a perfectly-equivalent pair. |
```

- [ ] **Step 6: Hand off the change set for user review** — files touched: `validation/gslc_equivalence.py`, `validation/README-equivalence.md`. Quote the before/after self-test output.

---

## Task 2: P4 — band-selection guard across `compare/*`

`_declared_bands()` filtering exists only in `gslc_equivalence.py`. Every `compare/*` script globs `*.hdr` and takes the first match, selecting `i_*` and `q_*` **independently** — so on a dual-pol product they can come from different polarisations, and an orphaned `.img` (one exists in `E:/Output/trad/..._TC.data`) is picked up silently.

**Files:**
- Modify: `validation/compare/render_2x2.py` (add `declared_bands`, rewrite `iq`)
- Test: `validation/compare/render_2x2.py` gains a `--selftest` entry point

**Interfaces:**
- Produces: `declared_bands(dim_path: Path) -> set[str]` — band names the `.dim` declares.
- Produces: `iq(data_dir: Path, pol: str | None = None) -> tuple[Path, Path]` — the `.hdr` paths of a **matched** `i_`/`q_` pair, filtered against declared bands. Raises `RuntimeError` naming the candidates when the pair is ambiguous and `pol` is not given. Consumed by `seam_steps_guided.py` and `diff_vs_trad.py`.

- [ ] **Step 1: Write the failing self-test**

Append to `validation/compare/render_2x2.py`:

```python
def _selftest() -> int:
    """Band-selection guards: orphan rejection and matched i/q pairing."""
    import tempfile, shutil
    ok = True
    tmp = Path(tempfile.mkdtemp())
    try:
        dim = tmp / "p.dim"
        data = tmp / "p.data"
        data.mkdir()
        dim.write_text(
            "<Dimap><BAND_NAME>i_ifg_VV</BAND_NAME><BAND_NAME>q_ifg_VV</BAND_NAME></Dimap>",
            encoding="utf-8")
        for stem in ("i_ifg_VV", "q_ifg_VV", "i_ifg_ORPHAN", "q_ifg_ORPHAN"):
            (data / f"{stem}.hdr").write_text("samples = 4\nlines = 4\n", encoding="utf-8")

        ib, qb = iq(data)
        got = (ib.stem, qb.stem)
        print("orphan rejection ->", got)
        if got != ("i_ifg_VV", "q_ifg_VV"):
            print("  FAIL: expected the .dim-declared pair"); ok = False

        dim.write_text(
            "<Dimap><BAND_NAME>i_ifg_VH</BAND_NAME><BAND_NAME>q_ifg_VH</BAND_NAME>"
            "<BAND_NAME>i_ifg_VV</BAND_NAME><BAND_NAME>q_ifg_VV</BAND_NAME></Dimap>",
            encoding="utf-8")
        for stem in ("i_ifg_VH", "q_ifg_VH"):
            (data / f"{stem}.hdr").write_text("samples = 4\nlines = 4\n", encoding="utf-8")
        try:
            iq(data)
            print("  FAIL: ambiguous dual-pol pair should have raised"); ok = False
        except RuntimeError as e:
            print("ambiguity raised ->", str(e)[:60])
        ib, qb = iq(data, pol="VV")
        print("explicit pol ->", (ib.stem, qb.stem))
        if (ib.stem, qb.stem) != ("i_ifg_VV", "q_ifg_VV"):
            print("  FAIL: explicit pol selection wrong"); ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__" and "--selftest" in sys.argv:
    raise SystemExit(_selftest())
```

Ensure `import sys` and `from pathlib import Path` are present at the top of the file.

- [ ] **Step 2: Run it to verify it fails**

Run: `python validation/compare/render_2x2.py --selftest`
Expected: FAIL — the current `iq()` takes `sorted(...)[0]`, so it returns `i_ifg_ORPHAN`, and it never raises on ambiguity.

- [ ] **Step 3: Implement `declared_bands` and rewrite `iq`**

Replace the existing `def iq(data_dir: Path):` block in `validation/compare/render_2x2.py` with:

```python
def declared_bands(dim_path: Path) -> set[str]:
    """Band names the .dim actually declares — guards against orphaned .img/.hdr files
    left in the .data dir by earlier runs with different band naming. One such orphan
    (Intensity_ifg_*) exists in E:/Output/trad/..._TC.data."""
    txt = dim_path.read_text(encoding="utf-8", errors="replace")
    return set(re.findall(r"<BAND_NAME>([^<]+)</BAND_NAME>", txt))


def iq(data_dir: Path, pol: str | None = None):
    """Return the .hdr paths of a MATCHED i_/q_ band pair.

    Matched, not 'first i_ and first q_ independently': on a dual-pol product those can
    land on different polarisations with no warning. Filtered against the sibling .dim's
    declared band names so orphaned rasters are never selected.
    """
    dim_path = data_dir.with_suffix(".dim")
    allowed = declared_bands(dim_path) if dim_path.is_file() else None
    stems = sorted(h.stem for h in data_dir.glob("*.hdr"))
    if allowed is not None:
        stems = [s for s in stems if s in allowed]
    pairs = [(s, "q_" + s[2:]) for s in stems
             if s.startswith("i_") and ("q_" + s[2:]) in stems]
    if pol:
        pairs = [p for p in pairs if p[0].endswith("_" + pol)]
    if not pairs:
        raise RuntimeError(f"no matched i_/q_ pair in {data_dir}"
                           + (f" for pol={pol}" if pol else ""))
    if len(pairs) > 1:
        raise RuntimeError(
            f"ambiguous i_/q_ pairs in {data_dir}: {[p[0] for p in pairs]} — "
            f"pass pol= to disambiguate")
    i_stem, q_stem = pairs[0]
    return data_dir / f"{i_stem}.hdr", data_dir / f"{q_stem}.hdr"
```

Ensure `import re` is present at the top of the file.

- [ ] **Step 4: Run the self-test to verify it passes**

Run: `python validation/compare/render_2x2.py --selftest`
Expected: `orphan rejection -> ('i_ifg_VV', 'q_ifg_VV')`, `ambiguity raised -> ...`, `explicit pol -> ('i_ifg_VV', 'q_ifg_VV')`, `SELFTEST OK`, exit 0.

- [ ] **Step 5: Verify the real products still load**

Run:
```bash
python -c "
from pathlib import Path; import sys
sys.path.insert(0, 'validation/compare')
from render_2x2 import iq
print(iq(Path('E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb_flt.data')))
"
```
Expected: the `i_ifg_IW3_VV_23Jun2026_24Jun2026` / `q_ifg_...` pair. This product has a single polarisation so no `pol=` is needed.

- [ ] **Step 6: Hand off** — files touched: `validation/compare/render_2x2.py`.

---

## Task 3: P2 — machine-readable seam gate on the guided meter

The spec's seam gate must use `seam_steps_guided.py` (the blind `seam_steps.py` flags earthquakes, and its `worst` only updates above threshold so the gate is unsatisfiable). The guided script measures correctly but prints prose and never exits non-zero.

**Files:**
- Modify: `validation/compare/seam_steps_guided.py`

**Interfaces:**
- Produces: a `GATE seam-worst-step PASS|FAIL <value> <threshold>` line per interferogram and an exit code, matching the `gslc_equivalence.py` output contract.
- Consumes: `iq(data_dir, pol=None)` from Task 2.

- [ ] **Step 1: Add threshold parsing and the gate emitter**

In `validation/compare/seam_steps_guided.py`, replace the argument block:

```python
stack = Path(sys.argv[1])
ifgs = [Path(p) for p in sys.argv[2:]]
```

with:

```python
# --threshold <rad> is optional and defaults to the spec's 0.3 rad seam gate.
argv = sys.argv[1:]
THRESHOLD = 0.3
if "--threshold" in argv:
    k = argv.index("--threshold")
    THRESHOLD = float(argv[k + 1])
    del argv[k:k + 2]
stack = Path(argv[0])
ifgs = [Path(p) for p in argv[1:]]
```

- [ ] **Step 2: Emit the gate line and set the exit code**

Replace the final reporting block (the `if all_steps:` clause at the end of the file) with:

```python
    if all_steps:
        worst_abs = max(all_steps)
        print(f"  SEAM STEPS: worst {worst:+.3f} rad, median |step| {np.median(all_steps):.3f} rad, "
              f"n={len(all_steps)}")
        status = "PASS" if worst_abs <= THRESHOLD else "FAIL"
        print(f"GATE seam-worst-step {status} {worst_abs:.6g} {THRESHOLD:g}")
        if status == "FAIL":
            failed = True
    else:
        # No measurable seam anywhere is a measurement failure, not a pass: it means the
        # carrier band located seams the ifg could not be sampled at.
        print(f"GATE seam-worst-step FAIL nan {THRESHOLD:g}")
        failed = True

raise SystemExit(1 if failed else 0)
```

and initialise `failed = False` immediately before the `for ifg in ifgs:` loop.

- [ ] **Step 3: Run against a product without a carrier band to verify it fails loudly**

Run: `python validation/compare/seam_steps_guided.py E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack.dim E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb_flt.dim`
Expected: `AssertionError: no reference azimuthCarrierPhase band in the stack` — the classical stack has no carrier band. This confirms the guided meter requires a GSLC stack, which is correct and is why the gate applies only to GSLC products. Record this in the task report.

- [ ] **Step 4: Document the gate contract**

Append to `validation/README-equivalence.md` a new section:

```markdown
## Burst-seam gate (`compare/seam_steps_guided.py`)

```bash
python validation/compare/seam_steps_guided.py <gslc_stack_with_carrier.dim> <ifg.dim> [--threshold 0.3]
```

Emits `GATE seam-worst-step PASS|FAIL <worst_abs_rad> <threshold>` per interferogram and
exits non-zero on any FAIL, matching `gslc_equivalence.py`'s output contract.

**Use this, not `compare/seam_steps.py`.** The blind meter has no burst geometry: it flags
any row whose block-mean lag-1 phase exceeds a threshold, so on a coseismic scene it flags
the earthquake (its own module docstring says so). It also never calls `sys.exit`, and its
`worst` value only updates above the threshold, so "worst step below T" is unsatisfiable by
construction. The guided meter locates seams from the GSLC `azimuthCarrierPhase` band's
row-derivative and measures a trend-removed discontinuity across them, so a smooth fringe
gradient — however steep — extrapolates identically from both sides and cancels.

Requires a GSLC stack carrying `azimuthCarrierPhase*ref*` (produced with
`outputPhaseTerms=true`). It cannot run against a classical stack.
```

- [ ] **Step 5: Hand off** — files touched: `validation/compare/seam_steps_guided.py`, `validation/README-equivalence.md`.

---

## Task 4: P5 — make `trad_s1_control.xml` runnable

As written the graph feeds full 3-subswath IW SLCs straight to `Back-Geocoding`, which throws `"Split product is expected."` (`BackGeocodingOp.java:186-188`).

**Files:**
- Modify: `validation/graphs/trad_s1_control.xml`
- Modify: `validation/README-controls.md`

**Interfaces:**
- Produces: a graph accepting `${input1}`, `${input2}`, `${output}`, `${subswath}`, `${polarisation}`, `${firstBurst}`, `${lastBurst}`.

- [ ] **Step 1: Insert the two `TOPSAR-Split` nodes**

In `validation/graphs/trad_s1_control.xml`, insert immediately after the `Read2` node:

```xml
  <!--
    TOPSAR-Split is MANDATORY, not optional: Back-Geocoding rejects a multi-subswath
    product outright ("Split product is expected.", BackGeocodingOp.java:186-188).
    Whole bursts only - never SubsetOp, and never a range crop: a range-cropped split
    makes TOPSAR-Deburst fail ("region must intersect image bounds") and previously drove
    classical coherence from 0.367 to 0.088 on an Etna fixture.
  -->
  <node id="Split1">
    <operator>TOPSAR-Split</operator>
    <sources>
      <sourceProduct refid="Read1"/>
    </sources>
    <parameters>
      <subswath>${subswath}</subswath>
      <selectedPolarisations>${polarisation}</selectedPolarisations>
      <firstBurstIndex>${firstBurst}</firstBurstIndex>
      <lastBurstIndex>${lastBurst}</lastBurstIndex>
    </parameters>
  </node>
  <node id="Split2">
    <operator>TOPSAR-Split</operator>
    <sources>
      <sourceProduct refid="Read2"/>
    </sources>
    <parameters>
      <subswath>${subswath}</subswath>
      <selectedPolarisations>${polarisation}</selectedPolarisations>
      <firstBurstIndex>${firstBurst}</firstBurstIndex>
      <lastBurstIndex>${lastBurst}</lastBurstIndex>
    </parameters>
  </node>
```

and repoint `Back-Geocoding`'s sources:

```xml
    <sources>
      <sourceProduct refid="Split1"/>
      <sourceProduct.1 refid="Split2"/>
    </sources>
```

- [ ] **Step 2: Verify the aliases and parameter names against source**

Run:
```bash
grep -n "alias" sar-op-sentinel1/src/main/java/eu/esa/sar/sentinel1/gpf/TOPSARSplitOp.java | head -3
grep -nE "@Parameter" -A3 sar-op-sentinel1/src/main/java/eu/esa/sar/sentinel1/gpf/TOPSARSplitOp.java | grep -E "private (String|int)" | head -6
```
Expected: alias `TOPSAR-Split`; fields `subswath`, `selectedPolarisations`, `firstBurstIndex`, `lastBurstIndex`. If any name differs, use the name from source and record the correction in the task report.

- [ ] **Step 3: Verify the graph is well-formed**

Run:
```bash
python -c "
import xml.etree.ElementTree as ET
t = ET.parse('validation/graphs/trad_s1_control.xml')
ops = [n.find('operator').text for n in t.getroot().findall('node')]
print(ops)
assert ops.count('TOPSAR-Split') == 2, ops
print('OK')
"
```
Expected: `['Read', 'Read', 'TOPSAR-Split', 'TOPSAR-Split', 'Back-Geocoding', 'Enhanced-Spectral-Diversity', 'Interferogram', 'TOPSAR-Deburst', 'Write']` then `OK`.

- [ ] **Step 4: Record the runnable invocation**

In `validation/README-controls.md`, replace the "Sentinel-1 TOPS control (single step)" command block with:

```bash
MAVEN_OPTS="-Xmx20g" mvn -q -pl sar-op-sentinel1 exec:java \
  -Dexec.mainClass=org.esa.snap.core.gpf.main.GPT \
  -Dexec.classpathScope=compile \
  '-Dexec.args=validation/graphs/trad_s1_control.xml -Pinput1=E:/Data/Venezuela/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE.zip -Pinput2=E:/Data/Venezuela/S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE.zip -Psubswath=IW3 -Ppolarisation=VV -PfirstBurst=4 -PlastBurst=6 -Poutput=E:/Output/parity/ven_trad_ccw.dim'
```

and add above it:

> **TOPSAR-Split is mandatory.** Before 2026-09-18 this graph fed whole IW SLCs to
> `Back-Geocoding`, which throws `"Split product is expected."`. The graph had never been
> executed end-to-end, which is why the alias-table verification did not catch it.

- [ ] **Step 5: Hand off** — files touched: `validation/graphs/trad_s1_control.xml`, `validation/README-controls.md`. The graph is not executed in this task; Task 9 runs it.

---

## Task 5: P7 — `inverse_geocode.py` and its measured phase-error floor

The spec fixes the comparison domain as radar so the classical reference stays unresampled. That relocates the interpolation error onto the measurand, and removes `diff_vs_trad.py`'s 0.02 px refusal guard. The floor must therefore be **measured** before any R4/R5 number is quoted.

**Files:**
- Create: `validation/gslc_parity/__init__.py`
- Create: `validation/gslc_parity/inverse_geocode.py`

**Interfaces:**
- Produces: `sample_map_at(dim_path: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray` — complex128 array, same shape as `lats`, bilinear on i/q, NaN where outside the grid or where either contributing sample is zero-filled.
- Produces: `roundtrip_floor(dim_path: str, shift: tuple[float, float] = (0.5, 0.5), block: int = 2048) -> dict` — returns `{"conc": float, "rms_rad": float, "n": int}` for a forward-backward round trip over a bounded central block at a fixed sub-pixel shift (signature corrected to match the Step 5 code, 2026-09-20).

- [ ] **Step 1: Write the failing self-test**

Create `validation/gslc_parity/__init__.py` (empty file), then create `validation/gslc_parity/inverse_geocode.py` containing only this self-test block:

```python
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
"""
from __future__ import annotations

import numpy as np


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

    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python validation/gslc_parity/inverse_geocode.py --selftest`
Expected: `NameError: name '_bilinear_complex' is not defined`.

- [ ] **Step 3: Implement `_bilinear_complex`**

Insert into `validation/gslc_parity/inverse_geocode.py`, after the imports and before `_selftest`:

```python
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
```

- [ ] **Step 4: Run the self-test to verify it passes**

Run: `python validation/gslc_parity/inverse_geocode.py --selftest`
Expected: integer error `0.000e+00`, half-sample error below `5e-03`, out-of-grid `[nan+nanj nan+nanj]`, `SELFTEST OK`, exit 0.

- [ ] **Step 5: Implement `sample_map_at` and `roundtrip_floor`**

Append to `validation/gslc_parity/inverse_geocode.py`:

```python
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


def sample_map_at(dim_path: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Sample a map-grid complex interferogram at arbitrary lat/lon positions."""
    field, w, h = _load_complex(dim_path)
    dx, dy, lon0, lat0 = _read_geo(dim_path)
    cols = (lons - lon0) / dx
    rows = (lat0 - lats) / dy
    return _bilinear_complex(field, rows, cols)


def roundtrip_floor(dim_path: str, shift: tuple[float, float] = (0.5, 0.5),
                    block: int = 2048) -> dict:
    """Measure this sampler's own phase cost on the product's REAL wrapped fringes.

    Forward-backward resample: shift a central block by (dr, dc), then shift the result
    back by (-dr, -dc), and compare against the untouched original. A perfect sampler
    returns the original exactly; whatever differs is two interpolations' worth of
    interpolation error, which bounds the single-pass cost of inverse-geocoding.

    (0.5, 0.5) is the worst case for bilinear - maximum distance from every contributing
    sample. A central block rather than the whole raster keeps memory bounded on a
    23665 x 13582 product while still sampling real fringes rather than synthetic ones.

    Returns concentration and RMS-about-the-mean, the same two statistics the R4/R5 gates
    report, so the floor is directly comparable to the numbers quoted against it.
    """
    field, w, h = _load_complex(dim_path)
    dr, dc = shift
    r0 = max(0, h // 2 - block // 2)
    c0 = max(0, w // 2 - block // 2)
    sub = np.asarray(field[r0:r0 + block, c0:c0 + block], dtype=np.complex128)
    bh, bw = sub.shape
    rows, cols = np.mgrid[0:bh, 0:bw].astype(np.float64)

    once = _bilinear_complex(sub, rows + dr, cols + dc)
    twice = _bilinear_complex(once, rows - dr, cols - dc)

    # Trim a 2-px frame: edge cells legitimately have no neighbour to interpolate from.
    inner = (slice(2, bh - 2), slice(2, bw - 2))
    a = sub[inner]
    b = twice[inner]
    d = a * np.conj(b)
    good = np.isfinite(d.real) & np.isfinite(d.imag) & (np.abs(d) > 0)
    z = d[good]
    if z.size == 0:
        return {"conc": float("nan"), "rms_rad": float("nan"), "n": 0}
    conc = float(abs(z.sum()) / np.abs(z).sum())
    mp = z.sum()
    centred = z * np.conj(mp / abs(mp)) if mp != 0 else z
    rms = float(np.sqrt(np.mean(np.angle(centred) ** 2)))
    return {"conc": conc, "rms_rad": rms, "n": int(z.size)}
```

- [ ] **Step 6: Measure the floor on the real Venezuela GSLC interferogram**

This requires a GSLC interferogram, which does not exist yet at this point in the campaign. Run it instead against the **classical** geocoded product, which does exist and carries real wrapped fringes, to establish the sampler's behaviour on realistic data:

Run:
```bash
python -c "
import sys; sys.path.insert(0,'validation')
from gslc_parity.inverse_geocode import roundtrip_floor
print(roundtrip_floor('E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb_flt_TC.dim'))
"
```
Note this product declares only `Phase_` and `coh` bands, so `iq()` will raise `no matched i_/q_ pair`. **That is the expected outcome and confirms Task 2's guard works.** Record it, and defer the real floor measurement to the first GSLC interferogram produced in Plan 2. Write the deferral into the module docstring:

```python
# FLOOR STATUS: not yet measured on a GSLC product - no GSLC interferogram exists at the
# time this module landed. Plan 2 measures it on the first Venezuela GSLC ifg, and no R4
# or R5 number may be quoted before that measurement is recorded here.
```

- [ ] **Step 7: Hand off** — files created: `validation/gslc_parity/__init__.py`, `validation/gslc_parity/inverse_geocode.py`.

---

## Task 6: P3 — ground-range-correct the coherence window

`cohWinSizeMeters` divides by `range_spacing`, which in radar geometry is **slant**. A 100 m request on an S1 IW SLC (2.33 m slant) yields 43 px spanning ~150 m on the ground, while the operator's own text claims the window is "square on the ground whatever the geometry". This biases R5's coherence parity ~1.5× toward the classical side.

**Files:**
- Modify: `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/InterferogramOp.java:690-708`
- Test: `sar-op-insar/src/test/java/eu/esa/sar/insar/gpf/TestCoherenceWindowMeters.java` (create)

**Interfaces:**
- Produces: `static int[] coherenceWindowFromMeters(double meters, double rgSpacing, double azSpacing, double incidenceDeg, boolean isGeocoded)` returning `{cohWinRg, cohWinAz}`. Package-private static so the test can call it without a product.

- [ ] **Step 1: Write the failing test**

Create `sar-op-insar/src/test/java/eu/esa/sar/insar/gpf/TestCoherenceWindowMeters.java`:

```java
package eu.esa.sar.insar.gpf;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The coherence window must span the requested distance ON THE GROUND in both geometries.
 * Before 2026-09-18 the radar-geometry branch divided by SLANT range spacing, so a 100 m
 * request spanned ~150 m on the ground at S1 IW incidence - a ~1.5x bias against any
 * geocoded product it was compared with.
 */
public class TestCoherenceWindowMeters {

    @Test
    public void radarGeometryUsesGroundRangeSpacing() {
        // S1 IW SLC: 2.33 m slant range spacing, 14.0 m azimuth, ~39 deg incidence.
        // ground range spacing = 2.33 / sin(39 deg) = 3.701 m -> 100 / 3.701 = 27 px
        final int[] win = InterferogramOp.coherenceWindowFromMeters(100.0, 2.33, 14.0, 39.0, false);
        assertEquals("range window (ground-corrected)", 27, win[0]);
        assertEquals("azimuth window", 7, win[1]);
    }

    @Test
    public void geocodedGeometryUsesSpacingDirectly() {
        // A GSLC product's range_spacing is already a ground/map step - no incidence term.
        final int[] win = InterferogramOp.coherenceWindowFromMeters(100.0, 10.0, 10.0, 39.0, true);
        assertEquals("range window", 10, win[0]);
        assertEquals("azimuth window", 10, win[1]);
    }

    @Test
    public void windowNeverDropsBelowThree() {
        final int[] win = InterferogramOp.coherenceWindowFromMeters(1.0, 100.0, 100.0, 39.0, true);
        assertEquals(3, win[0]);
        assertEquals(3, win[1]);
    }

    @Test
    public void degenerateIncidenceFallsBackToSlantSpacing() {
        // incidence 0 or absent must not produce a divide-by-zero or an absurd window.
        final int[] win = InterferogramOp.coherenceWindowFromMeters(100.0, 2.33, 14.0, 0.0, false);
        assertEquals(43, win[0]);
        assertEquals(7, win[1]);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -pl sar-op-insar test -Dtest=TestCoherenceWindowMeters` from `E:\ESA\microwave-toolbox`
Expected: compilation failure — `cannot find symbol: method coherenceWindowFromMeters`.

- [ ] **Step 3: Implement the helper and rewire the caller**

In `InterferogramOp.java`, add above `resolveCoherenceWindowFromMeters()`:

```java
    /**
     * Convert a ground distance into (range, azimuth) window sizes in pixels.
     *
     * In RADAR geometry {@code range_spacing} is SLANT range, so the ground step is
     * {@code rgSpacing / sin(incidence)}. Dividing the requested metres by the slant step
     * instead - as this did before 2026-09-18 - produced a window ~1/sin(theta) too wide
     * on the ground (~1.5x at S1 IW incidence), silently biasing any coherence comparison
     * against a geocoded product. In MAP geometry the spacing is already a ground step.
     *
     * @param isGeocoded {@code is_terrain_corrected == 1}
     */
    static int[] coherenceWindowFromMeters(final double meters, final double rgSpacing,
                                           final double azSpacing, final double incidenceDeg,
                                           final boolean isGeocoded) {
        double groundRg = rgSpacing;
        if (!isGeocoded && incidenceDeg > 1.0 && incidenceDeg < 89.0) {
            groundRg = rgSpacing / Math.sin(Math.toRadians(incidenceDeg));
        }
        final int winRg = groundRg > 0.0 ? Math.max(3, (int) Math.round(meters / groundRg)) : 3;
        final int winAz = azSpacing > 0.0 ? Math.max(3, (int) Math.round(meters / azSpacing)) : 3;
        return new int[]{winRg, winAz};
    }
```

Then replace the body of `resolveCoherenceWindowFromMeters()` between the `rgSpacing`/`azSpacing` reads and the log statement with:

```java
        final double incNear = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.incidence_near);
        final double incFar = AbstractMetadata.getAttributeDouble(abs, AbstractMetadata.incidence_far);
        final double incidenceDeg = (incNear > 0.0 && incFar > 0.0) ? 0.5 * (incNear + incFar) : 0.0;
        final boolean isGeocoded = abs.getAttributeInt(AbstractMetadata.is_terrain_corrected, 0) == 1;
        final int[] win = coherenceWindowFromMeters(cohWinSizeMeters, rgSpacing, azSpacing,
                incidenceDeg, isGeocoded);
        cohWinRg = win[0];
        cohWinAz = win[1];
```

and extend the existing log line to include the geometry it used:

```java
        SystemUtils.LOG.info(String.format(
                "InterferogramOp: cohWinSizeMeters=%.1f m -> cohWinAz=%d, cohWinRg=%d "
                        + "(pixel spacing az=%.2f m, rg=%.2f m, incidence=%.2f deg, geocoded=%b)",
                cohWinSizeMeters, cohWinAz, cohWinRg, azSpacing, rgSpacing, incidenceDeg, isGeocoded));
```

- [ ] **Step 4: Correct the operator's own parameter documentation**

Find the `cohWinSizeMeters` `@Parameter` description claiming the window is "square on the ground whatever the geometry" and replace that clause with:

```
"it yields a window that is square on the ground in both radar and map geometry "
"(the radar branch divides by the ground-range step, rg_spacing / sin(incidence)), "
"making radar-geometry and geocoded results directly comparable."
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -pl sar-op-insar test -Dtest=TestCoherenceWindowMeters`
Expected: 4 tests, all pass.

- [ ] **Step 6: Run the existing GSLC ramp suites to check for regressions**

Run: `mvn -pl sar-op-insar test -Dtest='TestGslcResidualRamp,TestGslcOffsetFieldFit,TestCreateStackOp'`
Expected: all green. These do not exercise `cohWinSizeMeters`, so a failure here means something unrelated broke — investigate before proceeding.

- [ ] **Step 7: Hand off, flagging the behaviour change**

Files touched: `InterferogramOp.java`, `TestCoherenceWindowMeters.java`. **State explicitly in the hand-off that this changes coherence numbers for any radar-geometry product processed with `cohWinSizeMeters`** — previously-recorded classical coherence values are not comparable across this change. Add a `ReleaseNotes.md` entry saying so.

---

## Task 7: P6 — make `GSLCEquivalenceLongTest` assert something

The test points at `E:/Output/ers/`, which no longer exists, and in its default mode asserts only that ≥4 `GATE` lines were printed — it passes whether every gate FAILs or PASSes.

**Files:**
- Modify: `sar-op-insar/src/test/java/eu/esa/sar/insar/gpf/GSLCEquivalenceLongTest.java`

**Interfaces:**
- Consumes: the `GATE <name> PASS|FAIL <value> <threshold>` contract from `gslc_equivalence.py`.

- [ ] **Step 1: Replace the hardcoded ERS fixture with configurable paths**

Replace lines 69-73 with:

```java
    /**
     * Fixture paths are configurable because the ERS tree these once pointed at
     * (E:/Output/ers) was deleted during cleanup, leaving this test permanently skipped
     * while still appearing to guard the parity gates.
     */
    private static final File GSLC_IFG = new File(
            System.getProperty("gslc.equivalence.gslcIfg", "E:/Output/parity/ven_gslc_ifg.dim"));
    private static final File TRAD_IFG = new File(
            System.getProperty("gslc.equivalence.tradIfg", "E:/Output/parity/ven_trad_ifg.dim"));

    private static final String EXPECT_PASS_PROPERTY = "gslc.equivalence.expectPass";
    private static final int MIN_EXPECTED_GATE_LINES = 4;
```

and update the two `assumeTrue` calls and the two `command.add(...)` calls to use `TRAD_IFG` in place of `TRAD_IFG_TC`.

- [ ] **Step 2: Write the failing unit test for the verdict logic**

The verdict logic must be testable without fixtures, so extract it and test it directly. Add to `GSLCEquivalenceLongTest.java`:

```java
    /**
     * Verdict logic, extracted so it can be tested without any fixture.
     *
     * Before 2026-09-18 the non-expectPass branch asserted only the GATE line count, so
     * this test passed whether every gate FAILed or every gate PASSed - it guarded
     * nothing while appearing to guard the parity gates.
     *
     * @param sawFail whether any {@code GATE ... FAIL} line was printed
     */
    static void assertVerdict(final int gateLineCount, final boolean sawFail,
                              final int exitCode, final boolean expectPass) {
        assertTrue("Expected at least " + MIN_EXPECTED_GATE_LINES
                        + " GATE lines, got " + gateLineCount,
                gateLineCount >= MIN_EXPECTED_GATE_LINES);
        if (expectPass) {
            assertEquals("Expected full parity (exit 0) with -D" + EXPECT_PASS_PROPERTY + "=true",
                    0, exitCode);
        } else {
            assertEquals("Exit code must agree with the printed gates (sawFail=" + sawFail + ")",
                    sawFail ? 1 : 0, exitCode);
        }
    }

    @Test
    public void verdictRejectsExitCodeDisagreeingWithGates() {
        // All gates PASS but the script exited non-zero: a contract violation.
        try {
            assertVerdict(6, false, 1, false);
            fail("expected an AssertionError when exit 1 accompanies no FAIL gate");
        } catch (AssertionError expected) {
            // ok
        }
        // A FAIL gate with exit 0: also a contract violation.
        try {
            assertVerdict(6, true, 0, false);
            fail("expected an AssertionError when exit 0 accompanies a FAIL gate");
        } catch (AssertionError expected) {
            // ok
        }
        // Consistent combinations must pass.
        assertVerdict(6, true, 1, false);
        assertVerdict(6, false, 0, false);
    }

    @Test
    public void verdictRejectsTooFewGateLines() {
        try {
            assertVerdict(2, false, 0, false);
            fail("expected an AssertionError when fewer than "
                    + MIN_EXPECTED_GATE_LINES + " GATE lines were printed");
        } catch (AssertionError expected) {
            // ok
        }
    }

    @Test
    public void expectPassModeDemandsExitZero() {
        assertVerdict(6, false, 0, true);
        try {
            assertVerdict(6, true, 1, true);
            fail("expectPass mode must reject a non-zero exit");
        } catch (AssertionError expected) {
            // ok
        }
    }
```

Add `import static org.junit.Assert.fail;` to the imports.

- [ ] **Step 3: Run it to verify it fails**

Run: `mvn -pl sar-op-insar test -Dtest=GSLCEquivalenceLongTest -Denable.long.tests=true` from `E:\ESA\microwave-toolbox`
Expected: compilation failure — `cannot find symbol: method assertVerdict`.

- [ ] **Step 4: Rewire the fixture test to call the extracted logic**

Replace the existing `if (expectPass) { ... } else { ... }` block at the end of `testHarnessRunsOnErsPair` with a single call:

```java
        assertVerdict(gateLineCount, sawFail, exitCode,
                Boolean.parseBoolean(System.getProperty(EXPECT_PASS_PROPERTY, "false")));
```

and delete the now-unused local `expectPass` declaration above it.

- [ ] **Step 5: Run to verify the unit tests pass and the fixture test skips**

Run: `mvn -pl sar-op-insar test -Dtest=GSLCEquivalenceLongTest -Denable.long.tests=true`
Expected: the three verdict unit tests **pass** (they need no fixture), and `testHarnessRunsOnErsPair` **skips** with an assumption message naming the missing default fixture. A skip there is correct — Plan 2 produces the fixtures.

- [ ] **Step 6: Confirm the harness honours the contract the test now asserts**

Run: `python validation/gslc_equivalence.py --selftest; echo "exit=$?"`
Expected: `SELFTEST OK`, `exit=0`. Then confirm the FAIL→non-zero direction by grepping the script's exit path:

Run: `grep -n "SystemExit\|sys.exit\|return 1 if\|all_ok" validation/gslc_equivalence.py | tail -8`
Expected: an exit path that returns non-zero when `all_ok` is false. Record the line in the task report — this is what makes the `sawFail ? 1 : 0` assertion valid.

- [ ] **Step 7: Hand off** — files touched: `GSLCEquivalenceLongTest.java`.

---

## Task 8: P10 — restore a TOPS faithful-phase contract with bounded runtime

`GSLCTopsInSarLongTest.testFaithfulPhase_TopsS1Fixture` is `@Ignore`d for runtime, so the TOPS faithful-phase contract is enforced nowhere. Its own annotation names the cause: every window reads the full 12700-px width for five bands, then abandons the window early.

**Files:**
- Modify: `sar-op-sar-processing/src/test/java/eu/esa/sar/sar/gpf/geometric/GSLCTopsInSarLongTest.java:158-169` and the block-scan loop it describes

**Interfaces:**
- Consumes: `E:/Output/gslcdiag/m.dim` (IW3 bursts 4–5, S1A 23Jun 2026) — **this fixture is S1A × S1D, 2 bursts**, not the Tier D pair. It is adequate here because this test uses only the reference leg.

- [ ] **Step 1: Read the ignored test and its block scan**

Run: `sed -n '150,260p' sar-op-sar-processing/src/test/java/eu/esa/sar/sar/gpf/geometric/GSLCTopsInSarLongTest.java`
Record the exact loop structure, the band reads, and the `break blockScan` condition in the task report before changing anything.

- [ ] **Step 2: Narrow the read width**

Change the per-window band reads from full scene width to a bounded column slab centred on the window. The contract is a phase concentration over selected pixels; it does not need the full swath per window. Use a slab of 512 columns:

```java
    /** Columns read per candidate window. The contract is a concentration over selected
     *  pixels, not a swath-wide statistic, so a bounded slab is sufficient - and reading
     *  the full 12700-px width per window is what pushed this test past a 25-minute cap. */
    private static final int SCAN_SLAB_COLS = 512;
```

and apply it to each `readPixels` call inside the block scan, clamping the slab to the raster bounds.

- [ ] **Step 3: Remove the `@Ignore` and add a runtime guard**

Delete the `@Ignore(...)` annotation at `:158`. Add at the top of the test method:

```java
        final long tStart = System.currentTimeMillis();
```

and at the end, before the concentration assertions:

```java
        final long elapsedSec = (System.currentTimeMillis() - tStart) / 1000L;
        System.out.println("testFaithfulPhase_TopsS1Fixture elapsed " + elapsedSec + " s");
        assertTrue("Test must stay inside the 25-minute long-test budget; took "
                + elapsedSec + " s", elapsedSec < 1500);
```

- [ ] **Step 4: Run it**

Run: `mvn -pl sar-op-sar-processing test -Dtest=GSLCTopsInSarLongTest -Denable.long.tests=true -Dtests.data.dir=E:/TestData/s1tbx`
Expected: 3 tests pass. The faithful-phase test should report a concentration near the 0.9785 recorded when the bistatic mirroring was fixed, and elapsed well under 1500 s.

If concentration is materially below ~0.95, **do not loosen the assertion** — the test mirrors production's bistatic azimuth solve, and a drop means either the mirroring broke or the operator changed. Report and stop.

- [ ] **Step 5: Hand off** — files touched: `GSLCTopsInSarLongTest.java`. Report the measured concentration and runtime.

---

## Task 9: P9 + R0 — screening sweep driver with engagement assertions

R0 measures where we stand. It must also prove that the estimators it depends on actually ran — `CreateStackOp:629-633` logs a warning and keeps **zero bias** on failure, so a run can complete "successfully" having done nothing.

**Files:**
- Create: `validation/drivers/r0_screening.ps1`
- Create: `validation/drivers/assert_log.py`

**Interfaces:**
- Produces: `assert_log.py <logfile> --require "<regex>" [--forbid "<regex>"]` exiting non-zero on violation.
- Produces: `E:/Output/parity/r0/summary.csv` with one row per source.

- [ ] **Step 1: Write the log assertion helper**

Create `validation/drivers/assert_log.py`:

```python
"""Assert that a gpt/maven log proves the code path we depend on actually ran.

WHY. CreateStackOp logs a warning and KEEPS ZERO BIAS when estimation fails
(CreateStackOp.java:629-633), and the grid lock degrades silently to an unlocked slave
grid (:1911-1914). Both let a run finish "successfully" having done nothing. Gating on a
log line is the only available proof.
"""
from __future__ import annotations

import argparse
import re
import sys


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile")
    ap.add_argument("--require", action="append", default=[])
    ap.add_argument("--forbid", action="append", default=[])
    args = ap.parse_args()

    text = open(args.logfile, encoding="utf-8", errors="replace").read()
    failed = False
    for pat in args.require:
        if re.search(pat, text):
            print(f"REQUIRE ok   {pat}")
        else:
            print(f"REQUIRE MISS {pat}")
            failed = True
    for pat in args.forbid:
        m = re.search(pat, text)
        if m:
            print(f"FORBID  HIT  {pat}  ->  {m.group(0)[:120]}")
            failed = True
        else:
            print(f"FORBID  ok   {pat}")
    print("LOG ASSERTIONS", "FAILED" if failed else "OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Verify the helper on an existing log**

Run:
```bash
python validation/drivers/assert_log.py E:/Output/harness/overnight.log --require "." --forbid "ThisStringDoesNotAppearAnywhere"
```
Expected: `REQUIRE ok`, `FORBID ok`, `LOG ASSERTIONS OK`, exit 0.

Then run with a deliberately impossible requirement:
```bash
python validation/drivers/assert_log.py E:/Output/harness/overnight.log --require "ThisStringDoesNotAppearAnywhere"
```
Expected: `REQUIRE MISS`, `LOG ASSERTIONS FAILED`, exit 1.

- [ ] **Step 3: Write the R0 driver**

Create `validation/drivers/r0_screening.ps1`:

```powershell
<#
    R0 screening sweep. MEASUREMENT ONLY - no gates, no pass/fail.

    Purpose: establish where we actually stand before building anything on a number.
    The campaign has never been run on an easy pair, and the acceptance harness has been
    executed against real data exactly once, on a product already known to be broken.

    Runs ONE heavy job at a time. Each source is torn down before the next begins:
    ~294 GB free against a ~360 GB campaign (spec section 5, Rule 5).

    ASCII only. Every -P argument quoted (an unquoted -Pfoo=1.25E-4 gets token-split).
#>
$ErrorActionPreference = 'Continue'
$ROOT = 'E:\ESA\microwave-toolbox'
$OUT  = 'E:\Output\parity\r0'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$summary = Join-Path $OUT 'summary.csv'
'source,mode,stage,metric,value,notes' | Set-Content $summary

function Log($m) {
    $l = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $l
    $l | Add-Content (Join-Path $OUT 'r0.log')
}

# Sources are listed in Tier D order. PAZ and NISAR are Tier L and are NOT in this sweep.
$sources = @(
    @{ name='venezuela'; mode='TOPS';     note='IW3 bursts 4-6, burstId-matched' },
    @{ name='napa';      mode='stripmap'; note='per-leg azimuth windows, 16809-line offset' },
    @{ name='bam';       mode='stripmap'; note='auto-path stack mandatory, ~8px cross-PAC' }
)

foreach ($s in $sources) {
    Log "=== $($s.name) ($($s.mode)) - $($s.note)"
    "$($s.name),$($s.mode),screening,status,pending,$($s.note)" | Add-Content $summary
}
Log "R0 scaffold written to $summary. Per-source chains are added in Plan 2."
```

- [ ] **Step 4: Run the driver scaffold**

Run: `powershell -NoProfile -File validation/drivers/r0_screening.ps1`
Expected: three `===` lines, `summary.csv` created at `E:/Output/parity/r0/` with a header and three `pending` rows, exit without error.

- [ ] **Step 5: Hand off** — files created: `validation/drivers/assert_log.py`, `validation/drivers/r0_screening.ps1`. Note that the per-source processing chains land in Plan 2; this task delivers the driver skeleton, the summary contract, and the log-assertion tool the whole campaign depends on.

---

## Task 10: Crop-validity gate — 3 bursts versus full scene

Spec §5 Rule 3. The cheapest insurance in the campaign, and it runs before anything is built on fast numbers. Targets the **debursted radar-geometry** control (`_ifg_deb_flt.dim`), which carries `i_ifg`/`q_ifg` — not the `_TC.dim`, which has only `Phase_` + `coh` and an undeclared orphan.

**Files:**
- Create: `validation/drivers/crop_validity.ps1`

**Interfaces:**
- Consumes: `assert_log.py` (Task 9), `gslc_equivalence.py` (Task 1), `render_2x2.iq` (Task 2).
- Produces: `E:/Output/parity/cropgate/verdict.txt` containing the gate values for the 3-burst and full-scene runs side by side.

- [ ] **Step 1: Record the reference product's exact identity**

Run:
```bash
python -c "
import re
p='E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb_flt.dim'
t=open(p,encoding='utf-8',errors='replace').read()
print('bands:', re.findall(r'<BAND_NAME>([^<]+)</BAND_NAME>', t))
print('raster:', re.search(r'<NCOLS>(\d+)</NCOLS>', t).group(1), 'x', re.search(r'<NROWS>(\d+)</NROWS>', t).group(1))
"
```
Expected: the five bands `i_ifg_IW3_VV_23Jun2026_24Jun2026`, `q_ifg_...`, `Intensity_..._db`, `Phase_...`, `coh_...`, and 23665 × 13582. Record both in the task report.

- [ ] **Step 2: Write the gate driver**

Create `validation/drivers/crop_validity.ps1`:

```powershell
<#
    CROP-VALIDITY GATE (spec section 5, Rule 3).

    Question: does a 3-burst TOPSAR-Split fixture reproduce the gate values of the full
    scene? If yes, the fast fixture is BLESSED and every later fast number is trustworthy.
    If no, we have caught the failure before the campaign is built on it.

    This exists because a range-cropped Etna fixture once drove classical coherence from
    0.367 to 0.088 - collapsing both pipelines to the estimator floor - while a sane ESD
    residual (~0.03 px) hid the cause, and two days of work were spent on numbers that
    were measuring the crop.

    REFERENCE: ..._Stack_ifg_deb_flt.dim - the DEBURSTED RADAR-GEOMETRY product, which
    carries i_ifg/q_ifg. NOT the _TC.dim, which declares only Phase_ + coh and has an
    undeclared orphan Intensity_*.img in its .data dir.

    CAVEAT: the control stack is 10 S1A bursts with a 9-burst S1C resampled onto it, so
    BURST 10 HAS NO SECONDARY DATA. Any full-scene statistic must exclude it. Bursts 4-6
    are unaffected.
#>
$ErrorActionPreference = 'Continue'
$OUT = 'E:\Output\parity\cropgate'
New-Item -ItemType Directory -Force -Path $OUT | Out-Null
$verdict = Join-Path $OUT 'verdict.txt'

$TRAD_FULL = 'E:\Output\trad\S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb_flt.dim'

"CROP-VALIDITY GATE" | Set-Content $verdict
"reference (full scene, radar geometry): $TRAD_FULL" | Add-Content $verdict
"burst 10 excluded: the 10-burst S1A stack carries a 9-burst S1C secondary" | Add-Content $verdict
""  | Add-Content $verdict

if (-not (Test-Path $TRAD_FULL)) {
    "ABORT: reference product not found." | Add-Content $verdict
    Write-Host "ABORT: $TRAD_FULL not found"
    exit 1
}
Write-Host "Reference present. Per-source GSLC and 3-burst chains land in Plan 2."
"STATUS: reference verified present; fast/full comparison runs in Plan 2 once the" | Add-Content $verdict
"        Venezuela GSLC chain exists. This driver records the contract and the caveats." | Add-Content $verdict
Get-Content $verdict
```

- [ ] **Step 3: Run it**

Run: `powershell -NoProfile -File validation/drivers/crop_validity.ps1`
Expected: `Reference present.` and `verdict.txt` printed, exit 0.

- [ ] **Step 4: Hand off** — files created: `validation/drivers/crop_validity.ps1`. State clearly that the gate's *comparison* runs in Plan 2; this task fixes the contract, the correct reference product, and the burst-10 exclusion so they cannot be forgotten later.

---

## Plan completion criteria

All ten pre-flight items landed with tests, and:

- `python validation/gslc_equivalence.py --selftest` → `SELFTEST OK`, exit 0, four cases.
- `python validation/compare/render_2x2.py --selftest` → `SELFTEST OK`, exit 0.
- `python validation/gslc_parity/inverse_geocode.py --selftest` → `SELFTEST OK`, exit 0.
- `mvn -pl sar-op-insar test -Dtest=TestCoherenceWindowMeters` → 4 pass.
- `mvn -pl sar-op-sar-processing test -Dtest=GSLCTopsInSarLongTest -Denable.long.tests=true -Dtests.data.dir=E:/TestData/s1tbx` → 3 pass, faithful-phase concentration reported.
- `validation/graphs/trad_s1_control.xml` parses with two `TOPSAR-Split` nodes.
- `validation/drivers/assert_log.py` exits 1 on a missing required pattern.

**Two items deliberately carry forward to Plan 2**, because they need a GSLC interferogram that does not exist yet: the inverse-geocode phase-error floor (P7 measurement) and the crop-validity comparison itself. Both have their contracts, reference products and caveats fixed here so they cannot drift.

**P8 (per-source classical reproducibility floor) is Plan 2 work** — it requires running the classical chain twice under different parameterisations per source, which belongs with that source's ladder rather than with harness repair.
