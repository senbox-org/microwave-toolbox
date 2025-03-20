/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf.changedetection;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The change detection operator.
 * <p/>
 * The operator performs change detection by computing the ratio of log ratio of given image pair.
 * It is assumed that the input product is a stack of two co-registered images.
 * <p/>
 */

@OperatorMetadata(alias = "Change-Detection",
        category = "Raster/Change Detection",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2025 by SkyWatch Space Applications Inc.",
        description = "Log Ratio Change Detection.")
public class ChangeDetectionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct = null;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames = null;

    @Parameter(description = "Mask upper threshold", defaultValue = "2.0", label = "Mask upper threshold")
    private float maskUpperThreshold = 2.0f;

    @Parameter(description = "Mask lower threshold", defaultValue = "-2.0", label = "Mask lower threshold")
    private float maskLowerThreshold = -2.0f;

    @Parameter(description = "Include source bands", defaultValue = "false", label = "Include source bands")
    private boolean includeSourceBands = false;

    @Parameter(description = "Output Difference", defaultValue = "false", label = "Output Difference")
    private boolean outputDifference = false;

    @Parameter(description = "Output Ratio", defaultValue = "false", label = "Output Ratio")
    private boolean outputRatio = false;

    @Parameter(description = "Output Log Ratio", defaultValue = "true", label = "Output Log Ratio")
    private boolean outputLogRatio = true;

    @Parameter(description = "Output Normalized Ratio", defaultValue = "false", label = "Output Normalized Ratio")
    private boolean outputNormalizedRatio = false;

    private static final String DIFFERENCE_BAND_NAME = "difference";
    private static final String RATIO_BAND_NAME = "ratio";
    private static final String LOG_RATIO_BAND_NAME = "log_ratio";
    private static final String NORMALIZED_RATIO_BAND_NAME = "normalized_ratio";
    private static final String MASK_NAME = "_change";

    @Override
    public void initialize() throws OperatorException {

        try {
            if(!outputRatio && !outputLogRatio && !outputNormalizedRatio) {
                throw new OperatorException("Please select an output");
            }

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Create target product.
     *
     * @throws Exception The exception.
     */
    private void createTargetProduct() {

        int sourceImageWidth = sourceProduct.getSceneRasterWidth();
        int sourceImageHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        addSelectedBands();
    }

    /**
     * Add the user selected bands to target product.
     *
     * @throws OperatorException The exceptions.
     */
    private void addSelectedBands() throws OperatorException {

        if (sourceBandNames == null || sourceBandNames.length == 0) { // if user did not select any band
            final Band[] bands = sourceProduct.getBands();
            final List<String> bandNameList = new ArrayList<>(sourceProduct.getNumBands());
            for (Band band : bands) {
                if (band.getUnit() != null && band.getUnit().contains(Unit.INTENSITY)) {
                    bandNameList.add(band.getName());
                    if(bandNameList.size() == 2)
                        break;
                }
            }
            if(bandNameList.size() < 2) {
                bandNameList.clear();
                for (Band band : bands) {
                    if (band.getUnit() != null && band.getUnit().contains(Unit.AMPLITUDE)) {
                        bandNameList.add(band.getName());
                        if(bandNameList.size() == 2)
                            break;
                    }
                }
            }
            if(bandNameList.size() < 2) {
                bandNameList.clear();
                for (Band band : bands) {
                    bandNameList.add(band.getName());
                    if(bandNameList.size() == 2)
                        break;
                }
            }
            sourceBandNames = bandNameList.toArray(new String[0]);
        }

        if (sourceBandNames.length != 2) {
            throw new OperatorException("Please select two source bands");
        }

        if(includeSourceBands) {
            for(String srcBandName : sourceBandNames) {
                ProductUtils.copyBand(srcBandName, sourceProduct, targetProduct, true);
            }
        }

        Band srcBand1 = sourceProduct.getBand(sourceBandNames[0]);
        String expression = null;
        Band targetBand = null;

        if (outputDifference) {
            final Band targetRatioBand = new Band(DIFFERENCE_BAND_NAME, ProductData.TYPE_FLOAT32,
                    srcBand1.getRasterWidth(), srcBand1.getRasterHeight());

            targetRatioBand.setNoDataValue(0);
            targetRatioBand.setNoDataValueUsed(true);
            targetRatioBand.setUnit("diff");

            targetProduct.addBand(targetRatioBand);

            expression = targetRatioBand.getName() + " > "+ maskUpperThreshold+" ? 1 : " + targetRatioBand.getName() + " < "+maskLowerThreshold+" ? -1 : 0";
            targetBand = targetRatioBand;
        }

        if (outputRatio) {
            final Band targetRatioBand = new Band(RATIO_BAND_NAME, ProductData.TYPE_FLOAT32,
                    srcBand1.getRasterWidth(), srcBand1.getRasterHeight());

            targetRatioBand.setNoDataValue(0);
            targetRatioBand.setNoDataValueUsed(true);
            targetRatioBand.setUnit("ratio");

            targetProduct.addBand(targetRatioBand);

            expression = targetRatioBand.getName() + " > "+ maskUpperThreshold+" ? 1 : " + targetRatioBand.getName() + " < "+maskLowerThreshold+" ? -1 : 0";
            targetBand = targetRatioBand;
        }

        if (outputLogRatio) {
            final Band targetRatioBand = new Band(LOG_RATIO_BAND_NAME, ProductData.TYPE_FLOAT32,
                    srcBand1.getRasterWidth(), srcBand1.getRasterHeight());

            targetRatioBand.setNoDataValue(0);
            targetRatioBand.setNoDataValueUsed(true);
            targetRatioBand.setUnit("log_ratio");
            targetProduct.addBand(targetRatioBand);

            expression = targetRatioBand.getName() + " > "+ maskUpperThreshold+" ? 1 : " + targetRatioBand.getName() + " < "+maskLowerThreshold+" ? -1 : 0";
            targetBand = targetRatioBand;
        }

        if (outputNormalizedRatio) {
            final Band targetRatioBand = new Band(NORMALIZED_RATIO_BAND_NAME, ProductData.TYPE_FLOAT32,
                    srcBand1.getRasterWidth(), srcBand1.getRasterHeight());

            targetRatioBand.setNoDataValue(0);
            targetRatioBand.setNoDataValueUsed(true);
            targetRatioBand.setUnit("ratio");
            targetProduct.addBand(targetRatioBand);

            expression = targetRatioBand.getName() + " > 0.2 ? 1 : " + targetRatioBand.getName() + " < 0 ? -1 : 0";
            targetBand = targetRatioBand;
        }

        //create Mask
        final Mask mask = new Mask(targetBand.getName() + MASK_NAME,
                targetBand.getRasterWidth(),
                targetBand.getRasterHeight(),
                Mask.BandMathsType.INSTANCE);

        mask.setDescription("Change");
        mask.getImageConfig().setValue("color", Color.RED);
        mask.getImageConfig().setValue("transparency", 0.7);
        mask.getImageConfig().setValue("expression", expression);
        mask.setNoDataValue(0);
        mask.setNoDataValueUsed(true);
        targetProduct.getMaskGroup().add(mask);
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            final Band nominatorBand = sourceProduct.getBand(sourceBandNames[0]);
            final Band denominatorBand = sourceProduct.getBand(sourceBandNames[1]);
            final Tile nominatorTile = getSourceTile(nominatorBand, targetRectangle);
            final Tile denominatorTile = getSourceTile(denominatorBand, targetRectangle);
            final ProductData nominatorData = nominatorTile.getDataBuffer();
            final ProductData denominatorData = denominatorTile.getDataBuffer();
            final Double noDataValueN = nominatorBand.getNoDataValue();
            final Double noDataValueD = denominatorBand.getNoDataValue();

            ProductData diffData = null, ratioData = null, logRatioData = null, normalizedRatioData = null;
            if(outputDifference) {
                final Band targetRatioBand = targetProduct.getBand(DIFFERENCE_BAND_NAME);
                final Tile targetRatioTile = targetTiles.get(targetRatioBand);
                diffData = targetRatioTile.getDataBuffer();
            }
            if(outputRatio) {
                final Band targetRatioBand = targetProduct.getBand(RATIO_BAND_NAME);
                final Tile targetRatioTile = targetTiles.get(targetRatioBand);
                ratioData = targetRatioTile.getDataBuffer();
            }
            if(outputLogRatio) {
                final Band targetRatioBand = targetProduct.getBand(LOG_RATIO_BAND_NAME);
                final Tile targetRatioTile = targetTiles.get(targetRatioBand);
                logRatioData = targetRatioTile.getDataBuffer();
            }
            if(outputNormalizedRatio) {
                final Band targetRatioBand = targetProduct.getBand(NORMALIZED_RATIO_BAND_NAME);
                final Tile targetRatioTile = targetTiles.get(targetRatioBand);
                normalizedRatioData = targetRatioTile.getDataBuffer();
            }

            final TileIndex trgIndex = new TileIndex(targetTiles.get(targetTiles.keySet().iterator().next()));
            final TileIndex srcIndex = new TileIndex(nominatorTile);

            final int maxy = ty0 + th;
            final int maxx = tx0 + tw;

            double vRatio;
            for (int ty = ty0; ty < maxy; ty++) {
                trgIndex.calculateStride(ty);
                srcIndex.calculateStride(ty);
                for (int tx = tx0; tx < maxx; tx++) {
                    final int trgIdx = trgIndex.getIndex(tx);
                    final int srcIdx = srcIndex.getIndex(tx);

                    final double vN = nominatorData.getElemDoubleAt(srcIdx);
                    final double vD = denominatorData.getElemDoubleAt(srcIdx);
                    if (noDataValueN.equals(vN) || noDataValueD.equals(vD) || vN <= 0.0 || vD <= 0.0) {
                        if(outputDifference) {
                            diffData.setElemFloatAt(trgIdx, 0.0f);
                        }
                        if(outputRatio) {
                            ratioData.setElemFloatAt(trgIdx, 0.0f);
                        }
                        if(outputLogRatio) {
                            logRatioData.setElemFloatAt(trgIdx, 0.0f);
                        }
                        if(outputNormalizedRatio) {
                            normalizedRatioData.setElemFloatAt(trgIdx, 0.0f);
                        }
                        continue;
                    }

                    vRatio = vN / vD;
                    if(outputDifference) {
                        diffData.setElemFloatAt(trgIdx, (float) (vN - vD));
                    }
                    if(outputRatio) {
                        ratioData.setElemFloatAt(trgIdx, (float) vRatio);
                    }
                    if(outputLogRatio) {
                        vRatio = Math.log(Math.max(vRatio, Constants.EPS));
                        logRatioData.setElemFloatAt(trgIdx, (float) vRatio);
                    }
                    if(outputNormalizedRatio) {
                        double normalizedValue = (vN - vD) / (vN + vD);
                        normalizedRatioData.setElemFloatAt(trgIdx, (float) normalizedValue);
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Operator SPI.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(ChangeDetectionOp.class);
        }
    }
}
