package eu.esa.snap.cimr.grid;


public class GridCoverageDataSource implements GridBandDataSource {


    private final GridBandDataSource source;


    public GridCoverageDataSource(GridBandDataSource source) {
        this.source = source;
    }


    @Override
    public double getSample(int x, int y) {
        return source.isCovered(x, y) ? 1.0 : 0.0;
    }

    @Override
    public void setSample(int x, int y, double value) {
        throw new UnsupportedOperationException("Coverage data source is read-only.");
    }

    @Override
    public GridSampleState getSampleState(int x, int y) {
        return GridSampleState.VALID;
    }

    @Override
    public boolean isCovered(int x, int y) {
        return source.isCovered(x, y);
    }

    @Override
    public boolean isValid(int x, int y) {
        return true;
    }
}
