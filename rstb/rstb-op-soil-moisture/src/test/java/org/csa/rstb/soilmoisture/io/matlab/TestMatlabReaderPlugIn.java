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
package org.csa.rstb.soilmoisture.io.matlab;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link MatlabReaderPlugIn}.
 */
public class TestMatlabReaderPlugIn {

    private MatlabReaderPlugIn plugin;
    private Path matFile;

    @Before
    public void setUp() throws Exception {
        plugin = new MatlabReaderPlugIn();
        matFile = Files.createTempFile("sm-test-", ".mat");
    }

    @After
    public void tearDown() throws Exception {
        if (matFile != null) {
            Files.deleteIfExists(matFile);
        }
    }

    // ---------- Format metadata ----------

    @Test
    public void testFormatNames() {
        assertArrayEquals(new String[] { "Matlab" }, plugin.getFormatNames());
    }

    @Test
    public void testDefaultFileExtensions() {
        assertArrayEquals(new String[] { "mat" }, plugin.getDefaultFileExtensions());
    }

    @Test
    public void testDescription() {
        assertEquals("Matlab mat", plugin.getDescription(Locale.getDefault()));
        // Description is locale-independent in this implementation.
        assertEquals("Matlab mat", plugin.getDescription(Locale.GERMAN));
        assertEquals("Matlab mat", plugin.getDescription(null));
    }

    @Test
    public void testInputTypesContainPathFileString() {
        final Class[] inputTypes = plugin.getInputTypes();
        assertNotNull(inputTypes);
        assertEquals(3, inputTypes.length);
        assertTrue(Arrays.asList(inputTypes).contains(Path.class));
        assertTrue(Arrays.asList(inputTypes).contains(File.class));
        assertTrue(Arrays.asList(inputTypes).contains(String.class));
    }

    @Test
    public void testGetProductFileFilterIsNotNull() {
        final SnapFileFilter filter = plugin.getProductFileFilter();
        assertNotNull(filter);
    }

    @Test
    public void testFileFilterAcceptsMatFile() {
        final MatlabReaderPlugIn.FileFilter filter = new MatlabReaderPlugIn.FileFilter();
        assertTrue(filter.accept(matFile.toFile()));
    }

    // ---------- createReaderInstance ----------

    @Test
    public void testCreateReaderInstanceReturnsMatlabReader() {
        final ProductReader reader = plugin.createReaderInstance();
        assertNotNull(reader);
        assertTrue(reader instanceof MatlabReader);
    }

    @Test
    public void testEachReaderInstanceIsFresh() {
        final ProductReader a = plugin.createReaderInstance();
        final ProductReader b = plugin.createReaderInstance();
        assertNotNull(a);
        assertNotNull(b);
        // Spec says createReaderInstance must never return null and the contract
        // allows fresh instances per call.
        if (a == b) {
            throw new AssertionError("createReaderInstance should return a fresh reader per call");
        }
    }

    // ---------- getDecodeQualification ----------

    @Test
    public void testDecodeQualificationIntendedForMatPath() {
        assertEquals(DecodeQualification.INTENDED, plugin.getDecodeQualification(matFile));
    }

    @Test
    public void testDecodeQualificationIntendedForMatFile() {
        assertEquals(DecodeQualification.INTENDED, plugin.getDecodeQualification(matFile.toFile()));
    }

    @Test
    public void testDecodeQualificationIntendedForMatPathString() {
        assertEquals(DecodeQualification.INTENDED,
                plugin.getDecodeQualification(matFile.toAbsolutePath().toString()));
    }

    @Test
    public void testDecodeQualificationUnableForMissingFile() {
        // A non-existent path should not decode.
        final Path missing = matFile.getParent().resolve("does-not-exist-" + System.nanoTime() + ".mat");
        assertEquals(DecodeQualification.UNABLE, plugin.getDecodeQualification(missing));
    }

    @Test
    public void testDecodeQualificationUnableForWrongExtension() throws Exception {
        final Path notMat = Files.createTempFile("sm-test-", ".txt");
        try {
            assertEquals(DecodeQualification.UNABLE, plugin.getDecodeQualification(notMat));
        } finally {
            Files.deleteIfExists(notMat);
        }
    }

    @Test
    public void testDecodeQualificationUnableForNullInput() {
        assertEquals(DecodeQualification.UNABLE, plugin.getDecodeQualification(null));
    }

    @Test
    public void testDecodeQualificationCaseInsensitiveExtension() throws Exception {
        // The implementation lower-cases the filename before comparing, so an
        // uppercase extension should still be accepted.
        final Path upper = Files.createTempFile("sm-test-", ".MAT");
        try {
            assertEquals(DecodeQualification.INTENDED, plugin.getDecodeQualification(upper));
        } finally {
            Files.deleteIfExists(upper);
        }
    }
}
