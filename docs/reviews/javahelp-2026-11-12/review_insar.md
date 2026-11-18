# Microwave Toolbox: InSAR help documentation review

**Date:** 2026-11-12. **Repo:** `E:\ESA\microwave-toolbox` (read-only; nothing in it was edited).

**Scope (37 pages)**
- `sar-op-insar-ui/src/main/resources/eu/esa/sar/insar/docs/operators/`: 24 pages. Below this is **INS/**.
- `jlinda/jlinda-nest-ui/src/main/resources/org/jlinda/nest/docs/operators/`: 13 pages. Below this is **JL/**.
- The `toc.xml`, `map.jhm` and `layer.xml` of both modules.

**Method**
- Read every page in full.
- Extracted the `@Parameter` fields from each operator class (`sar-op-insar/.../gpf/**`, `jlinda/jlinda-nest/.../{gpf,dataio}/*`) with a script and compared them with the parameters each page documents.
- Checked every `img src`, relative link and `nbdocs://` link against the files on disk and the module code-name bases.

**Concept score:** 0 = no concept explanation · 1 = one or two sentences · 2 = partial (mechanism or maths, but no interpretation or pitfalls) · 3 = good for a non-expert.

---

## (a) Per-page assessment

### A. `sar-op-insar-ui` (INS/)

| Page | Concept | Parameter gaps (code vs. page) | Staleness / accuracy / hygiene | Refs present | Refs missing (key) |
|---|---|---|---|---|---|
| **Coregistration.html** (overview) | 2 | n/a (overview). Gives no guidance on which coregistration path to use for which sensor or mode. | L38 claims the chain does "downloading and interpolation of the a-priori DEM". That is false for CreateStack→Cross-Correlation→Warp unless `demRefinement` is on. L91 "only complex data is supported" contradicts step 4 (L168–170) and CreateStack/Warp, which handle detected data. TOPS (Back-Geocoding + ESD) and DEM-Assisted Coregistration are not mentioned, although they are the recommended paths for S1 and modern stripmap. Typos: "inteferometric", "digitial", "differnt", "performend". The orphan image `images/coregistration_diagram.jpg` is never referenced. | none | Hanssen 2001 (§ coregistration); Scheiber & Moreira 2000; Sansosti et al. 2006; Yagüe-Martínez et al. 2016 |
| **CreateStackOp.html** | 1 | `initialOffsetMethod` (Orbit / Product Geolocation, `CreateStackOp.java:123`) is **undocumented**. No defaults are given (`resamplingType=NONE`, `extent=Master`). `autoCoregisterGSLC` and `skipBiasEstimation` are documented. | L39 says "three different resampling methods" but lists four. **master/slave** at L87–95 (GSLC section: "slave GSLC", "master-to-slave offset", "master SLC"). The **broken link** at L79 uses `nbdocs://eu.esa.microwavetbx.sar-op-sar-processing-ui/...`; the module code name is dotted (`eu.esa.microwavetbx.sar.op.sar.processing.ui`), as every other page uses. Empty anchors at L73 and L75. The page never says that with "NONE" the stack is only a pre-alignment and that Cross-Correlation + Warp still have to follow. | none | none essential (could cite Sansosti 2006 for the geometric approach) |
| **CrossCorrelationOp.html** | 2 | **Undocumented:** `inSAROptimized` ("Optimize for InSAR"), `fineRegistrationWindowAccAzimuth`, `fineRegistrationWindowAccRange`, `fineRegistrationOversampling`, `computeOffset` ("Estimate Coarse Offset"), `onlyGCPsOnLand`. No defaults are given (numGCP 2000, coarse window 128, fine window 32, coherence threshold 0.6, max iterations 10, GCP tolerance 0.5). There is no guidance on sizing windows for S1, ERS or high-resolution data. | The radio-button label "Compute Coherence with Sliding Window" is now "Use coherence sliding window". "Apply fine Registration" defaults to **false**, but the page implies fine registration happens for complex data. No accuracy requirement is stated (about 1/8 px stripmap, about 1/1000 px TOPS azimuth). Typos in the reference: "eidition", "Willaim". | Numerical Recipes (Powell) | Bamler & Hartl 1998; Hanssen 2001; Just & Bamler 1994 (coherence and registration accuracy) |
| **DEMAssistedCoregistrationOp.html** | 0 | **Undocumented:** `externalDEMNoDataValue`, `tileExtensionPercent` (50), `outputRangeAzimuthOffset`. Defaults are missing (DEM = Copernicus 30 m, DEM resampling = BICUBIC, image resampling = BISINC_5). | The DEM list (L62 ff.: ACE2_5min, ACE30, GETASSE30, SRTM 1Sec Grid…) is **stale**: the default is now Copernicus 30 m and the list omits it. The "DEM Assisted Coregistration with XCorr" menu action (`layer.xml:175–183`) reuses this help ID, but the page never describes the XCorr variant. Typo "restitude" (L36). It does not explain what geometric coregistration is, its accuracy limits (orbit, DEM, timing) or when to add cross-correlation. | none | **Sansosti et al. 2006** (geometric registration) |
| **DemodulateOp.html** | 0 | `excludeReference` (default true) is undocumented. | No explanation of why demodulation or deramping is needed: SLC spectra are not at baseband (squint, Doppler centroid, TOPS azimuth steering), so complex interpolation without deramping destroys phase. Unclosed `<p>`. No workflow position (when to use it with Warp or with an external resampler). | none | De Zan & Monti Guarnieri 2006 (TOPS); Miranda 2017 S-1 deramping TN (verify ID); Prats-Iraola et al. 2012 |
| **RemodulateOp.html** | 0 | No parameters in the code, so nothing to compare. | The same concept gap as Demodulate. It does not state that it must pair with Demodulate in the same graph. | none | as Demodulate |
| **EmpiricalTropoCorrectionOp.html** | **3** | All 8 parameters are documented with defaults and ranges. **The best page in scope.** | "matched by master/slave date suffix" (L60). The figure "30–80 % of the tropospheric phase variance" has no source. It does not say whether the fit is done before or after removing a deformation reference. | Doin 2009; Yunjun 2019; Yu 2018 (GACOS) | Jolivet et al. 2011; **Bekaert et al. 2015** (comparison of tropo corrections); Hanssen 2001 (atmosphere chapter) |
| **GoldsteinFilterOp.html** | 1 | **Severely stale: 1 of 9 parameters documented.** Undocumented: `useAdaptiveAlpha` (**default true**, Baran 2003), `alphaMin` (0.2), `alphaMax` (1.0), `FFTSizeString` (64), `fftSizeMeters` (metres, overrides FFT size; recommended for S1 because a 64×64 px block is about 224 m × 895 m), `windowSizeString` (3), `useCoherenceMask`, `coherenceThreshold` (0.2). | The one documented parameter, "Adaptive Filter Exponent" (L36), is **ignored by default** because adaptive alpha is on; the code falls back to the fixed alpha only when no coherence band exists. The UI screenshot `goldstein_phase_filtering_ui.jpg` is outdated. It does not warn that filtering alters the phase and should be used for unwrapping and visualisation, not for measuring phase. It does not compare the operator with the jLinda `PhaseFilter` "goldstein" method. | Goldstein & Werner 1998 | **Baran et al. 2003** (the adaptive alpha actually implemented); Li et al. 2008 (verify) |
| **HorizontalVerticalMotionOp.html** | 1 | `refPixelX` and `refPixelY` are documented, but without saying what a reference pixel is or how to choose one (a stable, coherent point). No defaults (0,0 is the image corner, usually a poor choice). | It does not state the key assumption that north–south motion is negligible because polar orbits are nearly blind to it. No sign convention (is East positive? is Up positive?). No warning that both interferograms must span the same time interval. It requires the `incidenceAngleFromEllipsoid` band but only hints at that. Unclosed `<p>`. | none | **Wright, Parsons & Lu 2004**; Fialko et al. 2001; Fuhrmann & Garthwaite 2019 |
| **InSARStackOverview.html** | 2 | `InSAROverviewOp.overviewJSONFile` is described only loosely. | L44 "values … typical for ERS and Envisat". The model constants (critical baseline, temporal decorrelation, Doppler) are **ERS-era**, and no S1, TSX or NISAR guidance is given. It does not say where the tool is in the menu (Radar → Interferometric → InSAR Stack Overview, `InSARStackOverviewAction.java:29`). The JSON screenshot is 1191 px wide. | none | **Zebker & Villasenor 1992**; **Kampes 2006** (stack-coherence reference selection); Hanssen 2001 |
| **IntegerInterferogramOp.html** | 2 | No parameters (q1 and q2 are searched in [−3, 3], `IntegerInterferogramOp.java:188`). The page does not say this. | **There is no `<h3>` title** (the page starts with `<br>`). It cites "**[R13]**" (L25) but has **no reference list**. It never states the input contract: two coregistered interferograms with flat-earth and topographic phase removed using the same DEM (from the class Javadoc). There is no workflow or menu context (it is a graph action, `layer.xml:218`). | dangling [R13] | **Massonnet, Vadon & Rossi 1996** (IEEE TGRS, "Reduction of the need for phase unwrapping…") (verify volume and pages); Massonnet & Feigl 1998 (cited in the code) |
| **IonosphericCorrectionOp.html** | 1 | Defaults are missing: `sigma` = 81 px, `coherenceThreshold` = 0.6, `minCoherence` = 0.2. There is no guidance on what sigma means physically (the ionosphere is smooth over km scales) or how to pick it relative to multilook factors. | No physics: the ionosphere is dispersive (phase ∝ TEC/f), split-spectrum separates dispersive from non-dispersive phase, the effect is much stronger at L-band than C-band, and it matters at high latitudes, the equator and solar maximum. No BandPassFilter sub-band settings are given. Typos "kenerl", "Guassian" (L87–88). The workflow is otherwise good (TOPS and stripmap steps). | Gomba et al. 2016 | **Fattahi, Simons & Agram 2017**; Brcic et al. 2010 (verify); Rosen, Hensley & Chen 2010 (verify); Meyer et al. 2006 (verify) |
| **MultiReference.html** ("Split stack into multiple references") | 1 | `outputFolder` is not mentioned. | **Factually wrong.** L20 and L35 say the output is "one product per acquisition acting as the reference, each with N−1 secondaries". `MultiMasterOp.splitSingleMasterProduct` actually writes **N−1 consecutive-date reference/secondary pair products** (A-B, B-C, C-D…; `MultiMasterOp.java:59–66` and the loop `for x < dates.size()-1`), intended for PyRate. L65 says Multi-Reference InSAR "consumes the multi-reference outputs". It does not; `MultiMasterInSAROp` takes the single coregistered stack directly. **master/slave** in prose: L23 "single global master", L26 "master entry", "multi-master", "original master". Hygiene: `layer.xml:128` carries `targetProductNameSuffix="_pca"` (copy-paste), and `layer.xml:398` binds `MultiReferenceOpUI` to operator name `MultiReferenceStackGenerator`, but the alias is `MultiMasterStackGenerator`, so the custom UI never attaches. | none | Berardino 2002 (small-baseline network concept) |
| **MultiReferenceInSAROp.html** | 1 | Defaults are missing: orbit degree 4, coherence window 10×10, include* = true. The DEM and `addElevation` parameters are documented. The "Max Doppler / spatial / temporal baseline diff" items are UI sliders, not `@Parameter`s; the page does explain that GPT uses `pairs`. | It does not explain why one would want multiple references: small spatial and temporal baselines reduce decorrelation, and the result is a network for SBAS. It does not explain the vertical wavenumber k<sub>z</sub> (= 2π/HoA) or how to use it. The title says "Multi-Reference" but the GPT alias is `MultiMasterInSAR`; the page does not mention the alias. The `mm.png` screenshot is 153 KB with no caption. | none | Berardino 2002; Hanssen 2001 (k<sub>z</sub> and height of ambiguity) |
| **OffsetTrackingOp.html** | 1 | **Undocumented:** `registrationWindowAccAzimuth` and `registrationWindowAccRange` (16), `registrationOversampling` (16), `resamplingType`, `spatialAverage`, `fillHoles`, `roiVector`. Defaults are missing (grid 40 px, window 128, xCorr threshold 0.1, max velocity 5 m/day, radius 4). | It is framed only for glaciers ("glacier velocity", L23–25). It is equally valid for landslides, dunes and earthquakes. It never explains what offset tracking measures: 2-D displacement in range and azimuth from amplitude correlation, with accuracy of about 1/10–1/20 px and much coarser than InSAR. It does not explain intensity versus coherence tracking, or the trade-off between temporal baseline and maximum measurable velocity. The output velocity unit and the direction convention are not given. | none | **Strozzi et al. 2002**; Michel, Avouac & Taboury 1999; Gray et al. 1998 (verify) |
| **PCAOp.html** | 2 | **Undocumented:** `selectEigenvaluesBy` (threshold or number), `numPCA`. | The operator is categorised "Raster/Image Analysis" and appears in **map.jhm but not toc.xml**, so it cannot be found from Help contents. In L39–41 the example "(a1+a2) >> 80%" should be "≥". Code bug noticed: `showEigenvalues` and `subtractMeanImage` have `defaultValue = "1"` on a Boolean (`PCAOp.java:84,87`). | none | Richards & Jia, *Remote Sensing Digital Image Analysis* (verify edition) |
| **PhaseLinkingOp.html** | 2 | Everything is documented with defaults, except `outputTempCoherence` and `outputShpCount`, which appear only under Output. | Expert register: it does not explain for a non-expert what a distributed scatterer is, what a statistically homogeneous pixel is, or why joint estimation over N epochs beats pairwise interferograms. It does not explain closure phase or the bias from short-baseline-only networks. **Attribution:** "EVD: dominant eigenvector of T (Ferretti SqueeSAR 2011)" (L100) is inaccurate; SqueeSAR uses the ML phase-triangulation estimator, and EVD is from Fornaro et al. 2015 (CAESAR) and Cao et al. 2015. It is **not in toc.xml** (map only). The "v1 / v2 ministack" wording is internal roadmap language. | Ferretti 2011; Ansari 2018; Mirzaee 2023 | **Guarnieri & Tebaldini 2008**; **Fornaro et al. 2015**; Cao, Lee & Jung 2015 (verify); Ansari, De Zan & Parizzi 2021 (verify) |
| **PhaseToDisplacementOp.html** | 2 | No parameters (consistent with the code). | **No sign convention.** The code computes d = −λ/4π·φ (`PhaseToDisplacementOp.java` initialize comment). The page shows the formula but never says whether positive means toward or away from the satellite. It does not say the result is relative: SNAPHU output carries an arbitrary integer-cycle offset, so a stable reference point must be subtracted. It does not say only the **first** band whose name starts with "unw" is converted, or that the output is LOS (not vertical) in metres. A stray `<hr>` sits before the title. | Ferretti et al. 2007 (TM-19) | Massonnet & Feigl 1998; Hanssen 2001 |
| **PhaseToElevationOp.html** | 1 | `externalDEMFile` and `externalDEMNoDataValue` are undocumented. Defaults are missing (method = DEM Seed, nPoints 200, nHeights 3, deg1D 2, deg2D 5, orbit 3). | The **title is "DEM Generation Operator"** (L19) while the menu says "Phase to Elevation". L23 says the input is "the output of **Stitch** operator", but **no Stitch operator exists** in the repo. It does not explain height of ambiguity, how baseline choice drives height sensitivity, or that the output is in radar geometry and needs Terrain Correction. "Schwabisch" should be "Schwäbisch". | Richards 2007; Kampes & Usai 1999 | **Zebker & Goldstein 1986**; Rosen et al. 2000; Schwäbisch 1995 DLR report (verify) |
| **SBASInversionOp.html** | 2 | All 8 parameters are documented with defaults. | It does not explain what SBAS is to a non-expert: small baselines limit decorrelation, and the method inverts a network to a time series. It does not explain network connectivity: Berardino used SVD across disconnected subsets, while here Tikhonov is used, and the user is not told what happens with gaps. **No spatial reference point:** there is no parameter for one, and the page does not warn that results must be referenced to a stable area. The sign convention is stated only as a formula. `masterDate` and `slaveDate` appear in the band-name templates (L28, L30). It is **not in toc.xml**: the "PSI/SBAS" TOC folder lists only StaMPS Export. | Berardino 2002; Pepe & Lanari 2006; Yunjun 2019 | **Usai 2003**; **Lanari et al. 2004**; Schmidt & Bürgmann 2003; Rodriguez & Martin 1992 (the CRLB weight used) |
| **StackAveragingOp.html** | 0 | Complete (one parameter), but the default "Mean Average" is not stated. | L19 uses the example "mean of all i_VV_ bands and q_VV_ bands". Averaging I/Q of SLCs from different dates is physically meaningless, because random phase makes them cancel. Users should average **intensity**, not I/Q. No use cases (temporal multilooking, change detection, CoV for PS candidates). | none | none needed |
| **StackSplitOp.html** | 0 | Complete (`targetFolder`, `formatName`; default BEAM-DIMAP not stated). | Output naming is not described. | none | none needed |
| **StampsExportOp.html** | 0 | `psiFormat` (PSI versus SBAS, default true) is **undocumented**. The page says PSI only, while the menu tooltip says "PSI or SBAS". | There is no `<h3>` title. Typo "applicaiton". It does not explain PS: point-like scatterers stable over time, amplitude dispersion, and StaMPS' phase-stability selection. No StaMPS version or `mt_prep_snap` / `mt_prep_gamma` guidance. | none | **Hooper et al. 2004**; **Hooper, Segall & Zebker 2007**; Hooper 2008; Ferretti, Prati & Rocca 2001 |
| **WarpOp.html** | 2 | `rmsThreshold` is now labelled **"Significance Level for Outlier Removal"**, with values {0.001, 0.05, 0.1, 0.5, 1.0} and default 0.05. Undocumented: `demRefinement`, `demName`, `inSAROptimized`, `excludeMaster`. | **The algorithm description is stale.** L40 describes the legacy "eliminate GCPs with RMS > mean RMS" loop. For complex data the code now uses the jLinda CPM with a Baarda w-test at the chosen significance level (`WarpOp.java:198–209`, critical values 3.29, 1.96, 1.64). Values 0.5 and 1.0 silently fall back to the legacy RMS mode. L58 says "linear warp … recommended as default", but the code default is order **2**. The interpolation list omits TRI and BICUBIC2, and the CC6P default is not stated. | none | Baarda 1968 (data snooping) (verify); Hanssen 2001 |

### B. `jlinda-nest-ui` (JL/)

| Page | Concept | Parameter gaps | Staleness / accuracy / hygiene | Refs present | Refs missing (key) |
|---|---|---|---|---|---|
| **AzimuthFilterOp.html** | 2 | All 3 parameters are documented, but without defaults (FFT 256, overlap 0, Hamming 0.75). | **Missing pitfall:** azimuth common-band filtering is meant for stripmap data with differing Doppler centroids. It must not be applied to TOPS bursts, whose azimuth spectrum is time-varying, and the page does not say so. Uses **master/slave** throughout (9 lines). Garbled text and typos: "spectras do that does not overlap" (L25), "not that", "Lenght" (L49). | none | Hanssen 2001 (§ spectral filtering); Schwäbisch & Geudtner 1995 (verify) |
| **CoherenceOp.html** | 1 | **Severely stale: 2 of 16 parameters documented.** Undocumented: `subtractFlatEarthPhase`, `srpPolynomialDegree`, `srpNumberPoints`, `orbitDegree`, `squarePixel`, `subtractTopographicPhase` (**default true**), `correctCoherenceBias` (**default true**), `removeLocalPhaseRamp`, the DEM and external-DEM parameters, `externalDEMApplyEGM`, `tileExtensionPercent`, `singleMaster`. | L22 says flat-earth phase is subtracted only "if there is a 2d-polynomial computed by **Compute Interferogram**". That is stale: the operator estimates it itself. **There is no interpretation of coherence**: what 0 and 1 mean, the estimator bias at low looks, window size versus resolution, and typical values by land cover. ERS-only multilook advice ("5:1 azimuth:range"); for S1 IW it is about 1:4 to 1:5 (azimuth:range). **Inconsistency:** this operator bias-corrects by default (`CoherenceOp.java:116`, (1−γ²)/2L), while `InterferogramOp` has no bias option, so the same window gives different coherence values in the two operators. Nothing on the page tells the user. | none | **Touzi et al. 1999**; **Zebker & Villasenor 1992**; Just & Bamler 1994; Bamler & Hartl 1998 |
| **CrossResamplingOp.html** | 1 | Documented, but the label "Interpolation Polynomial Degree" differs from the code's "Interpolation Polynomial Order". Defaults are missing (order 2, CC6P, target "ERS"). | Legacy ERS-2/Envisat use only, which should be stated. Typos "corregistration", "having having" (L27, L49). "master" (1). | none | Wegmüller et al. 2009, ERS–Envisat cross-interferometry (verify) |
| **DInSAROp.html** | 2 | Documented. The orbit-degree text (L59, "(number of state vectors)−1, but smaller than degree 5") is **stale**; the code default is 3, interval (1, 10]. | "see Coregistration and **Wrap** Operator" (L29) should be **Warp**. "master" (2). No geometric explanation of why topo-pair phase is scaled by the B⊥ ratio, and no reference. | none | **Gabriel, Goldstein & Zebker 1989**; Zebker et al. 1994 |
| **InSAR.html** (the only jLinda overview) | 0 | n/a | **Malformed HTML:** the `<head><title>` block comes *after* `</body>` (L78), so the page has no proper title. It is **not in toc.xml** (map only). The content is project history (DORIS→jLinda) rather than InSAR concepts. The GitHub link `ppolabs/jlinda` is probably stale (verify). "All implemented algorithms are fully documented…" is unsupported. | none | everything (see section c) |
| **InterferogramOp.html** | 2 | Parameters are listed without names or defaults. **Undocumented:** `cohWinSizeMeters` (GSLC, metres), `gslcModeAutoDetected`, `demName`, `externalDEMFile`, `externalDEMNoDataValue`, `externalDEMApplyEGM`, `tileExtensionPercent` (100), `outputFlatEarthPhase`, `outputTopoPhase`. | **Physics:** L27 and L60 attribute flat-earth phase to "the curvature of the reference surface / earth curvature". It is mainly the range-dependent look-angle change across the swath for a given baseline; curvature is a second-order term. L51 "complex Multilook operator will be released" is **stale**: Multilook already outputs I/Q (`MultilookOp.outputIntensity` default false). L76–77 describe the orbit-degree default as (#SV−1) < 5; the code default is 3. ERS 5:1 multilook advice only. **It does not explain what the interferogram is**: the phase components, fringes, and what one fringe means for topography or deformation. There is no GSLC explanation, although the operator has a GSLC mode. | none | **Bamler & Hartl 1998**; **Rosen et al. 2000**; Hanssen 2001; Zebker & Goldstein 1986 |
| **PhaseFilterOp.html** | 2 | Parameters are listed, but without defaults (alpha 0.5, block 32, overlap 8, kernel "1 2 3 2 1"). | The method name is "spatialconv" in prose (L29, L79), but the valueSet is **"convolution"**. "COMING SOON!" placeholder (L101). L118 is self-contradictory ("only odd sized kernels … add a zero to make a kernel size even"). It does not say how this differs from GoldsteinPhaseFiltering. The operator has an action but **no menu entry** (jlinda `layer.xml` has no shadow), so it is reachable only from Graph Builder. The page does not say so. | none (Goldstein named, not cited) | Goldstein & Werner 1998 |
| **RangeFilterOp.html** | 2 | Undocumented: `doWeightCorrel` ("De-weighting"). The "SNR Threshold" text (L75) actually describes de-weighting (copy confusion). **Wrong default:** L82 says oversampling "2 is default", but the code default is 1. L71 says walking mean "has to be an odd number", but the valueSet {5,10,15,20,25} contains even values. Code bugs: FFT `defaultValue="8"` versus field 64, and `doWeightCorrel defaultValue="off"` is not in the {true,false} valueSet (`RangeFilterOp.java:48–51, 84–88`). | master/slave (9 lines). "Signal-to-Noise **Ration**" (L22). No reference for the wavenumber shift. | none | **Gatelli et al. 1994** |
| **Slant2HeightOp.html** (deprecated) | 2 | Matches the code. | The deprecation banner is good. Otherwise the same gaps as PhaseToElevation. | none | Schwäbisch 1995 (verify) |
| **SnaphuExportOp.html** | 1 | **No parameters documented.** Code: `targetFolder`, `statCostMode` (TOPO/DEFO/SMOOTH/NOSTATCOSTS, default DEFO), `initMethod` (MST/MCF), `numberOfTileRows/Cols` (10), `numberOfProcessors` (4), `rowOverlap/colOverlap` (200), `tileCostThreshold` (500). Code bug: the `statCostMode` description reads "Size of coherence estimation window in Azimuth direction" (copy-paste, shown as tooltip). | **Wrong:** L51 says `99999` "represents the number of lines". SNAPHU's positional argument is the **line length**, that is, the number of columns (samples per line). Nothing explains when to pick DEFO versus TOPO versus SMOOTH, or the tile trade-offs. | none | **Chen & Zebker 2001**; **Chen & Zebker 2002** |
| **SnaphuImportOp.html** | 0 | `doNotKeepWrapped` is undocumented. | **Broken link** at L25: `nbdocs://org.jlinda.jlinda.nest.ui/org/jlinda/jlinda/nest/docs/...`; the resource path is `org/jlinda/nest/docs/...`. L51 says "In the **export** graph" but means the import graph. The note that the Generic Binary reader cannot be chained is probably stale; the exported `.hdr` is ENVI-readable (verify). Typo "Unwrapper". It does not say the import sets `Unit.ABS_PHASE` and renames the band to `Unw_Phase_ifg_<ref>_<sec>`, which SBAS, PhaseToElevation and Ionospheric depend on. | none | n/a |
| **SnaphuUnwrapping.html** | 1 | (The operator lives in an external STEP plugin, not this repo.) | It repeats SnaphuExport text verbatim. It is pasted with inline Verdana styles (L29, L34). The `http://step.esa.int` link is not https. The "About SNAPHU" text is lifted from the SNAPHU README without attribution. **There is no unwrapping concept**: residues, coherence masking, unwrapping errors (2π jumps), the need for a reference point, and checking results via closure. | Chen & Zebker named, not cited | **Chen & Zebker 2000/2001/2002**; Goldstein, Zebker & Werner 1988; Ghiglia & Pritt 1998; Costantini 1998 |
| **SubtRefDemOp.html** | 1 | Undocumented: `externalDEMFile`, `externalDEMNoDataValue`. Defaults are missing (DEM Copernicus 30 m, tile extension 100 %, orbit 3). | "SRTM can be used" (L24): the default is now Copernicus. It does not explain topographic phase, height of ambiguity, or the effect of DEM error on residual phase (proportional to B⊥). "master" (3). Odd wording in the output description ("of topographic phase"). | none | Hanssen 2001; Rosen et al. 2000; Massonnet & Feigl 1998 |

**Score distribution (37 pages):**

| Concept score | Pages |
|---|---|
| 3 | 1 (Empirical Tropo) |
| 2 | 16 |
| 1 | 13 |
| 0 | 7 |

- **Pages with a proper reference list:** 8 of 37 (Goldstein, Cross-Correlation, Empirical Tropo, Ionospheric, Phase Linking, Phase to Displacement, Phase to Elevation, SBAS).
- **Pages with partial or dangling references:** 2 (Integer Interferogram [R13], SNAPHU Unwrapping).

---

## (b) Top cross-cutting gaps

1. **No InSAR concept foundation anywhere.**
   - The only overview pages are `JL/InSAR.html` (project history, malformed, not in TOC) and `INS/Coregistration.html` (2009-era).
   - These concepts are not explained anywhere in the help:

     | Topic | Concepts not explained |
     |---|---|
     | Phase basics | interferometric phase and its components (flat-earth, topography, deformation, atmosphere, orbit, noise); phase wrapping; the meaning of one fringe (λ/2 LOS for deformation, HoA for topography) |
     | Baselines | perpendicular and temporal baseline; critical baseline; height of ambiguity |
     | Coherence | coherence and its interpretation |
     | Look direction | LOS and the sign convention |
     | Unwrapping | unwrapping errors |
     | TOPS | ESD and burst geometry |

   - A non-expert who opens Interferogram, Coherence, Phase to Displacement or SBAS cannot interpret the output.
2. **Parameter documentation drift is large in the most-used operators.**

   | Operator | Parameters documented |
   |---|---|
   | Goldstein | 1 of 9 (and the documented one is inactive by default) |
   | Coherence | 2 of 16 |
   | Interferogram | about 9 of 20 |
   | CrossCorrelation | 13 of 19 |
   | Warp | label, semantics and default changed |
   | Offset Tracking | 7 of 14 |
   | SNAPHU Export | 0 of 9 |
   | StaMPS Export | `psiFormat` missing |

   Defaults and units are missing on about 25 of 37 pages. Only Empirical Tropo, Phase Linking and SBAS give defaults systematically.
3. **Factually wrong or stale statements** (highest priority):
   - **`MultiReference.html`:** the output description is wrong.
   - **`SnaphuExportOp.html`:** `99999` is the line *length* (columns), not the number of lines.
   - **`WarpOp.html`:** describes the RMS-loop algorithm, but the code uses a CPM w-test; it also gives the wrong default order.
   - **`PhaseToElevationOp.html`:** refers to a non-existent "Stitch" operator, and its title says "DEM Generation".
   - **`CoherenceOp.html`:** says the reference phase comes from "Compute Interferogram".
   - **`InterferogramOp.html`:** says complex multilook is "coming"; misattributes the flat-earth phase to curvature; gives a stale orbit-degree default.
   - **`RangeFilterOp.html`:** wrong oversampling default.
   - **`DInSAROp.html`:** stale orbit-degree default.
   - **`DEMAssistedCoregistrationOp.html`:** stale DEM list.
   - **`PhaseLinkingOp.html`:** misattributes EVD to SqueeSAR.
4. **TOC holes.**
   - These pages are in `map.jhm` but absent from `toc.xml`, so they are unreachable from Help Contents:
     - Phase Linking, SBAS Inversion and PCA in the INS module.
     - `InSAR.html` in the JL module.
   - The "PSI/SBAS" TOC folder shows only StaMPS Export, although SBAS Inversion is a first-class operator. Create Stack is listed twice in the INS TOC.
   - There is no TOC node for time series or phase linking.
5. **Terminology.**
   - **master/slave in prose:** AzimuthFilter (9), RangeFilter (9), CreateStack GSLC section (8), MultiReference (7), SubtRefDem (3), DInSAR (2), CrossResampling (1), EmpiricalTropo (1), plus SBAS in band templates (2).
   - The project convention is Reference/Secondary. Literal band names and aliases (`MultiMasterInSAR`, `_mst_`) can stay, but should be explained once in a glossary.
6. **Sensor bias toward ERS/Envisat.**
   - The multilook advice (5:1), the coherence model constants, the critical baseline (1100 m), Cross-Resampling and Azimuth Filtering guidance are all ERS-era.
   - There is no Sentinel-1 IW, TerraSAR-X, ALOS-2 or NISAR guidance (looks for square pixels, typical B⊥, revisit time, L-band iono).
   - There is no TOPS-specific warning on the generic pages, for example that azimuth filtering is not applicable to TOPS, or that Goldstein FFT blocks are about 4:1 anisotropic on the ground for S1 (now handled by `fftSizeMeters`, but undocumented).
7. **No workflow navigation.**
   - Only Phase Linking, SBAS, Ionospheric and Empirical Tropo show where they sit in the chain.
   - Most pages lack prerequisites (orbit file, deburst, elevation band, unwrapped `abs_phase` unit) and "next step" links.
   - The prerequisite "unwrapped band named `Unw_*` with unit `abs_phase`" (relied on by PhaseToDisplacement, PhaseToElevation, SBAS, Ionospheric and H/V Motion) appears only scattered across pages.
8. **Displacement products lack the reference-point and sign-convention explanation.**
   - Phase to Displacement, SBAS and H/V Motion produce physical displacement. None states:
     - the positive direction (toward or away from the sensor, East, Up);
     - that unwrapped phase is relative and needs a stable reference area;
     - that values are LOS, not vertical (except H/V Motion).
   - This is the most common user error in forum posts.
9. **Two Goldstein implementations with no cross-reference.** `GoldsteinPhaseFiltering` (adaptive, coherence-aware, metre-sized FFT) and `PhaseFilter` method "goldstein" (fixed alpha, Graph-Builder-only) exist side by side. Neither page says which to use.
10. **Two coherence estimators that disagree.** `CoherenceOp` bias-corrects by default; `InterferogramOp` coherence does not. Same window, different numbers, and neither page says so.
11. **Broken links and HTML hygiene.**
    - **Broken links:** two `nbdocs://` links (`CreateStackOp.html:79` dash-form module name; `SnaphuImportOp.html:25` wrong `org/jlinda/jlinda/` path).
    - **Malformed HTML:** `InSAR.html` (head after body).
    - **Missing `<h3>` title:** IntegerInterferogram, StampsExport.
    - **Unclosed `<p>`:** Demodulate, H/V Motion.
    - **Placeholder:** PhaseFilter ("COMING SOON!").
    - **Image checks:** all `img src` targets exist, and no image is missing. `coregistration_diagram.jpg` is orphaned. UI screenshots (Goldstein, Warp, PCA, CreateStack, mm.png) predate current dialogs.
12. **Code-side defects found while comparing** (they surface as wrong tooltips or defaults to users):
    - `RangeFilterOp`: FFT `defaultValue="8"` versus field 64; `doWeightCorrel defaultValue="off"`; even values in the walking-mean valueSet.
    - `SnaphuExportOp.statCostMode` description is copy-pasted.
    - `PCAOp` has Boolean `defaultValue="1"`.
    - `layer.xml:398` `MultiReferenceOpUI` is bound to the wrong operator name.
    - `layer.xml:128` has a `_pca` suffix on the MultiReference action.

---

## (c) Recommended new concept pages

| # | Proposed page | Key content | Linked from |
|---|---|---|---|
| 1 | **InSAR Fundamentals** | Complex SLC; interferogram = s₁·s₂*; phase components φ = φ_flat + φ_topo + φ_defo + φ_atmo + φ_orb + φ_noise; wrapping; one fringe = λ/2 LOS (≈2.8 cm at C-band); B⊥, B_temp, critical baseline; height of ambiguity HoA = λR sinθ / (2B⊥) (repeat-pass); vertical wavenumber k<sub>z</sub>; decorrelation sources (geometric, temporal, volume, Doppler). Diagrams for S1, TSX and ALOS-2 with typical numbers. | every InSAR page |
| 2 | **Interferometric Coherence** | Definition; estimator; window size versus resolution; bias at low looks (Touzi); interpretation table (water ≈ 0, forest low at C-band, urban high); square-pixel windows for S1; coherence as a quality mask; difference between the CoherenceOp and InterferogramOp outputs. | Coherence, Interferogram, Goldstein, SBAS, Phase Linking |
| 3 | **Coregistration: choosing a method** | Accuracy requirements (stripmap about 0.1 px; TOPS azimuth about 0.001 px because of the Doppler ramp); decision tree: S1 IW/EW → TOPS Split → Apply Orbit → Back-Geocoding → ESD; stripmap → DEM-Assisted (± XCorr) or CreateStack + CC + Warp; GSLC path; ERS/Envisat cross-InSAR. ESD explained in plain language. | Coregistration, CreateStack, CC, Warp, DEM-Assisted, Demod/Remod |
| 4 | **Phase Unwrapping** | Wrapped versus unwrapped; residues; why filtering and multilooking help; SNAPHU cost modes (DEFO/TOPO/SMOOTH); tiling; coherence masking; unwrapping errors and how to detect them (closure phase, `closure_phase_rms`); the need for a spatial reference point; the band-name and unit contract (`Unw_Phase_ifg_*`, `abs_phase`). | SNAPHU Export/Import/Unwrapping, Goldstein, PhaseFilter, all downstream |
| 5 | **From Phase to Displacement: LOS, sign and reference** | The SNAP sign convention (state explicitly which direction is positive for d = −λ/4π·φ); LOS geometry; why InSAR is nearly blind to N–S motion; reference point selection; ascending + descending decomposition; units (m, mm, mm/yr) across the operators. | Phase to Displacement, SBAS, H/V Motion, Multi-Reference InSAR |
| 6 | **Atmospheric and Ionospheric Phase** | Stratified versus turbulent troposphere; magnitudes; empirical versus model corrections (GACOS, ERA5); dispersive ionosphere and split-spectrum; when each matters (L- versus C-band; latitude; solar cycle). | Empirical Tropo, Ionospheric, SBAS |
| 7 | **InSAR Time Series: PS, SBAS and Distributed Scatterers** | PS versus DS versus SBAS concepts; network design (small baselines, connectivity); Phase Linking → SBAS Inversion in SNAP versus export to StaMPS, PyRate or MintPy; temporal coherence; closure phase and short-baseline bias; minimum stack sizes. | Phase Linking, SBAS, StaMPS Export, Multi-Reference InSAR, Split stack, InSAR Stack Overview |
| 8 | **InSAR DEM Generation** | Baseline choice versus HoA; DEM Seed versus Schwäbisch; unwrapping requirements; geocoding the result; error budget. | Phase to Elevation, Slant2Height, Integer Interferogram, DInSAR (three-pass) |
| 9 | **Spectral (Common-Band) Filtering** | The range wavenumber shift (Gatelli) and azimuth Doppler non-overlap; when to apply them (stripmap with large B⊥ or Doppler difference); never on TOPS bursts; why only secondaries are filtered. | Range Filter, Azimuth Filter |
| 10 | **Workflow cookbooks** (graph diagrams) | (i) S1 IW DInSAR pair; (ii) stripmap DInSAR; (iii) S1 time series (Phase Linking + SBAS); (iv) DEM generation; (v) offset tracking (glacier or landslide); (vi) GSLC geocode-first InSAR; (vii) StaMPS export. Each step links to its operator page. | Overview / TOC root |
| 11 | **Glossary and terminology** | Reference/Secondary versus legacy master/slave; legacy band tokens (`_mst`, `_slv`, `MultiMasterInSAR`); ifg, coh, HoA, B⊥, LOS, ESD, SHP, DS/PS, GSLC, abs_phase. | everywhere |
| 12 | **Quality control and troubleshooting** | Reading the Warp residual file; ESD residuals; coherence histograms; checking unwrapping; common failure symptoms (fringes at burst seams, decorrelated pair, phase jumps). | Coregistration, Warp, Interferogram, SNAPHU |

---

## (d) Consolidated recommended bibliography

Entries marked "(verify)" need confirmation of details or attribution before they are cited.

### Foundations and overview
- Bamler, R., & Hartl, P. (1998). Synthetic aperture radar interferometry. *Inverse Problems*, 14(4), R1–R54.
- Rosen, P. A., Hensley, S., Joughin, I. R., Li, F. K., Madsen, S. N., Rodríguez, E., & Goldstein, R. M. (2000). Synthetic aperture radar interferometry. *Proceedings of the IEEE*, 88(3), 333–382.
- Hanssen, R. F. (2001). *Radar Interferometry: Data Interpretation and Error Analysis*. Kluwer Academic Publishers, Dordrecht.
- Massonnet, D., & Feigl, K. L. (1998). Radar interferometry and its application to changes in the Earth's surface. *Reviews of Geophysics*, 36(4), 441–500.
- Ferretti, A., Monti Guarnieri, A., Prati, C., Rocca, F., & Massonnet, D. (2007). *InSAR Principles: Guidelines for SAR Interferometry Processing and Interpretation*. ESA TM-19, ESA Publications.
- Moreira, A., Prats-Iraola, P., Younis, M., Krieger, G., Hajnsek, I., & Papathanassiou, K. P. (2013). A tutorial on synthetic aperture radar. *IEEE Geoscience and Remote Sensing Magazine*, 1(1), 6–43.
- Richards, M. A. (2007). A beginner's guide to interferometric SAR concepts and signal processing. *IEEE Aerospace and Electronic Systems Magazine*, 22(9), 5–29.
- Osmanoğlu, B., Sunar, F., Wdowinski, S., & Cabral-Cano, E. (2016). Time series analysis of InSAR data: Methods and trends. *ISPRS Journal of Photogrammetry and Remote Sensing*, 115, 90–102.

### Topography and DInSAR
- Zebker, H. A., & Goldstein, R. M. (1986). Topographic mapping from interferometric synthetic aperture radar observations. *Journal of Geophysical Research*, 91(B5), 4993–4999.
- Gabriel, A. K., Goldstein, R. M., & Zebker, H. A. (1989). Mapping small elevation changes over large areas: Differential radar interferometry. *Journal of Geophysical Research*, 94(B7), 9183–9191.
- Zebker, H. A., Rosen, P. A., Goldstein, R. M., Gabriel, A., & Werner, C. L. (1994). On the derivation of coseismic displacement fields using differential radar interferometry: The Landers earthquake. *Journal of Geophysical Research*, 99(B10), 19617–19634.
- Massonnet, D., Vadon, H., & Rossi, M. (1996). Reduction of the need for phase unwrapping in radar interferometry. *IEEE TGRS*, 34(2), 489–497. (verify volume and pages)
- Kampes, B., & Usai, S. (1999). Doris: The Delft Object-oriented Radar Interferometric Software. *2nd Int. Symp. Operationalization of Remote Sensing*, Enschede.
- Schwäbisch, M. (1995). *Die SAR-Interferometrie zur Erzeugung digitaler Geländemodelle*. DLR Forschungsbericht 95-25. (verify)

### Coherence and decorrelation
- Zebker, H. A., & Villasenor, J. (1992). Decorrelation in interferometric radar echoes. *IEEE TGRS*, 30(5), 950–959.
- Just, D., & Bamler, R. (1994). Phase statistics of interferograms with applications to synthetic aperture radar. *Applied Optics*, 33(20), 4361–4368.
- Touzi, R., Lopes, A., Bruniquel, J., & Vachon, P. W. (1999). Coherence estimation for SAR imagery. *IEEE TGRS*, 37(1), 135–149.
- Rodriguez, E., & Martin, J. M. (1992). Theory and design of interferometric synthetic aperture radars. *IEE Proceedings F*, 139(2), 147–159.

### Coregistration, TOPS and ESD
- Scheiber, R., & Moreira, A. (2000). Coregistration of interferometric SAR images using spectral diversity. *IEEE TGRS*, 38(5), 2179–2191.
- Sansosti, E., Berardino, P., Manunta, M., Serafino, F., & Fornaro, G. (2006). Geometrical SAR image registration. *IEEE TGRS*, 44(10), 2861–2870.
- De Zan, F., & Monti Guarnieri, A. (2006). TOPSAR: Terrain observation by progressive scans. *IEEE TGRS*, 44(9), 2352–2360.
- Prats-Iraola, P., Scheiber, R., Marotti, L., Wollstadt, S., & Reigber, A. (2012). TOPS interferometry with TerraSAR-X. *IEEE TGRS*, 50(8), 3179–3188.
- Yagüe-Martínez, N., Prats-Iraola, P., Rodríguez González, F., Brcic, R., Shau, R., Geudtner, D., Eineder, M., & Bamler, R. (2016). Interferometric processing of Sentinel-1 TOPS data. *IEEE TGRS*, 54(4), 2220–2234.
- Miranda, N. (2017). Definition of the TOPS SLC deramping function for products generated by the S-1 IPF. ESA technical note COPE-GSEG-EOPG-TN-14-0025. (verify ID and year)

### Spectral filtering
- Gatelli, F., Monti Guarnieri, A., Parizzi, F., Pasquali, P., Prati, C., & Rocca, F. (1994). The wavenumber shift in SAR interferometry. *IEEE TGRS*, 32(4), 855–865.

### Phase filtering
- Goldstein, R. M., & Werner, C. L. (1998). Radar interferogram filtering for geophysical applications. *Geophysical Research Letters*, 25(21), 4035–4038.
- Baran, I., Stewart, M. P., Kampes, B. M., Perski, Z., & Lilly, P. (2003). A modification to the Goldstein radar interferogram filter. *IEEE TGRS*, 41(9), 2114–2118.

### Phase unwrapping
- Goldstein, R. M., Zebker, H. A., & Werner, C. L. (1988). Satellite radar interferometry: Two-dimensional phase unwrapping. *Radio Science*, 23(4), 713–720.
- Ghiglia, D. C., & Pritt, M. D. (1998). *Two-Dimensional Phase Unwrapping: Theory, Algorithms, and Software*. Wiley.
- Costantini, M. (1998). A novel phase unwrapping method based on network programming. *IEEE TGRS*, 36(3), 813–821.
- Chen, C. W., & Zebker, H. A. (2000). Network approaches to two-dimensional phase unwrapping: Intractability and two new algorithms. *JOSA A*, 17(3), 401–414.
- Chen, C. W., & Zebker, H. A. (2001). Two-dimensional phase unwrapping with use of statistical models for cost functions in nonlinear optimization. *JOSA A*, 18(2), 338–351.
- Chen, C. W., & Zebker, H. A. (2002). Phase unwrapping for large SAR interferograms: Statistical segmentation and generalized network models. *IEEE TGRS*, 40(8), 1709–1719.

### Time series: PS, SBAS and DS
- Ferretti, A., Prati, C., & Rocca, F. (2000). Nonlinear subsidence rate estimation using permanent scatterers in differential SAR interferometry. *IEEE TGRS*, 38(5), 2202–2212.
- Ferretti, A., Prati, C., & Rocca, F. (2001). Permanent scatterers in SAR interferometry. *IEEE TGRS*, 39(1), 8–20.
- Berardino, P., Fornaro, G., Lanari, R., & Sansosti, E. (2002). A new algorithm for surface deformation monitoring based on small baseline differential SAR interferograms. *IEEE TGRS*, 40(11), 2375–2383.
- Usai, S. (2003). A least squares database approach for SAR interferometric data. *IEEE TGRS*, 41(4), 753–760.
- Schmidt, D. A., & Bürgmann, R. (2003). Time-dependent land uplift and subsidence in the Santa Clara valley, California, from a large interferometric SAR data set. *Journal of Geophysical Research*, 108(B9), 2416.
- Lanari, R., Mora, O., Manunta, M., Mallorquí, J. J., Berardino, P., & Sansosti, E. (2004). A small-baseline approach for investigating deformations on full-resolution differential SAR interferograms. *IEEE TGRS*, 42(7), 1377–1386.
- Hooper, A., Zebker, H., Segall, P., & Kampes, B. (2004). A new method for measuring deformation on volcanoes and other natural terrains using InSAR persistent scatterers. *Geophysical Research Letters*, 31, L23611.
- Pepe, A., & Lanari, R. (2006). On the extension of the minimum cost flow algorithm for phase unwrapping of multitemporal differential SAR interferograms. *IEEE TGRS*, 44(9), 2374–2383.
- Kampes, B. M. (2006). *Radar Interferometry: Persistent Scatterer Technique*. Springer, Dordrecht.
- Hooper, A., Segall, P., & Zebker, H. (2007). Persistent scatterer interferometric synthetic aperture radar for crustal deformation analysis, with application to Volcán Alcedo, Galápagos. *Journal of Geophysical Research*, 112, B07407.
- Hooper, A. (2008). A multi-temporal InSAR method incorporating both persistent scatterer and small baseline approaches. *Geophysical Research Letters*, 35, L16302.
- Guarnieri, A. M., & Tebaldini, S. (2008). On the exploitation of target statistics for SAR interferometry applications. *IEEE TGRS*, 46(11), 3436–3443.
- Ferretti, A., Fumagalli, A., Novali, F., Prati, C., Rocca, F., & Rucci, A. (2011). A new algorithm for processing interferometric data-stacks: SqueeSAR. *IEEE TGRS*, 49(9), 3460–3470.
- Fornaro, G., Verde, S., Reale, D., & Pauciullo, A. (2015). CAESAR: An approach based on covariance matrix decomposition to improve multibaseline–multitemporal interferometric SAR processing. *IEEE TGRS*, 53(4), 2050–2065.
- Cao, N., Lee, H., & Jung, H. C. (2015). Mathematical framework for phase-triangulation algorithms in distributed-scatterer interferometry. *IEEE GRSL*, 12(9), 1838–1842. (verify)
- Ansari, H., De Zan, F., & Bamler, R. (2018). Efficient phase estimation for interferogram stacks. *IEEE TGRS*, 56(7), 4109–4125.
- Yunjun, Z., Fattahi, H., & Amelung, F. (2019). Small baseline InSAR time series analysis: Unwrapping error correction and noise reduction. *Computers & Geosciences*, 133, 104331.
- Ansari, H., De Zan, F., & Parizzi, A. (2021). Study of systematic bias in measuring surface deformation with SAR interferometry. *IEEE TGRS*, 59(2), 1285–1301. (verify)
- Mirzaee, S., Amelung, F., & Fattahi, H. (2023). Non-linear phase linking using joined distributed and persistent scatterers. *Computers & Geosciences*, 171, 105291.

### Atmosphere
- Doin, M.-P., Lasserre, C., Peltzer, G., Cavalié, O., & Doubre, C. (2009). Corrections of stratified tropospheric delays in SAR interferometry: Validation with global atmospheric models. *Journal of Applied Geophysics*, 69(1), 35–50.
- Jolivet, R., Grandin, R., Lasserre, C., Doin, M.-P., & Peltzer, G. (2011). Systematic InSAR tropospheric phase delay corrections from global meteorological reanalysis data. *Geophysical Research Letters*, 38, L17311.
- Bekaert, D. P. S., Walters, R. J., Wright, T. J., Hooper, A. J., & Parker, D. J. (2015). Statistical comparison of InSAR tropospheric correction techniques. *Remote Sensing of Environment*, 170, 40–47.
- Yu, C., Li, Z., & Penna, N. T. (2018). Interferometric synthetic aperture radar atmospheric correction using a GPS-based iterative tropospheric decomposition model. *Remote Sensing of Environment*, 204, 109–121.

### Ionosphere
- Meyer, F., Bamler, R., Jakowski, N., & Fritz, T. (2006). The potential of low-frequency SAR systems for mapping ionospheric TEC distributions. *IEEE GRSL*, 3(4), 560–564. (verify)
- Brcic, R., Parizzi, A., Eineder, M., Bamler, R., & Meyer, F. (2010). Estimation and compensation of ionospheric delay for SAR interferometry. *Proc. IGARSS 2010*. (verify)
- Rosen, P. A., Hensley, S., & Chen, C. (2010). Measurement and mitigation of the ionosphere in L-band interferometric SAR data. *Proc. IEEE Radar Conference 2010*. (verify)
- Gomba, G., Parizzi, A., De Zan, F., Eineder, M., & Bamler, R. (2016). Toward operational compensation of ionospheric effects in SAR interferograms: The split-spectrum method. *IEEE TGRS*, 54(3), 1446–1461.
- Fattahi, H., Simons, M., & Agram, P. (2017). InSAR time-series estimation of the ionospheric phase delay: An extension of the split range-spectrum technique. *IEEE TGRS*, 55(10), 5984–5996.

### Multi-geometry decomposition
- Fialko, Y., Simons, M., & Agnew, D. (2001). The complete (3-D) surface displacement field in the epicentral area of the 1999 Mw7.1 Hector Mine earthquake, California, from space geodetic observations. *Geophysical Research Letters*, 28(16), 3063–3066.
- Wright, T. J., Parsons, B. E., & Lu, Z. (2004). Toward mapping surface deformation in three dimensions using InSAR. *Geophysical Research Letters*, 31, L01607.
- Fuhrmann, T., & Garthwaite, M. C. (2019). Resolving three-dimensional surface motion with InSAR: Constraints from multi-geometry data fusion. *Remote Sensing*, 11(3), 241.

### Offset tracking
- Gray, A. L., Mattar, K. E., Vachon, P. W., Bindschadler, R., Jezek, K. C., Forster, R., & Crawford, J. P. (1998). InSAR results from the RADARSAT Antarctic Mapping Mission data: Estimation of glacier motion using a simple registration procedure. *Proc. IGARSS 1998*. (verify)
- Michel, R., Avouac, J.-P., & Taboury, J. (1999). Measuring ground displacements from SAR amplitude images: Application to the Landers earthquake. *Geophysical Research Letters*, 26(7), 875–878.
- Strozzi, T., Luckman, A., Murray, T., Wegmüller, U., & Werner, C. L. (2002). Glacier motion estimation using SAR offset-tracking procedures. *IEEE TGRS*, 40(11), 2384–2391.

### Cross-interferometry
- Wegmüller, U., Santoro, M., Werner, C., Strozzi, T., Wiesmann, A., & Lengert, W. (2009). DEM generation using ERS–ENVISAT interferometry. *Journal of Applied Geophysics*, 69(1), 51–58. (verify)

### Statistics (Warp w-test)
- Baarda, W. (1968). *A Testing Procedure for Use in Geodetic Networks*. Netherlands Geodetic Commission, Publications on Geodesy, 2(5). (verify)

---

## Appendix: priority fix list

**Page-level fixes**

| Priority | Fix |
|---|---|
| P1 | Correct the factually wrong pages: MultiReference output description; SNAPHU `linelength`; Warp algorithm, labels and default order; Phase to Elevation title and "Stitch"; Coherence "Compute Interferogram"; the stale Interferogram statements. |
| P1 | Rewrite Goldstein and Coherence parameter sections from the `@Parameter` annotations, with defaults, units and guidance. |
| P1 | Add sign-convention and reference-point text to Phase to Displacement, SBAS and H/V Motion. |
| P2 | Add Phase Linking, SBAS Inversion, PCA and `InSAR.html` to `toc.xml`; fix the two `nbdocs://` links and the `InSAR.html` structure. |
| P2 | Replace master/slave in prose with Reference/Secondary on the 9 affected pages. |
| P2 | Write concept pages 1–5 from section (c) and link every operator page to them. |
| P3 | Fill the remaining undocumented parameters: CrossCorrelation (6), OffsetTracking (7), DEM-Assisted (3), Interferogram (9), SNAPHU Export (all), StaMPS `psiFormat`, PCA (2), RangeFilter `doWeightCorrel`, SnaphuImport `doNotKeepWrapped`, Demodulate `excludeReference`. |
| P3 | Refresh the UI screenshots; remove placeholders ("COMING SOON"); fix typos. |

**Code-side defects (separate from the help pages)**

| Priority | Fix |
|---|---|
| P3 | RangeFilter defaults and valueSets |
| P3 | SnaphuExport `statCostMode` description |
| P3 | PCA Boolean defaults |
| P3 | `layer.xml` MultiReferenceOpUI operator name and `_pca` suffix |
