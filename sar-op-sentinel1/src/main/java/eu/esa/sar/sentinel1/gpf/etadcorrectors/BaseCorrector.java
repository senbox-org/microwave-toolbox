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

import eu.esa.sar.commons.ETADUtils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.engine_utilities.util.Maths;

import java.awt.*;
import java.util.Map;

/**
 * Base class for ETAD correctors.
 */

 public class BaseCorrector {

    protected Product sourceProduct;
    protected Product targetProduct;
	protected ETADUtils etadUtils;
    protected Resampling selectedResampling;
    protected int sourceImageWidth;
    protected int sourceImageHeight;
    protected boolean troposphericCorrectionRg = false;
    protected boolean ionosphericCorrectionRg = false;
    protected boolean geodeticCorrectionRg = false;
    protected boolean dopplerShiftCorrectionRg = false;
    protected boolean geodeticCorrectionAz = false;
    protected boolean bistaticShiftCorrectionAz = false;
    protected boolean fmMismatchCorrectionAz = false;
    protected boolean sumOfAzimuthCorrections = false;
    protected boolean sumOfRangeCorrections = false;

    protected static final String TROPOSPHERIC_CORRECTION_RG = "troposphericCorrectionRg";
    protected static final String IONOSPHERIC_CORRECTION_RG = "ionosphericCorrectionRg";
    protected static final String GEODETIC_CORRECTION_RG = "geodeticCorrectionRg";
    protected static final String DOPPLER_RANGE_SHIFT_RG = "dopplerRangeShiftRg";
    protected static final String GEODETIC_CORRECTION_AZ = "geodeticCorrectionAz";
    protected static final String BISTATIC_CORRECTION_AZ = "bistaticCorrectionAz";
    protected static final String FM_MISMATCH_CORRECTION_AZ = "fmMismatchCorrectionAz";
    protected static final String SUM_OF_CORRECTIONS_RG = "sumOfCorrectionsRg";
    protected static final String SUM_OF_CORRECTIONS_AZ = "sumOfCorrectionsAz";


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public BaseCorrector(final Product sourceProduct, final Product targetProduct, final ETADUtils etadUtils,
                         final Resampling selectedResampling) {
		this.sourceProduct = sourceProduct;
		this.targetProduct = targetProduct;
		this.etadUtils = etadUtils;
		this.selectedResampling = selectedResampling;
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    public void setEtadUtils(final ETADUtils etadUtils) {
        this.etadUtils = etadUtils;
    }

    public void setTroposphericCorrectionRg(final boolean flag) {
        troposphericCorrectionRg = flag;
    }

    public void setIonosphericCorrectionRg(final boolean flag) {
        ionosphericCorrectionRg = flag;
    }

    public void setGeodeticCorrectionRg(final boolean flag) {
        geodeticCorrectionRg = flag;
    }

    public void setDopplerShiftCorrectionRg(final boolean flag) {
        dopplerShiftCorrectionRg = flag;
    }

    public void setGeodeticCorrectionAz(final boolean flag) {
        geodeticCorrectionAz = flag;
    }

    public void setBistaticShiftCorrectionAz(final boolean flag) {
        bistaticShiftCorrectionAz = flag;
    }

    public void setFmMismatchCorrectionAz(final boolean flag) {
        fmMismatchCorrectionAz = flag;
    }

    public void setSumOfAzimuthCorrections(final boolean flag) {
        sumOfAzimuthCorrections = flag;
    }

    public void setSumOfRangeCorrections(final boolean flag) {
        sumOfRangeCorrections = flag;
    }

    protected void getAzimuthTimeCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                          final double[][] azCorrection) throws Exception {
        getAzimuthTimeCorrectionForCurrentTile(x0, y0, w, h, -1, azCorrection);
    }

    protected void getAzimuthTimeCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                          final int burstIndex, final double[][] azCorrection)
            throws Exception {

        if (geodeticCorrectionAz) {
            getCorrectionForCurrentTile(GEODETIC_CORRECTION_AZ, x0, y0, w, h, burstIndex, azCorrection);
        }

        if (bistaticShiftCorrectionAz) {
            getCorrectionForCurrentTile(BISTATIC_CORRECTION_AZ, x0, y0, w, h, burstIndex, azCorrection);
        }

        if (fmMismatchCorrectionAz) {
            getCorrectionForCurrentTile(FM_MISMATCH_CORRECTION_AZ, x0, y0, w, h, burstIndex, azCorrection);
        }

        if (sumOfAzimuthCorrections) {
            getCorrectionForCurrentTile(SUM_OF_CORRECTIONS_AZ, x0, y0, w, h, burstIndex, azCorrection);
        }
    }

    protected void getRangeTimeCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                        final double[][] rgCorrection) throws Exception {
        getRangeTimeCorrectionForCurrentTile(x0, y0, w, h,-1, rgCorrection);
    }

    protected void getRangeTimeCorrectionForCurrentTile(final int x0, final int y0, final int w, final int h,
                                                        final int burstIndex, final double[][] rgCorrection)
            throws Exception {

        if (troposphericCorrectionRg) {
            getCorrectionForCurrentTile(TROPOSPHERIC_CORRECTION_RG, x0, y0, w, h, burstIndex, rgCorrection);
        }

        if (ionosphericCorrectionRg) {
            getCorrectionForCurrentTile(IONOSPHERIC_CORRECTION_RG, x0, y0, w, h, burstIndex, rgCorrection);
        }

        if (geodeticCorrectionRg) {
            getCorrectionForCurrentTile(GEODETIC_CORRECTION_RG, x0, y0, w, h, burstIndex, rgCorrection);
        }

        if (dopplerShiftCorrectionRg) {
            getCorrectionForCurrentTile(DOPPLER_RANGE_SHIFT_RG, x0, y0, w, h, burstIndex, rgCorrection);
        }

        if (sumOfRangeCorrections) {
            getCorrectionForCurrentTile(SUM_OF_CORRECTIONS_RG, x0, y0, w, h, burstIndex, rgCorrection);
        }
    }

    protected void getCorrectionForCurrentTile(
            final String layer, final int x0, final int y0, final int w, final int h, final int burstIndex,
            final double[][] correction) throws Exception {

        getCorrectionForCurrentTile(layer, x0, y0, w, h, burstIndex, correction, 1.0);
    }

    protected void getCorrectionForCurrentTile(final String layer, final int x0, final int y0, final int w, final int h,
                                               final int burstIndex, final double[][] correction, final double scale)
            throws Exception {
    }

    protected double getInstrumentAzimuthTimeCalibration(final String swathID) {

        if(geodeticCorrectionAz || bistaticShiftCorrectionAz || fmMismatchCorrectionAz) {
            return etadUtils.getAzimuthCalibration(swathID);
        } else {
            return 0.0;
        }
    }

    protected double getInstrumentRangeTimeCalibration(final String swathID) {

        if(troposphericCorrectionRg || ionosphericCorrectionRg || geodeticCorrectionRg || dopplerShiftCorrectionRg) {
            return etadUtils.getRangeCalibration(swathID);
        } else {
            return 0.0;
        }
    }

    protected Rectangle getSourceRectangle(final int tx0, final int ty0, final int tw, final int th, final int margin) {

        final int x0 = Math.max(0, tx0 - margin);
        final int y0 = Math.max(0, ty0 - margin);
        final int xMax = Math.min(tx0 + tw - 1 + margin, sourceImageWidth - 1);
        final int yMax = Math.min(ty0 + th - 1 + margin, sourceImageHeight - 1);
        final int w = xMax - x0 + 1;
        final int h = yMax - y0 + 1;
        return new Rectangle(x0, y0, w, h);
    }

    protected double getCorrection(final String layer, final double azimuthTime, final double slantRangeTime,
                                   final ETADUtils.Burst burst, final Map<String, double[][]> layerCorrectionMap) {

        if (burst == null) {
            return 0.0;
        }
        final String bandName = etadUtils.createBandName(burst.swathID, burst.bIndex, layer);
        double[][] layerCorrection = layerCorrectionMap.get(bandName);
        if (layerCorrection == null) {
            layerCorrection = etadUtils.getLayerCorrectionForCurrentBurst(burst, bandName);
            layerCorrectionMap.put(bandName, layerCorrection);
        }
        final double i = (azimuthTime - burst.azimuthTimeMin) / burst.gridSamplingAzimuth;
        final double j = (slantRangeTime - burst.rangeTimeMin) / burst.gridSamplingRange;
        final int i0 = (int)i;
        final int i1 = i0 + 1;
        final int j0 = (int)j;
        final int j1 = j0 + 1;
        final double c00 = layerCorrection[i0][j0];
        final double c01 = layerCorrection[i0][j1];
        final double c10 = layerCorrection[i1][j0];
        final double c11 = layerCorrection[i1][j1];
        return Maths.interpolationBiLinear(c00, c01, c10, c11, j - j0, i - i0);
    }
}
