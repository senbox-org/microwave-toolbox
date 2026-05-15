# rstb-op-biomass — Future Operator Roadmap

## Context

The \stb-op-biomass\ module has been recently ported with five core operators: **BIOMASAR** (C-band multi-temporal GSV), **DualPolForestHeightEstimation** (TanDEM-X), **DualPolPolarimetricCoherence**, **DualPolFlatEarthTopoPhaseRemoval**, and **SinglePolCoherenceCompensation**. Related capabilities exist in \stb-op-polarimetric-tools\ (Freeman-Durden, Cloude-Pottier, CoherenceOptimization, PartialTargetDetection) and \sar-op-insar\ (InSAR / SBAS). However, the toolbox lacks: (1) L-band PALSAR empirical models, (2) fully polarimetric PolInSAR height inversion (RVoG, 3SI), (3) operationally validated ensemble methods (CCI Biomass, BIOMASS mission L2), (4) disturbance and change detection, (5) forest/non-forest masking, (6) allometric bridges from height to AGB, and (7) uncertainty quantification for IPCC reporting. The ESA BIOMASS mission (launched April 2025) and CCI Biomass v5 have created urgent demand for P-band and multi-sensor fusion operators. This roadmap prioritizes operators with proven peer-reviewed algorithms, immediate user value, and reference implementations in open-source tools or operational pipelines.

---

## Tier 1 — Recommended next sprint

### BIOMASAR-L — L-band PALSAR multi-temporal AGB retrieval

- **Paper:** Santoro et al. 2013. "Retrieval of growing stock volume in boreal forest using hyper-temporal series of Envisat ASAR ScanSAR backscatter measurements." *Remote Sensing of Environment*, 130, 39–49. https://doi.org/10.1016/j.rse.2012.11.001
- **Gap filled:** Currently BIOMASAR only supports C-band ASAR (Envisat) backscatter. L-band (ALOS PALSAR, PALSAR-2, upcoming ALOS-4) extends saturation point and is preferred for AGB retrieval in dense tropical/boreal forests. CCI Biomass v5 operationally uses BIOMASAR-L; JAXA K&C Initiative provides multi-year validation data.
- **Inputs / outputs:** HV-pol backscatter time series (L-band); GSV and AGB with height-wood-density regression (via Chave or local allometric).
- **Cost:** M — mirrors BIOMASAR architecture but requires L-band-specific parameterization (attenuation, coherence thresholds); JAXA K&C papers provide lookup tables; CCI Biomass ATBD v4+ codifies the exact chain.
- **Chains with:** existing SimpleLinearRegression / ExponentialRegression; feeds into allometric AGB conversion (see below).
- **Implementation reference:** CCI Biomass ATBD v4.0 (ESA Climate Change Initiative), JAXA K&C Phase 4 final report.

### HV-Intensity Exponential AGB Fit — empirical tropical shortcut

- **Paper:** Mitchard et al. 2009. "Measuring biomass changes due to woody encroachment and deforestation/degradation in a forest–savanna boundary region of central Africa using multi-temporal L-band radar backscatter." *Remote Sensing of Environment*, 113, 1453–1461. https://doi.org/10.1016/j.rse.2009.03.001
- **Gap filled:** Physics-based inversion (BIOMASAR, RVoG) requires coherence or dual-pol; many operational monitoring pipelines use simple log(HV) → AGB fit due to computational speed and lower data requirements. Suitable for L-band and first-pass C-band screening.
- **Inputs / outputs:** HV backscatter (linear intensity); AGB [Mg/ha] via pre-calibrated exponential function (training data regional or global).
- **Cost:** S — ~200 lines of Java; wraps existing ExponentialRegression with regional coefficient lookup tables (e.g., CCI Biomass provides open LUT for pantropical regions).
- **Chains with:** ExponentialRegression operator; optional Forest/NonForest mask downstream.
- **Implementation reference:** Mitchard et al. (2009) Fig 2; Saatchi et al. (2011) Supplement; CCI Biomass v4 Supplement A.

### RVoG PolInSAR Forest Height (quad-pol variant)

- **Paper:** Cloude & Papathanassiou 2003. "Three-stage inversion process for deriving forest structure from polarimetric SAR interferometry." *IEE Proceedings-Radar, Sonar and Navigation*, 150(3), 125–134. https://doi.org/10.1049/ip-rsn:20030449
- **Gap filled:** Current dual-pol height operator uses TanDEM-X (X-band). RVoG is the mature foundation for quad-pol PolInSAR height on ALOS-2 and future L-/P-band; enables temporal coherence compensation and volume extinction modeling. Essential for multi-baseline BIOMASS tomography bridging.
- **Inputs / outputs:** Multi-baseline fully polarimetric interferogram (L- or P-band) + DEM for flat-earth/topo phase removal; forest height [m] + ground topography.
- **Cost:** M — requires full coherency matrix inversion, complex optimization (ground-volume coherence extraction); jlinda integration exists in s1tbx already; ~1500 lines of Java.
- **Chains with:** DualPolFlatEarthTopoPhaseRemoval (existing), CoherenceOptimization (existing), downstream allometric AGB conversion.
- **Implementation reference:** Cloude & Papathanassiou 2003 (canonical), Papathanassiou & Cloude 2003 IEEE IGARSS temporal decorrelation treatment, TanDEM-X forest height papers (Chen et al. 2016 reference in current codebase).

### ESA BIOMASS L2 AGB Retrieval — P-band tomography-based inversion

- **Paper:** Tebaldini et al. 2024. "The BIOMASS Level 2 Prototype Processor: Design and Experimental Results of Above-Ground Biomass Estimation." *Remote Sensing*, 12(6), 985. https://doi.org/10.3390/rs12060985
- **Gap filled:** BIOMASS mission (launched April 2025) is the first P-band SAR in orbit; L2a products include AGB with tomographic vertical structure extraction. This is not just a regression but a semi-empirical physically-based model combining ground-canceled interferometry with environmental backscatter parameterization. Essential for validation against satellite AGB time series.
- **Inputs / outputs:** P-band fully polarimetric interferometric + tomographic level-1 data (SAR images + baseline & orbit parameters); AGB [Mg/ha] + uncertainty maps + canopy height structure.
- **Cost:** L — requires tomographic spectral estimation (multi-baseline SAR stack processing), semi-empirical model (Tebaldini & Rocca 2012 framework), integration with orbit/baseline database, DEM fusion. ~3000–4000 lines of Java; dependencies: jlinda (SAR processing) + numerical libraries (eigenvalue/SVD). Port effort: 6–8 weeks with BIOMASS ATBD + algorithm papers as specification.
- **Chains with:** existing jlinda operators (InSARPhaseToHeight, PhaseLinking, SBAS), PartialTargetDetection (for disturbance masking), potential GRD-to-SLC bridging.
- **Implementation reference:** BioPAL open-source project (GitHub ESA, MIT license), BIOMASS mission ground processor, ATBD v1.0+.

### GEDI-SAR Fusion Calibration Operator — machine-learning height-to-biomass cross-calibration

- **Paper:** Qi et al. 2023 / Xu et al. 2024 (search results from 2023–2024 literature on GEDI SAR fusion). Representative: "Predicting forest above-ground biomass using SAR imagery and GEDI data through machine learning in GEE cloud." *International Journal of Applied Earth Observation and Geoinformation*, (2025).
- **Gap filled:** GEDI lidar footprints provide wall-to-wall height and structure; fusing with SAR backscatter time series (Sentinel-1, PALSAR-2) via Random Forest / gradient boosting improves AGB accuracy globally. Operators already exist for feature extraction; a dedicated fusion operator codifies the GEDI co-location, model fitting, and uncertainty propagation workflow.
- **Inputs / outputs:** GEDI L2B footprints (AGB + height structure), SAR backscatter stack (HH, HV, VV, coherence), optical indices (optional); trained ML model (Random Forest pickle) + uncertainty bands [Mg/ha].
- **Cost:** M — light lifting for Java operator (mostly I/O + feature engineering; ML logic delegated to pre-trained models or called via Python subprocess if needed); 800–1200 lines.
- **Chains with:** HV-Intensity Exponential Fit (as baseline comparison), Chave Allometric (for sanity checks), AGB Uncertainty Quantification operator (below).
- **Implementation reference:** Google Earth Engine tutorials (2024), ScienceDirect 2025 papers on Sentinel-1 + GEDI fusion.

---

## Tier 2 — v1.1+

### Chave Pantropical Allometric Calculator — DBH/height/wood-density to AGB

- **Paper:** Chave et al. 2014. "Improved allometric models to estimate the aboveground biomass of tropical trees." *Journal of Ecology*, 102(2), 242–254. https://doi.org/10.1111/1365-2745.12266
- **Gap filled:** All SAR operators yield forest structure (height, volume) or empirical backscatter-to-AGB fits. A standalone allometric calculator enables: (1) benchmarking against field surveys, (2) bridging SAR heights (from RVoG, DualPol, TanDEM-X, BIOMASS) to AGB, (3) local parameterization via user-supplied DBH-height relationships and wood density maps.
- **Inputs / outputs:** DBH [cm], tree height [m], wood density [g/cm³] (optionally fetched from global maps), environmental stress factor E (optional); individual AGB [kg] and stand-level AGB [Mg/ha].
- **Cost:** S — pure utility function (~300 lines); reference implementation at pantropical-allometry.org.
- **Chains with:** RVoG PolInSAR Forest Height, DualPolForestHeightEstimation, GEDI-SAR Fusion (as post-processor).
- **Implementation reference:** Chave et al. 2014 paper + appendix; official pantropical allometry website.

### Three-Stage Inversion (3SI) PolInSAR Forest Height — quad-pol optimization variant

- **Paper:** Cloude 2010. "Polarimetric SAR interferometry." *IEEE Transactions on Geoscience and Remote Sensing*, 48(8), 2957–2972. https://doi.org/10.1109/TGRS.2010.2043442
- **Gap filled:** RVoG assumes uniform volume scattering; 3SI optimizes ground and volume coherence estimates by fitting multiple coherence points (different polarization channels) to the RVoG straight-line model, reducing temporal decorrelation bias. Recommended over RVoG when multi-baseline quad-pol data is available.
- **Inputs / outputs:** Multi-baseline quad-pol interferometric SAR stack; forest height [m] + volume extinction coefficient; ground topography.
- **Cost:** M — augments RVoG with coherence optimization loop; ~600–800 additional lines.
- **Chains with:** RVoG PolInSAR Forest Height (Tier 1), CoherenceOptimization (existing).
- **Implementation reference:** Cloude 2010, Rocca & Tebaldini extensions, recent IGARSS papers (2018+).

### SAR Forest Disturbance / Change Detection Operator — time-series breakpoint detection

- **Paper:** "Mapping small-sized logging disturbances in tropical forests using Sentinel-1 time series." *Frontiers in Remote Sensing*, 7, 1659305 (2026).
- **Gap filled:** Operators detect AGB change but not the binary disturbance event (logging, fire, deforestation) in multi-year SAR time series. CCDC / BFAST / fused-lasso approaches on Sentinel-1 VV+VH backscatter enable near-real-time (<0.1 ha) event detection, complementary to optical for tropical monitoring.
- **Inputs / outputs:** VV/VH multi-temporal backscatter stack (Sentinel-1 IW or other C-band), 1–6 year span; disturbance mask [binary raster] + confidence + estimated change date + AGB loss [Mg/ha].
- **Cost:** M–L — statistical algorithm (BFAST breakpoint detection library in R exists; Java port ~1500 lines); requires temporal grid/stack management.
- **Chains with:** HV-Intensity Exponential Fit or BIOMASAR-L (to quantify AGB loss), Forest/NonForest mask (to filter non-forest false positives).
- **Implementation reference:** Frontiers 2026 paper on Sentinel-1 time series; RADD (Radar for Detecting Deforestation) algorithm; Google Earth Engine breakpoint detection tutorials.

### Forest / Non-Forest Classification Operator — SAR-only binary mask

- **Paper:** Cartus et al. 2020. "Assessing Forest/Non-Forest Separability Using Sentinel-1 C-Band Synthetic Aperture Radar." *Remote Sensing*, 12(11), 1899. https://doi.org/10.3390/rs12111899
- **Gap filled:** Many AGB algorithms require forest/non-forest masking; no operator currently exists. C-band (Sentinel-1) can achieve ~85% accuracy with annual time series; L-band (PALSAR-2) is better but sparse. Provides preprocessing for downstream AGB operators and enables forest extent monitoring.
- **Inputs / outputs:** Sentinel-1 annual backscatter composite (VV, VH, coherence; multitemporal mean/std) or PALSAR-2 single-season; forest/non-forest [binary or probability raster].
- **Cost:** M — random forest / SVM classifier; ~800 lines; training data from open sources (e.g., ESA WorldCover, FAO FRA maps, Hansen forest cover).
- **Chains with:** Upstream (standalone or preprocessing), feeds AGB operators (BIOMASAR-L, RVoG, Disturbance Detection).
- **Implementation reference:** Cartus et al. papers on C-band forest separability; ESA WorldCover (2021) forest class validation data.

### AGB Uncertainty Quantification & Error Propagation Operator — IPCC Tier 1 reporting

- **Paper:** "Intergovernmental Panel on Climate Change (IPCC) Tier 1 forest biomass estimates from Earth Observation." *Scientific Data*, 13, 107 (2024). https://doi.org/10.1038/s41597-024-03930-9
- **Gap filled:** AGB operators produce point estimates only; IPCC MRV (measurement, reporting, verification) for carbon credits requires uncertainty bands (confidence intervals / standard error). Error propagates from: (1) SAR backscatter radiometric accuracy, (2) regression model residual, (3) allometric equation coefficients, (4) spatial variance / covariance.
- **Inputs / outputs:** AGB map + input uncertainty layers (backscatter std, model RMSE, allometric coef. uncertainty); propagates via error budget or Monte Carlo; outputs: AGB [Mg/ha], SE [Mg/ha], 90% CI bounds per pixel/polygon.
- **Cost:** M — error propagation algebra + optional MC resampling; ~1000 lines; integrate with all AGB operators.
- **Chains with:** All tier 1 & 2 AGB operators (BIOMASAR-L, RVoG, HV-Intensity Fit, GEDI-SAR Fusion, Chave Allometric, BIOMASS L2).
- **Implementation reference:** IPCC 2024 Scientific Data paper; CCI Biomass v5 uncertainty layers; UNFCCC MRV guidelines.

---

## Tier 3 — research / deferred

### SAR Tomographic Vertical Reflectivity Profile — multi-baseline 3D structure extraction

- **Paper:** Tebaldini & Rocca 2012. "Multibaseline Polarimetric SAR Tomography of a Boreal Forest at P- and L-Bands." *IEEE Transactions on Geoscience and Remote Sensing*, 50(1), 232–246. https://doi.org/10.1109/TGRS.2011.2160644
- **Gap filled:** RVoG assumes 1D scattering profile (ground + uniform volume). Tomography reconstructs full 3D vertical reflectivity, enabling: (1) forest structure (DBH distribution approximation), (2) ground topography under dense canopy, (3) multi-layer forest separation. Frontier method; BIOMASS mission includes tomographic baselines.
- **Inputs / outputs:** Multi-baseline (10+) fully polarimetric SAR images (P-band or L-band) with precise baseline & orbit metadata; 1D vertical reflectivity profile (height-dependent backscatter) + 3D structure maps.
- **Cost:** L — requires spectral estimation (FFT + apodization), full coherency matrix inversion per cell, 3D forward modeling. ~2500–3500 lines; jlinda + Jama + numerical integration libraries.
- **Chains with:** Tomography not yet in toolbox; would be standalone research operator or research branch.
- **Implementation reference:** Tebaldini & Rocca 2012; UAVSAR tomography campaigns (NASA AfriSAR, 2016); recent reviews *Remote Sensing* 15(15), 3781 (2023).

### Soil-Vegetation Moisture Decoupling Operator — WCM backscatter bias correction

- **Paper:** Attema & Ulaby 1978. "Vegetation Modeled as a Water Cloud." *Radio Science*, 13(2), 357–364. https://doi.org/10.1029/RS013i002p00357
- **Gap filled:** WCM separates vegetation and soil moisture contributions to SAR backscatter, enabling AGB retrieval under wet ground conditions. Soils in tropics are often wet; ignoring this introduces systematic bias. Experimental operator in old RSTB (deferred) should be replaced with modern WCM + allometric integration.
- **Inputs / outputs:** SAR backscatter (VV, VH, HH), optional soil moisture map or precipitation history; vegetation water content [kg/m²] + ground reflectivity + AGB bias correction [Mg/ha].
- **Cost:** M–L — WCM model parameterization + vegetation-water-content inversion + bias field computation; ~1200 lines; climate/weather data integration optional.
- **Chains with:** BIOMASAR-L or HV-Intensity Fit (as correction filter), soil moisture operator (if available in soil-moisture module).
- **Implementation reference:** Attema & Ulaby 1978; recent Scandinavian forest case studies (2020); FAO FRA integration guidelines.

### CCI Biomass Ensemble Operator — multi-sensor fusion (S-1 C-band + PALSAR-2 L-band + optical)

- **Paper:** ESA CCI Biomass ATBD v4.0 & v5.0 (2023–2024). "Algorithm Theoretical Basis Document." https://climate.esa.int/
- **Gap filled:** CCI Biomass v5 is the operational global benchmark; it combines Sentinel-1 C-band, ALOS-2 PALSAR-2 L-band, and Sentinel-2 optical via ensemble weighting. No single-sensor operator matches this accuracy. Porting the full chain would establish SNAP as CCI-compatible for users needing production-grade AGB.
- **Inputs / outputs:** Sentinel-1 time series (IW mode, annual composite: VV, VH, coherence), ALOS-2 PALSAR-2 (annual HV, HH), Sentinel-2 (annual NDVI/NDMI), DEM; global AGB [Mg/ha] at ~100 m resolution.
- **Cost:** L — multi-stage pipeline: forest mask → per-sensor AGB regression → ensemble fusion (weighted by confidence/uncertainty) → final AGB + uncertainty. ~4000 lines; requires all tier-1 operators as dependencies.
- **Chains with:** Entire tier-1 & tier-2 operator suite; outputs production-grade AGB for validation / intercomparison studies.
- **Implementation reference:** CCI Biomass v5 code repos (CEDA, ESA Climate Change Initiative); GlobBiomass ATBD v3 (predecessor); academic papers (Santoro et al., ESA CCI consortia).

---

## Notes on open-source SAR biomass tools

### PyroSAR
[PyroSAR](https://github.com/johntruckenbrodt/pyroSAR) is a Python framework for large-scale SAR satellite data processing. It provides scalable ingestion of Sentinel-1, ALOS-2, and other missions; homogenized I/O across processors (SNAP, GAMMA); and integrates with data cubes for time-series analysis. Cross-reference for data I/O and time-series stack management.

### OpenSARLab (NASA ASF)
[OpenSARLab](https://asf.alaska.edu/asf-data-tools/opensarlab/) is a cloud-hosted Jupyter environment providing free access to Sentinel-1, ALOS-1, ALOS-2 in AWS, alongside processing tutorials. Excellent for algorithm prototyping and educational use.

### BioPAL (ESA BIOMASS Mission Ground Processor)
[BioPAL](https://github.com/BioPAL) is the open-source (MIT license) Level-2 algorithm suite for ESA BIOMASS mission. Implements P-band inversion chain in Python with simulated data, unit tests, and API documentation. Direct reference for Tier-1 **ESA BIOMASS L2 AGB** operator.

### CCI Biomass Data Portal & ATBD
[ESA CCI Biomass](https://climate.esa.int/en/projects/biomass/) provides global benchmark AGB maps (v4, v5) and formal ATBDs. ATBD v5.0 codifies ensemble fusion methodology combining Sentinel-1, PALSAR-2, and Sentinel-2. Data are free and open.

### GlobBiomass
[GlobBiomass](https://globbiomass.org/) is an ESA/FAO initiative producing global AGB maps by combining satellite SAR, lidar, and field plots. ATBD v3 publicly available; project archive includes JAXA K&C Initiative PALSAR-based products.

### JAXA Kyoto & Carbon Initiative
[K&C Initiative](https://www.eorc.jaxa.jp/ALOS/) provides multi-year ALOS/ALOS-2 PALSAR time series and AGB products over tropics and boreal regions. Operational L-band biomass pipeline; validation data for BIOMASAR-L.

### Google Earth Engine
GEE hosts Sentinel-1 ARD, GEDI, and optical stacks; tutorials on forest biomass estimation via machine learning (2024+) are publicly available. Useful for prototyping Tier-2 **GEDI-SAR Fusion** and **Forest/NonForest Classification** before Java porting.

### RADD & Related NRT Systems
Operational Sentinel-1 change-detection systems (RADD, GLAD, DETER, JJ-FAST) provide reference algorithms for Tier-2 **SAR Forest Disturbance / Change Detection** operator.

---

## References

1. Attema, E. P. W., & Ulaby, F. T. (1978). Vegetation modeled as a water cloud. *Radio Science*, 13(2), 357–364. https://doi.org/10.1029/RS013i002p00357

2. Cartus, O., Santoro, M., & Kellndorfer, J. (2012). Mapping forest aboveground biomass in the northeastern United States with ALOS PALSAR dual-polarization L-band SAR. *Remote Sensing of Environment*, 124, 466–478.

3. Cartus, O., & Santoro, M. (2020). Assessing forest/non-forest separability using Sentinel-1 C-band synthetic aperture radar. *Remote Sensing*, 12(11), 1899. https://doi.org/10.3390/rs12111899

4. Chave, J., Réjou-Méchain, M., Búrquez, A., et al. (2014). Improved allometric models to estimate the aboveground biomass of tropical trees. *Journal of Ecology*, 102(2), 242–254. https://doi.org/10.1111/1365-2745.12266

5. Cloude, S. R. (2010). Polarimetric SAR interferometry. *IEEE Transactions on Geoscience and Remote Sensing*, 48(8), 2957–2972. https://doi.org/10.1109/TGRS.2010.2043442

6. Cloude, S. R., & Papathanassiou, K. P. (2003). Three-stage inversion process for deriving forest structure from polarimetric SAR interferometry. *IEE Proceedings - Radar, Sonar and Navigation*, 150(3), 125–134. https://doi.org/10.1049/ip-rsn:20030449

7. ESA Climate Change Initiative (2024). CCI Biomass Product User Guide v5.0. https://climate.esa.int/

8. ESA BIOMASS Mission (2025). Biomass Level-2 Algorithm Theoretical Basis Documents (ATBD) — Phase E2. https://earth.esa.int/eogateway/missions/biomass/

9. Mitchard, E. T. A., Saatchi, S. S., Woodhouse, I. H., et al. (2009). Measuring biomass changes due to woody encroachment and deforestation/degradation in a forest–savanna boundary region of central Africa using multi-temporal L-band radar backscatter. *Remote Sensing of Environment*, 113(7), 1453–1461. https://doi.org/10.1016/j.rse.2009.03.001

10. Saatchi, S., Harris, N. L., Brown, S., et al. (2011). Benchmark map of forest carbon stocks in tropical regions across three continents. *Proceedings of the National Academy of Sciences*, 108(24), 9899–9904. https://doi.org/10.1073/pnas.1019576108

11. Santoro, M., Cartus, O., Mermoz, S., et al. (2013). Retrieval of growing stock volume in boreal forest using hyper-temporal series of Envisat ASAR ScanSAR backscatter measurements. *Remote Sensing of Environment*, 130, 39–49. https://doi.org/10.1016/j.rse.2012.11.001

12. Tebaldini, S., & Rocca, F. (2012). Multibaseline polarimetric SAR tomography of a boreal forest at P- and L-bands. *IEEE Transactions on Geoscience and Remote Sensing*, 50(1), 232–246. https://doi.org/10.1109/TGRS.2011.2160644

13. Tebaldini, S., Mariotti d'Alessandro, M., Ferro-Famil, L., et al. (2024). The BIOMASS Level 2 Prototype Processor: Design and experimental results of above-ground biomass estimation. *Remote Sensing*, 12(6), 985. https://doi.org/10.3390/rs12060985

---

**End of Roadmap. Last updated: 2026-05-14**
