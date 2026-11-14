# JavaHelp overhaul: parked code items and release notes

Generated 2026-11-13 from the final whole-plan review. Nothing in the parked list has been changed in the working tree. The release notes list user-visible behaviour changes made by the Phase 8 code fixes (all uncommitted).

## 6. Parked / open items (consolidated; nothing below is changed in the working tree)

The parked list is complete only once it is merged with the brief's 10 items. I found no item that is claimed fixed but is not. I checked each FIXED row of the P8 reports against the diff: Gabor theta, the TOPSAR/ETAD texts, IEM unit, Mosaic/GG/SARSim kernel lists, SnaphuExport statCostMode description, PhaseLinking/ClosurePhase/SBAS Javadoc, AzimuthFilter metadata check, and RangeFilter nlMean. P8-jlinda review M3 is still open; it is I2 above.

**A. Brief "Parked for the user"** (unchanged):

- TF `externalDEMApplyEGM` default false
- Warp detected-stack default 0.05
- Copernicus DEM EGM96 vs EGM2008 (snap-engine)
- S1 virtual Beta0/Gamma0 consistency
- Bistatic reference range (first pixel vs mid-swath)
- Phase Linking default SHP window
- CrossCorrelation coherence/Powell path unreachable
- PCAOp boolean "1" defaults
- Faraday Ω band
- MuLog exposure

**B. Parked during Phase 8:**

- RangeFilterOp `fftLength`: inert, overwritten by the tile width.
- RangeFilterOp multi-Secondary/dual-pol design: the Reference matches only the last pair.
- RangeFilterOp: no TOPS guard (added to AzimuthFilter only).
- PolarimetricParameters: Coh_/Phase_ are not windowed.
- PolCal: no cross-pol imbalance; co-pol imbalance assumes equal HH/VV power; π ambiguity of arg f.
- TF ACE2_5Min 1000 m vs 10000 m.
- GeolocationGrid 2R/c vs R/c (needs real ASAR).
- SAOCOM TNR power vs amplitude units.
- LUT table i/127 vs JAI i/128 (WarpOp).
- TestWarpOp has only smoke tests.
- GSLC: flattened + topo path loads an unused DEM (P8-insar re-review N2).

**C. Readers (Phase 7):**

- GenericReader multi-band reads band 1 for all bands.
- ALOS-2 GeoTIFF: amplitude only, hard-coded frequency/PRF, no orbits, ZIP broken.
- ALOS-4 GeoTIFF: fake orbit and fixed frequency/PRF/pass.
- CSG GeoTIFF has no import action.
- Gaofen-3: no calibrator; L2 not recognised.
- QPS-SAR hard-codes 9650 MHz and VV.
- EOS-04 and RISAT-1 both claim zips with BAND_META.txt; no EOS-04 CEOS reader.
- UAVSAR accepts `.dat` and reads it as garbage float32.
- BasicCeos mission is always RS1.
- SNAPHU import: q_band is float32 for float64 data.
- ETAD geocoding uses a lat/lon box.
- CIMR: no fallback for an unknown `format_version`; cimr-reader is not in the `microwavetbx-kit` pom (does it ship?).

**D. Utilities (P8-docs):**

- TileWriter: NPE with `numberOfTiles=2`; overlap is not clipped.
- BandMerge ignores `sourceBands`.
- Object-Discrimination double-counts targets at tile edges.

**E. "Needs code" lines from Phases 2 to 6 that were never addressed (not in the brief, not fixed):**

Geometry, DEM and calibration:

- GSLC layoverShadowMask: thresholds 5°/85° and coding 1/2/4 (TC uses 0/1/2/3).
- Five operators always add EGM96 to an external DEM; SubtRefDemOp's `applyEGM` call is commented out; SARSimTC ignores the SARSim EGM switch.
- TerrainFlattening: no dB-input check.
- Multilook "Output Intensity" default for complex input.
- Sentinel1Calibrator virtual Gamma0/Beta0 (also in A).

InSAR and time series:

- EmpiricalTropoCorrection: coherence pairing fails, and it corrects wrapped phase.
- HorizontalVerticalMotion default reference pixel (0,0).
- PhaseToDisplacement has no no-data value.
- SnaphuImport depends on input order.
- SBAS: check coherence up front; key pairs beyond two dates.
- ESD on a single burst silently applies no azimuth shift.
- BIOMASS orbit option unreachable.
- Shipped TOPS graphs lack ESD and Multilook, and hard-code Sentinel precise orbits.
- TLogSelector critical value 2.326 at α 0.025, normal vs t.
- Oversample assumes zero Doppler for non-ERS/ENVISAT.
- StackAveraging: no median; no-data in virtual bands unverified.
- `InSARStackOverview.java:51-53` constants comment.
- MultiMasterOp metadata.
- Coherence pair ordering by orbit string.

Coregistration:

- TOPSARSplit `subswath` has no valueSet (only the description was fixed).
- Warp: label "RMS Threshold" vs significance level, and a silent fallback to the RMS rule (both documented, not fixed).
- ETAD dialog label vs @Parameter label.

Polarimetry and soil moisture:

- PolarimetricDecompositionOpUI has no OAC checkbox.
- DpDecomposition/DpFactorization NESZ default −16 dB vs about −22 dB for S1 IW.
- Cameron has no FULL-input guard.
- HaAlphaDescriptor stale TODO.
- Soil-moisture graphs save the ellipsoid incidence angle, not the local one.

Change detection and feature extraction:

- LogRatio change detection zeroes dB input; ±2 thresholds on all outputs.
- REACTIV saturation tuned for amplitude.
- RPCA mask flags brightening only; threshold in input units.
- AdaptiveThresholding and OilSpillDetection accept a dB band.

**F. Other repo:** `guides-docs-tutorials` GSLC-Tutorial.md:87 is outdated about flattened output.

## 7. Release notes: user-visible behaviour changes

### Changed outputs

**Interferometry**

- **Interferogram, GSLC stacks:** on a fully phase-flattened GSLC stack the flat-Earth and topographic phase are no longer subtracted a second time. A stack that mixes flattened and natural-phase GSLCs is refused. Natural-phase stacks are unchanged.
- **Create Stack (GSLC auto-coregistration):** Secondaries that Create Stack geocodes now use the Reference's settings instead of the defaults:
  - DEM, including an external DEM file, its no-data value and EGM setting
  - DEM resampling method
  - Solid Earth Tide and troposphere settings

  If the Reference's external DEM file is missing on this machine, Create Stack stops with an error.
- **GSLC Terrain Correction:** the DEM metadata stamp names the external file only when "External DEM" is selected.
- **Horizontal/Vertical Motion:** the sign is flipped on both outputs. Uplift and eastward motion are now positive, as in Phase to Displacement and SBAS.
- **Phase to Elevation:** bistatic TanDEM-X CoSSC stacks (`bistatic_stack`) now give heights twice as large about the reference height, i.e. the correct ones. Before, they gave half the true relief. Repeat-pass output is unchanged.
- **SBAS Inversion:**
  - Pairs named later date first are negated, which changes epoch phase, displacement, velocity and closure for such stacks.
  - `closure_phase_rms` is NaN (no-data) where there is no usable triplet. It used to be 0.
- **Goldstein Phase Filtering (adaptive):** coherence no-data no longer counts as low coherence, so blocks near no-data are no longer over-filtered.
- **Offset Tracking:** averaged offsets and velocities are now deterministic and equal the true box mean. Before, they depended on thread order and were over-smoothed.
- **Stack Averaging:** the coefficient of variation is now std/mean of the samples. Before, it was computed on squared samples and divided by N again (for example 0.079 → 0.408).
- **Warp / Cross-Resampling:**
  - The "Cubic convolution (4 points)" kernel now works. Before, it gave NaN weights.
  - The complex-data outlier removal drops the GCP with the largest summed azimuth+range w-test. Before, it used range only. Surviving GCPs and the warp can differ.
- **Range Filtering:** the SNR-threshold fallback now reuses the last good shift.
- **Azimuth Filtering:** filtering the Reference is no longer disabled for the rest of the session after a multi-Secondary run.

**Unwrapping**

- **Batch SNAPHU Unwrap:**
  - Unwrapped bands carry data (they were all zero), with unit `abs_phase`.
  - Coherence and other non-phase bands are kept, so the output feeds SBAS directly.
  - A missing SNAPHU output file is an error.
  - SNAPHU is downloaded only if the folder has no executable. macOS gets an error instead of the Linux binary.

**Polarimetry**

- **Polarimetric Parameters:**
  - Span = trace(T3), half the earlier value.
  - Eigenvalue RVI is corrected.
  - Detected RVI follows Kim & van Zyl.
  - Ratios and indices use power, so they are the squares of the earlier values.
  - Quad-pol `Phase_VVVH` changes sign.
  - DUAL_VH_VV channel swap is fixed.
  - Per-band units are set.
  - Dual-pol GRD intensity input now works.
  - Intensity stacks are computed per date.
  - A C2 product with VV+VH metadata gets `VVVHRatio`/`Coh_VVVH`/`Phase_VVVH` instead of the HH/VV names.
- **Polarimetric Calibration:**
  - The estimator was rewritten. The channel imbalance is now applied (it was always 1).
  - Repeated (Ainsworth) passes converge instead of adding cross-talk.
  - Results differ from earlier versions.
- **Cross-Channel SNR Correction:** HV/VH are scaled by √γ instead of γ, so cross-pol power is higher by 1/γ (for example +3 dB at SNR 0 dB).
- **van Zyl decomposition:** surface and double-bounce are assigned by the sign of Re⟨HH·VV*⟩. On pixels with negative Re C13, Ps and Pd swap.
- **Cameron decomposition:** τ uses the Hermitian product, so symmetric targets give τ = 0°.
- **Krogager:** the unit label changes from `intensity_db` to `amplitude`. Values are unchanged.

**Other operators**

- **IEM soil-moisture inversion:** the RDC band unit changes from "Farad/m" to dimensionless.
- **Wind Field Estimation:** CMOD5 c9–c11 restored (Hersbach 2007), which removes a bias of about +0.6 m/s.
- **Gabor Filter:** in GPT/graphs `theta` now defaults to 0.6 rad, as in the dialog. Before, it was 0.
- **Multitemporal Compositing:** bands are paired by acquisition date, not band order. Bands without `_ref`/`_sec`, or without a simulated image, raise a clear error.
- **SAOCOM Thermal Noise Removal:**
  - SLC phase is preserved: i and q get the same factor.
  - `selectedPolarisations` is honoured.
  - Removing twice, re-introducing without removal, or selecting both is refused.

### Refused inputs (new errors)

- Interferogram: mixed flattened/natural GSLC stack.
- Create Stack: the Reference's external DEM file is missing.
- Azimuth Filtering: Sentinel-1 TOPS (IW/EW), including merged/debursted products.
- Batch SNAPHU: a missing `UnwPhase*.snaphu.hdr`; macOS without an installed SNAPHU.
- Multitemporal Compositing: unpairable bands.
- SAOCOM TNR: double removal, re-introduction without removal, both options, unknown polarisation.
- Range Filtering: even "Walking Mean Window" values (10/20) are no longer offered. They always failed at run time.

### Changed labels, descriptions and defaults

Labels:

- GSLC: "Output phase-flattened complex data (not for InSAR)" → "Output phase-flattened complex data" (operator and dialog).
- Speckle Filter / Multi-Temporal Speckle / Polarimetric Speckle Filter: Lee Sigma "Point target window Size" → "Sigma"; "Eqivalent" → "Equivalent".
- Phase to Elevation dialog title → "Phase to Elevation".

Defaults:

- Gabor `theta` 0.6 in GPT.
- Range Filtering `doWeightCorrel` default "false" (was the invalid "off").
- Range Filtering `nlMean` value set is odd only (5–25).

Descriptions and messages:

- GSLC: SET, troposphere, flattening and offset-sign descriptions.
- New parameter descriptions: RD/SARSim incidence-angle choices, Warp `excludeMaster`, SubGraph, DecisionTree, IEM LUT, SnaphuExport `statCostMode`.
- Operator descriptions: CrossChannelSNR, DpDecomposition, DpFactorization, TOPSARDerampDemod, TOPSARSplit `subswath`.
- ETAD error text.

### Menus, readers and help

- New menu entries: Radar > Radiometric > RCM Thermal Noise Removal and SAOCOM Thermal Noise Removal (next to S-1 TNR).
- PyRate Export and Batch Snaphu Unwrap menu entries are now well formed; check that they appear (M3).
- Multi-reference stack generator ("Split stack into multiple references") now uses its custom dialog (MultiMasterOpUI); see I2.
- Coregistered GCP Movement Vector layer works again: it reads `ref_`/`sec_`, falling back to `mst_`/`slv_`.
- RCM reader: the file filter offers `.safe`/`.zip` (`manifest.safe` is visible) instead of `xml`/`zip`.
- Help:
  - New concept pages, workflow pages and the Getting Started page.
  - Help sets now registered: microwavetbx-rcp and the ocean help.
  - New reader pages: ALOS-4, Basic CEOS, CIMR L1B, S1 ETAD, SNAPHU, Supported Missions.
  - New operator pages: SAOCOM TNR, GRD Post-Process, TileWriter, BandMerge.
  - Removed or merged: the old tutorials, Definitions, WorldMap, ImportERSCeos/Ers2/TanDEM-X/Envi/ImageIO, SupportedProducts and the S1 general pages.
  - Reader help IDs renamed: `importRadarsat1/2/RCM` → `ImportRadarsat1/2/RCM`; ALOS-2/4, EOS-04, ERS, TanDEM-X now point to their own pages.
