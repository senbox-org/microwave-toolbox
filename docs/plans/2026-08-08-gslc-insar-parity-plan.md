# GSLC InSAR Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** GSLC InSAR produces interferograms provably as good or better than traditional InSAR, for S1 TOPS and stripmap missions (ERS, ASAR, Capella), enforced by a contract-test pyramid whose top gate is numeric equivalence with the classical chain.

**Architecture:** Three test layers. Layer 1 (per-leg contracts) pins each GSLC's internal invariants: geometry consistent with the interferogram's removal model, and phase faithful to the source SLC. Layer 2 (pair contracts) pins the stack: registration field correctness and the interferogram's residual after all removals. Layer 3 (cross-chain equivalence) runs both chains on the same inputs and asserts coherence parity and a bounded smooth phase difference. Every gate is a number derived from measurements already made on real pairs; a failure at layer N with layers <N green localizes the defect by construction.

**Tech Stack:** Java (JUnit, file-gated + `LongTestRunner`/`-Denable.long.tests` convention per repo standard), Python analysis harness under `validation/` (numpy, memmap ENVI readers — the proven scripts from the ERS/Venezuela investigations), gpt/maven-exec drivers.

## Global Constraints

- No `git commit` / `git push` by agents — the user reviews and commits (user's global rule).
- File-gated tests must `assumeTrue(fixture.exists())` — CI without fixtures skips, never fails.
- Long-running tests use `@RunWith(LongTestRunner.class)` + `-Denable.long.tests=true` (ceres-core test-jar convention already in the repo).
- Fixture paths via `TestData.inputSAR` / explicit `E:/Output/...` real-pair gates; no new absolute paths inside `src/` outside file-gated test constants.
- Every operator change lands with a unit test in the same task; every gate threshold cites the measurement that set it.
- Maven runs MUST start from `E:\ESA\microwave-toolbox` (recurring cwd trap: `mvn -pl ...` from elsewhere fails with "project not in reactor").

## Established facts these gates encode (do not re-derive)

| Fact | Measured | Where recorded |
|---|---|---|
| Ifg residual = (4π/λ)·Δr·(registration-phase error − applied field) ≈ 1756 rad/SLC-px | ERS tandem, v0→v2 progression | memory `gslc-ers-offset-field` |
| Range mispairing costs no phase (baseband); azimuth mispairing costs 2π·f_dc·dt·δ ≈ −1.23 rad/px (ERS) | y-structure vanished with azimuth field | same |
| Block-CC field precision floor ≈ ±0.05 px; degree≥2 auto fields overfit and inject ±90–175 rad | v4/v4b bisect | same |
| ERS residual after affine field + quadratic ramp + 1-D range profile: smooth 2-D “arc” surface ~130 rad, absent in trad | v3/v5 vs trad-TC diff (conc ≈ 0.33) | same |
| Trad control on identical inputs: gx/gy 10–30× smaller than GSLC residual | co-lattice gradient split | same |
| S1 TOPS: carrier-free + per-burst ramp + burst lock reach annotation-level agreement; open: ~20-fringe F−TRAD route plane, ETAD symmetry | Venezuela campaign | memory `gslc-zero-coherence-root-finding` |
| Leg-A (reference, standalone grid) contract: R exact to 0.2 mm constant, faithful phase conc 0.875, no ramp | ers_faithful.py | same |
| Leg-B (secondary under lock+field params) contract: **NEVER VERIFIED** — experiment in flight 2026-08-08 (`ers_legB_verify.py`, products `ERS2_std_GSLC`/`ERS2_lock_GSLC`) | pending | Phase 0 below |

---

## Phase 0 — Decision experiment (IN FLIGHT): secondary-leg contract under production parameters

**Files:**
- Analysis (exists): `E:\ESA\snap_tmp\ers_legB_verify.py`
- Products (building): `E:/Output/ers/ERS2_std_GSLC.dim` (done), `E:/Output/ers/ERS2_lock_GSLC.dim` (running)

**Interfaces:**
- Produces: the branch decision for Phase 3 (`BRANCH-A` = lock/field path defect found; `BRANCH-B` = both legs clean).

- [ ] **Step 1: Wait for `ERS2_lock_GSLC` build; run `python E:\ESA\snap_tmp\ers_legB_verify.py` from `E:\ESA\snap_tmp`.**
- [ ] **Step 2: Read the verdict.**
  - STANDALONE and LOCKED both must show `R_py − R_snap` constant (|std| < 0.01 m, per-column means within ±0.005 m of each other) and faithful concentration > 0.8 with column-bin phase trend < 0.3 rad.
  - If LOCKED fails while STANDALONE passes → **BRANCH-A** (defect in the explicit-grid/lock/offset-field path of `GSLCGeocodingOp`). Phase 3A applies.
  - If both pass → **BRANCH-B** (the arc surface lives between the products/paths not yet instrumented). Phase 3B applies.
  - If BOTH fail → the leg-A test parameters masked a general defect: re-run leg-A verification with `pixelSpacingInDegree` explicitly set (same script, ERS-1 inputs) before branching.
- [x] **Step 3: Record the verdict.** **VERDICT (2026-08-08): BRANCH-B.** Standalone AND locked+field leg-B builds both pass: `R_py − R_snap` = −0.00023 m constant (zero drift), faithful concentration 0.770, swath phase trend ≤ 0.26 rad. The lock/offset-field rebuild path is clean. Phase 3B applies, amended with a decisive Step 0: the **cross-chain leg-split experiment** — terrain-correct the trad chain's *warped secondary complex SLC* (and the master SLC) to the GSLC map grid; compute per-leg cross-chain phase differences δ_A = angle(TC(SLC_A)·conj(GSLC_A)) and δ_B = angle(TC(warp(SLC_B))·conj(GSLC_B)). The arc surface must equal δ_A − δ_B (removal models cancel in the cross-chain diff); whichever leg carries it names the mechanism — and if it is a TC-vs-GSLC mapping difference of the SAME product, the problem is chain-internal and fixable rather than data-intrinsic.

---

## Phase 1 — Layer-1 contract tests in the repo (per-leg invariants, automated)

Today these contracts exist only as throwaway Python. They become permanent, file-gated JUnit tests so any regression or new mission failure localizes immediately.

### Task 1.1: `GSLCGeometryContractTest` — GSLC positioning ≡ interferogram removal model

The invariant that kills phase ramps is **internal consistency**: the slant range GSLC bakes into its restore phase must equal what `InterferogramOp`'s jlinda-based removal will compute at the same map pixel. Testing GSLC-vs-jlinda directly checks the exact pairing the interferogram relies on (an error common to both cancels and is allowed to).

**Files:**
- Create: `sar-op-sar-processing/src/test/java/eu/esa/sar/sar/gpf/geometric/GSLCGeometryContractTest.java`
- Test fixtures (gate list, `assumeTrue` each): ERS-1 `E:/Output/ers/ERS-1_..._Orb.dim`, ERS-2 `..._Orb.dim`, Capella `TestData.inputCapella_StripmapSLC`, S1 SM `TestData.inputS1_StripmapSLC`.

**Interfaces:**
- Produces: `static double[] measureGeometryDrift(Product gslcWithSimPhase, MetadataElement srcAbs)` returning `{meanResidualMeters, columnDriftMeters, rowDriftMeters}` — reused by Task 1.3 and Phase 3.

- [ ] **Step 1: Write the failing test.** For each gated fixture: run `GSLCGeocodingOp` with `saveSimulatedUnwrappedPhase=true`, `saveDEM=true` on a **subset** (use `SubsetOp` 2048×2048 around scene centre to keep runtime < 3 min per mission). For a 12×12 grid of valid output pixels: reconstruct `R_snap = simPhase·λ/4π`; compute `R_jlinda` via `new SLCImage(absSrc, src)` + `new Orbit(absSrc, 3)`: `xyz = Ellipsoid.ell2xyz(lat, lon, hDem)` (lat/lon from the product geocoding, h from the DEM band) then `orbit.xyz2t(xyz, slcimage).x * Constants.lightSpeed`. Assert: `|mean(R_jlinda − R_snap)| < 0.05 m` (bistatic-free comparison — document that a CONSTANT offset up to a few metres is allowed if it is constant: assert `max − min < 0.01 m` across the grid, i.e. **drift < 1 cm ⇒ < 2 rad of ramp**; the constant is absorbed per leg).

```java
// core assertion
final double[] r = measureGeometryDrift(gslc, srcAbs);
assertTrue("R drift across scene must stay below 1 cm (2 rad); got " + r[1] + "/" + r[2],
        Math.abs(r[1]) < 0.01 && Math.abs(r[2]) < 0.01);
```

- [ ] **Step 2: Run: `mvn -pl sar-op-sar-processing test -Dtest=GSLCGeometryContractTest` — expect FAIL (method not written).**
- [ ] **Step 3: Implement `measureGeometryDrift` + the fixture loops (minimal).**
- [ ] **Step 4: Run again — expect PASS on all present fixtures (leg-A already measured exact; if ERS-2 fails here, that duplicates Phase 0's verdict — cross-check).**
- [ ] **Step 5: Hand to user for commit review.**

### Task 1.2: `GSLCFaithfulPhaseTest` — output phase ≡ source SLC phase at the read position

**Files:**
- Create: `sar-op-sar-processing/src/test/java/eu/esa/sar/sar/gpf/geometric/GSLCFaithfulPhaseTest.java`

**Interfaces:**
- Consumes: same subset-GSLC products as Task 1.1 (share a `@BeforeClass` builder).
- Produces: `static double faithfulConcentration(Product gslc, Product srcSlc, double[] rangeField, double[] azField)` — reused in Phase 3.

- [ ] **Step 1: Write the failing test.** Port `ers_faithful.py`'s corrected method (memory-documented): select output pixels whose read position `rg = (R_snap − nearEdge)/Δr + field(rg,az)` has `|frac| < 0.02`; scan the azimuth offset over `[0, 8]` px in 0.25 steps (absorbs the bistatic convention); phase test statistic `conc = |mean( GSLC·conj(SLC[az,rg]) · exp(j·(4πΔr/λ)·frac) )|`. Assert `conc > 0.7` AND per-column-bin mean phase varies < 0.5 rad across the swath (the per-leg ramp bound: 0.5 rad ≪ the 600-rad class of defect this catches).

```java
assertTrue("faithful concentration " + conc, conc > 0.7);
assertTrue("per-leg phase trend across swath " + trend + " rad", Math.abs(trend) < 0.5);
```

- [ ] **Step 2: Run — expect FAIL (helpers missing). Step 3: implement. Step 4: run — PASS. Step 5: hand off.**

### Task 1.3: TOPS leg contract (S1 2-burst fixture)

**Files:**
- Modify: `sar-op-sar-processing/src/test/java/eu/esa/sar/sar/gpf/geometric/GSLCTopsInSarTest.java` (add two `@Test`s)
- Fixture: the Venezuela 2-burst split products `E:/Output/gslcdiag/m.dim`, `s.dim` (file-gated; rebuildable via `scratchpad/split.xml` recipe documented in memory).

- [ ] **Step 1: Write failing tests reusing Task 1.1/1.2 helpers on a TOPS GSLC (carrier-free default): geometry drift < 1 cm; faithful concentration > 0.7 (with the TOPS deramp model applied at read positions — reuse `computeDerampDemodPhaseAt` via the diag bands `-Dgslc.diagGeometry=true` which the TOPS path fills).**
- [ ] **Step 2–5: fail → implement → pass → hand off.**

---

## Phase 2 — Layer-3 cross-chain equivalence harness (the acceptance gate)

### Task 2.1: `validation/gslc_equivalence.py` — one entrypoint, numeric verdicts

**Files:**
- Create: `validation/gslc_equivalence.py` (consolidates the proven ad-hoc code from `ers_final_diff.py`, `ers_night1.py`, `ers_blockcc.py`)
- Create: `validation/README-equivalence.md` (usage + gate table)

**Interfaces:**
- CLI: `python gslc_equivalence.py <gslc_ifg.dim> <trad_ifg_tc.dim> [--coh-gslc BAND --coh-trad BAND]`
- Exit code 0 = all gates pass; prints a machine-parsable `GATE <name> PASS|FAIL <value> <threshold>` per line.

- [ ] **Step 1: Write the script with these gates (thresholds from the measured campaign):**

```text
GATE common-grid-valid-cells   >= 100000        (sanity: enough overlap)
GATE phase-residual-conc       >= 0.85          (|mean phasor| of GSLC·conj(trad) after removing ONE fitted plane;
                                                 measured: broken=0.32..0.36, trad self-noise ceiling ~0.9)
GATE residual-rms-rad          <= 1.0           (per-100m-cell wrapped residual after plane removal, coherent cells)
GATE gx-median-ratio           <= 2.0           (median |gx| of GSLC ifg / median |gx| of trad ifg on the common grid;
                                                 measured broken: 10..30)
GATE gy-median-ratio           <= 2.0
GATE coherence-parity          >= -0.02         (mean coh(GSLC) - mean coh(trad) over common coherent cells)
```

- [ ] **Step 2: Run it on the CURRENT ERS v5 vs `trad_dinsar2_TC` — expect FAIL on phase gates (conc 0.33) and PASS on coherence: this is the executable statement of the open problem.** Record output in the README.
- [ ] **Step 3: Wrap in `LongTestRunner`-gated JUnit `GSLCEquivalenceLongTest` (sar-op-insar) that shells the script per configured mission pair when `-Denable.long.tests=true` and the fixture pairs exist.**
- [ ] **Step 4: Hand off.**

### Task 2.2: Classical control recipes per mission (reproducible truth)

**Files:**
- Create: `validation/graphs/trad_ers_control.xml` (the WORKING single-graph classical chain: CreateStack→Cross-Correlation→Warp→Interferogram — CC+Warp MUST share one graph, GCPs are in-memory; TopoPhaseRemoval as a second step from jlinda-nest; TC with `-PsourceBands=i,q -PoutputComplex=true`)
- Create: `validation/graphs/trad_s1_control.xml` (Back-Geocoding→ESD→Interferogram→Deburst→TopoPhaseRemoval→TC, from the Venezuela campaign recipes)
- Create: `validation/README-controls.md` documenting the three TC traps discovered (Phase-virtual crash, Intensity collapse, complex flag).

- [ ] **Step 1: Write both graphs with exact parameters. Step 2: Execute the ERS one; verify non-zero output and gate `phase-residual-conc >= 0.85` of trad-vs-ITSELF re-run (determinism sanity). Step 3: hand off.**

---

## Phase 3 — Close the stripmap gap (branch on Phase 0)

### Task 3A (BRANCH-A): fix the lock/field-path defect

- [ ] **Step 1: Localize.** With Phase 0's failing measurement in hand, bisect the locked build's parameter set one parameter at a time (five builds max, each on the 2048² subset from Task 1.1 so each is ~3 min): `pixelSpacingInDegree` alone → `+Y` → `+mapProjection` → `+rangeOffsetPoly` → `+azimuthOffsetPoly`. The first configuration that reproduces the drift names the defective code path.
- [ ] **Step 2: Write the failing unit test** in `GSLCGeometryContractTest` (Task 1.1 helper, fixture = the defective configuration) asserting drift < 1 cm.
- [ ] **Step 3: Fix the defect in `GSLCGeocodingOp` (exact edit depends on Step 1's finding; candidates by prior analysis: `CRSGeoCodingHandler` standard-grid snap under explicit spacing, offset-field application interacting with `isValidCell` bounds, or degree→meter conversions feeding geometry rather than labels).**
- [ ] **Step 4: Contract test passes; rerun the full ERS chain (stack + ifg with ramp+profile); run Task 2.1 harness vs trad — ALL GATES must pass. This is the plan's primary success path for stripmap.**
- [ ] **Step 5: Hand off with the before/after harness outputs.**

### Task 3B (BRANCH-B): principled 2-D residual closure

**Step 0 VERDICT (2026-08-08, leg-split experiment):** amplitude block-CC of each chain's mapping of the SAME product shows leg A (master) agreeing to ±0.02 px while leg B (secondary) disagrees by −0.31..+0.11 px smoothly across the scene — the arc surface exactly (±0.3 px × ~500 rad/map-px). The classical warp (degree-2 polynomial on ~400 fine-registration GCPs, coherenceThreshold 0.4) captures registration structure our affine 65-block field cannot. **Task 3B is therefore concrete: fit a DEGREE-2 offset field from the classical-strength GCP set** — the same estimator the trad control demonstrably succeeds with on this pair — instead of the (correctly rejected) degree-3 block-CC fit. Guards: convert GCP offsets to needs via the jlinda geometric difference (as the block method does); robust trim; require ≥ 150 surviving GCPs for degree 2 else fall back to the affine block field; cross-check gate: the fitted field must agree with the block-CC medians within 0.1 px. Acceptance: leg-split re-run gives |dx_B| < 0.05 px everywhere; Task 2.1 equivalence gates pass on ERS.

- [ ] **Step 1: Quantify the floor formally:** extend `TestGslcErsBiasFieldGate` with a bootstrap of the block-CC noise (resample blocks, refit) → publish σ(field) per axis. This makes “cannot do better via registration” a number in a test.
- [ ] **Step 2: Implement `residualRamp2D` (expert opt-in, default OFF):** thin-plate/tensor-spline surface on an 8×8 node grid fitted to the SAME block gradients used by the ramp (reuse `GslcRampBlock`), ridge-regularized so its capacity stays below deformation scales (document: absorbs everything smoother than ~1/4 scene wavelength — UNSAFE for coseismic work; parameter description must say so).
- [ ] **Step 3: Unit test: synthetic 2-D smooth surface recovery < 2 rad; deformation-preservation test: a synthetic 10-km Gaussian deformation lobe must survive with > 80% amplitude.**
- [ ] **Step 4: ERS chain rerun + Task 2.1 harness: `phase-residual-conc >= 0.85` with `residualRamp2D=true`; document in explainer §6.1 that archive VMP pairs require this option (replacing the “prefer classical” language with the conditional recommendation).**

---

## Phase 4 — S1 TOPS parity closure

### Task 4.1: Run the equivalence harness on the Venezuela S1 pair

- [ ] **Step 1: Rebuild the S1 chain end-to-end on current code (`S1A_cd` recipes from memory §8c: carrier-free + outputPhaseTerms + option-1 ETAD both legs + burst lock) and the classical control (Task 2.2 graph). Run Task 2.1 harness.**
- [ ] **Step 2: Triage each failing gate to its named open item:** the ~20-fringe `F−TRAD` smooth plane (ETAD route asymmetry — compare `resamplingImage=true` bake-in vs classical grid consumption on one subswath with ETAD OFF in both as the null test), and seam steps via `validation/compare/seam_steps.py <ifg> 6 0.3` (gate: worst step < 0.3 rad, from the carrier-diff prediction).
- [ ] **Step 3: For each triaged item, write the failing gated test first (seam gate as a JUnit shelling seam_steps; ETAD symmetry as `TestInterferogramEtadSymmetry` extension), then fix, then harness re-run.**

### Task 4.2: TOPS regression pyramid completeness

- [ ] **Step 1: Promote the burst-lock, carrier-free convention, and per-burst ramp validations into the `LongTestRunner` suite** (currently file-gated one-offs; wire them to the 2-burst fixture so they run in ~10 min total).
- [ ] **Step 2: Add the Layer-1 contracts (Task 1.3) for the second platform (S1C leg) — cross-platform is where TOPS defects historically appeared.**

---

## Phase 5 — Permanence

- [ ] **Task 5.1: CI wiring:** all Layer-1/2 unit tests unconditional; file-gated real-pair tests skip cleanly; `GSLCEquivalenceLongTest` under `-Denable.long.tests=true` documented in `validation/README-equivalence.md` with expected runtimes (ERS ~2.5 h, S1 ~4 h full-chain).
- [ ] **Task 5.2: Docs:** explainer §7 gains the gate table + latest harness numbers per mission; §6.1 updated per Phase 3 outcome; tutorial troubleshooting rows for the three classical-control traps.
- [ ] **Task 5.3: Memory + ReleaseNotes updated with final state.**

---

## Self-review notes

- Spec coverage: “tests that get to the bottom of it” → Layers 1–2 localize by construction (Phase 1), “solution as good or better than traditional” → Phase 2 harness is the executable definition; “S1 TOPS as well as stripmap” → Phases 3 (stripmap) and 4 (TOPS) share the same harness and per-leg contracts.
- The single largest unknown (arc surface origin) is deliberately front-loaded as Phase 0 with a decision branch, because its outcome determines whether stripmap parity is a bug-fix (3A) or a bounded-model feature (3B).
- Thresholds all trace to measurements recorded in project memory; none are aspirational.
