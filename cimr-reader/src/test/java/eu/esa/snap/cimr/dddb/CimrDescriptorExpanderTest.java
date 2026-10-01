package eu.esa.snap.cimr.dddb;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrDescriptorExpanderTest {


    @Test
    @STTM("SNAP-4262")
    public void expand_createsDescriptorsForAllV5BandsAndFeeds() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");

        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        assertEquals(200, descriptorSet.getMeasurements().size());
        assertEquals(50, descriptorSet.getGeometries().size());
        assertEquals(75, descriptorSet.getTiepointVariables().size());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsMeasurementDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getMeasurementByName("C_BAND_brightness_temperature_h_feed4");

        assertNotNull(descriptor);
        assertEquals("brightness_temperature_h", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.C_BAND, descriptor.getBand());
        assertEquals("/Data/Measurement_Data/C_BAND/", descriptor.getGroupPath());
        assertEquals(3, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertEquals("K", descriptor.getUnit());
        assertArrayEquals(new String[]{"C_BAND_latitude_feed4", "C_BAND_longitude_feed4"}, descriptor.getGeometryNames());
        assertArrayEquals(new String[]{
                "C_BAND_footprint_minor_axis_feed4",
                "C_BAND_footprint_major_axis_feed4",
                "C_BAND_geometric_rot_angle_feed4"
        }, descriptor.getFootprintVars());
    }

    @Test
    @STTM("SNAP-4262")
    public void expand_createsGeometryDescriptorFromTemplate() throws Exception {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor productDescriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");
        CimrDescriptorSet descriptorSet = new CimrDescriptorExpander(dddb).expand(productDescriptor);

        CimrBandDescriptor descriptor = descriptorSet.getGeometryByName("KA_BAND_longitude_feed8");

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

        CimrBandDescriptor descriptor = descriptorSet.getTpVariableByName("KU_BAND_footprint_minor_axis_feed8");

        assertNotNull(descriptor);
        assertEquals("footprint_minor_axis", descriptor.getValueVarName());
        assertEquals(CimrFrequencyBand.KU_BAND, descriptor.getBand());
        assertEquals("/Data/Measurement_Data/KU_BAND/", descriptor.getGroupPath());
        assertEquals(7, descriptor.getFeedIndex());
        assertEquals(CimrDescriptorKind.TIEPOINT_VARIABLE, descriptor.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_KU_BAND", "n_feeds_KU_BAND"}, descriptor.getDimensions());
        assertEquals("float", descriptor.getDataType());
        assertEquals("m", descriptor.getUnit());
        assertArrayEquals(new String[]{"KU_BAND_latitude_feed8", "KU_BAND_longitude_feed8"}, descriptor.getGeometryNames());
    }
}
