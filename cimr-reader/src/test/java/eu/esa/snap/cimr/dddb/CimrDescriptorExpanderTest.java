package eu.esa.snap.cimr.dddb;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorSet;
import eu.esa.snap.cimr.dddb.descriptor.CimrFrequencyBand;
import eu.esa.snap.cimr.dddb.descriptor.CimrSampleCoding;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrDescriptorExpanderTest {


    @Test
    @STTM("SNAP-4262")
    public void expand_createsDescriptorsForAllV5BandsAndFeeds() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");

        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        assertEquals(1275, descriptorSet.getMeasurements().size());
        assertEquals(50, descriptorSet.getGeometries().size());
        assertEquals(200, descriptorSet.getTiepointVariables().size());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsMeasurementDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getMeasurementByName("C_BAND_brightness_temperature_h_feed_4");

        assertNotNull(descriptor);
        assertEquals("brightness_temperature_h", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.C_BAND, descriptor.getBand());
        assertEquals("/Data/Measurement_Data/C_BAND/", descriptor.getGroupPath());
        assertEquals(3, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertEquals("K", descriptor.getUnit());
        assertArrayEquals(new String[]{"C_BAND_latitude_feed_4", "C_BAND_longitude_feed_4"}, descriptor.getGeometryNames());
        assertArrayEquals(new String[]{
                "C_BAND_footprint_minor_axis_feed_4",
                "C_BAND_footprint_major_axis_feed_4",
                "C_BAND_geometric_rot_angle_feed_4"
        }, descriptor.getFootprintVars());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsGeometryDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getGeometryByName("KA_BAND_longitude_feed_8");

        assertNotNull(descriptor);
        assertEquals("longitude", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.KA_BAND, descriptor.getBand());
        assertEquals("/Data/Navigation_Data/KA_BAND/", descriptor.getGroupPath());
        assertEquals(7, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.GEOMETRY, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_tie_points_KA_BAND", "n_feeds_KA_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertEquals("degrees_east", descriptor.getUnit());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsFootprintDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getTpVariableByName("KU_BAND_footprint_minor_axis_feed_8");

        assertNotNull(descriptor);
        assertEquals("footprint_minor_axis", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.KU_BAND, descriptor.getBand());
        assertEquals("/Data/Measurement_Data/KU_BAND/", descriptor.getGroupPath());
        assertEquals(7, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.TIEPOINT_VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_KU_BAND", "n_feeds_KU_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertEquals("m", descriptor.getUnit());
        assertArrayEquals(new String[]{"KU_BAND_latitude_feed_8", "KU_BAND_longitude_feed_8"}, descriptor.getGeometryNames());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsCalibrationDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getMeasurementByName("KA_BAND_raw_counts_h_feed_8");

        assertNotNull(descriptor);
        assertEquals("raw_counts_h", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.KA_BAND, descriptor.getBand());
        assertEquals("/Data/Calibration_Data/KA_BAND/", descriptor.getGroupPath());
        assertEquals(7, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_KA_BAND", "n_feeds_KA_BAND"}, descriptor.getDimensions());
        assertEquals("int", descriptor.getDataType());
        assertArrayEquals(new String[]{"KA_BAND_latitude_feed_8", "KA_BAND_longitude_feed_8"}, descriptor.getGeometryNames());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsNoFeedMeasurementDescriptorForEachFeed() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getMeasurementByName("KU_BAND_tsu_feed_8");

        assertNotNull(descriptor);
        assertEquals("tsu", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.KU_BAND, descriptor.getBand());
        assertEquals("/Data/Measurement_Data/KU_BAND/", descriptor.getGroupPath());
        assertEquals(7, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_KU_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertEquals("K", descriptor.getUnit());
        assertArrayEquals(new String[]{"KU_BAND_latitude_feed_8", "KU_BAND_longitude_feed_8"}, descriptor.getGeometryNames());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsInstrumentStatusDescriptorForEachFeed() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getMeasurementByName("C_BAND_instrument_status_feed_4");

        assertNotNull(descriptor);
        assertEquals("instrument_status", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.C_BAND, descriptor.getBand());
        assertEquals("/Data/Measurement_Data/C_BAND/", descriptor.getGroupPath());
        assertEquals(3, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_C_BAND"}, descriptor.getDimensions());
        assertEquals("int", descriptor.getDataType());
        assertArrayEquals(new String[]{"C_BAND_latitude_feed_4", "C_BAND_longitude_feed_4"}, descriptor.getGeometryNames());
        assertNotNull(descriptor.getSampleCoding());
        assertEquals(CimrSampleCoding.Type.INDEX, descriptor.getSampleCoding().getType());
        assertEquals("instrument_status", descriptor.getSampleCoding().getName());
        assertEquals(6, descriptor.getSampleCoding().getEntries().length);
        assertEquals("hot_load_calibration", descriptor.getSampleCoding().getEntries()[4].getName());
        assertEquals(4, descriptor.getSampleCoding().getEntries()[4].getValue());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsNavigationTiepointDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getTpVariableByName("X_BAND_direct_sun_angle_feed_4");

        assertNotNull(descriptor);
        assertEquals("direct_sun_angle", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.X_BAND, descriptor.getBand());
        assertEquals("/Data/Navigation_Data/X_BAND/", descriptor.getGroupPath());
        assertEquals(3, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.TIEPOINT_VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_tie_points_X_BAND", "n_feeds_X_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertArrayEquals(new String[]{"X_BAND_latitude_feed_4", "X_BAND_longitude_feed_4"}, descriptor.getGeometryNames());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_propagatesOptionalFillValueFromDddbFamily() throws Exception {
        CimrProductDescriptor productDescriptor = new CimrProductDescriptor();
        productDescriptor.setDescriptorFiles(new String[]{"measurements.json"});

        CimrBandDefinition band = new CimrBandDefinition();
        band.setName("C_BAND");
        band.setFeedCount(1);
        band.setFeedDimension("n_feeds_C_BAND");
        band.setSampleDimension("n_samples_C_BAND");
        band.setTiePointDimension("n_tie_points_C_BAND");

        CimrVariableFamily family = new CimrVariableFamily();
        family.setKind("VARIABLE");
        family.setValueVarName("brightness_temperature_h");
        family.setGroupPath("/Data/Measurement_Data/{band}/");
        family.setDimensions(new String[]{"n_scans", "{sampleDimension}", "{feedDimension}"});
        family.setDataType("float");
        family.setUnit("K");
        family.setDescription("Brightness temperature for {band}");
        family.setGeometryVariables(new String[]{"latitude", "longitude"});
        family.setFillValue(-9999.0);

        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(new StaticDDDB(band, family)).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getMeasurementByName("C_BAND_brightness_temperature_h_feed_1");
        assertNotNull(descriptor);
        assertTrue(descriptor.hasFillValue());
        assertEquals(-9999.0, descriptor.getFillValue(), 0.0);
        assertTrue(descriptor.isInvalidSampleValue(-9999.0));
        assertFalse(descriptor.isInvalidSampleValue(120.0));
        assertEquals("Brightness temperature for C_BAND", descriptor.getDescription());
    }

    private static class StaticDDDB extends CimrDDDB {
        private final CimrBandDefinition bandDefinition;
        private final CimrVariableFamily family;

        StaticDDDB(CimrBandDefinition bandDefinition, CimrVariableFamily family) {
            this.bandDefinition = bandDefinition;
            this.family = family;
        }

        @Override
        public CimrBandDefinition[] getBandDefinitions(CimrProductDescriptor productDescriptor) {
            return new CimrBandDefinition[]{bandDefinition};
        }

        @Override
        public CimrVariableFamily[] getDescriptorFile(CimrProductDescriptor productDescriptor, String resourceName) {
            return new CimrVariableFamily[]{family};
        }
    }
}
