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
package eu.esa.sar.sar.gpf.pyrate;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link BatchSnaphuUnwrapOp}.
 *
 * The helper methods are package-private and have no dependency on the operator's
 * source-product state, so we exercise them directly via an instance created from
 * the SPI. The tests cover SPI/metadata smoke checks plus the defensive paths
 * around config-file discovery and the SNAPHU binary detection.
 */
public class TestBatchSnaphuUnwrapOp {

    private BatchSnaphuUnwrapOp op;
    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        op = (BatchSnaphuUnwrapOp) new BatchSnaphuUnwrapOp.Spi().createOperator();
        tempDir = Files.createTempDirectory("snaphu-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    @Test
    public void testSpiCreatesOperator() {
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = BatchSnaphuUnwrapOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("BatchSnaphuUnwrapOp", md.alias());
    }

    // ---------- getSnaphuConfigFiles ----------

    @Test
    public void testGetSnaphuConfigFilesReturnsEmptyForEmptyDirectory() {
        final File[] files = op.getSnaphuConfigFiles(tempDir.toFile());
        assertNotNull(files);
        assertEquals(0, files.length);
    }

    @Test
    public void testGetSnaphuConfigFilesReturnsEmptyArrayWhenListingFails() {
        // Passing a regular file (not a directory) causes File.list() to return null.
        // The fix should turn that into an empty array, not an NPE.
        final File regularFile = tempDir.resolve("not-a-directory.txt").toFile();
        try {
            assertTrue(regularFile.createNewFile());
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        final File[] files = op.getSnaphuConfigFiles(regularFile);
        assertNotNull("must not return null", files);
        assertEquals(0, files.length);
    }

    @Test
    public void testGetSnaphuConfigFilesPicksOnlyNumberedConfigFiles() throws IOException {
        Files.createFile(tempDir.resolve("snaphu.conf"));                  // master — excluded
        Files.createFile(tempDir.resolve("interferogram_01.snaphu.conf")); // included
        Files.createFile(tempDir.resolve("interferogram_02.snaphu.conf")); // included
        Files.createFile(tempDir.resolve("readme.txt"));                   // unrelated
        Files.createFile(tempDir.resolve("output.snaphu.log"));            // unrelated

        final File[] files = op.getSnaphuConfigFiles(tempDir.toFile());

        final Set<String> names = Arrays.stream(files)
                .map(File::getName)
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(2, names.size());
        assertTrue(names.contains("interferogram_01.snaphu.conf"));
        assertTrue(names.contains("interferogram_02.snaphu.conf"));
        assertFalse("master snaphu.conf must be excluded", names.contains("snaphu.conf"));
    }

    // ---------- isSnaphuBinary ----------

    @Test
    public void testIsSnaphuBinaryRejectsDirectory() {
        assertFalse(op.isSnaphuBinary(tempDir.toFile()));
    }

    @Test
    public void testIsSnaphuBinaryRejectsNonExecutableFile() throws IOException {
        final File f = tempDir.resolve("snaphu").toFile();
        assertTrue(f.createNewFile());
        // Force not-executable on Windows/Unix; behavior is OS-dependent so we
        // only assert when we can actually clear the flag.
        if (f.setExecutable(false)) {
            assertFalse(op.isSnaphuBinary(f));
        }
    }

    @Test
    public void testIsSnaphuBinaryAcceptsExecutableMatchingName() throws IOException {
        final boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        final String name = isWindows ? "snaphu.exe" : "snaphu";
        final File f = tempDir.resolve(name).toFile();
        assertTrue(f.createNewFile());
        if (!f.setExecutable(true)) {
            // On filesystems that can't grant +x, we can't run this assertion.
            return;
        }
        assertTrue(op.isSnaphuBinary(f));
    }

    @Test
    public void testIsSnaphuBinaryRejectsWrongName() throws IOException {
        final File f = tempDir.resolve("not-snaphu.bin").toFile();
        assertTrue(f.createNewFile());
        f.setExecutable(true);
        assertFalse(op.isSnaphuBinary(f));
    }
}
