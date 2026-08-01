package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.GridBandDataSource;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.image.RasterDataNodeOpImage;
import org.esa.snap.core.image.ResolutionLevel;

import java.awt.*;


public class CimrGridOpImage extends RasterDataNodeOpImage {

    private final GridBandDataSource gridDataSource;


    public CimrGridOpImage(RasterDataNode rasterDataNode, ResolutionLevel level, GridBandDataSource gridBandDataSource) {
        super(rasterDataNode, level);
        this.gridDataSource = gridBandDataSource;
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

                double sum = 0.0;
                int count = 0;

                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        double v;
                        try {
                            v = gridDataSource.getSample(xx, yy);
                        } catch (IllegalArgumentException e) {
                            v = Double.NaN;
                        }
                        if (!Double.isNaN(v)) {
                            sum += v;
                            count++;
                        }
                    }
                }

                double out = (count > 0) ? (sum / count) : Double.NaN;
                productData.setElemDoubleAt(idx++, out);
            }
        }
    }
}
