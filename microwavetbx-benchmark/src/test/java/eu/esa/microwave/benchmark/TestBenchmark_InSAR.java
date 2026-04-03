/*
 * Copyright (C) 2024 SkyWatch Space Applications Inc. https://www.skywatch.com
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

import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sentinel1.gpf.BackGeocodingOp;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.GraphIO;
import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;

public class TestBenchmark_InSAR extends BaseBenchmarks {

    public TestBenchmark_InSAR() {
        super("InSAR_BackGeoCoding");
    }

    // Split -> ApplyOrbit -> BackGeoCoding

    @Test
    public void testInSAR_BackGeoCoding_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        backGeoCoding(slcInSAR1, slcInSAR2, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testInSAR_BackGeoCoding_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        backGeoCoding(slcInSAR1, slcInSAR2, WriteMode.GPF);
    }

    @Test
    public void testInSAR_BackGeoCoding_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        backGeoCoding(slcInSAR1, slcInSAR2, WriteMode.GRAPH);
    }



    private void backGeoCoding(final File srcFile1, final File srcFile2, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        processStack(srcFile1, srcFile2, outputFolder, mode);
                        break;
                    case GRAPH:
                        processStackGraph(new File[] {srcFile1, srcFile2}, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void processStack(final File srcFile1, final File srcFile2,
                              final File outputFolder, final WriteMode mode) throws Exception {
        try(final Product srcProduct1 = read(srcFile1)) {
            try(final Product srcProduct2 = read(srcFile2)) {

                TOPSARSplitOp splitOp1 = new TOPSARSplitOp();
                splitOp1.setSourceProduct(srcProduct1);
                splitOp1.setParameter("subswath", "IW1");
                splitOp1.setParameter("selectedPolarisations", "VV");

                TOPSARSplitOp splitOp2 = new TOPSARSplitOp();
                splitOp2.setSourceProduct(srcProduct2);
                splitOp2.setParameter("subswath", "IW1");
                splitOp2.setParameter("selectedPolarisations", "VV");

                ApplyOrbitFileOp applyOrbitOp1 = new ApplyOrbitFileOp();
                applyOrbitOp1.setSourceProduct(splitOp1.getTargetProduct());

                ApplyOrbitFileOp applyOrbitOp2 = new ApplyOrbitFileOp();
                applyOrbitOp2.setSourceProduct(splitOp2.getTargetProduct());

                BackGeocodingOp backGeoOp = new BackGeocodingOp();
                backGeoOp.setSourceProducts(applyOrbitOp1.getTargetProduct(), applyOrbitOp2.getTargetProduct());

                Product trgProduct = backGeoOp.getTargetProduct();

                write(trgProduct, outputFolder, mode);

                trgProduct.dispose();
                srcProduct1.dispose();
                srcProduct2.dispose();
            }
        }
    }

    private void processStackGraph(final File[] srcFiles, final File outputFile) throws Exception {
        final String graphPath = getTestFilePath("/eu/esa/microwave/benchmark/graphs/Sentinel1-TOPS-Coregistration.xml");

        try (Reader fileReader = new FileReader(graphPath)) {
            Graph graph = GraphIO.read(fileReader);

            setIO(graph, srcFiles, outputFile, "BEAM-DIMAP");

            processGraph(graph);
        }
    }
}
