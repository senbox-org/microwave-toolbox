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

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.insar.gpf.coregistration.DEMAssistedCoregistrationOp;
import eu.esa.sar.teststacks.StackTest;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import static org.junit.Assume.assumeTrue;

@RunWith(LongTestRunner.class)
public class TestDemAssistedCoregistrationStack extends StackTest {

    private final static File asarSantoriniFolder = new File(TestData.inputSAR + "ASAR/Santorini");

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(asarSantoriniFolder + " not found", asarSantoriniFolder.exists());
    }

    @Test
    public void testStack1() throws Exception {
        final List<Product> products = readProducts(asarSantoriniFolder);

        DEMAssistedCoregistrationOp demAssistedCoregistration = new DEMAssistedCoregistrationOp();
        int cnt = 0;
        for(Product product : products) {
            demAssistedCoregistration.setSourceProduct("input"+cnt, product);
            ++cnt;
        }

        Product trgProduct = demAssistedCoregistration.getTargetProduct();

        File tmpFolder = createTmpFolder("stack1");
        ProductIO.writeProduct(trgProduct, new File(tmpFolder,"stack.dim"), "BEAM-DIMAP", true);

        trgProduct.close();
        closeProducts(products);
        delete(tmpFolder);
    }
}
