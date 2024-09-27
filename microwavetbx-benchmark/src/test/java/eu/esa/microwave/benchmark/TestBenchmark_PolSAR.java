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
import org.csa.rstb.polarimetric.gpf.PolarimetricDecompositionOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.Node;
import org.esa.snap.core.gpf.graph.NodeSource;
import org.junit.Test;

import java.io.File;

public class TestBenchmark_PolSAR extends BaseBenchmarks {

    public TestBenchmark_PolSAR() {
        super("PolSAR");
    }

    @Test
    public void testQP_decomposition_pauli_productIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Pauli Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_pauli_writeOp() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Pauli Decomposition", null, WriteMode.GPF);
    }

    @Test
    public void testQP_decomposition_pauli_graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Pauli Decomposition", null, WriteMode.GRAPH);
    }

    @Test
    public void testQP_decomposition_sinclair() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Sinclair Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_FreemanDurden() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Freeman-Durden Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_GeneralizedFreemanDurden() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Generalized Freeman-Durden Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_Yamaguchi() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Yamaguchi Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_vanZyl() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"van Zyl Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_Cloude() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Cloude Decomposition", null, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_Touzi() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile,"Touzi Decomposition", "outputTouziParamSet0", WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_decomposition_HAAlphaQuadPol() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        decomposition(qpFile, "H-A-Alpha Quad Pol Decomposition", "outputHAAlpha", WriteMode.PRODUCT_IO);
    }

    private void decomposition(final File srcFile, final String decompName, final String param, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName,testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        process(srcFile, decompName, param, outputFolder, mode);
                        break;
                    case GRAPH:
                        processGraph(srcFile, outputFolder, decompName, param);
                        break;
                }
            }
        };
        b.run();
    }

    private void process(final File srcFile, final String decompName, final String param,
                         final File outputFolder, final WriteMode mode) throws Exception {
        final Product srcProduct = read(srcFile);

        final PolarimetricDecompositionOp op = new PolarimetricDecompositionOp();
        op.setSourceProduct(srcProduct);
        op.setParameter("decomposition", decompName);
        if(param != null) {
            op.setParameter(param, true);
        }
        Product trgProduct = op.getTargetProduct();

        write(trgProduct, outputFolder, mode);

        trgProduct.dispose();
        srcProduct.dispose();
    }

    private void processGraph(final File file, final File outputFolder, final String decompName, final String param) throws Exception {

        final Graph graph = new Graph("graph");

        final Node readNode = new Node("read", "read");
        final DomElement readParameters = new DefaultDomElement("parameters");
        readParameters.createChild("file").setValue(file.getAbsolutePath());
        readNode.setConfiguration(readParameters);
        graph.addNode(readNode);

        final Node decompNode = new Node("Polarimetric-Decomposition", "Polarimetric-Decomposition");
        final DomElement decompParameters = new DefaultDomElement("parameters");
        decompParameters.createChild("decomposition").setValue(decompName);
        if(param != null) {
            decompParameters.createChild(param).setValue("true");
        }
        decompNode.setConfiguration(decompParameters);
        decompNode.addSource(new NodeSource("source", "read"));
        graph.addNode(decompNode);

        final Node writeNode = new Node("write", "write");
        final DomElement writeParameters = new DefaultDomElement("parameters");
        final File outFile = new File(outputFolder, file.getName());
        writeParameters.createChild("file").setValue(outFile.getAbsolutePath());
        writeNode.setConfiguration(writeParameters);
        writeNode.addSource(new NodeSource("source", "Polarimetric-Decomposition"));
        graph.addNode(writeNode);

        processGraph(graph);
    }
}
