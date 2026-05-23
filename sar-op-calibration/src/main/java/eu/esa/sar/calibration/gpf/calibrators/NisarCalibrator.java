/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
import eu.esa.sar.calibration.gpf.support.BaseCalibrator;
import eu.esa.sar.calibration.gpf.support.Calibrator;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Radiometric calibration for NASA-ISRO SAR (NISAR) Level-1 products.
 * <p>
 * NISAR Level-1 RSLC products carry an absolute calibration constant per polarisation
 * in the HDF5 metadata at
 * {@code /science/{LSAR|SSAR}/RSLC/metadata/calibrationInformation/frequency{A,B}/<pol>/radiometricCalibrationConstant}.
 * The reader surfaces this as {@code calibration_factor} on each band's abstract
 * metadata element; this operator consumes those values.
 * <p>
 * Geocoded products (GSLC, GCOV, GUNW, GOFF, GUNW) are rejected — GCOV is already in
 * calibrated intensity units (covariance terms), and the rest are derived products
 * that don't carry the raw DN-to-sigma0 calibration constants. The L3 SME2 soil
 * moisture product is also rejected as it ships in geophysical units.
 */
public final class NisarCalibrator extends BaseCalibrator implements Calibrator {

    private static final String[] SUPPORTED_MISSIONS = new String[] {"NISAR"};

    private TiePointGrid sigma0TPG = null;
    private TiePointGrid gamma0TPG = null;
    private TiePointGrid incidenceAngleTPG = null;
    private final Map<String, Double> calibrationConstants = new HashMap<>();
    private CALTYPE dataType = null;
    private Boolean doRetroCalibration = false;

    public enum CALTYPE {SIGMA0, BETA0, GAMMA, DN}

    /**
     * Default constructor required by the GPF framework.
     */
    public NisarCalibrator() {
    }

    @Override
    public String[] getSupportedMissions() {
        return SUPPORTED_MISSIONS;
    }

    /**
     * NISAR ships calibration constants inside the product itself; no external aux file.
     */
    public void setExternalAuxFile(File file) throws OperatorException {
        if (file != null) {
            throw new OperatorException("No external auxiliary file should be selected for NISAR product");
        }
    }

    @Override
    public void setAuxFileFlag(String file) {
    }

    public void initialize(final Operator op, final Product srcProduct, final Product tgtProduct,
                           final boolean mustPerformRetroCalibration, final boolean mustUpdateMetadata)
            throws OperatorException {
        try {
            this.calibrationOp = op;
            this.sourceProduct = srcProduct;
            this.targetProduct = tgtProduct;

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            // Reject products that are not RSLC. NISAR L2 products (GSLC, GCOV, GUNW, GOFF)
            // and L3 (SME2) don't carry the raw DN-to-sigma0 calibration constants — they're
            // either already in calibrated intensity units (GCOV), already complex-calibrated
            // (GSLC), or fundamentally not SAR measurements (SME2 = soil moisture).
            final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE, "");
            if (productType != null && !productType.isEmpty() && !"RSLC".equalsIgnoreCase(productType)) {
                throw new OperatorException(
                        "NISAR " + productType + " is not radiometrically calibratable: " +
                        "GCOV is already in covariance/intensity units, GSLC is complex-calibrated, " +
                        "GUNW/GOFF/RIFG/RUNW/ROFF are derived interferometric / offset products, " +
                        "and SME2 is a geophysical (soil moisture) product. " +
                        "Apply this calibrator to the corresponding RSLC product instead.");
            }

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
        incidenceAngleTPG = sourceProduct.getTiePointGrid(OperatorUtils.TPG_INCIDENT_ANGLE);

        // Read per-polarization calibration constants from band metadata. The NISAR
        // reader populates these from the HDF5 calibrationInformation group. If they
        // are missing (older product / partial metadata) we fall back to K=1.0 in
        // getLutValue, which leaves the data uncalibrated but consistent.
        final MetadataElement[] bandMetadataList = AbstractMetadata.getBandAbsMetadataList(absRoot);
        for (MetadataElement bandMeta : bandMetadataList) {
            final String pol = bandMeta.getAttributeString(AbstractMetadata.polarization, "");
            final double calFactor = bandMeta.getAttributeDouble(AbstractMetadata.calibration_factor,
                    AbstractMetadata.NO_METADATA);
            if (!pol.isEmpty() && calFactor != AbstractMetadata.NO_METADATA) {
                calibrationConstants.put(pol.toUpperCase(), calFactor);
            }
        }
    }

    private void updateTargetProductMetadata() {
        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        absTgt.getAttribute(AbstractMetadata.abs_calibration_flag).getData().setElemBoolean(true);

        final MetadataElement[] bandMetadataList = AbstractMetadata.getBandAbsMetadataList(absTgt);
        for (MetadataElement bandMeta : bandMetadataList) {
            boolean polFound = false;
            for (String pol : selectedPolList) {
                if (bandMeta.getName().contains(pol)) {
                    polFound = true;
                    break;
                }
            }
            if (!polFound) {
                absTgt.removeElement(bandMeta);
            }
        }
    }

    @Override
    protected void outputInComplex(final Product sourceProduct, final String[] sourceBandNames) {
        final Band[] allBands = getSourceBands(sourceProduct, sourceBandNames, true);

        List<Band> sourceBandsList = new ArrayList<>();
        for (Band band : allBands) {
            final String unit = band.getUnit();
            if (unit != null && (unit.contains(Unit.REAL) || unit.contains(Unit.IMAGINARY))) {
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

            if (shouldSkipForPolarisation(srcBandI.getName())) {
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
            if (srcBandI.hasGeoCoding()) {
                ProductUtils.copyGeoCoding(srcBandI, targetBandI);
            }

            targetBandNameToSourceBandName.put(srcBandNames[1], srcBandNames);
            final Band targetBandQ = new Band(
                    srcBandNames[1], ProductData.TYPE_FLOAT32, srcBandQ.getRasterWidth(), srcBandQ.getRasterHeight());
            targetProduct.addBand(targetBandQ);
            targetBandQ.setUnit(nextUnit);
            targetBandQ.setNoDataValueUsed(true);
            targetBandQ.setNoDataValue(srcBandQ.getNoDataValue());
            if (srcBandQ.hasGeoCoding()) {
                ProductUtils.copyGeoCoding(srcBandQ, targetBandQ);
            }

            final String suffix = '_' + OperatorUtils.getSuffixFromBandName(srcBandI.getName());
            ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, suffix);
        }
    }

    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        final Rectangle targetTileRectangle = targetTile.getRectangle();
        final int x0 = targetTileRectangle.x;
        final int y0 = targetTileRectangle.y;
        final int w = targetTileRectangle.width;
        final int h = targetTileRectangle.height;

        try {
            Tile sourceRaster1;
            ProductData srcData1;
            ProductData srcData2 = null;
            Band sourceBand1;

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
            final boolean isUnitIntensity = srcBandUnit == Unit.UnitType.INTENSITY;

            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex srcIndex = new TileIndex(sourceRaster1);
            final TileIndex trgIndex = new TileIndex(targetTile);
            final int maxY = y0 + h;
            final int maxX = x0 + w;

            final CALTYPE calType = getCalibrationType(targetBandName);
            final String bandPol = OperatorUtils.getPolarizationFromBandName(targetBandName);
            final double srcNodataValue = sourceBand1.getNoDataValue();
            final double trgNodataValue = targetBand.getNoDataValue();

            double i, q, phaseTerm = 0.0;
            for (int y = y0; y < maxY; ++y) {
                srcIndex.calculateStride(y);
                trgIndex.calculateStride(y);

                for (int x = x0; x < maxX; ++x) {
                    final int srcIdx = srcIndex.getIndex(x);

                    double dn = srcData1.getElemDoubleAt(srcIdx);
                    if (dn == srcNodataValue) {
                        tgtData.setElemDoubleAt(trgIndex.getIndex(x), trgNodataValue);
                        continue;
                    }

                    double calibrationFactor = 1.0;
                    if (calType != null) {
                        calibrationFactor = getLutValue(calType, x, y, bandPol);
                    }

                    if (doRetroCalibration && dataType != null) {
                        final double inv = getLutValue(dataType, x, y, bandPol);
                        if (inv != 0.0) calibrationFactor /= inv;
                    }

                    if (isUnitAmplitude) {
                        dn *= dn;
                    } else if (isUnitIntensitydB) {
                        dn = FastMath.pow(10, dn / 10.0);
                    } else if (isUnitReal) {
                        i = dn;
                        q = (srcData2 != null) ? srcData2.getElemDoubleAt(srcIdx) : 0.0;
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
                    } else if (isUnitIntensity) {
                        // Already intensity — no conversion, just apply the cal factor.
                    } else {
                        throw new OperatorException("NISAR Calibration: unhandled unit " + srcBandUnit);
                    }

                    double calValue = dn * calibrationFactor;

                    if (isComplex && outputImageInComplex) {
                        calValue = Math.sqrt(calValue) * phaseTerm;
                    }

                    tgtData.setElemDoubleAt(trgIndex.getIndex(x), calValue);
                }
            }
        } catch (Throwable e) {
            throw new OperatorException("NISAR calibration failed: " + e.getMessage(), e);
        } finally {
            pm.done();
        }
    }

    public static CALTYPE getCalibrationType(final String bandName) {
        if (bandName.contains("Beta")) return CALTYPE.BETA0;
        if (bandName.contains("Gamma")) return CALTYPE.GAMMA;
        if (bandName.contains("DN")) return CALTYPE.DN;
        return CALTYPE.SIGMA0;
    }

    private double getLutValue(final CALTYPE calType, final double x, final double y, final String bandPol) {
        // Pre-computed LUT tie-point grids would override the per-polarisation constant.
        // NISAR Phase-1 calibrator: not yet populated by the reader.
        if (calType == CALTYPE.SIGMA0 && sigma0TPG != null) {
            return sigma0TPG.getPixelDouble(x, y);
        }
        if (calType == CALTYPE.GAMMA && gamma0TPG != null) {
            return gamma0TPG.getPixelDouble(x, y);
        }

        // Fallback: per-polarisation absolute constant from the HDF5 calibrationInformation.
        final double K = calibrationConstants.getOrDefault(
                bandPol != null ? bandPol.toUpperCase() : "", 1.0);

        if (calType == CALTYPE.SIGMA0) {
            return K;
        }
        if (calType == CALTYPE.BETA0) {
            if (incidenceAngleTPG != null) {
                final double incAngleRad = Math.toRadians(incidenceAngleTPG.getPixelDouble(x, y));
                final double sinInc = Math.sin(incAngleRad);
                return sinInc > 0 ? K / sinInc : K;
            }
            return K;
        }
        if (calType == CALTYPE.GAMMA) {
            if (incidenceAngleTPG != null) {
                final double incAngleRad = Math.toRadians(incidenceAngleTPG.getPixelDouble(x, y));
                final double cosInc = Math.cos(incAngleRad);
                return cosInc > 0 ? K / cosInc : K;
            }
            return K;
        }
        return 1.0; // DN
    }

    public double applyCalibration(
            final double v, final double rangeIndex, final double azimuthIndex, final double slantRange,
            final double satelliteHeight, final double sceneToEarthCentre, final double localIncidenceAngle,
            final String bandName, final String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {
        final CALTYPE calType = getCalibrationType(bandName);
        final double lutVal = getLutValue(calType, rangeIndex, azimuthIndex, bandPolar);

        if (bandUnit == Unit.UnitType.AMPLITUDE) {
            return v * v * lutVal;
        }
        if (bandUnit == Unit.UnitType.INTENSITY) {
            return v * lutVal;
        }
        if (bandUnit == Unit.UnitType.INTENSITY_DB) {
            return FastMath.pow(10, v / 10.0) * lutVal;
        }
        if (bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY) {
            return v * Math.sqrt(lutVal);
        }
        throw new OperatorException("Unknown band unit");
    }

    public double applyRetroCalibration(
            int x, int y, double v, String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {
        // NISAR RSLC ships uncalibrated; nothing to undo.
        return v;
    }

    public void removeFactorsForCurrentTile(final Band targetBand, final Tile targetTile,
                                            final String srcBandName) throws OperatorException {
        final Band sourceBand = sourceProduct.getBand(targetBand.getName());
        final Tile sourceTile = calibrationOp.getSourceTile(sourceBand, targetTile.getRectangle());
        targetTile.setRawSamples(sourceTile.getRawSamples());
    }
}
