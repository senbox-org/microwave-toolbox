package eu.esa.snap.cimr.dddb;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.core.datamodel.group.BandGroup;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;

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
        assertTrue(descriptor.getAutoGrouping().startsWith("L_BAND:C_BAND:C_BAND_*feed1:C_BAND_*feed2:C_BAND_*feed3:C_BAND_*feed4"));
        assertTrue(descriptor.getAutoGrouping().contains("KU_BAND:KU_BAND_*feed1:KU_BAND_*feed2"));
        assertTrue(descriptor.getAutoGrouping().endsWith("KA_BAND_*feed7:KA_BAND_*feed8"));
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
    public void getProductDescriptor_autoGroupingGroupsMultiFeedBandsByFeedAndKeyword() throws IOException {
        CimrProductDescriptor descriptor = CimrDDDB.getInstance().getProductDescriptor("CIMR_L1B", "1.1");
        Product product = new Product("test", "CIMR_L1B", 1, 1);

        product.setAutoGrouping(descriptor.getAutoGrouping());
        BandGroup autoGrouping = product.getAutoGrouping();

        assertArrayEquals(new String[]{"L_BAND"}, autoGrouping.get(autoGrouping.indexOf("L_BAND_brightness_temperature_h_feed1")));
        assertArrayEquals(new String[]{"C_BAND_*feed2"}, autoGrouping.get(autoGrouping.indexOf("C_BAND_brightness_temperature_h_feed2")));
        assertArrayEquals(new String[]{"KU_BAND_*feed8"}, autoGrouping.get(autoGrouping.indexOf("KU_BAND_direct_sun_angle_feed8")));
        assertArrayEquals(new String[]{"KA_BAND_*feed7"}, autoGrouping.get(autoGrouping.indexOf("KA_BAND_raw_counts_h_feed7")));
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

    @Test
    @STTM("SNAP-4262")
    public void getProductDescriptor_failsWhenProductResourceDoesNotExist() {
        IOException actual = assertThrows(IOException.class, () -> CimrDDDB.getInstance().getProductDescriptor("NO_SUCH_PRODUCT", "1.1"));

        assertEquals("Invalid CIMR DDDB resource: NO_SUCH_PRODUCT/default/product.json", actual.getMessage());
    }

    @Test
    @STTM("SNAP-4262")
    public void getBandDefinitions_failsWhenBandResourceDoesNotExist() {
        CimrProductDescriptor descriptor = new CimrProductDescriptor();
        descriptor.setProductType("CIMR_L1B");
        descriptor.setVersion("1.1");
        descriptor.setBandsFile("missing-bands.json");

        IOException actual = assertThrows(IOException.class, () -> CimrDDDB.getInstance().getBandDefinitions(descriptor));

        assertEquals("Invalid CIMR DDDB band resource: CIMR_L1B/1.1/missing-bands.json", actual.getMessage());
    }

    @Test
    @STTM("SNAP-4262")
    public void getDescriptorFile_failsWhenDescriptorResourceDoesNotExist() throws IOException {
        CimrProductDescriptor descriptor = CimrDDDB.getInstance().getProductDescriptor("CIMR_L1B", "1.1");

        IOException actual = assertThrows(IOException.class, () -> CimrDDDB.getInstance().getDescriptorFile(descriptor, "missing-descriptors.json"));

        assertEquals("Invalid CIMR DDDB descriptor resource: CIMR_L1B/1.1/missing-descriptors.json", actual.getMessage());
    }

    @Test
    @STTM("SNAP-4262")
    public void read_wrapsIoExceptions() throws Exception {
        Method read = CimrDDDB.class.getDeclaredMethod("read", URL.class, Class.class);
        read.setAccessible(true);

        URL missingUrl = new URL("file:/missing-cimr-dddb-test-resource.json");
        InvocationTargetException actual = assertThrows(InvocationTargetException.class, () -> read.invoke(null, missingUrl, CimrProductDescriptor.class));

        assertTrue(actual.getCause() instanceof IllegalStateException);
        assertTrue(actual.getCause().getMessage().startsWith("Failed to read CIMR DDDB resource:"));
        assertTrue(actual.getCause().getCause() instanceof IOException);
    }
}
