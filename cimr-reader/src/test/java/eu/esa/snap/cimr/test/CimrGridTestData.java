package eu.esa.snap.cimr.test;

import eu.esa.snap.cimr.grid.CimrGeometry;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import eu.esa.snap.cimr.grid.CimrGridBandDataSource;
import eu.esa.snap.cimr.grid.CimrSampleBuffer;


public final class CimrGridTestData {


    private CimrGridTestData() {
    }


    public static CimrGridBandDataSource grid(int width, int height, int rasterDataType, double... values) {
        if (values.length != width * height) {
            throw new IllegalArgumentException("values length must be width * height");
        }
        CimrGridBandDataSource dataSource = CimrGridBandDataSource.createEmpty(width, height, rasterDataType);
        for (int index = 0; index < values.length; index++) {
            int x = index % width;
            int y = index / width;
            if (Double.isNaN(values[index])) {
                dataSource.setInvalidSample(x, y);
            } else {
                dataSource.setValidSample(x, y, values[index]);
            }
        }
        return dataSource;
    }

    public static CimrGeometryBand geometryBand(int rasterDataType, int scanCount, int sampleCount,
                                                CimrGeometry geometry, int feedIndex, double... values) {
        if (values.length != scanCount * sampleCount) {
            throw new IllegalArgumentException("values length must be scanCount * sampleCount");
        }
        CimrSampleBuffer buffer = CimrSampleBuffer.create(rasterDataType, values.length);
        for (int index = 0; index < values.length; index++) {
            buffer.set(index, values[index]);
        }
        return new CimrGeometryBand(buffer, scanCount, sampleCount, geometry, feedIndex);
    }
}
