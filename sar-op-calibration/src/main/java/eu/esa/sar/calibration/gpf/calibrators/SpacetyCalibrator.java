/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.calibration.gpf.calibrators;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import eu.esa.sar.calibration.gpf.Sentinel1RemoveThermalNoiseOp;
import eu.esa.sar.calibration.gpf.support.BaseCalibrator;
import eu.esa.sar.calibration.gpf.support.Calibrator;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Calibration for Spacety data products.
 */

public final class SpacetyCalibrator extends BaseCalibrator implements Calibrator {

    private static final String[] SUPPORTED_MISSIONS = new String[] {"Spacety"};

    private CalibrationInfo[] calibration = null;
    private boolean isMultiSwath = false;
    protected final HashMap<String, CalibrationInfo> targetBandToCalInfo = new HashMap<>(2);

    private CALTYPE dataType = null;
    private int subsetOffsetX = 0;
    private int subsetOffsetY = 0;

    public enum CALTYPE {SIGMA0, BETA0, GAMMA, DN}

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public SpacetyCalibrator() {
    }

    @Override
    public String[] getSupportedMissions() {
        return SUPPORTED_MISSIONS;
    }

    /**
     * Set external auxiliary file.
     */
    public void setExternalAuxFile(File file) throws OperatorException {
        if (file != null) {
            throw new OperatorException("No external auxiliary file should be selected for Spacety product");
        }
    }

    /**
     * Set auxiliary file flag.
     */
    @Override
    public void setAuxFileFlag(String file) {
    }

    private void validate(final Product sourceProduct) throws OperatorException {
        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkProductType(new String[]{"SLC"});

        isMultiSwath = validator.isMultiSwath();
    }

    /**

     */
    public void initialize(final Operator op, final Product srcProduct, final Product tgtProduct,
                           final boolean mustPerformRetroCalibration, final boolean mustUpdateMetadata)
            throws OperatorException {
        try {
            this.calibrationOp = op;
            this.sourceProduct = srcProduct;
            this.targetProduct = tgtProduct;

            validate(sourceProduct);

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            getSampleType();

            if (absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean()) {
                dataType = getCalibrationType(sourceProduct.getBandAt(0).getName());
            }

            getSubsetOffset();

            getVectors();

            createTargetBandToCalInfoMap();

            if (mustUpdateMetadata) {
                updateTargetProductMetadata();
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Get subset x and y offsets from abstract metadata.
     */
    private void getSubsetOffset() {
        subsetOffsetX = absRoot.getAttributeInt(AbstractMetadata.subset_offset_x);
        subsetOffsetY = absRoot.getAttributeInt(AbstractMetadata.subset_offset_y);
    }

    private void getVectors() throws IOException {

        boolean getSigmaLUT = outputSigmaBand;
        boolean getBetaLUT = outputBetaBand;
        boolean getGammaLUT = outputGammaBand;
        boolean getDNLUT = outputDNBand;

        if (dataType != null) {
            if (dataType.equals(CALTYPE.SIGMA0)) {
                getSigmaLUT = true;
            } else if (dataType.equals(CALTYPE.BETA0)) {
                getBetaLUT = true;
            } else if (dataType.equals(CALTYPE.GAMMA)) {
                getGammaLUT = true;
            } else {
                getDNLUT = true;
            }
        }

        calibration = getCalibrationVectors(sourceProduct, selectedPolList,
                getSigmaLUT, getBetaLUT, getGammaLUT, getDNLUT);
    }

    /**
     * Get calibration vectors from metadata.
     */
    public static CalibrationInfo[] getCalibrationVectors(
            final Product sourceProduct, final List<String> selectedPolList,
            final boolean getSigmaLUT, final boolean getBetaLUT, final boolean getGammaLUT,
            final boolean getDNLUT) throws IOException {

        final List<CalibrationInfo> calibrationInfoList = new ArrayList<>();
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
        if(origProdRoot == null) {
            throw new IOException("Unable to find original product metadata");
        }
        final MetadataElement calibrationElem = origProdRoot.getElement("calibration");
        if(calibrationElem == null) {
            throw new IOException("Unable to find calibration element in original product metadata");
        }
        final MetadataElement[] calibrationDataSetListElem = calibrationElem.getElements();

        for (MetadataElement dataSetListElem : calibrationDataSetListElem) {

            final MetadataElement calElem = dataSetListElem.getElement("calibration");
            final MetadataElement adsHeaderElem = calElem.getElement("adsHeader");
            final String pol = adsHeaderElem.getAttributeString("polarisation");
            if (!selectedPolList.contains(pol)) {
                continue;
            }

            final MetadataElement calVecListElem = calElem.getElement("calibrationVectorList");

            final String subSwath = adsHeaderElem.getAttributeString("swath");
            final double firstLineTime = Sentinel1Utils.getTime(adsHeaderElem, "startTime").getMJD();
            final double lastLineTime = Sentinel1Utils.getTime(adsHeaderElem, "stopTime").getMJD();
            final int numOfLines = getNumOfLines(origProdRoot, pol, subSwath);
            final int count = calVecListElem.getAttributeInt("count");
            final Sentinel1Utils.CalibrationVector[] calibrationVectorList =
                    Sentinel1Utils.getCalibrationVector(
                            calVecListElem, getSigmaLUT, getBetaLUT, getGammaLUT, getDNLUT);

            calibrationInfoList.add(new CalibrationInfo(subSwath, pol,
                    firstLineTime, lastLineTime, numOfLines, count, calibrationVectorList));
        }

        return calibrationInfoList.toArray(new CalibrationInfo[0]);
    }

    /**
     * Create a target band name to CalibrationInfo map.
     */
    private void createTargetBandToCalInfoMap() {

        final String[] targetBandNames = targetProduct.getBandNames();
        for (CalibrationInfo cal : calibration) {
            final String pol = cal.polarization;
            final String ss = cal.subSwath;
            for (String bandName : targetBandNames) {
                if (isMultiSwath) {
                    if (bandName.contains(pol) && bandName.contains(ss)) {
                        targetBandToCalInfo.put(bandName, cal);
                    }
                } else {
                    if (bandName.contains(pol)) {
                        targetBandToCalInfo.put(bandName, cal);
                    }
                }
            }
        }
    }

    /**
     * Get the number of output lines of a given swath.
     *
     * @param origProdRoot Root of the original metadata of the source product.
     * @param polarization Polarization of the given swath.
     * @param swath        Swath name.
     * @return The number of output lines.
     */
    public static int getNumOfLines(final MetadataElement origProdRoot, final String polarization, final String swath) {

        final MetadataElement annotationElem = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationDataSetListElem = annotationElem.getElements();

        for (MetadataElement dataSetListElem : annotationDataSetListElem) {
            final String elemName = dataSetListElem.getName();
            if (elemName.contains(swath.toLowerCase()) && elemName.contains(polarization.toLowerCase())) {
                final MetadataElement productElem = dataSetListElem.getElement("product");
                final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
                final MetadataElement imageInformationElem = imageAnnotationElem.getElement("imageInformation");
                return imageInformationElem.getAttributeInt("numberOfLines");
            }
        }

        return -1;
    }

    /**
     * Update the metadata in the target product.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().setElemBoolean(true);

        final String[] targetBandNames = targetProduct.getBandNames();
        Sentinel1Utils.updateBandNames(absRoot, selectedPolList, targetBandNames);

        final MetadataElement[] bandMetadataList = AbstractMetadata.getBandAbsMetadataList(absRoot);
        for (MetadataElement bandMeta : bandMetadataList) {
            boolean polFound = false;
            for (String pol : selectedPolList) {
                if (bandMeta.getName().contains(pol)) {
                    polFound = true;
                    break;
                }
            }
            if (!polFound) {
                // remove band metadata if polarization is not included
                absRoot.removeElement(bandMeta);
            }
        }
    }

    /**
     * Create target product.
     */
    @Override
    public Product createTargetProduct(final Product sourceProduct, final String[] sourceBandNames) {

        validate(sourceProduct);

        return super.createTargetProduct(sourceProduct, sourceBandNames);
    }

    @Override
    protected void outputInComplex(final Product sourceProduct, final String[] sourceBandNames) {

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        for (int i = 0; i < sourceBands.length; i += 2) {

            final Band srcBandI = sourceBands[i];
            final String unit = srcBandI.getUnit();
            String nextUnit = null;
            if (unit == null) {
                throw new OperatorException("band " + srcBandI.getName() + " requires a unit");
            } else if (unit.contains(Unit.DB)) {
                throw new OperatorException("Calibration of bands in dB is not supported");
            } else if (unit.contains(Unit.IMAGINARY)) {
                throw new OperatorException("I and Q bands should be selected in pairs");
            } else if (unit.contains(Unit.REAL)) {
                if (i + 1 >= sourceBands.length) {
                    throw new OperatorException("I and Q bands should be selected in pairs");
                }
                nextUnit = sourceBands[i + 1].getUnit();
                if (nextUnit == null || !nextUnit.contains(Unit.IMAGINARY)) {
                    throw new OperatorException("I and Q bands should be selected in pairs");
                }
            } else {
                throw new OperatorException("Please select I and Q bands in pairs only");
            }

            final String pol = srcBandI.getName().substring(srcBandI.getName().lastIndexOf("_") + 1);
            if (!selectedPolList.contains(pol)) {
                continue;
            }

            final Band srcBandQ = sourceBands[i + 1];
            final String[] srcBandNames = {srcBandI.getName(), srcBandQ.getName()};
            targetBandNameToSourceBandName.put(srcBandNames[0], srcBandNames);
            final Band targetBandI = new Band(srcBandNames[0],
                    ProductData.TYPE_FLOAT32,
                    srcBandI.getRasterWidth(),
                    srcBandI.getRasterHeight());
            targetBandI.setUnit(unit);
            targetBandI.setNoDataValueUsed(true);
            targetBandI.setNoDataValue(srcBandI.getNoDataValue());
            targetProduct.addBand(targetBandI);

            targetBandNameToSourceBandName.put(srcBandNames[1], srcBandNames);
            final Band targetBandQ = new Band(srcBandNames[1],
                    ProductData.TYPE_FLOAT32,
                    srcBandQ.getRasterWidth(),
                    srcBandQ.getRasterHeight());
            targetBandQ.setUnit(nextUnit);
            targetBandQ.setNoDataValueUsed(true);
            targetBandQ.setNoDataValue(srcBandQ.getNoDataValue());
            targetProduct.addBand(targetBandQ);

            final String suffix = "_" + OperatorUtils.getSuffixFromBandName(srcBandI.getName());
            ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, suffix);
        }
    }

    @Override
    protected void outputInIntensity(final Product sourceProduct, final String[] sourceBandNames) {

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        for (int i = 0; i < sourceBands.length; i++) {

            final Band srcBand = sourceBands[i];
            final String unit = srcBand.getUnit();
            if (unit == null) {
                throw new OperatorException("band " + srcBand.getName() + " requires a unit");
            }

            if (!unit.contains(Unit.REAL) && !unit.contains(Unit.AMPLITUDE) && !unit.contains(Unit.INTENSITY)) {
                continue;
            }

            String[] srcBandNames;
            if (unit.contains(Unit.REAL)) { // SLC

                if (i + 1 >= sourceBands.length) {
                    throw new OperatorException("Real and imaginary bands are not in pairs");
                }

                final String nextUnit = sourceBands[i + 1].getUnit();
                if (nextUnit == null || !nextUnit.contains(Unit.IMAGINARY)) {
                    throw new OperatorException("Real and imaginary bands are not in pairs");
                }

                srcBandNames = new String[2];
                srcBandNames[0] = srcBand.getName();
                srcBandNames[1] = sourceBands[i + 1].getName();
                ++i;

            } else { // GRD or calibrated product

                srcBandNames = new String[1];
                srcBandNames[0] = srcBand.getName();
            }

            final String pol = srcBandNames[0].substring(srcBandNames[0].lastIndexOf("_") + 1);
            if (!selectedPolList.contains(pol)) {
                continue;
            }

            final String[] targetBandNames = createTargetBandNames(srcBandNames[0]);
            for (String tgtBandName : targetBandNames) {
                if (targetProduct.getBand(tgtBandName) == null) {

                    targetBandNameToSourceBandName.put(tgtBandName, srcBandNames);

                    final Band targetBand = new Band(tgtBandName,
                            ProductData.TYPE_FLOAT32,
                            srcBand.getRasterWidth(),
                            srcBand.getRasterHeight());

                    targetBand.setUnit(Unit.INTENSITY);
                    targetBand.setDescription(srcBand.getDescription());
                    targetBand.setNoDataValue(srcBand.getNoDataValue());
                    targetBand.setNoDataValueUsed(srcBand.isNoDataValueUsed());
                    targetProduct.addBand(targetBand);
                }
            }
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
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        final Rectangle targetTileRectangle = targetTile.getRectangle();
        final int x0 = targetTileRectangle.x;
        final int y0 = targetTileRectangle.y;
        final int w = targetTileRectangle.width;
        final int h = targetTileRectangle.height;
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h + ", target band = " + targetBand.getName());

        try {
            Tile sourceRaster1 = null;
            ProductData srcData1 = null;
            ProductData srcData2 = null;
            Band sourceBand1 = null;

            final String targetBandName = targetBand.getName();
            final String[] srcBandNames = targetBandNameToSourceBandName.get(targetBandName);
            if (srcBandNames.length == 1) {
                sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
                sourceRaster1 = calibrationOp.getSourceTile(sourceBand1, targetTileRectangle);
                srcData1 = sourceRaster1.getDataBuffer();
            } else {
                sourceBand1 = sourceProduct.getBand(srcBandNames[0]);
                final Band sourceBand2 = sourceProduct.getBand(srcBandNames[1]);
                sourceRaster1 = calibrationOp.getSourceTile(sourceBand1, targetTileRectangle);
                final Tile sourceRaster2 = calibrationOp.getSourceTile(sourceBand2, targetTileRectangle);
                srcData1 = sourceRaster1.getDataBuffer();
                srcData2 = sourceRaster2.getDataBuffer();
            }

            final Double noDataValue = sourceBand1.getNoDataValue();
            final Unit.UnitType tgtBandUnit = Unit.getUnitType(targetBand);
            final Unit.UnitType srcBandUnit = Unit.getUnitType(sourceBand1);

            final boolean isUnitAmplitude = srcBandUnit == Unit.UnitType.AMPLITUDE;
            final boolean isUnitIntensity = srcBandUnit == Unit.UnitType.INTENSITY;
            final boolean isUnitReal = srcBandUnit == Unit.UnitType.REAL;
            final boolean isUnitIntensitydB = srcBandUnit == Unit.UnitType.INTENSITY_DB;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(sourceRaster1);
            final TileIndex trgIndex = new TileIndex(targetTile);
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            final CalibrationInfo calInfo = targetBandToCalInfo.get(targetBandName);
            if(calInfo == null) {
                throw new OperatorException("Calibration information not found.");
            }
            final CALTYPE calType = getCalibrationType(targetBandName);

            double dn = 0.0, i, q, muX, lutVal, retroLutVal = 1.0, calValue, calibrationFactor, phaseTerm = 0.0;
            int srcIdx;
            int pixelIdx = -1;

            float trgFloorValue = Sentinel1RemoveThermalNoiseOp.trgFloorValue;

            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                trgIndex.calculateStride(y);

                final int calVecIdx = calInfo.getCalibrationVectorIndex(subsetOffsetY + y);
                final Sentinel1Utils.CalibrationVector vec0 = calInfo.getCalibrationVector(calVecIdx);
                final Sentinel1Utils.CalibrationVector vec1 = calInfo.getCalibrationVector(calVecIdx + 1);
                final float[] vec0LUT = getVector(calType, vec0);
                final float[] vec1LUT = getVector(calType, vec1);
                float[] retroVec0LUT = null;
                float[] retroVec1LUT = null;
                if (dataType != null) {
                    retroVec0LUT = getVector(dataType, vec0);
                    retroVec1LUT = getVector(dataType, vec1);
                }
                final double azTime = calInfo.firstLineTime + (subsetOffsetY + y) * calInfo.lineTimeInterval;
                final double muY = (azTime - vec0.timeMJD) / (vec1.timeMJD - vec0.timeMJD);
                final int[] vec0Pixels = vec0.pixels;
                final Sentinel1Utils.CalibrationVector calVec = calInfo.calibrationVectorList[calVecIdx];

                for (int x = x0; x < maxX; ++x) {
                    srcIdx = srcIndex.getIndex(x);

                    dn = srcData1.getElemDoubleAt(srcIdx);

                    pixelIdx = getPixelIndex(calVec, pixelIdx, subsetOffsetX + x);
                    muX = (subsetOffsetX + x - vec0Pixels[pixelIdx]) /
                            (double)(vec0Pixels[pixelIdx + 1] - vec0Pixels[pixelIdx]);

                    lutVal = (1 - muY) * ((1 - muX) * vec0LUT[pixelIdx] + muX * vec0LUT[pixelIdx + 1]) +
                            muY * ((1 - muX) * vec1LUT[pixelIdx] + muX * vec1LUT[pixelIdx + 1]);

                    calibrationFactor = 1.0 / (lutVal*lutVal);

                    if (isUnitAmplitude) {
                        dn *= dn;
                    } else if (isUnitIntensity) {
                        if (dataType != null) {
                            retroLutVal = (1 - muY) * ((1 - muX) * retroVec0LUT[pixelIdx] + muX * retroVec0LUT[pixelIdx + 1]) +
                                    muY * ((1 - muX) * retroVec1LUT[pixelIdx] + muX * retroVec1LUT[pixelIdx + 1]);
                        }
                        calibrationFactor *= retroLutVal;
                    } else if (isUnitReal) {
                        i = dn;
                        q = srcData2.getElemDoubleAt(srcIdx);
                        dn = i * i + q * q;
                        if (dn > 0.0) {
                            if (tgtBandUnit == Unit.UnitType.REAL) {
                                phaseTerm = i / Math.sqrt(dn);
                            } else if (tgtBandUnit == Unit.UnitType.IMAGINARY) {
                                phaseTerm = q / Math.sqrt(dn);
                            }
                        } else {
                            phaseTerm = 0.0;
                        }
                    } else if (isUnitIntensitydB) {
                        dn = FastMath.pow(10, dn / 10.0); // convert dB to linear scale
                    } else {
                        throw new OperatorException("Sentinel-1 Calibration: unhandled unit");
                    }

                    calValue = dn * calibrationFactor;

                    if(dn == trgFloorValue) {
                        while((float)calValue < 0.00001) {
                            dn *= 2;
                            calValue = dn * calibrationFactor;
                        }
                    }

                    if (isComplex && outputImageInComplex) {
                        calValue = Math.sqrt(calValue)*phaseTerm;
                    }

                    tgtData.setElemDoubleAt(trgIndex.getIndex(x), calValue);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
            //OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private static int getPixelIndex(final Sentinel1Utils.CalibrationVector calVec, final int lastIndex, final int x) {
        if(lastIndex >= 0 && lastIndex < calVec.pixels.length-1 && x >= calVec.pixels[lastIndex] && x < calVec.pixels[lastIndex+1]) {
            return lastIndex;
        }
        int index = Arrays.binarySearch(calVec.pixels, x);
        if(index < 0) {
            index *= -1;
            index -= 2;
        }
        if(index >= calVec.pixels.length-1)
            index--;

        return index;
    }

    public static CALTYPE getCalibrationType(final String bandName) {
        CALTYPE calType;
        if (bandName.contains("Beta")) {
            calType = CALTYPE.BETA0;
        } else if (bandName.contains("Gamma")) {
            calType = CALTYPE.GAMMA;
        } else if (bandName.contains("DN")) {
            calType = CALTYPE.DN;
        } else {
            calType = CALTYPE.SIGMA0;
        }
        return calType;
    }

    public static float[] getVector(final CALTYPE calType, final Sentinel1Utils.CalibrationVector vec) {
        if (calType == null) {
            return null;
        }else if (calType.equals(CALTYPE.SIGMA0)) {
            return vec.sigmaNought;
        } else if (calType.equals(CALTYPE.BETA0)) {
            return vec.betaNought;
        } else if (calType.equals(CALTYPE.GAMMA)) {
            return vec.gamma;
        } else {
            return vec.dn;
        }
    }


    public double applyCalibration(
            final double v, final double rangeIndex, final double azimuthIndex, final double slantRange,
            final double satelliteHeight, final double sceneToEarthCentre, final double localIncidenceAngle,
            final String bandName, final String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {

        final CalibrationInfo calInfo = targetBandToCalInfo.get(bandName);
        final int calVecIdx = calInfo.getCalibrationVectorIndex((int)azimuthIndex);
        final Sentinel1Utils.CalibrationVector vec0 = calInfo.getCalibrationVector(calVecIdx);
        final Sentinel1Utils.CalibrationVector vec1 = calInfo.getCalibrationVector(calVecIdx + 1);
        final CALTYPE calType = getCalibrationType(bandName);
        final float[] vec0LUT = getVector(calType, vec0);
        final float[] vec1LUT = getVector(calType, vec1);
        final Sentinel1Utils.CalibrationVector calVec = calInfo.calibrationVectorList[calVecIdx];
        final int pixelIdx = calVec.getPixelIndex((int)rangeIndex);
        final double azTime = calInfo.firstLineTime + azimuthIndex * calInfo.lineTimeInterval;
        final double muY = (azTime - vec0.timeMJD) / (vec1.timeMJD - vec0.timeMJD);
        final double muX =
                (rangeIndex - vec0.pixels[pixelIdx]) / (double)(vec0.pixels[pixelIdx + 1] - vec0.pixels[pixelIdx]);

        final double lutVal =
                (1 - muY) * ((1 - muX) * vec0LUT[pixelIdx] + muX * vec0LUT[pixelIdx + 1]) +
                        muY * ((1 - muX) * vec1LUT[pixelIdx] + muX * vec1LUT[pixelIdx + 1]);

        double sigma = 0.0;
        if (bandUnit == Unit.UnitType.AMPLITUDE) {
            sigma = v*v / (lutVal*lutVal);
        } else if (bandUnit == Unit.UnitType.INTENSITY) {
            sigma = v / (lutVal*lutVal);
        } else if (bandUnit == Unit.UnitType.INTENSITY_DB) {
            sigma = FastMath.pow(10, v / 10.0) / (lutVal * lutVal); // convert dB to linear scale
        } else if (bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY) {
            sigma = v / lutVal;
        } else {
            throw new OperatorException("Unknown band unit");
        }

        return sigma;
    }

    public double applyRetroCalibration(
            int x, int y, double v, String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {

        // no need to do anything because s-1 GRD product has not yet been calibrated
        return v;
    }

    public void removeFactorsForCurrentTile(final Band targetBand, final Tile targetTile,
                                            final String srcBandName) throws OperatorException {

        final Band sourceBand = sourceProduct.getBand(targetBand.getName());
        final Tile sourceTile = calibrationOp.getSourceTile(sourceBand, targetTile.getRectangle());
        targetTile.setRawSamples(sourceTile.getRawSamples());
    }

    public final static class CalibrationInfo {
        public final String subSwath;
        public final String polarization;
        public final double firstLineTime;
        public final double lastLineTime;
        public final int numOfLines;
        public final int count; // number of calibrationVector records within the list
        public final Sentinel1Utils.CalibrationVector[] calibrationVectorList;
        public final double lineTimeInterval;

        CalibrationInfo(String subSwath, String polarization, final double firstLineTime, final double lastLineTime,
                        final int numOfLines, final int count,
                        final Sentinel1Utils.CalibrationVector[] calibrationVectorList) {
            this.subSwath = subSwath;
            this.polarization = polarization;
            this.firstLineTime = firstLineTime;
            this.lastLineTime = lastLineTime;
            this.numOfLines = numOfLines;
            this.count = count;
            this.calibrationVectorList = calibrationVectorList;

            this.lineTimeInterval = (lastLineTime - firstLineTime) / (numOfLines - 1);
        }

        public int getCalibrationVectorIndex(final int y) {
            for (int i = 1; i < count; i++) {
                if (y < calibrationVectorList[i].line) {
                    return i - 1;
                }
            }
            return -1;
        }

        public Sentinel1Utils.CalibrationVector getCalibrationVector(final int calVecIdx) {
            return calibrationVectorList[calVecIdx];
        }
    }
}
