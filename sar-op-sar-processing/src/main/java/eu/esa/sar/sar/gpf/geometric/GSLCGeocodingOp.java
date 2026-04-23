/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.CRSGeoCodingHandler;
import eu.esa.sar.commons.OrbitStateVectors;
import eu.esa.sar.commons.SARGeocoding;
import eu.esa.sar.commons.SARUtils;
import eu.esa.sar.commons.Sentinel1Utils;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.PosVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.eo.GeoUtils;
import org.esa.snap.engine_utilities.eo.LocalGeometry;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileGeoreferencing;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Geocoded Single Look Complex (GSLC) Generator.
 * <p>
 * This operator performs Geocoded Single Look Complex (GSLC) generation by
 * transforming SAR data from slant-range geometry to a map projection while
 * preserving phase information.
 * <p>
 * Key features:
 * 1. Precise Orbit Ephemerides and High-Resolution DEM usage.
 * 2. Phase Flattening (Carrier Phase Compensation) before resampling.
 * 3. High-Fidelity Complex Resampling (e.g., truncated Sinc).
 * 4. Phase Restoration (optional).
 */
@OperatorMetadata(alias = "GSLC-Terrain-Correction",
        category = "Radar/Geometric/Terrain Correction",
        authors = "Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Geocoded Single Look Complex (GSLC) generation with phase preservation")
public class GSLCGeocodingOp extends Operator {

    @SourceProduct(alias = "source")
    Product sourceProduct;
    @TargetProduct
    Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames = null;

    @Parameter(description = "The digital elevation model.",
            defaultValue = "Copernicus 30m Global DEM", label = "Digital Elevation Model")
    private String demName = "Copernicus 30m Global DEM";

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "External DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(label = "External DEM Apply EGM", defaultValue = "true")
    private Boolean externalDEMApplyEGM = true;

    @Parameter(defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME, label = "DEM Resampling Method",
            valueSet = {
                    ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
                    ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
                    ResamplingFactory.CUBIC_CONVOLUTION_NAME,
                    ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_11_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BICUBIC_INTERPOLATION_NAME,
                    DEMFactory.DELAUNAY_INTERPOLATION
            })
    private String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(defaultValue = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME, label = "Image Resampling Method",
            valueSet = {
                    ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
                    ResamplingFactory.BILINEAR_INTERPOLATION_NAME,
                    ResamplingFactory.CUBIC_CONVOLUTION_NAME,
                    ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_11_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BISINC_21_POINT_INTERPOLATION_NAME,
                    ResamplingFactory.BICUBIC_INTERPOLATION_NAME
            })
    private String imgResamplingMethod = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    @Parameter(description = "The pixel spacing in meters", defaultValue = "0", label = "Pixel Spacing (m)")
    private double pixelSpacingInMeter = 0;

    @Parameter(description = "The pixel spacing in degrees", defaultValue = "0", label = "Pixel Spacing (deg)")
    private double pixelSpacingInDegree = 0;

    @Parameter(description = "The pixel spacing oversampling percentage (0-100%)", defaultValue = "0.0", label = "Oversampling (%)")
    private double oversamplingPercent = 0.0;

    @Parameter(description = "The coordinate reference system in well known text format", defaultValue = "WGS84(DD)")
    private String mapProjection = "WGS84(DD)";

    @Parameter(description = "Force the image grid to be aligned with a specific point", defaultValue = "false")
    private boolean alignToStandardGrid = false;

    @Parameter(description = "x-coordinate of the standard grid's origin point", defaultValue = "0")
    private double standardGridOriginX = 0;

    @Parameter(description = "y-coordinate of the standard grid's origin point", defaultValue = "0")
    private double standardGridOriginY = 0;

    @Parameter(defaultValue = "true", label = "Mask out areas with no elevation", description = "Mask the sea with no data value (faster)")
    protected boolean nodataValueAtSea = true;

    @Parameter(defaultValue = "false", label = "Save DEM as band")
    private boolean saveDEM = false;

    @Parameter(defaultValue = "false", label = "Save latitude and longitude as band")
    private boolean saveLatLon = false;

    @Parameter(defaultValue = "false", label = "Save incidence angle from ellipsoid as band")
    private boolean saveIncidenceAngleFromEllipsoid = false;

    @Parameter(defaultValue = "false", label = "Save local incidence angle as band")
    private boolean saveLocalIncidenceAngle = false;

    @Parameter(defaultValue = "false", label = "Save projected local incidence angle as band")
    private boolean saveProjectedLocalIncidenceAngle = false;

    @Parameter(defaultValue = "false", label = "Save layover shadow mask")
    private boolean saveLayoverShadowMask = false;

    @Parameter(defaultValue = "true", label = "Output flattened complex data")
    private boolean outputFlattened = true;

    @Parameter(defaultValue = "false", label = "Save simulated phase")
    private boolean saveSimulatedPhase = false;

    @Parameter(defaultValue = "false", label = "Save simulated unwrapped phase")
    private boolean saveSimulatedUnwrappedPhase = false;

    private MetadataElement absRoot = null;
    private ElevationModel dem = null;
    private Band elevationBand = null;
    private Band simulatedPhaseBand = null;
    private Band simulatedUnwrappedPhaseBand = null;
    private double demNoDataValue = 0.0f; // no data value for DEM
    private GeoCoding targetGeoCoding = null;

    private boolean srgrFlag = false;
    private boolean isElevationModelAvailable = false;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private int targetImageWidth = 0;
    private int targetImageHeight = 0;
    private int margin = 0;

    private double wavelength = 0.0; // in m
    private double rangeSpacing = 0.0;
    private double firstLineUTC = 0.0; // in days
    private double lastLineUTC = 0.0; // in days
    private double lineTimeInterval = 0.0; // in days
    private double nearEdgeSlantRange = 0.0; // in m

    private CoordinateReferenceSystem targetCRS;
    private double delLat = 0.0;
    private double delLon = 0.0;
    private OrbitStateVectors orbit = null;

    private AbstractMetadata.SRGRCoefficientList[] srgrConvParams = null;
    private OrbitStateVector[] orbitStateVectors = null;
    private TiePointGrid incidenceAngle = null;

    private Resampling imgResampling = null;

    private boolean nearRangeOnLeft = true;
    private boolean skipBistaticCorrection = false;
    private double bistaticCorrectionRefRange = 0.0;
    private String mission = null;

    // TOPS burst-level processing fields
    private boolean isTOPSProduct = false;
    private Sentinel1Utils su = null;
    private Sentinel1Utils.SubSwathInfo[] subSwath = null;
    private int subSwathIndex = 0;

    public static final String externalDEMStr = "External DEM";
    private static final String PRODUCT_SUFFIX = "_GSLC";
    private static final double lightSpeedInMetersPerSecond = 299792458.0;

    private final List<ComplexPair> complexPairs = new ArrayList<>();

    private static class ComplexPair {
        Band srcI;
        Band srcQ;
        Band tgtI;
        Band tgtQ;
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfMapProjected(false);

            if (validator.isTOPSARProduct()) {
                if (validator.isDebursted()) {
                    throw new OperatorException(
                            "Debursted TOPS SLC products are not supported for GSLC geocoding.\n" +
                            "Please use the pre-deburst split product (single subswath with burst structure).\n" +
                            "Processing chain: Apply-Orbit-File -> TOPSAR-Split -> GSLC-Terrain-Correction");
                }
                isTOPSProduct = true;
            }

            getSourceImageDimension();
            getMetadata();

            if (isTOPSProduct) {
                initTOPSData();
            }

            imgResampling = ResamplingFactory.createResampling(imgResamplingMethod);
            if (imgResampling == null) {
                throw new OperatorException("Resampling method " + imgResamplingMethod + " is invalid");
            }

            createTargetProduct();
            computeSensorPositionsAndVelocities();
            updateTargetProductMetadata();

            if (!demName.contains(externalDEMStr)) {
                DEMFactory.checkIfDEMInstalled(demName);
            }

            if (demName.contains(externalDEMStr) && externalDEMFile == null) {
                throw new OperatorException("External DEM file is not specified. ");
            }

            DEMFactory.validateDEM(demName, sourceProduct);

            margin = getMargin();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() throws OperatorException {
        if (dem != null) {
            dem.dispose();
        }
    }

    private void getMetadata() throws Exception {
        absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

        mission = RangeDopplerGeocodingOp.getMissionType(absRoot);
        skipBistaticCorrection = absRoot.getAttributeInt(AbstractMetadata.bistatic_correction_applied, 0) == 1;
        if (skipBistaticCorrection && mission != null && mission.startsWith("SENTINEL-1")) {
            bistaticCorrectionRefRange = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel);
        }
        srgrFlag = AbstractMetadata.getAttributeBoolean(absRoot, AbstractMetadata.srgr_flag);

        // SLC products are always in slant range geometry regardless of what the metadata says.
        // The srgr_flag can be incorrectly set to true in some product readers.
        final String sampleType = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE);
        if (sampleType != null && sampleType.contains("COMPLEX")) {
            if (srgrFlag) {
                SystemUtils.LOG.warning("GSLC: Overriding srgr_flag to false for SLC product");
                srgrFlag = false;
            }
        }
        
        // Robust wavelength calculation
        double freq = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.radar_frequency);
        if (freq < 1.0e8) { // Assume MHz if less than 100 MHz
             freq *= 1.0e6;
        }
        wavelength = lightSpeedInMetersPerSecond / freq;

        rangeSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
        if (rangeSpacing <= 0.0) {
            throw new OperatorException("Invalid input for range pixel spacing: " + rangeSpacing);
        }
        // Check if rangeSpacing is in seconds (e.g. < 0.001)
        if (rangeSpacing < 0.001) {
            rangeSpacing *= (lightSpeedInMetersPerSecond / 2.0);
            SystemUtils.LOG.info("GSLC: Converted rangeSpacing from seconds to meters: " + rangeSpacing);
        }

        firstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        lastLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.last_line_time)).getMJD(); // in days
        lineTimeInterval = (lastLineUTC - firstLineUTC) / (sourceImageHeight - 1); // in days
        if (lineTimeInterval == 0.0) {
            throw new OperatorException("Invalid input for Line Time Interval: " + lineTimeInterval);
        }

        orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        if (orbitStateVectors == null || orbitStateVectors.length == 0) {
            throw new OperatorException("Invalid Orbit State Vectors");
        }

        // Always read near-edge slant range (needed for SLC range index and TOPS processing)
        nearEdgeSlantRange = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel);
        if (nearEdgeSlantRange < 10000.0) {
            nearEdgeSlantRange *= (lightSpeedInMetersPerSecond / 2.0);
            SystemUtils.LOG.info("GSLC: Converted nearEdgeSlantRange from seconds to meters: " + nearEdgeSlantRange);
        }

        if (srgrFlag) {
            srgrConvParams = AbstractMetadata.getSRGRCoefficients(absRoot);
            if (srgrConvParams == null || srgrConvParams.length == 0) {
                throw new OperatorException("Invalid SRGR Coefficients");
            }
        }

        incidenceAngle = OperatorUtils.getIncidenceAngle(sourceProduct);
        nearRangeOnLeft = SARGeocoding.isNearRangeOnLeft(incidenceAngle, sourceImageWidth);
    }

    private void initTOPSData() throws Exception {
        su = new Sentinel1Utils(sourceProduct);
        subSwath = su.getSubSwath();
        su.computeDopplerRate();
        su.computeReferenceTime();

        final String[] subSwathNames = su.getSubSwathNames();
        if (subSwathNames.length != 1) {
            throw new OperatorException(
                    "GSLC for TOPS requires a split product with a single subswath. " +
                    "Please apply TOPSAR-Split first.");
        }
        subSwathIndex = 1; // always 1 for split product

        SystemUtils.LOG.info("GSLC: TOPS mode enabled for " + subSwathNames[0] +
                ", bursts=" + subSwath[0].numOfBursts +
                ", linesPerBurst=" + subSwath[0].linesPerBurst);
    }

    private synchronized void getElevationModel() throws Exception {
        if (isElevationModelAvailable) return;
        if (demName.contains(externalDEMStr) && externalDEMFile != null) {
            dem = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            ((FileElevationModel) dem).applyEarthGravitionalModel(externalDEMApplyEGM);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getName();
        } else {
            dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
            demNoDataValue = dem.getDescriptor().getNoDataValue();
        }

        if (elevationBand != null) {
            elevationBand.setNoDataValue(demNoDataValue);
            elevationBand.setNoDataValueUsed(true);
        }

        isElevationModelAvailable = true;
    }

    private void getSourceImageDimension() {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    private void createTargetProduct() {
        try {
            if (pixelSpacingInMeter <= 0.0 && pixelSpacingInDegree <= 0) {
                pixelSpacingInMeter = Math.max(SARGeocoding.getAzimuthPixelSpacing(sourceProduct),
                        SARGeocoding.getRangePixelSpacing(sourceProduct));
                pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeter);

                // Apply oversampling percent
                double multiplier = 1.0;
                if (oversamplingPercent > 0.0 && oversamplingPercent < 100.0) {
                    multiplier = 1.0 - (oversamplingPercent / 100.0);
                }

                pixelSpacingInMeter *= multiplier;
                pixelSpacingInDegree *= multiplier;
            }
            if (pixelSpacingInMeter <= 0.0) {
                pixelSpacingInMeter = SARGeocoding.getPixelSpacingInMeter(pixelSpacingInDegree);
            }
            if (pixelSpacingInDegree <= 0) {
                pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeter);
            }
            delLat = pixelSpacingInDegree;
            delLon = pixelSpacingInDegree;

            final CRSGeoCodingHandler crsHandler = new CRSGeoCodingHandler(sourceProduct, mapProjection,
                    pixelSpacingInDegree, pixelSpacingInMeter,
                    alignToStandardGrid, standardGridOriginX, standardGridOriginY);

            targetCRS = crsHandler.getTargetCRS();

            targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                    sourceProduct.getProductType(), crsHandler.getTargetWidth(), crsHandler.getTargetHeight());
            targetProduct.setSceneGeoCoding(crsHandler.getCrsGeoCoding());

            targetImageWidth = targetProduct.getSceneRasterWidth();
            targetImageHeight = targetProduct.getSceneRasterHeight();

            addSelectedBands();

            targetGeoCoding = targetProduct.getSceneGeoCoding();

            ProductUtils.copyMetadata(sourceProduct, targetProduct);
            ProductUtils.copyMasks(sourceProduct, targetProduct);
            ProductUtils.copyVectorData(sourceProduct, targetProduct);

            targetProduct.setStartTime(sourceProduct.getStartTime());
            targetProduct.setEndTime(sourceProduct.getEndTime());
            targetProduct.setDescription(sourceProduct.getDescription());

            try {
                ProductUtils.copyIndexCodings(sourceProduct, targetProduct);
            } catch (Exception e) {
                if (!imgResampling.equals(Resampling.NEAREST_NEIGHBOUR)) {
                    throw new OperatorException("Use Nearest Neighbour with Classifications: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void computeSensorPositionsAndVelocities() {
        orbit = new OrbitStateVectors(orbitStateVectors, firstLineUTC, lineTimeInterval, sourceImageHeight);
    }

    private void addSelectedBands() throws OperatorException {
        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        for (int i = 0; i < sourceBands.length; i++) {
            Band b = sourceBands[i];
            if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                // Look for corresponding Imaginary
                Band q = null;
                if (i + 1 < sourceBands.length && sourceBands[i + 1].getUnit().equals(Unit.IMAGINARY)) {
                    q = sourceBands[i + 1];
                    i++; // Skip next
                }
                
                if (q != null) {
                    ComplexPair pair = new ComplexPair();
                    pair.srcI = b;
                    pair.srcQ = q;
                    pair.tgtI = addTargetBand(b.getName(), Unit.REAL, b);
                    pair.tgtQ = addTargetBand(q.getName(), Unit.IMAGINARY, q);
                    complexPairs.add(pair);

                    String suffix = b.getName().substring(b.getName().lastIndexOf("_"));
                    ReaderUtils.createVirtualIntensityBand(targetProduct, pair.tgtI, pair.tgtQ, suffix);
                }
            } else {
                // Handle other bands if needed, but GSLC focuses on complex
                addTargetBand(b.getName(), b.getUnit(), b);
            }
        }

        if (saveDEM) {
            elevationBand = addTargetBand("elevation", Unit.METERS, null);
        }

        if (saveLatLon) {
            addTargetBand("latitude", Unit.DEGREES, null);
            addTargetBand("longitude", Unit.DEGREES, null);
        }

        if (saveLocalIncidenceAngle) {
            addTargetBand("localIncidenceAngle", Unit.DEGREES, null);
        }

        if (saveProjectedLocalIncidenceAngle) {
            addTargetBand("projectedLocalIncidenceAngle", Unit.DEGREES, null);
        }

        if (saveIncidenceAngleFromEllipsoid) {
            addTargetBand("incidenceAngleFromEllipsoid", Unit.DEGREES, null);
        }

        if (saveLayoverShadowMask) {
            addTargetBand(targetProduct, targetImageWidth, targetImageHeight, "layoverShadowMask",
                    Unit.BIT, null, ProductData.TYPE_INT8);
        }

        if (saveSimulatedPhase) {
            simulatedPhaseBand = addTargetBand("simulatedPhase", Unit.PHASE, null);
        }

        if (saveSimulatedUnwrappedPhase) {
            simulatedUnwrappedPhaseBand = addTargetBand("simulatedUnwrappedPhase", Unit.PHASE, null);
        }
    }

    private Band addTargetBand(final String bandName, final String bandUnit, final Band sourceBand) {
        return addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                bandName, bandUnit, sourceBand, ProductData.TYPE_FLOAT32);
    }

    static Band addTargetBand(final Product targetProduct, final int targetImageWidth, final int targetImageHeight,
                              final String bandName, final String bandUnit, final Band sourceBand,
                              final int dataType) {
        String name = bandName;
        int cnt = 2;
        while (targetProduct.containsBand(name)) {
            name = bandName + cnt;
            ++cnt;
        }

        if (targetProduct.getBand(name) == null) {
            final Band targetBand = new Band(name, dataType, targetImageWidth, targetImageHeight);
            targetBand.setUnit(bandUnit);
            if (sourceBand != null) {
                targetBand.setDescription(sourceBand.getDescription());
                targetBand.setNoDataValue(sourceBand.getNoDataValue());
            }
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);
            return targetBand;
        }
        return null;
    }

    private void updateTargetProductMetadata() throws Exception {
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.srgr_flag, 1);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, targetImageHeight);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, targetImageWidth);

        final GeoPos geoPosFirstNear = targetGeoCoding.getGeoPos(new PixelPos(0, 0), null);
        final GeoPos geoPosFirstFar = targetGeoCoding.getGeoPos(new PixelPos(targetImageWidth - 1, 0), null);
        final GeoPos geoPosLastNear = targetGeoCoding.getGeoPos(new PixelPos(0, targetImageHeight - 1), null);
        final GeoPos geoPosLastFar = targetGeoCoding.getGeoPos(new PixelPos(targetImageWidth - 1, targetImageHeight - 1), null);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_lat, geoPosFirstNear.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_lat, geoPosFirstFar.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_lat, geoPosLastNear.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_lat, geoPosLastFar.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_long, geoPosFirstNear.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_long, geoPosFirstFar.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_long, geoPosLastNear.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_long, geoPosLastFar.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.TOT_SIZE, ReaderUtils.getTotalSize(targetProduct));
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.map_projection, targetCRS.getName().getCode());
        
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.is_terrain_corrected, 1);
        if (externalDEMFile != null) {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, externalDEMFile.getPath());
        } else {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, demName);
        }

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.geo_ref_system, "WGS84");
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.lat_pixel_res, delLat);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.lon_pixel_res, delLon);

        if (pixelSpacingInMeter > 0.0 &&
                Double.compare(pixelSpacingInMeter, SARGeocoding.getPixelSpacing(sourceProduct)) != 0) {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.range_spacing, pixelSpacingInMeter);
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.azimuth_spacing, pixelSpacingInMeter);
        }
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
        try {
            if (!isElevationModelAvailable) {
                getElevationModel();
            }

            if (isTOPSProduct) {
                computeTileStackTOPS(targetTiles, targetRectangle);
            } else {
                computeTileStackSM(targetTiles, targetRectangle);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeTileStackSM(Map<Band, Tile> targetTiles, Rectangle targetRectangle) throws Exception {
        {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;

            final TileGeoreferencing tileGeoRef = new TileGeoreferencing(targetProduct, x0 - 1, y0 - 1, w + 2, h + 2);

            double[][] localDEM = new double[h + 2][w + 2];
            final boolean valid = DEMFactory.getLocalDEM(
                    dem, demNoDataValue, demResamplingMethod, tileGeoRef, x0, y0, w, h, sourceProduct,
                    nodataValueAtSea, localDEM);

            if (!valid && nodataValueAtSea) {
                for (Band targetBand : targetTiles.keySet()) {
                    ProductData data = targetTiles.get(targetBand).getRawSamples();
                    double nodatavalue = targetBand.getNoDataValue();
                    final int length = data.getNumElems();
                    for (int i = 0; i < length; ++i) {
                        data.setElemDoubleAt(i, nodatavalue);
                    }
                }
                return;
            }

            Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h, tileGeoRef, localDEM);

            // Ensure the source rectangle intersects with the source image bounds
            if (sourceRectangle != null) {
                final Rectangle sourceBounds = new Rectangle(0, 0, sourceImageWidth, sourceImageHeight);
                sourceRectangle = sourceRectangle.intersection(sourceBounds);
                if (sourceRectangle.isEmpty()) {
                    sourceRectangle = null;
                }
            }

            if (sourceRectangle == null) {
                 for (Tile t : targetTiles.values()) {
                     ProductData data = t.getRawSamples();
                     final double noData = t.getRasterDataNode().getNoDataValue();
                     for(int i = 0; i < data.getNumElems(); i++) data.setElemDoubleAt(i, noData);
                 }
                 return;
            }

            // Prepare buffers and rasters for Complex Pairs
            class ActivePair {
                GSLCResamplingRaster raster;
                ProductData bufI;
                ProductData bufQ;
                double noDataI;
                double noDataQ;
            }
            List<ActivePair> activePairs = new ArrayList<>();

            for (ComplexPair pair : complexPairs) {
                boolean processI = targetTiles.containsKey(pair.tgtI);
                boolean processQ = targetTiles.containsKey(pair.tgtQ);
                
                if (!processI && !processQ) continue;

                Tile srcTileI = getSourceTile(pair.srcI, sourceRectangle);
                Tile srcTileQ = getSourceTile(pair.srcQ, sourceRectangle);
                
                ActivePair ap = new ActivePair();
                ap.raster = new GSLCResamplingRaster(srcTileI, srcTileQ, rangeSpacing, wavelength, nearEdgeSlantRange, sourceImageWidth, sourceImageHeight, nearRangeOnLeft);
                ap.bufI = processI ? targetTiles.get(pair.tgtI).getRawSamples() : null;
                ap.bufQ = processQ ? targetTiles.get(pair.tgtQ).getRawSamples() : null;
                ap.noDataI = pair.tgtI.getNoDataValue();
                ap.noDataQ = pair.tgtQ.getNoDataValue();
                activePairs.add(ap);
            }

            // Prepare buffers for other bands
            ProductData bufPhase = (saveSimulatedPhase && targetTiles.containsKey(simulatedPhaseBand)) ? targetTiles.get(simulatedPhaseBand).getRawSamples() : null;
            ProductData bufUnwrappedPhase = (saveSimulatedUnwrappedPhase && targetTiles.containsKey(simulatedUnwrappedPhaseBand)) ? targetTiles.get(simulatedUnwrappedPhaseBand).getRawSamples() : null;
            
            ProductData demBuffer = (saveDEM && targetTiles.containsKey(elevationBand)) ? targetTiles.get(elevationBand).getRawSamples() : null;
            ProductData latBuffer = (saveLatLon && targetTiles.containsKey(targetProduct.getBand("latitude"))) ? targetTiles.get(targetProduct.getBand("latitude")).getRawSamples() : null;
            ProductData lonBuffer = (saveLatLon && targetTiles.containsKey(targetProduct.getBand("longitude"))) ? targetTiles.get(targetProduct.getBand("longitude")).getRawSamples() : null;
            ProductData localIncidenceAngleBuffer = (saveLocalIncidenceAngle && targetTiles.containsKey(targetProduct.getBand("localIncidenceAngle"))) ? targetTiles.get(targetProduct.getBand("localIncidenceAngle")).getRawSamples() : null;
            ProductData projectedLocalIncidenceAngleBuffer = (saveProjectedLocalIncidenceAngle && targetTiles.containsKey(targetProduct.getBand("projectedLocalIncidenceAngle"))) ? targetTiles.get(targetProduct.getBand("projectedLocalIncidenceAngle")).getRawSamples() : null;
            ProductData incidenceAngleFromEllipsoidBuffer = (saveIncidenceAngleFromEllipsoid && targetTiles.containsKey(targetProduct.getBand("incidenceAngleFromEllipsoid"))) ? targetTiles.get(targetProduct.getBand("incidenceAngleFromEllipsoid")).getRawSamples() : null;
            ProductData layoverShadowMaskBuffer = (saveLayoverShadowMask && targetTiles.containsKey(targetProduct.getBand("layoverShadowMask"))) ? targetTiles.get(targetProduct.getBand("layoverShadowMask")).getRawSamples() : null;

            final Resampling.Index resamplingIndex = imgResampling.createIndex();
            final PositionData posData = new PositionData();
            final GeoPos geoPos = new GeoPos();
            final double phaseConstant = 4.0 * Math.PI / wavelength;

            // Pre-fetch noDataValues for auxiliary bands to avoid repeated band lookups in the loop
            final double noDataPhase = (simulatedPhaseBand != null) ? simulatedPhaseBand.getNoDataValue() : 0;
            final double noDataUnwrappedPhase = (simulatedUnwrappedPhaseBand != null) ? simulatedUnwrappedPhaseBand.getNoDataValue() : 0;
            final double noDataDem = (elevationBand != null) ? elevationBand.getNoDataValue() : 0;
            final Band latBand = saveLatLon ? targetProduct.getBand("latitude") : null;
            final Band lonBand = saveLatLon ? targetProduct.getBand("longitude") : null;
            final Band localIncAngleBand = saveLocalIncidenceAngle ? targetProduct.getBand("localIncidenceAngle") : null;
            final Band projIncAngleBand = saveProjectedLocalIncidenceAngle ? targetProduct.getBand("projectedLocalIncidenceAngle") : null;
            final Band ellipIncAngleBand = saveIncidenceAngleFromEllipsoid ? targetProduct.getBand("incidenceAngleFromEllipsoid") : null;
            final double noDataLat = (latBand != null) ? latBand.getNoDataValue() : 0;
            final double noDataLon = (lonBand != null) ? lonBand.getNoDataValue() : 0;
            final double noDataLocalInc = (localIncAngleBand != null) ? localIncAngleBand.getNoDataValue() : 0;
            final double noDataProjInc = (projIncAngleBand != null) ? projIncAngleBand.getNoDataValue() : 0;
            final double noDataEllipInc = (ellipIncAngleBand != null) ? ellipIncAngleBand.getNoDataValue() : 0;

            // Unified Loop
            for (int y = y0; y < y0 + h; y++) {
                final int yy = y - y0 + 1;
                for (int x = x0; x < x0 + w; x++) {
                    final int xx = x - x0 + 1;
                    final int idx = (y - y0) * w + (x - x0);

                    final double alt = localDEM[yy][xx];
                    boolean isNoData = (alt == demNoDataValue);

                    if (!isNoData) {
                        tileGeoRef.getGeoPos(x, y, geoPos);
                        if (!getPosition(geoPos.lat, geoPos.lon, alt, posData) || !isValidCell(posData.rangeIndex, posData.azimuthIndex)) {
                            isNoData = true;
                        }
                    }

                    if (isNoData) {
                        for (ActivePair ap : activePairs) {
                            if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                            if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                        }
                        if (bufPhase != null) bufPhase.setElemDoubleAt(idx, noDataPhase);
                        if (bufUnwrappedPhase != null) bufUnwrappedPhase.setElemDoubleAt(idx, noDataUnwrappedPhase);
                        if (demBuffer != null) demBuffer.setElemDoubleAt(idx, noDataDem);
                        if (latBuffer != null) latBuffer.setElemDoubleAt(idx, noDataLat);
                        if (lonBuffer != null) lonBuffer.setElemDoubleAt(idx, noDataLon);
                        if (localIncidenceAngleBuffer != null) localIncidenceAngleBuffer.setElemDoubleAt(idx, noDataLocalInc);
                        if (projectedLocalIncidenceAngleBuffer != null) projectedLocalIncidenceAngleBuffer.setElemDoubleAt(idx, noDataProjInc);
                        if (incidenceAngleFromEllipsoidBuffer != null) incidenceAngleFromEllipsoidBuffer.setElemDoubleAt(idx, noDataEllipInc);
                        if (layoverShadowMaskBuffer != null) layoverShadowMaskBuffer.setElemIntAt(idx, 0);
                        continue;
                    }

                    // Valid Pixel Processing

                    // 1. Complex Resampling
                    final double rangeIndex = posData.rangeIndex;
                    final double azimuthIndex = posData.azimuthIndex;
                    final double slantRange = posData.slantRange;

                    imgResampling.computeCornerBasedIndex(rangeIndex, azimuthIndex, sourceImageWidth, sourceImageHeight, resamplingIndex);

                    final double phase = phaseConstant * slantRange;
                    final double cosPhi = FastMath.cos(phase);
                    final double sinPhi = FastMath.sin(phase);

                    for (ActivePair ap : activePairs) {
                        ap.raster.setSlantRangeAtCenter(slantRange, rangeIndex);
                        
                        ap.raster.setReturnReal(true);
                        double iFlat = imgResampling.resample(ap.raster, resamplingIndex);
                        
                        ap.raster.setReturnReal(false);
                        double qFlat = imgResampling.resample(ap.raster, resamplingIndex);

                        if (iFlat == ap.raster.getNoDataValue() || qFlat == ap.raster.getNoDataValue()) {
                             if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                             if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                        } else {
                             double iFinal, qFinal;
                             if (outputFlattened) {
                                  iFinal = iFlat;
                                  qFinal = qFlat;
                             } else {
                                  iFinal = iFlat * cosPhi - qFlat * sinPhi;
                                  qFinal = qFlat * cosPhi + iFlat * sinPhi;
                             }
                             if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, iFinal);
                             if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, qFinal);
                        }
                    }

                    // 2. Simulated Phase
                    if (bufPhase != null) {
                        double wrappedPhase = Math.atan2(sinPhi, cosPhi);
                        bufPhase.setElemDoubleAt(idx, wrappedPhase);
                    }
                    if (bufUnwrappedPhase != null) {
                        bufUnwrappedPhase.setElemDoubleAt(idx, phase);
                    }

                    // 3. Other Bands
                    if (demBuffer != null) demBuffer.setElemDoubleAt(idx, alt);
                    if (latBuffer != null) latBuffer.setElemDoubleAt(idx, geoPos.lat);
                    if (lonBuffer != null) lonBuffer.setElemDoubleAt(idx, geoPos.lon);

                    if (localIncidenceAngleBuffer != null || projectedLocalIncidenceAngleBuffer != null) {
                        final double[] localIncidenceAngles = {SARGeocoding.NonValidIncidenceAngle, SARGeocoding.NonValidIncidenceAngle};
                         final LocalGeometry localGeometry = new LocalGeometry(
                                x, y, tileGeoRef, posData.earthPoint, posData.sensorPos);
                        
                        SARGeocoding.computeLocalIncidenceAngle(
                                localGeometry, demNoDataValue, saveLocalIncidenceAngle, saveProjectedLocalIncidenceAngle,
                                false, x0, y0, x, y, localDEM, localIncidenceAngles);

                        if (localIncidenceAngleBuffer != null) {
                            localIncidenceAngleBuffer.setElemDoubleAt(idx, localIncidenceAngles[0]);
                        }
                        if (projectedLocalIncidenceAngleBuffer != null) {
                            projectedLocalIncidenceAngleBuffer.setElemDoubleAt(idx, localIncidenceAngles[1]);
                        }
                    }

                    if (incidenceAngleFromEllipsoidBuffer != null && incidenceAngle != null) {
                        incidenceAngleFromEllipsoidBuffer.setElemDoubleAt(idx, incidenceAngle.getPixelDouble(posData.rangeIndex, posData.azimuthIndex));
                    }
                    
                    if (layoverShadowMaskBuffer != null) {
                        layoverShadowMaskBuffer.setElemIntAt(idx, 0); 
                    }
                }
            }

        }
    }

    private void computeTileStackTOPS(Map<Band, Tile> targetTiles, Rectangle targetRectangle) throws Exception {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int numPixels = w * h;

        final TileGeoreferencing tileGeoRef = new TileGeoreferencing(targetProduct, x0 - 1, y0 - 1, w + 2, h + 2);

        double[][] localDEM = new double[h + 2][w + 2];
        final boolean valid = DEMFactory.getLocalDEM(
                dem, demNoDataValue, demResamplingMethod, tileGeoRef, x0, y0, w, h, sourceProduct,
                nodataValueAtSea, localDEM);

        if (!valid && nodataValueAtSea) {
            fillAllNoData(targetTiles);
            return;
        }

        // Phase 1: Backward geocode all target pixels with burst-aware azimuth mapping.
        // For TOPS burst products, the azimuth time-to-line mapping is NOT linear across the
        // full image — each burst has its own time window with gaps between bursts.
        final double[] azimuthIndices = new double[numPixels];
        final double[] rangeIndices = new double[numPixels];
        final double[] slantRanges = new double[numPixels];
        final int[] bestBurst = new int[numPixels];
        java.util.Arrays.fill(bestBurst, -1);

        final PosVector earthPoint = new PosVector();
        final PosVector sensorPos = new PosVector();
        final GeoPos geoPos = new GeoPos();
        final Sentinel1Utils.SubSwathInfo ss = subSwath[subSwathIndex - 1];
        final double phaseConstant = 4.0 * Math.PI / wavelength;

        for (int y = y0; y < y0 + h; y++) {
            final int yy = y - y0 + 1;
            for (int x = x0; x < x0 + w; x++) {
                final int xx = x - x0 + 1;
                final int idx = (y - y0) * w + (x - x0);

                final double alt = localDEM[yy][xx];
                if (alt == demNoDataValue) continue;

                tileGeoRef.getGeoPos(x, y, geoPos);
                GeoUtils.geo2xyzWGS84(geoPos.lat, geoPos.lon, alt, earthPoint);

                // Find zero-Doppler time using orbit state vector bisection (not pre-computed array).
                // For TOPS burst products, the pre-computed array uses lineTimeInterval that includes
                // burst gaps, causing incorrect orbit positions and failed Doppler searches.
                final double zeroDopplerTime = SARGeocoding.getZeroDopplerTime(
                        lineTimeInterval, wavelength, earthPoint, orbit);

                if (zeroDopplerTime == SARGeocoding.NonValidZeroDopplerTime) continue;

                // Compute slant range from orbit interpolation
                double slantRange = SARGeocoding.computeSlantRange(
                        zeroDopplerTime, orbit, earthPoint, sensorPos);

                // Bistatic correction
                double correctedTime = zeroDopplerTime;
                if (!skipBistaticCorrection) {
                    correctedTime += slantRange / Constants.lightSpeedInMetersPerDay;
                    slantRange = SARGeocoding.computeSlantRange(
                            correctedTime, orbit, earthPoint, sensorPos);
                } else if (bistaticCorrectionRefRange > 0.0) {
                    correctedTime += (slantRange - bistaticCorrectionRefRange) / Constants.lightSpeedInMetersPerDay;
                    slantRange = SARGeocoding.computeSlantRange(
                            correctedTime, orbit, earthPoint, sensorPos);
                }

                // Convert zero-Doppler time to seconds for burst lookup
                final double zeroDopplerTimeSec = correctedTime * Constants.secondsInDay;

                // Determine burst membership
                final int burst = selectBurst(zeroDopplerTimeSec, ss);
                if (burst < 0) continue;

                // Compute burst-local azimuth index
                final double lineWithinBurst = (zeroDopplerTimeSec - ss.burstFirstLineTime[burst])
                        / ss.azimuthTimeInterval;
                final double azimuthIndex = burst * ss.linesPerBurst + lineWithinBurst;

                // Compute range index
                double rangeIndex;
                if (!srgrFlag) {
                    rangeIndex = (slantRange - nearEdgeSlantRange) / rangeSpacing;
                } else {
                    rangeIndex = SARGeocoding.computeRangeIndex(
                            srgrFlag, sourceImageWidth, firstLineUTC, lastLineUTC, rangeSpacing,
                            correctedTime, slantRange, nearEdgeSlantRange, srgrConvParams);
                    if (rangeIndex == -1.0) continue;
                }

                if (!nearRangeOnLeft) {
                    rangeIndex = sourceImageWidth - 1 - rangeIndex;
                }

                // Validate within burst valid region
                if (!isValidBurstSample(burst, azimuthIndex, rangeIndex, ss)) continue;
                if (!isValidCell(rangeIndex, azimuthIndex)) continue;

                azimuthIndices[idx] = azimuthIndex;
                rangeIndices[idx] = rangeIndex;
                slantRanges[idx] = slantRange;
                bestBurst[idx] = burst;
            }
        }

        // Prepare output buffers
        class ActivePair {
            ComplexPair pair;
            ProductData bufI;
            ProductData bufQ;
            double noDataI;
            double noDataQ;
        }
        final List<ActivePair> activePairs = new ArrayList<>();

        for (ComplexPair pair : complexPairs) {
            boolean processI = targetTiles.containsKey(pair.tgtI);
            boolean processQ = targetTiles.containsKey(pair.tgtQ);
            if (!processI && !processQ) continue;

            ActivePair ap = new ActivePair();
            ap.pair = pair;
            ap.bufI = processI ? targetTiles.get(pair.tgtI).getRawSamples() : null;
            ap.bufQ = processQ ? targetTiles.get(pair.tgtQ).getRawSamples() : null;
            ap.noDataI = pair.tgtI.getNoDataValue();
            ap.noDataQ = pair.tgtQ.getNoDataValue();
            activePairs.add(ap);
        }

        ProductData bufPhase = (saveSimulatedPhase && targetTiles.containsKey(simulatedPhaseBand))
                ? targetTiles.get(simulatedPhaseBand).getRawSamples() : null;
        ProductData bufUnwrappedPhase = (saveSimulatedUnwrappedPhase && targetTiles.containsKey(simulatedUnwrappedPhaseBand))
                ? targetTiles.get(simulatedUnwrappedPhaseBand).getRawSamples() : null;

        // Fill noData for pixels that didn't geocode
        for (int idx = 0; idx < numPixels; idx++) {
            if (bestBurst[idx] == -1) {
                for (ActivePair ap : activePairs) {
                    if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                    if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                }
                if (bufPhase != null) bufPhase.setElemDoubleAt(idx, simulatedPhaseBand.getNoDataValue());
                if (bufUnwrappedPhase != null) bufUnwrappedPhase.setElemDoubleAt(idx, simulatedUnwrappedPhaseBand.getNoDataValue());
            }
        }

        // Phase 2: Process burst by burst
        final Resampling.Index resamplingIndex = imgResampling.createIndex();
        final Rectangle sourceBounds = new Rectangle(0, 0, sourceImageWidth, sourceImageHeight);

        for (int burstIndex = 0; burstIndex < ss.numOfBursts; burstIndex++) {

            // Compute source rectangle for this burst's target pixels
            final Rectangle burstSourceRect = computeBurstSourceRectangle(
                    burstIndex, bestBurst, azimuthIndices, rangeIndices, w, h, ss);
            if (burstSourceRect == null) continue;

            // Clamp to source image bounds
            final Rectangle clampedRect = burstSourceRect.intersection(sourceBounds);
            if (clampedRect.isEmpty()) continue;

            for (ActivePair ap : activePairs) {
                final Tile srcTileI = getSourceTile(ap.pair.srcI, clampedRect);
                final Tile srcTileQ = getSourceTile(ap.pair.srcQ, clampedRect);

                // Compute deramp+demod phase for this burst region
                final double[][] derampDemodPhase = su.computeDerampDemodPhase(
                        subSwath, subSwathIndex, burstIndex, clampedRect);

                // Apply deramp to source data
                final int bw = clampedRect.width;
                final int bh = clampedRect.height;
                final double[][] derampedI = new double[bh][bw];
                final double[][] derampedQ = new double[bh][bw];
                performDerampDemod(srcTileI, srcTileQ, clampedRect, derampDemodPhase, derampedI, derampedQ);

                // Create resampling rasters from deramped arrays
                final ArrayResamplingRaster rasterI = new ArrayResamplingRaster(derampedI, bw, bh);
                final ArrayResamplingRaster rasterQ = new ArrayResamplingRaster(derampedQ, bw, bh);
                final ArrayResamplingRaster rasterPhase = new ArrayResamplingRaster(derampDemodPhase, bw, bh);

                // Resample each target pixel belonging to this burst
                for (int idx = 0; idx < numPixels; idx++) {
                    if (bestBurst[idx] != burstIndex) continue;

                    // Convert to local coordinates within the burst source rect
                    final double localRg = rangeIndices[idx] - clampedRect.x;
                    final double localAz = azimuthIndices[idx] - clampedRect.y;

                    imgResampling.computeCornerBasedIndex(localRg, localAz, bw, bh, resamplingIndex);

                    final double sampI = imgResampling.resample(rasterI, resamplingIndex);
                    final double sampQ = imgResampling.resample(rasterQ, resamplingIndex);

                    if (Double.isNaN(sampI) || Double.isNaN(sampQ)) {
                        if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, ap.noDataI);
                        if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, ap.noDataQ);
                        continue;
                    }

                    // Interpolate the deramp phase at the resampled position and reramp
                    final double sampPhase = imgResampling.resample(rasterPhase, resamplingIndex);
                    final double cosReramp = FastMath.cos(sampPhase);
                    final double sinReramp = FastMath.sin(sampPhase);
                    double convergentI = sampI * cosReramp + sampQ * sinReramp;
                    double convergentQ = -sampI * sinReramp + sampQ * cosReramp;

                    // Apply range phase flattening
                    if (outputFlattened) {
                        final double rangePhase = phaseConstant * slantRanges[idx];
                        final double cosPhi = FastMath.cos(rangePhase);
                        final double sinPhi = FastMath.sin(rangePhase);
                        final double iFinal = convergentI * cosPhi - convergentQ * sinPhi;
                        final double qFinal = convergentQ * cosPhi + convergentI * sinPhi;
                        convergentI = iFinal;
                        convergentQ = qFinal;
                    }

                    if (ap.bufI != null) ap.bufI.setElemDoubleAt(idx, convergentI);
                    if (ap.bufQ != null) ap.bufQ.setElemDoubleAt(idx, convergentQ);
                }
            }
        }

        // Simulated phase bands
        for (int idx = 0; idx < numPixels; idx++) {
            if (bestBurst[idx] == -1) continue;
            final double phase = phaseConstant * slantRanges[idx];
            if (bufPhase != null) bufPhase.setElemDoubleAt(idx, Math.atan2(FastMath.sin(phase), FastMath.cos(phase)));
            if (bufUnwrappedPhase != null) bufUnwrappedPhase.setElemDoubleAt(idx, phase);
        }
    }

    private void fillAllNoData(Map<Band, Tile> targetTiles) {
        for (Tile t : targetTiles.values()) {
            ProductData data = t.getRawSamples();
            final double noData = t.getRasterDataNode().getNoDataValue();
            for (int i = 0; i < data.getNumElems(); i++) data.setElemDoubleAt(i, noData);
        }
    }

    /**
     * Determine which burst a pixel belongs to using valid line times and midpoint overlap rule.
     */
    private static int selectBurst(double zeroDopplerTimeSec, Sentinel1Utils.SubSwathInfo ss) {
        int firstBurst = -1;
        int secondBurst = -1;

        for (int i = 0; i < ss.numOfBursts; i++) {
            // Use valid line times (not full burst extent) to avoid including invalid edge samples
            if (zeroDopplerTimeSec >= ss.burstFirstValidLineTime[i] &&
                    zeroDopplerTimeSec <= ss.burstLastValidLineTime[i]) {
                if (firstBurst == -1) {
                    firstBurst = i;
                } else {
                    secondBurst = i;
                    break;
                }
            }
        }

        if (firstBurst == -1) return -1;
        if (secondBurst == -1) return firstBurst;

        // Overlap: use midpoint rule between valid regions (same as TOPSARDeburstOp)
        final double midTime = (ss.burstLastValidLineTime[firstBurst] +
                ss.burstFirstValidLineTime[secondBurst]) / 2.0;
        return (zeroDopplerTimeSec < midTime) ? firstBurst : secondBurst;
    }

    /**
     * Check if a source pixel falls within the valid sample region of its burst.
     */
    private static boolean isValidBurstSample(int burstIndex, double azimuthIndex, double rangeIndex,
                                               Sentinel1Utils.SubSwathInfo ss) {
        final int lineInBurst = (int) Math.round(azimuthIndex) - burstIndex * ss.linesPerBurst;
        if (lineInBurst < 0 || lineInBurst >= ss.linesPerBurst) return false;

        // Check valid line range
        if (lineInBurst < ss.firstValidLine[burstIndex] || lineInBurst > ss.lastValidLine[burstIndex]) {
            return false;
        }

        // Check valid sample range for this line
        final int sample = (int) Math.round(rangeIndex);
        final int firstValid = ss.firstValidSample[burstIndex][lineInBurst];
        final int lastValid = ss.lastValidSample[burstIndex][lineInBurst];
        return firstValid != -1 && sample >= firstValid && sample <= lastValid;
    }

    private Rectangle computeBurstSourceRectangle(int burstIndex, int[] bestBurst,
                                                   double[] azimuthIndices, double[] rangeIndices,
                                                   int w, int h, Sentinel1Utils.SubSwathInfo ss) {

        final int burstFirstLine = burstIndex * ss.linesPerBurst;
        final int burstLastLine = burstFirstLine + ss.linesPerBurst - 1;

        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
        int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
        boolean found = false;

        for (int i = 0; i < w * h; i++) {
            if (bestBurst[i] != burstIndex) continue;
            found = true;
            int rg = (int) Math.floor(rangeIndices[i]);
            int az = (int) Math.floor(azimuthIndices[i]);
            xMin = Math.min(xMin, rg);
            xMax = Math.max(xMax, rg + 1);
            yMin = Math.min(yMin, az);
            yMax = Math.max(yMax, az + 1);
        }

        if (!found) return null;

        xMin = Math.max(xMin - margin, 0);
        xMax = Math.min(xMax + margin, sourceImageWidth - 1);
        yMin = Math.max(yMin - margin, burstFirstLine);
        yMax = Math.min(yMax + margin, burstLastLine);

        if (xMin > xMax || yMin > yMax) return null;
        return new Rectangle(xMin, yMin, xMax - xMin + 1, yMax - yMin + 1);
    }

    private static void performDerampDemod(final Tile tileI, final Tile tileQ,
                                            final Rectangle rectangle, final double[][] derampDemodPhase,
                                            final double[][] derampedI, final double[][] derampedQ) {

        final int x0 = rectangle.x;
        final int y0 = rectangle.y;
        final int xMax = x0 + rectangle.width;
        final int yMax = y0 + rectangle.height;

        for (int y = y0; y < yMax; y++) {
            final int yy = y - y0;
            for (int x = x0; x < xMax; x++) {
                final int xx = x - x0;
                final double valueI = tileI.getSampleDouble(x, y);
                final double valueQ = tileQ.getSampleDouble(x, y);
                final double cosPhase = FastMath.cos(derampDemodPhase[yy][xx]);
                final double sinPhase = FastMath.sin(derampDemodPhase[yy][xx]);
                derampedI[yy][xx] = valueI * cosPhase - valueQ * sinPhase;
                derampedQ[yy][xx] = valueI * sinPhase + valueQ * cosPhase;
            }
        }
    }

    private static class ArrayResamplingRaster implements Resampling.Raster {
        private final double[][] data;
        private final int width;
        private final int height;

        ArrayResamplingRaster(double[][] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }

        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }

        @Override
        public boolean getSamples(int[] x, int[] y, double[][] samples) {
            boolean allValid = true;
            for (int i = 0; i < y.length; i++) {
                for (int j = 0; j < x.length; j++) {
                    if (y[i] >= 0 && y[i] < height && x[j] >= 0 && x[j] < width) {
                        samples[i][j] = data[y[i]][x[j]];
                    } else {
                        samples[i][j] = Double.NaN;
                        allValid = false;
                    }
                }
            }
            return allValid;
        }
    }

    private Rectangle getSourceRectangle(final int x0, final int y0, final int w, final int h,
                                         final TileGeoreferencing tileGeoRef, final double[][] localDEM) {
        // Use a denser step to capture terrain effects
        final int step = 8;

        int xMax = Integer.MIN_VALUE;
        int xMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;
        int yMin = Integer.MAX_VALUE;

        PositionData posData = new PositionData();
        GeoPos geoPos = new GeoPos();

        for (int y = y0; ; y += step) {
            boolean lastY = false;
            if (y >= y0 + h - 1) {
                y = y0 + h - 1;
                lastY = true;
            }

            for (int x = x0; ; x += step) {
                boolean lastX = false;
                if (x >= x0 + w - 1) {
                    x = x0 + w - 1;
                    lastX = true;
                }

                tileGeoRef.getGeoPos(x, y, geoPos);

                final Double alt = localDEM[y - y0 + 1][x - x0 + 1];
                if (!alt.equals(demNoDataValue)) {
                    if (getPosition(geoPos.lat, geoPos.lon, alt, posData)) {
                        if (xMax < posData.rangeIndex) {
                            xMax = (int) Math.ceil(posData.rangeIndex);
                        }
                        if (xMin > posData.rangeIndex) {
                            xMin = (int) Math.floor(posData.rangeIndex);
                        }
                        if (yMax < posData.azimuthIndex) {
                            yMax = (int) Math.ceil(posData.azimuthIndex);
                        }
                        if (yMin > posData.azimuthIndex) {
                            yMin = (int) Math.floor(posData.azimuthIndex);
                        }
                    }
                }

                if (lastX) break;
            }
            if (lastY) break;
        }

        xMin = Math.max(xMin - margin, 0);
        xMax = Math.min(xMax + margin, sourceImageWidth - 1);
        yMin = Math.max(yMin - margin, 0);
        yMax = Math.min(yMax + margin, sourceImageHeight - 1);

        if (xMin > xMax || yMin > yMax) {
            return null;
        }
        return new Rectangle(xMin, yMin, xMax - xMin + 1, yMax - yMin + 1);
    }

    private int getMargin() {
        if (imgResampling == Resampling.BILINEAR_INTERPOLATION) {
            return 1;
        } else if (imgResampling == Resampling.NEAREST_NEIGHBOUR) {
            return 1;
        } else if (imgResampling == Resampling.CUBIC_CONVOLUTION) {
            return 2;
        } else if (imgResampling == Resampling.BISINC_5_POINT_INTERPOLATION) {
            return 3;
        } else if (imgResampling == Resampling.BISINC_11_POINT_INTERPOLATION) {
            return 6;
        } else if (imgResampling == Resampling.BISINC_21_POINT_INTERPOLATION) {
            return 11;
        } else if (imgResampling == Resampling.BICUBIC_INTERPOLATION) {
            return 2;
        } else {
            throw new OperatorException("Unhandled interpolation method");
        }
    }

    private boolean isValidCell(double x, double y) {
        return x >= margin && x < sourceImageWidth - margin && y >= margin && y < sourceImageHeight - margin;
    }

    private boolean getPosition(final double lat, final double lon, final double alt, final PositionData data) {
        GeoUtils.geo2xyzWGS84(lat, lon, alt, data.earthPoint);

        double zeroDopplerTime = SARGeocoding.getEarthPointZeroDopplerTime(firstLineUTC,
                lineTimeInterval, wavelength, data.earthPoint, orbit.sensorPosition, orbit.sensorVelocity);

        if (Double.compare(zeroDopplerTime, SARGeocoding.NonValidZeroDopplerTime) == 0) {
            return false;
        }

        data.slantRange = SARGeocoding.computeSlantRangeFast(orbit, firstLineUTC, lineTimeInterval,
                zeroDopplerTime, data.earthPoint, data.sensorPos);

        if (!skipBistaticCorrection) {
            // Full bistatic correction: product has no bulk correction applied
            zeroDopplerTime += data.slantRange / Constants.lightSpeedInMetersPerDay;
            data.slantRange = SARGeocoding.computeSlantRangeFast(orbit, firstLineUTC, lineTimeInterval,
                    zeroDopplerTime, data.earthPoint, data.sensorPos);
        } else if (bistaticCorrectionRefRange > 0.0) {
            // Bistatic residual correction (Section 4.7.3 of UZH-S1-GC-AD v1.12):
            // IPF applied bulk correction using a reference range; apply the range-dependent residual.
            zeroDopplerTime += (data.slantRange - bistaticCorrectionRefRange) / Constants.lightSpeedInMetersPerDay;
            data.slantRange = SARGeocoding.computeSlantRangeFast(orbit, firstLineUTC, lineTimeInterval,
                    zeroDopplerTime, data.earthPoint, data.sensorPos);
        }

        data.rangeIndex = SARGeocoding.computeRangeIndex(srgrFlag, sourceImageWidth, firstLineUTC, lastLineUTC,
                rangeSpacing, zeroDopplerTime, data.slantRange, nearEdgeSlantRange, srgrConvParams);

        if (data.rangeIndex == -1.0) {
            return false;
        }

        if (!nearRangeOnLeft) {
            data.rangeIndex = sourceImageWidth - 1 - data.rangeIndex;
        }

        data.azimuthIndex = (zeroDopplerTime - firstLineUTC) / lineTimeInterval;
        return true;
    }

    private static class PositionData {
        final PosVector earthPoint = new PosVector();
        final PosVector sensorPos = new PosVector();
        double azimuthIndex;
        double rangeIndex;
        double slantRange;
    }

    private static class GSLCResamplingRaster implements Resampling.Raster {
        private final Tile sourceTileI;
        private final Tile sourceTileQ;
        private final double rangeSpacing;
        private final double wavelength;
        private final double nearEdgeSlantRange;
        private final double noDataValue;
        private boolean returnReal;
        private final int sourceWidth;
        private final int sourceHeight;
        private final boolean nearRangeOnLeft;
        private double centerSlantRange;
        private double centerRangeIndex;
        
        private final double phaseStep;
        private final double sign;
        
        // Cache fields
        private double lastRangeIndex = -1.0;
        private double[][] cachedI;
        private double[][] cachedQ;
        private boolean lastAllValid;

        public GSLCResamplingRaster(Tile sourceTileI, Tile sourceTileQ, 
                                    double rangeSpacing, double wavelength, 
                                    double nearEdgeSlantRange, int sourceWidth, int sourceHeight, boolean nearRangeOnLeft) {
            this.sourceTileI = sourceTileI;
            this.sourceTileQ = sourceTileQ;
            this.rangeSpacing = rangeSpacing;
            this.wavelength = wavelength;
            this.nearEdgeSlantRange = nearEdgeSlantRange;
            this.noDataValue = sourceTileI.getRasterDataNode().getNoDataValue();
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.nearRangeOnLeft = nearRangeOnLeft;
            
            this.phaseStep = 4.0 * Math.PI * rangeSpacing / wavelength;
            this.sign = nearRangeOnLeft ? 1.0 : -1.0;
        }

        public double getNoDataValue() {
            return noDataValue;
        }

        public void setReturnReal(boolean returnReal) {
            this.returnReal = returnReal;
        }

        public void setSlantRangeAtCenter(double slantRange, double rangeIndex) {
            this.centerSlantRange = slantRange;
            this.centerRangeIndex = rangeIndex;
        }

        @Override
        public int getWidth() {
            return sourceWidth;
        }

        @Override
        public int getHeight() {
            return sourceHeight;
        }

        @Override
        public boolean getSamples(int[] x, int[] y, double[][] samples) {
            // Check if we can serve from cache
            if (Double.compare(centerRangeIndex, lastRangeIndex) == 0 && cachedI != null) {
                // Verify dimensions just in case (fast)
                if (cachedI.length == y.length && cachedI[0].length == x.length) {
                    double[][] source = returnReal ? cachedI : cachedQ;
                    for (int i = 0; i < y.length; i++) {
                        System.arraycopy(source[i], 0, samples[i], 0, x.length);
                    }
                    return lastAllValid;
                }
            }

            boolean allValid = true;
            Rectangle rect = sourceTileI.getRectangle();
            
            // Ensure cache is allocated
            if (cachedI == null || cachedI.length != y.length || cachedI[0].length != x.length) {
                cachedI = new double[y.length][x.length];
                cachedQ = new double[y.length][x.length];
            }

            // Use incremental phase rotation across range samples to avoid per-sample cos/sin.
            // Phase at kernel sample x[j] = phaseBase + (x[j] - centerRangeIndex) * sign * phaseStep
            // For consecutive integer x values, phase increments by sign * phaseStep.
            final double phaseBase = centerSlantRange * 4.0 * Math.PI / wavelength;
            final double cosStep = FastMath.cos(sign * phaseStep);
            final double sinStep = FastMath.sin(sign * phaseStep);

            final int rxMin = rect.x;
            final int rxMax = rect.x + rect.width - 1;
            final int ryMin = rect.y;
            final int ryMax = rect.y + rect.height - 1;

            for (int i = 0; i < y.length; i++) {
                final int yi = y[i];
                final boolean yInBounds = (yi >= ryMin && yi <= ryMax);

                // Compute phase for the first x sample in this row
                double deltaX0 = (x[0] - centerRangeIndex) * sign;
                double phi0 = phaseBase + deltaX0 * phaseStep;
                double cosPhiCur = FastMath.cos(phi0);
                double sinPhiCur = FastMath.sin(phi0);

                for (int j = 0; j < x.length; j++) {
                    final int xj = x[j];

                    if (!yInBounds || xj < rxMin || xj > rxMax) {
                        cachedI[i][j] = noDataValue;
                        cachedQ[i][j] = noDataValue;
                        allValid = false;
                    } else {
                        final double iVal = sourceTileI.getSampleDouble(xj, yi);
                        final double qVal = sourceTileQ.getSampleDouble(xj, yi);

                        if (iVal == noDataValue || qVal == noDataValue) {
                            cachedI[i][j] = noDataValue;
                            cachedQ[i][j] = noDataValue;
                            allValid = false;
                        } else {
                            // (I + jQ) * e^{+j*phi} = (I*cos - Q*sin) + j(Q*cos + I*sin)
                            cachedI[i][j] = iVal * cosPhiCur - qVal * sinPhiCur;
                            cachedQ[i][j] = qVal * cosPhiCur + iVal * sinPhiCur;
                        }
                    }

                    // Incremental phase rotation for next x sample: e^{j*(phi+step)} = e^{j*phi} * e^{j*step}
                    if (j < x.length - 1) {
                        final double cosNext = cosPhiCur * cosStep - sinPhiCur * sinStep;
                        final double sinNext = sinPhiCur * cosStep + cosPhiCur * sinStep;
                        cosPhiCur = cosNext;
                        sinPhiCur = sinNext;
                    }
                }
            }
            
            lastRangeIndex = centerRangeIndex;
            lastAllValid = allValid;
            
            // Copy to output
            double[][] source = returnReal ? cachedI : cachedQ;
            for (int i = 0; i < y.length; i++) {
                System.arraycopy(source[i], 0, samples[i], 0, x.length);
            }

            return allValid;
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GSLCGeocodingOp.class);
        }
    }
}
