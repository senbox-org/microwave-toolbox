
package eu.esa.sar.io.strix.slc;

import eu.esa.sar.commons.test.MetadataValidator;
import eu.esa.sar.commons.test.ProductValidator;
import eu.esa.sar.commons.test.ReaderTest;
import eu.esa.sar.commons.test.SARTests;
import eu.esa.sar.io.strix.StriXProductReaderPlugIn;
import org.esa.snap.core.datamodel.Product;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/**
 * Test Product Reader.
 *
 */
public class TestStriXProductReader extends ReaderTest {

    final static File inputSMSLCMeta = new File(SARTests.inputPathProperty + "/SAR/Synspective/SLC/StrixA/STRIX-A_SM_SLC_CEOS_202111_044_2021-11-11T133724Z/VOL-STRIXA111111222222-211111-SMLSLCD");
    final static File inputSMSLCFolder = new File(SARTests.inputPathProperty + "/SAR/Synspective/SLC/StrixA/STRIX-A_SM_SLC_CEOS_202111_044_2021-11-11T133724Z");

    final static MetadataValidator.Options options = new MetadataValidator.Options();

    public TestStriXProductReader() {
        super(new StriXProductReaderPlugIn());
    }

    @Before
    public void setUp() {
        // If any of the file does not exist: the test will be ignored
        assumeTrue(inputSMSLCMeta + " not found", inputSMSLCMeta.exists());
        assumeTrue(inputSMSLCFolder + " not found", inputSMSLCFolder.exists());

        options.validateOrbitStateVectors = false;
        options.validateSRGR = false;
        options.validateDopplerCentroids = false;
    }

    @Test
    public void testOpeningSM_SLC_Folder() throws Exception {
        Product prod = testReader(inputSMSLCFolder.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_VV", "q_VV", "Intensity_VV"});
    }

    @Test
    public void testOpeningSM_SLC_Metadata() throws Exception {
        Product prod = testReader(inputSMSLCMeta.toPath());

        final ProductValidator validator = new ProductValidator(prod);
        validator.validateProduct();
        validator.validateMetadata(options);
        validator.validateBands(new String[] {"i_VV", "q_VV", "Intensity_VV"});
    }
}
