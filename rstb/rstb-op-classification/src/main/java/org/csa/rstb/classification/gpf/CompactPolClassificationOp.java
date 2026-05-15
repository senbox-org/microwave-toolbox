/*
 * Copyright (C) 2018 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package org.csa.rstb.classification.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.polsar.PolBandUtils;
import org.csa.rstb.classification.gpf.classifiers.CP_HAlphaWishartC2;
import org.csa.rstb.classification.gpf.classifiers.PolClassifier;
import org.csa.rstb.classification.gpf.classifiers.PolClassifierBase;
import org.csa.rstb.polarimetric.gpf.support.CompactPolProcessor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

/**
 * Unsupervised H/&alpha; Wishart classification on a compact-pol C2
 * covariance product. Delegates to {@link CP_HAlphaWishartC2}.
 *
 * Reference: Cloude, S. R.; Goodenough, D. G.; Chen, H. (2012).
 * <i>Compact Decomposition Theory</i>. IEEE Geoscience and Remote Sensing
 * Letters 9(1), 28-32.
 */
@OperatorMetadata(alias = "CP-Classification",
        category = "Radar/Polarimetric/Compact Polarimetry",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2018 SkyWatch Space Applications Inc.",
        description = "Unsupervised H/Alpha Wishart classification on compact-pol products.")
public final class CompactPolClassificationOp extends PolarimetricClassificationOp {

    private static final String PRODUCT_SUFFIX = "_Class";

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size X")
    private String windowSizeXStr = "5";

    @Parameter(valueSet = {"3", "5", "7", "9", "11", "13", "15", "17", "19"}, defaultValue = "5", label = "Window Size Y")
    private String windowSizeYStr = "5";

    private int windowSizeX = 0;
    private int windowSizeY = 0;
    private String compactMode = null;

    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfSLC();

            sourceImageWidth = sourceProduct.getSceneRasterWidth();
            sourceImageHeight = sourceProduct.getSceneRasterHeight();
            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);
            if (sourceProductType != PolBandUtils.MATRIX.LCHCP &&
                    sourceProductType != PolBandUtils.MATRIX.RCHCP &&
                    sourceProductType != PolBandUtils.MATRIX.C2) {
                throw new OperatorException("Compact pol source product or C2 covariance matrix product is expected.");
            }

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            getCompactPolMode();

            windowSizeX = Integer.parseInt(windowSizeXStr);
            windowSizeY = Integer.parseInt(windowSizeYStr);

            classification = UNSUPERVISED_HALPHA_WISHART_DUAL_POL_CLASSIFICATION;
            classifier = createClassifier(classification);

            createTargetProduct();

            if (targetProduct.getNumBands() > 1 && !classifier.canProcessStacks()) {
                throw new OperatorException("Stack processing is not supported with this classifier.");
            }

            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void getCompactPolMode() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        compactMode = absRoot.getAttributeString(AbstractMetadata.compact_mode, CompactPolProcessor.rch);
        if (!compactMode.equals(CompactPolProcessor.rch) && !compactMode.equals(CompactPolProcessor.lch)) {
            throw new OperatorException("Right/Left Circular Hybrid Mode is expected.");
        }
    }

    private PolClassifier createClassifier(final String classification) {
        if (classification.equals(UNSUPERVISED_HALPHA_WISHART_DUAL_POL_CLASSIFICATION)) {
            return new CP_HAlphaWishartC2(sourceProductType, sourceImageWidth, sourceImageHeight, compactMode,
                    windowSizeX, windowSizeY, bandMap, maxIterations, this);
        }
        throw new OperatorException(classification + " is an invalid classification name.");
    }

    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(),
                sourceImageWidth, sourceImageHeight);

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final String targetBandName = classifier.getTargetBandName();
        final IndexCoding indexCoding = classifier.createIndexCoding();
        targetProduct.getIndexCodingGroup().add(indexCoding);

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band targetBand = new Band(targetBandName + bandList.suffix,
                    ProductData.TYPE_UINT8,
                    targetProduct.getSceneRasterWidth(),
                    targetProduct.getSceneRasterHeight());

            targetBand.setUnit("zone_index");
            targetBand.setNoDataValue(PolClassifierBase.NODATACLASS);
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);

            bandMap.put(targetBand, bandList);
            targetBand.setSampleCoding(indexCoding);
        }
    }

    private void updateTargetProductMetadata() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
        absRoot.setAttributeString(AbstractMetadata.compact_mode, compactMode);
    }

    public void checkIfCancelled() {
        checkForCancellation();
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            classifier.computeTile(targetBand, targetTile);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(CompactPolClassificationOp.class);
        }
    }
}
