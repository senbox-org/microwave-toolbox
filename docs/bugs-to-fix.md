# Bugs & Improvements Still To Do — microwave-toolbox

Date: 2026-05-23. Carry-forward list from review passes on `sar-op-sar-processing`, `sar-op-calibration`, and `sar-commons`. Each item names a file, lines, what's wrong, what the fix looks like, and why it wasn't applied in the same pass that found it.

This is the "deferred" tail of those reviews; the high-confidence, low-risk fixes were already applied.

## Conventions

- **Severity:** Bug = produces wrong output or crash. Performance = real cost, no correctness impact. Quality = readability / API hygiene / dead code.
- **Confidence:** High = verified against the source. Medium = needs a 5-minute domain check before changing. Low = agent report I haven't reread the source for.
- **Effort:** XS = one-line edit. S = single-method change. M = touches a few methods. L = touches multiple files / new test fixture.

---

## sar-op-calibration

### Radiometric-accuracy claims that need an SME review before changing

These are calibration-math claims from an automated review that I refused to apply without a numerical / domain check. They may be real bugs, may be artifacts of the metadata convention, may be the agent confused. Each should be confirmed against the mission's calibration spec document before any code change.

#### 1. `SpacetyCalibrator.java:530` — possible missing squared term in retro-cal path
- **Claim:** `calibrationFactor *= retroLutVal` should be `calibrationFactor *= retroLutVal * retroLutVal`, by symmetry with the forward path which divides `calibrationFactor` by `lutVal * lutVal`.
- **Why deferred:** the "symmetry" was inferred without showing both paths in the same trace; needs verification by walking the forward path and the retro path side-by-side against the Spacety calibration spec.
- **Severity:** Bug if real (silent radiometric scale error in retro-calibrated intensity).
- **Confidence:** Low.
- **Effort:** XS to fix; M to verify.

#### 2. `StriXCalibrator.java:242` — possible dB-vs-linear mismatch
- **Claim:** `sigma = dn * calibrationFactor` where `calibrationFactor` was parsed from metadata in dB units (per a comment at line 123); should be `dn * FastMath.pow(10, calibrationFactor / 10.0)`.
- **Why deferred:** depends on what the StriX product metadata actually encodes — the line-123 comment could be stale; field unit needs confirmation against a sample StriX product or the vendor spec.
- **Severity:** Bug if real (off by orders of magnitude in linear units).
- **Confidence:** Low.
- **Effort:** XS to fix; M to verify.

### Performance — per-pixel tie-point grid lookups

Several calibrators call `incidenceAngleTPG.getPixelDouble(x, y)` inside the inner `x`-loop of `computeTile`. Each call is a tie-point interpolation. For a typical tile that's tens-of-thousands of redundant interpolations — cache once per row instead.

| File | Line | Severity | Effort |
|---|---|---|---|
| `Sentinel1Calibrator.java` | 415-416 (bilinear LUT per pixel) | Performance | M |
| `Risat1Calibrator.java` | 252 | Performance | S |
| `IceyeCalibrator.java` | 235 | Performance | S |
| `TerraSARXCalibrator.java` | 269-295 (also noise polynomial per pixel) | Performance | S |
| `BiomassCalibrator.java` | 377-408 | Performance | S |
| `NisarCalibrator.java` | 360-394 | Performance | S |

Recommended pattern: pre-compute `double[] rowIncidenceAngles = new double[w]` outside the x-loop, fill once via `incidenceAngleTPG.getPixels(x0, y, w, 1, …)`, then index `rowIncidenceAngles[x - x0]` inside the loop.

### Other deferred items

#### 3. `CalibratorRegistry.java:43-52` — linear search through services
- **Claim:** `getCalibrator()` iterates all registered services for every lookup; should cache a `Map<missionName, Calibrator>` populated at first call.
- **Why deferred:** not a hot path. `getCalibrator()` is called once per operator initialization, not per tile.
- **Severity:** Performance, very low.
- **Confidence:** High that the code is linear; High that it doesn't matter.
- **Effort:** S.

#### 4. `RemoveGRDBorderNoiseOp.java:238-267` — `computeNoiseLUT` in `initialize()`
- **Claim:** Scene-width-sized `double[]` allocated and populated during `initialize()`; should be in `doExecute` or lazily on first tile.
- **Why deferred:** the LUT is used by every tile and the allocation cost is small (one `double[]` of width N, N ≈ 25,000 for S1 GRD). The GUI-freeze symptom this rule usually catches doesn't apply here because the work is bounded and cheap.
- **Severity:** Performance, marginal.
- **Confidence:** High.
- **Effort:** S.

#### 5. All calibrators — verify `setNoDataValueUsed(true)` on every target band
- **Claim:** Intensity / GRD output bands aren't all marked with `setNoDataValueUsed(true)`.
- **Why deferred:** needs a per-calibrator audit; `BiomassCalibrator` at lines 220-221 has the correct pattern, but the others haven't been checked one by one.
- **Severity:** Quality (downstream operators may treat unwritten pixels as valid 0 instead of no-data).
- **Confidence:** Medium.
- **Effort:** M (one-line per file × ~14 calibrators after verifying which output bands can produce NaN).

#### 6. `Sentinel1Calibrator` / `SpacetyCalibrator` — XML parsing duplication
- **Claim:** Both parse calibration-vector metadata with copy-pasted blocks.
- **Why deferred:** refactor risk vs. value is low; the duplication is bounded.
- **Severity:** Quality.
- **Confidence:** Medium.
- **Effort:** M.

---

## sar-op-sar-processing

#### 7. `Frost.getFrostValue` and `GammaMap.getGammaMapValue` — defensive guards are unreachable
- **What:** `Frost.java:201` (`if (totalWeight <= 0.0) return noDataValue;`) and `GammaMap.java:191` (`if (d < 0.0) return cp;`) were added during the review pass, but are upstream-protected by `if (mean <= Double.MIN_VALUE) return mean;` so they cannot be triggered through normal flow.
- **Why deferred:** writing a regression test would require either reflection-based access to the private `getFrostValue` / `getGammaMapValue` or making them package-private. Neither felt worth the API perturbation for a guard against a state the upstream early-return already prevents.
- **Severity:** Quality (defensive code without a test that proves it's needed).
- **Confidence:** High.
- **Effort:** S to expose for testing, then XS for the test itself.

#### 8. Unblock the remaining "skipped in CI" test classes
- **What:** `TestTerrainFlatteningOp`, `TestTiePoints`, `TestMosaic` still have class-wide `@Before { assumeTrue(inputFile.exists()) }` gates that skip every test in the class when fixture data is missing. The other 8 classes in the same module have been migrated to per-method gating.
- **Why deferred:** these three classes happen to have no fixture-independent tests (every method needs a real product), so the migration would not unblock any test that currently skips. The migration is still a code-hygiene improvement.
- **Severity:** Quality.
- **Confidence:** High.
- **Effort:** S per file.

#### 9. Algorithm-level unit tests still missing for individual speckle filters
- **What:** `Lee`, `Frost`, `GammaMap`, `RefinedLee`, `LeeSigma`, `IDAN`, `Boxcar`, `Median`, `MuLog` are only exercised via the `SpeckleFilterOp` dispatcher with synthetic 4×4 products. There are no direct algorithm-level unit tests that pin each filter's output against a hand-computed expected value on edge inputs (all-no-data window, single-pixel window, constant input, high-variance input).
- **Why deferred:** out of scope for the bug-fix-driven test additions in that session; the existing `SpeckleFilterOperatorTest` dispatcher tests do exercise each filter end-to-end, just not at the algorithm level.
- **Severity:** Quality (testing coverage).
- **Confidence:** High.
- **Effort:** M (~9 small test classes).

#### 10. MuLog filter is never tested at all
- **What:** `MULOG_FILTER` switch case in `SpeckleFilterOp.createFilter()` is unexercised; `MuLog.computeTile` (its custom implementation) never runs in any test.
- **Why deferred:** same as above; can be done together with #9.
- **Severity:** Quality.
- **Confidence:** High.
- **Effort:** S.

#### 11. Regression tests for the DEM no-data NaN handling
- **What:** The mechanical `Double.equals` → primitive + `Double.isNaN` sweep in `RangeDopplerGeocodingOp`, `GSLCGeocodingOp`, `TerrainFlatteningOp`, `SARSimulationOp`, `SARSimTerrainCorrectionOp`, `UpdateGeoRefOp` has no regression test that feeds NaN DEM cells and asserts the output is masked.
- **Why deferred:** writing a regression test requires building a synthetic product with full SAR metadata (orbit, tie-points, etc.) — large fixture for the value gained.
- **Severity:** Quality (the fix itself is small and locally obviously correct, but a regression test would prevent backslide).
- **Confidence:** High.
- **Effort:** M.

#### 12. Regression test for the `DeburstWSSOp` Rectangle width fix
- **What:** Line 713 — the `new Rectangle(startX, y, endX, 1)` → `new Rectangle(startX, y, endX - startX, 1)` fix has no test. The existing `TestDeburstWSSOp` has only SPI/metadata smoke tests.
- **Why deferred:** building a synthetic ASAR WSS fixture is non-trivial.
- **Severity:** Quality.
- **Confidence:** High.
- **Effort:** L.

#### 13. `SpeckleFilter.java` — duplicate `getMeanValue` / `getVarianceValue` overloads with different semantics
- **What:** the interface has two overloads each of `getMeanValue` and `getVarianceValue` — one no-data-aware (used by the per-mission filters) and one not. Callers can pick the wrong variant by mistake. The signatures are similar enough that the compiler won't catch it.
- **Why deferred:** the rename/consolidation is invasive across every speckle filter in the module.
- **Severity:** Quality.
- **Confidence:** High.
- **Effort:** M.

#### 14. `LeeSigma.java:112-156` — parameter validation happens during filter construction, not in `initialize()`
- **What:** `setSigmaRange()` throws on an unknown `sigmaStr`, but it's called from the filter constructor (deep in `SpeckleFilterOp.createFilter`) rather than during `SpeckleFilterOp.initialize()`. Users hit the error during graph execution instead of at the parameter-dialog "OK" click.
- **Why deferred:** moving validation up requires reordering construction logic.
- **Severity:** Quality (worse UX, not a correctness bug).
- **Confidence:** High.
- **Effort:** S.

#### 15. `Median.java`, `Boxcar.java`, `IDAN.java` — verify `setNoDataValueUsed(true)` on output bands
- **What:** these filters' output bands may or may not have `setNoDataValueUsed(true)` set correctly. The agent flagged it; I didn't verify per-file because `SpeckleFilterOp` delegates band creation to `OperatorUtils.addSelectedBands(...true, true)` which probably handles it, but each filter still needs to be audited if it ever produces NaN on all-no-data windows.
- **Why deferred:** needs a per-file check rather than a mechanical sweep.
- **Severity:** Quality.
- **Confidence:** Medium (the helper probably already handles it).
- **Effort:** S.

#### 16. `GSLCGeocodingOp.java:593-597` — MHz/GHz frequency-unit detection is fragile
- **What:** the code detects unit by magnitude (`freq < 1e8` → MHz, else Hz). Works by accident for L-band missions reporting GHz, but the threshold is a magic number that could mis-categorize a future mission.
- **Why deferred:** the current threshold works for all missions in scope.
- **Severity:** Quality.
- **Confidence:** High.
- **Effort:** S (make the unit explicit per mission or per metadata field).

#### 17. `GSLCGeocodingOp.java:1065` — verify `fillTilesAsNoSource(targetTiles)` covers every target band
- **What:** on the SM failure path the operator calls `fillTilesAsNoSource(targetTiles)`. If the implementation doesn't iterate every target band (phase + ancillary outputs), some tiles stay uninitialized.
- **Why deferred:** needs reading the actual implementation against the list of bands `createTargetProduct` adds.
- **Severity:** Bug if `fillTilesAsNoSource` misses a band.
- **Confidence:** Low until verified.
- **Effort:** S.

#### 18. `ALOSDeskewingOp.java:302-308` — no progress reporting in heavy `computeTileStack` work
- **What:** the deskew shift loop runs over all azimuth lines with DEM queries inside; no `pm.worked()` calls. UI shows "(Not Responding)" during large products.
- **Why deferred:** real fix is to move the heavy precomputation into `doExecute()` with a `ProgressMonitor` (the GPF-skill recommended pattern). That's a larger restructure.
- **Severity:** Performance / UX.
- **Confidence:** High.
- **Effort:** M.

### Test coverage gaps not yet addressed

Items #9 (per-filter algorithm tests), #10 (MuLog), #11 (DEM NaN regression), #12 (DeburstWSS Rectangle width regression) above are about specific tests. The list below is the rest of the test-coverage gap analysis that wasn't acted on.

#### 19. `nodataValueAtSea` true vs. false branch — never asserted
- **What:** the `nodataValueAtSea` parameter exists on `RangeDopplerGeocodingOp`, `GSLCGeocodingOp`, `TerrainFlatteningOp`, `SARSimulationOp`. Existing tests set it, but no test asserts that toggling it actually produces different outputs (one masked, the other filled with avg height).
- **Effort:** M.

#### 20. `useAvgSceneHeight` fallback path in `RangeDopplerGeocodingOp` — never exercised
- **What:** when DEM is unavailable, the operator falls back to scene-average height. Zero tests exercise this branch.
- **Effort:** M.

#### 21. External DEM successful load — only the error path is tested
- **What:** `TestSARSimulationOp.testExternalDEM_missingFile_throwsDescriptiveError` covers the failure. No test verifies a valid external GeoTIFF loads and produces sensible geocoding.
- **Effort:** M (small fixture DEM needed).

#### 22. Layover / shadow mask actually contains layover pixels
- **What:** `TestSARSimulationOp.testLayoverShadow` reads the band but asserts `{0, 0, 0, 0}` — i.e., specifically tests that a flat patch has no layover. There's no positive-side test that a mountainous scene produces non-zero mask entries. `GSLCInSarGradeTest.testLayoverShadowMask_IsActuallyComputedNotHardZeros` is a RED spec test documenting this gap.
- **Effort:** M (needs a mountainous fixture scene with known layover).

#### 23. `GSLCGeocodingOp.referenceProduct` slave-grid-lock path — never tested
- **What:** the `referenceProduct` parameter (per memory: a recent feature that locks the slave grid to a master) has no test. Easy to regress.
- **Effort:** M.

#### 24. `MultilookOp.getMeanValue` dB / complex / linear branches — only tested via integration
- **What:** three separate branches, all only exercised through full-product CI-skipped tests. Static-helper-style unit tests would cover each in isolation.
- **Why deferred:** the static method is `private`; needs visibility relaxation or a reflection-based test harness.
- **Effort:** S to expose; XS for the tests.

#### 25. `BandPassFilterOp` — synthetic-chirp test
- **What:** all three existing tests are CI-skipped on real data. A synthetic complex chirp → filter → spectral attenuation check would cover the FFT math without product data.
- **Effort:** M.

#### 26. `MosaicOp` overlap averaging on synthetic two-tile input
- **What:** no synthetic test covers the overlap-region weighted-average code (where the `MathUtils.equalValues(sample, 0.0F, 1e-4F)` rejection and the `setNoDataValueUsed(true)` semantics interact). Would help pin behavior before any cleanup.
- **Effort:** S.

---

## sar-commons

#### 27. `Sentinel1Utils.CalibrationVector.getPixelIndex` linear search returning -1
- **Claim:** at lines ~1490-1497 the method uses a linear scan; when `x < pixels[0]` it returns `-1` (or 0 after the recent clamp) and is potentially incorrect for sparse arrays.
- **Why deferred:** the related `getPixelIndex` in `RemoveGRDBorderNoiseOp` was clamped during the pass. The Sentinel1Utils.CalibrationVector version wasn't audited line-by-line yet.
- **Severity:** Bug if the negative-return case is reachable, otherwise Performance (O(n) per pixel where O(log n) would do, hot for tile-by-tile calibration).
- **Confidence:** Low until audited.
- **Effort:** S to clamp; M to replace with binary search.

#### 28. `ImageIOFile` constructor leaks `ImageInputStream` if `createReader` throws
- **What:** at lines 65-75 the constructor assigns `this.stream = inputStream`, then calls `createReader(iioReader)` which may throw. The stream leaks on that exception path (no `close()` after partial construction).
- **Why deferred:** corner case; happy path is fine, and `ImageIOFile.close()` handles the normal cleanup.
- **Severity:** Quality (file-descriptor leak only on construction failure).
- **Confidence:** High.
- **Effort:** S.

#### 29. `FileImageInputStreamExtImpl` construction leaks `EnhancedRandomAccessFile` if `setByteOrder` throws
- **What:** at lines 233-237 the ERAF is opened, then `setByteOrder(ByteOrder.BIG_ENDIAN)` is called. If anything between the assignment and the end of the constructor throws, the ERAF leaks.
- **Why deferred:** `setByteOrder` on a buffered file stream basically can't throw in practice; this is a theoretical leak only.
- **Severity:** Quality.
- **Confidence:** Medium.
- **Effort:** S.

#### 30. `ProductValidator.validateOpticalBand` commented-out throws (lines 195-200)
- **What:** two `// throw new Exception(...)` lines guarded by `if (band.getSpectralWavelength() < 10)` / `< 1`. The if-bodies are now empty.
- **Why deferred:** the comment-out is clearly deliberate (threshold-tuning), but there's no log/issue link explaining whether the validation was disabled because the thresholds were wrong or because the SAR/optical-detection heuristic was unreliable. Removing the dead code could lose intent; uncommenting could re-introduce false positives.
- **Severity:** Quality.
- **Confidence:** High that something is wrong; Low on which direction is right.
- **Effort:** S either way.

#### 31. `OrbitStateVectors.getPositionVelocity(final Double time)` boxes every key
- **What:** the parameter is `Double` (boxed). Even with the `ConcurrentHashMap` fix, the cache lookup auto-boxes a fresh `Double` per call. Cache hits depend on `Double.equals` matching, which is fine, but every call still allocates a `Double`.
- **Why deferred:** changing the signature to `double` would require auditing every caller, and the gain over the now-thread-safe cache is moderate. Better measured first.
- **Severity:** Performance.
- **Confidence:** High.
- **Effort:** M.

---

## Modules not yet reviewed

The review pass stopped before covering these modules. They are likely to contain the same patterns we fixed elsewhere (boxed `Double.equals` against no-data, per-pixel tie-point grid lookups, JAI-thread-unsafe lazy init, missing `setNoDataValueUsed`) but the specific findings aren't enumerated yet.

- **`rstb/`** — full polarimetric / classification / soil-moisture stack. ~140 Java files. The polarimetric speckle filters under `rstb-op-polarimetric-tools/specklefilters/` use the same shared utilities that `sar-op-sar-processing` filters do, so the boxed-Double sweep almost certainly applies.
- **`sar-io/`**, **`sar-io-gdal/`**, **`sar-io-ephemeris/`** — product readers. Resource-leak audit likely productive given what we found in `AbstractProductDirectory` and `ImageIOFile`.
- **`sar-op-insar/`** — InSAR operators (coherence, interferogram, phase unwrapping). Touches `OrbitStateVectors` heavily so the `ConcurrentHashMap` fix already helps; per-pixel tie-point caching opportunities likely.
- **`sar-op-sentinel1/`** — TOPS deburst, back-geocoding, ESD. Heavy users of `Sentinel1Utils`; the substring fix at 213 already helps.
- **`sar-op-feature-extraction/`**, **`sar-op-analysis-ui/`**, **`microwavetbx-rcp/`**, **`*-ui`** modules — UI code; lower priority for correctness review.

---

## Priority-ordered "if I had a day" list

1. **Verify and act on items #1 (Spacety) and #2 (StriX).** If real, these are silent radiometric-scale bugs in mission outputs.
2. **Per-row tie-point caching pass** across the seven calibrators in the table above. Biggest measurable performance win in this whole list; mechanical change.
3. **Audit `setNoDataValueUsed(true)` across calibrators** (item #5). Cheap; protects downstream operators from treating zero-padded tiles as valid 0.
4. **Algorithm-level speckle-filter tests** (items #9 and #10). Unlocks confident future refactors of those files.
5. **Audit the still-unreviewed modules**, starting with `sar-op-insar` and `rstb/rstb-op-polarimetric-tools` (highest density of math-heavy operators that share the patterns we already found).

---

## sar-op-feature-extraction (review pass 2026-05-23)

The bulk of the findings from this pass were fixed in-place. The items below are the ones deferred.

#### 32. `AdaptiveThresholdingOp.java:111` — `backgroundThreshold = 0.5` sentinel collision
- **What:** A `private static final double backgroundThreshold = 0.5` is used at lines 480 and 491 as a hard upper bound on what's considered "background" in the rough-estimate path: any pixel with `val >= 0.5` is excluded from the background mean/std. For calibrated sigma0 intensity products, 0.5 (linear) is a normal sea / land backscatter level — large swaths of valid background are silently dropped, biasing `mean` toward the dimmest pixels and the threshold low (false ships).
- **Why deferred:** the safe fix turns `backgroundThreshold` into a user-configurable parameter, which is an API change. Hard-coding a different magic number would just move the problem.
- **Severity:** Bug (false-positive ship detections in normal calibrated SAR products).
- **Confidence:** High.
- **Effort:** S to make it a parameter; M if the parameter dialog needs revising too.

#### 33. `ReactivOp.java:585` — `stdPol / meanPol` divides by zero
- **What:** Inside `computeMaxVarianceCoefficient`, `stdPol / meanPol` has no `meanPol == 0` guard. Any pixel whose intensity is zero across the entire time series (water shadow, masked, calibration zero) → NaN coefficient of variation; mask comparison `NaN > maskThreshold` is false but the NaN is then written into the saturation band, poisoning the output. There's an additional risk of `sqrt(small negative number)` from FP error in `sum2/n - mean^2`.
- **Why deferred:** the partial fix landed (max accumulator initialised to `-Double.MAX_VALUE`, `meanPol == 0` skipped, `variance = Math.max(0.0, ...)`); the remaining concern is whether the skipped pixels should be written with the band's NaN sentinel rather than 0.0 — that decision needs domain input.
- **Severity:** Bug if downstream consumers cannot distinguish 0.0 from no-data; Quality otherwise.
- **Confidence:** High that the guard is missing; Medium on the right sentinel.
- **Effort:** S.

#### 34. `ReactivOp.java:277-279` — DEM-style "do heavy stats in `doExecute`" still applies
- **What:** The flag-and-volatile fix landed, but the pre-pass over the entire source product (a `ThreadExecutor` over every source tile) still runs inside the first `computeTileStack` call rather than in `doExecute(ProgressMonitor)`. The first tile blocks for the duration of the global statistics gather; progress UI freezes; under tight executor pools the nested ThreadExecutor can deadlock against JAI's pool.
- **Why deferred:** moving the pre-pass to `doExecute` is the right fix per GPF convention but requires restructuring the operator's lifecycle.
- **Severity:** Performance / UX; potential deadlock risk under contended thread pools.
- **Confidence:** High.
- **Effort:** M.

#### 35. `ObjectDiscriminationOp.java:207-211` — 10-pixel halo too small for real ships
- **What:** The source rectangle adds a hard-coded 10-pixel halo, but `maxTargetSizeInMeter = 600.0` / `rangeSpacing ≈ 10 m` corresponds to 60-pixel ships. A ship straddling a tile boundary clusters from inside, but the iterative flood-fill stops at the halo edge, so one ship gets split into two partial clusters; each is then below `minTargetSizeInMeter` and both are rejected — silent false negative.
- **Why deferred:** the halo math fix landed (no longer over-runs the raster) and the recursive flood fill is now iterative, but the halo itself should scale with `maxTargetSizeInMeter / rangeSpacing` rather than being a hard-coded constant. The right fix needs validation that increasing the halo doesn't blow up memory on large products.
- **Severity:** Bug (missed ship detections at tile seams; ship count under-reported).
- **Confidence:** High.
- **Effort:** S to compute halo from parameters; M to validate against test data.

---

## sar-op-insar (review pass 2026-05-23)

#### 36. `IonosphericCorrectionOp.java:412 + 466-473` — Gaussian filter leaves border at 0 → divide produces NaN ring
- **What:** The 1-D convolution at lines 466-473 only writes the interior `[halfWin, size-halfWin)`; border samples stay at `0.0`. After two passes there's a zero ring of width `halfWin ≈ 4·sigma`. Line 412 then divides `filteredData` by `normalization` (also zeroed at the edges), producing NaN in a band-wide ring (`halfWin = 4·sigma = 324 pixels` at the default `sigma = 81`). Even in the interior, line 412 yields NaN/∞ whenever every kernel sample had coherence below `coherenceThreshold`.
- **Why deferred:** the right fix is a proper border-extend strategy (BORDER_REFLECT or BORDER_ZERO with the normalization correctly counting only in-bounds samples). That changes the numerical behaviour everywhere and needs a regression baseline before changing.
- **Severity:** Bug (default-parameters run produces NaN-filled bands of ~324 pixels around every tile).
- **Confidence:** High.
- **Effort:** M.

#### 37. `EmpiricalTropoCorrectionOp.java:303` — entire scene fetched as single tile inside `computeTile`
- **What:** `getSourceTile(sourceBand, new Rectangle(0, 0, width, height))` requests the entire scene as one tile during the first `computeTile` invocation. For Sentinel-1 IW SLC at ~25 000 × 17 000 float pixels, that's ~1.7 GB per tile × 2 bands; routinely OOMs.
- **Why deferred:** the right fix is to perform the lazy fit in `doExecute(ProgressMonitor)` with whole-product iteration over reasonably-sized chunks. Larger restructure.
- **Severity:** Bug (OOM crash on realistic SLC frames).
- **Confidence:** High.
- **Effort:** M.

#### 38. `WarpData.java:138-160` — JAI normal-equation least squares with no conditioning check
- **What:** `WarpPolynomial.createWarp` was wrapped with a corner-case fallback (when master/slave GCPs are identical), but the general path still uses JAI's normal-equation fit with no rank / condition test. For long narrow swaths or coastline acquisitions where GCPs lie along a quasi-linear strip, the 6-coefficient quadratic normal matrix is near-singular; JAI returns coefficients of order 1e+10; `WarpData.isValid()` doesn't NaN-check the coefficients so the bad polynomial silently warps the slave.
- **Why deferred:** replacing the JAI fit with a QR/SVD-based solver is a substantive change.
- **Severity:** Bug (silent corruption of coregistered stacks on narrow scenes).
- **Confidence:** High.
- **Effort:** L.

#### 39. `WarpData.java:343-362` — `eliminateGCPsBasedOnRMS` early-return is effectively dead code
- **What:** The `if (slaveGCPList.size() < rms.length) { notEnoughGCPs = true; return true; }` short-circuit fires only when the list was already shrunk between the `computeRMS` call and the `eliminateGCPsBasedOnRMS` call. In normal flow, that ordering keeps `size() == rms.length`, so the guard never fires. It only triggers if a concurrent caller mutates `slaveGCPList`, which is exactly when the guard would falsely declare the warp invalid.
- **Why deferred:** removing dead code is mostly an audit exercise; risk is low but value is also low.
- **Severity:** Quality.
- **Confidence:** High.
- **Effort:** S.

#### 40. `CrossCorrelationOp.java:1188-1198` — Nyquist `peakRow <= h/2` asymmetric mapping
- **What:** Peak-to-shift conversion uses `peakRow <= h / 2`, so when `peakRow == h/2` it's mapped to `-h/2 / rowUpSamplingFactor` (a negative shift of half the window). For an exactly-centred peak (zero true shift, which lands at index `h/2` for even `h`), the operator reports `-h/(2·upsamplingFactor)` instead of 0. Same logic in `CoarseRegistration.java:251-261` and `CrossCorrelationOp.java:795-805`.
- **Why deferred:** FFT/IFT cross-correlation has multiple valid conventions for the Nyquist bin; changing it requires picking the convention against a controlled test.
- **Severity:** Bug if the asymmetric bias is systematic; the iterative refinement may compensate.
- **Confidence:** Medium.
- **Effort:** S.

#### 41. `StampsExportOp.java:259` — concurrent `ProductWriter.writeBandRasterData` from JAI tile threads
- **What:** Multiple JAI tile threads call `info.productWriter.writeBandRasterData(...)` concurrently for different tiles of the same band. SNAP's `AbstractProductWriter` implementations (BEAM-DIMAP, ENVI) hold per-band file handles that are not documented thread-safe — concurrent `RandomAccessFile.seek/write` interleaves bytes producing a corrupt `.img` on output.
- **Why deferred:** the fix needs a per-band write lock or single-threaded write coordination via `doExecute`. Architectural change.
- **Severity:** Bug (output file corruption under multi-threaded execution).
- **Confidence:** High.
- **Effort:** M.

#### 42. `MultiMasterInSAROp.java:838-841` — hard-coded `nodata` sentinel propagated through coherence accumulator
- **What:** When `valueI0 == nodata || valueI1 == nodata`, the code writes `intensity0[yy][xx] = nodata` (a magic value like 0 or -9999). The subsequent `computeCoherence` sums `intensitySum0 += intensity0[y_r][x+c]` over the window with no validity mask. Any window straddling a no-data boundary accumulates the sentinel (e.g. -9999) into the denominator, producing `intensitySum0 * intensitySum1 < 0` → `sqrt(negative)` = NaN coherence.
- **Why deferred:** the partial fix landed (`denom > 0` guard before `sqrt`), but the underlying issue — accumulating sentinel values into the window sum — remains. Real fix needs a parallel validity mask propagated through the windowed accumulation.
- **Severity:** Bug (NaN/Inf coherence bleeds into pixels near every no-data boundary).
- **Confidence:** High.
- **Effort:** M.

#### 43. `MultiMasterInSAROp.java:717` — `incidenceAngle = asin(rangeSpacing / rangeSpacingGround)` returns 90° for GRD products
- **What:** For SLC products `rangeSpacing` is slant-range spacing and the formula is correct (θ = asin(slant/ground)). For GRD products `rangeSpacing` IS the ground-range spacing, so the formula returns `asin(1) = 90°` for every pixel. No SLC/GRD check.
- **Why deferred:** needs adding a SAMPLE_TYPE / SRGR check at the boundary; the operator may not be intended for GRD input but currently fails silently rather than rejecting.
- **Severity:** Bug if a user feeds a GRD product; Quality if the operator is documented as SLC-only.
- **Confidence:** High.
- **Effort:** S.

---

## sar-op-sar-processing (review pass 2026-05-23)

#### 44. `RangeDopplerGeocodingOp.java:1352` — per-pixel `synchronized (layoverShadowMask)` in geocoding hot loop
- **What:** `layoverShadowMask` is a global `byte[][]` accumulator across all tile threads; the monitor is taken for every pixel of every tile when `saveLayoverShadowMask` is enabled. The field is also non-volatile and is reassigned at line 1133 inside `createLayoverShadowMask()` — a tile that observed the new array reference but synchronizes on the cached old reference loses its writes.
- **Why deferred:** the right fix is to build the layover/shadow mask in `doExecute()` rather than incrementally during tile computation. That's a substantive restructure.
- **Severity:** Performance (severe throughput collapse on large scenes); Bug if the reassignment race triggers (rare in practice).
- **Confidence:** High on performance; Medium on the race.
- **Effort:** M.

#### 45. `BandPassFilterOp.java:202` — FFT window normalization depends on tile width
- **What:** `sourceRectangle.width = targetRectangle.width + halo` is fed into the Hamming window at line 284 (`hamming(sourceAlpha, n, sourceWindow.length)`), and the target Hamming size at line 326 also scales with `dataI[0].length`. The same source pixel gets a different per-bin Hamming normalization depending on JAI tile geometry — replaying the operator with a different `preferredTileSize` produces different pixel values, and tiles within one run have inconsistent normalization at column-tile boundaries.
- **Why deferred:** correct fix is to fix the FFT block size at a constant (e.g., next power of two of a fixed multiple of the impulse response length) independent of target tile dims. That changes the block-pixel grid and needs a regression baseline.
- **Severity:** Bug (results not reproducible across tile-size choices; tile-seam artifacts at boundaries).
- **Confidence:** High.
- **Effort:** M.

#### 46. `MosaicOp.java` — `0.0` used as both "no sample" marker and valid data value
- **What:** The partial fix landed (the `targetVal != 0` flag was replaced by `numSamples > 0`), but the operator still has other paths that rely on `0.0` as a "no sample" sentinel — most notably the `isValidSample` predicate at line 902 (`!MathUtils.equalValues(sample, 0.0F, 1e-4F)`) that rejects valid samples within ±1e-4 of zero before they ever reach the averaging buffer. For dB backscatter (near `-∞` linear), normalized indices, or calibrated complex-real bands, 0 is valid signal.
- **Why deferred:** a clean fix needs a separate "did we get a sample" boolean from the data value itself. Touches more of the operator than the targeted patch.
- **Severity:** Bug (mosaics over dB / index bands silently drop valid near-zero pixels).
- **Confidence:** High.
- **Effort:** M.

#### 47. `MosaicOp.java:504, 704` — `normalizeByMean` field mutated from `computeTileStack`
- **What:** The volatile fix landed, but the `@Parameter` field is still being mutated from tile threads (`normalizeByMean = false` when stats fail). That's a parameter contract violation regardless of memory safety; downstream consumers of the operator's parameter map (graph serialization, parameter dialog reflection) see a value that doesn't match the user input.
- **Why deferred:** the right fix introduces a separate internal flag (`normalizeByMeanEffective`) so the user's parameter stays untouched. Small but propagates through several code sites.
- **Severity:** Quality (silent parameter mutation breaks reproducibility of graph runs).
- **Confidence:** High.
- **Effort:** S.

#### 48. `MultilookOp.java:371-372` — multilook flag / ENL update doesn't compose with prior multilooking
- **What:** `azimuth_looks` and `range_looks` are written as `existingLooks * nNewLooks`, but there's no `multilook_flag` check warning when the source was already multilooked; `sampleType` may be left as `COMPLEX` / `DETECTED` from the source even though averaging has destroyed the complex phase relationship — downstream operators that key off `SAMPLE_TYPE` will misinterpret the band.
- **Why deferred:** needs deciding what the right `sampleType` is for each input type / output (real vs complex vs dB), which is a calibration-spec question.
- **Severity:** Quality / Bug if downstream operators key on sampleType.
- **Confidence:** Medium.
- **Effort:** S once the right semantics are agreed.

#### 49. `BatchSnaphuUnwrapOp.java:217 + 226-227` — Windows path with spaces still fragile after fix
- **What:** The `substring(14)` was replaced with defensive comment-prefix stripping and the argv list is now built explicitly. But the snaphu config file template still embeds full paths (input/output file names) into the same line. If the config file's `infile` / `outfile` paths contain spaces (e.g., `C:\Users\Some User\snaphu_in\...`), they'll be tokenised on whitespace by the same defensive `split("\\s+")` we now use in the comment-stripper.
- **Why deferred:** the right fix is to read each argument from a dedicated config line rather than splitting one line, which requires changing `SnaphuExportOp`'s template too.
- **Severity:** Bug on Windows with paths containing spaces.
- **Confidence:** High.
- **Effort:** M (touches both ops).

#### 50. `RangeDopplerGeocodingOp` / `TerrainFlatteningOp` / `SARSimulationOp` — heavy whole-scene work in `initialize()`
- **What:** Volatile flags landed for the DEM lazy init, but `initialize()` still does CRS construction, source-corner geocoding via `computeImageGeoBoundary`, target product allocation, and other expensive work that the GPF spec wants in `doExecute`. Graph builder UIs, validation passes, or stack pipelines reinstantiating the operator per acquisition pay the full setup cost N times.
- **Why deferred:** moving each piece to `doExecute` is a per-operator restructure with non-trivial impact on the operator's contract; needs to be done deliberately, not mechanically.
- **Severity:** Performance / UX.
- **Confidence:** High.
- **Effort:** L across all three operators.

#### 51. `MultitemporalCompositingOp.java:138-145` — O(bands²) source/sim band lookup per tile
- **What:** `computeTileStack` rebuilds the source/simulated band list on every tile by scanning all of `sourceProduct.getBands()` with string-matching against `targetBandName`. For stacks with many epochs this is quadratic per tile.
- **Why deferred:** cache the mapping in `initialize()` / `doExecute()` — mechanical change, but it touches the operator's setup-vs-tile contract.
- **Severity:** Performance.
- **Confidence:** High.
- **Effort:** S.

---

## Priority-ordered "if I had another day" list (post-2026-05-23 review)

In addition to the original five-item list above:

6. **Verify CMOD5 coefficients are now reachable in test scenarios** — the c9/c10/c11 fix landed but no test exercises the wind-speed retrieval at a known truth, so a regression would slip in silently.
7. **Move the heavy `doExecute`-class work out of `initialize()`** (items #34, #37, #50). Best done as one coordinated pass since the pattern is identical.
8. **Border-extend strategy for `IonosphericCorrectionOp`** (item #36). High-impact bug producing 324-pixel NaN halos by default.
9. **Audit `ProductWriter` thread-safety** for `StampsExportOp` (item #41) and other operators that write directly from tile threads — corrupt-output bugs are the hardest to notice.
10. **Replace JAI normal-equation warp fits with QR/SVD** (item #38) — long-tail bug that only fires on degenerate GCP geometries but produces silent garbage when it does.
