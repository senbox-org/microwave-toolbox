/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.utilities.gpf;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.multilevel.MultiLevelImage;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductSubsetBuilder;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.dataio.ProductWriter;
import org.esa.snap.core.dataio.dimap.DimapProductWriter;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.subset.PixelSubsetRegion;
import org.esa.snap.core.util.io.FileUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Split a product into several tiles
 */
@OperatorMetadata(alias = "TileWriter",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2015 by Array Systems Computing Inc.",
        version = "1.0",
        description = "Writes a data product to a tiles.",
        autoWriteDisabled = true,
        category = "Tools")
public class TileWriterOp extends Operator {

    @TargetProduct
    private Product targetProduct;

    @SourceProduct(alias = "source", description = "The source product to be written.")
    private Product sourceProduct;

    @Parameter(description = "The output file to which the data product is written.")
    private File file;

    @Parameter(defaultValue = ProductIO.DEFAULT_FORMAT_NAME,
            description = "The name of the output file format.")
    private String formatName;

    @Parameter(defaultValue = "Tiles", valueSet = {"Tiles", "Pixels"}, label = "Division by",
            description = "How to divide the tiles")
    private String divisionBy = "Tiles";

    @Parameter(defaultValue = "4", valueSet = {"2", "4", "9", "16", "36", "64", "100", "256"},
            description = "The number of output tiles")
    private String numberOfTiles = "4";

    @Parameter(description = "Tile pixel width", label = "Pixel width", defaultValue = "200")
    private int pixelSizeX = 200;

    @Parameter(description = "Tile pixel height", label = "Pixel height", defaultValue = "200")
    private int pixelSizeY = 200;

    @Parameter(description = "Tile overlap", label = "Overlap", defaultValue = "0")
    private int overlap = 0;

    private final Map<MultiLevelImage, List<Point>> todoLists = new HashMap<>();

    private boolean productFileWritten;

    private SubsetInfo[] subsetInfo = null;

    public TileWriterOp() {
        setRequiresAllBands(true);
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            targetProduct = sourceProduct;

            int numFiles, numRows, numCols, width, height;
            if (divisionBy.equals("Tiles")) {
                numFiles = Integer.parseInt(numberOfTiles);
                numRows = (int) Math.sqrt(numFiles);
                numCols = numRows;
                width = sourceProduct.getSceneRasterWidth() / numRows;
                height = sourceProduct.getSceneRasterHeight() / numRows;
            } else {
                width = pixelSizeX;
                height = pixelSizeY;
                numCols = sourceProduct.getSceneRasterWidth() / width;
                numRows = sourceProduct.getSceneRasterHeight() / height;
                numFiles = numRows * numCols;
            }

            subsetInfo = new SubsetInfo[numFiles];
            int n = 0;
            for (int r = 0; r < numRows; ++r) {
                for (int c = 0; c < numCols; ++c) {
                    final ProductSubsetDef subsetDef = new ProductSubsetDef();
                    subsetDef.addNodeNames(sourceProduct.getTiePointGridNames());
                    subsetDef.addNodeNames(sourceProduct.getBandNames());
                    subsetDef.setSubsetRegion(new PixelSubsetRegion(Math.max(0, c * width - overlap),
                                        Math.max(0, r * height - overlap),
                                        width+overlap+overlap, height+overlap+overlap, 0));
                    subsetDef.setSubSampling(1, 1);
                    subsetDef.setIgnoreMetadata(false);

                    subsetInfo[n] = new SubsetInfo();
                    subsetInfo[n].subsetBuilder = new ProductSubsetBuilder();
                    subsetInfo[n].product = subsetInfo[n].subsetBuilder.readProductNodes(sourceProduct, subsetDef);
                    subsetInfo[n].file = new File(file.getParentFile(), createName(file, n + 1));

                    subsetInfo[n].productWriter = ProductIO.getProductWriter(formatName);
                    if (subsetInfo[n].productWriter == null) {
                        throw new OperatorException("No data product writer for the '" + formatName + "' format available");
                    }
                    subsetInfo[n].productWriter.setIncrementalMode(false);
                    subsetInfo[n].productWriter.setFormatName(formatName);
                    subsetInfo[n].product.setProductWriter(subsetInfo[n].productWriter);

                    final Band[] bands = subsetInfo[n].product.getBands();
                    for (Band b : bands) {
                        // b.getSourceImage(); // trigger source image creation
                    }
                    ++n;
                }
            }

        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    private static String createName(final File file, final int n) {
        return FileUtils.getFilenameWithoutExtension(file) + '_' + n + FileUtils.getExtension(file);
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            synchronized (this) {
                if (!productFileWritten) {
                    for (SubsetInfo info : subsetInfo) {
                        info.productWriter.writeProductNodes(info.product, info.file);
                    }
                    productFileWritten = true;
                }
            }
            final Rectangle rect = targetTile.getRectangle();

            for (SubsetInfo info : subsetInfo) {
                final Rectangle trgRect = info.subsetBuilder.getSubsetDef().getRegion();
                if (rect.intersects(trgRect)) {
                    writeTile(info, targetBand.getName(), trgRect);
                }
            }
            markTileDone(targetBand, targetTile);
        } catch (Exception e) {
            if (e instanceof OperatorException) {
                throw (OperatorException) e;
            } else {
                throw new OperatorException(e);
            }
        }
    }

    private synchronized void writeTile(final SubsetInfo info, final String bandName, final Rectangle trgRect)
            throws IOException {

        final Tile sourceTile = getSourceTile(sourceProduct.getBand(bandName), trgRect);
        final ProductData rawSamples = sourceTile.getRawSamples();

        final Band trgBand = info.product.getBand(bandName);
        info.productWriter.writeBandRasterData(trgBand,
                0, 0, trgBand.getRasterWidth(), trgBand.getRasterHeight(), rawSamples, ProgressMonitor.NULL);
    }

    private void markTileDone(Band targetBand, Tile targetTile) throws IOException {
        boolean done;
        synchronized (todoLists) {
            MultiLevelImage sourceImage = targetBand.getSourceImage();

            final List<Point> currentTodoList = getTodoList(sourceImage);
            currentTodoList.remove(new Point(sourceImage.XToTileX(targetTile.getMinX()),
                    sourceImage.YToTileY(targetTile.getMinY())));

            done = isDone();
        }
        if (done) {
            // If we get here all tiles are written
            for (SubsetInfo info : subsetInfo) {
                if (info.productWriter instanceof DimapProductWriter) {
                    // if we can update the header (only DIMAP) rewrite it!
                    synchronized (info.productWriter) {
                        info.productWriter.writeProductNodes(info.product, info.file);
                    }
                }
            }
        }
    }

    private boolean isDone() {
        for (List<Point> todoList : todoLists.values()) {
            if (!todoList.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<Point> getTodoList(MultiLevelImage sourceImage) {
        List<Point> todoList = todoLists.get(sourceImage);
        if (todoList == null) {
            final int numXTiles = sourceImage.getNumXTiles();
            final int numYTiles = sourceImage.getNumYTiles();
            todoList = new ArrayList<>(numXTiles * numYTiles);
            for (int y = 0; y < numYTiles; y++) {
                for (int x = 0; x < numXTiles; x++) {
                    todoList.add(new Point(x, y));
                }
            }
            todoLists.put(sourceImage, todoList);
        }
        return todoList;
    }

    @Override
    public void dispose() {
        try {
            for (SubsetInfo info : subsetInfo) {
                info.productWriter.close();
            }
        } catch (IOException ignore) {
        }
        todoLists.clear();
        super.dispose();
    }

    private static class SubsetInfo {
        Product product;
        ProductSubsetBuilder subsetBuilder;
        File file;
        ProductWriter productWriter;
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(TileWriterOp.class);
        }
    }

}
