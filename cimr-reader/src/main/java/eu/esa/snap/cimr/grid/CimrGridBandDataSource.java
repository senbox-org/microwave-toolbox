package eu.esa.snap.cimr.grid;

import java.util.Arrays;


public class CimrGridBandDataSource implements GridBandDataSource {

    private final int width;
    private final int height;
    private final double[] data;
    private final GridSampleState[] states;


    public CimrGridBandDataSource(int width, int height, double[] data) {
        this(width, height, data, createStates(data));
    }

    private CimrGridBandDataSource(int width, int height, double[] data, GridSampleState[] states) {
        if (data.length != width * height) {
            throw new IllegalArgumentException("data length must be width * height");
        }
        if (states.length != width * height) {
            throw new IllegalArgumentException("states length must be width * height");
        }
        this.width = width;
        this.height = height;
        this.data = data;
        this.states = states;
    }


    public static CimrGridBandDataSource createEmpty(int width, int height) {
        double[] data = new double[width * height];
        Arrays.fill(data, Double.NaN);
        GridSampleState[] states = new GridSampleState[width * height];
        Arrays.fill(states, GridSampleState.EMPTY);
        return new CimrGridBandDataSource(width, height, data, states);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public double getSample(int x, int y) {
        checkBounds(x, y);
        int index = y * width + x;
        return data[index];
    }

    @Override
    public void setSample(int x, int y, double value) {
        if (Double.isNaN(value)) {
            setInvalidSample(x, y);
        } else {
            setValidSample(x, y, value);
        }
    }

    @Override
    public GridSampleState getSampleState(int x, int y) {
        checkBounds(x, y);
        int index = y * width + x;
        return states[index];
    }

    @Override
    public void setValidSample(int x, int y, double value) {
        if (Double.isNaN(value)) {
            setInvalidSample(x, y);
            return;
        }
        checkBounds(x, y);
        int index = y * width + x;
        data[index] = value;
        states[index] = GridSampleState.VALID;
    }

    @Override
    public void setInvalidSample(int x, int y) {
        checkBounds(x, y);
        int index = y * width + x;
        data[index] = Double.NaN;
        states[index] = GridSampleState.INVALID;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Grid index out of range: x=" + x + ", y=" + y);
        }
    }

    private static GridSampleState[] createStates(double[] data) {
        GridSampleState[] states = new GridSampleState[data.length];
        for (int ii = 0; ii < data.length; ii++) {
            states[ii] = Double.isNaN(data[ii]) ? GridSampleState.INVALID : GridSampleState.VALID;
        }
        return states;
    }
}
