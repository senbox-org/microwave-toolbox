package eu.esa.snap.cimr.cimr;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.grid.CimrGridBandDataSource;
import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.PlateCarreeProjection;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.IndexCoding;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import java.awt.image.Raster;

import static org.junit.Assert.*;


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
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        double[] data = {1.0, 2.0};
        GridBandDataSource ds = new CimrGridBandDataSource(2, 1, data);
        gridProduct.addBand(bandDesc, ds);

        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, "path", "L_BAND:C_BAND");

        assertEquals(2, product.getSceneRasterWidth());
        assertEquals(1, product.getSceneRasterHeight());
        assertNotNull(product.getSceneGeoCoding());

        Band band = product.getBand("C_raw_bt_h_feed1");
        assertNotNull(band);
        assertEquals(ProductData.TYPE_FLOAT64, band.getDataType());

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
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "float", ProductData.TYPE_FLOAT32, "", ""
        );

        GridBandDataSource ds = new CimrGridBandDataSource(2, 1, new double[] {1.0, 2.0});
        gridProduct.addBand(bandDesc, ds);

        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, "path", "L_BAND:C_BAND");

        assertEquals(ProductData.TYPE_FLOAT32, product.getBand("C_raw_bt_h_feed1").getDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuildSnapProduct_setsInstrumentStatusIndexCoding() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid cimrGrid = new CimrGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(cimrGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_BAND_instrument_status_feed1", "instrument_status", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND"},
                "int", ProductData.TYPE_INT32, "", "Instrument Calibration or Observation mode, for all samples",
                instrumentStatusCoding()
        );

        GridBandDataSource ds = new CimrGridBandDataSource(2, 1, new double[] {0.0, 4.0});
        gridProduct.addBand(bandDesc, ds);

        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, "path", "L_BAND:C_BAND");

        Band band = product.getBand("C_BAND_instrument_status_feed1");
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
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "K",
                "Brightness temperature of the Earth, in H polarization, from raw counts (no RFI mitigation)"
        );

        double[] data = {1.0, 2.0};
        GridBandDataSource ds = new CimrGridBandDataSource(2, 1, data);
        gridProduct.addBand(bandDesc, ds);

        String path = "some\\path\\file.nc";
        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, path, "L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND");

        assertEquals("TEST", product.getName());
        assertEquals("CIMR_GRID", product.getProductType());
        assertNotNull(product.getFileLocation());
        assertTrue(product.getFileLocation().getPath().endsWith(path));
        assertEquals("L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND", product.getAutoGrouping().toString());

        Band band = product.getBand("C_raw_bt_h_feed1");
        assertEquals("K", band.getUnit());
        assertEquals("Brightness temperature of the Earth, in H polarization, from raw counts (no RFI mitigation)", band.getDescription());
        assertFalse(band.isNoDataValueSet());
        assertEquals(43300000f, band.getSpectralWavelength(), doubleErr);
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

        GridBandDataSource ds1 = new CimrGridBandDataSource(2, 1, new double[]{1.0, 2.0});
        GridBandDataSource ds2 = new CimrGridBandDataSource(2, 1, new double[]{10.0, 20.0});

        gridProduct.addBand(band1, ds1);
        gridProduct.addBand(band2, ds2);

        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, "path", "L_BAND:C_BAND");

        assertEquals(2, product.getNumBands());

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

        Product product = CimrSnapProductBuilder.buildProduct("EMPTY", "CIMR_GRID", gridProduct, "path", "L_BAND:C_BAND");

        assertEquals(4, product.getSceneRasterWidth());
        assertEquals(2, product.getSceneRasterHeight());
        assertNotNull(product.getSceneGeoCoding());
        assertEquals(0, product.getNumBands());
    }
}