package eu.esa.snap.cimr.cimr;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.dddb.descriptor.CimrFrequencyBand;
import eu.esa.snap.cimr.dddb.descriptor.CimrSampleCoding;
import eu.esa.snap.cimr.dddb.descriptor.CimrSampleCodingEntry;
import eu.esa.snap.cimr.grid.CimrGridBandDataSource;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.PlateCarreeProjection;
import eu.esa.snap.cimr.metadata.CimrProductMetadata;
import eu.esa.snap.cimr.test.CimrGridTestData;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.Mask;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.awt.image.Raster;
import java.nio.file.Paths;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class CimrSnapProductBuilderTest {


    private static final double doubleErr = 1e-6;


    @Test
    public void testBuildSnapProduct_createsBandsAndValues() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed_1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        GridBandDataSource ds = CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 1.0, 2.0);
        gridProduct.addBand(bandDesc, ds);

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        assertEquals(2, product.getSceneRasterWidth());
        assertEquals(1, product.getSceneRasterHeight());
        assertNotNull(product.getSceneGeoCoding());

        Band band = product.getBand("C_raw_bt_h_feed_1");
        assertNotNull(band);
        assertEquals(ProductData.TYPE_FLOAT64, band.getDataType());
        assertEquals(coverageExpression(bandDesc), band.getValidPixelExpression());

        Raster raster = band.getSourceImage().getImage(0).getData();
        assertEquals(1.0, raster.getSampleDouble(0,0,0), doubleErr);
        assertEquals(2.0, raster.getSampleDouble(1,0,0), doubleErr);
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_usesDescriptorRasterDataType() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed_1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "float", ProductData.TYPE_FLOAT32, "", ""
        );

        GridBandDataSource ds = CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT32, 1.0, 2.0);
        gridProduct.addBand(bandDesc, ds);

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        assertEquals(ProductData.TYPE_FLOAT32, product.getBand("C_raw_bt_h_feed_1").getDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_addsCoverageBandAndMaskPerFeedGeometry() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(3, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid cimrGrid = new CimrGrid(proj, 3, 1);
        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed_1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {"geometry_1"}, new String[] {"footprint_1"},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        CimrGridBandDataSource ds = CimrGridBandDataSource.createEmpty(3, 1, ProductData.TYPE_FLOAT64);
        ds.setValidSample(0, 0, 10.0);
        ds.setInvalidSample(1, 0);
        gridProduct.addBand(bandDesc, ds);

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        String coverageBandName = coverageBandName(bandDesc);
        Band coverageBand = product.getBand(coverageBandName);
        assertNotNull(coverageBand);
        assertEquals(ProductData.TYPE_UINT8, coverageBand.getDataType());
        assertTrue(coverageBand.isStxSet());
        assertEquals(0.0, coverageBand.getStx().getMinimum(), doubleErr);
        assertEquals(1.0, coverageBand.getStx().getMaximum(), doubleErr);
        assertTrue(Double.isNaN(coverageBand.getStx().getMean()));
        assertTrue(Double.isNaN(coverageBand.getStx().getStandardDeviation()));
        assertNotNull(coverageBand.getImageInfo());
        assertEquals(0.0, coverageBand.getImageInfo().getColorPaletteDef().getMinDisplaySample(), doubleErr);
        assertEquals(1.0, coverageBand.getImageInfo().getColorPaletteDef().getMaxDisplaySample(), doubleErr);
        assertEquals(256, coverageBand.getImageInfo().getColorPaletteDef().getNumColors());
        assertFalse(coverageBand.getImageInfo().getColorPaletteDef().isDiscrete());

        Raster coverageRaster = coverageBand.getSourceImage().getImage(0).getData();
        assertEquals(1.0, coverageRaster.getSampleDouble(0, 0, 0), doubleErr);
        assertEquals(1.0, coverageRaster.getSampleDouble(1, 0, 0), doubleErr);
        assertEquals(0.0, coverageRaster.getSampleDouble(2, 0, 0), doubleErr);

        Band measurementBand = product.getBand(bandDesc.getName());
        assertTrue(measurementBand.isNoDataValueUsed());
        assertTrue(Double.isNaN(measurementBand.getNoDataValue()));
        assertEquals(coverageExpression(bandDesc), measurementBand.getValidPixelExpression());

        Raster measurementRaster = measurementBand.getSourceImage().getImage(0).getData();
        assertEquals(10.0, measurementRaster.getSampleDouble(0, 0, 0), doubleErr);
        assertTrue(Double.isNaN(measurementRaster.getSampleDouble(1, 0, 0)));
        assertTrue(Double.isNaN(measurementRaster.getSampleDouble(2, 0, 0)));

        // A valid pixel requires both geometry coverage and a non-NoData measurement value.
        assertTrue(measurementBand.isPixelValid(0, 0));
        assertFalse(measurementBand.isPixelValid(1, 0));
        assertFalse(measurementBand.isPixelValid(2, 0));

        Mask coverageMask = product.getMaskGroup().get(coverageMaskName(bandDesc));
        assertNotNull(coverageMask);
        assertEquals(coverageExpression(bandDesc), Mask.BandMathsType.getExpression(coverageMask));
        assertTrue(coverageMask.isStxSet());
        assertEquals(0.0, coverageMask.getStx().getMinimum(), doubleErr);
        assertEquals(255.0, coverageMask.getStx().getMaximum(), doubleErr);
        assertTrue(Double.isNaN(coverageMask.getStx().getMean()));
        assertTrue(Double.isNaN(coverageMask.getStx().getStandardDeviation()));
        assertNotNull(coverageMask.getImageInfo());
        assertEquals(0.0, coverageMask.getImageInfo().getColorPaletteDef().getMinDisplaySample(), doubleErr);
        assertEquals(255.0, coverageMask.getImageInfo().getColorPaletteDef().getMaxDisplaySample(), doubleErr);
        assertEquals(256, coverageMask.getImageInfo().getColorPaletteDef().getNumColors());
        assertFalse(coverageMask.getImageInfo().getColorPaletteDef().isDiscrete());

        Raster maskRaster = coverageMask.getSourceImage().getImage(0).getData();
        assertEquals(255.0, maskRaster.getSampleDouble(0, 0, 0), doubleErr);
        assertEquals(255.0, maskRaster.getSampleDouble(1, 0, 0), doubleErr);
        assertEquals(0.0, maskRaster.getSampleDouble(2, 0, 0), doubleErr);
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_reusesCoverageForSameFeedGeometry() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);
        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor band1 = new CimrBandDescriptor(
                "C_band1_feed_1", "band1", CimrFrequencyBand.C_BAND,
                new String[] {"geometry_1"}, new String[] {"footprint_1"},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        CimrBandDescriptor band2 = new CimrBandDescriptor(
                "C_band2_feed_1", "band2", CimrFrequencyBand.C_BAND,
                new String[] {"geometry_1"}, new String[] {"footprint_1"},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        gridProduct.addBand(band1, CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 1.0, 2.0));
        gridProduct.addBand(band2, CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 3.0, 4.0));

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        assertNotNull(product.getBand(coverageBandName(band1)));
        assertEquals(coverageExpression(band1), product.getBand("C_band1_feed_1").getValidPixelExpression());
        assertEquals(coverageExpression(band1), product.getBand("C_band2_feed_1").getValidPixelExpression());
        assertEquals(3, product.getNumBands());
        assertEquals(1, product.getMaskGroup().getNodeCount());
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_reusesCoverageForSameBandAndFeedEvenWithDifferentDescriptorGeometry() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);
        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor band1 = new CimrBandDescriptor(
                "L_band1_feed0", "band1", CimrFrequencyBand.L_BAND,
                new String[] {"L_geometry_a_lat", "L_geometry_a_lon"}, new String[] {"L_footprint_a"},
                "/Data/Measurement_Data/L_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_L_BAND", "n_feeds_L_BAND"},
                "double", "", ""
        );
        CimrBandDescriptor band2 = new CimrBandDescriptor(
                "L_band2_feed0", "band2", CimrFrequencyBand.L_BAND,
                new String[] {"L_geometry_b_lat", "L_geometry_b_lon"}, new String[] {"L_footprint_b"},
                "/Data/Measurement_Data/L_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_L_BAND", "n_feeds_L_BAND"},
                "double", "", ""
        );

        gridProduct.addBand(band1, CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 1.0, 2.0));
        gridProduct.addBand(band2, CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 3.0, 4.0));

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        assertEquals(coverageBandName(band1), coverageBandName(band2));
        assertNotNull(product.getBand(coverageBandName(band1)));
        assertEquals(coverageExpression(band1), product.getBand("L_band1_feed0").getValidPixelExpression());
        assertEquals(coverageExpression(band1), product.getBand("L_band2_feed0").getValidPixelExpression());
        assertEquals(3, product.getNumBands());
        assertEquals(1, product.getMaskGroup().getNodeCount());
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_setsInstrumentStatusIndexCoding() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_BAND_instrument_status_feed_1", "instrument_status", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND"},
                "int", ProductData.TYPE_INT32, "", "Instrument Calibration or Observation mode, for all samples",
                instrumentStatusCoding()
        );

        GridBandDataSource ds = CimrGridTestData.grid(2, 1, ProductData.TYPE_INT32, 0.0, 4.0);
        gridProduct.addBand(bandDesc, ds);

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        Band band = product.getBand("C_BAND_instrument_status_feed_1");
        assertNotNull(band);
        assertEquals(ProductData.TYPE_INT32, band.getDataType());

        IndexCoding indexCoding = band.getIndexCoding();
        assertNotNull(indexCoding);
        assertSame(product.getIndexCodingGroup().get("instrument_status"), indexCoding);
        assertEquals(0, indexCoding.getIndexValue("forward_scan_observation"));
        assertEquals(1, indexCoding.getIndexValue("backward_scan_observation"));
        assertEquals(2, indexCoding.getIndexValue("forward_external_cold_sky_observation"));
        assertEquals(3, indexCoding.getIndexValue("backward_external_cold_sky_observation"));
        assertEquals(4, indexCoding.getIndexValue("hot_load_calibration"));
        assertEquals(5, indexCoding.getIndexValue("active_cold_load_calibration"));

        Raster overview = band.getSourceImage().getImage(1).getData();
        assertEquals(0.0, overview.getSampleDouble(0, 0, 0), doubleErr);
    }

    private static CimrSampleCoding instrumentStatusCoding() {
        return new CimrSampleCoding(CimrSampleCoding.Type.INDEX, "instrument_status", new CimrSampleCodingEntry[]{
                new CimrSampleCodingEntry("forward_scan_observation", 0, "Forward scan observation"),
                new CimrSampleCodingEntry("backward_scan_observation", 1, "Backward scan observation"),
                new CimrSampleCodingEntry("forward_external_cold_sky_observation", 2, "Forward external cold sky observation"),
                new CimrSampleCodingEntry("backward_external_cold_sky_observation", 3, "Backward external cold sky observation"),
                new CimrSampleCodingEntry("hot_load_calibration", 4, "Hot load calibration"),
                new CimrSampleCodingEntry("active_cold_load_calibration", 5, "Active cold load calibration")
        });
    }

    @Test
    public void testBuildSnapProduct_setsMetadataAndAutoGrouping() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed_1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "K",
                "Brightness temperature of the Earth, in H polarization, from raw counts (no RFI mitigation)"
        );

        GridBandDataSource ds = CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 1.0, 2.0);
        gridProduct.addBand(bandDesc, ds);

        String path = Paths.get("some", "path", "file.nc").toString();
        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), path, "L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND");

        assertEquals("TEST", product.getName());
        assertEquals("CIMR_GRID", product.getProductType());
        assertNotNull(product.getFileLocation());
        assertTrue(product.getFileLocation().getPath().endsWith(path));
        assertEquals("L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND", product.getAutoGrouping().toString());

        Band band = product.getBand("C_raw_bt_h_feed_1");
        assertEquals("K", band.getUnit());
        assertEquals("Brightness temperature of the Earth, in H polarization, from raw counts (no RFI mitigation)", band.getDescription());
        assertTrue(band.isNoDataValueSet());
        assertTrue(band.isNoDataValueUsed());
        assertTrue(Double.isNaN(band.getNoDataValue()));
        assertEquals(43300000f, band.getSpectralWavelength(), doubleErr);
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_setsNanNoDataForFloatingPointBandsOnly() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);
        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor floatBand = new CimrBandDescriptor(
                "float_band", "raw_float", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "float", ProductData.TYPE_FLOAT32, "", ""
        );
        CimrBandDescriptor intBand = new CimrBandDescriptor(
                "int_band", "raw_int", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND"},
                "int", ProductData.TYPE_INT32, "", ""
        );

        gridProduct.addBand(floatBand, CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT32, 1.0, 2.0));
        gridProduct.addBand(intBand, CimrGridTestData.grid(2, 1, ProductData.TYPE_INT32, 3.0, 4.0));

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        Band floatSnapBand = product.getBand("float_band");
        assertTrue(floatSnapBand.isNoDataValueSet());
        assertTrue(floatSnapBand.isNoDataValueUsed());
        assertTrue(Double.isNaN(floatSnapBand.getNoDataValue()));

        Band intSnapBand = product.getBand("int_band");
        assertFalse(intSnapBand.isNoDataValueUsed());
        assertEquals(coverageExpression(floatBand), intSnapBand.getValidPixelExpression());
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_fromProductMetadataAddsMetadataElement() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);
        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);
        MetadataElement metadataElement = new MetadataElement("CIMR_Metadata");
        metadataElement.addElement(new MetadataElement("Global_Attributes"));
        CimrProductMetadata metadata = new CimrProductMetadata("TEST_PRODUCT", "CIMR_L1B", metadataElement);

        Product product = buildProduct(gridProduct, metadata, "path", "L_BAND:C_BAND");

        assertEquals("TEST_PRODUCT", product.getName());
        assertEquals("CIMR_L1B", product.getProductType());
        assertSame(metadataElement, product.getMetadataRoot().getElement("CIMR_Metadata"));
    }

    @Test
    public void testBuildSnapProduct_withMultipleBands_allPresentAndCorrect() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor band1 = new CimrBandDescriptor(
                "band1", "raw1", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        CimrBandDescriptor band2 = new CimrBandDescriptor(
                "band2", "raw2", CimrFrequencyBand.X_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/X_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_X_BAND", "n_feeds_X_BAND"},
                "double", "", ""
        );

        GridBandDataSource ds1 = CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 1.0, 2.0);
        GridBandDataSource ds2 = CimrGridTestData.grid(2, 1, ProductData.TYPE_FLOAT64, 10.0, 20.0);

        gridProduct.addBand(band1, ds1);
        gridProduct.addBand(band2, ds2);

        Product product = buildProduct(gridProduct, metadata("TEST", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        assertEquals(4, product.getNumBands());

        Band b1 = product.getBand("band1");
        Band b2 = product.getBand("band2");
        assertNotNull(b1);
        assertNotNull(b2);

        assertEquals(1.0, b1.getSourceImage().getImage(0).getData().getSampleDouble(0, 0, 0), 1e-6);
        assertEquals(2.0, b1.getSourceImage().getImage(0).getData().getSampleDouble(1, 0, 0), 1e-6);
        assertEquals(10.0, b2.getSourceImage().getImage(0).getData().getSampleDouble(0, 0, 0), 1e-6);
        assertEquals(20.0, b2.getSourceImage().getImage(0).getData().getSampleDouble(1, 0, 0), 1e-6);
    }

    @Test
    public void testBuildSnapProduct_withNoBands_createsEmptyProductWithGeoCoding() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                4, 2,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid cimrGrid = new CimrGrid(proj, 4, 2);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        Product product = buildProduct(gridProduct, metadata("EMPTY", "CIMR_GRID"), "path", "L_BAND:C_BAND");

        assertEquals(4, product.getSceneRasterWidth());
        assertEquals(2, product.getSceneRasterHeight());
        assertNotNull(product.getSceneGeoCoding());
        assertEquals(0, product.getNumBands());
    }


    private static CimrProductMetadata metadata(String productName, String productType) {
        return new CimrProductMetadata(productName, productType, new MetadataElement("CIMR_Metadata"));
    }

    private static Product buildProduct(CimrGridProduct gridProduct, CimrProductMetadata metadata, String path, String autoGrouping) throws Exception {
        CimrReaderContext readerContext = mock(CimrReaderContext.class);
        when(readerContext.getAutoGrouping()).thenReturn(autoGrouping);
        return CimrSnapProductBuilder.buildProduct(readerContext, gridProduct, metadata, path);
    }

    private static String coverageExpression(CimrBandDescriptor descriptor) {
        return coverageBandName(descriptor) + " > 0.0";
    }

    private static String coverageBandName(CimrBandDescriptor descriptor) {
        return coverageName(descriptor, "coverage");
    }

    private static String coverageMaskName(CimrBandDescriptor descriptor) {
        return coverageName(descriptor, "coverage_mask");
    }

    private static String coverageName(CimrBandDescriptor descriptor, String name) {
        return descriptor.getBand().name() + "_" + name + "_feed_" + (descriptor.getFeedIndex() + 1);
    }
}
