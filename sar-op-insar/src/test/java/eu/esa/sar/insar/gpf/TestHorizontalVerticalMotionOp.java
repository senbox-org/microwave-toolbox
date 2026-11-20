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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link HorizontalVerticalMotionOp}.
 */
public class TestHorizontalVerticalMotionOp {

    @Test
    public void testSpiCreatesOperator() {
        final HorizontalVerticalMotionOp op = (HorizontalVerticalMotionOp) new HorizontalVerticalMotionOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = HorizontalVerticalMotionOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("HorizontalVerticalMotion", md.alias());
    }

    // ---- synthetic ascending/descending motion decomposition ----------------------------------

    private static final int W = 4, H = 4;
    private static final double FREQ_MHZ = 5405.0;
    private static final double LAMBDA = 299792458.0 / (FREQ_MHZ * 1e6);
    private static final double HEADING_DSC = 190.0, HEADING_ASC = 350.0;   // degrees from north
    private static final double INC_DSC = 39.0, INC_ASC = 34.0;             // degrees

    /**
     * LOS displacement, positive TOWARDS the satellite, of a ground motion (east, up) seen by a
     * right-looking SAR with the given heading and incidence. The ground-to-satellite unit vector
     * has east component -sin(theta)*cos(heading) and up component cos(theta).
     */
    private static double losTowardsSatellite(final double east, final double up,
                                              final double headingDeg, final double incDeg) {
        final double t = Math.toRadians(incDeg);
        return up * Math.cos(t) - east * Math.sin(t) * Math.cos(Math.toRadians(headingDeg));
    }

    /**
     * SNAP interferometric phase of a LOS displacement. The interferogram is ref * conj(sec) of
     * SLCs that carry exp(-j 4 pi R / lambda), so phi = 4 pi / lambda (R_sec - R_ref), and motion
     * towards the satellite (R_sec < R_ref) gives NEGATIVE phase: phi = -4 pi / lambda * d_LOS.
     * This is the convention PhaseToDisplacementOp and SBASInversionOp invert with -lambda/4pi.
     */
    private static double phaseOf(final double losMetres) {
        return -4.0 * Math.PI / LAMBDA * losMetres;
    }

    private static void addConstBand(final Product p, final String name, final String unit,
                                     final double value, final double valueAtOrigin) {
        final float[] data = new float[W * H];
        java.util.Arrays.fill(data, (float) value);
        data[0] = (float) valueAtOrigin;
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, W, H);
        b.setUnit(unit);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
    }

    private static Product newCollocatedPair(final double eastM, final double upM) {
        return newCollocatedPair(eastM, upM, HEADING_ASC, INC_ASC);
    }

    private static Product newCollocatedPair(final double eastM, final double upM,
                                             final double headingAsc, final double incAsc) {
        final Product p = new Product("hvm", "SLC", W, H);
        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        abs.setAttributeDouble(AbstractMetadata.radar_frequency, FREQ_MHZ);
        abs.setAttributeString(AbstractMetadata.PASS, "DESCENDING");
        abs.setAttributeDouble("centre_heading", HEADING_DSC);
        final MetadataElement sec = new MetadataElement("Secondary_Heading_Angles");
        sec.setAttributeDouble("centre_heading_0", headingAsc);
        abs.addElement(sec);

        final double phiDsc = phaseOf(losTowardsSatellite(eastM, upM, HEADING_DSC, INC_DSC));
        final double phiAsc = phaseOf(losTowardsSatellite(eastM, upM, headingAsc, incAsc));
        // reference pixel (0,0) is stable: zero phase there
        addConstBand(p, "Unw_Phase_ifg_M", Unit.ABS_PHASE, phiDsc, 0.0);
        addConstBand(p, "Unw_Phase_ifg_S", Unit.ABS_PHASE, phiAsc, 0.0);
        addConstBand(p, "incidenceAngleFromEllipsoid_M", Unit.DEGREES, INC_DSC, INC_DSC);
        addConstBand(p, "incidenceAngleFromEllipsoid_S", Unit.DEGREES, incAsc, incAsc);
        return p;
    }

    /** Marks 0 as the unwrapped-phase no-data value, as SNAPHU import and terrain correction do. */
    private static void zeroIsNoData(final Product p) {
        for (String name : new String[]{"Unw_Phase_ifg_M", "Unw_Phase_ifg_S"}) {
            p.getBand(name).setNoDataValue(0.0);
            p.getBand(name).setNoDataValueUsed(true);
        }
    }

    private static boolean causeMentions(Throwable t, final String text) {
        for (; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** A 0-filled no-data reference pixel would leave both interferograms unreferenced: refuse it. */
    @Test
    public void testNoDataReferencePixelRefused() {
        final Product src = newCollocatedPair(0.0, 0.010);
        zeroIsNoData(src);   // the reference pixel (0,0) holds 0 = no-data
        final HorizontalVerticalMotionOp op = new HorizontalVerticalMotionOp();
        op.setSourceProduct(src);
        try {
            read(op.getTargetProduct(), "verticalMotion");
            fail("a no-data reference pixel must be refused");
        } catch (Exception e) {
            assertTrue(e.toString(), causeMentions(e, "no-data"));
        }
    }

    /** Where only one pass has data the 2x2 system has one equation: output NaN, not a finite value. */
    @Test
    public void testOnePassNoDataGivesNaN() throws Exception {
        final Product src = newCollocatedPair(0.005, 0.010);
        final Band asc = src.getBand("Unw_Phase_ifg_S");
        asc.setNoDataValue(-9999.0);
        asc.setNoDataValueUsed(true);
        asc.getRasterData().setElemFloatAt(W + 1, -9999.0f);
        src.getBand("Unw_Phase_ifg_M").getRasterData().setElemFloatAt(2 * W + 1, Float.NaN);

        final HorizontalVerticalMotionOp op = new HorizontalVerticalMotionOp();
        op.setSourceProduct(src);
        final Product target = op.getTargetProduct();
        final float[] up = read(target, "verticalMotion");
        final float[] east = read(target, "horizontalMotion");
        assertTrue("asc no-data", Float.isNaN(up[W + 1]) && Float.isNaN(east[W + 1]));
        assertTrue("dsc NaN", Float.isNaN(up[2 * W + 1]) && Float.isNaN(east[2 * W + 1]));
        assertEquals("valid pixel unaffected", 10.0, up[2 * W + 2], 1e-3);
    }

    /** Nearly identical lines of sight make the system ill-conditioned: output NaN rather than amplified noise. */
    @Test
    public void testNearParallelGeometryGivesNaN() throws Exception {
        final HorizontalVerticalMotionOp op = new HorizontalVerticalMotionOp();
        op.setSourceProduct(newCollocatedPair(0.0, 0.010, HEADING_DSC + 0.01, INC_DSC));
        final float[] up = read(op.getTargetProduct(), "verticalMotion");
        assertTrue(Float.isNaN(up[W + 1]));
    }

    private static float[] read(final Product target, final String band) throws Exception {
        final float[] out = new float[W * H];
        target.getBand(band).readPixels(0, 0, W, H, out);
        return out;
    }

    /** Pure uplift of 10 mm must come out as +10 mm vertical and 0 horizontal. */
    @Test
    public void testSyntheticUpliftIsPositiveVertical() throws Exception {
        final HorizontalVerticalMotionOp op = new HorizontalVerticalMotionOp();
        op.setSourceProduct(newCollocatedPair(0.0, 0.010));
        final Product target = op.getTargetProduct();
        final float[] up = read(target, "verticalMotion");
        final float[] east = read(target, "horizontalMotion");
        assertEquals("uplift (mm)", 10.0, up[W + 1], 1e-3);
        assertEquals("east (mm)", 0.0, east[W + 1], 1e-3);
        assertEquals("reference pixel is zero", 0.0, up[0], 1e-6);
    }

    /** 5 mm eastward + 10 mm uplift: both components recovered with their signs. */
    @Test
    public void testSyntheticEastAndUpRecovered() throws Exception {
        final HorizontalVerticalMotionOp op = new HorizontalVerticalMotionOp();
        op.setSourceProduct(newCollocatedPair(0.005, 0.010));
        final Product target = op.getTargetProduct();
        assertEquals("up (mm)", 10.0, read(target, "verticalMotion")[2 * W + 2], 1e-3);
        assertEquals("east (mm)", 5.0, read(target, "horizontalMotion")[2 * W + 2], 1e-3);
    }
}
