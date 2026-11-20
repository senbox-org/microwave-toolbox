/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.orbits.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.dataio.envisat.EnvisatOrbitReader;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * Created by IntelliJ IDEA.
 * User: lveci
 * Date: Sep 4, 2008
 * To change this template use File | Settings | File Templates.
 */
public class TestApplyOrbitFile {

    @Before
    public void setUp() {
        TestUtils.initTestEnvironment();
        // If any of the file does not exist: the test will be ignored
        assumeTrue(TestData.inputS1_GRDSubset + " not found", TestData.inputS1_GRDSubset.exists());
    }

    @Test
    public void testOpenFile() {

        EnvisatOrbitReader reader = new EnvisatOrbitReader();
        assertNotNull(reader);
    }

    @Test
    public void testApplyOrbit_S1() throws Exception {
        try(final Product srcProduct = ProductIO.readProduct(TestData.inputS1_GRDSubset)) {

            ApplyOrbitFileOp op = new ApplyOrbitFileOp();
            op.setSourceProduct(srcProduct);
            Product trgProduct = op.getTargetProduct();
            trgProduct.close();
        }
    }

    @Test
    public void testApplyOrbit_ASAR_WSM() throws Exception {
        try(final Product srcProduct = ProductIO.readProduct(TestData.inputASAR_WSM)) {

            ApplyOrbitFileOp op = new ApplyOrbitFileOp();
            op.setSourceProduct(srcProduct);
            Product trgProduct = op.getTargetProduct();
            trgProduct.close();
        }
    }

    @Test
    public void testApplyOrbit_ASAR_IMS() throws Exception {
        try(final Product srcProduct = ProductIO.readProduct(TestData.inputASAR_IMS)) {

            ApplyOrbitFileOp op = new ApplyOrbitFileOp();
            op.setSourceProduct(srcProduct);
            Product trgProduct = op.getTargetProduct();
            trgProduct.close();
        }
    }

    /**
     * GPF initializes every node of a graph before it executes any of them, and a downstream operator
     * (Subset, Calibration, TOPSAR-Split, ...) copies its source's metadata and tie-point grids in its own
     * initialize(). The orbit therefore has to be applied by the time getTargetProduct() returns; an
     * update made later in doExecute() leaves the downstream copy with the original orbit and geocoding.
     */
    @Test
    public void testOrbitAppliedBeforeDownstreamCopy_ASAR_IMS() throws Exception {
        assumeTrue(TestData.inputASAR_IMS + " not found", TestData.inputASAR_IMS.exists());
        try (final Product srcProduct = ProductIO.readProduct(TestData.inputASAR_IMS)) {
            final String srcOrbitFile = AbstractMetadata.getAbstractedMetadata(srcProduct)
                    .getAttributeString(AbstractMetadata.orbit_state_vector_file, "");

            final ApplyOrbitFileOp op = new ApplyOrbitFileOp();
            op.setSourceProduct(srcProduct);
            final Product trgProduct;
            try {
                trgProduct = op.getTargetProduct();
            } catch (OperatorException e) {
                assumeNoException("orbit file not available", e);
                return;
            }

            // what a downstream operator copies during graph initialisation
            final Product downstream = new Product("downstream", trgProduct.getProductType(),
                    trgProduct.getSceneRasterWidth(), trgProduct.getSceneRasterHeight());
            ProductUtils.copyMetadata(trgProduct, downstream);
            ProductUtils.copyTiePointGrids(trgProduct, downstream);

            // then GPF executes the graph
            op.execute(ProgressMonitor.NULL);

            final String trgOrbitFile = AbstractMetadata.getAbstractedMetadata(trgProduct)
                    .getAttributeString(AbstractMetadata.orbit_state_vector_file);
            assertNotEquals("orbit not applied", srcOrbitFile, trgOrbitFile);
            assertEquals(trgOrbitFile, AbstractMetadata.getAbstractedMetadata(downstream)
                    .getAttributeString(AbstractMetadata.orbit_state_vector_file));

            for (TiePointGrid trgGrid : trgProduct.getTiePointGrids()) {
                final TiePointGrid copied = downstream.getTiePointGrid(trgGrid.getName());
                assertNotNull(trgGrid.getName(), copied);
                assertEquals(trgGrid.getName(), trgGrid.getGridWidth(), copied.getGridWidth());
                assertEquals(trgGrid.getName(), trgGrid.getGridHeight(), copied.getGridHeight());
                assertArrayEquals(trgGrid.getName(), trgGrid.getTiePoints(), copied.getTiePoints(), 0.0f);
            }
            downstream.dispose();
            trgProduct.close();
        }
    }

    @Test
    public void testApplyOrbit_ERS1() {
        try (final Product srcProduct = ProductIO.readProduct(TestData.inputERS_IMP)) {
            // http://step.esa.int/auxdata/orbits/ers_precise_orb/ERS1/1997.zip does not exist

            ApplyOrbitFileOp op = new ApplyOrbitFileOp();
            op.setSourceProduct(srcProduct);
            Product trgProduct = op.getTargetProduct();
            trgProduct.close();
        } catch (Exception e) {
            assertTrue(e.getMessage().startsWith("Unable to find suitable orbit file"));
        }
    }
}
