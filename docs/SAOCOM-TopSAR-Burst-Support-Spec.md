# SAOCOM TopSAR Burst Support Specification

**Version:** 1.0
**Date:** 2026-04-03
**Status:** Draft
**Module:** `sar-io`, `sar-op-sar-processing`

---

## 1. Overview

SAOCOM TopSAR (TOPS) products — TopSAR Narrow, TopSAR Wide, and TopSAR Full modes — use the same burst-mode acquisition as Sentinel-1 IW/EW. The current reader treats TopSAR SLC products as continuous images without burst delineation. This spec describes what is needed for proper burst-level support.

### 1.1 Current State

The `SaocomProductDirectory` handles TopSAR products by:
- Detecting mode from metadata (`acqMode` contains `TOPSAR`)
- Creating per-swath bands with `_s1`, `_s2`, etc. suffixes
- Reading the data as continuous rasters from per-swath GeoTIFF files

**What's missing:**
- Burst boundary metadata (burst start/stop lines, valid sample ranges)
- Burst-level azimuth time annotation
- Debursting support (merging bursts into a seamless image)
- Burst-level InSAR coregistration support

### 1.2 Why It Matters

Without burst delineation:
- The overlap regions between bursts produce artifacts in the continuous image
- InSAR processing cannot properly align bursts between passes
- Phase discontinuities at burst boundaries are not handled
- The SNAP S-1 TOPS coregistration/debursting operators cannot be used

---

## 2. SAOCOM TopSAR Product Structure

### 2.1 Acquisition Modes

| Mode | Sub-swaths | Swath Width | Resolution |
|------|-----------|-------------|------------|
| TopSAR Narrow (TN) | 4 | ~135 km | ~30 m |
| TopSAR Wide (TW) | 5-6 | ~200 km | ~50 m |
| TopSAR Full (TF) | 6-7 | ~320-350 km | ~100 m |

### 2.2 SLC Product Files

Each TopSAR SLC product contains one GeoTIFF per polarization per sub-swath:
```
Data/
  slc-s1-HH-<datetime>.tif    # Sub-swath 1, HH pol
  slc-s1-HV-<datetime>.tif    # Sub-swath 1, HV pol
  slc-s2-HH-<datetime>.tif    # Sub-swath 2, HH pol
  ...
```

### 2.3 Burst Metadata

The per-channel XML files in the `Data/` directory contain burst information in the `BurstInfo` element:

```xml
<BurstInfo NumberOfBursts="N">
  <Burst>
    <BurstNumber>1</BurstNumber>
    <RangeStartTime unit="s">...</RangeStartTime>
    <AzimuthStartTime unit="s">...</AzimuthStartTime>
    <Lines>
      <FirstLine>0</FirstLine>
      <LastLine>1503</LastLine>
    </Lines>
    <Samples>
      <FirstValidSample>123</FirstValidSample>
      <LastValidSample>24567</LastValidSample>
    </Samples>
  </Burst>
  ...
</BurstInfo>
```

Additionally, `AcquisitionTimeLine` contains:
- `Swst_changes_values` — Sampling Window Start Time changes per burst
- `Swst_changes_azimuthtimes` — Azimuth times for SWST changes

---

## 3. Required Changes

### 3.1 Reader Changes (`SaocomProductDirectory`)

#### 3.1.1 Parse Burst Metadata

Extract from each channel XML:
- Number of bursts per sub-swath
- Per-burst: first/last valid line, first/last valid sample
- Per-burst: azimuth start time, range start time
- SWST (Sampling Window Start Time) changes

Store in the product metadata under a structure compatible with what the TOPS processing operators expect:
```
Abstracted_Metadata/
  [band_prefix]/
    burst_count: N
    burst_1/
      first_line: 0
      last_line: 1503
      first_valid_sample: 123
      last_valid_sample: 24567
      azimuth_start_time: UTC
      byte_offset: 0
    burst_2/
      ...
```

#### 3.1.2 Band Organization

Currently bands are created as `i_s1_HH`, `q_s1_HH`, etc. This is correct. The burst metadata should be associated with the band's metadata element.

### 3.2 Debursting Operator

Two approaches:

**Option A: Extend the Sentinel-1 TOPS Deburst operator**
- The `S1TBXDeburstOp` in `sar-op-sentinel1` currently validates for Sentinel-1 only
- Generalize the mission check to also accept SAOCOM
- The burst structure is architecturally the same (TOPS beam steering)
- The metadata paths may differ — adapt to read SAOCOM burst metadata

**Option B: Create a SAOCOM-specific deburst operator**
- More isolated change, no risk of breaking Sentinel-1 processing
- More code duplication
- Easier to maintain independently

**Recommendation:** Option A (extend S-1 operator) since the physics and geometry are identical.

### 3.3 TOPS Coregistration

The `S1BackGeocodingOp` and related operators use burst boundaries for precise sub-pixel coregistration. These would need to:
- Accept SAOCOM products
- Read burst metadata from SAOCOM metadata structure
- Handle the slightly different timing and orbit formats

### 3.4 InSAR Support

For TopSAR InSAR:
- Spectral diversity (SD) or Enhanced Spectral Diversity (ESD) estimation requires burst overlap regions
- The overlap region metadata (number of overlap lines) must be computed from burst timing

---

## 4. Implementation Phases

### Phase 1: Burst Metadata Reading (Low effort)
- Parse `BurstInfo` elements from channel XMLs
- Store per-burst metadata in product metadata tree
- No processing changes needed

### Phase 2: Debursting (Medium effort)
- Extend or create deburst operator for SAOCOM TopSAR
- Merge bursts into seamless sub-swath images
- Handle valid sample masking

### Phase 3: TOPS Coregistration (High effort)
- Adapt S-1 back-geocoding for SAOCOM orbit/timing
- Implement or adapt ESD for SAOCOM burst overlaps
- Validate with real InSAR pairs

### Phase 4: Full InSAR Chain (High effort)
- End-to-end InSAR processing with SAOCOM TopSAR
- Interferogram generation, coherence estimation
- Phase unwrapping

---

## 5. Noise Removal

### Current Status

The Sentinel-1 thermal noise removal operator (`Sentinel1RemoveThermalNoiseOp`) explicitly validates that the product is Sentinel-1 and rejects all other missions. It reads noise vectors from Sentinel-1's specific metadata path (`Original_Product_Metadata/noise/`).

### SAOCOM Noise Data

SAOCOM products include `noiseLut_<pol>.xml` files in the `Calibration/` directory. These contain NESZ (Noise Equivalent Sigma Zero) as a function of range sample, analogous to Sentinel-1 noise vectors.

### Recommended Approach

Rather than modifying the Sentinel-1 noise operator (which is tightly coupled to S-1 metadata):

1. **Create a generic `RemoveThermalNoiseOp`** (or `SaocomRemoveThermalNoiseOp`) that:
   - Reads SAOCOM `noiseLut_*.xml` files
   - Interpolates NESZ to image dimensions
   - Subtracts noise from pixel values (in linear power domain)
   - Handles per-polarization noise profiles
   
2. **Or generalize the existing operator** by:
   - Extracting the mission-independent noise subtraction logic
   - Adding a `NoiseProvider` interface with S-1 and SAOCOM implementations
   - This is cleaner but higher risk for regression

**Recommendation for first pass:** Create a standalone `SaocomRemoveThermalNoiseOp` that reads the noise LUT XMLs and applies the subtraction. Can be unified with S-1 later when the pattern stabilizes.

---

## 6. Dependencies

- Real SAOCOM TopSAR SLC products for testing
- Access to burst metadata examples to verify XML parsing
- SAOCOM InSAR pairs for coregistration validation
- CONAE documentation for burst timing specifications
