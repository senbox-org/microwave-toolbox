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
package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GcpDescriptor;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Secondaries whose complex polynomial model (CPM) cannot be used: too few GCPs for it, or an estimation that
 * fails. Only that Secondary falls back, to a plain polynomial fit whose degree is lowered until the GCPs
 * over-determine it; with fewer than 4 GCPs there is no warp at all and the band must be no-data. The method and
 * degree used are recorded per band in the metadata.
 */
public class TestWarpOpTooFewGCPs {

    static {
        TestUtils.initTestEnvironment();
    }

    private static final int W = 64, H = 64;
    private static final double NO_DATA = -9999.0;
    private static final double DX = 1.5, DY = -0.5;
    private static final String REF_I = "i_VV_ref_01Jan2020", REF_Q = "q_VV_ref_01Jan2020";
    private static final String SEC_I = "i_VV_sec1_13Jan2020", SEC_Q = "q_VV_sec1_13Jan2020";

    @After
    public void clearGcps() {
        GCPManager.instance().removeAllGcpGroups();
    }

    private static Band addBand(final Product p, final String name, final String unit, final double shiftX,
                                final double shiftY) {
        final float[] data = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                data[y * W + x] = (float) (100 + (x - shiftX) + 2 * (y - shiftY));
            }
        }
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        b.setUnit(unit);
        b.setNoDataValue(NO_DATA);
        b.setNoDataValueUsed(true);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
        return b;
    }

    private static final String SEC2_I = "i_VV_sec2_25Jan2020", SEC2_Q = "q_VV_sec2_25Jan2020";
    private static final double DX2 = -1.0, DY2 = 2.0;
    private static final double[][] POS = {
            {10, 10}, {50, 12}, {30, 30}, {12, 52}, {52, 50}, {31, 8},
            {20, 40}, {44, 22}, {8, 30}, {56, 34}, {26, 56}, {40, 46}};

    private static void addGcps(final Band ref, final Band sec, final int numGcps, final double dx, final double dy,
                                final int outlier) {
        final ProductNodeGroup<Placemark> refGcps = GCPManager.instance().getGcpGroup(ref);
        final ProductNodeGroup<Placemark> secGcps = GCPManager.instance().getGcpGroup(sec);
        for (int k = 0; k < numGcps; k++) {
            final String name = "gcp_" + k;
            if (refGcps.get(name) == null) {
                refGcps.add(Placemark.createPointPlacemark(GcpDescriptor.getInstance(), name, name, "",
                        new PixelPos(POS[k][0], POS[k][1]), null, null));
            }
            final double err = k == outlier ? 5.0 : 0.0;     // a mismatched GCP
            secGcps.add(Placemark.createPointPlacemark(GcpDescriptor.getInstance(), name, name, "",
                    new PixelPos(POS[k][0] + dx + err, POS[k][1] + dy - err), null, null));
        }
    }

    private static Product stack(final int numGcps) {
        return stack(numGcps, 0, -1);
    }

    /** One Secondary with numGcps GCPs, plus a second one with numGcps2 GCPs (none if 0). */
    private static Product stack(final int numGcps, final int numGcps2, final int outlier2) {
        final Product p = new Product("stack", "SLC", W, H);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString("processed_slave", SEC_I);
        final Band refI = addBand(p, REF_I, Unit.REAL, 0, 0);
        addBand(p, REF_Q, Unit.IMAGINARY, 0, 0);
        final Band secI = addBand(p, SEC_I, Unit.REAL, DX, DY);
        addBand(p, SEC_Q, Unit.IMAGINARY, DX, DY);
        addGcps(refI, secI, numGcps, DX, DY, -1);
        if (numGcps2 > 0) {
            final Band sec2I = addBand(p, SEC2_I, Unit.REAL, DX2, DY2);
            addBand(p, SEC2_Q, Unit.IMAGINARY, DX2, DY2);
            addGcps(refI, sec2I, numGcps2, DX2, DY2, outlier2);
        }
        return p;
    }

    private static Product warp(final Product source) {
        return warp(source, 0.05f);
    }

    private static Product warp(final Product source, final float rmsThreshold) {
        final WarpOp op = (WarpOp) new WarpOp.Spi().createOperator();
        op.setSourceProduct(source);
        op.setParameter("rmsThreshold", rmsThreshold);
        return op.getTargetProduct();
    }

    private static MetadataElement warpData(final Product target, final String band) {
        final MetadataElement bandElem = AbstractMetadata.getBandAbsMetadata(
                AbstractMetadata.getAbstractedMetadata(target), band, false);
        return bandElem.getElement("WarpData");
    }

    private static int warpValid(final Product target, final String band) {
        return warpData(target, band).getAttributeInt("warpValid");
    }

    private static String warpMethod(final Product target, final String band) {
        final MetadataElement wd = warpData(target, band);
        return wd.getAttributeString("warpMethod") + ' ' + wd.getAttributeInt("warpDegree");
    }

    /** The warped band must reproduce the reference plane at the centre (the test images are linear ramps). */
    private static void assertWarpedOntoReference(final Product target, final String band) throws Exception {
        final float[] out = new float[W * H];
        target.getBand(band).readPixels(0, 0, W, H, out);
        final int k = 32 * W + 32;
        assertEquals(band + " warped back onto the reference grid", 100 + 32 + 2 * 32, out[k], 0.05);
    }

    /**
     * 6 GCPs at degree 2: no CPM redundancy, and a degree-2 fit through 6 GCPs would be exact (zero residuals
     * whatever the warp), so this band uses a degree-1 fit, which the 6 GCPs over-determine.
     */
    @Test
    public void testExactlyDeterminedSecondaryFallsBackToLowerDegree() throws Exception {
        final Product target = warp(stack(6));
        assertWarpedOntoReference(target, SEC_I);
        assertWarpedOntoReference(target, SEC_Q);
        assertEquals(1, warpValid(target, SEC_I));
        assertEquals("Polynomial 1", warpMethod(target, SEC_I));
    }

    /** 4 GCPs (fewer than degree 2 needs): a degree-1 fit instead of no warp at all. */
    @Test
    public void testFourGcpsGiveDegreeOneWarp() throws Exception {
        final Product target = warp(stack(4));
        assertWarpedOntoReference(target, SEC_I);
        assertEquals("Polynomial 1", warpMethod(target, SEC_I));
    }

    /** A Secondary short of GCPs falls back alone: the other Secondary keeps its CPM. */
    @Test
    public void testFallbackIsPerSecondary() throws Exception {
        final Product target = warp(stack(12, 6, -1));
        for (String band : new String[]{SEC_I, SEC_Q, SEC2_I, SEC2_Q}) {
            assertWarpedOntoReference(target, band);
        }
        // the warp metadata is written with the first computed tile
        assertEquals("CPM 2", warpMethod(target, SEC_I));
        assertEquals("Polynomial 1", warpMethod(target, SEC2_I));
    }

    /**
     * A CPM that fails during estimation (here the overall model test, with one redundant GCP that is a gross
     * outlier) also falls back for that Secondary only; it used to re-fit every Secondary with the RMS rule.
     */
    @Test
    public void testFailedEstimationFallsBackPerSecondary() throws Exception {
        final Product target = warp(stack(12, 7, 6));
        assertWarpedOntoReference(target, SEC_I);
        assertWarpedOntoReference(target, SEC2_I);
        assertEquals("CPM 2", warpMethod(target, SEC_I));
        assertEquals("Polynomial 1", warpMethod(target, SEC2_I));
    }

    /** 2 GCPs: no warp is possible; the band must be no-data, not a valid-looking zero image. */
    @Test
    public void testSecondaryWithoutWarpIsNoData() throws Exception {
        final Product target = warp(stack(2));
        final float[] out = new float[W * H];
        target.getBand(SEC_I).readPixels(0, 0, W, H, out);
        for (float v : out) {
            assertEquals(NO_DATA, v, 0.0);
        }
        assertTrue(target.getBand(SEC_I).isNoDataValueUsed());
        assertEquals(0, warpValid(target, SEC_I));
        assertEquals("None 0", warpMethod(target, SEC_I));
    }

    /**
     * A complex stack whose single Secondary has numGcps GCPs with the given noise (pixels, per axis) and, at k = 3,
     * a gross outlier of 7 pixels.
     */
    private static Product noisyStack(final int numGcps, final double noise, final long seed) {
        final Product p = new Product("stack", "SLC", W, H);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString("processed_slave", SEC_I);
        final Band refI = addBand(p, REF_I, Unit.REAL, 0, 0);
        addBand(p, REF_Q, Unit.IMAGINARY, 0, 0);
        final Band secI = addBand(p, SEC_I, Unit.REAL, DX, DY);
        addBand(p, SEC_Q, Unit.IMAGINARY, DX, DY);
        final ProductNodeGroup<Placemark> refGcps = GCPManager.instance().getGcpGroup(refI);
        final ProductNodeGroup<Placemark> secGcps = GCPManager.instance().getGcpGroup(secI);
        final java.util.Random random = new java.util.Random(seed);
        for (int k = 0; k < numGcps; k++) {
            final String name = "gcp_" + k;
            refGcps.add(Placemark.createPointPlacemark(GcpDescriptor.getInstance(), name, name, "",
                    new PixelPos(POS[k][0], POS[k][1]), null, null));
            final double err = k == 3 ? 7.0 : 0.0;
            secGcps.add(Placemark.createPointPlacemark(GcpDescriptor.getInstance(), name, name, "",
                    new PixelPos(POS[k][0] + DX + err + noise * random.nextGaussian(),
                            POS[k][1] + DY + noise * random.nextGaussian()), null, null));
        }
        return p;
    }

    /**
     * The fallback fit of a noisy Secondary must be valid, keep more GCPs than coefficients and drop the outlier;
     * the warped band then matches the reference within the GCP noise. Returns its degree.
     */
    private static int assertValidFallback(final Product target, final double noise, final String what)
            throws Exception {
        final float[] out = new float[W * H];
        target.getBand(SEC_I).readPixels(0, 0, W, H, out);
        final MetadataElement wd = warpData(target, SEC_I);
        final int degree = wd.getAttributeInt("warpDegree");
        assertEquals(what + ": " + wd.getAttributeString("warpMethod"), 1, wd.getAttributeInt("warpValid"));
        assertEquals(what, WarpOp.WARP_METHOD_POLYNOMIAL, wd.getAttributeString("warpMethod"));
        assertTrue(what + ": " + wd.getNumElements() + " GCPs left for degree " + degree,
                wd.getNumElements() > WarpOp.numCoefficients(degree));
        for (MetadataElement gcp : wd.getElements()) {
            assertTrue(what + ": the outlier is kept", gcp.getAttributeDouble("ref_x") != POS[3][0] ||
                    gcp.getAttributeDouble("ref_y") != POS[3][1]);
        }
        // the image is 100 + x + 2y: a few times the noise of a fit through 5 or more GCPs
        assertEquals(what + ": warped onto the reference", 100 + 32 + 2 * 32, out[32 * W + 32],
                Math.max(1.0, 6 * noise));
        return degree;
    }

    /**
     * GCPs with 0.1 px noise and one gross outlier, so the CPM fails. The RMS rule removes GCPs as it iterates; it
     * must not stop at exactly as many GCPs as coefficients, a fit through every GCP whose zero residuals hide a
     * wrong warp (often keeping the outlier), nor remove good GCPs until no fit is left (a 0.05-pixel cut gave
     * no warp for 12 of these 20 draws). Several noise draws, since how many GCPs the rule removes depends on them.
     */
    @Test
    public void testFallbackFitKeepsRedundantGcps() throws Exception {
        for (int seed = 1; seed <= 20; seed++) {
            GCPManager.instance().removeAllGcpGroups();
            // 7 GCPs: one more than degree 2 needs, so the CPM fails on the outlier
            assertValidFallback(warp(noisyStack(7, 0.1, seed)), 0.1, "seed " + seed);
        }
    }

    /**
     * The significance level 0.001 must not become a 0.001-pixel cut in the fallback: with GCPs as noisy as those
     * that make the CPM fail, such a cut removes every good GCP.
     */
    @Test
    public void testFallbackAtSmallestSignificanceLevelGivesWarp() throws Exception {
        for (int seed = 1; seed <= 10; seed++) {
            GCPManager.instance().removeAllGcpGroups();
            assertValidFallback(warp(noisyStack(7, 0.3, seed), 0.001f), 0.3, "seed " + seed);
        }
    }

    /** A detected (amplitude) stack uses the RMS rule throughout; the same order rule applies there. */
    private static Product detectedStack(final int numGcps) {
        final Product p = new Product("stack", "GRD", W, H);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString("processed_slave", "Amplitude_VV_sec1_13Jan2020");
        final Band ref = addBand(p, "Amplitude_VV_ref_01Jan2020", Unit.AMPLITUDE, 0, 0);
        final Band sec = addBand(p, "Amplitude_VV_sec1_13Jan2020", Unit.AMPLITUDE, DX, DY);
        addGcps(ref, sec, numGcps, DX, DY, -1);
        return p;
    }

    @Test
    public void testDetectedStackFallsBackToLowerDegree() throws Exception {
        final Product target = warp(detectedStack(5));
        assertWarpedOntoReference(target, "Amplitude_VV_sec1_13Jan2020");
        assertEquals("Polynomial 1", warpMethod(target, "Amplitude_VV_sec1_13Jan2020"));
        assertEquals(0, warpData(target, "Amplitude_VV_sec1_13Jan2020").getAttributeInt("demRefined"));
    }

    /** 3 GCPs determine a degree-1 fit exactly: nothing checks it, so the band has no warp. */
    @Test
    public void testThreeGcpsGiveNoWarp() throws Exception {
        final Product target = warp(detectedStack(3));
        final float[] out = new float[W * H];
        target.getBand("Amplitude_VV_sec1_13Jan2020").readPixels(0, 0, W, H, out);
        assertEquals(NO_DATA, out[W + 1], 0.0);
        assertEquals("None 0", warpMethod(target, "Amplitude_VV_sec1_13Jan2020"));
    }
}
