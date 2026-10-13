package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.CimrReaderContext;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.GridSampleState;


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

    @Override
    public GridSampleState getSampleState(int x, int y) {
        return getDelegate().getSampleState(x, y);
    }

    @Override
    public boolean isCovered(int x, int y) {
        return getDelegate().isCovered(x, y);
    }

    @Override
    public boolean isValid(int x, int y) {
        return getDelegate().isValid(x, y);
    }

    @Override
    public void setValidSample(int x, int y, double value) {
        getDelegate().setValidSample(x, y, value);
    }

    @Override
    public void setInvalidSample(int x, int y) {
        getDelegate().setInvalidSample(x, y);
    }
}
