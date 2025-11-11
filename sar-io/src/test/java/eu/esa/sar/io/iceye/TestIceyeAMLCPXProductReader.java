package eu.esa.sar.io.iceye;

import static org.junit.Assume.assumeTrue;

import java.io.File;

import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.gpf.TestProcessor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestIceyeAMLCPXProductReader extends ReaderTest {

    final static File inputAMLtif = new File(
            SARTests.inputPathProperty + "/AML/ICEYE_TKMZR7_20240122T215606Z_3303779_X13_SLH_AML.tif");
    final static File inputCPXtif = new File(
            SARTests.inputPathProperty + "/CPX/ICEYE_TKMZR7_20240122T215606Z_3303779_X13_SLH_CPX.tif");
    final static File inputAMLjson = new File(
            SARTests.inputPathProperty + "/AML/ICEYE_TKMZR7_20240122T215606Z_3303779_X13_SLH_AML.json");
    final static File inputCPXjson = new File(
            SARTests.inputPathProperty + "/CPX/ICEYE_TKMZR7_20240122T215606Z_3303779_X13_SLH_CPX.json");

    private final static File[] iceyeAMLFiles = SARTests.loadFilePath(SARTests.inputPathProperty + "/AML");
    private final static File[] iceyeCPXFiles = SARTests.loadFilePath(SARTests.inputPathProperty + "/CPX");

    final static MetadataValidator.Options options = new MetadataValidator.Options();

    private final String[] exceptionExemptions = { "not supported" };

    public TestIceyeAMLCPXProductReader() {
        super(new IceyeProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the files does not exist, all tests will be ignored
        assumeTrue(inputAMLtif + " not found", inputAMLtif.exists());
        assumeTrue(inputCPXtif + " not found", inputCPXtif.exists());
        assumeTrue(inputAMLjson + " not found", inputAMLjson.exists());
        assumeTrue(inputCPXjson + " not found", inputCPXjson.exists());
    }

    @Test
    public void testOpenAll() {
        TestProcessor testProcessor = new TestProcessor(100, 100, 100, 100, 1, true, false);

        File file = new File(SARTests.inputPathProperty);
        File[] folderPaths = new File[1];
        folderPaths[0] = file;
        System.out.println(file);
        try {
            testProcessor.recurseReadFolder(this, folderPaths, readerPlugIn, reader, null, exceptionExemptions);
        } catch (Exception e) {
            Assert.fail();
        }
    }

    @Test
    public void testTestServerOpenAllAML() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, iceyeAMLFiles, readerPlugIn, null, null, null);
    }

    @Test
    public void testTestServerOpenAllCPX() throws Exception {
        TestProcessor testProcessor = SARTests.createTestProcessor();
        testProcessor.recurseReadFolder(this, iceyeCPXFiles, readerPlugIn, null, null, null);
    }

    @Test
    public void testOpeningAMLtif() throws Exception {
        Product prod = testReader(inputAMLtif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] { "Amplitude_VV", "Intensity_VV" });
    }

    @Test
    public void testOpeningCPXtif() throws Exception {
        Product prod = testReader(inputCPXtif.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] { "Amplitude_VV", "Phase_VV", "i_VV", "q_VV", "Intensity_VV" });
    }

    @Test
    public void testOpneingAMLjson() throws Exception {
        Product prod = testReader(inputAMLjson.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] { "Amplitude_VV", "Intensity_VV" });
    }

    @Test
    public void testOpeningCPXjson() throws Exception {
        Product prod = testReader(inputCPXjson.toPath());

        options.validateSRGR = false;

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] { "Amplitude_VV", "Phase_VV", "i_VV", "q_VV", "Intensity_VV" });
    }

}
