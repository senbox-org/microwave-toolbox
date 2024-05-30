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
import org.esa.snap.engine_utilities.util.Maths;

import java.awt.*;
import java.text.DateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * ETAD corrector for S-1 GRD products.
 */

 public class GRDCorrector extends BaseCorrector implements Corrector {

    private double firstLineTime = 0.0;
    private double lastLineTime = 0.0;
    private double lineTimeInterval = 0.0;
    private double groundRangeSpacing = 0.0;
    private SRGRCoefficientList[] srgrConvParams = null;
    private GRSRCoefficientList[] grsrConvParams = null;


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public GRDCorrector(final Product sourceProduct, final Product targetProduct, final ETADUtils etadUtils,
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
            firstLineTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time).getMJD() * Constants.secondsInDay;
            lastLineTime = absRoot.getAttributeUTC(AbstractMetadata.last_line_time).getMJD() * Constants.secondsInDay;
            lineTimeInterval = (lastLineTime - firstLineTime) / (sourceImageHeight - 1);
            groundRangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing);

            final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement annotationElem = origProdRoot.getElement("annotation");
            final MetadataElement imgElem = annotationElem.getElementAt(0);
            final MetadataElement productElem = imgElem.getElement("product");
            final MetadataElement coordConvElem = productElem.getElement("coordinateConversion");
            final MetadataElement grsrCoefficientsElem = addGRSRCoefficients(coordConvElem);
            final MetadataElement srgrCoefficientsElem = addSRGRCoefficients(coordConvElem);
            grsrConvParams = getGRSRCoefficients(grsrCoefficientsElem);
            srgrConvParams = getSRGRCoefficients(srgrCoefficientsElem);
            if (grsrConvParams == null || grsrConvParams.length == 0) {
                throw new OperatorException("Invalid GRSR Coefficients for product " + sourceProduct.getName());
            }
            if (srgrConvParams == null || srgrConvParams.length == 0) {
                throw new OperatorException("Invalid SRGR Coefficients for product " + sourceProduct.getName());
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
            final int xMax = x0 + w - 1;
            final int yMax = y0 + h - 1;

            final PixelPos[][] slavePixPos = new PixelPos[h][w];
            computeETADCorrPixPos(x0, y0, w, h, slavePixPos);

            final int margin = selectedResampling.getKernelSize();
            final Rectangle srcRectangle = getSourceRectangle(x0, y0, w, h, margin);

            for (Band tgtBand : targetProduct.getBands()) {
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
            azimuthTimeCalibration = getInstrumentAzimuthTimeCalibration("IW1");
        }

        double rangeTimeCalibration = 0.0;
        if (!sumOfRangeCorrections) {
            rangeTimeCalibration = getInstrumentRangeTimeCalibration("IW1");
        }

        for (int y = y0; y < y0 + h; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;
            final double azimuthTimeInDays = azTime / Constants.secondsInDay;
            final double[] grsrCoefficients = getGRSRCoefficients(azimuthTimeInDays, grsrConvParams);
            final double[] srgrCoefficients = getSRGRCoefficients(azimuthTimeInDays, srgrConvParams);

            for (int x = x0; x < x0 + w; ++x) {
                final int xx = x - x0;
                final double groundRange = x * groundRangeSpacing;
                final double slantRange = Maths.computePolynomialValue(
                        groundRange - grsrConvParams[0].ground_range_origin, grsrCoefficients);
                final double rgTime = slantRange / Constants.halfLightSpeed;

                final double azCorrTime = azTime + azCorr[yy][xx] + azimuthTimeCalibration;
                final double rgCorrTime = rgTime + rgCorr[yy][xx] + rangeTimeCalibration;
                final double slantRangeCorr = rgCorrTime * Constants.halfLightSpeed;
                final double groundRangeCorr = Maths.computePolynomialValue(
                        slantRangeCorr - srgrConvParams[0].slant_range_origin, srgrCoefficients);

                final double yCorr = (azCorrTime - firstLineTime) / lineTimeInterval;
                final double xCorr = groundRangeCorr / groundRangeSpacing;
                slavePixPos[yy][xx] = new PixelPos(xCorr, yCorr);
            }
        }
    }

	@Override
    protected void getCorrectionForCurrentTile(final String layer, final int x0, final int y0, final int w, final int h,
                                               final int burstIndex, final double[][] correction, final double scale)
            throws Exception {

        Map<String, double[][]> correctionMap = new HashMap<>(10);
        final int xMax = x0 + w - 1;
        final int yMax = y0 + h - 1;

        for (int y = y0; y <= yMax; ++y) {
            final int yy = y - y0;
            final double azTime = firstLineTime + y * lineTimeInterval;
            final double azimuthTimeInDays = azTime / Constants.secondsInDay;
            final double[] grsrCoefficients = getGRSRCoefficients(azimuthTimeInDays, grsrConvParams);

            for (int x = x0; x <= xMax; ++x) {
                final int xx = x - x0;
                final double groundRange = x * groundRangeSpacing;
                final double slantRange = Maths.computePolynomialValue(
                        groundRange - grsrConvParams[0].ground_range_origin, grsrCoefficients);
                final double rgTime = slantRange / Constants.halfLightSpeed;
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

    // todo: The following code should eventually be moved into SARGeocoding and AbstractedMetadata
    private MetadataElement addGRSRCoefficients(final MetadataElement coordinateConversion) {

        DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

        if (coordinateConversion == null)
            return null;

        final MetadataElement coordinateConversionList = coordinateConversion.getElement("coordinateConversionList");
        if (coordinateConversionList == null)
            return null;

        final MetadataElement grsrCoefficientsElem = new MetadataElement("GRSR_Coefficients");

        int listCnt = 1;
        for (MetadataElement elem : coordinateConversionList.getElements()) {
            final MetadataElement grsrListElem = new MetadataElement("grsr_coef_list" + '.' + listCnt);
            grsrCoefficientsElem.addElement(grsrListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
            grsrListElem.setAttributeUTC("zero_doppler_time", utcTime);

            final double grOrigin = elem.getAttributeDouble("gr0", 0);
            AbstractMetadata.addAbstractedAttribute(grsrListElem, "ground_range_origin",
                    ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
            AbstractMetadata.setAttribute(grsrListElem, "ground_range_origin", grOrigin);

            final String coeffStr = elem.getElement("grsrCoefficients").getAttributeString("grsrCoefficients", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement("coefficient" + '.' + cnt);
                    grsrListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, "grsr_coef",
                            ProductData.TYPE_FLOAT64, "", "GRSR Coefficient");
                    AbstractMetadata.setAttribute(coefElem, "grsr_coef", coefValue);
                }
            }
        }
        return grsrCoefficientsElem;
    }

    private MetadataElement addSRGRCoefficients(final MetadataElement coordinateConversion) {

        DateFormat sentinelDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss");

        if (coordinateConversion == null)
            return null;

        final MetadataElement coordinateConversionList = coordinateConversion.getElement("coordinateConversionList");
        if (coordinateConversionList == null)
            return null;

        final MetadataElement srgrCoefficientsElem = new MetadataElement("SRGR_Coefficients");

        int listCnt = 1;
        for (MetadataElement elem : coordinateConversionList.getElements()) {
            final MetadataElement srgrListElem = new MetadataElement("srgr_coef_list" + '.' + listCnt);
            srgrCoefficientsElem.addElement(srgrListElem);
            ++listCnt;

            final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "azimuthTime", sentinelDateFormat);
            srgrListElem.setAttributeUTC("zero_doppler_time", utcTime);

            final double srOrigin = elem.getAttributeDouble("sr0", 0);
            AbstractMetadata.addAbstractedAttribute(srgrListElem, "slant_range_origin",
                    ProductData.TYPE_FLOAT64, "m", "Slant Range Origin");
            AbstractMetadata.setAttribute(srgrListElem, "slant_range_origin", srOrigin);

            final String coeffStr = elem.getElement("srgrCoefficients").getAttributeString("srgrCoefficients", "");
            if (!coeffStr.isEmpty()) {
                final StringTokenizer st = new StringTokenizer(coeffStr);
                int cnt = 1;
                while (st.hasMoreTokens()) {
                    final double coefValue = Double.parseDouble(st.nextToken());

                    final MetadataElement coefElem = new MetadataElement("coefficient" + '.' + cnt);
                    srgrListElem.addElement(coefElem);
                    ++cnt;
                    AbstractMetadata.addAbstractedAttribute(coefElem, "srgr_coef",
                            ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                    AbstractMetadata.setAttribute(coefElem, "srgr_coef", coefValue);
                }
            }
        }
        return srgrCoefficientsElem;
    }

    private static GRSRCoefficientList[] getGRSRCoefficients(final MetadataElement grsrCoefficientsElem) {

        if(grsrCoefficientsElem != null) {
            final MetadataElement[] grsr_coef_listElem = grsrCoefficientsElem.getElements();
            final GRSRCoefficientList[] grsrCoefficientList = new GRSRCoefficientList[grsr_coef_listElem.length];
            int k = 0;
            for (MetadataElement listElem : grsr_coef_listElem) {
                final GRSRCoefficientList grsrList = new GRSRCoefficientList();
                grsrList.time = listElem.getAttributeUTC("zero_doppler_time");
                grsrList.timeMJD = grsrList.time.getMJD();
                grsrList.ground_range_origin = listElem.getAttributeDouble("ground_range_origin");

                final int numSubElems = listElem.getNumElements();
                grsrList.coefficients = new double[numSubElems];
                for (int i = 0; i < numSubElems; ++i) {
                    final MetadataElement coefElem = listElem.getElementAt(i);
                    grsrList.coefficients[i] = coefElem.getAttributeDouble("grsr_coef", 0.0);
                }
                grsrCoefficientList[k++] = grsrList;
            }
            return grsrCoefficientList;
        }
        return null;
    }

    private static SRGRCoefficientList[] getSRGRCoefficients(final MetadataElement srgrCoefficientsElem) {

        if(srgrCoefficientsElem != null) {
            final MetadataElement[] srgr_coef_listElem = srgrCoefficientsElem.getElements();
            final SRGRCoefficientList[] srgrCoefficientList = new SRGRCoefficientList[srgr_coef_listElem.length];
            int k = 0;
            for (MetadataElement listElem : srgr_coef_listElem) {
                final SRGRCoefficientList srgrList = new SRGRCoefficientList();
                srgrList.time = listElem.getAttributeUTC("zero_doppler_time");
                srgrList.timeMJD = srgrList.time.getMJD();
                srgrList.slant_range_origin = listElem.getAttributeDouble("slant_range_origin");

                final int numSubElems = listElem.getNumElements();
                srgrList.coefficients = new double[numSubElems];
                for (int i = 0; i < numSubElems; ++i) {
                    final MetadataElement coefElem = listElem.getElementAt(i);
                    srgrList.coefficients[i] = coefElem.getAttributeDouble("srgr_coef", 0.0);
                }
                srgrCoefficientList[k++] = srgrList;
            }
            return srgrCoefficientList;
        }
        return null;
    }

    private static double[] getGRSRCoefficients(final double zeroDopplerTime,
                                                final GRSRCoefficientList[] grsrConvParams) throws Exception {

        if(grsrConvParams == null || grsrConvParams.length == 0) {
            throw new Exception("SARGeoCoding: grsrConvParams not set");
        }

        final double[] grsrCoefficients = new double[grsrConvParams[0].coefficients.length];

        int idx = 0;
        if (grsrConvParams.length == 1) {
            System.arraycopy(grsrConvParams[0].coefficients, 0, grsrCoefficients, 0, grsrConvParams[0].coefficients.length);
        } else {
            for (int i = 0; i < grsrConvParams.length && zeroDopplerTime >= grsrConvParams[i].timeMJD; i++) {
                idx = i;
            }

            if (idx == grsrConvParams.length - 1) {
                idx--;
            }

            final double mu = (zeroDopplerTime - grsrConvParams[idx].timeMJD) /
                    (grsrConvParams[idx + 1].timeMJD - grsrConvParams[idx].timeMJD);

            for (int i = 0; i < grsrCoefficients.length; i++) {
                grsrCoefficients[i] = Maths.interpolationLinear(grsrConvParams[idx].coefficients[i],
                        grsrConvParams[idx + 1].coefficients[i], mu);
            }
        }

        return grsrCoefficients;
    }

    private static double[] getSRGRCoefficients(final double zeroDopplerTime,
                                                final SRGRCoefficientList[] srgrConvParams) throws Exception {

        if(srgrConvParams == null || srgrConvParams.length == 0) {
            throw new Exception("SARGeoCoding: srgrConvParams not set");
        }

        final double[] srgrCoefficients = new double[srgrConvParams[0].coefficients.length];

        int idx = 0;
        if (srgrConvParams.length == 1) {
            System.arraycopy(srgrConvParams[0].coefficients, 0, srgrCoefficients, 0, srgrConvParams[0].coefficients.length);
        } else {
            for (int i = 0; i < srgrConvParams.length && zeroDopplerTime >= srgrConvParams[i].timeMJD; i++) {
                idx = i;
            }

            if (idx == srgrConvParams.length - 1) {
                idx--;
            }

            final double mu = (zeroDopplerTime - srgrConvParams[idx].timeMJD) /
                    (srgrConvParams[idx + 1].timeMJD - srgrConvParams[idx].timeMJD);

            for (int i = 0; i < srgrCoefficients.length; i++) {
                srgrCoefficients[i] = Maths.interpolationLinear(srgrConvParams[idx].coefficients[i],
                        srgrConvParams[idx + 1].coefficients[i], mu);
            }
        }

        return srgrCoefficients;
    }

    public static class GRSRCoefficientList {
        public ProductData.UTC time = null;
        public double timeMJD = 0;
        public double ground_range_origin = 0.0;
        public double[] coefficients = null;
    }

    public static class SRGRCoefficientList {
        public ProductData.UTC time = null;
        public double timeMJD = 0;
        public double slant_range_origin = 0.0;
        public double[] coefficients = null;
    }
}
