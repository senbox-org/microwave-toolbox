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
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sentinel1.gpf.S1ETADCorrectionOp;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.GraphProcessor;
import org.esa.snap.core.gpf.graph.Node;
import org.esa.snap.core.gpf.graph.NodeSource;
import org.junit.Test;

import java.io.File;

public class TestBenchmark_ETAD extends BaseBenchmarks {

    protected enum ProcessMode {SPLIT_ORBIT, SPLIT_ORBIT_ETAD}

    public TestBenchmark_ETAD() {
        super("ETAD");
    }

    // Split -> ApplyOrbit

    @Test
    public void testInSAR_Split_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        etad(slcInSAR1, etadInSAR1, ProcessMode.SPLIT_ORBIT, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testInSAR_Split_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        etad(slcInSAR1, etadInSAR1, ProcessMode.SPLIT_ORBIT, WriteMode.GPF);
    }

    @Test
    public void testInSAR_Split_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        etad(slcInSAR1, etadInSAR1, ProcessMode.SPLIT_ORBIT, WriteMode.GRAPH);
    }

    // Split -> ApplyOrbit -> ETAD

    @Test
    public void testInSAR_ETAD_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        etad(slcInSAR1, etadInSAR1, ProcessMode.SPLIT_ORBIT_ETAD, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testInSAR_ETAD_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        etad(slcInSAR1, etadInSAR1, ProcessMode.SPLIT_ORBIT_ETAD, WriteMode.GPF);
    }

    @Test
    public void testInSAR_ETAD_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        etad(slcInSAR1, etadInSAR1, ProcessMode.SPLIT_ORBIT_ETAD, WriteMode.GRAPH);
    }

    @Test
    public void testInSAR_Coregister_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        coregister(slcInSAR1, slcInSAR2, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testInSAR_Coregister_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        coregister(slcInSAR1, slcInSAR2, WriteMode.GPF);
    }

    @Test
    public void testInSAR_Coregister_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        coregister(slcInSAR1, slcInSAR2, WriteMode.GRAPH);
    }




    private void etad(final File srcFile1, final File etadFile, final ProcessMode processMode, final WriteMode writeMode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (writeMode) {
                    case PRODUCT_IO:
                    case GPF:
                        processETAD(srcFile1, etadFile, outputFolder, processMode, writeMode);
                        break;
                    case GRAPH:
                        processETADGraph(srcFile1, etadFile, processMode, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void processETAD(final File file1, final File etadFile,
                             final File outputFolder,
                             final ProcessMode processMode, final WriteMode mode) throws Exception {
        final Product srcProduct = read(file1);
        Product trgProduct;

        TOPSARSplitOp splitOp = new TOPSARSplitOp();
        splitOp.setSourceProduct(srcProduct);
        splitOp.setParameter("subswath", "IW1");
        splitOp.setParameter("selectedPolarisations", "VV");

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(splitOp.getTargetProduct());

        if(processMode.equals(ProcessMode.SPLIT_ORBIT_ETAD)) {
            S1ETADCorrectionOp etadOp = new S1ETADCorrectionOp();
            etadOp.setSourceProduct(applyOrbitOp.getTargetProduct());
            etadOp.setParameter("etadFile", etadFile);
            trgProduct = etadOp.getTargetProduct();
        } else {
            trgProduct = applyOrbitOp.getTargetProduct();
        }

        write(trgProduct, outputFolder, mode);

        trgProduct.dispose();
        srcProduct.dispose();
    }

    private void processETADGraph(final File file1, final File etadFile,
                                  final ProcessMode processMode, final File outputFolder) throws Exception {

        final Graph graph = new Graph("graph");

        final Node readNode = new Node("read", "read");
        final DomElement readParameters = new DefaultDomElement("parameters");
        readParameters.createChild("file").setValue(file1.getAbsolutePath());
        readNode.setConfiguration(readParameters);
        graph.addNode(readNode);

        final Node splitNode = new Node("TOPSAR-Split", "TOPSAR-Split");
        splitNode.addSource(new NodeSource("source", "read"));
        final DomElement splitParameters = new DefaultDomElement("parameters");
        splitParameters.createChild("subswath").setValue("IW1");
        splitParameters.createChild("selectedPolarisations").setValue("VV");
        graph.addNode(splitNode);

        final Node applyOrbitNode = new Node("Apply-Orbit-File", "Apply-Orbit-File");
        applyOrbitNode.addSource(new NodeSource("source", "TOPSAR-Split"));
        graph.addNode(applyOrbitNode);

        if(processMode.equals(ProcessMode.SPLIT_ORBIT_ETAD)) {
            final Node etadNode = new Node("S1-ETAD-Correction", "S1-ETAD-Correction");
            etadNode.addSource(new NodeSource("source", "Apply-Orbit-File"));
            final DomElement etadParameters = new DefaultDomElement("parameters");
            etadParameters.createChild("etadFile").setValue(etadFile.getAbsolutePath());
            graph.addNode(etadNode);
        }

        final Node writeNode = new Node("write", "write");
        final DomElement writeParameters = new DefaultDomElement("parameters");
        final File outFile = new File(outputFolder, file1.getName());
        writeParameters.createChild("file").setValue(outFile.getAbsolutePath());
        writeNode.setConfiguration(writeParameters);

        if(processMode.equals(ProcessMode.SPLIT_ORBIT_ETAD)) {
            writeNode.addSource(new NodeSource("source", "S1-ETAD-Correction"));
        } else {
            writeNode.addSource(new NodeSource("source", "Apply-Orbit-File"));
        }
        graph.addNode(writeNode);

        final GraphProcessor processor = new GraphProcessor();
        processor.executeGraph(graph, ProgressMonitor.NULL);
    }

    private void coregister(final File srcFile1, final File srcFile2, final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        process(srcFile1, srcFile2, outputFolder, mode);
                        break;
                    case GRAPH:
                        processGraph(srcFile1, srcFile2, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void process(final File file1, final File file2, final File outputFolder, final WriteMode mode) throws Exception {
        final Product srcProduct = read(file1);

        TOPSARSplitOp splitOp = new TOPSARSplitOp();
        splitOp.setSourceProduct(srcProduct);
        splitOp.setParameter("subswath", "IW1");
        splitOp.setParameter("selectedPolarisations", "VV");

        ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
        applyOrbitOp.setSourceProduct(splitOp.getTargetProduct());

        S1ETADCorrectionOp etadOp = new S1ETADCorrectionOp();
        etadOp.setSourceProduct(applyOrbitOp.getTargetProduct());
        Product trgProduct = etadOp.getTargetProduct();

        write(trgProduct, outputFolder, mode);

        trgProduct.dispose();
        srcProduct.dispose();
    }

    private void processGraph(final File file1, final File file2, final File outputFolder) throws Exception {

        final Graph graph = new Graph("graph");

        final Node readNode = new Node("read", "read");
        final DomElement readParameters = new DefaultDomElement("parameters");
        readParameters.createChild("file").setValue(file1.getAbsolutePath());
        readNode.setConfiguration(readParameters);
        graph.addNode(readNode);

        final Node decompNode = new Node("Calibration", "Calibration");
        decompNode.addSource(new NodeSource("source", "read"));
        graph.addNode(decompNode);

        final Node writeNode = new Node("write", "write");
        final DomElement writeParameters = new DefaultDomElement("parameters");
        final File outFile = new File(outputFolder, file1.getName());
        writeParameters.createChild("file").setValue(outFile.getAbsolutePath());
        writeNode.setConfiguration(writeParameters);
        writeNode.addSource(new NodeSource("source", "Calibration"));
        graph.addNode(writeNode);

        final GraphProcessor processor = new GraphProcessor();
        processor.executeGraph(graph, ProgressMonitor.NULL);
    }
}
