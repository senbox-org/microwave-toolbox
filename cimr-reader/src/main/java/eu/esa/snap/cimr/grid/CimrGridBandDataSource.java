package eu.esa.snap.cimr.grid;


public class CimrGridBandDataSource implements GridBandDataSource {


    private final int width;
    private final int height;
    private final CimrSampleBuffer data;
    private final byte[] states;

    private static final byte EMPTY = 0;
    private static final byte INVALID = 1;
    private static final byte VALID = 2;


    private CimrGridBandDataSource(int width, int height, CimrSampleBuffer data, byte[] states) {
        this.width = width;
        this.height = height;
        this.data = data;
        this.states = states;
    }


    public static CimrGridBandDataSource createEmpty(int width, int height, int rasterDataType) {
        int size = width * height;
        CimrSampleBuffer data = CimrSampleBuffer.create(rasterDataType, size);
        byte[] states = new byte[size];
        return new CimrGridBandDataSource(width, height, data, states);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getRasterDataType() {
        return data.getRasterDataType();
    }

    @Override
    public double getSample(int x, int y) {
        checkBounds(x, y);
        int index = y * width + x;
        return states[index] == VALID ? data.get(index) : Double.NaN;
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
        return toState(states[index]);
    }

    @Override
    public void setValidSample(int x, int y, double value) {
        if (Double.isNaN(value)) {
            setInvalidSample(x, y);
            return;
        }
        checkBounds(x, y);
        int index = y * width + x;
        data.set(index, value);
        states[index] = VALID;
    }

    @Override
    public void setInvalidSample(int x, int y) {
        checkBounds(x, y);
        int index = y * width + x;
        states[index] = INVALID;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Grid index out of range: x=" + x + ", y=" + y);
        }
    }

    private static GridSampleState toState(byte state) {
        return switch (state) {
            case EMPTY -> GridSampleState.EMPTY;
            case INVALID -> GridSampleState.INVALID;
            case VALID -> GridSampleState.VALID;
            default -> throw new IllegalStateException("Unknown grid sample state: " + state);
        };
    }
}
