/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.polarimetric.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.csa.rstb.polarimetric.gpf.decompositions.*;
import eu.esa.sar.commons.polsar.PolBandUtils;
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
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.awt.*;
import java.util.Map;

/**
 * Perform Polarimetric decomposition of a given polarimetric product
 */

@OperatorMetadata(alias = "Polarimetric-Decomposition",
        category = "Radar/Polarimetric",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Perform Polarimetric decomposition of a given product")
public final class PolarimetricDecompositionOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {SINCLAIR_DECOMPOSITION, PAULI_DECOMPOSITION, FREEMAN_DURDEN_DECOMPOSITION,
            GENERALIZED_FREEMAN_DURDEN_DECOMPOSITION,
            YAMAGUCHI_DECOMPOSITION, VANZYL_DECOMPOSITION, H_A_ALPHA_DECOMPOSITION, H_ALPHA_DECOMPOSITION,
            CLOUDE_DECOMPOSITION, TOUZI_DECOMPOSITION, HUYNEN_DECOMPOSITION, YANG_DECOMPOSITION,
            KROGAGER_DECOMPOSITION, CAMERON_DECOMPOSITION, MF3CF_DECOMPOSITION, MF4CF_DECOMPOSITION,
            MODEL_BASED_C2_DECOMPOSITION},
            defaultValue = SINCLAIR_DECOMPOSITION, label = "Decomposition")
    private String decomposition = SINCLAIR_DECOMPOSITION;

    @Parameter(description = "The sliding window size", interval = "[1, 100]", defaultValue = "5", label = "Window Size")
    private int windowSize = 5;

    // H-A-Alpha flags
    @Parameter(description = "Output entropy, anisotropy, alpha", defaultValue = "false",
            label = "Entropy (H), Anisotropy (A), Alpha")
    private boolean outputHAAlpha = false;

    @Parameter(description = "Output beta, delta, gamma, lambda", defaultValue = "false",
            label = "Beta, Delta, Gamma, Lambda")
    private boolean outputBetaDeltaGammaLambda = false;

    @Parameter(description = "Output alpha 1, 2, 3", defaultValue = "false", label = "Alpha 1, Alpha 2, Alpha 3")
    private boolean outputAlpha123 = false;

    @Parameter(description = "Output lambda 1, 2, 3", defaultValue = "false", label = "Lambda 1, Lambda 2, Lambda 3")
    private boolean outputLambda123 = false;

    // Touzi flags
    @Parameter(description = "Output psi, tau, alpha, phi", defaultValue = "false", label = "Psi, Tau, Alpha, Phi")
    private boolean outputTouziParamSet0 = false;

    @Parameter(description = "Output psi1, tau1, alpha1, phi1", defaultValue = "false", label = "Psi 1, Tau 1, Alpha 1, Phi 1")
    private boolean outputTouziParamSet1 = false;

    @Parameter(description = "Output psi2, tau2, alpha2, phi2", defaultValue = "false", label = "Psi 2, Tau 2, Alpha 2, Phi 2")
    private boolean outputTouziParamSet2 = false;

    @Parameter(description = "Output psi3, tau3, alpha3, phi3", defaultValue = "false", label = "Psi 3, Tau 3, Alpha 3, Phi 3")
    private boolean outputTouziParamSet3 = false;

    // Huynen flags
    @Parameter(description = "Output 2A0_b, B0_plus_B, B0_minus_B", defaultValue = "true", label = "2A0_b, B0_plus_B, B0_minus_B")
    private boolean outputHuynenParamSet0 = true;

    @Parameter(description = "Output A0, B0, B, C, D, E, F, G, H", defaultValue = "false", label = "A0, B0, B, C, D, E, F, G, H")
    private boolean outputHuynenParamSet1 = false;

    public static final String SINCLAIR_DECOMPOSITION = "Sinclair Decomposition";
    public static final String PAULI_DECOMPOSITION = "Pauli Decomposition";
    public static final String FREEMAN_DURDEN_DECOMPOSITION = "Freeman-Durden Decomposition";
    public static final String GENERALIZED_FREEMAN_DURDEN_DECOMPOSITION = "Generalized Freeman-Durden Decomposition";
    public static final String YAMAGUCHI_DECOMPOSITION = "Yamaguchi Decomposition";
    public static final String VANZYL_DECOMPOSITION = "van Zyl Decomposition";
    public static final String H_A_ALPHA_DECOMPOSITION = "H-A-Alpha Quad Pol Decomposition";
    public static final String H_ALPHA_DECOMPOSITION = "H-Alpha Dual Pol Decomposition";
    public static final String CLOUDE_DECOMPOSITION = "Cloude Decomposition";
    public static final String TOUZI_DECOMPOSITION = "Touzi Decomposition";
    public static final String HUYNEN_DECOMPOSITION = "Huynen Decomposition";
    public static final String YANG_DECOMPOSITION = "Yang Decomposition";
    public static final String KROGAGER_DECOMPOSITION = "Krogager Decomposition";
    public static final String CAMERON_DECOMPOSITION = "Cameron Decomposition";
    public static final String MF3CF_DECOMPOSITION = "Model-free 3-component Decomposition";
    public static final String MF4CF_DECOMPOSITION = "Model-free 4-component Decomposition";
    public static final String MODEL_BASED_C2_DECOMPOSITION = "Model-Based Dual Pol Decomposition";
    public static final String VH_VV = "VH_VV";
    public static final String HH_HV = "HH_HV";

    private PolBandUtils.PolSourceBand[] srcBandList;
    private PolBandUtils.MATRIX sourceProductType = null;
    private Decomposition polDecomp;

    /**
     * Set decomposition. This function is used by unit test only.
     *
     * @param s The decomposition name.
     */
    protected void SetDecomposition(final String s) {

        if (s.equals(SINCLAIR_DECOMPOSITION) || s.equals(PAULI_DECOMPOSITION) ||
                s.equals(FREEMAN_DURDEN_DECOMPOSITION) || s.equals(YAMAGUCHI_DECOMPOSITION) ||
                s.equals(VANZYL_DECOMPOSITION) || s.equals(H_A_ALPHA_DECOMPOSITION) || s.equals(H_ALPHA_DECOMPOSITION) ||
                s.equals(CLOUDE_DECOMPOSITION) || s.equals(TOUZI_DECOMPOSITION) || s.equals(HUYNEN_DECOMPOSITION) ||
                s.equals(YANG_DECOMPOSITION) || s.equals(KROGAGER_DECOMPOSITION) || s.equals(CAMERON_DECOMPOSITION) ||
                s.equals(GENERALIZED_FREEMAN_DURDEN_DECOMPOSITION) || s.equals(MF3CF_DECOMPOSITION) ||
                s.equals(MF4CF_DECOMPOSITION) || s.equals(MODEL_BASED_C2_DECOMPOSITION)) {
            decomposition = s;
        } else {
            throw new OperatorException(s + " is an invalid decomposition name.");
        }
    }

    protected void setTouziParameters(final boolean set0, final boolean set1,
                                      final boolean set2, final boolean set3) {
        outputTouziParamSet0 = set0;
        outputTouziParamSet1 = set1;
        outputTouziParamSet2 = set2;
        outputTouziParamSet3 = set3;
    }

    protected void setHAAlphaParameters(final boolean HAAlpha, final boolean BetaDeltaGammaLambda,
                                        final boolean Alpha123, final boolean Lambda123) {
        outputHAAlpha = HAAlpha;
        outputBetaDeltaGammaLambda = BetaDeltaGammaLambda;
        outputAlpha123 = Alpha123;
        outputLambda123 = Lambda123;
    }

    protected void setHuynenParameters(final boolean set0, final boolean set1) {
        outputHuynenParamSet0 = set0;
        outputHuynenParamSet1 = set1;
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
            validator.checkIfSLC();
            validator.checkIfTOPSARBurstProduct(false);

            sourceProductType = PolBandUtils.getSourceProductType(sourceProduct);

            srcBandList = PolBandUtils.getSourceBands(sourceProduct, sourceProductType);

            polDecomp = createDecomposition();

            checkSourceProductType(sourceProductType);

            createTargetProduct();

            updateTargetProductMetadata();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void checkSourceProductType(final PolBandUtils.MATRIX sourceProductType) {

        if (sourceProductType == PolBandUtils.MATRIX.UNKNOWN) {

            throw new OperatorException("Input should be a polarimetric product");
        }

        if ((polDecomp instanceof HAlphaC2 || polDecomp instanceof ModelBasedC2) &&
                !PolBandUtils.isDualPol(sourceProductType)) {

            throw new OperatorException("Input should be a dual polarimetric product");

        } else if (!(polDecomp instanceof HAlphaC2) && !(polDecomp instanceof ModelBasedC2) &&
                !PolBandUtils.isQuadPol(sourceProductType) && !PolBandUtils.isFullPol(sourceProductType)) {

            throw new OperatorException("Input should be a full polarimetric product");
        }
    }

    private Decomposition createDecomposition() throws OperatorException {
        int sourceImageWidth = sourceProduct.getSceneRasterWidth();
        int sourceImageHeight = sourceProduct.getSceneRasterHeight();

        if (sourceProductType == null) {
            throw new OperatorException("Source product type is unknown");
        }
        if (sourceImageWidth == 0 || sourceImageHeight == 0) {
            throw new OperatorException("Source image dimensions unknown");
        }

        switch (decomposition) {
            case SINCLAIR_DECOMPOSITION:
                return new Sinclair(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case PAULI_DECOMPOSITION:
                return new Pauli(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case FREEMAN_DURDEN_DECOMPOSITION:
                return new FreemanDurden(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case GENERALIZED_FREEMAN_DURDEN_DECOMPOSITION:
                return new GeneralizedFreemanDurden(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case YAMAGUCHI_DECOMPOSITION:
                return new Yamaguchi(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case VANZYL_DECOMPOSITION:
                return new vanZyl(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case MF3CF_DECOMPOSITION:
                return new MF3CF(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case MF4CF_DECOMPOSITION:
                return new MF4CF(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case CLOUDE_DECOMPOSITION:
                return new Cloude(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case H_A_ALPHA_DECOMPOSITION:
                return new hAAlpha(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight,
                        outputHAAlpha,
                        outputBetaDeltaGammaLambda,
                        outputAlpha123,
                        outputLambda123);
            case H_ALPHA_DECOMPOSITION:
                return new HAlphaC2(srcBandList, sourceProductType,
                        windowSize, windowSize, sourceImageWidth, sourceImageHeight);
            case TOUZI_DECOMPOSITION:
                return new Touzi(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight,
                        outputTouziParamSet0,
                        outputTouziParamSet1,
                        outputTouziParamSet2,
                        outputTouziParamSet3);
            case HUYNEN_DECOMPOSITION:
                return new Huynen(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight,
                        outputHuynenParamSet0,
                        outputHuynenParamSet1);
            case YANG_DECOMPOSITION:
                return new Yang(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight,
                        outputHuynenParamSet0,
                        outputHuynenParamSet1);
            case KROGAGER_DECOMPOSITION:
                return new Krogager(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case CAMERON_DECOMPOSITION:
                return new Cameron(srcBandList, sourceProductType,
                        windowSize, sourceImageWidth, sourceImageHeight);
            case MODEL_BASED_C2_DECOMPOSITION:
                final String polarization = getPolarization(sourceProduct);
                return new ModelBasedC2(srcBandList, sourceProductType,
                        windowSize, windowSize, polarization, sourceImageWidth, sourceImageHeight);
        }
        return null;
    }

    private String getPolarization(final Product sourceProduct) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        final String pol1 = absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar);
        final String pol2 = absRoot.getAttributeString(AbstractMetadata.mds2_tx_rx_polar);

        if (pol1 == null || pol2 == null) {
            throw new OperatorException("Dual-pol product with VH-VV or HH-HV polarization is expected");
        }

        if (pol1.toLowerCase().equals("vh") && pol2.toLowerCase().equals("vv") ||
                pol1.toLowerCase().equals("vv") && pol2.toLowerCase().equals("vh")) {
            return VH_VV;
        } else if (pol1.toLowerCase().equals("hh") && pol2.toLowerCase().equals("hv") ||
                pol1.toLowerCase().equals("hv") && pol2.toLowerCase().equals("hh")) {
            return HH_HV;
        } else {
            throw new OperatorException("Dual-pol product with VH-VV or HH-HV polarization is expected");
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName() + polDecomp.getSuffix(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        addSelectedBands();

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    /**
     * Update metadata in the target product.
     */
    private void updateTargetProductMetadata() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);

        absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);

        // Save new slave band names
        PolBandUtils.saveNewBandNames(targetProduct, srcBandList);
    }

    /**
     * Add bands to the target product.
     *
     * @throws OperatorException The exception.
     */
    private void addSelectedBands() throws OperatorException {

        final String[] targetBandNames = polDecomp.getTargetBandNames();

        for (final PolBandUtils.PolSourceBand bandList : srcBandList) {
            final Band[] targetBands = new Band[targetBandNames.length];
            int i = 0;
            for (String targetBandName : targetBandNames) {

                final Band targetBand = new Band(targetBandName + bandList.suffix,
                        ProductData.TYPE_FLOAT32,
                        targetProduct.getSceneRasterWidth(),
                        targetProduct.getSceneRasterHeight());

                polDecomp.setBandUnit(targetBandName + bandList.suffix, targetBand);
                targetBand.setNoDataValueUsed(true);
                targetBand.setNoDataValue(0);

                targetProduct.addBand(targetBand);
                targetBands[i++] = targetBand;
            }
            bandList.addTargetBands(targetBands);
        }
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {
        try {

            polDecomp.computeTile(targetTiles, targetRectangle, this);

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
            super(PolarimetricDecompositionOp.class);
        }
    }
}
