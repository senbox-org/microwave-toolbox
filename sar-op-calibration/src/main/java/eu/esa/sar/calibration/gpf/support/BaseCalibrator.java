/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.calibration.gpf.support;

import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Calibration base class.
 */

public class BaseCalibrator {

    protected Operator calibrationOp;
    protected Product sourceProduct;
    protected Product targetProduct;

    protected final List<String> selectedPolList = new ArrayList<>(4);
    protected boolean outputSigmaBand = false;
    protected boolean outputGammaBand = false;
    protected boolean outputBetaBand = false;
    protected boolean outputDNBand = false;

    protected boolean outputImageInComplex = false;
    protected boolean outputImageScaleInDb = false;
    protected boolean isComplex = false;
    protected String incidenceAngleSelection = null;

    protected MetadataElement absRoot = null;
    protected MetadataElement origMetadataRoot = null;

    protected static final double underFlowFloat = 1.0e-30;
    protected static final String PRODUCT_SUFFIX = "_Cal";

    protected final HashMap<String, String[]> targetBandNameToSourceBandName = new HashMap<>(2);

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public BaseCalibrator() {
    }

    public void setUserSelections(final Product sourceProduct,
                                  final String[] selectedPolarisations,
                                  final boolean outputSigmaBand,
                                  final boolean outputGammaBand,
                                  final boolean outputBetaBand,
                                  final boolean outputDNBand) {

        this.absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        this.outputSigmaBand = outputSigmaBand;
        this.outputGammaBand = outputGammaBand;
        this.outputBetaBand = outputBetaBand;
        this.outputDNBand = outputDNBand;

        String[] selectedPols = selectedPolarisations;
        if (selectedPols == null || selectedPols.length == 0) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            selectedPols = Sentinel1Utils.getProductPolarizations(absRoot);
        }

        for (String pol : selectedPols) {
            selectedPolList.add(pol.toUpperCase());
        }

        if (!outputSigmaBand && !outputGammaBand && !outputBetaBand && !outputDNBand) {
            this.outputSigmaBand = true;
        }
    }

    /**
     * Set flag indicating if target image is output in complex.
     */
    public void setOutputImageInComplex(boolean flag) {
        outputImageInComplex = flag;
    }

    /**
     * Set flag indicating if target image is output in dB scale.
     */
    public void setOutputImageIndB(boolean flag) {
        outputImageScaleInDb = flag;
    }

    public void setIncidenceAngleForSigma0(String incidenceAngleForSigma0) {
        incidenceAngleSelection = incidenceAngleForSigma0;
    }

    /**
     * Get calibration flag from abstract metadata.
     */
    public void getCalibrationFlag() {
        if (absRoot.getAttribute(AbstractMetadata.abs_calibration_flag).getData().getElemBoolean()) {
            throw new OperatorException("Absolute radiometric calibration has already been applied to the product");
        }
    }

    /**
     * Get sample type from abstract metadata.
     */
    public void getSampleType() {
        final String sampleType = absRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE);
        if (sampleType.equals("COMPLEX")) {
            isComplex = true;
        }
    }

    /**
     * Create target product.
     */
    public Product createTargetProduct(final Product sourceProduct, final String[] sourceBandNames) {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        addSelectedBands(sourceProduct, sourceBandNames);

        return targetProduct;
    }

    /**
     * Add the user selected bands to the target product.
     */
    protected void addSelectedBands(final Product sourceProduct, final String[] sourceBandNames) {

        if (outputImageInComplex) {
            outputInComplex(sourceProduct, sourceBandNames);
        } else {
            outputInIntensity(sourceProduct, sourceBandNames);
        }
    }

    protected Band[] getSourceBands(
            final Product sourceProduct, String[] sourceBandNames, final boolean includeVirtualBands) {
        return OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);
    }

    protected void outputInComplex(final Product sourceProduct, final String[] sourceBandNames) {

        final Band[] sourceBands = getSourceBands(sourceProduct, sourceBandNames, false);

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
            final Band targetBandI = targetProduct.addBand(srcBandNames[0], ProductData.TYPE_FLOAT32);
            targetBandI.setUnit(unit);
            targetBandI.setNoDataValueUsed(true);
            targetBandI.setNoDataValue(srcBandI.getNoDataValue());

            if(srcBandI.hasGeoCoding()) {
                // copy band geocoding after target band added to target product
                ProductUtils.copyGeoCoding(srcBandI, targetBandI);
            }

            targetBandNameToSourceBandName.put(srcBandNames[1], srcBandNames);
            final Band targetBandQ = targetProduct.addBand(srcBandNames[1], ProductData.TYPE_FLOAT32);
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

    protected void outputInIntensity(final Product sourceProduct, final String[] sourceBandNames) {

        final Band[] sourceBands = getSourceBands(sourceProduct, sourceBandNames, false);

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
            if (!selectedPolList.isEmpty() && !selectedPolList.contains(pol)) {
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

                    if(srcBand.hasGeoCoding()) {
                        // copy band geocoding after target band added to target product
                        ProductUtils.copyGeoCoding(srcBand, targetBand);
                    }
                }
            }
        }
    }

    /**
     * Create target band names for given source band name.
     *
     * @param srcBandName The given source band name.
     * @return The target band name array.
     */
    protected String[] createTargetBandNames(final String srcBandName) {

        List<String> targetBandNames = new ArrayList<>();

        String pol = srcBandName.contains("_") ? srcBandName.substring(srcBandName.indexOf("_")) : "";
        if(pol.isEmpty()) {
            pol = "_" + OperatorUtils.getBandPolarization(srcBandName, absRoot).toUpperCase();
        }

        if (outputSigmaBand) {
            targetBandNames.add("Sigma0" + pol);
        }
        if (outputGammaBand) {
            targetBandNames.add("Gamma0" + pol);
        }
        if (outputBetaBand) {
            targetBandNames.add("Beta0" + pol);
        }
        if (outputDNBand) {
            targetBandNames.add("DN" + pol);
        }
        if (outputImageScaleInDb) {
            targetBandNames.replaceAll(s -> s + "_dB");
        }

        return targetBandNames.toArray(new String[0]);
    }
}
