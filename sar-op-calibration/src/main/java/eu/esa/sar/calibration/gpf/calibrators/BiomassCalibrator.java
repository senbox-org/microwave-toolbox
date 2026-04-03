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
package eu.esa.sar.calibration.gpf.calibrators;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import eu.esa.sar.calibration.gpf.Sentinel1RemoveThermalNoiseOp;
import eu.esa.sar.calibration.gpf.support.BaseCalibrator;
import eu.esa.sar.calibration.gpf.support.Calibrator;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Calibration for ESA Biomass data products.
 */

public final class BiomassCalibrator extends BaseCalibrator implements Calibrator {

    private static final String[] SUPPORTED_MISSIONS = new String[] {"BIOMASS"};

    private TiePointGrid sigma0TPG = null;
    private TiePointGrid gamma0TPG = null;
    private CALTYPE dataType = null;
    private Boolean doRetroCalibration = false;

    public enum CALTYPE {SIGMA0, BETA0, GAMMA, DN}

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public BiomassCalibrator() {
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
            throw new OperatorException("No external auxiliary file should be selected for Sentinel1 product");
        }
    }

    /**
     * Set auxiliary file flag.
     */
    @Override
    public void setAuxFileFlag(String file) {
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

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            getSampleType();

            doRetroCalibration = absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean();
            if (doRetroCalibration) {
                dataType = getCalibrationType(sourceProduct.getBandAt(0).getName());
            }

            getTiePointGrids();

            if (mustUpdateMetadata) {
                updateTargetProductMetadata();
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void getTiePointGrids() {
        sigma0TPG = sourceProduct.getTiePointGrid("sigmaNought");
        gamma0TPG = sourceProduct.getTiePointGrid("gammaNought");
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

    @Override
    protected void outputInComplex(final Product sourceProduct, final String[] sourceBandNames) {

        final Band[] allBands = getSourceBands(sourceProduct, sourceBandNames, true);

        List<Band> sourceBandsList = new ArrayList<>();
        for(Band band : allBands) {
            String unit = band.getUnit();
            if(unit.contains(Unit.REAL) || unit.contains(Unit.IMAGINARY)) {
                sourceBandsList.add(band);
            }
        }
        final Band[] sourceBands = sourceBandsList.toArray(new Band[0]);

        for (int i = 0; i < sourceBands.length; i += 2) {

            final Band srcBandI = sourceBands[i];
            final String unit = srcBandI.getUnit();
            String nextUnit;
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
            if (!selectedPolList.isEmpty() && !selectedPolList.contains(pol)) {
                continue;
            }

            final Band srcBandQ = sourceBands[i + 1];
            final String[] srcBandNames = {srcBandI.getName(), srcBandQ.getName()};
            targetBandNameToSourceBandName.put(srcBandNames[0], srcBandNames);
            final Band targetBandI = new Band(
                    srcBandNames[0], ProductData.TYPE_FLOAT32, srcBandI.getRasterWidth(), srcBandI.getRasterHeight());
            targetProduct.addBand(targetBandI);
            targetBandI.setUnit(unit);
            targetBandI.setNoDataValueUsed(true);
            targetBandI.setNoDataValue(srcBandI.getNoDataValue());

            if(srcBandI.hasGeoCoding()) {
                // copy band geocoding after target band added to target product
                ProductUtils.copyGeoCoding(srcBandI, targetBandI);
            }

            targetBandNameToSourceBandName.put(srcBandNames[1], srcBandNames);
            final Band targetBandQ = new Band(
                    srcBandNames[1], ProductData.TYPE_FLOAT32, srcBandQ.getRasterWidth(), srcBandQ.getRasterHeight());
            targetProduct.addBand(targetBandQ);
            targetBandQ.setUnit(nextUnit);
            targetBandQ.setNoDataValueUsed(true);
            targetBandQ.setNoDataValue(srcBandQ.getNoDataValue());

            if(srcBandQ.hasGeoCoding()) {
                // copy band geocoding after target band added to target product
                ProductUtils.copyGeoCoding(srcBandQ, targetBandQ);
            }

            final String suffix = '_' + OperatorUtils.getSuffixFromBandName(srcBandI.getName());
            ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, suffix);
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

            final Unit.UnitType tgtBandUnit = Unit.getUnitType(targetBand);
            final Unit.UnitType srcBandUnit = Unit.getUnitType(sourceBand1);
            final boolean isUnitAmplitude = srcBandUnit == Unit.UnitType.AMPLITUDE;
            final boolean isUnitIntensitydB = srcBandUnit == Unit.UnitType.INTENSITY_DB;
            final boolean isUnitReal = srcBandUnit == Unit.UnitType.REAL;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(sourceRaster1);
            final TileIndex trgIndex = new TileIndex(targetTile);
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            final CALTYPE calType = getCalibrationType(targetBandName);
            float trgFloorValue = Sentinel1RemoveThermalNoiseOp.trgFloorValue;
            double srcNodataValue = sourceBand1.getNoDataValue();
            double trgNodataValue = targetBand.getNoDataValue();

            double i, q, phaseTerm = 0.0;
            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                trgIndex.calculateStride(y);

                for (int x = x0; x < maxX; ++x) {
                    final int srcIdx = srcIndex.getIndex(x);

                    double dn = srcData1.getElemDoubleAt(srcIdx);
                    if(dn == srcNodataValue) {
                        tgtData.setElemDoubleAt(trgIndex.getIndex(x), trgNodataValue);
                        continue;
                    }

                    double calibrationFactor = 1.0;
                    if (calType != null) {
                        calibrationFactor = getLutValue(calType, x, y);
                    }

                    if (doRetroCalibration && dataType != null) {
                        calibrationFactor /= getLutValue(dataType, x, y);
                    }

                    if (isUnitAmplitude) {
                        dn *= dn;
                    } else if (isUnitIntensitydB) {
                        dn = FastMath.pow(10, dn / 10.0); // convert dB to linear scale
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
                    } else {
                        throw new OperatorException("Biomass Calibration: unhandled unit");
                    }

                    double calValue = dn * calibrationFactor;
                    if (dn == trgFloorValue) {
                        final int max_iter = 1000;
                        int iter = 0;
                        while( (float)calValue < 0.00001 && iter < max_iter) {
                            dn *= 2;
                            calValue = dn * calibrationFactor;
                            iter += 1;
                        }
                    }

                    if (isComplex && outputImageInComplex) {
                        calValue = Math.sqrt(calValue) * phaseTerm;
                    }

                    tgtData.setElemDoubleAt(trgIndex.getIndex(x), calValue);
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            pm.done();
        }
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

    private double getLutValue(final CALTYPE calType, final double x, final double y) {

        if (calType.equals(CALTYPE.SIGMA0)) {
            return sigma0TPG.getPixelDouble(x, y);
        } else if (calType.equals(CALTYPE.BETA0)) {
            return 1.0;
        } else if (calType.equals(CALTYPE.GAMMA)) {
            return gamma0TPG.getPixelDouble(x, y);
        } else {
            return 1.0;
        }
    }


    public double applyCalibration(
            final double v, final double rangeIndex, final double azimuthIndex, final double slantRange,
            final double satelliteHeight, final double sceneToEarthCentre, final double localIncidenceAngle,
            final String bandName, final String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {

        final CALTYPE calType = getCalibrationType(bandName);
        final double lutVal = getLutValue(calType, rangeIndex, azimuthIndex);

        double sigma = 0.0;
        if (bandUnit == Unit.UnitType.AMPLITUDE) {
            sigma = v * v * lutVal;
        } else if (bandUnit == Unit.UnitType.INTENSITY) {
            sigma = v * lutVal;
        } else if (bandUnit == Unit.UnitType.INTENSITY_DB) {
            sigma = FastMath.pow(10, v / 10.0) * lutVal; // convert dB to linear scale
        } else if (bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY) {
            sigma = v * Math.sqrt(lutVal);
        } else {
            throw new OperatorException("Unknown band unit");
        }

        return sigma;
    }

    public double applyRetroCalibration(
            int x, int y, double v, String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {

        // no need to do anything because biomass DGM product has not yet been calibrated
        return v;
    }

    public void removeFactorsForCurrentTile(final Band targetBand, final Tile targetTile,
                                            final String srcBandName) throws OperatorException {

        final Band sourceBand = sourceProduct.getBand(targetBand.getName());
        final Tile sourceTile = calibrationOp.getSourceTile(sourceBand, targetTile.getRectangle());
        targetTile.setRawSamples(sourceTile.getRawSamples());
    }
}
