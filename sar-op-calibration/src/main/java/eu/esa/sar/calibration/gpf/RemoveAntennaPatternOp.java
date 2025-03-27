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
package eu.esa.sar.calibration.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.calibration.gpf.support.CalibrationFactory;
import eu.esa.sar.calibration.gpf.support.Calibrator;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * RemoveAntennaPattern for ASAR & ERS products.
 */
@OperatorMetadata(alias = "RemoveAntennaPattern",
        category = "Radar/Radiometric",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        version = "1.0",
        description = "Remove Antenna Pattern")
public class RemoveAntennaPatternOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames = null;

    private Calibrator calibrator = null;

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkMission(new String[] {"ENVISAT", "ERS"});

            getProductType();

            getMultilookFlag();

            createTargetProduct();

            calibrator = CalibrationFactory.createCalibrator(sourceProduct);

            calibrator.initialize(this, sourceProduct, targetProduct, true, false);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Get product type.
     *
     * @throws OperatorException The exceptions.
     */
    private void getProductType() throws OperatorException {

        String productType = sourceProduct.getProductType();
        if (productType.contains("ASA_IMS_1") && !productType.contains("ASA_APS_1")) {
            throw new OperatorException(productType + " is not a valid ASAR product type for the operator.");
        }
    }

    /**
     * Get multilook flag from the abstracted metadata.
     *
     * @throws Exception The exceptions.
     */
    private void getMultilookFlag() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        if (AbstractMetadata.getAttributeBoolean(absRoot, AbstractMetadata.multilook_flag)) {
            throw new OperatorException("Cannot apply the operator to multilooked product.");
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.ant_elev_corr_flag, 0);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.range_spread_comp_flag, 0);
        absTgt.setAttributeInt("retro-calibration performed flag", 1);
    }

    /**
     * Add the user selected bands to the target product.
     */
    private void addSelectedBands() {

        if (sourceBandNames == null || sourceBandNames.length == 0) {
            final Band[] bands = sourceProduct.getBands();
            final List<String> bandNameList = new ArrayList<>(sourceProduct.getNumBands());
            for (Band band : bands) {
                final String unit = band.getUnit();
                if (unit != null && (unit.contains(Unit.PHASE) || unit.contains(Unit.REAL) || unit.contains(Unit.IMAGINARY))) {
                    continue;
                }
                bandNameList.add(band.getName());
            }
            sourceBandNames = bandNameList.toArray(new String[0]);
        }

        final Band[] sourceBands = new Band[sourceBandNames.length];
        for (int i = 0; i < sourceBandNames.length; i++) {
            final String sourceBandName = sourceBandNames[i];
            final Band sourceBand = sourceProduct.getBand(sourceBandName);
            if (sourceBand == null) {
                throw new OperatorException("Source band not found: " + sourceBandName);
            }
            sourceBands[i] = sourceBand;
        }

        for (Band srcBand : sourceBands) {

            final String unit = srcBand.getUnit();
            String srcBandName = srcBand.getName();
            if (unit == null) {
                throw new OperatorException("band " + srcBand.getName() + " requires a unit");
            }

            if (unit.contains(Unit.PHASE) || unit.contains(Unit.REAL) || unit.contains(Unit.IMAGINARY)) {
                throw new OperatorException("Please select amplitude or intensity band.");
            }

            if (targetProduct.getBand(srcBandName) == null) {
                final Band targetBand = new Band(srcBandName,
                        ProductData.TYPE_FLOAT32,
                        sourceProduct.getSceneRasterWidth(),
                        sourceProduct.getSceneRasterHeight());

                targetBand.setUnit(unit);
                targetProduct.addBand(targetBand);
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
    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            final String srcBandName = targetBand.getName();
            calibrator.removeFactorsForCurrentTile(targetBand, targetTile, srcBandName);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RemoveAntennaPatternOp.class);
        }
    }
}
