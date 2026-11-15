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
package org.csa.rstb.polarimetric.gpf;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link PolarimetricParametersOp}.
 */
public class TestPolarimetricParametersOp {

    @Test
    public void testSpiCreatesOperator() {
        final PolarimetricParametersOp op =
                (PolarimetricParametersOp) new PolarimetricParametersOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = PolarimetricParametersOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Polarimetric-Parameters", md.alias());
    }

    // ---- Closed-form checks (Phase 8). Test input: a constant reflection-symmetric C3 with
    // |HH|^2 = 4, |HV|^2 = 0.5 (C22 = 2|HV|^2 = 1), |VV|^2 = 1, <HH VV*> = 1.
    // Then T3 = [[3.5, 1.5, 0], [1.5, 1.5, 0], [0, 0, 1]], eigenvalues 2.5 +- sqrt(13)/2 and 1.

    private static final double C11 = 4, C22 = 1, C33 = 1, C13 = 1;
    private static final double HH = 4, HV = 0.5, VV = 1;
    private static final double LAMBDA1 = 2.5 + Math.sqrt(13) / 2;
    private static final double LAMBDA2 = 1.0;
    private static final double LAMBDA3 = 2.5 - Math.sqrt(13) / 2;

    @Test
    public void testSpanIsTraceOfT3() {
        // Span = trace(T3) = trace(C3) = |HH|^2 + 2|HV|^2 + |VV|^2 (Lee & Pottier 2009).
        final double[][] tr = {{3.5, 1.5, 0}, {1.5, 1.5, 0}, {0, 0, 1}};
        final double[][] ti = new double[3][3];
        final PolarimetricParametersOp.PolarimetricParameters p =
                PolarimetricParametersOp.computePolarimetricParameters(tr, ti);
        assertEquals(HH + 2 * HV + VV, p.Span, 1e-9);   // 6.0 (was 12.0)
    }

    @Test
    public void testEigenRviAndPedestal() {
        // Eigen RVI = 4 lambda3 / (lambda1 + lambda2 + lambda3) (Cloude); pedestal = lambda3 / lambda1.
        final double[][] tr = {{3.5, 1.5, 0}, {1.5, 1.5, 0}, {0, 0, 1}};
        final double[][] ti = new double[3][3];
        final PolarimetricParametersOp.PolarimetricParameters p =
                PolarimetricParametersOp.computePolarimetricParameters(tr, ti);
        assertEquals(4 * LAMBDA3 / (LAMBDA1 + LAMBDA2 + LAMBDA3), p.RVI, 1e-6);   // 0.464816 (was 0.0294)
        assertEquals(LAMBDA3 / LAMBDA1, p.PedestalHeight, 1e-6);
    }

    @Test
    public void testEigenRviOfRandomVolumeIsOne() {
        // Uniform random dipole cloud: T3 = diag(4/3, 2/3, 2/3) -> RVI = 1.
        final double[][] tr = {{4.0 / 3, 0, 0}, {0, 2.0 / 3, 0}, {0, 0, 2.0 / 3}};
        final double[][] ti = new double[3][3];
        assertEquals(1.0, PolarimetricParametersOp.computePolarimetricParameters(tr, ti).RVI, 1e-6);
    }

    @Test
    public void testOperatorOutputsOnComplexC3() throws Exception {
        final Product src = SyntheticPolProducts.createConstantC3Product(9, 9, true, C11, C22, C33, C13, 0);
        final Product trg = runOperator(src, "outputSpan", "outputPedestalHeight", "outputRVI");

        assertConstant(trg, "Span", HH + 2 * HV + VV, 1e-4);
        assertConstant(trg, "RVI", 4 * LAMBDA3 / (LAMBDA1 + LAMBDA2 + LAMBDA3), 1e-4);
        assertConstant(trg, "PedestalHeight", LAMBDA3 / LAMBDA1, 1e-4);
    }

    @Test
    public void testIndicesUseLinearPower() throws Exception {
        // Pope et al. 1994 (CSI, VSI, BMI, ITI) and Mitchard et al. 2012 (RFDI) use linear sigma0 power,
        // and the operator documents HH/VV etc. as intensity ratios.
        final Product src = SyntheticPolProducts.createConstantC3Product(9, 9, true, C11, C22, C33, C13, 0);
        final Product trg = runOperator(src, "outputRFDI", "outputCSI", "outputVSI", "outputBMI", "outputITI",
                "outputHHVVRatio", "outputHHHVRatio", "outputVVVHRatio");

        assertConstant(trg, "RFDI", (HH - HV) / (HH + HV), 1e-5);              // 0.7778 (was 0.4776)
        assertConstant(trg, "CSI", VV / (VV + HH), 1e-5);                      // 0.2    (was 0.3333)
        assertConstant(trg, "VSI", (2 * HV) / (HH + VV + 2 * HV), 1e-5);       // 0.1667 (was 0.3204)
        assertConstant(trg, "BMI", (HH + VV) / 2, 1e-5);                       // 2.5    (was 1.5)
        assertConstant(trg, "ITI", HH / VV, 1e-5);                             // 4      (was 2)
        assertConstant(trg, "HHVVRatio", HH / VV, 1e-5);                       // 4      (was 2)
        assertConstant(trg, "HHHVRatio", HH / HV, 1e-5);                       // 8      (was 2.83)
        assertConstant(trg, "VVVHRatio", VV / HV, 1e-5);                       // 2      (was 1.41)
    }

    @Test
    public void testDetectedRviIsKimVanZyl() throws Exception {
        // Kim & van Zyl 2009: RVI = 8 sigmaHV / (sigmaHH + sigmaVV + 2 sigmaHV), linear power.
        final Product src = SyntheticPolProducts.createConstantC3Product(9, 9, false, C11, C22, C33, C13, 0);
        final Product trg = runOperator(src, "outputRVI");
        assertConstant(trg, "RVI", 8 * HV / (HH + VV + 2 * HV), 1e-5);       // 0.6667 (was 1.2815)
    }

    @Test
    public void testIntensityBandModeUsesLinearPower() throws Exception {
        final Product src = SyntheticPolProducts.newSarProduct(9, 9, false);
        SyntheticPolProducts.addConstantBand(src, "Intensity_HH", Unit.INTENSITY, HH);
        SyntheticPolProducts.addConstantBand(src, "Intensity_HV", Unit.INTENSITY, HV);
        SyntheticPolProducts.addConstantBand(src, "Intensity_VH", Unit.INTENSITY, HV);
        SyntheticPolProducts.addConstantBand(src, "Intensity_VV", Unit.INTENSITY, VV);
        final Product trg = runOperator(src, "outputRFDI", "outputHHHVRatio");
        assertConstant(trg, "RFDI", (HH - HV) / (HH + HV), 1e-5);
        assertConstant(trg, "HHHVRatio", HH / HV, 1e-5);
    }

    @Test
    public void testOutputBandUnits() throws Exception {
        final Product src = SyntheticPolProducts.createConstantC3Product(9, 9, true, C11, C22, C33, C13, 0);
        final Product trg = runOperator(src, "outputSpan", "outputRVI", "outputBMI", "outputHHVVRatio",
                "outputCoherence", "outputPhase");
        assertEquals(Unit.INTENSITY, trg.getBand("Span").getUnit());
        assertEquals(Unit.INTENSITY, trg.getBand("BMI").getUnit());
        assertNull(trg.getBand("RVI").getUnit());          // dimensionless (was "intensity")
        assertNull(trg.getBand("HHVVRatio").getUnit());
        assertEquals(Unit.COHERENCE, trg.getBand("Coh_HHVV").getUnit());
        assertEquals(Unit.PHASE, trg.getBand("Phase_HHVV").getUnit());
    }

    @Test
    public void testPhaseVVVHSignQuadPol() throws Exception {
        // Phase_VVVH = arg<S_VV S_VH*>, as on the dual-pol path. C3 c23 = <sqrt2 S_HV S_VV*> = 0.3 i,
        // so arg<S_HV S_VV*> = +pi/2 and arg<S_VV S_VH*> = -pi/2 (was +pi/2).
        final Product src = SyntheticPolProducts.createConstantC3Product(9, 9, true, C11, C22, C33, C13, 0);
        src.getBand("C23_imag").setData(constant(81, 0.3));
        final Product trg = runOperator(src, "outputPhase");
        assertConstant(trg, "Phase_VVVH", -Math.PI / 2, 1e-5);
    }

    @Test
    public void testDualVhVvSlcChannelOrder() throws Exception {
        // Dual-pol k = [S_VV, S_VH]: VV = 2, VH = 0.5 i -> |VV|^2 = 4, |VH|^2 = 0.25,
        // arg<S_VV S_VH*> = -pi/2.
        final Product src = SyntheticPolProducts.newSarProduct(9, 9, true);
        SyntheticPolProducts.addConstantBand(src, "i_VV", Unit.REAL, 2.0);
        SyntheticPolProducts.addConstantBand(src, "q_VV", Unit.IMAGINARY, 0.0);
        SyntheticPolProducts.addConstantBand(src, "i_VH", Unit.REAL, 0.0);
        SyntheticPolProducts.addConstantBand(src, "q_VH", Unit.IMAGINARY, 0.5);
        final Product trg = runOperator(src, "outputVVVHRatio", "outputPhase");
        assertConstant(trg, "VVVHRatio", 16.0, 1e-4);       // was 0.25 (VV and VH swapped, amplitudes)
        assertConstant(trg, "Phase_VVVH", -Math.PI / 2, 1e-5);
    }

    @Test
    public void testDualPolIntensityBands() throws Exception {
        // Dual-pol GRD (VV + VH intensity bands only).
        final Product src = SyntheticPolProducts.newSarProduct(9, 9, false);
        SyntheticPolProducts.addConstantBand(src, "Intensity_VH", Unit.INTENSITY, 0.25);
        SyntheticPolProducts.addConstantBand(src, "Intensity_VV", Unit.INTENSITY, 4.0);
        final Product trg = runOperator(src, "outputVVVHRatio");
        assertConstant(trg, "VVVHRatio", 16.0, 1e-4);
    }

    @Test
    public void testIntensityStackIsPerDate() throws Exception {
        // 2-date dual-pol GRD stack: date 1 VV=4, VH=0.25 (ratio 16); date 2 VV=1, VH=0.5 (ratio 2).
        final Product src = SyntheticPolProducts.newSarProduct(9, 9, false);
        final String[] ref = {"Intensity_VV_ref_01Jan2020", "Intensity_VH_ref_01Jan2020"};
        final String[] sec = {"Intensity_VV_sec_13Jan2020", "Intensity_VH_sec_13Jan2020"};
        SyntheticPolProducts.addConstantBand(src, ref[0], Unit.INTENSITY, 4.0);
        SyntheticPolProducts.addConstantBand(src, ref[1], Unit.INTENSITY, 0.25);
        SyntheticPolProducts.addConstantBand(src, sec[0], Unit.INTENSITY, 1.0);
        SyntheticPolProducts.addConstantBand(src, sec[1], Unit.INTENSITY, 0.5);
        SyntheticPolProducts.makeStack(src, ref, "S1_13Jan2020", sec);

        final Product trg = runOperator(src, "outputVVVHRatio");
        assertConstant(trg, "VVVHRatio_01Jan2020", 16.0, 1e-4);   // was missing (single output of the last date)
        assertConstant(trg, "VVVHRatio_13Jan2020", 2.0, 1e-4);
    }

    @Test
    public void testIntensityQuadStackIsPerDate() throws Exception {
        // 2-date detected quad-pol stack (FULL path): date 1 HH=4, HV=0.5; date 2 HH=1, HV=0.5.
        final Product src = SyntheticPolProducts.newSarProduct(9, 9, false);
        final String[] pols = {"HH", "HV", "VH", "VV"};
        final double[] d1 = {4, 0.5, 0.5, 1}, d2 = {1, 0.5, 0.5, 2};
        final String[] ref = new String[4], sec = new String[4];
        for (int i = 0; i < 4; i++) {
            ref[i] = "Intensity_" + pols[i] + "_ref_01Jan2020";
            sec[i] = "Intensity_" + pols[i] + "_sec_13Jan2020";
            SyntheticPolProducts.addConstantBand(src, ref[i], Unit.INTENSITY, d1[i]);
        }
        for (int i = 0; i < 4; i++) {
            SyntheticPolProducts.addConstantBand(src, sec[i], Unit.INTENSITY, d2[i]);
        }
        SyntheticPolProducts.makeStack(src, ref, "S1_13Jan2020", sec);

        final Product trg = runOperator(src, "outputHHHVRatio");
        assertConstant(trg, "HHHVRatio_01Jan2020", 8.0, 1e-4);    // was 2 (date 2 values)
        assertConstant(trg, "HHHVRatio_13Jan2020", 2.0, 1e-4);
    }

    @Test
    public void testC2UsesPolarisationsFromMetadata() throws Exception {
        // Sentinel-1 VV+VH C2 (k = [VV, VH]): C11 = |VV|^2 = 4, C22 = |VH|^2 = 0.25.
        final Product src = SyntheticPolProducts.newSarProduct(9, 9, true);
        final org.esa.snap.core.datamodel.MetadataElement absRoot =
                org.esa.snap.engine_utilities.datamodel.AbstractMetadata.getAbstractedMetadata(src);
        absRoot.setAttributeString(org.esa.snap.engine_utilities.datamodel.AbstractMetadata.mds1_tx_rx_polar, "VV");
        absRoot.setAttributeString(org.esa.snap.engine_utilities.datamodel.AbstractMetadata.mds2_tx_rx_polar, "VH");
        SyntheticPolProducts.addConstantBand(src, "C11", Unit.INTENSITY, 4.0);
        SyntheticPolProducts.addConstantBand(src, "C12_real", Unit.REAL, 0.0);
        SyntheticPolProducts.addConstantBand(src, "C12_imag", Unit.IMAGINARY, -0.5);
        SyntheticPolProducts.addConstantBand(src, "C22", Unit.INTENSITY, 0.25);

        final Product trg = runOperator(src, "outputVVVHRatio", "outputPhase");   // was refused (C2 = HH/VV)
        assertConstant(trg, "VVVHRatio", 16.0, 1e-4);
        assertConstant(trg, "Phase_VVVH", -Math.PI / 2, 1e-5);
        assertNull(trg.getBand("Phase_HHVV"));
    }

    private static org.esa.snap.core.datamodel.ProductData constant(final int n, final double v) {
        final float[] a = new float[n];
        java.util.Arrays.fill(a, (float) v);
        return org.esa.snap.core.datamodel.ProductData.createInstance(a);
    }

    private static Product runOperator(final Product src, final String... enabledOutputs) {
        final PolarimetricParametersOp op =
                (PolarimetricParametersOp) new PolarimetricParametersOp.Spi().createOperator();
        op.setSourceProduct(src);
        op.setParameter("outputSpan", false);
        for (String name : enabledOutputs) {
            op.setParameter(name, true);
        }
        return op.getTargetProduct();
    }

    private static void assertConstant(final Product trg, final String bandName, final double expected,
                                       final double tol) throws Exception {
        assertNotNull(bandName, trg.getBand(bandName));
        final float[] values = SyntheticPolProducts.readBand(trg, bandName);
        for (float v : values) {
            assertEquals(bandName, expected, v, tol);
        }
    }
}
