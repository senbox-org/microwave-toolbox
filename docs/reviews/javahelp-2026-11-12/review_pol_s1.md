# Microwave Toolbox help review: Polarimetry, Soil Moisture, Sentinel-1 TOPS, Orbits

Reviewed 2026-11-12 on branch `help` of `E:\ESA\microwave-toolbox` (read-only; no files changed).
I read every in-scope page (21 polarimetric, 2 classification, 11 soil moisture, 15 Sentinel-1 TOPS, 1 orbits = 50 pages) and compared each with its operator's `@Parameter` list.

Path abbreviations used below:

| Abbrev | Path |
|---|---|
| `POL/` | `rstb/rstb-op-polarimetric-tools-ui/src/main/resources/org/csa/rstb/polarimetric/docs/operators/` |
| `POLsrc/` | `rstb/rstb-op-polarimetric-tools/src/main/java/org/csa/rstb/polarimetric/gpf/` |
| `CLS/` | `rstb/rstb-op-classification-ui/src/main/resources/org/csa/rstb/classification/docs/operators/` |
| `SM/` | `rstb/rstb-op-soil-moisture-ui/src/main/resources/org/csa/rstb/soilmoisture/docs/operators/` |
| `S1/` | `sar-op-sentinel1-ui/src/main/resources/eu/esa/sar/sentinel1/docs/operators/` |
| `S1src/` | `sar-op-sentinel1/src/main/java/eu/esa/sar/sentinel1/gpf/` |
| `ORB/` | `sar-io-ephemeris-ui/src/main/resources/eu/esa/sar/orbits/docs/operators/` |

Concept score: 0 = nothing; 1 = says what the tool does but not why; 2 = explains the method, but a non-expert would still miss the physical meaning; 3 = explains the concept, its physical meaning and when to use the tool.

---

## 0. Defects to fix first

1. **The Pauli colour meanings are swapped.** In `POL/PolarimetricDecompositionOp.html:46,54`, Red = 0.5·|HH−VV|² is called "single- or odd-bounce" and Blue = 0.5·|HH+VV|² is called "double- or even-bounce". The physics is the other way round: |HH+VV|² is odd-bounce (surface or trihedral) and |HH−VV|² is even-bounce (dihedral). The Pauli RGB is the most-used polarimetric image, so users are reading it backwards.
2. **Eight images are broken once the help is packaged.** `DpRBIformulation.PNG`, `DpRBIflowchart.PNG`, `DpRSIformulation.PNG`, `DpRSIflowchart.PNG`, `DpDcmpformulation.PNG`, `DpDcmpflowchart.PNG`, `DpFactformulation.PNG` and `DpFactflowchart.PNG` are referenced with an upper-case `.PNG`, but the files on disk end in lower-case `.png`. The images show on Windows but are missing inside the NBM/JAR (case-sensitive) and on Linux.
   - References: `POL/DPRBIOp.html:39,43`, `POL/DPRSIOp.html:37,41`, `POL/DpDecompositionOp.html:44,48`, `POL/DpFactorizationOp.html:41,45`.
   - On these four pages the formula and the flowchart are the whole method description, so the pages are effectively empty.
3. **The header link is broken on seven pages.** `href="../general/Overview.html"` points to a folder that does not exist under `polarimetric/docs/`. The other pages use `nbdocs://…/SnapOverview.html`.
   - Affected: `POL/DPRBIOp.html:13`, `DPRSIOp.html:13`, `DpDecompositionOp.html:13`, `DpFactorizationOp.html:13`, `DualPolRatioIndicesOp.html:12`, `DualPolY3DecompositionOp.html:12`, `PolarimetricCalibrationOp.html:12`, `PolarimetricChangeDetectionOp.html:12`.
4. **ETAD Deburst layer names do not match the code.** GPT users must type these strings exactly. The page (`S1/ETADDeburstOp.html:58`) lists four names that the code rejects. The valueSet is in `S1src/ETADDeburstOp.java:58-60`.

   | Page says | Code accepts |
   |---|---|
   | `dopplerShiftCorrectionRg` | `dopplerRangeShiftRg` |
   | `bistaticShiftCorrectionAz` | `bistaticCorrectionAz` |
   | `sumOfAzimuthCorrections` | `sumOfCorrectionsAz` |
   | `sumOfRangeCorrections` | `sumOfCorrectionsRg` |

5. **The ESD distributed-workflow instructions in `S1/SpectralDiversityOp.html` are wrong.**
   - Line 215 says to set `doNotWriteTargetBands` to **false** for the "compute shifts only" stage. It must be **true**.
   - Lines 232-233 say to set `useSuppliedRangeShift`/`useSuppliedAzimuthShift` "to the corresponding shifts". The fields that hold the shift values are `overallRangeShift` and `overallAzimuthShift` (`S1src/SpectralDiversityOp.java:238,246`).
   - Anyone who follows the cloud recipe as written gets a wrong result.
6. **The Faraday page has two errors.**
   - The formula (`POL/FaradayRotationCorrectionOp.html:40`, Ω = ¼·arg((Z12−Z21)·conj(Z22+Z11))) is not what the code computes. The code uses the Bickel-Bates estimator Ω = −¼·atan2 of ⟨Z12·Z21*⟩ (`POLsrc/FaradayRotationCorrectionOp.java:296,337`). The sign and the form both differ.
   - Line 55 says the output can optionally include a band with the estimated angle Ω. `outputEstimatedFRA` (line 73) is a private field fixed at `false` and has no `@Parameter`, so users cannot turn that band on.
7. **The Y3-DP page has a wrong author and a questionable decision rule.** (`POL/DualPolY3DecompositionOp.html:64,67`)
   - Reference [1] lists the third author as "Lopez-Martinez, C." The decomposition page in the same toolbox (`PolarimetricDecompositionOp.html` [6]) correctly gives J. M. Lopez-Sanchez.
   - Reference [2], "Mascolo, Lopez-Martinez et al. 2024, ISPRS 212:289-305", is actually Verma, Bhattacharya, Dey, Lopez-Martinez & Gamba 2024 (cited correctly on `DpFactorizationOp.html` and `DPRSIOp.html`).
   - The page decides surface vs double-bounce from the sign of Re(c12). For co-pol/cross-pol C2 (VV+VH or HH+HV), reflection symmetry implies c12 ≈ 0. The sign is therefore mostly noise, unlike the HH·VV* term in quad-pol. This needs a scientific check.
   - The names "Y3-DP / iDPSV" cannot be traced to the cited papers (verify).
8. **The RFDI reference venue is likely wrong.** It is given as "Carbon Balance and Management 7, 1" (`POL/DualPolRatioIndicesOp.html:39`, `POL/PolarimetricParametersOp.html:86`). The Mitchard et al. 2012 Lopé paper ("…overcoming problems of high biomass and persistent cloud") appeared in *Biogeosciences* 9:179-191 (verify).
9. **A unit error on the IEM LUT page.** `SM/IEM-LUT.html:28` gives "RDC (Farad/m)". The real dielectric constant (relative permittivity) has no unit; F/m is the unit of absolute permittivity.
10. **The ETAD correction page describes an older dialog.** `S1/S1ETADCorrectionOp.html:29-35,57` presents "Option 1 / Option 2" as alternatives. The current UI (`S1ETADCorrectionOpUI.java:46-56`) states they are independent, and that enabling both is what the geocode-first (GSLC) InSAR chain needs. The page also omits the `outputETADPhaseBand` diagnostic and `sourceBands`, and the screenshot is likely out of date.

---

## (a) Per-page table

### Polarimetry: `POL/` (21 pages)

| Page | Concept (0-3) | Parameter gaps (code vs page) | Staleness / errors | Refs present | Refs missing |
|---|---|---|---|---|---|
| CompactPolDataSimulationOp | 1 | `noisePower` (−25 dB, interval (−35,−15)) and `simulateNoiseFloor` not documented; the C2 vs S2 output values are not named | Says "primarily RADARSAT-2", with no mention that real CP data now exist (RCM, and NISAR/RISAT verify). Hybrid/CTLR is not explained, nor why one transmits circular. Encoding issues in the header (“�”). | Cloude 2012; Ainsworth et al. 2007 PolInSAR | Raney 2007; Souyris et al. 2005 (π/4); Charbonneau et al. 2010 |
| CompactPolDecompositionOp | 2 | `computeAlphaByT3` and `outputRVOG` not documented | The Output section lists only m-chi/m-delta/H-α/RVOG (no m-alpha or MF3CC). Input is said to be "simulated" CP only, although RCM CP is supported. Equations are images. | Raney 2012; Raney 2007; Cloude 2012; Dey 2020 | Cloude 2009 book (RVoG CP); Charbonneau 2010 |
| CompactPolStokesParametersOp | 1 | `outputPhasePhi` not documented | Lists nine child parameters with no definition or meaning, e.g. circular polarisation ratio and conformity coefficient (useful for distinguishing scattering types). | Raney et al. 2011 Mini-RF (conference) | Raney 2007; Raney et al. 2012; Born & Wolf (Stokes formalism) |
| CPRVIOp | 2 | OK (`windowSize`, default 3) | Footer is in contributor-card style with phone and e-mail. "matric" typo. | Mandal et al. 2020 TGRS 58(9) | – |
| CrossChannelSNRCorrectionOp | 2 | OK | Code `description` is copy-paste ("Compute general polarimetric parameters"). Does not say when to run it (low-backscatter or L-band). | Quegan 1994; Touzi & Lopes 1996 | Lee & Pottier 2009 (noise in PolSAR) |
| DpDecompositionOp | 2 (effectively 0, images broken) | **`NESZ` (default −16 dB) not documented.** The code description says S1 IW ≈ −22 dB, so the default contradicts its own guidance. The slope requirement is described in prose only (DPRBI hard-requires a band named `slope`). | Broken `.PNG` images; broken header link. Code `description` is "Dual-pol Radar Built-up Index" (copy-paste). | Verma et al. 2024 TGRS 62 | Barakat 1977 (DoP); Cloude 2007 dual-pol H/α |
| DpFactorizationOp | 2 (effectively 0) | `NESZ` not documented | Same as above | Ratha et al. 2020 TGRS 58(5); Verma 2024 ISPRS | – |
| DPRBIOp | 2 (effectively 0) | OK; an exact band-name requirement (`slope`), the 15° slope threshold (`DPRBIOp.java:72`) and the operator error are undocumented | Broken images/link | Verma et al. 2023 ISPRS 203 | – |
| DPRSIOp | 2 (effectively 0) | OK | Broken images/link | Verma 2024 (×2), 2025 | – |
| DualPolRatioIndicesOp | 2 | OK | Wrong RFDI venue (verify) | Mitchard 2012 | Saatchi et al. 2011 (RFDI origin, verify) |
| DualPolY3DecompositionOp | 2 | OK | See §0.7 (author error, wrong attribution, questionable Re(c12) rule). The "normalised" V = diag(3/8, 1/8) does not sum to 1. | Mascolo 2022 (author wrong), "Mascolo 2024" (wrong) | Freeman & Durden 1998; Mascolo, Cloude & Lopez-Sanchez 2022 (correct form) |
| FaradayRotationCorrectionOp | 3 | Promises an Ω output band that has no parameter (§0.6) | The formula does not match the code | Bickel & Bates 1965; Freeman 2004; Wright et al. 2003 | Meyer & Nicoll 2008 (verify) |
| GRVIOp | 2 | OK | "SCattering" typo; no output-range interpretation | Mandal 2020 RSE; Ratha 2019 GRSL | Antropov et al. 2011 GVSM (verify) |
| OrientationAngleCorrectionOp | 2 | **States "No processing parameter is required", but `outputOrientationAngle` exists (`POLsrc/OrientationAngleCorrectionOp.java:63`)** | Duplicated sentence ("Define three circular…"). No plain-language "why": azimuth slopes and oriented buildings cause volume over-estimation in Freeman/Yamaguchi. Does not mention that Polarimetric-Decomposition now has `applyOrientationAngleCorrection`. | Lee & Pottier 2009 | **Lee, Schuler & Ainsworth 2000**; Lee et al. 2002 (verify) |
| PolarimetricCalibrationOp | 3 (best page in scope) | OK | The mission table says S-1 delivers "polsar" products (S-1 is dual-pol only). RCM listed as "compact-pol and dual-pol" (RCM also has a quad-pol mode, verify). | Quegan 1994; Ainsworth et al. 2006 | van Zyl 1990 (verify); Freeman 1992 calibration review (verify) |
| PolarimetricChangeDetectionOp | 2 | OK | **Conceptual gap.** The test treats N as the input ENL, but the matrices are first averaged over `windowSize²` pixels, so the effective N ≈ ENL × (number of independent pixels in the window). The page ("~1 for SLC") steers SLC users to N=1 with a 5×5 window, which under-states T. Omits Conradsen's ρ small-sample correction. Equal-N assumption not stated. | Conradsen et al. 2003 | **Conradsen et al. 2016 (omnibus/time series)**; Nielsen et al. 2017 (verify) |
| PolarimetricDecompositionOp | 2 | **Huynen, Yang, Krogager, Cameron and "H-Alpha Dual Pol" are offered (`POLsrc/PolarimetricDecompositionOp.java:60-65`) but not documented.** `applyOrientationAngleCorrection` and `outputHuynenParamSet0/1` are not documented. | **Pauli colours swapped (§0.1).** "H-a Alpha" and "Anistropy" typos. The Cloude decomposition gets one sentence. Freeman/Yamaguchi have no equations. No guidance on choosing a decomposition, on negative-power artefacts, or on volume over-estimation. | Lee & Pottier 2009; Touzi 2007; Cloude 2009; Dey 2020/2021; Mascolo 2022 | **Cloude & Pottier 1996, 1997; Freeman & Durden 1998; Yamaguchi 2005, 2011; van Zyl 1989; Krogager 1990; Huynen 1970; Cameron & Leung 1990 (verify); Yang et al. 2001 (verify)** |
| PolarimetricMatricesOp | 2 | **C2 is in the valueSet but not documented**; input described as full-pol only | Band list typo (line 85): "C22_real, C12_imag", which should be "C12_real, C12_imag". "Cohrency", "Polarimetrix" typos. No plain-language C-vs-T / Pauli-basis explanation. | Lee & Pottier 2009 | Cloude & Pottier 1996 |
| PolarimetricParametersOp | 2 | OK (all flags covered generically) | CSI/VSI/BMI/ITI formulas are in the code descriptions but not on the page. RVI formula not given. "Pedestal height" approximated as λ3/λ1 without saying so. | Pope et al. 1994; Mitchard 2012 (venue?); Lee & Pottier | **Kim & van Zyl 2009 (RVI)**; van Zyl et al. 1987 (pedestal, verify) |
| PolarimetricSpeckleFilterOp | 2 | `filterSize` (box car) is called "Filter Size", OK. Lee Sigma parameters `sigmaStr`, `windowSize` and `targetWindowSizeStr` are listed with **empty descriptions**. Window sizes 5-17 in code vs "7, 9, 11" on the page. `searchWindowSizeStr`, `patchSizeStr` and `scaleSizeStr` (non-local filter) are present in code but not documented. | IDAN section mislabelled "For refined Lee filter". The code `label` for `sigmaStr` is "Point target window Size" (copy-paste bug, line 82). No explanation of Wishart statistics, ENL or why filtering must preserve the matrix structure. | Lee & Pottier; Vasile 2006; Lee et al. 2009; Lee 1983 | **Lee, Grunes & De Grandi 1999 (polarimetric refined Lee)**; Goodman 1963 (verify) |
| RVIOp (DpRVI) | 2 | OK | Contributor card | Barakat 1977; Mandal 2020 RSE; Mandal 2019 preprint | Kim & van Zyl 2009 (quad-pol RVI baseline) |

### Classification: `CLS/` (2 pages)

| Page | Concept | Parameter gaps | Staleness / errors | Refs present | Refs missing |
|---|---|---|---|---|---|
| PolarimetricClassificationOp | 2 | **"Cloude-Pottier Dual Pol" and "H Alpha Wishart Dual Pol" are in the valueSet (`PolarimetricClassificationOp.java:55-60`) but not documented.** The General Wishart decomposition list omits H-A-Alpha (in code). Default classification (H Alpha Wishart) not stated. | "Unsupervised Whishart" typo; the "Figure 4" caption is used twice; the nine H/α zones are not named. | Lee & Pottier 2009; Lee, Grunes & Pottier 2004 (omits Ferro-Famil; pages 722-731 missing) | **Cloude & Pottier 1997; Lee et al. 1999 (H/α-Wishart); Lee, Grunes & Kwok 1994** |
| SupervisedWishartClassificationOp | 2 | OK | "RSTB" product name (lines 48, 114); "qual-pol" typo (line 108); menu path vague (actual: Radar > Polarimetric > Polarimetric Classification > Supervised Classification Training) | Lee & Pottier 2009 | **Lee, Grunes & Kwok 1994** |

### Soil moisture: `SM/` (11 pages, **no references on any page**)

| Page | Concept | Parameter gaps | Staleness / errors | Refs present | Refs missing |
|---|---|---|---|---|---|
| Hallikainen.html | 1 | – | **Orphan:** not in `map.jhm` or `toc.xml`, so unreachable. Duplicates SMDielectricModel. | none | Hallikainen 1985 |
| HybridSoilMoisture | 1 | Graph wrapper | "AM and PM" never explained (RADARSAT-2 ascending/descending at different incidence angles) | none | Fung 1992; Hallikainen 1985; Mironov 2009 |
| IEM-LUT | 1 | – | **"RDC (Farad/m)" (line 28) is a unit error.** "Original" vs "Calibrated" IEM is never explained (calibrated means Baghdadi-style cl(rms)). Reachable only from in-page links. | none | Fung 1992; Baghdadi et al. 2004 (verify) |
| IEMHybrid | 2 | `thresholdRDC` documented as "RDC deviation threshold" without the default (0.5); N and M defaults (5) not given; `lutFile` has no label | Outlier band is controlled by editing `snap.properties` under "snap-desktop\snap-application\target\snap\etc", a developer build path; it should be `$SNAP_HOME/etc/snap.properties` or `~/.snap/etc` | none | Fung 1992; Merzouki et al. 2011 (verify) |
| IEMMultiAngle | 2 | `sigmaPol` options documented | Same snap.properties path; "utiliizes" typo | none | same |
| IEMMultiPol | 2 | OK | Same | none | same |
| MultiAnglePreProcessing | 1 | Graph | **Says "SRTM 3sec"; the graphs use Copernicus 30m.** **Band naming documented only as "mst"/"slvx" ("master"/"slave", lines 78-81); the code (`IEMInverBase.java:198-227`) uses "ref"/"secx" with mst/slv as legacy.** | none | – |
| MultiAngleSoilMoisture | 1 | Graph | "Use can select" typo | none | – |
| MultiPolPreProcessing | 1 | Graph | SRTM 3sec is stale; says "The **multi-angle** soil moisture retrieval requires…" on the multi-pol page (copy-paste) | none | – |
| MultiPolSoilMoisture | 1 | Graph | – | none | – |
| SMDielectricModel | 1 | `minSM`/`maxSM` defaults (0/0.55), `effectiveSoilTemperature` default 18 °C and `outputLandCover` not stated | No formula, frequency validity or soil-texture range for either model | none | **Hallikainen et al. 1985; Dobson et al. 1985; Mironov et al. 2009** |

### Sentinel-1 TOPS: `S1/` (15 pages, **only SpectralDiversity, S1ETAD and EAP cite anything**)

| Page | Concept | Parameter gaps | Staleness / errors | Refs present | Refs missing |
|---|---|---|---|---|---|
| AzimuthShiftEstimationUsingESDOp | 1 | Defaults (`cohThreshold` 0.15, `numBlocksPerOverlap` 10) not stated | Title says "(EDS)" (line 14). Log path is "\.snap\var\log" (Windows-only notation). No explanation of what ESD measures or why 0.001 px matters. | none | Prats-Iraola 2012; Yagüe-Martínez 2016; Scheiber & Moreira 2000 |
| AzimuthShiftOp | 1 | **States "No parameter is needed" (line 67), but `cohThreshold` and `numBlocksPerOverlap` exist (`S1src/AzimuthShiftOp.java:64,68`)** | Legacy op superseded by Enhanced-Spectral-Diversity; page doesn't say so | none | same |
| BackGeocodingOp | 1 | `externalDEMNoDataValue` and `outputRangeAzimuthOffset` not documented | **DEM list stale** (ACE2_5min, ACE30, ASTER, SRTM; no Copernicus 30m, which is the code default). Recommends 21-point sinc, but the code default is 5-point. "Restitude" typo (lines 47-48). Deramp/demod is mentioned without saying why. | none | De Zan & Monti Guarnieri 2006; Sansosti et al. 2006 (geometric coregistration); Miranda 2017 (deramp TN) |
| DoubleDifferenceInterferogramOp | 0 | `cohWinSize` valueSet {3,5,9,11} | Never explains what a burst-overlap double-difference interferogram is or what it shows (residual azimuth misregistration). "outout" typo. | none | Yagüe-Martínez 2016 |
| EAPPhaseCorrection | 2 | none (no params) | The sentinels.copernicus.eu document URL is probably dead (migrated to SentiWiki, verify). "IPF V243/V236" should read 2.43/2.36. | ESA EAP note | – |
| ETADDeburstOp | 1 | Layer names wrong (§0.4) | Doesn't explain what each correction layer physically is | none | Gisinger et al. 2022 (verify) |
| RangeShiftOp | 1 | Defaults not given | Legacy (superseded by ESD) | none | – |
| S1ETADCorrectionOp | 2 | `sourceBands` and `outputETADPhaseBand` missing; Option 1/2 model stale (§0.10) | "new auxiliary product" is dated; no TOPS/GSLC chain guidance | ETAD PDD/PFS (DLR) | **Gisinger et al. 2022 TGRS (verify)** |
| Sentinel1SLCtoGRD | 1 | – | GRD-Post section: "Parameters Used: Non" (line 80, truncated) | none | – |
| SliceAssemblyOp | 2 | OK | "Sentinuel-1" (line 81) | none | S-1 Product Specification |
| SpectralDiversityOp | 2 | **`optObjective`, `noOfCandidateSolutions`, `optTolerance` and `optMaxIterations` not documented** (`S1src/SpectralDiversityOp.java:206-225`); `weightFunc` values not listed | Wrong XML instructions (§0.5); "worklfow" typo | Fattahi 2017; Yagüe-Martínez 2016 | Prats-Iraola 2012; Scheiber & Moreira 2000 |
| TOPSARDeburstOp | 2 | OK | "scae" typo (line 87); half the page describes IPF GRD assembly rather than the operator | none | De Zan & Monti Guarnieri 2006; Torres 2012 |
| TOPSARDerampDemodOp | 1 | OK | States deramping is needed but not why: no Doppler-centroid-variation or azimuth-steering explanation, no figure | none | **Miranda 2017 TN; De Zan & Monti Guarnieri 2006** |
| TOPSARMergeOp | 1 | OK | **Output claimed to be "co-registered reference and secondary bands in I/Q"**, but Merge works on any debursted split product (e.g. interferogram or coherence). Mentions the "TOPSAR Coreg Interferogram" graph by an old name. "on complete" typo. | none | – |
| TOPSARSplitOp | 1 | **`wktAoi` (AOI-based burst selection) and `enableSingleSwathMode` not documented**; bursts are actually `firstBurstIndex`/`lastBurstIndex` | "spilt" typo (line 57); no burst/sub-swath concept | none | – |

### Orbits: `ORB/` (1 page)

| Page | Concept | Parameter gaps | Staleness / errors | Refs present | Refs missing |
|---|---|---|---|---|---|
| ApplyOrbitFileOp | 2 | **Orbit-type list is stale.** The page lists 9 split entries; the code has 8 merged ones plus **K5 (KOMPSAT-5) and BIOMASS precise** (`sar-io-ephemeris/.../ApplyOrbitFileOp.java:91-97`), and "(Auto Download)" labels. | **"ESA GNSS Hub" (retired); the code auto-downloads from step.esa.int and points to CDSE (`SentinelPODOrbitFile.java:99`).** "UTM time" (lines 47-48) should be UTC. "Preceise" typo. No orbit accuracy figures (RESORB vs POEORB) or latency, and no account of why orbits matter for InSAR/TC. | none | Peter et al. 2017 (S1 POD, verify); Scharroo & Visser 1998 (ERS/DEOS, verify); S1 POD service file format spec |

---

## (b) Operators lacking help

1. **The "missing" biomass and forest helpIds are not a problem in this branch.** `AGBUncertaintyOp`, `BIOMASAROp`, `BIOMASARLOp`, `ChaveAllometricOp`, `DualPolFlatEarthTopoPhaseRemovalOp`, `DualPolForestHeightEstimationOp`, `DualPolPolarimetricCoherenceOp`, `ForestNonForestMaskOp`, `HVIntensityAGBOp`, `RVoG3StageInversionOp`, `RVoGForestHeightOp`, `SARDisturbanceDetectionOp`, `SinglePolCoherenceCompensationOp` and `WCMSoilVegetationOp` appear **only in leftover build output**:
   - `rstb/rstb-op-biomass-ui/target/classes/org/csa/rstb/biomass/layer.xml` (e.g. line 145, `RVoGForestHeightOp`)
   - `rstb/rstb-op-biomass/target/classes/org/csa/rstb/biomass/gpf/*.class` (jar built 2026-11-06)

   Neither module has a `src/` folder on branch `help`, and neither is listed in `rstb/pom.xml` (lines 45-56). The sources and help pages live on the **`rstb` branch** (commit `7e6941fc0`), which has `rstb/rstb-op-biomass-ui/src/main/resources/org/csa/rstb/biomass/docs/operators/{AGBUncertaintyOp,BIOMASARLOp,BIOMASAROp,ChaveAllometricOp,DualPolFlatEarthTopoPhaseRemovalOp,DualPolForestHeightEstimationOp,DualPolPolarimetricCoherenceOp,ForestNonForestMaskOp,…}.html` and its own `map.jhm`. A helpId audit that scans `target/` reports these as false positives. Action: delete the stale `rstb/rstb-op-biomass*/target` folders. When the `rstb` branch merges, review its biomass pages separately.
2. **`SAOCOM-Thermal-Noise-Removal`** (`sar-op-calibration/src/main/java/eu/esa/sar/calibration/gpf/SaocomRemoveThermalNoiseOp.java:46`) is public (not `internal`), registered in `sar-op-calibration/src/main/resources/META-INF/services/org.esa.snap.core.gpf.OperatorSpi`, and has parameters `selectedPolarisations`, `removeThermalNoise`, `reIntroduceThermalNoise` and a "Clip Negative Values" flag. It has **no menu entry, no helpId and no help page**, so it is reachable only from GPT or Graph Builder. It needs a page (it could share the concept text with the S-1 Thermal Noise Removal page).
3. **`GRD-Post`** (`S1src/GRDPostProcessOp.java:37`, public, used by the `Sentinel1SLCtoGRD` graphs) has no helpId of its own. It is described only as a sub-section of `S1/Sentinel1SLCtoGRD.html`, which is truncated ("Non").
4. **Internal operators** need no help page but are listed for completeness: `Azimuth-Spectrum` (`S1src/experimental/DerampedAzimuthSpectrumOp.java:46`), `Deramp-Demod-Phase` (`S1src/experimental/DerampDemodPhaseOp.java:41`), `Flood-Detection` (`sar-op-feature-extraction/.../flood/FloodDetectionOp.java:40`), and `Forest-Area-Detection` / `Forest-Area-Classification` (`sar-op-feature-extraction/.../forest/*.java`).
5. **`MuLog-Filter`** (`sar-op-sar-processing/.../SpeckleFilters/MuLog.java:18`) carries `@OperatorMetadata` (authors = "AI") but is a `SpeckleFilter` implementation, not an Operator. It is documented inside `SpeckleFilterOp.html`. No page is needed, but the stray annotation and the "AI" author should be cleaned up.
6. **`rstb-io` helpIds** `importRadarsat1`, `importRadarsat2` and `importRCM` (`rstb/rstb-io/src/main/resources/org/csa/rstb/io/layer.xml:11,20,29`) have no `map.jhm` target anywhere in the repo, so the reader "Help" buttons are dead. This is out of scope but was found by the same audit.
7. **Pages that exist but cannot be reached:**
   - `SM/Hallikainen.html` is in neither `map.jhm` nor `toc.xml`.
   - `SM/IEM-LUT.html` is not in `map.jhm` or `toc.xml`; it is reachable only through in-page links.
8. **Decomposition and classification methods offered in the UI but undocumented:**
   - Huynen, Yang, Krogager, Cameron and H-Alpha Dual Pol (Polarimetric-Decomposition).
   - Cloude-Pottier Dual Pol and H-Alpha-Wishart Dual Pol (Polarimetric-Classification).
   - Matrix C2 (Polarimetric-Matrices).

---

## (c) Problems that recur across pages

1. **No page explains the underlying concepts.**
   - Polarimetry: every page assumes the reader already knows the scattering matrix, reciprocity, the lexicographic vs Pauli basis, C vs T, reflection symmetry, roll invariance and Wishart statistics. The decomposition page is the closest to an introduction but has the Pauli error.
   - Sentinel-1: none of the 15 pages explains TOPS. Bursts, azimuth beam steering, Doppler-centroid variation within a burst, why deramping is required before interpolation, and why azimuth coregistration must reach about 0.001 px (otherwise there are phase jumps at burst boundaries) are all missing. The ESD pages do not say what ESD measures.
2. **References are thin.**
   - The 11 soil-moisture pages cite **nothing**, not even Fung 1992 (IEM), Hallikainen 1985 or Mironov 2009.
   - 12 of the 15 Sentinel-1 pages and the orbits page cite nothing.
   - In polarimetry, the core papers (Cloude & Pottier 1996/1997, Freeman & Durden 1998, Yamaguchi 2005/2011, van Zyl 1989, Lee 1994/1999 Wishart, Lee 2000 POA) are missing. The pages lean on the Lee & Pottier book alone.
3. **Pages drift from the code.** There are about 20 undocumented parameters (listed in table (a)) and 3 pages that say "no parameters" or "optional output" when the reverse is true:
   - AzimuthShift and OrientationAngleCorrection both say "no parameter" although each has parameters.
   - The Faraday Ω output band is described but cannot be enabled.

   A drift check like `guides-docs-tutorials/tools/check_params.py` should be pointed at these JavaHelp folders.
4. **Most pages give no defaults or physical units.** For example, they do not explain why NESZ matters or what ENL means. Users cannot tell a sensible setting from a damaging one.
5. **Equations are bitmaps.** About 40 JPEG/PNG equation images:
   - cannot be searched or read by screen readers;
   - are low resolution and in mixed styles;
   - break when the file-name case is wrong.

   Recommend MathML-free HTML entities (as on the newer Faraday and change-detection pages) or re-rendered SVG.
6. **Stale terminology and products.**
   - "RSTB" appears on the Supervised Wishart page (lines 48, 114).
   - The soil-moisture page uses "master/slave/mst/slv" (`MultiAnglePreProcessing.html:78-81`), against the project's Reference/Secondary convention.
   - Out-of-date items: the SRTM 3sec and ACE DEM lists, "ESA GNSS Hub", the ETAD "Option 1/2" model, and the "TOPSAR Coreg Interferogram" graph name.
   - The linked `general/Sentinel1.html` still describes an S-1A/B constellation.
7. **Workflow context is missing.**
   - Sentinel-1 pages almost never link to one another or to `sar/docs/general/Sentinel1.html`, and never show the chain Split → Apply Orbit → Back-Geocoding → ESD → Interferogram → Deburst → Merge (or the GSLC alternative).
   - Polarimetric pages don't state the canonical order: calibrate (complex) → [Faraday / cross-channel SNR] → matrix → speckle filter → [POA] → decomposition / classification → terrain correction.
   - S-1 dual-pol C2 users are pointed to an external preprint graph (`RVIOp.html`) rather than an in-toolbox page.
8. **The Indian Institute of Technology Bombay (IITB) Microwave Remote Sensing Lab (MRSLab) pages use a different layout.** Their contributor cards carry personal phone numbers and e-mail addresses. They lack "Parameters Used" and "Input/Output" sections; RVIOp and CPRVIOp have them in a different form. They also lack consistent result-interpretation guidance (value ranges and what a high or low index means).
9. **Code metadata has copy-paste errors.** Descriptions in `CrossChannelSNRCorrectionOp`, `DpDecompositionOp`, `DpFactorizationOp` and `DPRBIOp` are wrong ("Dual-pol Radar Built-up Index" / "Compute general polarimetric parameters"). The `PolarimetricSpeckleFilterOp.sigmaStr` label is "Point target window Size". These strings appear in GPT `-h` output and tool-tips.

---

## (d) Recommended new concept pages

Each page should be about 1-2 screens long, written for non-experts, with SVG diagrams and full citations, and linked from every relevant operator page.

1. **"Polarimetry Primer: Scattering Matrix, C and T Matrices, Pauli Basis".**
   - Content: the Sinclair matrix; reciprocity; target vectors k_L and k_P; C2/C3/C4/T3/T4 and when to use each; dual-pol C2 (S-1, NISAR); compact-pol C2.
   - Content: Pauli RGB interpretation (correct colours); span; reflection symmetry; roll invariance.
   - Refs: Lee & Pottier 2009; Cloude & Pottier 1996; Cloude 2009.
2. **"Incoherent Decompositions Explained".**
   - Eigen-based methods: Cloude-Pottier H/A/ᾱ with the H-α plane and its nine zones; Touzi TSVM.
   - Model-based methods: Freeman-Durden, Yamaguchi (with the rotation of T3), van Zyl, Generalised FD, MF3CF/MF4CF.
   - Coherent methods: Krogager, Cameron, Huynen.
   - Pitfalls: negative powers, volume over-estimation, POA (orientation angle) compensation.
   - A "which decomposition for which question" table.
3. **"Polarimetric Speckle and Wishart Statistics".** Covers the complex Wishart distribution, ENL, why filtering must preserve the matrix, the windowSize-vs-ENL trade-off (needed by change detection and classification), and Box car / refined Lee / IDAN / Lee sigma compared. Refs: Lee, Grunes & De Grandi 1999; Lee et al. 1994; Conradsen 2003.
4. **"Compact and Hybrid Polarimetry".**
   - The CTLR / hybrid-pol architecture and the π/4 mode; Stokes vector g0-g3; degree of polarisation m; m-χ / m-δ / m-α; CpRVI.
   - Real CP missions: RCM (plus NISAR/RISAT, verify).
   - Refs: Raney 2007; Raney et al. 2012; Souyris 2005; Charbonneau 2010; Cloude, Goodenough & Chen 2012.
5. **"Dual-pol (Sentinel-1) Polarimetry".** Covers what C2 from VV+VH can and cannot tell you, the absence of co-pol phase, DoP/entropy, DpRVI/DpRBI/DpRSI/RFDI, and the NESZ floor and why it matters. Refs: Cloude 2007; Mandal 2020; Mascolo 2022; Verma 2023/2024.
6. **"Propagation and Calibration Effects".** Covers Faraday rotation (Ω ∝ λ²·TEC·B∥), cross-talk and channel imbalance, cross-channel SNR, and the "do I need polarimetric calibration?" decision. Refs: Freeman 2004; Bickel & Bates 1965; Quegan 1994; Ainsworth 2006.
7. **"Polarimetric Classification Overview".** Covers Wishart distance, the H/α-Wishart, Freeman-Wishart and supervised workflows, and their dual-pol variants. Refs: Lee 1994, 1999, 2004; Cloude & Pottier 1997.
8. **"Soil Moisture from SAR".**
   - Dielectric constant vs volumetric moisture: Hallikainen and Dobson (empirical) vs Mironov (spectroscopic), and their validity ranges.
   - Surface roughness (rms, cl).
   - The IEM forward model and LUT inversion, including the non-uniqueness that motivates the outlier filter.
   - What "calibrated IEM" means, and multi-angle vs multi-pol vs hybrid.
   - Refs: Fung 1992; Hallikainen 1985; Dobson 1985; Mironov 2009; Baghdadi 2004; Ulaby, Moore & Fung (book).
9. **"Sentinel-1 TOPS Primer".**
   - Sub-swaths and bursts, illustrated, with burst overlap.
   - Azimuth beam steering and the Doppler centroid sweep within a burst, with a figure of the azimuth spectrum before and after deramping.
   - Why deramp/demod is needed before resampling, debursting and merging.
   - Burst IDs; Sentinel-1C/1D.
   - Refs: De Zan & Monti Guarnieri 2006; Torres et al. 2012; Miranda 2017.
10. **"TOPS InSAR Coregistration and ESD".**
    - Geometric back-geocoding, then ESD in the burst overlaps.
    - The 0.001 px requirement, i.e. the phase jump Δφ = 2π·Δf_DC·Δt at burst edges.
    - Double-difference interferograms as a diagnostic; the network ESD (NESD) estimator.
    - The full workflow diagram (classical vs GSLC).
    - Refs: Prats-Iraola 2012; Yagüe-Martínez 2016; Fattahi 2017; Scheiber & Moreira 2000.
11. **"Timing Corrections: ETAD and EAP".** Covers each ETAD layer (tropo, iono, solid-earth tide/geodetic, Doppler, bistatic, FM mismatch), their magnitude, when to apply them, and their interaction with ESD and GSLC; also the EAP phase history (IPF 2.43). Refs: Gisinger et al. 2022; ESA EAP note.
12. **"Orbits for SAR".** Covers orbit types (predicted, restituted, precise/POE), latency and accuracy, sources (step.esa.int, CDSE, MAAP for BIOMASS), and the effect on geolocation and InSAR baselines. Refs: Peter et al. 2017.

---

## (e) Consolidated bibliography

"(verify)" marks details I am not fully certain of. Entries I am confident in carry no mark.

### Polarimetry: foundations and books
- Lee, J.-S., Pottier, E. (2009). *Polarimetric Radar Imaging: From Basics to Applications*. CRC Press.
- Cloude, S. R. (2009). *Polarisation: Applications in Remote Sensing*. Oxford University Press.
- Cloude, S. R., Pottier, E. (1996). A review of target decomposition theorems in radar polarimetry. *IEEE TGRS* 34(2), 498-518.
- Cloude, S. R., Pottier, E. (1997). An entropy based classification scheme for land applications of polarimetric SAR. *IEEE TGRS* 35(1), 68-78.
- Huynen, J. R. (1970). *Phenomenological Theory of Radar Targets*. PhD thesis, Delft University of Technology.
- Barakat, R. (1977). Degree of polarization and the principal idempotents of the coherency matrix. *Optics Communications* 23(2), 147-150.

### Polarimetry: decompositions
- Freeman, A., Durden, S. L. (1998). A three-component scattering model for polarimetric SAR data. *IEEE TGRS* 36(3), 963-973.
- Yamaguchi, Y., Moriyama, T., Ishido, M., Yamada, H. (2005). Four-component scattering model for polarimetric SAR image decomposition. *IEEE TGRS* 43(8), 1699-1706.
- Yamaguchi, Y., Sato, A., Boerner, W.-M., Sato, R., Yamada, H. (2011). Four-component scattering power decomposition with rotation of coherency matrix. *IEEE TGRS* 49(6), 2251-2258. (verify author order)
- van Zyl, J. J. (1989). Unsupervised classification of scattering behavior using radar polarimetry data. *IEEE TGRS* 27(1), 36-45.
- Touzi, R. (2007). Target scattering decomposition in terms of roll-invariant target parameters. *IEEE TGRS* 45(1), 73-84.
- Krogager, E. (1990). New decomposition of the radar target scattering matrix. *Electronics Letters* 26(18), 1525-1527.
- Cameron, W. L., Leung, L. K. (1990). Feature motivated polarization scattering matrix decomposition. *Proc. IEEE International Radar Conference*, 549-557. (verify)
- Yang, J., Peng, Y.-N., Lin, S.-M. (2001). Similarity between two scattering matrices. *Electronics Letters* 37(3), 193-194. (verify)
- Dey, S., Bhattacharya, A., Ratha, D., Mandal, D., Frery, A. C. (2021). Target characterization and scattering power decomposition for full and compact polarimetric SAR data. *IEEE TGRS* 59(5), 3981-3998. (The page gives 2020: the online date versus the issue year.)
- Dey, S., Bhattacharya, A., Frery, A. C., López-Martínez, C., Rao, Y. S. (2021). A model-free four component scattering power decomposition for polarimetric SAR data. *IEEE JSTARS* 14, 3887-3902.
- Ratha, D., Pottier, E., Bhattacharya, A., Frery, A. C. (2020). A PolSAR scattering power factorization framework and novel roll-invariant parameter-based unsupervised classification scheme using a geodesic distance. *IEEE TGRS* 58(5), 3509-3525.

### Polarimetry: dual-pol and compact-pol
- Mascolo, L., Cloude, S. R., Lopez-Sanchez, J. M. (2022). Model-based decomposition of dual-pol SAR data: application to Sentinel-1. *IEEE TGRS* 60. doi:10.1109/TGRS.2021.3137588.
- Cloude, S. R. (2007). The dual polarization entropy/alpha decomposition: a PALSAR case study. *Proc. PolInSAR 2007*, ESA SP-644. (verify)
- Raney, R. K. (2007). Hybrid-polarity SAR architecture. *IEEE TGRS* 45(11), 3397-3404.
- Raney, R. K., Cahill, J. T. S., Patterson, G. W., Bussey, D. B. J. (2012). The m-chi decomposition of hybrid dual-polarimetric radar data with application to lunar craters. *JGR Planets* 117, E00H21.
- Souyris, J.-C., Imbo, P., Fjørtoft, R., Mingot, S., Lee, J.-S. (2005). Compact polarimetry based on symmetry properties of geophysical media: the π/4 mode. *IEEE TGRS* 43(3), 634-646.
- Cloude, S. R., Goodenough, D. G., Chen, H. (2012). Compact decomposition theory. *IEEE GRSL* 9(1), 28-32. (verify pages)
- Charbonneau, F. J., Brisco, B., Raney, R. K., et al. (2010). Compact polarimetry overview and applications assessment. *Canadian Journal of Remote Sensing* 36(S2), S298-S315. (verify)
- Verma, A., Bhattacharya, A., Dey, S., López-Martínez, C., Gamba, P. (2023). Built-up area mapping using Sentinel-1 SAR data. *ISPRS J. Photogramm. Remote Sens.* 203, 55-70.
- Verma, A., Bhattacharya, A., Dey, S., López-Martínez, C., Gamba, P. (2024). Scattering power components from dual-pol Sentinel-1 SLC and GRD SAR data. *ISPRS J. Photogramm. Remote Sens.* 212, 289-305.

### Vegetation and forest indices
- Kim, Y., van Zyl, J. J. (2009). A time-series approach to estimate soil moisture using polarimetric radar data. *IEEE TGRS* 47(8), 2519-2527. (RVI definition)
- Mandal, D., Kumar, V., Ratha, D., et al. (2020). Dual polarimetric radar vegetation index for crop growth monitoring using Sentinel-1 SAR data. *Remote Sensing of Environment* 247, 111954.
- Mandal, D., et al. (2020). A radar vegetation index for crop monitoring using compact polarimetric SAR data. *IEEE TGRS* 58(9), 6321-6335.
- Ratha, D., Mandal, D., Kumar, V., McNairn, H., Bhattacharya, A., Frery, A. C. (2019). A generalized volume scattering model-based vegetation index from polarimetric SAR data. *IEEE GRSL* 16(11), 1791-1795.
- Mitchard, E. T. A., Saatchi, S. S., White, L. J. T., et al. (2012). Mapping tropical forest biomass with radar and spaceborne LiDAR in Lopé National Park, Gabon: overcoming problems of high biomass and persistent cloud. *Biogeosciences* 9, 179-191. (verify; the pages currently say *Carbon Balance and Management* 7, 1)
- Pope, K. O., Rey-Benayas, J. M., Paris, J. F. (1994). Radar remote sensing of forest and wetland ecosystems in the Central American tropics. *Remote Sensing of Environment* 48(2), 205-219.

### Speckle, statistics, classification and change detection
- Lee, J.-S., Grunes, M. R., Kwok, R. (1994). Classification of multi-look polarimetric SAR imagery based on complex Wishart distribution. *Int. J. Remote Sensing* 15(11), 2299-2311.
- Lee, J.-S., Grunes, M. R., Ainsworth, T. L., Du, L.-J., Schuler, D. L., Cloude, S. R. (1999). Unsupervised classification using polarimetric decomposition and the complex Wishart classifier. *IEEE TGRS* 37(5), 2249-2258.
- Lee, J.-S., Grunes, M. R., Pottier, E., Ferro-Famil, L. (2004). Unsupervised terrain classification preserving polarimetric scattering characteristics. *IEEE TGRS* 42(4), 722-731.
- Lee, J.-S., Grunes, M. R., De Grandi, G. (1999). Polarimetric SAR speckle filtering and its implication for classification. *IEEE TGRS* 37(5), 2363-2373.
- Lee, J.-S., Wen, J.-H., Ainsworth, T. L., Chen, K.-S., Chen, A. J. (2009). Improved sigma filter for speckle filtering of SAR imagery. *IEEE TGRS* 47(1), 202-213.
- Vasile, G., Trouvé, E., Lee, J.-S., Buzuloiu, V. (2006). Intensity-driven adaptive-neighborhood technique for polarimetric and interferometric SAR parameters estimation. *IEEE TGRS* 44(6), 1609-1621.
- Lee, J.-S. (1983). Digital image smoothing and the sigma filter. *Computer Vision, Graphics, and Image Processing* 24(2), 255-269.
- Conradsen, K., Nielsen, A. A., Schou, J., Skriver, H. (2003). A test statistic in the complex Wishart distribution and its application to change detection in polarimetric SAR data. *IEEE TGRS* 41(1), 4-19.
- Conradsen, K., Nielsen, A. A., Skriver, H. (2016). Determining the points of change in time series of polarimetric SAR data. *IEEE TGRS* 54(5), 3007-3024.

### Polarimetric calibration and propagation
- Quegan, S. (1994). A unified algorithm for phase and cross-talk calibration of polarimetric data: theory and observations. *IEEE TGRS* 32(1), 89-99.
- Ainsworth, T. L., Ferro-Famil, L., Lee, J.-S. (2006). Orientation angle preserving a posteriori polarimetric SAR calibration. *IEEE TGRS* 44(4), 994-1003.
- Lee, J.-S., Schuler, D. L., Ainsworth, T. L. (2000). Polarimetric SAR data compensation for terrain azimuth slope variation. *IEEE TGRS* 38(5), 2153-2163.
- Freeman, A. (2004). Calibration of linearly polarized polarimetric SAR data subject to Faraday rotation. *IEEE TGRS* 42(8), 1617-1624.
- Bickel, S. H., Bates, R. H. T. (1965). Effects of magneto-ionic propagation on the polarization scattering matrix. *Proc. IEEE* 53(8), 1089-1091.
- Wright, P. A., Quegan, S., Wheadon, N. S., Hall, C. D. (2003). Faraday rotation effects on L-band spaceborne SAR data. *IEEE TGRS* 41(12), 2735-2744.
- Touzi, R., Lopes, A. (1996). Statistics of the Stokes parameters and of the complex coherence parameters in one-look and multi-look speckle fields. *IEEE TGRS* 34(2), 519-531.
- Meyer, F. J., Nicoll, J. B. (2008). Prediction, detection, and correction of Faraday rotation in full-polarimetric L-band SAR data. *IEEE TGRS* 46(10), 3076-3086. (verify)

### Soil moisture
- Fung, A. K., Li, Z., Chen, K. S. (1992). Backscattering from a randomly rough dielectric surface. *IEEE TGRS* 30(2), 356-369.
- Hallikainen, M. T., Ulaby, F. T., Dobson, M. C., El-Rayes, M. A., Wu, L.-K. (1985). Microwave dielectric behavior of wet soil, Part I: Empirical models and experimental observations. *IEEE TGRS* GE-23(1), 25-34.
- Dobson, M. C., Ulaby, F. T., Hallikainen, M. T., El-Rayes, M. A. (1985). Microwave dielectric behavior of wet soil, Part II: Dielectric mixing models. *IEEE TGRS* GE-23(1), 35-46.
- Mironov, V. L., Kosolapova, L. G., Fomin, S. V. (2009). Physically and mineralogically based spectroscopic dielectric model for moist soils. *IEEE TGRS* 47(7), 2059-2070.
- Baghdadi, N., Gherboudj, I., Zribi, M., Sahebi, M., King, C., Bonn, F. (2004). Semi-empirical calibration of the IEM backscattering model using radar images and moisture and roughness field measurements. *Int. J. Remote Sensing* 25(18), 3593-3623. (verify)
- Merzouki, A., McNairn, H., Pacheco, A. (2011). Mapping soil moisture using RADARSAT-2 data and local autocorrelation statistics. *IEEE JSTARS* 4(1), 128-137. (verify; this is likely the basis of the RSTB IEM-LUT and neighbourhood-outlier scheme)
- Ulaby, F. T., Moore, R. K., Fung, A. K. (1986). *Microwave Remote Sensing: Active and Passive*, Vol. III. Artech House.

### Sentinel-1 TOPS
- De Zan, F., Monti Guarnieri, A. (2006). TOPSAR: Terrain Observation by Progressive Scans. *IEEE TGRS* 44(9), 2352-2360.
- Torres, R., Snoeij, P., Geudtner, D., et al. (2012). GMES Sentinel-1 mission. *Remote Sensing of Environment* 120, 9-24.
- Prats-Iraola, P., Scheiber, R., Marotti, L., Wollstadt, S., Reigber, A. (2012). TOPS interferometry with TerraSAR-X. *IEEE TGRS* 50(8), 3179-3188.
- Yagüe-Martínez, N., Prats-Iraola, P., Rodríguez González, F., et al. (2016). Interferometric processing of Sentinel-1 TOPS data. *IEEE TGRS* 54(4), 2220-2234.
- Fattahi, H., Agram, P., Simons, M. (2017). A network-based enhanced spectral diversity approach for TOPS time-series analysis. *IEEE TGRS* 55(2), 777-786.
- Scheiber, R., Moreira, A. (2000). Coregistration of interferometric SAR images using spectral diversity. *IEEE TGRS* 38(5), 2179-2191.
- Sansosti, E., Berardino, P., Manunta, M., Serafino, F., Fornaro, G. (2006). Geometrical SAR image registration. *IEEE TGRS* 44(10), 2861-2870. (verify)
- Miranda, N. (2017). *Definition of the TOPS SLC deramping function for products generated by the S-1 IPF*. ESA technical note COPE-GSEG-EOPG-TN-14-0025. (verify issue)
- Gisinger, C., Libert, L., Marinkovic, P., et al. (2022). The Extended Timing Annotation Dataset for Sentinel-1: product description and first evaluation results. *IEEE TGRS* 60, 5232622. (verify year and article number)
- ESA/DLR. S1-ETAD Product Definition Document ETAD-DLR-PS-0002 and Product Format Specification ETAD-DLR-PS-0014 (already cited).
- ESA. *Impact of the Elevation Antenna Pattern phase compensation on the interferometric phase preservation* (EAP note, 2015; already cited by a probably-dead URL).
- ESA/MDA. *Sentinel-1 Product Specification*, S1-RS-MDA-52-7441 (slice assembly rules).

### Orbits
- Peter, H., Jäggi, A., Fernández, J., et al. (2017). Sentinel-1A: first precise orbit determination results. *Advances in Space Research* 60(5), 879-892. (verify)
- Scharroo, R., Visser, P. (1998). Precise orbit determination and gravity field improvement for the ERS satellites. *JGR Oceans* 103(C4), 8113-8127. (verify)
