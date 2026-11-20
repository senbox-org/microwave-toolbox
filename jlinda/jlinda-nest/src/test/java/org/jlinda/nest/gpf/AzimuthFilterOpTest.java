/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package org.jlinda.nest.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AzimuthFilterOpTest {

    private static final String REF = "10Jul2018";
    private static final String SEC1 = "22Jul2018";
    private static final String SEC2 = "27Aug2018";

    /**
     * With more than one Secondary the Reference is left unfiltered. That decision must belong to the operator
     * instance: it used to be a static flag, so after one multi-Secondary run every later single-pair run in the
     * same session also left the Reference unfiltered.
     */
    @Test
    public void testSinglePairAfterMultiSecondaryRunFiltersReference() throws IOException {
        final Product multiTarget = runOn(createStack("", SEC1, SEC2));
        assertNull("multi-Secondary stack: Reference stays unfiltered",
                multiTarget.getBand("i_VV_ref_" + REF + "_azifilt"));
        assertNotNull(multiTarget.getBand("i_VV_sec1_" + SEC1 + "_azifilt"));

        final Product singleTarget = runOn(createStack("", SEC1));
        assertNotNull("single pair: Reference must be filtered, whatever ran before",
                singleTarget.getBand("i_VV_ref_" + REF + "_azifilt"));
        assertNotNull(singleTarget.getBand("i_VV_sec1_" + SEC1 + "_azifilt"));
    }

    /** Azimuth common-band filtering assumes one Doppler centroid per image; TOPS sweeps it within each burst. */
    @Test
    public void testRefusesTOPSProduct() throws IOException {
        try {
            runOn(createStack("IW1_", SEC1));
            fail("TOPS input must be refused");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("TOPS"));
        }
    }

    /** TOPSAR-Merge drops the IW1/EW1 tags from band names; the metadata still says Sentinel-1 IW/EW. */
    @Test
    public void testRefusesMergedTOPSProduct() throws IOException {
        for (String mode : new String[]{"IW", "EW"}) {
            final Product merged = createStack("", SEC1);
            AbstractMetadata.getAbstractedMetadata(merged).setAttributeString(AbstractMetadata.ACQUISITION_MODE, mode);
            try {
                runOn(merged);
                fail("merged S1 " + mode + " input must be refused");
            } catch (OperatorException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("TOPS"));
            }
        }
    }

    /** SAOCOM TOPSAR modes sweep the Doppler centroid too and carry no IW/EW band tags. */
    @Test
    public void testRefusesSaocomTOPSARProduct() throws IOException {
        for (String mode : new String[]{"TOPSARNarrow", "TOPSARWide"}) {
            final Product saocom = createStack("", SEC1);
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(saocom);
            absRoot.setAttributeString(AbstractMetadata.MISSION, "SAOCOM");
            absRoot.setAttributeString(AbstractMetadata.ACQUISITION_MODE, mode);
            try {
                runOn(saocom);
                fail("SAOCOM " + mode + " input must be refused");
            } catch (OperatorException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("TOPS"));
            }
        }
    }

    /** Sentinel-1 Stripmap (createStack default metadata) and Wave mode are not TOPS and must pass. */
    @Test
    public void testAcceptsSentinel1WaveMode() throws IOException {
        final Product wv = createStack("", SEC1);
        AbstractMetadata.getAbstractedMetadata(wv).setAttributeString(AbstractMetadata.ACQUISITION_MODE, "WV");
        assertNotNull(runOn(wv).getBand("i_VV_ref_" + REF + "_azifilt"));
    }

    private static Product runOn(final Product source) {
        final AzimuthFilterOp op = (AzimuthFilterOp) new AzimuthFilterOp.Spi().createOperator();
        op.setSourceProduct(source);
        return op.getTargetProduct();
    }

    /**
     * Single-polarisation (VV) stack with one Reference and the given Secondary dates, built on the metadata of
     * dualPolMetadata.xml (Reference 10Jul2018, Secondaries 22Jul2018, 27Aug2018, 20Sep2018).
     */
    private static Product createStack(final String swathTag, final String... secDates) throws IOException {
        final int size = 10;
        final Product p = TestUtils.createProduct("SLC", size, size);
        addPair(p, "i_" + swathTag + "VV_ref_" + REF, "q_" + swathTag + "VV_ref_" + REF, size);
        for (int k = 0; k < secDates.length; k++) {
            final String tag = "sec" + (k + 1) + "_" + secDates[k];
            addPair(p, "i_" + swathTag + "VV_" + tag, "q_" + swathTag + "VV_" + tag, size);
        }

        AbstractMetadataIO.Load(p, p.getMetadataRoot(), new File("src/test/resources/dualPolMetadata.xml"));

        final MetadataElement secRoot = StackUtils.findSecondaryMetadataRoot(p);
        final MetadataElement[] secElems = secRoot.getElements();
        for (int k = secDates.length; k < secElems.length; k++) {
            secRoot.removeElement(secElems[k]);
        }
        return p;
    }

    private static void addPair(final Product p, final String iName, final String qName, final int size) {
        final Band i = TestUtils.createBand(p, iName, ProductData.TYPE_FLOAT32, Unit.REAL, size, size, true);
        final Band q = TestUtils.createBand(p, qName, ProductData.TYPE_FLOAT32, Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(p, i, q, "");
    }
}
