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
package eu.esa.sar.sentinel1.gpf;

import eu.esa.sar.sentinel1.gpf.etadcorrectors.ETADUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.*;

import javax.media.jai.PlanarImage;
import javax.media.jai.RasterFactory;
import java.awt.*;
import java.awt.image.*;
import java.util.Arrays;
import java.util.Hashtable;

/**
 * The operator debursts ETAD product for user selected swath and correction layer.
 */
@OperatorMetadata(alias = "ETAD-Deburst",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2025 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "Deburst ETAD product")
public class ETADDeburstOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"IW1", "IW2", "IW3", "All"}, defaultValue = "IW1", label = "Swath")
    private String selectedSwath = "IW1";

    @Parameter(valueSet = {"troposphericCorrectionRg", "ionosphericCorrectionRg", "geodeticCorrectionRg",
    "dopplerRangeShiftRg", "geodeticCorrectionAz", "bistaticCorrectionAz", "fmMismatchCorrectionAz",
    "sumOfCorrectionsAz", "sumOfCorrectionsRg"}, defaultValue = "ionosphericCorrectionRg", label = "Correction layer")
    private String selectedLayer = "ionosphericCorrectionRg";

    private ETADUtils etadUtils = null;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public ETADDeburstOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            checkProductValidity();

            etadUtils = new ETADUtils(sourceProduct);

            final int pIndex = 1;
            final Dimension targetDimension = computeTargetProductDimension(pIndex);

            final RenderedImage targetImage = createTargetImage(pIndex, targetDimension);

            createTargetProduct(targetDimension, targetImage);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkProductValidity() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        if (!productType.equals("ETAD")) {
            throw new OperatorException("Sentinel-1 ETAD product is expected");
        }
    }

    private Dimension computeTargetProductDimension(final int pIndex) {

        int numLines, numPixels;
        if (selectedSwath.equals("All")) {
            final TemporalCoverage sw1 = getSwathTemporalCoverage(pIndex, 1);
            final TemporalCoverage sw2 = getSwathTemporalCoverage(pIndex, 2);
            final TemporalCoverage sw3 = getSwathTemporalCoverage(pIndex, 3);
            final double firstLineTime = Math.min(Math.min(sw1.azimuthTimeMin, sw2.azimuthTimeMin), sw3.azimuthTimeMin);
            final double lasLineTime = Math.max(Math.max(sw1.azimuthTimeMax, sw2.azimuthTimeMax), sw3.azimuthTimeMax);
            numLines = (int)Math.round((lasLineTime - firstLineTime) / sw1.gridSamplingAzimuth) + 1;
            numPixels = (int)Math.round((sw3.rangeTimeMax - sw1.rangeTimeMin) / sw1.gridSamplingRange) + 1;
        } else {
            final int sIndex = switch (selectedSwath) {
                case "IW1" -> 1;
                case "IW2" -> 2;
                case "IW3" -> 3;
                default -> -1;
            };
            final TemporalCoverage sw = getSwathTemporalCoverage(pIndex, sIndex);
            numLines = (int)Math.round((sw.azimuthTimeMax - sw.azimuthTimeMin) / sw.gridSamplingAzimuth) + 1;
            numPixels = (int)Math.round((sw.rangeTimeMax - sw.rangeTimeMin) / sw.gridSamplingRange) + 1;
        }
        return new Dimension(numPixels, numLines);
    }

    private RenderedImage createTargetImage(final int pIndex, final Dimension targetDimension) {

        final double[] array = new double[targetDimension.height * targetDimension.width];
        Arrays.fill(array, Double.NaN);

        if (selectedSwath.equals("All")) {
            final TemporalCoverage asw = getAllSwathTemporalCoverage(pIndex);
            final int[] sIndexArray = new int[]{1, 2, 3};
            for (int sIndex : sIndexArray) {
                final int[] burstIndexArray = etadUtils.getBurstIndexArray(pIndex, sIndex);
                for (final int bIndex : burstIndexArray) {
                    final ETADUtils.Burst burst = etadUtils.getBurst(pIndex, sIndex, bIndex);
                    final String bandName = etadUtils.createBandName(burst.swathID, burst.bIndex, selectedLayer);
                    final double[][] layerCorrection = etadUtils.getLayerCorrectionForCurrentBurst(burst, bandName);

                    final int[] x0y0 = computeX0Y0(asw, burst);
                    final int x0 = x0y0[0];
                    final int y0 = x0y0[1];
                    for (int r = 0; r < burst.azimuthExtent; ++r) {
                        final int y = y0 + r;
                        for (int c = 0; c < burst.rangeExtent; ++c) {
                            final int x = x0 + c;
                            final int k = y * targetDimension.width + x;
                            array[k] = layerCorrection[r][c];
                        }
                    }
                }
            }

        } else {
            final int sIndex = switch (selectedSwath) {
                case "IW1" -> 1;
                case "IW2" -> 2;
                case "IW3" -> 3;
                default -> -1;
            };

            final TemporalCoverage sw = getSwathTemporalCoverage(pIndex, sIndex);
            final int[] burstIndexArray = etadUtils.getBurstIndexArray(pIndex, sIndex);
            for (final int bIndex : burstIndexArray) {
                final ETADUtils.Burst burst = etadUtils.getBurst(pIndex, sIndex, bIndex);
                final String bandName = etadUtils.createBandName(burst.swathID, burst.bIndex, selectedLayer);
                final double[][] layerCorrection = etadUtils.getLayerCorrectionForCurrentBurst(burst, bandName);

                final int[] x0y0 = computeX0Y0(sw, burst);
                final int x0 = x0y0[0];
                final int y0 = x0y0[1];
                for (int r = 0; r < burst.azimuthExtent; ++r) {
                    final int y = y0 + r;
                    for (int c = 0; c < burst.rangeExtent; ++c) {
                        final int x = x0 + c;
                        final int k = y * targetDimension.width + x;
                        array[k] = layerCorrection[r][c];
                    }
                }
            }
        }

        return createRenderedImage(array, targetDimension.width, targetDimension.height);
    }

    private TemporalCoverage getSwathTemporalCoverage(final int pIndex, final int sIndex) {

        final int[] burstIndexArray = etadUtils.getBurstIndexArray(pIndex, sIndex);
        final ETADUtils.Burst firstBurst = etadUtils.getBurst(pIndex, sIndex, burstIndexArray[0]);
        final ETADUtils.Burst lastBurst = etadUtils.getBurst(pIndex, sIndex, burstIndexArray[burstIndexArray.length-1]);

        return new TemporalCoverage(firstBurst.rangeTimeMin, firstBurst.rangeTimeMax, firstBurst.gridSamplingRange,
                firstBurst.azimuthTimeMin, lastBurst.azimuthTimeMax, firstBurst.gridSamplingAzimuth);
    }

    private TemporalCoverage getAllSwathTemporalCoverage(final int pIndex) {

        final TemporalCoverage sw1 = getSwathTemporalCoverage(pIndex, 1);
        final TemporalCoverage sw2 = getSwathTemporalCoverage(pIndex, 2);
        final TemporalCoverage sw3 = getSwathTemporalCoverage(pIndex, 3);
        final double azimuthTimeMin = Math.min(Math.min(sw1.azimuthTimeMin, sw2.azimuthTimeMin), sw3.azimuthTimeMin);
        final double azimuthTimeMax = Math.max(Math.max(sw1.azimuthTimeMax, sw2.azimuthTimeMax), sw3.azimuthTimeMax);

        return new TemporalCoverage(sw1.rangeTimeMin, sw3.rangeTimeMax, sw1.gridSamplingRange,
                azimuthTimeMin, azimuthTimeMax, sw1.gridSamplingAzimuth);
    }

    private int[] computeX0Y0(final TemporalCoverage sw, final ETADUtils.Burst burst) {

        final int x0 = (int)Math.round((burst.rangeTimeMin - sw.rangeTimeMin) / sw.gridSamplingRange);
        final int y0 = (int)Math.round((burst.azimuthTimeMin - sw.azimuthTimeMin) / sw.gridSamplingAzimuth);
        return new int[]{x0, y0};
    }

    private static RenderedImage createRenderedImage(final double[] array, final int width, final int height) {

        final SampleModel sampleModel = RasterFactory.createBandedSampleModel(DataBuffer.TYPE_DOUBLE, width, height, 1);
        final ColorModel colourModel = PlanarImage.createColorModel(sampleModel);
        final DataBufferDouble dataBuffer = new DataBufferDouble(array, array.length);
        final WritableRaster raster = RasterFactory.createWritableRaster(sampleModel, dataBuffer, new Point(0, 0));
        return new BufferedImage(colourModel, raster, false, new Hashtable());
    }

    private void createTargetProduct(final Dimension targetDimension, final RenderedImage targetImage) {

        targetProduct = new Product(sourceProduct.getName(), sourceProduct.getProductType(),
                targetDimension.width, targetDimension.height);
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
        final String bandName = selectedSwath.equals("All")?selectedLayer : selectedSwath + "_" + selectedLayer;
        final Band targetBand = targetProduct.addBand(bandName, ProductData.TYPE_FLOAT64);
        targetBand.setNoDataValue(Double.NaN);
        targetBand.setNoDataValueUsed(true);
        targetBand.setUnit("s");
        targetBand.setDescription(selectedLayer);
        targetBand.setSourceImage(targetImage);
    }

    public static class TemporalCoverage {
        public double rangeTimeMin;
        public double rangeTimeMax;
        public double gridSamplingRange;
        public double azimuthTimeMin;
        public double azimuthTimeMax;
        public double gridSamplingAzimuth;

        public TemporalCoverage(final double rangeTimeMin, final double rangeTimeMax, final double gridSamplingRange,
                                final double azimuthTimeMin, final double azimuthTimeMax, final double gridSamplingAzimuth) {
            this.rangeTimeMin = rangeTimeMin;
            this.rangeTimeMax = rangeTimeMax;
            this.gridSamplingRange = gridSamplingRange;
            this.azimuthTimeMin = azimuthTimeMin;
            this.azimuthTimeMax = azimuthTimeMax;
            this.gridSamplingAzimuth = gridSamplingAzimuth;
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(ETADDeburstOp.class);
        }
    }
}
