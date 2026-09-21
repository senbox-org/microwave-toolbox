# GSLC Parity Campaign — Plan 2: Venezuela TOPS, 3-Day Cut

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Two kinds of step.** Ordinary steps (write a file, run a self-test) are for the implementer subagent. Steps marked **[HEAVY]** are multi-minute to multi-hour GPT/Maven jobs: run them **one at a time**, in the background, with output going to the log file the driver names, and read the log's exit verdict — never two heavy jobs at once (memory, and ~294 GB free disk).

**Goal:** Produce a defensible, measured equivalence result for GSLC versus the classical chain on **S1 IW TOPS (Venezuela, bursts 4–6)** in three working days: Tier 1 self-consistency (R1, R2-lite, R3), Tier 3 numeric parity (R4, R5) with its measured floors (P7, P8), the crop-validity gate, the burst-seam gate, and a scorecard with an error-budget table.

**Architecture:** Everything lives in the existing `validation/gslc_parity/` Python package plus `validation/drivers/` PowerShell drivers. Fixtures come from `TOPSAR-Split` only. Chains that must exercise the pre-flight fixes run through maven-exec GPT (current source, no deploy); operators this work never touched run through the installed `gpt.exe`. Every rung records one JSON row per gate through `budget.record`, and the scorecard is generated from those rows — nothing is typed by hand.

**Tech Stack:** Python 3.13 + numpy 2.2 (no pytest: `--selftest` flags), PowerShell 7, Maven 3 (offline, from `E:\ESA\microwave-toolbox`), SNAP `gpt`, existing harness `validation/gslc_equivalence.py`.

**Spec:** `docs/superpowers/specs/2026-09-18-gslc-insar-parity-campaign-design.md` (§7 the ladder, §8 the Venezuela row, §5 fixture rules, §13 pre-flight). Predecessor: `docs/superpowers/plans/2026-09-18-gslc-parity-preflight.md` (Plan 1, done; ledger in `.superpowers/sdd/2026-09-18-gslc-parity-preflight/`).

## Global Constraints

Copied from the spec and from the working rules; every task's requirements include this section.

- **No `git commit` / `git push` by agents.** The user reviews and commits. Every task ends by handing off a described change set.
- **TOPS: `TOPSAR-Split` only. No `SubsetOp`, ever, and no range crop** (a range-cropped Etna fixture drove classical coherence 0.367 → 0.088).
- **Venezuela fixture = IW3 VV bursts 4–6**, full range width, burstIds 225587/225588/225589 in all three products. S1C has 23767 samples per line against S1A/S1D's 23665.
- **`E:/Output/gslcdiag/{m,s}.dim` is S1A × S1D, 2 bursts. It must never be substituted for the S1A × S1C fixture.**
- **ETAD must be option 1** (`resamplingImage=true` + `outputPhaseCorrections=true`); option 2 is a silent no-op for the GSLC chain. ETAD is passed with an explicit `-PetadFile` (auto-search matches nothing on a burst subset), and the source product **name must keep the original S1 stem**.
- **Pairwise rungs use ETAD option 1 (S1A × S1C). The R3 closure triple runs ETAD-off on all three legs** (no S1D ETAD product exists) and is labelled as a different configuration everywhere.
- **`CreateStack` auto path** (GSLC reference + raw secondary SLC) for every GSLC pair. Assert the log line `locked to the reference (N of M seam(s)` — without it the run is not a valid GSLC result.
- **All `residualRamp*` options OFF** for R5. (R2-lite deliberately measures ramp ON versus OFF.)
- **Coherence parity uses ground-corrected windows:** `cohWinSizeMeters=100` in **both** chains, and the log line `cohWinSizeMeters=100.0 m -> cohWinAz=…, cohWinRg=…` is asserted.
- **Materialise the stack to disk before `Interferogram`.** Chaining `Interferogram` onto an in-memory `CreateStack → GSLC` graph livelocks.
- **Maven runs start from `E:\ESA\microwave-toolbox`, offline (`-o`).** A `sar-op-insar` change is invisible to a run launched from another module until `mvn -o -pl sar-op-insar install -DskipTests`.
- **Exit code is the verdict; exit 0 with no product is a false success.** Delete a partial product before a re-run.
- **One heavy job at a time; per-source teardown** (delete stage products once gate values are recorded; keep gate outputs, the budget row and the figures).
- **PowerShell: ASCII only, every `-P` argument quoted, operator parameters through a `-p` file so no argument contains a space.**
- **Figures are full-resolution crops, never decimated full-scene views** (decimation aliases hundreds of flat-earth cycles into apparent noise).
- **Thresholds carry provenance.** A threshold from the spec says so; a threshold chosen here without prior data is recorded as `PROVISIONAL: chosen before measurement, no prior data`; a threshold derived from a measured floor names the floor.
- **Self-tests:** `--selftest` inside the script, not pytest; output must be pristine.
- Half-pixel convention: SNAP's `IMAGE_TO_MODEL_TRANSFORM` and tie-point grids are **corner-origin** (integer image coordinate = pixel corner, pixel centre at +0.5). Use `geo_to_index` / `tpg_at`, never the raw affine result as an array index.

## Scope of this plan (what the 3-day cut keeps and drops)

**Kept (must do):** crop-validity gate, P7 and P8 floors, R1, R2-lite, R3, R4, R5, burst-seam gate, Venezuela scorecard row, error-budget table, short technical note.

**Dropped or reduced — say so to ESA, do not hide it:**

| Spec item | Status in this plan |
|---|---|
| Napa, ASAR Bam (the whole stripmap half of the claim) | **Not run.** The claim this plan can support is *S1 IW TOPS only*. |
| PAZ, NISAR, R7 | Not run. |
| R0 on all three sources | Folded into Tasks 3–5 on Venezuela only. |
| R1 perturbed-orbit variant and the flat-earth bilinear-error measurement | **Not run.** R1 here is the **null-baseline** form (geometry identical, phase injected). It regression-guards the removal chain but does *not* measure the 10-px node-surface error on a high-B⊥ pair. |
| R2 "re-annotated resample" | Replaced by **R2-lite**: same-geometry pair with a **range-only** lobe; it gives each chain's *retention* of a known signal, with GSLC ramp ON and OFF. The lobe is 1-D, so retention of a localised 2-D lobe is not measured. |
| R6 geophysical equivalence, ETAD-off-in-both null, geolocation offset map | "Should do" only if time remains (listed at the end). |
| Deck | Replaced by a short technical note. |

## Schedule (estimates, not measurements)

Heavy-job durations are guesses scaled from the full-scene times (a 3-burst fixture is ~30% of the scene) and have **not** been measured on the 3-burst fixture. Replace them with the first real timings.

| Day | Track A — compute, strictly serial | Track B — code, no processing |
|---|---|---|
| 1 | T1 fixtures (~1 h) → T3 classical control + crop gate (~1.5 h) | T2 scorecard + closure code; T8 code (synth generator, scorer) |
| 2 | T4 GSLC pair + P7 (~1.5 h) → T5 P8 + R5 (~0.5 h) → T6 R4 (~0.5 h) → T7 R3 (~4 h, overnight) | T8 go/no-go spike, then T8 runs (~3.7 h, overnight) |
| 3 | T9 seam gate, scorecard, budget, note | — |

Serial heavy total ≈ **12 h**. It fits three days only if T7 and T8 run overnight and nothing needs re-running.

## Provisional thresholds (chosen before any measurement)

| Gate | Value | Why provisional |
|---|---|---|
| `crop-coherence-delta` | ≤ 0.03 | No prior measurement of fixture-vs-full-scene coherence |
| `crop-gx-delta`, `crop-gy-delta` | ≤ 0.02 rad per 4×4-multilooked cell | same |
| `conc-over-floor` (R5) | ≥ 0.90 × the P8 floor concentration | The spec fixes the *form* (versus the measured floor), not the margin |
| `coherence-parity-abs` (R5) | ≤ 0.05 | Spec gives no number |
| `closure-rms-rad` (R3) | ≤ 0.30 | ≈ 2× the expected noise of a 3-pair closure at ~190 looks and coherence ~0.5 |
| `closure-worst-band-mean-rad` (R3) | ≤ 0.10 | "No spatial structure" needs a number |

Thresholds taken from the spec: R1 RMS < 0.05 rad; R2 within 2× the measured generator floor; R4 per-leg offset < 0.1 px after removing the modelled bistatic term; R5 gradient ratios ≤ 2.

## Execution findings (run of 2026-09-20; corrections to the plan above)

The plan was executed inline the same day it was written. These are the places where reality differed from the plan; the task text and the embedded code below already reflect them.

**Measured timings** (3-burst fixtures, replacing the estimates above): fixtures ~10 min in total (ETAD ~4.3 min each); classical coreg 7.7 min, interferogram 3.6 min, stack deburst 1.5 min (the R5 control, with ESD); matched-config coreg 3 min (no ESD); GSLC 17 min, auto-path stack 26 min, interferogram 9 min for the S1A × S1C pair; the same pair with `-Dgslc.diagGeometry=true` (diagnostic bands) GSLC 39 min, stack 10 min, interferogram 9 min. Disk: a `-Diag` GSLC + stack is ~25 GB, so tear down as you go.

**Results so far:** crop gate FAILED first (confounded, see below) then PASSED matched (coherence Δ 0.0039, gx 0.0011, gy 0.0035); P8 classical floor concentration 0.99995 / 0.043 rad; P7 floors single-look 0.93 rad, multilooked 0.13 rad (concentration 0.999); generator floor exactly 0.0 rad (GO); GSLC pair `ven_etad` built with both engagement assertions passing.

**What differed from the plan:**

1. **The crop gate was confounded and had to be re-run with a matched chain.** The full-scene classical control on disk was built with `BILINEAR` Back-Geocoding, **no Enhanced-Spectral-Diversity**, a 2×10 coherence window and the auto DEM; the fixture chain used ESD, a 7×30 window, a staged DEM and bicubic DEM resampling. The first gate reported `coh Δ 0.142` (a look-count artefact). The matched re-test (Task 3 Steps 11-17) isolates the crop. Read the control's own `Processing_Graph` before comparing anything to it.
2. **The auto-downloaded Copernicus DEM does not finish in practice** with the maven-exec classpath: every worker thread queues on one lock in `CopernicusDirectElevationTile.getSample` (27 min, no output; the staged tile takes ~4 min). Always pass the staged GeoTIFF.
3. **The master GSLC must be `WGS84(DD)`, not UTM.** `CreateStack`'s auto path locks the secondary's grid in *degrees*; against a UTM master it fails with a non-integer lattice offset. R5's `geo_to_index` also assumes a geographic map grid. `ven_gslc.ps1` uses `WGS84(DD)`; `closure.cell_size_m` converts degree cells to metres.
4. **`cohWinRg` is 30, not 27.** IW3's mean incidence is 43.9°, not the 39° of the unit test. The assertion accepts 26-34 and rejects the old 43; `run_r5.ML_RG = 30`.
5. **P7 needs two floors.** A single-look half-pixel round trip costs ~0.93 rad RMS on speckle; the floor R4/R5 are judged against is the round trip followed by the ~100 m multilook (0.13 rad RMS, concentration 0.999). The plan's stop rule ("RMS ≥ 0.05 rad") was written for one number and was not applied to the single-look figure.
6. **R4 and R5 as designed are INVALID.** Radar-pixel lat/lon were taken from the classical product's coarse tie-point grid, which does not follow local terrain, while GSLC geocodes with the DEM. Symptoms: R5 phase concentration 0.043 (random), R4 range offsets of −11 / −20 / −27 px across the three bursts, identical for the reference and secondary legs; registering with those offsets did not recover the concentration, so it is not a constant shift. **Do not read these as GSLC findings.** The rows are recorded as INVALID in the scorecard. A terrain-aware radar↔map link is needed (bin GSLC map pixels into radar cells by their `diag_rangeIndex`/`diag_azimuthIndex`, with a stack-row → deburst-row mapping; or a SNAP Terrain-Correction map-domain comparison). Neither has been done.
7. **`offset_map.median_offsets` had a NaN bug** (tiles with holes returned NaN); fixed, with a self-test case, and R4 now uses amplitude rather than speckled intensity.
8. **Driver-library traps** (all fixed in `lib.ps1`): teeing one log file twice fails on the file lock; a killed maven-exec run returns exit 0 and leaves a partial product; the completion marker must be `End writing product|90% done` (single-operator runs print the first, graph runs the second) and an "incomplete" verdict renames the product rather than deleting it; a watch pattern that matches `FAIL` in an appended log fires on old lines — watch only new lines.
9. **Synthetic generator**: the real SLC bands are int16 (see Task 8), the zero-phase pair must be scored against zero truth, and the measured floor of exactly 0.0 required the gate rule `max(2×floor, 0.05 rad)`.

## File structure

| File | Task | Responsibility |
|---|---|---|
| `validation/gslc_parity/ven_checks.py` | 1 | Read-only `.dim` metadata checks: burst IDs, ETAD option, provenance flags, times |
| `validation/drivers/lib.ps1` | 1 | Shared PowerShell helpers: `Step`, `Invoke-Gpt`, `Invoke-MvnGpt`, `New-ParamFile`, `Assert-Log` |
| `validation/drivers/ven_fixture.ps1` | 1 | Build the 3-burst fixtures for S1A, S1C, S1D |
| `validation/gslc_parity/budget.py` | 2 | Result records, scorecard, error-budget arithmetic |
| `validation/gslc_parity/closure.py` | 2 | Co-lattice cropping, multilook, closure statistics, R3 runner |
| `validation/graphs/trad_s1_coreg_ven.xml`, `trad_s1_ifgdeb_ven.xml` | 3 | Classical chain from already-split inputs, staged DEM, ground-corrected coherence window |
| `validation/drivers/ven_classical.ps1` | 3 | Classical control and P8 twin |
| `validation/gslc_parity/crop_gate.py` | 3 | Crop-validity gate |
| `validation/drivers/ven_gslc.ps1` | 4 | One GSLC pair: GSLC → auto stack → interferogram, with log assertions |
| `validation/gslc_parity/radar_domain.py` | 4 | Radar pixel lat/lon from tie-point grids; GSLC → radar resampling |
| `validation/gslc_parity/run_r5.py` | 4 | P7, P8 and R5 runner |
| `validation/gslc_parity/offset_map.py`, `run_r4.py` | 6 | Amplitude cross-correlation registration; R4 per-leg runner |
| `validation/drivers/ven_closure.ps1` | 7 | Three pair runs + the closure verdict |
| `validation/gslc_parity/synth.py`, `synth_eval.py`, `validation/drivers/ven_synth.ps1` | 8 | Synthetic second-date generator, scorer, driver |

---

### Task 1: Fixture metadata checks, shared driver library, Venezuela fixtures (Day 1, Track A)

**Files:**
- Create: `validation/gslc_parity/ven_checks.py`
- Create: `validation/drivers/lib.ps1`
- Create: `validation/drivers/ven_fixture.ps1`

**Interfaces:**
- Produces (Python): `burst_ids(dim) -> list[int]`, `attr(dim, name) -> str`, `snap_time(s) -> float`, `etad_option(dim) -> dict`, `etad_azimuth_applied(dim) -> bool`, `check_fixture(root) -> int`. CLI: `ven_checks.py fixture <dir>` and `ven_checks.py etad <dim>`.
- Produces (PowerShell, dot-sourced): `Set-ParityLog`, `Log`, `Invoke-Gpt`, `Invoke-MvnGpt`, `New-ParamFile`, `Step`, `Assert-Log`, `$script:DEM`, `$script:PY`.
- Produces (data): in `E:\Output\parity\ven\` — `<stem>_b4-6_orb.dim` for S1A/S1C/S1D and `<stem>_b4-6_orb_etad.dim` for S1A/S1C.

- [ ] **Step 1: Create `ven_checks.py`** with exactly this content:

```python
"""Metadata checks for the Venezuela parity fixtures (read-only, regex on the .dim)."""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path


def _txt(dim) -> str:
    return Path(dim).read_text(encoding="utf-8", errors="replace")


def burst_ids(dim) -> list[int]:
    """Track-anchored (relative) S1 burst IDs in first-seen order, de-duplicated (the split
    product repeats the annotation block several times)."""
    seen, out = set(), []
    for m in re.finditer(r'<MDATTR name="burstId"[^>]*>(\d+)</MDATTR>', _txt(dim)):
        v = int(m.group(1))
        if v not in seen:
            seen.add(v)
            out.append(v)
    return out


def attr(dim, name: str) -> str:
    m = re.search(rf'<MDATTR name="{re.escape(name)}"[^>]*>([^<]*)</MDATTR>', _txt(dim))
    if not m:
        raise ValueError(f"{name!r} not found in {Path(dim).name}")
    return m.group(1)


def snap_time(s: str) -> float:
    """'23-JUN-2026 22:50:52.310630' -> seconds since the epoch (UTC)."""
    d = dt.datetime.strptime(s.strip(), "%d-%b-%Y %H:%M:%S.%f").replace(tzinfo=dt.timezone.utc)
    return d.timestamp()


def etad_option(dim) -> dict:
    """ETAD parameters the product's Processing_Graph recorded for S1-ETAD-Correction ({} if the
    product never went through it). SNAP writes them as <MDATTR name="resamplingImage">true</...>
    inside the node's parameters element, after the operator name attribute."""
    t = _txt(dim)
    m = re.search(r'<MDATTR name="operator"[^>]*>S1-ETAD-Correction</MDATTR>', t)
    if not m:
        return {}
    blk = t[m.end():m.end() + 6000]
    out = {}
    for k in ("resamplingImage", "outputPhaseCorrections", "sumOfAzimuthCorrections"):
        v = re.search(rf'<MDATTR name="{k}"[^>]*>([^<]*)</MDATTR>', blk)
        if v:
            out[k] = v.group(1).strip().lower() == "true"
    return out


def etad_azimuth_applied(dim) -> bool:
    """True when the product carries the ETAD azimuth provenance flag (the GSLC then
    suppresses its own bistatic residual, so R4 must not model it)."""
    return attr(dim, "etad_azimuth_applied").strip() == "1"


def check_fixture(root, expect_ids=(225587, 225588, 225589)) -> int:
    root = Path(root)
    ok = True
    for tag in ("A", "C", "D"):
        cand = sorted(root.glob(f"S1{tag if tag != 'A' else 'A'}_IW_SLC*_b4-6_orb.dim"))
        if not cand:
            print(f"FAIL: no {tag} orbit-corrected fixture in {root}")
            ok = False
            continue
        ids = burst_ids(cand[0])
        print(f"{tag}: burst IDs {ids}")
        if ids != list(expect_ids):
            print(f"  FAIL: expected {list(expect_ids)}")
            ok = False
    for tag in ("A", "C"):
        cand = sorted(root.glob(f"S1{tag}_IW_SLC*_b4-6_orb_etad.dim"))
        if not cand:
            print(f"FAIL: no {tag} ETAD fixture")
            ok = False
            continue
        o = etad_option(cand[0])
        print(f"{tag}: ETAD {o}")
        if not (o.get("resamplingImage") and o.get("outputPhaseCorrections")):
            print("  FAIL: ETAD must be option 1 (resamplingImage=true, outputPhaseCorrections=true)")
            ok = False
    print("FIXTURE", "OK" if ok else "FAILED")
    return 0 if ok else 1


def _selftest() -> int:
    import tempfile
    ok = True
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "x.dim"
        p.write_text('<MDATTR name="burstId" type="ascii" mode="rw">7</MDATTR>'
                     '<MDATTR name="absolute" type="ascii">9</MDATTR>'
                     '<MDATTR name="burstId" type="ascii" mode="rw">8</MDATTR>'
                     '<MDATTR name="burstId" type="ascii" mode="rw">7</MDATTR>'
                     '<MDATTR name="first_line_time" type="utc">23-JUN-2026 22:50:52.310630</MDATTR>'
                     '<MDATTR name="operator" type="ascii">S1-ETAD-Correction</MDATTR>'
                     '<MDATTR name="resamplingImage" type="ascii">true</MDATTR>'
                     '<MDATTR name="outputPhaseCorrections" type="ascii">false</MDATTR>'
                     '<MDATTR name="etad_azimuth_applied" type="uint8">1</MDATTR>')
        if burst_ids(p) != [7, 8]:
            print("  FAIL: burst_ids", burst_ids(p))
            ok = False
        if abs(snap_time(attr(p, "first_line_time")) - snap_time("23-JUN-2026 22:50:52.310630")) > 0:
            ok = False
        if snap_time("24-JUN-2026 00:00:00.000000") - snap_time("23-JUN-2026 00:00:00.000000") != 86400:
            print("  FAIL: snap_time")
            ok = False
        if etad_option(p) != {"resamplingImage": True, "outputPhaseCorrections": False}:
            print("  FAIL: etad_option", etad_option(p))
            ok = False
        if not etad_azimuth_applied(p):
            print("  FAIL: etad_azimuth_applied")
            ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if len(sys.argv) == 3 and sys.argv[1] == "fixture":
        raise SystemExit(check_fixture(sys.argv[2]))
    if len(sys.argv) == 3 and sys.argv[1] == "etad":
        print(etad_option(sys.argv[2]))
        raise SystemExit(0)
    print(__doc__)
    raise SystemExit(2)
```

- [ ] **Step 2: Run its self-test**

Run: `python validation/gslc_parity/ven_checks.py --selftest`
Expected: `SELFTEST OK`

- [ ] **Step 3: Confirm the ETAD reader against real products** (these are option-1 ETAD products already on disk)

Run: `python validation/gslc_parity/ven_checks.py etad E:/Output/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad.dim`
Expected: `{'resamplingImage': True, 'outputPhaseCorrections': True, 'sumOfAzimuthCorrections': True}`
Run the same on the `_split_Orb.dim` (no ETAD). Expected: `{}`.

- [ ] **Step 4: Create `lib.ps1`** with exactly this content:

```powershell
# Shared helpers for the Venezuela parity drivers. Dot-source it:  . "$PSScriptRoot\lib.ps1"
# ASCII ONLY. Every -P argument is quoted. No Python here-strings (they break in PowerShell).
#
# Two ways to run an operator, and WHICH ONE MATTERS:
#   Invoke-Gpt      installed SNAP (C:\Program Files\esa-snap). Use ONLY for operators this work
#                   never changed: TOPSAR-Split, Apply-Orbit-File, S1-ETAD-Correction.
#   Invoke-MvnGpt   maven-exec GPT from the repo. Runs CURRENT source without deploying, so it is
#                   the only way to exercise the pre-flight fixes (cohWinSizeMeters, GSLC lock).
#                   NOTE: a sar-op-insar change is invisible to a run launched from another module
#                   until `mvn -o -pl sar-op-insar install -DskipTests` has put it in ~/.m2.
#
# Exit code is the verdict, never the log text. An exit-0 run that produced no product is a FALSE
# success (it has misled this work before), so Step re-checks that the target exists.

$script:GPT  = 'C:\Program Files\esa-snap\bin\gpt.exe'
$script:REPO = 'E:\ESA\microwave-toolbox'
$script:LOG  = $null

function Set-ParityLog([string]$Path) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Path) | Out-Null
    $script:LOG = $Path
}

function Log([string]$m) {
    $l = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m
    Write-Host $l
    if ($script:LOG) { $l | Add-Content $script:LOG }
}

function Invoke-Gpt([string[]]$GptArgs, [string]$StepLog = $null) {
    if (-not $StepLog) { $StepLog = $script:LOG }
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        if ($StepLog -eq $script:LOG) {
            # one file, opened once: teeing the same path twice fails on the file lock
            & $script:GPT @GptArgs 2>&1 | Tee-Object -FilePath $script:LOG -Append | Out-Host
        } else {
            & $script:GPT @GptArgs 2>&1 | Tee-Object -FilePath $StepLog | Tee-Object -FilePath $script:LOG -Append | Out-Host
        }
        return [int]$LASTEXITCODE
    } finally { $ErrorActionPreference = $prev }
}

# Module/Scope pairs that are known to work (see the project memory notes):
#   GSLC-Terrain-Correction, CreateStack  -> sar-op-sar-processing , test
#   Interferogram                         -> sar-op-insar          , compile
#   classical graphs (Back-Geocoding, ESD, Interferogram, Deburst) -> sar-op-sentinel1 , compile
function Invoke-MvnGpt([string]$Module, [string]$Scope, [string[]]$GptArgs, [string]$StepLog,
                       [string]$Xmx = '24g', [string]$ExtraOpts = '') {
    foreach ($a in $GptArgs) {
        if ($a -match '\s') { throw "argument contains whitespace (use a -p parameter file): '$a'" }
    }
    $argStr = ($GptArgs | ForEach-Object { $_.Replace('\', '/') }) -join ' '
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    Push-Location $script:REPO
    try {
        $env:MAVEN_OPTS = ("-Xmx$Xmx $ExtraOpts").Trim()
        $mvnArgs = @('-o', '-q', '-pl', $Module, 'exec:java', '-Dexec.mainClass=org.esa.snap.core.gpf.main.GPT',
                     "-Dexec.classpathScope=$Scope", "-Dexec.args=$argStr")
        if ($StepLog -eq $script:LOG) {
            & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $script:LOG -Append | Out-Host
        } else {
            & mvn @mvnArgs 2>&1 | Tee-Object -FilePath $StepLog | Tee-Object -FilePath $script:LOG -Append | Out-Host
        }
        $rc = [int]$LASTEXITCODE
        # A killed or crashed maven-exec run can still return 0 and leave a partial product behind
        # (seen 2026-09-20: a run killed at 60% logged OK). A finished graph run prints "...90% done."
        # and a finished single-operator run logs "End writing product". (gpt.exe's short steps print
        # neither, so this check is NOT applied in Invoke-Gpt.) The FIRST version of this check looked
        # only for '90% done', which single-operator runs never print - it flagged a good GSLC product.
        if ($rc -eq 0 -and $StepLog -ne $script:LOG -and
            -not (Select-String -Path $StepLog -Pattern 'End writing product|90% done' -Quiet)) {
            Log "no completion marker in $StepLog - treating the run as incomplete"
            return 97
        }
        return $rc
    } finally { Pop-Location; $ErrorActionPreference = $prev }
}

# Operator parameters go in a file (-p) so no argument ever needs a space or a quote.
function New-ParamFile([string]$Path, $Params) {
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<parameters>')
    foreach ($k in $Params.Keys) { [void]$sb.AppendLine("  <$k>$($Params[$k])</$k>") }
    [void]$sb.AppendLine('</parameters>')
    [IO.File]::WriteAllText($Path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
    return $Path
}

function Remove-Partial([string]$Dim) {
    $d = [IO.Path]::ChangeExtension($Dim, '.data')
    if (Test-Path $Dim) { Remove-Item $Dim -Force }
    if (Test-Path $d)   { Remove-Item $d -Recurse -Force }
}

# $Run returns the process exit code. Skips when the target exists; deletes a partial on failure
# (a half-written product must never be mistaken for a finished one on the next run).
function Step([string]$Name, [string]$Target, [scriptblock]$Run) {
    if (Test-Path $Target) { Log "SKIP $Name (exists)"; return $true }
    Log "RUN  $Name"
    $rc = [int](& $Run | Select-Object -Last 1)
    if ($rc -eq 97) {
        # heuristic verdict: set the product aside rather than destroy it, and never leave it where
        # a later run would treat it as finished
        if (Test-Path $Target) { Move-Item $Target "$Target.incomplete" -Force }
        Log "FAIL $Name incomplete (no completion marker); product renamed to $Target.incomplete"
        return $false
    }
    if ($rc -ne 0) { Log "FAIL $Name exit $rc"; Remove-Partial $Target; return $false }
    if (-not (Test-Path $Target)) { Log "FAIL $Name exit 0 but '$Target' absent"; return $false }
    Log "OK   $Name"
    return $true
}

# Gate on a log line proving the code path ran (see validation/drivers/assert_log.py).
function Assert-Log([string]$LogFile, [string[]]$Require, [string[]]$Forbid = @()) {
    $a = @("$script:REPO\validation\drivers\assert_log.py", $LogFile)
    foreach ($r in $Require) { $a += @('--require', $r) }
    foreach ($f in $Forbid)  { $a += @('--forbid', $f) }
    & python @a | Out-Host
    return ($LASTEXITCODE -eq 0)
}

$script:DEM = 'E:/TestData/dem/copernicus30_venezuela_orbit106.tif'
$script:PY  = "$script:REPO\validation\gslc_parity"
```

- [ ] **Step 5: Create `ven_fixture.ps1`** with exactly this content:

```powershell
<#
    Venezuela IW3 VV bursts 4-6 fixtures for S1A (23 Jun), S1C (24 Jun), S1D (30 Jun 2026).

    TOPSAR-Split ONLY (spec Rule 1): never SubsetOp, never a range crop. Bursts 4-6 carry the
    same burstIds (225587/8/9) in all three products. S1C has 23767 samples per line against
    S1A/S1D's 23665 - "full range width" is not the same number on every leg.

    Outputs in E:\Output\parity\ven:
      <stem>_b4-6_orb.dim         precise orbit, NO ETAD   (A, C, D: the R3 closure legs and the synthetic base)
      <stem>_b4-6_orb_etad.dim    precise orbit + ETAD option 1   (A and C only: no S1D ETAD exists)

    The ETAD source product NAME must keep the original S1 stem or ETADUtils.getProductIndex
    cannot parse the timestamps from it, and ETAD auto-search cannot work on a burst subset
    (a ~9 s window against a ~30 s frame matches nothing) - so -PetadFile is always explicit.
    ETAD must be option 1 (resamplingImage=true + outputPhaseCorrections=true): option 2 is a
    guaranteed silent no-op for the GSLC chain.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$OUT = 'E:\Output\parity\ven'
Set-ParityLog "$OUT\fixture.log"
$FB = 4; $LB = 6

$SRC = @(
    @{ N = 'A'; F = 'E:\Data\Venezuela\S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5.SAFE.zip';
       ETAD = 'C:\Users\luis_\.snap\var\cache\etad\S1A_IW_ETA__AXDV_20260623T225050_20260623T225120_065103_0834C8_D7B7.SAFE.zip' },
    @{ N = 'C'; F = 'E:\Data\Venezuela\S1C_IW_SLC__1SDV_20260624T224958_20260624T225025_008254_010515_304A.SAFE.zip';
       ETAD = 'C:\Users\luis_\.snap\var\cache\etad\S1C_IW_ETA__AXDV_20260624T224958_20260624T225025_008254_010515_8B0D.SAFE.zip' },
    @{ N = 'D'; F = 'E:\Data\Venezuela\S1D_IW_SLC__1SDV_20260630T225009_20260630T225040_003472_006226_2543.SAFE.zip';
       ETAD = $null }
)
foreach ($s in $SRC) {
    if (-not (Test-Path $s.F)) { Log "ABORT: source $($s.F) not found"; exit 1 }
    $stem = [IO.Path]::GetFileNameWithoutExtension($s.F) -replace '\.SAFE$', ''
    $t = Join-Path $OUT "$stem`_b$FB-$LB.dim"
    $o = Join-Path $OUT "$stem`_b$FB-$LB`_orb.dim"
    $e = Join-Path $OUT "$stem`_b$FB-$LB`_orb_etad.dim"

    $ok = Step "split-$($s.N)" $t { Invoke-Gpt @('TOPSAR-Split', "-Ssource=$($s.F)", '-Psubswath=IW3',
            '-PselectedPolarisations=VV', "-PfirstBurstIndex=$FB", "-PlastBurstIndex=$LB",
            '-t', $t, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT split-$($s.N)"; exit 1 }

    $ok = Step "orbit-$($s.N)" $o { Invoke-Gpt @('Apply-Orbit-File', "-Ssource=$t",
            '-PorbitType=Sentinel Precise (Auto Download)', '-PcontinueOnFail=false',
            '-t', $o, '-f', 'BEAM-DIMAP', '-q', '8') }
    if (-not $ok) { Log "ABORT orbit-$($s.N)"; exit 1 }

    if ($s.ETAD) {
        if (-not (Test-Path $s.ETAD)) { Log "ABORT: ETAD product missing at $($s.ETAD)"; exit 1 }
        $ok = Step "etad-$($s.N)" $e { Invoke-Gpt @('S1-ETAD-Correction', "-Ssource=$o", "-PetadFile=$($s.ETAD)",
                '-PresamplingImage=true', '-PoutputPhaseCorrections=true',
                '-PsumOfRangeCorrections=true', '-PtroposphericCorrectionRg=true',
                '-PionosphericCorrectionRg=true', '-PgeodeticCorrectionRg=true',
                '-PoutputETADPhaseBand=true',
                '-t', $e, '-f', 'BEAM-DIMAP', '-q', '8') }
        if (-not $ok) { Log "ABORT etad-$($s.N)"; exit 1 }
    }
}

& python "$script:PY\ven_checks.py" fixture $OUT
if ($LASTEXITCODE -ne 0) { Log 'FIXTURE CHECK FAILED'; exit 1 }
Log 'fixture complete'
```

- [ ] **Step 6: Parse-check both scripts**

Run: `pwsh -NoProfile -Command "foreach ($f in 'validation/drivers/lib.ps1','validation/drivers/ven_fixture.ps1') { $e=$null; [void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $f), [ref]$null, [ref]$e); if ($e.Count) { $e } else { \"$f parse OK\" } }"`
Expected: two `parse OK` lines.

- [ ] **Step 7: [HEAVY] Install the pre-flight `sar-op-insar` into `~/.m2`** so runs launched from other modules see the ground-corrected coherence window (Plan 1 Task 6):

Run (from `E:\ESA\microwave-toolbox`): `mvn -o -q -pl sar-op-insar install -DskipTests`
Expected: exits 0, no output. If it fails offline for a plugin, retry once without `-o` and note that in the report.

- [ ] **Step 8: [HEAVY] Build the fixtures**

Run: `pwsh -NoProfile -File validation/drivers/ven_fixture.ps1`
Expected: log `E:\Output\parity\ven\fixture.log` ends `fixture complete`; the embedded check prints, for A, C and D, `burst IDs [225587, 225588, 225589]`, for A and C `ETAD {'resamplingImage': True, 'outputPhaseCorrections': True, ...}`, and `FIXTURE OK`.
If `Apply-Orbit-File` fails, it needs the network to fetch precise orbits: report the exact error; do not substitute predicted orbits.

- [ ] **Step 9: Hand off** — files created: the three above. Report the wall-clock of each `Step` (from `fixture.log`) so the schedule can be re-estimated. Do not commit.

---

### Task 2: Scorecard and closure code (Day 1, Track B — code only, parallel to Task 1's compute)

**Files:**
- Create: `validation/gslc_parity/budget.py`
- Create: `validation/gslc_parity/closure.py`

**Interfaces:**
- Produces: `budget.record(rung, source, gate, value, threshold, provenance, passed, note="", root=None) -> Path` (one JSON per gate; re-recording a gate replaces it), `budget.load(root=None) -> list[dict]`, `budget.scorecard_md(rows) -> str`, `budget.budget_row(residual_rad, terms) -> dict`.
- Produces: `closure.colattice_offset(geo_a, geo_b, tol_px=0.02) -> (row, col)`, `closure.crop_to_common(fields, geos)`, `closure.multilook(z, ml_az, ml_rg)`, `closure.closure_stats(z_ab, z_bc, z_ac, coh_min_amp=0.0) -> dict`, `closure.run(dim_ac, dim_cd, dim_ad) -> int`. CLI: `closure.py run <AC.dim> <CD.dim> <AD.dim>`.
- Consumes: `gslc_equivalence.geotransform`, `.load_complex_ifg`, `.emit_gate` (only inside `closure.run`, imported lazily).

- [ ] **Step 1: Create `budget.py`** with exactly this content:

```python
"""Scorecard and error-budget bookkeeping. Every rung records ONE JSON row per gate; nothing is
typed by hand, so the scorecard cannot drift from what was measured."""
from __future__ import annotations

import json
import math
import os
import sys
import tempfile
from pathlib import Path

RESULTS = Path(os.environ.get("PARITY_RESULTS", "E:/Output/parity/results"))


def record(rung: str, source: str, gate: str, value: float, threshold: float | None,
           provenance: str, passed: bool | None, note: str = "", root: Path | None = None) -> Path:
    """Write one result. `provenance` says where the threshold came from: 'spec', 'measured floor
    (<file>)' or 'PROVISIONAL: chosen before measurement, no prior data'. passed=None means the
    row is a measurement with no gate (R0-style)."""
    root = Path(root) if root else RESULTS
    root.mkdir(parents=True, exist_ok=True)
    row = {"rung": rung, "source": source, "gate": gate,
           "value": None if value is None or (isinstance(value, float) and math.isnan(value)) else float(value),
           "threshold": threshold, "provenance": provenance, "passed": passed, "note": note}
    p = root / f"{rung}__{source}__{gate}.json".replace(" ", "_")
    p.write_text(json.dumps(row, indent=2), encoding="utf-8")
    return p


def load(root: Path | None = None) -> list[dict]:
    root = Path(root) if root else RESULTS
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted(root.glob("*.json"))]


def scorecard_md(rows: list[dict]) -> str:
    def cell(r):
        v = "n/a" if r["value"] is None else f"{r['value']:.4g}"
        t = "" if r["threshold"] is None else f" (gate {r['threshold']:.4g})"
        s = "MEASURED" if r["passed"] is None else ("PASS" if r["passed"] else "FAIL")
        return f"{s} {v}{t}"
    lines = ["| Rung | Source | Gate | Result | Threshold provenance | Note |", "|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['rung']} | {r['source']} | {r['gate']} | {cell(r)} | {r['provenance']} | {r['note']} |")
    return "\n".join(lines)


def budget_row(residual_rad: float, terms: dict[str, float]) -> dict:
    """Decompose a measured residual into named terms (each an RMS in rad, assumed independent):
    explained = sqrt(sum t^2), unexplained = sqrt(max(res^2 - explained^2, 0)). A term that alone
    exceeds the residual is flagged, because an over-explained residual means a term is wrong."""
    expl2 = sum(t * t for t in terms.values())
    over = [k for k, t in terms.items() if t > residual_rad * 1.0000001]
    return {"residual_rad": residual_rad, "explained_rad": math.sqrt(expl2),
            "unexplained_rad": math.sqrt(max(residual_rad ** 2 - expl2, 0.0)),
            "coverage": (expl2 / residual_rad ** 2) if residual_rad > 0 else float("nan"),
            "over_explained": over, "terms": dict(terms)}


def _selftest() -> int:
    ok = True
    with tempfile.TemporaryDirectory() as d:
        record("R3", "venezuela", "closure-rms", 0.21, 0.3, "PROVISIONAL: chosen before measurement", True, root=Path(d))
        record("R1", "venezuela", "rms", float("nan"), 0.05, "spec", False, "no data", root=Path(d))
        record("R0", "venezuela", "coh", 0.4, None, "n/a", None, root=Path(d))
        rows = load(Path(d))
        md = scorecard_md(rows)
        print(md)
        if len(rows) != 3 or "PASS 0.21 (gate 0.3)" not in md or "FAIL n/a (gate 0.05)" not in md or "MEASURED 0.4" not in md:
            print("  FAIL: scorecard")
            ok = False
        record("R3", "venezuela", "closure-rms", 0.25, 0.3, "x", True, root=Path(d))
        if len(load(Path(d))) != 3:
            print("  FAIL: re-recording a gate must replace, not duplicate")
            ok = False
    b = budget_row(0.5, {"ETAD": 0.3, "geoloc": 0.2})
    print(b)
    if not (abs(b["explained_rad"] - math.sqrt(0.13)) < 1e-12 and abs(b["unexplained_rad"] - math.sqrt(0.12)) < 1e-12
            and not b["over_explained"]):
        print("  FAIL: budget arithmetic")
        ok = False
    if budget_row(0.1, {"a": 0.3})["over_explained"] != ["a"] or budget_row(0.1, {"a": 0.3})["unexplained_rad"] != 0.0:
        print("  FAIL: over-explained residual must be flagged")
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if "--scorecard" in sys.argv:
        print(scorecard_md(load()))
        raise SystemExit(0)
    print("usage: budget.py --selftest | --scorecard")
    raise SystemExit(2)
```

- [ ] **Step 2: Run its self-test**

Run: `python validation/gslc_parity/budget.py --selftest`
Expected: a three-row markdown table, the budget dict, `SELFTEST OK`.

- [ ] **Step 3: Create `closure.py`** with exactly this content:

```python
"""R3 phase closure over three pairwise interferograms on co-lattice grids."""
from __future__ import annotations

import sys

import numpy as np


def colattice_offset(geo_a, geo_b, tol_px: float = 0.02) -> tuple[int, int]:
    """Integer (row, col) offset of grid B's origin relative to grid A's, from
    (dx, dy, lon0, lat0) tuples. Refuses a fractional remainder above tol_px: resampling
    would inject exactly the interpolation error the closure is meant to expose."""
    dxa, dya, lon_a, lat_a = geo_a
    dxb, dyb, lon_b, lat_b = geo_b
    if abs(dxa - dxb) > 1e-9 * abs(dxa) or abs(dya - dyb) > 1e-9 * abs(dya):
        raise ValueError(f"step mismatch: ({dxa},{dya}) vs ({dxb},{dyb})")
    oc = (lon_b - lon_a) / dxa
    orow = (lat_a - lat_b) / dya
    if max(abs(oc - round(oc)), abs(orow - round(orow))) > tol_px:
        raise ValueError(f"grids are not co-lattice: offset ({orow:.4f}, {oc:.4f}) px")
    return int(round(orow)), int(round(oc))


def cell_size_m(geo) -> tuple[float, float]:
    """(east, north) cell size in metres from a (dx, dy, x0, y0) tuple. A geographic grid (steps in
    degrees, which are always < 0.1) is converted at the grid's own latitude; a projected grid is
    already in metres. The GSLC pair grids are WGS84(DD): the CreateStack auto path locks the secondary
    in degrees and cannot lock it to a projected master."""
    import math
    dx, dy, _x0, y0 = geo
    if abs(dx) < 0.1:
        return abs(dx) * 111320.0 * math.cos(math.radians(y0)), abs(dy) * 110574.0
    return abs(dx), abs(dy)


def crop_to_common(fields, geos):
    """Crop complex fields to their common window; fields[0] is the reference lattice."""
    offs = [colattice_offset(geos[0], g) for g in geos]        # (row, col) of each origin in ref px
    r0 = max(o[0] for o in offs)
    c0 = max(o[1] for o in offs)
    r1 = min(o[0] + f.shape[0] for o, f in zip(offs, fields))
    c1 = min(o[1] + f.shape[1] for o, f in zip(offs, fields))
    if r1 <= r0 or c1 <= c0:
        raise ValueError("no common window")
    return [f[r0 - o[0]:r1 - o[0], c0 - o[1]:c1 - o[1]] for o, f in zip(offs, fields)]


def multilook(z: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    """Complex block sum; a block containing any zero (no-data) sample is set to 0."""
    h = (z.shape[0] // ml_az) * ml_az
    w = (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    s = zz.sum(axis=(1, 3))
    bad = (zz == 0).any(axis=(1, 3))
    return np.where(bad, 0, s)


def closure_stats(z_ab: np.ndarray, z_bc: np.ndarray, z_ac: np.ndarray,
                  coh_min_amp: float = 0.0) -> dict:
    """Closure phasor z_ab * z_bc * conj(z_ac) (all defined first*conj(second)).
    Returns RMS about zero, RMS about the mean, the mean, and the per-row-band means."""
    c = z_ab * z_bc * np.conj(z_ac)
    valid = (np.abs(c) > coh_min_amp) & np.isfinite(c.real) & (z_ab != 0) & (z_bc != 0) & (z_ac != 0)
    if not valid.any():
        return {"n": 0, "rms_rad": float("nan"), "rms_centred_rad": float("nan"),
                "mean_rad": float("nan"), "band_means_rad": []}
    ph = np.angle(c[valid])
    mean = float(np.angle(np.exp(1j * ph).mean()))
    centred = np.angle(np.exp(1j * (ph - mean)))
    rows = np.where(valid.any(axis=1))[0]
    bands = np.array_split(rows, 6)
    bm = []
    for b in bands:
        m = valid[b[0]:b[-1] + 1]
        if m.any():
            bm.append(float(np.angle(c[b[0]:b[-1] + 1][m].sum())))
    return {"n": int(valid.sum()), "rms_rad": float(np.sqrt(np.mean(ph ** 2))),
            "rms_centred_rad": float(np.sqrt(np.mean(centred ** 2))),
            "mean_rad": mean, "band_means_rad": bm}


def run(dim_ac: str, dim_cd: str, dim_ad: str) -> int:
    """R3 on three GSLC interferograms, each formed first*conj(second): AC, CD, AD. Closure is
    z_AC * z_CD * conj(z_AD). All three sit on the standard grid, so they share a lattice up to an
    integer offset; anything else is refused rather than resampled."""
    import sys as _s
    from pathlib import Path as _P
    _s.path.insert(0, str(_P(__file__).resolve().parents[1]))
    import gslc_equivalence as ge
    from budget import record
    fields, geos = [], []
    for d in (dim_ac, dim_cd, dim_ad):
        i, q, w, h = ge.load_complex_ifg(d)
        fields.append(np.asarray(i[:], np.float64) + 1j * np.asarray(q[:], np.float64))
        geos.append(ge.geotransform(d))
    ac, cd, ad = crop_to_common(fields, geos)
    dx_m, dy_m = cell_size_m(geos[0])
    ml_col, ml_row = max(1, round(100.0 / dx_m)), max(1, round(100.0 / dy_m))
    print(f"# common window {ac.shape}, multilook {ml_row} rows x {ml_col} cols (~100 m)")
    s = closure_stats(multilook(ac, ml_row, ml_col), multilook(cd, ml_row, ml_col), multilook(ad, ml_row, ml_col))
    print(s)
    prov = "PROVISIONAL: chosen before measurement, no prior data"
    worst_band = max((abs(b) for b in s["band_means_rad"]), default=float("nan"))
    ok = True
    for name, val, thr in (("closure-rms-rad", s["rms_rad"], 0.30), ("closure-worst-band-mean-rad", worst_band, 0.10)):
        passed = bool(val <= thr)
        ge.emit_gate(name, passed, val, thr)
        record("R3", "venezuela", name, val, thr, prov, passed, "ETAD-off on all three legs (no S1D ETAD exists)")
        ok &= passed
    record("R3", "venezuela", "closure-rms-centred-rad", s["rms_centred_rad"], None, "measured", None)
    record("R3", "venezuela", "closure-mean-rad", s["mean_rad"], None, "measured", None)
    return 0 if ok else 1


def _selftest() -> int:
    rng = np.random.default_rng(5)
    ok = True
    H, W = 240, 320
    a = np.exp(1j * rng.uniform(-np.pi, np.pi, (H, W)))
    c = np.exp(1j * rng.uniform(-np.pi, np.pi, (H, W)))
    d = np.exp(1j * rng.uniform(-np.pi, np.pi, (H, W)))
    # perfectly consistent triple -> closure exactly zero
    s = closure_stats(a * np.conj(c), c * np.conj(d), a * np.conj(d))
    print(f"consistent triple: rms {s['rms_rad']:.2e}")
    if not s["rms_rad"] < 1e-6:
        print("  FAIL: consistent triple must close"); ok = False
    # inject a 0.4 rad constant into one pair -> mean 0.4, centred rms ~0
    s = closure_stats(a * np.conj(c) * np.exp(0.4j), c * np.conj(d), a * np.conj(d))
    print(f"0.4 rad offset: mean {s['mean_rad']:.3f} centred {s['rms_centred_rad']:.2e} raw {s['rms_rad']:.3f}")
    if not (abs(s["mean_rad"] - 0.4) < 1e-6 and s["rms_centred_rad"] < 1e-6 and abs(s["rms_rad"] - 0.4) < 1e-6):
        print("  FAIL: constant offset must show in raw rms and mean, not centred"); ok = False
    # a wrong-sign pair (conj) must NOT close -> catches a convention mismatch
    s = closure_stats(np.conj(a * np.conj(c)), c * np.conj(d), a * np.conj(d))
    print(f"wrong-sign pair: rms {s['rms_rad']:.3f}")
    if s["rms_rad"] < 0.5:
        print("  FAIL: sign mismatch must be visible"); ok = False
    # zero (no-data) blocks are excluded
    z = a * np.conj(c)
    z[:20] = 0
    s = closure_stats(z, c * np.conj(d), a * np.conj(d))
    if s["n"] != (H - 20) * W:
        print(f"  FAIL: no-data excluded, n={s['n']}"); ok = False
    # lattice offsets
    g = (1e-4, 1e-4, -69.0, 11.0)
    if colattice_offset(g, (1e-4, 1e-4, -69.0 + 5e-4, 11.0 - 3e-4)) != (3, 5):
        print("  FAIL: integer offset"); ok = False
    try:
        colattice_offset(g, (1e-4, 1e-4, -69.0 + 5.5e-4, 11.0))
        print("  FAIL: fractional offset must raise"); ok = False
    except ValueError:
        print("fractional offset rejected")
    f = crop_to_common([np.ones((10, 12)), np.ones((10, 12))], [g, (1e-4, 1e-4, -69.0 + 2e-4, 11.0 - 1e-4)])
    if f[0].shape != (9, 10) or f[1].shape != (9, 10):
        print(f"  FAIL: crop shapes {f[0].shape} {f[1].shape}"); ok = False
    ex, ny = cell_size_m((2.1333673657863983e-5, 1.2664129273964875e-4, -69.0, 10.44))
    if not (abs(ex - 2.34) < 0.03 and abs(ny - 14.0) < 0.15) or cell_size_m((2.35, 14.0, 5e5, 1.1e6)) != (2.35, 14.0):
        print(f"  FAIL: cell_size_m {ex:.3f} x {ny:.3f}")
        ok = False
    m = multilook(np.ones((8, 12), complex), 4, 6)
    if m.shape != (2, 2) or abs(m[0, 0] - 24) > 1e-9:
        print("  FAIL: multilook"); ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    if len(sys.argv) == 5 and sys.argv[1] == "run":
        raise SystemExit(run(sys.argv[2], sys.argv[3], sys.argv[4]))
    print("usage: closure.py run <ifg_AC.dim> <ifg_CD.dim> <ifg_AD.dim>  |  --selftest")
    raise SystemExit(2)
```

- [ ] **Step 4: Run its self-test**

Run: `python validation/gslc_parity/closure.py --selftest`
Expected: consistent triple `rms 6e-17`, the 0.4 rad offset reading `mean 0.400 centred ~1e-16 raw 0.400`, the wrong-sign pair `rms 1.814` (a convention mismatch is visible), `fractional offset rejected`, `SELFTEST OK`.

- [ ] **Step 5: Hand off** — files created: the two above. Note for the reviewer: `closure.py` imports `gslc_equivalence` and `budget` lazily inside `run`, so its self-test needs neither. Do not commit.

---

### Task 3: Classical control, P8 twin, crop-validity gate (Day 1, Track A)

**Files:**
- Create: `validation/graphs/trad_s1_coreg_ven.xml`
- Create: `validation/graphs/trad_s1_ifgdeb_ven.xml`
- Create: `validation/drivers/ven_classical.ps1`
- Create: `validation/gslc_parity/crop_gate.py`
- Create (added during execution, Steps 11-13): `validation/graphs/trad_s1_coreg_ctl.xml`, `validation/graphs/trad_s1_ifgdeb_ctl.xml`, `validation/drivers/ven_crop_matched.ps1`

**Interfaces:**
- Consumes: Task 1 (`lib.ps1`, `ven_checks.attr/snap_time`, the ETAD fixtures), Task 2 (`closure.multilook`, `budget.record`), `gslc_equivalence.load_complex_ifg / find_coherence_band / load_real_band / median_lag1_gradients / emit_gate / emit_skip`.
- Produces (data, in `E:\Output\parity\ven\`): `ven_trad_stack.dim`, `ven_trad_ifg_deb.dim` (**the R5 control**), `ven_trad_stack_deb.dim` (legs for R4), `ven_trad_alt_stack.dim`, `ven_trad_alt_ifg_deb.dim` (**the P8 twin**).
- Produces (Python): `crop_gate.row_offset`, `crop_gate.compare_arrays`, `crop_gate.gates`, `crop_gate.run(fix_dim, full_dim) -> int`. CLI: `crop_gate.py <fixture_ifg.dim> <full_scene_ifg.dim>`.

- [ ] **Step 1: Create `trad_s1_coreg_ven.xml`**

```xml
<graph id="trad_s1_coreg_ven">
  <version>1.0</version>
  <!--
    Classical S1 TOPS coregistration for the Venezuela parity campaign. Inputs are ALREADY-SPLIT
    (and orbit/ETAD-corrected) products, so there is no TOPSAR-Split node here. The DEM is the same
    staged Copernicus 30 m GeoTIFF the GSLC chain uses, so the two chains cannot differ by
    elevation source. ${resampling} is BISINC_5_POINT_INTERPOLATION for the control and
    BICUBIC_INTERPOLATION for the P8 reproducibility floor.
  -->
  <node id="Read1">
    <operator>Read</operator>
    <parameters><file>${input1}</file></parameters>
  </node>
  <node id="Read2">
    <operator>Read</operator>
    <parameters><file>${input2}</file></parameters>
  </node>
  <node id="Back-Geocoding">
    <operator>Back-Geocoding</operator>
    <sources>
      <sourceProduct refid="Read1"/>
      <sourceProduct.1 refid="Read2"/>
    </sources>
    <parameters>
      <demName>External DEM</demName>
      <externalDEMFile>${dem}</externalDEMFile>
      <externalDEMNoDataValue>0.0</externalDEMNoDataValue>
      <resamplingType>${resampling}</resamplingType>
    </parameters>
  </node>
  <node id="Enhanced-Spectral-Diversity">
    <operator>Enhanced-Spectral-Diversity</operator>
    <sources>
      <sourceProduct refid="Back-Geocoding"/>
    </sources>
    <parameters/>
  </node>
  <node id="Write">
    <operator>Write</operator>
    <sources>
      <sourceProduct refid="Enhanced-Spectral-Diversity"/>
    </sources>
    <parameters>
      <file>${output}</file>
      <formatName>BEAM-DIMAP</formatName>
    </parameters>
  </node>
</graph>
```

- [ ] **Step 2: Create `trad_s1_ifgdeb_ven.xml`**

```xml
<graph id="trad_s1_ifgdeb_ven">
  <version>1.0</version>
  <!--
    Differential interferogram + deburst, radar geometry, UNFILTERED (Goldstein would smooth away the
    very differences being measured). Flat-earth and topographic phase removed with the staged DEM.
    cohWinSizeMeters=100 makes the coherence window ~100 m on the GROUND in both this chain and the
    GSLC chain (radar branch divides by the ground-range step since the 2026-09-18 fix), so
    coherence parity compares like with like. All residual-ramp options are absent (OFF).
  -->
  <node id="Read1">
    <operator>Read</operator>
    <parameters><file>${input1}</file></parameters>
  </node>
  <node id="Interferogram">
    <operator>Interferogram</operator>
    <sources>
      <sourceProduct refid="Read1"/>
    </sources>
    <parameters>
      <subtractFlatEarthPhase>true</subtractFlatEarthPhase>
      <subtractTopographicPhase>true</subtractTopographicPhase>
      <demName>External DEM</demName>
      <externalDEMFile>${dem}</externalDEMFile>
      <externalDEMNoDataValue>0.0</externalDEMNoDataValue>
      <includeCoherence>true</includeCoherence>
      <cohWinSizeMeters>100</cohWinSizeMeters>
    </parameters>
  </node>
  <node id="TOPSAR-Deburst">
    <operator>TOPSAR-Deburst</operator>
    <sources>
      <sourceProduct refid="Interferogram"/>
    </sources>
    <parameters/>
  </node>
  <node id="Write">
    <operator>Write</operator>
    <sources>
      <sourceProduct refid="TOPSAR-Deburst"/>
    </sources>
    <parameters>
      <file>${output}</file>
      <formatName>BEAM-DIMAP</formatName>
    </parameters>
  </node>
</graph>
```

- [ ] **Step 3: Confirm the parameter names against source** (they were verified once on 2026-09-20; re-check in case the operators moved)

Run: `grep -n "externalDEMFile\|externalDEMNoDataValue\|resamplingType" sar-op-sentinel1/src/main/java/eu/esa/sar/sentinel1/gpf/BackGeocodingOp.java` and `grep -n "cohWinSizeMeters\|externalDEMFile" sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/InterferogramOp.java`
Expected: each name is a `@Parameter` field. If a name differs, fix the graph, not the source.

- [ ] **Step 4: Create `ven_classical.ps1`**

```powershell
<#
    Classical S1 TOPS control on the Venezuela 3-burst ETAD fixtures (S1A x S1C), plus the P8
    reproducibility twin (identical except the Back-Geocoding kernel).

      ven_trad_stack.dim       coreg (Back-Geocoding + ESD)              BISINC_5_POINT
      ven_trad_ifg_deb.dim     ifg (flat+topo removed, cohWin 100 m) + deburst  <- the R5 control
      ven_trad_stack_deb.dim   the coreg stack debursted, so both LEGS sit on the radar grid (R4)
      ven_trad_alt_stack.dim / ven_trad_alt_ifg_deb.dim                  BICUBIC  <- the P8 twin

    Runs through maven-exec GPT (sar-op-sentinel1 classpath) so the cohWinSizeMeters fix is live;
    that needs sar-op-insar installed into ~/.m2 first (done once in Task 1).
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\classical.log"

$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
if (-not $A -or -not $C) { Log 'ABORT: ETAD fixtures missing - run ven_fixture.ps1'; exit 1 }

$G_COREG = 'validation/graphs/trad_s1_coreg_ven.xml'
$G_IFG   = 'validation/graphs/trad_s1_ifgdeb_ven.xml'

foreach ($v in @(@{ Tag = 'ven_trad';     Res = 'BISINC_5_POINT_INTERPOLATION' },
                 @{ Tag = 'ven_trad_alt'; Res = 'BICUBIC_INTERPOLATION' })) {
    $stack = "$D\$($v.Tag)_stack.dim"
    $ifg   = "$D\$($v.Tag)_ifg_deb.dim"
    $l1 = "$D\$($v.Tag)_coreg.log"
    $l2 = "$D\$($v.Tag)_ifg.log"

    $ok = Step "$($v.Tag)-coreg" $stack {
        Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @($G_COREG, "-Pinput1=$A", "-Pinput2=$C",
            "-Pdem=$script:DEM", "-Presampling=$($v.Res)", "-Poutput=$stack") $l1 }
    if (-not $ok) { Log "ABORT $($v.Tag)-coreg"; exit 1 }

    $ok = Step "$($v.Tag)-ifg" $ifg {
        Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @($G_IFG, "-Pinput1=$stack",
            "-Pdem=$script:DEM", "-Poutput=$ifg") $l2 }
    if (-not $ok) { Log "ABORT $($v.Tag)-ifg"; exit 1 }

    # Proof the fixed code ran: the window is now derived on the ground. At IW3's ~43.9 deg
    # incidence a 100 m request gives 30 range pixels (measured 2026-09-20); the old slant-spacing
    # formula gave 43. The pattern accepts 26-34 and so rejects 43.
    if (-not (Assert-Log $l2 @('cohWinSizeMeters=100\.0 m -> cohWinAz=7, cohWinRg=(2[6-9]|3[0-4])') @('OutOfMemoryError|NullPointerException'))) {
        Log "FAIL $($v.Tag): the ground-corrected coherence window did not engage"; exit 1
    }
}
# Legs on the debursted radar grid for R4 (TOPSAR-Deburst is unchanged by this work: installed gpt).
$sd = "$D\ven_trad_stack_deb.dim"
$ok = Step 'ven_trad-stack-deburst' $sd { Invoke-Gpt @('TOPSAR-Deburst', "-Ssource=$D\ven_trad_stack.dim",
        '-t', $sd, '-f', 'BEAM-DIMAP', '-q', '8') }
if (-not $ok) { Log 'ABORT stack deburst'; exit 1 }
Log 'classical control + P8 twin complete'
```

- [ ] **Step 5: Create `crop_gate.py`**

```python
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
```

- [ ] **Step 6: Self-test the gate and parse-check the driver**

Run: `python validation/gslc_parity/crop_gate.py --selftest` — Expected: `row offset 3000`, a perturbed line reading `dgx -0.2000 dcoh +0.050`, `SELFTEST OK`.
Run: the same `pwsh` parse-check as Task 1 Step 6 on `ven_classical.ps1` — Expected: `parse OK`.

- [ ] **Step 7: [HEAVY] Run the classical control and the P8 twin**

Run: `pwsh -NoProfile -File validation/drivers/ven_classical.ps1`
Expected: `classical.log` ends `classical control + P8 twin complete`; the ifg logs each contain `cohWinSizeMeters=100.0 m -> cohWinAz=7, cohWinRg=30` (measured: IW3 incidence 43.9 deg; this proves the Plan 1 ground-range fix is live, the old formula prints `cohWinRg=43`).
If the assertion fails: the run used a stale `sar-op-insar` jar — redo Task 1 Step 7, delete `ven_trad*` products, re-run.

- [ ] **Step 8: Record which ETAD option the FULL-SCENE control was built with.** The crop gate's pixelwise number is only meaningful if the control used the same option as the fixture (option 1), because option 1 resamples the data and so changes the speckle realisation.

Run: `python validation/gslc_parity/ven_checks.py etad "E:/Output/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad.dim"`
Expected: `{'resamplingImage': True, 'outputPhaseCorrections': True, 'sumOfAzimuthCorrections': True}` (verified 2026-09-20 — this is the ETAD product the full-scene stack was built from; the downstream `_ifg_deb` product carries no ETAD node of its own, so asking it returns `{}`). Copy that line into the report. If it ever reads `resamplingImage False`, the pixelwise concentration in Step 9 is informational only.

- [ ] **Step 9: [HEAVY] Run the crop-validity gate against the R5-control fixture** (fixture ifg vs the full-scene **unfiltered** `_ifg_deb`, never the `_TC` product and never the Goldstein `_flt`)

Run: `python validation/gslc_parity/crop_gate.py E:/Output/parity/ven/ven_trad_ifg_deb.dim "E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb.dim"`
Expected: three `GATE crop-…` lines and a `# rows N..M of 13582` comment with `M` at least 1358 short of 13582 (burst 10 excluded). Exit 0 means PASS.
**What happened on 2026-09-20 — read this before believing a FAIL here:** it FAILED (`crop-coherence-delta 0.142` against 0.03; gradient deltas 0.002 passed; informational pixelwise concentration 0.49) and the FAIL was **confounded, not a crop result**. The full-scene control on disk was built by a different chain than this fixture: coherence window 2×10 px (this fixture 7×30), auto-downloaded Copernicus DEM (staged tile here), `BILINEAR` Back-Geocoding and DEM resampling (`BISINC`/`BICUBIC` here), and **no Enhanced-Spectral-Diversity step at all** in its recorded history (ESD on here). A 10× difference in looks alone moves the coherence estimator floor. The gate cannot isolate the crop unless everything else matches.
**Rule:** compare a fixture to a full-scene control only if the fixture was built with the control's own recorded parameters. Read them from the control's `Processing_Graph`; do not assume them. Record the confounded FAIL as it stands (Step 10) and go to Step 11 — do **not** loosen the thresholds and do **not** delete the row.

- [ ] **Step 10: Record the first result honestly**

Run (replace `1` with the exit code Step 9 returned): `python -c "import sys; sys.path.insert(0,'validation/gslc_parity'); from budget import record; rc=int(sys.argv[1]); record('CROP','venezuela','crop-gate',float(rc),0.0,'PROVISIONAL: chosen before measurement, no prior data', rc==0,'as run: comparison CONFOUNDED (control used cohWin 2x10, auto DEM, BILINEAR, no ESD; fixture used 7x30, staged DEM, BICUBIC, ESD). Not a clean crop test.')" 1`
Expected: writes `E:/Output/parity/results/CROP__venezuela__crop-gate.json`. Keep it: the scorecard must show that the first attempt failed and why.

- [ ] **Step 11: Create the matched-configuration graphs** (parameters read from the control's own `Processing_Graph` on 2026-09-20)

Read them again first in case the control was rebuilt: `python -c "import re; t=open(r'E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb.dim',encoding='utf-8',errors='replace').read(); print(re.findall(r'<MDATTR name=\"operator\"[^>]*>([^<]*)</MDATTR>',t))"` — expected operator history `TOPSAR-Split, Apply-Orbit-File, S1-ETAD-Correction, Back-Geocoding, Interferogram, TOPSAR-Deburst` (each followed by `Write`) with **no** `Enhanced-Spectral-Diversity`.

`validation/graphs/trad_s1_coreg_ctl.xml`:

```xml
<graph id="trad_s1_coreg_ctl">
  <version>1.0</version>
  <!--
    MATCHED-CONFIGURATION coregistration: reproduces, parameter for parameter, the chain that built the
    FULL-SCENE classical control on disk (E:/Output/trad/..._Stack.dim), read from that product's own
    Processing_Graph on 2026-09-20: Back-Geocoding only (NO Enhanced-Spectral-Diversity),
    resamplingType BILINEAR_INTERPOLATION, demResamplingMethod BILINEAR_INTERPOLATION, DEM
    "Copernicus 30m Global DEM" (auto), maskOutAreaWithoutElevation true.
    Used ONLY for the crop-validity gate, where the fixture must differ from the full scene by the
    crop and nothing else. The R5 control (trad_s1_coreg_ven.xml) is a different, stronger chain.
  -->
  <node id="Read1">
    <operator>Read</operator>
    <parameters><file>${input1}</file></parameters>
  </node>
  <node id="Read2">
    <operator>Read</operator>
    <parameters><file>${input2}</file></parameters>
  </node>
  <node id="Back-Geocoding">
    <operator>Back-Geocoding</operator>
    <sources>
      <sourceProduct refid="Read1"/>
      <sourceProduct.1 refid="Read2"/>
    </sources>
    <parameters>
      <demName>Copernicus 30m Global DEM</demName>
      <demResamplingMethod>BILINEAR_INTERPOLATION</demResamplingMethod>
      <resamplingType>BILINEAR_INTERPOLATION</resamplingType>
      <maskOutAreaWithoutElevation>true</maskOutAreaWithoutElevation>
    </parameters>
  </node>
  <node id="Write">
    <operator>Write</operator>
    <sources>
      <sourceProduct refid="Back-Geocoding"/>
    </sources>
    <parameters>
      <file>${output}</file>
      <formatName>BEAM-DIMAP</formatName>
    </parameters>
  </node>
</graph>
```

`validation/graphs/trad_s1_ifgdeb_ctl.xml`:

```xml
<graph id="trad_s1_ifgdeb_ctl">
  <version>1.0</version>
  <!--
    MATCHED-CONFIGURATION interferogram + deburst: the parameters recorded in the full-scene control's
    own Processing_Graph (2026-09-20): flat-earth and topographic phase subtracted,
    coherence window cohWinAz=2 x cohWinRg=10 pixels (cohWinSizeMeters 0), srpPolynomialDegree 5,
    srpNumberPoints 501, orbitDegree 3. Unfiltered. Crop-validity gate ONLY (see trad_s1_coreg_ctl.xml).
    ONE DELIBERATE DEVIATION: the control used the auto-downloaded "Copernicus 30m Global DEM"; here the
    staged Copernicus 30 m GeoTIFF is passed as ${dem}. Same data, different reader. The auto path is
    serialised by a synchronised CopernicusDirectElevationTile.getSample (every worker thread queued on
    one lock in a thread dump; 27 min with no output where the staged tile takes ~4 min), so a matched
    run on it is impractical. Residual difference to be reported with the gate result.
  -->
  <node id="Read1">
    <operator>Read</operator>
    <parameters><file>${input1}</file></parameters>
  </node>
  <node id="Interferogram">
    <operator>Interferogram</operator>
    <sources>
      <sourceProduct refid="Read1"/>
    </sources>
    <parameters>
      <subtractFlatEarthPhase>true</subtractFlatEarthPhase>
      <subtractTopographicPhase>true</subtractTopographicPhase>
      <demName>External DEM</demName>
      <externalDEMFile>${dem}</externalDEMFile>
      <externalDEMNoDataValue>0.0</externalDEMNoDataValue>
      <includeCoherence>true</includeCoherence>
      <cohWinAz>2</cohWinAz>
      <cohWinRg>10</cohWinRg>
      <srpPolynomialDegree>5</srpPolynomialDegree>
      <srpNumberPoints>501</srpNumberPoints>
      <orbitDegree>3</orbitDegree>
    </parameters>
  </node>
  <node id="TOPSAR-Deburst">
    <operator>TOPSAR-Deburst</operator>
    <sources>
      <sourceProduct refid="Interferogram"/>
    </sources>
    <parameters/>
  </node>
  <node id="Write">
    <operator>Write</operator>
    <sources>
      <sourceProduct refid="TOPSAR-Deburst"/>
    </sources>
    <parameters>
      <file>${output}</file>
      <formatName>BEAM-DIMAP</formatName>
    </parameters>
  </node>
</graph>
```

- [ ] **Step 12: Create `ven_crop_matched.ps1`**

```powershell
<#
    MATCHED-CONFIGURATION classical fixture for the crop-validity gate.

    The first crop-gate run compared the fixture chain (ground-window coherence, staged DEM, bicubic
    DEM resampling, ESD) against a full-scene control built differently (coherence window 2x10,
    auto DEM, BILINEAR resampling, NO ESD). That measured chain differences, not the crop. This
    driver rebuilds the 3-burst fixture with the control's own recorded parameters
    (validation/graphs/trad_s1_coreg_ctl.xml, trad_s1_ifgdeb_ctl.xml) so that the only differences
    left are the crop and ONE deliberate deviation: the interferogram reads the staged Copernicus 30 m
    GeoTIFF instead of the auto-downloaded DEM (same data; the auto reader is serialised by a
    synchronised getSample and does not finish in practice - see trad_s1_ifgdeb_ctl.xml).

      ven_ctl_stack.dim, ven_ctl_ifg_deb.dim   in E:\Output\parity\ven
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\crop_matched.log"

$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
if (-not $A -or -not $C) { Log 'ABORT: ETAD fixtures missing - run ven_fixture.ps1'; exit 1 }

$stack = "$D\ven_ctl_stack.dim"
$ifg   = "$D\ven_ctl_ifg_deb.dim"
$ok = Step 'ven_ctl-coreg' $stack {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_coreg_ctl.xml',
        "-Pinput1=$A", "-Pinput2=$C", "-Poutput=$stack") "$D\ven_ctl_coreg.log" }
if (-not $ok) { Log 'ABORT ven_ctl-coreg'; exit 1 }
$ok = Step 'ven_ctl-ifg' $ifg {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifgdeb_ctl.xml',
        "-Pinput1=$stack", "-Pdem=$script:DEM", "-Poutput=$ifg") "$D\ven_ctl_ifg.log" }
if (-not $ok) { Log 'ABORT ven_ctl-ifg'; exit 1 }
Log 'matched-configuration fixture complete'
```

- [ ] **Step 13: Parse-check it** (Task 1 Step 6 command). Expected: `parse OK`.

- [ ] **Step 14: [HEAVY] Run it** (~3 min coregistration + ~4 min interferogram; ESD is off, so it is faster than the R5 control)

Run: `pwsh -NoProfile -File validation/drivers/ven_crop_matched.ps1`
Expected: `crop_matched.log` ends `matched-configuration fixture complete`; both step logs contain `90% done`.
**Known trap — do not put the auto DEM back:** with `demName` = `Copernicus 30m Global DEM` the interferogram did not finish in 27 minutes (9 worker threads all blocked in `CopernicusDirectElevationTile.getSample`, a synchronised read; CPU ~1.2 cores). The matched graph therefore reads the staged Copernicus 30 m GeoTIFF instead. That is the **one deliberate deviation** from the control and must be quoted with the result.
**Do not kill a run and trust the driver afterwards:** a run killed mid-write returned exit 0 and left a partial `.dim`. Delete `ven_ctl_ifg_deb.*` before re-running.

- [ ] **Step 15: [HEAVY] Re-run the gate against the matched fixture**

Run: `python validation/gslc_parity/crop_gate.py E:/Output/parity/ven/ven_ctl_ifg_deb.dim "E:/Output/trad/S1A_IW_SLC__1SDV_20260623T225050_20260623T225120_065103_0834C8_BAD5_split_Orb_etad_Stack_ifg_deb.dim"`
Expected (2026-09-20): `GATE crop-coherence-delta PASS 0.00393377 0.03`, `crop-gx-delta PASS 0.00107947`, `crop-gy-delta PASS 0.00354021`; informational pixelwise concentration `0.4904`. Same thresholds as Step 9 — unchanged.
The pixelwise 0.49 is **not** explained: the chains match, yet single-look phases agree at only ~0.49. It is informational (not gated) and is the reason every R4/R5 phase comparison is made on multilooked cells. Do not read it as a fixture defect without a further test.
**If this FAILS,** stop the campaign and report.

- [ ] **Step 16: Record the matched result**

Run (replace `0` with the exit code): `python -c "import sys; sys.path.insert(0,'validation/gslc_parity'); from budget import record; rc=int(sys.argv[1]); record('CROP','venezuela','crop-gate-matched',float(rc),0.0,'PROVISIONAL: chosen before measurement, no prior data', rc==0,'matched configuration (control params from its Processing_Graph: BILINEAR, no ESD, cohWin 2x10); one deviation: staged Cop30 GeoTIFF instead of auto DEM. Supersedes the confounded crop-gate row.')" 0`
Expected: writes `CROP__venezuela__crop-gate-matched.json`.

- [ ] **Step 17: Hand off** — files created: the four originally listed plus the two `_ctl` graphs and `ven_crop_matched.ps1`. Report both crop-gate results side by side, the deviation, and the ESD finding: **the classical control on disk was built without ESD**, so the R5 control in this campaign (which has ESD) is a stronger chain than the one previously used. Do not commit.

---

### Task 4: GSLC pair driver, radar-domain machinery, P7 floor (Day 2, Track A)

**Files:**
- Create: `validation/drivers/ven_gslc.ps1`
- Create: `validation/gslc_parity/radar_domain.py`
- Create: `validation/gslc_parity/run_r5.py`

**Interfaces:**
- Consumes: Task 1 (`lib.ps1`), Task 2 (`budget.record`, `closure.multilook`), Task 3 (`ven_trad_ifg_deb.dim`), existing `inverse_geocode._bilinear_complex / _read_geo / geo_to_index / roundtrip_floor`.
- Produces: `ven_gslc.ps1 -Ref <dim> -Sec <dim> -Tag <name> [-Ramp] [-Diag]` writing `<Tag>_gslc.dim`, `<Tag>_stack.dim`, `<Tag>_ifg.dim` (`<Tag>_ifg_ramp.dim` with `-Ramp`) in `E:\Output\parity\ven\`.
- Produces: `radar_domain.hdr_dtype(hdr) -> np.dtype`, `read_tpg`, `tpg_at`, `radar_latlon(dim, rows, cols) -> (lat, lon)`, `gslc_to_radar(field_map, map_dim, radar_dim, r0, nr, c0, nc)`.
- Produces: `run_r5.py p7 <gslc_ifg>`, `run_r5.py p8 <alt> <base>`, `run_r5.py r5 <gslc_ifg> <classical_ifg> <floor_conc>`; `run_r5.gate_metrics`, `run_r5.r5_verdict`.

- [ ] **Step 0: Replace `validation/gslc_parity/inverse_geocode.py` with this version** (the Plan 1 file, plus `geo_to_index` — the corner-origin half-pixel fix — and `roundtrip_stats` / `_block_sum`, which measure the round-trip floor at single-look **and** multilooked scale). Its own self-test now includes the closed-form bilinear cases, the half-pixel convention and a coherent-plus-speckle round trip.

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

FLOOR STATUS: not yet measured on a GSLC product - no GSLC interferogram exists at the
time this module landed. Plan 2 measures it on the first Venezuela GSLC ifg, and no R4
or R5 number may be quoted before that measurement is recorded here.
"""
from __future__ import annotations

import numpy as np


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


def geo_to_index(lats, lons, dx, dy, lon0, lat0):
    """Map lat/lon to fractional ARRAY indices (row, col), integer = pixel CENTRE.

    SNAP's IMAGE_TO_MODEL_TRANSFORM maps image coordinates in which integers are pixel
    CORNERS (CrsGeoCoding.createImageToMapTransform; getPixelsOneByOne evaluates pixel
    (x, y) at image coordinate (x + 0.5, y + 0.5)). _bilinear_complex indexes the array
    with integers at pixel centres, so the affine result must be shifted by -0.5 on both
    axes. Omitting this samples every position half a pixel off.
    """
    cols = (np.asarray(lons) - lon0) / dx - 0.5
    rows = (lat0 - np.asarray(lats)) / dy - 0.5
    return rows, cols


def sample_map_at(dim_path: str, lats: np.ndarray, lons: np.ndarray) -> np.ndarray:
    """Sample a map-grid complex interferogram at arbitrary lat/lon positions."""
    field, w, h = _load_complex(dim_path)
    dx, dy, lon0, lat0 = _read_geo(dim_path)
    rows, cols = geo_to_index(lats, lons, dx, dy, lon0, lat0)
    return _bilinear_complex(field, rows, cols)


def _block_sum(z: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    """Complex block sum; a block containing a NaN or a zero (no-data) sample becomes 0."""
    h, w = (z.shape[0] // ml_az) * ml_az, (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    bad = (~np.isfinite(zz.real) | ~np.isfinite(zz.imag) | (zz == 0)).any(axis=(1, 3))
    return np.where(bad, 0, np.nan_to_num(zz, nan=0.0).sum(axis=(1, 3)))


def roundtrip_stats(sub: np.ndarray, shift=(0.5, 0.5), ml=None) -> dict:
    """Forward-backward resample `sub` by `shift` and compare with the untouched original.

    ml=None compares single looks (the per-pixel cost of bilinear on speckle). ml=(az, rg) sums both
    fields over az x rg blocks BEFORE comparing - the scale R5 actually judges at, because the
    inverse-geocoded interferogram is multilooked to ~100 m cells before any gate runs.
    Returns concentration and RMS-about-the-mean."""
    dr, dc = shift
    bh, bw = sub.shape
    rows, cols = np.mgrid[0:bh, 0:bw].astype(np.float64)
    once = _bilinear_complex(sub, rows + dr, cols + dc)
    twice = _bilinear_complex(once, rows - dr, cols - dc)
    inner = (slice(2, bh - 2), slice(2, bw - 2))    # edge cells have no neighbour to interpolate from
    a, b = sub[inner], twice[inner]
    if ml is not None:
        a, b = _block_sum(a, *ml), _block_sum(b, *ml)
    d = a * np.conj(b)
    good = np.isfinite(d.real) & np.isfinite(d.imag) & (np.abs(d) > 0)
    z = d[good]
    if z.size == 0:
        return {"conc": float("nan"), "rms_rad": float("nan"), "n": 0}
    conc = float(abs(z.sum()) / np.abs(z).sum())
    mp = z.sum()
    centred = z * np.conj(mp / abs(mp)) if mp != 0 else z
    return {"conc": conc, "rms_rad": float(np.sqrt(np.mean(np.angle(centred) ** 2))), "n": int(z.size)}


def roundtrip_floor(dim_path: str, shift: tuple[float, float] = (0.5, 0.5),
                    block: int = 2048, ml=None) -> dict:
    """Measure this sampler's own phase cost on the product's REAL wrapped fringes.

    A central block (not the whole raster) keeps memory bounded on a 23665 x 13582 product while
    still sampling real fringes. (0.5, 0.5) is the worst case for bilinear. Pass ml=(az, rg) for the
    multilooked floor that R4/R5 are actually judged against; ml=None is the single-look cost.
    """
    field, w, h = _load_complex(dim_path)
    r0 = max(0, h // 2 - block // 2)
    c0 = max(0, w // 2 - block // 2)
    sub = np.asarray(field[r0:r0 + block, c0:c0 + block], dtype=np.complex128)
    return roundtrip_stats(sub, shift, ml)


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

    # Case 4: exact closed form with UNEQUAL row/col fractions. Real = y^2, imag = 3 x^2, so the
    # linear interpolant between the floor neighbours is y0^2 + fr(2 y0 + 1) (resp. 3(...)).
    # A row/col swap, floor->round, or a wrong weight all break this; cases 1-2 cannot see them.
    q = np.mgrid[0:16, 0:16]
    qf = (q[0] ** 2).astype(float) + 1j * 3.0 * (q[1] ** 2)
    r, c = np.array([[5.3, 7.9]]), np.array([[9.7, 2.1]])
    got = _bilinear_complex(qf, r, c)
    r0, c0 = np.floor(r), np.floor(c)
    exp = (r0 ** 2 + (r - r0) * (2 * r0 + 1)) + 1j * 3.0 * (c0 ** 2 + (c - c0) * (2 * c0 + 1))
    err = float(np.max(np.abs(got - exp)))
    print(f"unequal-fraction closed-form error: {err:.3e}")
    if not (err < 1e-12):
        print("  FAIL: bilinear weights/orientation wrong"); ok = False

    # Case 5: a zero contributor (BEAM-DIMAP no-data fill) must give NaN, not a partial value.
    z = qf.copy()
    z[6, 10] = 0
    got = _bilinear_complex(z, np.array([[5.3]]), np.array([[9.7]]))
    print("zero-neighbour ->", got)
    if not np.all(np.isnan(got)):
        print("  FAIL: zero-fill contributor must give NaN"); ok = False

    # Case 6: pixel-centre convention. Pixel (row 3, col 5) has its centre at image coordinate
    # (5.5, 3.5) under SNAP's corner-origin affine; it must map back to integer indices (3, 5).
    dx_, dy_, lon0_, lat0_ = 1.25e-4, 1.25e-4, -69.2, 11.4
    lon_c = lon0_ + (5 + 0.5) * dx_
    lat_c = lat0_ - (3 + 0.5) * dy_
    r_, c_ = geo_to_index(np.array([lat_c]), np.array([lon_c]), dx_, dy_, lon0_, lat0_)
    print(f"pixel-centre -> index: ({float(r_[0]):.6f}, {float(c_[0]):.6f})")
    if not (abs(r_[0] - 3) < 1e-6 and abs(c_[0] - 5) < 1e-6):
        print("  FAIL: pixel centre must map to integer array indices"); ok = False

    # Case 7: the round trip on a COHERENT-PLUS-SPECKLE field (signal 1 + unit complex noise, i.e.
    # coherence^2 ~ 0.33, typical of the real pair). Per pixel the half-pixel double-bilinear smears
    # the phase (rms ~ 1 rad); after a 7x30 multilook both fields agree to ~0.02 rad - the scale R5
    # judges at. A zero-mean speckle field is the wrong test: its multilooked phase is pure noise.
    rng = np.random.default_rng(11)
    ny7, nx7 = 210, 600
    fr = np.exp(1j * np.add.outer(np.linspace(0, 6, ny7), np.linspace(0, 18, nx7)))
    sp = fr * (1.0 + rng.normal(size=(ny7, nx7)) + 1j * rng.normal(size=(ny7, nx7)))
    single = roundtrip_stats(sp, (0.5, 0.5))
    looked = roundtrip_stats(sp, (0.5, 0.5), ml=(7, 30))
    print(f"speckle round trip: single-look rms {single['rms_rad']:.3f} rad, 7x30 multilook rms {looked['rms_rad']:.3f} rad")
    if not (single["rms_rad"] > 0.5 and looked["rms_rad"] < 0.05 and looked["conc"] > 0.99):
        print("  FAIL: single-look floor must be large and the multilooked floor small"); ok = False

    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    import sys
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
```

Run: `python validation/gslc_parity/inverse_geocode.py --selftest` — Expected: `SELFTEST OK`, including `pixel-centre -> index: (3.000000, 5.000000)` and `speckle round trip: single-look rms 0.952 rad, 7x30 multilook rms 0.015 rad`.

- [ ] **Step 1: Create `radar_domain.py`**

```python
"""Radar-domain comparison helpers: lat/lon of every radar pixel from SNAP tie-point grids, and
resampling of a map-grid (GSLC) complex field onto that radar grid."""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inverse_geocode import _bilinear_complex, geo_to_index, _read_geo  # noqa: E402


def hdr_dtype(hdr: Path) -> np.dtype:
    """numpy dtype of an ENVI .hdr (float32/float64, honouring its byte order)."""
    t = Path(hdr).read_text()
    code = int(re.search(r"^data type\s*=\s*(\d+)", t, re.M).group(1))
    bo = int(re.search(r"^byte order\s*=\s*(\d)", t, re.M).group(1))
    return np.dtype({4: np.float32, 5: np.float64}[code]).newbyteorder(">" if bo == 1 else "<")


def read_tpg(dim_path: str, name: str):
    """(values[NROWS,NCOLS], offset_x, offset_y, step_x, step_y) of a tie-point grid."""
    dim = Path(dim_path)
    txt = dim.read_text(encoding="utf-8", errors="replace")
    blk = re.search(r"<Tie_Point_Grid_Info>(?:(?!</Tie_Point_Grid_Info>).)*?<TIE_POINT_GRID_NAME>"
                    + re.escape(name) + r"</TIE_POINT_GRID_NAME>.*?</Tie_Point_Grid_Info>", txt, re.S)
    if not blk:
        raise ValueError(f"tie-point grid {name!r} not declared in {dim.name}")
    b = blk.group(0)

    def num(tag):
        return float(re.search(rf"<{tag}>([^<]+)</{tag}>", b).group(1))

    nc, nr = int(num("NCOLS")), int(num("NROWS"))
    img = dim.with_suffix(".data") / "tie_point_grids" / f"{name}.img"
    hdr = dim.with_suffix(".data") / "tie_point_grids" / f"{name}.hdr"
    vals = np.fromfile(img, dtype=hdr_dtype(hdr))
    return vals.reshape(nr, nc).astype(np.float64), num("OFFSET_X"), num("OFFSET_Y"), num("STEP_X"), num("STEP_Y")


def tpg_at(grid, offx, offy, stepx, stepy, rows, cols):
    """Bilinear tie-point-grid value at IMAGE coordinates (x = col + 0.5, y = row + 0.5).
    SNAP evaluates tie-point grids at pixel-centre image coordinates, exactly as
    CrsGeoCoding.getPixelsOneByOne does; a corner-indexed lookup would be half a pixel off."""
    fx = np.clip((np.asarray(cols, float) + 0.5 - offx) / stepx, 0, grid.shape[1] - 1)
    fy = np.clip((np.asarray(rows, float) + 0.5 - offy) / stepy, 0, grid.shape[0] - 1)
    x0 = np.minimum(np.floor(fx).astype(int), grid.shape[1] - 2)
    y0 = np.minimum(np.floor(fy).astype(int), grid.shape[0] - 2)
    ax, ay = fx - x0, fy - y0
    return ((1 - ay) * ((1 - ax) * grid[y0, x0] + ax * grid[y0, x0 + 1])
            + ay * ((1 - ax) * grid[y0 + 1, x0] + ax * grid[y0 + 1, x0 + 1]))


def radar_latlon(dim_path: str, rows: np.ndarray, cols: np.ndarray):
    la = read_tpg(dim_path, "latitude")
    lo = read_tpg(dim_path, "longitude")
    return tpg_at(*la, rows, cols), tpg_at(*lo, rows, cols)


def gslc_to_radar(field_map: np.ndarray, map_dim: str, radar_dim: str,
                  r0: int, nr: int, c0: int, nc: int) -> np.ndarray:
    """Sample a complex map-grid field at the lat/lon of radar pixels rows r0..r0+nr, cols c0..c0+nc."""
    rows, cols = np.mgrid[r0:r0 + nr, c0:c0 + nc]
    lat, lon = radar_latlon(radar_dim, rows, cols)
    dx, dy, lon0, lat0 = _read_geo(map_dim)
    ri, ci = geo_to_index(lat, lon, dx, dy, lon0, lat0)
    return _bilinear_complex(field_map, ri, ci)


def _selftest() -> int:
    ok = True
    g = np.add.outer(np.arange(4) * 10.0, np.arange(5) * 1.0)       # linear in both axes
    v = tpg_at(g, 0.0, 0.0, 100.0, 200.0, np.array([199.5]), np.array([99.5]))   # image (100, 200) -> node (1, 1)
    print("tpg at node ->", float(v[0]), "(expect 11.0)")
    if abs(v[0] - 11.0) > 1e-9:
        print("  FAIL: pixel-centre convention"); ok = False
    v = tpg_at(g, 0.0, 0.0, 100.0, 200.0, np.array([-0.5]), np.array([-0.5]))    # image (0,0) -> node (0,0)
    if abs(v[0]) > 1e-9:
        print("  FAIL: origin"); ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
```

- [ ] **Step 2: Self-test it and smoke-test it on the real classical product**

Run: `python validation/gslc_parity/radar_domain.py --selftest` — Expected: `tpg at node -> 11.0 (expect 11.0)`, `SELFTEST OK`.
Run: `python -c "import sys,numpy as np; sys.path.insert(0,'validation/gslc_parity'); import radar_domain as rd; d='E:/Output/parity/ven/ven_trad_ifg_deb.dim'; la,lo=rd.radar_latlon(d,np.array([0,1000]),np.array([0,1000])); print(la,lo)"`
Expected: latitudes near 9.6–11.4 and longitudes near −69.2…−68.1 (northern Venezuela). Anything else means the tie-point grid was read wrong.

- [ ] **Step 3: Create `run_r5.py`**

```python
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
```

- [ ] **Step 4: Self-test it**

Run: `python validation/gslc_parity/run_r5.py --selftest`
Expected: a metrics dict with `phase-residual-conc` above 0.95, a verdict dict `{'conc-over-floor': True, 'gx-median-ratio': True, 'gy-median-ratio': False, 'coherence-parity-abs': True}`, `SELFTEST OK`.

- [ ] **Step 5: Create `ven_gslc.ps1`**

```powershell
<#
    GSLC chain for one PAIR on the Venezuela 3-burst fixtures, through the CreateStack AUTO path
    (GSLC reference + raw secondary SLC), so the secondary is grid-locked, burst-ID matched and
    bias-corrected. The both-GSLC path has no lock and no bias estimate.

      -Ref  <dim>   reference SLC (already split + orbit [+ ETAD])   -> GSLC-Terrain-Correction
      -Sec  <dim>   secondary SLC, RAW (the stack builds its GSLC itself)
      -Tag  <name>  output prefix in E:\Output\parity\ven
      -Ramp         switch subtractResidualRamp ON (default OFF: R5 requires all residualRamp* off)
      -Diag         run with -Dgslc.diagGeometry=true (adds diag_rangeIndex etc. for the synthetic rungs)

    Products:  <Tag>_gslc.dim   <Tag>_stack.dim   <Tag>_ifg.dim   (+ <Tag>_ifg_ramp.dim with -Ramp)
#>
param(
    [Parameter(Mandatory = $true)][string]$Ref,
    [Parameter(Mandatory = $true)][string]$Sec,
    [Parameter(Mandatory = $true)][string]$Tag,
    [switch]$Ramp,
    [switch]$Diag
)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\$Tag.log"
$extra = if ($Diag) { '-Dgslc.diagGeometry=true' } else { '' }

$gslc  = "$D\$Tag`_gslc.dim"
$stack = "$D\$Tag`_stack.dim"
$ifg   = if ($Ramp) { "$D\$Tag`_ifg_ramp.dim" } else { "$D\$Tag`_ifg.dim" }

$pg = New-ParamFile "$D\$Tag`_gslc_params.xml" ([ordered]@{
    externalDEMFile = $script:DEM; externalDEMNoDataValue = '0.0';
    imgResamplingMethod = 'BISINC_5_POINT_INTERPOLATION'; gridSpacing = 'NATIVE_ANISOTROPIC';
    mapProjection = 'WGS84(DD)'; outputFlattened = 'false'; outputAzimuthCarrier = 'false';
    outputPhaseTerms = 'true'; nodataValueAtSea = 'false' })

$pi = New-ParamFile "$D\$Tag`_ifg_params$(if ($Ramp) { '_ramp' }).xml" ([ordered]@{
    subtractFlatEarthPhase = 'true'; subtractTopographicPhase = 'true'; demName = 'External DEM';
    externalDEMFile = $script:DEM; externalDEMNoDataValue = '0.0'; includeCoherence = 'true';
    cohWinSizeMeters = '100'; subtractResidualRamp = $(if ($Ramp) { 'true' } else { 'false' }) })

$ok = Step "$Tag-gslc" $gslc {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('GSLC-Terrain-Correction', "-Ssource=$Ref", '-p', $pg,
        '-t', $gslc, '-f', 'BEAM-DIMAP', '-q', '8') "$D\$Tag`_gslc.log" '24g' $extra }
if (-not $ok) { Log "ABORT $Tag-gslc"; exit 1 }

# Auto path: reference GSLC first, RAW secondary second. The lock line proves the burst-selection
# lock engaged; without it a mixed-burst strip is incoherent by construction.
$ok = Step "$Tag-stack" $stack {
    Invoke-MvnGpt 'sar-op-sar-processing' 'test' @('CreateStack', '-Pextent=Master', '-t', $stack,
        '-f', 'BEAM-DIMAP', '-q', '8', $gslc, $Sec) "$D\$Tag`_stack.log" '24g' $extra }
if (-not $ok) { Log "ABORT $Tag-stack"; exit 1 }
if (-not (Assert-Log "$D\$Tag`_stack.log" @('locked to the reference \(\d+ of \d+ seam') @('OutOfMemoryError|NullPointerException'))) {
    Log "FAIL $Tag`: burst-overlap lock did not engage"; exit 1
}

$ok = Step "$Tag-ifg" $ifg {
    Invoke-MvnGpt 'sar-op-insar' 'compile' @('Interferogram', '-p', $pi, '-t', $ifg,
        '-f', 'BEAM-DIMAP', '-q', '8', $stack) "$D\$Tag`_ifg.log" }
if (-not $ok) { Log "ABORT $Tag-ifg"; exit 1 }
if (-not (Assert-Log "$D\$Tag`_ifg.log" @('cohWinSizeMeters=100\.0 m -> cohWinAz=\d+, cohWinRg=\d+') @('OutOfMemoryError|NullPointerException'))) {
    Log "FAIL $Tag`: ifg did not report the metre-based coherence window"; exit 1
}
Log "GSLC pair $Tag complete: $ifg"
```

- [ ] **Step 6: Parse-check it** (same command as Task 1 Step 6). Expected: `parse OK`.

- [ ] **Step 7: [HEAVY] Build the ETAD-on S1A × S1C GSLC pair** (the pairwise-rung pair)

Run: `pwsh -NoProfile -File validation/drivers/ven_gslc.ps1 -Ref <S1A _b4-6_orb_etad.dim> -Sec <S1C _b4-6_orb_etad.dim> -Tag ven_etad`
(Use the full paths in `E:\Output\parity\ven\`.)
Expected: `ven_etad.log` ends `GSLC pair ven_etad complete`; the stack log contains `locked to the reference (N of N seam(s)` and the ifg log contains `cohWinSizeMeters=100.0 m -> cohWinAz=…`.
If the lock line is missing, **stop**: the burst-ID stamp did not forward, and a mixed-burst strip makes the pair incoherent by construction. Debug `readMasterBurstValidTimes` in `CreateStackOp`; do not proceed.

- [ ] **Step 8: [HEAVY] P7 — measure the inverse-geocode round-trip floor on the real GSLC fringes**

Run: `python validation/gslc_parity/run_r5.py p7 E:/Output/parity/ven/ven_etad_ifg.dim`
Expected: two dicts and four recorded rows (`roundtrip-conc-single`, `roundtrip-rms-rad-single`, `roundtrip-conc-ml`, `roundtrip-rms-rad-ml`). **Measured 2026-09-20:** single-look `conc 0.953, rms 0.934 rad (n=4,177,936)`; multilooked (7×43 map cells ≈ 100 m) `conc 0.9991, rms 0.1325 rad (n=13,724)`.
Interpretation rule (write it in the report): the **multilooked** floor is the one R4/R5 numbers are judged against, because both fields are multilooked to ~100 m cells before any gate runs; the single-look figure is only the speckle cost of a half-pixel bilinear (it scrambles speckle, it does not damage fringes). The original rule here — "stop if `rms_rad` ≥ 0.05" — was written for a single number and was **not** applied to the single-look figure; state the decision and the reason in the report, and do not silently drop the rule.

- [ ] **Step 9: Hand off** — files created: the three above. Report the `ven_etad` timings (from `ven_etad.log`) and the P7 floor. Do not commit.

---

### Task 5: P8 classical floor and the R5 verdict (Day 2, Track A — runs only, no new files)

> **STATUS 2026-09-20: P8 is valid (`conc 0.99995, rms 0.043 rad`). The R5 step below is INVALID as designed** — it inverse-geocodes through the classical product's tie-point-grid lat/lon, which ignores terrain (see "Execution findings" item 6). It ran and produced `conc 0.043`, which is a misregistration artefact, not a GSLC result. Do not run R5 again until the radar↔map link is terrain-aware.

**Files:** none created. Consumes Task 3's `ven_trad_ifg_deb.dim` / `ven_trad_alt_ifg_deb.dim` and Task 4's `ven_etad_ifg.dim`.

**Interfaces:**
- Consumes: `run_r5.py p8 / r5`, `budget.record`.
- Produces: `P8__venezuela__classical-floor-conc.json` and the `R5__venezuela__*.json` rows.

- [ ] **Step 1: [HEAVY] P8 — classical against its own twin** (the alternative differs only in the Back-Geocoding kernel; same speckle, same geometry)

Run: `python validation/gslc_parity/run_r5.py p8 E:/Output/parity/ven/ven_trad_alt_ifg_deb.dim E:/Output/parity/ven/ven_trad_ifg_deb.dim`
Expected: a metrics dict; `P8 classical reproducibility floor` recorded. Read `phase-residual-conc` and note it in the report as `FLOOR`.
Caveat to state with the number: this floor is **optimistic** (an upper bound on how well two different chains can agree), because only the kernel changes. R5's 0.90 margin is therefore demanding.

- [ ] **Step 2: [HEAVY] R5 — GSLC against the classical control, in the radar domain**

Run: `python validation/gslc_parity/run_r5.py r5 E:/Output/parity/ven/ven_etad_ifg.dim E:/Output/parity/ven/ven_trad_ifg_deb.dim <FLOOR from Step 1>`
Expected: `GATE r5-conc-over-floor …`, `GATE r5-gx-median-ratio …`, `GATE r5-gy-median-ratio …`, `GATE r5-coherence-parity-abs …`; exit 0 only if all pass. All rows recorded.
**A FAIL is a result, not a defect to tune away.** Do not change the multilook, the gates or the floor after seeing the number. Record it and go to Task 6, whose per-leg offsets say *which leg* carries the residual.

- [ ] **Step 3: Hand off** — report the raw metrics dict, the floor, the ratio, and which gates passed. Do not commit.

---

### Task 6: R4 per-leg registration (Day 2, Track A)

> **STATUS 2026-09-20: the code and self-tests are valid; the RUN is INVALID as designed** for the same reason as R5 (tie-point-grid geolocation ignores terrain). It measured range offsets of −11 / −20 / −27 px across the three bursts, identical for both legs. The leg-band picker was verified on the real stacks (`i_IW3_VV_ref_23Jun2026`, `i_IW3_VV_sec1_24Jun2026`). `median_offsets` had a NaN bug that is fixed.

**Files:**
- Create: `validation/gslc_parity/offset_map.py`
- Create: `validation/gslc_parity/run_r4.py`

**Interfaces:**
- Consumes: Task 3 (`ven_trad_stack_deb.dim`, `ven_trad_ifg_deb.dim` for its tie-point grids), Task 4 (`ven_etad_stack.dim`, `radar_domain.radar_latlon`, `radar_domain.hdr_dtype`), Task 2 (`budget.record`), `ven_checks.attr / etad_azimuth_applied`, existing `inverse_geocode._bilinear_complex / _read_geo / geo_to_index`, `gslc_equivalence._declared_bands / emit_gate`.
- Produces: `offset_map.tile_shift(a, b) -> (d_row, d_col)`, `offset_map.median_offsets(a, b, tile=256, stride=512, min_valid=0.9) -> dict`, `offset_map.bistatic_az_offset_px(...)`, `run_r4.pick_leg`, `run_r4.r4_leg_verdict`, `run_r4.run(gslc_stack, trad_stack_deb, radar_dim, gslc_ref_dim) -> int`.

- [ ] **Step 1: Create `offset_map.py`**

```python
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
```

- [ ] **Step 2: Self-test it**

Run: `python validation/gslc_parity/offset_map.py --selftest`
Expected: recovered shifts within 0.05 px of `(+1.00,-2.00)`, `(+0.30,+0.60)`, `(-0.45,+0.25)` (measured 0.002–0.014), `bistatic offset over 55 km: 0.089 lines`, `SELFTEST OK`.

- [ ] **Step 3: Create `run_r4.py`**

```python
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
```

- [ ] **Step 4: Self-test it**

Run: `python validation/gslc_parity/run_r4.py --selftest`
Expected: `SELFTEST OK`. This checks leg picking (dual-pol ambiguity raises), that a correctly signed modelled asymmetry cancels, that a flipped sign **fails**, and that 0.3 px fails the 0.1 px gate.

- [ ] **Step 5: List the stack bands and confirm the leg picker sees exactly one i/q pair per leg** — the band-name tags (`_ref`/`_mst`, `_sec<N>`/`_slv<N>`) were inferred from earlier products, not verified on this stack

Run: `python -c "import sys; sys.path.insert(0,'validation'); sys.path.insert(0,'validation/gslc_parity'); import gslc_equivalence as ge, run_r4 as r; [print(n, r.pick_leg(sorted(ge._declared_bands(d)), n)) for d in ('E:/Output/parity/ven/ven_etad_stack.dim','E:/Output/parity/ven/ven_trad_stack_deb.dim') for n in ('ref','sec')]"`
Expected: four `(i_…, q_…)` pairs. If `ValueError: cannot pick the … leg unambiguously`, print `sorted(ge._declared_bands(d))`, read the real names, and extend the two regexes in `pick_leg` — and add the real names to the self-test.

- [ ] **Step 6: [HEAVY] Run R4**

Run: `python validation/gslc_parity/run_r4.py E:/Output/parity/ven/ven_etad_stack.dim E:/Output/parity/ven/ven_trad_stack_deb.dim E:/Output/parity/ven/ven_trad_ifg_deb.dim <S1A _b4-6_orb_etad.dim>`
Expected: per leg one `R4 ref:` / `R4 sec:` line with the raw `d_row`, `d_col`, the modelled bistatic term and the residual, three per-burst-band lines, and `GATE r4-ref-offset-px` / `r4-sec-offset-px` at 0.1 px.
Note in the report: the products carry `etad_azimuth_applied=1`, so the GSLC **suppresses** its bistatic term and nothing is modelled here (`applied=False`); the modelled-asymmetry path is exercised by the self-test and by any ETAD-off run.

- [ ] **Step 7: Hand off** — files created: the two above. Report raw offsets per leg and per burst band; a per-burst-band spread larger than the per-leg median is a burst-structure finding worth naming. Do not commit.

---

### Task 7: R3 phase closure (Day 2 overnight, Track A)

> **STATUS 2026-09-21: run.** Three pairs of ~50 min each. The pre-registered gates FAILED (closure RMS 1.365 rad vs 0.3; worst band mean 0.154 vs 0.1) because the pairs are barely coherent (mean coherence 0.284 / 0.177 / 0.161, tropical, ETAD-off), not on evidence that GSLC is inconsistent: stratified by coherence the closure collapses (0.52 / 0.25 / 0.15 / 0.11 / 0.043 at minimum coherence >= 0.2 / 0.3 / 0.4 / 0.5 / 0.7) and matches a Cramer-Rao noise model at coherence >= 0.4. **Choose the closure gate from the pair coherences BEFORE running** (for example observed <= 1.5x the noise-model prediction), and run the stratified diagnostic (`closure_diag.py`, Task 10 Step 9) as part of this task, not after a failure.

**Files:**
- Create: `validation/drivers/ven_closure.ps1`

**Interfaces:**
- Consumes: Task 4 (`ven_gslc.ps1`), Task 2 (`closure.py run`), Task 1 fixtures (ETAD-**off**, all of S1A/S1C/S1D).
- Produces: `clos_AC_ifg.dim`, `clos_CD_ifg.dim`, `clos_AD_ifg.dim` and the `R3__venezuela__*.json` rows.

- [ ] **Step 1: Create `ven_closure.ps1`**

```powershell
<#
    R3 phase closure on Venezuela S1A / S1C / S1D, ETAD-OFF on all three legs (no S1D ETAD product
    exists anywhere, so this is a deliberately DIFFERENT configuration from the pairwise rungs and
    every output says so).

    Three independent pair runs, one heavy job at a time. Each is its own GSLC -> auto-stack -> ifg,
    so each pair has its own bias estimate, burst lock and coreg - which is the whole point: pairwise
    products formed from the SAME three SLCs by plain complex arithmetic would close identically to
    zero and test nothing.

        AC = A*conj(C)  (ref A, sec C)      CD = C*conj(D)  (ref C, sec D)      AD = A*conj(D)  (ref A, sec D)
        closure = AC * CD * conj(AD)
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\closure.log"
function Pick([string]$tag) { (Get-ChildItem $D -Filter "S1$tag`_IW_SLC*_b4-6_orb.dim" | Select-Object -First 1).FullName }
$A = Pick 'A'; $C = Pick 'C'; $Dd = Pick 'D'
if (-not $A -or -not $C -or -not $Dd) { Log 'ABORT: ETAD-off fixtures missing - run ven_fixture.ps1'; exit 1 }

foreach ($p in @(@{ T = 'clos_AC'; R = $A; S = $C }, @{ T = 'clos_CD'; R = $C; S = $Dd }, @{ T = 'clos_AD'; R = $A; S = $Dd })) {
    & "$PSScriptRoot\ven_gslc.ps1" -Ref $p.R -Sec $p.S -Tag $p.T
    if ($LASTEXITCODE -ne 0) { Log "ABORT pair $($p.T)"; exit 1 }
}
& python "$script:PY\closure.py" run "$D\clos_AC_ifg.dim" "$D\clos_CD_ifg.dim" "$D\clos_AD_ifg.dim"
exit $LASTEXITCODE
```

- [ ] **Step 2: Parse-check it** (Task 1 Step 6 command). Expected: `parse OK`.

- [ ] **Step 3: [HEAVY] Run it** — three sequential pair runs (~1.3 h each) then the closure verdict

Run: `pwsh -NoProfile -File validation/drivers/ven_closure.ps1`
Expected: three `GSLC pair clos_… complete` lines, then the closure line `# common window (rows, cols), multilook …`, a stats dict, and `GATE closure-rms-rad …`, `GATE closure-worst-band-mean-rad …`.
**Sign convention check (do this before believing a FAIL):** every interferogram must be `first * conj(second)`. A closure RMS near 1.8 rad rather than 0.1–0.3 means one pair has the opposite convention — the self-test's wrong-sign case shows that signature. Verify with the `Interferogram` operator's convention on one pair before touching anything else.
The result is labelled ETAD-off in every recorded row.

- [ ] **Step 4: Per-pair disk teardown** — after the verdict is recorded, delete each `clos_*_stack.*` and `clos_*_gslc.*` (large); keep the three `clos_*_ifg.*` until the technical note is done.

- [ ] **Step 5: Hand off** — report the closure RMS, the mean, the per-row-band means, and the timings. Do not commit.

---

### Task 8: Synthetic pairs — R1 and R2-lite (Day 1 code, Day 2 spike and runs)

> **STATUS 2026-09-21: done.** Spike GO (generator floor exactly 0.0 rad). R1 RMS 0.0004 rad (gate 0.05). R2-lite retention: classical 1.0000, GSLC ramp off 1.0000, GSLC ramp on 0.9764 (RMS 0.1135). About 1.5 h of compute after the spike.

**Files:**
- Create: `validation/gslc_parity/synth.py`
- Create: `validation/gslc_parity/synth_eval.py`
- Create: `validation/drivers/ven_synth.ps1`

**Interfaces:**
- Consumes: Task 2 (`budget.record`), Task 4 (`ven_gslc.ps1 -Diag`, `-Ramp`), Task 3 (the two classical graphs), `gslc_equivalence.load_complex_ifg`.
- Produces: `synth.write_second_date(src_dim, dst_dim, days, phase_fn=None)`, `synth.lobe_phase`, `synth.recovery_stats(rec, true, valid) -> dict` (`rms_rad`, `rms_centred_rad`, `mean_rad`, `retention`), constants `AMP=6.0`, `CENTRE=11800.0`, `SIGMA=1500.0`. `synth.py make <src.dim> <dst.dim> <days> <lobe|zero>`.
- Produces: `synth_eval.py score-gslc <ifg> <master_gslc> <rung> <name> <floor|none>` and `synth_eval.py score-classical <ifg> <rung> <name> <floor|none>`.

**Data-type trap found in execution:** real S1 split SLC bands are **int16** (ENVI data type 2, `<DATA_TYPE>int16</DATA_TYPE>`, no scaling). Rotating them by a phase and writing back as int16 would add rounding noise unrelated to the phase, so `write_second_date` writes float32 and retypes the `.hdr` and the `.dim` (the toy float test alone would never have caught this; the self-test now has an int16 case).

**What this measures, exactly:** `SLC_B = SLC_A · exp(−j·lobe(col))` with every date shifted +12 days. The geometry of B is bit-identical to A's, so the lobe is the *only* difference and the truth is exact. **R1** = GSLC, ramp OFF, `RMS(recovered − lobe) < 0.05 rad`. **Generator floor** = the same pair with `phi = 0`. **R2-lite** = retention (slope of recovered on true) for classical, GSLC ramp OFF, GSLC ramp ON. The lobe depends on range column only, so it is identical in every burst and continuous across burst overlaps.

- [ ] **Step 1: Create `synth.py`**

```python
"""Synthetic second-acquisition generator for the parity campaign (R1 / R2-lite).

SLC_B is a copy of a real split SLC whose complex samples are multiplied by exp(-j*phi(range col))
and whose DATES are shifted by a whole number of days. Only the calendar date moves: every orbit
state vector, burst time and line time keeps its time of day, so the orbit-to-time mapping inside
the product is unchanged and the geometry of B is bit-identical to A's. That makes phi the ONLY
difference between the two legs, and lets a chain be judged against a known truth.
"""
from __future__ import annotations

import datetime as dt
import re
import shutil
import sys
import tempfile
from pathlib import Path

import numpy as np

AMP, CENTRE, SIGMA = 6.0, 11800.0, 1500.0     # the R1/R2 lobe: 6 rad, ~4 km sigma on the ground

_MON = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
_ISO = re.compile(r"(\d{4})-(\d{2})-(\d{2})(T\d{2}:\d{2}:\d{2}(?:\.\d+)?)")
_SNAP = re.compile(r"(\d{2})-(" + "|".join(_MON) + r")-(\d{4})( \d{2}:\d{2}:\d{2}(?:\.\d+)?)")


def shift_times(text: str, days: int) -> str:
    """Shift every ISO 'YYYY-MM-DDThh:mm:ss[.f]' and SNAP 'DD-MON-YYYY hh:mm:ss[.f]' date by +days."""
    d = dt.timedelta(days=days)

    def iso(m):
        s = dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3))) + d
        return f"{s.year:04d}-{s.month:02d}-{s.day:02d}{m.group(4)}"

    def snap(m):
        s = dt.date(int(m.group(3)), _MON.index(m.group(2)) + 1, int(m.group(1))) + d
        return f"{s.day:02d}-{_MON[s.month - 1]}-{s.year:04d}{m.group(4)}"

    return _SNAP.sub(snap, _ISO.sub(iso, text))


def lobe_phase(cols: np.ndarray, amp_rad: float, centre_col: float, sigma_cols: float) -> np.ndarray:
    """Range-only Gaussian 'deformation' lobe. Depends on the range column alone, so it is the
    same on the ground in every burst and continuous across burst overlaps."""
    return amp_rad * np.exp(-0.5 * ((np.asarray(cols, float) - centre_col) / sigma_cols) ** 2)


_ENVI = {1: np.uint8, 2: np.int16, 3: np.int32, 4: np.float32, 5: np.float64, 12: np.uint16}


def _hdr_dtype(hdr: Path) -> np.dtype:
    t = hdr.read_text()
    dtc = int(re.search(r"^data type\s*=\s*(\d+)", t, re.M).group(1))
    bo = int(re.search(r"^byte order\s*=\s*(\d)", t, re.M).group(1))
    return np.dtype(_ENVI[dtc]).newbyteorder(">" if bo == 1 else "<")


def _retype_band(dim_text: str, band: str, new_type: str) -> str:
    """Change <DATA_TYPE> inside one band's Spectral_Band_Info block of a .dim."""
    pat = re.compile(r"(<Spectral_Band_Info>(?:(?!</Spectral_Band_Info>).)*?<BAND_NAME>" + re.escape(band)
                     + r"</BAND_NAME>.*?)<DATA_TYPE>[^<]*</DATA_TYPE>", re.S)
    out, n = pat.subn(lambda m: m.group(1) + f"<DATA_TYPE>{new_type}</DATA_TYPE>", dim_text, count=1)
    if n != 1:
        raise ValueError(f"band {band!r} not declared in the .dim")
    return out


def write_second_date(src_dim: Path, dst_dim: Path, days: int, phase_fn=None,
                      i_band: str = "i_IW3_VV", q_band: str = "q_IW3_VV", block_rows: int = 256) -> None:
    """Write dst_dim = src_dim with dates +days and i/q multiplied by exp(-j*phase_fn(col))."""
    src_dim, dst_dim = Path(src_dim), Path(dst_dim)
    src_data, dst_data = src_dim.with_suffix(".data"), dst_dim.with_suffix(".data")
    if dst_dim.exists() or dst_data.exists():
        raise FileExistsError(f"{dst_dim} already exists")
    shutil.copytree(src_data, dst_data)
    txt = src_dim.read_text(encoding="utf-8", errors="replace")
    txt = txt.replace(src_data.name, dst_data.name)
    txt = shift_times(txt, days)
    if phase_fn is None:
        dst_dim.write_text(txt, encoding="utf-8")
        return
    ih = dst_data / f"{i_band}.hdr"
    w = int(re.search(r"^samples\s*=\s*(\d+)", ih.read_text(), re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", ih.read_text(), re.M).group(1))
    dtp = _hdr_dtype(ih)
    rot = np.exp(-1j * phase_fn(np.arange(w)))
    # Real S1 SLC bands are int16. Rotating by a phase gives non-integer samples, and rounding them
    # back to int16 would add quantisation noise unrelated to the phase under test - so an integer
    # source is written out as big-endian float32 and its .hdr and .dim declarations are retyped.
    out_dt = np.dtype(">f4") if dtp.kind in "iu" else dtp
    # write i and q together (a rotation mixes them), block by block, to side files first
    si = np.memmap(dst_data / f"{i_band}.img", dtype=dtp, mode="r", shape=(h, w))
    sq = np.memmap(dst_data / f"{q_band}.img", dtype=dtp, mode="r", shape=(h, w))
    ti, tq = dst_data / f"{i_band}.img.new", dst_data / f"{q_band}.img.new"
    oi = np.memmap(ti, dtype=out_dt, mode="w+", shape=(h, w))
    oq = np.memmap(tq, dtype=out_dt, mode="w+", shape=(h, w))
    for r0 in range(0, h, block_rows):
        z = (np.asarray(si[r0:r0 + block_rows], np.float64) + 1j * np.asarray(sq[r0:r0 + block_rows], np.float64)) * rot
        oi[r0:r0 + block_rows] = z.real.astype(out_dt)
        oq[r0:r0 + block_rows] = z.imag.astype(out_dt)
    oi.flush()
    oq.flush()
    del oi, oq, si, sq
    (dst_data / f"{i_band}.img").unlink()
    (dst_data / f"{q_band}.img").unlink()
    ti.rename(dst_data / f"{i_band}.img")
    tq.rename(dst_data / f"{q_band}.img")
    if out_dt != dtp:
        new_code = {np.dtype(v): k for k, v in _ENVI.items()}[np.dtype(out_dt.type)]
        for band in (i_band, q_band):
            hp = dst_data / f"{band}.hdr"
            hp.write_text(re.sub(r"^(data type\s*=\s*)\d+", rf"\g<1>{new_code}", hp.read_text(), flags=re.M))
            txt = _retype_band(txt, band, "float32")
    dst_dim.write_text(txt, encoding="utf-8")


def recovery_stats(rec_phase: np.ndarray, true_phase: np.ndarray, valid: np.ndarray) -> dict:
    """RMS of wrapped(recovered - true) about zero and about the mean, plus retention =
    least-squares slope of recovered on true (1.0 = lobe fully retained, 0 = removed)."""
    if not valid.any():
        return {"n": 0, "rms_rad": float("nan"), "rms_centred_rad": float("nan"),
                "mean_rad": float("nan"), "retention": float("nan")}
    d = np.angle(np.exp(1j * (rec_phase[valid] - true_phase[valid])))
    mean = float(d.mean())
    t = true_phase[valid]
    rec_unw = t + d                        # residual is small, so this is the unwrapped recovery
    tc, rc = t - t.mean(), rec_unw - rec_unw.mean()
    slope = float((tc * rc).sum() / (tc * tc).sum()) if (tc * tc).sum() > 0 else float("nan")
    return {"n": int(valid.sum()), "rms_rad": float(np.sqrt(np.mean(d ** 2))),
            "rms_centred_rad": float(np.sqrt(np.mean((d - mean) ** 2))), "mean_rad": mean,
            "retention": slope}


def _selftest() -> int:
    ok = True
    t = ("<a>23-JUN-2026 22:50:50.409240</a><b>2026-06-23T22:50:59.847329</b>"
         "<c>2026-06-30T00:00:00</c><d>30-JUN-2026 23:59:59.5</d><e>x_2026 no date</e>")
    s = shift_times(t, 12)
    print(s)
    if not ("05-JUL-2026 22:50:50.409240" in s and "2026-07-05T22:50:59.847329" in s
            and "2026-07-12T00:00:00" in s and "12-JUL-2026 23:59:59.5" in s and "x_2026 no date" in s):
        print("  FAIL: date shift")
        ok = False
    if shift_times("31-DEC-2026 01:02:03.5", 1) != "01-JAN-2027 01:02:03.5":
        print("  FAIL: year roll")
        ok = False
    p = lobe_phase(np.array([0, 500, 1000]), 6.0, 500, 200)
    if abs(p[1] - 6.0) > 1e-12 or abs(p[0] - 6.0 * np.exp(-3.125)) > 1e-12 or abs(p[0] - p[2]) > 1e-12:
        print("  FAIL: lobe")
        ok = False

    tmp = Path(tempfile.mkdtemp())
    try:
        src = tmp / "S1_x.dim"
        (tmp / "S1_x.data").mkdir()
        src.write_text("<D>S1_x.data/i_IW3_VV.hdr 23-JUN-2026 22:50:50.4</D>")
        h, w = 6, 32
        rng = np.random.default_rng(1)
        z0 = rng.normal(size=(h, w)) + 1j * rng.normal(size=(h, w))
        for nm, v in (("i_IW3_VV", z0.real), ("q_IW3_VV", z0.imag)):
            v.astype(">f4").tofile(tmp / "S1_x.data" / f"{nm}.img")
            (tmp / "S1_x.data" / f"{nm}.hdr").write_text(
                f"samples = {w}\nlines = {h}\nbands = 1\ndata type = 4\nbyte order = 1\n")
        dst = tmp / "S1_y.dim"
        ph = lambda c: 0.02 * np.asarray(c, float)
        write_second_date(src, dst, 12, ph)
        out = dst.read_text()
        if "S1_y.data/i_IW3_VV.hdr" not in out or "05-JUL-2026" not in out or "S1_x" in out:
            print("  FAIL: dim rewrite:", out)
            ok = False
        zi = np.fromfile(tmp / "S1_y.data" / "i_IW3_VV.img", ">f4").reshape(h, w)
        zq = np.fromfile(tmp / "S1_y.data" / "q_IW3_VV.img", ">f4").reshape(h, w)
        z1 = zi + 1j * zq
        rec = np.angle(z0.astype(np.complex64) * np.conj(z1.astype(np.complex64)))
        tru = np.broadcast_to(ph(np.arange(w)), (h, w))
        st = recovery_stats(rec, tru, np.ones((h, w), bool))
        print(f"toy recovery: rms {st['rms_rad']:.2e} retention {st['retention']:.4f}")
        if not (st["rms_rad"] < 1e-5 and abs(st["retention"] - 1) < 1e-3):
            print("  FAIL: injected phase must be recovered as master*conj(B)")
            ok = False
        if (tmp / "S1_x.data" / "i_IW3_VV.img").read_bytes() != z0.real.astype(">f4").tobytes():
            print("  FAIL: source was modified")
            ok = False
        try:
            write_second_date(src, dst, 12)
            print("  FAIL: must refuse to overwrite")
            ok = False
        except FileExistsError:
            pass

        # int16 source (what a real S1 split SLC stores): the output must be float32, retyped in both
        # the .hdr and the .dim, and the phase must survive WITHOUT int16 rounding noise
        zi16 = np.round(300 * (rng.normal(size=(h, w)) + 1j * rng.normal(size=(h, w))))
        d16 = tmp / "S1_i.dim"
        (tmp / "S1_i.data").mkdir()
        d16.write_text(
            "<Dimap><Spectral_Band_Info><BAND_INDEX>0</BAND_INDEX><BAND_NAME>i_IW3_VV</BAND_NAME>"
            "<DATA_TYPE>int16</DATA_TYPE></Spectral_Band_Info>"
            "<Spectral_Band_Info><BAND_INDEX>1</BAND_INDEX><BAND_NAME>q_IW3_VV</BAND_NAME>"
            "<DATA_TYPE>int16</DATA_TYPE></Spectral_Band_Info>S1_i.data/ 23-JUN-2026 22:50:50.4</Dimap>")
        for nm, v in (("i_IW3_VV", zi16.real), ("q_IW3_VV", zi16.imag)):
            v.astype(">i2").tofile(tmp / "S1_i.data" / f"{nm}.img")
            (tmp / "S1_i.data" / f"{nm}.hdr").write_text(
                f"samples = {w}\nlines = {h}\nbands = 1\ndata type = 2\nbyte order = 1\n")
        o16 = tmp / "S1_o.dim"
        write_second_date(d16, o16, 12, ph)
        hdr_o = (tmp / "S1_o.data" / "i_IW3_VV.hdr").read_text()
        dim_o = o16.read_text()
        if "data type = 4" not in hdr_o or dim_o.count("<DATA_TYPE>float32</DATA_TYPE>") != 2 or "int16" in dim_o:
            print("  FAIL: int16 source must be retyped to float32 in .hdr and .dim:", hdr_o, dim_o)
            ok = False
        oi = np.fromfile(tmp / "S1_o.data" / "i_IW3_VV.img", ">f4").reshape(h, w)
        oq = np.fromfile(tmp / "S1_o.data" / "q_IW3_VV.img", ">f4").reshape(h, w)
        rec16 = np.angle(zi16 * np.conj(oi + 1j * oq))
        st16 = recovery_stats(rec16, tru, np.ones((h, w), bool))
        print(f"int16 source: rms {st16['rms_rad']:.2e}")
        if not st16["rms_rad"] < 1e-5:
            print("  FAIL: an int16 source must not pick up rounding noise")
            ok = False
        if (tmp / "S1_i.data" / "i_IW3_VV.img").read_bytes() != zi16.real.astype(">i2").tobytes():
            print("  FAIL: int16 source was modified")
            ok = False
        if list((tmp / "S1_o.data").glob("*.new")) or list((tmp / "S1_o.data").glob("*.tmp")):
            print("  FAIL: side files left behind")
            ok = False
        st = recovery_stats(0.6 * tru, tru, np.ones((h, w), bool))
        if abs(st["retention"] - 0.6) > 1e-6:
            print("  FAIL: retention measure")
            ok = False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(a) == 5 and a[0] == "make":            # make <src.dim> <dst.dim> <days> <lobe|zero>
        fn = (lambda c: lobe_phase(c, AMP, CENTRE, SIGMA)) if a[4] == "lobe" else None
        write_second_date(Path(a[1]), Path(a[2]), int(a[3]), fn)
        print(f"wrote {a[2]} (+{a[3]} days, phase={a[4]})")
        raise SystemExit(0)
    print("usage: synth.py make <src.dim> <dst.dim> <days> <lobe|zero>   |  --selftest")
    raise SystemExit(2)
```

- [ ] **Step 2: Self-test it**

Run: `python validation/gslc_parity/synth.py --selftest`
Expected: the shifted-date line (`05-JUL-2026 …`, `2026-07-05T…`, `2026-07-12T00:00:00`, `12-JUL-2026 23:59:59.5`), `toy recovery: rms 2e-08 retention 1.0000`, `SELFTEST OK`.

- [ ] **Step 3: Create `synth_eval.py`**

```python
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
```

- [ ] **Step 4: Self-test it**

Run: `python validation/gslc_parity/synth_eval.py --selftest`
Expected: `SELFTEST OK` (exact lobe recovered, half a lobe reads retention 0.5, no-data and non-finite range indices dropped, pooled mean 0.05).

- [ ] **Step 5: Create `ven_synth.ps1`**

```powershell
<#
    Synthetic pairs for R1 / R2-lite. SLC_B = SLC_A (S1A bursts 4-6, orbit-corrected, ETAD-off) with
    every date shifted +12 days and the samples multiplied by exp(-j*phi(range column)):
      syn0 : phi = 0     -> the generator floor (a zero-perturbation pair must give zero phase)
      syn1 : phi = lobe  -> R1 (GSLC, ramp off) and R2-lite (retention: GSLC ramp on/off vs classical)

    Because only the DATE moves, the geometry of B is bit-identical to A's and the lobe is the only
    difference between the legs. Requires the go/no-go spike in Task 8 to have passed.
#>
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\synth.log"
$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb.dim' | Select-Object -First 1).FullName
if (-not $A) { Log 'ABORT: ETAD-off S1A fixture missing'; exit 1 }

$B0 = "$D\SYN_B0_b4-6_orb.dim"
$B1 = "$D\SYN_B1_b4-6_orb.dim"
foreach ($b in @(@{ P = $B0; K = 'zero' }, @{ P = $B1; K = 'lobe' })) {
    $ok = Step "make-$($b.K)" $b.P { & python "$script:PY\synth.py" make $A $b.P 12 $b.K | Out-Host; $LASTEXITCODE }
    if (-not $ok) { Log "ABORT make-$($b.K)"; exit 1 }
}

# GSLC pairs. -Diag adds diag_rangeIndex to the master GSLC so the lobe is evaluated exactly.
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $B0 -Tag syn0 -Diag
if ($LASTEXITCODE -ne 0) { Log 'ABORT syn0'; exit 1 }
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $B1 -Tag syn1 -Diag
if ($LASTEXITCODE -ne 0) { Log 'ABORT syn1'; exit 1 }
# Same GSLC + stack are reused (Step skips them); only the ifg is rebuilt with the ramp option ON.
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $B1 -Tag syn1 -Diag -Ramp
if ($LASTEXITCODE -ne 0) { Log 'ABORT syn1 ramp'; exit 1 }

# Classical on the same synthetic pair (BISINC control settings, ETAD-off inputs).
$stack = "$D\syn1_trad_stack.dim"; $ifg = "$D\syn1_trad_ifg_deb.dim"
$ok = Step 'syn1-trad-coreg' $stack { Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_coreg_ven.xml',
        "-Pinput1=$A", "-Pinput2=$B1", "-Pdem=$script:DEM", '-Presampling=BISINC_5_POINT_INTERPOLATION',
        "-Poutput=$stack") "$D\syn1_trad_coreg.log" }
if (-not $ok) { Log 'ABORT syn1 classical coreg'; exit 1 }
$ok = Step 'syn1-trad-ifg' $ifg { Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifgdeb_ven.xml',
        "-Pinput1=$stack", "-Pdem=$script:DEM", "-Poutput=$ifg") "$D\syn1_trad_ifg.log" }
if (-not $ok) { Log 'ABORT syn1 classical ifg'; exit 1 }
Log 'synthetic products complete'
```

- [ ] **Step 6: Parse-check it** (Task 1 Step 6 command). Expected: `parse OK`.

- [ ] **Step 7: [HEAVY] GO / NO-GO SPIKE — will SNAP accept a same-geometry, date-shifted copy as a second acquisition?** This is the one assumption the synthetic rungs rest on and it has not been tested.

Run only the generator and the first pair:
`python validation/gslc_parity/synth.py make <S1A _b4-6_orb.dim> E:/Output/parity/ven/SYN_B0_b4-6_orb.dim 12 zero`
then `pwsh -NoProfile -File validation/drivers/ven_gslc.ps1 -Ref <S1A _b4-6_orb.dim> -Sec E:\Output\parity\ven\SYN_B0_b4-6_orb.dim -Tag syn0 -Diag`
**GO criteria (all must hold):** the stack builds; its log contains `locked to the reference`; `python validation/gslc_parity/synth_eval.py gslc E:/Output/parity/ven/syn0_ifg.dim E:/Output/parity/ven/syn0_gslc.dim syn0 zero` prints `rms` **< 0.05 rad** and `n` > 1,000,000. **The trailing `zero` is essential:** without it the scorer compares against the LOBE and reports ~0.92 rad for a perfect pair (it did, on 2026-09-20, and that was read as a NO-GO until the ifg itself was inspected: median |phase| 0.000, coherence exactly 1.000 in every row and range band). **Measured 2026-09-20: `n=136998286, rms=0.0000`, i.e. a generator floor of exactly 0.0, GO.**
**NO-GO (any of: `CreateStack` rejects or mis-names the second date, the geolocation of B differs from A's, or `rms` ≥ 0.05):** stop, record the exact symptom in the report, and **drop R1 and R2-lite from this plan** (they become "not run" in the scorecard, with the symptom as the reason). Do **not** patch further times or metadata to make it work — a patched-until-it-works fixture is exactly the failure mode the campaign exists to avoid. The `syn0` `rms` is the **generator floor** carried into Step 9.

- [ ] **Step 8: [HEAVY] Run the rest** (only after GO)

Run: `pwsh -NoProfile -File validation/drivers/ven_synth.ps1`
Expected: `synth.log` ends `synthetic products complete`. Products: `syn0_*`, `syn1_*` (`syn1_ifg.dim` ramp OFF, `syn1_ifg_ramp.dim` ramp ON), `syn1_trad_stack.dim`, `syn1_trad_ifg_deb.dim`.

- [ ] **Step 9: Score** — the generator floor is the `syn0` `rms`; call it `F`. **F was measured as exactly 0.0, which makes the spec's "within 2x the floor" gate unsatisfiable; `synth_eval.gate_threshold` therefore gates R2 at `max(2*F, 0.05 rad)`** (a rule fixed before any lobe result existed, labelled PROVISIONAL).

Run:
`python validation/gslc_parity/synth_eval.py score-gslc E:/Output/parity/ven/syn1_ifg.dim E:/Output/parity/ven/syn1_gslc.dim R1 gslc-ramp-off none`
`python validation/gslc_parity/synth_eval.py score-gslc E:/Output/parity/ven/syn1_ifg.dim E:/Output/parity/ven/syn1_gslc.dim R2 gslc-ramp-off <F>`
`python validation/gslc_parity/synth_eval.py score-gslc E:/Output/parity/ven/syn1_ifg_ramp.dim E:/Output/parity/ven/syn1_gslc.dim R2 gslc-ramp-on <F>`
`python validation/gslc_parity/synth_eval.py score-classical E:/Output/parity/ven/syn1_trad_ifg_deb.dim R2 classical <F>`
Expected: each prints `n=… rms=… retention=…` and records an rms row (gate: R1 < 0.05; R2 < 2×F) plus a `retention` row (measured, no gate).
Report the four retention numbers side by side. **GSLC ramp ON versus OFF is the point of R2-lite:** a retention well below 1 with the ramp on is the first measured cost of that option.

- [ ] **Step 10: Hand off** — files created: the three above. State plainly in the report that R1 is the null-baseline form and R2 is the range-only-lobe form (see "Scope"). Do not commit.

---

### Task 9: Burst-seam gate, scorecard, error budget, note (Day 3)

> **STATUS 2026-09-21: done, with caveats.** The seam meter's locator reported 7-10 rows per strip on a 2-seam product; checked against the GSLC's `diag_burst` band it finds the true seams (about 9 rows early) plus about 5 spurious rows per strip, and its two-sided complex-mean step is contaminated by any azimuth ramp (so run it on the ramp-ON interferogram and treat the result as an upper bound). At the 11 confirmed seam rows the steps are 0.4-3.1 rad; the cause was not isolated. The scorecard, the error budget (residual 0.508 rad, explained 0.243, unexplained 0.446) and the note (`docs/gslc-parity/venezuela-tops-results.md`) exist.

**Files:**
- Create: `docs/gslc-parity/venezuela-tops-results.md` (the technical note)

**Interfaces:**
- Consumes: Task 3's `seam_steps_guided.py` (Plan 1 Task 3: `python validation/compare/seam_steps_guided.py <stack.dim> <ifg.dim>… [--threshold T]`, prints `GATE seam-worst-step PASS|FAIL <value> <threshold>`, exit 1 on any FAIL), `budget.load / scorecard_md / budget_row`, and every `results/*.json` row from Tasks 3–8.

- [ ] **Step 1: [HEAVY] Seam gate on the ETAD pair** (the stack **must** carry the `azimuthCarrierPhase` reference band, which `outputPhaseTerms=true` provides; the script asserts it)

Run: `python validation/compare/seam_steps_guided.py E:/Output/parity/ven/ven_etad_stack.dim E:/Output/parity/ven/ven_etad_ifg.dim`
Expected: `GATE seam-worst-step PASS|FAIL …`. The 3-burst fixture contains two seams. The baseline to beat is the ~3.14 rad worst step measured on the presented full-scene product before the burst lock and the carrier-difference bands. If the script raises `AssertionError: no reference azimuthCarrierPhase band in the stack`, the GSLC was built without the phase terms — that is a driver bug, not a seam result.
Record it: `python -c "import sys; sys.path.insert(0,'validation/gslc_parity'); from budget import record; record('SEAM','venezuela','seam-worst-step',<value>,0.3,'PROVISIONAL: chosen before measurement, no prior data',<True|False>)"`

- [ ] **Step 2: Generate the scorecard**

Run: `python validation/gslc_parity/budget.py --scorecard > E:/Output/parity/scorecard.md`
Expected: one row per recorded gate. Read it end to end; a gate with no row is a gate that did not run, and must be listed as "not run" in the note with its reason.

- [ ] **Step 3: Build the error-budget row** from the **measured** residuals — do not invent terms. From the R5 `residual-rms-rad` and the terms this campaign actually measured: P7 (`roundtrip-rms-rad`), P8 (`classical-floor-rms-rad`), and R4's residual offsets converted to phase where the local fringe rate is known. Anything unmeasured is left out and shows up as **unexplained**.

Run: `python -c "import sys; sys.path.insert(0,'validation/gslc_parity'); from budget import budget_row; print(budget_row(<R5 rms>, {'inverse_geocode_floor': <P7 rms>, 'classical_reproducibility': <P8 rms>}))"`
Expected: a dict with `unexplained_rad` and `coverage`. `over_explained` must be empty — a term larger than the residual it is meant to explain means a term is wrong (or the floors are not additive in quadrature); report it, do not clip it.

- [ ] **Step 4: Write the technical note** at `docs/gslc-parity/venezuela-tops-results.md` with these sections, in this order, each filled from measured rows only:
1. **Claim and scope** — S1 IW TOPS only; the dropped items from this plan's Scope table stated as *not run*.
2. **Scorecard** — the generated table, unedited.
3. **Error budget** — the row from Step 3, with the unexplained remainder in bold.
4. **What failed** — every FAIL, verbatim value, and what it means. A FAIL is reported, never softened.
5. **Threshold provenance** — the provisional-thresholds table from this plan, marked with which ones the measurements later showed to be badly chosen (recorded, not silently moved).
6. **Reproduction** — the exact commands from Tasks 1–9.
Figures, if any, are full-resolution crops.

- [ ] **Step 5: Teardown** — delete the large stage products (`*_stack.*`, `*_gslc.*`, `syn*_trad_stack.*`, `ven_trad*_stack*`) once their rows are recorded; keep `results/*.json`, the scorecard, the note, and the `_ifg` products the note's figures use. Report free disk before and after.

- [ ] **Step 6: Hand off** — report the full scorecard and state, in one sentence each, which of the spec's three claim tiers (self-consistent / geophysically equivalent / numerically equivalent) this run supports on Venezuela and which it does not. Do not commit.

---

### Task 10: R5b — the terrain-aware replacement for R4/R5 (added during execution)

**Why:** R4 and R5 as designed (Tasks 5-6) inverse-geocode through the classical product's tie-point-grid lat/lon, which does not follow terrain, and produced an invalid comparison (R5 concentration 0.043; link amplitude correlation 0.30). This task replaces the link with one that needs no geolocation at all.

**Files:**
- Create: `validation/gslc_parity/run_r5b.py`, `validation/gslc_parity/closure_diag.py`
- Create: `validation/graphs/trad_s1_ifg_ven_burst.xml`, `validation/drivers/ven_r5b.ps1`

**Interfaces:**
- Consumes: Task 3 (`ven_trad_stack.dim`), Task 4 (`ven_gslc.ps1 -Diag [-Ramp]`), `radar_domain.hdr_dtype`, `budget.record`, `gslc_equivalence.compute_gates / load_complex_ifg`.
- Produces: `run_r5b.bin_to_cells`, `radar_block_mean`, `paired_cells`, `link_correlation`, `verdict`, `cells_from_products`, `run(gslc_ifg, gslc_diag, classical_ifg, floor_conc, rung="R5b")`. CLI: `run_r5b.py r5b <gslc_ifg> <gslc_master_diag> <classical_burst_ifg> <floor_conc> [rung_label]`. Data: `ven_trad_ifg_burst.dim`, `ven_etadD_{gslc,stack,ifg,ifg_ramp}.dim`.

**Method:** a GSLC built with `-Dgslc.diagGeometry=true` writes, for every map pixel, the source range and azimuth index it was read from. Binning the map pixels of the GSLC interferogram into 8-line x 30-column radar cells by those indices puts it on the classical **burst-geometry** grid (the coregistered stack and its un-debursted interferogram share the master SLC's indexing) with no resampling. **Validity check built in:** the amplitude correlation of the two products over shared cells must be at least 0.8 (PROVISIONAL), or every gate is recorded INVALID rather than FAIL. Measured 2026-09-21: 0.923 (ramp off), 0.945 (ramp on).

- [ ] **Step 1: Create `run_r5b.py`**

```python
"""R5b - GSLC vs classical in the RADAR domain with a TERRAIN-AWARE link (replaces the tie-point-grid
inverse geocoding of run_r5.py, which ignored terrain and produced an invalid comparison).

Every map pixel of a GSLC built with -Dgslc.diagGeometry=true carries the SOURCE range and azimuth
index it was read from (diag_rangeIndex, diag_azimuthIndex - float64, in the reference SLC's burst-
stacked raster). Binning the map pixels of the GSLC interferogram into radar multilook cells by those
indices puts the GSLC product on the classical BURST-GEOMETRY grid (the coregistered stack and its
un-debursted interferogram share the master SLC's row/column indexing) with no resampling and no
geolocation at all. Both sides are then multilooked over the same (ml_az x ml_rg) radar cells.

Burst overlaps: the classical interferogram holds an overlap twice (once per burst); the GSLC picked
one burst per map pixel, so a cell is compared only where the GSLC has data - and then against the
very same source rows.

VALIDITY CHECK BUILT IN: the binned GSLC amplitude and the classical amplitude are correlated over the
shared cells. Below LINK_MIN_CORR the link is not trusted and every recorded row is marked INVALID.

  r5b <gslc_ifg.dim> <gslc_master_diag.dim> <classical_burst_ifg.dim> <floor_conc> [rung_label]
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
import gslc_equivalence as ge                       # noqa: E402
from budget import record                           # noqa: E402
from radar_domain import hdr_dtype                  # noqa: E402

ML_AZ, ML_RG = 8, 30          # 8 divides the 1504-line burst; 30 range px ~ 100 m on the ground
LINK_MIN_CORR = 0.8           # PROVISIONAL: chosen before measurement
MIN_FILL = 0.6                # a cell needs >= this fraction of the median map-pixel count
SRC = "venezuela"
PROV_PROV = "PROVISIONAL: chosen before measurement, no prior data"


def bin_to_cells(z: np.ndarray, az: np.ndarray, rg: np.ndarray, n_rows: int, n_cols: int,
                 ml_az: int, ml_rg: int):
    """Sum complex map pixels into radar cells by their source (az, rg) index.
    Returns (sum[n_rows//ml_az, n_cols//ml_rg], count[...]). Pixels with a non-finite or negative
    index, an index outside the raster, or z == 0 (no data) are ignored."""
    ny, nx = n_rows // ml_az, n_cols // ml_rg
    ok = np.isfinite(az) & np.isfinite(rg) & (az >= 0) & (rg >= 0) & (az < ny * ml_az) & (rg < nx * ml_rg) & (z != 0)
    if not ok.any():
        return np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64)
    cell = (np.floor(az[ok] / ml_az).astype(np.int64) * nx + np.floor(rg[ok] / ml_rg).astype(np.int64))
    zz = z[ok]
    re = np.bincount(cell, weights=zz.real, minlength=ny * nx)
    im = np.bincount(cell, weights=zz.imag, minlength=ny * nx)
    cnt = np.bincount(cell, minlength=ny * nx)
    return (re + 1j * im).reshape(ny, nx), cnt.reshape(ny, nx)


def radar_block_mean(z: np.ndarray, ml_az: int, ml_rg: int) -> np.ndarray:
    """Mean over ml_az x ml_rg blocks of the radar-geometry field; a block containing a zero
    (no-data) sample or a NaN is set to 0."""
    h, w = (z.shape[0] // ml_az) * ml_az, (z.shape[1] // ml_rg) * ml_rg
    zz = z[:h, :w].reshape(h // ml_az, ml_az, w // ml_rg, ml_rg)
    bad = (~np.isfinite(zz.real) | ~np.isfinite(zz.imag) | (zz == 0)).any(axis=(1, 3))
    return np.where(bad, 0, np.nan_to_num(zz, nan=0.0).mean(axis=(1, 3)))


def paired_cells(G_sum, G_cnt, T_mean, min_fill: float = MIN_FILL):
    """Cells valid on both sides -> (G_mean, T_mean) with zeros elsewhere, plus the fill mask."""
    med = np.median(G_cnt[G_cnt > 0]) if (G_cnt > 0).any() else 0
    fill = (G_cnt >= min_fill * med) & (np.abs(T_mean) > 0)
    G_mean = np.where(fill, G_sum / np.maximum(G_cnt, 1), 0)
    return G_mean, np.where(fill, T_mean, 0), fill


def link_correlation(G_mean: np.ndarray, T_mean: np.ndarray, fill: np.ndarray, min_cells: int = 100) -> float:
    """Correlation of log-amplitude over the shared cells (phase independent)."""
    if fill.sum() < min_cells:
        return float("nan")
    a, b = np.log(np.abs(G_mean[fill])), np.log(np.abs(T_mean[fill]))
    return float(np.corrcoef(a, b)[0, 1])


def gate_metrics(G, T) -> dict:
    m: dict = {}
    with contextlib.redirect_stdout(io.StringIO()):
        ge.compute_gates(G, T, None, None, metrics_out=m)
    return m


def verdict(m: dict, floor_conc: float, corr: float) -> list[tuple]:
    """(gate, value, threshold, provenance, passed). If the link check fails, passed is None
    (INVALID) for every gate - a comparison through an untrusted link is not a result."""
    valid = bool(np.isfinite(corr) and corr >= LINK_MIN_CORR)
    conc = m.get("phase-residual-conc", float("nan"))
    ratio = conc / floor_conc if floor_conc and floor_conc > 0 else float("nan")

    def p(x):
        return bool(x) if valid else None
    rows = [("link-amplitude-corr", corr, LINK_MIN_CORR, PROV_PROV, bool(valid)),
            ("conc-over-floor", ratio, 0.90, PROV_PROV, p(ratio >= 0.90))]
    for g in ("gx-median-ratio", "gy-median-ratio"):
        v = m.get(g, float("nan"))
        rows.append((g, v, 2.0, "spec section 7 (R5)", p(v <= 2.0)))
    return rows


def _band(dim: str, name: str) -> np.ndarray:
    import re
    data = Path(dim).with_suffix(".data")
    hdr = data / f"{name}.hdr"
    t = hdr.read_text()
    w = int(re.search(r"^samples\s*=\s*(\d+)", t, re.M).group(1))
    h = int(re.search(r"^lines\s*=\s*(\d+)", t, re.M).group(1))
    return np.memmap(data / f"{name}.img", dtype=hdr_dtype(hdr), mode="r", shape=(h, w))


def cells_from_products(gslc_ifg: str, gslc_diag: str, classical_ifg: str):
    """(G_mean, T_mean, fill, G_count, link_corr) on the classical burst-geometry radar cells."""
    ti, tq, tw, th = ge.load_complex_ifg(classical_ifg)
    T = radar_block_mean(np.asarray(ti[:], np.float64) + 1j * np.asarray(tq[:], np.float64), ML_AZ, ML_RG)
    gi, gq, gw, gh = ge.load_complex_ifg(gslc_ifg)
    rgi, azi = _band(gslc_diag, "diag_rangeIndex"), _band(gslc_diag, "diag_azimuthIndex")
    if rgi.shape != (gh, gw):
        raise ValueError(f"diag bands {rgi.shape} do not match the interferogram {(gh, gw)} - the master GSLC "
                         f"and the interferogram must share one lattice")
    sub_rg, sub_az = rgi[::50, ::50], azi[::50, ::50]
    print(f"# classical burst-geometry ifg {tw}x{th}; GSLC ifg {gw}x{gh}; source-index ranges: "
          f"rg {float(np.nanmin(sub_rg[sub_rg > 0])):.0f}..{float(np.nanmax(sub_rg)):.0f}, "
          f"az {float(np.nanmin(sub_az[sub_az > 0])):.0f}..{float(np.nanmax(sub_az)):.0f}")
    ny, nx = th // ML_AZ, tw // ML_RG
    Gs, Gc = np.zeros((ny, nx), complex), np.zeros((ny, nx), np.int64)
    for r0 in range(0, gh, 512):
        z = np.asarray(gi[r0:r0 + 512], np.float64) + 1j * np.asarray(gq[r0:r0 + 512], np.float64)
        s_, c_ = bin_to_cells(z.ravel(), np.asarray(azi[r0:r0 + 512], np.float64).ravel(),
                              np.asarray(rgi[r0:r0 + 512], np.float64).ravel(), th, tw, ML_AZ, ML_RG)
        Gs += s_
        Gc += c_
    Gm, Tm, fill = paired_cells(Gs, Gc, T)
    print(f"# cells {ny}x{nx}; shared {int(fill.sum())} ({fill.mean():.1%}); median map pixels per cell {np.median(Gc[Gc > 0]):.0f}")
    return Gm, Tm, fill, Gc, link_correlation(Gm, Tm, fill)


def run(gslc_ifg: str, gslc_diag: str, classical_ifg: str, floor_conc: float, rung: str = "R5b") -> int:
    Gm, Tm, fill, _Gc, corr = cells_from_products(gslc_ifg, gslc_diag, classical_ifg)
    m = gate_metrics(Gm, Tm)
    print("R5b raw metrics:", m, "| link amplitude corr", round(corr, 3))
    ok = True
    for gate, v, thr, prov, passed in verdict(m, floor_conc, corr):
        tag = "INVALID" if passed is None else ("PASS" if passed else "FAIL")
        print(f"GATE {rung.lower()}-{gate} {tag} {v:.6g} {thr:g}")
        record(rung, SRC, gate, v, thr, prov, passed,
               "terrain-aware bin link (diag_rangeIndex/diag_azimuthIndex); replaces the invalid tie-point-grid R5" +
               ("" if passed is not None else "; LINK CHECK FAILED - not a result"))
        ok &= bool(passed)
    record(rung, SRC, "phase-residual-conc", m["phase-residual-conc"], None, "measured", None, f"link corr {corr:.3f}")
    record(rung, SRC, "residual-rms-rad", m["residual-rms-rad"], None, "measured", None, f"link corr {corr:.3f}")
    return 0 if ok else 1


def _selftest() -> int:
    ok = True
    rng = np.random.default_rng(6)
    H, W = 64, 120                                    # radar raster: 8x30 cells -> 8 x 4
    Z = np.exp(1j * np.add.outer(np.linspace(0, 3, H), np.linspace(0, 9, W))) * (1 + 0.5 * rng.normal(size=(H, W)))
    # a map that samples every radar pixel exactly 4 times (2x oversampling both ways)
    mi, mj = np.mgrid[0:2 * H, 0:2 * W]
    az, rg = (mi // 2).astype(float), (mj // 2).astype(float)
    Zmap = Z[mi // 2, mj // 2]
    s, c = bin_to_cells(Zmap.ravel(), az.ravel(), rg.ravel(), H, W, 8, 30)
    Tm = radar_block_mean(Z, 8, 30)
    Gm, Tm2, fill = paired_cells(s, c, Tm)
    err = float(np.max(np.abs(Gm[fill] - Tm2[fill])))
    print(f"bin vs block mean: max err {err:.2e}, cells {fill.sum()}/{fill.size}, count per cell {int(c[0, 0])}")
    if not (err < 1e-12 and fill.all() and c[0, 0] == 8 * 30 * 4):
        print("  FAIL: binning must reproduce the radar block mean exactly")
        ok = False
    # no-data and invalid indices are ignored
    Zb = Zmap.copy()
    Zb[:2, :] = 0
    azb = az.copy()
    azb[10, :] = np.nan
    s2, c2 = bin_to_cells(Zb.ravel(), azb.ravel(), rg.ravel(), H, W, 8, 30)
    if c2.sum() != c.sum() - 2 * (2 * W) - (2 * W):
        print(f"  FAIL: no-data / NaN index handling ({c2.sum()} vs {c.sum() - 6 * W})")
        ok = False
    # the link check: identical amplitude structure correlates ~1; a shifted one does not
    corr_ok = link_correlation(Gm, Tm2, fill, min_cells=10)
    Gshift = np.roll(Gm, 3, axis=1)
    corr_bad = link_correlation(Gshift, Tm2, fill, min_cells=10)
    print(f"link correlation: aligned {corr_ok:.3f}, misregistered {corr_bad:.3f}")
    if not (corr_ok > 0.99 and corr_bad < 0.5):
        print("  FAIL: link check must separate aligned from misregistered")
        ok = False
    # verdicts: a failing link makes every gate INVALID (None), never a FAIL or a PASS
    good = {"phase-residual-conc": 0.99, "gx-median-ratio": 1.1, "gy-median-ratio": 1.2}
    v = {r[0]: r[4] for r in verdict(good, 0.999, 0.95)}
    if v != {"link-amplitude-corr": True, "conc-over-floor": True, "gx-median-ratio": True, "gy-median-ratio": True}:
        print("  FAIL: verdict (valid link)", v)
        ok = False
    v = {r[0]: r[4] for r in verdict(good, 0.999, 0.3)}
    if v["link-amplitude-corr"] is not False or any(v[k] is not None for k in ("conc-over-floor", "gx-median-ratio", "gy-median-ratio")):
        print("  FAIL: an untrusted link must make the gates INVALID", v)
        ok = False
    m = gate_metrics(Gm, Tm2)
    if not m["phase-residual-conc"] > 0.95:
        print("  FAIL: identical fields must concentrate", m)
        ok = False
    print("SELFTEST", "OK" if ok else "FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--selftest"]:
        raise SystemExit(_selftest())
    if len(a) in (5, 6) and a[0] == "r5b":          # optional 6th arg: the rung label to record under
        raise SystemExit(run(a[1], a[2], a[3], float(a[4]), *(a[5:] or [])))
    print(__doc__)
    raise SystemExit(2)
```

- [ ] **Step 2: Self-test it**

Run: `python validation/gslc_parity/run_r5b.py --selftest`
Expected: `bin vs block mean: max err 3e-15 ... count per cell 960`, `link correlation: aligned 1.000, misregistered 0.009`, `SELFTEST OK`.

- [ ] **Step 3: Create the burst-geometry interferogram graph**

```xml
<graph id="trad_s1_ifg_ven_burst">
  <version>1.0</version>
  <!--
    The R5 control interferogram in BURST GEOMETRY: identical to trad_s1_ifgdeb_ven.xml but WITHOUT
    TOPSAR-Deburst. The coregistered stack and this interferogram keep the master SLC's burst-stacked
    row/column indexing, which is exactly the indexing of the GSLC's diag_azimuthIndex / diag_rangeIndex
    bands, so GSLC map pixels can be binned onto it with no resampling and no geolocation (run_r5b.py).
    Unfiltered; flat-earth + topographic phase removed with the staged DEM; ground-corrected 100 m
    coherence window; all residual-ramp options off.
  -->
  <node id="Read1">
    <operator>Read</operator>
    <parameters><file>${input1}</file></parameters>
  </node>
  <node id="Interferogram">
    <operator>Interferogram</operator>
    <sources>
      <sourceProduct refid="Read1"/>
    </sources>
    <parameters>
      <subtractFlatEarthPhase>true</subtractFlatEarthPhase>
      <subtractTopographicPhase>true</subtractTopographicPhase>
      <demName>External DEM</demName>
      <externalDEMFile>${dem}</externalDEMFile>
      <externalDEMNoDataValue>0.0</externalDEMNoDataValue>
      <includeCoherence>true</includeCoherence>
      <cohWinSizeMeters>100</cohWinSizeMeters>
    </parameters>
  </node>
  <node id="Write">
    <operator>Write</operator>
    <sources>
      <sourceProduct refid="Interferogram"/>
    </sources>
    <parameters>
      <file>${output}</file>
      <formatName>BEAM-DIMAP</formatName>
    </parameters>
  </node>
</graph>
```

- [ ] **Step 4: Create `ven_r5b.ps1`** (`-WaitForClosure` blocks until Task 7's run has finished: one heavy job at a time)

```powershell
<#
    R5b: GSLC vs classical in the radar domain through the terrain-aware bin link (see run_r5b.py).

      1. the R5 control interferogram in BURST geometry (no deburst)   -> ven_trad_ifg_burst.dim
      2. the ETAD-on S1A x S1C GSLC pair built WITH the diagnostic bands (-Diag) -> ven_etadD_*.dim
      3. run_r5b.py r5b <ven_etadD_ifg> <ven_etadD_gslc> <ven_trad_ifg_burst> <P8 floor>

    -WaitForClosure blocks until the R3 closure run has finished (or aborted): only one heavy job may
    run at a time (memory and disk).
#>
param([switch]$WaitForClosure, [double]$FloorConc = 0.999945149102415)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib.ps1"
$D = 'E:\Output\parity\ven'
Set-ParityLog "$D\r5b.log"

if ($WaitForClosure) {
    Log 'waiting for the R3 closure run to finish'
    while ($true) {
        $t = if (Test-Path "$D\closure.stdout") { Get-Content "$D\closure.stdout" -Raw } else { '' }
        if ($t -match 'GATE closure|ABORT') { break }
        Start-Sleep -Seconds 60
    }
    Log 'closure run finished; continuing'
}

$stack = "$D\ven_trad_stack.dim"
$ifgB  = "$D\ven_trad_ifg_burst.dim"
if (-not (Test-Path $stack)) { Log "ABORT: $stack missing - run ven_classical.ps1"; exit 1 }
$ok = Step 'ven_trad-ifg-burst' $ifgB {
    Invoke-MvnGpt 'sar-op-sentinel1' 'compile' @('validation/graphs/trad_s1_ifg_ven_burst.xml',
        "-Pinput1=$stack", "-Pdem=$script:DEM", "-Poutput=$ifgB") "$D\ven_trad_ifg_burst.log" }
if (-not $ok) { Log 'ABORT classical burst ifg'; exit 1 }

$A = (Get-ChildItem $D -Filter 'S1A_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
$C = (Get-ChildItem $D -Filter 'S1C_IW_SLC*_b4-6_orb_etad.dim' | Select-Object -First 1).FullName
& "$PSScriptRoot\ven_gslc.ps1" -Ref $A -Sec $C -Tag ven_etadD -Diag
if ($LASTEXITCODE -ne 0) { Log 'ABORT GSLC pair with diagnostic bands'; exit 1 }

& python "$script:PY\run_r5b.py" r5b "$D\ven_etadD_ifg.dim" "$D\ven_etadD_gslc.dim" $ifgB $FloorConc
Log "run_r5b exit $LASTEXITCODE"
exit $LASTEXITCODE
```

- [ ] **Step 5: Parse-check the driver and the graph** (Task 1 Step 6 command). Expected: `parse OK`.

- [ ] **Step 6: [HEAVY] Run it** (about 2 min classical burst ifg, 35 min GSLC with diagnostic bands, 10 min stack, 9 min interferogram, 2 min comparison)

Run: `pwsh -NoProfile -File validation/drivers/ven_r5b.ps1`
Expected: `GATE r5b-link-amplitude-corr PASS 0.92...` then the three R5 gates. **Measured 2026-09-21 (ramp off): concentration 0.236 (gate 0.90, FAIL), gx ratio 1.18 (PASS), gy ratio 37.9 (FAIL).** The source-index ranges printed must lie inside the raster (`rg 4..23648, az 26..4487` of 23665 x 4512).

- [ ] **Step 7: [HEAVY] The ramp-on variant** (only the interferogram is rebuilt, about 9 min; the GSLC and stack are reused)

Run: `pwsh -NoProfile -File validation/drivers/ven_gslc.ps1 -Ref <S1A _b4-6_orb_etad.dim> -Sec <S1C _b4-6_orb_etad.dim> -Tag ven_etadD -Diag -Ramp`, then `python validation/gslc_parity/run_r5b.py r5b E:/Output/parity/ven/ven_etadD_ifg_ramp.dim E:/Output/parity/ven/ven_etadD_gslc.dim E:/Output/parity/ven/ven_trad_ifg_burst.dim 0.999945149102415 R5b-ramp`
Expected: link 0.945; concentration 0.288 (FAIL), gx ratio 0.16, **gy ratio 3.09** (the residual-ramp option removes most of the azimuth gradient, not the phase disagreement). Recorded under the separate rung `R5b-ramp`; the spec's R5 is the ramp-OFF row.

- [ ] **Step 8: Diagnose, do not tune.** Post-hoc and labelled as such (rung `R5b-diag`): per-burst plane and offset; phase-only concentration by range band; the median over 32 x 64-cell tiles of the phase-only concentration after each tile's own plane (measured: 0.787 ramp off, 0.889 ramp on). Findings: the GSLC's own azimuth gradient is -0.89 rad per 8-line cell against -0.02 classical; the mean offset is common to all bursts (+0.85 / +0.81 / +0.84 rad); local agreement is good while a single plane over the swath leaves 0.005, i.e. a smooth low-order surface separates the chains. Its cause is untested (candidates: the flat-earth and topographic reference-phase models, the coregistration polynomials).

- [ ] **Step 9: Coherence-stratified closure diagnostic (for Task 7)** — `closure_diag.py` streams the three closure interferograms, multilooks to about 100 m and prints the closure RMS by minimum pair coherence plus a convention check (either pair flipped must be about 1.8 rad):

```python
"""Coherence-stratified R3 closure diagnostic (streaming: memory stays small).

The pre-registered R3 gate is over ALL valid cells. Two of the three pairs span 6-7 days over tropical
terrain, so many cells are nearly incoherent and their closure phase is random. This stratifies the
closure phase by the WEAKEST pair coherence in each cell: a chain that is self-consistent shows a
closure that collapses as coherence rises; a convention or registration defect does not.
It does not replace the recorded gates."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                       # noqa: E402
from closure import cell_size_m, colattice_offset   # noqa: E402


def cells(dims):
    ifg = [ge.load_complex_ifg(d) for d in dims]
    coh = [ge.load_real_band(ge.find_coherence_band(d, None))[0] for d in dims]
    geos = [ge.geotransform(d) for d in dims]
    offs = [colattice_offset(geos[0], g) for g in geos]
    r0 = max(o[0] for o in offs)
    c0 = max(o[1] for o in offs)
    r1 = min(o[0] + f[3] for o, f in zip(offs, ifg))
    c1 = min(o[1] + f[2] for o, f in zip(offs, ifg))
    dx_m, dy_m = cell_size_m(geos[0])
    mc, mr = max(1, round(100.0 / dx_m)), max(1, round(100.0 / dy_m))
    ny, nx = (r1 - r0) // mr, (c1 - c0) // mc
    Z = np.zeros((3, ny, nx), complex)
    C = np.zeros((3, ny, nx))
    BLK = mr * 32
    for b0 in range(0, ny * mr, BLK):
        nrow = min(BLK, ny * mr - b0)
        for k in range(3):
            i, q, _, _ = ifg[k]
            ro, co = offs[k]
            rs = slice(r0 - ro + b0, r0 - ro + b0 + nrow)
            cs = slice(c0 - co, c0 - co + nx * mc)
            z = np.asarray(i[rs, cs], np.float64) + 1j * np.asarray(q[rs, cs], np.float64)
            c = np.asarray(coh[k][rs, cs], np.float64)
            zz = z.reshape(nrow // mr, mr, nx, mc)
            bad = (zz == 0).any(axis=(1, 3))
            Z[k, b0 // mr:(b0 + nrow) // mr] = np.where(bad, 0, zz.sum(axis=(1, 3)))
            cc = c.reshape(nrow // mr, mr, nx, mc)
            C[k, b0 // mr:(b0 + nrow) // mr] = np.where(bad, 0, cc.mean(axis=(1, 3)))
    return Z, C, (mr, mc)


def main(dims):
    Z, C, ml = cells(dims)
    print(f"# cells {Z.shape[1:]} multilook {ml}")
    names = ("AC (1 d)", "CD (6 d)", "AD (7 d)")
    valid = (np.abs(Z) > 0).all(axis=0)
    for k in range(3):
        v = np.abs(Z[k]) > 0
        print(f"pair {names[k]}: valid {v.mean():.2f}, mean coherence {C[k][v].mean():.3f}, median {np.median(C[k][v]):.3f}")
    clo = Z[0] * Z[1] * np.conj(Z[2])
    minc = C.min(axis=0)
    print(f"{'min coh >=':>10} {'cells':>9} {'rms rad':>8} {'rms centred':>11} {'|mean phasor|':>13}")
    for thr in (0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7):
        m = valid & (minc >= thr)
        if m.sum() < 50:
            print(f"{thr:10.1f} {int(m.sum()):9d}  (too few)")
            continue
        ph = np.angle(clo[m])
        mean = np.angle(np.exp(1j * ph).mean())
        cen = np.angle(np.exp(1j * (ph - mean)))
        conc = abs(clo[m].sum()) / np.abs(clo[m]).sum()
        print(f"{thr:10.1f} {int(m.sum()):9d} {np.sqrt(np.mean(ph**2)):8.3f} {np.sqrt(np.mean(cen**2)):11.3f} {conc:13.3f}")
    # sign/convention check on the well-coherent subset: wrong-sign variants must be much worse
    m = valid & (minc >= 0.5)
    if m.sum() >= 50:
        for label, cl in (("as formed  AC*CD*conj(AD)", clo), ("flip AD      AC*CD*AD", Z[0] * Z[1] * Z[2]),
                          ("flip CD      AC*conj(CD)*conj(AD)", Z[0] * np.conj(Z[1]) * np.conj(Z[2]))):
            print(f"convention check (min coh>=0.5): {label:32s} rms {np.sqrt(np.mean(np.angle(cl[m])**2)):.3f}")


if __name__ == "__main__":
    D = "E:/Output/parity/ven/"
    main([D + "clos_AC_ifg.dim", D + "clos_CD_ifg.dim", D + "clos_AD_ifg.dim"])
```

Run: `python validation/gslc_parity/closure_diag.py`

- [ ] **Step 10: Hand off** — report the R5b gates for ramp off and ramp on side by side, the link correlations and the diagnostics, stating that the diagnostics do not turn a FAIL into a pass. Do not commit.

---

### Task 11: The smooth surface between the chains (added after the first run)

**Question:** what is the smooth phase surface that separates the GSLC and classical interferograms in R5b? All of this is post-hoc analysis of the saved R5b cells and changes no gate; rows are recorded under rung `SURFACE`. The scripts are analysis scripts for one dataset and have no `--selftest`; their outputs are the evidence.

**Files:**
- Create: `validation/gslc_parity/surface_diag.py`, `surface_diag2.py`, `surface_diag3.py`, `surface_diag4.py`, `surface_diag5.py`

**Inputs:** the saved cells `r5b_cells_ramp_off.npz` and `r5b_cells_ramp_on.npz` (G = GSLC mean, T = classical mean, fill mask; written by the Task 10 diagnostics with `run_r5b.cells_from_products`), the classical burst product `ven_trad_ifg_burst.dim` (tie-point grids for incidence, slant range and lat/lon), the staged DEM, and the ramp-on interferogram log for the estimator's per-burst rates.

- [ ] **Step 1: Save the cells** (after Task 10 Steps 6-7): `np.savez(path, G=Gm, T=Tm, fill=fill)` from `cells_from_products` for the ramp-off and the ramp-on interferograms, to `%TEMP%/r5b_cells_ramp_off.npz` and `%TEMP%/r5b_cells_ramp_on.npz`.

- [ ] **Step 2: A global unwrap is a trap — `surface_diag.py`.** It unwraps the whole coarse grid and fits global polynomials. **Read its figure before trusting its numbers:** the burst patches are disconnected at the seams, so the unwrapped offsets between bursts are arbitrary multiples of 2π and the "130 rad surface" it reports is largely an artefact.

```python
"""Find the smooth phase surface between the GSLC and classical interferograms (R5b cells).

Input: the binned cells saved by the R5b diagnostics (G = GSLC mean, T = classical mean, on 8-line x 30-col
radar cells of the classical burst-geometry grid). D = G * conj(T) is the phase difference between the chains.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
import gslc_equivalence as ge                               # noqa: E402
from radar_domain import read_tpg, tpg_at, radar_latlon     # noqa: E402

CELLS = sys.argv[1] if len(sys.argv) > 1 else "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_on.npz"
RADAR = "E:/Output/parity/ven/ven_trad_ifg_burst.dim"
DEM = "E:/TestData/dem/copernicus30_venezuela_orbit106.tif"
ML_AZ, ML_RG = 8, 30
B = 8                                  # coarse block: 8x8 cells ~ 0.9 km x 0.8 km
WAVELENGTH = 0.05546576                # C-band, metres


def coarse(D, B):
    ny, nx = (D.shape[0] // B) * B, (D.shape[1] // B) * B
    blk = D[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    return s, q, n


def polyfit2d(x, y, z, w, degx, degy, total=None):
    cols, names = [], []
    for i in range(degx + 1):
        for j in range(degy + 1):
            if total is not None and i + j > total:
                continue
            cols.append((x ** i) * (y ** j))
            names.append((i, j))
    A = np.stack(cols, axis=1)
    sw = np.sqrt(w)
    coef, *_ = np.linalg.lstsq(A * sw[:, None], z * sw, rcond=None)
    return coef, names, A @ coef


def main():
    z = np.load(CELLS)
    G, T = z["G"], z["T"]
    valid = (np.abs(G) > 0) & (np.abs(T) > 0)
    D = np.where(valid, G * np.conj(T), 0)
    s, q, n = coarse(D, B)
    ok = (q > 0.45) & (n > 0.6 * B * B)
    print(f"# coarse grid {s.shape}; usable blocks {int(ok.sum())} ({ok.mean():.0%}); mean quality {q[ok].mean():.2f}")
    from skimage.restoration import unwrap_phase
    ph = np.angle(np.where(ok, s, 1.0))
    U = unwrap_phase(np.ma.masked_array(ph, mask=~ok))
    U = np.ma.filled(U, np.nan)
    ok &= np.isfinite(U)
    U -= np.nanmedian(U[ok])
    print(f"# unwrapped surface: range {np.nanmin(U[ok]):+.1f} .. {np.nanmax(U[ok]):+.1f} rad, std {np.nanstd(U[ok]):.2f} rad")

    iy, ix = np.where(ok)
    y = (iy + 0.5) / s.shape[0]            # azimuth 0..1 over the 3 bursts
    x = (ix + 0.5) / s.shape[1]            # range 0..1
    u = U[ok]
    w = q[ok] ** 2
    tot = np.sum(w * (u - np.average(u, weights=w)) ** 2)

    def r2(pred):
        return 1 - np.sum(w * (u - pred) ** 2) / tot

    print("\n-- how much of the unwrapped surface is explained by")
    for name, (dx, dy, t) in {"range only, degree 1": (1, 0, None), "range only, degree 2": (2, 0, None),
                              "range only, degree 4": (4, 0, None), "azimuth only, degree 2": (0, 2, None),
                              "plane (range+azimuth)": (1, 1, 1), "quadratic 2-D": (2, 2, 2),
                              "cubic 2-D": (3, 3, 3), "quartic 2-D": (4, 4, 4)}.items():
        c, nm, pred = polyfit2d(x, y, u, w, dx, dy, t)
        res = u - pred
        print(f"   {name:26s} R2 {r2(pred):6.3f}   residual rms {np.sqrt(np.average(res ** 2, weights=w)):5.2f} rad")

    # per-burst range polynomials (bursts are 1/3 of the rows each)
    print("\n-- range-polynomial (degree 2) fitted separately per burst:")
    burst = np.minimum((y * 3).astype(int), 2)
    pred_all = np.zeros_like(u)
    for b in range(3):
        m = burst == b
        c, nm, p = polyfit2d(x[m], y[m], u[m], w[m], 2, 0)
        pred_all[m] = p
        print(f"   burst {b + 1}: coef (const, range, range^2) = {c[0]:+.2f} {c[1]:+.2f} {c[2]:+.2f} rad; n {int(m.sum())}")
    print(f"   per-burst range-quadratic: R2 {r2(pred_all):.3f}")

    # smooth 2-D low-order surface and what is left
    c, nm, pred = polyfit2d(x, y, u, w, 3, 3, 3)
    res = u - pred
    print(f"\n-- after a cubic 2-D surface the residual is {np.sqrt(np.average(res ** 2, weights=w)):.2f} rad rms "
          f"(vs {np.sqrt(np.average((u - np.average(u, weights=w)) ** 2, weights=w)):.2f} rad before)")

    # terrain and geometry at the block centres
    rows = (iy * B + B / 2) * ML_AZ
    cols = (ix * B + B / 2) * ML_RG
    lat, lon = radar_latlon(RADAR, rows, cols)
    import rasterio
    with rasterio.open(DEM) as ds:
        h = np.array([v[0] for v in ds.sample(list(zip(lon, lat)))], float)
    h[h < -100] = np.nan
    la, *_ = read_tpg(RADAR, "latitude")
    inc = tpg_at(*read_tpg(RADAR, "incident_angle"), rows, cols)
    srt = tpg_at(*read_tpg(RADAR, "slant_range_time"), rows, cols)      # ns, two-way
    R = srt * 1e-9 * 299792458.0 / 2
    okh = np.isfinite(h)
    print(f"\n-- terrain: DEM height at the blocks {np.nanmin(h):.0f}..{np.nanmax(h):.0f} m, std {np.nanstd(h):.0f} m; "
          f"incidence {inc.min():.1f}..{inc.max():.1f} deg; slant range {R.min() / 1e3:.0f}..{R.max() / 1e3:.0f} km")
    # regress the RESIDUAL of a low-order surface (so a smooth range/azimuth trend is not mistaken for terrain)
    for label, deg in (("after removing a plane", (1, 1, 1)), ("after removing a quadratic", (2, 2, 2)),
                       ("after removing a cubic", (3, 3, 3))):
        c, nm, pred = polyfit2d(x, y, u, w, *deg)
        res = u - pred
        m = okh
        # remove the same-order surface from the height too, so both are 'local' quantities
        ch, _, ph_ = polyfit2d(x[m], y[m], h[m], w[m], *deg)
        hl = h[m] - ph_
        k = np.sum(w[m] * hl * res[m]) / np.sum(w[m] * hl * hl)                  # rad per metre
        rr = np.corrcoef(hl, res[m])[0, 1]
        th = np.radians(inc[m]).mean()
        Rm = R[m].mean()
        dB = k * WAVELENGTH * Rm * np.sin(th) / (4 * np.pi)
        print(f"   {label:28s}: corr(local height, residual) {rr:+.3f}; slope {k * 1000:+.3f} rad per 1000 m of height "
              f"-> implied perpendicular-baseline mismatch {dB:+.1f} m")
    # raw correlation with height and with range
    print(f"   raw corr(height, unwrapped surface) {np.corrcoef(h[okh], u[okh])[0, 1]:+.3f}; corr(range x, surface) {np.corrcoef(x, u)[0, 1]:+.3f}; corr(azimuth y, surface) {np.corrcoef(y, u)[0, 1]:+.3f}")

    # save arrays and a figure for the morning
    outd = Path("E:/Output/parity/figures")
    outd.mkdir(parents=True, exist_ok=True)
    np.savez(outd / "surface_diag.npz", U=U, ok=ok, q=q)
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(1, 3, figsize=(15, 5.2), dpi=110)
        Um = np.ma.masked_array(U, mask=~np.isfinite(U))
        im = ax[0].imshow(Um, cmap="RdBu_r", aspect=B * ML_AZ / (B * ML_RG) * 0 + 0.55)
        ax[0].set_title("unwrapped GSLC - classical phase (rad)\nblocks of 8x8 cells (~0.9 x 0.8 km)")
        plt.colorbar(im, ax=ax[0], fraction=0.046)
        R2map = np.full(U.shape, np.nan)
        R2map[iy, ix] = res
        im = ax[1].imshow(np.ma.masked_invalid(R2map), cmap="RdBu_r", vmin=-2, vmax=2, aspect=0.55)
        ax[1].set_title("residual after a cubic 2-D surface (rad)")
        plt.colorbar(im, ax=ax[1], fraction=0.046)
        Hm = np.full(U.shape, np.nan)
        Hm[iy, ix] = h
        im = ax[2].imshow(np.ma.masked_invalid(Hm), cmap="terrain", aspect=0.55)
        ax[2].set_title("DEM height at the block centres (m)")
        plt.colorbar(im, ax=ax[2], fraction=0.046)
        for a in ax:
            a.set_xlabel("range block ->")
            a.set_ylabel("azimuth block (3 bursts)")
        fig.tight_layout()
        fig.savefig(outd / "surface_diag.png")
        print("\nfigure:", outd / "surface_diag.png")
    except Exception as e:
        print("figure skipped:", e)


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Per burst — `surface_diag2.py`** (ramp off and ramp on; each burst unwrapped and fitted on its own; also prints the estimator's logged per-burst ramp rates). Expected: ramp on, per-burst cubic block residual 0.17 / 0.27 / 0.48 rad and cell-level phase-only concentration 0.928 / 0.898 / 0.863 after removing it. **The ramp-off half fails for a mundane reason** (the azimuth ramp of ~0.8 rad per cell destroys 8 × 8 block coherence, leaving 150-300 usable blocks): do not read its numbers.

```python
"""Per-burst characterisation of the GSLC - classical phase surface (companion to surface_diag.py).

Each burst is unwrapped and fitted on its own: the offsets BETWEEN bursts are arbitrary multiples of 2*pi
(the burst patches are disconnected at the seams), so only the shape inside a burst is meaningful.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
ML_AZ, ML_RG, B = 8, 30, 8
LINE_DT = 0.002055556299999998          # s per azimuth line
PER = 1504 // ML_AZ                     # cells per burst (188)
NPX = ML_RG                             # range pixels per cell


def unwrap_burst(s, q, n, rows):
    from skimage.restoration import unwrap_phase
    ok = (q[rows] > 0.45) & (n[rows] > 0.6 * B * B)
    ph = np.angle(np.where(ok, s[rows], 1.0))
    U = np.ma.filled(unwrap_phase(np.ma.masked_array(ph, mask=~ok)), np.nan)
    ok &= np.isfinite(U)
    return U, ok


def fit_poly(xc, yc, u, w, deg):
    cols = [(xc ** i) * (yc ** j) for i in range(deg + 1) for j in range(deg + 1 - i)]
    A = np.stack(cols, axis=1)
    sw = np.sqrt(w)
    coef, *_ = np.linalg.lstsq(A * sw[:, None], u * sw, rcond=None)
    return coef, A


def eval_poly(coef, xc, yc, deg):
    cols = [(xc ** i) * (yc ** j) for i in range(deg + 1) for j in range(deg + 1 - i)]
    return np.stack(cols, axis=1) @ coef


def analyse(path, label, ramp_rates=None):
    z = np.load(path)
    G, T = z["G"], z["T"]
    valid = (np.abs(G) > 0) & (np.abs(T) > 0)
    D = np.where(valid, G * np.conj(T), 0)
    ny, nx = (D.shape[0] // B) * B, (D.shape[1] // B) * B
    blk = D[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    per_b = PER // B                                   # coarse rows per burst (23.5 -> use edges)
    edges = [0, int(round(PER / B)), int(round(2 * PER / B)), s.shape[0]]
    print(f"\n===== {label}  (coarse grid {s.shape}, bursts at coarse rows {edges}) =====")
    total_conc_before, total_conc_after, tot_n = 0j, 0j, 0
    results = []
    for b in range(3):
        rows = slice(edges[b], edges[b + 1])
        U, ok = unwrap_burst(s, q, n, rows)
        iy, ix = np.where(ok)
        yy = (iy + 0.5) / (edges[b + 1] - edges[b])
        xx = (ix + 0.5) / s.shape[1]
        u = U[ok]
        w = q[rows][ok] ** 2
        out = {}
        for deg in (1, 2, 3):
            coef, A = fit_poly(xx, yy, u, w, deg)
            res = u - A @ coef
            out[deg] = (coef, np.sqrt(np.average(res ** 2, weights=w)))
        coef1 = out[1][0]        # (const, y, x) order: i=range power, j=azimuth power -> cols (0,0),(0,1),(1,0)
        # slopes in physical units
        d_az = coef1[1] / (edges[b + 1] - edges[b]) / (B * ML_AZ)             # rad per azimuth line
        d_rg = coef1[2] / s.shape[1] / (B * NPX)                               # rad per range pixel
        std = np.sqrt(np.average((u - np.average(u, weights=w)) ** 2, weights=w))
        line = (f"burst {b + 1}: blocks {int(ok.sum()):5d} | surface std {std:5.1f} rad | residual rms after plane "
                f"{out[1][1]:.2f}, quadratic {out[2][1]:.2f}, cubic {out[3][1]:.2f} rad | plane slopes: "
                f"azimuth {d_az:+.4f} rad/line ({d_az / LINE_DT:+.1f} rad/s), range {d_rg:+.5f} rad/px")
        print(line)
        results.append((b, d_az / LINE_DT, d_rg, out[2][1], std))
        # cell-level test: subtract the fitted cubic surface and measure the phase-only concentration
        for deg, key in ((1, "plane"), (3, "cubic")):
            coef = out[deg][0]
            r0, r1 = b * PER, (b + 1) * PER
            cy = (np.arange(r0, r1) + 0.5 - r0) / PER
            cx = (np.arange(D.shape[1]) + 0.5) / D.shape[1]
            X, Y = np.meshgrid(cx, cy)
            fit = eval_poly(coef, X.ravel(), Y.ravel(), deg).reshape(X.shape)
            Db = D[r0:r1]
            vb = valid[r0:r1]
            u_ = np.exp(1j * np.angle(Db[vb] * np.exp(-1j * fit[vb])))
            conc = abs(u_.mean())
            rms = np.sqrt(np.mean(np.angle(u_ * np.exp(-1j * np.angle(u_.mean()))) ** 2))
            print(f"          cell-level phase-only concentration after removing the per-burst {key:5s}: {conc:.3f}  (rms about mean {rms:.2f} rad)")
    if ramp_rates:
        print("   GSLC per-burst ramp the estimator REMOVED (interferogram log), rad/s:", ramp_rates)
        print("   azimuth slope of the GSLC-minus-classical surface, per burst,     rad/s:", [round(r[1], 1) for r in results])
    return results


def ramp_rates_from_log(path):
    t = Path(path).read_text(errors="replace")
    m = re.search(r"GSLC residual ramp per-burst.*", t)
    return [float(v) for v in re.findall(r"rate=([+-]?[\d.]+)", m.group(0))] if m else None


if __name__ == "__main__":
    tmp = "C:/Users/luis_/AppData/Local/Temp/"
    rates = ramp_rates_from_log("E:/Output/parity/ven/ven_etadD_ifg.log")
    analyse(tmp + "r5b_cells_ramp_off.npz", "ramp OFF (spec R5 configuration)", rates)
    analyse(tmp + "r5b_cells_ramp_on.npz", "ramp ON (per-burst residual ramp applied)", None)
    print("\n(ramp rates parsed from the ramp-on interferogram log; the estimator's rate is the ramp it REMOVED, so the ramp-OFF surface's azimuth slope should equal it)")
```

- [ ] **Step 4: Compare the two independent azimuth estimates.** The ramp the estimator removed (from the ramp-on `ven_etadD_ifg.log`: −46.8 / −47.8 / −73.0 rad/s, ×8 lines × 2.0556 ms = −0.770 / −0.786 / −1.200 rad per cell) against the GSLC-minus-classical azimuth gradient measured directly from the ramp-off cells (median lag-1 gradient per burst: −0.780 / −0.813 / −1.256). Measured ratios 1.013 / 1.034 / 1.047: the azimuth part of the surface is the GSLC's own annotation ramp.

- [ ] **Step 5: Is it terrain? — `surface_diag3.py`** (per-burst fits with block-averaged DEM height as an extra regressor). Expected: the block residual changes by ≤ 0.3 rad and the coefficient is unstable in sign and size across polynomial degree; the topographic sensitivity printed is 0.00035 rad per m of height per m of baseline. Conclusion: not a topographic or baseline mismatch.

```python
"""Does the per-burst smooth GSLC - classical surface track terrain? (companion to surface_diag2.py)

For each burst (unwrapped on its own), fit  U = poly(range, azimuth; degree d) + k * height  and report k,
the implied perpendicular-baseline mismatch and how much the residual falls when height is allowed.
Heights are block averages of the staged Copernicus DEM (3x3 samples per 8x8-cell block) at lat/lon taken
from the classical product's tie-point grids - good enough for km-scale relief."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from radar_domain import radar_latlon, read_tpg, tpg_at     # noqa: E402
from surface_diag2 import B, ML_AZ, ML_RG, PER, unwrap_burst  # noqa: E402

RADAR = "E:/Output/parity/ven/ven_trad_ifg_burst.dim"
DEM = "E:/Output/parity/ven/../../../TestData/dem/copernicus30_venezuela_orbit106.tif"
DEM = "E:/TestData/dem/copernicus30_venezuela_orbit106.tif"
WAVELENGTH = 0.05546576


def block_heights(shape):
    import rasterio
    ny, nx = shape
    hs = np.full((ny, nx), np.nan)
    offs = np.array([-0.3, 0.0, 0.3])
    with rasterio.open(DEM) as ds:
        rr, cc = np.meshgrid(np.arange(ny), np.arange(nx), indexing="ij")
        acc = np.zeros((ny, nx))
        cnt = np.zeros((ny, nx))
        for oy in offs:
            for ox in offs:
                rows = ((rr + 0.5 + oy) * B) * ML_AZ
                cols = ((cc + 0.5 + ox) * B) * ML_RG
                lat, lon = radar_latlon(RADAR, rows, cols)
                v = np.array([p[0] for p in ds.sample(list(zip(lon.ravel(), lat.ravel())))], float).reshape(ny, nx)
                ok = v > -100
                acc += np.where(ok, v, 0)
                cnt += ok
        hs = np.where(cnt > 0, acc / np.maximum(cnt, 1), np.nan)
    return hs


def design(x, y, deg, extra=None):
    cols = [(x ** i) * (y ** j) for i in range(deg + 1) for j in range(deg + 1 - i)]
    if extra is not None:
        cols.append(extra)
    return np.stack(cols, axis=1)


def wls(A, u, w):
    sw = np.sqrt(w)
    coef, *_ = np.linalg.lstsq(A * sw[:, None], u * sw, rcond=None)
    res = u - A @ coef
    return coef, np.sqrt(np.average(res ** 2, weights=w)), res


def main(path):
    z = np.load(path)
    G, T = z["G"], z["T"]
    valid = (np.abs(G) > 0) & (np.abs(T) > 0)
    D = np.where(valid, G * np.conj(T), 0)
    ny, nx = (D.shape[0] // B) * B, (D.shape[1] // B) * B
    blk = D[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    H = block_heights(s.shape)
    edges = [0, int(round(PER / B)), int(round(2 * PER / B)), s.shape[0]]
    print(f"# DEM block heights {np.nanmin(H):.0f}..{np.nanmax(H):.0f} m (std {np.nanstd(H):.0f} m)")
    # sensitivity of the topographic phase to a baseline error: dphi/dh per metre of B_perp
    for b in range(3):
        rows = slice(edges[b], edges[b + 1])
        U, ok = unwrap_burst(s, q, n, rows)
        iy, ix = np.where(ok)
        h = H[rows][ok]
        good = np.isfinite(h)
        iy, ix, h, u = iy[good], ix[good], h[good], U[ok][good]
        w = q[rows][ok][good] ** 2
        yy = (iy + 0.5) / (edges[b + 1] - edges[b])
        xx = (ix + 0.5) / s.shape[1]
        rr = np.arange(len(u))
        R = tpg_at(*read_tpg(RADAR, "slant_range_time"), (edges[b] + iy + 0.5) * B * ML_AZ, (ix + 0.5) * B * ML_RG) * 1e-9 * 299792458.0 / 2
        th = np.radians(tpg_at(*read_tpg(RADAR, "incident_angle"), (edges[b] + iy + 0.5) * B * ML_AZ, (ix + 0.5) * B * ML_RG))
        sens = 4 * np.pi / (WAVELENGTH * R * np.sin(th))             # rad per metre of height per metre of B_perp
        print(f"\nburst {b + 1}: {len(u)} blocks, surface std {np.std(u):.1f} rad, height std {np.std(h):.0f} m")
        print(f"   topographic sensitivity {sens.mean():.5f} rad per metre of height per metre of B_perp (1 m of baseline error over 400 m of relief = {sens.mean() * 400:.2f} rad)")
        for deg in (1, 2, 3):
            c0, r0, _ = wls(design(xx, yy, deg), u, w)
            c1, r1, _ = wls(design(xx, yy, deg, h), u, w)
            k = c1[-1]
            dB = k / sens.mean()
            # uncertainty of k from the weighted LS covariance (assumes independent residuals; blocks are not, so optimistic)
            A = design(xx, yy, deg, h) * np.sqrt(w)[:, None]
            cov = np.linalg.inv(A.T @ A) * (r1 ** 2)
            se = np.sqrt(cov[-1, -1])
            print(f"   degree {deg}: residual {r0:.2f} -> {r1:.2f} rad with height; k = {k * 1000:+.3f} +/- {se * 1000:.3f} rad per 1000 m "
                  f"-> implied perpendicular-baseline mismatch {dB:+.2f} m (t = {k / se:+.1f}, optimistic)")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_on.npz")
```

- [ ] **Step 6: A rejected method — `surface_diag4.py`.** A gradient-based emulation of the ramp estimator. **Its upper bound (a per-burst cubic fitted to the difference with the classical chain) only reached a concentration of 0.15, where the unwrapped fit reaches 0.896, so the method is too imprecise to say anything and its A/B/C rows must not be read.** Kept so the dead end is not repeated: a 1% error on a 137 rad ramp is already 1.4 rad.

```python
"""Would a per-burst RANGE model in the GSLC ramp estimator close the gap to the classical chain?

Emulates the estimator on the saved ramp-OFF cells, using ONLY the GSLC interferogram (lag-1 phase gradients,
robust weighted least squares, curl-free by construction), then measures the agreement with the classical
chain. The classical data are used for scoring, never for estimation.

  A  estimator as implemented: range terms (u, u^2) SHARED by all bursts, azimuth terms (v, v^2) per burst
  B  per-burst quadratic incl. cross term: u, u^2, v, v^2, uv  for each burst
  C  per-burst cubic
  U  upper bound: per-burst cubic fitted to the phase difference with the classical chain (uses T)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
PER = 188
CELLS = "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_off.npz"


def mono(deg, total=True, drop_const=True):
    ms = [(i, j) for i in range(deg + 1) for j in range(deg + 1) if (i + j <= deg if total else True)]
    return [m for m in ms if not (drop_const and m == (0, 0))]


def grad_rows(G, mons, W, Hb):
    """Design rows (per burst) for the lag-1 phase gradients of the wrapped field G (rad per cell)."""
    v = np.abs(G) > 0
    # range gradient: between columns j and j+1 ; azimuth gradient: between rows i and i+1
    gx = np.angle(G[:, 1:] * np.conj(G[:, :-1]))
    vx = v[:, 1:] & v[:, :-1]
    wx = np.minimum(np.abs(G[:, 1:]), np.abs(G[:, :-1]))
    gy = np.angle(G[1:, :] * np.conj(G[:-1, :]))
    vy = v[1:, :] & v[:-1, :]
    wy = np.minimum(np.abs(G[1:, :]), np.abs(G[:-1, :]))
    rows, cols = G.shape
    jx = (np.arange(cols - 1) + 1.0) / W - 0.5
    ix = (np.arange(rows) + 0.5) / Hb - 0.5
    U, V = np.meshgrid(jx, ix)                              # range gradient at (i, j+1/2)
    jy = (np.arange(cols) + 0.5) / W - 0.5
    iy = (np.arange(rows - 1) + 1.0) / Hb - 0.5
    U2, V2 = np.meshgrid(jy, iy)                            # azimuth gradient at (i+1/2, j)
    dx = np.stack([(i * U ** max(i - 1, 0) * V ** j if i > 0 else 0 * U) / W for (i, j) in mons], axis=-1)
    dy = np.stack([(j * U2 ** i * V2 ** max(j - 1, 0) if j > 0 else 0 * U2) / Hb for (i, j) in mons], axis=-1)
    return (dx[vx], gx[vx], wx[vx]), (dy[vy], gy[vy], wy[vy])


def robust_ls(A, y, w, passes=3, clip=1.0):
    keep = np.ones(len(y), bool)
    for _ in range(passes):
        sw = np.sqrt(w[keep])
        coef, *_ = np.linalg.lstsq(A[keep] * sw[:, None], y[keep] * sw, rcond=None)
        res = y - A @ coef
        keep = np.abs(res) < clip
    return coef


def phase_surface(coef, mons, shape, W, Hb):
    rows, cols = shape
    u = (np.arange(cols) + 0.5) / W - 0.5
    v = (np.arange(rows) + 0.5) / Hb - 0.5
    U, V = np.meshgrid(u, v)
    return sum(c * U ** i * V ** j for c, (i, j) in zip(coef, mons))


def score(G, T, label):
    """Per-burst and overall phase-only concentration of G*conj(T); per-burst constants are free."""
    out = []
    allu = []
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        d = G[sl] * np.conj(T[sl])
        v = (np.abs(G[sl]) > 0) & (np.abs(T[sl]) > 0)
        u = np.exp(1j * np.angle(d[v]))
        m = u.mean()
        out.append(abs(m))
        allu.append(u * np.exp(-1j * np.angle(m)))             # align the burst's constant offset
    a = np.concatenate(allu)
    rms = np.sqrt(np.mean(np.angle(a * np.exp(-1j * np.angle(a.mean()))) ** 2))
    print(f"   {label:58s} conc per burst {out[0]:.3f} {out[1]:.3f} {out[2]:.3f} | all bursts (constants aligned) {abs(a.mean()):.3f}, rms {rms:.2f} rad")
    return out


def main():
    z = np.load(CELLS)
    G, T = z["G"], z["T"]
    W = G.shape[1]
    print(f"# ramp-OFF cells {G.shape}: {G.shape[0] // PER} bursts of {PER} cells x {W}")
    score(G, T, "no correction (per-burst constants free)")

    # ---- A: shared range terms (u, u^2), per-burst azimuth terms (v, v^2)
    ma_rg = [(1, 0), (2, 0)]
    ma_az = [(0, 1), (0, 2)]
    nA = 2 + 3 * 2
    rowsA, yA, wA = [], [], []
    for b in range(3):
        Gb = G[b * PER:(b + 1) * PER]
        (dx, gx, wx), (dy, gy, wy) = grad_rows(Gb, ma_rg + ma_az, W, PER)
        for D_, g_, w_ in ((dx, gx, wx), (dy, gy, wy)):
            A = np.zeros((len(g_), nA))
            A[:, 0:2] = D_[:, 0:2]                                # shared range terms
            A[:, 2 + 2 * b:4 + 2 * b] = D_[:, 2:4]                # this burst's azimuth terms
            rowsA.append(A)
            yA.append(g_)
            wA.append(w_)
    cA = robust_ls(np.vstack(rowsA), np.concatenate(yA), np.concatenate(wA))
    GA = G.copy()
    for b in range(3):
        coef = np.concatenate([cA[0:2], cA[2 + 2 * b:4 + 2 * b]])
        GA[b * PER:(b + 1) * PER] = G[b * PER:(b + 1) * PER] * np.exp(-1j * phase_surface(coef, ma_rg + ma_az, (PER, W), W, PER))
    print(f"   A coefficients: shared range (u,u^2) = {cA[0]:+.1f} {cA[1]:+.1f} rad; azimuth per burst (v,v^2) = "
          f"{cA[2]:+.1f} {cA[3]:+.1f} | {cA[4]:+.1f} {cA[5]:+.1f} | {cA[6]:+.1f} {cA[7]:+.1f}")
    score(GA, T, "A  shared range terms + per-burst azimuth (as implemented)")

    # ---- B, C: independent per-burst polynomials
    for name, mons in (("B  per-burst quadratic incl. cross term", mono(2)), ("C  per-burst cubic", mono(3))):
        Gc = G.copy()
        for b in range(3):
            Gb = G[b * PER:(b + 1) * PER]
            (dx, gx, wx), (dy, gy, wy) = grad_rows(Gb, mons, W, PER)
            coef = robust_ls(np.vstack([dx, dy]), np.concatenate([gx, gy]), np.concatenate([wx, wy]))
            Gc[b * PER:(b + 1) * PER] = Gb * np.exp(-1j * phase_surface(coef, mons, (PER, W), W, PER))
            if b == 0:
                print(f"   {name[:1]} burst-1 coefficients: " + " ".join(f"{m}:{c:+.1f}" for m, c in zip(mons, coef)))
        score(Gc, T, name)

    # ---- U: upper bound using the classical chain (per-burst cubic on the wrapped difference, by gradients)
    mons = mono(3)
    Gu = G.copy()
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        Db = G[sl] * np.conj(T[sl])
        (dx, gx, wx), (dy, gy, wy) = grad_rows(Db, mons, W, PER)
        coef = robust_ls(np.vstack([dx, dy]), np.concatenate([gx, gy]), np.concatenate([wx, wy]))
        Gu[sl] = G[sl] * np.exp(-1j * phase_surface(coef, mons, (PER, W), W, PER))
    score(Gu, T, "U  upper bound: per-burst cubic fitted TO THE DIFFERENCE with T")


if __name__ == "__main__":
    main()
```

- [ ] **Step 7: Can the GSLC estimate it alone? — `surface_diag5.py`** (per-burst surfaces from the ramp-on GSLC interferogram alone by unwrapped fits: shared-range, plane, quadratic, cubic; scored against the classical chain; plus the upper bound and the classical's own per-burst cubic). Expected: GSLC-alone 0.013-0.060 against 0.022 uncorrected; upper bound 0.896; the classical interferogram's own per-burst cubic has std 16.6 rad. Conclusion: real large-scale phase of the same order and shape exists, so the annotation surface cannot be separated from signal without external information.

```python
"""Estimate the per-burst smooth surface from the GSLC (ramp-ON) interferogram ALONE, by unwrapped-phase
fits per burst, and score the corrected GSLC against the classical chain (the classical data are used only
for scoring). Answers: would per-burst range terms in the GSLC ramp estimator help, and do they absorb
genuine signal?

  A  shared range terms (u, u^2) across the bursts + per-burst azimuth terms (v, v^2) + per-burst constants
  B  per-burst quadratic (u, v, u^2, v^2, uv)
  C  per-burst cubic
  U  upper bound: per-burst cubic fitted to the unwrapped GSLC-minus-classical difference (uses T)
  T  the classical interferogram's OWN per-burst cubic (how large is the real large-scale signal?)
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from surface_diag2 import B, PER, eval_poly, fit_poly, unwrap_burst   # noqa: E402

CELLS = "C:/Users/luis_/AppData/Local/Temp/r5b_cells_ramp_on.npz"


def blocks(F):
    ny, nx = (F.shape[0] // B) * B, (F.shape[1] // B) * B
    blk = F[:ny, :nx].reshape(ny // B, B, nx // B, B)
    s = blk.sum(axis=(1, 3))
    a = np.abs(blk).sum(axis=(1, 3))
    q = np.where(a > 0, np.abs(s) / np.maximum(a, 1e-30), 0)
    n = (np.abs(blk) > 0).sum(axis=(1, 3))
    return s, q, n


def surface_per_burst(F, deg, shared_range=False):
    """Per-burst polynomial surfaces (evaluated at every cell) fitted to the unwrapped phase of F."""
    s, q, n = blocks(F)
    edges = [0, int(round(PER / B)), int(round(2 * PER / B)), s.shape[0]]
    W = F.shape[1]
    surf = np.zeros(F.shape)
    data = []
    for b in range(3):
        rows = slice(edges[b], edges[b + 1])
        U, ok = unwrap_burst(s, q, n, rows)
        iy, ix = np.where(ok)
        data.append(((iy + 0.5) / (edges[b + 1] - edges[b]) - 0.5, (ix + 0.5) / s.shape[1] - 0.5, U[ok], q[rows][ok] ** 2))
    if shared_range:
        # unknowns: shared (u, u^2), per burst (v, v^2, const)
        rowsA, yA, wA = [], [], []
        for b, (v, u, val, w) in enumerate(data):
            A = np.zeros((len(val), 2 + 3 * 3))
            A[:, 0], A[:, 1] = u, u ** 2
            A[:, 2 + 3 * b], A[:, 3 + 3 * b], A[:, 4 + 3 * b] = v, v ** 2, 1.0
            rowsA.append(A)
            yA.append(val)
            wA.append(w)
        A, y, w = np.vstack(rowsA), np.concatenate(yA), np.concatenate(wA)
        coef, *_ = np.linalg.lstsq(A * np.sqrt(w)[:, None], y * np.sqrt(w), rcond=None)
        for b in range(3):
            r0, r1 = b * PER, (b + 1) * PER
            V, Uu = np.meshgrid((np.arange(PER) + 0.5) / PER - 0.5, (np.arange(W) + 0.5) / W - 0.5, indexing="ij")
            surf[r0:r1] = coef[0] * Uu + coef[1] * Uu ** 2 + coef[2 + 3 * b] * V + coef[3 + 3 * b] * V ** 2
        return surf
    for b, (v, u, val, w) in enumerate(data):
        coef, _ = fit_poly(u, v, val, w, deg)
        r0, r1 = b * PER, (b + 1) * PER
        V, Uu = np.meshgrid((np.arange(PER) + 0.5) / PER - 0.5, (np.arange(W) + 0.5) / W - 0.5, indexing="ij")
        surf[r0:r1] = eval_poly(coef, Uu.ravel(), V.ravel(), deg).reshape(PER, W)
    return surf


def score(G, T, label):
    out, allu = [], []
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        d = G[sl] * np.conj(T[sl])
        v = (np.abs(G[sl]) > 0) & (np.abs(T[sl]) > 0)
        u = np.exp(1j * np.angle(d[v]))
        m = u.mean()
        out.append(abs(m))
        allu.append(u * np.exp(-1j * np.angle(m)))
    a = np.concatenate(allu)
    rms = np.sqrt(np.mean(np.angle(a * np.exp(-1j * np.angle(a.mean()))) ** 2))
    print(f"   {label:62s} conc per burst {out[0]:.3f} {out[1]:.3f} {out[2]:.3f} | all (constants aligned) {abs(a.mean()):.3f}, rms {rms:.2f} rad")
    return abs(a.mean()), rms


def main():
    z = np.load(CELLS)
    G, T = z["G"], z["T"]
    print(f"# ramp-ON cells {G.shape}")
    score(G, T, "no further correction (per-burst constants free)")
    res = {}
    for name, kw in (("A  shared range terms + per-burst azimuth  (estimator's structure)", dict(deg=2, shared_range=True)),
                     ("B1 per-burst plane", dict(deg=1)), ("B2 per-burst quadratic", dict(deg=2)), ("C  per-burst cubic", dict(deg=3))):
        surf = surface_per_burst(G, **kw)
        res[name[:2]] = score(G * np.exp(-1j * surf), T, name + " [from GSLC alone]")
    # absorbed signal: how much of the CLASSICAL interferogram's own phase would the same per-burst cubic remove?
    surfT = surface_per_burst(T, 3)
    print(f"   classical's own per-burst cubic: std of the removed surface {np.std(surfT):.1f} rad (the GSLC-alone cubic above removes "
          f"{np.std(surface_per_burst(G, 3)):.1f} rad)")
    d = G * np.conj(T)
    surfU = surface_per_burst(d, 3)
    score(G * np.exp(-1j * surfU), T, "U  upper bound: cubic fitted to the difference with T")
    # what is the correlation between the GSLC-alone surface and the true difference surface?
    surfC = surface_per_burst(G, 3)
    for b in range(3):
        sl = slice(b * PER, (b + 1) * PER)
        a_, b_ = surfC[sl].ravel(), surfU[sl].ravel()
        print(f"   burst {b + 1}: GSLC-alone cubic surface vs difference-with-T cubic surface: corr {np.corrcoef(a_, b_)[0, 1]:+.3f}, "
              f"rms of (alone - difference) {np.sqrt(np.mean(((a_ - a_.mean()) - (b_ - b_.mean())) ** 2)):.2f} rad")


if __name__ == "__main__":
    main()
```

> **SUPERSEDED BY TASK 12.** The hypothesis in Step 8 was tested and refuted: the surface is `2 x (m_ref - m_sec)` from an inverted sign in `InterferogramOp`, not annotation DC/FM error. Steps 2-7 stay valid as descriptions of the surface; their interpretation does not.

- [ ] **Step 8: Record and write up.** Record the rows under rung `SURFACE` (`budget.record`, passed=None) and add section 11 to the note. State the hypothesis as a hypothesis: the carrier-free GSLC keeps each acquisition's per-burst annotation DC/FM deramp-model error while the classical deramp/reramp cancels it. A direct test compares the per-burst annotation polynomials of S1A and S1C with the fitted surfaces. The discriminating measurement is an ESD-like double difference in the burst overlaps, where genuine signal cancels; the current GSLC output selects one burst per pixel and does not provide it.

- [ ] **Step 9: Hand off** — report the per-burst structure, the azimuth match, the terrain result and the GSLC-alone result, with the caveats: one pair, three bursts, mean coherence 0.28, block-level unwrapping, the classical chain is not ground truth. Do not commit.

---

### Task 12: Find and fix the cause of the smooth surface (added after Task 11)

**Question:** is the Task 11 surface annotation error (the hypothesis) or something in the chain? Post-hoc diagnostics record rungs `CAUSE` and `SIGNFIX`; only the Java change and its tests are production. Analysis scripts have no `--selftest`; their outputs are the evidence.

**Files:**
- Create: `validation/gslc_parity/leg_split.py` (has `--selftest`), `leg_split2.py` (has `--selftest`), `leg_split3.py`, `plot_ifg_compare.py`
- Create: `validation/graphs/trad_s1_bg_deramped.xml`, `validation/drivers/ven_bg_deramped.ps1`, `validation/drivers/ven_signfix.ps1` (`-Ramp`, `-Legacy`)
- Modify: `sar-op-insar/src/main/java/eu/esa/sar/insar/gpf/InterferogramOp.java`, `ReleaseNotes.md`
- Create: `sar-op-insar/src/test/java/eu/esa/sar/insar/gpf/TestCarrierDiffSign.java`

- [ ] **Step 1: Deramped legs from the classical chain.** `ven_bg_deramped.ps1` runs Back-Geocoding with `disableReramp` and `outputDerampDemodPhase` (graph `trad_s1_bg_deramped.xml`) to get the classical carrier-free legs and model phases (`ven_bgd_stack.dim`).

- [ ] **Step 2: Compare legs and carrier models (`leg_split.py`, `leg_split2.py`).** GSLC `azimuthCarrierPhase` vs the classical model phase at each map pixel's source position: the same function to <0.01 rad. GSLC carrier-free legs vs the classical deramped legs: concentration 0.994 (reference) / 0.959 (secondary), mean phase within 0.001 rad. Integer-shift scan: both legs peak at 0 lines and 0 pixels (rules out registration). **Conclusion: the chains differ neither in model nor in registration.**

- [ ] **Step 3: Locate the surface (`leg_split3.py raw ...`).** Raw interferograms (carrier added back, before any reference phase is removed) agree at 0.956; the reference phase removed by each chain agrees only with the opposite add-back sign. Swapping the sign at cell level predicts concentration 0.001 -> 0.900 and gy ratio 37.9 -> 0.77.

- [ ] **Step 4: Read the sign in the code.** `GSLCGeocodingOp` restores the carrier as `convergentI = I*cos + Q*sin`, i.e. multiplies by exp(-j*phi), so a carrier-free leg is data x exp(+j*phi). `InterferogramOp.addGslcCarrierModelDiff` assumed `truth x exp(-j*m)`. Why the tests missed it: synthetic pairs have m_ref = m_sec (identical geometry) and the closure of three pairs telescopes the error away.

- [ ] **Step 5: Controlled experiment, then the default.** Change the operator (first as an opt-in switch, rebuilt for real with `ven_signfix.ps1`, then as the default):

```java
static final double CARRIER_DIFF_SIGN = readCarrierDiffSign(System.getProperty("gslc.carrierDiffSign"));

static double readCarrierDiffSign(final String property) {
    final String p = property == null ? "" : property.trim();
    return ("+1".equals(p) || "1".equals(p)) ? 1.0 : -1.0;   // -1 default; +1 = legacy
}

public static double carrierDiffAngle(final double mRef, final double mSec) {
    return CARRIER_DIFF_SIGN * (mSec - mRef);
}
// in addGslcCarrierModelDiff:
row[x] += carrierDiffAngle(refT.getSampleDouble(xx, yy), secT.getSampleDouble(xx, yy));
```

Correct the Javadoc premise (legs carry `truth x exp(+j*m)`), log a LEGACY warning when `+1` is selected, add a ReleaseNotes entry (behaviour change).

- [ ] **Step 6: Tests.** `TestCarrierDiffSign`: default -1; only an explicit `+1`/`1` selects legacy; malformed -> -1; and a convention test with DISTINCT m_ref/m_sec (legs carry truth x exp(+j*m); the corrected add-back must return the truth phase). Run `mvn -o -q -pl sar-op-insar install -DskipTests`, then `mvn -o -pl sar-op-insar test -Dtest='TestCarrierDiffSign,TestCoherenceWindowMeters,GSLCEquivalenceVerdictTest,TestGslc*,CreateStack*Test'` (61 tests, 0 failures, 2 skipped by design).

- [ ] **Step 7: Rebuild and re-score.** `ven_signfix.ps1` (ramp off) then `run_r5b.py r5b ... R5b-signfix`: link 0.949, concentration 0.961, gx / gy 0.87 / 0.86, all four gates PASS (as shipped: 0.236 / 1.18 / 37.9). `ven_signfix.ps1 -Ramp` then `R5b-signfix-ramp`: concentration 0.334, FAIL: with the sign right the ramp degrades agreement. `plot_ifg_compare.py <dir>` writes the three phase images used in the note and the slides.

- [ ] **Step 8: Record and write up.** Record the rows (rungs `CAUSE`, `SIGNFIX`), add note section 12, revise sections 1-3 and 11, update memory.

- [ ] **Step 9: Hand off with the caveats:** one pair (S1A x S1C, 3 bursts, ETAD on); other pairs, ETAD-off, Etna and full scenes are not re-run; the Javadoc's former "sign pinned empirically" claim is unreconciled; `subtractResidualRamp`'s rationale needs re-evaluation; older recorded GSLC results are affected. Do not commit; exclude the tracked `__pycache__/*.pyc` files.

---

## Should-do (only if time remains; not tasked here)

- **R6 geophysical equivalence:** unwrap both chains' interferograms over the coseismic lobes and compare LOS displacement with the published Venezuela solutions (geohazards-tep.eu, jgi-inc.com). Needs an unwrapper choice and a defined comparison region — write a small design first.
- **ETAD-off-in-both null:** removes the route-asymmetry confound behind the ~20-fringe smooth plane seen in F−TRAD. It is `ven_gslc.ps1` on the ETAD-off fixtures plus the classical chain on the same, then `run_r5.py r5`. Task 7's ETAD-off pairs already give the GSLC side for S1A × S1C (`clos_AC`).
- **Geolocation offset map:** the amplitude cross-correlation of R4 run per tile in steep-gradient zones to attribute part of the residual to a geolocation convention (δ·∇φ). `offset_map.median_offsets` already returns per-call medians; a per-tile map is a small extension.
- **R1 perturbed-orbit variant** and the flat-earth bilinear-error measurement on a high-B⊥ pair.

## Self-review against the spec

| Spec requirement | Where |
|---|---|
| §5 Rule 1 (TOPSAR-Split only), Rule 3 (crop-validity gate first, against `_ifg_deb_flt`-style radar ifg, burst 10 excluded) | Tasks 1, 3 |
| §5 Rule 5 per-source teardown | Tasks 7, 9 |
| §7 R1 | Task 8 (null-baseline form — reduction stated) |
| §7 R2 (both chains' retention; generator floor first) | Task 8 (R2-lite — reduction stated) |
| §7 R3 (ETAD-off triple, labelled) | Task 7 |
| §7 R4 (per-leg, bistatic asymmetry modelled and sign-checked) | Task 6 |
| §7 R5 (versus measured classical floor; ground-corrected coherence; ramps off) | Tasks 3, 4, 5 |
| §13 P7 (inverse-geocode floor), P8 (classical reproducibility floor), P9 (log-line engagement assertions) | Tasks 4, 5, and the `Assert-Log` calls in `ven_gslc.ps1` / `ven_classical.ps1` |
| §7 targeted closer: burst-seam gate | Task 9 |
| §9 scorecard and error-budget table | Tasks 2, 9 |
| §7 R6, R7; Napa, Bam; PAZ, NISAR; the deck | Not in this plan (stated in Scope) |

**Type and name consistency checked:** `record(...)` argument order is identical in `budget.py`, `run_r5.py`, `run_r4.py`, `synth_eval.py` and `closure.py`; `closure.multilook(z, ml_az, ml_rg)` is called with (rows, cols) everywhere (`crop_gate.py`, `run_r5.py`, `closure.run`); `geo_to_index` returns `(rows, cols)` in `radar_domain.py` and `run_r4.py`; the lobe constants exist only in `synth.py`.

