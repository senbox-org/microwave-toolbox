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

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link MultiMasterOp}.
 */
public class TestMultiMasterOp {

    private static final String[] DATES = {"12Jan2020", "24Jan2020", "05Feb2020"};

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void testSpiCreatesOperator() {
        final MultiMasterOp op = (MultiMasterOp) new MultiMasterOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = MultiMasterOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("MultiMasterStackGenerator", md.alias());
    }

    @Test
    public void blankOutputFolderIsRejected() throws Exception {
        for (String folder : new String[]{null, "", "   "}) {
            final MultiMasterOp op = createOp(buildStack(), folder);
            try {
                op.getTargetProduct();
                fail("expected OperatorException for outputFolder=" + folder);
            } catch (OperatorException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("Output folder must be set"));
            }
        }
    }

    @Test
    public void outputFolderThatIsAFileIsRejected() throws Exception {
        final File file = tmp.newFile("not-a-folder.txt");
        final MultiMasterOp op = createOp(buildStack(), file.getPath());
        try {
            op.getTargetProduct();
            fail("expected OperatorException for a file path");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not a folder"));
        }
    }

    @Test
    public void outputFolderUnderAFileCannotBeCreated() throws Exception {
        final File file = tmp.newFile("blocker");
        final MultiMasterOp op = createOp(buildStack(), new File(file, "pairs").getPath());
        try {
            op.getTargetProduct();
            fail("expected OperatorException when the folder cannot be created");
        } catch (OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("could not be created"));
        }
    }

    @Test
    public void missingOutputFolderIsCreatedAndPairsAreWritten() throws Exception {
        final File folder = new File(tmp.getRoot(), "new/pairs");
        final MultiMasterOp op = createOp(buildStack(), folder.getPath());
        op.getTargetProduct();
        assertTrue("initialize must create the output folder", folder.isDirectory());

        op.execute(ProgressMonitor.NULL);

        final String[] written = folder.list((dir, name) -> name.endsWith(".dim"));
        assertNotNull(written);
        Arrays.sort(written);
        // N = 3 acquisitions -> N-1 = 2 consecutive-date pairs
        assertArrayEquals(new String[]{"SLC_20200112_20200124.dim", "SLC_20200124_20200205.dim"}, written);
    }

    /**
     * Pair products must get copies of the source metadata. addElement takes ownership, so attaching the source's
     * elements re-parented them: the middle date sat in both pairs and the source lost its own Secondary_Metadata.
     */
    @Test
    public void pairsDoNotTakeOverSourceMetadata() throws Exception {
        final Product stack = buildStack();
        final MetadataElement root = stack.getMetadataRoot();
        final MetadataElement secRoot = root.getElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        final MetadataElement absRoot = root.getElement(AbstractMetadata.ABSTRACT_METADATA_ROOT);
        final MetadataElement original = AbstractMetadata.addOriginalProductMetadata(root);

        original.addElement(new MetadataElement("annotation"));

        final File folder = tmp.newFolder("pairs");
        final MultiMasterOp op = createOp(stack, folder.getPath());
        op.getTargetProduct();
        op.execute(ProgressMonitor.NULL);

        for (int k = 1; k < DATES.length; k++) {
            final MetadataElement sec = secRoot.getElement("secondary_" + DATES[k]);
            assertNotNull(DATES[k] + " left the source's Secondary_Metadata", sec);
            assertSame(secRoot, sec.getParentElement());
        }
        assertSame(root, absRoot.getParentElement());
        assertSame(root, original.getParentElement());

        // and every written pair carries its own copies
        for (String name : new String[]{"SLC_20200112_20200124.dim", "SLC_20200124_20200205.dim"}) {
            final Product pair = org.esa.snap.core.dataio.ProductIO.readProduct(new File(folder, name));
            try {
                final MetadataElement pairRoot = pair.getMetadataRoot();
                assertNotNull(name, pairRoot.getElement("Original_Product_Metadata").getElement("annotation"));
                assertEquals(name, 2, pairRoot.getElement(AbstractMetadata.SECONDARY_METADATA_ROOT).getNumElements());
            } finally {
                pair.dispose();
            }
        }
    }

    /**
     * ThreadRunnable records only Exceptions. A pair whose worker ends in an Error (here raised while the pair is
     * built, as an OutOfMemoryError could be) must still fail the operator, not be reported as success.
     */
    @Test
    public void errorInOnePairIsReported() throws Exception {
        final Product stack = buildStack();
        final String name = "i_VV_sec2_" + DATES[2];
        final org.esa.snap.core.datamodel.Band original = stack.getBand(name);
        stack.removeBand(original);
        final java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        final org.esa.snap.core.datamodel.Band failing = new org.esa.snap.core.datamodel.Band(
                name, original.getDataType(), original.getRasterWidth(), original.getRasterHeight()) {
            @Override
            public com.bc.ceres.multilevel.MultiLevelImage getSourceImage() {
                if (armed.get()) {
                    throw new InternalError("simulated");   // only once the pairs are being built
                }
                return original.getSourceImage();
            }
        };
        failing.setUnit(original.getUnit());
        stack.addBand(failing);
        final MultiMasterOp op = createOp(stack, tmp.newFolder("errors").getPath());
        op.getTargetProduct();
        armed.set(true);
        try {
            op.execute(ProgressMonitor.NULL);
        } catch (Exception e) {
            assertTrue(String.valueOf(e), String.valueOf(e).contains("simulated"));
            return;
        }
        fail("a pair that failed with an Error must not look like success");
    }

    @Test
    public void writeFailureIsReported() throws Exception {
        final File folder = tmp.newFolder("vanishing");
        final MultiMasterOp op = createOp(buildStack(), folder.getPath());
        op.getTargetProduct();
        // the folder is replaced by a file after validation, so every write fails
        assertTrue(folder.delete());
        assertTrue(folder.createNewFile());
        try {
            op.execute(ProgressMonitor.NULL);
            fail("a failed write must not look like success");
        } catch (OperatorException e) {
            assertNotNull("the cause must be kept", e.getCause());
        }
    }

    private static MultiMasterOp createOp(final Product source, final String outputFolder) {
        final MultiMasterOp op = (MultiMasterOp) new MultiMasterOp.Spi().createOperator();
        op.setSourceProduct(source);
        if (outputFolder != null) {
            op.setParameter("outputFolder", outputFolder);
        }
        return op;
    }

    /** Minimal coregistered stack: one reference date and two secondaries, i/q bands per date. */
    private static Product buildStack() throws Exception {
        final int w = 4, h = 4;
        final Product product = TestUtils.createProduct("SLC", w, h);
        final DateFormat parser = new SimpleDateFormat("ddMMMyyyy HH:mm:ss", Locale.ENGLISH);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time,
                ProductData.UTC.create(parser.parse(DATES[0] + " 12:00:00"), 0));

        TestUtils.createBand(product, "i_VV_ref_" + DATES[0], ProductData.TYPE_FLOAT32, Unit.REAL, w, h, true);
        TestUtils.createBand(product, "q_VV_ref_" + DATES[0], ProductData.TYPE_FLOAT32, Unit.IMAGINARY, w, h, true);

        final MetadataElement secRoot = new MetadataElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        product.getMetadataRoot().addElement(secRoot);
        for (int k = 1; k < DATES.length; k++) {
            TestUtils.createBand(product, "i_VV_sec" + k + "_" + DATES[k], ProductData.TYPE_FLOAT32, Unit.REAL, w, h, true);
            TestUtils.createBand(product, "q_VV_sec" + k + "_" + DATES[k], ProductData.TYPE_FLOAT32, Unit.IMAGINARY, w, h, true);
            final MetadataElement secMeta = new MetadataElement("secondary_" + DATES[k]);
            secMeta.setAttributeUTC(AbstractMetadata.first_line_time,
                    ProductData.UTC.create(parser.parse(DATES[k] + " 12:00:00"), 0));
            secRoot.addElement(secMeta);
        }
        return product;
    }
}
