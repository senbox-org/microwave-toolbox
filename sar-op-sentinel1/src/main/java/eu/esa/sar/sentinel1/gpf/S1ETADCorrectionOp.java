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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.cloud.opendata.DataSpaces;
import eu.esa.sar.commons.ETADUtils;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.Corrector;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.GRDCorrector;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.SMCorrector;
import eu.esa.sar.sentinel1.gpf.etadcorrectors.TOPSCorrector;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * The operator performs ETAD correction for S-1 TOPS SLC / Stripmap SLC / GRD products.
 */
@OperatorMetadata(alias = "S1-ETAD-Correction",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2023 by SkyWatch Space Applications Inc.",
        version = "1.0",
        description = "ETAD correction of S-1 TOPS/SM/GRD products")
public class S1ETADCorrectionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Band")
    private String[] sourceBandNames;

    @Parameter(label = "ETAD product")
    private File etadFile = null;

    @Parameter(defaultValue = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME,
            description = "Method for resampling image from the un-corrected grid to the etad-corrected grid.",
            label = "Resampling Type")
    private String resamplingType = ResamplingFactory.BISINC_5_POINT_INTERPOLATION_NAME;

    @Parameter(description = "Tropospheric Correction (Range)", defaultValue = "false",
            label = "Tropospheric Correction (Range)")
    private boolean troposphericCorrectionRg = false;

    @Parameter(description = "Ionospheric Correction (Range)", defaultValue = "false",
            label = "Ionospheric Correction (Range)")
    private boolean ionosphericCorrectionRg = false;

    @Parameter(description = "Geodetic Correction (Range)", defaultValue = "false",
            label = "Geodetic Correction (Range)")
    private boolean geodeticCorrectionRg = false;

    @Parameter(description = "Doppler Shift Correction (Range)", defaultValue = "false",
            label = "Doppler Shift Correction (Range)")
    private boolean dopplerShiftCorrectionRg = false;

    @Parameter(description = "Geodetic Correction (Azimuth)", defaultValue = "false",
            label = "Geodetic Correction (Azimuth)")
    private boolean geodeticCorrectionAz = false;

    @Parameter(description = "Bistatic Shift Correction (Azimuth)", defaultValue = "false",
            label = "Bistatic Shift Correction (Azimuth)")
    private boolean bistaticShiftCorrectionAz = false;

    @Parameter(description = "FM Mismatch Correction (Azimuth)", defaultValue = "false",
            label = "FM Mismatch Correction (Azimuth)")
    private boolean fmMismatchCorrectionAz = false;

    @Parameter(description = "Sum Of Azimuth Corrections", defaultValue = "true",
            label = "Sum Of Azimuth Corrections")
    private boolean sumOfAzimuthCorrections = true;

    @Parameter(description = "Sum Of Range Corrections", defaultValue = "true",
            label = "Sum Of Range Corrections")
    private boolean sumOfRangeCorrections = true;

    private Corrector etadCorrector;
    private MetadataElement absRoot = null;

    private Resampling selectedResampling = null;
    private ETADUtils etadUtils = null;

    protected static final String PRODUCT_SUFFIX = "_etad";


    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public S1ETADCorrectionOp() {
    }

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
            validator.checkIfSentinel1Product();

            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            selectedResampling = ResamplingFactory.createResampling(resamplingType);
            if(selectedResampling == null) {
                throw new OperatorException("Resampling method "+ resamplingType + " is invalid");
            }

            if (etadFile != null) {
                etadUtils = createETADUtils();
            }

            createTargetProduct();

            etadCorrector = createETADCorrector();
			etadCorrector.initialize();
            etadCorrector.setTroposphericCorrectionRg(troposphericCorrectionRg);
            etadCorrector.setIonosphericCorrectionRg(ionosphericCorrectionRg);
            etadCorrector.setGeodeticCorrectionRg(geodeticCorrectionRg);
            etadCorrector.setDopplerShiftCorrectionRg(dopplerShiftCorrectionRg);
            etadCorrector.setGeodeticCorrectionAz(geodeticCorrectionAz);
            etadCorrector.setBistaticShiftCorrectionAz(bistaticShiftCorrectionAz);
            etadCorrector.setFmMismatchCorrectionAz(fmMismatchCorrectionAz);
            etadCorrector.setSumOfAzimuthCorrections(sumOfAzimuthCorrections);
            etadCorrector.setSumOfRangeCorrections(sumOfRangeCorrections);

            updateTargetProductMetadata();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private boolean noCorrectionLayerSelected() {
        return !troposphericCorrectionRg && !ionosphericCorrectionRg && !geodeticCorrectionRg &&
                !dopplerShiftCorrectionRg && !geodeticCorrectionAz && !bistaticShiftCorrectionAz &&
                !fmMismatchCorrectionAz && !sumOfAzimuthCorrections && !sumOfRangeCorrections;
    }

    private synchronized ETADUtils createETADUtils() throws Exception {
        if(etadUtils != null) {
            return etadUtils;
        }
        if(etadFile == null) {
            ETADSearch etadSearch = new ETADSearch();
            DataSpaces.Result[] results = etadSearch.search(sourceProduct);

            if(results.length == 0) {
                throw new OperatorException("ETAD product not found");
            }

            File outputFolder = new File(SystemUtils.getCacheDir(), "etad");
            etadFile = etadSearch.download(results[0], outputFolder);
        }

        Product etadProduct = getETADProduct(etadFile);

        validateETADProduct(sourceProduct, etadProduct);

        etadUtils = new ETADUtils(etadProduct);
        return etadUtils;
    }

	private Corrector createETADCorrector() {
		
		final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
		final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);
		
		if (acquisitionMode.equals("IW") && productType.equals("SLC")) { // TOPS SLC
			return new TOPSCorrector(sourceProduct, targetProduct, etadUtils, selectedResampling);
		} else if (acquisitionMode.equals("IW") && productType.equals("GRD")) { // GRD
            return new GRDCorrector(sourceProduct, targetProduct, etadUtils, selectedResampling);
        } else if (acquisitionMode.equals("SM") && productType.equals("SLC")) { // SM SLC
			return new SMCorrector(sourceProduct, targetProduct, etadUtils, selectedResampling);
		} else {
            throw new OperatorException("The source product is currently not supported for ETAD correction");
        }
	}

    private Product getETADProduct(final File etadFile) {

        try {
            return ProductIO.readProduct(etadFile);
        } catch(Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
        return null;

    }

    private void validateETADProduct(final Product sourceProduct, final Product etadProduct) {

        try {
            final MetadataElement srcOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(sourceProduct);
            final MetadataElement srcAnnotation = srcOrigProdRoot.getElement("annotation");
            if (srcAnnotation == null) {
                throw new IOException("Annotation Metadata not found for product: " + sourceProduct.getName());
            }
            final MetadataElement srcProdElem = srcAnnotation.getElements()[0].getElement("product");
            final MetadataElement adsHeaderElem = srcProdElem.getElement("adsHeader");
            final double srcStartTime = ETADUtils.getTime(adsHeaderElem, "startTime").getMJD()* Constants.secondsInDay;
            final double srcStopTime = ETADUtils.getTime(adsHeaderElem, "stopTime").getMJD()* Constants.secondsInDay;

            final MetadataElement etadOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(etadProduct);
            final MetadataElement etadAnnotation = etadOrigProdRoot.getElement("annotation");
            if (etadAnnotation == null) {
                throw new IOException("Annotation Metadata not found for ETAD product: " + etadProduct.getName());
            }
            final MetadataElement etadProdElem = etadAnnotation.getElement("etadProduct");
            final MetadataElement etadHeaderElem = etadProdElem.getElement("etadHeader");
            final double etadStartTime = ETADUtils.getTime(etadHeaderElem, "startTime").getMJD()* Constants.secondsInDay;
            final double etadStopTime = ETADUtils.getTime(etadHeaderElem, "stopTime").getMJD()* Constants.secondsInDay;

            if (srcStartTime < etadStartTime || srcStopTime > etadStopTime) {
                //throw new OperatorException("The selected ETAD product does not match the source product");
            }

        } catch(Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Create target product.
     */
    public Product createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX, sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());

        for (Band srcBand : sourceProduct.getBands()) {
            if (srcBand instanceof VirtualBand) {
                continue;
            }

            final Band targetBand = new Band(srcBand.getName(), ProductData.TYPE_FLOAT32,
                    srcBand.getRasterWidth(), srcBand.getRasterHeight());

            targetBand.setNoDataValueUsed(true);
            targetBand.setNoDataValue(srcBand.getNoDataValue());
            targetBand.setUnit(srcBand.getUnit());
            targetBand.setDescription(srcBand.getDescription());
            targetProduct.addBand(targetBand);
			
            if(targetBand.getUnit() != null && targetBand.getUnit().equals(Unit.IMAGINARY)) {
                int idx = targetProduct.getBandIndex(targetBand.getName());
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBandAt(idx-1), targetBand, "");
            }
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        return targetProduct;
    }

    /**
     * Update the metadata in the target product.
     */
    private void updateTargetProductMetadata() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absRoot, "etad_correction_flag", 1);
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
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        if (noCorrectionLayerSelected()) {
            throw new OperatorException("No correction layer is selected");
        }

        try {
            if (etadUtils == null) {
                etadUtils = createETADUtils();
                etadCorrector.setEtadUtils(etadUtils);
            }

            etadCorrector.computeTileStack(targetTileMap, targetRectangle, pm, this);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
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
            super(S1ETADCorrectionOp.class);
        }
    }
}
