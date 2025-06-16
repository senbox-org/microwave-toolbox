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
package eu.esa.sar.teststacks.corner_reflectors;

import au.com.bytecode.opencsv.CSVReader;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PinDescriptor;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.PlacemarkDescriptor;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.main.GPT;
import org.esa.snap.core.util.SystemUtils;
import org.junit.Assert;

import javax.media.jai.JAI;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.logging.Logger;

public class BaseCRTest extends ProcessorTest {

    private final PlacemarkDescriptor pinDescriptor = PinDescriptor.getInstance();
    private final File baseFolder = new File("/tmp/corner_reflectors");
    protected final File tempFolder;
    protected String testName = "";
    private static final Logger LOG = Logger.getLogger("test");

    protected BaseCRTest(String folder) {
        this.tempFolder = new File(baseFolder, folder);
        tempFolder.mkdirs();

        debugEnvironment();
    }

    protected void setName(String name) {
        this.testName = "_"+name;
    }

    protected void addPin(Product product, String id, double lat, double lon) {
        Placemark pin = Placemark.createPointPlacemark(pinDescriptor, id, id, "",
                null, new GeoPos(lat, lon), product.getSceneGeoCoding());
        product.getPinGroup().add(pin);
    }

    protected List<String[]> readCSVFile(String fileName) throws IOException {
        URL url = this.getClass().getResource(fileName);
        Assert.assertNotNull(url);
        try (FileReader reader = new FileReader(url.getPath())) {
            final CSVReader cvsReader = new CSVReader(reader);
            return cvsReader.readAll();
        }
    }

    protected void write(Product product) throws IOException {
        ProductIO.writeProduct(product,
                tempFolder.getAbsolutePath() +"/"+ product.getName()+testName+".dim",
                "BEAM-DIMAP");
    }

    public static void debugEnvironment() {
        SystemUtils.init3rdPartyLibs(GPT.class);

        LOG.info("ApplicationDataDir: " + SystemUtils.getApplicationDataDir());
        LOG.info("ApplicationHomeDir: " + SystemUtils.getApplicationHomeDir());
        LOG.info("AuxDataPath: " + SystemUtils.getAuxDataPath());
        LOG.info("CacheDir: " + SystemUtils.getCacheDir());

        final File etcFolder = new File(SystemUtils.getApplicationDataDir(), "etc");
        etcFolder.mkdirs();

        final StringBuilder debugStr = new StringBuilder("Runtime: ");
        final Runtime runtime = Runtime.getRuntime();
        debugStr.append(" Processors: " + runtime.availableProcessors());
        debugStr.append(" Max memory: " + fromBytes(runtime.maxMemory()));

        debugStr.append(" Cache size: " + fromBytes(JAI.getDefaultInstance().getTileCache().getMemoryCapacity()));
        debugStr.append(" Tile parallelism: " + JAI.getDefaultInstance().getTileScheduler().getParallelism());
        debugStr.append(" SNAP Tile size: " + (int) JAI.getDefaultTileSize().getWidth() + " x " +
                (int) JAI.getDefaultTileSize().getHeight() + " pixels");
        LOG.info(debugStr.toString());

    }

    public static final int K = 1024;
    public static final int M = K * 1024;
    public static final int G = M * 1024;

    public static String fromBytes(final long bytes) {
        if (bytes > G) {
            return String.format("%.1f GB", (double) bytes / G);
        } else if (bytes > M) {
            return String.format("%.1f MB", (double) bytes / M);
        } else if (bytes > K) {
            return String.format("%.1f KB", (double) bytes / K);
        }
        return String.format("%d B", bytes);
    }
}
