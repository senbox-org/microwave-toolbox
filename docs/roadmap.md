# Microwave Toolbox Roadmap: Parked Work

Date: 2026-11-13. This list collects every item parked during the JavaHelp overhaul and its Phase 8 code-defect pass. None of these items has been changed in the working tree.

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
- [ ] **[Bug]** QPS-SAR reader hard-codes 9650 MHz (actual 9.38 GHz) and VV.
- [ ] **[Bug]** The EOS-04 and RISAT-1 readers both claim any zip containing `BAND_META.txt`. There is no EOS-04 CEOS reader.
- [ ] **[Bug]** UAVSAR reader accepts `.dat` files and reads them as garbage float32.
- [ ] **[Bug]** Basic CEOS reader records every product's mission as `RS1`.
- [ ] **[Bug]** SNAPHU reader creates `q_band` as float32 even for float64 data.
- [ ] **[Bug]** ETAD reader geocoding is a simple lat/lon box.
- [ ] **[Bug]** CIMR reader has no fallback for an unknown `format_version`.
- [ ] **[Bug]** No reader for ESA's reprocessed ERS-1/2 and Envisat ASAR heritage products, if they are distributed in a new format.

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
