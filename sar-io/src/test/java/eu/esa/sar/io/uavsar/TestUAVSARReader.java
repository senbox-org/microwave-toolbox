package eu.esa.sar.io.uavsar;

import org.esa.snap.core.dataio.DecodeQualification;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Product;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertSame;

public class TestUAVSARReader {

    private final static File inputFile = new File("I:\\ESA-Data\\UAVSAR\\UA_HaitiQ_05701_10011_008_100127_L090_CX_01\\HaitiQ_05701_10011_008_100127_L090HHHH_CX_01.mlc");

    private UAVSARReaderPlugIn readerPlugin;
    private ProductReader reader;

    @Before
    public void setUp() throws Exception {
        readerPlugin = new UAVSARReaderPlugIn();
        reader = readerPlugin.createReaderInstance();
    }

    @After
    public void tearDown() {
        reader = null;
        readerPlugin = null;
    }

    /**
     * Open a file
     *
     * @throws Exception anything
     */
    @Test
    public void testOpen() throws Exception {
        if (!inputFile.exists()) return;

        assertSame(readerPlugin.getDecodeQualification(inputFile), DecodeQualification.INTENDED);

        final Product product = reader.readProductNodes(inputFile, null);
        //TestUtils.verifyProduct(product, true, true);
    }
}
