/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.alos4;

import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test ALOS 4 Geotiff Product Reader.
 *
 * @author lveci
 */
public class TestAlos4GeotiffProductReader extends ReaderTest {

    private final static File grd = new File("E:\\EO\\ALOS4\\ALOS40182900250502UWDPRD0107_2.1GU-\\ALOS40182900250502UWDPRD0107_2.1GU-\\summary-ALOS40182900250502UWDPRD0107-2.1GU-.txt");

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(grd + " not found", grd.exists());
    }

    public TestAlos4GeotiffProductReader() {
        super(new Alos4GeoTiffProductReaderPlugIn());
    }

    @Test
    public void testOpeningGRD() throws Exception {

        final DecodeQualification canRead = readerPlugIn.getDecodeQualification(grd);
        Assert.assertTrue(canRead == DecodeQualification.INTENDED);

        final Product product = reader.readProductNodes(grd, null);
        Assert.assertTrue(product != null);

        final ProductValidator validator = new ProductValidator(product);
        validator.validateProduct();
        validator.validateMetadata();
        validator.validateBands(new String[] {"Amplitude_HH","Intensity_HH","Amplitude_HV","Intensity_HV"});
        validator.validateBandData();
    }
}
