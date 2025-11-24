package eu.esa.snap.cimr.grid;

import java.util.Arrays;


public class GlobalGridBandDataSource implements GridBandDataSource {

    private final int width;
    private final int height;
    private final double[] data;


    public GlobalGridBandDataSource(int width, int height, double[] data) {
        if (data.length != width * height) {
            throw new IllegalArgumentException("data length must be width * height");
        }
        this.width = width;
        this.height = height;
        this.data = data;
    }


    public static GlobalGridBandDataSource createEmpty(int width, int height) {
        double[] data = new double[width * height];
        Arrays.fill(data, Double.NaN);
        return new GlobalGridBandDataSource(width, height, data);
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
        checkBounds(x, y);
        int index = y * width + x;
        data[index] = value;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Grid index out of range: x=" + x + ", y=" + y);
        }
    }
}
