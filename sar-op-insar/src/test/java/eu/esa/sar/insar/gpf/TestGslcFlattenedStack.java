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
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * GSLC interferogram on a phase-flattened stack ({@code gslc_output_flattened=true}).
 * <p>
 * Each flattened leg already had exp(+j 4 pi R_mod / lambda) applied, so ref * conj(sec) is already a
 * flat-Earth- and topography-removed interferogram. InterferogramOp used to ignore the stamp and,
 * with its default "subtract flat-Earth phase", removed the reference phase a second time. Only that
 * term may be skipped: flattening touches the range phase only, so the TOPS azimuth carrier-model
 * difference must still be removed. A stack that mixes flattened and natural-phase legs is refused.
 * <p>
 * The fixture carries full reference/secondary geometry (Doppler, orbits with a 150 m baseline,
 * Secondary_Metadata) so the reference-phase setup runs exactly as on a real stack and the
 * flat-Earth phase it would subtract is non-zero.
 */
public class TestGslcFlattenedStack {

    private static final int SIZE = 64;
    private static final double LAT0 = 10.0, LON0 = -68.0, STEP = 1.2566e-4;
    private static final String T0 = "23-JUN-2026 22:50:52.000000";

    private static double plane(final int x, final int y) {
        return 0.03 * x - 0.02 * y;
    }

    /** Azimuth carrier models of the two legs (radians), different and spatially varying. */
    private static double carrierRef(final int x, final int y) {
        return 3.0 + 0.05 * y;
    }

    private static double carrierSec(final int x, final int y) {
        return 1.0 + 0.01 * x;
    }

    private static void addBand(final Product p, final String name, final String unit, final float[] data) {
        final Band b = new Band(name, ProductData.TYPE_FLOAT32, SIZE, SIZE);
        b.setUnit(unit);
        b.setRasterData(ProductData.createInstance(data));
        p.addBand(b);
    }

    private static void stamp(final MetadataElement abs, final Boolean flattened) {
        if (flattened != null) {
            abs.setAttributeString("gslc_output_flattened", flattened.toString());
        }
    }

    private static double[] ecef(final double latDeg, final double lonDeg, final double h) {
        final double a = 6378137.0, e2 = 6.69437999014e-3;
        final double lat = Math.toRadians(latDeg), lon = Math.toRadians(lonDeg);
        final double n = a / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        return new double[]{(n + h) * Math.cos(lat) * Math.cos(lon),
                (n + h) * Math.cos(lat) * Math.sin(lon),
                (n * (1 - e2) + h) * Math.sin(lat)};
    }

    /**
     * Straight-line orbit moving north at 7 km/s, 700 km up and 400 km west of the scene centre
     * (right-looking, ascending), crossing the centre's zero-Doppler plane 14 s after T0.
     * {@code baselineM} shifts the whole track along the local up axis.
     */
    private static void setOrbit(final MetadataElement abs, final double baselineM) throws Exception {
        final double lat = Math.toRadians(LAT0 - STEP * SIZE / 2), lon = Math.toRadians(LON0 + STEP * SIZE / 2);
        final double[] p = ecef(Math.toDegrees(lat), Math.toDegrees(lon), 0.0);
        final double[] up = {Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)};
        final double[] east = {-Math.sin(lon), Math.cos(lon), 0.0};
        final double[] north = {-Math.sin(lat) * Math.cos(lon), -Math.sin(lat) * Math.sin(lon), Math.cos(lat)};
        final double v = 7000.0, tc = 14.0;
        final ProductData.UTC t0 = AbstractMetadata.parseUTC(T0);
        final OrbitStateVector[] osv = new OrbitStateVector[8];
        for (int k = 0; k < osv.length; k++) {
            final double dt = -20.0 + 10.0 * k;
            final double[] s = new double[3];
            for (int c = 0; c < 3; c++) {
                s[c] = p[c] + (700e3 + baselineM) * up[c] - 400e3 * east[c] + v * (dt - tc) * north[c];
            }
            final ProductData.UTC t = new ProductData.UTC(t0.getMJD() + dt / 86400.0);
            osv[k] = new OrbitStateVector(t, s[0], s[1], s[2], v * north[0], v * north[1], v * north[2]);
        }
        AbstractMetadata.setOrbitStateVectors(abs, osv);
    }

    private static void setGeometry(final MetadataElement abs) throws Exception {
        final ProductData.UTC t0 = AbstractMetadata.parseUTC(T0);
        abs.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        abs.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.0);
        abs.setAttributeDouble(AbstractMetadata.pulse_repetition_frequency, 486.5);
        abs.setAttributeDouble(AbstractMetadata.range_sampling_rate, 64.0);
        abs.setAttributeDouble(AbstractMetadata.range_bandwidth, 56.5);
        abs.setAttributeDouble(AbstractMetadata.azimuth_bandwidth, 327.0);
        abs.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 800000.0);
        abs.setAttributeDouble(AbstractMetadata.line_time_interval, 0.4);
        abs.setAttributeInt(AbstractMetadata.num_samples_per_line, SIZE);
        abs.setAttributeInt(AbstractMetadata.num_output_lines, SIZE);
        abs.setAttributeUTC(AbstractMetadata.first_line_time, t0);
        abs.setAttributeUTC(AbstractMetadata.last_line_time,
                new ProductData.UTC(t0.getMJD() + 28.0 / 86400.0));
        MetadataElement dop = abs.getElement(AbstractMetadata.dop_coefficients);
        if (dop == null) {
            dop = new MetadataElement(AbstractMetadata.dop_coefficients);
            abs.addElement(dop);
        }
        final MetadataElement list = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
        list.setAttributeUTC(AbstractMetadata.srgr_coef_time, t0);
        list.setAttributeDouble(AbstractMetadata.slant_range_time, 5.3e6);
        final MetadataElement coef = new MetadataElement(AbstractMetadata.coefficient + ".1");
        coef.setAttributeDouble(AbstractMetadata.dop_coef, 0.0);
        list.addElement(coef);
        dop.addElement(list);
    }

    /** Minimal S1 annotation with two bursts: marks the stack as TOPS for the carrier-difference path. */
    private static void addTopsBurstAnnotation(final Product p) {
        final MetadataElement opm = new MetadataElement(AbstractMetadata.ORIGINAL_PRODUCT_METADATA);
        final MetadataElement annotation = new MetadataElement("annotation");
        final MetadataElement file = new MetadataElement("s1a-iw1-slc-vv-20260623.xml");
        final MetadataElement prod = new MetadataElement("product");
        final MetadataElement swathTiming = new MetadataElement("swathTiming");
        swathTiming.setAttributeString("linesPerBurst", "1500");
        final MetadataElement burstList = new MetadataElement("burstList");
        for (final String t : new String[]{"2026-06-23T22:50:52", "2026-06-23T22:50:55"}) {
            final MetadataElement burst = new MetadataElement("burst");
            burst.setAttributeString("azimuthTime", t);
            burstList.addElement(burst);
        }
        swathTiming.addElement(burstList);
        final MetadataElement imgAnn = new MetadataElement("imageAnnotation");
        final MetadataElement imgInfo = new MetadataElement("imageInformation");
        imgInfo.setAttributeString("azimuthTimeInterval", "0.002055556");
        imgAnn.addElement(imgInfo);
        prod.addElement(swathTiming);
        prod.addElement(imgAnn);
        file.addElement(prod);
        annotation.addElement(file);
        opm.addElement(annotation);
        p.getMetadataRoot().addElement(opm);
    }

    /**
     * Reference and secondary legs. A flattened (or natural, the fixture does not care) leg pair
     * whose conjugate product is exp(+j plane); with {@code withCarrier} the legs also carry their
     * TOPS carrier models exp(+j m) (GSLCGeocodingOp convention, see TestCarrierDiffSign) and the
     * azimuthCarrierPhase bands that describe them.
     */
    private static Product newStack(final Boolean refFlattened, final Boolean secFlattened,
                                    final boolean withCarrier) throws Exception {
        final Product p = new Product("gslcStack", "GSLC", SIZE, SIZE);
        final ProductData.UTC t0 = AbstractMetadata.parseUTC(T0);
        p.setStartTime(t0);
        p.setEndTime(new ProductData.UTC(t0.getMJD() + 28.0 / 86400.0));
        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        abs.setAttributeInt(AbstractMetadata.is_terrain_corrected, 1);
        setGeometry(abs);
        setOrbit(abs, 0.0);
        stamp(abs, refFlattened);
        p.setSceneGeoCoding(new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                SIZE, SIZE, LON0, LAT0, STEP, STEP));

        final MetadataElement secRoot = new MetadataElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        p.getMetadataRoot().addElement(secRoot);
        final MetadataElement secAbs = new MetadataElement("S1A_secondary_24Jun2026");
        ProductUtils.copyMetadata(abs, secAbs);
        setOrbit(secAbs, 150.0);
        if (secAbs.getAttribute("gslc_output_flattened") != null) {
            secAbs.removeAttribute(secAbs.getAttribute("gslc_output_flattened"));
        }
        stamp(secAbs, secFlattened);
        secAbs.setAttributeString(AbstractMetadata.SECONDARY_BANDS, "i_VV_sec1_24Jun2026 q_VV_sec1_24Jun2026");
        secRoot.addElement(secAbs);

        if (withCarrier) {
            addTopsBurstAnnotation(p);
        }

        final float[] ri = new float[SIZE * SIZE], rq = new float[SIZE * SIZE];
        final float[] si = new float[SIZE * SIZE], sq = new float[SIZE * SIZE];
        final float[] mr = new float[SIZE * SIZE], ms = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                final int k = y * SIZE + x;
                final double mRef = withCarrier ? carrierRef(x, y) : 0.0;
                final double mSec = withCarrier ? carrierSec(x, y) : 0.0;
                ri[k] = (float) Math.cos(mRef);
                rq[k] = (float) Math.sin(mRef);
                si[k] = (float) Math.cos(mSec - plane(x, y));
                sq[k] = (float) Math.sin(mSec - plane(x, y));
                mr[k] = (float) mRef;
                ms[k] = (float) mSec;
            }
        }
        addBand(p, "i_VV_ref_23Jun2026", Unit.REAL, ri);
        addBand(p, "q_VV_ref_23Jun2026", Unit.IMAGINARY, rq);
        addBand(p, "i_VV_sec1_24Jun2026", Unit.REAL, si);
        addBand(p, "q_VV_sec1_24Jun2026", Unit.IMAGINARY, sq);
        if (withCarrier) {
            addBand(p, "azimuthCarrierPhase_ref_23Jun2026", Unit.PHASE, mr);
            addBand(p, "azimuthCarrierPhase_sec1_24Jun2026", Unit.PHASE, ms);
        }
        return p;
    }

    /** Default parameters: subtractFlatEarthPhase = true. */
    private static Product runIfg(final Product src) {
        final InterferogramOp op = new InterferogramOp();
        op.setSourceProduct(src);
        op.setParameter("includeCoherence", false);
        return op.getTargetProduct();
    }

    /** Largest |wrapped(ifg phase - plane)| over a few pixels. */
    private static double maxDeviationFromPlane(final Product tgt) throws Exception {
        Band bi = null, bq = null;
        for (final Band b : tgt.getBands()) {
            if (b.getName().startsWith("i_ifg")) bi = b;
            if (b.getName().startsWith("q_ifg")) bq = b;
        }
        assertNotNull("i_ifg missing", bi);
        assertNotNull("q_ifg missing", bq);
        final float[] iv = new float[SIZE * SIZE];
        final float[] qv = new float[SIZE * SIZE];
        bi.readPixels(0, 0, SIZE, SIZE, iv);
        bq.readPixels(0, 0, SIZE, SIZE, qv);
        double worst = 0.0;
        for (final int[] xy : new int[][]{{3, 5}, {40, 10}, {20, 60}, {60, 60}}) {
            final int k = xy[1] * SIZE + xy[0];
            final double got = Math.atan2(qv[k], iv[k]);
            final double want = plane(xy[0], xy[1]);
            worst = Math.max(worst, Math.abs(Math.atan2(Math.sin(got - want), Math.cos(got - want))));
        }
        return worst;
    }

    @Test
    public void testFlattenedStackIsNotFlattenedTwice() throws Exception {
        assertEquals("flattened stack: ifg must be the raw conjugate product",
                0.0, maxDeviationFromPlane(runIfg(newStack(true, true, false))), 1e-4);
        // Control: the same legs read as natural phase DO get a non-zero flat-Earth phase removed,
        // so the assertion above would fail if that subtraction ran on the flattened stack.
        assertTrue("fixture must produce a non-zero flat-Earth phase",
                maxDeviationFromPlane(runIfg(newStack(false, false, false))) > 0.1);
    }

    /**
     * Flattened TOPS stack with carrier-model bands: only the reference phase is skipped; the
     * carrier-model difference (m_ref - m_sec) must still be removed. Skipping the whole
     * reference-phase setup left exp(+j (m_ref - m_sec)) in the interferogram.
     */
    @Test
    public void testFlattenedTopsStackStillRemovesCarrierDifference() throws Exception {
        assertEquals("flattened TOPS stack: carrier difference must be removed, reference phase not",
                0.0, maxDeviationFromPlane(runIfg(newStack(true, true, true))), 1e-4);
    }

    @Test
    public void testMixedStackIsRefused() throws Exception {
        try {
            runIfg(newStack(true, false, false));
            fail("a stack mixing flattened and natural-phase legs must be refused");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mixes phase-flattened"));
        }
    }

    @Test
    public void testStampResolution() throws Exception {
        assertTrue(InterferogramOp.isGslcStackFlattened(newStack(true, true, false)));
        assertFalse(InterferogramOp.isGslcStackFlattened(newStack(false, false, false)));
        // legacy products without a stamp are natural-phase
        assertFalse(InterferogramOp.isGslcStackFlattened(newStack(null, null, false)));
        try {
            InterferogramOp.isGslcStackFlattened(newStack(false, true, false));
            fail("mixed stack");
        } catch (OperatorException expected) {
            // ok
        }
    }
}
