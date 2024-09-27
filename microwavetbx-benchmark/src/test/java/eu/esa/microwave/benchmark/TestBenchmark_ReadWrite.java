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
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.Node;
import org.esa.snap.core.gpf.graph.NodeSource;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;


public class TestBenchmark_ReadWrite extends BaseBenchmarks {

    public TestBenchmark_ReadWrite() {
        super("ReadWrite");
    }

    // GRD
    @Test
    public void testGRD_read_write_productIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(grdFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRD_read_write_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(grdFile, WriteMode.GPF);
    }

    @Test
    public void testGRD_read_write_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(grdZipFile, WriteMode.GRAPH);
    }

    // GRD ZIP
    @Test
    public void testGRDZIP_read_write_productIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(grdFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testGRDZIP_read_write_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(grdZipFile, WriteMode.GPF);
    }

    @Test
    @Ignore
    public void testGRDZIP_read_write_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(grdZipFile, WriteMode.GRAPH);
    }

    // SLC
    @Test
    public void testSLC_read_write_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(slcFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testSLC_read_write_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(slcFile, WriteMode.GPF);
    }

    @Test
    public void testSLC_read_write_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(slcFile, WriteMode.GRAPH);
    }

    // RS2 QuadPol SLC
    @Test
    public void testQP_read_write_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(qpFile, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testQP_read_write_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(qpFile, WriteMode.GPF);
    }

    @Test
    public void testQP_read_write_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        readWrite(qpFile, WriteMode.GRAPH);
    }

    private void readWrite(File srcFile, WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                        final Product srcProduct = read(srcFile);
                        write(srcProduct, outputFolder, DIMAP);
                        srcProduct.dispose();
                        break;
                    case GPF:
                        final Product srcProductGPF = read(srcFile);
                        writeGPF(srcProductGPF, outputFolder, DIMAP);
                        srcProductGPF.dispose();
                        break;
                    case GRAPH:
                        processReadWriteGraph(srcFile, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void processReadWriteGraph(final File file, final File outputFolder) throws Exception {

        final Graph graph = new Graph("graph");

        final Node readNode = new Node("read", "read");
        final DomElement readParameters = new DefaultDomElement("parameters");
        readParameters.createChild("file").setValue(file.getAbsolutePath());
        readNode.setConfiguration(readParameters);
        graph.addNode(readNode);

        final Node writeNode = new Node("write", "write");
        final DomElement writeParameters = new DefaultDomElement("parameters");
        final File outFile = new File(outputFolder, file.getName());
        writeParameters.createChild("file").setValue(outFile.getAbsolutePath());
        writeNode.setConfiguration(writeParameters);
        writeNode.addSource(new NodeSource("source", "read"));
        graph.addNode(writeNode);

        processGraph(graph);
    }
}
