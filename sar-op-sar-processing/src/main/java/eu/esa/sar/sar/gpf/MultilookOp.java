/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.sar.gpf;

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
import java.util.HashMap;

/**
 * Original SAR images generally appears with inherent speckle noise. Multi-look integration is one category
 * of methods to reduce this inherent speckle noise. The frequency-domain method consis of
 * <p/>
 * (1) dividing the bandwidth of the azimuth spectrum of the image into L segments (called looks),
 * (2) forming L independent images from these spectra, and
 * (3) incoherently averaing them.
 * <p/>
 * There is also a time-domain method which produce the multi-looked image by averaging the single look image
 * with a small sliding window.
 * <p/>
 * This operator implements the simple time-domain method. The multi-looked image is produced according to a
 * user specified factor and a default factor determined by range and azimuth spacings of the original image.
 * As a result, image with equal pixel spacing is produced.
 */

@OperatorMetadata(alias = "Multilook",
        category = "Radar/SAR Utilities",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Averages the power across a number of lines in both the azimuth and range directions")
public final class MultilookOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames;

    @Parameter(description = "The user defined number of range looks", interval = "[1, *)", defaultValue = "1",
            label = "Number of Range Looks")
    private int nRgLooks = 1;

    @Parameter(description = "The user defined number of azimuth looks", interval = "[1, *)", defaultValue = "1",
            label = "Number of Azimuth Looks")
    private int nAzLooks = 1;

    @Parameter(description = "For complex product output intensity or i and q", defaultValue = "false",
            label = "Output Intensity")
    private Boolean outputIntensity = false;

    @Parameter(description = "Use ground square pixel", defaultValue = "true", label = "GR Square Pixel")
    private Boolean grSquarePixel = true;

    private MetadataElement absRoot = null;

    private double azimuthLooks; // original azimuth_looks from metadata
    private double rangeLooks;   // original range_looks from metadata
    private int sourceImageWidth;
    private int sourceImageHeight;
    private int targetImageWidth;
    private int targetImageHeight;

    private double rangeSpacing;
    private double azimuthSpacing;
    private boolean isPolsar = false;

    private final HashMap<String, String[]> targetBandNameToSourceBandName = new HashMap<>();
    private static final String PRODUCT_SUFFIX = "_ML";

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
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfMapProjected(false);
            validator.checkIfTOPSARBurstProduct(false);

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            isPolsar = absRoot.getAttributeInt(AbstractMetadata.polsarData, 0) == 1;

            getRangeAzimuthSpacing();

            getRangeAzimuthLooks();

            getSourceImageDimension();

            if(grSquarePixel != null && grSquarePixel) {
                MultilookOp.DerivedParams param = new MultilookOp.DerivedParams();
                param.nRgLooks = nRgLooks;
                getDerivedParameters(sourceProduct, param);
                setNumAzimuthLooks(param.nAzLooks);
            }

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        final Rectangle targetTileRectangle = targetTile.getRectangle();
        final int tx0 = targetTileRectangle.x;
        final int ty0 = targetTileRectangle.y;
        final int tw = targetTileRectangle.width;
        final int th = targetTileRectangle.height;

        final int x0 = tx0 * nRgLooks;
        final int y0 = ty0 * nAzLooks;
        final int w = tw * nRgLooks;
        final int h = th * nAzLooks;
        final Rectangle sourceTileRectangle = new Rectangle(x0, y0, w, h);

        //System.out.println(targetBand.getName()+ " tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        try {
            Tile sourceRaster1;
            Tile sourceRaster2 = null;
            final String[] srcBandNames = targetBandNameToSourceBandName.get(targetBand.getName());
            Band sourceBand1;
            if (srcBandNames.length == 1) {
                sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
                sourceRaster1 = getSourceTile(sourceBand1, sourceTileRectangle);
                if (sourceRaster1 == null) {
                    throw new OperatorException("Cannot get source tile");
                }
            } else {
                sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
                final Band sourceBand2 = sourceProduct.getBand(srcBandNames[1]);
                sourceRaster1 = getSourceTile(sourceBand1, sourceTileRectangle);
                sourceRaster2 = getSourceTile(sourceBand2, sourceTileRectangle);
                if (sourceRaster1 == null || sourceRaster2 == null) {
                    throw new OperatorException("Cannot get source tile");
                }
            }

            final ProductData trgData = targetTile.getDataBuffer();

            final ProductData srcData1 = sourceRaster1.getDataBuffer();
            final ProductData srcData2 = sourceRaster2 != null ? sourceRaster2.getDataBuffer() : null;

            final TileIndex trgIndex = new TileIndex(targetTile);
            final TileIndex srcIndex = new TileIndex(sourceRaster1);

            final Unit.UnitType bandUnit = Unit.getUnitType(sourceBand1);
            final boolean isdB = bandUnit == Unit.UnitType.INTENSITY_DB || bandUnit == Unit.UnitType.AMPLITUDE_DB;
            final boolean isComplex = outputIntensity && (bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY);

            double meanValue;
            final int maxy = ty0 + th;
            final int maxx = tx0 + tw;
            if (nRgLooks == 1 && nAzLooks == 1) {
                //no mean
                if (!isComplex && targetTile.getDataBuffer().getType() == sourceRaster1.getDataBuffer().getType()) {
                    targetTile.setRawSamples(sourceRaster1.getRawSamples());
                } else {
                    for (int ty = ty0; ty < maxy; ty++) {
                        trgIndex.calculateStride(ty);
                        srcIndex.calculateStride(ty);
                        for (int tx = tx0; tx < maxx; tx++) {
                            final int index = srcIndex.getIndex(tx);
                            final double i = srcData1.getElemDoubleAt(index);
                            if (srcData2 != null) {
                                final double q = srcData2.getElemDoubleAt(index);
                                trgData.setElemDoubleAt(trgIndex.getIndex(tx), i * i + q * q);
                            } else {
                                trgData.setElemDoubleAt(trgIndex.getIndex(tx), i);
                            }
                        }
                    }
                }
            } else {
                for (int ty = ty0; ty < maxy; ty++) {
                    trgIndex.calculateStride(ty);
                    for (int tx = tx0; tx < maxx; tx++) {
                        meanValue = getMeanValue(
                                tx, ty, srcData1, srcData2, srcIndex, nRgLooks, nAzLooks, isdB, isComplex, isPolsar);
                        trgData.setElemDoubleAt(trgIndex.getIndex(tx), meanValue);
                    }
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Get the range and azimuth spacings (in meter).
     */
    private void getRangeAzimuthSpacing() {

        rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 1);
        azimuthSpacing = absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing, 1);
        //System.out.println("Range spacing is " + rangeSpacing);
        //System.out.println("Azimuth spacing is " + azimuthSpacing);
    }

    /**
     * Get azimuth and range looks.
     */
    private void getRangeAzimuthLooks() {

        azimuthLooks = absRoot.getAttributeDouble(AbstractMetadata.azimuth_looks, 1);
        rangeLooks = absRoot.getAttributeDouble(AbstractMetadata.range_looks, 1);
        //System.out.println("Azimuth looks is " + azimuthLooks);
        //System.out.println("Range looks is " + rangeLooks);
    }

    /**
     * Get source image dimension.
     */
    private void getSourceImageDimension() {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
        //System.out.println("Source image width = " + sourceImageWidth);
        //System.out.println("Source image height = " + sourceImageHeight);
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetImageWidth = sourceImageWidth / nRgLooks;
        targetImageHeight = sourceImageHeight / nAzLooks;

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                targetImageWidth,
                targetImageHeight);

        boolean noBandsSelected = sourceBandNames == null || sourceBandNames.length == 0;

        OperatorUtils.addSelectedBands(
                sourceProduct, sourceBandNames, targetProduct, targetBandNameToSourceBandName, outputIntensity, true);

        if (!outputIntensity && noBandsSelected) { // add virtual bands

            final Band[] bands = sourceProduct.getBands();
            for (Band band : bands) {
                if (band instanceof VirtualBand) {
                    VirtualBand srcBand = (VirtualBand) band;

                    final VirtualBand virtBand = new VirtualBand(srcBand.getName(), srcBand.getDataType(),
                            targetImageWidth, targetImageHeight, srcBand.getExpression());
                    virtBand.setUnit(srcBand.getUnit());
                    virtBand.setDescription(srcBand.getDescription());
                    virtBand.setNoDataValue(srcBand.getNoDataValue());
                    virtBand.setNoDataValueUsed(srcBand.isNoDataValueUsed());
                    virtBand.setOwner(targetProduct);
                    targetProduct.addBand(virtBand);
                }
            }
        }

        ProductUtils.copyMetadata(sourceProduct, targetProduct);
        //ProductUtils.copyTiePointGrids(sourceProduct, targetProduct);
        ProductUtils.copyFlagCodings(sourceProduct, targetProduct);
        //ProductUtils.copyGeoCoding(sourceProduct, targetProduct);
        ProductUtils.copyMasks(sourceProduct, targetProduct);
        ProductUtils.copyVectorData(sourceProduct, targetProduct);
        ProductUtils.copyIndexCodings(sourceProduct, targetProduct);
        targetProduct.setStartTime(sourceProduct.getStartTime());
        targetProduct.setEndTime(sourceProduct.getEndTime());
        targetProduct.setDescription(sourceProduct.getDescription());

        addGeoCoding();

        updateTargetProductMetadata();
    }

    private void addGeoCoding() {

        final int gridWidth = 11;
        final int gridHeight = 11;
        final float subSamplingX = targetImageWidth / (gridWidth - 1.0f);
        final float subSamplingY = targetImageHeight / (gridHeight - 1.0f);
        final PixelPos[] newTiePointPos = new PixelPos[gridWidth * gridHeight];

        int k = 0;
        for (int j = 0; j < gridHeight; j++) {
            final float y = (nAzLooks - 1) / 2.0f + Math.min(j * subSamplingY, targetImageHeight - 1) * nAzLooks;
            for (int i = 0; i < gridWidth; i++) {
                final float x = (nRgLooks - 1) / 2.0f + Math.min(i * subSamplingX, targetImageWidth - 1) * nRgLooks;
                newTiePointPos[k] = new PixelPos();
                newTiePointPos[k].x = x;
                newTiePointPos[k].y = y;
                k++;
            }
        }

        OperatorUtils.createNewTiePointGridsAndGeoCoding(
                sourceProduct,
                targetProduct,
                gridWidth,
                gridHeight,
                subSamplingX,
                subSamplingY,
                newTiePointPos);
    }

    /**
     * Update metadata in the target product.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.multilook_flag, 1);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.azimuth_looks, azimuthLooks * nAzLooks);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.range_looks, rangeLooks * nRgLooks);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.azimuth_spacing, azimuthSpacing * nAzLooks);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.range_spacing, rangeSpacing * nRgLooks);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, targetImageHeight);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, targetImageWidth);

        final float oldLineTimeInterval = (float) absTgt.getAttributeDouble(AbstractMetadata.line_time_interval);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.line_time_interval, oldLineTimeInterval * nAzLooks);

        final double oldNearEdgeSlantRange = absTgt.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
        final double newNearEdgeSlantRange = oldNearEdgeSlantRange + rangeSpacing * (nRgLooks - 1) / 2.0;
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.slant_range_to_first_pixel, newNearEdgeSlantRange);

        double oldFirstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        double newFirstLineUTC = oldFirstLineUTC + oldLineTimeInterval * ((nAzLooks - 1) / 2.0) / Constants.secondsInDay;
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_line_time, new ProductData.UTC(newFirstLineUTC));

        if(outputIntensity) {
            absTgt.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        }
    }

    /**
     * Compute the mean value of pixels of the source image in the sliding window.
     *
     * @param tx       The x coordinate of a pixel in the current target tile.
     * @param ty       The y coordinate of a pixel in the current target tile.
     * @param srcData1 The product data for i band in case of complex product.
     * @param srcData2 The product data for q band in case of complex product.
     * @param nRgLooks number of range looks
     * @param nAzLooks number of azimuth looks
     * @return The mean value.
     */
    private static double getMeanValue(final int tx, final int ty,
                                       final ProductData srcData1, final ProductData srcData2,
                                       final TileIndex srcIndex,
                                       final int nRgLooks, final int nAzLooks,
                                       final boolean isdB, final boolean isComplex, final boolean isPolsar) {

        final int xStart = tx * nRgLooks;
        final int yStart = ty * nAzLooks;
        final int xEnd = xStart + nRgLooks;
        final int yEnd = yStart + nAzLooks;

        double meanValue = 0.0;
        int offset;
        if (isdB) {
            for (int y = yStart; y < yEnd; y++) {
                offset = srcIndex.calculateStride(y);
                for (int x = xStart; x < xEnd; x++) {
                    meanValue += FastMath.pow(10, srcData1.getElemDoubleAt(x - offset) / 10.0); // dB to linear
                }
            }

            meanValue /= (nRgLooks * nAzLooks);
            return 10.0 * Math.log10(meanValue); // linear to dB
        } else if (isComplex && !isPolsar) { // COMPLEX
            double i, q;
            int index;
            for (int y = yStart; y < yEnd; y++) {
                offset = srcIndex.calculateStride(y);
                for (int x = xStart; x < xEnd; x++) {
                    index = x - offset;
                    i = srcData1.getElemDoubleAt(index);
                    q = srcData2.getElemDoubleAt(index);
                    meanValue += i * i + q * q;
                }
            }
        } else {
            for (int y = yStart; y < yEnd; y++) {
                offset = srcIndex.calculateStride(y);
                for (int x = xStart; x < xEnd; x++) {
                    meanValue += srcData1.getElemDoubleAt(x - offset);
                }
            }
        }

        return meanValue / (nRgLooks * nAzLooks);
    }

    /**
     * Compute number of azimuth looks and the mean ground pixel spacings for given number of range looks.
     *
     * @param srcProduct The source product.
     * @param param      The computed parameters.
     * @throws Exception The exception.
     */
    public static void getDerivedParameters(Product srcProduct, DerivedParams param) throws Exception {

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(srcProduct);
        final boolean srgrFlag = AbstractMetadata.getAttributeBoolean(abs, AbstractMetadata.srgr_flag);
        double rangeSpacing = abs.getAttributeDouble(AbstractMetadata.range_spacing, 1);
        double azimuthSpacing = abs.getAttributeDouble(AbstractMetadata.azimuth_spacing, 1);

        double groundRangeSpacing = rangeSpacing;
        if (rangeSpacing == AbstractMetadata.NO_METADATA) {
            azimuthSpacing = 1;
            groundRangeSpacing = 1;
        } else if (!srgrFlag) {
            final TiePointGrid incidenceAngle = OperatorUtils.getIncidenceAngle(srcProduct);
            if (incidenceAngle != null) {
                final double incidenceAngleAtCentreRangePixel = getIncidenceAngleAtCentreRangePixel(srcProduct,
                        incidenceAngle);
                groundRangeSpacing /= FastMath.sin(incidenceAngleAtCentreRangePixel * Constants.DTOR);
            }
        }

        //final int nAzLooks = Math.max(1, (int)((double)nRgLooks * groundRangeSpacing / azimuthSpacing + 0.5));
        final double nAzLooks = param.nRgLooks * groundRangeSpacing / azimuthSpacing;
        if (nAzLooks < 1.0) {
            param.nAzLooks = 1;
            param.nRgLooks = (int) Math.round(azimuthSpacing / groundRangeSpacing);
        } else {
            param.nAzLooks = (int) Math.round(nAzLooks);
        }
        param.meanGRSqaurePixel = (float) ((param.nRgLooks * groundRangeSpacing + param.nAzLooks * azimuthSpacing) * 0.5);
    }

    /**
     * Get incidence angle at centre range pixel (in degree).
     *
     * @param srcProduct     the source product
     * @param incidenceAngle The incidenceAngle tie point grid.
     * @return The incidence angle.
     * @throws OperatorException if incidenceAngle is null
     */
    private static double getIncidenceAngleAtCentreRangePixel(final Product srcProduct,
                                                              final TiePointGrid incidenceAngle) throws OperatorException {

        final int sourceImageWidth = srcProduct.getSceneRasterWidth();
        final int sourceImageHeight = srcProduct.getSceneRasterHeight();
        final int x = sourceImageWidth / 2;
        final int y = sourceImageHeight / 2;
        return incidenceAngle.getPixelDouble(x, y);
    }

    public static class DerivedParams {
        public int nAzLooks = 0;
        public int nRgLooks = 0;
        public float meanGRSqaurePixel = 0;
    }

    /**
     * Set the number of range looks. This method is for unit test only.
     *
     * @param numRangelooks The number of range looks.
     */
    public void setNumRangeLooks(int numRangelooks) {
        nRgLooks = numRangelooks;
    }

    /**
     * Set the number of azimuth looks. This method is for unit test only.
     *
     * @param numAzimuthlooks The number of azimuth looks.
     */
    public void setNumAzimuthLooks(int numAzimuthlooks) {
        nAzLooks = numAzimuthlooks;
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
            super(MultilookOp.class);
        }
    }
}
