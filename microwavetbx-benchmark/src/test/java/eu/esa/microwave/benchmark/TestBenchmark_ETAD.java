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

import com.bc.ceres.binding.dom.DefaultDomElement;
import com.bc.ceres.binding.dom.DomElement;
import eu.esa.sar.orbits.gpf.ApplyOrbitFileOp;
import eu.esa.sar.sentinel1.gpf.BackGeocodingOp;
import eu.esa.sar.sentinel1.gpf.S1ETADCorrectionOp;
import eu.esa.sar.sentinel1.gpf.TOPSARSplitOp;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.Node;
import org.esa.snap.core.gpf.graph.NodeSource;
import org.junit.Ignore;
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

    // Split -> ApplyOrbit -> ETAD -> BackGeoCoding

    @Test
    public void testInSAR_Coregister_ProductIO() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        coregister(slcInSAR1, etadInSAR1, slcInSAR2, etadInSAR2, WriteMode.PRODUCT_IO);
    }

    @Test
    public void testInSAR_Coregister_GPF() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        coregister(slcInSAR1, etadInSAR1, slcInSAR2, etadInSAR2, WriteMode.GPF);
    }

    @Test
    public void testInSAR_Coregister_Graph() throws Exception {
        setName(new Throwable().getStackTrace()[0].getMethodName());
        coregister(slcInSAR1, etadInSAR1, slcInSAR2, etadInSAR2, WriteMode.GRAPH);
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
        try(final Product srcProduct = read(file1)) {
            Product trgProduct;

            TOPSARSplitOp splitOp = new TOPSARSplitOp();
            splitOp.setSourceProduct(srcProduct);
            splitOp.setParameter("subswath", "IW1");
            splitOp.setParameter("selectedPolarisations", "VV");
            splitOp.setParameter("lastBurstIndex", 2);

            ApplyOrbitFileOp applyOrbitOp = new ApplyOrbitFileOp();
            applyOrbitOp.setSourceProduct(splitOp.getTargetProduct());

            if (processMode.equals(ProcessMode.SPLIT_ORBIT_ETAD)) {
                S1ETADCorrectionOp etadOp = new S1ETADCorrectionOp();
                etadOp.setSourceProduct(applyOrbitOp.getTargetProduct());
                etadOp.setParameter("etadFile", etadFile);
                etadOp.setParameter("resamplingImage", false);
                etadOp.setParameter("outputPhaseCorrections", true);
                trgProduct = etadOp.getTargetProduct();
            } else {
                trgProduct = applyOrbitOp.getTargetProduct();
            }

            write(trgProduct, outputFolder, mode);

            trgProduct.dispose();
            srcProduct.dispose();
        }
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
        splitParameters.createChild("lastBurstIndex").setValue("2");
        splitNode.setConfiguration(splitParameters);
        graph.addNode(splitNode);

        final Node applyOrbitNode = new Node("Apply-Orbit-File", "Apply-Orbit-File");
        applyOrbitNode.addSource(new NodeSource("source", "TOPSAR-Split"));
        graph.addNode(applyOrbitNode);

        if(processMode.equals(ProcessMode.SPLIT_ORBIT_ETAD)) {
            final Node etadNode = new Node("S1-ETAD-Correction", "S1-ETAD-Correction");
            etadNode.addSource(new NodeSource("source", "Apply-Orbit-File"));
            final DomElement etadParameters = new DefaultDomElement("parameters");
            etadParameters.createChild("etadFile").setValue(etadFile.getAbsolutePath());
            etadParameters.createChild("resamplingImage").setValue("false");
            etadParameters.createChild("outputPhaseCorrections").setValue("true");
            etadNode.setConfiguration(etadParameters);
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

        processGraph(graph);
    }

    private void coregister(final File srcFile1, final File etadFile1,
                            final File srcFile2, final File etadFile2,
                            final WriteMode mode) throws Exception {
        Benchmark b = new Benchmark(groupName, testName) {
            @Override
            protected void execute() throws Exception {
                switch (mode) {
                    case PRODUCT_IO:
                    case GPF:
                        processStack(srcFile1, etadFile1, srcFile2, etadFile2, outputFolder, mode);
                        break;
                    case GRAPH:
                        processStackGraph(srcFile1, etadFile1, srcFile2, etadFile2, outputFolder);
                        break;
                }
            }
        };
        b.run();
    }

    private void processStack(final File srcFile1, final File etadFile1,
                              final File srcFile2, final File etadFile2,
                              final File outputFolder, final WriteMode mode) throws Exception {
        try(final Product srcProduct1 = read(srcFile1)) {
            try(final Product srcProduct2 = read(srcFile2)) {

                TOPSARSplitOp splitOp1 = new TOPSARSplitOp();
                splitOp1.setSourceProduct(srcProduct1);
                splitOp1.setParameter("subswath", "IW1");
                splitOp1.setParameter("selectedPolarisations", "VV");
                splitOp1.setParameter("lastBurstIndex", 2);

                TOPSARSplitOp splitOp2 = new TOPSARSplitOp();
                splitOp2.setSourceProduct(srcProduct2);
                splitOp2.setParameter("subswath", "IW1");
                splitOp2.setParameter("selectedPolarisations", "VV");
                splitOp2.setParameter("lastBurstIndex", 2);

                ApplyOrbitFileOp applyOrbitOp1 = new ApplyOrbitFileOp();
                applyOrbitOp1.setSourceProduct(splitOp1.getTargetProduct());

                ApplyOrbitFileOp applyOrbitOp2 = new ApplyOrbitFileOp();
                applyOrbitOp2.setSourceProduct(splitOp2.getTargetProduct());

                S1ETADCorrectionOp etadOp1 = new S1ETADCorrectionOp();
                etadOp1.setSourceProduct(applyOrbitOp1.getTargetProduct());
                etadOp1.setParameter("etadFile", etadFile1);
                etadOp1.setParameter("resamplingImage", false);
                etadOp1.setParameter("outputPhaseCorrections", true);

                S1ETADCorrectionOp etadOp2 = new S1ETADCorrectionOp();
                etadOp2.setSourceProduct(applyOrbitOp2.getTargetProduct());
                etadOp2.setParameter("etadFile", etadFile2);
                etadOp2.setParameter("resamplingImage", false);
                etadOp2.setParameter("outputPhaseCorrections", true);

                BackGeocodingOp backGeoOp = new BackGeocodingOp();
                backGeoOp.setSourceProducts(etadOp1.getTargetProduct(), etadOp2.getTargetProduct());

                Product trgProduct = backGeoOp.getTargetProduct();

                write(trgProduct, outputFolder, mode);

                trgProduct.dispose();
                srcProduct1.dispose();
                srcProduct2.dispose();
            }
        }
    }

    private void processStackGraph(final File srcFile1, final File etadFile1,
                                   final File srcFile2, final File etadFile2,
                                   final File outputFolder) throws Exception {

        final Graph graph = new Graph("graph");

        final Node readNode1 = new Node("read1", "read");
        final DomElement readParameters1 = new DefaultDomElement("parameters");
        readParameters1.createChild("file").setValue(srcFile1.getAbsolutePath());
        readNode1.setConfiguration(readParameters1);
        graph.addNode(readNode1);

        final Node readNode2 = new Node("read2", "read");
        final DomElement readParameters2 = new DefaultDomElement("parameters");
        readParameters2.createChild("file").setValue(srcFile2.getAbsolutePath());
        readNode2.setConfiguration(readParameters2);
        graph.addNode(readNode2);

        final Node splitNode1 = new Node("TOPSAR-Split1", "TOPSAR-Split");
        splitNode1.addSource(new NodeSource("source", "read1"));
        final DomElement splitParameters1 = new DefaultDomElement("parameters");
        splitParameters1.createChild("subswath").setValue("IW1");
        splitParameters1.createChild("selectedPolarisations").setValue("VV");
        splitParameters1.createChild("lastBurstIndex").setValue("2");
        splitNode1.setConfiguration(splitParameters1);
        graph.addNode(splitNode1);

        final Node splitNode2 = new Node("TOPSAR-Split2", "TOPSAR-Split");
        splitNode2.addSource(new NodeSource("source", "read2"));
        final DomElement splitParameters2 = new DefaultDomElement("parameters");
        splitParameters2.createChild("subswath").setValue("IW1");
        splitParameters2.createChild("selectedPolarisations").setValue("VV");
        splitParameters2.createChild("lastBurstIndex").setValue("2");
        splitNode2.setConfiguration(splitParameters2);
        graph.addNode(splitNode2);

        final Node applyOrbitNode1 = new Node("Apply-Orbit-File1", "Apply-Orbit-File");
        applyOrbitNode1.addSource(new NodeSource("source", "TOPSAR-Split1"));
        graph.addNode(applyOrbitNode1);

        final Node applyOrbitNode2 = new Node("Apply-Orbit-File2", "Apply-Orbit-File");
        applyOrbitNode2.addSource(new NodeSource("source", "TOPSAR-Split2"));
        graph.addNode(applyOrbitNode2);

        final Node etadNode1 = new Node("S1-ETAD-Correction1", "S1-ETAD-Correction");
        etadNode1.addSource(new NodeSource("source", "Apply-Orbit-File1"));
        final DomElement etadParameters1 = new DefaultDomElement("parameters");
        etadParameters1.createChild("etadFile").setValue(etadFile1.getAbsolutePath());
        etadParameters1.createChild("resamplingImage").setValue("false");
        etadParameters1.createChild("outputPhaseCorrections").setValue("true");
        etadNode1.setConfiguration(etadParameters1);
        graph.addNode(etadNode1);

        final Node etadNode2 = new Node("S1-ETAD-Correction2", "S1-ETAD-Correction");
        etadNode2.addSource(new NodeSource("source", "Apply-Orbit-File2"));
        final DomElement etadParameters2 = new DefaultDomElement("parameters");
        etadParameters2.createChild("etadFile").setValue(etadFile2.getAbsolutePath());
        etadParameters2.createChild("resamplingImage").setValue("false");
        etadParameters2.createChild("outputPhaseCorrections").setValue("true");
        etadNode2.setConfiguration(etadParameters2);
        graph.addNode(etadNode2);

        final Node backGeoCodingNode = new Node("Back-Geocoding", "Back-Geocoding");
        backGeoCodingNode.addSource(new NodeSource("source", "S1-ETAD-Correction1"));
        backGeoCodingNode.addSource(new NodeSource("source", "S1-ETAD-Correction2"));
        graph.addNode(backGeoCodingNode);

        final Node writeNode = new Node("write", "write");
        final DomElement writeParameters = new DefaultDomElement("parameters");
        final File outFile = new File(outputFolder, srcFile1.getName());
        writeParameters.createChild("file").setValue(outFile.getAbsolutePath());
        writeNode.setConfiguration(writeParameters);
        writeNode.addSource(new NodeSource("source", "Back-Geocoding"));
        graph.addNode(writeNode);

        processGraph(graph);
    }
}
