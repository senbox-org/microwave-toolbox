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
package eu.esa.sar.sentinel1.gpf.etadcorrectors;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.ETADUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ETAD corrector for S-1 Stripmap SLC products.
 */

 public class SMCorrector extends BaseCorrector implements Corrector {

    private double firstLineTime = 0.0;
    private double lastLineTime = 0.0;
    private double lineTimeInterval = 0.0;
    private double slantRangeToFirstPixel = 0.0;
    private double rangeSpacing = 0.0;

    private String[] swathID = null;
    private String[] polarizations = null;


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public SMCorrector(final Product sourceProduct, final Product targetProduct, final ETADUtils etadUtils,
                       final Resampling selectedResampling) {
		super(sourceProduct, targetProduct, etadUtils, selectedResampling);
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            getSourceProductMetadata();
        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private void getSourceProductMetadata() {

        try {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            if(absRoot == null) {
                throw new OperatorException("Abstracted Metadata not found");
            }

            final String acqMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE).toLowerCase();
            if (!acqMode.equals("sm")) {
                throw new OperatorException("StripMap product is expected.");
            }

            firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD() * Constants.secondsInDay;
            lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD() * Constants.secondsInDay;
            lineTimeInterval = (lastLineTime - firstLineTime) / (sourceImageHeight - 1);
            slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel);
            rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);

            final MetadataElement srcOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement srcAnnotation = srcOrigProdRoot.getElement("annotation");
            if (srcAnnotation == null) {
                throw new IOException("Annotation Metadata not found for product: " + sourceProduct.getName());
            }
            final MetadataElement[] elements = srcAnnotation.getElements();
            final int numOfPolarizations = elements.length;
            swathID = new String[numOfPolarizations];
            polarizations = new String[numOfPolarizations];
            for (int i = 0; i < numOfPolarizations; ++i) {
                final MetadataElement productElem = elements[i].getElement("product");
                final MetadataElement adsHeaderElem = productElem.getElement("adsHeader");
                swathID[i] = adsHeaderElem.getAttributeString("swath");
                polarizations[i] = adsHeaderElem.getAttributeString("polarisation");
            }

        } catch(Throwable e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm,
                                 final Operator op) throws OperatorException {

        try {
            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            final int yMax = y0 + h - 1;
            final int xMax = x0 + w - 1;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle srcRectangle = getSourceRectangle(x0, y0, w, h, margin);

            for (Band tgtBand : targetProduct.getBands()) {
                if (tgtBand instanceof VirtualBand) {
                    continue;
                }

                final Band srcBand = sourceProduct.getBand(tgtBand.getName());
                final Tile srcTile = op.getSourceTile(srcBand, srcRectangle);
                final ProductData srcData = srcTile.getDataBuffer();

                final Tile tgtTile = targetTileMap.get(tgtBand);
                final ProductData tgtData = tgtTile.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(srcTile);
                final TileIndex tgtIndex = new TileIndex(tgtTile);

                final ResamplingRaster slvResamplingRaster = new ResamplingRaster(srcTile, srcData);
                final Resampling.Index resamplingIndex = selectedResampling.createIndex();

                for (int y = y0; y <= yMax; ++y) {
                    tgtIndex.calculateStride(y);
                    srcIndex.calculateStride(y);
                    final int yy = y - y0;

                    for (int x = x0; x <= xMax; ++ x) {
                        final int tgtIdx = tgtIndex.getIndex(x);
                        final int xx = x - x0;
                        final PixelPos slavePixelPos = slavePixPos[yy][xx];

                        selectedResampling.computeCornerBasedIndex(slavePixelPos.x, slavePixelPos.y,
                                sourceImageWidth, sourceImageHeight, resamplingIndex);

                        final double v = selectedResampling.resample(slvResamplingRaster, resamplingIndex);
                        tgtData.setElemDoubleAt(tgtIdx, v);
                    }
                }
            }

        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private void computeETADCorrPixPos(final int x0, final int y0, final int w, final int h,
                                       final PixelPos[][] slavePixPos) throws Exception {

        final double[][] azCorr = new double[h][w];
        getAzimuthTimeCorrectionForCurrentTile(x0, y0, w, h, azCorr);

        final double[][] rgCorr = new double[h][w];
        getRangeTimeCorrectionForCurrentTile(x0, y0, w, h, rgCorr);

        double azimuthTimeCalibration = 0.0;
        if (!sumOfAzimuthCorrections) {
            azimuthTimeCalibration = getInstrumentAzimuthTimeCalibration(swathID[0]);
        }

        double rangeTimeCalibration = 0.0;
        if (!sumOfRangeCorrections) {
            rangeTimeCalibration = getInstrumentRangeTimeCalibration(swathID[0]);
        }

        for (int y = y0; y < y0 + h; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;

            for (int x = x0; x < x0 + w; ++x) {
                final int xx = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;

                final double azCorrTime = azTime + azCorr[yy][xx] + azimuthTimeCalibration;
                final double rgCorrTime = rgTime + rgCorr[yy][xx] + rangeTimeCalibration;

                final double yCorr = (azCorrTime - firstLineTime) / lineTimeInterval;
                final double xCorr = (rgCorrTime * Constants.halfLightSpeed - slantRangeToFirstPixel) / rangeSpacing;

                slavePixPos[yy][xx] = new PixelPos(xCorr, yCorr);
            }
        }
    }

	@Override
    protected void getCorrectionForCurrentTile(final String layer, final int x0, final int y0, final int w, final int h,
                                               final int burstIndex, final double[][] correction, final double scale) {

        Map<String, double[][]> correctionMap = new HashMap<>(10);
        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        for (int y = y0; y <= yMax; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;
            for (int x = x0; x <= xMax; ++x) {
                final int xx = x - x0;
                final double rgTime = (slantRangeToFirstPixel + x * rangeSpacing) / Constants.halfLightSpeed;
                final ETADUtils.Burst burst = etadUtils.getBurst(azTime, rgTime);
                correction[yy][xx] += scale * getCorrection(layer, azTime, rgTime, burst, correctionMap);
            }
        }
    }


    private static class ResamplingRaster implements Resampling.Raster {

        private final Tile tile;
        private final ProductData dataBuffer;

        private ResamplingRaster(final Tile tile, final ProductData dataBuffer) {
            this.tile = tile;
            this.dataBuffer = dataBuffer;
        }

        public final int getWidth() {
            return tile.getWidth();
        }

        public final int getHeight() {
            return tile.getHeight();
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) throws Exception {
            boolean allValid = true;

            try {
                final TileIndex index = new TileIndex(tile);
                final int maxX = x.length;
                for (int i = 0; i < y.length; i++) {
                    index.calculateStride(y[i]);
                    for (int j = 0; j < maxX; j++) {
                        double v = dataBuffer.getElemDoubleAt(index.getIndex(x[j]));
                        samples[i][j] = v;
                    }
                }

            } catch (Exception e) {
                SystemUtils.LOG.severe(e.getMessage());
                allValid = false;
            }

            return allValid;
        }
    }
}
