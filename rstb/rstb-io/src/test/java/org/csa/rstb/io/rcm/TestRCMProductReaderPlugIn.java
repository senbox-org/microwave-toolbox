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
package org.csa.rstb.io.rcm;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The declared extensions and the file filter must match what getDecodeQualification accepts:
 * an RCM*.zip, an RCM* product folder, or the manifest.safe inside it (not product.xml).
 */
public class TestRCMProductReaderPlugIn {

    private static final String PRODUCT_NAME = "RCM1_OK1024567_PK1025865_1_SC50MA_20191205_225938_HH_HV_GRD";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final RCMProductReaderPlugIn plugIn = new RCMProductReaderPlugIn();

    @Test
    public void testFormatNameAndDefaultFileExtensions() {
        assertArrayEquals(new String[]{"RCM"}, plugIn.getFormatNames());
        assertArrayEquals(new String[]{".safe", ".zip"}, plugIn.getDefaultFileExtensions());
    }

    @Test
    public void testDecodeQualificationAndFileFilterAgree() throws IOException {
        final File productDir = tmp.newFolder(PRODUCT_NAME);
        final File manifest = new File(productDir, "manifest.safe");
        assertTrue(manifest.createNewFile());
        final File metadataDir = new File(productDir, "metadata");
        assertTrue(metadataDir.mkdir());
        final File productXml = new File(metadataDir, "product.xml");
        assertTrue(productXml.createNewFile());
        final File zip = tmp.newFile(PRODUCT_NAME + ".zip");

        final SnapFileFilter filter = plugIn.getProductFileFilter();
        assertEquals("RCM", filter.getFormatName());

        // accepted by the reader, so they must be selectable in the import dialog
        for (File accepted : new File[]{manifest, productDir, zip}) {
            assertEquals(accepted.getName(), DecodeQualification.INTENDED, plugIn.getDecodeQualification(accepted));
            assertTrue(accepted.getName(), filter.accept(accepted));
        }

        // product.xml is not a reader entry point and must not be offered
        assertEquals(DecodeQualification.UNABLE, plugIn.getDecodeQualification(productXml));
        assertFalse(filter.accept(productXml));
    }
}
