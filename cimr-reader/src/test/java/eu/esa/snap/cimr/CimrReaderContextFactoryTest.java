package eu.esa.snap.cimr;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import org.junit.Test;
import ucar.nc2.Attribute;
import ucar.nc2.NetcdfFile;

import java.io.IOException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class CimrReaderContextFactoryTest {


    @Test
    @STTM("SNAP-4262")
    public void loadDescriptorSet_usesFormatVersionFromProduct() throws IOException {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(new Attribute("format_version", "1.1"));

        CimrDescriptorSet descriptorSet = CimrReaderContextFactory.loadDescriptorSet(ncFile);

        assertEquals(200, descriptorSet.getMeasurements().size());
        assertNotNull(descriptorSet.getMeasurementByName("L_BAND_brightness_temperature_h_feed1"));
        assertNotNull(descriptorSet.getTpVariableByName("KA_BAND_footprint_major_axis_feed8"));
        assertNotNull(descriptorSet.getGeometryByName("C_BAND_longitude_feed4"));
    }

    @Test
    @STTM("SNAP-4262")
    public void loadDescriptorSet_fallsBackToDefaultDescriptorWhenFormatVersionIsMissing() throws IOException {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(null);

        CimrDescriptorSet descriptorSet = CimrReaderContextFactory.loadDescriptorSet(ncFile);

        assertEquals(200, descriptorSet.getMeasurements().size());
        assertNotNull(descriptorSet.getMeasurementByName("X_BAND_raw_brightness_temperature_h_feed4"));
    }
}
