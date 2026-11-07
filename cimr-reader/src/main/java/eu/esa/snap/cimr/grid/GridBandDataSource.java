package eu.esa.snap.cimr.grid;


public interface GridBandDataSource {

    double getSample(int x, int y);
    void setSample(int x, int y, double value);

    default GridSampleState getSampleState(int x, int y) {
        return Double.isNaN(getSample(x, y)) ? GridSampleState.INVALID : GridSampleState.VALID;
    }

    default boolean isCovered(int x, int y) {
        return getSampleState(x, y) != GridSampleState.EMPTY;
    }

    default boolean isValid(int x, int y) {
        return getSampleState(x, y) == GridSampleState.VALID;
    }

    default void setValidSample(int x, int y, double value) {
        setSample(x, y, value);
    }

    default void setInvalidSample(int x, int y) {
        setSample(x, y, Double.NaN);
    }
}
