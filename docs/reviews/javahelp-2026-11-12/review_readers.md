# Microwave Toolbox JavaHelp review: reader/mission pages and help-system structure

Date: 2026-11-12. Repo: `E:\ESA\microwave-toolbox` (read-only review, no files changed).
Method: parsed every `help.hs` / `map.jhm` / `toc.xml` (16 helpsets, excluding `target/`), extracted the text of all 41 in-scope pages, checked the internal links, images and `nbdocs://` links, cross-checked `layer.xml` helpIds against all map IDs, and compared the pages with the `ProductReaderPlugIn` classes and `META-INF/services` registrations.
The scripts are in the scratchpad (`helpaudit.py`, `helpids.py`, `pages.py`, `nb.py`).

Abbreviations: **SR** = `sar-io/src/main/javahelp/eu/esa/microwavetbx/sar/io/docs/readers/`, **RR** = `rstb/rstb-io/src/main/javahelp/org/csa/rstb/rstb/io/docs/readers/`, **GEN** = `sar-op-sar-processing-ui/src/main/resources/eu/esa/sar/sar/docs/general/`.
"eoportal-legacy" means links of the form `https://directory.eoportal.org/web/eoportal/...` (old CMS scheme; eoPortal moved to `https://www.eoportal.org/satellite-missions/...`). These appear 37 times across the help.

---

## Executive summary

1. **Content quality is very uneven.** About 8 pages are proper mission pages (BIOMASS, NISAR, ICEYE COG, RCM, Gaofen-3, SAOCOM, RISAT, Sentinel-1). About 10 are stubs or near-stubs under 60 words (RAT 22, Gamma 31, PCIDSK 34, ENVI 34, Capella 35, ERS-CEOS 52, TanDEM-X 53, SeaSat 54, ImageIO 55, ICEYE 68).
2. **One page is factually wrong.** `ImportQPSSAR.html` describes QPS-SAR as a "Quad-Polarization SAR / Generic Polarimetric Stack GeoTIFF interchange format". The reader (`QPSSARProductReaderPlugIn`, description "iQPS QPS-SAR Products") handles the Japanese iQPS X-band small-satellite constellation.
3. **Copy-paste errors are visible to users.** The page header reads "Import TerraSarX SAR" on Gamma, RAT and SeaSat. The `<title>` reads "Import ERS1/2 SAR or ATSR" on Radarsat-1, ImageIO, ERS, ERS-2 and ERS-CEOS. ALOS-2's title says "ALOS Palsar" and TanDEM-X's says "TerraSarX".
4. **The concept/overview layer is unreachable.** The microwavetbx-rcp helpset (Abstracted Metadata, Definitions, 3 tutorials) is never registered. The 4 GEN pages (Overview, Supported Products, Sentinel-1 mission, Sentinel-1 functionality) are in no map. They are also badly stale: S1TBX branding, Windows XP–8, S1A/S1B only, about 10 SAR missions listed against about 35 readers.
5. **Help buttons are broken or misrouted.**
   - The RADARSAT-1, RADARSAT-2 and RCM import dialogs use helpIds `importRadarsat1`, `importRadarsat2` and `importRCM`. The map defines `ImportRadarsat1`, `ImportRadarsat2` and `ImportRCM`. JavaHelp matching is case-sensitive, so these lookups fail.
   - The ALOS-2 and ALOS-4 dialogs (CEOS and GeoTIFF) open the ALOS-1 page.
   - TanDEM-X opens TerraSAR-X, and EOS-04 COG opens the CEOS-only RISAT page.
6. **Readers without help:** ALOS-4 (CEOS and GeoTIFF), EOS-04 COG, Sentinel-1 ETAD, Sentinel-1 COG (not mentioned on the S1 page), CIMR L1B, SNAPHU, Basic CEOS. Two pages document readers that are **not registered** in `META-INF/services`: ImageIO and the in-house PCIDSK reader.
7. **Few pages link a product format specification.** Only NISAR (`nisar.jpl.nasa.gov/documents/`), ICEYE COG (`sar.iceye.com`) and Envisat do. The Envisat link is an obsolete `earth.esa.int/pub/ESA_DOC` path. There are no links to the S1 Product Specification, RCM PFS, RS2 PFD, TSX L1b format spec, CSK/CSG handbooks, ALOS-2 format description, SAOCOM, Capella or the BIOMASS PFS.
8. **No master/slave wording on any in-scope page.** It does remain elsewhere: GSLCGeocodingOp.html (26 occurrences), coregistering_products.html (22), jlinda Range/AzimuthFilter (9 each), CreateStackOp (8), MultiReference (6). That is 129 occurrences across all help HTML.

---

## (a) Per-page table

Column key:
- **Types** = product types, levels, modes and polarisations documented.
- **Open** = says which file to select.
- **Limits** = known limitations stated.
- **Spec** = link to an official product format specification (a mission homepage or eoPortal does not count).
- **Bands** = describes the resulting bands or metadata.

Y = yes, P = partial, N = no.

### sar-io readers (SR, 34 pages)

| Page | Words | Types | Open | Limits | Bands | Spec | Staleness / errors |
|---|---|---|---|---|---|---|---|
| ImportAlos2.html | 105 | Y (L1.1/1.5/2.1/3.1, SM/ScanSAR/Spot, pols) | Y (volume file/zip) | P (order CEOS for InSAR) | N | N (JAXA home) | `<title>` "Import ALOS Palsar". eoportal-legacy. **Unreachable from menu**: all ALOS-2/ALOS-4 actions use helpId `ImportAlosPalsar` (`sar-io/.../layer.xml:21,30`, `sar-io-gdal/.../layer.xml:11,20`). Does not explain the separate "ALOS-2 GeoTIFF" reader (select `IMG-*-HH-*.tif`). L2.1/L3.1 support not evident in `ceos/alos2` code. |
| ImportAlosPalsar.html | 131 | Y | Y | Y (JAXA non-zero-Doppler, use ALOS Deskewing) | N | N | eoportal-legacy. Doubles as help for ALOS-2 and ALOS-4 dialogs without mentioning them. |
| ImportBiomass.html | 462 | Y (L1A SCS, L1B DGM, L1C STA, L2a/L2b FH/FD/GN/AGB with bands) | P (dir or ZIP; does not name main XML header) | P (L2 rejected by calibration) | Y | N (no BIOMASS L1/L2 PFS) | Points users to internal developer doc "`docs/BIOMASS-L2-Reader-Spec.md` in the toolbox repository" (not shipped). Opt-in BioPAL routing (`BiomassProductReaderPlugIn.java:91`, system property) undocumented. Otherwise a model page. |
| ImportCapella.html | **35** | P (GEO, SLC only) | **N** (reader keys on `CAPELLA_`/`ARL_` + `_extended.json`/`.json` + `.tif`) | N | N | N | "Polarizations: Single Pol HH" (Capella also acquires VV). "Operational: late 2020". eoportal-legacy. Effectively a placeholder. |
| ImportComplexGenericBinary.html | 175 | n/a (format) | Y (dialog) | Y (BSQ only) | P | n/a | Supported extensions (`bin, raw, cpx, cr4, ci2`) not listed. |
| ImportCosmoSkymed.html | 157 | Y (SCS/DGM/GEC/GTC, CSK+CSG, H5 + GeoTIFF) | Y | Y (SCS ScanSAR Huge unsupported) | N | N (ASI CSK/CSG product handbooks) | eoportal-legacy (×2). Good otherwise. |
| ImportERSCeos.html | 52 | P (GEC/PRI/SLC) | N | N | N | N | **Orphan**: in map, not in TOC, and no menu action points to it (ERS CEOS action uses `importERSProduct` → ImportErs.html). `<title>` "ERS1/2 SAR or ATSR". Duplicates ImportErs. |
| ImportEnvi.html | 34 | N | P | N | N | n/a | **Not in map.jhm** (unreachable except via SupportedProducts nbdocs link). The ENVI reader is SNAP-core. Stub. |
| ImportEnvisat.html | 384 | Y (full ASAR product table) | Y (.N1/zip) | N | P | P (`https://earth.esa.int/pub/ESA_DOC/ENVISAT/ASAR/asar.ProductHandbook.2_2.pdf` is an obsolete path) | Also lists **MERIS and AATSR** product tables (optical; out of place in the Microwave TBX; MER_RR/FR rows duplicated with "valid until 2002-12"). No statement that ESA's reprocessed ASAR products in SAFE/EE format are not readable (no such reader exists in microwave-toolbox or snap-engine). |
| ImportErs.html | 121 | Y | Y (.E1 / Volume) | N | N | N | `<title>` "ERS1/2 SAR or ATSR". No note that ERS SAR reprocessed SAFE products are unsupported. |
| ImportErs2.html | 107 | Y | Y | N | N | N | "Operational: to 2011-09-05" (start date 1995-04-21 missing). `<title>` ATSR. Near-duplicate of ImportErs (merge). |
| ImportGamma.html | **31** | N | N (no file type named) | Y ("only products as used by StaMPS") | N | n/a | **Header "Import TerraSarX SAR"** (copy-paste). |
| ImportGaofen3.html | 204 | Y (12 modes, L1A/L1B/L2, pols) | Y (.meta.xml/dir/zip) | N | N | N | Header logo links to the stale S1TBX `Overview.html` instead of SnapOverview. TOC label "Gaofen3". |
| ImportGenericBinary.html | 82 | n/a | Y | Y (BSQ only) | N | n/a | Extensions (`bin, bil, bsq, bip, img, ras, dat`) not listed. Note: the BSQ-only claim sits oddly with the accepted `.bil/.bip` extensions (verify). |
| ImportIceye.html | 68 | Y | **N** (reader takes `ICEYE_*.h5`, or `.xml` with a matching `.h5`) | N | N | N | **Stale**: "The constellation will have 18 satellites providing revisits of 1 hour" (contradicts COG page ">30 satellites"). Undefined terms "Ortho", "PRES". Does not say this is the legacy HDF5 delivery. |
| ImportIceyeCOG.html | 256 | Y | Y (.tif or .json) | Y (shadows-down transpose; generic GeoTIFF reader loses SAR metadata) | P | **Y** (`sar.iceye.com`) | Model page. |
| ImportImageIO.html | 55 | n/a | N | N | N | n/a | **Not in map.jhm**. **Reader not registered**: `sar-io/.../imageio/ImageIOReaderPlugIn.java` absent from `sar-io/src/main/resources/META-INF/services/org.esa.snap.core.dataio.ProductReaderPlugIn` (only a test uses it). Documents a non-existent reader. `<title>` "ERS1/2 SAR or ATSR". Typo "BPM". |
| ImportJERSCeos.html | 96 | Y | Y | N | N | N | eoportal-legacy. |
| ImportKompsat-5.html | 81 | P (SCS, GEC, WEC, GTC, WTC acronyms unexplained) | Y (`*_aux.xml`; reader also accepts `.h5`/`.tif`) | N | N | N | **Stale**: "will be followed by … Kompsat-6 in 2020". `http://www.si-imaging.com/products` (http, likely dead). "All modes are Single Pol (HH, HV, VV, VH)" ambiguous. |
| ImportNisar.html | 554 | Y (RSLC/RIFG/RUNW/ROFF/GSLC/GCOV/GUNW/GOFF/SME2, all present in code) | **N** (never says "select the .h5") | P | Y | **Y** (`nisar.jpl.nasa.gov/documents/`) | **Too developer-oriented** for end users: `Float.float16ToFloat(short)`, "ucar.nc2", `gslc_source_slc_path`/`gslc_output_flattened` stamps, `dop_coef_list.N`, `WINNIP_*` pre-launch simulations. "BIOMASS / NISAR profile" wording confusing. Missing: wavelength (~24 cm L / ~9 cm S), what an end-user should pick (RSLC for InSAR vs GSLC vs GCOV). |
| ImportPCIDSK.html | **34** | N | N | P ("only UTM … band interleaved") | N | N (refers to "PCIDSK Database Reference Manual" without link) | `sar-io/.../pcidsk/PCIReaderPlugIn.java` is **not registered** in services. The `importPCIDSK` menu action (formatName `PCIDSK`) therefore resolves to SNAP's GDAL reader `snap-engine/snap-gdal-reader/.../PCIDSKDriverProductReaderPlugIn.java`, so the UTM-only limitation probably describes the wrong reader. |
| ImportPaz.html | 139 | Y | Y | N | N | N (PAZ Image Products Guide) | eoportal-legacy. |
| ImportPolsarPro.html | 190 | n/a | Y (.hdr or folder) | P | P | N | Legacy "PolSARPro_NEST_metadata.xml" naming. Reader also keys on `config.txt` / `*bin.hdr` (not stated). |
| ImportQPSSAR.html | 119 | **Wrong** | N | N | N | N | **Factually incorrect**: describes a "QPS (Quad-Polarization SAR / Generic Polarimetric Stack) GeoTIFF interchange format". Code: `sar-io-gdal/.../qps/QPSSARProductReaderPlugIn.java` = "iQPS QPS-SAR Products", files `QPS-SAR*`/`QPSSAR*`/`QPS_SAR*` with `.tif/.xml/.json`. No mission info (iQPS, X-band, Japan). Must be rewritten. |
| ImportRAT.html | **22** | N | N | N | N | N | **Header "Import TerraSarX SAR"**. Nothing on the RAT format, file extension or use case. |
| ImportRISAT.html | 188 | Y (RISAT-1 + EOS-04, beams, levels, pols) | Y | N | N | N | Says EOS-04 uses CEOS. The **EOS-04 COG** reader (`sar-io-gdal/.../eos4/EOS4CogProductReaderPlugIn.java`, "EOS-04 / RISAT COG Products"), whose menu action uses `importRISAT1`, is not described. eoportal-legacy. |
| ImportSAOCOM.html | 183 | Y | Y (.xemt/dir/zip) | N | N | N (CONAE product format doc) | Good. eoportal-legacy. |
| ImportSeaSat.html | 54 | P (GRD only) | **N** | N | N | N | **Header "Import TerraSarX SAR"**. eoportal-legacy. |
| ImportSentinel1.html | 966 | Y (SLC/GRD/OCN, modes) | Y (manifest.safe/zip) | N | N | **N**: links `https://sentinel.esa.int/web/sentinel/user-guides/sentinel-1-sar` (retired; now SentiWiki); no S1 Product Specification | **Stale**: "S1B 2016-04-25 to present" (S1B ended 2022 after the Dec-2021 anomaly). No S1C (launched 2024-12) or S1D (2025-11). "Polarizations: Dual Pol" omits single-pol. Silent on the **SENTINEL-1 COG** reader (sar-io-gdal) and **ETAD** products (separate reader). Silent on Level-0 (SupportedProducts says L0 metadata-only). About 700 words of SAFE-format prose copied from the product spec, but no description of the resulting bands (`i_IW1_VV`, burst structure) or Abstracted Metadata. |
| ImportSpacety.html | 101 | Y | P ("manifest XML"; reader accepts `.safe`/`.zip`, `bc*`/`bx*` folders) | P (orbits) | N | N | Mentions "SpacetyCalibrator" (exists: `sar-op-calibration/.../SpacetyCalibrator.java`); class names are not user language. |
| ImportStriX.html | 120 | Y | **N** | N | N | N | **Inaccurate**: says the reader "parses the StriX product manifest (XML + GeoTIFF)". The **SLC reader is CEOS** (`strix/StriXProductReaderPlugIn.java`, vol/leader/image/trailer, `vol-strix`). Only the **GRD** reader (`strix/grd/StriXGRDProductReaderPlugIn.java`, `PAR-*.xml` + `.tif`) is XML+GeoTIFF. The InSAR advice ("bundled state vectors … sufficient for InSAR baselines below a few weeks") is unsupported. |
| ImportTanDEM-X.html | 53 | P (no pols) | N | N | N | N | `<title>` "TerraSarX". `https://www.dlr.de/hr/en/desktopdefault.aspx/tabid-2317/` (dead DLR CMS URL). Menu TanDEM-X action uses `ImportTerraSarX`, so this page is only reachable from the TOC. Fully superseded by ImportTerraSarX (which covers TDX + CoSSC). Merge or delete. |
| ImportTerraSarX.html | 146 | Y (incl. CoSSC) | Y | N | N | N (TSX L1b Product Format Spec) | `https://www.dlr.de/dlr/en/desktopdefault.aspx/tabid-10377` (dead DLR CMS URL). |
| ImportUAVSAR.html | 152 | Y | P (ANN must sit alongside; does not say to select the `.mlc/.grd/.slc/.dat` data file) | P | N | N | Lists "HGT" product but reader extensions are `mlc, grd, slc, dat` only (`uavsar/UAVSARReaderPlugIn.java`). |

### rstb-io readers (RR, 3 pages)

| Page | Words | Types | Open | Limits | Bands | Spec | Staleness / errors |
|---|---|---|---|---|---|---|---|
| ImportRCM.html | 306 | Y (SLC/GRC/GCC/GRD/GCD/MLC, all in code; modes; CP) | Y (manifest.safe/dir/zip; reader also accepts `product.xml`) | N ("supports all product types and polarization modes" over-claims) | P (LUT files) | **N** (RCM Product Format Specification) | **Help button broken**: `rstb-io/.../layer.xml:29` helpId `importRCM` vs map `ImportRCM`. File is UTF-8 with no charset meta (3 non-ASCII chars). Good mission content. |
| ImportRadarsat1.html | 99 | Y | Y | N | N | N | `<title>` "Import ERS1/2 SAR or ATSR". `http://www.asc-csa.gc.ca/...` (http). Typos "mulitlooked". **Help button broken** (`importRadarsat1` vs `ImportRadarsat1`, `layer.xml:11`). |
| ImportRadarsat2.html | 116 | Y (SPG missing from list; SupportedProducts lists SPG) | Y (product.xml/zip) | Y (NITF unsupported) | N | N (RS2 Product Format Definition RN-RP-51-2713) | http link. Typos. **Help button broken** (`importRadarsat2`, `layer.xml:20`). |

### General pages (GEN, 4 pages): none are in any map.jhm or TOC

| Page | Words | Assessment |
|---|---|---|
| Overview.html | 537 | Title "S1TBX Help", "Sentinel-1 Toolbox (S1TBX)", "SeNtinels Application Platform", "Integrated WorldWind visualisation", "Automatic SRTM DEM download", "tested under MS Windows XP, Vista, 7 and 8", supported formats list from about 2015 (MERIS/AATSR, ASAR Wave). Reachable only via the header link on ImportGaofen3.html. Should be rewritten as "Microwave Toolbox Overview" or deleted. |
| SupportedProducts.html | 313 | Lists Sentinel-1, ASAR, ERS, JERS, ALOS-1, RS-1/2, TSX, CSK + optical (MERIS, ATSR, AVNIR-2, PRISM, Landsat) + "ImageIO". **Missing about 25 SAR readers**: NISAR, BIOMASS, ALOS-2/4, Capella, ICEYE (+COG), Gaofen-3, Kompsat-5, PAZ, RISAT/EOS-04, SAOCOM, SeaSat, Spacety, StriX, QPS-SAR, UAVSAR, RCM, TanDEM-X, CSG, S1 ETAD/COG, Gamma, RAT, complex generic binary, CIMR. Links into `eu.esa.opt.opttbx.*` modules break when the Optical Toolbox is not installed. Links to the orphan ImportEnvi/ImportImageIO pages. |
| Sentinel1.html | 1906 | The richest mission-education page in the help (modes, resolutions, revisit, levels), but: "constellation of two satellites, Sentinel-1A and Sentinel-1B … six day repeat cycle"; future tense ("will be delivered", "constellation will have"); dangling citation "[R11]"; no links to S1 specifications or SentiWiki. Unreachable. |
| Sentinel1_Functionality.html | 96 | "Sentinel-1 Toolbox" naming. Bullet list with no links to the operator pages. Omits ETAD, GSLC and S-1 COG. Unreachable. |

### Images and links
- All relative `<img src>` and internal `href` targets in the 41 pages resolve.
- All `nbdocs://` targets exist in microwave-toolbox, snap-desktop, snap-engine or optical-toolbox. The optical ones need the Optical Toolbox installed.
- `readers/images/generic_reader.jpg` is used by the two Generic Binary pages.

---

## (b) Readers in code without (adequate) help

Registered readers are listed in `*/src/main/resources/META-INF/services/org.esa.snap.core.dataio.ProductReaderPlugIn`.

| Reader | Plugin class | Help situation |
|---|---|---|
| ALOS-4 CEOS | `sar-io/src/main/java/eu/esa/sar/io/ceos/alos4/Alos4ProductReaderPlugIn.java` | **No page**; menu "ALOS-4 CEOS" uses `ImportAlosPalsar` (ALOS-1 page). |
| ALOS-4 GeoTIFF | `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/alos4/Alos4GeoTiffProductReaderPlugIn.java` | **No page**; helpId `ImportAlosPalsar`. |
| ALOS-2 GeoTIFF | `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/alos2/Alos2GeoTiffProductReaderPlugIn.java` | ImportAlos2 exists but the helpId points to `ImportAlosPalsar`. |
| EOS-04 COG | `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/eos4/EOS4CogProductReaderPlugIn.java` | **No page**; helpId `importRISAT1`, whose page describes only CEOS. |
| Sentinel-1 ETAD | `sar-io/src/main/java/eu/esa/sar/io/sentinel1/Sentinel1ETADProductReaderPlugIn.java` | **No page, no menu action, not mentioned** on the S1 page. (ETAD operators have help in sar-op-sentinel1-ui.) |
| Sentinel-1 COG | `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/sentinel1/Sentinel1GDALProductReaderPlugIn.java` | Menu "SENTINEL-1 COG" uses `importSentinel1Product`; the S1 page never mentions COG. |
| CIMR L1B (radiometer) | `cimr-reader/src/main/java/eu/esa/snap/cimr/CimrL1BProductReaderPlugin.java` | **No help at all**; `cimr-reader-ui` has no helpset. |
| SNAPHU import | `sar-io/src/main/java/eu/esa/sar/io/snaphu/SNAPHUProductReaderPlugIn.java` | No reader page (the jlinda SnaphuImportOp page covers the operator only). |
| Basic CEOS (fallback) | `sar-io/src/main/java/eu/esa/sar/io/ceos/basic/BasicCeosProductReaderPlugIn.java` | No page; users are not told a generic CEOS fallback exists. |
| StriX GRD | `sar-io/src/main/java/eu/esa/sar/io/strix/grd/StriXGRDProductReaderPlugIn.java` | Covered by ImportStriX, but that page mixes up the SLC (CEOS) and GRD (XML+GeoTIFF) formats. |
| QPS-SAR | `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/qps/QPSSARProductReaderPlugIn.java` | Page exists but describes the wrong thing. |
| Cosmo-SkyMed GDAL (CSG GeoTIFF) | `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/cosmo/CosmoSkymedGDALReaderPlugIn.java` | Adequately covered by ImportCosmoSkymed. |
| GTOPO30 | `sar-io/src/main/java/eu/esa/sar/io/gtopo30/GTOPO30ReaderPlugIn.java` | Help lives in snap-desktop `snap-dem-ui` (ImportGTOPO30.html). OK. |
| Matlab (soil moisture) | `rstb/rstb-op-soil-moisture/src/main/java/org/csa/rstb/soilmoisture/io/matlab/MatlabReaderPlugIn.java` | No page (internal LUT format; mention it on the soil-moisture operator pages). |
| NovaSAR | separate repo `E:\ESA\snap-novasar-reader` (`NovaSARProductReaderPlugIn`) | Has its own helpset; not in microwave TOC. Out of scope, but SupportedProducts should list it. |

Pages documenting **unregistered / dropped** readers:
- `SR/ImportImageIO.html` → `ImageIOReaderPlugIn` not in services (test-only).
- `SR/ImportPCIDSK.html` → `PCIReaderPlugIn` not in services; the action resolves to the GDAL PCIDSK reader.
- `SR/ImportEnvi.html` → ENVI is a SNAP-core reader; this page is unmapped.

Gap in both code and documentation: there is **no reader for ESA's reprocessed ERS-1/2 SAR and Envisat ASAR products in SAFE format**, now the standard ESA distribution of the heritage archive. The ERS and Envisat pages should state this.

Pages that are placeholders (≤60 words): RAT (22), Gamma (31), PCIDSK (34), ENVI (34), Capella (35), ERSCeos (52), TanDEM-X (53), SeaSat (54), ImageIO (55).

---

## (c) Help-system structural issues

### Registration
1. **microwavetbx-rcp helpset is never registered.** `microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs/help.hs` has no `@HelpSetRegistration` package-info and no `Services/JavaHelp` entry in `microwavetbx-rcp/.../layer.xml`. No JavaHelpSearch index is built for it either.
   - Consequence: Abstracted Metadata, Definitions, and the tutorials `remote_sensing.html`, `coregistering_products.html` and `terrain_correction.html` (plus `visat/ProjectView.html`, `visat/WorldMap.html`) are absent from TOC and search.
   - Only `AbstractedMetadata.html` is reachable, via nbdocs links from the ENVI and ImageIO pages, which are themselves orphans.
   - Its map contains only `AbstractedMetadata`, so 6 of its HTML files are unmapped.
2. **The ocean helpset is never registered.** `sar-op-analysis-ui/src/main/resources/eu/esa/sar/ocean/docs/help.hs` has no registration (only `eu/esa/sar/analysis/docs` is annotated). Its TOC is empty, `wave/PolarView.html` is mapped but not in the TOC, and `wave/ProjectView.html` is unmapped.
3. **Duplicate registration.** `sar-io-gdal/src/main/resources/eu/esa/sar/iogdal/helpset.xml` points at **sar-io's** help.hs (`nbdocs:/eu/esa/microwavetbx/sar/io/docs/help.hs`), so the same helpset is merged twice.
   - `sar-io-gdal/target/javahelp/eu/esa/microwavetbx/sar/io/gdal/docs/JavaHelpSearch` is a leftover from a former own helpset.
   - The sar-io-gdal reader pages (BIOMASS, ICEYE COG, QPS) live in sar-io, coupling the two modules.
   - Recommendation: either drop the gdal `helpset.xml` or give sar-io-gdal its own helpset.

### Map / TOC consistency (per helpset)

| Helpset | Issue |
|---|---|
| sar-io | `ImportEnvi.html` and `ImportImageIO.html` not in map. `ImportERSCeos` in map but not in TOC. 14 alias mapIDs (`importCapella` etc.) are a workaround for helpId casing drift. |
| rstb-io | **No alias block**. The `layer.xml` helpIds `importRadarsat1`, `importRadarsat2` and `importRCM` (`rstb/rstb-io/src/main/resources/org/csa/rstb/io/layer.xml:11,20,29`) do not resolve, so the three Help buttons fail. |
| sar-op-sar-processing-ui | TOC entry `PyRateExportOp` has **no mapID**; the file `operators/PyRateExportOp.html` exists but is unmapped, so the TOC node is dead. The 4 `general/*.html` pages are unmapped. |
| sar-op-insar-ui | `PCAOp`, `PhaseLinkingOp` and `SBASInversionOp` are in the map but **not in the TOC** (new DS-InSAR/SBAS operators are invisible in Contents). "Create Stack" appears twice in the TOC (Coregistration and Stack Tools). |
| jlinda-nest-ui | `operators/InSAR.html` (the InSAR overview, conceptually the most useful page) is mapped but not in the TOC. |
| rstb-op-soil-moisture-ui | `operators/Hallikainen.html` and `operators/IEM-LUT.html` are not in the map. |
| all 16 helpsets | `<homeID>top</homeID>` but no `top` mapID exists anywhere. No `IndexView` / `index.xml` (no keyword index), so only full-text search is available. |

- Other helpId check: of all `layer.xml` / Java helpIds, apart from the rstb trio only `exportImageFile` (`sar-op-analysis-ui/.../GraphExportImageAction.java:45`, probably resolved by SNAP core) and a `MicrowaveTBXOptionsPanelController` constant are unresolved within this repo.
- **Duplicate mapIDs across helpsets: none.**
- **Missing files behind map targets: none.**

### Wrong-target helpIds (resolve, but open the wrong page)
- ALOS-2 CEOS, ALOS-4 CEOS, ALOS-2 GeoTIFF and ALOS-4 GeoTIFF all open `ImportAlosPalsar` (`sar-io/.../layer.xml:21,30`; `sar-io-gdal/.../layer.xml:11,20`).
- TanDEM-X opens `ImportTerraSarX` (`sar-io/.../layer.xml:157`).
- EOS-04 opens `importRISAT1` (`sar-io-gdal/.../layer.xml:47`).
- S1 COG opens `importSentinel1Product` (acceptable once the S1 page covers COG).

### Learning path
- Every microwave TOC hangs under the SNAP-wide merge root "Sentinel Toolbox Application". It is shared with snap-desktop, so it cannot be renamed unilaterally.
- Inside it, microwave content has no own entry point. There is no "Microwave Toolbox → Getting started / SAR concepts / Supported missions / Workflows" branch.
- Readers sit at "Import and Export → Import Raster Data → SAR Sensors / SAR Formats"; operators at "Radar → …". The concept and tutorial pages that exist (rcp helpset, jlinda InSAR.html, GEN pages) are all unreachable.
- So the path **concepts → workflows → operators → readers does not exist**. Only the last two layers are navigable.
- TOC label hygiene:
  - The SAR Sensors list is not alphabetical: Gaofen3 comes before Cosmo-Skymed, and TerraSAR-X before TanDEM-X.
  - Naming is inconsistent: "Gaofen3", "Paz", "Cosmo-Skymed", "ALOS PALSAR 1".
  - ALOS-4, EOS-04, S1 ETAD, S1 COG and CIMR are missing.

---

## (d) Recommendations (prioritised)

**P0: correctness and broken navigation (small effort)**
1. Rewrite `SR/ImportQPSSAR.html` for the iQPS QPS-SAR mission (X-band, Japan; file prefixes and `.tif/.xml/.json`).
2. Fix the headers and titles:
   - Headers on Gamma, RAT and SeaSat ("Import TerraSarX SAR").
   - `<title>` on RS-1, ImageIO, ERS, ERS-2, ERS-CEOS, ALOS-2 and TanDEM-X.
3. Fix the rstb-io helpIds. Either change `layer.xml` to `ImportRadarsat1`/`ImportRadarsat2`/`ImportRCM`, or add lowercase aliases to `RR/../map.jhm`, as sar-io already does.
4. Point the ALOS-2 CEOS/GeoTIFF actions to `ImportAlos2`. Add an ALOS-4 page or section, and an EOS-04 COG section on ImportRISAT.
5. Add a `PyRateExportOp` mapID in `sar-op-sar-processing-ui/.../docs/map.jhm`.
6. Add `PhaseLinkingOp`, `SBASInversionOp` and `PCAOp` to the insar TOC, and `InSAR.html` to the jlinda TOC.
7. Register the microwavetbx-rcp helpset (`@HelpSetRegistration`, as the other UI modules do) and map its tutorials. Decide whether the ocean helpset is still wanted, then register it or delete it.
8. Remove `sar-io-gdal/.../helpset.xml`, which double-registers the sar-io helpset.

**P1: currency (stale facts)**
9. Sentinel-1:
   - S1B end of mission (2022). Add S1C and S1D.
   - Replace `sentinel.esa.int` with SentiWiki and link the S1 Product Specification and S1 Level-1 Product Format Specification.
   - Document COG products, ETAD products (and their reader) and Level-0 behaviour.
   - Shorten the copied SAFE prose; replace it with "what you get": band naming such as `i_IW1_VV`/`q_`/`Intensity_`, bursts, and tie-point grids.
10. Replace all 37 `directory.eoportal.org/web/eoportal/...` links with the `www.eoportal.org/satellite-missions/...` scheme. Fix the DLR `desktopdefault.aspx` links, the `earth.esa.int/pub/ESA_DOC` ASAR handbook link and the `si-imaging.com` link.
11. Remove the statements about future plans ("ICEYE will have 18 satellites", "Kompsat-6 in 2020").
12. Rewrite or retire the GEN pages:
    - Overview → "Microwave Toolbox overview".
    - SupportedProducts → generate it from the `META-INF/services` list, or maintain it as a table: mission | reader format name | levels | file to select | help link. Drop the optical rows.
    - Sentinel1.html → update and link it from the TOC.
13. Delete or merge the orphan and duplicate pages: ImportImageIO (reader unregistered), ImportEnvi (SNAP-core), ImportERSCeos → ImportErs, ImportErs2 → ImportErs, ImportTanDEM-X → ImportTerraSarX. Verify ImportPCIDSK against the GDAL reader.

**P2: user value (mission education and consistency)**
14. Adopt one template for every mission page, modelled on ImportIceyeCOG/ImportBiomass but in user language. Sections:
    - Mission (operator, band and wavelength, orbit and revisit, launch/end).
    - Supported products (levels × modes × polarisations).
    - **How to open** (exact file to select).
    - **What you get** (bands, units, complex vs detected, geocoding, Abstracted Metadata link).
    - **Known limitations**.
    - **Recommended next step** (e.g. Apply Orbit → Calibration).
    - **Official product format specification link**.
15. Add the missing format-spec links:
    - S1 PS / L1 PFS.
    - RCM Product Format Specification.
    - RADARSAT-2 Product Format Definition.
    - TerraSAR-X Level 1b Product Format Specification.
    - CSK and CSG product handbooks.
    - ALOS-2/ALOS-4 product format descriptions.
    - SAOCOM product format.
    - Capella SAR Imagery Products Format Specification.
    - BIOMASS L1/L2 Product Format Specification.
    - PAZ Image Products Guide.
    - UAVSAR data format docs.
16. Strip developer internals from ImportNisar (`ucar.nc2`, float16 decoding, metadata stamp names) and ImportBiomass (repo spec reference). Move them to developer docs. Add user guidance on which NISAR product to choose.
17. Expand the stubs: Capella, SeaSat, Gamma (say which `.par`/data files), RAT, PCIDSK. Document BIOMASS BioPAL opt-in and the UAVSAR "select the data file, `.ann` alongside" rule. Correct the StriX SLC-is-CEOS description.
18. Add a CIMR L1B reader page, or at least a TOC entry under a "Microwave Radiometer" node.
19. Add a microwave landing branch (Getting started / SAR concepts / Supported missions / Workflows). Sort the SAR Sensors list alphabetically with consistent names (Cosmo-SkyMed, Gaofen-3, PAZ, ALOS PALSAR, ALOS-2, ALOS-4).
20. Add an `IndexView` (index.xml) for keyword lookup, or at least define a `top` mapID so the declared homeID resolves.
21. Housekeeping beyond scope:
    - Finish the Reference/Secondary terminology migration in the operator pages (129 master/slave occurrences, mostly GSLCGeocodingOp.html and coregistering_products.html).
    - Add `<meta charset>` to `RR/ImportRCM.html` (UTF-8 without declaration).
