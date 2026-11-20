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

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.common.BandMathsOp;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.junit.Assume;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
import static org.junit.Assert.assertNull;
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
        op.dispose(); // releases the SNAPHU output files held open for the target bands
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

    // ---------- unwrapped-band assembly (regression: all-zero output after c322d2d7b) ----------

    private static final int W = 4;
    private static final int H = 3;
    private static final String IFG = "Phase_ifg_01Jan2020_13Jan2020";

    /** Unwrapped value written for pixel (x, y): never zero, so a zero read means lost data. */
    private static float unwValue(int x, int y) {
        return 1.5f + x + 10f * y;
    }

    /** Writes UnwPhase_*.snaphu.hdr/.img as SnaphuWriter + SNAPHU leave them (float32, byte order 0). */
    private static void writeUnwrappedEnvi(File dir, String unwBandName) throws IOException {
        final String hdr = "ENVI\n"
                + "description = {Unwrapped phase - Unit: 1}\n"
                + "samples = " + W + "\n"
                + "lines = " + H + "\n"
                + "bands = 1\n"
                + "header offset = 0\n"
                + "file type = ENVI Standard\n"
                + "data type = 4\n"
                + "interleave = bsq\n"
                + "byte order = 0\n"
                + "band names = { " + unwBandName + " }\n";
        Files.write(new File(dir, unwBandName + ".snaphu.hdr").toPath(), hdr.getBytes(StandardCharsets.US_ASCII));
        final ByteBuffer buf = ByteBuffer.allocate(W * H * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                buf.putFloat(unwValue(x, y));
            }
        }
        Files.write(new File(dir, unwBandName + ".snaphu.img").toPath(), buf.array());
    }

    private static void addBand(Product p, String name, String unit, float value) {
        final Band b = p.addBand(name, ProductData.TYPE_FLOAT32);
        b.setUnit(unit);
        final float[] data = new float[W * H];
        Arrays.fill(data, value);
        b.setRasterData(ProductData.createInstance(data));
    }

    private static Product createInterferogramStack() {
        final Product p = new Product("ifg_stack", "SLC", W, H);
        AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(p);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        addBand(p, IFG, Unit.PHASE, 0.25f);
        addBand(p, "Phase_ifg_01Jan2020_25Jan2020", Unit.PHASE, 0.5f);
        addBand(p, "coh_01Jan2020_13Jan2020", Unit.COHERENCE, 0.8f);
        addBand(p, "coh_01Jan2020_25Jan2020", Unit.COHERENCE, 0.7f);
        return p;
    }

    private static float[] readAll(Band b) {
        final float[] out = new float[W * H];
        b.getSourceImage().getData().getSamples(0, 0, W, H, 0, out);
        return out;
    }

    private static File createFakeSnaphuBinary(File dir) throws IOException {
        final boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        final File binary = new File(dir, isWindows ? "snaphu.exe" : "snaphu");
        assertTrue(binary.createNewFile());
        binary.setExecutable(true);
        Assume.assumeTrue("file system cannot mark files executable", binary.canExecute());
        return binary;
    }

    @Test
    public void testAssembledUnwrappedBandHoldsSnaphuData() throws IOException {
        writeUnwrappedEnvi(tempDir.toFile(), "Unw" + IFG);
        final Product target = new Product("t", "t", W, H);

        op.assembleUnwrappedFilesIntoSingularProduct(tempDir.toFile(), target);

        final Band unw = target.getBand("Unw" + IFG);
        assertNotNull(unw);
        final float[] data = readAll(unw);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                assertEquals("pixel " + x + "," + y, unwValue(x, y), data[y * W + x], 0.0f);
            }
        }
        assertEquals(Unit.ABS_PHASE, unw.getUnit());

        // dispose() releases the ENVI files (on Windows an open handle would block the delete)
        op.dispose();
        assertTrue(new File(tempDir.toFile(), "Unw" + IFG + ".snaphu.img").delete());
    }

    @Test
    public void testExecuteKeepsCoherenceAndNonZeroUnwrappedData() throws IOException {
        final File exportDir = Files.createDirectory(tempDir.resolve("export")).toFile();
        final File installDir = Files.createDirectory(tempDir.resolve("install")).toFile();
        // With no <pair>snaphu.conf in the export folder SNAPHU is never started, and because a
        // binary is found in the install folder nothing is downloaded.
        createFakeSnaphuBinary(installDir);
        writeUnwrappedEnvi(exportDir, "Unw" + IFG);
        writeUnwrappedEnvi(exportDir, "UnwPhase_ifg_01Jan2020_25Jan2020");

        op.setSourceProduct(createInterferogramStack());
        op.setParameter("snaphuProcessingLocation", exportDir);
        op.setParameter("snaphuInstallLocation", installDir);
        final Product target = op.getTargetProduct();
        op.execute(ProgressMonitor.NULL);

        final Band unw = target.getBand("Unw" + IFG);
        assertNotNull(unw);
        assertEquals(Unit.ABS_PHASE, unw.getUnit());
        final float[] data = readAll(unw);
        for (int i = 0; i < data.length; i++) {
            assertEquals(unwValue(i % W, i / W), data[i], 0.0f);
        }
        assertNotNull(target.getBand("UnwPhase_ifg_01Jan2020_25Jan2020"));

        final Band coh = target.getBand("coh_01Jan2020_13Jan2020");
        assertNotNull("coherence must be kept for SBAS / PyRate", coh);
        assertEquals(Unit.COHERENCE, coh.getUnit());
        assertEquals(0.8f, readAll(coh)[5], 0.0f);
        assertNotNull(target.getBand("coh_01Jan2020_25Jan2020"));
        assertNull("wrapped phase is replaced by the unwrapped band", target.getBand(IFG));
    }

    /**
     * A downstream operator initialised after BatchSnaphu's initialize() (the shipped PyRate graph
     * runs BatchSnaphu -> Terrain-Correction -> Write) captures the Unw* Band objects then; they must
     * be the bands that carry SNAPHU's output after doExecute, not placeholders that are swapped out.
     */
    @Test
    public void testDownstreamOperatorInitialisedBeforeExecuteSeesUnwrappedData() throws IOException {
        final File exportDir = Files.createDirectory(tempDir.resolve("export")).toFile();
        final File installDir = Files.createDirectory(tempDir.resolve("install")).toFile();
        createFakeSnaphuBinary(installDir);
        writeUnwrappedEnvi(exportDir, "Unw" + IFG);
        writeUnwrappedEnvi(exportDir, "UnwPhase_ifg_01Jan2020_25Jan2020");

        op.setSourceProduct(createInterferogramStack());
        op.setParameter("snaphuProcessingLocation", exportDir);
        op.setParameter("snaphuInstallLocation", installDir);
        final Product target = op.getTargetProduct();
        final Band capturedUnw = target.getBand("Unw" + IFG);
        assertNotNull("Unw* band must exist after initialize()", capturedUnw);

        final BandMathsOp.BandDescriptor bd = new BandMathsOp.BandDescriptor();
        bd.name = "twice";
        bd.type = ProductData.TYPESTRING_FLOAT32;
        bd.expression = "2 * Unw" + IFG;
        final BandMathsOp downstream = new BandMathsOp();
        downstream.setSourceProduct(target);
        downstream.setParameterDefaultValues();
        downstream.setParameter("targetBands", new BandMathsOp.BandDescriptor[]{bd});
        final Product downstreamTarget = downstream.getTargetProduct();

        op.execute(ProgressMonitor.NULL);

        assertTrue("the band captured at initialize() is the output band", target.getBand("Unw" + IFG) == capturedUnw);
        assertEquals(Unit.ABS_PHASE, capturedUnw.getUnit());
        final float[] data = readAll(downstreamTarget.getBand("twice"));
        for (int i = 0; i < data.length; i++) {
            assertEquals(2f * unwValue(i % W, i / W), data[i], 0.0f);
        }
        downstream.dispose();
    }

    /**
     * An expected UnwPhase*.snaphu.hdr is missing: a downstream operator initialised earlier would
     * otherwise write that band as all zeros, so the operator must fail and name the file.
     */
    @Test
    public void testMissingSnaphuOutputFailsNamingTheFile() throws IOException {
        final File exportDir = Files.createDirectory(tempDir.resolve("export")).toFile();
        final File installDir = Files.createDirectory(tempDir.resolve("install")).toFile();
        createFakeSnaphuBinary(installDir);
        writeUnwrappedEnvi(exportDir, "Unw" + IFG); // the 25Jan2020 output is missing

        op.setSourceProduct(createInterferogramStack());
        op.setParameter("snaphuProcessingLocation", exportDir);
        op.setParameter("snaphuInstallLocation", installDir);
        op.getTargetProduct();
        try {
            op.execute(ProgressMonitor.NULL);
            org.junit.Assert.fail("expected OperatorException");
        } catch (org.esa.snap.core.gpf.OperatorException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("UnwPhase_ifg_01Jan2020_25Jan2020.snaphu.hdr"));
        }
    }

    // ---------- SNAPHU binary location ----------

    @Test
    public void testDownloadSnaphuReusesBinaryInInstallFolder() throws IOException {
        final File sub = Files.createDirectories(tempDir.resolve("snaphu-v2.0.4_win64").resolve("bin")).toFile();
        final File binary = createFakeSnaphuBinary(sub);
        // Must not download (no network in unit tests): the binary already in the folder is reused.
        assertEquals(binary.getAbsoluteFile(), op.downloadSnaphu(tempDir.toFile()).getAbsoluteFile());
    }

    @Test
    public void testSnaphuDownloadUrlPerOs() {
        assertTrue(BatchSnaphuUnwrapOp.getSnaphuDownloadUrl("Windows 11", "amd64").contains("win64"));
        assertTrue(BatchSnaphuUnwrapOp.getSnaphuDownloadUrl("Windows 10", "x86").contains("win32"));
        assertTrue(BatchSnaphuUnwrapOp.getSnaphuDownloadUrl("Linux", "amd64").contains("linux"));
        // an executable must not be fetched over plain http
        for (String os : new String[]{"Windows 11", "Linux"}) {
            assertTrue(BatchSnaphuUnwrapOp.getSnaphuDownloadUrl(os, "amd64").startsWith("https://"));
        }
        assertTrue(BatchSnaphuUnwrapOp.getSnaphuDownloadUrl("Windows 10", "x86").startsWith("https://"));
        assertNull("no macOS build is published; the Linux binary must not be offered",
                BatchSnaphuUnwrapOp.getSnaphuDownloadUrl("Mac OS X", "aarch64"));
    }
}
