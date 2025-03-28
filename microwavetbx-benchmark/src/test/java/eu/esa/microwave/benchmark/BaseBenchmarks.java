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
import com.bc.ceres.core.PrintWriterConciseProgressMonitor;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.common.ReadOp;
import org.esa.snap.core.gpf.common.SubsetOp;
import org.esa.snap.core.gpf.common.WriteOp;
import org.esa.snap.core.gpf.graph.Graph;
import org.esa.snap.core.gpf.graph.GraphException;
import org.esa.snap.core.gpf.graph.GraphProcessor;
import org.esa.snap.core.gpf.graph.Node;
import org.esa.snap.core.gpf.main.CommandLineArgs;
import org.esa.snap.engine_utilities.util.TestUtils;

import javax.media.jai.JAI;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class BaseBenchmarks {

    static {
        TestUtils.initTestEnvironment();
    }

    protected final static File grdFile = new File(TestData.inputSAR +"S1/AWS/S1A_IW_GRDH_1SDV_20180719T002854_20180719T002919_022856_027A78_042A/manifest.safe");
    protected final static File grdZipFile = new File(TestData.inputSAR +"S1/GRD/S1A_IW_GRDH_1SDV_20240508T062559_20240508T062624_053776_0688DB_1A13.SAFE.zip");
    protected final static File slcFile = new File(TestData.inputSAR +"S1/SLC/S1A_IW_SLC__1SDV_20240504T180410_20240504T180437_053725_0686E4_637E.SAFE.zip");
    protected final static File qpFile = new File(TestData.inputSAR +"RS2/RS2_OK2084_PK24911_DK25857_FQ14_20080802_225909_HH_VV_HV_VH_SLC/product.xml");

    protected final static File slcInSAR1 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200815T173048_20200815T173116_022937_02B897_F7CF.SAFE.zip");
    protected final static File slcInSAR2 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_SLC__1SDV_20200908T173050_20200908T173118_023287_02C398_F0FB.SAFE.zip");
    protected final static File etadInSAR1 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200815T173048_20200815T173116_022937_02B897_E56D.SAFE.zip");
    protected final static File etadInSAR2 = new File(TestData.inputSAR +"S1/ETAD/IW/InSAR/S1B_IW_ETA__AXDV_20200908T173050_20200908T173118_023287_02C398_E9CB.SAFE.zip");

    protected final static Rectangle rect = new Rectangle(0, 0, 5000, 5000);

    protected final String groupName;
    protected String testName;
    protected final String DIMAP = "BEAM-DIMAP";
    protected enum WriteMode {PRODUCT_IO, GPF, GRAPH}

    BaseBenchmarks(final String groupName) {
        this.groupName = groupName;
        diagnostics();
    }

    protected void setName(final String name) {
        this.testName = name;
    }

    protected Product read(final File file) throws IOException {
        return ProductIO.readProduct(file);
    }

    protected Product subset(final File file, final Rectangle rect) throws IOException {
        final Product srcProduct = ProductIO.readProduct(file);
        SubsetOp op = new SubsetOp();
        op.setSourceProduct(srcProduct);
        op.setCopyMetadata(true);
        op.setRegion(rect);
        return op.getTargetProduct();
    }

    protected void write(final Product trgProduct, File outputFolder, final WriteMode mode) throws IOException {
        if(mode == WriteMode.GPF) {
            writeGPF(trgProduct, outputFolder, DIMAP);
        } else {
            write(trgProduct, outputFolder, DIMAP);
        }
    }

    protected void write(final Product trgProduct, final File outputFolder, final String format) throws IOException {
        ProductIO.writeProduct(trgProduct, new File(outputFolder, trgProduct.getName()), format, false,
                new PrintWriterConciseProgressMonitor(System.out));
    }

    protected void writeGPF(final Product trgProduct, final File outputFolder, final String format) {
        GPF.writeProduct(trgProduct, new File(outputFolder, trgProduct.getName()), format, false,
                new PrintWriterConciseProgressMonitor(System.out));
    }

    protected void processGraph(final Graph graph) throws GraphException {
        final GraphProcessor processor = new GraphProcessor();
        processor.executeGraph(graph, new PrintWriterConciseProgressMonitor(System.out));
    }

    protected String getTestFilePath(String name) throws URISyntaxException {
        URI uri = new URI(Benchmark.class.getResource(name).toString());
        return uri.getPath();
    }

    protected void setIO(final Graph graph, final File[] srcFiles, final File tgtFile, final String format) {
        final String readOperatorAlias = OperatorSpi.getOperatorAlias(ReadOp.class);
        final Node[] readerNodes = findNodes(graph, readOperatorAlias);
        int i = 0;
        for(Node readerNode : readerNodes) {
            final DomElement param = new DefaultDomElement("parameters");
            param.createChild("file").setValue(srcFiles[i++].getAbsolutePath());
            readerNode.setConfiguration(param);
        }

        final String writeOperatorAlias = OperatorSpi.getOperatorAlias(WriteOp.class);
        final Node[] writerNodes = findNodes(graph, writeOperatorAlias);
        if (writerNodes.length > 0 && tgtFile != null) {
            final DomElement origParam = writerNodes[0].getConfiguration();
            origParam.getChild("file").setValue(tgtFile.getAbsolutePath());
            if (format != null)
                origParam.getChild("formatName").setValue(format);
        }
    }

    private static Node[] findNodes(final Graph graph, final String alias) {
        List<Node> nodeList = new ArrayList<>();
        for (Node n : graph.getNodes()) {
            if (n.getOperatorName().equals(alias))
                nodeList.add(n);
        }
        return nodeList.toArray(new Node[0]);
    }

    protected void diagnostics() {
        System.out.println("Maximum Memory (MB): " + Runtime.getRuntime().maxMemory() / 1024 / 1024);
        System.out.println("Total Memory (MB): " + Runtime.getRuntime().totalMemory() / 1024 / 1024);
        System.out.println("Free Memory (MB): " + Runtime.getRuntime().freeMemory() / 1024 / 1024);
        System.out.println("Used Memory (MB): " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024);

        System.out.println("JVM Arguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println("OS Name: " + System.getProperty("os.name"));
        System.out.println("OS Version: " + System.getProperty("os.version"));

        System.out.println("Cache size: " + fromBytes(JAI.getDefaultInstance().getTileCache().getMemoryCapacity()));
        System.out.println("Tile parallelism: " + JAI.getDefaultInstance().getTileScheduler().getParallelism());
        System.out.println("Tile size: " + (int) JAI.getDefaultTileSize().getWidth() + " x " +
                (int) JAI.getDefaultTileSize().getHeight() + " pixels");
    }

    static String fromBytes(long bytes) {
        if (bytes > CommandLineArgs.G) {
            return String.format("%.1f GB", (double) bytes / CommandLineArgs.G);
        } else if (bytes > CommandLineArgs.M) {
            return String.format("%.1f MB", (double) bytes / CommandLineArgs.M);
        } else if (bytes > CommandLineArgs.K) {
            return String.format("%.1f KB", (double) bytes / CommandLineArgs.K);
        }
        return String.format("%d B", bytes);
    }
}
