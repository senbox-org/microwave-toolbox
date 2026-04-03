/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.iogdal.biomass;

import eu.esa.sar.commons.test.TestData;
import eu.esa.sar.iogdal.AbstractProductReaderPlugInGDALTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestBiomassProductReaderPlugIn extends AbstractProductReaderPlugInGDALTest {

    public TestBiomassProductReaderPlugIn() {
        super(new BiomassProductReaderPlugIn());
    }

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof BiomassProductReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"BIOMASS"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{".xml",".zip"}, plugin.getDefaultFileExtensions());
    }

    @Override
    protected String[] getValidPrimaryMetadataFileNames() {
        return new String[] {"bio_s1_dgm__1s_20170101t060309_20170101t060330_i_g03_m03_c03_t010_f001_01_d0nnqp.xml"};
    }

    @Override
    protected String[] getInvalidPrimaryMetadataFileNames() {
        return new String[] {"manifest.xml"};
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestBiomassProductReader.input_L1B_DGM);
        isValidDecodeQualification(TestBiomassProductReader.input_L1B_DGM_ZIP);

        isValidDecodeQualification(TestBiomassProductReader.input_L1A_SCS);
        isValidDecodeQualification(TestBiomassProductReader.input_L1C_IntPhase_SCS);

        isInValidDecodeQualification(TestData.inputS1_GRD);
        isInValidDecodeQualification(TestData.inputS1_SLC);
    }
}
