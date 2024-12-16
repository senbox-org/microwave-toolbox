/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf;

import com.bc.ceres.core.ProgressMonitor;
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
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The REACTIV change detection operator.
 * <p/>
 * The operator implements the "Rapid and EAsy Change detection in radar TIme-series by Variation coefficient (REACTIV)"
 * algorithm proposed by Koeniguer et al. in [1]. The REACTIV algorithm filters the speckle noise by exploiting the
 * time dimension. Therefor the algorithm can not only to improve the signal-to-noise ratio, but also to detect all
 * the pixels for which a change occurred between the first and the last observation date. The REACTIV algorithm is
 * also a visualization tool. It highlights the detected changes in a stack of SAR images: where are the changes,
 * when they happened and what their intensities. By exploiting the HSV display, the algorithm presents all the change
 * information of the time series in one image.
 *
 * It is assumed that the input product is a stack of multiple co-registered Sentinel-1 IW VV-VH images of the same
 * pass: ascending or descending.
 *
 * [1]	Elise Colin Koeniguer, Alexandre Boulch, Pauline Trouve-Peloux and Fabrice Janez,
 * “Colored visualization of multitemporal data for change detection: issues and methods”, EUSAR, 2018.
 * <p/>
 */

@OperatorMetadata(alias = "REACTIV-Change-Detection",
        category = "Radar/SAR Applications",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2024 by SkyWatch Space Applications Inc.",
        description = "REACTIV Change Detection.")
public class ReactivOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct = null;

    @Parameter(description = "Include source bands", defaultValue = "false", label = "Include source bands")
    private boolean includeSourceBands = false;

    private int sourceImageWidth;
    private int sourceImageHeight;
    private String pol1 = null;
    private String pol2 = null;
    private boolean isComplex = false;
    private Band hueBand = null;
    private Band saturationBand = null;
    private Band valueBand = null;
    private String[] prodAcqDateArray = null;

    private static final String HUE_BAND_NAME = "hue";
    private static final String SATURATION_BAND_NAME = "saturation";
    private static final String VALUE_BAND_NAME = "value";

    @Override
    public void initialize() throws OperatorException {

        try {
            checkSourceProductValidity();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkSourceProductValidity() {

        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfSARProduct();
        validator.checkIfSentinel1Product();
        validator.checkIfCoregisteredStack();

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        if (!absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE).equals("IW")) {
            throw new OperatorException("Source product should be a coregistered stack of Sentinel-1 IW Dual-pol images");
        }

        isComplex = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE).contains("COMPLEX");
        pol1 = absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar);
        pol2 = absRoot.getAttributeString(AbstractMetadata.mds2_tx_rx_polar);
        prodAcqDateArray = getProdAcqDatesFromBands();
    }

    private void createTargetProduct() {

        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceImageWidth,
                sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        addSelectedBands();
    }

    private void addSelectedBands() {

        if (includeSourceBands) {
            final String[] masterBandNames = sourceProduct.getBandNames();
            for (String bandName : masterBandNames) {
                final Band srcBand = sourceProduct.getBand(bandName);
                if (srcBand instanceof VirtualBand) {
                    continue;
                }

                final Band targetBand = ProductUtils.copyBand(bandName, sourceProduct, bandName, targetProduct, true);
                if(targetBand != null && targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                    final int idx = targetProduct.getBandIndex(targetBand.getName());
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
                }
            }
        }

        hueBand = new Band(HUE_BAND_NAME, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
        targetProduct.addBand(hueBand);

        saturationBand = new Band(SATURATION_BAND_NAME, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
        targetProduct.addBand(saturationBand);

        valueBand = new Band(VALUE_BAND_NAME, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
        targetProduct.addBand(valueBand);
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
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        if (isComplex) {
            computeTileStackSLC(targetTiles, targetRectangle, pm);
        } else {
            computeTileStackGRD(targetTiles, targetRectangle, pm);
        }
    }

    private void computeTileStackSLC(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int maxX = tx0 + tw;
            final int maxY = ty0 + th;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            final ComplexTileData[] tileDataArray = createComplexTileDataArray(prodAcqDateArray, targetRectangle);
            final double[] minMaxTime = findMinMaxTime(tileDataArray);
            final double timeMin = minMaxTime[0];
            final double timeMax = minMaxTime[1];
            final TileIndex srcIndex = new TileIndex(tileDataArray[0].tileIPol1);

            final Tile hueTile = targetTiles.get(hueBand);
            final Tile satTile = targetTiles.get(saturationBand);
            final Tile valTile = targetTiles.get(valueBand);
            final ProductData hueData = hueTile.getDataBuffer();
            final ProductData satData = satTile.getDataBuffer();
            final ProductData valData = valTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(hueTile);

            for (int ty = ty0; ty < maxY; ty++) {
                tgtIndex.calculateStride(ty);
                srcIndex.calculateStride(ty);

                for (int tx = tx0; tx < maxX; tx++) {
                    final int tgtIdx = tgtIndex.getIndex(tx);
                    final int srcIdx = srcIndex.getIndex(tx);

                    final double[] valuesPol1 = new double[tileDataArray.length];
                    final double[] valuesPol2 = new double[tileDataArray.length];
                    getBandValues(tileDataArray, srcIdx, valuesPol1, valuesPol2);

                    final double hue = computeHue(valuesPol1, valuesPol2, timeMin, timeMax, tileDataArray);
                    hueData.setElemDoubleAt(tgtIdx, hue);

                    final double saturation = computeSaturation(valuesPol1, valuesPol2);
                    satData.setElemDoubleAt(tgtIdx, saturation);

                    final double value = computeValue(valuesPol1, valuesPol2);
                    valData.setElemDoubleAt(tgtIdx, value);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void computeTileStackGRD(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int maxX = tx0 + tw;
            final int maxY = ty0 + th;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            final TileData[] tileDataArray = createTileDataArray(prodAcqDateArray, targetRectangle);
            final double[] minMaxTime = findMinMaxTime(tileDataArray);
            final double timeMin = minMaxTime[0];
            final double timeMax = minMaxTime[1];
            final TileIndex srcIndex = new TileIndex(tileDataArray[0].tilePol1);

            final Tile hueTile = targetTiles.get(hueBand);
            final Tile satTile = targetTiles.get(saturationBand);
            final Tile valTile = targetTiles.get(valueBand);
            final ProductData hueData = hueTile.getDataBuffer();
            final ProductData satData = satTile.getDataBuffer();
            final ProductData valData = valTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(hueTile);

            for (int ty = ty0; ty < maxY; ty++) {
                tgtIndex.calculateStride(ty);
                srcIndex.calculateStride(ty);

                for (int tx = tx0; tx < maxX; tx++) {
                    final int tgtIdx = tgtIndex.getIndex(tx);
                    final int srcIdx = srcIndex.getIndex(tx);

                    final double[] valuesPol1 = new double[tileDataArray.length];
                    final double[] valuesPol2 = new double[tileDataArray.length];
                    getBandValues(tileDataArray, srcIdx, valuesPol1, valuesPol2);

                    final double hue = computeHue(valuesPol1, valuesPol2, timeMin, timeMax, tileDataArray);
                    hueData.setElemDoubleAt(tgtIdx, hue);

                    final double saturation = computeSaturation(valuesPol1, valuesPol2);
                    satData.setElemDoubleAt(tgtIdx, saturation);

                    final double value = computeValue(valuesPol1, valuesPol2);
                    valData.setElemDoubleAt(tgtIdx, value);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private String[] getProdAcqDatesFromBands() {

        final List<String> timeStampList = new ArrayList<>();
        final String[] bandNames = sourceProduct.getBandNames();
        for (String bandName : bandNames) {
            final String timeStamp = bandName.substring(bandName.lastIndexOf('_') + 1);
            if (!timeStampList.contains(timeStamp)) {
                timeStampList.add(timeStamp);
            }
        }
        return timeStampList.toArray(new String[0]);
    }

    private TileData[] createTileDataArray(final String[] prodAcqDateArray, final Rectangle rectangle) {

        final TileData[] tileDataArray = new TileData[prodAcqDateArray.length];
        for (int i = 0; i < prodAcqDateArray.length; ++i) {
            final String date = prodAcqDateArray[i];
            final Band bandPol1 = getBand(date, pol1);
            final Band bandPol2 = getBand(date, pol2);
            final Tile tilePol1 = getSourceTile(bandPol1, rectangle);
            final Tile tilePol2 = getSourceTile(bandPol2, rectangle);
            tileDataArray[i] = new TileData(date, bandPol1, bandPol2, tilePol1, tilePol2);
        }
        return tileDataArray;
    }

    private ComplexTileData[] createComplexTileDataArray(final String[] prodAcqDateArray, final Rectangle rectangle) {

        final ComplexTileData[] tileDataArray = new ComplexTileData[prodAcqDateArray.length];
        for (int i = 0; i < prodAcqDateArray.length; ++i) {
            final String date = prodAcqDateArray[i];
            final Band bandIPol1 = getBand("i_", date, pol1);
            final Band bandQPol1 = getBand("q_", date, pol1);
            final Band bandIPol2 = getBand("i_", date, pol2);
            final Band bandQPol2 = getBand("q_", date, pol2);
            final Tile tileIPol1 = getSourceTile(bandIPol1, rectangle);
            final Tile tileQPol1 = getSourceTile(bandQPol1, rectangle);
            final Tile tileIPol2 = getSourceTile(bandIPol2, rectangle);
            final Tile tileQPol2 = getSourceTile(bandQPol2, rectangle);
            tileDataArray[i] = new ComplexTileData(date, bandIPol1, bandQPol1, bandIPol2, bandQPol2, tileIPol1,
                    tileQPol1, tileIPol2, tileQPol2);
        }
        return tileDataArray;
    }

    private Band getBand(final String date, final String pol) {

        for (Band band : sourceProduct.getBands()) {
            final String bandName = band.getName();
            if (bandName.contains(date) && bandName.contains(pol)) {
                return band;
            }
        }
        throw new OperatorException("No source band found: containing date " + date + " and polarization " + pol);
    }

    private Band getBand(final String prefix, final String date, final String pol) {

        for (Band band : sourceProduct.getBands()) {
            final String bandName = band.getName();
            if (bandName.startsWith(prefix) && bandName.contains(date) && bandName.contains(pol)) {
                return band;
            }
        }
        throw new OperatorException("No source band found: starting with " + prefix + " and containing date " + date +
                " and polarization " + pol);
    }

    private double[] findMinMaxTime(final TileData[] tileDataArray) {

        double timeMin = tileDataArray[0].time;
        double timeMax = tileDataArray[0].time;
        for (int i = 1; i < tileDataArray.length; ++i) {
            if (timeMin > tileDataArray[i].time) {
                timeMin = tileDataArray[i].time;
            }
            if (timeMax < tileDataArray[i].time) {
                timeMax = tileDataArray[i].time;
            }
        }
        return new double[]{timeMin, timeMax};
    }

    private double[] findMinMaxTime(final ComplexTileData[] tileDataArray) {

        double timeMin = tileDataArray[0].time;
        double timeMax = tileDataArray[0].time;
        for (int i = 1; i < tileDataArray.length; ++i) {
            if (timeMin > tileDataArray[i].time) {
                timeMin = tileDataArray[i].time;
            }
            if (timeMax < tileDataArray[i].time) {
                timeMax = tileDataArray[i].time;
            }
        }
        return new double[]{timeMin, timeMax};
    }

    private void getBandValues(final TileData[] tileDataArray, final int srcIdx,
                                   final double[] valuesPol1, final double[] valuesPol2) {

        for (int i = 0; i < tileDataArray.length; ++i) {
            valuesPol1[i] = tileDataArray[i].dataBufferPol1.getElemDoubleAt(srcIdx);
            valuesPol2[i] = tileDataArray[i].dataBufferPol2.getElemDoubleAt(srcIdx);
        }
    }

    private void getBandValues(final ComplexTileData[] tileDataArray, final int srcIdx,
                               final double[] valuesPol1, final double[] valuesPol2) {

        for (int i = 0; i < tileDataArray.length; ++i) {
            final double iPol1 = tileDataArray[i].dataBufferIPol1.getElemDoubleAt(srcIdx);
            final double qPol1 = tileDataArray[i].dataBufferQPol1.getElemDoubleAt(srcIdx);
            valuesPol1[i] = Math.sqrt(iPol1 * iPol1 + qPol1 * qPol1);

            final double iPol2 = tileDataArray[i].dataBufferIPol2.getElemDoubleAt(srcIdx);
            final double qPol2 = tileDataArray[i].dataBufferQPol2.getElemDoubleAt(srcIdx);
            valuesPol2[i] = Math.sqrt(iPol2 * iPol2 + qPol2 * qPol2);
        }
    }

    private double computeHue(final double[] valuesPol1, final double[] valuesPol2,
                              final double timeMin, final double timeMax, final TileData[] tileDataArray) {

        double max = Double.MIN_VALUE;
        int index = 0;
        for (int i = 0; i < valuesPol1.length; ++i) {
            final double v = Math.max(valuesPol1[i], valuesPol2[i]);
            if (max < v) {
                max = v;
                index = i;
            }
        }
        return 0.9 * (timeMax - tileDataArray[index].time) / (timeMax - timeMin);
    }

    private double computeHue(final double[] valuesPol1, final double[] valuesPol2,
                              final double timeMin, final double timeMax, final ComplexTileData[] tileDataArray) {

        double max = Double.MIN_VALUE;
        int index = 0;
        for (int i = 0; i < valuesPol1.length; ++i) {
            final double v = Math.max(valuesPol1[i], valuesPol2[i]);
            if (max < v) {
                max = v;
                index = i;
            }
        }
        return 0.9 * (timeMax - tileDataArray[index].time) / (timeMax - timeMin);
    }

    private double computeSaturation(final double[] valuesPol1, final double[] valuesPol2) {

        final double[] meanAndSTDPol1 = computeMeanAndSTD(valuesPol1);
        final double[] meanAndSTDPol2 = computeMeanAndSTD(valuesPol2);
        final double varCoefPol1 = meanAndSTDPol1[1] / meanAndSTDPol1[0];
        final double varCoefPol2 = meanAndSTDPol2[1] / meanAndSTDPol2[0];
        return (Math.max(varCoefPol1, varCoefPol2) - 0.2286) / (10.0 * 0.1616) + 0.25;
    }

    private double[] computeMeanAndSTD(final double[] values) {

        double sum = 0.0, sum2 = 0.0;
        for(double v : values) {
            sum += v;
            sum2 += v * v;
        }
        final double mean = sum / values.length;
        final double mean2 = sum2 / values.length;
        final double std = Math.sqrt(mean2 - mean * mean);
        return new double[]{mean, std};
    }

    private double computeValue(final double[] valuesPol1, final double[] valuesPol2) {

        double sum = 0.0;
        double maxValue = Double.MIN_VALUE;
        for (int i = 0; i < valuesPol1.length; ++i) {
            double max = Math.max(valuesPol1[i], valuesPol2[i]);
            sum += max;
            maxValue = Math.max(maxValue, max);
        }
        final double meanOfMax = sum / valuesPol1.length;
        return 0.4 * (maxValue + meanOfMax);
    }

    private static double dateToTime(final String dateStr) {
        // dateStr in ddMMMyyyy format
        final int day = Integer.parseInt(dateStr.substring(0, 2));
        final String month = dateStr.substring(2, 5).toUpperCase();
        final int year = Integer.parseInt(dateStr.substring(5));
        final String timeStr = day + "-" + month + "-" + year + " 00:00:00.000000";
        return AbstractMetadata.parseUTC(timeStr).getMJD();
    }

    private static class TileData {
        final String dateStr;
        final double time;
        final Band bandPol1;
        final Band bandPol2;
        final Tile tilePol1;
        final Tile tilePol2;
        final ProductData dataBufferPol1;
        final ProductData dataBufferPol2;
        final double noDataValuePol1;
        final double noDataValuePol2;

        TileData(final String dateStr, final Band bandPol1, final Band bandPol2, final Tile tilePol1,
                 final Tile tilePol2) {

            this.dateStr = dateStr;
            this.time = dateToTime(dateStr);
            this.bandPol1 = bandPol1;
            this.bandPol2 = bandPol2;
            this.tilePol1 = tilePol1;
            this.tilePol2 = tilePol2;
            this.dataBufferPol1 = tilePol1.getDataBuffer();
            this.dataBufferPol2 = tilePol2.getDataBuffer();
            this.noDataValuePol1 = bandPol1.getNoDataValue();
            this.noDataValuePol2 = bandPol2.getNoDataValue();
        }
    }

    private static class ComplexTileData {
        final String dateStr;
        final double time;
        final Band bandIPol1;
        final Band bandQPol1;
        final Band bandIPol2;
        final Band bandQPol2;
        final Tile tileIPol1;
        final Tile tileQPol1;
        final Tile tileIPol2;
        final Tile tileQPol2;
        final ProductData dataBufferIPol1;
        final ProductData dataBufferQPol1;
        final ProductData dataBufferIPol2;
        final ProductData dataBufferQPol2;
        final double noDataValueIPol1;
        final double noDataValueQPol1;
        final double noDataValueIPol2;
        final double noDataValueQPol2;

        ComplexTileData(final String dateStr, final Band bandIPol1, final Band bandQPol1, final Band bandIPol2,
                        final Band bandQPol2, final Tile tileIPol1, final Tile tileQPol1, final Tile tileIPol2,
                        final Tile tileQPol2) {

            this.dateStr = dateStr;
            this.time = dateToTime(dateStr);
            this.bandIPol1 = bandIPol1;
            this.bandQPol1 = bandQPol1;
            this.bandIPol2 = bandIPol2;
            this.bandQPol2 = bandQPol2;
            this.tileIPol1 = tileIPol1;
            this.tileQPol1 = tileQPol1;
            this.tileIPol2 = tileIPol2;
            this.tileQPol2 = tileQPol2;
            this.dataBufferIPol1 = tileIPol1.getDataBuffer();
            this.dataBufferQPol1 = tileQPol1.getDataBuffer();
            this.dataBufferIPol2 = tileIPol2.getDataBuffer();
            this.dataBufferQPol2 = tileQPol2.getDataBuffer();
            this.noDataValueIPol1 = bandIPol1.getNoDataValue();
            this.noDataValueQPol1 = bandQPol1.getNoDataValue();
            this.noDataValueIPol2 = bandIPol2.getNoDataValue();
            this.noDataValueQPol2 = bandQPol2.getNoDataValue();
        }
    }


    /**
     * Operator SPI.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(ReactivOp.class);
        }
    }
}
