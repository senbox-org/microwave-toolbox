# GSLC InSAR Parity Campaign — Design

**Date:** 2026-09-18
**Status:** design approved; amended 2026-09-18 after adversarial review
**Supersedes framing in:** `docs/plans/2026-08-08-gslc-insar-parity-plan.md` (Phases 0–2 landed; Phase 3 closed as a data limitation; Phase 4 never executed)

> **Amendment note.** A four-batch adversarial review verified every claim in the first
> draft against current source and current disk. Four premises failed. This revision
> records what was measured, not what was remembered. Claims corrected by the review are
> marked **[verified]** with the file:line that settles them. Every fact below was read from
> code or product metadata, not from project memory.

---

## 1. Purpose and the claim we must defend

Demonstrate to ESA that GSLC (geocode-first) InSAR produces interferograms equivalent to the classical (radar-geometry coregistration) chain, for **S1 IW TOPS and for stripmap**, across several missions, with every number traceable to a measurement.

The claim is **tiered**, strongest first:

| Tier | Claim | Evidence | Needs a classical control? |
|---|---|---|---|
| 1 | GSLC is **self-consistent** | Phase closure on a real acquisition triple; synthetic pairs with analytically known truth | **No** |
| 2 | GSLC is **geophysically equivalent** | Unwrapped LOS displacement agrees with the classical chain and with published solutions | Yes + published |
| 3 | GSLC is **numerically equivalent** | Per-pixel gate table, measured against each source's own classical reproducibility floor | Yes |

Tier 1 leads deliberately. It is immune to the objection that the classical chain is itself imperfect — an objection §4 class C takes seriously, and which the review sharpened rather than removed.

**Execution order.** Three sources get the full depth — **S1 Venezuela** (IW TOPS), **S1 Napa** (stripmap), **ASAR Bam** (stripmap) — plus synthetics derived from the first two. PAZ and NISAR are breadth confirmation and run **last**. Rationale in §8.

**Nothing in the ladder runs until §13's pre-flight fixes land.** The review established that several harness components would produce confidently wrong numbers today.

## 2. Established facts — do not re-derive

Measured, and re-verified against current source during the review.

| Fact | Status |
|---|---|
| GSLC output phase = the SLC's own annotation-position phase at the read index; flatten anchors at geometric R and cancels restore exactly | holds |
| Sub-pixel **range** mispairing costs no phase (compressed pulse is baseband, `E[s(k+d)s*(k)]` is real) | holds |
| **Azimuth** mispairing does cost phase: `2*pi*f_dc*dt*d_az` (about −1.23 rad/px for ERS, `f_dc` about −330 Hz) | holds |
| Block-CC registration precision floor about **±0.05 px**; degree ≥ 2 fields on 65 blocks overfit and inject ±90–175 rad | **holds only for `estimateSlcBiasByBlocks`**, the *fallback* estimator. See §4 class C1. |
| DEM quality is the dominant rough-terrain lever: SRTM-3Sec to Copernicus 30 m took Etna GSLC coherence 0.188 to 0.424 (classical 0.421) | holds |
| Carrier-free TOPS output required; `outputAzimuthCarrier` defaults `false` | **[verified]** `GSLCGeocodingOp.java:224-230` |
| Raw i/q interpolation with flatten applied **after** the kernel, in both SM and TOPS branches | **[verified]** SM `:2113-2121`, TOPS `:2505-2514` |
| Adjacent TOPS bursts see the overlap through about 4 kHz-disjoint Doppler bands, so a mixed-burst strip is inherently incoherent | holds |
| ETAD `resamplingImage=false` (option 2) is a silent no-op for the GSLC chain; option 1 is required | holds |
| Standard-grid snap hardcoded on; `alignToStandardGrid` removed as a GSLC parameter | **[verified]** `GSLCGeocodingOp.java:1596-1600` |
| Post-fix GSLC chain matches ISCE3 `geocodeSlc` conventions | holds |

### 2.1 The master equation

```
ifg_residual = (4*pi/lambda) * dR * (true_relative_misregistration - applied_offset_field)
             ~ 1756 rad per SLC pixel   (ERS, C-band)
```

GSLC pays the **full carrier** for registration and annotation error. The classical chain pays only the **flat-earth gradient** (about 0.1 rad/px) — roughly 10^4 immunity.

**Corollary, and the actual specification of this work:** for a GSLC interferogram residual below 1 rad, the two legs' annotation-to-phase consistency must hold to about 6e-4 SLC pixels. Whether a given mission's products meet that is an empirical question per mission, and is what this campaign measures.

## 3. What went wrong with the campaign so far

Five structural problems this design exists to correct.

1. **We debugged only the two hardest datasets available.** ERS tandem VMP and S1A x S1C Venezuela. **No run has ever been made on an easy pair.** The acceptance harness has been executed against real data exactly once, on a product already known to be broken.
2. **Phase 4 (S1 TOPS closure) was never executed.** The burst-lock verdict was never read.
3. **The classical chain is treated as truth but has never been audited** — and worse, **two different classical chains have been used interchangeably**. See §4 class C.
4. **The comparison metric carries known confounds** — comparison domain, look counts, ETAD route asymmetry.
5. **The acceptance thresholds have no provenance.** **[verified]** No command, product, date, or captured output anywhere in the repo produced `0.85` or the "~0.9 trad self-noise ceiling"; `validation/README-equivalence.md:213-223` further admits they "were never measured with that formula." They entered as prose in a task brief and were then cited as evidence.

## 4. Error inventory — both sides

### Class G — GSLC, per leg

| ID | Source | State |
|---|---|---|
| G1 | Bistatic azimuth residual | **Corrected by review.** The operator does not determine whether the IPF applied a bulk correction — it trusts `bistatic_correction_applied`, which `sar-io/.../sentinel1/Sentinel1Level1Directory.java:519` sets to `1` **unconditionally**, never reading the product. For Napa this happens to be right: both annotations carry `bistaticDelayCorrectionApplied=true` at IPF 002.34 **[verified]**. Correct by coincidence, not by verification — add an assertion, do not assume. |
| G2 | DEM error causing differential read-position error between legs | Dominant on rough terrain. Copernicus 30 m largely closes it at scales of 100 m and below. |
| G3 | Annotation phase-vs-position scale `eps(R, az)` | Mission- **and processor-vintage** dependent. Fatal for VMP ERS. Unquantified for ASAR — and Bam's two frames were made by **different ASAR processor versions (3.06 vs 3.07S00)** with different `PROC_STAGE`, so this term is *within* the Bam pair, not just across missions. |
| G4 | SM Doppler centroid for deramp | Data-driven Madsen lag-1 estimator is live and SM-only. **Silent size gate:** `estimateFdcFromData` returns `null` below **1539 azimuth lines** with no log **[verified]** `GSLCGeocodingOp.java:1020-1022`. Collides with §5 Rule 2 — see the constraint there. |
| G5 | TOPS deramp/reramp, carrier-free convention, burst selection, burst-overlap Doppler mixing | Burst-valid-time lock is landed and reachable, forwarded at both `CreateStackOp` sites. Its only integration test is triple-`assumeTrue`-gated. No-burstId products fall back to **positional** burst matching, which can mispair silently. `extractBurstIds` has no test. |
| G6 | Interpolation kernel and flatten ordering | Fixed and verified in both branches. TOPS ordering has no hermetic test. |
| G7 | Map grid: Nyquist, standard-grid snap, anisotropic cells | Done and verified. |
| G8 | Offset-field estimation | See C1 — the shipping primary estimator is **degree 2**, not affine. |
| G9 | ETAD application route (option-1 bake-in) | Works. Option 2 is a silent no-op — a user-facing footgun to document. |
| G10 | Offset-field source-rectangle widening | **Dead code.** `margin += ceil(maxAbsField)` (`:789`, in `getMetadata`) is overwritten by `margin = getMargin()` (`:599`, in `initialize`) **[verified]**. Field-shifted lookups near tile edges can fall outside the fetched source rect and are silently returned as no-data. Matters for stripmap offset fields of ~2 px. |

### Class P — stack and pair

Grid lock, integer co-lattice, bias estimator, TOPS burst-valid-time lock — all landed. Two corrections:

- **`applyMasterGridLockParams` reads exact affine i2m scales only for an ANGULAR CRS** **[verified]** `CreateStackOp.java:1883-1892`. For a projected (UTM) master it falls back to differenced geo positions — precisely the cancellation loss the exact-affine path exists to avoid. The whole method is wrapped in `catch (Throwable)` and degrades silently to an unlocked slave grid (`:1911-1914`). No test.
- **TOPS gets constants only.** `CreateStackOp.java:2010-2013` returns `SlcBiasEstimate(off[0], off[1], null, null)` for TOPS pairs — ESD residual, both polynomials null. Venezuela has no offset field at all.

### Class I — InterferogramOp, GSLC mode

| ID | Source | State |
|---|---|---|
| I1 | Reference-phase surface subsampling | **Prediction retracted.** The first draft predicted a live defect. `pixelTopo` defaults **ON** and genuinely applies `dphidh * (hPx - hInterp)` **[verified]** `InterferogramOp.java:3355-3356`, `:3491`. What remains genuinely unmeasured is the **bilinear error of the flat-earth part** of the 10-px node surface, which on a high-`B_perp` pair is bounded by nothing in the tree. And the existing fix has **zero tests** — `GSLCRefPhaseProbeTest` contains no assertions. |
| I2 | Coherence estimation in map geometry | `cohWinSizeMeters` divides by `range_spacing`, which in radar geometry is **slant** **[verified]** `InterferogramOp.java:700`. 100 m → 43 px → ~150 m on the ground. The operator's own text claims it "yields a window that is square on the ground whatever the geometry" (`:684-686`); it does not. A ~1.5× systematic bias favouring the radar-geometry side. |
| I3 | `residualRamp` family | **RESOLVED BY DELETION 2026-09-22.** The smooth surface these models existed to remove was an inverted carrier-difference add-back sign in `InterferogramOp`; with the sign corrected they only removed signal (Venezuela concentration against the classical chain 0.961 off / 0.334 on; ERS-1/2 tandem Etna 0.566 / 0.343 per tile and 0.569 / 0.030 over the scene). `subtractResidualRamp`, `residualRampDegree`, `residualRampRangeProfile`, `residualRamp2D` and `residualRamp2DNodes` were removed from the operator — they had never shipped. See `docs/gslc-parity/etna-ramp-ab.md`. Burst seam-step removal survives as `subtractSeamSteps` (TOPS only, OFF by default, **stays OFF for every headline result**). |
| I4 | Default parameters | `subtractTopographicPhase` defaults **false** **[verified]** `:129`. Any rung testing topographic removal must set it explicitly or it tests nothing. |

### Class C — the classical chain

**Rewritten. The first draft's C1 was wrong.**

| ID | Finding |
|---|---|
| C1 | **Both chains apply a data-driven degree-2 offset field on stripmap.** The *primary* GSLC bias estimator `estimateSlcBiasByGcpField` fits `fitPolyOffsetField(..., 2, 100)` **[verified]** `CreateStackOp.java:2785-2786`, derived from a CPM warp that is itself degree 2 (`:2724`). The `wantDegree = 1` affine cap is inside `estimateSlcBiasByBlocks` (`:2249`), the *fallback*. So the hypothesis "classical absorbs deformation, GSLC does not" is **false**. R2 must measure absorption in **both** chains; the expected answer is not that one absorbs and the other does not. |
| C2 | ESD absorbs a constant azimuth shift per burst overlap. TOPS only, weak. |
| C3 | **Two different classical chains have been used interchangeably.** `validation/graphs/trad_ers_control.xml` is CC + Warp(order 2) **[verified]**. But `GSLCEquivalenceLongTest.java:70` points at `trad_dinsar2_TC.dim`, described in `README-equivalence.md:176` as **DEM-Assisted-Coregistration**, and **no graph producing it exists in the repo**. Every recorded number (conc 0.355, gx-ratio 24) came from a control of undocumented provenance. **Each source must name its control explicitly; the two are not interchangeable.** |
| C4 | `BackGeocodingOp` applies **no** bistatic residual (zero matches in `sar-op-sentinel1/src/main`) while GSLC does. Common-mode in an interferogram, so R5 is unaffected — but **not** in R4's per-leg difference. See §7 R4. |
| C5 | `WarpOp` silently switches to a different warp estimator on CPM failure — `catch (Exception) { inSAROptimized = false; ... }` at `WarpOp.java:678-682`, WARNING only. A C1 measurement could silently characterise a different algorithm. |
| C6 | Deburst, Terrain-Correction of wrapped phase, TopoPhaseRemoval model differences. |

### Class M — comparison methodology

- **`residual-rms-rad` is computed about ZERO**, not about the mean **[verified]** `gslc_equivalence.py:322-326`, so it fails a constant phase offset that the harness's own docstring declares an allowed datum ambiguity. The sibling `diff_vs_trad.py:168` does it correctly.
- **The seam meter in the first draft was the wrong one.** `seam_steps.py` never calls `sys.exit`, prints prose, and updates `worst` only inside the above-threshold branch, so "worst step < 0.3 rad" is unsatisfiable by construction. `seam_steps_guided.py:3-5` documents that the blind meter "on a coseismic scene flags the earthquake" — and all three deep sources are coseismic.
- **Band selection is unguarded outside one script.** `_declared_bands()` filtering exists only in `gslc_equivalence.py`; `compare/*` scripts glob `*.hdr` and take the first match, selecting `i_*` and `q_*` independently and picking an arbitrary polarisation.
- Look-count mismatch; speckle decorrelation between ETAD-resampled and non-resampled products (judge via multilooked diffs only).
- **Inverse-geocoding relocates the resampling onto the measurand.** See §6.

## 5. Fixture strategy — hard rules

### Rule 1 — TOPS: `TOPSAR-Split` only. No `SubsetOp`, ever.

TOPS fixtures come **exclusively** from `TOPSAR-Split` selecting a subswath and whole bursts. `SubsetOp` must not appear in any TOPS graph. A range-cropped Etna fixture previously drove classical coherence from 0.367 to 0.088, and a range-cropped split makes `TOPSAR-Deburst` fail outright, killing the classical control.

### Rule 2 — Stripmap: crop azimuth, never range. Minimum 1539 lines.

Every recorded defect is range-varying, so cropping range discards the axis where the errors live. **New constraint from the review:** a stripmap azimuth crop must retain **at least 1539 lines**, or `estimateFdcFromData` silently returns null and the fixture gets different deramp behaviour from the full scene — making the fast fixture a different experiment. Assert the log line that proves which Doppler table was used.

### Rule 3 — Crop-validity gate, run before anything is built on fast numbers.

S1 Venezuela retains a full-scene classical control. **Corrected target:** the reference is `E:/Output/trad/..._Stack_ifg_deb_flt.dim` — the **debursted radar-geometry** product, which carries `i_ifg`/`q_ifg` **[verified]**. The `_TC.dim` product has only `Phase_` + `coh`, no i/q, and carries an **undeclared orphan** `Intensity_*.img` in its `.data` directory. Since §6 fixes the comparison domain as radar, `_flt` is the right reference anyway.

Caveat: the control stack is 10 S1A bursts with a 9-burst S1C resampled onto it, so **burst 10 has no secondary data**. Any full-scene statistic must exclude it. Bursts 4–6 are unaffected.

### Rule 4 — Stripmap subset metadata consistency.

Assert `first_line_time`, `slant_range_to_first_pixel` and tie-point grids were updated coherently, via a `GSLCGeometryContractTest`-style drift check against the parent.

### Rule 5 — Per-source teardown (new).

The campaign generates ~360 GB against **294 GB free**, and the overrun is dominated by the synthetic ladder. Each source's stage products are **deleted once its gate values are recorded**; only the gate outputs, the error-budget row, and the figures are retained. Sources run strictly one at a time.

### Known fixture traps

- **ETAD auto-download cannot work on a burst subset** (a ~9 s window against a ~30 s frame returns zero matches, reported as "ETAD product not found"). Pass `-PetadFile` explicitly.
- **The source product name must retain the original S1 stem**, or `ETADUtils.getProductIndex` cannot parse timestamps.
- **ETAD must use option 1.**
- **Chaining `Interferogram` onto an in-memory `CreateStack`→`GSLC` graph livelocks.** Materialize the stack to disk first.
- **Maven runs must start from `E:\ESA\microwave-toolbox`.**
- **A fix in `sar-op-insar` is invisible** to a `sar-op-sar-processing` test until `mvn -pl sar-op-insar install -DskipTests`. Gate on a log line proving new code ran.
- ~~Surefire double-execution~~ — **already fixed**; the second execution now lives in a profile activated by `!test` **[verified]** `sar-op-sar-processing/pom.xml:177-183`. No workaround needed.

## 6. Harness architecture

One Python package, `validation/gslc_parity/`.

| Component | New | Purpose |
|---|---|---|
| `inverse_geocode.py` | yes | Sample a map-grid complex interferogram at each radar pixel's lat/lon onto the radar grid. |
| `synth.py` | yes | Rung A and Rung B synthetic pair generation (§7). |
| `closure.py` | yes | Triple difference over a common coherent mask. |
| `budget.py` | yes | Decomposes a measured residual into named terms; emits the scorecard row. |
| `gates.py` | refactor | Today's `gslc_equivalence.py`, retargeted to radar domain, **with §13's P1 fix applied**. |
| `compare/*` | reuse **after P4** | `metrics.py`, `seam_steps_guided.py`, `diff_vs_trad.py`, `render_*.py`. |

**Comparison domain: radar — with a measured floor.** The GSLC interferogram is inverse-geocoded onto the classical chain's native radar grid, so the classical reference stays unresampled and the three Terrain-Correction traps are sidestepped.

**The review's objection is accepted and handled.** `diff_vs_trad.py:11-13` records the harness's own rule: resampling either product "injects exactly the interpolation error being measured," and it refuses fractional offsets above 0.02 px. Inverse-geocoding is an arbitrary-fractional resample at every pixel — it moves the interpolation error from the reference onto the measurand and removes that guard. The choice stands, because the alternative resamples the thing we are calling truth, but it is now conditional on **P7: the inverse-geocode's own phase-error floor must be measured** (round-trip a GSLC interferogram map→radar→map and characterise the phase cost on wrapped fringes) **before any concentration number derived from it is quoted.** Gates are expressed relative to that floor.

**Chain drivers**: one explicit linear script per source, modelled on `validation/experiment_2x2.ps1`. Strictly one heavy job at a time.

## 7. The rung ladder

| Rung | Proves | Control | Gate | Runtime |
|---|---|---|---|---|
| **R0** Screening sweep | Where we stand | existing | measurement only | ~1 day |
| **R1** Synthetic, Rung A | The **removal models** are exact | none | RMS of `recovered - phi_true` below **0.05 rad**, no spatial structure | minutes |
| **R2** Synthetic, Rung B | The **full chain** is exact; deformation retention of **both** chains | none | within **2x the measured generator floor**; retention reported per chain | ~20 min |
| | *Amended 2026-09-22:* R2's third arm (GSLC with the residual ramp ON) is withdrawn — the parameter was removed. The Venezuela measurement that retired it is kept as a result: retention classical 1.0000, GSLC ramp-off 1.0000, GSLC ramp-on 0.9764. | | | |
| **R3** Phase closure | GSLC is **self-consistent** | **none** | closure RMS below gate, no spatial structure | ~1 h |
| **R4** Per-leg cross-chain split | *Which leg* carries any residual | classical stack | per-leg delta below **0.1 px** equivalent, **after removing the modelled bistatic asymmetry** | ~1 h |
| **R5** Cross-chain equivalence | Numeric parity in radar domain | classical ifg | concentration versus the **measured per-source classical reproducibility floor**; gradient ratios ≤ 2; coherence parity at **ground-corrected** cell size | ~2 h |
| **R6** Geophysical equivalence | The **product** agrees | classical + published | LOS displacement RMS within stated tolerance | ~1 h |
| **R7** External reference | Agreement with a third party | JPL products | our GSLC vs JPL L2 GSLC **on EPSG:32611, 10.0 x 5.0 m posting**; our ifg vs GUNW | ~1 h |

### R1 — synthetic Rung A (phase injection)

`SLC_B = SLC_A * exp(j*phi_true)` with annotations for a perturbed orbit;
`phi_true = flat_earth(B_perp) + topo(DEM) + deformation_lobe`. No resampling, no noise term.

**Reframed.** The first draft expected this to expose the I1 defect. That defect is fixed, so R1's value is now: (a) **regression-guarding an untested fix** — `pixelTopo` has zero tests today; (b) measuring the **flat-earth bilinear error** of the 10-px node surface, which is genuinely unmeasured and unbounded on a high-`B_perp` pair.

**Must set `-PsubtractTopographicPhase=true`** — it defaults false.

0.05 rad remains generous for a deterministic, noise-free model comparison.

### R2 — synthetic Rung B (re-annotated resample)

Resample `SLC_A` onto a genuinely perturbed secondary geometry plus DEM and a known deformation lobe; write consistent annotations.

**Reframed per C1.** Both chains apply a degree-2 data-driven field on stripmap, so this rung measures **how much of the known lobe each chain retains**, reported per chain, with no prior that one is better. That number is the first error bar either chain has ever had, and it is what §9's error budget needs.

**The generator's noise floor is measured first**, via a zero-perturbation generation. The gate derives from it.

### R3 — phase closure

`phi_AB + phi_BC + phi_CA` over a common coherent mask, on S1A / S1C / S1D.

**Configuration decision.** The S1D ETAD product **does not exist** — the cache holds S1A and two S1C slices, and a search of `~/.snap` and `E:\` finds no S1D ETAD anywhere **[verified]**. R3 therefore runs **ETAD-off on all three legs**, which is internally consistent and requires no acquisition. This is a *different configuration* from the pairwise rungs and is labelled as such in every output. The S1A x S1C pairwise rungs keep ETAD option-1, since the matching classical control on disk was built with it. If the S1D ETAD is later obtained, R3 is re-run ETAD-on as a confirmation.

### R4 — per-leg cross-chain split

Inverse-geocode each GSLC leg onto the classical stack's radar grid; form `delta_A`, `delta_B`.

**Correction from the review.** `BackGeocodingOp` applies no bistatic residual while GSLC does (C4). The term cancels in an interferogram but **not** in a per-leg difference, where it appears as a range-varying azimuth offset of about 0.17 ms ≈ 0.08 px. R4 must **model and remove this asymmetry explicitly** before interpreting any residual, or it will report the correction as a defect.

### R5 — cross-chain equivalence, and its own ceiling

**The per-source classical reproducibility floor is mandatory, not a refinement.** The historical `0.85` and "~0.9" have no provenance and were not measured with the shipping formula, so they are **withdrawn as priors**. R5 first re-runs the classical chain under an equivalent-but-different parameterisation (different CC window / GCP count) to establish that source's floor, then reports GSLC-vs-classical against it. The claim is *"GSLC agrees with the classical chain as closely as the classical chain agrees with itself."*

Each source **names its classical control explicitly** (CC+Warp or DEM-Assisted) per C3.

Coherence parity uses **ground-range-corrected** window sizing per I2.

The `residualRamp*` options no longer exist (I3). `subtractSeamSteps` stays OFF for every headline
result; a rung that enables it must say so and must not be compared against a rung that did not.

### Targeted closers

- **ETAD-off-in-both null** — removes the route-asymmetry confound behind the ~20-fringe smooth plane.
- **Burst-seam gate** — via **`seam_steps_guided.py`** (carrier-band-guided seam location), not the blind meter, with a parseable `GATE` line and an exit code added (§13 P2).
- **Geolocation offset map** — amplitude-CC offset map GSLC versus classical in steep-gradient zones.

## 8. Source matrix

### Tier D — deep

| Source | Mode / band | Fixture | Rungs | Character |
|---|---|---|---|---|
| **S1 Venezuela** | IW TOPS, C | `TOPSAR-Split` IW3 **bursts 4–6**, full range width, burstId-matched. ETAD explicit option 1 for pairwise; **ETAD-off for the R3 triple**. Cop30 staged GeoTIFF. | R0–R6, incl. **R3 closure** | S1A 23Jun / S1C 24Jun / S1D 30Jun 2026, **all relative orbit 106, all IPF 004.03** [verified]. Coseismic, cross-platform, 1-day. Hosts the crop-validity gate. |
| **S1 Napa** | Stripmap (S1 beam), C | **Per-leg geometry-derived azimuth windows** — see the constraint below. Full range width. | R0–R2, R4–R6 | 20140807 (abs. orbit 1835) x 20140831 (abs. orbit 2185), **both relative orbit 15, both IPF 002.34, B_perp ≈ 127 m** [verified]. Brackets the 24 Aug 2014 South Napa M6.0 event. |
| **ASAR Bam** *(RUN 2026-09-22 — R5 measured, see the scorecard; the block-CC cross-check guard FAILED as this spec predicted)* | Stripmap, C | Full range width, azimuth window over Bam. **`CreateStack` auto path mandatory.** | R0–R2, R4–R6 | `1PNUPA` 03Dec2003 x `1PXPDE` 11Feb2004, **both REL_ORBIT 00120, both IMS/COMPLEX, IS2, V/V, descending** [verified]. Brackets the 26 Dec 2003 M6.6 event. |
| **Synthetic** | both | derived from the Venezuela and Napa fixtures | R1, R2, plus a synthetic triple as a closure harness self-test | Known truth; one TOPS and one stripmap synthetic, both from S1A. |

### Tier L — later, breadth confirmation only

| Source | Mode / band | Fixture | Rungs | Character |
|---|---|---|---|---|
| **PAZ Mojave** | Stripmap, X | Full range width, ~25% azimuth | R0, R4–R5 | 20180520 x 20180531 SSC, **same beam `strip_007`, incidence 31.02° both, 11-day** [verified]. The least problematic source in the matrix. |
| **NISAR** | Stripmap, L | as delivered | R0, R5, **R7** | RSLC pair + JPL L2 GSLC + GUNW, all cross-referenced and consistent [verified]. |

### Per-source constraints established by the review

**Napa — the legs cannot share a line window. [verified]** Both are slice 1 of 4, but the datatakes start 8.99 s apart in ANX-relative time = **16,809 azimuth lines**. The epicentre sits at line 5430 in leg A and line 22239 in leg B. Footprints overlap ~67%, and the **northern half of Napa Valley is outside leg A entirely** (Calistoga is in B only). Consequences:
- The fixture window is **derived per product from geometry**, never from a shared line range.
- Napa city is only ~3138 lines (~11 km) from leg A's start-of-scene edge, so an azimuth window centred on the deformation field is truncated at the top in A. Size the window from A's limit.
- Raster sizes differ: A is 21733 x 52999, B is 21754 x 52975.
- Incidence 19.57–26.20° is the **lowest in the matrix**, so foreshortening in the flanking ranges loads the G2 (DEM) term precisely on the clean control. Note it in the error budget.

**Bam — role changed, and it carries two confounds. [verified]** The frames differ not only by PAC (UPA vs PDE) but by **ASAR processor version (3.06 vs 3.07S00)** and `PROC_STAGE` (N vs X), with `PRODUCT_ERR=1` on both. That is a G3 annotation-vintage mismatch *inside the pair*, on top of the known ~8 px azimuth misregistration. Bam is therefore **not** the clean stripmap parity case; it is the **bias-estimator stress case with published truth**. Napa carries stripmap parity.
- `CreateStack` **auto path mandatory** — the both-GSLC path yields zero coherence.
- **Its ~8 px sits exactly on `FIELD_MAX_RESIDUAL_PX = 8`** (`CreateStackOp.java:2402`). Above 8.0 every block is rejected, `MIN_FIELD_BLOCKS = 12` fails, and the block cross-check **silently becomes unavailable** — the GCP field is then accepted with no independent veto. Assert the cross-check ran.
- Bias-estimation failure logs a warning and **keeps zero bias** (`:629-633`) — a Bam run can complete "successfully" having done nothing. Asserting the estimated bias in the log is **mandatory**, not advisory.

**Venezuela — confirmed, with two notes. [verified]** Bursts 4–6 carry identical `burstId`s (225587/225588/225589) in all three products, and their geolocation grids agree to <0.0005°. The 10-vs-9 framing difference is at the tail. Note that **S1C is 23767 samples / 1503 lines per burst** against S1A and S1D's **23665 / 1504** — "full range width" is not the same number on both legs.

**`E:/Output/gslcdiag/{m,s}.dim` is S1A x S1D, 2 bursts — not the Tier D fixture. [verified]** `s.dim` reports `MISSION = SENTINEL-1D`, 30-JUN-2026. It is IW3 bursts 4–5, 7-day baseline, no ETAD. Usable as a fast smoke fixture; it is **not** the S1A x S1C 3-burst fixture and must never be substituted for it.

**NISAR — feasible, with caveats the first draft missed. [verified]** The L2 GSLC's start time matches RSLC #2 exactly and the GUNW references the RSLC pair, so R7 is possible. But these are **simulated sample products** (`productVersion 0.1.0`, `absoluteOrbitNumber 0`, `orbitType Custom`, UAVSAR-derived over Los Angeles) — "third-party reference" is true, "real mission data" is not. Polarisation is `[HH]` only despite `DHDH` in the filename, and the GSLC's `productLevel` says `L1`. **R7 requires our GSLC on EPSG:32611 with 10.0 x 5.0 m anisotropic posting** to match JPL's grid.

### Accepted limitations

- **Closure (R3) rests on Venezuela alone**, and runs ETAD-off. Napa and Bam have two scenes each. Real stripmap closure would need RS2 Manitoba's five FQ10W acquisitions or a third S1 SM Napa-track scene.
- **Capella is excluded** — geocodes empty in this environment regardless of DEM.
- **SAOCOM is excluded** — the `Interferometric-Pair/Slave` directory is empty.

## 9. Deliverables

1. **Scorecard** — source by rung, each cell a PASS/FAIL with the measured value and the provenance of its threshold. Generated by the harness.
2. **Error budget table** — the measured residual per source decomposed into named terms, each with a magnitude. This answers "identify all sources of error on both sides" in an auditable form. **It must include the R2 deformation-retention figure for both chains**, which is the campaign's most novel number.
3. **Technical note and deck** — via the `creating-snap-docs-and-decks` skill.

**Figure discipline.** Fringe figures must be **full-resolution crops, never decimated full-scene views** — display decimation aliases hundreds of flat-earth cycles into apparent noise, which is what produced the original "GSLC has dense extra fringes" screenshot.

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Burst-subset gates differ from full-scene gates | Campaign speed collapses | Crop-validity gate runs **first**, against `_ifg_deb_flt.dim`, excluding burst 10 |
| Stripmap azimuth crop below 1539 lines | Fast fixture silently becomes a different experiment | Rule 2 minimum; assert the Doppler-table log line |
| Bam completes with zero bias, silently | A headline result that measured nothing | Assert the CC bias and the block cross-check in the log; treat absence as failure |
| Disk overrun (~360 GB vs 294 GB free) | First source strands the rest | Rule 5 per-source teardown; sources run one at a time |
| Inverse-geocode phase-error floor unmeasured | Every R4/R5 number is uninterpretable | P7 blocks the ladder |
| Classical control ambiguity (CC+Warp vs DEM-Assisted) | Incomparable numbers across runs | Each source names its control; historical 0.355 is not comparable to a CC+Warp rebuild |
| Synthetic generator noise floor unmeasured | R2's gate is meaningless | Zero-perturbation generation first |
| Several days of wall-clock | Schedule | Ladder front-loads Tier 1 — R1–R3 need no classical chain |
| ERS remains out of scope | An open ESA question stays open | Documented as a legacy-VMP limitation with the measurement that proves it |

## 11. Out of scope

- **ERS tandem VMP** — closed as a legacy-VMP limitation: the north arc train is annotation-phase error with no feature-registration counterpart (row-resolved needs clean to 0.0096 px azimuth, 0.0150 px range), invisible to amplitude CC and unfixable by registration. Reopen only if the 2006 PGS-reprocessed frames are ordered.
- **Capella and SAOCOM** — see §8.
- **Wet-troposphere LUTs (RAiDER/ERA5)** and split-spectrum ionosphere.
- **Algorithmic gap-reduction via FM-rate mismatch** — measured sub-pixel, repeatedly disproven as the lever.

## 12. Decisions log

| Decision | Choice | Rationale |
|---|---|---|
| Acceptance bar | Tiered: self-consistency, geophysics, numeric | Tier 1 is immune to "is the classical chain right?" |
| Sources, Tier D | S1 Venezuela (TOPS), S1 Napa (stripmap), ASAR Bam (stripmap), synthetic | Mode isolated with processor held constant; published truth on two; one closure triple |
| Sources, Tier L | PAZ Mojave, NISAR | Breadth confirmation and a third-party reference |
| ASAR scene | **Bam** | Coseismic with published truth; 70-day baseline |
| Bam's role | **Bias-estimator stress case**, not the clean control | Two processor-lineage confounds plus `PRODUCT_ERR=1` |
| Clean stripmap control | **S1 Napa** | Same satellite, same track, same IPF, B_perp ≈ 127 m, real published signal |
| Napa fixture | **Per-leg geometry-derived windows** | The legs are offset by 16,809 azimuth lines |
| R3 configuration | **ETAD-off on all three legs** | The S1D ETAD does not exist on disk; an ETAD-off triple is internally consistent |
| Synthetic generator | Rung A phase injection + Rung B re-annotated resample | Rung A isolates removal models; Rung B tests the full chain |
| Comparison domain | **Radar** — inverse-geocode GSLC, **conditional on P7** | Reference stays unresampled; the relocated interpolation error must be measured, not assumed |
| Sequencing | Ladder with an upfront screening sweep | Every number attributable |
| TOPS fixtures | `TOPSAR-Split` subswath + whole bursts only; **no `SubsetOp`** | Range cropping collapsed classical coherence and breaks `TOPSAR-Deburst` |
| Stripmap fixtures | Full range width, azimuth crop only, **≥1539 lines** | Defects are range-varying; below 1539 lines the Doppler estimator silently disengages |
| R1 purpose | **Regression-guard an untested fix + measure flat-earth bilinear error** | The predicted I1 defect is already fixed |
| R2 purpose | Deformation retention of **both** chains | Both apply a degree-2 data-driven field; neither is a priori neutral |
| Acceptance threshold | **Per-source measured classical floor** | 0.85 and "~0.9" have no provenance and were not measured with the shipping formula |
| Seam meter | **`seam_steps_guided.py`** | The blind meter flags earthquakes; all deep sources are coseismic |
| Disk | Per-source teardown | ~360 GB needed against 294 GB free |

## 13. Pre-flight fixes — required before the ladder produces trustworthy numbers

| # | Fix | Why |
|---|---|---|
| P1 | `residual-rms-rad` → RMS about the **mean** (`gslc_equivalence.py:322-326`) | Currently fails a constant offset the harness documents as allowed |
| P2 | Seam gate → `seam_steps_guided.py`, add parseable `GATE` output and an exit code | Blind meter has no exit code and an unsatisfiable gate; it flags earthquakes |
| P3 | Ground-range-correct `cohWinSizeMeters`, or compensate in the harness | ~1.5× bias favouring the classical side in R5 |
| P4 | `_declared_bands()` filtering + explicit polarisation selection across `compare/*` | Orphan `.img` files and arbitrary polarisation selection, unlogged |
| P5 | Add `TOPSAR-Split` to `trad_s1_control.xml` | Throws "Split product is expected." as written |
| P6 | Repoint `GSLCEquivalenceLongTest` off the deleted ERS tree; assert **gate outcomes**, not line count | Currently passes whether every gate FAILs or PASSes |
| P7 | **Measure the inverse-geocode phase-error floor** (map→radar→map round trip on wrapped fringes) | Gates R4 and R5 entirely; §6 |
| P8 | Measure the per-source classical reproducibility floor | Replaces the unsourced 0.85 / ~0.9 |
| P9 | Assert bias estimation and the block cross-check actually engaged | Silent-zero completion on Bam |
| P10 | Restore a TOPS faithful-phase contract (un-`@Ignore` with a bounded runtime, or replace) | `GSLCTopsInSarLongTest.java:158` — the contract is enforced nowhere |
