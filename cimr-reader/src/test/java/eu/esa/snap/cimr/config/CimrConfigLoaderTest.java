package eu.esa.snap.cimr.config;

import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;


public class CimrConfigLoaderTest {


    @Test
    public void testLoadTestConfigJson() throws Exception {
        CimrDescriptorSet set = CimrConfigLoader.load("test-config.json");
        assertNotNull(set);

        List<CimrBandDescriptor> meas      = set.getMeasurements();
        List<CimrBandDescriptor> tpVars    = set.getTiepointVariables();
        List<CimrBandDescriptor> tpGeoms   = set.getGeometries();

        assertEquals(4, meas.size());
        assertEquals(1, tpVars.size());
        assertEquals(4, tpGeoms.size());


        CimrBandDescriptor m0 = meas.get(0);
        assertEquals("C_BAND_raw_bt_h_feed1", m0.getName());
        assertEquals("raw_bt_h", m0.getValueVarName());
        assertEquals(CimrFrequencyBand.C_BAND, m0.getBand());
        assertEquals("/Data/Measurement_Data/C_BAND/", m0.getGroupPath());
        assertEquals(0, m0.getFeedIndex());
        assertEquals(CimrDescriptorKind.VARIABLE, m0.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"}, m0.getDimensions());
        assertEquals("double", m0.getDataType());
        assertArrayEquals(new String[]{"C_BAND_latitude_feed1", "C_BAND_longitude_feed1"}, m0.getGeometryNames());


        CimrBandDescriptor tpVal0 = tpVars.get(0);
        assertEquals("C_BAND_altitude_feed1", tpVal0.getName());
        assertEquals("altitude", tpVal0.getValueVarName());
        assertEquals(CimrFrequencyBand.C_BAND, tpVal0.getBand());
        assertEquals("/Data/Navigation_Data/C_BAND/", tpVal0.getGroupPath());
        assertEquals(0, tpVal0.getFeedIndex());
        assertEquals(CimrDescriptorKind.TIEPOINT_VARIABLE, tpVal0.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_tie_points_C_BAND", "n_feeds_C_BAND"}, tpVal0.getDimensions());
        assertEquals("double", tpVal0.getDataType());
        assertArrayEquals(new String[]{"C_BAND_latitude_feed1", "C_BAND_longitude_feed1"}, tpVal0.getGeometryNames());


        CimrBandDescriptor tpGeom0 = tpGeoms.get(0);
        assertEquals("C_BAND_latitude_feed1", tpGeom0.getName());
        assertEquals("latitude", tpGeom0.getValueVarName());
        assertEquals(CimrFrequencyBand.C_BAND, tpGeom0.getBand());
        assertEquals("/Data/Navigation_Data/C_BAND/", tpGeom0.getGroupPath());
        assertEquals(0, tpGeom0.getFeedIndex());
        assertEquals(CimrDescriptorKind.GEOMETRY, tpGeom0.getKind());
        assertArrayEquals(new String[]{"n_scans", "n_tie_points_C_BAND", "n_feeds_C_BAND"}, tpGeom0.getDimensions());
        assertEquals("double", tpGeom0.getDataType());
    }


    @Test(expected = IOException.class)
    public void testLoadTestConfigJson_throws() throws IOException {
        CimrConfigLoader.load("not-existent.json");
    }
}