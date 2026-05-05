package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.grid.*;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;


public class CimrGridProductTest {


    @Test
    public void testAddAndGetBands() {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid grid = new CimrGrid(proj, 2,1);

        CimrGridProduct product = new CimrGridProduct(grid);

        CimrBandDescriptor band1 = new CimrBandDescriptor(
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        CimrBandDescriptor band2 = new CimrBandDescriptor(
                "X_raw_bt_v_feed1", "raw_bt_h", CimrFrequencyBand.X_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                2, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        double[] data1 = {1.0, 2.0};
        double[] data2 = {10.0, 20.0};
        GridBandDataSource ds1 = new CimrGridBandDataSource(2, 1, data1);
        GridBandDataSource ds2 = new CimrGridBandDataSource(2, 1, data2);

        product.addBand(band1, ds1);
        product.addBand(band2, ds2);

        assertEquals(2, product.getBandCount());
        assertSame(grid, product.getGlobalGrid());
        assertEquals(ds1, product.getBandData(band1));
        assertEquals(2, product.getBands().size());

        assertEquals(1.0, product.getBandData(band1).getSample(0, 0), 1e-12);
        assertEquals(20.0, product.getBandData(band2).getSample(1, 0), 1e-12);
    }

    @Test
    public void testBuildLazyCreatesBandsFromDescriptorSet() {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 2, 1);

        CimrBandDescriptor tieDesc = new CimrBandDescriptor(
                "altitude", "altitude", CimrFrequencyBand.C_BAND,
                new String[] {"lat", "lon"}, new String[] {""},
                "/Geolocation/", 0,
                CimrDescriptorKind.TIEPOINT_VARIABLE,
                new String[] {"n_scans", "n_tie_points_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        CimrBandDescriptor measDesc = new CimrBandDescriptor(
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {"lat", "lon"}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/", 0,
                CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );

        CimrDescriptorSet descriptorSet = new CimrDescriptorSet(
                List.of(measDesc),
                java.util.Collections.emptyList(),
                List.of(tieDesc)
        );

        CimrReaderContext context = new CimrReaderContext(
                null, descriptorSet, grid, null, null
        );

        CimrGridProduct product = CimrGridProduct.buildLazy(context, true);

        assertSame(grid, product.getGlobalGrid());
        assertEquals(2, product.getBandCount());
        assertTrue(product.getBands().containsKey(tieDesc));
        assertTrue(product.getBands().containsKey(measDesc));
        assertTrue(product.getBandData(tieDesc) instanceof LazyGridBandDataSource);
        assertTrue(product.getBandData(measDesc) instanceof LazyGridBandDataSource);
    }


    @Test(expected = UnsupportedOperationException.class)
    public void testGetBandsIsUnmodifiable() {
        PlateCarreeProjection proj = new PlateCarreeProjection(2, 1, 0.0, 1.0, 1.0, 1.0);
        CimrGrid grid = new CimrGrid(proj, 2, 1);
        CimrGridProduct product = new CimrGridProduct(grid);

        CimrBandDescriptor band = new CimrBandDescriptor(
                "C_raw_bt_h_feed1", "raw_bt_h", CimrFrequencyBand.C_BAND,
                new String[] {""}, new String[] {""},
                "/Data/Measurement_Data/C_BAND/",
                1, CimrDescriptorKind.VARIABLE,
                new String[] {"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        GridBandDataSource ds = new CimrGridBandDataSource(2, 1, new double[]{1.0, 2.0});
        product.addBand(band, ds);

        product.getBands().put(band, ds);
    }

}