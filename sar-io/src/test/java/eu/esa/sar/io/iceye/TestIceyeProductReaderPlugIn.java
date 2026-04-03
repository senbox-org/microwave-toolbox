package eu.esa.sar.io.iceye;

import eu.esa.sar.io.AbstractProductReaderPlugInTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestIceyeProductReaderPlugIn extends AbstractProductReaderPlugInTest {

    public TestIceyeProductReaderPlugIn() {
        super(new IceyeProductReaderPlugIn());
    }

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof IceyeProductReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"ICEYE"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{ ".TIF", ".H5", ".XML", ".JSON" }, plugin.getDefaultFileExtensions());
    }

    @Override
    protected String[] getValidPrimaryMetadataFileNames() {
        return new String[] {"ICEYE_XYZ_EXTENDED.xml", "ICEYE_XYZ_EXTENDED.json"};
    }

    @Override
    protected String[] getInvalidPrimaryMetadataFileNames() {
        return new String[] {"Iceye_xyz_extended.txt"};
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(TestIceyeAMLCPXProductReader.inputAMLtif);
        isValidDecodeQualification(TestIceyeAMLCPXProductReader.inputAMLjson);
        isValidDecodeQualification(TestIceyeAMLCPXProductReader.inputCPXtif);
        isValidDecodeQualification(TestIceyeAMLCPXProductReader.inputCPXjson);
    }
}
