package eu.esa.sar.insar.gpf;

import com.bc.ceres.annotation.STTM;
import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.test.ProcessorTest;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit test for TestIonosphericCorrectionOp.
 */
public class TestIonosphericCorrectionOp extends ProcessorTest {

    private final static OperatorSpi spi = new IonosphericCorrectionOp.Spi();

    @Test
    @STTM("SNAP-4000")
    public void testSetCenterFrequencies() throws Exception {

        final IonosphericCorrectionOp op = (IonosphericCorrectionOp) spi.createOperator();
        assertNotNull(op);

        final double centerFreqLow  = 5305.000454334349; // MHz
        final double centerFreqHigh = 5505.000454334349; // MHz
        final double centerFreqFull = 5405.000454334349; // MHz
        final Product productLow = createTestProduct(centerFreqLow);
        final Product productHigh = createTestProduct(centerFreqHigh);
        final Product productFull = createTestProduct(centerFreqFull);

        op.setSourceProducts(productLow, productHigh, productFull);

        // get targetProduct gets initialize to be executed
        final Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);

        final double[] centerFrequencies = op.getCenterFrequencies();
        assertEquals(centerFreqLow * 1e6, centerFrequencies[0], 0.01);
        assertEquals(centerFreqHigh * 1e6, centerFrequencies[1], 0.01);
        assertEquals(centerFreqFull * 1e6, centerFrequencies[2], 0.01);
    }

    private static Product createTestProduct(final double centerFrequency) {

        final int w = 10, h = 10;
        final Product product = TestUtils.createProduct("SLC", w, h);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, centerFrequency);

        TestUtils.createBand(product, "Unw_Phase_ifg_IW2_VV_09Jan2025_21Jan2025", ProductData.TYPE_FLOAT32, Unit.PHASE, w, h, true);
        TestUtils.createBand(product, "coh_IW2_VV_09Jan2025_21Jan2025", ProductData.TYPE_FLOAT32, Unit.COHERENCE, w, h, true);
        return product;
    }
}
