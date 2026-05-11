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
package eu.esa.sar.iogdal.cosmo;

import eu.esa.sar.iogdal.AbstractProductReaderPlugInGDALTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestCosmoSkymedGDALReaderPlugIn extends AbstractProductReaderPlugInGDALTest {

    public TestCosmoSkymedGDALReaderPlugIn() {
        super(new CosmoSkymedGDALReaderPlugIn());
    }

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof CosmoSkymedGDALReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"CosmoSkymedGDAL"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{".tif", ".attribs.xml"}, plugin.getDefaultFileExtensions());
    }

    @Override
    protected String[] getValidPrimaryMetadataFileNames() {
        return new String[]{"CSG_SSAR1_GEC_B_0101_STR_012.attribs.xml"};
    }

    @Override
    protected String[] getInvalidPrimaryMetadataFileNames() {
        return new String[]{"random.xml"};
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestCosmoSkymedGDALReader.inputSM_GeoTiff_1B_tif);
        isValidDecodeQualification(TestCosmoSkymedGDALReader.inputSM_GeoTiff_1B_xml);
        isValidDecodeQualification(TestCosmoSkymedGDALReader.inputSM_GeoTiff_1C_tif);
        isValidDecodeQualification(TestCosmoSkymedGDALReader.inputSC_GeoTiff_DGM_tif);
        isValidDecodeQualification(TestCosmoSkymedGDALReader.inputSC_GeoTiff_GEC_tif);
    }
}
