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
package eu.esa.sar.iogdal.iceye;

import eu.esa.sar.commons.product.Missions;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Maps the ICEYE Open Data STAC item onto SNAP's abstracted metadata.
 * <p>
 * The Open Data delivery carries a flat STAC property set ({@code sar:*},
 * {@code iceye:*}, {@code proj:*}) which shares no key with the nested schema
 * the AML/CPX reader consumes, so the mapping gets its own test. Everything
 * here runs off the committed side-car items - no GDAL, no raster.
 *
 * @author Luis Veci
 */
public class TestIceyeOpenDataMetadata {

    private static IceyeOpenDataMetadata grd;
    private static IceyeOpenDataMetadata slc;

    @BeforeClass
    public static void loadFixtures() throws Exception {
        grd = IceyeOpenDataMetadata.fromStacItem(IceyeOpenDataFixtures.grdStacItem());
        slc = IceyeOpenDataMetadata.fromStacItem(IceyeOpenDataFixtures.slcStacItem());
    }

    @Test
    public void testIdentification() {
        assertEquals("GRD-COG", grd.getProductType());
        assertEquals("SLC-COG", slc.getProductType());
        assertEquals("spotlight", grd.getAcquisitionMode());
        assertEquals("VV", grd.getPolarization());
        assertEquals("ICEYE-X56", grd.getPlatform());
        assertFalse(grd.isComplex());
        assertTrue(slc.isComplex());
    }

    /**
     * {@code proj:shape} is [azimuth, range] - the TIFF's [ImageWidth,
     * ImageLength] - which is the transpose of SNAP's raster convention.
     */
    @Test
    public void testRasterDimensionsAreTransposed() {
        assertEquals(512, grd.getRangeSamples());
        assertEquals(1024, grd.getAzimuthLines());
        assertEquals(512, slc.getRangeSamples());
        assertEquals(512, slc.getAzimuthLines());
    }

    @Test
    public void testZeroDopplerTimes() {
        assertEquals("10-NOV-2025 18:21:11.159320", grd.getFirstLineTime().format());
        assertEquals("10-NOV-2025 18:21:11.874747", grd.getLastLineTime().format());
    }

    /**
     * The line time interval has to agree with the first/last line times and
     * the line count, otherwise azimuth time is not a linear function of y and
     * interferometric processing drifts across the frame. It is derived, not
     * taken from {@code iceye:processing_prf} (163774.952 Hz), which differs by
     * about 2%.
     */
    @Test
    public void testLineTimeIntervalIsSelfConsistent() {
        final double span = (grd.getLastLineTime().getMJD() - grd.getFirstLineTime().getMJD()) * 24 * 3600;
        assertEquals(span / (grd.getAzimuthLines() - 1), grd.getLineTimeInterval(), 1e-12);
        assertEquals(0.715427 / 1023, grd.getLineTimeInterval(), 1e-9);
    }

    @Test
    public void testOrbitStateVectors() throws Exception {
        final OrbitStateVector[] vectors = grd.getOrbitStateVectors();
        assertEquals(50, vectors.length);

        final OrbitStateVector first = vectors[0];
        assertEquals("10-NOV-2025 18:20:57.395204", first.time.format());
        assertEquals(1256493.68, first.x_pos, 1e-6);
        assertEquals(-6785812.73, first.y_pos, 1e-6);
        assertEquals(-980007.074, first.z_pos, 1e-6);
        assertEquals(-1698.783, first.x_vel, 1e-6);
        assertEquals(749.488, first.y_vel, 1e-6);
        assertEquals(-7419.8, first.z_vel, 1e-6);
    }

    @Test
    public void testDopplerCentroidCoefficients() {
        final AbstractMetadata.DopplerCentroidCoefficientList[] dop = grd.getDopplerCentroidCoefficients();
        assertEquals(10, dop.length);
        assertEquals("10-NOV-2025 18:21:11.159320", dop[0].time.format());
        assertEquals(1, dop[0].coefficients.length);
        assertEquals(-1851.8329462217184, dop[0].coefficients[0], 1e-9);
        assertEquals(1832.9309111066436, dop[9].coefficients[0], 1e-9);
    }

    /**
     * {@code iceye:incidence_angle_coeffs} is a polynomial in the range sample
     * index. This is the check that establishes which TIFF axis is range: it
     * reproduces the stated near and far angles only when evaluated over
     * {@code proj:shape[1]}.
     */
    @Test
    public void testIncidenceAnglePolynomialRunsAlongRange() {
        assertEquals(28.822174, grd.getIncidenceAngle(0), 1e-5);
        assertEquals(29.243950, grd.getIncidenceAngle(20000), 1e-4);
        assertEquals(29.243945, slc.getIncidenceAngle(15828), 1e-4);

        // increasing with range, as near-to-far requires
        assertTrue(grd.getIncidenceAngle(100) > grd.getIncidenceAngle(0));
    }

    /**
     * The GRD is ground range, so slant range has to come through the
     * ground-to-slant polynomial; the SLC is already slant range.
     */
    @Test
    public void testSlantRange() {
        assertEquals(663357.817622, grd.getSlantRange(0), 1e-3);
        assertEquals(663357.817622, slc.getSlantRange(0), 1e-3);

        final double groundRangeSpacing = 0.25;
        assertEquals(663357.8176216189 + 0.46861423200681296 * (100 * groundRangeSpacing),
                grd.getSlantRange(100), 1e-3);
        assertEquals(663357.817622 + 0.15331 * 100, slc.getSlantRange(100), 1e-3);
    }

    @Test
    public void testAbstractedMetadataHeader() throws Exception {
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(new MetadataElement("root"));
        grd.populateAbstractedMetadata(absRoot);

        assertEquals(Missions.ICEYE, absRoot.getAttributeString(AbstractMetadata.MISSION));
        assertEquals("GRD-COG", absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE));
        assertEquals("spotlight", absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE));
        assertEquals("left", absRoot.getAttributeString(AbstractMetadata.antenna_pointing));
        assertEquals("DESCENDING", absRoot.getAttributeString(AbstractMetadata.PASS));
        assertEquals("VV", absRoot.getAttributeString(AbstractMetadata.mds1_tx_rx_polar));
        assertEquals("ICEYE_I_1.1.4", absRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier));

        assertEquals(512, absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line));
        assertEquals(1024, absRoot.getAttributeInt(AbstractMetadata.num_output_lines));
        assertEquals(1, absRoot.getAttributeInt(AbstractMetadata.range_looks));
        assertEquals(10, absRoot.getAttributeInt(AbstractMetadata.azimuth_looks));

        assertEquals(0.25, absRoot.getAttributeDouble(AbstractMetadata.range_spacing), 1e-9);
        assertEquals(0.25, absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing), 1e-9);
        assertEquals(28.822174, absRoot.getAttributeDouble(AbstractMetadata.incidence_near), 1e-6);
        assertEquals(29.243950, absRoot.getAttributeDouble(AbstractMetadata.incidence_far), 1e-6);
        assertEquals(4363.0, absRoot.getAttributeDouble(AbstractMetadata.avg_scene_height), 1e-6);
        assertEquals(663357.817622, absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel), 1e-6);
        assertEquals(2.8245812401035815e-05,
                absRoot.getAttributeDouble(AbstractMetadata.calibration_factor), 1e-15);

        // frequencies are stored in MHz
        assertEquals(9600.0, absRoot.getAttributeDouble(AbstractMetadata.radar_frequency), 1e-6);
        assertEquals(977.718769, absRoot.getAttributeDouble(AbstractMetadata.range_sampling_rate), 1e-6);
        assertEquals(600.0, absRoot.getAttributeDouble(AbstractMetadata.range_bandwidth), 1e-6);
        assertEquals(139283.143, absRoot.getAttributeDouble(AbstractMetadata.azimuth_bandwidth), 1e-6);
        assertEquals(163774.952,
                absRoot.getAttributeDouble(AbstractMetadata.pulse_repetition_frequency), 1e-6);

        assertEquals("10-NOV-2025 18:21:11.159320",
                absRoot.getAttributeUTC(AbstractMetadata.first_line_time).format());
        assertEquals("18-DEC-2025 10:34:54.992929",
                absRoot.getAttributeUTC(AbstractMetadata.PROC_TIME).format());
        assertEquals("10-NOV-2025 18:20:57.395204",
                absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME).format());

        assertEquals(50, AbstractMetadata.getOrbitStateVectors(absRoot).length);
    }

    @Test
    public void testGrdIsGroundRangeDetectedAndSlcIsSlantRangeComplex() throws Exception {
        final MetadataElement grdRoot = AbstractMetadata.addAbstractedMetadataHeader(new MetadataElement("root"));
        grd.populateAbstractedMetadata(grdRoot);
        assertEquals(1, grdRoot.getAttributeInt(AbstractMetadata.srgr_flag));
        assertEquals(IceyeConstants.DETECTED, grdRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE));
        assertEquals(1, grdRoot.getAttributeInt(AbstractMetadata.multilook_flag));

        final MetadataElement srgr = grdRoot.getElement(AbstractMetadata.srgr_coefficients);
        assertNotNull(srgr);
        final MetadataElement list = srgr.getElementAt(0);
        assertEquals(5, list.getElements().length);
        assertEquals(663357.8176216189,
                list.getElementAt(0).getAttributeDouble(AbstractMetadata.srgr_coef), 1e-6);

        final MetadataElement slcRoot = AbstractMetadata.addAbstractedMetadataHeader(new MetadataElement("root"));
        slc.populateAbstractedMetadata(slcRoot);
        assertEquals(0, slcRoot.getAttributeInt(AbstractMetadata.srgr_flag));
        assertEquals(IceyeConstants.COMPLEX, slcRoot.getAttributeString(AbstractMetadata.SAMPLE_TYPE));
        assertEquals(0, slcRoot.getAttributeInt(AbstractMetadata.multilook_flag));
    }

    /**
     * The delivered timestamps carry five or six fractional digits; truncating
     * them silently would move first_line_time by up to a millisecond.
     */
    @Test
    public void testFractionalSecondsSurviveParsing() throws Exception {
        assertEquals(159320, grd.getFirstLineTime().getMicroSecondsFraction());
        assertEquals(874747, grd.getLastLineTime().getMicroSecondsFraction());
    }

    @Test
    public void testMissingKeyDoesNotThrow() throws Exception {
        final IceyeOpenDataMetadata sparse = IceyeOpenDataMetadata.fromProperties(
                IceyeOpenDataFixtures.parse("{\"sar:product_type\":\"GRD-COG\",\"proj:shape\":[4,2]}"));
        assertEquals("GRD-COG", sparse.getProductType());
        assertEquals(2, sparse.getRangeSamples());
        assertEquals(0, sparse.getOrbitStateVectors().length);
        assertEquals(0, sparse.getDopplerCentroidCoefficients().length);

        // a header built from almost nothing must still be a valid header
        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(new MetadataElement("root"));
        sparse.populateAbstractedMetadata(absRoot);
        assertEquals("GRD-COG", absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE));
        assertEquals(AbstractMetadata.NO_METADATA,
                absRoot.getAttributeInt(AbstractMetadata.REL_ORBIT, AbstractMetadata.NO_METADATA));
    }

    @Test
    public void testUtcParsingAcceptsBothStacForms() throws Exception {
        assertEquals("10-NOV-2025 18:20:58.000000",
                IceyeOpenDataMetadata.parseUTC("2025-11-10T18:20:58Z").format());
        assertEquals("10-NOV-2025 18:21:11.159320",
                IceyeOpenDataMetadata.parseUTC("2025-11-10T18:21:11.15932Z").format());
        assertEquals("10-NOV-2025 18:21:11.159320",
                IceyeOpenDataMetadata.parseUTC("2025-11-10T18:21:11.1593204Z").format());
        assertEquals(null, IceyeOpenDataMetadata.parseUTC(null));
        assertEquals(null, IceyeOpenDataMetadata.parseUTC("not a date"));
    }

    @Test
    public void testProductNameDropsTheExtension() {
        assertEquals("ICEYE_6Q31WW_20251110T182058Z_7010715_X56_SLEDF_crop_GRD", grd.getProductName());
    }

    /**
     * The bounding box derived from proj:transform + proj:shape has to agree
     * with the one ICEYE declares on the STAC item, otherwise the geocoding is
     * being built from the wrong axis order.
     */
    @Test
    public void testDerivedBoundingBoxMatchesTheDeclaredOne() throws Exception {
        final double[] derived = grd.getBoundingBox();
        final org.json.simple.JSONArray declared = (org.json.simple.JSONArray)
                IceyeOpenDataFixtures.stacItem(IceyeOpenDataFixtures.grdStacItem()).get("bbox");

        assertEquals(4, derived.length);
        for (int i = 0; i < 4; ++i) {
            assertEquals(((Number) declared.get(i)).doubleValue(), derived[i], 1e-9);
        }
    }

    @Test
    public void testGeoTransformIsColumnThenRow() {
        final double[] transform = grd.getGeoTransform();
        assertEquals(6, transform.length);

        // lon = t[0] + t[1]*column + t[2]*row, lat = t[3] + t[4]*column + t[5]*row
        final double[] bbox = grd.getBoundingBox();
        final double lon = transform[0] + transform[1] * (grd.getAzimuthLines() - 1)
                + transform[2] * (grd.getRangeSamples() - 1);
        final double lat = transform[3] + transform[4] * (grd.getAzimuthLines() - 1)
                + transform[5] * (grd.getRangeSamples() - 1);

        assertTrue("lon " + lon + " outside " + bbox[0] + ".." + bbox[2], lon >= bbox[0] && lon <= bbox[2]);
        assertTrue("lat " + lat + " outside " + bbox[1] + ".." + bbox[3], lat >= bbox[1] && lat <= bbox[3]);
    }

    @Test
    public void testProductDataTypes() {
        assertEquals(ProductData.TYPE_UINT16, grd.getRasterDataType());
        assertEquals(ProductData.TYPE_FLOAT32, slc.getRasterDataType());
        assertEquals(1, grd.getBandCount());
        assertEquals(2, slc.getBandCount());
    }
}
