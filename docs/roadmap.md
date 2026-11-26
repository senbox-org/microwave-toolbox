# Microwave Toolbox Roadmap: Parked Work

Date: 2026-11-13. Sections 1–7 collect every item parked during the JavaHelp overhaul and its Phase 8 code-defect pass. None of these items has been changed in the working tree.

Sections 8 and 9 (added 2026-11-17) are new workstreams, not parked items: **§8 Memory efficiency** and **§9 Validation framework**.

Sources: [`docs/reviews/javahelp-2026-11-12/phase8-parked-and-release-notes.md`](reviews/javahelp-2026-11-12/phase8-parked-and-release-notes.md) (the consolidated list, with the release notes for the fixes that *were* made) and the plan [`docs/plans/2026-11-12-javahelp-overhaul-plan.md`](plans/2026-11-12-javahelp-overhaul-plan.md). The evidence (file:line, reasoning) for each item is in the Phase 8 reports in the local SDD workspace (`.superpowers/sdd/2026-11-12-javahelp-overhaul-plan/phase8-*-report.md`, `final-review.md`).

**Tags**
- **[Decision]** a default or design choice for the maintainers, not a clear bug.
- **[Bug]** wrong output, crash or misleading behaviour.
- **[Hygiene]** labels, comments, dead code, test gaps.
- **[Cross-repo]** the change belongs in snap-engine or another repository.

---

## 1. Decisions needed

- [ ] **[Decision]** Terrain Flattening `externalDEMApplyEGM` defaults to false, while Terrain Correction and the other DEM operators default to true. Pick one default.
- [ ] **[Decision]** Terrain Flattening ACE2_5Min resolution handling: the code tests for 10000 m but uses 1000 m. Switching to 10000 m makes each tile about 100× slower; keeping 1000 m leaves holes in the output.
- [ ] **[Decision]** Warp default threshold 0.05 on detected stacks. It acts as a 0.05-pixel RMS cut, too tight for the half-pixel coarse GCPs. Consider a separate default for detected stacks (e.g. 0.5) and one consistent label: the dialog says "RMS Threshold (pixel accuracy)", the parameter says "Significance Level".
- [ ] **[Decision]** Warp falls back silently to the legacy RMS rule for all Secondaries when the CPM w-test fit throws. Make it a clearer log message, a warning in the product metadata, or a failure.
- [ ] **[Decision]** Phase Linking default SHP window is 21 az × 7 rg (about 290 m × 26 m on S1 IW). It may be transposed; the help advises 7 × 21.
- [ ] **[Decision]** Cross-Correlation: the Coherence Window, sliding-window and Powell (coherence) fine-registration path cannot be reached, and "Coherence Threshold" actually thresholds an amplitude NCC. Restore a reachable coherence mode, or rename and remove the dead parameters.
- [ ] **[Decision]** RangeFilterOp `fftLength` has no effect because it is overwritten by the tile width. Wire it in (Doris suggests 64) or hide it.
- [ ] **[Decision]** PolarimetricParameters `Coh_` and `Phase_` bands are computed per pixel, not over a window. Choose the windowing behaviour.
- [ ] **[Decision]** Sentinel-1 calibrator: the virtual Beta0/Gamma0 use sin θ, while Sigma0 comes from the LUT. Under radiometric normalisation the two are inconsistent.
- [ ] **[Decision]** Bistatic reference range: the code uses `slant_range_to_first_pixel`; the S1 geocoding guide uses the central sub-swath mid-range.
- [ ] **[Decision]** PCAOp boolean parameters use `"1"` as their defaultValue.
- [ ] **[Decision]** Faraday Rotation: wire up the Ω output band, or drop it permanently.
- [ ] **[Decision]** MuLog is annotated as an operator but is not runnable or exposed. Expose it or remove the annotation.
- [ ] **[Decision]** Multilook "Output Intensity" default for complex input: coherent i/q averaging is the current default.
- [ ] **[Decision]** DpDecomposition/DpFactorization NESZ default −16 dB vs about −22 dB for S1 IW. Note that it is a C11 threshold.

## 2. Cross-repository

- [ ] **[Cross-repo]** **Copernicus DEM vertical datum.** `snap-engine CopernicusElevationTile:57` converts with EGM96, but GLO-30 heights are EGM2008.
- [ ] **[Cross-repo]** **ProductSubsetBuilder** shifts `ground_range_origin` incorrectly for flipped RS2/RCM descending subsets (snap-engine).
- [ ] **[Cross-repo]** `guides-docs-tutorials/microwave-toolbox/gslc/GSLC-Tutorial.md` (and its .docx/.pdf builds): update it for the Phase 8 GSLC/Interferogram changes.
    - L87: "True only for amplitude/PolSAR single-date use". Interferogram now accepts a fully flattened stack (it skips the second flat-Earth subtraction) and refuses a mixed one.
    - L90, L223, L616: "turn on Solid Earth Tide / Tropospheric for displacement-grade InSAR". In the default (unflattened) mode these change only the `flatteningPhase` band (plus the tide's sub-pixel sampling shift) and do not correct the interferogram. Only with phase-flattened output are they removed from the pixels.
    - L149-152, L286-289: "ETAD + GSLC troposphere double-counts". This is true only in flattened mode; in the default mode the GSLC term never reaches the interferogram.
    - L169, L615: a reference/secondary `outputFlattened` mismatch now raises an Interferogram error rather than producing noise.
    - L159/L223: Create Stack now forwards the Reference's DEM, tide and troposphere settings to the Secondaries it geocodes. Bias is measured with Back-Geocoding + ESD for TOPS and a polynomial offset field for stripmap, and is applied only above 0.05 pixel.
    - The solid-Earth tide is up to about 30 cm vertical; it is not 10 cm.
    - Rebuild the .docx/.pdf with tools/build.py, and run check_params.py.
- [ ] **[Cross-repo]** **Help keyword index.** Every microwave helpset now ships an `index.xml` with an Index view (556 entries, generated by `tools/javahelp/gen_index.py`). SNAP shows the Index tab only once the master helpset in snap-help-system (1.0.3, `masterHelpSet.xml`) re-enables its commented-out Index view (name `Index`, `javax.help.SortMerge`). Until then the module index views are ignored. A local probe with the view enabled merged all entries.
- [ ] **[Cross-repo]** Is `cimr-reader` meant to ship? It is not in the `microwavetbx-kit` pom.

## 3. Correctness bugs

### Geometry, DEM and radiometry
- [ ] **[Bug]** GeolocationGrid geocoding uses 2R/c where Range-Doppler/SARSim use R/c. Because the operator reads time from the product's own tie-point grid, the right offset may be 0. Settle it with a real ASAR or ERS product.
- [ ] **[Bug]** Five operators always add EGM96 to an external DEM with no opt-out. SubtRefDemOp's `applyEGM` call is commented out, and SARSim Terrain Correction ignores the SARSim EGM switch.
- [ ] **[Bug]** GSLC `layoverShadowMask` flags layover only for local incidence < 5° and shadow at 85°, and codes them 1/2/4 where Terrain Correction uses 0/1/2/3.
- [ ] **[Bug]** Terrain Flattening does not check for dB input.
- [ ] **[Bug]** GSLC: a flattened stack with topographic-phase subtraction switched on still loads a DEM it never uses. This costs time and can fail offline.

### InSAR and time series
- [ ] **[Bug]** EmpiricalTropoCorrection: coherence-band pairing fails, and it corrects wrapped phase.
- [ ] **[Bug]** SnaphuImport output depends on the input order.
- [ ] **[Bug]** SBAS: check coherence before starting, and key pairs beyond two dates.
- [ ] **[Bug]** ESD on a single burst silently applies no azimuth shift.
- [ ] **[Bug]** Oversample assumes zero Doppler for sensors other than ERS/ENVISAT.
- [ ] **[Bug]** PhaseToDisplacement sets no no-data value.
- [ ] **[Bug]** RangeFilterOp with several Secondaries: the filtered Reference matches only the last pair, only one Secondary per polarisation reaches the output, and dual-pol stacks never filter the Reference. Interim fix: refuse more than one Secondary per Reference; full support needs a redesign (see §7.1).
- [ ] **[Bug]** RangeFilterOp has no TOPS guard; AzimuthFilter now has one.
- [ ] **[Bug]** TLogSelector uses critical value 2.326 at α = 0.025, and the normal instead of the t distribution.
- [ ] **[Bug]** StackAveraging has no median. (No-data inclusion is now confirmed; see §7.3.)
- [ ] **[Bug]** Coherence pairs are ordered by orbit string.
- [ ] **[Bug]** MultiMasterOp metadata issues (see the Phase 2 ledger notes).
- [ ] **[Bug]** BIOMASS Precise (MAAP) orbit option is unreachable, and it throws on non-BIOMASS products.
- [ ] **[Bug]** The shipped TOPS coregistration/interferogram graphs lack ESD, the Deformation/DEM-generation graphs lack Multilook, and the graphs hard-code Sentinel precise orbits.
- [ ] **[Bug]** WarpOp LUT: table rows are built at f = i/127 but JAI reads them at i/128, up to 1/128 pixel off.
- [ ] **[Decision]** Cross-Correlation on temporally decorrelated SLC pairs (added 2026-11-25). The fine-step amplitude NCC is often 0.1–0.3 (RS2 Manitoba cropland, 24 and 96 days), so the default Coherence Threshold of 0.6 rejects almost every GCP. Warp now stops with "not enough valid GCPs" instead of writing zero Secondaries, and `applyFineRegistration=false` is now honoured for SLCs. Open: an automatic fallback to the coarse GCPs, or a threshold derived from the NCC distribution. Regression data: `SAR/RS2/Manitoba/subsets` (`TestCoregistrationDecorrelatedPair`).

### Polarimetry and soil moisture
- [ ] **[Bug]** Polarimetric Calibration does not estimate cross-pol imbalance. The co-pol imbalance estimate assumes equal HH/VV power, and arg f is ambiguous by π. A design note is in the P8-rstb report.
- [ ] **[Bug]** Cameron decomposition has no guard for FULL (quad-pol) input.
- [ ] **[Bug]** The PolarimetricDecomposition dialog has no OAC (orientation-angle correction) checkbox.
- [ ] **[Bug]** The soil-moisture graphs save the ellipsoid incidence angle, not the local one.
- [ ] **[Bug]** SAOCOM thermal noise removal: the noise LUT may be power while the data are amplitude (per the CONAE spec). Fix this in the reader's intensity labelling once confirmed.
- [ ] **[Decision]** Polarimetric Box Car treats image edges differently by input type (added 2026-11-25). Quad-pol input averages over the window clipped to the image (`getMeanCoherencyMatrix`); C3/T3/C4/T4 input replicates the edge pixels (`BoxCar.getNeighborValues`). In the 2-pixel border the results differ (35 % at the corner pixel of the QuadPolStack fixture). Pick one convention; `TestPolarimetricSpeckleFilterOp` excludes the border until then.
- [ ] **[Decision]** The polarimetric tools reject Create-Stack-only stacks (added 2026-11-25). `PolBandUtils.getSourceBands` groups bands by date only when `coregistered_stack=1` (set by Warp); a Create Stack product without Warp has `collocated_stack=1`, so all dates are treated as one acquisition and the operators fail with "A full polarization product is expected as input".

### Change detection and feature extraction
- [ ] **[Bug]** LogRatio change detection zeroes dB input and applies ±2 thresholds to every output.
- [ ] **[Bug]** The REACTIV saturation is tuned for amplitude.
- [ ] **[Bug]** The RPCA mask flags only brightening, and its threshold is in input units.
- [ ] **[Bug]** AdaptiveThresholding can pick a dB band instead of a linear one, and OilSpillDetection accepts a dB band.
- [ ] **[Bug]** Object-Discrimination counts a target that crosses a tile edge twice.

### Utilities
- [ ] **[Bug]** TileWriter throws an NPE with `numberOfTiles=2`, and its overlap is not clipped at the image edges.
- [ ] **[Bug]** BandMerge ignores `sourceBands`.

## 4. Readers

- [ ] **[Bug]** GenericReader: multi-band reads return band 1 for every band.
- [ ] **[Bug]** ALOS-2 GeoTIFF reader: amplitude only, hard-coded radar frequency and PRF, no orbit vectors or calibration factor, broken ZIP handling.
- [ ] **[Bug]** ALOS-4 GeoTIFF reader writes one made-up orbit vector and fixes frequency, PRF and pass direction.
- [ ] **[Bug]** COSMO-SkyMed Second Generation GeoTIFF has no import menu action.
- [ ] **[Bug]** Gaofen-3: no calibrator, and Level 2 products are not recognised.
- [ ] **[Bug]** QPS-SAR reader hard-codes 9650 MHz (actual 9.38 GHz) and VV.
- [ ] **[Bug]** The EOS-04 and RISAT-1 readers both claim any zip containing `BAND_META.txt`. There is no EOS-04 CEOS reader.
- [ ] **[Bug]** UAVSAR reader accepts `.dat` files and reads them as garbage float32.
- [ ] **[Bug]** Basic CEOS reader records every product's mission as `RS1`.
- [ ] **[Bug]** SNAPHU reader creates `q_band` as float32 even for float64 data.
- [ ] **[Bug]** ETAD reader geocoding is a simple lat/lon box.
- [ ] **[Bug]** CIMR reader has no fallback for an unknown `format_version`.
- [ ] **[Bug]** No reader for ESA's reprocessed ERS-1/2 and Envisat ASAR heritage products, if they are distributed in a new format.
- [ ] **[Bug]** NISAR GCOV reader cannot feed the polarimetric tools (added 2026-11-24, [SNAP-4384](https://senbox.atlassian.net/browse/SNAP-4384)). Spec: [`superpowers/specs/2026-11-24-nisar-gcov-reader-design.md`](superpowers/specs/2026-11-24-nisar-gcov-reader-design.md).
  - Off-diagonal covariance terms (`HHHV`, `HHVV`, `HVVV`, …) are complex but read as one `INTENSITY` band holding the real part only.
  - Compact-pol terms `RHRH`/`RHRV`/`RVRV` are not read at all.
  - GCOV is not exposed as C3/C2 (no `C11`… bands, no `polsar_data`/`compact_mode`), so decompositions, H/A/α, speckle filters and Wishart refuse it.
  - `mask`, `numberOfLooks` and the RTC γ⁰→σ⁰ factor are not exposed. No-data is 0 but GCOV fill is NaN.
  - Latent: `PolBandUtils.getSourceProductType` would misdetect `RHRH` as raw `RCHCP` (substring match on `RH`).
  - Phases P1–P4 in the spec. Needs quad-pol full-covariance, dual-pol and compact-pol GCOV test products, plus answers on the conjugation convention and compact-pol chirality (spec §9).

- [ ] **[Bug]** RCM burst-mode (ScanSAR) SLC products are not supported (added 2026-11-25). They deliver one image per burst (`imagery/<id>_<pol>_<burst>.tif`); the reader assumes one per polarisation. It now refuses them with a clear message instead of failing on a duplicate band name (`TestEODMSRCMProductReaderLongTest.testScanSAR_CX_SLCZipIsRefusedClearly`; test product `RCM/EODMS/SLC/RCM1_OK1683816_..._SC30MCPB_..._CH_CV_SLC.zip`).
- [ ] **[Bug]** **[Cross-repo]** Subsetting the ALOS test products fails (added 2026-11-25): `ArrayIndexOutOfBoundsException: Index 9 out of bounds for length 9` from `ProductSubsetBuilder` via snap-engine `TestProcessor.createSubsetProduct`, so all 9 `testProcessAllALOS` tests in sar-op-sar-processing error. Not investigated; possibly the tie-point-grid subset (compare the ApplyOrbit→Subset stale-TPG bug found in snap-gpt-tests).
- [ ] **[Bug]** There is no RADARSAT-1 calibrator (added 2026-11-25): Calibration refuses mission RS1. `TestCalibrationOpenAll.testProcessAllRadarsat1` is ignored until one exists. (The RS1 ScanSAR Wide read failure it also hit is fixed.)

## 5. Hygiene: labels, comments and tests

- [ ] **[Hygiene]** TOPSARSplit `subswath` has no valueSet; only its description was fixed.
- [ ] **[Hygiene]** S1 ETAD Correction: the dialog labels (`S1ETADCorrectionOp.java:76-82`) differ from the GPF `@Parameter` labels.
- [ ] **[Hygiene]** HaAlphaDescriptor has a stale TODO.
- [ ] **[Hygiene]** `InSARStackOverview.java:51-53`: the comment claims the C-band model constants match Sentinel-1 IW; they are ERS-era values.
- [ ] **[Hygiene]** TestWarpOp has only smoke tests; add tests for the CPM outlier path and the CC4P kernel through WarpOp.
- [ ] **[Hygiene]** PolarimetricDecomposition van Zyl golden values are regenerated but only run with `../testData`; check them on the test-data machine.
- [ ] **[Hygiene]** GSLC: write explicit stamps for tide and troposphere instead of relying on the Processing_Graph record that Create Stack reads (prerequisite for §7.2 items B and C).
- [ ] **[Hygiene]** Create Stack bias estimation still uses a hard-coded Copernicus DEM (see §7.2 D).
- [ ] **[Hygiene]** HorizontalVerticalMotion uses (0, 0) as the default reference pixel (see §7.3: a no-data reference is accepted).
- [ ] **[Hygiene]** Soil moisture: `TestIEMMultiPolInverOp` still has its value check commented out (since SNAP-3562). Rebuild it like `TestIEMHybridInverOp` / `TestIEMMultiAngleInverOp` (added 2026-11-25): inputs copied from shipped-LUT rows, N=1 must return that row, plus an N=5 reference. Note the javaml KDTree keeps only the first of duplicate keys.
- [ ] **[Hygiene]** jLinda: 15 test classes and 5 RangeFilterTest methods are ignored because they need Doris `.res` files and Doris-produced binaries from the original developer's `/d2/…` tree, which cannot be regenerated without Doris (added 2026-11-25): BaselineTest, OrbitTest, SLCImageTest, AzimuthFilterTest, PhaseFilterTest, RangeFilterTest, DInSARTest, Slant2HeightTest, DemTileTest, TopoPhaseTest, FlatBinaryDoubleTest, SnaphuConfigFileTest, SarUtilsTest, TriangleInterpolatorTest, GeoUtilsTest. Rewrite them on synthetic data or SNAP-produced fixtures (raw ASAR Bam and ERS Etna scenes are in the test data), or delete them.
- [ ] **[Hygiene]** jLinda `SpectralUtils.fft2D_inplace(DoubleMatrix)` calls `realForwardFull` on a rows×cols buffer (it needs 2×rows×cols) and has no callers: fix or remove (added 2026-11-25).
- [ ] **[Hygiene]** rstb data-dependent tests check every fixture file in a class-level `@Before`, so one missing file skips the whole class, including tests that need no data (added 2026-11-25). Move the `assumeTrue` calls into the tests that use each file.
- [ ] **[Hygiene]** Tests added in the 2026-11-25 rstb/jLinda test review have no `@STTM` ticket tag (the work was on master without a ticket).

## 6. Verification still to do before release

- [ ] Open the Multi-Reference ("Split stack into multiple references") dialog in SNAP Desktop. The custom dialog is bound for the first time, after the layer.xml fix.
- [ ] Run Batch SNAPHU → SBAS Inversion end to end. The route follows from the code but has not been run on data.
- [ ] Run the PyRate Export menu graph (Batch SNAPHU → Terrain Correction → Write) on a real stack.
- [ ] Run a full reactor build and test before committing the Phase 8 changes. 24 of 25 modules passed at the final review, and the one failure is fixed.
- [ ] Refresh the help screenshots on the most-used pages (Terrain Correction, Calibration, Coregistration, Interferogram). This needs a SNAP 15 build; the candidates are listed in the Task 6 inventory.
- [ ] Release notes: carry over section 7 of `docs/reviews/javahelp-2026-11-12/phase8-parked-and-release-notes.md` (changed outputs, new refusals, label/default changes, new menu entries).

## 7. Findings from the adversarial production-readiness review (2026-11-13)

An independent review of the Phase 8 code fixes found further defects. Most predate those fixes. The three critical ones (Polarimetric Calibration co-pol imbalance, the Multi-Reference output folder, SAOCOM amplitude units) are being fixed directly and are not listed here.

Each item gives the effort (XS one line, S one method, M a few methods, L multi-file) and, where needed, the decision with a recommendation in brackets. Everything here can be tested on synthetic data unless marked **needs real data**.

The full evidence (file:line, approach, regression risk) is in the "Fixability" sections of the review reports `.superpowers/sdd/2026-11-12-javahelp-overhaul-plan/adv-review-*.md` (local workspace).

### 7.1 Coregistration and filters (jLinda, sar-op-insar-ui)
- [ ] **[Bug]** CPM crashes WarpOp with IndexOutOfBounds when there are fewer GCPs than polynomial unknowns, or when the Reference and Secondary offsets are identical. Three causes: `numObservations` is kept while the lists are empty, the identity test sums `|dx+dy|` instead of `|dx|+|dy|`, and `rms` is not filled when estimation is skipped. S.
- [ ] **[Bug]** RangeFilterOp leaves the top and bottom (nlMean-1)/2 lines of every tile unfiltered, because `TILE_OVERLAP_Y` is never set. Pad the tile rectangle and crop. S.
- [ ] **[Bug]** RangeFilterOp crashes (IllegalArgumentException) when the last tile row has fewer lines than nlMean. Fixed by the same padding. XS.
- [ ] **[Bug]** AzimuthFilterOp: the last matching band wins regardless of polarisation, so dual-pol stacks silently lose one polarisation. Loop per polarisation, as RangeFilterOp does. M.
- [ ] **[Bug]** AzimuthFilterOp refuses only Sentinel-1 TOPS; SAOCOM TOPSARNarrow/Wide still pass. Also refuse modes starting with "TOPS". XS. Decision: other ScanSAR modes? Move the TOPS detection into a shared helper in snap-engine?
- [ ] **[Bug]** Azimuth/Range Filter maps are keyed by ABS_ORBIT. Missions without an orbit number all get 99999, so Secondaries collide and are dropped. Key by acquisition date and polarisation. S.

- [ ] **[Bug]** Split stack into multiple references (MultiMasterStackGenerator): the pair products share the source product's metadata elements instead of copying them, so edits to one pair's metadata affect the others. S.

### 7.2 GSLC, Create Stack and Interferogram
- [ ] **[Bug] A.** Interferogram removes the TOPS carrier-model difference (and sets up the seam steps) only when flat-Earth or topographic subtraction is ticked. With both off, the per-burst carrier surface stays in the interferogram. Move carrier-band discovery out of `setupGSLCReferencePhase`. XS-S. Decision: this also changes natural-phase output with both flags off [yes, with a system-property escape hatch].
- [ ] **[Bug] B.** Flattened GSLC legs made with different tide, troposphere or DEM settings are not refused, and a troposphere mismatch alone leaves hundreds of radians. Stamp `gslc_solid_earth_tide`/`gslc_tropospheric_correction` in GSLCGeocodingOp and compare them in Interferogram. M. Decision: [refuse on tide/troposphere, warn on DEM, warn when stamps are missing on old products].
- [ ] **[Bug] C.** Create Stack stacks a same-grid Secondary as-is without comparing DEM, tide or troposphere with the Reference. Add a model-match check and fall through to the existing re-geocode path, which already forwards the Reference's settings. S-M, after B. Decision: re-geocode or refuse? **Needs real data** for end-to-end testing.
- [ ] **[Bug] D.** Create Stack TOPS bias estimation hard-codes the Copernicus 30m DEM. Pass the Reference's DEM settings through to Back-Geocoding, keeping Copernicus as the fallback; warn when the Reference used EGM off. S. **Needs real data** for end-to-end ESD.
- [ ] **[Bug] E.** Create Stack bias estimation and rebuild failures silently become zero bias, with nothing recorded on the product. Distinguish failure from zero, write a per-Secondary `gslc_bias_status`, and add a `failOnBiasEstimationError` option. M. Decision: [record and warn by default; fail-on-error option for gpt; Interferogram warns on FAILED].
- [ ] **[Hygiene]** GSLC dialog drift: some labels differ from `@Parameter` labels; there are no tide/troposphere controls; the SQUARE_FINEST preview shows slant range while the operator uses ground range.

### 7.3 InSAR displacement, time series and filtering
- [ ] **[Bug]** Horizontal/Vertical Motion does not propagate no-data. Where only one pass has data, the 0-filled phase/incidence still gives a finite, wrong value. Return NaN when any input is no-data or the 2x2 system is near-singular. S.
- [ ] **[Bug]** Horizontal/Vertical Motion accepts a no-data (0-filled) reference pixel, so neither interferogram is referenced. This is typical at the default (0,0) corner of terrain-corrected products. Reject no-data references. XS. Decision: [keep the (0,0) default but fail loudly when it is no-data].
- [ ] **[Bug]** Phase to Elevation: one NaN seed makes the whole product NaN with no error, and 0-filled seeds bias the fit. Drop no-data seeds and throw on a non-finite reference height. S. Decision (optional): a robust 3·MAD seed fit (M).
- [ ] **[Bug]** SBAS: an epoch with no usable pair, or a group cut off from the reference, gets Tikhonov damping and `ok=true`. It is then written as 0 mm or an arbitrary offset and feeds the velocity fit. Use union-find on the active pairs and write NaN for disconnected epochs. M. Decision: [per-epoch NaN; optional flag band].
- [ ] **[Bug]** SBAS does not scale: two SVDs per pixel (about 10^7 flops/pixel at 100 dates) and about 600 MiB of source tiles per tile at 300 pairs. Replace with one Cholesky solve in a reused workspace (about 30-100x faster), and size tiles from the pair count. M. Keep the SVD solver as a fallback and test oracle.
- [ ] **[Bug]** Stack Averaging counts per-date no-data (0) as real samples. Make the expressions validity-aware, honouring each band's own no-data value. S-M. Decision: minimum number of valid dates.
- [ ] **[Bug]** Stack Averaging on i/q stacks computes SD/CV per component, which is meaningless. XS (refuse) or M (compute on intensity). Decision: [refuse non-mean statistics on i/q].
- [ ] **[Bug]** Goldstein uses the first coherence band for adaptive alpha (on by default) and for the coherence mask of every interferogram pair in a multi-pair product. Match `coh<tag>` to each `i_<tag>` pair. S. Single-pair graphs are unchanged.

### 7.4 SAR processing (Batch SNAPHU, compositing)
- [ ] **[Bug]** Batch SNAPHU: operators that copy the band image at their own initialize (MergeOp, BandSelectOp) still read zeros, because the band image is swapped in doExecute. Keep the operator image and copy the SNAPHU data in `computeTile` from the ENVI reader; reads stay lazy. M. This has been verified with a scratch probe.
- [ ] **[Bug]** Batch SNAPHU on Linux: the unzipped binary is not executable, so it is rejected, re-downloaded every run, and then fails with a NullPointerException. Match the exact name `snaphu`, `setExecutable(true)` after unzip, and throw a clear IOException. S. The help page's "downloaded once and reused" is false on Linux until this is fixed.
- [ ] **[Bug]** Batch SNAPHU downloads an executable over plain http with no checksum. Switch to https (the files are byte-identical; verified). XS. Decision: pin SHA-256 hashes? [no, unless ESA publishes them].
- [ ] **[Hygiene]** `sar-op-sar-processing-ui` layer.xml:431-434 registers `PyRateExportUI`, a class that no longer exists, so SNAP logs SEVERE at every start. Delete the entry. XS.
- [ ] **[Hygiene]** Multitemporal Compositing refuses two Secondaries on the same day, and its output bands have no unit or no-data value.

### 7.5 Feature extraction, calibration and readers
- [ ] **[Bug]** Wind Field Estimation inverts the wind speed from one unaveraged, speckled pixel per 20 km frame, which gives errors of several m/s. Use the mean linear sigma0 over the frame's valid pixels, at the frame-centre incidence. S. Decision: confirm the estimator; lift the 20 m/s cap? snap-gpt-tests expected results may need regenerating.
- [ ] **[Bug]** SAOCOM noise removal is a silent no-op when no noise LUT is found or it fails to parse: an unchanged `_tnr` product is reported as success. None of the 35 local SAOCOM products has noise LUTs. XS-S. Decision: [hard-fail as RCM does], or document pass-through.
- [ ] **[Bug]** SAOCOM noise removal stretches the LUT over the current raster width, with no guard for subsets or multilooked/resampled input. Interim fix: refuse such input as RCM does (M); correct LUT sample positions **needs real data**.
- [ ] **[Bug]** SAOCOM TOPSAR: noise LUTs are keyed by polarisation only, so per-swath LUTs overwrite each other. Interim fix: refuse duplicate-polarisation LUTs and TOPSAR modes (S); per-swath keying **needs real data**.
- [ ] **[Bug]** RCM reader file filter is looser than decode: it accepts any `manifest.safe` (including S1) and lowercase `rcm*.zip`. The help says "select the product folder", but the import dialog only picks files. Tighten `accept()`; optionally allow folder selection. S. Decision: [allow folder selection].

### 7.6 Polarimetry
- [ ] **[Bug]** Polarimetric Parameters: the C2 channel pair falls back to HH/VV unless the metadata lists exactly two polarisations. BandSelect keeps all four tags, so an RS2 quad cut to HH+HV reads HV as VV, without warning. Have Polarimetric Matrices write a `polsar_channels` attribute from its source bands and use it first. M (L if BandSelect's metadata tags are also fixed). Decision: [raise when the pair is ambiguous]; add a `c2ChannelPair` override for old products?
- [ ] **[Bug]** Polarimetric Calibration (Ainsworth) has no convergence or divergence check. At co-pol coherence 0.95-0.98 the cross-talk error grows with iterations and can corrupt HV/HH by 5 dB. Track the residual per pass, keep the best iterate, stop when it grows, and warn on low conditioning (kappa). S-M. Decision: kappa threshold; warn or raise.
- [ ] **[Hygiene]** Polarimetric Calibration: add optional parameters for an externally measured (corner-reflector) co-pol imbalance f.
- [ ] **[Hygiene]** `docs/reviews/javahelp-2026-11-12/polcal_port.py` still ports the old estimator; update it or mark it historical.

---

## 8. Memory efficiency (workstream)

**Spec:** [`superpowers/specs/2026-11-17-memory-efficiency-design.md`](superpowers/specs/2026-11-17-memory-efficiency-design.md) covers the metric definitions, the workflow suite W1–W7, the measurement architecture, the suspect table with fixes and tests, the phases P0–P4 and the guard rails.

**Update 2026-11-20.**
- The release-time minimum-heap and wall-time measurement (the "heap sweep") is now built in **`snap-validation`**, not snap-gpt-tests. The initial pass covers S1 only, for the four scorecard features (W1 GRD→TC, W2 TOPS InSAR, GSLC).
- W3 (S1 stack) and W5 (SBAS) can be added later as efficiency-only rows. W4 and W6 (non-S1) wait until those missions join. W7 (Desktop) stays here.
- The P2 local fixes come after the T1 contract scaffold exists, because they use its tile-size invariance check.
- See §9.0 for the overall order.

**Problem.** Users report that SNAP needs too much memory, and that this hurts performance: GC thrash, swapping, OOM on 16 GB laptops. Today nothing measures it. `microwavetbx-benchmark` records only time. `snap-gpt-tests` records process `memory_max`, but under a fixed `-Xmx` the RSS reflects the heap setting, not what the workflow needs.

**Metric.** The number that matches the complaint is the **minimum heap a standard workflow completes in**, plus the wall time at 4, 8 and 16 GB. Track both per release, and treat any increase as a regression.

### 8.1 Measure first
- [ ] Define the **memory budget suite**: about six workflows that cover what users actually run.
  1. S1 GRD → Calibrate → Speckle → Terrain Correction.
  2. S1 IW SLC pair → TOPS coregistration → Interferogram → Deburst → Terrain Correction.
  3. A 10-date S1 SLC stack (GSLC and classical).
  4. An RS2/RCM quad-pol → Decomposition → Wishart classification.
  5. SBAS on 100 dates.
  6. Open a large product in SNAP Desktop, then view and pan it.
- [ ] Add a **heap sweep** runner: run each workflow at `-Xmx` 2/3/4/6/8/12/16 GB and record pass/fail, wall time and GC time (from `-Xlog:gc`). Then find the minimum heap by bisection. M. **Now built in `snap-validation`** (plan Tasks 6, 7 and 11): a 256 MB ladder from 1 GB, starting at the baseline's value, with an output-equality check per rung.
- [ ] Add peak heap-after-GC, allocation rate and GC-pause share to `microwavetbx-benchmark` (`Benchmark.java` currently writes runtimes only). Use the `GarbageCollectorMXBean` and `MemoryPoolMXBean` peak usage. S.
- [ ] Profile every suite workflow once with **JFR** (allocation profiling, old-object sample) and take a heap dump at peak. Rank the retainers by class and owner (operator, reader, cache, metadata). This ranking replaces guesswork. M.

### 8.2 Known suspects (found by code scan; confirm with 8.1 before fixing)
- [ ] **[Bug]** Whole-scene arrays in operators that should be tiled:
  - `GeneralWishart` and `FreemanDurdenWishart` hold `int[H][W]` + `double[H][W]` (about 1.2 GB for a 10k×10k scene). Train the clusters on a sample, then classify per tile. L.
  - `ForestAreaClassificationOp` holds a full-scene `byte[H][W]` mask.
  - `RangeDopplerGeocodingOp:1192` holds a full-scene `layoverShadowMask` (about 425 MB for an S1 GRD). Compute it per tile with an overlap margin.
- [ ] **[Bug]** `EmpiricalTropoCorrectionOp:308-309` fetches the whole phase and coherence image with `getSourceTile(0,0,W,H)` just to subsample it with a stride. The comment there says it reads stride rows, but it does not. Read the stride rows (or a decimated image). S.
- [ ] **[Bug]** `ETADUtils:436` and `TerraSarXProductDirectory:703` call `readRasterDataFully` on bands. Check the sizes; if they are large, read them lazily or by region.
- [ ] **[Bug]** SBAS: about 600 MiB of source tiles per tile at 300 pairs (already in §7.3). Size tiles from the pair count.
- [ ] **[Decision]** **Metadata duplication.** 118 operator classes copy the full metadata tree. For S1 SLC (annotation, calibration and noise vectors) that tree is tens of MB, and every node in a graph holds its own copy. Measure the share of the heap. If it matters, the options are copy-on-write or shared read-only `Original_Product_Metadata` in snap-engine **[Cross-repo]**, or slimming what S1/RCM readers load eagerly.
- [ ] **[Decision]** **Competing caches.** The JAI tile cache, the snap-engine `ProductCache` (heap-scaled budget since its accounting fix), DEM tile caches (per operator instance?) and orbit caches each size themselves on their own. Consider one memory governor that splits the heap: a cache budget plus working-set headroom. **[Cross-repo]** for JAI and ProductCache.
- [ ] **[Bug]** Lifecycle leaks: an operator chain is not disposed after a graph or a Desktop action, so DEM caches and metadata copies stay reachable. The `sar-test-stacks` OOM showed this class of leak. Audit the Desktop "Run" path and the batch-processing path for `dispose()` of intermediate products. M.
- [ ] **[Hygiene]** Check data types: bands or intermediates kept as float64 where float32 is enough; i/q stored as two float32 where int16 would do in reader-backed products; tile sizes that span the full width on S1 (a 25k-wide row of tiles × bands × threads).
- [ ] **[Decision]** Defaults that ship to users: the `-Xmx` fraction in snap.conf and gpt.vmoptions, the JAI cache size and the tile size. Review them against the 8.1 results and publish a short "how much memory do I need" table per workflow.

### 8.3 Guard rails
- [ ] **Bounded-heap tests**: run each suite workflow on a cropped fixture at a fixed small `-Xmx` (e.g. 1.5 GB) under `LongTestRunner`. If a change needs more memory, the test fails. M.
- [ ] Add an **ArchUnit or grep check** in CI that flags `getSourceTile(..., new Rectangle(0, 0, width, height))` and `new X[height][width]` for scene dimensions in operator code. Allow listed exceptions. S.
- [ ] Add the minimum-heap and wall-time results to the release notes per release, so users can see the trend.

---

## 9. Validation framework (workstream)

**Spec:** [`superpowers/specs/2026-11-17-validation-framework-design.md`](superpowers/specs/2026-11-17-validation-framework-design.md) covers the tier model and placement, the contract checks C1–C9 and R1–R8, the fixture registry, the T2 tolerance model, the T3 repository layout and case schema, the register schema and the phases V0–V4.

### 9.0 Status and order of work (updated 2026-11-20)

**T3 and the release efficiency measurement are now a new Python repo, `snap-validation`.** It is on GitHub, cloned to `E:\ESA\snap-validation`.
- **Design (approved):** [`superpowers/specs/2026-11-20-snap-validation-design.md`](superpowers/specs/2026-11-20-snap-validation-design.md).
  - It replaces §6 of the 2026-11-17 validation spec.
  - It also takes over the release heap sweep from §5.2 of the memory spec, which had placed it in snap-gpt-tests.
- **Report mockup:** [`superpowers/specs/2026-11-20-snap-validation-report-mockup.html`](superpowers/specs/2026-11-20-snap-validation-report-mockup.html).
- **Implementation plan:** [`superpowers/plans/2026-11-20-snap-validation-plan.md`](superpowers/plans/2026-11-20-snap-validation-plan.md). It has 20 tasks in phases A–E and is executed subagent-driven.

**Decisions taken 2026-11-20**
- **Repo and runtime:**
  - `snap-validation` is written in Python. It tests the installed SNAP through `gpt`; the peer tools run in Docker.
  - Microwave Toolbox comes first; optical may join later.
  - It runs on the user's Windows workstation before each release, started manually. It is built to run on AWS, but that path is not implemented.
- **Initial pass:** Sentinel-1 only, for both correctness and memory. Four scorecard features:
  - S1 Calibration;
  - S1 GRD → Terrain Correction;
  - S1 TOPS InSAR (the classical chain);
  - GSLC.
- **Report:**
  - The front page is a scorecard table with pass/fail, accuracy, peer agreement, and speed and minimum heap compared with the last release. Each row links to a detail page per feature.
  - It uses the SNAP house palette with modern cards and charts.
  - Toolbox test results are not included.
- **Test data:** the S3 copy is `s3://skywatch-image-processing-test/SNAPTestData/`, holding only what the first-wave cases need. The data is *not* in `skywatch-image-processing`; only `E:\TestData\s1tbx\SAR` syncs to OVH `snap-s3`.
- **Corner reflectors:** the user-facing corner reflector and point-target tools will live in microwave-toolbox. They are deferred and out of scope for `snap-validation`.
- **`sar-test-stacks`:** the CR tests move to `snap-validation`, and are deleted only after the Python port reproduces them within 0.1 px. The stack tests stay.
- **`microwavetbx-benchmark`:** stays as a developer profiling tool. It is switched on by `-Dbenchmark.enable`, and gains `MemoryProbe`.

**Order of work**
1. **`snap-validation`**: the plan above. The initial S1 pass was built by 2026-11-24 and is waiting for the user's review and commit. The next work is the expansion projects in §9.0.1.
2. **T1 contract tier (`sar-contract-tests`, §9.2)**, *after snap-validation*.
   - It gets its own brainstorm, then its own plan. Review §9.2 and the 2026-11-17 spec's T1 sections first: they were written before the user confirmed anything.
   - Open question: does it run in the default build from day one, or stay opt-in until the known-failures list is short?
   - Move `MemoryProbe` somewhere both can use (e.g. `sar-commons` test-jar), so check C8 (bounded allocation) can reuse it.
   - Expect a long first-run failure list that maps onto §3–§7. Triaging it is the main work of V1.
3. **§8 memory fixes (P2)**, *after T1 V0 exists*. The Wishart and ForestArea fixes use T1's tile-size invariance check as their test. P0 measurement for S1 comes from `snap-validation`'s efficiency pass.
4. **T2 additions in snap-gpt-tests (§9.3)**, independent. The heap-sweep item there is superseded.

### 9.0.1 snap-validation expansion projects (added 2026-11-24)

**Intent (user, 2026-11-24).** Extend `snap-validation` past Sentinel-1, as was done for S1. For each sensor and topic, the report must show two things:
- the standard processing is **correct** against an independent reference;
- **performance and memory** improvements are real.

"SNAP opens the product" is not a report row: T1 and T2 already cover it, and a processing case fails anyway if the reader fails.

**Survey finding.** No microwave-toolbox test checks a value against an independent source. Their golden values are earlier SNAP output. The exceptions are a few closed-form synthetic tests: `TestDecompositionFormulas`, `TestPolarimetricParametersOp`, the `SpeckleFilterOperatorTest` ramps, the ICEYE calibration identities and the RCM noise model. So every project below needs a reference from outside SNAP:
- the provider's own higher-level product of the same acquisition;
- a peer tool;
- a synthetic input with a known answer;
- corner reflectors.

**How each project runs.** One project per sensor or topic. Each gets its own brainstorm, then its own design and plan. The order is not decided yet.

**Data.** Many inputs are only in `E:\EO`. Today's manifest roots are `testdata` and `scenes`, and `E:\EO` is neither. It also does not sync to OVH. Each project must either copy its inputs into `E:\TestData` or add a manifest root, and must upload them to the S3 `SNAPTestData/` copy for cloud runs.

- [ ] **P0 Improvement detection (cross-cutting; do this before the sensor projects' efficiency rows).**
  - Today's grading flags only regressions. The report must also confirm a real, significant improvement.
  - Repeat wall and heap measurements enough to put a noise band on each feature. The S1 tops_insar minimum heap ranges 7680–8448 MB over 4 runs.
  - Mark an improvement only outside that band.
  - Record heap as a range for noisy features (already deferred from the S1 work).
  - Decide how the report adds a sensor dimension next to the topic pages (Calibration, Terrain Correction, InSAR).
- [ ] **Apply-Orbit (added 2026-11-24; used by most chains).**
  - Reference 1, interpolation: the precise orbit files themselves. ESA POD accuracy is about 5 cm.
    - Withhold state vectors from a POEORB file and compare SNAP's interpolated position and velocity at those epochs.
    - Compare the orbit SNAP writes into the product against an independent interpolator (ISCE3/s1-reader) at the same epochs.
  - Reference 2, geolocation: corner-reflector position error at Rosamond and Surat with the annotated orbit, RESORB and POEORB. That shows the orbit's effect on the final product.
  - Reference 3, file choice: the right orbit file is picked, and RESORB is used when POEORB is missing. The rival-rule check from the S1 orbit staging work can be reused.
  - Other missions with orbit files in SNAP: BIOMASS POD (the MAAP option is unreachable today, see §7), ENVISAT DORIS and ERS.
  - Data: `E:\TestData\orbits` (S1A, S1B and S1C POEORB) and `SAR/Orbits` (2014 POEORB/RESORB).
  - Efficiency: seconds per product, so not a row of its own. It is timed inside the chains that use it.
  - Known defect to cover: ApplyOrbit → Subset keeps a stale tie-point grid (found in the snap-gpt-tests run).
- [ ] **Coregistration (added 2026-11-24; used by InSAR, multi-temporal speckle, polarimetric stacks and change detection).**
  - S1 TOPS (Back-Geocoding + ESD):
    - residual offset after coregistration, by sub-pixel cross-correlation of the coregistered pair, against the 0.001 px azimuth requirement for TOPS interferometry;
    - the ESD residual (already in the tops_insar case);
    - agreement with COMPASS coregistered CSLCs (the Docker peer exists).
  - Stripmap and other sensors (CreateStack + Cross-Correlation + Warp, and DEM-Assisted Coregistration):
    - recover a known synthetic shift or warp applied to a real SLC (analytic truth);
    - compare with NISAR's ISCE3 offset products (ROFF/GOFF from the simulated 2008 set and the S-band set);
    - compare with the ESA-coregistered BIOMASS L1C STA stack;
    - check the residual offset after coregistration.
  - Data:
    - Venezuela S1 pair;
    - ETAD SM stack (`E:\EO\S1\ETAD\SM`);
    - RCM BC Border 3 dates;
    - BIOMASS simulated INT set;
    - NISAR simulated RSLC pair with ROFF;
    - RS2 Manitoba 5-date quad stack;
    - the ICEYE Turkey/Syria SM dates (check that the geometry repeats).
  - Efficiency: Back-Geocoding on a stack; the memory spec's W3 (10-date S1 stack) and W5 (100-date SBAS); CreateStack + CC + Warp on a stripmap stack.
  - Known defect to cover: Warp silently falls back to the legacy RMS rule when the CPM fit throws (§2 decision item).
- [ ] **ETAD (S1).**
  - Data: ESA's ETAD InSAR test set, `E:\EO\S1\ETAD\snap_test_data`. It has differential interferograms made with and without ETAD, and the correction phase screens `int_<id>_geco_phase.tiff`. The SM stack's primary SLC (20200920) and the 20201002 and 20201014 SLCs are on disk with their ETAD products, in `E:\EO\S1\ETAD\SM`. Of the IW stack only 20201014 is on disk.
  - Case 1: SNAP's phase-correction mode against ESA's phase screen and corrected interferogram. This closes the README's phase-correction coverage gap.
  - Case 2: classical TOPS geometric ETAD on Surat. Compare corner-reflector position error with and without ETAD, using the IW SLC and ETAD product already in the manifest.
  - Efficiency: ETAD on an IW SLC pair.
  - The ESA corner-reflector sites (Côte d'Azur, Metsähovi, Wettzell, Yarragadee) have ETAD products only. Their SLCs would have to be downloaded.
- [ ] **Speckle filtering.**
  - Reference: a synthetic speckle field with known reflectivity and a known number of looks (gamma-distributed multiplicative noise). Measure ENL gain, mean bias and edge and point preservation per filter (Lee, Lee Sigma, Refined Lee, Frost, Gamma Map, IDAN, Boxcar, Median).
  - Also measure the peak loss of the Rosamond and Surat corner reflectors after filtering.
  - Efficiency: each filter on a full S1 GRD.
  - No ENL test exists in the Java tests today.
- [ ] **Polarimetry.**
  - References:
    - closed-form synthetic matrices (van Zyl, Cameron, Krogager, H/A/α, Span/RVI);
    - PolSARpro in Docker as the peer tool, already named in the design (C3/T3, decompositions, polarimetric speckle filters, Wishart);
    - CP Stokes closed forms for compact-pol.
  - Data: RS2 FQ14 quad (`SAR/RS2`), the RS2 Manitoba 5-date FQ10W stack, RCM QP (`SAR/RCM/QP`, `OpenData/Quebec City`, `EODMS/SLC` QP8), RCM CP (Winnipeg 3MCP24, BC Border 3MCP30), CSG quad (`SAR/Cosmo/QUAD POL`).
  - Efficiency: memory-spec W4 (quad-pol → T3 → decomposition → Wishart).
  - Blocker for NISAR polarimetry: SNAP-4384 (GCOV is not exposed as C2/C3).
- [ ] **NISAR.**
  - References: NISAR's own processor (built on ISCE3) made the RIFG/RUNW, GCOV and GSLC products. Compare SNAP's interferogram from the RSLC pair, SNAP's RTC and SNAP's geocoding against them.
  - The simulated 2008 L-band set (`SAR/NISAR`, track 005 frame 219, lat 34.24–35.36, lon −118.48 to −117.47) covers the Rosamond corner-reflector site. Check whether reflectors are visible in that 2008 data.
  - Data: a real 12-day pair, RSLC + GSLC 20260108/20260120, track 162 frame 24, Ontario, in `E:\EO\NISAR\NISAR`. Also a real RUNW and a real GCOV in `E:\EO\NISAR\Real`, and the S-band simulated set with a 2026 "New" RSLC/RIFG/GSLC.
  - Efficiency: RSLC → calibration → terrain correction, and pair InSAR on full-size products.
  - Gaps: no numeric NISAR assertion and no calibration test exist today.
- [ ] **BIOMASS.**
  - References: ESA's documented gamma0 processor (`E:\ESA\biomass_gamma0_allpol.txt`, PFD v1.6.1 §4.3.2) for calibration. The ESA-coregistered L1C STA stack (`SAR/BIOMASS/L1C_INT_phase`) for coregistration.
  - Data:
    - the simulated 3-date SCS interferometric set (`SAR/BIOMASS/L1A_INT_phase`);
    - real June 2025 DGM/SCS in `E:\EO\BIOMASS\Real`, with repeat passes F308 and F158 on 6 and 9 June;
    - real L2A (FD/FH/GN) in `E:\EO\BIOMASS\L2`.
  - Efficiency: DGM → calibration → terrain correction; SCS pair InSAR.
- [ ] **RCM.**
  - References: RCM's own GRD/GRC/GCD of the same acquisition, against SNAP's SLC → multilook → terrain correction. Analytic thermal-noise model (already in T1).
  - Data:
    - `SAR/RCM/QP` and `CP`, simulated 2011, each at SLC/GRD/GRC;
    - Winnipeg 3MCP24 at SLC/GRC/GCC/GCD;
    - EODMS GRD/GCD/SLC.
  - Polarimetric cases join the Polarimetry project.
- [ ] **ICEYE.**
  - References: ICEYE's GRD of the same acquisition. The published calibration σ⁰ = cf·amp².
  - Data: SLC+GRD pairs in `SAR/Iceye` and `E:\EO\Iceye` (Brawley, Copeland, Corpus Christi, London, Escondida, Houston Dwell and others); the 15.8 GB Open Data SLC.
  - No corner reflectors, so geometric truth needs a reflector-site acquisition.
  - Efficiency: memory-spec W6 (large SLC → calibrate → multilook → terrain correction) and the large-product reader.
- [ ] **COSMO-SkyMed / CSG.**
  - References: the provider's DGM, for SNAP's detection and multilook. The provider's GEC, for SNAP's terrain-correction geolocation.
  - Data: CSG SCS/DGM/GEC of the same acquisitions (`SAR/Cosmo/NG`, `STRIPMAP`, `SCANSAR-1`, CSK level1B); the 2026 two-frame DGM larger than 2^31 pixels (`E:\EO\Cosmo`).
  - Efficiency: W6 and the large-product read path.
  - No corner reflectors on disk.

**Goal.** Show for every feature (reader, operator, shipped graph) that it (a) runs correctly, (b) is comparable to or better than other tools, and (c) is scientifically correct. Publish the evidence per release.

### 9.1 Three tiers, three homes

T3 design (repo layout, cases, per-release scorecard): see `docs/superpowers/specs/2026-11-20-snap-validation-design.md`.

These are three different questions. Each needs different data and a different runtime, so each lives where its runtime already is.

| Tier | Question | Data | Runs | Lives in |
|---|---|---|---|---|
| **T1 Contract** | Does it behave correctly on known input? | Synthetic plus tiny committed fixtures | Every commit (CI) | **microwave-toolbox** (unit tests plus long-gated integration tests, as now) |
| **T2 Regression** | Does the shipped build give the same answer on real data, through gpt and graphs? | Real products, GB scale, on the test-data server | Nightly / per RC | **snap-gpt-tests** (already ESA's gpt runner; already has `s1tbx/` tests, expected outputs and a performance DB) |
| **T3 Scientific** | Is it right against ground truth, and how does it compare with peer tools? | Reference sites, truth data, peer-tool outputs | Per release / quarterly | **New repo `snap-validation`** (renamed from `microwave-validation`, 2026-11-20; see §9.0): move `microwave-toolbox/validation/` there, plus the corner-reflector tests from `sar-test-stacks` |

Why a separate repo for T3: it is Python and Docker (ISCE3, Dolphin, MintPy, PolSARpro), needs multi-GB truth data, runs on a release cadence rather than per commit, and has a different audience (ESA reviewers, users). It tests the **installed** build, not the source tree, so pinning a SNAP build version in each report is enough. The single-commit argument in `validation/README.md` no longer holds once reports record the SNAP version and git hash. Keep T1 and T2 out of it; a validation repo that also does regression testing becomes a second gpt-tests.

Readers fit the same split. T1 is a generic contract test per reader plug-in, in a new test-only module `sar-contract-tests`. T2 uses **snap-reader-tests**, which runs the `<PlugIn>-data.json` descriptors kept in each reader module's test resources. 23 descriptors cover 22 of the 39 readers today; add the missing 17 and SAR metadata expectations. T3 for readers is geolocation and radiometry against corner reflectors.

### 9.2 T1: generic contract tests (microwave-toolbox)
These parametrised tests run over **every** registered plug-in, so new readers and operators get them automatically. Each check below matches a class of defect found in 2026.
- [ ] **Reader contract** (`ReaderContractTest`, parametrised over all SAR `ProductReaderPlugIn`s with a fixture):
  - `getDecodeQualification` is INTENDED only for its own product and SUITABLE/UNABLE for the others. This catches the EOS-04/RISAT-1 and RCM-accepts-S1 claims.
  - Required abstracted metadata is present and plausible: radar frequency, PRF, pass, orbit vectors, slant range to first pixel. This catches hard-coded frequency/PRF and the single made-up orbit vector.
  - A region read equals the same crop of a full read.
  - Multi-band reads differ per band (GenericReader).
  - Geocoding round-trips (pixel → geo → pixel within 0.5 px).
  - Closing the reader releases its file handles.
  - The data type matches the source (SNAPHU float64).
- [ ] **Operator contract** (`OperatorContractTest`, parametrised over all `OperatorSpi`s with a minimal synthetic input):
  - **Tile-size invariance**: the output at tile sizes 64, 256 and 513 is identical (or within float tolerance). This catches the RangeFilter overlap, the Goldstein no-data strip and Object-Discrimination edge double counts.
  - **Thread determinism**: 1 thread versus N threads gives an identical result. This catches the CPM/GCP order, EnvisatOrbitReader singleton and ProductCache races.
  - **No-data propagation**: a no-data input region gives no-data output and never a finite value (Horizontal/Vertical Motion, PhaseToDisplacement, StackAveraging).
  - **Unit guard**: dB input is either refused or handled (Terrain Flattening, LogRatio, AdaptiveThresholding).
  - **Default parameters** initialise without exceptions, and every `@Parameter` label matches the UI.
  - **Bounded memory**: computing one tile allocates at most k × tile size (ties to §8.3).
- [ ] **Algorithm oracles**: per operator, a closed-form synthetic case. Examples: a point target through Terrain Correction lands at a known lat/lon; a known phase ramp through Interferogram gives a known fringe rate; an analytic covariance matrix gives known H/A/α. Many exist already (GSLC test pyramid, PhaseLinking). The register in 9.5 shows the gaps.

### 9.3 T2: regression on real data (snap-gpt-tests)
- [ ] Add a gpt test for **every shipped graph**: 97 graph XMLs under the `*-ui` modules' `graphs/` resources, against about 35 tests in `tests/s1tbx` today; the §3 bug "shipped TOPS graphs lack ESD" shows that the graphs themselves are untested.
- [ ] Add per-mission reader-to-Calibration-to-Terrain-Correction tests for each supported mission (S1, RS2, RCM, CSK/CSG, TSX/PAZ, ICEYE, Capella, SAOCOM, ALOS-2, NISAR, BIOMASS, ERS/ASAR). Today `missions/` holds only Sentinel1.
- [ ] Compare with tolerances, not MD5. Use per-band statistics plus sampled pixels with an explicit tolerance per test, so a legitimate change can be accepted deliberately (regenerate with a reason recorded).
- [x] ~~Wire the §8 heap sweep into the existing performance database (it already has `memory_max`; add `min_heap_mb` and `gc_time`).~~ Superseded 2026-11-20: the release heap sweep runs in `snap-validation` (§9.0).

### 9.4 T3: scientific validation (`snap-validation` repo; tracked by its plan, see §9.0)

T3 design (repo layout, cases, per-release scorecard): see `docs/superpowers/specs/2026-11-20-snap-validation-design.md`.
Each feature gets a **reference** (ground truth or peer tool), a **metric** and an **acceptance threshold** taken from mission requirements or the literature. Examples:

| Area | Reference | Metric / threshold |
|---|---|---|
| Radiometric calibration | Corner reflectors (Rosamond, Surat; add DLR, Australian CR array) | Absolute σ0 bias within ±0.5 dB; stability |
| Geolocation (TC, GSLC) | CR surveyed positions | ALE below 1 resolution cell; with ETAD below 0.2 m |
| Terrain Flattening / γ0 RTC | ISCE3 RTC, OPERA RTC-S1 | Bias and RMSE in dB per land-cover class |
| Coregistration | ISCE3/COMPASS CSLC | Residual offset in px; ESD residual |
| Interferogram / coherence | ISCE3, Dolphin | Circular std of the phase difference; coherence bias |
| Phase linking / SBAS | Dolphin, MintPy; P-SBAS Campi Flegrei; GNSS | Velocity RMSE in mm/yr against GNSS |
| Polarimetry | PolSARpro | H/A/α, Freeman/Yamaguchi powers: relative difference |
| Noise removal | S1 NESZ annotations; RCM noise | Residual noise floor; scalloping |
| Ocean (wind, ships) | ECMWF/ASCAT wind; AIS | Wind RMSE in m/s; detection probability / false-alarm rate |

- [x] (done 2026-11-21: moved to snap-validation; see 2026-11-20 design) Move `microwave-toolbox/validation/` into `snap-validation`, keeping the engines/compare/cases layout. Add `truth/` adapters (CR, GNSS, AIS). This is plan Task 1. The first wave needs only the CR and OPERA adapters; GNSS and AIS come later.
- [x] (done 2026-11-21: moved to snap-validation; see 2026-11-20 design) Move the corner-reflector tests (`sar-test-stacks` `CRValidation*`) into it as T3 cases. The Java CR tests, their resources and the `-Pcorner-reflectors` profile were deleted.
- [ ] Generate a **per-release validation report** (HTML/PDF): a pass/fail scorecard per feature, plots, the SNAP build and the peer-tool versions. This is the artifact for ESA and users.

### 9.5 Validation register
- [ ] Create `docs/validation/register.yaml` in microwave-toolbox, with static definitions next to the code and a drift check in `sar-contract-tests`. Results stay with the T2 and T3 runs and are joined at report time. It has one row per reader, operator and shipped graph, with:
  - T1, T2 and T3 status
  - reference
  - metric
  - threshold
  - last result and date
  - owner

  Seed it automatically from the `OperatorSpi`, `ProductReaderPlugIn` and graph lists so nothing is missing. The register is how "each feature" becomes trackable; the release gate is "no feature regressed a tier".
- [ ] Define the order of work: the most-used workflows first (S1 GRD → TC, S1 TOPS InSAR, Calibration, Speckle, PolSAR decomposition). The long tail of readers (§4) gets T1 contracts first, because those catch most of the known reader bugs cheaply.
