/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.insar.gpf.coregistration.CreateStackOp;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * End-to-end alignment test for a real ENVISAT ASAR master/slave pair (orbit-applied).
 * Diagnoses the noise-interferogram problem the user reported by checking:
 * <ol>
 *     <li><b>Check A — same lattice.</b> Both GSLCs use the same pixel size and their
 *     origin difference is an integer multiple of that pixel size, so master pixel
 *     {@code (i,j)} maps to slave pixel {@code (i+dx, j+dy)} with integer {@code dx,dy}.</li>
 *     <li><b>Check B — CreateStack offset is correct.</b> The {@code init_offset_X/Y} that
 *     CreateStack writes into {@code Orbit_Offsets} matches the integer-pixel mapping
 *     derived directly from the two scene geocodings.</li>
 * </ol>
 * Prints every intermediate value so the failure mode is obvious even when an assertion
 * fires. File-existence gated; skipped on machines without the fixtures.
 */
public class GSLCStackAlignmentTest extends ProcessorTest {

    private static final File MASTER_FILE = new File(
            "E:/out/ASA_IMS_1PNUPA20031203_061259_000000162022_00120_09192_0099_Orb.dim");
    private static final File SLAVE_FILE  = new File(
            "E:/out/ASA_IMS_1PXPDE20040211_061300_000000142024_00120_10194_0013_Orb.dim");

    private static final OperatorSpi GSLC_SPI = new GSLCGeocodingOp.Spi();
    private static final OperatorSpi STACK_SPI = new CreateStackOp.Spi();

    /** Sub-pixel tolerance when asserting integer-pixel offsets. */
    private static final double INTEGER_PIXEL_TOL = 1e-6;

    @Test
    public void testEnvisatASARPair_AlignmentEndToEnd() throws Exception {
        assumeTrue(MASTER_FILE + " not found", MASTER_FILE.exists());
        assumeTrue(SLAVE_FILE + " not found", SLAVE_FILE.exists());

        try (final Product masterSLC = TestUtils.readSourceProduct(MASTER_FILE);
             final Product slaveSLC  = TestUtils.readSourceProduct(SLAVE_FILE)) {

            System.out.println("\n=== GSLCStackAlignmentTest ===");
            printSlcInfo("master SLC", masterSLC);
            printSlcInfo("slave  SLC", slaveSLC);

            // --- Run GSLCGeocoding with the current defaults (outputFlattened=false,
            //     alignToStandardGrid=true). --------------------------------------------
            final Product masterGSLC = runDefaultGslc(masterSLC, "master");
            final Product slaveGSLC  = runDefaultGslc(slaveSLC,  "slave");

            printGslcInfo("master GSLC", masterGSLC);
            printGslcInfo("slave  GSLC", slaveGSLC);

            // --- Check A: same lattice. -------------------------------------------------
            final double pxMasterX = pixelSizeLon(masterGSLC);
            final double pxSlaveX  = pixelSizeLon(slaveGSLC);
            final double pxMasterY = pixelSizeLat(masterGSLC);
            final double pxSlaveY  = pixelSizeLat(slaveGSLC);

            System.out.printf("pixel size  master  Δlon=%.10f deg  Δlat=%.10f deg%n",
                    pxMasterX, pxMasterY);
            System.out.printf("pixel size  slave   Δlon=%.10f deg  Δlat=%.10f deg%n",
                    pxSlaveX, pxSlaveY);

            assertEquals("CHECK A.1: master/slave Δlon pixel sizes must match bit-exact",
                    pxMasterX, pxSlaveX, 0.0);
            assertEquals("CHECK A.2: master/slave Δlat pixel sizes must match bit-exact",
                    pxMasterY, pxSlaveY, 0.0);

            // origin difference / pixel size must be integer
            final GeoPos refOrigin = masterGSLC.getSceneGeoCoding()
                    .getGeoPos(new PixelPos(0.5, 0.5), null);
            final GeoPos secOrigin = slaveGSLC.getSceneGeoCoding()
                    .getGeoPos(new PixelPos(0.5, 0.5), null);
            final double dLonPx = (refOrigin.lon - secOrigin.lon) / pxMasterX;
            final double dLatPx = (refOrigin.lat - secOrigin.lat) / pxMasterY;

            System.out.printf("origin diff in pixels  Δlon=%.6f px  Δlat=%.6f px%n",
                    dLonPx, dLatPx);

            final double fracLon = dLonPx - Math.round(dLonPx);
            final double fracLat = dLatPx - Math.round(dLatPx);
            assertTrue(String.format(
                    "CHECK A.3: Δlon between origins must be integer pixels (got %.6f, frac %.6f)",
                    dLonPx, fracLon),
                    Math.abs(fracLon) < INTEGER_PIXEL_TOL);
            assertTrue(String.format(
                    "CHECK A.4: Δlat between origins must be integer pixels (got %.6f, frac %.6f)",
                    dLatPx, fracLat),
                    Math.abs(fracLat) < INTEGER_PIXEL_TOL);

            // --- Compute what offset CreateStack SHOULD use. ----------------------------
            // Same logic as CreateStackOp.computeTargetSecondaryCoordinateOffsets_Geocoded():
            // pick the master scene centre, find it in the slave's pixel coords.
            final PixelPos refAnchorPP = new PixelPos(
                    masterGSLC.getSceneRasterWidth() / 2.0,
                    masterGSLC.getSceneRasterHeight() / 2.0);
            final GeoPos anchorGP = masterGSLC.getSceneGeoCoding().getGeoPos(refAnchorPP, null);
            final PixelPos secAnchorPP = new PixelPos();
            slaveGSLC.getSceneGeoCoding().getPixelPos(anchorGP, secAnchorPP);

            final double dxRaw = secAnchorPP.x - refAnchorPP.x;
            final double dyRaw = secAnchorPP.y - refAnchorPP.y;
            final int expectedX = (int) Math.floor(dxRaw + 0.5);
            final int expectedY = (int) Math.floor(dyRaw + 0.5);

            System.out.printf("expected CreateStack offset  X=%d  Y=%d  (raw %.6f, %.6f)%n",
                    expectedX, expectedY, dxRaw, dyRaw);

            // The grids being on the same lattice (Check A) implies the raw offset is
            // also integer. Verify that explicitly so a Check A pass isn't a fluke.
            assertTrue(String.format(
                    "anchor offset must be integer pixels (rawX=%.6f, frac=%.6f)",
                    dxRaw, dxRaw - expectedX),
                    Math.abs(dxRaw - expectedX) < INTEGER_PIXEL_TOL);
            assertTrue(String.format(
                    "anchor offset must be integer pixels (rawY=%.6f, frac=%.6f)",
                    dyRaw, dyRaw - expectedY),
                    Math.abs(dyRaw - expectedY) < INTEGER_PIXEL_TOL);

            // --- Run CreateStack and Check B. -------------------------------------------
            final CreateStackOp stackOp = (CreateStackOp) STACK_SPI.createOperator();
            stackOp.setSourceProducts(masterGSLC, slaveGSLC);
            stackOp.setParameter("extent", CreateStackOp.MASTER_EXTENT);
            stackOp.setParameter("initialOffsetMethod", CreateStackOp.INITIAL_OFFSET_ORBIT);
            stackOp.setParameter("resamplingType", "NONE");
            // This test only verifies grid alignment of the integer-pixel mapping.
            // Disable the heavy auto-coregister rebuild path (full-scene CC + re-GSLC)
            // that would otherwise add ~1 hour to the test runtime.
            stackOp.setParameter("autoCoregisterGSLC", false);
            final Product stack = stackOp.getTargetProduct();
            assertNotNull("CreateStack target product must not be null", stack);

            System.out.printf("stack dims  %d x %d%n",
                    stack.getSceneRasterWidth(), stack.getSceneRasterHeight());

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(stack);
            final MetadataElement orbitOffsets = absRoot.getElement("Orbit_Offsets");
            assertNotNull("CHECK B.1: Orbit_Offsets element must exist", orbitOffsets);
            final MetadataElement[] children = orbitOffsets.getElements();
            assertTrue("CHECK B.2: expected at least one init_offsets child",
                    children != null && children.length >= 1);

            // Find the one entry for the secondary (skip nothing — there should be exactly one).
            int gotX = Integer.MIN_VALUE;
            int gotY = Integer.MIN_VALUE;
            String gotName = null;
            for (final MetadataElement child : children) {
                gotName = child.getName();
                gotX = child.getAttributeInt("init_offset_X");
                gotY = child.getAttributeInt("init_offset_Y");
                System.out.printf("Orbit_Offsets entry '%s'  init_offset_X=%d  init_offset_Y=%d%n",
                        gotName, gotX, gotY);
            }

            assertEquals(String.format(
                    "CHECK B.3: init_offset_X must match the geocoding-derived offset " +
                            "(expected %d, got %d)", expectedX, gotX),
                    expectedX, gotX);
            assertEquals(String.format(
                    "CHECK B.4: init_offset_Y must match the geocoding-derived offset " +
                            "(expected %d, got %d)", expectedY, gotY),
                    expectedY, gotY);

            System.out.println("=== alignment OK; if interferogram is still noise the " +
                    "issue is geometric precision (per-pixel R_M − R_S noise), not alignment ===");
        }
    }

    // ---------------------------------------------------------------------------------

    private static Product runDefaultGslc(final Product source, final String label) {
        final GSLCGeocodingOp op = (GSLCGeocodingOp) GSLC_SPI.createOperator();
        op.setSourceProduct(source);
        // Use a DEM that's reliably available locally; if neither is present the operator
        // will fail and the test will surface the failure clearly.
        op.setParameter("demName", "Copernicus 30m Global DEM");
        op.setParameter("imgResamplingMethod", "BILINEAR_INTERPOLATION");
        op.setParameter("nodataValueAtSea", false);
        // Defaults that the InSAR pipeline depends on — set explicitly so the test
        // documents the requirement and survives any future default flips.
        op.setParameter("outputFlattened", false);
        op.setParameter("alignToStandardGrid", true);
        op.setParameter("standardGridOriginX", 0.0);
        op.setParameter("standardGridOriginY", 0.0);
        final Product target = op.getTargetProduct();
        assertNotNull(label + " GSLC must not be null", target);
        return target;
    }

    private static double pixelSizeLon(final Product p) {
        final GeoPos g0 = p.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 0.5), null);
        final GeoPos g1 = p.getSceneGeoCoding().getGeoPos(new PixelPos(1.5, 0.5), null);
        return g1.lon - g0.lon;
    }

    private static double pixelSizeLat(final Product p) {
        final GeoPos g0 = p.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 0.5), null);
        final GeoPos g1 = p.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 1.5), null);
        return g1.lat - g0.lat;  // negative since lat decreases as row increases
    }

    private static void printSlcInfo(final String label, final Product p) throws Exception {
        System.out.printf("%-12s name=%s  type=%s  %d x %d%n",
                label, p.getName(), p.getProductType(),
                p.getSceneRasterWidth(), p.getSceneRasterHeight());
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(p);
        if (absRoot != null) {
            final double rs = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
            final double azs = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.azimuth_spacing);
            final String firstLine = absRoot.getAttributeString(AbstractMetadata.first_line_time, "?");
            System.out.printf("             rangeSpacing=%.3f m  azSpacing=%.3f m  firstLineTime=%s%n",
                    rs, azs, firstLine);
        }
    }

    private static void printGslcInfo(final String label, final Product p) {
        final GeoPos topLeft  = p.getSceneGeoCoding().getGeoPos(new PixelPos(0.5, 0.5), null);
        final GeoPos topRight = p.getSceneGeoCoding().getGeoPos(
                new PixelPos(p.getSceneRasterWidth() - 0.5, 0.5), null);
        final GeoPos bottomLeft = p.getSceneGeoCoding().getGeoPos(
                new PixelPos(0.5, p.getSceneRasterHeight() - 0.5), null);
        System.out.printf("%-12s name=%s  %d x %d%n",
                label, p.getName(), p.getSceneRasterWidth(), p.getSceneRasterHeight());
        System.out.printf("             TL (%.7f, %.7f)  TR (%.7f, %.7f)  BL (%.7f, %.7f)%n",
                topLeft.lat,    topLeft.lon,
                topRight.lat,   topRight.lon,
                bottomLeft.lat, bottomLeft.lon);
    }
}
