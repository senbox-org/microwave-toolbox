/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestPhaseLinkingOp {

    private static final OperatorSpi spi = new PhaseLinkingOp.Spi();

    @Test
    public void spi_creates_operator() {
        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = PhaseLinkingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("PhaseLinking", md.alias());
        assertEquals("Radar/Interferometric/Phase Linking", md.category());
    }

    /**
     * Regression: target band names must carry _ref / _sec<n> tags so downstream
     * InSAR operators (InterferogramOp, CoherenceOp, MultiMasterInSAROp) can resolve them.
     */
    @Test
    public void output_band_names_carry_ref_and_sec_tags() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        // Disable diagnostic bands for a clean assert; not relevant to naming.
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        // Master (01Jan2025) -> _ref. Two secondaries -> _sec1 / _sec2.
        assertNotNull("reference real band", target.getBand("i_pl_VV_ref_01Jan2025"));
        assertNotNull("reference imag band", target.getBand("q_pl_VV_ref_01Jan2025"));
        assertNotNull("secondary-1 real band", target.getBand("i_pl_VV_sec1_13Jan2025"));
        assertNotNull("secondary-1 imag band", target.getBand("q_pl_VV_sec1_13Jan2025"));
        assertNotNull("secondary-2 real band", target.getBand("i_pl_VV_sec2_25Jan2025"));
        assertNotNull("secondary-2 imag band", target.getBand("q_pl_VV_sec2_25Jan2025"));
    }

    /**
     * Regression: Reference_bands / Secondary_bands metadata must be rewritten to point
     * to the phase-linked band names; otherwise StackUtils helpers return stale names.
     */
    @Test
    public void stack_metadata_points_to_phase_linked_bands() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final String[] refBands = StackUtils.getReferenceBandNames(target);
        assertEquals("two reference bands expected", 2, refBands.length);
        for (String name : refBands) {
            assertTrue("reference band " + name + " must include _ref", name.contains(StackUtils.REF));
            assertNotNull("reference band " + name + " must exist in product", target.getBand(name));
        }

        final String[] secProducts = StackUtils.getSecondaryProductNames(target);
        assertEquals("two secondary products expected", 2, secProducts.length);
        for (String secName : secProducts) {
            final String[] secBands = StackUtils.getSecondaryBandNames(target, secName);
            assertEquals("two bands per secondary expected", 2, secBands.length);
            for (String name : secBands) {
                assertTrue("secondary band " + name + " must include _sec", name.contains(StackUtils.SEC));
                assertNotNull("secondary band " + name + " must exist in product", target.getBand(name));
            }
        }
    }

    /**
     * Regression: the phase-linked product must be a drop-in input for InterferogramOp -
     * i.e. InterferogramOp's polarisation-pairing helper must find at least one pol shared
     * between the new _ref and _sec bands. Pre-fix, PhaseLinking emitted bands like
     * "i_pl_VV_<date>" (no _ref/_sec tags), so this returned [""] - the downstream search
     * then matched zero bands and silently produced an empty interferogram product.
     */
    @Test
    public void phase_linked_output_is_consumable_by_interferogram_pairing() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final String[] sharedPols = InterferogramOp.getPolsSharedByRefSec(target, new String[]{"VV"});
        assertEquals("InterferogramOp must find VV shared between phase-linked _ref and _sec bands",
                1, sharedPols.length);
        assertEquals("VV", sharedPols[0]);
    }

    /**
     * Regression: when a centre pixel has a noData epoch, the operator previously zeroed every
     * epoch at that pixel. The fix passes the original SLC samples through, so valid epochs at
     * the pixel are preserved and downstream interferograms/coherence don't get giant zero
     * holes.
     */
    @Test
    public void invalid_centre_pixel_passes_through_original_samples() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");
        final int w = stack.getSceneRasterWidth();
        final int h = stack.getSceneRasterHeight();

        // Force the secondary-1 i/q band to be all-zero at the centre pixel only. The reference
        // and secondary-2 are left as TestUtils' increasing ramp, so they are non-zero everywhere.
        final int px = w / 2, py = h / 2;
        final org.esa.snap.core.datamodel.Band sec1I = stack.getBand("i_VV" + StackUtils.SEC + "1_13Jan2025");
        final org.esa.snap.core.datamodel.Band sec1Q = stack.getBand("q_VV" + StackUtils.SEC + "1_13Jan2025");
        assertNotNull(sec1I);
        assertNotNull(sec1Q);
        final float[] sec1Idata = (float[]) sec1I.getData().getElems();
        final float[] sec1Qdata = (float[]) sec1Q.getData().getElems();
        final int idx = py * w + px;
        sec1Idata[idx] = 0f;
        sec1Qdata[idx] = 0f;

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final org.esa.snap.core.datamodel.Band tgtRefI =
                target.getBand("i_pl_VV" + StackUtils.REF + "_01Jan2025");
        final org.esa.snap.core.datamodel.Band tgtSec2I =
                target.getBand("i_pl_VV" + StackUtils.SEC + "2_25Jan2025");
        assertNotNull(tgtRefI);
        assertNotNull(tgtSec2I);

        final float[] refIpx = new float[w * h];
        final float[] sec2Ipx = new float[w * h];
        tgtRefI.readPixels(0, 0, w, h, refIpx);
        tgtSec2I.readPixels(0, 0, w, h, sec2Ipx);

        // At the marked centre pixel: secondary-1 was zeroed, so PhaseLinking can't form a clean
        // covariance there. Reference and secondary-2 had valid samples and must come through
        // unchanged (i.e. equal to the input's increasing ramp at that index, which is idx + 1.5f).
        final float expected = idx + 1.5f;
        assertEquals("reference epoch must pass through at noData-affected pixel",
                expected, refIpx[idx], 0f);
        assertEquals("secondary-2 epoch must pass through at noData-affected pixel",
                expected, sec2Ipx[idx], 0f);
    }

    /** Build a minimal valid coregistered complex SLC stack. dates[0] is the master. */
    private static Product buildCoregisteredComplexStack(final String[] dates, final String pol) throws Exception {
        final int w = 8, h = 8;
        final Product product = TestUtils.createProduct("SLC", w, h);

        final DateFormat parser = new SimpleDateFormat("ddMMMyyyy HH:mm:ss", Locale.ENGLISH);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        // Use a non-S1 mission so the validator does not branch into TOPS-burst checks.
        absRoot.setAttributeString(AbstractMetadata.MISSION, "TestMission");
        // Required by InputProductValidator.isSARProduct().
        absRoot.setAttributeDouble("radar_frequency", 5405.0);
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time,
                ProductData.UTC.create(parser.parse(dates[0] + " 12:00:00"), 0));

        // Reference (master) i/q bands.
        final String refSuffix = "_" + pol + StackUtils.REF + "_" + dates[0];
        final String iRefName = "i" + refSuffix;
        final String qRefName = "q" + refSuffix;
        TestUtils.createBand(product, iRefName, ProductData.TYPE_FLOAT32, Unit.REAL, w, h, true);
        TestUtils.createBand(product, qRefName, ProductData.TYPE_FLOAT32, Unit.IMAGINARY, w, h, true);

        // Secondary_Metadata root with one element per secondary product.
        MetadataElement secRoot = product.getMetadataRoot().getElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        if (secRoot == null) {
            secRoot = new MetadataElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
            product.getMetadataRoot().addElement(secRoot);
        }
        secRoot.setAttributeString(AbstractMetadata.REFERENCE_BANDS, iRefName + " " + qRefName);

        for (int k = 1; k < dates.length; k++) {
            final String secTag = StackUtils.SEC + k;
            final String iName = "i_" + pol + secTag + "_" + dates[k];
            final String qName = "q_" + pol + secTag + "_" + dates[k];
            TestUtils.createBand(product, iName, ProductData.TYPE_FLOAT32, Unit.REAL, w, h, true);
            TestUtils.createBand(product, qName, ProductData.TYPE_FLOAT32, Unit.IMAGINARY, w, h, true);

            final String secProductName = "secondary_" + dates[k];
            final MetadataElement secMeta = new MetadataElement(secProductName);
            secRoot.addElement(secMeta);
            secMeta.setAttributeUTC(AbstractMetadata.first_line_time,
                    ProductData.UTC.create(parser.parse(dates[k] + " 12:00:00"), 0));
            secMeta.setAttributeString(AbstractMetadata.SECONDARY_BANDS, iName + " " + qName);
        }

        return product;
    }
}
