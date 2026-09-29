package eu.esa.sar.io.iceye;

import eu.esa.sar.io.AbstractProductReaderPlugInTest;
import org.esa.snap.core.dataio.ProductReader;
import org.junit.Test;

import static eu.esa.sar.io.iceye.TestIceyeReader.SL_SLC_ImageFile;
import static eu.esa.sar.io.iceye.TestIceyeReader.SL_SLC_MetadataFile;
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
        assertArrayEquals(new String[]{ ".H5", ".XML" }, plugin.getDefaultFileExtensions());
    }

    @Override
    protected String[] getValidPrimaryMetadataFileNames() {
        return new String[] {"ICEYE_XYZ_EXTENDED.xml"};
    }

    @Override
    protected String[] getInvalidPrimaryMetadataFileNames() {
        return new String[] {"Iceye_xyz_extended.txt"};
    }

    @Test
    public void testValidDecodeQualification() {
        isValidDecodeQualification(SL_SLC_MetadataFile);
        isValidDecodeQualification(SL_SLC_ImageFile);
    }
}
