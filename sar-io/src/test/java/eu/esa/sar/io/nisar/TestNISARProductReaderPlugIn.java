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
package eu.esa.sar.io.nisar;


import eu.esa.sar.io.cosmo.TestCosmoSkymedReader;
import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.SystemUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestNISARProductReaderPlugIn {

    protected NisarProductReaderPlugIn plugin = new NisarProductReaderPlugIn();

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof NisarProductReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"NISAR"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{".h5"}, plugin.getDefaultFileExtensions());
    }

    @Test
    public void testDecodeQualificationRoot() {
        isInValidDecodeQualification(new File("/"));
        isInValidDecodeQualification(new File("c:\\"));
        isInValidDecodeQualification(new File("z:\\"));
    }

    protected void isValidDecodeQualification(final File file) {
        if(!file.exists()) {
            SystemUtils.LOG.warning(plugin +": isValidDecodeQualification file " + file + " not found");
            return;
        }
        DecodeQualification decodeQualification = plugin.getDecodeQualification(file);
        assertEquals(file.getPath() + " DecodeQualification="+decodeQualification,
                DecodeQualification.INTENDED, decodeQualification);
    }

    protected void isInValidDecodeQualification(final File file) {
        DecodeQualification decodeQualification = plugin.getDecodeQualification(file);
        assertEquals(file.getPath() + " DecodeQualification="+decodeQualification,
                DecodeQualification.UNABLE, decodeQualification);
    }

    @Test
    public void testAvailableProductIOReader() {
        for(String formatName : plugin.getFormatNames()) {
            ProductReader productReader = ProductIO.getProductReader(formatName);
            assertNotNull("ProductReader not found for " +formatName, productReader);
        }
    }

    @Test
    public void testGetDescription() {
        final String desc = plugin.getDescription(null);
        assertNotNull("ReaderPlugIn description invalid", desc);
        assertFalse("ReaderPlugIn description invalid", desc.isEmpty());
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestNISARReader.input_L1_RIFG_H5);
        isValidDecodeQualification(TestNISARReader.input_L1_RUNW_H5);
        isValidDecodeQualification(TestNISARReader.input_L1_ROFF_H5);
        isValidDecodeQualification(TestNISARReader.input_L1_RSLC_H5);

        isValidDecodeQualification(TestNISARReader.input_L2_GCOV_H5);
        isValidDecodeQualification(TestNISARReader.input_L2_GOFF_H5);
        isValidDecodeQualification(TestNISARReader.input_L2_GSLC_H5);
        isValidDecodeQualification(TestNISARReader.input_L2_GUNW_H5);

        isValidDecodeQualification(TestNISARReader.input_L3_SME2_H5);

        isInValidDecodeQualification(TestCosmoSkymedReader.inputSCS_H5);
        isInValidDecodeQualification(TestCosmoSkymedReader.inputDGM_H5);
    }
}
