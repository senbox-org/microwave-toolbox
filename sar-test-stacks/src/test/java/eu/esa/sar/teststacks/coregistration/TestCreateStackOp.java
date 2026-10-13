/*
 * Copyright (C) 2025 SkyWatch. https://www.skywatch.com
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
package eu.esa.sar.teststacks.coregistration;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.insar.gpf.coregistration.CreateStackOp;
import eu.esa.sar.teststacks.StackTest;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import com.bc.ceres.test.LongTestRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Unit test for CreateStackOp.
 */
@RunWith(LongTestRunner.class)
public class TestCreateStackOp extends StackTest {

    private final static File asarBamFile1 = new File(TestData.inputSAR + "ASAR/Bam/ASA_IMS_1PNUPA20031203_061259_000000162022_00120_09192_0099.N1");
    private final static File asarBamFile2 = new File(TestData.inputSAR + "ASAR/Bam/ASA_IMS_1PXPDE20040211_061300_000000142024_00120_10194_0013.N1");
    private final static File asarSantoriniFolder = new File(TestData.inputSAR + "ASAR/Santorini");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(asarBamFile1 + " not found", asarBamFile1.exists());
        assumeTrue(asarBamFile2 + " not found", asarBamFile2.exists());
        assumeTrue(asarSantoriniFolder + " not found", asarSantoriniFolder.exists());
    }

    @Test
    public void testForumIssue() throws IOException {

        Product[] products = new Product[2];
        products[0] = ProductIO.readProduct(asarBamFile1);
        products[1] = ProductIO.readProduct(asarBamFile2);
        assertNotNull(products[0]);
        assertNotNull(products[1]);

        GPF.getDefaultInstance().getOperatorSpiRegistry().loadOperatorSpis();

        final HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("extent","Master");
        parameters.put("initialOffsetMethod","Product Geolocation");
        parameters.put("masterBands",products[0].getBandAt(0).getName()+","+products[0].getBandAt(1).getName());
        parameters.put("resamplingType","NEAREST_NEIGHBOUR");
        parameters.put("sourceBands",products[1].getBandAt(0).getName()+","+products[1].getBandAt(1).getName());

        System.out.println("Creating stack...");
        Product outProduct = GPF.createProduct("CreateStack", parameters, products);

        File tmpFolder = createTmpFolder("stacks");
        ProductIO.writeProduct(outProduct, new File(tmpFolder,"target.dim"), "BEAM-DIMAP", true);

        outProduct.dispose();
        delete(tmpFolder);
    }

    @Test
    public void testStack1() throws Exception {
        final List<Product> products = readProducts(asarSantoriniFolder);

        CreateStackOp createStack = new CreateStackOp();
        int cnt = 0;
        for(Product product : products) {
            createStack.setSourceProduct("input"+cnt, product);
            ++cnt;
        }

        Product prod = createStack.getTargetProduct();

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateBands(new String[] {
                "i_VV_ref_25Feb2004", "q_VV_ref_25Feb2004",
                "i_VV_sec1_31Mar2004", "q_VV_sec1_31Mar2004",
                "i_VV_sec2_14Jul2004", "q_VV_sec2_14Jul2004",
                "i_VV_sec3_27Oct2004", "q_VV_sec3_27Oct2004"
        });

        File tmpFolder = createTmpFolder("stack1");
        ProductIO.writeProduct(prod, new File(tmpFolder,"stack.dim"), "BEAM-DIMAP", true);

        prod.close();
        closeProducts(products);
        delete(tmpFolder);
    }

    /**
     * Orbit initial-offset path on real ASAR orbits, with every acquisition made dual-pol (a VH pair
     * sharing the VV data) so each secondary carries two _secN tags, as an RCM CH/CV or S1 SM VV/VH
     * stack does. Every tag needs exactly one init_offsets entry holding its own product's offsets:
     * the orbit path used to keep only the last band's entry (the first pol's tag silently missing).
     */
    @Test
    public void testOrbitOffsets_DualPol_OneEntryPerSecondaryTag() throws Exception {
        final List<Product> products = readProducts(asarSantoriniFolder);
        for (Product product : products) {
            addVHPair(product);
        }

        final CreateStackOp createStack = new CreateStackOp();
        int cnt = 0;
        for (Product product : products) {
            createStack.setSourceProduct("input" + cnt, product);
            ++cnt;
        }
        final Product stack = createStack.getTargetProduct();

        final MetadataElement[] all = AbstractMetadata.getAbstractedMetadata(stack).getElements();
        int orbitOffsetsCount = 0;
        for (MetadataElement e : all) {
            if (e.getName().equals("Orbit_Offsets")) {
                orbitOffsetsCount++;
            }
        }
        assertEquals("exactly one Orbit_Offsets element", 1, orbitOffsetsCount);
        final MetadataElement orbitOffsets = AbstractMetadata.getAbstractedMetadata(stack).getElement("Orbit_Offsets");

        // tag -> date, from the stack's own secondary bands
        final Map<String, String> tagDates = new TreeMap<>();
        for (String name : stack.getBandNames()) {
            if (name.contains(StackUtils.SEC)) {
                final String suffix = StackUtils.getBandSuffix(name);
                tagDates.put(suffix, suffix.substring(suffix.lastIndexOf('_') + 1));
            }
        }
        assertEquals("3 secondaries x 2 pols", 6, tagDates.size());

        final Set<String> seen = new HashSet<>();
        for (MetadataElement e : orbitOffsets.getElements()) {
            assertTrue("duplicate " + e.getName(), seen.add(e.getName()));
        }
        final Map<String, String> offsetsByDate = new HashMap<>();
        for (Map.Entry<String, String> tag : tagDates.entrySet()) {
            final MetadataElement e = orbitOffsets.getElement("init_offsets" + tag.getKey());
            assertNotNull("init_offsets" + tag.getKey() + " missing; have " + seen, e);
            final String xy = e.getAttributeInt("init_offset_X") + "," + e.getAttributeInt("init_offset_Y");
            // both pols of one acquisition must carry that acquisition's offsets
            final String prev = offsetsByDate.put(tag.getValue(), xy);
            assertTrue("pols of " + tag.getValue() + " disagree: " + prev + " vs " + xy, prev == null || prev.equals(xy));
        }
        assertEquals("only secondary tags get entries", tagDates.size(), seen.size());

        stack.dispose();
        closeProducts(products);
    }

    /**
     * Appending to an existing stack with the orbit method used to add a second Orbit_Offsets sibling;
     * getElement() returns the first (copied) one, so the appended secondary's offsets were invisible
     * to DemodulateOp and read as {0, 0}.
     */
    @Test
    public void testOrbitOffsets_AppendToStack_SingleElementWithAllSecondaries() throws Exception {
        final List<Product> products = readProducts(asarSantoriniFolder);
        assumeTrue("need 3 Santorini products", products.size() >= 3);

        final CreateStackOp first = new CreateStackOp();
        first.setSourceProduct("input0", products.get(0));
        first.setSourceProduct("input1", products.get(1));
        final Product stack = first.getTargetProduct();
        final int before = AbstractMetadata.getAbstractedMetadata(stack).getElement("Orbit_Offsets").getNumElements();
        assertEquals("one entry for the single-pol secondary", 1, before);

        final CreateStackOp append = new CreateStackOp();
        append.setSourceProduct("input0", stack);
        append.setSourceProduct("input1", products.get(2));
        final Product appended = append.getTargetProduct();

        final MetadataElement abs = AbstractMetadata.getAbstractedMetadata(appended);
        int orbitOffsetsCount = 0;
        for (MetadataElement e : abs.getElements()) {
            if (e.getName().equals("Orbit_Offsets")) {
                orbitOffsetsCount++;
            }
        }
        assertEquals("exactly one Orbit_Offsets element after append", 1, orbitOffsetsCount);

        final Set<String> tags = new TreeSet<>();
        for (String name : appended.getBandNames()) {
            if (name.contains(StackUtils.SEC)) {
                tags.add("init_offsets" + StackUtils.getBandSuffix(name));
            }
        }
        assertEquals("the original and the appended secondary: " + tags, 2, tags.size());
        final MetadataElement orbitOffsets = abs.getElement("Orbit_Offsets");
        for (String tag : tags) {
            assertNotNull(tag + " not visible through getElement(\"Orbit_Offsets\")", orbitOffsets.getElement(tag));
        }

        appended.dispose();
        stack.dispose();
        closeProducts(products);
    }

    /** A second complex pair (VH) sharing the VV data, so the product is dual-pol for stacking purposes. */
    private static void addVHPair(final Product product) {
        for (String part : new String[]{"i", "q"}) {
            final Band vv = product.getBand(part + "_VV");
            assertNotNull(part + "_VV missing in " + product.getName(), vv);
            final Band vh = new Band(part + "_VH", vv.getDataType(), vv.getRasterWidth(), vv.getRasterHeight());
            vh.setUnit(vv.getUnit());
            vh.setSourceImage(vv.getSourceImage());
            product.addBand(vh);
        }
    }
}
