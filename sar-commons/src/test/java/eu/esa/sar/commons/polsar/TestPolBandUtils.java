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
package eu.esa.sar.commons.polsar;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestPolBandUtils {

    // --- isDualPol ---

    @Test
    public void testIsDualPolTrue() {
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.DUAL_HH_HV));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.DUAL_VH_VV));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.DUAL_HH_VV));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.C2));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.LCHCP));
        assertTrue(PolBandUtils.isDualPol(PolBandUtils.MATRIX.RCHCP));
    }

    @Test
    public void testIsDualPolFalse() {
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.C3));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.T3));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.C4));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.T4));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.FULL));
        assertFalse(PolBandUtils.isDualPol(PolBandUtils.MATRIX.UNKNOWN));
    }

    // --- isQuadPol ---

    @Test
    public void testIsQuadPolTrue() {
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.C3));
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.T3));
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.C4));
        assertTrue(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.T4));
    }

    @Test
    public void testIsQuadPolFalse() {
        assertFalse(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.DUAL_HH_HV));
        assertFalse(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.FULL));
        assertFalse(PolBandUtils.isQuadPol(PolBandUtils.MATRIX.UNKNOWN));
    }

    // --- isFullPol ---

    @Test
    public void testIsFullPolTrue() {
        assertTrue(PolBandUtils.isFullPol(PolBandUtils.MATRIX.FULL));
    }

    @Test
    public void testIsFullPolFalse() {
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.DUAL_HH_HV));
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.C3));
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.T4));
        assertFalse(PolBandUtils.isFullPol(PolBandUtils.MATRIX.UNKNOWN));
    }

    // --- band name getters ---

    @Test
    public void testGetComplexBandNames() {
        String[] names = PolBandUtils.getComplexBandNames();
        assertEquals(2, names.length);
        assertEquals("i_", names[0]);
        assertEquals("q_", names[1]);
    }

    @Test
    public void testGetC2BandNames() {
        String[] names = PolBandUtils.getC2BandNames();
        assertEquals(4, names.length);
        assertEquals("C11", names[0]);
        assertEquals("C22", names[3]);
    }

    @Test
    public void testGetC3BandNames() {
        String[] names = PolBandUtils.getC3BandNames();
        assertEquals(9, names.length);
        assertEquals("C11", names[0]);
        assertEquals("C33", names[8]);
    }

    @Test
    public void testGetC4BandNames() {
        String[] names = PolBandUtils.getC4BandNames();
        assertEquals(16, names.length);
        assertEquals("C11", names[0]);
        assertEquals("C44", names[15]);
    }

    @Test
    public void testGetT3BandNames() {
        String[] names = PolBandUtils.getT3BandNames();
        assertEquals(9, names.length);
        assertEquals("T11", names[0]);
        assertEquals("T33", names[8]);
    }

    @Test
    public void testGetT4BandNames() {
        String[] names = PolBandUtils.getT4BandNames();
        assertEquals(16, names.length);
        assertEquals("T11", names[0]);
        assertEquals("T44", names[15]);
    }

    @Test
    public void testGetG4BandNames() {
        String[] names = PolBandUtils.getG4BandNames();
        assertEquals(4, names.length);
        assertEquals("g0", names[0]);
        assertEquals("g3", names[3]);
    }

    @Test
    public void testGetLCHModeS2BandNames() {
        String[] names = PolBandUtils.getLCHModeS2BandNames();
        assertEquals(4, names.length);
        assertEquals("i_LCH", names[0]);
        assertEquals("q_LCV", names[3]);
    }

    @Test
    public void testGetRCHModeS2BandNames() {
        String[] names = PolBandUtils.getRCHModeS2BandNames();
        assertEquals(4, names.length);
        assertEquals("i_RCH", names[0]);
        assertEquals("q_RCV", names[3]);
    }

    // --- isBandForMatrixElement ---

    @Test
    public void testIsBandForMatrixElementMatch() {
        assertTrue(PolBandUtils.isBandForMatrixElement("CC11", "C11"));
        assertTrue(PolBandUtils.isBandForMatrixElement("TT33_real", "T33"));
    }

    @Test
    public void testIsBandForMatrixElementNoMatch() {
        assertFalse(PolBandUtils.isBandForMatrixElement("CC22", "C11"));
    }

    @Test
    public void testIsBandForMatrixElementTooShort() {
        assertFalse(PolBandUtils.isBandForMatrixElement("C", "C11"));
    }

    // --- MATRIX enum values ---

    @Test
    public void testMatrixEnumValues() {
        PolBandUtils.MATRIX[] values = PolBandUtils.MATRIX.values();
        assertEquals(12, values.length);
    }

    // --- PolSourceBand ---

    @Test
    public void testPolSourceBandConstructor() {
        PolBandUtils.PolSourceBand psb = new PolBandUtils.PolSourceBand("product1", new org.esa.snap.core.datamodel.Band[0], "_mst");
        assertEquals("product1", psb.productName);
        assertEquals("_mst", psb.suffix);
        assertEquals(0, psb.srcBands.length);
        assertFalse(psb.spanMinMaxSet);
    }

    @Test
    public void testPolSourceBandSpanDefaults() {
        PolBandUtils.PolSourceBand psb = new PolBandUtils.PolSourceBand("p", new org.esa.snap.core.datamodel.Band[0], "");
        assertTrue(psb.spanMin > 1e+29);
        assertTrue(psb.spanMax < -1e+29);
    }

    // --- getSourceBands on a coregistered stack ---

    /**
     * Builds a two-date dual-pol (VV/VH) coregistered stack the way Back-Geocoding leaves it:
     * every date contributes an i/q pair per polarisation and the per-date band lists live in
     * the Secondary_Metadata element.
     */
    private static Product createDualPolStack() {
        final Product product = new Product("stack", "SLC", 4, 4);

        // Deliberately interleave the polarisations so that a correct implementation has to
        // regroup them (VV pair first, then VH pair) rather than return product order.
        addComplexPair(product, "i_IW1_VH_ref_01Jan2020", "q_IW1_VH_ref_01Jan2020");
        addComplexPair(product, "i_IW1_VV_ref_01Jan2020", "q_IW1_VV_ref_01Jan2020");
        addComplexPair(product, "i_IW1_VH_sec1_02Jan2020", "q_IW1_VH_sec1_02Jan2020");
        addComplexPair(product, "i_IW1_VV_sec1_02Jan2020", "q_IW1_VV_sec1_02Jan2020");

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);

        final MetadataElement secRoot = AbstractMetadata.getSecondaryMetadata(product.getMetadataRoot());
        secRoot.setAttributeString(AbstractMetadata.REFERENCE_BANDS,
                "i_IW1_VH_ref_01Jan2020 q_IW1_VH_ref_01Jan2020 i_IW1_VV_ref_01Jan2020 q_IW1_VV_ref_01Jan2020");
        final MetadataElement secElem = new MetadataElement("secondary_02Jan2020");
        secElem.setAttributeString(AbstractMetadata.SECONDARY_BANDS,
                "i_IW1_VH_sec1_02Jan2020 q_IW1_VH_sec1_02Jan2020 i_IW1_VV_sec1_02Jan2020 q_IW1_VV_sec1_02Jan2020");
        secRoot.addElement(secElem);

        return product;
    }

    private static void addComplexPair(final Product product, final String iName, final String qName) {
        final Band iBand = new Band(iName, ProductData.TYPE_FLOAT32, 4, 4);
        iBand.setUnit(Unit.REAL);
        product.addBand(iBand);
        final Band qBand = new Band(qName, ProductData.TYPE_FLOAT32, 4, 4);
        qBand.setUnit(Unit.IMAGINARY);
        product.addBand(qBand);
    }

    private static String[] namesOf(final Band[] bands) {
        final String[] names = new String[bands.length];
        for (int i = 0; i < bands.length; ++i) {
            names[i] = bands[i].getName();
        }
        return names;
    }

    /**
     * Each date of a coregistered dual-pol stack must yield its own four source bands.
     * Returning every i/q band in the stack makes the covariance matrix of every date read
     * dataBuffers[0..3] = VV(date1) and VV(date2), so C11 comes out identical for all dates.
     */
    @Test
    public void testGetSourceBandsDualPolStackKeepsEachDateSeparate() throws Exception {
        final Product stack = createDualPolStack();

        final PolBandUtils.PolSourceBand[] srcBandList =
                PolBandUtils.getSourceBands(stack, PolBandUtils.MATRIX.DUAL_VH_VV);

        assertEquals(2, srcBandList.length);

        assertArrayEquals(new String[]{
                        "i_IW1_VV_ref_01Jan2020", "q_IW1_VV_ref_01Jan2020",
                        "i_IW1_VH_ref_01Jan2020", "q_IW1_VH_ref_01Jan2020"},
                namesOf(srcBandList[0].srcBands));

        assertArrayEquals(new String[]{
                        "i_IW1_VV_sec1_02Jan2020", "q_IW1_VV_sec1_02Jan2020",
                        "i_IW1_VH_sec1_02Jan2020", "q_IW1_VH_sec1_02Jan2020"},
                namesOf(srcBandList[1].srcBands));
    }

    /**
     * A single-date dual-pol product still resolves all four of its bands, VV pair first.
     */
    @Test
    public void testGetSourceBandsDualPolSingleProduct() throws Exception {
        final Product product = new Product("single", "SLC", 4, 4);
        addComplexPair(product, "i_IW1_VH", "q_IW1_VH");
        addComplexPair(product, "i_IW1_VV", "q_IW1_VV");
        AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());

        final PolBandUtils.PolSourceBand[] srcBandList =
                PolBandUtils.getSourceBands(product, PolBandUtils.MATRIX.DUAL_VH_VV);

        assertEquals(1, srcBandList.length);
        assertArrayEquals(new String[]{"i_IW1_VV", "q_IW1_VV", "i_IW1_VH", "q_IW1_VH"},
                namesOf(srcBandList[0].srcBands));
    }

    // --- getSourceBands failure reporting ---

    private static Product createStackShell(final String referenceBands, final String secondaryBands) {
        final Product product = new Product("stack", "SLC", 4, 4);
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        final MetadataElement secRoot = AbstractMetadata.getSecondaryMetadata(product.getMetadataRoot());
        if (referenceBands != null) {
            secRoot.setAttributeString(AbstractMetadata.REFERENCE_BANDS, referenceBands);
        }
        if (secondaryBands != null) {
            final MetadataElement secElem = new MetadataElement("secondary_02Jan2020");
            secElem.setAttributeString(AbstractMetadata.SECONDARY_BANDS, secondaryBands);
            secRoot.addElement(secElem);
        }
        return product;
    }

    private static void assertNotIndexOutOfBounds(final Exception e) {
        assertFalse("raw " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                e instanceof IndexOutOfBoundsException);
        assertNotNull("exception carries no message", e.getMessage());
    }

    /**
     * A product flagged as a coregistered stack whose Reference_bands metadata is gone (and whose
     * band names carry no _ref/_sec tag for the fallback scan) must be reported by name, not
     * indexed as refBandNames[0] - that is the same "Index 0 out of bounds for length 0" the user hit.
     */
    @Test
    public void testGetSourceBandsEmptyReferenceBandListIsReported() {
        final Product stack = createStackShell(null, null);
        addComplexPair(stack, "i_IW1_VV", "q_IW1_VV");
        addComplexPair(stack, "i_IW1_VH", "q_IW1_VH");

        try {
            PolBandUtils.getSourceBands(stack, PolBandUtils.MATRIX.DUAL_VH_VV);
            fail("expected a reported failure for the missing reference band list");
        } catch (Exception e) {
            assertNotIndexOutOfBounds(e);
        }
    }

    /**
     * A band named in the stack metadata but absent from the product must be named, not silently
     * skipped - a short srcBands array surfaces later as an out-of-bounds inside computeTile.
     */
    @Test
    public void testGetSourceBandsMissingBandIsReported() {
        final Product stack = createStackShell(
                "i_IW1_VV_ref_01Jan2020 q_IW1_VV_ref_01Jan2020 i_IW1_VH_ref_01Jan2020 q_IW1_VH_ref_01Jan2020", null);
        addComplexPair(stack, "i_IW1_VV_ref_01Jan2020", "q_IW1_VV_ref_01Jan2020");
        // i_IW1_VH_ref_01Jan2020 / q_IW1_VH_ref_01Jan2020 deliberately absent

        try {
            PolBandUtils.getSourceBands(stack, PolBandUtils.MATRIX.DUAL_VH_VV);
            fail("expected the missing band to be reported");
        } catch (Exception e) {
            assertNotIndexOutOfBounds(e);
            assertTrue("message should name the missing band: " + e.getMessage(),
                    e.getMessage().contains("i_IW1_VH_ref_01Jan2020"));
        }
    }

    /**
     * Consumers read dataBuffers[0..3] unconditionally, so an acquisition that resolves to fewer
     * than four complex bands has to fail here with a message rather than out of bounds later.
     */
    @Test
    public void testGetSourceBandsIncompleteDualPolIsReported() {
        final Product stack = createStackShell(
                "i_IW1_VV_ref_01Jan2020 q_IW1_VV_ref_01Jan2020", null);
        addComplexPair(stack, "i_IW1_VV_ref_01Jan2020", "q_IW1_VV_ref_01Jan2020");

        try {
            PolBandUtils.getSourceBands(stack, PolBandUtils.MATRIX.DUAL_VH_VV);
            fail("expected the incomplete dual-pol acquisition to be reported");
        } catch (Exception e) {
            assertNotIndexOutOfBounds(e);
        }
    }

    /**
     * getProductBands fills the array consumers read positionally, so it must place each band at
     * the slot of the element it matches - not at a running counter over whatever order the stack
     * metadata happens to list. A stack written with C12_real first would otherwise assemble the
     * covariance matrix with C11 taken from C12_real, silently and with the count check satisfied.
     */
    @Test
    public void testGetSourceBandsC2IsOrderedByMatrixElement() throws Exception {
        final Product stack = createStackShell(
                "C12_real_ref_01Jan2020 C12_imag_ref_01Jan2020 C11_ref_01Jan2020 C22_ref_01Jan2020", null);
        for (String name : new String[]{"C11_ref_01Jan2020", "C12_real_ref_01Jan2020",
                "C12_imag_ref_01Jan2020", "C22_ref_01Jan2020"}) {
            final Band band = new Band(name, ProductData.TYPE_FLOAT32, 4, 4);
            band.setUnit(name.contains("_real") ? Unit.REAL : name.contains("_imag") ? Unit.IMAGINARY : Unit.INTENSITY);
            stack.addBand(band);
        }

        final PolBandUtils.PolSourceBand[] srcBandList =
                PolBandUtils.getSourceBands(stack, PolBandUtils.MATRIX.C2);

        assertArrayEquals(new String[]{"C11_ref_01Jan2020", "C12_real_ref_01Jan2020",
                        "C12_imag_ref_01Jan2020", "C22_ref_01Jan2020"},
                namesOf(srcBandList[0].srcBands));
    }

    // --- round 3: duplicate elements, band count, i/q ordering, quad-pol ---

    private static void addMatrixBand(final Product product, final String name) {
        final Band band = new Band(name, ProductData.TYPE_FLOAT32, 4, 4);
        band.setUnit(name.contains("_real") ? Unit.REAL : name.contains("_imag") ? Unit.IMAGINARY : Unit.INTENSITY);
        product.addBand(band);
    }

    /**
     * Two acquisitions' worth of matrix bands reaching the single-acquisition path (a stack whose
     * coregistered_stack flag was lost) must be reported. Slotting each band by the element it
     * matches makes the second date's bands land on already-filled slots; ignoring them would
     * return date 1 alone and label it as the whole product.
     */
    @Test
    public void testGetSourceBandsDuplicateMatrixElementIsReported() {
        final Product product = new Product("unflagged", "SLC", 4, 4);
        for (String suffix : new String[]{"_ref_01Jan2020", "_sec_02Jan2020"}) {
            for (String elem : PolBandUtils.getC2BandNames()) {
                addMatrixBand(product, elem + suffix);
            }
        }
        AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());

        try {
            PolBandUtils.getSourceBands(product, PolBandUtils.MATRIX.C2);
            fail("expected the duplicated matrix element to be reported");
        } catch (Exception e) {
            assertNotIndexOutOfBounds(e);
            assertTrue("message should name the duplicated element: " + e.getMessage(),
                    e.getMessage().contains("C11"));
        }
    }

    /**
     * An unsplit multi-swath dual-pol product resolves to twelve complex bands. Consumers read
     * dataBuffers[0..3], so this has to be reported rather than quietly correlating IW1 against IW2.
     */
    @Test
    public void testGetSourceBandsTooManyDualPolBandsIsReported() {
        final Product product = new Product("unsplit", "SLC", 4, 4);
        for (String swath : new String[]{"IW1", "IW2", "IW3"}) {
            addComplexPair(product, "i_" + swath + "_VV", "q_" + swath + "_VV");
            addComplexPair(product, "i_" + swath + "_VH", "q_" + swath + "_VH");
        }
        AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());

        try {
            PolBandUtils.getSourceBands(product, PolBandUtils.MATRIX.DUAL_VH_VV);
            fail("expected the multi-swath band count to be reported");
        } catch (Exception e) {
            assertNotIndexOutOfBounds(e);
        }
    }

    /**
     * getScatterVector takes srcBands[0] as the real part and srcBands[1] as the imaginary part, so
     * the pair must be ordered by unit. Taking them in encounter order silently conjugates C12 for
     * any product that happens to list q before i.
     */
    @Test
    public void testGetSourceBandsDualPolOrdersPairByUnit() throws Exception {
        final Product product = new Product("reversed", "SLC", 4, 4);
        addComplexPair(product, "i_IW1_VV", "q_IW1_VV");
        addComplexPair(product, "i_IW1_VH", "q_IW1_VH");
        // re-list so that the imaginary band of each polarisation comes first
        product.removeBand(product.getBand("i_IW1_VV"));
        product.removeBand(product.getBand("i_IW1_VH"));
        final Band iVV = new Band("i_IW1_VV", ProductData.TYPE_FLOAT32, 4, 4);
        iVV.setUnit(Unit.REAL);
        product.addBand(iVV);
        final Band iVH = new Band("i_IW1_VH", ProductData.TYPE_FLOAT32, 4, 4);
        iVH.setUnit(Unit.REAL);
        product.addBand(iVH);
        AbstractMetadata.addAbstractedMetadataHeader(product.getMetadataRoot());

        final PolBandUtils.PolSourceBand[] srcBandList =
                PolBandUtils.getSourceBands(product, PolBandUtils.MATRIX.DUAL_VH_VV);

        assertArrayEquals(new String[]{"i_IW1_VV", "q_IW1_VV", "i_IW1_VH", "q_IW1_VH"},
                namesOf(srcBandList[0].srcBands));
    }

    /**
     * The quad-pol path must name a band listed in the stack metadata but absent from the product,
     * the way the dual-pol and matrix paths do, instead of dereferencing null.
     */
    @Test
    public void testGetSourceBandsQuadPolMissingBandIsReported() {
        final Product stack = createStackShell(
                "i_HH_ref_01Jan2020 q_HH_ref_01Jan2020 i_HV_ref_01Jan2020 q_HV_ref_01Jan2020 " +
                        "i_VH_ref_01Jan2020 q_VH_ref_01Jan2020 i_VV_ref_01Jan2020 q_VV_ref_01Jan2020", null);
        addComplexPair(stack, "i_HH_ref_01Jan2020", "q_HH_ref_01Jan2020");
        // the HV / VH / VV bands are deliberately absent
        AbstractMetadata.getAbstractedMetadata(stack)
                .setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");

        try {
            PolBandUtils.getSourceBands(stack, PolBandUtils.MATRIX.FULL);
            fail("expected the missing quad-pol band to be reported");
        } catch (Exception e) {
            assertFalse("raw NullPointerException: " + e.getMessage(), e instanceof NullPointerException);
            assertNotNull(e.getMessage());
            assertTrue("message should name the missing band: " + e.getMessage(),
                    e.getMessage().contains("i_HV_ref_01Jan2020"));
        }
    }
}
