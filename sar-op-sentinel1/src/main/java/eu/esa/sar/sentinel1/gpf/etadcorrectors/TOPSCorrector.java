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

import Jama.Matrix;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.Sentinel1Utils;
import eu.esa.sar.commons.ETADUtils;
import eu.esa.sar.sentinel1.gpf.BackGeocodingOp;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

import static Jama.Matrix.constructWithCopy;

/**
 * ETAD corrector for split Sentinel-1 TOPS SLC products.
 * The reason that the operator cannot take the original Sentinel-1 product with 3 sub-swaths as input is because
 * 1. computeTileStack cannot handle 3 sub-swathes with different dimensions,
 * 2. if computeTile is used, then the i-band and q-band must be processed twice in order to output i and q bands separately.
 */

 public class TOPSCorrector extends BaseCorrector implements Corrector {

    private Sentinel1Utils mSU = null;
    private Sentinel1Utils.SubSwathInfo[] mSubSwath = null;
    Sentinel1Utils.SubSwathInfo subSwath = null;
    private int subSwathIndex = 0;
    private String swathIndexStr = null;
    private double noDataValue = 0.0;
    private double radarFrequency = 0.0;
    private static final String ETAD = "ETAD";


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public TOPSCorrector(final Product sourceProduct, final ETADUtils etadUtils, final Resampling selectedResampling) {
        super(sourceProduct, etadUtils, selectedResampling);
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            mSU = new Sentinel1Utils(sourceProduct);
            mSubSwath = mSU.getSubSwath();
            mSU.computeDopplerRate();
            mSU.computeReferenceTime();

            final String[] mSubSwathNames = mSU.getSubSwathNames();
            if (mSubSwathNames.length != 1) {
                throw new OperatorException("Split product is expected.");
            }
            
            subSwathIndex = 1; // subSwathIndex is always 1 because of split product
            swathIndexStr = mSubSwathNames[0].substring(2);
            subSwath = mSubSwath[subSwathIndex - 1];

            final Band masterBandI = BackGeocodingOp.getBand(sourceProduct, "i_", swathIndexStr, mSU.getPolarizations()[0]);
            if(masterBandI != null && masterBandI.isNoDataValueUsed()) {
                noDataValue = masterBandI.getNoDataValue();
            }

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            radarFrequency = absRoot.getAttributeDouble(AbstractMetadata.radar_frequency) * 1E6; // MHz to Hz
        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    @Override
    public Product createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX, sourceProduct.getProductType(),
                sourceImageWidth, sourceImageHeight);

        if (outputPhaseCorrections) {

            for (Band srcBand : sourceProduct.getBands()) {
                if (srcBand instanceof VirtualBand) {
                    continue;
                }

                final Band targetBand = ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, true);

                if(targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                    int idx = targetProduct.getBandIndex(targetBand.getName());
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
                }
            }

            final String phaseBandName = ETAD_PHASE_CORRECTION + "_" + subSwath.subSwathName;
            final Band phaseBand = new Band(phaseBandName, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
            phaseBand.setUnit(Unit.RADIANS);
            targetProduct.addBand(phaseBand);

            final String heightBandName = ETAD_HEIGHT + "_" + subSwath.subSwathName;
            final Band heightBand = new Band(heightBandName, ProductData.TYPE_FLOAT32, sourceImageWidth, sourceImageHeight);
            heightBand.setUnit(Unit.METERS);
            targetProduct.addBand(heightBand);

        } else { // resampling image

            for (Band srcBand : sourceProduct.getBands()) {
                if (srcBand instanceof VirtualBand) {
                    continue;
                }

                final Band targetBand = new Band(srcBand.getName(), ProductData.TYPE_FLOAT32,
                        srcBand.getRasterWidth(), srcBand.getRasterHeight());

                targetBand.setUnit(srcBand.getUnit());
                targetBand.setDescription(srcBand.getDescription());
                targetProduct.addBand(targetBand);

                if(targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                    int idx = targetProduct.getBandIndex(targetBand.getName());
                    ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
                }
            }
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        return targetProduct;
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
            final int tx0 = targetRectangle.x;
            final int ty0 = targetRectangle.y;
            final int tw = targetRectangle.width;
            final int th = targetRectangle.height;
            final int tyMax = ty0 + th;
            final int txMax = tx0 + tw;
//            System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            for (int burstIndex = 0; burstIndex < subSwath.numOfBursts; burstIndex++) {
                final int firstLineIdx = burstIndex * subSwath.linesPerBurst;
                final int lastLineIdx = firstLineIdx + subSwath.linesPerBurst - 1;

                if (tyMax <= firstLineIdx || ty0 > lastLineIdx) {
                    continue;
                }

                final int ntx0 = tx0;
                final int ntw = tw;
                final int nty0 = Math.max(ty0, firstLineIdx);
                final int ntyMax = Math.min(tyMax, lastLineIdx + 1);
                final int nth = ntyMax - nty0;
                //System.out.println("burstIndex = " + burstIndex + ": ntx0 = " + ntx0 + ", nty0 = " + nty0 + ", ntw = " + ntw + ", nth = " + nth);

                if (resamplingImage) {
                    computePartialTileResampleImage(subSwathIndex, burstIndex, ntx0, nty0, ntw, nth, targetTileMap, op);
                } else { // outputInSARPhaseCorrections
                    computePartialTileOutputCorrections(subSwathIndex, burstIndex, ntx0, nty0, ntw, nth, targetTileMap, op);
                }
            }

        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private synchronized void computeTroposphericToHeightGradient() {

        if (tropToHeightGradientComputed) return;

        int prodSubswathIndex = -1;
        if (subSwath.subSwathName.toLowerCase().equals("iw1")) {
            prodSubswathIndex = 1;
        } else if (subSwath.subSwathName.toLowerCase().equals("iw2")) {
            prodSubswathIndex = 2;
        } else if (subSwath.subSwathName.toLowerCase().equals("iw3")) {
            prodSubswathIndex = 3;
        }

        final int pIndex = etadUtils.getProductIndex(sourceProduct.getName());
        final int[] burstIndexArray = etadUtils.getBurstIndexArray(pIndex, prodSubswathIndex);

        final double[] gradientArray = new double[burstIndexArray.length];
        int i = 0;
        for (int burstIndex : burstIndexArray) {
            final ETADUtils.Burst burst = etadUtils.getBurst(pIndex, prodSubswathIndex, burstIndex);
            final double[][] tropCorr = getBurstCorrection(TROPOSPHERIC_CORRECTION_RG, burst);
            final double[][] height = getBurstCorrection(HEIGHT, burst);
            final double gradient = computeGradientForCurrentBurst(tropCorr, height);
            gradientArray[i++] = -2.0 * Constants.PI * radarFrequency * gradient;
        }

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        MetadataElement etadElem = absTgt.getElement(ETAD);
        if (etadElem == null) {
            etadElem = new MetadataElement(ETAD);
            absTgt.addElement(etadElem);
        }
        final MetadataAttribute attrib = new MetadataAttribute("gradient", ProductData.TYPE_FLOAT64, gradientArray.length);
        attrib.getData().setElems(gradientArray);
        etadElem.addAttribute(attrib);

        tropToHeightGradientComputed = true;
    }

    private double computeGradientForCurrentBurst(final double[][] tropCorr, final double[][] height) {

        final int rows = tropCorr.length;
        final int cols = tropCorr[0].length;

        double sumX = 0.0, sumX2 = 0.0, sumY = 0.0, sumXY = 0.0;
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols - 1; ++c) {
                final double dh = height[r][c + 1] - height[r][c];
                final double dt = tropCorr[r][c + 1] - tropCorr[r][c];
                sumX += dh;
                sumX2 += dh * dh;
                sumY += dt;
                sumXY += dh * dt;
            }
        }

        final Matrix A = new Matrix(new double[][]{{sumX2, sumX}, {sumX, rows * (cols - 1)}});
        final Matrix b = new Matrix(new double[]{sumXY, sumY}, 2);
        final Matrix c = A.solve(b);
        return c.get(0,0);
    }

    private void computePartialTileOutputCorrections(final int subSwathIndex, final int mBurstIndex,
                                                     final int x0, final int y0, final int w, final int h,
                                                     final Map<Band, Tile> targetTileMap, final Operator op) {

        try {
            if (!tropToHeightGradientComputed) {
                computeTroposphericToHeightGradient();
            }

            double[][] correction = new double[h][w];
            getInSARRangeTimeCorrectionForCurrentTile(x0, y0, w, h, mBurstIndex, correction);
            final double rangeTimeCalibration = getInstrumentRangeTimeCalibration(subSwath.subSwathName);

            double[][] height = new double[h][w];
            getCorrectionForCurrentTile(HEIGHT, x0, y0, w, h, mBurstIndex, height);

            final String phaseBandName = ETAD_PHASE_CORRECTION + "_" + subSwath.subSwathName;
            final Band phaseBand = targetProduct.getBand(phaseBandName);
            final Tile phaseTile = targetTileMap.get(phaseBand);
            final ProductData phaseData = phaseTile.getDataBuffer();
            final TileIndex phaseIndex = new TileIndex(phaseTile);

            final String heightBandName = ETAD_HEIGHT + "_" + subSwath.subSwathName;
            final Band heightBand = targetProduct.getBand(heightBandName);
            final Tile heightTile = targetTileMap.get(heightBand);
            final ProductData heightData = heightTile.getDataBuffer();
            final TileIndex heightIndex = new TileIndex(phaseTile);

            final int xMax = x0 + w - 1;
            final int yMax = y0 + h - 1;

            for (int y = y0; y <= yMax; ++y) {
                phaseIndex.calculateStride(y);
                heightIndex.calculateStride(y);
                int yy = y - y0;

                for (int x = x0; x <= xMax; ++x) {
                    final int phaseIdx = phaseIndex.getIndex(x);
                    final int heightIdx = heightIndex.getIndex(x);
                    final int xx = x - x0;

                    final double delay = correction[yy][xx] + rangeTimeCalibration;
                    final double phase = -2.0 * Constants.PI * radarFrequency * delay; // delay time (s) to phase (radian)
                    phaseData.setElemDoubleAt(phaseIdx, phase);
                    heightData.setElemDoubleAt(heightIdx, height[yy][xx]);
                }
            }

        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private void computePartialTileResampleImage(final int subSwathIndex, final int mBurstIndex,
                                                 final int x0, final int y0, final int w, final int h,
                                                 final Map<Band, Tile> targetTileMap, final Operator op) {

        try {
            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, mBurstIndex, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle sourceRectangle = BackGeocodingOp.getBoundingBox(slavePixPos, margin, subSwathIndex,
                    mBurstIndex, mSU.getSubSwath());

            if (sourceRectangle == null) {
                return;
            }

            final double[][] mstDerampDemodPhase = mSU.computeDerampDemodPhase(mSubSwath,
                    subSwathIndex, mBurstIndex, sourceRectangle);


            for(String polarization : mSU.getPolarizations()) {
                final Band masterBandI = BackGeocodingOp.getBand(sourceProduct, "i_", swathIndexStr, polarization);
                final Band masterBandQ = BackGeocodingOp.getBand(sourceProduct, "q_", swathIndexStr, polarization);
                final Tile masterTileI = op.getSourceTile(masterBandI, sourceRectangle);
                final Tile masterTileQ = op.getSourceTile(masterBandQ, sourceRectangle);

                if (masterTileI == null || masterTileQ == null) {
                    return;
                }

                final double[][] mstDerampDemodI = new double[sourceRectangle.height][sourceRectangle.width];
                final double[][] mstDerampDemodQ = new double[sourceRectangle.height][sourceRectangle.width];

                BackGeocodingOp.performDerampDemod(masterTileI, masterTileQ, sourceRectangle, mstDerampDemodPhase,
                        mstDerampDemodI, mstDerampDemodQ);

                final Band targetBandI = targetProduct.getBand(masterBandI.getName());
                final Band targetBandQ = targetProduct.getBand(masterBandQ.getName());
                final Tile targetTileI = targetTileMap.get(targetBandI);
                final Tile targetTileQ = targetTileMap.get(targetBandQ);

                PerformETADCorrection(x0, y0, w, h, sourceRectangle, masterTileI, masterTileQ, targetTileI,
                        targetTileQ, mstDerampDemodPhase, mstDerampDemodI, mstDerampDemodQ, slavePixPos);
            }

        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

    private void computeETADCorrPixPos(final int x0, final int y0, final int w, final int h, final int prodBurstIndex,
                                       final PixelPos[][] slavePixPos) {

        try {
            final double[][] azCorr = new double[h][w];
            getAzimuthTimeCorrectionForCurrentTile(x0, y0, w, h, prodBurstIndex, azCorr);

            final double[][] rgCorr = new double[h][w];
            getRangeTimeCorrectionForCurrentTile(x0, y0, w, h, prodBurstIndex, rgCorr);

            double azimuthTimeCalibration = 0.0;
            if (!sumOfAzimuthCorrections) {
                azimuthTimeCalibration = getInstrumentAzimuthTimeCalibration(subSwath.subSwathName);
            }

            double rangeTimeCalibration = 0.0;
            if (!sumOfRangeCorrections) {
                rangeTimeCalibration = getInstrumentRangeTimeCalibration(subSwath.subSwathName);
            }

            final int xMax = x0 + w - 1;
            final int yMax = y0 + h - 1;

            for (int y = y0; y <= yMax; ++y) {
                final int yy = y - y0;
                final double azTime = subSwath.burstFirstLineTime[prodBurstIndex] +
                        (y - prodBurstIndex * subSwath.linesPerBurst) * subSwath.azimuthTimeInterval;

                for (int x = x0; x <= xMax; ++x) {
                    final int xx = x - x0;
                    final double rgTime = 2.0 * (subSwath.slrTimeToFirstPixel + x * mSU.rangeSpacing / Constants.lightSpeed);

                    final double azCorrTime = azTime + azCorr[yy][xx] + azimuthTimeCalibration;
                    final double rgCorrTime = rgTime + rgCorr[yy][xx] + rangeTimeCalibration;

                    final double yCorr = prodBurstIndex * subSwath.linesPerBurst +
                            (azCorrTime - subSwath.burstFirstLineTime[prodBurstIndex]) / subSwath.azimuthTimeInterval;

                    final double xCorr = (rgCorrTime * 0.5 - subSwath.slrTimeToFirstPixel) * Constants.lightSpeed / mSU.rangeSpacing;

                    slavePixPos[yy][xx] = new PixelPos(xCorr, yCorr);
                }
            }
        } catch (Throwable e) {
            throw new OperatorException(e);
        }
    }

	@Override
    protected void getCorrectionForCurrentTile(final String layer, final int x0, final int y0, final int w, final int h,
                                               final int prodBurstIndex, final double[][] correction, final double scale) {

        int prodSubswathIndex = -1;
        if (subSwath.subSwathName.toLowerCase().equals("iw1")) {
            prodSubswathIndex = 1;
        } else if (subSwath.subSwathName.toLowerCase().equals("iw2")) {
            prodSubswathIndex = 2;
        } else if (subSwath.subSwathName.toLowerCase().equals("iw3")) {
            prodSubswathIndex = 3;
        }

        final int pIndex = etadUtils.getProductIndex(sourceProduct.getName());
        final double burstAzTime = 0.5 * (subSwath.burstFirstLineTime[prodBurstIndex] +  subSwath.burstLastLineTime[prodBurstIndex]);
        final int burstIndex = etadUtils.getBurstIndex(pIndex, prodSubswathIndex, burstAzTime);
        final ETADUtils.Burst burst = etadUtils.getBurst(pIndex, prodSubswathIndex, burstIndex);

        Map<String, double[][]> correctionMap = new HashMap<>(10);
        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        for (int y = y0; y <= yMax; ++y) {
            final int yy = y - y0;
            final double azTime = subSwath.burstFirstLineTime[prodBurstIndex] +
                    (y - prodBurstIndex * subSwath.linesPerBurst) * subSwath.azimuthTimeInterval;

            for (int x = x0; x <= xMax; ++x) {
                final int xx = x - x0;
                final double rgTime = 2.0 * (subSwath.slrTimeToFirstPixel + x * mSU.rangeSpacing / Constants.lightSpeed);

                correction[yy][xx] += scale * getCorrection(layer, azTime, rgTime, burst, correctionMap);
            }
        }
    }

    private void PerformETADCorrection(final int x0, final int y0, final int w, final int h,
                                       final Rectangle sourceRectangle, final Tile slaveTileI, final Tile slaveTileQ,
                                       final Tile tgtTileI, final Tile tgtTileQ, final double[][] derampDemodPhase,
                                       final double[][] derampDemodI, final double[][] derampDemodQ,
                                       final PixelPos[][] slavePixPos) {

        try {
            final BackGeocodingOp.ResamplingRaster resamplingRasterI = new BackGeocodingOp.ResamplingRaster(slaveTileI, derampDemodI);
            final BackGeocodingOp.ResamplingRaster resamplingRasterQ = new BackGeocodingOp.ResamplingRaster(slaveTileQ, derampDemodQ);
            final BackGeocodingOp.ResamplingRaster resamplingRasterPhase = new BackGeocodingOp.ResamplingRaster(slaveTileI, derampDemodPhase);

            final ProductData tgtBufferI = tgtTileI.getDataBuffer();
            final ProductData tgtBufferQ = tgtTileQ.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(tgtTileI);

            final Resampling.Index resamplingIndex = selectedResampling.createIndex();

            final int sxMin = sourceRectangle.x;
            final int syMin = sourceRectangle.y;
            final int sxMax = sourceRectangle.x + sourceRectangle.width - 1;
            final int syMax = sourceRectangle.y + sourceRectangle.height - 1;

            for (int y = y0; y < y0 + h; y++) {
                tgtIndex.calculateStride(y);
                final int yy = y - y0;
                for (int x = x0; x < x0 + w; x++) {
                    final int xx = x - x0;
                    final int tgtIdx = tgtIndex.getIndex(x);
                    final PixelPos slavePixelPos = slavePixPos[yy][xx];

                    if (slavePixelPos == null || slavePixelPos.x < sxMin || slavePixelPos.x > sxMax ||
                            slavePixelPos.y < syMin || slavePixelPos.y > syMax) {

                        tgtBufferI.setElemDoubleAt(tgtIdx, noDataValue);
                        tgtBufferQ.setElemDoubleAt(tgtIdx, noDataValue);
                        continue;
                    }

                    selectedResampling.computeCornerBasedIndex(
                            slavePixelPos.x - sourceRectangle.x, slavePixelPos.y - sourceRectangle.y,
                            sourceRectangle.width, sourceRectangle.height, resamplingIndex);

                    double sampleI = selectedResampling.resample(resamplingRasterI, resamplingIndex);

                    double rerampRemodI, rerampRemodQ;
                    if (Double.isNaN(sampleI)) {
                        rerampRemodI = noDataValue;
                        rerampRemodQ = noDataValue;
                    } else {
                        double sampleQ = selectedResampling.resample(resamplingRasterQ, resamplingIndex);
                        final double samplePhase = selectedResampling.resample(resamplingRasterPhase, resamplingIndex);
                        final double cosPhase = FastMath.cos(samplePhase);
                        final double sinPhase = FastMath.sin(samplePhase);
                        rerampRemodI = sampleI * cosPhase + sampleQ * sinPhase;
                        rerampRemodQ = -sampleI * sinPhase + sampleQ * cosPhase;
                    }

                    tgtBufferI.setElemDoubleAt(tgtIdx, rerampRemodI);
                    tgtBufferQ.setElemDoubleAt(tgtIdx, rerampRemodQ);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("PerformETADCorrection", e);
        }
    }
}
