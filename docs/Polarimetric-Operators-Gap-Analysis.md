# Polarimetric SAR Operator Gap Analysis — microwave-toolbox/rstb

Date: 2026-04-27. Survey of `E:\ESA\microwave-toolbox\rstb` against recent (2018–2025) polarimetric SAR literature.

## A. What is already in rstb (recap)

- Matrix gen: `PolarimetricMatricesOp` (T3/C3/C2)
- Decompositions: 19 quad-pol (Cloude H/A/α, Pauli, Sinclair, Cameron, Krogager, Huynen, Yamaguchi 4C, Freeman-Durden, Generalized FD, vanZyl, Yang T-invariant, Touzi TSVM, MF3CF, MF4CF), 5 compact-pol (H/α, m-χ, m-δ, MF3CC, RVoG), 2 dual-pol (H/α C2, ModelBased C2)
- Indices: RVI, CPRVI, GRVI; with this branch DpRBI, DpRSI, DpDecomposition, DpFactorization
- Speckle filters: BoxCar, RefinedLee, LeeSigma, IDAN, NonLocal
- Calibration: FaradayRotation, OrientationAngle, CrossChannelSNR
- Compact-pol: CP simulation, CP Stokes, CP decomposition wrapper
- Classification (separate module): Cloude-Pottier, FreemanDurden-Wishart, H/α-Wishart (T3 + C2), General Wishart, Supervised Wishart
- Soil moisture (separate module): hybrid IEM, multi-angle, multi-pol, dielectric model

Strong on classical quad-pol decomposition. Big gaps: **modern (post-2018) decompositions**, **PolInSAR**, **change detection**, **deep speckle filters**, and **modern dual/compact-pol indices**.

## B. Missing operators — ranked candidates

Each entry: name → mode → algorithm → reference. Ordered by impact-to-effort.

### B.1 Quad-pol decompositions

1. **Yamaguchi 6-component (Y6SD)** — Quad — Adds *oriented dipole (OD)* and *oriented quarter-wave (OQW)* to Y4D using rotated T3, accounting for ImT13/ReT13 asymmetry. Singh, Yamaguchi & Park, *IEEE TGRS* 2018.
2. **Yamaguchi/Singh 7-component (Y7SD)** — Quad — Y6SD + *mixed dipole (MD)* model. Better in oblique urban. Singh et al. 2019; refined 2020 with eigenvalue-based obliquely-oriented dihedral.
3. **G4U / G5U — General N-component with Unitary Transformation** — Quad — SU(3) rotation that zeros T13 to maximize physical scattering powers; G4U (2014), G5U (2023, *Remote Sens.* 15(5):1332). Mathematically clean, tile-friendly.
4. **NNED / ANNED — Non-Negative Eigenvalue Decomposition** — Quad — Removes the negative-power problem of FD/Y4D by enforcing positive remainder eigenvalues. ANNED replaces fixed cosine-squared volume PDF with adaptive branching ratio. van Zyl, Arii & Kim 2011.
5. **Cameron full classification** — Quad — Confirm `Cameron` op exposes the symmetric/non-symmetric/reciprocal classification, not just powers.
6. **Polarimetric Subaperture Decomposition (Souyris-style)** — Quad — Splits T3 into azimuth subapertures; spectral coherence per Pauli channel for non-stationary target detection. Souyris 1995, modern revisits 2022.

### B.2 Dual-pol decompositions / indices

7. **iDPSV / Stokes-vector dual-pol decomposition** — Dual — 3-component decomposition for HH+HV or VV+VH using normalized Stokes (g0..g3) and degree of polarization m. Mascolo, Lopez-Martinez et al. 2015–2024.
8. **Modified Yamaguchi 3-component for dual-pol (Y3-DP)** — Dual — Surface/double/volume powers from C2 with HV-volume hypothesis. Mascolo & Cloude 2022.
9. **Radar Forest Degradation Index (RFDI)** — Dual — `RFDI = (HH − HV)/(HH + HV)`. Trivial; widely used. Mitchard et al. 2012.
10. **Cross-Ratio (CR)** — Dual — `CR = HV/VV` (or `VH/VV`). Used in moisture/biomass; missing as one-click op.
11. **Dual-pol H/A/α extended (Cloude C2)** — Dual — Verify existing `HAlphaC2` exposes the **anisotropy A12** parameter (Cloude 2007); many implementations skip A.
12. **Polarimetric Channel Algebra** — Dual — Generic helpers (HH−VV, VH/VV phase, span). Useful as a one-click utility.
13. **Model-and-learning-aided dual-pol decomposition** — Dual — CNN-aided physical decomposition of S1 (Mu, Verma et al. 2024). Defer — heavy ML dep.

### B.3 Compact-pol decompositions

14. **m-α (modified m-α / RVoG-like)** — Compact — `α_s = arctan(sqrt((1−m)/m))` on Stokes; complement to m-δ/m-χ. Cloude 2009.
15. **3-component CP with urban descriptor** — Compact — Adds urban scattering term to m-χ. Yin & Yang 2021.
16. **CP X-Bragg + Oh inversion** — Compact — Soil moisture from compact-pol via X-Bragg. Candidate for `rstb-op-soil-moisture`.

### B.4 Polarimetric speckle filters

17. **NL-SAR (non-local SAR)** — All modes — Patch-based non-local averaging on C2/C3/T3 with Wishart weighting. Deledalle, Denis & Tupin, *IEEE TGRS* 2015. Strict upgrade over current `NonLocal`.
18. **MuLoG (Multi-channel Logarithm with Gaussian denoising)** — All modes — Plug-and-play framework — any Gaussian denoiser (BM3D, DnCNN) works on PolSAR via log transform. Deledalle et al. 2017/2022.
19. **RABASAR (RAtio-BAsed SAR)** — Time-series — Multi-temporal denoising via super-image + ratio. Zhao et al. *IEEE TGRS* 2019.
20. **Polarimetric BM3D / SAR-BM3D** — All modes — Block-matching 3D adapted to PolSAR. Foucher 2017.
21. **Adaptive RefinedLee** — All modes — Verify rstb's `RefinedLee` is the 2009 Lee/Wen/Ainsworth version, not 1981.
22. **PolSAR2PolSAR / SAR2SAR (deep)** — All modes — Self-supervised CNN denoiser. Dalsasso, Denis, Tupin 2021/2025. Belongs in separate `rstb-op-deep-learning` module.

### B.5 Change detection — entirely missing

23. **Polarimetric coherent change detection (P-CCD / GLRT)** — Quad/Dual — Generalized likelihood-ratio test on T3 covariance pairs. Conradsen et al. *IEEE TGRS* 2003.
24. **Omnibus test (Conradsen et al. 2016)** — Quad/Dual — Multi-temporal Wishart change detection across N images. Currently a script in S1TBX; deserves a first-class op.
25. **Bivariate-Gamma / Hotelling-Lawley for dual-pol time series** — Dual — Lighter change indicators for S1 stacks. Nielsen et al. 2017.
26. **REACTIV color visualization** — Dual — Standard time-series RGB compositor for S1. Hagolle et al. 2019. Trivial.

### B.6 PolInSAR — entirely missing from rstb-op-polarimetric-tools

27. **PolInSAR Coherence Optimization (ESPRIT/Numerical)** — Quad+Interferometric — Cloude & Papathanassiou 1998.
28. **Three-Stage RVoG forest height inversion** — Quad+Interferometric — Closed-form height + extinction estimator. Papathanassiou & Cloude *IEEE TGRS* 2001.
29. **Modified three-stage / TF-RVoG / R-RVoG / TD-RVoG** — Variants for sloped terrain, time decorrelation, sublook (2016–2023). Add as parameters of #28 rather than separate ops.
30. **Compact-PolInSAR forest height** — Compact+Interferometric — For NISAR/RISAT-1.

### B.7 Calibration — partial coverage

31. **Quegan crosstalk calibration** — Quad — Closed-form distributed-target calibrator. Quegan *IEEE TGRS* 1994. Most-cited; missing.
32. **Ainsworth crosstalk calibration** — Quad — Improved-Quegan with iterative cross-coupling. Ainsworth et al. *IEEE TGRS* 2006.
33. **Trihedral CR channel imbalance** — Quad — Phase + amplitude balance from corner-reflector signatures.
34. **CMET (Covariance Matching Estimation)** — Quad — Newer (*Remote Sens.* 16(13):2400, 2024) iterative calibration.

### B.8 Biomass / forest / crop / snow inversion

35. **Extended Water Cloud Model (EWCM) AGB** — Quad/Dual — Polarimetric WCM with FD components. Kumar et al. 2019; refinements 2023.
36. **PolSAR snow wetness / SWE** — Compact/Quad — Cloude H/α + extinction, recent cryosphere papers (2024).
37. **Crop phenology Stokes/H-α descriptor sets** — Dual — IIT Bombay growth-stage descriptors as a packaged op.

### B.9 Polarimetric classification — gaps

38. **Wishart-Markov contextual classifier** — Quad/Dual — Wishart likelihood + ICM/MRF spatial prior. Wu et al. 2008.
39. **Lee-style H/α/A 16-class Wishart entry** — Quad — Verify existing `HAlphaWishart` uses anisotropy (3D zones, 16 classes, Lee 1999).
40. **Deep PolSAR classifier (CV-CNN, attention)** — Quad/Dual — *ISPRS J. Photogramm.* 2025. Likely separate module due to heavy deps.

## C. Recommended priorities

**Tier 1 — must-have, low effort (<1 week each)**
- RFDI (#9), Cross-Ratio (#10), Channel Algebra (#12) — trivial dual-pol utilities
- Y6SD / Y7SD (#1, #2) — extensions of existing Yamaguchi
- G4U / G5U (#3) — small SU(3) rotation on existing T3
- m-α compact (#14) — analog of existing m-χ/m-δ
- Quegan + Ainsworth (#31, #32) — well-documented closed forms

**Tier 2 — high impact, medium effort (1–3 weeks each)**
- P-CCD / Omnibus change detection (#23, #24) — entire missing capability
- NL-SAR (#17) — strict upgrade over current `NonLocal`
- iDPSV / Y3-DP dual-pol decomposition (#7, #8) — fills dual-pol decomposition gap
- Three-Stage RVoG forest height (#28) — opens PolInSAR area
- EWCM biomass (#35) — leverages existing FD outputs

**Tier 3 — strategic, larger investment**
- MuLoG / RABASAR / SAR2SAR family (#18, #19, #22) — modern denoising
- Deep PolSAR classifier (#40) — separate module
- Full PolInSAR module (coherence optimization + height + biomass + change) — substantial but high-impact

## D. Cross-cutting fixes worth doing in parallel

1. ✅ **Anisotropy A coverage**: `HaAlphaDescriptor.getZoneIndex(H, α, A, useLee)` overload added (Lee 1999 18-zone form). `CloudePottier` opts in via `<contextId>.useAnisotropy` system property and reports `getNumClasses() = 18`. `HAlphaWishart` flagged with TODO — its K-means fixed-size `[9]` arrays need refactoring to support 18 zones.
2. ✅ **NESZ-aware decompositions**: `DpDecompositionOp` and `DpFactorizationOp` now expose NESZ as a `@Parameter` (default −16 dB) with sensor-specific guidance in the description.
3. ✅ **POA deshift**: `PolarimetricDecompositionOp` has a new `applyOrientationAngleCorrection` boolean parameter; when true it programmatically chains `Orientation-Angle-Correction` (via `GPF.createProduct`) before dispatching to the selected decomposition. C2 dual-pol input is silently passed through.
4. ⏸️ **C2 vs T2 input**: deferred. `T2` is not in `PolBandUtils.MATRIX` enum; full-pol T3↔C3 is already handled internally via `t3ToC3`. Adding genuine T2 dual-pol support is a multi-file change without a clear user requirement.
5. ✅ **Tile boundary handling**: audited. All window-based ops (DecompositionBase, RVI/CPRVI/GRVI, CrossChannelSNR, DPRBI/DPRSI/DpDecomposition/DpFactorization, and the new DualPolRatioIndices/Y3-DP/PCCD/MAlpha/ChannelAlgebra) use the same canonical half-window padding pattern. No inconsistency found.

## E. Canonical references

| # | Paper |
|---|-------|
| 1, 2 | Singh, Yamaguchi, Park, *IEEE TGRS* 2013/2018; Singh et al. 2019; Chen et al. *Remote Sens.* 2020 |
| 3 | Singh & Yamaguchi, *Remote Sens.* 2014 (G4U); 2023 (G5U) |
| 4 | van Zyl, Arii & Kim, *IEEE TGRS* 2011 |
| 7, 8 | Mascolo et al., *IEEE TGRS* 2015–2024 |
| 9 | Mitchard et al., *Carbon Balance Manag.* 2012 |
| 17 | Deledalle, Denis, Tupin, *IEEE TGRS* 2015 (NL-SAR) |
| 18 | Deledalle et al., *IEEE TGRS* 2017/2022 (MuLoG) |
| 19 | Zhao, Deledalle et al., *IEEE TGRS* 2019 (RABASAR) |
| 22 | Dalsasso, Denis, Tupin, *IEEE TGRS* 2021/2025 |
| 23, 24 | Conradsen, Nielsen et al., *IEEE TGRS* 2003, 2016 |
| 28 | Papathanassiou & Cloude, *IEEE TGRS* 2001 (Three-Stage RVoG) |
| 31, 32 | Quegan *IEEE TGRS* 1994; Ainsworth et al. *IEEE TGRS* 2006 |
| 35 | Kumar et al., *Remote Sens.* 2019 (EWCM) |
