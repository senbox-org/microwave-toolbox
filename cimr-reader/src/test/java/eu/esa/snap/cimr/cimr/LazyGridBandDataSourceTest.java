package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.dddb.CimrProductDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorSet;
import eu.esa.snap.cimr.dddb.descriptor.CimrFrequencyBand;
import eu.esa.snap.cimr.grid.CimrGridBandDataSource;
import eu.esa.snap.cimr.test.CimrGridTestData;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import ucar.nc2.NetcdfFile;

import static org.junit.Assert.*;


public class LazyGridBandDataSourceTest {


    private static final double doubleErr = 1e-6;


    @Test
    public void testLazyInitializationAndGetSampleDelegation() {
        CimrGridBandDataSource delegate = CimrGridTestData.grid(2, 2, ProductData.TYPE_FLOAT64, 1.0, 2.0, 3.0, 4.0);

        TestReaderContext context = new TestReaderContext(delegate);
        CimrBandDescriptor desc = createDummyDescriptor("test_band");

        LazyGridBandDataSource lazy = new LazyGridBandDataSource(context, desc);

        double v00 = lazy.getSample(0, 0);
        double v11 = lazy.getSample(1, 1);

        assertEquals(1.0, v00, doubleErr);
        assertEquals(4.0, v11, doubleErr);

        assertEquals(1, context.callCount);
        assertSame(desc, context.lastDescriptor);
    }

    @Test
    public void testSetSampleDelegation() {
        CimrGridBandDataSource delegate = CimrGridTestData.grid(2, 2, ProductData.TYPE_FLOAT64, 0.0, 0.0, 0.0, 0.0);

        TestReaderContext context = new TestReaderContext(delegate);
        CimrBandDescriptor desc = createDummyDescriptor("test_band_2");

        LazyGridBandDataSource lazy = new LazyGridBandDataSource(context, desc);

        assertEquals(0.0, delegate.getSample(1, 1), doubleErr);

        lazy.setSample(1, 1, 42.0);
        assertEquals(42.0, delegate.getSample(1, 1), doubleErr);

        assertEquals(1, context.callCount);
        assertSame(desc, context.lastDescriptor);
    }


    private static class TestReaderContext extends CimrReaderContext {

        int callCount = 0;
        CimrBandDescriptor lastDescriptor;
        private final CimrGridBandDataSource delegate;

        TestReaderContext(CimrGridBandDataSource delegate) {
            super((NetcdfFile) null, productDescriptor(), new CimrDescriptorSet(null, null, null), "L_BAND:C_BAND", null, null, null);
            this.delegate = delegate;
        }

        private static CimrProductDescriptor productDescriptor() {
            CimrProductDescriptor productDescriptor = new CimrProductDescriptor();
            productDescriptor.setProductType("CIMR_L1B");
            return productDescriptor;
        }

        @Override
        public CimrGridBandDataSource getOrCreateGridForVariable(CimrBandDescriptor descriptor) {
            callCount++;
            lastDescriptor = descriptor;
            return delegate;
        }
    }

    private static CimrBandDescriptor createDummyDescriptor(String name) {
        return new CimrBandDescriptor(
                name,
                "valueVar",
                CimrFrequencyBand.C_BAND,
                new String[]{"lat", "lon"},
                new String[] {""},
                "/dummy/path",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double",
                "",
                ""
        );
    }
}
