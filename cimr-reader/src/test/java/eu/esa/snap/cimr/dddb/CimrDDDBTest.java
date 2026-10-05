package eu.esa.snap.cimr.dddb;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;


public class CimrDDDBTest {


    @Test
    @STTM("SNAP-4262")
    public void getProductDescriptor_loadsVersionedDescriptor() throws IOException {
        CimrProductDescriptor descriptor = CimrDDDB.getInstance().getProductDescriptor("CIMR_L1B", "1.1");

        assertEquals("CIMR_L1B", descriptor.getProductType());
        assertEquals("1.1", descriptor.getVersion());
        assertEquals("bands.json", descriptor.getBandsFile());
        assertArrayEquals(new String[]{"navigation.json", "measurement.json", "calibration.json"}, descriptor.getDescriptorFiles());
        assertEquals("L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND", descriptor.getAutoGrouping());
    }

    @Test
    @STTM("SNAP-4262")
    public void getProductDescriptor_fallsBackToDefaultDescriptor() throws IOException {
        CimrProductDescriptor descriptor = CimrDDDB.getInstance().getProductDescriptor("CIMR_L1B", "not-yet-known");

        assertEquals("CIMR_L1B", descriptor.getProductType());
        assertEquals("default", descriptor.getVersion());
    }

    @Test
    @STTM("SNAP-4262")
    public void getDescriptorFile_loadsMeasurementDescriptorsFromVersionDirectory() throws IOException {
        CimrDDDB dddb = CimrDDDB.getInstance();
        CimrProductDescriptor descriptor = dddb.getProductDescriptor("CIMR_L1B", "1.1");

        CimrVariableFamily[] families = dddb.getDescriptorFile(descriptor, "measurement.json");

        assertEquals("VARIABLE", families[0].getKind());
        CimrVariableFamily footprintFamily = null;
        for (CimrVariableFamily family : families) {
            if ("footprint_major_axis".equals(family.getValueVarName())) {
                footprintFamily = family;
                break;
            }
        }
        assertNotNull(footprintFamily);
        assertEquals("TIEPOINT_VARIABLE", footprintFamily.getKind());
    }
}
