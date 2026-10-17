/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.ocean.worldwind.layers.ocn;

import eu.esa.sar.ocean.worldwind.layers.Level2ProductLayer;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;

import java.io.IOException;
import java.util.Map;

public class RVL extends OCNComponent {

    public RVL(final Level2ProductLayer wwLayer,
               final Map<String, ColorBarLegend> theColorBarLegendHash,
               final Map<Object, String> theObjectInfoHash,
               final Map<Object, Product> theSurfaceProductHash,
               final Map<Object, Integer> theSurfaceSequenceHash) {
        super(wwLayer,
                theColorBarLegendHash,
                theObjectInfoHash,
                theSurfaceProductHash,
                theSurfaceSequenceHash);
    }

    // default colour range of the radial velocity in m/s, symmetric so that zero is white
    public static final int DEFAULT_MAX_RADIAL_VELOCITY = 6;

    /**
     * Extent of the valid positions of a grid. Swaths are padded with fill values at the end of the rows and
     * columns, but the padding is not the same in every row (slanted swath edges), so the whole grid is searched.
     *
     * @return the number of leading columns and rows up to and including the last one with a valid position
     */
    static int[] getValidExtent(final double[] latValues, final double[] lonValues, final int width, final int height) {
        int maxCol = -1;
        int maxRow = -1;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                final int i = row * width + col;
                if (isValidLatLon(latValues[i], lonValues[i])) {
                    maxCol = Math.max(maxCol, col);
                    maxRow = row;
                }
            }
        }
        return new int[]{maxCol + 1, maxRow + 1};
    }

    /**
     * @return the top left w x h samples of a grid that is srcWidth samples wide
     */
    static double[] crop(final double[] values, final int srcWidth, final int w, final int h) {
        final double[] cropped = new double[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(values, row * srcWidth, cropped, row * w, w);
        }
        return cropped;
    }
    @Override
    public void addProduct(final Product product, final ProductRenderablesInfo productRenderablesInfo)
            throws IOException {

        final MetadataElement metadataRoot = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final String acquisitionMode = absRoot != null ?
                absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE, "") : "";

        int numRVLElements = 0;
        int numSwaths = 0;

        String swathName = "Swath";
        int numImagettes = 1;
        if (acquisitionMode.equalsIgnoreCase("IW")) {
            numSwaths = 3;
        } else if (acquisitionMode.equalsIgnoreCase("EW")) {
            numSwaths = 5;
        } else if (acquisitionMode.equalsIgnoreCase("WV")) {
            numSwaths = 2;
            swathName = "WV";

            if (metadataRoot.getElement("Original_Product_Metadata") != null &&
                    metadataRoot.getElement("Original_Product_Metadata").getElement("annotation") != null) {
                numImagettes = metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getNumElements();
            }
        }

        for (int i = 0; i < numSwaths; i++) {
            String swath = swathName + (i + 1);
            Band rvlLonBand = findFirstBand(product, swath, "rvlLon");
            if (rvlLonBand == null) {
                // swath not present, e.g. in a subset
                continue;
            }

            numRVLElements += (rvlLonBand.getRasterWidth() * rvlLonBand.getRasterHeight());
        }

        if (numRVLElements > 0) {

            for(int img=1; img <= numImagettes; ++img) {
                for (int i = 0; i < numSwaths; i++) {
                    String swath = swathName + (i + 1);
                    String imageName = "IMG" + StringUtils.padNum(img, 3, '0');
                    Band[] lonBands = numImagettes > 1 ?
                            findBands(product, swath, imageName, "rvlLon") : findBands(product, swath, "rvlLon");
                    Band[] latBands = numImagettes > 1 ?
                            findBands(product, swath, imageName, "rvlLat") : findBands(product, swath, "rvlLat");
                    Band[] radVelBands = numImagettes > 1 ?
                            findBands(product, swath, imageName, "rvlRadVel") : findBands(product, swath, "rvlRadVel");

                    if(lonBands.length == 0 || latBands.length == 0 || radVelBands.length == 0) {
                        continue;
                    }

                    Band currRVLLonBand = lonBands[0];
                    Band currRVLLatBand = latBands[0];
                    Band currRVLRadVelBand = radVelBands[0];

                    final int bandW = currRVLRadVelBand.getRasterWidth();
                    final int bandH = currRVLRadVelBand.getRasterHeight();
                    if (currRVLLonBand.getRasterWidth() != bandW || currRVLLonBand.getRasterHeight() != bandH ||
                            currRVLLatBand.getRasterWidth() != bandW || currRVLLatBand.getRasterHeight() != bandH) {
                        throw new IOException("RVL position and radial velocity bands of " + swath + " differ in size");
                    }

                    final double[] lonValues = readValues(currRVLLonBand);
                    final double[] latValues = readValues(currRVLLatBand);
                    final double[] radVelValues = readValues(currRVLRadVelBand);

                    // trim the trailing columns and rows that only contain fill values
                    final int[] extent = getValidExtent(latValues, lonValues, bandW, bandH);
                    final int w = extent[0];
                    final int h = extent[1];
                    if (w < 2 || h < 2) {
                        SystemUtils.LOG.warning("RVL grid of " + swath + " has too few valid positions (" + w + " x " + h + ") and is not drawn");
                        continue;
                    }

                    // fill values inside the trimmed grid (slanted swath edges) are NaN and are not drawn
                    createColorSurfaceWithGradient(null, null, crop(latValues, bandW, w, h), crop(lonValues, bandW, w, h),
                            crop(radVelValues, bandW, w, h), w, h,
                            -DEFAULT_MAX_RADIAL_VELOCITY, DEFAULT_MAX_RADIAL_VELOCITY, true, productRenderablesInfo.theRenderableListHash.get("rvl"), productRenderablesInfo, "rvl");
                }
            }
        }

        double[][] rvlData = null;
        if (acquisitionMode.equalsIgnoreCase("WV") && numImagettes > 1 && numRVLElements == 0) {
            rvlData = new double[3][numImagettes];

            int i = 0;
            for (MetadataElement element : metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getElements()) {

                rvlData[0][i] = getData(element.getElement("rvlLat"));
                rvlData[1][i] = getData(element.getElement("rvlLon"));
                rvlData[2][i] = getData(element.getElement("rvlRadVel"));

                i++;
            }
        }

        if (rvlData != null && numRVLElements == 0) {
            createWVColorSurfaceWithGradient(product, rvlData[0], rvlData[1], rvlData[2],
                    -DEFAULT_MAX_RADIAL_VELOCITY, DEFAULT_MAX_RADIAL_VELOCITY, true,
                    productRenderablesInfo.theRenderableListHash.get("rvl"), productRenderablesInfo, "rvl");
        }
    }
}
