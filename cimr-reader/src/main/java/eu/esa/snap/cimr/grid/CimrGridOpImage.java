package eu.esa.snap.cimr.grid;

import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.image.RasterDataNodeOpImage;
import org.esa.snap.core.image.ResolutionLevel;

import java.awt.*;


public class CimrGridOpImage extends RasterDataNodeOpImage {


    private final GridBandDataSource gridDataSource;
    private final CimrOverviewResampling overviewResampling;


    public CimrGridOpImage(RasterDataNode rasterDataNode, ResolutionLevel level, GridBandDataSource gridBandDataSource) {
        this(rasterDataNode, level, gridBandDataSource, CimrOverviewResampling.CONTINUOUS_AVERAGE);
    }

    public CimrGridOpImage(RasterDataNode rasterDataNode, ResolutionLevel level, GridBandDataSource gridBandDataSource, CimrOverviewResampling overviewResampling) {
        super(rasterDataNode, level);
        this.gridDataSource = gridBandDataSource;
        this.overviewResampling = overviewResampling;
    }


    @Override
    protected void computeProductData(ProductData productData, Rectangle region) {
        int w = region.width;
        int h = region.height;

        int levelIndex = getLevel();
        double scale = Math.pow(2.0, levelIndex);
        int blockSize = (int) Math.round(scale);

        int baseWidth = getRasterDataNode().getRasterWidth();
        int baseHeight = getRasterDataNode().getRasterHeight();

        int idx = 0;
        for (int dy = 0; dy < h; dy++) {
            int yLevel = region.y + dy;
            int y0 = (int) Math.floor(yLevel * scale);
            int y1 = Math.min(y0 + blockSize, baseHeight);

            for (int dx = 0; dx < w; dx++) {
                int xLevel = region.x + dx;
                int x0 = (int) Math.floor(xLevel * scale);
                int x1 = Math.min(x0 + blockSize, baseWidth);

                productData.setElemDoubleAt(idx++, getOverviewSample(x0, x1, y0, y1));
            }
        }
    }

    private double getOverviewSample(int x0, int x1, int y0, int y1) {
        return switch (overviewResampling) {
            case CONTINUOUS_AVERAGE -> getAverageOfValidSamples(x0, x1, y0, y1);
            case CATEGORICAL_NEAREST -> getNearestSample(x0, y0);
            case COVERAGE_ANY -> hasCoveredSample(x0, x1, y0, y1) ? 1.0 : 0.0;
        };
    }

    private double getAverageOfValidSamples(int x0, int x1, int y0, int y1) {
        double sum = 0.0;
        int count = 0;

        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                try {
                    if (gridDataSource.isValid(xx, yy)) {
                        sum += gridDataSource.getSample(xx, yy);
                        count++;
                    }
                } catch (IllegalArgumentException e) {
                    // Treat unavailable source pixels as NoData for this output sample.
                }
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    private double getNearestSample(int x, int y) {
        try {
            return gridDataSource.isValid(x, y) ? gridDataSource.getSample(x, y) : Double.NaN;
        } catch (IllegalArgumentException e) {
            return Double.NaN;
        }
    }

    private boolean hasCoveredSample(int x0, int x1, int y0, int y1) {
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                try {
                    if (gridDataSource.isCovered(xx, yy)) {
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    // Treat unavailable source pixels as uncovered.
                }
            }
        }
        return false;
    }
}
