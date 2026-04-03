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
package eu.esa.sar.fex.gpf.changedetection;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.downloadable.StatusProgressMonitor;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.ThreadExecutor;
import org.esa.snap.core.util.ThreadRunnable;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.util.*;
import java.util.List;

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
        category = "Raster/Change Detection",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2024 by SkyWatch Space Applications Inc.",
        description = "REACTIV Change Detection.")
public class ReactivOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct = null;

    @Parameter(description = "Mask threshold", interval = "(0, 1)", defaultValue = "0.8", label = "Mask threshold")
    private float maskThreshold = 0.8f;

    @Parameter(description = "Include source bands", defaultValue = "false", label = "Include source bands")
    private boolean includeSourceBands = false;

    private int sourceImageWidth;
    private int sourceImageHeight;
    private boolean isComplex = false;
    private Band hueBand = null;
    private Band saturationBand = null;
    private Band valueBand = null;
    private double timeMin = 0.0;
    private double timeMax = 0.0;
    private String[] selectedPolarisations;
    private String[] prodAcqDateArray = null;
    private int numOfProducts = 0;
    private double threshold = 0.0;
    private boolean thresholdComputed = false; // threshold for Value normalization
    private MetadataElement absRoot = null;

    private static double noDataValue = 0.0;
    private static final String HUE_BAND_NAME = "hue";
    private static final String SATURATION_BAND_NAME = "saturation";
    private static final String VALUE_BAND_NAME = "value";
    private static final String MASK_NAME = "change";

    @Override
    public void initialize() throws OperatorException {

        try {
            checkSourceProductValidity();

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            isComplex = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE).contains("COMPLEX");
            if (isComplex) {
                createVirtualBand();
            }

            getSelectedPolarisations();

            getProdAcqDatesFromBands();

            createTargetProduct();

            findMinMaxTime();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkSourceProductValidity() {

        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfSARProduct();
        validator.checkIfCoregisteredStack();
    }

    private void createVirtualBand() {

        final int width = sourceProduct.getSceneRasterWidth();
        final int height = sourceProduct.getSceneRasterHeight();
        final Band[] sourceBands = sourceProduct.getBands();
        for (int i = 0; i < sourceBands.length; ++i) {
            final Band srcBand = sourceBands[i];
            if (srcBand instanceof VirtualBand) {
                continue;
            }

            if (srcBand.getUnit().equals(Unit.REAL) && (i == sourceBands.length - 1 ||
                    !sourceBands[i + 1].getUnit().equals(Unit.IMAGINARY))) {
                throw new OperatorException("Source bands are not provided in real and imaginary pairs");
            }

            final String iBandName = srcBand.getName();
            final String date = iBandName.substring(iBandName.lastIndexOf('_') + 1);
            final String pol = OperatorUtils.getPolarizationFromBandName(iBandName);
            if (getBand(date, pol) != null) {
                i++;
                continue;
            }

            final String qBandName = sourceBands[i + 1].getName();
            final String expression = iBandName + " * " + iBandName + " + " + qBandName + " * " + qBandName;
            final String virtBandName = "Intensity_" + iBandName.substring(iBandName.indexOf('_') + 1);
            final VirtualBand virtBand = new VirtualBand(virtBandName, ProductData.TYPE_FLOAT64, width, height, expression);
            virtBand.setNoDataValueUsed(true);
            sourceProduct.addBand(virtBand);
            i++;
        }
    }

    private void getSelectedPolarisations() {

        final List<String> polList = new ArrayList<>(4);
        final String[] sourceBandNames = sourceProduct.getBandNames();
        for (String bandName : sourceBandNames) {
            final String pol = OperatorUtils.getPolarizationFromBandName(bandName);
            if (!polList.contains(pol)) {
                polList.add(pol);
            }
        }

        if (polList.isEmpty()) {
            final String pol1 = absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar, "-");
            final String pol2 = absRoot.getAttributeString(AbstractMetadata.mds2_tx_rx_polar, "-");
            final String pol3 = absRoot.getAttributeString(AbstractMetadata.mds3_tx_rx_polar, "-");
            final String pol4 = absRoot.getAttributeString(AbstractMetadata.mds4_tx_rx_polar, "-");
            if (!pol1.equals("-")) polList.add(pol1);
            if (!pol2.equals("-")) polList.add(pol2);
            if (!pol3.equals("-")) polList.add(pol3);
            if (!pol4.equals("-")) polList.add(pol4);
        }

        selectedPolarisations = polList.toArray(new String[0]);
    }

    private void findMinMaxTime() {

        double tMin = Double.MAX_VALUE;
        double tMax = Double.MIN_VALUE;
        for (final String date : prodAcqDateArray) {
            final double time = dateToTime(date);
            if (tMin > time) {
                tMin = time;
            }
            if (tMax < time) {
                tMax = time;
            }
        }
        timeMin = tMin;
        timeMax = tMax;
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

        //create mask
        String expression = saturationBand.getName() + " > "+ maskThreshold + " ? 1 : 0";
        final Mask mask = new Mask(MASK_NAME, sourceImageWidth, sourceImageHeight, Mask.BandMathsType.INSTANCE);

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
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        try {
            if (!thresholdComputed) {
                computeThreshold();
            }

            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int maxX = x0 + w;
            final int maxY = y0 + h;
            //System.out.println("tx0 = " + tx0 + ", ty0 = " + ty0 + ", tw = " + tw + ", th = " + th);

            final HashMap<String, double[][]> sumPol = new HashMap<>();
            final HashMap<String, double[][]> sum2Pol = new HashMap<>();
            for (String pol : selectedPolarisations) {
                final double[][] sum = new double[h][w];
                final double[][] sum2 = new double[h][w];
                sumPol.put(pol, sum);
                sum2Pol.put(pol, sum2);
            }
            final double[][] sumMax = new double[h][w];
            final double[][] max = new double[h][w];
            final double[][] time = new double[h][w];

            for (String date : prodAcqDateArray) {
                final double timeProd = dateToTime(date);

                final HashMap<String, ProductData> dataBuffersPol = new HashMap<>();
                final HashMap<String, Double> noDataValuePol = new HashMap<>();
                TileIndex srcIndex = null;
                for (String pol : selectedPolarisations) {
                    final Band band = getBand(date, pol);
                    if (band == null) {
                        dataBuffersPol.put(pol, null);
                        noDataValuePol.put(pol, null);
                    } else {
                        final Tile tile = getSourceTile(band, targetRectangle);
                        dataBuffersPol.put(pol, tile.getDataBuffer());
                        noDataValuePol.put(pol, band.getNoDataValue());
                        if (srcIndex == null) {
                            srcIndex = new TileIndex(tile);
                        }
                    }
                }

                if (srcIndex == null) {
                    throw new OperatorException("No polarization found for source band containing date: " + date);
                }

                for (int y = y0; y < maxY; ++y) {
                    srcIndex.calculateStride(y);
                    final int yy = y - y0;

                    for (int x = x0; x < maxX; ++x) {
                        final int srcIdx = srcIndex.getIndex(x);
                        final int xx = x - x0;

                        final double vMax = getMaxValue(srcIdx, dataBuffersPol, noDataValuePol);
                        if (vMax == -9999) {
                            time[yy][xx] = -1.0;
                            continue;
                        }

                        sumMax[yy][xx] += vMax;
                        if (max[yy][xx] < vMax) {
                            max[yy][xx] = vMax;
                            time[yy][xx] = timeProd;
                        }

                        updateSumAndSum2(srcIdx, dataBuffersPol, xx, yy, sumPol, sum2Pol);
                    }
                }
            }

            final Tile hueTile = targetTiles.get(hueBand);
            final Tile satTile = targetTiles.get(saturationBand);
            final Tile valTile = targetTiles.get(valueBand);
            final ProductData hueData = hueTile.getDataBuffer();
            final ProductData satData = satTile.getDataBuffer();
            final ProductData valData = valTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(hueTile);

            for (int y = y0; y < maxY; ++y) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;

                for (int x = x0; x < maxX; ++x) {
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final int xx = x - x0;

                    if (time[yy][xx] == -1.0) {
                        hueData.setElemDoubleAt(tgtIdx, noDataValue);
                        satData.setElemDoubleAt(tgtIdx, noDataValue);
                        valData.setElemDoubleAt(tgtIdx, noDataValue);
                        continue;
                    }

                    final double hue = 0.9 * (time[yy][xx] - timeMin) / (timeMax - timeMin);
                    hueData.setElemDoubleAt(tgtIdx, hue);

                    final double maxVC = computeMaxVarianceCoefficient(xx, yy, sumPol, sum2Pol);
                    double saturation = (maxVC - 0.2286) / (10.0 * 0.1616) + 0.25;
                    saturation = saturation < 0?0:saturation > 1?1:saturation;
                    satData.setElemDoubleAt(tgtIdx, saturation);

                    final double meanOfMax = sumMax[yy][xx] / numOfProducts;
                    final double value = 0.4 * (max[yy][xx] + meanOfMax);
                    final double normValue = value < threshold? (value / threshold) : 1.0;
                    valData.setElemDoubleAt(tgtIdx, normValue);
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void getProdAcqDatesFromBands() {

        final List<String> timeStampList = new ArrayList<>();
        final String[] bandNames = sourceProduct.getBandNames();
        for (String bandName : bandNames) {
            final String timeStamp = bandName.substring(bandName.lastIndexOf('_') + 1);
            if (!timeStampList.contains(timeStamp)) {
                timeStampList.add(timeStamp);
            }
        }
        if (timeStampList.isEmpty()) {
            throw new OperatorException("No time stamp found in the source band names.");
        }
        prodAcqDateArray = timeStampList.toArray(new String[0]);
        numOfProducts = prodAcqDateArray.length;
    }

    private Band getBand(final String date, final String pol) {

        for (Band band : sourceProduct.getBands()) {
            final String bandName = band.getName();
            final String unit = band.getUnit();
            if ((unit.equals(Unit.AMPLITUDE) || unit.equals(Unit.INTENSITY)) &&
                    bandName.contains(date) && bandName.toLowerCase().contains(pol.toLowerCase())) {
                return band;
            }
        }
        return null;
    }

    private static double dateToTime(final String dateStr) {
        // dateStr in ddMMMyyyy format
        final int day = Integer.parseInt(dateStr.substring(0, 2));
        final String month = dateStr.substring(2, 5).toUpperCase();
        final int year = Integer.parseInt(dateStr.substring(5));
        final String timeStr = day + "-" + month + "-" + year + " 00:00:00.000000";
        return AbstractMetadata.parseUTC(timeStr).getMJD();
    }

    private synchronized void computeThreshold() {

        if (thresholdComputed) return;

        final Dimension tileSize = new Dimension(256, 256);
        final Rectangle[] tileRectangles = OperatorUtils.getAllTileRectangles(sourceProduct, tileSize, 0);
        final StatusProgressMonitor status = new StatusProgressMonitor(StatusProgressMonitor.TYPE.SUBTASK);
        status.beginTask("Computing Value normalization threshold... ", tileRectangles.length);
        final ThreadExecutor executor = new ThreadExecutor();

        double[] sumSum2 = new double[2];
        try {
            for (final Rectangle rectangle : tileRectangles) {
                final ThreadRunnable worker = new ThreadRunnable() {
                    @Override
                    public void process() {
                        final int x0 = rectangle.x;
                        final int y0 = rectangle.y;
                        final int w = rectangle.width;
                        final int h = rectangle.height;
                        final int maxX = x0 + w;
                        final int maxY = y0 + h;

                        final double[][] sumMax = new double[h][w];
                        final double[][] max = new double[h][w];
                        for (String date : prodAcqDateArray) {

                            final HashMap<String, ProductData> dataBuffersPol = new HashMap<>();
                            final HashMap<String, Double> noDataValuePol = new HashMap<>();
                            TileIndex srcIndex = null;
                            for (String pol : selectedPolarisations) {
                                final Band band = getBand(date, pol);
                                if (band == null) {
                                    dataBuffersPol.put(pol, null);
                                    noDataValuePol.put(pol, null);
                                } else {
                                    final Tile tile = getSourceTile(band, rectangle);
                                    dataBuffersPol.put(pol, tile.getDataBuffer());
                                    noDataValuePol.put(pol, band.getNoDataValue());
                                    if (srcIndex == null) {
                                        srcIndex = new TileIndex(tile);
                                    }
                                }
                            }

                            if (srcIndex == null) {
                                throw new OperatorException("No polarization found for source band containing date: " + date);
                            }

                            for (int y = y0; y < maxY; ++y) {
                                srcIndex.calculateStride(y);
                                final int yy = y - y0;

                                for (int x = x0; x < maxX; ++x) {
                                    final int srcIdx = srcIndex.getIndex(x);
                                    final int xx = x - x0;

                                    final double vMax = getMaxValue(srcIdx, dataBuffersPol, noDataValuePol);
                                    if (vMax == -9999) {
                                        sumMax[yy][xx] = -1.0;
                                        continue;
                                    }

                                    sumMax[yy][xx] += vMax;
                                    if (max[yy][xx] < vMax) {
                                        max[yy][xx] = vMax;
                                    }
                                }
                            }
                        }

                        for (int y = y0; y < maxY; ++y) {
                            final int yy = y - y0;
                            for (int x = x0; x < maxX; ++x) {
                                final int xx = x - x0;
                                if (sumMax[yy][xx] == -1.0) {
                                    continue;
                                }

                                final double meanOfMax = sumMax[yy][xx] / numOfProducts;
                                final double value = 0.4 * (max[yy][xx] + meanOfMax);
                                final double value2 = value * value;
                                synchronized (sumSum2) {
                                    sumSum2[0] += value;
                                    sumSum2[1] += value2;
                                }
                            }
                        }
                    }
                };
                executor.execute(worker);
                status.worked(1);
            }
            executor.complete();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId() + " computeThreshold ", e);
        } finally {
            status.done();
        }

        final double mean = sumSum2[0] / (sourceImageWidth * sourceImageHeight);
        final double std = Math.sqrt(sumSum2[1] / (sourceImageWidth * sourceImageHeight) - mean * mean);
        threshold = mean + std;

        thresholdComputed = true;
    }

    private double getMaxValue(final int srcIdx, final HashMap<String, ProductData> dataBuffersPol,
                               final HashMap<String, Double> noDataValuePol) {

        double vMax = Double.MIN_VALUE;
        for (String pol : selectedPolarisations) {
            final ProductData dataBuffer = dataBuffersPol.get(pol);
            if (dataBuffer == null) {
                continue;
            }

            final double v = dataBuffer.getElemDoubleAt(srcIdx);
            if (v == noDataValuePol.get(pol)) {
                return -9999;
            }
            if (v > vMax) {
                vMax = v;
            }
        }
        return vMax;
    }

    private void updateSumAndSum2(final int srcIdx, final HashMap<String, ProductData> dataBuffersPol,
                                  final int xx, final int yy, final HashMap<String, double[][]> sumPol,
                                  final HashMap<String, double[][]> sum2Pol) {

        for (String pol : selectedPolarisations) {
            final ProductData dataBuffer = dataBuffersPol.get(pol);
            if (dataBuffer == null) {
                continue;
            }

            final double v = dataBuffer.getElemDoubleAt(srcIdx);
            sumPol.get(pol)[yy][xx] += v;
            sum2Pol.get(pol)[yy][xx] += v*v;
        }
    }

    private double computeMaxVarianceCoefficient(final int xx, final int yy,
                                                 final HashMap<String, double[][]> sumPol,
                                                 final HashMap<String, double[][]> sum2Pol) {

        double maxVC = Double.MIN_VALUE;
        for (String pol : selectedPolarisations) {
            final double meanPol = sumPol.get(pol)[yy][xx] / numOfProducts;
            final double stdPol = Math.sqrt(sum2Pol.get(pol)[yy][xx] / numOfProducts - meanPol * meanPol);
            final double varCoefPol = stdPol / meanPol;
            if (varCoefPol > maxVC) {
                maxVC = varCoefPol;
            }
        }
        return maxVC;
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
