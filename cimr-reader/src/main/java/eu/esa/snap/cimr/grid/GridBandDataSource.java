package eu.esa.snap.cimr.grid;


public interface GridBandDataSource {

    double getSample(int x, int y);
    void setSample(int x, int y, double value);
}
