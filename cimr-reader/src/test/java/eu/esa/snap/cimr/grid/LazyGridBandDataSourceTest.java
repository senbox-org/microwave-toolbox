package eu.esa.snap.cimr.grid;


import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;
import org.junit.Test;
import ucar.nc2.NetcdfFile;

import static org.junit.Assert.*;


public class LazyGridBandDataSourceTest {


    private static final double doubleErr = 1e-6;

    @Test
    public void testLazyInitializationAndGetSampleDelegation() {
        double[] data = {
                1.0, 2.0,
                3.0, 4.0
        };
        GlobalGridBandDataSource delegate = new GlobalGridBandDataSource(2, 2, data);

        TestReaderContext context = new TestReaderContext(delegate);
        CimrBandDescriptor desc = createDummyDescriptor("test_band");

        LazyGridBandDataSource lazy = new LazyGridBandDataSource(context, desc, true);

        double v00 = lazy.getSample(0, 0);
        double v11 = lazy.getSample(1, 1);

        assertEquals(1.0, v00, doubleErr);
        assertEquals(4.0, v11, doubleErr);

        assertEquals(1, context.callCount);
        assertSame(desc, context.lastDescriptor);
        assertTrue(context.lastUseAverage);
    }

    @Test
    public void testSetSampleDelegationAndUseAverageFalse() {
        double[] data = {
                0.0, 0.0,
                0.0, 0.0
        };
        GlobalGridBandDataSource delegate = new GlobalGridBandDataSource(2, 2, data);

        TestReaderContext context = new TestReaderContext(delegate);
        CimrBandDescriptor desc = createDummyDescriptor("test_band_2");

        LazyGridBandDataSource lazy = new LazyGridBandDataSource(context, desc, false);

        assertEquals(0.0, delegate.getSample(1, 1), doubleErr);

        lazy.setSample(1, 1, 42.0);
        assertEquals(42.0, delegate.getSample(1, 1), doubleErr);

        assertEquals(1, context.callCount);
        assertSame(desc, context.lastDescriptor);
        assertFalse(context.lastUseAverage);
    }


    private static class TestReaderContext extends CimrReaderContext {

        int callCount = 0;
        CimrBandDescriptor lastDescriptor;
        boolean lastUseAverage;
        private final GlobalGridBandDataSource delegate;

        TestReaderContext(GlobalGridBandDataSource delegate) {
            super((NetcdfFile) null,
                    new CimrDescriptorSet(null, null, null),
                    null, null, null);
            this.delegate = delegate;
        }

        @Override
        public GlobalGridBandDataSource getOrCreateGridForVariable(CimrBandDescriptor descriptor, boolean useAverage) {
            callCount++;
            lastDescriptor = descriptor;
            lastUseAverage = useAverage;
            return delegate;
        }
    }

    private static CimrBandDescriptor createDummyDescriptor(String name) {
        return new CimrBandDescriptor(
                name,
                "valueVar",
                CimrFrequencyBand.C_BAND,
                new String[]{"lat", "lon"},
                "/dummy/path",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double"
        );
    }
}