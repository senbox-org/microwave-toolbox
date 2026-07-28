# Plan A — All-GSLC (NISAR-style) InSAR readiness

**Goal:** make the "every acquisition is a GSLC" workflow — the NISAR/OPERA architecture — a
first-class, tested path, not just the one our fixtures happen to exercise.

**Status of the premise (verified 2026-07-27, not assumed):** the all-GSLC path already *works* for
a single pair. Every fixture validation this week ran `CreateStack(mgcf, sgcf)` — two independently
produced GSLCs, no raw secondary — through to a gap-closed interferogram. `CreateStackOp`'s
geocoded-offset path loops over all secondaries and the per-axis lattice guard validates each one.
So this plan is about the three things that *don't* yet work, each verified in code below.

---

## A1 — N interferograms from one all-GSLC stack — **DONE (2026-07-27)**

Implemented, with one addition beyond the plan and one incidental bug found. Pairing is now by
polarisation rather than list position (a multi-pol stack could otherwise cross VV with VH whenever
band order differed between reference and secondaries), an unmatchable secondary is a loud error, and
`setupGSLCReferencePhase` validates that every paired secondary has its own `SLCImage`/`Orbit` —
previously only pair 0 was used, so missing `Secondary_Metadata` went unnoticed and would now have
surfaced as an NPE mid-tile.

**Incidental find, fixed:** `computeGSLCCoherence`'s extended source rectangle (`cohRect`) was never
clamped to the image, and neither was the window loop. At the first tile the origin goes negative;
whether that throws or is silently border-extended depends on the source image implementation, which
is why it had not shown up on file-backed products. Both are now clamped and border pixels are
estimated over the truncated window.

Tests: `TestGslcMultiSecondary` (4 tests — 3 secondaries → 3 ifgs + 3 coherence bands each carrying
its own phase plane; multi-pol pairing with deliberately reversed band order so positional pairing
would fail; loud failure on an unmatchable polarisation; single-secondary regression). Full
`sar-io` + `sar-op-insar` suite: 133 run, 0 failures.

**Verified defect (for the record):** `InterferogramOp.initializeGSLC()` line 393:
```java
final int numPairs = Math.min(refIBands.size(), secIBands.size());
```
Reference bands are detected by name tag (`ref`/`mst`) and secondaries by (`sec`/`slv`). A stack of
1 reference + N secondaries yields `refIBands.size() == 1`, so `numPairs == 1`: **only the first
secondary is interfered**; secondaries 2..N are silently dropped from the output. Same limitation on
the coherence bands.

**Fix:** in GSLC mode, pair the single reference against *each* secondary — `numPairs =
secIBands.size()`, with `gslcReferenceI[p]` repeating the reference band. The per-pair machinery
downstream is already N-safe: `gslcSecSLCMap`/`gslcSecOrbitMap` are keyed per secondary band,
`computeGslcReferencePhase` takes the secondary's `SLCImage`/`Orbit`, and `gslcRampCoef` is already
a per-pair array. Band naming already appends the secondary date (fixed 2026-07-26), so N pairs get
distinct names for free.

**Tests:**
- Synthetic: 1 reference + 3 secondaries with *different* known phase planes → assert 3 ifg band
  pairs + 3 coherence bands exist, and each carries its own plane (extends
  `TestGslcResidualRamp`'s synthetic-stack builder).
- Guard: reference-band-count > 1 (a mis-tagged stack) must fail loudly rather than silently
  truncate.

**Effort:** ~½ day including tests. **Risk:** low, contained to GSLC mode.
**Value:** turns "run N two-product stacks" into one stack — and is a prerequisite for A3.

---

## A2 — NISAR L2 GSLC read-through

**A `NisarGSLCProductReader` already exists** (188 lines, `sar-io/.../nisar/subreaders/`) and does
more than expected: it stamps `gslc_output_flattened=false` and resolves `gslc_source_slc_path`
(HDF5 `sourceData` attribute, then a `_L2_/_GSLC_ → _L1_/_RSLC_` filename fallback), and reads
`grids/frequencyA` polarisation bands. But the read-through to our InSAR chain has **four specific
gaps**, all verified:

1. **`is_terrain_corrected` is never set** — `grep -rn is_terrain_corrected sar-io/.../nisar/`
   returns nothing. This is the primary GSLC-detection flag for both
   `CreateStackOp.isGeocoded()` and `InterferogramOp`'s GSLC-mode auto-detect.
2. **The geocoding is a `TiePointGeoCoding`, not a `CrsGeoCoding`** (`NisarSubReader`
   `createTiePointGridFrom1DProjected` / `createTiePointGridFromUTM`). That defeats
   `isGeocoded()`'s *fallback* too, and `applyMasterGridLockParams` reads
   `CrsGeoCoding.getMapCRS()`/`getImageToMapTransform()` — so grid-lock and the exact-affine
   step read (added 2026-07-26) silently no-op.
   **Consequence if unfixed:** CreateStack treats NISAR GSLCs as slant-range, routes to
   `computeTargetSecondaryCoordinateOffsets_Orbits()` — exactly the "garbage XYZ from map
   coordinates" path its own comment warns about — and `InterferogramOp` runs the classical
   flat-earth *polynomial* path on geocoded data. Both fail quietly.
3. **No `gslc_azimuth_carrier` stamp** (the attribute is new as of 2026-07-26). The missing-stamp
   fallback is `true` (legacy = carrier restored). Harmless for all-GSLC stacking (the stamp is
   only consulted when auto-*building* a secondary), but wrong in principle: NISAR is not TOPS, so
   there is no beam-steering azimuth carrier at all — the honest value is a third state.
4. **Unverified:** whether the NISAR abstracted metadata carries what `InterferogramOp`'s GSLC
   reference-phase path needs (orbit state vectors — `addOrbitStateVectors` *is* called, good —
   plus the timing/wavelength fields `SLCImage` reads). Cannot be settled by inspection; needs a
   real product.

**Status 2026-07-27: A2.1, A2.2, A2.3 DONE; A2.4 still blocked on a real product.**
- **A2.1 done** — `is_terrain_corrected = 1` stamped in `NisarGSLCProductReader`.
- **A2.2 done** — `NisarSubReader.setProjectedCrsGeoCoding()` builds an exact affine `CrsGeoCoding`
  for 1-D projected grids from the EPSG code + pixel-centre coordinate arrays, preferred over the
  tie-point path; the lat/lon tie-point grids are still added for operators that look them up by
  name, but no longer overwrite the exact geocoding. Falls back to tie-points (with a warning) for
  a non-uniform lattice or ascending northings, rather than emitting a wrong or flipped grid.
  Tests: `TestNisarProjectedGeoCoding` (3 tests) pins the pixel-centre / `referencePixel 0.5`
  convention the reader depends on — a half-pixel error here would have been silent and plausible —
  and that per-axis rectangular steps survive.
- **A2.3 done, differently than proposed.** Rather than widen a boolean to three states across
  consumers, the reader stamps `gslc_azimuth_carrier = "none"` and
  `CreateStackOp.readMasterAzimuthCarrierState` recognises `none`/`n/a` explicitly. The resulting
  behaviour (build no carrier) was already what `Boolean.parseBoolean` happened to produce; it is now
  intentional and documented instead of accidental.
- **Checked, deliberately not changed:** GCOV/GUNW/GOFF also omit `is_terrain_corrected` and do not
  override `addAbstractedMetadataHeader` at all. They are geocoded too, so the flag is arguably
  right for them — but GCOV is backscatter and GUNW an unwrapped interferogram, neither feeds the
  GSLC InSAR path, and setting it could change behaviour in terrain-correction operators. Left as a
  separate decision rather than changed blind. **A2.2's `CrsGeoCoding` improvement does reach them**,
  since it lives in the shared `NisarSubReader`.

**Remaining work:**
- **A2.1 (done)** Set `is_terrain_corrected = 1` in `NisarGSLCProductReader` (and check GCOV/GUNW
  subreaders for the same omission).
- **A2.2** Build a **`CrsGeoCoding`** when the grid is 1-D projected with a known EPSG (the common
  NISAR case) — the coordinates are a regular lattice, so the affine transform is exact; keep the
  tie-point path as the irregular-grid fallback. This is the highest-value item: it unlocks
  grid-lock, the lattice guard, the pixel-relative pass-through eps, and the exact-affine step read
  all at once.
- **A2.3** Extend the carrier stamp to three states (`true` / `false` / `n/a`) or add
  `gslc_sensor_mode`, so non-TOPS GSLCs are represented honestly rather than defaulting to legacy.
- **A2.4** Read-through test on a real NISAR L2 GSLC pair: open → assert `is_terrain_corrected`,
  `CrsGeoCoding`, orbit SV count, `SLCImage` constructible → `CreateStack` (2 GSLCs) → lattice guard
  passes → `Interferogram` GSLC mode auto-detects → coherence band is non-degenerate. **File-gated**;
  if no NISAR GSLC pair is available, a synthetic HDF5 fixture matching the NISAR group layout is
  the fallback (moderate extra effort — decide when we know product availability).

**Effort:** A2.1 ~1 h; A2.2 ~½–1 day (the EPSG→CRS plumbing exists in `NisarSubReader` already);
A2.3 ~2 h + touching the two consumers; A2.4 ~½ day given a product, +1–2 days if a synthetic
fixture is needed.
**Blocking dependency:** a real NISAR (or NISAR-simulated) L2 GSLC pair. Everything except A2.4 can
land without it.

---

## A3 — GSLC-aware multi-reference / SBAS time series

**Verified gap:** `MultiMasterInSAROp` builds its own `SLCImage`/`Orbit` per band
(lines 515–528), computes interferograms internally (`computeIfgPhasorAndIntensities`), and its only
input validation is `checkIfSARProduct()` + `checkIfCoregisteredStack()` — **no
`is_terrain_corrected` check and no geocoded branch**. So a GSLC stack fed to it today would get the
slant-range flat-earth/topo treatment on map-geometry data, and none of the GSLC-mode handling
(`computeGslcReferencePhase`, the residual-ramp estimator, the derotated coherence).

This is the **real work item behind "NISAR-style processing"** — bigger than CreateStack, which is
already there.

**Approach (decide at design time, both viable):**
- **(a) Share the machinery.** Extract `InterferogramOp`'s GSLC reference-phase + residual-ramp code
  into a reusable helper (e.g. `GslcInterferometry` in `sar-op-insar`) and call it from both
  operators. Best long-term; one implementation, one place to fix.
- **(b) Delegate.** Have `MultiMasterInSAROp` detect a GSLC stack and form its per-pair
  interferograms by invoking `InterferogramOp` internally (the SPI-invocation pattern CreateStack
  already uses for `Back-Geocoding`/`ESD`). Less refactoring, more runtime overhead, and it inherits
  A1 automatically.

**Also needed regardless:** GSLC-awareness in whatever the chain does next — `SBASInversionOp`
consumes the multi-reference output, so verify it is geometry-agnostic (likely, since it works on
phase stacks) rather than assuming.

**Tests:** synthetic geocoded 3-epoch stack with known per-epoch phase → assert the multi-reference
network reproduces it; plus a real-fixture pair-count/geometry sanity test.

**Effort:** ~2–4 days depending on (a) vs (b). **Risk:** moderate — this operator has its own
conventions (`[[project_multireference_insar_addelevation]]`), so regression coverage on the
existing slant-range path matters as much as the new branch.

---

## A4 — BIOMASS (P-band stripmap) as the second GSLC mission

BIOMASS is the other mission that makes geocode-first InSAR attractive, and it is *easier* than
Sentinel-1 in the ways that hurt us this week — but it inverts two assumptions the current code and
docs quietly carry. All numbers below are read from the real product annotation
(`L1A_INT_phase/BIO_S1_SCS__1S_20170101T060307...annot.xml`), not inferred.

**Verified from the product:**

| Quantity | BIOMASS SM | Sentinel-1 IW | Consequence |
|---|---|---|---|
| Carrier frequency | 435 MHz (λ = 0.689 m) | 5.405 GHz (λ = 0.0555 m) | 4π/λ = **18.2 rad/m** vs 226.5 — **12.4× less geometry-sensitive** |
| One 2π fringe | 0.345 m LOS | 0.028 m LOS | geocoding/DEM error budget is 12.4× looser |
| Range spacing (slant) | 19.81 m | ~2.33 m | |
| Azimuth spacing | 6.71 m | ~13.9 m | **fine axis is azimuth, not range — inverted** |
| Range bandwidth | 6.0 MHz (7.57 total) | ~56 MHz | slant-range resolution ~25 m |
| Azimuth bandwidth | 849 Hz | ~330 Hz | |
| Mode | stripmap | TOPS | **no beam-steering azimuth carrier** |

**What this means, in order of importance:**

1. **The rectangular-cell axis orientation flips.** In *ground* geometry (the geometry map cells live
   in) S1 IW is range-fine/azimuth-coarse at roughly 3:1; BIOMASS is azimuth-fine/range-coarse at
   roughly 6:1 (ground-range spacing ≈ 19.81/sin θ ≈ 40 m at a nominal θ = 30° — **θ was not read
   from this fixture**, so treat the ratio as indicative). So rectangular cells matter *more* for
   BIOMASS than for S1, and in the opposite direction: `dy < dx` rather than `dx < dy`.
   The good news: the criterion implemented for ESA's non-square-pixel request —
   `dx ≤ 1/(B_rg·cosθ + B_az·sinθ)`, `dy ≤ 1/(B_rg·sinθ + B_az·cosθ)` — is symmetric in the two
   axes and has no hardcoded assumption about which is finer, so it already handles this.
   **Action:** audit the explainer/tutorial prose for any statement that range is the finer axis, and
   confirm the default square-spacing rule (`max` of the two spacings) is documented as
   "coarser axis wins" rather than "azimuth wins".
2. **Ionosphere becomes first-order.** Total-electron-content phase scales as 1/f, so P-band is ~12×
   more affected than C-band; ionospheric phase screens are a *dominant* error term for BIOMASS
   InSAR, not a refinement. Directly relevant: the `applyIonosphericCorrection` stub was **removed as
   out-of-scope on 2026-07-25**. That was the right call for S1, but it is the wrong end state for
   BIOMASS, and the split-spectrum method the correction would use needs range bandwidth we have
   (6 MHz total across two sub-looks is thin but the mission is designed for it). **Action:** record
   this as a known BIOMASS prerequisite rather than silently carrying an S1-shaped decision forward.
   It is out of scope for this plan; it should not be out of *sight*.
3. **The TOPS azimuth-carrier problem does not exist here** — no beam steering, so no per-burst
   quadratic carrier, so no `outputAzimuthCarrier` ambiguity and no disjoint-Doppler-band obstacle to
   within-acquisition verification (the thing that made root-cause avenue #2 physically impossible for
   S1 *is* testable at BIOMASS). The stripmap `buildFdcPerSourceColumn` deramp path is the one that
   applies, and **`addDopplerCentroidCoefficients` is called by the BIOMASS reader** (verified), so
   the inputs that path needs are present.
4. **The residual-ramp estimator should be far less necessary** — it corrects a cross-acquisition
   Doppler-annotation mismatch expressed through 4π/λ, so the same annotation error produces 12.4×
   less phase. Expect `subtractResidualRamp` to be a no-op-sized correction at P-band; that is itself
   a useful cross-check on the estimator (it should find a *small* ramp, not zero and not a large one).

**Two reader-level facts that affect the GSLC path (both verified):**
- **i/q are `VirtualBand`s.** BIOMASS SCS measurement is stored as separate `_i_abs.tiff` and
  `_i_phase.tiff` (both Float32 — **no phase quantisation loss**), and the reader synthesises i/q as
  virtual bands with expression `abs·cos(phase)` / `abs·sin(phase)`. This is already accommodated
  downstream: `CreateStackOp:912` explicitly admits virtual bands whose unit is `REAL`/`IMAGINARY`
  and skips `PHASE` bands. `GSLCGeocodingOp` has no `VirtualBand` special-casing at all, which is
  correct — it reads through `getSourceImage()` like any band. **Unverified:** the performance cost
  of evaluating a two-source band-arithmetic expression under the GSLC resampler's large source
  neighbourhoods. Worth measuring before concluding it is fine.
- **A ready-made interferometric pair already exists locally.** `L1A_INT_phase/` holds two SCS
  products 3 days apart on the same track and frame (`20170101T060307` and `20170104T060310`,
  `T000_F001`). That is the BIOMASS analogue of the S1A/S1C fixture and removes the data-hunting
  prerequisite entirely. It is small (822 × 24898 — a ~16 km-wide crop), which makes it a good
  *test* fixture rather than a showcase scene.

**A4.1 — RUN 2026-07-27. GSLC works on BIOMASS; the predicted resolution loss is confirmed.**

Reading the product through `gpt` reproduced every value taken from the annotation XML earlier:
822 × 24898, `MISSION=BIOMASS`, `PRODUCT_TYPE=SCS`, `ACQUISITION_MODE=SM`, `radar_frequency=435.0`,
`range_spacing=19.8139`, `azimuth_spacing=6.7089`, `SAMPLE_TYPE=COMPLEX`, 49 orbit state vectors,
incidence 20.84–23.75°.

**`GSLC-Terrain-Correction` ran to completion on the reference** — output 4252 × 10162, geocoded,
`is_terrain_corrected=1`, `gslc_output_flattened=false`, `gslc_azimuth_carrier=false`. Three findings:

1. **The square-cell resolution loss is real and measured.** Auto spacing chose
   `19.813869328 m -> 19.814 m` (the quantiser at work) — i.e. `max(azimuth, range)`, the **range**
   axis. The output carries `range_spacing = azimuth_spacing = 19.814`, so the native **6.709 m
   azimuth sampling was coarsened 2.95×** to force square cells. This is exactly the inverted-aspect
   problem predicted above, now observed rather than inferred. It is the concrete motivation for
   A4.2: BIOMASS should be geocoded with rectangular cells (`dy < dx`).
2. **The stripmap deramp path works and is nearly a no-op at P-band**, as predicted:
   `residual Doppler centroid built from 5 coefficient(s); |f_dc| range across swath = 0.05 Hz (max)`.
   Compare S1 TOPS, where the azimuth carrier is the dominant term.
3. **BIOMASS is left-looking** (`antenna_pointing = left`) where Sentinel-1 is right-looking — not
   previously noted anywhere in this plan. The geocoding completed correctly, so the backward
   geometry solution is not making a right-looking assumption; worth an explicit regression test
   since it is the kind of assumption that hides until a left-looking mission arrives.
4. **The grid lock transfers to another mission — exactly.** The two independently geocoded legs came
   out on one lattice: identical step (1.7799219039544198e-4°) and an origin offset of exactly
   **−71.000000 × −15.000000 pixels**, whole-pixel to 1e-13. This matters because the cross-platform
   lattice mismatch was one of the three root causes of the S1 zero-coherence bug, and the snapping
   was built entirely against S1. It holding on a different band, geometry and look direction is
   evidence the fix was general rather than tuned. Note the legs report slightly different *metre*
   cell sizes (19.4118 vs 19.8141 m E-W) because they sit at different latitudes — the snapping works
   in degrees on a shared lattice, which is precisely why that difference is harmless.

**A4.1 defect found: `CreateStack` tries to REBUILD an already-geocoded secondary.**
Feeding it two finished BIOMASS GSLCs failed with `[bandNames] is an empty array`, and the log shows
why: `CreateStack: locking slave grid to master` followed by a second `GSLCGeocodingOp` run. Both legs
carry `gslc_source_slc_path` (correctly — each points at its own source XML), and the presence of that
stamp sends CreateStack down the auto-coregister path intended for a **raw** secondary, re-geocoding a
product that is already geocoded. The rebuild then yields no matching bands.

`skipBiasEstimation=true` does **not** help — that flag only skips cross-correlation refinement, exactly
as its description says.

### ROOT CAUSE (2026-07-27): the all-GSLC path frees the SLC its secondary is still reading

**This invalidates this plan's opening premise.** The claim that "the all-GSLC path already *works* for
a single pair" is **wrong**, and the way it failed is instructive: on S1 it does not error at all, it
produces a **silently empty secondary**. Measured on a real S1 pair (20200815 × 20200908, IW1, 2 bursts,
both legs geocoded to GSLC first):

| stack band | nonzero |
|---|---|
| `i/q_IW1_VV_ref_15Aug2020` | **61.4%** |
| `i/q_IW1_VV_sec1_08Sep2020` | **0.0%** |

The interferogram that follows is all zeros — full-size bands, plausible product, no data. The two legs
overlap almost exactly (origin offset ~0.0001°), so this is not a coverage problem.

**Mechanism** (`CreateStackOp`, `doExecute` ≈ line 638):
1. For a secondary that is *itself* a GSLC, CreateStack reloads its slant-range SLC from the
   `gslc_source_slc_path` stamp and sets `disposeSlaveSlcAfter = slaveIsGslc = true`.
2. It builds a **bias=0 placeholder** GSLC from that SLC — schema only, "no pixels are computed", meant
   to be swapped later.
3. Bias is estimated. **Two already-geocoded legs are already on the same locked grid, so the bias is
   always tiny** (measured: Δrg −0.0391 px, Δaz +0.0047 px). Below `MIN_BIAS_PIXELS = 0.05` the rebuild
   is skipped and the placeholder is kept.
4. The loop then calls `job.slaveSlc.dispose()` — **but the placeholder reads that SLC lazily from
   `computeTile`, which runs after `doExecute` returns.** The secondary reads a dead product: zeros.

**Why it was never caught:** with a *raw* secondary (the documented workflow) `slaveIsGslc` is false,
`slaveSlc` IS the source product, `disposeSlaveSlcAfter` is false, and nothing is freed. That path —
the one the Venezuela tutorial and every fixture run uses — works. Only the all-GSLC path disposes, and
only the all-GSLC path has a bias small enough to keep the placeholder. The two conditions coincide
exactly.

**Fix applied and VERIFIED on real data (2026-07-27):** the reloaded SLCs are collected into
`deferredDisposeProducts` and freed in the operator's `dispose()` instead of mid-`doExecute`. Re-running
the identical two-GSLC stack after redeploying:

| stack band | before fix | after fix |
|---|---|---|
| `i/q_..._ref_15Aug2020` | 61.4% | 61.4% |
| `i/q_..._sec1_08Sep2020` | **0.0%** | **61.4%** |

(283,121 nonzero against the reference's 283,305 — the small difference is the legs' ~0.0001° origin
offset, as expected.)

**Regression risk if this is ever refactored:** the failing configuration needs *all three* of
(a) secondary is itself a GSLC, (b) `gslc_source_slc_path` present, (c) bias below `MIN_BIAS_PIXELS`.
Condition (c) is automatic for well-aligned inputs, so any test that stacks two GSLCs made on the same
locked grid exercises it — but a test that only checks the chain *completes* will still pass while the
secondary is empty. **Assert non-zero pixel content in the secondary band, not just band existence.**

**Note the BIOMASS symptom differs** (`[bandNames] is an empty array` rather than silent zeros) — same
broken path, different failure mode, probably because the BIOMASS band set survives the rebuild
differently. Re-test BIOMASS once the fix is deployed before assuming it is the same defect.

**Work:**
- **A4.1 (done for the reference leg; secondary + interferogram in progress)** Run the existing
  GSLC → CreateStack → Interferogram chain on the 3-day SCS pair as-is and record what happens.
- **A4.2** Fix whatever A4.1 exposes; the likely candidates are the square-spacing default picking
  the coarse range axis, and any prose/parameter defaulting that assumes C-band magnitudes.
- **A4.3** Add a BIOMASS row to the validation-evidence table in the explainer, and state the
  ionosphere caveat explicitly.
- **A4.4 (stretch, high value)** Use BIOMASS stripmap to run the within-acquisition deramp-model
  verification that S1 TOPS makes impossible — burst overlaps do not have disjoint Doppler bands here
  because there are no bursts. This would convert avenue #2 from "closed by physics" to "closed by
  measurement on the mission where measurement is possible".

**Effort:** A4.1 ~½ day; A4.2 unknown until A4.1 runs (that is the point of ordering it first);
A4.3 ~2 h; A4.4 ~1–2 days.
**Risk:** low — nothing here changes the S1 path.
**Note:** BIOMASS's 3-day repeat and multi-baseline tomographic phase map directly onto **A3**'s
multi-reference work; if A3 lands, this fixture is also the natural test for it.

---

## A5 — Documentation

- Explainer: a short "all-GSLC workflow" subsection — N products → N geocodings → any pair on
  demand, versus classical's per-pair coregistration; state the convention requirements
  (`outputFlattened`, carrier state, shared lattice) and that our own GSLCs satisfy them by
  construction.
- Tutorial: note the all-GSLC variant alongside the reference-only workflow (one paragraph + the
  `skipBiasEstimation=true` hint for externally produced GSLCs).
- Release notes as each item lands.

---

## Sequencing and rationale

```
A1 (½ d, no deps)  ──►  A3 (2–4 d)          A1 first: A3's value depends on N-pair support
A2.1–A2.3 (1–1.5 d, no deps)                can proceed in parallel with A1
A2.4  ── blocked on a real NISAR GSLC pair
A4.1 (½ d, no deps) ──► A4.2 ──► A4.4       BIOMASS: data already on disk, so nothing blocks it
A5    ── as each lands
```

**Recommended first step:** A1 + A2.1/A2.2 together (~1.5 days, no external dependencies, both
unblock later work). A2.2 in particular converts NISAR GSLCs from "silently mis-routed" to
"first-class geocoded input", which is the single highest-leverage change in this plan.

**Worth noting about the two missions:** A2.4 is blocked on NISAR product availability, while **A4.1
is blocked on nothing** — the BIOMASS interferometric pair is already on disk. So if the NISAR
dependency slips, BIOMASS is the second-mission evidence that can proceed regardless, and it exercises
a genuinely different corner of the code (stripmap deramp, inverted pixel aspect, P-band scaling)
rather than a second instance of the same one.

**Do not start A3 before A1** — otherwise the multi-reference path would be built on an
interferogram former that can only make one pair.
