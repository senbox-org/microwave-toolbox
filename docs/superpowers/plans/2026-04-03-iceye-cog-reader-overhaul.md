# ICEYE COG Reader Overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the broken ICEYE COG reader (AML/CPX formats) by replacing ImageIO-based raster reading with GDAL-based reading (matching the BIOMASS reader pattern), fix bugs in the legacy GRD reader, and update the plugin to handle new ICEYE naming conventions.

**Architecture:** The AML/CPX reader (`IceyeAMLCPXProductReader`) currently has a fatal bug -- the `TIFFImageReader` is never initialized (lines 77-78 commented out), making it completely non-functional. Rather than re-enabling ImageIO, we will switch to GDAL's `GTiffDriverProductReaderPlugIn` for raster reading, delegating band data access through source images on the GDAL-opened product (the same pattern used by `BiomassProductDirectory`). Metadata extraction from the GDAL_METADATA TIFF tag will still use ImageIO for tag access, but raster I/O will be GDAL-only. The GRD reader already uses `GeoTiffProductReaderPlugIn` for bands but has its own issues (dead code, unused bandMap, NPE risks).

**Tech Stack:** Java, SNAP Engine, GDAL (via `snap-dataio-gdal`), ImageIO TIFF plugin (metadata only), json-simple

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `sar-io-gdal/.../iceye/IceyeAMLCPXProductReader.java` | **Major rewrite** | Base class for AML/CPX: metadata extraction from TIFF GDAL_METADATA tag, GDAL-based band loading, geocoding |
| `sar-io-gdal/.../iceye/IceyeAMLProductReader.java` | Minor update | AML-specific bands/metadata -- update to use GDAL band source images |
| `sar-io-gdal/.../iceye/IceyeCPXProductReader.java` | Minor update | CPX-specific bands/metadata -- update virtual band expressions for polarization |
| `sar-io-gdal/.../iceye/IceyeGRDProductReader.java` | Fix bugs | Legacy GRD reader -- remove dead code, fix readBandRasterDataImpl |
| `sar-io-gdal/.../iceye/IceyeCOGReader.java` | Minor update | Dispatcher -- fix readBandRasterDataImpl delegation for GDAL pattern |
| `sar-io-gdal/.../iceye/IceyeCOGReaderPlugIn.java` | Update | Support new ICEYE naming convention (geohash format) |
| `sar-io-gdal/.../iceye/IceyeConstants.java` | No change | Constants -- already complete |

---

### Task 1: Fix IceyeAMLCPXProductReader -- Replace ImageIO raster reading with GDAL

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeAMLCPXProductReader.java`

This is the core fix. The `imageReader` (TIFFImageReader) is never initialized because lines 77-78 are commented out. Instead of re-enabling ImageIO for raster data, we switch to GDAL's GTiffDriverProductReaderPlugIn. Metadata will still be extracted from the TIFF GDAL_METADATA tag using ImageIO (read-only, just for tag access), but band data comes from GDAL.

- [ ] **Step 1: Add GDAL reader fields and imports**

Replace the ImageIO raster-reading infrastructure with GDAL product fields:

```java
// At top of class, add:
import org.esa.snap.dataio.gdal.reader.plugins.GTiffDriverProductReaderPlugIn;
import org.esa.snap.core.dataio.ProductReader;

// Replace field:
//   private TIFFImageReader imageReader = null;
// With:
private static final GTiffDriverProductReaderPlugIn gdalReaderPlugIn = new GTiffDriverProductReaderPlugIn();
private Product bandProduct = null;
```

- [ ] **Step 2: Rewrite readProductNodesImpl to use GDAL for band data**

In `readProductNodesImpl()`, after extracting metadata from the TIFF tag via ImageIO, open the file a second time via GDAL for band data:

Replace lines 76-80 (the commented-out imageReader initialization and NPE-causing call) with:

```java
// Use ImageIO only for metadata extraction from TIFF tags
inputStream = ImageIO.createImageInputStream(inputFile);
Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(inputStream);
TIFFImageReader tiffReader = null;
while (readers.hasNext()) {
    javax.imageio.ImageReader r = readers.next();
    if (r instanceof TIFFImageReader) {
        tiffReader = (TIFFImageReader) r;
        tiffReader.setInput(inputStream, false);
        break;
    }
}
if (tiffReader == null) {
    close();
    throw new IllegalFileFormatException("TIFF reader not found.");
}

TIFFImageMetadata tiffMetadata = (TIFFImageMetadata) tiffReader.getImageMetadata(0);
```

After the product is created and metadata added, open via GDAL for band data:

```java
// Open via GDAL for raster data access
ProductReader gdalReader = gdalReaderPlugIn.createReaderInstance();
bandProduct = gdalReader.readProductNodes(inputFile, null);
```

- [ ] **Step 3: Update addBandsToProduct to use GDAL source images**

In the base class `addBandsToProduct()`, after creating the amplitude band, set its source image from the GDAL-opened product:

```java
void addBandsToProduct(Product product) {
    String polarization = (String) ((JSONArray) getFromJSON(IceyeConstants.polarization)).get(0);
    String bandName = IceyeConstants.amplitude_band_prefix + polarization;

    // Get the amplitude band from GDAL product
    Band gdalBand = bandProduct.getBandAt(IceyeConstants.AMPLITUDE_BAND_INDEX);

    final Band ampBand = new Band(bandName, gdalBand.getDataType(), imageWidth, imageHeight);
    ampBand.setUnit(Unit.AMPLITUDE);
    ampBand.setNoDataValue(0);
    ampBand.setNoDataValueUsed(true);
    ampBand.setSourceImage(gdalBand.getSourceImage());
    product.addBand(ampBand);
    bandMap.put(ampBand, IceyeConstants.AMPLITUDE_BAND_INDEX);

    addProductSpecificBands(product, polarization);
}
```

- [ ] **Step 4: Make readBandRasterDataImpl empty (GDAL handles via source images)**

Since bands now get their data from GDAL source images, the `readBandRasterDataImpl` method should be empty (same pattern as `BiomassProductReader`):

```java
@Override
public void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY,
        int sourceWidth, int sourceHeight,
        int sourceStepX, int sourceStepY,
        Band destBand,
        int destOffsetX, int destOffsetY,
        int destWidth, int destHeight,
        ProductData destBuffer, ProgressMonitor pm) throws IOException {
}
```

- [ ] **Step 5: Fix close() to clean up GDAL resources and handle nulls**

```java
@Override
public synchronized void close() throws IOException {
    if (bandProduct != null) {
        bandProduct.dispose();
        bandProduct = null;
    }
    if (inputStream != null) {
        inputStream.close();
        inputStream = null;
    }
    super.close();
}
```

- [ ] **Step 6: Remove unused imageReader field and getRasterValue/readRect methods**

Remove:
- `private TIFFImageReader imageReader = null;` field
- `private synchronized Raster readRect(...)` method
- `abstract float getRasterValue(...)` method (and implementations in subclasses)
- The commented-out `GeoTiffUtils` import
- The `import javax.imageio.ImageReadParam` (no longer needed for raster)
- The `import java.awt.image.DataBuffer`, `Raster`, `RenderedImage` imports (no longer needed)

---

### Task 2: Update IceyeCPXProductReader for GDAL-based band loading

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeCPXProductReader.java`

The CPX TIFF has 2 bands: amplitude (band 0) and phase (band 1). With GDAL, both bands are accessible from `bandProduct`.

- [ ] **Step 1: Update addProductSpecificBands to use GDAL source image for phase band**

```java
void addProductSpecificBands(Product product, String polarization) {
    // Phase band from GDAL (band index 1)
    Band gdalPhaseBand = bandProduct.getBandAt(IceyeConstants.PHASE_BAND_INDEX);

    final Band phaseBand = new Band(IceyeConstants.phase_band_prefix + polarization,
            gdalPhaseBand.getDataType(), imageWidth, imageHeight);
    phaseBand.setUnit(Unit.PHASE);
    phaseBand.setNoDataValue(99999.0);
    phaseBand.setNoDataValueUsed(true);
    phaseBand.setSourceImage(gdalPhaseBand.getSourceImage());
    product.addBand(phaseBand);
    bandMap.put(phaseBand, IceyeConstants.PHASE_BAND_INDEX);

    // Virtual I band
    String ampBandName = IceyeConstants.amplitude_band_prefix + polarization;
    String phsBandName = IceyeConstants.phase_band_prefix + polarization;

    final Band iBand = new VirtualBand(IceyeConstants.i_band_prefix + polarization,
            ProductData.TYPE_FLOAT32, imageWidth, imageHeight,
            ampBandName + " * cos(" + phsBandName + ")");
    iBand.setUnit(Unit.REAL);
    iBand.setNoDataValue(0);
    iBand.setNoDataValueUsed(true);
    product.addBand(iBand);

    // Virtual Q band
    final Band qBand = new VirtualBand(IceyeConstants.q_band_prefix + polarization,
            ProductData.TYPE_FLOAT32, imageWidth, imageHeight,
            ampBandName + " * sin(" + phsBandName + ")");
    qBand.setUnit(Unit.IMAGINARY);
    qBand.setNoDataValue(0);
    qBand.setNoDataValueUsed(true);
    product.addBand(qBand);

    ReaderUtils.createVirtualIntensityBand(product, iBand, qBand, "_" + polarization);
}
```

Key changes:
- Phase band gets `sourceImage` from GDAL (no manual raster reading)
- Virtual I/Q band expressions use the actual polarization (not hardcoded `VV`)
- Removed `bandMap.put` for virtual bands (they don't need raster reading)

- [ ] **Step 2: Remove getRasterValue override**

Delete the `getRasterValue` method entirely (no longer abstract, no longer needed).

- [ ] **Step 3: Provide access to bandProduct from subclass**

In `IceyeAMLCPXProductReader`, make `bandProduct` accessible to subclasses:

```java
// Change from private to protected:
protected Product bandProduct = null;
```

---

### Task 3: Update IceyeAMLProductReader for GDAL pattern

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeAMLProductReader.java`

- [ ] **Step 1: Remove getRasterValue override**

Delete the `getRasterValue` method.

- [ ] **Step 2: No other changes needed**

The `addProductSpecificBands` method creates a virtual intensity band from the amplitude band, which is correct. The amplitude band already gets its source image in the base class.

---

### Task 4: Update IceyeCOGReader dispatcher

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeCOGReader.java`

- [ ] **Step 1: Simplify readBandRasterDataImpl**

Since AML/CPX bands now use GDAL source images (readBandRasterDataImpl is empty), the dispatcher no longer needs to delegate raster reading for new-format products. Only GRD still needs delegation:

```java
@Override
protected void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
        int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
        int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
        ProgressMonitor pm) throws IOException {
    // AML/CPX: bands use GDAL source images, no manual reading needed
    // GRD: bands use copyBand with source images from GeoTiffProductReader, also no manual reading needed
}
```

Since the GRD reader uses `ProductUtils.copyBand(..., true)` which copies the source image, `readBandRasterDataImpl` can actually be empty for all cases. The `callReadBandRasterData` path in the GRD reader is dead code because the GRD reader's `addBandsToProduct()` already copies bands with source images.

- [ ] **Step 2: Remove isTiff and isNewFormat flags**

These flags are only used for readBandRasterDataImpl delegation, which is now empty:

```java
// Remove:
//   private AtomicBoolean isTiff = new AtomicBoolean();
//   private AtomicBoolean isNewFormat = new AtomicBoolean();
// And remove all .set() calls to them
```

---

### Task 5: Fix IceyeGRDProductReader bugs and dead code

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeGRDProductReader.java`

- [ ] **Step 1: Remove dead bandImageFileMap and bandMap code**

The `bandImageFileMap` and `bandMap` fields are never populated (the code that would populate them is commented out at line 613-614). The `readBandRasterDataImpl` method tries to use `bandMap` but it's always empty, meaning bands would never be read. However, the bands are actually created via `ProductUtils.copyBand(..., true)` which copies the source image from `bandProduct` -- so the ImageIOFile-based reading path is dead code.

Remove:
- `protected transient final Map<String, ImageIOFile> bandImageFileMap` field
- `protected transient final Map<Band, ImageIOFile.BandInfo> bandMap` field
- The entire `readBandRasterDataImpl` method (make it empty -- bands use source images)
- The `readAscendingRasterBand` method
- The `readDescendingRasterBand` method
- The `callReadBandRasterData` method
- Related imports: `ImageIOFile`, `ImageReadParam`, `ImageReader`, `ImageInputStream`, `ImageIO`, `Raster`, `RenderedImage`, `SampleModel`, `DataBuffer`
- The commented-out `GeoTiffUtils` import
- The `InputStream`/`BufferedInputStream`/`FileInputStream` imports (from addBandsToProduct)

- [ ] **Step 2: Clean up addBandsToProduct**

Remove the unnecessary `InputStream` wrapping. The `geoTiffPlugIn.createReaderInstance()` and `ProductUtils.copyBand()` are already correct:

```java
private void addBandsToProduct() {
    try {
        final File inputFile = getPathFromInput(getInput()).toFile();

        ProductReader reader = geoTiffPlugIn.createReaderInstance();
        bandProduct = reader.readProductNodes(inputFile, null);

        int cnt = 1;
        boolean multiband = bandProduct.getNumBands() > 1;
        for (Band tifBand : bandProduct.getBands()) {
            String polarization = get(IceyeConstants.MDS1_TX_RX_POLAR);
            String suffix = '_' + polarization;
            if (multiband) {
                suffix += cnt;
            }
            String trgBandName = "Amplitude" + suffix;

            Band trgBand = ProductUtils.copyBand(tifBand.getName(), bandProduct, trgBandName, product, true);
            trgBand.setUnit(Unit.AMPLITUDE);
            trgBand.setNoDataValue(0);
            trgBand.setNoDataValueUsed(true);

            SARReader.createVirtualIntensityBand(product, trgBand, suffix);
            ++cnt;
        }
    } catch (IOException e) {
        SystemUtils.LOG.severe(e.getMessage());
    }
}
```

- [ ] **Step 3: Fix close() to also close bandImageFileMap iteration (remove it)**

The close method iterates `bandImageFileMap` which is always empty. Simplify:

```java
@Override
public void close() throws IOException {
    if (bandProduct != null) {
        bandProduct.dispose();
        bandProduct = null;
    }
    product = null;
    tiffFields.clear();
    super.close();
}
```

- [ ] **Step 4: Fix `get()` method -- it throws IOException for missing optional tags**

The `get()` method throws `IOException` when a tag is not found, but many callers expect null for optional fields (checking `if(get(X) != null)`). This causes the method to throw before returning null. Fix:

```java
private String get(final String tag) {
    if (tiffFields != null && tiffFields.containsKey(tag.toUpperCase())) {
        return tiffFields.get(tag.toUpperCase());
    }
    return null;
}
```

Then update callers that depend on the exception to explicitly check for null:
- Line 207: `get(IceyeConstants.PRODUCT_TYPE)` -- add null check
- Lines 208-211: `get(NUM_SAMPLES/NUM_OUTPUT_LINES)` -- add null check, throw if null
- All metadata setAttribute calls already handle null via try-catch

---

### Task 6: Update IceyeCOGReaderPlugIn for new ICEYE naming convention

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeCOGReaderPlugIn.java`

ICEYE changed their naming convention in 2025. Old format: `ICEYE_X6_GRD_SM_153426_...`. New format: `ICEYE_75CMBF_20240612T174339Z_4119892_X11_SLF_GRD.tif`. Both start with `ICEYE_` so the current prefix check works, but:

- [ ] **Step 1: Add `.tif` (lowercase) to extensions**

The current `FILE_EXTS` only has uppercase `.TIF`. While the `checkProductQualification` uppercases the filename, `getDefaultFileExtensions` returns the extensions as-is for display and file filter:

```java
private final String[] FILE_EXTS = { ".tif", ".xml", ".json" };
```

- [ ] **Step 2: Verify both old and new naming work**

Both `ICEYE_X6_GRD_SM_...tif` and `ICEYE_75CMBF_...tif` start with `ICEYE` when uppercased. The constant is `ICEYE_FILE_PREFIX = "ICEYE"` (no underscore), so both match. No code change needed -- just verify.

---

### Task 7: Add ICEYE COG to layer.xml menu registration

**Files:**
- Modify: `sar-io-gdal/src/main/resources/eu/esa/sar/iogdal/layer.xml`

- [ ] **Step 1: Add ICEYE COG import action**

Add after the existing BIOMASS entry:

```xml
<file name="eu-esa-microwavetbx-sar-io-gdal-iceye-import.instance">
    <attr name="instanceCreate" methodvalue="org.openide.awt.Actions.alwaysEnabled"/>
    <attr name="delegate" methodvalue="org.esa.snap.rcp.actions.file.ImportProductAction.create"/>
    <attr name="displayName" stringvalue="ICEYE COG"/>
    <attr name="formatName" stringvalue="ICEYE COG"/>
    <attr name="useAllFileFilter" boolvalue="true"/>
    <attr name="helpId" stringvalue="importIceyeCOGProduct"/>
    <attr name="ShortDescription" stringvalue="Open ICEYE COG data product."/>
    <attr name="position" intvalue="280"/>
</file>
```

---

### Task 8: Final cleanup and NPE safety

**Files:**
- Modify: `sar-io-gdal/src/main/java/eu/esa/sar/iogdal/iceye/IceyeAMLCPXProductReader.java`

- [ ] **Step 1: Fix NPE in getQuicklookFile**

`dir.listFiles()` can return null:

```java
private File getQuicklookFile(File inputFile) {
    File dir = inputFile.getParentFile();
    File[] files = dir.listFiles();
    if (files == null) return null;
    for (String suffix : new String[] { IceyeConstants.qlk_png, IceyeConstants.thm_png }) {
        for (File f : files) {
            if (f.getName().toLowerCase().endsWith(suffix))
                return f;
        }
    }
    return null;
}
```

- [ ] **Step 2: Remove `product.getGcpGroup()` no-op call**

Line 128 calls `product.getGcpGroup()` with no purpose (result not used). Remove it.

- [ ] **Step 3: Fix error message typos**

Line 335: `"Unable to parse UTC from metadata"` in `addMetaString` -- should say "string"
Line 344: Same wrong message in `addMetaLong`
Line 354: `"Unable to parse doube"` -- should be "double"

---

## Summary of Issues Fixed

| # | Severity | Issue | Fix |
|---|----------|-------|-----|
| 1 | **Critical** | `imageReader` never initialized in AML/CPX reader -- NPE on any use | Replace with GDAL-based band loading |
| 2 | **Critical** | `readBandRasterDataImpl` in AML/CPX tries to use null imageReader | Made empty -- GDAL source images handle data |
| 3 | **High** | GRD `bandMap` never populated -- readBandRasterDataImpl silently reads nothing | Removed dead code; bands already work via copyBand source images |
| 4 | **High** | GRD `get()` throws IOException for missing optional tags, breaking null checks | Returns null instead of throwing |
| 5 | **High** | CPX virtual band expressions hardcode `VV` instead of using actual polarization | Use polarization variable in expressions |
| 6 | **Medium** | `close()` NPE if inputStream is null | Added null check |
| 7 | **Medium** | `getQuicklookFile` NPE if listFiles returns null | Added null check |
| 8 | **Medium** | ICEYE COG not registered in layer.xml menu | Added menu entry |
| 9 | **Low** | File extensions uppercase only (`.TIF`) | Changed to lowercase |
| 10 | **Low** | Error message typos | Fixed |
| 11 | **Low** | Pointless `product.getGcpGroup()` call | Removed |
