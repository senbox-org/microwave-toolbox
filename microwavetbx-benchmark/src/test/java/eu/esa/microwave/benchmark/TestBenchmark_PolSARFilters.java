/*
 * Copyright (C) 2021 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.microwave.benchmark;

import org.csa.rstb.polarimetric.gpf.PolarimetricSpeckleFilterOp;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestBenchmark_PolSARFilters extends BaseBenchmarks {

    public TestBenchmark_PolSARFilters() {
        super("PolSARFilters");
    }

    @Test
    public void testQP_specklefilter_Boxcar() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(qpFile, "Box Car Filter", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_specklefilter_RefinedLee() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(qpFile, "Refined Lee Filter", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_specklefilter_IDAN() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(qpFile, "IDAN Filter", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_specklefilter_LeeSigma() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(qpFile, "Improved Lee Sigma Filter", WriteMode.PRODUCT_IO);
    }

    private void specklefilter(final File srcFile, final String filterName, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        process(srcFile, filterName, outputFolder, mode);
                        break;
                    case GRAPH:
                        //processGraph(srcFile, outputFolder, filterName);
                        break;
                }
            }
        };
        b.run();
    }

    private void process(final File srcFile, final String filterName, final File outputFolder,
                         final WriteMode mode) throws IOException {
        final Product srcProduct = read(srcFile);

        PolarimetricSpeckleFilterOp op = new PolarimetricSpeckleFilterOp();
        op.setSourceProduct(srcProduct);
        op.SetFilter(filterName);
        Product trgProduct = op.getTargetProduct();

        write(trgProduct, outputFolder, mode);

        trgProduct.dispose();
        srcProduct.dispose();
    }
}
