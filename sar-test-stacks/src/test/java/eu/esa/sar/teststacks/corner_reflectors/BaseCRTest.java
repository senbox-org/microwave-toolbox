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
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PinDescriptor;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.PlacemarkDescriptor;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.List;

public class BaseCRTest {

    private final PlacemarkDescriptor pinDescriptor = PinDescriptor.getInstance();
    private final File baseFolder = new File("/tmp/corner_reflectors");
    private final File tempFolder;
    private String testName = "";

    protected BaseCRTest(String folder) {
        this.tempFolder = new File(baseFolder, folder);
        tempFolder.mkdirs();
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
}
