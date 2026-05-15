/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.biomass.gpf.treeheight;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.Arrays;
import java.util.Map;

/**
 * This operator estimates forest canopy heights using the dual-pol TanDEM-X (HH and VV) data.
 * <p>
 * [1]	H. Chen, S. R. Cloude, and D. G. Goodenough, "Forest Canopy Height Estimation Using Tandem-X Coherence
 * Data", IEEE Journal of Selected Topics in Applied Earth Observations and Remote Sensing, Draft, 2016.
 */

@OperatorMetadata(alias = "DualPolForestHeightEstimation",
        category = "Radar/Biomass",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2017 by Array Systems Computing Inc.",
        description = "Estimate forest height from coherence for dual-pol TDX data")
public class DualPolForestHeightEstimationOp extends Operator {

    @SourceProduct
    Product sourceProduct;

    @TargetProduct
    Product targetProduct;

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "9",
            label = "Median filter size")
    private String windowSizeStr = "9";

    @Parameter(defaultValue = "false", label = "Output kz, local incidence angle, ground phase and coherence")
    private boolean dumpResult = false;

    // source bands
    private Band incidenceBand = null;
    private Band cohAmpBand = null;
    private Band cohPhaseBand = null;
    private Band groundPhaseBand = null;

    // target bands
    private Band kzBand = null;
    private Band heightBand = null;
    private Band filteredGroundPhaseBand = null;

    private int windowSize = 0;
    private int halfWindowSize = 0;
    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private double hoa = 0.0;       // height of ambiguity
    private double theta0 = 0.0;    // nominal angle of incidence at scene center
    private double k = 0.0;
    private double cohAmpNoDataValue = 0.0;
    private double cohPhaseNoDataValue = 0.0;
    private double groundPhaseNoDataValue = 0.0;
    private double incNoDataValue = 0.0;
    private MetadataElement absRoot = null;
    private boolean isA1 = true;

    private static final String COHERENCE_AMPLITUDE = "coherence_amplitude";
    private static final String COHERENCE_PHASE = "coherence_phase";
    private static final String GROUND_PHASE = "ground_phase";

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public DualPolForestHeightEstimationOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.snap.core.datamodel.Product}
     * annotated with the {@link org.esa.snap.core.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            windowSize = Integer.parseInt(windowSizeStr);
            halfWindowSize = windowSize / 2;
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            checkMission();

            getMetadata();

            computeK();

            getSourceBands();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkMission() {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
            if (!mission.startsWith("TDM") || !productType.contains("COSSC")) {
                throw new OperatorException("TanDEM-X COSSC product is expected.");
            }
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Get CoSSC metadata.
     */
    private void getMetadata() throws Exception {

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        final MetadataElement root = sourceProduct.getMetadataRoot();
        if (root == null) {
            throw new OperatorException("Root metadata not found");
        }

        final MetadataElement coSSCMetadata = root.getElement("CoSSC_Metadata");
        if (coSSCMetadata == null) {
            throw new OperatorException("CoSSC metadata not found");
        }

        final MetadataElement cosscProductElem = coSSCMetadata.getElement("cossc_product");
        if (cosscProductElem == null) {
            throw new OperatorException("cossc_product metadata not found");
        }

        final MetadataElement commonAcquisitionInfoElem = cosscProductElem.getElement("commonAcquisitionInfo");
        if (commonAcquisitionInfoElem == null) {
            throw new OperatorException("commonAcquisitionInfo metadata not found");
        }

        final MetadataElement acquisitionGeometryElem = commonAcquisitionInfoElem.getElement("acquisitionGeometry");
        if (acquisitionGeometryElem == null) {
            throw new OperatorException("acquisitionGeometry metadata not found");
        }

        hoa = Math.abs(acquisitionGeometryElem.getAttributeDouble("heightOfAmbiguity"));

        final MetadataElement commonSceneInfoElem = cosscProductElem.getElement("commonSceneInfo");
        if (commonSceneInfoElem == null) {
            throw new OperatorException("commonSceneInfoElem metadata not found");
        }

        final MetadataElement sceneCenterCoordElem = commonSceneInfoElem.getElement("sceneCenterCoord");
        if (sceneCenterCoordElem == null) {
            throw new OperatorException("sceneCenterCoord metadata not found");
        }

        theta0 = sceneCenterCoordElem.getAttributeDouble("incidenceAngle");
    }

    private void computeK() {
        k = Constants._TWO_PI * FastMath.sin(theta0 * Constants.DTOR) / hoa;
    }

    private void getSourceBands() {

        final Band[] srcBands = sourceProduct.getBands();

        for (Band band : srcBands) {
            if (band instanceof VirtualBand) {
                continue;
            }

            final String bandName = band.getName().toLowerCase();
            if (bandName.contains(COHERENCE_AMPLITUDE)) {
                cohAmpBand = band;
            } else if (bandName.contains(COHERENCE_PHASE)) {
                cohPhaseBand = band;
            } else if (bandName.contains(GROUND_PHASE)) {
                groundPhaseBand = band;
            } else if (bandName.contains("incidence")) {
                incidenceBand = band;
            }
        }

        isA1 = srcBands.length == 2;

        if (isA1 && (cohAmpBand == null || incidenceBand == null)) {
            throw new OperatorException("Coherence amplitude and incidence angle bands are expected.");
        }

        if (!isA1 && (cohAmpBand == null || cohPhaseBand == null || groundPhaseBand == null || incidenceBand == null)) {
            throw new OperatorException("Coherence amplitude, phase, ground phase and incidence angle bands are expected.");
        }

        cohAmpNoDataValue = cohAmpBand.getNoDataValue();
        incNoDataValue = incidenceBand.getNoDataValue();
        if (!isA1) {
            cohPhaseNoDataValue = cohPhaseBand.getNoDataValue();
            groundPhaseNoDataValue = groundPhaseBand.getNoDataValue();
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(
                sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        heightBand = new Band("forestHeight", ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
        heightBand.setUnit(Unit.METERS);
        targetProduct.addBand(heightBand);

        if (dumpResult) {
            ProductUtils.copyBand(cohAmpBand.getName(), sourceProduct, cohAmpBand.getName(), targetProduct, true);

            kzBand = new Band(
                    "Kz",
                    ProductData.TYPE_FLOAT32,
                    sourceImageWidth,
                    sourceImageHeight);

            kzBand.setUnit("radians/m");
            targetProduct.addBand(kzBand);

            if (!isA1) {
                ProductUtils.copyBand(cohPhaseBand.getName(), sourceProduct, cohPhaseBand.getName(), targetProduct, true);
                ProductUtils.copyBand(incidenceBand.getName(), sourceProduct, incidenceBand.getName(), targetProduct, true);

                filteredGroundPhaseBand = new Band(
                        "filteredGroundPhase",
                        ProductData.TYPE_FLOAT32,
                        sourceImageWidth,
                        sourceImageHeight);

                filteredGroundPhaseBand.setUnit(Unit.PHASE);
                targetProduct.addBand(filteredGroundPhaseBand);
            }
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancellation requests.
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        final int yMax = y0 + h;
        final int xMax = x0 + w;
        //System.out.println("Do: tx0 = " + tx0 + " ty0 = " + ty0 + " tw = " + tw + " th = " + th);

        try {
            final Tile incidenceTile = getSourceTile(incidenceBand, targetRectangle);
            final ProductData incidenceData = incidenceTile.getDataBuffer();
            final Tile cohAmpTile = getSourceTile(cohAmpBand, targetRectangle);
            final ProductData cohAmpData = cohAmpTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(incidenceTile);

            final Tile heightTile = targetTileMap.get(heightBand);
            final ProductData heightData = heightTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(heightTile);

            ProductData kzData = null;
            if (dumpResult) {
                final Tile kzTile = targetTileMap.get(kzBand);
                kzData = kzTile.getDataBuffer();
            }

            if (isA1) {
                for (int y = y0; y < yMax; ++y) {
                    tgtIndex.calculateStride(y);
                    srcIndex.calculateStride(y);
                    for (int x = x0; x < xMax; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int srcIdx = srcIndex.getIndex(x);

                        final double cohAmp = cohAmpData.getElemDoubleAt(srcIdx);
                        if (cohAmp == cohAmpNoDataValue)
                            continue;

                        final double theta = incidenceData.getElemDoubleAt(srcIdx);
                        if (theta == incNoDataValue)
                            continue;

                        final double kz = k / FastMath.sin(theta * Constants.DTOR);
                        if (dumpResult && kzData != null) {
                            kzData.setElemFloatAt(tgtIdx, (float) kz);
                        }

                        final double height = Constants._TWO_PI *
                                (1 - 2.0 * FastMath.asin(FastMath.pow(cohAmp, 0.8)) / Math.PI) / Math.abs(kz);

                        heightData.setElemFloatAt(tgtIdx, (float) height);
                    }
                }

            } else { // A2

                ProductData filteredGroundPhaseData = null;
                if (dumpResult) {
                    final Tile filteredGroundPhaseTile = targetTileMap.get(filteredGroundPhaseBand);
                    filteredGroundPhaseData = filteredGroundPhaseTile.getDataBuffer();
                }

                final Tile cohPhaseTile = getSourceTile(cohPhaseBand, targetRectangle);
                final ProductData cohPhaseData = cohPhaseTile.getDataBuffer();

                final double[][] filteredGroundPhase = new double[h][w];
                filterGroundPhase(x0, y0, w, h, filteredGroundPhase);

                for (int y = y0; y < yMax; ++y) {
                    tgtIndex.calculateStride(y);
                    srcIndex.calculateStride(y);
                    final int yy = y - y0;
                    for (int x = x0; x < xMax; ++x) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int srcIdx = srcIndex.getIndex(x);
                        final int xx = x - x0;

                        final double cohAmp = cohAmpData.getElemDoubleAt(srcIdx);
                        if (cohAmp == cohAmpNoDataValue)
                            continue;

                        double cohPhase = cohPhaseData.getElemDoubleAt(srcIdx);
                        if (cohPhase == cohPhaseNoDataValue)
                            continue;

                        final double groundPhase = filteredGroundPhase[yy][xx];
                        if (groundPhase == groundPhaseNoDataValue)
                            continue;

                        if (dumpResult && filteredGroundPhaseData != null) {
                            filteredGroundPhaseData.setElemFloatAt(tgtIdx, (float) groundPhase);
                        }

                        final double theta = incidenceData.getElemDoubleAt(srcIdx);
                        if (theta == incNoDataValue)
                            continue;

                        final double kz = k / FastMath.sin(theta * Constants.DTOR);
                        if (dumpResult && kzData != null) {
                            kzData.setElemFloatAt(tgtIdx, (float) kz);
                        }

                        double diffPhase = cohPhase - groundPhase;
                        if (diffPhase < 0.0)
                            diffPhase += Constants._TWO_PI;

                        final double height = (diffPhase +
                                0.8 * (Math.PI - 2.0 * FastMath.asin(FastMath.pow(cohAmp, 0.8)))) / Math.abs(kz);

                        heightData.setElemFloatAt(tgtIdx, (float) height);
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void filterGroundPhase(
            final int x0, final int y0, final int w, final int h, final double[][] filteredGroundPhase) {

        final Rectangle sourceRectangle = getSourceRectangle(x0, y0, w, h);
        final Tile groundPhaseTile = getSourceTile(groundPhaseBand, sourceRectangle);
        final ProductData groundPhaseData = groundPhaseTile.getDataBuffer();
        final TileIndex srcIndex = new TileIndex(groundPhaseTile);

        final int yMax = y0 + h;
        final int xMax = x0 + w;
        for (int y = y0; y < yMax; ++y) {
            final int yy = y - y0;
            for (int x = x0; x < xMax; ++x) {
                filteredGroundPhase[yy][x - x0] = getMedianValue(x, y, groundPhaseData, srcIndex);
            }
        }
    }

    private Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th) {

        final int x0 = Math.max(0, tx0 - halfWindowSize);
        final int y0 = Math.max(0, ty0 - halfWindowSize);
        final int xMax = Math.min(tx0 + tw - 1 + halfWindowSize, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + halfWindowSize, sourceImageHeight - 1);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
    }

    private double getMedianValue(
            final int tx, final int ty, final ProductData groundPhaseData, final TileIndex srcIndex) {

        final int minX = Math.max(tx - halfWindowSize, 0);
        final int maxX = Math.min(tx + halfWindowSize, sourceImageWidth - 1);
        final int minY = Math.max(ty - halfWindowSize, 0);
        final int maxY = Math.min(ty + halfWindowSize, sourceImageHeight - 1);

        int k = 0;
        double[] groundPhaseArray = new double[windowSize * windowSize];
        for (int y = minY; y <= maxY; y++) {
            srcIndex.calculateStride(y);
            for (int x = minX; x <= maxX; x++) {
                final double groundPhase = groundPhaseData.getElemDoubleAt(srcIndex.getIndex(x));
                if (groundPhase != groundPhaseNoDataValue) {
                    groundPhaseArray[k++] = groundPhase;
                }
            }
        }

        if (k == 0) {
            return groundPhaseNoDataValue;
        } else if (k == groundPhaseArray.length) {
            Arrays.sort(groundPhaseArray);
            return groundPhaseArray[k / 2];
        } else {
            final double[] tmpArray = new double[k];
            System.arraycopy(groundPhaseArray, 0, tmpArray, 0, k);
            Arrays.sort(tmpArray);
            return tmpArray[k / 2];
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator()
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(DualPolForestHeightEstimationOp.class);
        }
    }
}