# Microwave Toolbox JavaHelp review (2026-11-12)

Scope: all 16 helpsets, 178 HTML pages (~90k words), plus every operator `@Parameter` list and every reader plug-in. Each finding marked ✔ below was spot-checked against the code or page by hand; the rest come from four domain reviews:

| Report | Scope |
|---|---|
| [review_insar.md](review_insar.md) | sar-op-insar-ui + jlinda (37 pages) |
| [review_sarproc.md](review_sarproc.md) | SAR processing, calibration, feature extraction, analysis/ocean, utilities, rcp general/tutorials (54 pages) |
| [review_pol_s1.md](review_pol_s1.md) | Polarimetry, classification, soil moisture, Sentinel-1 TOPS, orbits (50 pages) |
| [review_readers.md](review_readers.md) | 37 reader pages, general pages, help-system structure (TOC/map/registration) |

Each report has a per-page table (concept score 0–3, parameter gaps, staleness, references present/missing), proposed concept pages, and a bibliography. Citations marked "(verify)" there need checking before use.

## 1. Verdict

The help works as a **parameter reference**, and an often stale one. It does **not educate**:

- **No reachable SAR concept pages.** The only conceptual/tutorial pages (`microwavetbx-rcp`: remote_sensing, terrain_correction, coregistering tutorials, Definitions) are in a helpset that is **never registered** (no `@HelpSetRegistration`) ✔. The four `sar/docs/general` pages (Overview, SupportedProducts, Sentinel1, Sentinel1_Functionality) are in no map or TOC.
- **The only glossary is the wrong one.** `Definitions.html` is a MERIS/MODIS leftover with no SAR terms.
- **Core concepts are explained nowhere:**
  - SAR geometry: slant vs ground range, layover, foreshortening, shadow.
  - Radiometry: β0/σ0/γ0/γ0T, dB vs linear.
  - Speckle and ENL.
  - Geoid vs ellipsoid heights.
  - InSAR basics: phase wrapping, baselines and height of ambiguity, coherence interpretation, LOS sign convention.
  - TOPS: bursts, Doppler steering, why deramping and ESD are needed.
  - Polarimetry: the scattering matrix and C/T matrices.
  - Ocean: Bragg scattering and wind GMFs.
- **Few pages give workflow context.** Few pages state prerequisites or "what next". There is no learning path from concepts to workflows, operators and readers.
- **References are thin.** About half the pages cite nothing, including all 11 soil-moisture pages, 13 of 16 S1/orbit pages, most jLinda pages, and all utilities. Polarimetry lacks Cloude-Pottier, Freeman-Durden, Yamaguchi, van Zyl and the Lee Wishart papers. Where citations exist they are mostly 2004–2008 ESA technical notes.

## 2. Factually wrong — fix first (P0)

These mislead users into wrong results, not just incomplete understanding.

| # | Page | Error |
|---|---|---|
| 1 ✔ | `rstb/.../PolarimetricDecompositionOp.html:46,54` | **Pauli RGB mechanisms swapped.** \|HH−VV\|² is labelled odd-bounce and \|HH+VV\|² double-bounce. It is the reverse, and this is the most-used polarimetric product. |
| 2 ✔ | `sar/.../GSLCGeocodingOp.html:318-321` | Recommends Thermal-Noise-Removal before GSLC. On complex input that operator outputs Intensity/Sigma0 (`Sentinel1RemoveThermalNoiseOp.createTargetBandName`), so the phase is destroyed. |
| 3 ✔ | `sar/.../general/Sentinel1.html:226-228` | States that S-1 GRD products have thermal noise removed. They don't, which contradicts the ThermalNoiseRemoval page. The page also describes an S1A+S1B constellation. |
| 4 ✔ | `jlinda/.../SnaphuExportOp.html:51` | Says `99999` is the "number of lines"; SNAPHU expects the line length (number of columns). |
| 5 | `sar-op-insar-ui/.../MultiReference.html` | Says it writes one product per reference acquisition. It actually writes N−1 consecutive pairs, and Multi-Reference InSAR does not consume them. |
| 6 | `WarpOp.html` | Describes the legacy RMS loop; complex data now uses a w-test at a significance level (`WarpOp.java:198-209`). Says linear warp is the default; the code default is order 2. |
| 7 | `PhaseToElevationOp.html` | Titled "DEM Generation"; says the input comes from a non-existent "Stitch" operator. |
| 8 | `CoherenceOp.html` / `InterferogramOp.html` | CoherenceOp bias-corrects and removes topography by default, Interferogram does not, so the "same" coherence differs between them, and neither page says so. Interferogram also says complex multilook is "coming" (it exists) and blames flat-earth phase on Earth curvature. |
| 9 | `ETADDeburstOp.html` | 4 layer names don't match the code (e.g. `dopplerShiftCorrectionRg` vs `dopplerRangeShiftRg`), so GPT users get errors. |
| 10 | `SpectralDiversityOp.html` | The cloud workflow says `doNotWriteTargetBands`=false where it must be true, and names the wrong shift fields. |
| 11 | `FaradayRotationCorrectionOp.html` | The formula shown is not the Bickel-Bates estimator the code computes; it promises an Ω output band that no parameter enables. |
| 12 | Copernicus DEM pages | Copernicus DEM is described as EGM96-referenced. GLO-30 is EGM2008, but `snap-engine/.../CopernicusElevationTile.java:57` converts with EGM96 ✔. This is **also a code issue** (error is sub-metre to a few metres). |
| 13 | `GeolocationGridGeocodingOp`, `SARSimulationOp` | Time correction is written as `t + 2R/c`; the code uses R/c. |
| 14 | `OilSpillDetectionOp` | Background window is given in pixels; it is in km. The TC tutorial gives σ0 in "dB/m²", but σ0 is dimensionless. |
| 15 | `WindFieldEstimationOp` | Says ERS/ENVISAT only; the code also accepts S-1 and RS-2. |
| 16 | `BandSelectOp` | XML example uses `<polarisations>`/`<subImages>`; the real names are `selectedPolarisations`/`selectedSubImages`, so the example silently does nothing. |
| 17 | `ImportQPSSAR.html` | Describes a non-existent "Quad-Pol SAR stack" format; the reader is for the iQPS X-band constellation. `ImportStriX` covers only one of its two formats. |
| 18 | `PolarimetricChangeDetectionOp` | Advises ENL ≈ 1 for SLC, ignoring window averaging, which understates the test statistic. |
| 19 | Citations | Errors to fix: Small 2011 is vol. 49 (not 48); "Lezek" should be Jezek; "T. L. Toan" should be Le Toan; CMOD5 should be Hersbach et al. 2007 JGR. Y3-DP cites the wrong co-author (Lopez-Sanchez); the RFDI venue is likely *Biogeosciences*; PhaseLinking credits EVD to SqueeSAR (it is Fornaro 2015 / Cao 2015). |

## 3. Broken help plumbing (quick wins)

- **Unregistered helpsets:** `microwavetbx-rcp` ✔ and `sar-op-analysis-ui/.../ocean`. Add `package-info.java` with `@HelpSetRegistration`.
- **Help buttons that fail:**
  - Case-mismatched help IDs: `importRadarsat1/2`, `importRCM` in `rstb-io/layer.xml` ✔ vs `ImportRadarsat1/2`, `ImportRCM` in the map.
  - **Wrong page opens:** ALOS-2/4 → ALOS-1, TanDEM-X → TerraSAR-X, EOS-04 → RISAT.
- **Pages missing from the TOC:**
  - Mapped but not in any TOC: PhaseLinkingOp, SBASInversionOp, PCAOp, jlinda `InSAR.html`, ImportERSCeos.
  - In neither map nor TOC: ImportEnvi, ImportImageIO, Hallikainen, IEM-LUT, and all `sar/docs/general` pages.
- **Dead links and entries:**
  - Dead TOC entry "PyRate Export Workflow" (no map ID).
  - Broken `nbdocs://` links: `CreateStackOp.html:79`, `SnaphuImportOp.html:25`.
  - `../general/Overview.html` broken on 8 polarimetric pages.
  - Three broken links in the TC tutorial; about 7 external 404s.
- **Images missing in the packaged build:** 8 dual-pol formula and flowchart images are referenced as `.PNG` but the files are `.png` ✔. They work on Windows and break in the JAR/Linux, and they *are* the method description.
- **Other help-system defects:**
  - Every helpset's home ID `top` doesn't exist.
  - No keyword index anywhere.
  - `sar-io-gdal` registers the sar-io helpset twice.
  - jlinda `InSAR.html` has `<title>` after `</body>`.
- **Code defects found on the way:**
  - `layer.xml:398` binds the UI to `MultiReferenceStackGenerator`, but the alias is `MultiMasterStackGenerator`, so the custom UI never attaches ✔.
  - RangeFilterOp default mismatch and invalid `"off"` default.
  - `PCAOp` booleans with `defaultValue="1"`.
  - `SnaphuExportOp.statCostMode` tooltip copy-pasted.
  - PolSpeckleFilter `sigmaStr` labelled "Point target window Size".

## 4. Missing pages

**Operators with no help page:**
- AdaptiveThresholding, Object-Discrimination, Oil-Spill-Clustering. These are sub-steps of ship/oil detection; at least describe them on the parent pages.
- SAOCOM-Thermal-Noise-Removal (public in GPT, no menu entry or help).
- GRD-Post (only a truncated section of SLC-to-GRD).
- TileWriter, BandMerge, SubGraph.
- MultiMasterStackGenerator: its page is wrong (see §2).

RCM thermal noise has a help page but no menu entry.

**Readers with no page:** ALOS-4 (CEOS + GeoTIFF), EOS-04 COG, Sentinel-1 ETAD product, CIMR L1B, SNAPHU, Basic CEOS. Sentinel-1 COG is not mentioned on the S-1 page.

**Pages describing unregistered readers:** ImageIO, and the in-house PCIDSK reader (the menu opens GDAL's).

**Reader stubs (≤60 words):** RAT, Gamma, PCIDSK, ENVI, Capella, ERS-CEOS, TanDEM-X, SeaSat, ImageIO. Only NISAR, ICEYE COG and Envisat link a product-format spec, and the Envisat link is dead.

Note: the forest/biomass helpIds (RVoG*, BIOMASAR*, WCM*, …) appear only in stale `rstb/rstb-op-biomass*/target/` output. They have no source on this branch, so they are not a gap here.

## 5. Parameter drift (selection)

| Page | Gap |
|---|---|
| Goldstein | Documents 1 of 9 parameters, and that one (fixed α) is ignored by default because adaptive α (Baran 2003) is on. |
| Coherence | Documents 2 of 16. |
| SNAPHU Export | Documents 0 of 9. |
| Offset Tracking | 7 missing. |
| Cross-Correlation | 6 missing. |
| GSLC | 8 missing. |
| Calibration | 6 output toggles missing. |
| Polarimetric Decomposition | Huynen, Yang, Krogager, Cameron, H-α Dual-Pol and `applyOrientationAngleCorrection` missing. |
| Speckle Filter | Documents a removed "Edge Threshold"; none of the default Lee Sigma parameters, ENL or IDAN. |
| Azimuth Shift, Orientation Angle Correction | Wrongly say "no parameters". |
| Back-Geocoding | DEM list lacks Copernicus 30 m (the default). |
| Dual-pol powers | Default NESZ −16 dB while the code says S1 IW ≈ −22 dB. |

About 25 InSAR pages and most others give **no defaults or units**.

**Terminology and staleness:**
- "master/slave" appears ~130 times, against the Reference/Secondary convention.
- `NEST4A`/`.s1tbx` paths.
- SRTM auto-download from xftp.jrc.it.
- Windows-XP screenshots.
- Sentinel-1B shown as current; S1C/D absent.
- S1TBX branding.

## 6. Recommended concept layer

Add a new top-level TOC branch, **"Microwave → SAR Concepts"**, registered in the rcp helpset. Each page should be 1–2 screens, with SVG diagrams, typical numbers and full references. Every operator page should get a "Background" link to the relevant concept page plus "Prerequisites / Next step" lines.

| Priority | Concept page | Main sources |
|---|---|---|
| 1 | **SAR Fundamentals**: complex reflectivity, resolution, SLC/GRD/GSLC, bands, polarisation | Moreira et al. 2013; Curlander & McDonough 1991 |
| 1 | **Geometry & Distortions**: slant/ground range, incidence angles, layover/foreshortening/shadow, asc/desc, EC vs TC | Small & Schubert 2008; Meier et al. 1993 |
| 1 | **Radiometric Quantities & Calibration**: DN→β0→σ0→γ0→γ0T, NESZ, dB vs linear ("average in linear"), typical values | Small 2011; Freeman 1992; Miranda & Meadows (S1 cal/TNR notes) |
| 1 | **Speckle & Multilooking**: statistics, ENL, filter-selection table | Lee 1980/81; Lopes et al. 1990; Quegan & Yu 2001; Deledalle et al. 2017 |
| 1 | **InSAR Fundamentals**: phase components, wrapping, B⊥/HoA, decorrelation | Bamler & Hartl 1998; Rosen et al. 2000; Hanssen 2001 |
| 1 | **S1 TOPS Primer + TOPS Coregistration/ESD**: bursts, Doppler steering, deramp, 0.001 px, double-difference QC | De Zan & Monti Guarnieri 2006; Prats-Iraola et al. 2012; Yagüe-Martínez et al. 2016 |
| 1 | **Polarimetry Primer**: scattering matrix, C/T, Pauli (correct!), reflection symmetry | Lee & Pottier 2009; Cloude & Pottier 1996 |
| 2 | **DEMs & Height References**: ellipsoid vs geoid, per-DEM datum, Apply-EGM | — |
| 2 | **Coherence** (estimator, bias, interpretation) | Touzi et al. 1999 |
| 2 | **Phase Unwrapping** (SNAPHU) | Chen & Zebker 2001 |
| 2 | **Phase→Displacement**: LOS, sign, reference point | — |
| 2 | **Atmosphere & Ionosphere** | Gomba et al. 2016; Fattahi et al. 2017 |
| 2 | **Decompositions explained** | Cloude & Pottier 1997; Freeman & Durden 1998; Yamaguchi et al. 2005; van Zyl 1989 |
| 2 | **Dual-pol / Compact-pol** | Raney 2007; Mandal et al. 2020 |
| 2 | **Wishart statistics & classification** | Lee et al. 1994, 1999; Conradsen et al. 2003 |
| 3 | **InSAR Time Series: PS/SBAS/DS** | Ferretti et al. 2001; Berardino et al. 2002; Guarnieri & Tebaldini 2008; Ansari et al. 2018 |
| 3 | **Ocean primer**: Bragg, CMOD, oil look-alikes, CFAR | Hersbach et al. 2007; Solberg et al. 1999; Crisp 2004 |
| 3 | **Soil moisture**: dielectric models, IEM | Fung et al. 1992; Hallikainen 1985; Dobson 1985 |
| 3 | **Change detection primer**: ratio, REACTIV, false alarms | Rignot & van Zyl 1993; Koeniguer & Nicolas 2020 |
| 3 | **Orbits, ETAD & EAP** | Gisinger et al. 2022 (verify); Peter et al. 2017 |
| 3 | **Workflow cookbooks**: GRD→γ0T; S1 DInSAR; stripmap DInSAR; GSLC; time series; DEM gen; offset tracking; ocean | — |
| 3 | **Supported Missions** (generated from reader/calibrator registry) + **SAR Glossary** replacing Definitions.html | — |

The full topic-grouped bibliographies (~60 InSAR, ~50 polarimetry/S1, ~45 SAR processing entries) are in the detailed reports.

## 7. Suggested order of work

1. **P0 corrections** from §2. These are small page edits, and items 1–4 are one-liners.
2. **Plumbing** from §3:
   - register the rcp and ocean helpsets;
   - fix help-ID case and wrong targets;
   - add the orphan pages to the TOC;
   - fix `.PNG` and broken links;
   - fix the code defects found.
3. **Concept layer, priority 1 pages.** Add a "Background / Prerequisites / Next / References" footer template to every operator page.
4. **Parameter sync.** Consider generating the parameter tables from `@Parameter` metadata (label, description, default, unit, valueSet) so they can't drift again; a drift check like `guides-docs-tutorials/tools/check_params.py` would work.
5. Global master/slave → Reference/Secondary sweep. Replace the stale general pages with a generated Supported Missions page, then reader-page template and mission specs, then priority 2–3 concept pages.
