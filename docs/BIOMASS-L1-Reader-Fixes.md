# BIOMASS L1 Reader - Bug Fixes and Improvements

**Date:** 2026-04-03
**Module:** `sar-io-gdal` and `sar-op-calibration`

---

## Summary

Code review of the BIOMASS L1 reader identified and fixed 10 issues ranging from critical (broken calibration) to medium (copy-paste remnants, logging). This document records all changes made.

## Changes

### Critical: Calibration was broken (#10, #14)

**Problem:** `BiomassCalibrator` expected `sigmaNought` and `gammaNought` tie-point grids that the reader never creates, causing NPE on any calibration attempt.

**Fix:** Rewrote calibration to read per-polarization absolute calibration constants from band metadata and use the incidence angle tie-point grid for beta0/gamma0 conversion. Falls back to TPGs if they exist for forward compatibility.

**Files:** `BiomassCalibrator.java`

### High: NetCDF 4D array handling (#8)

**Problem:** NetCDF LUT variables assumed 2D (dimension 0 = height, dimension 1 = width). Variables with rank > 2 would use wrong dimensions.

**Fix:** Use last two dimensions for spatial grid. For rank > 2, read only a 2D slice using `variable.read(origin, shape).reduce()`.

**File:** `BiomassProductDirectory.java`

### High: NPE in getDecodeQualification (#4)

**Problem:** `path.toFile().listFiles()` can return null on IO error, causing NPE in the for-each loop.

**Fix:** Added null check with early return of `UNABLE`.

**File:** `BiomassProductReaderPlugIn.java`

### High: ZipFile resource leak (#5)

**Problem:** `ZipFile` opened in `findInZip()` was never closed.

**Fix:** Changed to try-with-resources. Also fixed raw `Optional` type to `Optional<? extends ZipEntry>`.

**File:** `BiomassProductDirectory.java`

### High: cleanMetadata destroyed attribute values (#18)

**Problem:** `cleanMetadata()` replaced all non-alphanumeric characters in both metadata names AND ASCII values. This corrupted timestamps, file paths, version strings, and scientific values.

**Fix:** Removed the value-cleaning code. Only element/attribute names are sanitized.

**File:** `BiomassProductDirectory.java`

### High: Height grid unit wrong (#20)

**Problem:** The "height" tie-point grid was assigned `Unit.DEGREES`. It should be `Unit.METERS`.

**Fix:** Conditional unit assignment based on variable name.

**File:** `BiomassProductDirectory.java`

### Medium: Sentinel-1 copy-paste remnants (#1, #2)

**Problem:** Javadocs and error messages referenced "Sentinel1" instead of "BIOMASS". SafeManifest.java defaulted mission name to "Sentinel-1" and checked for "ENVISAT".

**Fix:** Updated all references. Deleted `SafeManifest.java` entirely (confirmed dead code - never imported or referenced anywhere).

**Files:** `BiomassProductReader.java`, `BiomassProductReaderPlugIn.java`, `BiomassCalibrator.java`, `SafeManifest.java` (deleted)

### Medium: System.out.println instead of logger (#3)

**Problem:** Two error handlers in `addTiePointGrids()` used `System.out.println` instead of the SNAP logger.

**Fix:** Changed to `SystemUtils.LOG.warning()`.

**File:** `BiomassProductDirectory.java`

### Medium: S1 thermal noise dependency in calibrator (#9)

**Problem:** `BiomassCalibrator` imported and used `Sentinel1RemoveThermalNoiseOp.trgFloorValue` for a noise floor workaround that is not applicable to BIOMASS.

**Fix:** Removed the dependency, import, and the floor-value retry loop.

**File:** `BiomassCalibrator.java`

### Medium: Navigation folder path for L1C (#19)

**Problem:** `readOrbitStateVectors()` hardcoded `"annotation/navigation"` as the first path to check. For L1C products using `annotation_primary`, this path would not exist, and the method would only try `annotation_coregistered/navigation` as fallback.

**Fix:** Uses the `annotationName` field (which is already set to `"annotation"` or `"annotation_primary"` earlier in the flow) as the first path to check, then falls back to `annotation_coregistered/navigation` and `annotation/navigation`.

**File:** `BiomassProductDirectory.java`

## Remaining Issues (Low Priority)

| Issue | Description |
|-------|-------------|
| No L2 product support | See `BIOMASS-L2-Reader-Spec.md` |
| Commented-out noise/RFI metadata | Lines 672-676 have stubs for calibration/noise/RFI metadata extraction |
| Pre-launch test data only | Tests use simulated 2017 data; should add real post-launch products |
| Monitoring products undocumented | `__1M` products skip band reading without explanation |
| Multi-swath band naming | Swath prefix commented out in band suffix (line 209) |
| findInZip prefix filter fragile | `startsWith(prefix)` on full zip entry paths may miss nested entries |
