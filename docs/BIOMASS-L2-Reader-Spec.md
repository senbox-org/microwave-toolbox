# BIOMASS Level 2 Reader Specification

**Version:** 1.0
**Date:** 2026-04-03
**Status:** Draft — awaiting open access to L2 products (expected summer 2026)
**Module:** `sar-io-gdal`
**Package:** `eu.esa.sar.iogdal.biomass`

---

## 1. Overview

This document specifies the reader implementation for ESA BIOMASS Level 2 (L2a and L2b) data products in the SNAP/microwave-toolbox framework. BIOMASS is ESA's 7th Earth Explorer mission, carrying the first spaceborne P-band SAR (435 MHz, ~70 cm wavelength). It launched on 29 April 2025.

L2 products contain derived geophysical parameters (forest height, above-ground biomass density, forest disturbance) rather than SAR measurement data. They use a fundamentally different product structure from L1 and require a dedicated reader class.

### 1.1 Reference Documents

| ID | Document | Issue |
|----|----------|-------|
| PFD-L1 | BPS L1 Product Format Definition | v1.3.0 |
| PFD-FH | BIOMASS Forest Height Products Format Specification | v3.3.0, Nov 2025 |
| PFD-FD | BIOMASS Forest Disturbance Products Format Specification | v3.3.0, Nov 2025 |
| PFD-AGB | BIOMASS Above Ground Biomass Products Format Specification | v3.3.0, Nov 2025 |

Available at: https://earth.esa.int/eogateway/missions/biomass/documentation

### 1.2 Data Access

- **STAC Catalog:** `https://catalog.maap.eo.esa.int/catalogue/`
- **L2a Collection:** `BiomassLevel2a` (also `BiomassLevel2aIOC`)
- **L2b Collection:** `BiomassLevel2b` (also `BiomassLevel2bIOC`)
- **MAAP Explorer:** `https://explorer.maap.eo.esa.int/?q=BiomassLevel2a`
- **Authentication:** OIDC token from `https://portal.maap.eo.esa.int/`
- **Current status:** L2 products restricted to commissioning team; open access expected summer 2026

---

## 2. Product Types

### 2.1 L2a Products (Stack-based, Per-swath)

L2a products are geocoded to a minimum lat-lon bounding box covering the stack footprint. One product per stack per swath.

| Product Type ID | Name | Resolution | Bands |
|-----------------|------|------------|-------|
| `FP_FD__L2A` | Forest Disturbance | 50 x 50 m | fd (binary uint8), probability (float32), cfm (uint8) |
| `FP_FH__L2A` | Forest Height | 200 x 200 m | fh (float32, meters), quality (float32) |
| `FP_GN__L2A` | Ground Notch | 200 x 200 m | gn (float32, 3-band: HH/VH/VV backscatter) |

### 2.2 L2b Products (Tile-based, Aggregated)

L2b products are aggregated from L2a over a Global Cycle and geocoded to BIOMASS Discrete Global Grid (DGG) tiles. One product per tile.

| Product Type ID | Name | Resolution | Bands |
|-----------------|------|------------|-------|
| `FP_FD__L2B` | Forest Disturbance | 50 x 50 m | fd (uint8), probability (float32), cfm (uint8) |
| `FP_FH__L2B` | Forest Height | 200 x 200 m | fh (float32, meters), quality (float32) |
| `FP_AGB_L2B` | Above Ground Biomass | 200 x 200 m | agb (float32, t/ha), agb_std_dev (float32) |

---

## 3. Product Naming Convention

### 3.1 L2a

```
BIO_<TYPE>_<start_datetime>_<stop_datetime>_<phase>_G<cycle>_M<major>_C<___>_T<track>_F<frame>_<ver>_<uid>
```

Example:
```
BIO_FP_FH__L2A_20260101T120000_20260101T120030_I_G01_M01_C____T010_F001_01_D0ABCD
```

### 3.2 L2b

```
BIO_<TYPE>_<phase>_G<cycle>_T<tile_id>_B<basin>_<ver>_<uid>
```

Where `<tile_id>` is in format `[N|S]dd[E|W]ddd` (e.g., `N05W060`).

Example:
```
BIO_FP_AGB_L2B_I_G01_TN05W060_B100_01_D0ABCD
```

### 3.3 File-level Detection

All L2 products use the prefix `BIO_FP_`. The metadata entry-point XML filename matches the product directory name (lowercased, with `.xml` extension), same pattern as L1.

---

## 4. Product Directory Structure

### 4.1 L2a

```
BIO_FP_FH__L2A_<...>/
  bio_fp_fh__l2a_<...>.xml              # Main Product Header (MPH)
  *.json                                  # STAC metadata
  measurement/
    *_i_fh.tiff                           # Forest Height COG (float32)
    *_i_quality.tiff                      # Quality COG (float32)
  annotation/
    *_annot.xml                           # Main annotation
    *_lut.nc                              # LUT NetCDF4 (FNF mask, incidence angle, etc.)
  preview/
    *_fh_ql.png                           # Quicklook
    *_fh_map.kml                          # KML overlay
  schema/
    *.xsd                                 # XML schemas
```

#### L2a Variant: Forest Disturbance

```
  measurement/
    *_i_fd.tiff                           # Disturbance binary (uint8: 0/1)
    *_i_probability.tiff                  # Probability of change (float32: 0.0-1.0)
    *_i_cfm.tiff                          # Computed Forest Mask (uint8)
```

#### L2a Variant: Ground Notch

```
  measurement/
    *_i_gn.tiff                           # 3-band COG: HH, VH, VV backscatter (float32)
```

### 4.2 L2b

```
BIO_FP_AGB_L2B_<...>/
  bio_fp_agb_l2b_<...>.xml              # Main Product Header (MPH)
  *.json                                  # STAC metadata
  measurement/
    *_i_agb.tiff                          # AGB density COG (float32, t/ha)
    *_i_agb_std_dev.tiff                  # Standard deviation COG (float32)
  annotation/
    *_annot.xml                           # Main annotation
    *_i_bps_fnf.tiff                      # Forest/Non-Forest mask COG (uint8)
    *_i_heatmap.tiff                      # Heat map COG (multi-band)
    *_i_acquisition_id_image.tiff         # Acquisition ID COG
  preview/
    *_ql.png
    *_map.kml
  schema/
    *.xsd
```

Note: L2b products have **no LUT NetCDF**. Auxiliary layers (FNF, heatmap, acquisition ID) are stored as COG files in `annotation/`.

---

## 5. Data Formats

### 5.1 Measurement Data: Cloud Optimized GeoTIFF (COG)

| Property | Value |
|----------|-------|
| Format | GeoTIFF with cloud-optimized tiling |
| Float data type | Float32 |
| Integer data type | UByte (uint8) for binary/mask layers |
| Compression | LERC+ZSTD (float), ZSTD (binary) |
| Overviews | 2x2 and 4x4 pyramid layers |
| No-data value | -9999.0 (float32) or 255 (uint8) |
| CRS | WGS84 geographic (EPSG:4326) |
| Geotransform | Encoded via GeoTIFF ModelPixelScaleTag + ModelTiePointTag |

GDAL reads COG/LERC+ZSTD natively. The existing `GTiffDriverProductReaderPlugIn` should handle these without modification.

### 5.2 Annotation LUT: NetCDF4 (L2a only)

Contains lookup tables such as:
- Forest/Non-Forest mask
- Local incidence angle (for FH, AGB)
- ACM covariance matrix and numberOfAverages (for FD)

### 5.3 Main Annotation: XML

Single `*_annot.xml` file per product containing:
- Product DSR (identifier, type, processing info)
- Raster Image DSR (dimensions, pixel spacing, geolocation)
- Input Information DSR (source product references)
- Processing Parameters DSR
- Annotation LUT DSR (references to NetCDF/COG layers)

### 5.4 Main Product Header (MPH): XML

Uses `bio:EarthObservation` root element (OGC/EOP-compliant). Structurally similar to L1 MPH.

Key metadata paths:

```
EarthObservation
  metaDataProperty
    EarthObservationMetaData
      identifier                    # Product ID
      productType                   # e.g., "FP_FH__L2A", "FP_AGB_L2B"
      processing
        ProcessingInformation
          processingLevel           # "L2A" or "L2B"
  procedure
    EarthObservationEquipment
      sensor
        Sensor
          operationalMode
          swathIdentifier           # S1, S2, S3 (L2a only)
      acquisitionParameters
        Acquisition
          orbitDirection
          missionPhase              # COM, INT, TOM
          polarisationChannels
          globalCoverageID
          majorCycleID
          tileID                    # DGG tile IDs (L2b)
```

---

## 6. Projection and Grid

| Property | Value |
|----------|-------|
| CRS | WGS84 geographic (lat-lon), EPSG:4326 |
| Pixel spacing (FD) | ~0.00045 deg (~50 m at equator) |
| Pixel spacing (FH/AGB/GN) | ~0.0018 deg (~200 m at equator) |
| L2a extent | Minimum bounding box of stack footprint |
| L2b extent | Fixed DGG tile (e.g., N05W060 = 5x5 deg tile) |

L2 products are already map-projected. Geocoding comes directly from the GeoTIFF geotransform tags, not from NetCDF tie-point grids.

---

## 7. Band Definitions

### 7.1 Forest Height (FH)

| Band Name | Source File | Data Type | Unit | Description |
|-----------|------------|-----------|------|-------------|
| `Forest_Height` | `*_i_fh.tiff` | Float32 | meters | Top canopy height |
| `Forest_Height_Quality` | `*_i_quality.tiff` | Float32 | % | Percentage bias quality |

### 7.2 Forest Disturbance (FD)

| Band Name | Source File | Data Type | Unit | Description |
|-----------|------------|-----------|------|-------------|
| `Forest_Disturbance` | `*_i_fd.tiff` | UByte | flag | Binary: 0=intact, 1=deforested |
| `Probability_of_Change` | `*_i_probability.tiff` | Float32 | - | Probability [0.0, 1.0] |
| `Computed_Forest_Mask` | `*_i_cfm.tiff` | UByte | flag | Forest mask classification |

### 7.3 Ground Notch (GN) — L2a only

| Band Name | Source File | Data Type | Unit | Description |
|-----------|------------|-----------|------|-------------|
| `Ground_Notch_HH` | `*_i_gn.tiff` band 1 | Float32 | - | Ground-cancelled backscatter HH |
| `Ground_Notch_VH` | `*_i_gn.tiff` band 2 | Float32 | - | Ground-cancelled backscatter VH |
| `Ground_Notch_VV` | `*_i_gn.tiff` band 3 | Float32 | - | Ground-cancelled backscatter VV |

### 7.4 Above Ground Biomass (AGB) — L2b only

| Band Name | Source File | Data Type | Unit | Description |
|-----------|------------|-----------|------|-------------|
| `AGB` | `*_i_agb.tiff` | Float32 | t/ha | Above-ground biomass density |
| `AGB_Std_Dev` | `*_i_agb_std_dev.tiff` | Float32 | t/ha | Standard deviation |

### 7.5 Annotation Layers (L2b)

| Band Name | Source File | Data Type | Unit | Description |
|-----------|------------|-----------|------|-------------|
| `FNF_Mask` | `annotation/*_i_bps_fnf.tiff` | UByte | flag | Forest/Non-Forest mask |
| `Heatmap` | `annotation/*_i_heatmap.tiff` | Float32 | - | Observation density |
| `Acquisition_ID` | `annotation/*_i_acquisition_id_image.tiff` | Int32 | - | Source acquisition identifier |

---

## 8. Implementation Design

### 8.1 Plugin Detection

Extend `BiomassProductReaderPlugIn` to recognize L2 products:

```java
// Current L1 prefix
private final static String PRODUCT_PREFIX_L1 = "BIO_S";
// New L2 prefix
private final static String PRODUCT_PREFIX_L2 = "BIO_FP";
```

The `getDecodeQualification()` method must match filenames starting with either prefix. The `getProductMetadataFilePrefixes()` method returns both prefixes.

### 8.2 Product Directory Class

Create `BiomassL2ProductDirectory extends XMLProductDirectory`:

```
BiomassProductReader
  ├── BiomassProductDirectory        (L1: SCS, DGM, STA)
  └── BiomassL2ProductDirectory      (L2a: FD, FH, GN; L2b: FD, FH, AGB)
```

`BiomassProductReader.readProductNodesImpl()` determines which directory class to instantiate based on the product type detected from the filename or MPH metadata.

Decision logic:
```java
boolean isL2 = productName.toUpperCase().contains("_FP_");
if (isL2) {
    dataDir = new BiomassL2ProductDirectory(metadataFile);
} else {
    dataDir = new BiomassProductDirectory(metadataFile);
}
```

### 8.3 BiomassL2ProductDirectory Responsibilities

#### 8.3.1 Metadata Extraction

Read from MPH XML (`bio_fp_*.xml`):
- `identifier`, `productType`, `processingLevel`
- `missionPhase`, `globalCoverageID`, `majorCycleID`
- `orbitDirection`, `swathIdentifier` (L2a only)
- `tileID` (L2b only)
- Set `MISSION = "BIOMASS"`

Read from annotation XML (`*_annot.xml`):
- Raster dimensions (`numberOfLines`, `numberOfSamples`)
- Pixel spacing (`latitudeSpacing`, `longitudeSpacing`)
- Processing parameters

**Not applicable for L2** (skip entirely):
- Orbit state vectors
- SRGR coefficients
- Doppler centroid coefficients
- SAR instrument parameters (PRF, radar frequency, etc.)
- Calibration constants

#### 8.3.2 Band Creation

Discover COG files in `measurement/` directory. Map filename suffixes to band names:

```java
private static final Map<String, String[]> L2_BAND_MAP = Map.of(
    "fh",          new String[]{"Forest_Height", Unit.METERS},
    "quality",     new String[]{"Forest_Height_Quality", "%"},
    "fd",          new String[]{"Forest_Disturbance", "flag"},
    "probability", new String[]{"Probability_of_Change", ""},
    "cfm",         new String[]{"Computed_Forest_Mask", "flag"},
    "agb",         new String[]{"AGB", "t/ha"},
    "agb_std_dev", new String[]{"AGB_Std_Dev", "t/ha"}
);
```

For `*_i_gn.tiff` (multi-band COG), create 3 bands: `Ground_Notch_HH`, `Ground_Notch_VH`, `Ground_Notch_VV`.

For L2b annotation COGs (`annotation/*_i_bps_fnf.tiff`, etc.), optionally create additional bands.

#### 8.3.3 Geocoding

L2 products are map-projected. Use `CrsGeoCoding` from the GeoTIFF geotransform:

```java
// Read geotransform from COG via GDAL
double originX = ...;   // from ModelTiePointTag
double originY = ...;
double pixelSizeX = ...; // from ModelPixelScaleTag
double pixelSizeY = ...;

CoordinateReferenceSystem crs = CRS.decode("EPSG:4326");
product.setSceneGeoCoding(new CrsGeoCoding(crs, width, height, originX, originY, pixelSizeX, pixelSizeY));
```

**Do not** create SAR-specific tie-point grids (latitude, longitude, incident_angle, slant_range_time).

#### 8.3.4 Annotation LUTs (L2a only)

Read NetCDF4 LUT file from `annotation/*.nc` if present. Create tie-point grids for:
- Forest/Non-Forest mask
- Local incidence angle

#### 8.3.5 Quicklooks

Same pattern as L1: look for `preview/*_ql.png`.

### 8.4 No Calibration for L2

L2 products are fully processed geophysical parameters. The `BiomassCalibrator` should not be invoked. The reader must not set `abs_calibration_flag` or create calibration-related metadata.

### 8.5 Menu Registration

Update `layer.xml` to add L2 import action, or have the existing BIOMASS import action handle both L1 and L2 (preferred, since the reader auto-detects).

---

## 9. Differences Between L1 and L2 Readers

| Aspect | L1 Reader | L2 Reader |
|--------|-----------|-----------|
| Filename prefix | `BIO_S` | `BIO_FP` |
| Product directory class | `BiomassProductDirectory` | `BiomassL2ProductDirectory` |
| Measurement format | GeoTIFF (plain) | Cloud Optimized GeoTIFF (LERC+ZSTD) |
| Band types | SAR: Amplitude, Phase, I, Q, Intensity | Geophysical: height, biomass, probability, masks |
| Virtual bands | I/Q from amp+phase, Intensity from amp | None |
| Geocoding | TiePointGeoCoding from NetCDF LUT | CrsGeoCoding from GeoTIFF geotransform |
| SAR metadata | Orbit vectors, SRGR, Doppler, PRF, etc. | None |
| Calibration | BiomassCalibrator (sigma0/beta0/gamma0) | Not applicable |
| SLC flag | true for SCS/STA, false for DGM | Always false |
| Tie-point grids | lat, lon, incidence, elevation, slant_range_time | Optional (from annotation LUT) |

---

## 10. Test Plan

### 10.1 Unit Tests

Create `TestBiomassL2ProductReader.java`:

| Test | Input | Validation |
|------|-------|------------|
| `testDecodeQualification_L2a_FH` | `BIO_FP_FH__L2A_*.xml` | Returns `INTENDED` |
| `testDecodeQualification_L2b_AGB` | `BIO_FP_AGB_L2B_*.xml` | Returns `INTENDED` |
| `testDecodeQualification_L1_unchanged` | `BIO_S1_SCS__1S_*.xml` | Still returns `INTENDED` |
| `testOpeningFile_L2a_FH` | L2a FH product | Bands: `Forest_Height`, `Forest_Height_Quality` |
| `testOpeningFile_L2a_FD` | L2a FD product | Bands: `Forest_Disturbance`, `Probability_of_Change`, `Computed_Forest_Mask` |
| `testOpeningFile_L2a_GN` | L2a GN product | Bands: `Ground_Notch_HH`, `Ground_Notch_VH`, `Ground_Notch_VV` |
| `testOpeningFile_L2b_AGB` | L2b AGB product | Bands: `AGB`, `AGB_Std_Dev` |
| `testOpeningZip_L2a` | Zipped L2a product | Same as unzipped |
| `testGeoCoding_L2` | Any L2 product | `CrsGeoCoding` with EPSG:4326, correct pixel spacing |
| `testMetadata_L2a` | L2a product | Product type, processing level, mission phase present |
| `testNoSARMetadata_L2` | Any L2 product | No orbit vectors, no SRGR, no Doppler |

### 10.2 Test Data

Test data availability:
- **Simulated:** No L2 simulated samples currently available from ESA
- **Real data:** L2a/L2b products available via MAAP with commissioning team access
- **Workaround:** Use STAC API to download metadata, create minimal synthetic COGs for structural testing

### 10.3 Integration Tests

- Open L2 product in SNAP Desktop, verify band display and geocoding
- Verify L1 products still open correctly (no regression)
- Verify calibration operator rejects L2 products gracefully

---

## 11. Implementation Phases

### Phase 1: Plugin Extension (Can start now)
- Extend `BiomassProductReaderPlugIn` to detect `BIO_FP_` prefix
- Add routing logic in `BiomassProductReader` to select L1 vs L2 directory class
- Scaffold `BiomassL2ProductDirectory` class

### Phase 2: L2a Reader (When L2a products become available)
- Implement MPH metadata parsing
- Implement COG band loading for FH, FD, GN products
- Implement CrsGeoCoding from GeoTIFF geotransform
- Implement annotation LUT reading (NetCDF)
- Write tests with real L2a products

### Phase 3: L2b Reader (When L2b products become available)
- Extend for L2b naming convention differences
- Handle L2b annotation COGs (FNF, heatmap, acquisition ID)
- Handle AGB product type
- Write tests with real L2b products

### Phase 4: Validation
- End-to-end testing in SNAP Desktop
- Verify geocoding accuracy against known locations
- Cross-validate L2 values against reference datasets
