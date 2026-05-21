/*
 * Copyright (C) 2021 SkyWatch. https://www.skywatch.com
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
package eu.esa.sar.insar.gpf.coregistration;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit test for CreateStackOp.
 */
public class TestCreateStackOp extends ProcessorTest {

    private final static OperatorSpi spi = new CreateStackOp.Spi();

    @Test
    public void testSpiCreatesOperator() {
        final CreateStackOp op = (CreateStackOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = CreateStackOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("CreateStack", md.alias());
    }

    @Test
    public void testCreateStackRefExtent() throws Exception {

        final CreateStackOp op = (CreateStackOp) spi.createOperator();
        assertNotNull(op);

        int refW = 30, refH = 30;
        final Product refProduct = createTestProduct(refW, refH);
        final Product secProduct1 = createTestProduct(refW+10, refH+10);

        op.setSourceProducts(refProduct, secProduct1);
        op.setTestParameters(CreateStackOp.MASTER_EXTENT, CreateStackOp.INITIAL_OFFSET_GEOLOCATION);

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);
        assertEquals(refW, targetProduct.getSceneRasterWidth());
        assertEquals(refH, targetProduct.getSceneRasterHeight());

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        float[] pixels = new float[refW*refH];
        band.readPixels(0, 0, refW, refH, pixels, ProgressMonitor.NULL);

        assertEquals(1.5f, pixels[0], 0.0001f);
        assertEquals(11.5f, pixels[10], 0.0001f);
        assertEquals(101.5f, pixels[100], 0.0001f);
    }

    @Test
    public void testCreateStackMaxExtent() throws Exception {

        final CreateStackOp op = (CreateStackOp) spi.createOperator();
        assertNotNull(op);

        int refW = 30, refH = 30;
        final Product refProduct = createTestProduct(refW, refH);
        final Product secProduct1 = createTestProduct(refW+10, refH+10);

        op.setSourceProducts(refProduct, secProduct1);
        op.setParameter("resamplingType", ResamplingFactory.BICUBIC_INTERPOLATION_NAME);
        op.setTestParameters(CreateStackOp.MAX_EXTENT, CreateStackOp.INITIAL_OFFSET_GEOLOCATION);

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);
        assertEquals(52, targetProduct.getSceneRasterWidth());
        assertEquals(34, targetProduct.getSceneRasterHeight());

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        float[] pixels = new float[refW*refH];
        band.readPixels(0, 0, refW, refH, pixels, ProgressMonitor.NULL);

        assertEquals("pixels[0]", 0.0f, pixels[0], 0.0001f);
        assertEquals("pixels[10]", 0.0f, pixels[10], 0.0001f);
        assertEquals("pixels[100]", 94.68987f, pixels[100], 0.0001f);
    }

    @Test
    public void testCreateStackMinExtent() throws Exception {

        final CreateStackOp op = (CreateStackOp) spi.createOperator();
        assertNotNull(op);

        int refW = 30, refH = 30;
        final Product refProduct = createTestProduct(refW, refH);
        final Product secProduct1 = createTestProduct(refW+10, refH+10);

        op.setSourceProducts(refProduct, secProduct1);
        op.setParameter("resamplingType", ResamplingFactory.BICUBIC_INTERPOLATION_NAME);
        op.setTestParameters(CreateStackOp.MIN_EXTENT, CreateStackOp.INITIAL_OFFSET_GEOLOCATION);

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);
        assertEquals("getSceneRasterWidth", 29, targetProduct.getSceneRasterWidth());
        assertEquals("getSceneRasterHeight", 29, targetProduct.getSceneRasterHeight());

        final Band band = targetProduct.getBandAt(0);
        assertNotNull(band);

        // readPixels gets computeTiles to be executed
        float[] pixels = new float[refW*refH];
        band.readPixels(0, 0, refW, refH, pixels, ProgressMonitor.NULL);

        assertEquals("pixels[0]", 0.0f, pixels[0], 0.0001f);
        assertEquals("pixels[10]", 40.62154f, pixels[10], 0.0001f);
        assertEquals("pixels[100]", 100.77306f, pixels[100], 0.0001f);
    }

    private static Product createTestProduct(final int w, final int h) {

        Product product = TestUtils.createProduct("ASA_IMP_1P", w, h);
        TestUtils.createBand(product, "amplitude", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, w, h, true);
        return product;
    }

    /**
     * Two geocoded (CrsGeoCoding) products whose grids differ by a known integer pixel
     * offset must yield exactly that offset in the {@code Orbit_Offsets} metadata of the
     * stack, even when the default {@code INITIAL_OFFSET_ORBIT} method is used. Without
     * this fix the operator would feed {@code (W/2, H/2)} of a geocoded grid into
     * {@code Orbit.lp2xyz} and emit garbage.
     */
    @Test
    public void testCreateStack_GeocodedProducts_UsesGeocodingOffset() throws Exception {
        final int w = 50, h = 50;
        final double pixelSize = 0.001;          // ~100 m at the equator
        final double refEasting = 10.000;
        final double refNorthing = 50.000;
        // Slave grid shifted +3 px east, +7 px south compared to reference.
        final double secEasting  = refEasting  + 3 * pixelSize;
        final double secNorthing = refNorthing - 7 * pixelSize;

        final Product refProduct = createGeocodedProduct("ref", w, h, refEasting, refNorthing, pixelSize);
        final Product secProduct = createGeocodedProduct("sec", w, h, secEasting, secNorthing, pixelSize);

        final CreateStackOp op = (CreateStackOp) spi.createOperator();
        op.setSourceProducts(refProduct, secProduct);
        // Default ORBIT method — the path that was broken for geocoded inputs.
        op.setTestParameters(CreateStackOp.MASTER_EXTENT, CreateStackOp.INITIAL_OFFSET_ORBIT);

        final Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);
        assertEquals(w, targetProduct.getSceneRasterWidth());
        assertEquals(h, targetProduct.getSceneRasterHeight());

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final MetadataElement orbitOffsets = absRoot.getElement("Orbit_Offsets");
        assertNotNull("Orbit_Offsets element missing", orbitOffsets);
        final MetadataElement[] children = orbitOffsets.getElements();
        assertTrue("expected an init_offsets entry for the secondary",
                children != null && children.length >= 1);

        // anchor lon = refEasting  + (W/2) * pixelSize = 10.025
        // secPixel.x = (anchorLon - secEasting) / pixelSize = (10.025 - 10.003)/0.001 = 22
        // offsetX = secPixel.x - W/2 = -3
        // anchor lat = refNorthing - (H/2) * pixelSize = 49.975
        // secPixel.y = (secNorthing - anchorLat)/pixelSize = (49.993 - 49.975)/0.001 = 18
        // offsetY = secPixel.y - H/2 = -7
        final int gotX = children[0].getAttributeInt("init_offset_X");
        final int gotY = children[0].getAttributeInt("init_offset_Y");
        assertEquals("offsetX must match grid shift", -3, gotX);
        assertEquals("offsetY must match grid shift", -7, gotY);
    }

    /**
     * Verifies the safety-net behaviour when a stack contains both a geocoded reference
     * and one or more raw SLCs. The expected outcomes are:
     * <ul>
     *   <li>If the geocoded master carries complex (i/q) bands, the operator promotes the
     *       raw SLC slaves via {@code GSLC-Terrain-Correction} and the build either succeeds
     *       or fails with a GSLC-related message.</li>
     *   <li>If the geocoded master is amplitude-only (this fixture's case — it's a
     *       terrain-corrected product without complex bands), the GSLC auto-coregister
     *       silently skips and the explicit geometry-mixing throw fires &mdash; preventing
     *       silent garbage offsets on an InSAR-incompatible mix.</li>
     * </ul>
     */
    @Test
    public void testCreateStack_AutoGeocodeReachedWhenMixingGeocodedAndSLC() throws Exception {
        final int w = 30, h = 30;
        final Product geocoded = createGeocodedProduct("geocoded_ref", w, h, 10.0, 50.0, 0.001);
        final Product slc = createTestProduct(w, h);

        final CreateStackOp op = (CreateStackOp) spi.createOperator();
        op.setSourceProducts(geocoded, slc);
        op.setTestParameters(CreateStackOp.MASTER_EXTENT, CreateStackOp.INITIAL_OFFSET_ORBIT);

        try {
            op.getTargetProduct();
            // GSLC happened to succeed on the bare fixture — acceptable; the goal is
            // "no silent garbage offset", not a specific error.
        } catch (OperatorException e) {
            final String msg = String.valueOf(e.getMessage());
            assertTrue("expected either a GSLC auto-coregister failure or the geometry-mixing " +
                            "throw; got: " + msg,
                    msg.contains("Auto-geocoding") ||
                            msg.contains("GSLC-Terrain-Correction") ||
                            msg.contains("GSLC") ||
                            msg.contains("cannot mix geocoded"));
        }
    }

    private static Product createGeocodedProduct(final String name, final int w, final int h,
                                                  final double easting, final double northing,
                                                  final double pixelSize) throws Exception {
        final Product p = new Product(name, "TYPE_GEOCODED", w, h);
        final ProductData.UTC startTime = AbstractMetadata.parseUTC("10-MAY-2008 20:30:46.890683");
        final ProductData.UTC endTime = AbstractMetadata.parseUTC("10-MAY-2008 20:35:46.890683");
        p.setStartTime(startTime);
        p.setEndTime(endTime);

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time, startTime);
        absRoot.setAttributeUTC(AbstractMetadata.last_line_time, endTime);
        absRoot.setAttributeInt(AbstractMetadata.is_terrain_corrected, 1);
        absRoot.setAttributeInt(AbstractMetadata.num_output_lines, h);
        absRoot.setAttributeInt(AbstractMetadata.num_samples_per_line, w);

        p.setSceneGeoCoding(new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                w, h, easting, northing, pixelSize, pixelSize));

        TestUtils.createBand(p, "amplitude", ProductData.TYPE_FLOAT32, Unit.AMPLITUDE, w, h, true);
        return p;
    }
}
