/*
 * Copyright (C) 2023 by SkyWatch Space Applications http://www.skywatch.com
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
import eu.esa.sar.calibration.gpf.support.BaseCalibrator;
import eu.esa.sar.calibration.gpf.support.Calibrator;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Calibration for CONAE SAOCOM data products.
 *
 * For SLC products: applies radiometric calibration using per-pixel LUT vectors
 * from the product's Calibration/ directory (if available), or a scalar calibration
 * factor as fallback.
 *
 * For DI/GEC/GTC products: data is already calibrated to sigma0, so calibration
 * is a pass-through (with optional incidence angle re-projection).
 */
public class SaocomCalibrator extends BaseCalibrator implements Calibrator {

    private static final String[] SUPPORTED_MISSIONS = new String[]{"SAOCOM"};

    private boolean inputSigma0 = false;
    private TiePointGrid incidenceAngle = null;
    private double scalarCalibrationFactor = 1.0;

    // Per-pixel calibration LUT, keyed by polarization
    private final Map<String, CalibrationLut> calibrationLuts = new HashMap<>();
    private boolean hasPerPixelLut = false;

    private static final String USE_INCIDENCE_ANGLE_FROM_DEM = "Use projected local incidence angle from DEM";

    /**
     * Holds per-pixel calibration vectors for a single polarization channel.
     * Vectors are 1D (range direction only) — each element corresponds to a range pixel.
     */
    static class CalibrationLut {
        final float[] sigmaNought;  // sigma0 calibration LUT values (linear scale)
        final int numPixels;

        CalibrationLut(float[] sigmaNought) {
            this.sigmaNought = sigmaNought;
            this.numPixels = sigmaNought != null ? sigmaNought.length : 0;
        }

        /**
         * Get the interpolated sigma0 calibration value for a given range pixel.
         * Uses linear interpolation between LUT samples.
         */
        double getSigma0Value(int rangePixel, int imageWidth) {
            if (sigmaNought == null || numPixels == 0) {
                return 1.0;
            }
            // Map image pixel to LUT index (LUT may be subsampled)
            double lutIndex = (double) rangePixel / imageWidth * (numPixels - 1);
            int idx0 = Math.max(0, Math.min((int) lutIndex, numPixels - 2));
            int idx1 = idx0 + 1;
            double frac = lutIndex - idx0;

            return (1.0 - frac) * sigmaNought[idx0] + frac * sigmaNought[idx1];
        }
    }

    public SaocomCalibrator() {
    }

    @Override
    public String[] getSupportedMissions() {
        return SUPPORTED_MISSIONS;
    }

    public void setExternalAuxFile(File file) throws OperatorException {
        if (file != null) {
            throw new OperatorException("No external auxiliary file should be selected for SAOCOM product");
        }
    }

    @Override
    public void setAuxFileFlag(String file) {
    }

    public void initialize(final Operator op, final Product srcProduct, final Product tgtProduct,
                           final boolean mustPerformRetroCalibration, final boolean mustUpdateMetadata)
            throws OperatorException {
        try {
            calibrationOp = op;
            sourceProduct = srcProduct;
            targetProduct = tgtProduct;

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
            if (!mission.equals("SAOCOM"))
                throw new OperatorException(mission + " is not a valid mission for SAOCOM Calibration");

            if (absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean()) {
                inputSigma0 = true;
            }

            getSampleType();
            loadCalibrationData();
            getTiePointGridData(sourceProduct);

            if (mustUpdateMetadata) {
                updateTargetProductMetadata();
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Load calibration data from product metadata.
     * Tries per-pixel LUT vectors first, falls back to scalar calibration factor.
     */
    private void loadCalibrationData() {
        // Try to load per-pixel calibration LUTs from metadata
        final MetadataElement calibrationElem = absRoot.getElement("calibration");
        if (calibrationElem != null) {
            for (MetadataElement calFileElem : calibrationElem.getElements()) {
                try {
                    final CalibrationLut lut = parseCalibrationLut(calFileElem);
                    if (lut != null && lut.numPixels > 0) {
                        // Extract polarization from filename (e.g., calibrationLut_HH.xml -> HH)
                        String pol = extractPolFromName(calFileElem.getName());
                        if (pol != null) {
                            calibrationLuts.put(pol, lut);
                            hasPerPixelLut = true;
                        }
                    }
                } catch (Exception e) {
                    SystemUtils.LOG.warning("Error parsing SAOCOM calibration LUT: " + e.getMessage());
                }
            }
        }

        if (hasPerPixelLut) {
            SystemUtils.LOG.info("SAOCOM: Using per-pixel calibration LUT vectors (" + calibrationLuts.size() + " channels)");
        }

        // Always load scalar fallback
        final double metaCal = absRoot.getAttributeDouble(AbstractMetadata.calibration_factor, AbstractMetadata.NO_METADATA);
        if (metaCal != AbstractMetadata.NO_METADATA && metaCal != 0) {
            scalarCalibrationFactor = metaCal;
        }
    }

    /**
     * Parse a calibration LUT from a metadata element.
     * Tries multiple XML structures that SAOCOM products may use.
     */
    private CalibrationLut parseCalibrationLut(MetadataElement elem) {
        // Try: direct sigmaNought element with space-delimited values
        float[] sigma = tryParseVector(elem, "sigmaNought");
        if (sigma == null) sigma = tryParseVector(elem, "SigmaNought");
        if (sigma == null) sigma = tryParseVector(elem, "sigma_nought");

        // Try: nested structure (calibrationVector/sigmaNought)
        if (sigma == null) {
            MetadataElement vectorElem = elem.getElement("calibrationVector");
            if (vectorElem == null) vectorElem = elem.getElement("CalibrationVector");
            if (vectorElem != null) {
                sigma = tryParseVector(vectorElem, "sigmaNought");
                if (sigma == null) sigma = tryParseVector(vectorElem, "SigmaNought");
            }
        }

        // Try: values element
        if (sigma == null) {
            MetadataElement valuesElem = elem.getElement("values");
            if (valuesElem == null) valuesElem = elem.getElement("Values");
            if (valuesElem != null) {
                sigma = tryParseVector(valuesElem, "sigmaNought");
                if (sigma == null) sigma = tryParseVector(valuesElem, "sigma_nought");
            }
        }

        if (sigma != null) {
            return new CalibrationLut(sigma);
        }

        // Try: scalar calibration constant as 1-element LUT
        double calConst = elem.getAttributeDouble("calibrationConstant",
                elem.getAttributeDouble("CalibrationConstant", 0));
        if (calConst != 0) {
            return new CalibrationLut(new float[]{(float) calConst});
        }

        return null;
    }

    /**
     * Try to parse a space/tab-delimited float vector from a metadata attribute.
     */
    private float[] tryParseVector(MetadataElement elem, String attrName) {
        if (!elem.containsAttribute(attrName)) {
            // Try as sub-element with text content
            MetadataElement subElem = elem.getElement(attrName);
            if (subElem != null && subElem.containsAttribute(attrName)) {
                return parseFloatString(subElem.getAttributeString(attrName, ""));
            }
            return null;
        }
        return parseFloatString(elem.getAttributeString(attrName, ""));
    }

    private float[] parseFloatString(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try {
            String[] tokens = str.trim().split("[\\s,;]+");
            if (tokens.length < 2) return null;  // Need at least 2 values for interpolation
            float[] values = new float[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                values[i] = Float.parseFloat(tokens[i]);
            }
            return values;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractPolFromName(String filename) {
        // calibrationLut_HH.xml -> HH
        String upper = filename.toUpperCase();
        for (String pol : new String[]{"HH", "HV", "VH", "VV", "RCH", "RCV"}) {
            if (upper.contains(pol)) return pol;
        }
        return null;
    }

    private void getTiePointGridData(Product sourceProduct) {
        incidenceAngle = OperatorUtils.getIncidenceAngle(sourceProduct);
    }

    private void updateTargetProductMetadata() {
        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(targetProduct);
        abs.getAttribute(AbstractMetadata.abs_calibration_flag).getData().setElemBoolean(true);
    }

    /**
     * Get the calibration value for a given pixel.
     * Uses per-pixel LUT if available, otherwise scalar fallback.
     */
    private double getCalibrationValue(int x, String bandPol, int imageWidth) {
        if (hasPerPixelLut && bandPol != null) {
            CalibrationLut lut = calibrationLuts.get(bandPol.toUpperCase());
            if (lut != null) {
                return lut.getSigma0Value(x, imageWidth);
            }
        }
        return scalarCalibrationFactor;
    }

    public void computeTile(Band targetBand, Tile targetTile,
                            ProgressMonitor pm) throws OperatorException {

        final Rectangle targetTileRectangle = targetTile.getRectangle();
        final int x0 = targetTileRectangle.x;
        final int y0 = targetTileRectangle.y;
        final int w = targetTileRectangle.width;
        final int h = targetTileRectangle.height;

        Tile sourceRaster1 = null;
        ProductData srcData1 = null;
        ProductData srcData2 = null;
        Band sourceBand1 = null;

        final String[] srcBandNames = targetBandNameToSourceBandName.get(targetBand.getName());
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

        if (tgtBandUnit == Unit.UnitType.PHASE) {
            targetTile.setRawSamples(sourceRaster1.getRawSamples());
            return;
        }

        final ProductData trgData = targetTile.getDataBuffer();
        final TileIndex srcIndex = new TileIndex(sourceRaster1);
        final TileIndex tgtIndex = new TileIndex(targetTile);

        final int maxY = y0 + h;
        final int maxX = x0 + w;
        final int imageWidth = sourceProduct.getSceneRasterWidth();
        final String bandPol = OperatorUtils.getPolarizationFromBandName(targetBand.getName());

        double sigma, dn, i, q, phaseTerm = 0.0;
        int srcIdx, tgtIdx;

        for (int y = y0; y < maxY; ++y) {
            srcIndex.calculateStride(y);
            tgtIndex.calculateStride(y);

            for (int x = x0; x < maxX; ++x) {
                srcIdx = srcIndex.getIndex(x);
                tgtIdx = tgtIndex.getIndex(x);

                dn = srcData1.getElemDoubleAt(srcIdx);

                if (srcBandUnit == Unit.UnitType.AMPLITUDE) {
                    dn *= dn;
                } else if (srcBandUnit == Unit.UnitType.INTENSITY) {
                    // already intensity
                } else if (srcBandUnit == Unit.UnitType.REAL) {
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
                } else if (srcBandUnit == Unit.UnitType.INTENSITY_DB) {
                    dn = FastMath.pow(10, dn / 10.0);
                } else {
                    throw new OperatorException("SAOCOM Calibration: unhandled unit");
                }

                if (inputSigma0) {
                    sigma = dn;
                } else {
                    // Apply per-pixel calibration LUT
                    final double calValue = getCalibrationValue(x, bandPol, imageWidth);
                    sigma = dn / (calValue * calValue);
                }

                if (isComplex && outputImageInComplex) {
                    sigma = Math.sqrt(sigma) * phaseTerm;
                }

                if (outputImageScaleInDb) {
                    if (sigma < underFlowFloat) {
                        sigma = -underFlowFloat;
                    } else {
                        sigma = 10.0 * Math.log10(sigma);
                    }
                }

                trgData.setElemDoubleAt(tgtIdx, sigma);
            }
        }
    }

    public double applyCalibration(
            final double v, final double rangeIndex, final double azimuthIndex, final double slantRange,
            final double satelliteHeight, final double sceneToEarthCentre, final double localIncidenceAngle,
            final String bandName, final String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {

        double sigma = 0.0;
        if (bandUnit == Unit.UnitType.AMPLITUDE) {
            sigma = v * v;
        } else if (bandUnit == Unit.UnitType.INTENSITY || bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY) {
            sigma = v;
        } else if (bandUnit == Unit.UnitType.INTENSITY_DB) {
            sigma = FastMath.pow(10, v / 10.0);
        } else {
            throw new OperatorException("Unknown band unit");
        }

        if (inputSigma0) {
            if (incidenceAngleSelection.contains(USE_INCIDENCE_ANGLE_FROM_DEM)) {
                final double ellipsoidAngle = incidenceAngle != null ?
                        incidenceAngle.getPixelDouble(rangeIndex, azimuthIndex) : localIncidenceAngle;
                return sigma * FastMath.sin(localIncidenceAngle * Constants.DTOR) /
                        FastMath.sin(ellipsoidAngle * Constants.DTOR);
            }
            return sigma;
        } else {
            final int imageWidth = sourceProduct.getSceneRasterWidth();
            final double calValue = getCalibrationValue((int) rangeIndex, bandPolar, imageWidth);
            double calibrated = sigma / (calValue * calValue);

            if (incidenceAngleSelection.contains(USE_INCIDENCE_ANGLE_FROM_DEM)) {
                calibrated *= FastMath.sin(localIncidenceAngle * Constants.DTOR);
            }
            return calibrated;
        }
    }

    public double applyRetroCalibration(int x, int y, double v, String bandPolar, final Unit.UnitType bandUnit, int[] subSwathIndex) {
        if (incidenceAngleSelection.contains(USE_INCIDENCE_ANGLE_FROM_DEM)) {
            return v / FastMath.sin(incidenceAngle.getPixelDouble(x, y) * Constants.DTOR);
        } else {
            return v;
        }
    }

    public void removeFactorsForCurrentTile(Band targetBand, Tile targetTile, String srcBandName) throws OperatorException {
        Band sourceBand = sourceProduct.getBand(targetBand.getName());
        Tile sourceTile = calibrationOp.getSourceTile(sourceBand, targetTile.getRectangle());
        targetTile.setRawSamples(sourceTile.getRawSamples());
    }
}
