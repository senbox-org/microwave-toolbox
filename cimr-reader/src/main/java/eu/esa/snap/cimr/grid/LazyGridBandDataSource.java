package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.cimr.CimrBandDescriptor;


public class LazyGridBandDataSource implements GridBandDataSource {

    private final CimrReaderContext context;
    private final CimrBandDescriptor descriptor;
    private final boolean useAverage;

    private volatile GridBandDataSource delegate;


    public LazyGridBandDataSource(CimrReaderContext context,
                                  CimrBandDescriptor descriptor,
                                  boolean useAverage) {
        this.context = context;
        this.descriptor = descriptor;
        this.useAverage = useAverage;
    }

    private GridBandDataSource getDelegate() {
        GridBandDataSource local = delegate;
        if (local == null) {
            synchronized (this) {
                local = delegate;
                if (local == null) {
                    local = context.getOrCreateGridForVariable(descriptor, useAverage);
                    delegate = local;
                }
            }
        }
        return local;
    }

    @Override
    public double getSample(int x, int y) {
        return getDelegate().getSample(x, y);
    }

    @Override
    public void setSample(int x, int y, double value) {
        getDelegate().setSample(x, y, value);
    }
}
