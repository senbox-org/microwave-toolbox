# RSTB Operator Porting Recommendation Report

**Date:** 2026-05-14
**Scope:** the 63 operators present in `E:\build-old\RSTB\rstb` and absent from `E:\ESA\microwave-toolbox`.

## Executive Summary

### Verdict Breakdown

| Verdict | Count |
|---|---:|
| PORT | 10 |
| PORT-with-care | 3 |
| DEFER | 14 |
| SKIP | 36 |
| **Total** | **63** |

### Top 5 to Port First

1. **BIOMASAR** — multi-temporal GSV retrieval (Santoro et al., peer-reviewed). Self-contained. Cost: M.
2. **DualPolForestHeightEstimation** — TanDEM-X tree height (Chen et al., peer-reviewed). Chains cleanly with the two `PORT-with-care` dual-pol helpers. Cost: M.
3. **CP-Parameters** + **CP-Classification** + **CP-Supervised-Classification** — full compact-pol classification suite (unsupervised H/α-Wishart + supervised); SkyWatch 2018 code, production-quality, fills a real gap in the new toolbox. Cost: M each.
4. **CoherenceOptimization** — multi-temporal coherence stacking; Jama-only dependency. Cost: M.
5. **PartialTargetDetection** — fire / burn-scar detection (Marino et al. 2012); thorough but needs unit-test coverage. Cost: M.

---

## Detailed Verdicts by Domain

### PORT (10 operators)

| Operator | Source file | LOC | Cost | Notes |
|---|---|---:|:--:|---|
| BIOMASAR | `rstb-biomass/.../BIOMASAROp.java` | ~2000 | M | GSV retrieval; Santoro et al. peer-reviewed |
| DualPolForestHeightEstimation | `rstb-biomass/.../treeheight/DualPolForestHeightEstimationOp.java` | ~1000 | M | TanDEM-X height; Chen et al. peer-reviewed |
| Multi-Input-Stack-Averaging | `rstb-insar/.../MultiInputStackAveragingOp.java` | ~400 | S | InSAR stack aggregation; clean code |
| Rasterize | `rstb-land-cover/.../RasterizeOp.java` | ~600 | S | Vector → raster; clean vector logic |
| VectorAveraging | `rstb-land-cover/.../VectorAveragingOp.java` | ~600 | S | Polygon-based spatial averaging |
| CP-Parameters | `rstb-compact-polarimetry/.../CompactPolParametersOp.java` | ~600 | M | Compact-pol parameter suite; SkyWatch 2018 |
| CP-Classification | `rstb-compact-polarimetry/.../CompactPolClassificationOp.java` | ~300 | M | Compact-pol H/α-Wishart unsupervised; fills toolbox gap |
| CP-Supervised-Classification | `rstb-compact-polarimetry/.../CompactPolSupervisedClassificationOp.java` | ~500 | M | Compact-pol supervised classifier; SkyWatch 2018; pairs with CP-Classification |
| CoherenceOptimization | `rstb-insar/.../CoherenceOptimizationOp.java` | ~500 | M | Multi-temporal coherence stacking; Jama dep only |
| PartialTargetDetection | `rstb-sarfire/.../PartialTargetDetectionOp.java` | ~1000 | M | Fire / burn-scar detection; Marino et al. 2012 |

### PORT-with-care (3 operators)

| Operator | Source file | LOC | Cost | Issues |
|---|---|---:|:--:|---|
| DualPolFlatEarthTopoPhaseRemoval | `rstb-biomass/.../treeheight/DualPolFlatEarthTopoPhaseRemovalOp.java` | ~800 | M | jlinda dependency; s1tbx integration looks OK; companion to forest-height chain |
| DualPolPolarimetricCoherence | `rstb-biomass/.../treeheight/DualPolPolarimetricCoherenceOp.java` | ~600 | S | Jama only; companion to forest-height chain |
| SinglePolCoherenceCompensation | `rstb-biomass/.../treeheight/SinglePolCoherenceCompensationOp.java` | ~600 | S | Jama only; single-pol variant |

### DEFER (14 operators)

| Operator | Source file | Cost | Reason |
|---|---|:--:|---|
| SinglePolForestHeightEstimation | `rstb-biomass/.../treeheight/SinglePolForestHeightEstimationOp.java` | M | Single-pol variant; lower priority than dual-pol |
| Extract-Regression-Data | `rstb-biomass/.../ExtractRegressionDataOp.java` | M | Calibration helper; complex polygon logic; not core algorithm |
| MAI | `rstb-insar/.../experimental/MAIOp.java` | L | Experimental; heavy jlinda; needs file-I/O refactor |
| PhaseStitch | `rstb-insar/.../StitchOp.java` | M | Niche; defer until unwrapping mosaicking becomes critical |
| CP-T3-Reconstruction | `rstb-compact-polarimetry/.../CompactPolT3ReconstructionOp.java` | M | T3 coherency; lower user demand than CP-C2 |
| FuzzyKMeansClusterAnalysis | `rstb-machine-learning/.../fuzzykmeans/FuzzyKMeansOp.java` | M | Apache Mahout; useful but old-fashioned; defer pending ML roadmap |
| DirichletClusterAnalysis | `rstb-machine-learning/.../dirichlet/DirichletOp.java` | M | Research-origin; low demand |
| MeanShiftClusterAnalysis | `rstb-machine-learning/.../meanshift/MeanShiftCanopyOp.java` | M | Mahout; lower priority than K-Means |
| Classification-Fusion | `rstb-classification/.../postclassification/ClassificationFusionOp.java` | M | Post-classification utility; defer to v1.1 |
| Validate-Classification | `rstb-classification/.../postclassification/ValidateClassificationOp.java` | S | Metrics-only (confusion matrix, kappa); research tool |
| Abs-Timing-Err-Correction | `rstb-insar/.../experimental/AbsTimingErrCorrectionOp.java` | L | Experimental path; rare scenario |
| DEM-Based-Coregistration | `rstb-insar/.../experimental/DEMBasedCoregistrationOp.java` | L | Experimental; SNAP-Core coregistration already sufficient |
| FeatureWriter | `rstb-ocean/.../FeatureWriter.java` | S | Utility writer, not really an operator |
| VectorNodePatchWriter | `rstb-ocean/.../VectorNodePatchWriter.java` | S | Utility writer, not really an operator |

### SKIP (36 operators)

**Image-processing module — entire module skipped (25 ops).** All depend on stagnant or dead libraries (JAI last ~2010, OpenImaj ~2016, ImageJ ties operators to UI). SNAP Filters / GDAL / modern libs are the replacement.

- JAI wrappers: `Contour`, `Convolve`, `Dilate`, `Erode`, `Segment`, `Vectorize`
- Segmentation: `ActiveContour`, `Morphology`, `RegionGrowing`, `RegionGrowing2`, `Watershed`, `BasicThresholding`, `HysteresisThresholding`, `OtsuThresholding`, `MaximumEntropyThresholding`, `MixtureModelingThresholding`
- Features: `SIFTKeypoint`, `ASIFTKeypoint`, `MSER`, `Feature`, `SIFT-Based-Coregistration`, `SAR-Optical-Coregistration`
- Misc: `HaarWavelet`, `ModeFilter`, `OpenImaj`

**Per-user-direction skips (5):**

| Operator | Reason |
|---|---|
| Slope | Per direction. |
| VegetationIndices | Per direction. |
| RadiometricCorrection | Per direction. |
| CP-C3-Reconstruction for Ocean | Per direction. |
| Ship-Detection | Per direction. Compare with `ObjectDiscriminationOp` for any remaining gap. |

**Other skips (6):**

| Operator | Reason |
|---|---|
| GoldsteinPhaseUnwrap | Inferior to snaphu; toolbox already has `SnaphuExport` / `SnaphuImport`. |
| WaterCloudModel | Experimental; JavaDoc says "will be removed later". |
| SVMClassificationAnalysis | **Broken** — verbatim copy-paste of `FuzzyKMeansOp` with the class renamed. Not an SVM. |
| BinarySVM-Classifier | Old libsvm; replace with a modern ML pipeline. |
| CreateEmptyProductOp | Test utility from `geoint-incubation`; not a real operator. |
| BandImageWriter | I/O utility; SNAP `ProductIO` supersedes. |

---

## Critical Findings

### Bug: `SVMClassificationAnalysis` is a copy-paste of `FuzzyKMeansOp`

The `SVMClassificationAnalysis` class is **not an SVM implementation**. The class structure, parameters (`clusterCount`, `maxIterations`, `randomSeed`, `t1`, `t2`, `m`, `fuzziness`, `convergenceDelta`), and per-pixel logic are byte-for-byte the FuzzyKMeans implementation with the class name swapped. Do not port. Do not use.

### Image-processing module: skip wholesale

23 of the 25 operators are thin wrappers over JAI / OpenImaj / ImageJ. JAI hasn't seen a release since ~2010; OpenImaj's last release was ~2016 and no longer builds against Java 11+. The remaining two (`SAR-Optical-Coregistration`, `SIFT-Based-Coregistration`) carry an OpenImaj dependency that would need full replacement to survive — better written from scratch with modern feature libraries if and when needed.

### Biomass / forest: high-value, mature, low risk

The `rstb-biomass` family is the cleanest portable surface area in the old tree. BIOMASAR is peer-reviewed (Santoro et al., 2011–2013) and self-contained. The dual-pol forest-height chain (`DualPolForestHeightEstimation` → `DualPolPolarimetricCoherence` → `DualPolFlatEarthTopoPhaseRemoval`) is well-structured and aligns with the existing InSAR helper set. Recommend prioritising in v1.0.

### Compact-pol: closes a real gap

The new toolbox has quad-pol classifiers but **no compact-pol classification at all**. The full compact-pol suite — `CP-Parameters` + `CP-Classification` (unsupervised H/α-Wishart) + `CP-Supervised-Classification` (SkyWatch 2018) — is production-quality and fills that gap directly. `CP-T3-Reconstruction` is deferred (lower demand); `CP-C3-Reconstruction for Ocean` was explicitly de-prioritised.

### Machine learning: dependency dead-end

All four ML clustering operators depend on Apache Mahout, which has pivoted to Spark-based distributed compute. Single-node Java APIs are stale. Defer all ML porting until a project-wide ML strategy exists. Critically, do **not** port `SVMClassificationAnalysis` (see bug above).

---

## v1.0 Porting Roadmap

### Immediate (next sprint) — 5 operators

1. BIOMASAR (M)
2. DualPolForestHeightEstimation (M)
3. Multi-Input-Stack-Averaging (S)
4. Rasterize (S)
5. VectorAveraging (S)

### Secondary (if time permits) — 5 operators

1. CP-Parameters (M)
2. CP-Classification (M)
3. CP-Supervised-Classification (M)
4. CoherenceOptimization (M)
5. PartialTargetDetection (M)

### Companion ports for the forest-height chain — 3 operators

Port together with `DualPolForestHeightEstimation`:

1. DualPolFlatEarthTopoPhaseRemoval (M)
2. DualPolPolarimetricCoherence (S)
3. SinglePolCoherenceCompensation (S)

### v1.1+ (deferred, reassess post-release)

- Forest-height single-pol variants
- Extract-Regression-Data, Classification-Fusion, Validate-Classification
- Clustering operators (pending ML roadmap)
- Advanced compact-pol (CP-T3)
- InSAR niche (MAI, PhaseStitch, Abs-Timing-Err-Correction, DEM-Based-Coregistration)

### Do NOT port

- All 25 image-processing operators (JAI / OpenImaj / ImageJ dead-ends)
- GoldsteinPhaseUnwrap (snaphu is the better path)
- SVMClassificationAnalysis (broken copy-paste)
- BinarySVM-Classifier (old libsvm)
- WaterCloudModel (experimental)
- CreateEmptyProductOp, BandImageWriter, FeatureWriter, VectorNodePatchWriter (utilities, not operators)
- Slope, VegetationIndices, RadiometricCorrection, CP-C3-Reconstruction for Ocean, Ship-Detection (per user direction)

---

**End of report.**
