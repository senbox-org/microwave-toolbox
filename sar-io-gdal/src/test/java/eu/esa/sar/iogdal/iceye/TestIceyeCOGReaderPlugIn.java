package eu.esa.sar.iogdal.iceye;

import eu.esa.sar.iogdal.AbstractProductReaderPlugInGDALTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestIceyeCOGReaderPlugIn extends AbstractProductReaderPlugInGDALTest {

    public TestIceyeCOGReaderPlugIn() {
        super(new IceyeCOGReaderPlugIn());
    }

    @Test
    public void testCreateReaderInstance() {
        ProductReader productReader = plugin.createReaderInstance();
        assertNotNull(productReader);
        assertTrue(productReader instanceof IceyeCOGReader);
    }

    @Test
    public void testGetFormatNames() {
        assertArrayEquals(new String[]{"ICEYE COG"}, plugin.getFormatNames());
    }

    @Test
    public void testGetDefaultFileExtensions() {
        assertArrayEquals(new String[]{ ".TIF", ".XML", ".JSON" }, plugin.getDefaultFileExtensions());
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
