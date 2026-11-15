# Microwave Toolbox help review: core SAR processing, geometry, radiometry, analysis, feature extraction, general pages

Reviewed 2026-11-12. The repository was only read, not edited. All 54 in-scope `.html` pages were read in full. For every operator page, the `@Parameter` list in the Java source was extracted and compared with the page. Local `href`/`img` links were all resolved, as were all 46 `nbdocs://` cross-module links (checked against microwave-toolbox, snap-engine, snap-desktop and optical-toolbox). External URLs were probed with curl.

Paths are abbreviated as follows:
- `SP` = `sar-op-sar-processing-ui/src/main/resources/eu/esa/sar/sar/docs`
- `CAL` = `sar-op-calibration-ui/.../calibration/docs`
- `FEX` = `sar-op-feature-extraction-ui/.../fex/docs`
- `AN` = `sar-op-analysis-ui/.../analysis/docs`
- `OC` = `sar-op-analysis-ui/.../ocean/docs`
- `RCP` = `microwavetbx-rcp/.../microwave/dat/docs`
- `UT` = `sar-op-utilities-ui/.../utilities/docs`

Concept score: 0 = no concept content; 1 = one-line definition; 2 = explains the method but leaves non-experts without interpretation, pitfalls or context; 3 = good teaching page.

---

## (a) Per-page assessment

### A1. SAR processing: general pages (`SP/general`). None of these four is in `toc.xml` or `map.jhm`, so users cannot reach them from Help.

| Page | Concept | Parameter gaps | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| Overview.html | 0 | n/a | Titled "SENTINEL-1 Toolbox (S1TBX)". Lists "Automatic SRTM DEM download", "ERS-ASAR precise orbit handling (Doris, Prare, Delft)", "Integrated WorldWind", "Windows XP, Vista, 7 and 8" (l.174), and optical MERIS/AATSR as supported. No Copernicus DEM, no modern missions. Orphan page. | none | Moreira 2013; Torres 2012 (Sentinel-1 mission) |
| Sentinel1.html | 2 (good mode and product descriptions) | n/a | Describes a "constellation of two satellites, S1A and S1B... six day repeat" (l.22, l.36). S1B failed in Dec 2021; S1C launched in Dec 2024 and S1D in Nov 2025 (verify date). Pre-launch future tense: "Radar data will be delivered" (l.139). **Factually wrong**: "GRD products have thermal noise removed" (l.228). The IPF does not subtract noise; it annotates noise vectors, which is the reason ThermalNoiseRemoval exists. This contradicts `CAL/ThermalNoiseRemoval.html`. Dangling citation "[R11]" (l.67) with no reference list. | none (only [R11]) | Torres et al. 2012; De Zan & Monti Guarnieri 2006 (TOPSAR); Miranda & Meadows 2015 (calibration) |
| Sentinel1_Functionality.html | 0 | n/a | A bare list of tool names with no links. Title "S1TBX Help". | none | n/a |
| SupportedProducts.html | 0 | n/a | DEM list is GETASSE30/ACE/GTOPO30/SRTM/ASTER, with no Copernicus DEM (the default everywhere). Lists optical readers (MERIS, ATSR, AVNIR-2, PRISM, Landsat) that now live in the Optical Toolbox. Omits RCM, SAOCOM, ICEYE, Capella, NISAR, BIOMASS, KOMPSAT-5, PAZ, NovaSAR, Gaofen-3, StriX and others. Two `nbdocs://org.esa.snap.snap.geotiff//...` links have a double slash and do not resolve. | n/a | n/a |

### A2. SAR processing: operator pages (`SP/operators`)

| Page | Concept | Parameter gaps (code vs page) | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| RangeDopplerGeocodingOp.html | 2. Has a distortion figure and local-incidence-angle definitions. Missing: side-looking geometry, layover, foreshortening, shadow, meaning of the mask values, and when to choose σ0 vs γ0 vs β0. | Undocumented: `externalDEMNoDataValue`, `externalDEMApplyEGM`, `alignToStandardGrid`, `standardGridOriginX/Y`, `externalAuxFile`. `incidenceAngleForSigma0/Gamma0` appear only in prose. The image-resampling list says "nearest, bilinear, cubic and bi-sinc", but code also offers BISINC_5/11/21 and BICUBIC. | Says precise orbits are "only for ERS and ASAR" (l.40). Says "SRTM v.4 from JRC FTP xftp.jrc.it will automatically be downloaded" (l.50), but the default is Copernicus 30 m. Says "For Aster Dem height correction is not yet applied" (l.61), but `AsterElevationTile` applies EGM96, and the `RCP/tutorials/terrain_correction.html` l.78 says "already applied", so the two pages contradict each other. Says Copernicus heights are "referred to geoid EGM96". Copernicus DEM is actually EGM2008-referenced, and the code (`CopernicusElevationTile` l.57, `CopernicusDirectElevationTile` l.276) also converts with EGM96. The page should state this approximation; it is also a possible code issue. Sentinel-1 is not among the per-mission radiometric-normalisation sections, although the code has a Sentinel-1 calibrator path (l.329). The S-1 bistatic residual correction (code l.455, UZH-S1-GC-AD) is not mentioned. The dialog screenshot `range_doppler_dlg.JPG` shows SRTM 3Sec as the default and no APD checkbox. It presents Kellndorfer-style normalisation as "Radiometric normalization" with no pointer that true RTC (γ0T) is Terrain Flattening. | Small & Schubert 2008 ASAR guide; Schreier 1993; Rosich & Meadows 2004; Laur 2004; RS-2 PFD; TSX TN; Kellndorfer 1998 (named but not in the reference list) | Small & Schubert, Guide to S-1 Geocoding (UZH-S1-GC-AD); Meier et al. 1993; Kellndorfer 1998 full citation; Small 2011; Curlander & McDonough 1991; EGM96/EGM2008 refs |
| GSLCGeocodingOp.html | 3. The strongest page: explains phase flattening, deramp and workflows. | Undocumented: `gridSpacing` (SQUARE_COARSEST, NATIVE_ANISOTROPIC default, SQUARE_FINEST), `pixelSpacingInMeterY`, `pixelSpacingInDegreeY`, `outputAzimuthCarrier`, `outputPhaseTerms` (default true), `refBurstValidTimes`, `rangeOffsetPoly`, `azimuthOffsetPoly`. | Uses "master/slave" 26 times; the project convention is Reference/Secondary. **Workflow error**: "S-1 SM / ASAR IMS → Apply-Orbit-File → (optional) Thermal-Noise-Removal → GSLC" (l.321). `Sentinel1RemoveThermalNoiseOp` turns complex input into an `Intensity_*` band (`createTargetBandName` l.608-620; i²+q² at l.985), so the phase is destroyed and GSLC cannot run. Remove that step. Cites only a preprint. | Agram et al. 2022 preprint | Agram et al. 2022 in *Remote Sensing* 14(15):3524 (verify); Zebker 2017 GRSL (geocoded SLC); De Zan & Monti Guarnieri 2006; Small & Schubert S-1 geocoding guide |
| SARSimTerrainCorrectionOp.html | 2. Has layover/shadow mask coding and the 2-pass algorithm. | Undocumented: `mapProjection`, `alignToStandardGrid`, `standardGridOriginX/Y`, `saveLatLon`, `openResidualsFile`, `incidenceAngleForSigma0/Gamma0`. Says "Three interpolation methods are available: nearest neighbour, bi-linear, cubic and bi-sinc" (l.380). That names four, and the code valueSet has three (no bi-sinc). | **NEST-era paths** "C:\Program Files\NEST4A\auxdata\envisat" (l.133, 150, 170). SRTM via xftp.jrc.it (l.63). DEM list "ACE, GETASSE30, ASTER, SRTM 3Sec". Refers to the "GCP Selection Operator" and "Warp Operator" without links. Copies about 60% of the RD TC page, so the two drift apart. | Same as RD TC | Liu et al. 2004; Gelautz et al. 1998 (SAR simulation, verify) |
| SARSimulationOp.html | 1 | Undocumented: `externalDEMNoDataValue`, `externalDEMApplyEGM`, `isSARSimTC`, `reGridMethod`, `orbitMethod`, `saveDEM`, `saveZeroHeightSimulation`, `saveLocalIncidenceAngle`. | Garbled coordinate symbols "(Pא, Pא, Ph)" (Hebrew aleph entity). Formula "tc = t + r*2/c" (l.105), but code uses `zeroDopplerTime + slantRange / lightSpeed` (`SARSimulationOp.java` l.504), i.e. R/c. Does not say which backscatter model is used. | Liu, Zhao, "**Lezek**" 2004 (should be Jezek); Schreier 1993 | Correct the author name; Gelautz 1998 (verify) |
| GeolocationGridGeocodingOp.html | 1. Gives no reason to use ellipsoid correction instead of terrain correction. | Matches code (sourceBands, imgResamplingMethod, mapProjection). | Says the output "will be expressed in WGS84 latlong", contradicting the Map Projection parameter. Formula "Tc = T + R*2/C" (l.40) is likewise suspect (code uses R/c). | Small & Schubert 2008 | n/a |
| EllipsoidCorrectionRDOp.html | 1 | Inherits all RD TC parameters (`extends RangeDopplerGeocodingOp`) but documents three. | n/a | Small & Schubert 2008 | n/a |
| TerrainFlatteningOp.html | 2. Explains the motivation (classification). Never defines β0/σ0/γ0/γ0T, the reference-area normalisation, or that the output stays in radar geometry and needs TC afterwards. That chain appears only on the Compositing page. | Undocumented: `outputSigma0` (σ0T output), `outputSimulatedImage`, `nodataValueAtSea`, `applyAPDCorrection`. `externalDEMApplyEGM` defaults to **false** here but **true** in RD TC and GSLC; this is not explained and is a pitfall. | "Input should be calibrated beta0": the operator now auto-runs Calibration with `outputBetaBand=true` when input is uncalibrated (code l.202-207), and the page does not say so. **Citation error**: Small 2011 given as "Vol. 48" (l.71); the correct volume is 49(8):3081-3093. Says nothing about sensitivity to DEM quality or resolution, or about layover/shadow areas becoming no-data. | Small 2011 (wrong volume) | Ulander 1996; Small 2012 (compositing); Small & Schubert S-1 guide; Hoekman & Reiche 2015 (verify) |
| MultitemporalCompositingOp.html | 2 | None (operator has no parameters). | Good chain description. | Small 2012 IGARSS | n/a |
| SpeckleFilterOp.html | 1. One paragraph on speckle origin calls it "noise". Missing: multiplicative model, Rayleigh/exponential statistics, ENL, filter selection guidance, resolution trade-off, apply in linear power (not dB), and the effect on point targets and edges. No per-filter description. | Documents **"Edge Threshold" for Refined Lee, which was removed** (commented out in `SpeckleFilterOp.java` l.78-80; screenshot `speckle_filter_dlg_3.jpg` is Windows XP era). Undocumented: `estimateENL`, `enl`, `numLooksStr`, `windowSize` (5x5-17x17), `targetWindowSizeStr`, `sigmaStr` (Lee Sigma, which is the **default** filter), `anSize` (IDAN, default 50), MuLoG options. Size X/Y range (1,100] not stated. Code inconsistency: `estimateENL` is annotated `defaultValue="false"` but the field is initialised `true`. | Mansourpour 2006 URL (people.ucalgary.ca) likely dead. Lee & Pottier book title given as "Polarimetric SAR Radar Imaging: From Basic to Applications"; correct title is "Polarimetric Radar Imaging: From Basics to Applications". | Lee & Pottier 2009; Robinson 1977; Frost 1982; Mansourpour; Deledalle 2017 | Lee 1980, 1981, 1983; Lee et al. 2009 (improved sigma); Kuan 1985; Lopes et al. 1990 (Gamma-MAP); Vasile et al. 2006 (IDAN); Argenti et al. 2013 tutorial; Goodman 1976 |
| MultiTemporalSpeckleFilterOp.html | 2. Gives the equation. | **Wrong window sizes**: page says 3x3-11x11 (l.80), code says 5x5-17x17. Undocumented: `filter` (spatial filter applied per date, including Lee Sigma, IDAN and others), `estimateENL`, `enl`, `numLooksStr`, `targetWindowSizeStr`, `sigmaStr`, `filterSizeX/Y`, `dampingFactor`, `anSize`. | Author "T. L. Toan" should be "T. Le Toan" (l.87). Requires co-registered calibrated input. Does not mention stack depth N or co-registration accuracy. | Quegan et al. 2000 | Quegan & Yu 2001 |
| MultilookOp.html | 2. Explains intensity vs I/Q and phase pitfalls well. Missing: ENL increases with looks, that looks are averaged in linear power, and the resolution trade-off. | All five parameters covered. | n/a | Small & Schubert 2008 | Oliver & Quegan 1998; Curlander & McDonough 1991 |
| SRGROp.html | 1. No figure or explanation of why ground-range spacing is non-uniform. | Undocumented: `numRangePoints` (default 100). `warpPolynomialOrder` default 4 not stated. | n/a | none | Curlander & McDonough 1991 |
| MosaicOp.html | 1 | Lists "Bi-Sinc or Bi-Cubic" resampling (l.44), which the code does not offer (NN, bilinear, cubic). Calls the option "Normalizer" (l.52-54); code and UI call it `normalizeByMean`/"Normalize". Undocumented: `gradientDomainMosaic`, `maxIterations`, `convergenceThreshold`. Defaults not stated (NN, average=true). Code label typo "Feature (pixels)" for feather. | Example uses ASAR GM1 data. | none | Pérez et al. 2003 (Poisson / gradient-domain, verify) |
| ALOSDeskewingOp.html | 1 | Undocumented: `demName`, `useMapreadyShiftOnly`, `useFAQShiftOnly`, `useBoth`, `useHybrid` (the default mode is not explained at all). | n/a | none | n/a |
| BandPassFilterOp.html | 1. Does not explain why one would split the range spectrum (split-spectrum ionosphere or ΔK methods). | Parameter names given in code form (`subband`, `alpha`); defaults not stated (low, 1). | n/a | none | Gomba et al. 2016 (split-spectrum, verify) |
| BatchSnaphuUnwrapOp.html | 1 | Does not document `snaphuProcessingLocation` or `snaphuInstallLocation`. | Says "corresponding SNAPHU export help pages" without a link. | none | Chen & Zebker 2001/2002 (SNAPHU) |
| PyRateExportOp.html | 1 | Graph parameters not listed. | **Two `<title>` tags**, the first of which reads "Help - Batch Snaphu Unwrapping" (l.2, copy-paste). | PyRate URL (200) | n/a |
| SLCtoPRI.html | 0 | Graph; nothing listed. | Typo "sland range". | none | n/a |
| UpdateGeoRefOp.html | 1 | Covered. | Garbled aleph coordinates. DEM list (ACE, GETASSE30, SRTM 3Sec, ASTER) differs from the code valueSet, which includes the Copernicus 30 m default and SRTM 1Sec HGT. | none | n/a |
| WSSDeburstOp.html | 1 | `subSwath` undocumented. | ASAR only, which is fine. Typos. | none | n/a |
| WSSMosaicOp.html | 1 | Graph. | n/a | none | n/a |

### A3. Calibration (`CAL/operators`)

| Page | Concept | Parameter gaps | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| CalibrationOp.html | 2. Gives the purpose and the S-1 LUT equation. **Never explains what β0, σ0 and γ0 are** (reference area: slant plane, ground plane, plane perpendicular to the look direction), their relations (σ0 = β0·sinθ, γ0 = σ0/cosθ), or why γ0 is preferred. | Undocumented: `selectedPolarisations`, `outputImageInComplex` ("Save in complex"), `outputSigmaBand` (default true), `outputGammaBand`, `outputBetaBand`, `outputDNBand`, `externalAuxFile`. Documents only the "Create gamma0/beta0 virtual band" pair. "Scale in dB" (l.373) carries no warning not to filter, multilook, terrain-flatten or average dB data. | Intro still says "calibrations for ASAR, ERS, ALOS and Radarsat-2" (l.31). Supported list omits calibrators present in code: BIOMASS, Capella, NISAR, SAOCOM, Spacety, StriX (`calibrators/`). ICEYE listed as "SLC only". RADARSAT-2 cites the RADARSAT-1 spec RSI-GS-026 (l.396, verify). No S-1 reference. | Rosich & Meadows 2004; Laur 2004; Lavalle 2008; RSI-GS-026; TSX TN | Freeman 1992; Miranda & Meadows 2015 (S-1 cal TN); Ulaby & Long 2014; Small 2011 (β0/σ0/γ0 definitions) |
| RemoveAntennaPatternOp.html | 1 | Covered. | ASAR/ERS only, which is fine. | Rosich 2004; Laur 2004 | n/a |
| ThermalNoiseRemoval.html | 2. Excellent on clipping and bias. **Never defines NESZ**, typical levels (around -22 to -25 dB for IW, verify) or why noise matters (cross-pol, water, sub-swath scalloping). | `selectedPolarisations` undocumented. **Missing warning**: complex SLC input is converted to an Intensity band, so phase is lost. The page says "can be applied to Sentinel-1 Level-1 SLC" without this caveat. | Recent (2024-2025 TNs). | ESA MPC-0392; Recchia 2025 (verify); Mascolo 2022 (verify) | Park et al. 2018 (cross-pol TNR); Miranda & Meadows 2015 |
| RCMThermalNoiseRemoval.html | 2 | Fully covered. | **No menu entry**: `RCM-Thermal-Noise-Removal` is not in any `layer.xml`, so it is reachable only through GPT or Graph Builder, yet the TOC lists it. | MPC-0392 | RCM product spec (verify) |
| RemoveGRDBorderNoise.html | 3 | Fully covered, defaults correct. | Current. | MPC-0243; Ali et al. 2018; Stasolla & Neyt 2018 | n/a |
| importXCAProduct.html | 2 | n/a | Menu path given as "File → Import → SAR Auxiliary Data → ASAR XCA", but the actual menu is Radar → SAR Utilities → Auxiliary Files → "View ASAR XCA Product" (`layer.xml` l.86-97). The claim that SNAP's aux manager "downloads them on demand" needs verification. | n/a | Rosich & Meadows 2004 |

### A4. Feature extraction (`FEX/operators`)

| Page | Concept | Parameter gaps | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| ChangeDetection.html | 0. Does not say why the log ratio suits multiplicative speckle (ratio statistics are independent of the mean), that input must be linear intensity (if dB, the difference already is a log ratio), or how to choose thresholds. | All covered. Not stated: log ratio uses the **natural** log (`Math.log`, l.315), so the default ±2 thresholds mean a factor of e² ≈ 7.4 (≈ 8.7 dB). | No references at all. | none | Rignot & van Zyl 1993; Bazi, Bruzzone & Melgani 2005; Bovolo & Bruzzone 2005 (verify) |
| DecisionTreeOp.html | 0 | No parameters or output description. | n/a | none | n/a |
| GaborFilterOp.html | 2 | Covered (only `theta`). | Operator is `internal=true` but is on the menu. λ = 1.0 px is below the Nyquist limit of 2 px; worth a note. | Daugman 1985; Manjunath & Ma 1996 | Clausi & Jernigan 2000 (SAR sea-ice texture, verify) |
| ObjectDetection.html | 3. Good CFAR and windows explanation. | `estimateBackground` undocumented. PFA default (6.5) and window defaults (50/500/800 m) not stated. | Report path ".s1tbx/log" (l.203); code writes to `~/.snap/var/log` (`ResourceUtils.getReportFolder`). Assumes Gaussian clutter without mentioning K / Gamma / G0 clutter or the higher false-alarm rate in rough seas. Gives no input guidance (σ0 in linear units; HV best for ships). | Crisp 2004 | El-Darymli et al. 2013 (verify); Wackerman et al. 2001 (verify) |
| OilSpillDetectionOp.html | 1. **No physics**: never explains that oil damps capillary and short gravity (Bragg) waves, the wind window of about 3-10 m/s, or look-alikes (low wind, biogenic films, rain cells, grease ice, upwelling). | **Wrong unit**: page says "Background Window Size: ... in pixels" (l.92); code `backgroundWindowDim` is in **km** (default 0.5). Defaults not stated (k = 2.0 dB, min cluster 0.1 km²). | Copy-paste "producing multi-looked images" (l.89). | Solberg et al. 2004 IGARSS | Solberg et al. 1999; Brekke & Solberg 2005; Solberg et al. 2007; Alpers & Hühnerfuss 1988 (verify) |
| REACTIVChangeDetectionOp.html | 2 | Covered. | Does not say whether input should be amplitude or intensity, linear not dB. No explanation of the coefficient of variation. | Koeniguer et al. EUSAR 2018 | Koeniguer & Nicolas 2020 *Remote Sensing* 12(13):2089 (the actual REACTIV paper) |
| RPCAChangeDetectionOp.html | 2 | Covered. | "Lower lambda gives more detected targets": the direction depends on formulation (verify). | Schwartz et al. 2020 | Candès et al. 2011 |
| SpeckleDivergence.html | 1 | **No parameters or output documented** (`windowSizeStr`, default 15x15). | References only; no algorithm or workflow. | Esch 2010; Esch & Roth 2004; Stasolla & Gamba 2008 | n/a |
| WindFieldEstimationOp.html | 2. Missing: Bragg mechanism, VV/HH dependence, incidence-angle validity of CMOD5, coastal and low-wind limits, 180° ambiguity resolution with a numerical weather prediction (NWP) prior. | Defaults not stated (window 20 km). | **Stale**: "only supported for ERS and ENVISAT" (l.96), but code accepts ERS, ENVISAT, SENTINEL-1, RS2 (`WindFieldEstimationOp.java` l.149). Report path ".s1tbx/log/wind_field_report.xml" (l.130); actual is `~/.snap/var/log/<product>_wind_field_report.xml`. CMOD5 cited as a 2003 ECMWF report instead of the 2007 JGR paper. Polarisation-ratio formula has no source. Copy-paste "multi-looked images" (l.104). | Hersbach 2003 report; Wackerman 2003 | Hersbach, Stoffelen & de Haan 2007; Stoffelen & Anderson 1997; Hersbach 2010 (CMOD5.N, verify); Thompson et al. 1998 (polarisation ratio, verify); Koch 2004 |

### A5. Analysis and ocean (`AN`, `OC`)

| Page | Concept | Parameter gaps | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| AN/rcp/TimeSeries.html | 1. UI-only, written around SMOS. No SAR guidance: use one track or orbit, calibrate and terrain-correct, average in linear units. | n/a | Refers to "Figure 7", which does not exist. | none | n/a |
| OC/wave/PolarView.html | 1 | n/a | ASAR WV only. Says imagettes are "5 km by 5 km to 10 km by 5 km" and later "10 km x 10 km" (inconsistent). No S-1 WV/OCN OSW context. | ASAR handbook (200) | Hasselmann & Hasselmann 1991; Engen & Johnsen 1995 (verify) |
| OC/wave/ProjectView.html | 0 | n/a | **Duplicate** of `RCP/visat/ProjectView.html`. Not mapped (ocean `map.jhm` has only polarView), so it is an orphan. | n/a | n/a |

### A6. General and tutorials (`RCP`). Only AbstractedMetadata is in the TOC or map; **all other RCP pages are orphans**.

| Page | Concept | Parameter gaps | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| general/AbstractedMetadata.html | 1 | n/a | Example is ASAR. "Search Metadata menu item in the SAR Tools Menu" (that menu no longer exists). "BEAM DIMAP". | n/a | n/a |
| general/Definitions.html | 0 for SAR | n/a | Copied from the BEAM glossary: MERIS, AATSR, MODIS, NEST, EnviView, plus dead esa.it/ecsinfo links. Defines "Pixel value" as an RGB composite, which is misleading for SAR. **No SAR terms**: slant range, incidence angle, β0/σ0/γ0, NESZ, ENL, look, burst, SLC, GRD, zero-Doppler, layover. | n/a | n/a |
| tutorials/remote_sensing.html | 0 (link farm) | n/a | Dead or unreachable: Eduspace (404), earth.esa.int SARDOCS (404), esa.int esaMI InSAR manual (404), tiger.esa.int (timeout), cgeo.gc.ca (timeout), virtual.vtt.fi (timeout), eoscience.esa.int landtraining2017 (timeout). sarusersmanual.com and epsilon.nought.de respond but are legacy. | n/a | ESA "InSAR Principles" TM-19 (2007); NASA ARSET SAR training (verify); Moreira 2013 |
| tutorials/terrain_correction.html | 2 | n/a | **3 broken links**: `nbdocs://...sar.processing.ui/eu/esa/sar/insar/docs/operators/{RangeDoppler,EllipsoidCorrectionRD,GeolocationGrid}...html`; the path should be `.../sar/sar/docs/...`. SRTM via xftp.jrc.it; "C:\AuxData" (l.69); "Test Connectivity under Help" (l.72); recommends "SRTM 3 second DEM" as the best choice. Unit "dB/m^2" (l.439) is wrong: σ0 is dimensionless (m²/m²), shown in dB. "Figure 3" is used twice (l.441, l.455). Supported list is ASAR/ERS/RS2/TSX/CSK, with no S-1. | Same as RD TC | Same as RD TC |
| tutorials/coregistering_products.html | 1 | n/a | "master/slave" 22 times. Describes the "GCP Selection operator" and "From the SAR Tools menu, select Coregistration"; current names and menus differ (verify). No TOPS / BackGeocoding / ESD context. | none | Bamler & Hartl 1998 (verify) |
| visat/ProjectView.html | 0 | n/a | Search-and-replace damage: "The Project View is a ProjectView.html for managing your data products" (l.43). | n/a | n/a |
| visat/WorldMap.html | 0 | n/a | Duplicates `snap-help desktop/WorldMap.html`. | n/a | n/a |

### A7. Utilities (`UT/operators`)

| Page | Concept | Parameter gaps | Staleness / accuracy | Refs present | Refs missing |
|---|---|---|---|---|---|
| BandSelectOp.html | 1 | Undocumented: `sourceMaskNames`, `includeReferences` (default true). **XML example uses wrong element names**: `<polarisations>` and `<subImages>`; the fields are `selectedPolarisations` and `selectedSubImages` with no alias (`BandSelectOp.java` l.57-61), so the example graph silently ignores them. | n/a | n/a | n/a |
| OverSampleOp.html | 1. Doesn't say when oversampling is needed (e.g. before detection, to avoid aliasing in coherence or interferogram estimation). | Defaults (ratio 2.0, 12.5 m) not stated. | n/a | none | n/a |
| UnderSampleOp.html | 1. Sub-sampling aliases speckle; kernel filtering is the safer choice. Not said. | `filterType` (SUMMARY, EDGE_DETECT, EDGE_ENHANCEMENT, LOW_PASS, HIGH_PASS, HORIZONTAL, VERTICAL, USER_DEFINED) undocumented. The page says the kernel is "all 1's". | n/a | none | n/a |
| SetNoDataValueOp.html | 2 | Covered. | Good. | n/a | n/a |

---

## (b) Top cross-cutting gaps

1. **No conceptual foundation anywhere.** No page explains side-looking geometry, slant vs ground range, the incidence and local incidence angles, or layover, foreshortening and shadow (only a 4-value mask table in SARSimTC). Nothing covers the β0/σ0/γ0/γ0T definitions and conversions, dB vs linear (and that averaging, filtering and RTC must be done in linear units), speckle statistics, ENL or looks, or the geoid vs ellipsoid distinction. The only glossary (`RCP/general/Definitions.html`) is a BEAM/MERIS-era leftover with no SAR terms.
2. **Help-set structure is broken for the general content.** 4 SP general pages and 6 of the 7 RCP pages are in no `toc.xml`/`map.jhm`. The ocean ProjectView duplicate is orphaned. The two ProjectView copies and the WorldMap page duplicate each other or snap-help.
3. **DEM content is 10+ years stale and partly wrong.** SRTM auto-download from xftp.jrc.it, "C:\AuxData", and "C:\Program Files\NEST4A" paths appear on RD TC, SARSimTC, UpdateGeoRef, SARSim and the TC tutorial, while every operator now defaults to the Copernicus 30 m DEM. The ASTER "not yet applied" statement contradicts the code (EGM96 is applied). Copernicus DEM is described, and **converted in code**, with EGM96 although the product is EGM2008-based. The `externalDEMApplyEGM` default is inconsistent: false in Terrain Flattening, true in TC and GSLC.
4. **Copy-paste drift.** About 60% of RD TC is duplicated into SARSimTC and the TC tutorial, and the copies have diverged (NEST paths, different DEM lists, ASTER contradiction). The "multi-looked images" boilerplate appears in OilSpill and Wind. PyRate has a duplicate `<title>`.
5. **Parameter documentation lags code**, most severely for the default Speckle Filter (Lee Sigma) parameters, GSLC (8 new parameters), Calibration (6 output toggles), RD TC (standard-grid and external-DEM options), Terrain Flattening (`outputSigma0`), Mosaic (gradient domain) and ALOS Deskew (shift modes). Pages also document removed parameters: Refined Lee "Edge Threshold", Mosaic "Bi-Sinc/Bi-Cubic", MT-speckle window sizes. Defaults and units are rarely given, and the OilSpill window unit is wrong (pixels vs km).
6. **Workflow errors and absent chain context.** GSLC recommends Thermal Noise Removal before GSLC, but TNR destroys the phase. Neither TNR page warns that SLC input becomes intensity. The TF page doesn't say TC must follow, or that TF now auto-calibrates. Nothing describes a canonical GRD to γ0T analysis-ready backscatter chain (Orbit, Border Noise, TNR, Calibration β0, TF, TC, optional speckle filter, dB for display only).
7. **Mission information is stale.** S1A+S1B constellation; S-1 GRD described as "noise removed" (wrong); calibration intro lists ASAR/ERS/ALOS/RS2; Wind lists ERS/ENVISAT only. Supported-product lists omit about 10 current missions that have calibrators or readers in code.
8. **Terminology.** "master/slave" appears 48 times (GSLC 26, coregistering tutorial 22) against the Reference/Secondary convention. "S1TBX"/"NEST" branding remains in 5 pages and in report paths (".s1tbx/log").
9. **References** are dominated by 2004-2008 ESA technical notes. Foundational speckle, RTC, calibration and ocean GMF papers are missing. Several citations are wrong: Small 2011 volume (48 → 49), "Lezek" for Jezek, "T. L. Toan" for Le Toan, the Lee & Pottier title, and CMOD5 cited as a report instead of the JGR paper. Kellndorfer 1998 is named but not listed.
10. **Feature-extraction pages lack the physics.** Bragg scattering and damping (oil, wind), clutter distributions (CFAR), why ratio detectors fit multiplicative noise, and how to interpret outputs and false alarms are all missing. ChangeDetection, DecisionTree and SpeckleDivergence have essentially no explanatory content.
11. **Operators with no help page** (in-scope modules): `SAOCOM-Thermal-Noise-Removal` (also no menu); `MuLog-Filter` (standalone operator, author "AI", no menu or page; only reachable as a Speckle-Filter option with no documented parameters); `TileWriter` and `BandMerge` (have OperatorUIs in utilities-ui but no pages); `SubGraph`; the internal `Flood-Detection`, `Forest-Area-Classification`, `Forest-Area-Detection` and `Data-Analysis`. `AdaptiveThresholding`, `Object-Discrimination` and `Oil-Spill-Clustering` are documented only inside the graph pages. `RCM-Thermal-Noise-Removal` has a page but no menu action.
12. **Stale screenshots.** `speckle_filter_dlg_3.jpg` shows Windows XP and the removed Edge Threshold. `range_doppler_dlg.JPG` shows SRTM 3Sec as the default and no APD option. Most dialog images predate SNAP 8.

---

## (c) Recommended new concept pages (suggested TOC: "Radar → SAR Concepts")

1. **SAR Fundamentals**: what a SAR measures (complex reflectivity, amplitude, phase); range and azimuth resolution; SLC vs GRD; zero-Doppler geometry; bursts and TOPS; wavelength bands (X/C/L/P) and penetration; polarisation (HH/HV/VH/VV). Short, with links to Moreira et al. 2013.
2. **SAR Geometry and Geometric Distortions**: side-looking geometry; slant vs ground range (with a figure); incidence vs local incidence vs projected local incidence angle; foreshortening, layover and shadow (diagram); how the RD TC layover-shadow mask values (0-3) map to these; ascending vs descending; ellipsoid correction vs terrain correction and when each is appropriate.
3. **Radiometric Quantities and Calibration**: DN → β0 → σ0 → γ0 → γ0T, with reference-area figures and the formulas σ0 = β0·sinθ, γ0 = σ0/cosθ (ellipsoid) and Small's γ0T; NESZ and thermal noise; dB vs linear and the rule "average, filter and flatten in linear units; convert to dB only for display or statistics"; typical value ranges (water, vegetation, urban); recommended analysis-ready backscatter chain for S-1 GRD and SLC.
4. **Speckle and Multilooking**: speckle as coherent interference (not sensor noise); exponential (single-look intensity) and Gamma (L-look) statistics; ENL and how to estimate it; multilooking vs spatial vs multitemporal filtering; filter selection guide (Boxcar, Lee, Refined Lee, Lee Sigma, Gamma-MAP, IDAN, MuLoG, Quegan multitemporal) with trade-offs; apply in linear units; point targets.
5. **DEMs, Geoids and Height References**: ellipsoid (WGS84) vs geoid (EGM96, EGM2008) heights; which auto-downloaded DEMs carry which datum (Copernicus = EGM2008, SRTM = EGM96, ...); external DEM requirements and the "Apply EGM" switch; DEM resolution vs image resolution (for TF); DEM errors as a geocoding and RTC error source; polar coverage.
6. **Resampling and Interpolation for SAR**: nearest, bilinear, cubic, bisinc and bicubic; why complex data needs sinc and deramping; why detected data is resampled in linear power; aliasing when undersampling.
7. **Standard Processing Chains (recipes)**: GRD to γ0T backscatter; SLC to InSAR (classical TOPS and GSLC); change detection (log ratio, REACTIV, RPCA); ocean (wind, ships, oil); time-series compositing. Each with prerequisites and "what next" links. This would fix workflow context across all operator pages.
8. **Ocean SAR Primer**: Bragg scattering; wind GMFs (CMOD4, CMOD5, CMOD5.N) and the polarisation ratio; wind-direction ambiguity; oil damping and look-alikes; ship detection clutter and CFAR; wave spectra (S-1 WV, ASAR WV).
9. **SAR Change Detection Primer**: why ratios (multiplicative noise); log ratio vs difference in dB; threshold selection; temporal coefficient of variation (REACTIV); false alarms from incidence angle, soil moisture and wind.
10. **Supported Missions** (replaces the stale general pages): current mission list (S-1A/C/D, RCM, SAOCOM, NISAR, BIOMASS, ICEYE, Capella, Umbra, TSX/TDX, PAZ, CSK/CSG, ALOS-2/4, K5, NovaSAR, Gaofen-3, ...) with calibration support status, generated from the calibrator and reader list so it doesn't go stale.
11. **SAR Glossary**: replace `Definitions.html` with SAR terms (β0, σ0, γ0, NESZ, ENL, look, burst, deramp, zero-Doppler, PRF, swath, sub-swath, IPF, SLC/GRD/GSLC, Reference/Secondary, coherence, layover, ...).

---

## (d) Consolidated bibliography

Items marked "(verify)" are ones whose details I am not fully certain of.

**SAR fundamentals and textbooks**
- Curlander, J.C., McDonough, R.N. (1991). *Synthetic Aperture Radar: Systems and Signal Processing*. Wiley.
- Ulaby, F.T., Long, D.G. (2014). *Microwave Radar and Radiometric Remote Sensing*. University of Michigan Press.
- Moreira, A., Prats-Iraola, P., Younis, M., Krieger, G., Hajnsek, I., Papathanassiou, K.P. (2013). A tutorial on synthetic aperture radar. *IEEE Geosci. Remote Sens. Mag.* 1(1):6-43.
- Oliver, C., Quegan, S. (1998). *Understanding Synthetic Aperture Radar Images*. Artech House (reprinted SciTech 2004).
- Woodhouse, I.H. (2006). *Introduction to Microwave Remote Sensing*. CRC Press.
- Richards, J.A. (2009). *Remote Sensing with Imaging Radar*. Springer.
- Lee, J.S., Pottier, E. (2009). *Polarimetric Radar Imaging: From Basics to Applications*. CRC Press.

**Sentinel-1 and TOPS**
- Torres, R., et al. (2012). GMES Sentinel-1 mission. *Remote Sens. Environ.* 120:9-24.
- De Zan, F., Monti Guarnieri, A. (2006). TOPSAR: Terrain Observation by Progressive Scans. *IEEE TGRS* 44(9):2352-2360.

**Geocoding and DEMs**
- Schreier, G. (ed.) (1993). *SAR Geocoding: Data and Systems*. Wichmann, Karlsruhe.
- Meier, E., Frei, U., Nüesch, D. (1993). Precise terrain corrected geocoded images. In Schreier (ed.), *SAR Geocoding: Data and Systems*, 173-185. (verify pages)
- Small, D., Schubert, A. (2008). *Guide to ASAR Geocoding*. RSL-ASAR-GC-AD, Issue 1.0, Univ. Zurich.
- Small, D., Schubert, A. (2019). *Guide to Sentinel-1 Geocoding*. UZH-S1-GC-AD (verify issue; code cites v1.12).
- Liu, H., Zhao, Z., Jezek, K.C. (2004). Correction of positional errors and geometric distortions in topographic maps and DEMs using a rigorous SAR simulation technique. *Photogramm. Eng. Remote Sens.* 70(9):1031-1042. (verify pages)
- Lemoine, F.G., et al. (1998). *The Development of the Joint NASA GSFC and NIMA Geopotential Model EGM96*. NASA/TP-1998-206861.
- Pavlis, N.K., Holmes, S.A., Kenyon, S.C., Factor, J.K. (2012). The development and evaluation of the Earth Gravitational Model 2008 (EGM2008). *J. Geophys. Res.* 117:B04406.
- Zebker, H.A. (2017). User-friendly InSAR data products: fast and simple timeseries processing. *IEEE GRSL* 14(11):2122-2126.
- Agram, P.S., Warren, M.S., Calef, M.T., Arko, S.A. (2022). An efficient global scale Sentinel-1 radar backscatter and interferometric processing system. *Remote Sensing* 14(15):3524. (verify)

**Radiometric terrain correction**
- Small, D. (2011). Flattening Gamma: Radiometric terrain correction for SAR imagery. *IEEE TGRS* 49(8):3081-3093.
- Small, D. (2012). SAR backscatter multitemporal compositing via local resolution weighting. *Proc. IGARSS 2012*, 4521-4524.
- Ulander, L.M.H. (1996). Radiometric slope correction of synthetic-aperture radar images. *IEEE TGRS* 34(5):1115-1122.
- Kellndorfer, J.M., Pierce, L.E., Dobson, M.C., Ulaby, F.T. (1998). Toward consistent regional-to-global-scale vegetation characterization using orbital SAR systems. *IEEE TGRS* 36(5):1396-1411.

**Calibration and thermal noise**
- Freeman, A. (1992). SAR calibration: an overview. *IEEE TGRS* 30(6):1107-1121.
- Rosich, B., Meadows, P. (2004). *Absolute Calibration of ASAR Level 1 Products*. ENVI-CLVL-EOPG-TN-03-0010, Issue 1 Rev. 5.
- Laur, H., et al. (2004). *ERS SAR Calibration: Derivation of σ0 in ESA ERS SAR PRI Products*. ES-TN-RS-PM-HL09, Issue 2 Rev. 5f.
- Miranda, N., Meadows, P.J. (2015). *Radiometric Calibration of S-1 Level-1 Products Generated by the S-1 IPF*. ESA-EOPG-CSCOP-TN-0002.
- ESA MPC-0392, *Thermal Denoising of Products Generated by the S-1 IPF* (v2.3, 2024). ESA MPC-0243, *Masking "No-value" Pixels on GRD Products* (v2.2, 2023).
- Park, J.-W., Korosov, A.A., Babiker, M., Sandven, S., Won, J.-S. (2018). Efficient thermal noise removal for Sentinel-1 TOPSAR cross-polarization channel. *IEEE TGRS* 56(3):1555-1565.
- Ali, I., Cao, S., Naeimi, V., Paulik, C., Wagner, W. (2018). Methods to remove the border noise from Sentinel-1 SAR data. *IEEE J-STARS* 11(3):777-786.
- Recchia et al. (2025), *Remote Sensing* 17:3474; Mascolo et al. (2022), *IEEE GRSL* 19:4009105. Both are already cited on the TNR page. (verify)

**Speckle**
- Goodman, J.W. (1976). Some fundamental properties of speckle. *J. Opt. Soc. Am.* 66(11):1145-1150.
- Lee, J.S. (1980). Digital image enhancement and noise filtering by use of local statistics. *IEEE TPAMI* 2(2):165-168.
- Lee, J.S. (1981). Refined filtering of image noise using local statistics. *Comput. Graph. Image Process.* 15(4):380-389.
- Lee, J.S. (1983). Digital image smoothing and the sigma filter. *Comput. Vis. Graph. Image Process.* 24(2):255-269.
- Lee, J.S., Wen, J.H., Ainsworth, T.L., Chen, K.S., Chen, A.J. (2009). Improved sigma filter for speckle filtering of SAR imagery. *IEEE TGRS* 47(1):202-213.
- Frost, V.S., Stiles, J.A., Shanmugan, K.S., Holtzman, J.C. (1982). A model for radar images and its application to adaptive digital filtering of multiplicative noise. *IEEE TPAMI* 4(2):157-166.
- Kuan, D.T., Sawchuk, A.A., Strand, T.C., Chavel, P. (1985). Adaptive noise smoothing filter for images with signal-dependent noise. *IEEE TPAMI* 7(2):165-177.
- Lopes, A., Touzi, R., Nezry, E. (1990). Adaptive speckle filters and scene heterogeneity. *IEEE TGRS* 28(6):992-1000.
- Vasile, G., Trouvé, E., Lee, J.S., Buzuloiu, V. (2006). Intensity-driven adaptive-neighborhood technique for polarimetric and interferometric SAR parameters estimation. *IEEE TGRS* 44(6):1609-1621.
- Deledalle, C.-A., Denis, L., Tabti, S., Tupin, F. (2017). MuLoG, or how to apply Gaussian denoisers to multi-channel SAR speckle reduction? *IEEE TIP* 26(9):4389-4403.
- Quegan, S., Le Toan, T., Yu, J.J., Ribbes, F., Floury, N. (2000). Multitemporal ERS SAR analysis applied to forest mapping. *IEEE TGRS* 38(2):741-753.
- Quegan, S., Yu, J.J. (2001). Filtering of multichannel SAR images. *IEEE TGRS* 39(11):2373-2379.
- Argenti, F., Lapini, A., Bianchi, T., Alparone, L. (2013). A tutorial on speckle reduction in synthetic aperture radar images. *IEEE Geosci. Remote Sens. Mag.* 1(3):6-35.

**Ocean: wind, oil, ships, waves**
- Stoffelen, A., Anderson, D. (1997). Scatterometer data interpretation: estimation and validation of the transfer function CMOD4. *J. Geophys. Res.* 102(C3):5767-5780.
- Hersbach, H., Stoffelen, A., de Haan, S. (2007). An improved C-band scatterometer ocean geophysical model function: CMOD5. *J. Geophys. Res.* 112:C03006.
- Hersbach, H. (2010). Comparison of C-band scatterometer CMOD5.N equivalent neutral winds with ECMWF. *J. Atmos. Ocean. Technol.* 27(4):721-736. (verify)
- Thompson, D.R., Elfouhaily, T.M., Chapron, B. (1998). Polarization ratio for microwave backscattering from the ocean surface at low to moderate incidence angles. *Proc. IGARSS 1998*. (verify)
- Koch, W. (2004). Directional analysis of SAR images aiming at wind direction. *IEEE TGRS* 42(4):702-710.
- Valenzuela, G.R. (1978). Theories for the interaction of electromagnetic and oceanic waves: a review. *Boundary-Layer Meteorol.* 13:61-85.
- Solberg, A.H.S., Storvik, G., Solberg, R., Volden, E. (1999). Automatic detection of oil spills in ERS SAR images. *IEEE TGRS* 37(4):1916-1924.
- Brekke, C., Solberg, A.H.S. (2005). Oil spill detection by satellite remote sensing. *Remote Sens. Environ.* 95(1):1-13.
- Solberg, A.H.S., Brekke, C., Husøy, P.O. (2007). Oil spill detection in Radarsat and Envisat SAR images. *IEEE TGRS* 45(3):746-755.
- Crisp, D.J. (2004). *The State-of-the-Art in Ship Detection in Synthetic Aperture Radar Imagery*. DSTO-RR-0272.
- El-Darymli, K., McGuire, P., Power, D., Moloney, C. (2013). Target detection in synthetic aperture radar imagery: a state-of-the-art survey. *J. Appl. Remote Sens.* 7(1):071598. (verify)
- Hasselmann, K., Hasselmann, S. (1991). On the nonlinear mapping of an ocean wave spectrum into a SAR image spectrum and its inversion. *J. Geophys. Res.* 96(C6):10713-10729. (verify)

**Change detection and texture**
- Rignot, E.J.M., van Zyl, J.J. (1993). Change detection techniques for ERS-1 SAR data. *IEEE TGRS* 31(4):896-906.
- Bazi, Y., Bruzzone, L., Melgani, F. (2005). An unsupervised approach based on the generalized Gaussian model to automatic change detection in multitemporal SAR images. *IEEE TGRS* 43(4):874-887.
- Colin Koeniguer, E., Nicolas, J.-M. (2020). Change detection based on the coefficient of variation in SAR time-series of urban areas. *Remote Sensing* 12(13):2089.
- Candès, E.J., Li, X., Ma, Y., Wright, J. (2011). Robust principal component analysis? *J. ACM* 58(3):11.
- Esch, T., et al. (2010). Delineation of urban footprints from TerraSAR-X data by analyzing speckle characteristics and intensity information. *IEEE TGRS* 48(2):905-916. Already cited.

**Unwrapping**
- Chen, C.W., Zebker, H.A. (2001). Two-dimensional phase unwrapping with use of statistical models for cost functions in nonlinear optimization. *J. Opt. Soc. Am. A* 18(2):338-351.

---

### Appendix: link-check results
- Local `img`/`href`: all resolve.
- nbdocs: 3 broken in `RCP/tutorials/terrain_correction.html` (`.../sar/insar/docs/...` should be `.../sar/sar/docs/...`) and 2 broken in `SP/general/SupportedProducts.html` (`org.esa.snap.snap.geotiff//`, double slash). The optical-reader links resolve only when the Optical Toolbox is installed.
- External links returning 404 or timing out (2026-11-12): esa.int Eduspace, earth.esa.int SARDOCS, esa.int esaMI InSAR manual, tiger.esa.int, cgeo.gc.ca, virtual.vtt.fi, eoscience.esa.int landtraining2017. Links that respond but are probably legacy or parked: sarusersmanual.com, esa.it/export/esaCP, ecsinfo.gsfc.nasa.gov.
