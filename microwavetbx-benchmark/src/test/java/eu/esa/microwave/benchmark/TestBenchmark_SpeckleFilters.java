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

import com.bc.ceres.binding.dom.DefaultDomElement;
import com.bc.ceres.binding.dom.DomElement;
import eu.esa.sar.sar.gpf.filtering.SpeckleFilterOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.Node;
import org.esa.snap.core.gpf.graph.NodeSource;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class TestBenchmark_SpeckleFilters extends BaseBenchmarks {

    public TestBenchmark_SpeckleFilters() {
        super("SpeckleFilters");
    }

    @Test
    public void testGRD_specklefilter_Boxcar_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Boxcar", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_BoxcarWriteOp() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Boxcar", WriteMode.GPF);
    }

    @Test
    public void testGRD_specklefilter_BoxcarGraph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Boxcar", WriteMode.GRAPH);
    }

    @Test
    public void testGRD_specklefilter_Median() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Median", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_Frost() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Frost", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_GammaMap() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Gamma Map", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_Lee() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Lee", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_RefinedLee() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Refined Lee", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_LeeSigma() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "Lee Sigma", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_specklefilter_IDAN() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        specklefilter(grdFile, "IDAN", WriteMode.PRODUCT_IO);
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
                        processGraph(srcFile, outputFolder, filterName);
                        break;
                }
            }
        };
        b.run();
    }

    private void process(final File srcFile, final String filterName, final File outputFolder,
                         final WriteMode mode) throws IOException {
        final Product srcProduct = read(srcFile);

        SpeckleFilterOp op = new SpeckleFilterOp();
        op.setSourceProduct(srcProduct);
        op.SetFilter(filterName);
        Product trgProduct = op.getTargetProduct();

        write(trgProduct, outputFolder, mode);

        trgProduct.dispose();
        srcProduct.dispose();
    }

    private void processGraph(final File file, final File outputFolder, final String filterName) throws Exception {

        final Graph graph = new Graph("graph");

        final Node readNode = new Node("read", "read");
        final DomElement readParameters = new DefaultDomElement("parameters");
        readParameters.createChild("file").setValue(file.getAbsolutePath());
        readNode.setConfiguration(readParameters);
        graph.addNode(readNode);

        final Node decompNode = new Node("Speckle-Filter", "Speckle-Filter");
        final DomElement decompParameters = new DefaultDomElement("parameters");
        decompParameters.createChild("filter").setValue(filterName);

        decompNode.setConfiguration(decompParameters);
        decompNode.addSource(new NodeSource("source", "read"));
        graph.addNode(decompNode);

        final Node writeNode = new Node("write", "write");
        final DomElement writeParameters = new DefaultDomElement("parameters");
        final File outFile = new File(outputFolder, file.getName());
        writeParameters.createChild("file").setValue(outFile.getAbsolutePath());
        writeNode.setConfiguration(writeParameters);
        writeNode.addSource(new NodeSource("source", "Speckle-Filter"));
        graph.addNode(writeNode);

        processGraph(graph);
    }
}
