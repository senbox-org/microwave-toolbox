package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.GlobalGridBandDataSource;
import eu.esa.snap.cimr.grid.GlobalGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.PlateCarreeProjection;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
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
        GlobalGrid globalGrid = new GlobalGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(globalGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        double[] data = {1.0, 2.0};
        GridBandDataSource ds = new GlobalGridBandDataSource(2, 1, data);
        gridProduct.addBand(bandDesc, ds);

        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, "path");

        assertEquals(2, product.getSceneRasterWidth());
        assertEquals(1, product.getSceneRasterHeight());
        assertNotNull(product.getSceneGeoCoding());

        Band band = product.getBand("C_raw_bt_h_feed1");
        assertNotNull(band);

        Raster raster = band.getSourceImage().getImage(0).getData();
        assertEquals(1.0, raster.getSampleDouble(0,0,0), doubleErr);
        assertEquals(2.0, raster.getSampleDouble(1,0,0), doubleErr);
    }

    @Test
    public void testBuildSnapProduct_setsMetadataAndAutoGrouping() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        GlobalGrid globalGrid = new GlobalGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(globalGrid);

        CimrBandDescriptor bandDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "K",
                "Brightness temperature of the Earth, in H polarization, from raw counts (no RFI mitigation)"
        );

        double[] data = {1.0, 2.0};
        GridBandDataSource ds = new GlobalGridBandDataSource(2, 1, data);
        gridProduct.addBand(bandDesc, ds);

        String path = "some\\path\\file.nc";
        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, path);

        assertEquals("TEST", product.getName());
        assertEquals("CIMR_GRID", product.getProductType());
        assertNotNull(product.getFileLocation());
        assertTrue(product.getFileLocation().getPath().endsWith(path));
        assertEquals("L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND", product.getAutoGrouping().toString());

        Band band = product.getBand("C_raw_bt_h_feed1");
        assertEquals("K", band.getUnit());
        assertEquals("Brightness temperature of the Earth, in H polarization, from raw counts (no RFI mitigation)", band.getDescription());
        assertEquals(Double.NaN, band.getNoDataValue(), doubleErr);
        assertTrue(band.isNoDataValueSet());
        assertEquals(43300000f, band.getSpectralWavelength(), doubleErr);
    }

    @Test
    public void testBuildSnapProduct_withMultipleBands_allPresentAndCorrect() throws Exception {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        GlobalGrid globalGrid = new GlobalGrid(proj, 2, 1);

        CimrGridProduct gridProduct = new CimrGridProduct(globalGrid);

        CimrBandDescriptor band1 = new CimrBandDescriptor(
                "band1", "raw1", CimrFrequencyBand.C_BAND,
                new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        CimrBandDescriptor band2 = new CimrBandDescriptor(
                "band2", "raw2", CimrFrequencyBand.X_BAND,
                new String[] {""},
                "/Data/Measurement_Data/X_BAND/",
                0, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_X_BAND", "n_feeds_X_BAND"},
                "double", "", ""
        );

        GridBandDataSource ds1 = new GlobalGridBandDataSource(2, 1, new double[]{1.0, 2.0});
        GridBandDataSource ds2 = new GlobalGridBandDataSource(2, 1, new double[]{10.0, 20.0});

        gridProduct.addBand(band1, ds1);
        gridProduct.addBand(band2, ds2);

        Product product = CimrSnapProductBuilder.buildProduct("TEST", "CIMR_GRID", gridProduct, "path");

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
        GlobalGrid globalGrid = new GlobalGrid(proj, 4, 2);

        CimrGridProduct gridProduct = new CimrGridProduct(globalGrid);

        Product product = CimrSnapProductBuilder.buildProduct("EMPTY", "CIMR_GRID", gridProduct, "path");

        assertEquals(4, product.getSceneRasterWidth());
        assertEquals(2, product.getSceneRasterHeight());
        assertNotNull(product.getSceneGeoCoding());
        assertEquals(0, product.getNumBands());
    }
}