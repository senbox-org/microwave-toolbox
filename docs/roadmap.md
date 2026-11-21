# Microwave Toolbox Roadmap: Parked Work

Date: 2026-11-13. Sections 1–7 collect every item parked during the JavaHelp overhaul and its Phase 8 code-defect pass. Ticked items are fixed; each has a dated note saying what changed. Unticked items that carry a "Checked … not changed" note were looked at and deliberately left alone, for the reason given in the note.

**2026-11-20 quick-fix pass** (branch `roadmap-quick-fixes`): PhaseToDisplacement no-data (§3), Basic CEOS mission (§4), InSARStackOverview comment (§5), AzimuthFilter SAOCOM TOPS guard (§7.1), Batch SNAPHU https and the stale PyRateExportUI registration (§7.4). A second pass the same day fixed the six S-sized items: Horizontal/Vertical Motion no-data (§7.3), RangeFilter tile padding (§7.1), the CPM crash (§7.1), Batch SNAPHU on Linux (§7.4), Goldstein coherence pairing (§7.3) and the Multi-Reference metadata sharing (§7.1). An adversarial review the same day found a MultiMaster memory regression from that pass, the WarpOp stack-wide fallback, and the SNAPHU install-file case; all three are fixed, and the remaining SNAPHU findings are in §10. The QPS-SAR frequency (§4) and the TOPSARSplit valueSet (§5) were checked and are not safe one-liners; see the notes on those items.

Sections 8 and 9 (added 2026-11-17) are new workstreams, not parked items: **§8 Memory efficiency** and **§9 Validation framework**. Section 10 (added 2026-11-20) collects SNAPHU download and install issues, to be taken up with the other SNAPHU work.

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
- [ ] **[Decision]** Sentinel-1 calibrator: the virtual Beta0/Gamma0 use sin θ, while Sigma0 comes from the LUT. Under radiometric normalisation the two are inconsistent. *Confirmed still open 2026-11-20 (SNAP-4377 did not touch this path). In Terrain Correction with radiometric normalisation, `Sentinel1Calibrator.applyCalibration` ignores the projected local incidence angle it is given (RangeDopplerGeocodingOp.java:1156-1158) and returns the ellipsoid σ0 from the LUT, while `CalibrationFactory.createBetaNoughtVirtualBand`/`createGammaNoughtVirtualBand` divide that by sin(θ_proj). Beta0 and Gamma0 are therefore off by sin θ_ell / sin θ_proj (0 on flat ground, about +1.1 dB on a 10° slope facing the radar at 39°), and Sigma0 is ellipsoid-based although other calibrators (e.g. ERS: sin θ_loc / sin θ_ref) normalise to the projected angle. Suggested fix: calibrate S1 to β0 with its own LUT and multiply by sin θ_proj, as the other calibrators effectively do. Plain Calibration is not affected: CalibrationOp skips the virtual bands for S1 and uses the β0/γ LUTs.*
- [ ] **[Decision]** Bistatic reference range: the code uses `slant_range_to_first_pixel`; the S1 geocoding guide uses the central sub-swath mid-range.
- [ ] **[Decision]** PCAOp boolean parameters use `"1"` as their defaultValue.
- [ ] **[Decision]** Faraday Rotation: wire up the Ω output band, or drop it permanently.
- [ ] **[Decision]** MuLog is annotated as an operator but is not runnable or exposed. Expose it or remove the annotation.
- [ ] **[Decision]** Multilook "Output Intensity" default for complex input: coherent i/q averaging is the current default.
- [ ] **[Decision]** DpDecomposition/DpFactorization NESZ default −16 dB vs about −22 dB for S1 IW. Note that it is a C11 threshold.

## 2. Cross-repository

- [x] **[Cross-repo]** **Copernicus DEM vertical datum.** `snap-engine CopernicusElevationTile:57` converts with EGM96, but GLO-30 heights are EGM2008. *Fixed 2026-11-20 on snap-engine branch `copernicus-egm2008` (1eccd23ed0): new `EarthGravitationalModel2008` (5' grid derived from NGA's 2.5' grid; 5 mm RMS, 29 cm worst case against it), used by both `CopernicusElevationTile` and `CopernicusDirectElevationTile`. EGM2008 − EGM96 is 0.72 m RMS, 3 m at the 99th percentile, up to 12 m (eastern Himalaya). **Pending:** upload `egm2008_5min_cm.zip` (13.2 MB, regenerate with `snap-dem/tools/make_egm2008_5min.py`) to https://step.esa.int/auxdata/dem/egm2008/; until then the tiles fall back to EGM96 with a one-time warning, so behaviour is unchanged. Follow-up: `DEMFactory` fills DEM no-data at sea with the EGM96 geoid for every DEM, including Copernicus.*
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
- [x] **[Bug]** PhaseToDisplacement sets no no-data value. *Fixed 2026-11-20: NaN no-data; source no-data and NaN pixels map to NaN.*
- [ ] **[Bug]** RangeFilterOp with several Secondaries: the filtered Reference matches only the last pair, only one Secondary per polarisation reaches the output, and dual-pol stacks never filter the Reference. Interim fix: refuse more than one Secondary per Reference; full support needs a redesign (see §7.1).
- [ ] **[Bug]** RangeFilterOp has no TOPS guard; AzimuthFilter now has one.
- [ ] **[Bug]** TLogSelector uses critical value 2.326 at α = 0.025, and the normal instead of the t distribution.
- [ ] **[Bug]** StackAveraging has no median. (No-data inclusion is fixed; see §7.3.)
- [ ] **[Bug]** Coherence pairs are ordered by orbit string.
- [ ] **[Bug]** MultiMasterOp metadata issues (see the Phase 2 ledger notes).
- [ ] **[Bug]** BIOMASS Precise (MAAP) orbit option is unreachable, and it throws on non-BIOMASS products.
- [ ] **[Bug]** The shipped TOPS coregistration/interferogram graphs lack ESD, the Deformation/DEM-generation graphs lack Multilook, and the graphs hard-code Sentinel precise orbits.
- [ ] **[Bug]** WarpOp LUT: table rows are built at f = i/127 but JAI reads them at i/128, up to 1/128 pixel off.

### Polarimetry and soil moisture
- [ ] **[Bug]** Polarimetric Calibration does not estimate cross-pol imbalance. The co-pol imbalance estimate assumes equal HH/VV power, and arg f is ambiguous by π. A design note is in the P8-rstb report.
- [ ] **[Bug]** Cameron decomposition has no guard for FULL (quad-pol) input.
- [ ] **[Bug]** The PolarimetricDecomposition dialog has no OAC (orientation-angle correction) checkbox.
- [ ] **[Bug]** The soil-moisture graphs save the ellipsoid incidence angle, not the local one.
- [ ] **[Bug]** SAOCOM thermal noise removal: the noise LUT may be power while the data are amplitude (per the CONAE spec). Fix this in the reader's intensity labelling once confirmed.

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
- [ ] **[Bug]** QPS-SAR reader hard-codes 9650 MHz and VV. *Checked 2026-11-20, not changed: the frequency is unsettled. iQPS publishes 9.6 GHz, and WMO OSCAR lists both 9.38 GHz (instrument page) and 9.65 GHz (satellite page). Take it from a real product's metadata.*
- [ ] **[Bug]** The EOS-04 and RISAT-1 readers both claim any zip containing `BAND_META.txt`. There is no EOS-04 CEOS reader.
- [ ] **[Bug]** UAVSAR reader accepts `.dat` files and reads them as garbage float32.
- [x] **[Bug]** Basic CEOS reader records every product's mission as `RS1`. *Fixed 2026-11-20: read from the scene record's "Sensor platform mission identifier", falling back to `CEOS`. No calibrator dispatches on `RS1`. Side effect: a product whose field reads e.g. `ERS1` or `ALOS` now takes that mission's calibrator, orbit and deskew paths (ERSCalibrator, ApplyOrbitFileOp, ALOSDeskewingOp) instead of being treated as unsupported, and values are not normalised to the `Missions` names. Not yet run on a real CEOS product; no unit test yet (feasible with the synthetic records in `records/BaseRecordTest`).*
- [ ] **[Bug]** SNAPHU reader creates `q_band` as float32 even for float64 data.
- [ ] **[Bug]** ETAD reader geocoding is a simple lat/lon box.
- [ ] **[Bug]** CIMR reader has no fallback for an unknown `format_version`.
- [ ] **[Bug]** No reader for ESA's reprocessed ERS-1/2 and Envisat ASAR heritage products, if they are distributed in a new format.

## 5. Hygiene: labels, comments and tests

- [ ] **[Hygiene]** TOPSARSplit `subswath` has no valueSet; only its description was fixed. *Checked 2026-11-20, not changed: Six graph files carry 12 empty `<subswath/>` elements: four shipped (sar-op-insar-ui TOPSAR Coreg Interferogram and TOPSAR Slices Coreg Interferogram; sar-op-sentinel1-ui Sentinel1-TOPS-Coregistration and Sentinel1-TOPS-ESD-Coregistration) and two benchmark test graphs. A valueSet rejects `""` (it lets only `null` through), so first confirm that graph parsing turns the empty element into `null`.*
- [ ] **[Hygiene]** S1 ETAD Correction: the dialog labels (`S1ETADCorrectionOp.java:76-82`) differ from the GPF `@Parameter` labels.
- [ ] **[Hygiene]** HaAlphaDescriptor has a stale TODO.
- [x] **[Hygiene]** `InSARStackOverview.java:51-53`: the comment claims the C-band model constants match Sentinel-1 IW; they are ERS-era values. *Fixed 2026-11-20.*
- [ ] **[Hygiene]** TestWarpOp has only smoke tests; add tests for the CPM outlier path and the CC4P kernel through WarpOp.
- [ ] **[Hygiene]** PolarimetricDecomposition van Zyl golden values are regenerated but only run with `../testData`; check them on the test-data machine.
- [ ] **[Hygiene]** GSLC: write explicit stamps for tide and troposphere instead of relying on the Processing_Graph record that Create Stack reads (prerequisite for §7.2 items B and C).
- [ ] **[Hygiene]** Create Stack bias estimation still uses a hard-coded Copernicus DEM (see §7.2 D).
- [ ] **[Hygiene]** HorizontalVerticalMotion uses (0, 0) as the default reference pixel (see §7.3: a no-data reference is accepted). *2026-11-20: a no-data reference is now refused (§7.3), so the default fails loudly instead of silently.*

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
- [x] **[Bug]** CPM crashes WarpOp with IndexOutOfBounds when there are fewer GCPs than polynomial unknowns, or when the Reference and Secondary offsets are identical. Three causes: `numObservations` is kept while the lists are empty, the identity test sums `|dx+dy|` instead of `|dx|+|dy|`, and `rms` is not filled when estimation is skipped. S. *Fixed 2026-11-20: |dx|+|dy|; `numObservations` = 0 when the lists stay empty; the identity path records the offsets as residuals so `rms` is filled. Tests in CPMTest. Review round 1 (same day): CPM is invalid unless there is at least one redundant GCP (`n > u`; the outlier tests divide by `n - u`), outlier removal stops at `n = u + 1`, and WarpOp skips an invalid CPM per Secondary instead of letting it throw, which had dropped the CPM fit for every Secondary.*
- [x] **[Bug]** RangeFilterOp leaves the top and bottom (nlMean-1)/2 lines of every tile unfiltered, because `TILE_OVERLAP_Y` is never set. Pad the tile rectangle and crop. S. *Fixed 2026-11-20: the source rectangle is padded by (nlMean-1)/2 lines above and below and cropped on output. Test: output is independent of tile height for high-SNR data. Caveat: a line below the SNR threshold reuses the last good shift, and that state restarts at 0 in every tile, so low-SNR areas (water, forest) can still show tile seams. This predates the fix; a longer warm-up padding would remove it.*
- [x] **[Bug]** RangeFilterOp crashes (IllegalArgumentException) when the last tile row has fewer lines than nlMean. Fixed by the same padding. XS. *Fixed 2026-11-20 by the same padding (test: 10-line tile with nlMean 15).*
- [ ] **[Bug]** AzimuthFilterOp: the last matching band wins regardless of polarisation, so dual-pol stacks silently lose one polarisation. Loop per polarisation, as RangeFilterOp does. M.
- [x] **[Bug]** AzimuthFilterOp refuses only Sentinel-1 TOPS; SAOCOM TOPSARNarrow/Wide still pass. Also refuse modes starting with "TOPS". XS. *Fixed 2026-11-20, with a test; the decision below is still open.* Decision: other ScanSAR modes? Move the TOPS detection into a shared helper in snap-engine?
- [ ] **[Bug]** Azimuth/Range Filter maps are keyed by ABS_ORBIT. Missions without an orbit number all get 99999, so Secondaries collide and are dropped. Key by acquisition date and polarisation. S.

- [x] **[Bug]** Split stack into multiple references (MultiMasterStackGenerator): the pair products share the source product's metadata elements instead of copying them, so edits to one pair's metadata affect the others. S. *Fixed 2026-11-20 in `MultiMasterOp`: deep clones of Original_Product_Metadata and both date elements. `addElement` takes ownership, so the old code also re-parented the source's own elements into the pairs. Review round 1: each pair is now built inside its worker (under a lock on the source) and released after writing; building all pairs up front kept N metadata copies alive until the end. Pairs are not disposed, because their bands share the source's images.*

### 7.2 GSLC, Create Stack and Interferogram
- [ ] **[Bug] A.** Interferogram removes the TOPS carrier-model difference (and sets up the seam steps) only when flat-Earth or topographic subtraction is ticked. With both off, the per-burst carrier surface stays in the interferogram. Move carrier-band discovery out of `setupGSLCReferencePhase`. XS-S. Decision: this also changes natural-phase output with both flags off [yes, with a system-property escape hatch].
- [ ] **[Bug] B.** Flattened GSLC legs made with different tide, troposphere or DEM settings are not refused, and a troposphere mismatch alone leaves hundreds of radians. Stamp `gslc_solid_earth_tide`/`gslc_tropospheric_correction` in GSLCGeocodingOp and compare them in Interferogram. M. Decision: [refuse on tide/troposphere, warn on DEM, warn when stamps are missing on old products].
- [ ] **[Bug] C.** Create Stack stacks a same-grid Secondary as-is without comparing DEM, tide or troposphere with the Reference. Add a model-match check and fall through to the existing re-geocode path, which already forwards the Reference's settings. S-M, after B. Decision: re-geocode or refuse? **Needs real data** for end-to-end testing.
- [ ] **[Bug] D.** Create Stack TOPS bias estimation hard-codes the Copernicus 30m DEM. Pass the Reference's DEM settings through to Back-Geocoding, keeping Copernicus as the fallback; warn when the Reference used EGM off. S. **Needs real data** for end-to-end ESD.
- [ ] **[Bug] E.** Create Stack bias estimation and rebuild failures silently become zero bias, with nothing recorded on the product. Distinguish failure from zero, write a per-Secondary `gslc_bias_status`, and add a `failOnBiasEstimationError` option. M. Decision: [record and warn by default; fail-on-error option for gpt; Interferogram warns on FAILED].
- [ ] **[Hygiene]** GSLC dialog drift: some labels differ from `@Parameter` labels; there are no tide/troposphere controls; the SQUARE_FINEST preview shows slant range while the operator uses ground range.

### 7.3 InSAR displacement, time series and filtering
- [x] **[Bug]** Horizontal/Vertical Motion does not propagate no-data. Where only one pass has data, the 0-filled phase/incidence still gives a finite, wrong value. Return NaN when any input is no-data or the 2x2 system is near-singular. S. *Fixed 2026-11-20: NaN where any input is NaN or its band's no-data, or where |det| < 1e-3 (asc/desc geometry gives |det| ~ 1).*
- [x] **[Bug]** Horizontal/Vertical Motion accepts a no-data (0-filled) reference pixel, so neither interferogram is referenced. This is typical at the default (0,0) corner of terrain-corrected products. Reject no-data references. XS. Decision: [keep the (0,0) default but fail loudly when it is no-data]. *Fixed 2026-11-20: a reference pixel equal to the band's no-data value is refused; the (0,0) default is kept.*
- [ ] **[Bug]** Phase to Elevation: one NaN seed makes the whole product NaN with no error, and 0-filled seeds bias the fit. Drop no-data seeds and throw on a non-finite reference height. S. Decision (optional): a robust 3·MAD seed fit (M).
- [ ] **[Bug]** SBAS: an epoch with no usable pair, or a group cut off from the reference, gets Tikhonov damping and `ok=true`. It is then written as 0 mm or an arbitrary offset and feeds the velocity fit. Use union-find on the active pairs and write NaN for disconnected epochs. M. Decision: [per-epoch NaN; optional flag band].
- [ ] **[Bug]** SBAS does not scale: two SVDs per pixel (about 10^7 flops/pixel at 100 dates) and about 600 MiB of source tiles per tile at 300 pairs. Replace with one Cholesky solve in a reused workspace (about 30-100x faster), and size tiles from the pair count. M. Keep the SVD solver as a fallback and test oracle.
- [x] **[Bug]** Stack Averaging counts per-date no-data (0) as real samples. Make the expressions validity-aware, honouring each band's own no-data value. S-M. Decision: minimum number of valid dates. *Fixed 2026-11-20: every statistic counts only dates that are not NaN and not their band's own no-data value, and divides by the per-pixel count of valid dates. New parameter `minValidDates` (default 1; Standard Deviation and Coefficient of Variation need at least 2); fewer valid dates give no-data. Tests in TestStackAveragingStatistics.*
- [ ] **[Bug]** Stack Averaging on i/q stacks computes SD/CV per component, which is meaningless. XS (refuse) or M (compute on intensity). Decision: [refuse non-mean statistics on i/q].
- [x] **[Bug]** Goldstein uses the first coherence band for adaptive alpha (on by default) and for the coherence mask of every interferogram pair in a multi-pair product. Match `coh<tag>` to each `i_<tag>` pair. S. Single-pair graphs are unchanged. *Fixed 2026-11-20: each `i_<productTag><tag>` pair uses `coh<tag>`; a lone coherence band is still used whatever its name; with several bands and no match, the mask fails and adaptive alpha falls back to the fixed alpha for that pair.*

### 7.4 SAR processing (Batch SNAPHU, compositing)
- [ ] **[Bug]** Batch SNAPHU: operators that copy the band image at their own initialize (MergeOp, BandSelectOp) still read zeros, because the band image is swapped in doExecute. Keep the operator image and copy the SNAPHU data in `computeTile` from the ENVI reader; reads stay lazy. M. This has been verified with a scratch probe.
- [x] **[Bug]** Batch SNAPHU on Linux: the unzipped binary is not executable, so it is rejected, re-downloaded every run, and then fails with a NullPointerException. Match the exact name `snaphu`, `setExecutable(true)` after unzip, and throw a clear IOException. S. The help page's "downloaded once and reused" is false on Linux until this is fixed. *Fixed 2026-11-20: exact name match (the archive also holds src/snaphu.c, man/snaphu.1, config/snaphu.conf.*), execute bit granted after unzip and on reuse, clear IOException when no binary is found. The grant path is only exercised on Unix (test skipped on Windows). Review round 1: an install location that is a file is never used as a download folder; a non-executable `snaphu` file is made executable, anything else gets a clear error. Remaining SNAPHU findings: §10.*
- [x] **[Bug]** Batch SNAPHU downloads an executable over plain http with no checksum. Switch to https (the files are byte-identical; verified). XS. *Fixed 2026-11-20 (byte-identity re-checked by SHA-256 for all three archives).* Decision: pin SHA-256 hashes? [no, unless ESA publishes them].
- [x] **[Hygiene]** `sar-op-sar-processing-ui` layer.xml:431-434 registers `PyRateExportUI`, a class that no longer exists, so SNAP logs SEVERE at every start. Delete the entry. XS. *Fixed 2026-11-20.* (The help index still lists "PyrateExport (GPT operator)", but no such operator exists.)
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
- [ ] Add a **heap sweep** runner: run each workflow at `-Xmx` 2/3/4/6/8/12/16 GB and record pass/fail, wall time and GC time (from `-Xlog:gc`). Then find the minimum heap by bisection. M.
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

**Goal.** Show for every feature (reader, operator, shipped graph) that it (a) runs correctly, (b) is comparable to or better than other tools, and (c) is scientifically correct. Publish the evidence per release.

### 9.1 Three tiers, three homes

These are three different questions. Each needs different data and a different runtime, so each lives where its runtime already is.

| Tier | Question | Data | Runs | Lives in |
|---|---|---|---|---|
| **T1 Contract** | Does it behave correctly on known input? | Synthetic plus tiny committed fixtures | Every commit (CI) | **microwave-toolbox** (unit tests plus long-gated integration tests, as now) |
| **T2 Regression** | Does the shipped build give the same answer on real data, through gpt and graphs? | Real products, GB scale, on the test-data server | Nightly / per RC | **snap-gpt-tests** (already ESA's gpt runner; already has `s1tbx/` tests, expected outputs and a performance DB) |
| **T3 Scientific** | Is it right against ground truth, and how does it compare with peer tools? | Reference sites, truth data, peer-tool outputs | Per release / quarterly | **New repo `microwave-validation`**: move `microwave-toolbox/validation/` there, plus the corner-reflector tests from `sar-test-stacks` |

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
- [ ] Wire the §8 heap sweep into the existing performance database (it already has `memory_max`; add `min_heap_mb` and `gc_time`).

### 9.4 T3: scientific validation (microwave-validation repo)
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

- [ ] Move `microwave-toolbox/validation/` into `microwave-validation`, keeping the engines/compare/cases layout. Add `truth/` adapters (CR, GNSS, AIS).
- [ ] Move the corner-reflector tests (`sar-test-stacks` `CRValidation*`) into it as T3 cases. They are already excluded from the default build (`-Pcorner-reflectors`).
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

---

## 10. SNAPHU (group with the upcoming SNAPHU work)

Found by the adversarial review of 2026-11-20 (branch `roadmap-quick-fixes`). None is a regression from that branch, but the Linux download path, which now works, reaches several of them for the first time. Related items elsewhere: SnaphuImport input order (§3), SNAPHU reader `q_band` data type (§4), Batch SNAPHU → SBAS and PyRate end-to-end runs (§6), MergeOp/BandSelectOp reading zeros (§7.4).

- [ ] **[Bug] [Cross-repo]** Download under headless gpt. `FileDownloader` (snap-engine) drives a `javax.swing.ProgressMonitor`; a download longer than about 2 s may try to open a dialog and throw `HeadlessException`. It is a RuntimeException, so `downloadFile` does not delete the partial zip. **Not confirmed**: run Batch SNAPHU with gpt on a headless Linux box with an empty install folder. Fix: a non-Swing download path for operators.
- [ ] **[Bug]** An interrupted unzip leaves a truncated binary that is reused on the next run: `ZipUtils.unzip` skips entries that already exist, and the reuse path now makes the file executable and runs it. Unzip into a temporary folder and move it into place, or check the binary's size.
- [ ] **[Bug]** The install folder is searched recursively, twice. Pointing it at a large folder (a home directory) is slow, an unreadable subfolder can throw an `UncheckedIOException` that is not caught, and any stray file named `snaphu` would be made executable and run. Search only the archive layout (`<folder>/snaphu-v*/bin/`).
- [ ] **[Bug]** `setExecutable(true)` sets the owner bit only. On a shared install owned by another user it returns false, and the binary is downloaded again into the same folder.
- [ ] **[Hygiene]** The downloaded zip is never deleted, and the download ignores operator cancellation.
- [ ] **[Hygiene]** A missing install folder is reported as "not writeable"; say that it does not exist (or create it).
- [ ] **[Hygiene] [Cross-repo]** `ZipUtils.unzip` does not check entry paths (zip slip). Only https protects the SNAPHU download today.
- [ ] **[Hygiene]** Test gaps: the execute-bit tests are skipped on Windows, so they run only on Linux CI (check that CI runs them), and the download, unzip and "no binary found" path has no test because the downloader cannot be injected.
