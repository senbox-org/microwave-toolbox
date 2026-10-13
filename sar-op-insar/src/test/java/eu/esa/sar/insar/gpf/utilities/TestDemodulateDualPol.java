/*
 * Copyright (C) 2026 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf.utilities;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.insar.gpf.coregistration.CreateStackOp;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Demodulate / Remodulate on a dual-pol stack as CreateStack builds it: one secondary product carrying
 * two complex pairs under two tags (i/q_CH_sec1_D, i/q_CV_sec2_D). Both operators used to keep only
 * the last i/q pair of each product, and DemodulateOp keyed its Doppler/offset metadata to the first
 * i band - so one polarisation vanished and the other had no metadata to compute with.
 * Data-free on purpose: the IMS tests in TestDemodulateOp/TestRemodulateOp skip without E:\TestData.
 */
public class TestDemodulateDualPol {

    private static final int W = 16, H = 12;
    private static final String[] POLS = {"CH", "CV"};

    @Test
    public void testDemodulateKeepsEveryPolarisationOfASecondary() throws Exception {
        final Product stack = createDualPolStack();
        final Product demod = demodulate(stack);

        for (final Band stackI : secondaryIBands(stack)) {
            final String suffix = StackUtils.getBandSuffix(stackI.getName());
            assertNotNull(stackI.getName() + " dropped by Demodulate", demod.getBand(stackI.getName()));
            assertNotNull("q partner of " + stackI.getName() + " dropped",
                    demod.getBand("q_" + stackI.getName().substring(2)));

            final Band phaseBand = demod.getBand("DemodPhase" + suffix);
            assertNotNull("no demodulation phase for " + suffix, phaseBand);
            final float[] phase = read(phaseBand);
            boolean nonZero = false;
            for (final float p : phase) {
                assertTrue("non-finite demodulation phase for " + suffix, Float.isFinite(p));
                nonZero |= p != 0f;
            }
            assertTrue("demodulation phase for " + suffix + " is all zero - Doppler metadata not applied", nonZero);
        }
        assertEquals("one demodulation phase band per secondary pair", 2, countBandsStartingWith(demod, "DemodPhase"));
    }

    @Test
    public void testRemodulateRestoresEveryPolarisation() throws Exception {
        final Product stack = createDualPolStack();
        final Product demod = demodulate(stack);

        final RemodulateOp remodOp = (RemodulateOp) new RemodulateOp.Spi().createOperator();
        remodOp.setSourceProduct(demod);
        final Product remod = remodOp.getTargetProduct();

        for (final Band stackI : secondaryIBands(stack)) {
            final String qName = "q_" + stackI.getName().substring(2);
            final Band remodI = remod.getBand(stackI.getName());
            final Band remodQ = remod.getBand(qName);
            assertNotNull(stackI.getName() + " dropped by Remodulate", remodI);
            assertNotNull(qName + " dropped by Remodulate", remodQ);
            assertArrayEquals(stackI.getName() + " not restored", read(stackI), read(remodI), 1e-3f);
            assertArrayEquals(qName + " not restored", read(stack.getBand(qName)), read(remodQ), 1e-3f);
        }
    }

    private static Product demodulate(final Product stack) {
        final DemodulateOp op = (DemodulateOp) new DemodulateOp.Spi().createOperator();
        op.setSourceProduct(stack);
        return op.getTargetProduct();
    }

    private static Product createDualPolStack() {
        final CreateStackOp op = (CreateStackOp) new CreateStackOp.Spi().createOperator();
        op.setSourceProducts(createDualPolSlc("ref", 0.25), createDualPolSlc("sec", 0.5));
        op.setParameter("extent", CreateStackOp.MASTER_EXTENT);
        op.setParameter("initialOffsetMethod", CreateStackOp.INITIAL_OFFSET_GEOLOCATION);
        final Product stack = op.getTargetProduct();

        final List<Band> secI = secondaryIBands(stack);
        assertEquals("fixture: expected a CH and a CV pair for the secondary", 2, secI.size());
        assertNotEquals("fixture: CreateStack tags each complex pair separately",
                StackUtils.getBandSuffix(secI.get(0).getName()), StackUtils.getBandSuffix(secI.get(1).getName()));
        return stack;
    }

    private static Product createDualPolSlc(final String name, final double dopplerConstant) {
        final Product p = TestUtils.createProduct("SLC", W, H);
        for (final Band b : p.getBands().clone()) {
            p.removeBand(b);
        }
        p.setName(name);
        float scale = 1f;
        for (final String pol : POLS) {
            fill(TestUtils.createBand(p, "i_" + pol, ProductData.TYPE_FLOAT32, Unit.REAL, W, H, true), scale);
            fill(TestUtils.createBand(p, "q_" + pol, ProductData.TYPE_FLOAT32, Unit.IMAGINARY, W, H, true), -scale);
            scale *= 2f;
        }

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(p);
        AbstractMetadata.setAttribute(abs, AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.ACQUISITION_MODE, "Stripmap");
        AbstractMetadata.setAttribute(abs, AbstractMetadata.range_sampling_rate, 100.0); // MHz
        AbstractMetadata.setAttribute(abs, AbstractMetadata.slant_range_to_first_pixel, 800000.0);
        // A real stripmap line interval. TestUtils derives seconds per line from its 5-minute product,
        // which drives 2*pi*fDC*t to ~1e5 rad - beyond what the float32 DemodPhase band can round-trip.
        AbstractMetadata.setAttribute(abs, AbstractMetadata.line_time_interval, 5.0e-4);

        final AbstractMetadata.DopplerCentroidCoefficientList dop = new AbstractMetadata.DopplerCentroidCoefficientList();
        dop.time = abs.getAttributeUTC(AbstractMetadata.first_line_time);
        dop.slant_range_time = 5.3e6; // ns
        dop.coefficients = new double[]{dopplerConstant * 1000.0, 0.0, 0.0};
        AbstractMetadata.setDopplerCentroidCoefficients(abs, new AbstractMetadata.DopplerCentroidCoefficientList[]{dop});
        return p;
    }

    private static void fill(final Band band, final float scale) {
        final float[] data = new float[W * H];
        for (int k = 0; k < data.length; k++) {
            data[k] = scale * (1 + (k % 7));
        }
        band.setRasterData(ProductData.createInstance(data));
    }

    private static List<Band> secondaryIBands(final Product stack) {
        final List<Band> bands = new ArrayList<>();
        for (final Band b : stack.getBands()) {
            if (b.getName().startsWith("i_") && b.getName().contains(StackUtils.SEC)) {
                bands.add(b);
            }
        }
        return bands;
    }

    private static int countBandsStartingWith(final Product p, final String prefix) {
        int n = 0;
        for (final String name : p.getBandNames()) {
            if (name.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    private static float[] read(final Band band) throws Exception {
        final float[] data = new float[W * H];
        band.readPixels(0, 0, W, H, data, ProgressMonitor.NULL);
        return data;
    }
}
