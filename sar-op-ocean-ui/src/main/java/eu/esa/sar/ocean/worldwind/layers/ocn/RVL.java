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

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.ocean.worldwind.layers.Level2ProductLayer;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
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

    @Override
    public void addProduct(final Product product, final ProductRenderablesInfo productRenderablesInfo)
            throws IOException {

        final MetadataElement metadataRoot = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        int numRVLElements = 0;
        int numSwaths = 0;

        if (acquisitionMode.equalsIgnoreCase("IW")) {
            numSwaths = 3;
        } else if (acquisitionMode.equalsIgnoreCase("EW")) {
            numSwaths = 5;
        }
        for (int i = 0; i < numSwaths; i++) {
            String swath = acquisitionMode.toUpperCase() + (i + 1);
            Band rvlLonBand = findBands(product, swath, "rvlLon")[0];

            numRVLElements += (rvlLonBand.getRasterWidth() * rvlLonBand.getRasterHeight());
        }

        final GeoPos geoPos1 = product.getSceneGeoCoding().getGeoPos(new PixelPos(0, 0), null);
        final GeoPos geoPos2 = product.getSceneGeoCoding().getGeoPos(new PixelPos(product.getSceneRasterWidth() - 1,
                product.getSceneRasterHeight() - 1), null);

        if (numRVLElements > 0) {

            boolean displayAsOne = false;

            // with numRVLElements we get BufferOverflow exception
            // it works with 2 swaths: 2*125 *233
            double[] rvlLonValues = null;
            double[] rvlLatValues = null;
            double[] rvlRadVelValues = null;

            if (displayAsOne) {
                rvlLonValues = new double[numRVLElements];
                rvlLatValues = new double[numRVLElements];
                rvlRadVelValues = new double[numRVLElements];
            }

            for (int i = 0; i < numSwaths; i++) {
                String swath = acquisitionMode.toUpperCase() + (i + 1);
                Band currRVLLonBand = findBands(product, swath, "rvlLon")[0];
                Band currRVLLatBand = findBands(product, swath, "rvlLat")[0];
                Band currRVLRadVelBand = findBands(product, swath, "rvlRadVel")[0];

                int w = currRVLRadVelBand.getRasterWidth();
                int h = currRVLRadVelBand.getRasterHeight();

                double[] currRVLLonValues = new double[w];
                currRVLLonBand.readPixels(0, 0, w, 1, currRVLLonValues, ProgressMonitor.NULL);

                // find no data value edge
                for(int cnt=w-1; cnt>0; --cnt) {
                    if(currRVLLonValues[cnt] != currRVLLatBand.getNoDataValue()) {
                        w = cnt;
                        break;
                    }
                }

                currRVLLonValues = new double[h];
                currRVLLonBand.readPixels(0, 0, 1, h, currRVLLonValues, ProgressMonitor.NULL);

                // find no data value edge
                for(int cnt=h-1; cnt>0; --cnt) {
                    if(currRVLLonValues[cnt] != currRVLLatBand.getNoDataValue()) {
                        h = cnt;
                        break;
                    }
                }

                currRVLLonValues = new double[w*h];
                currRVLLonBand.readPixels(0, 0, w, h, currRVLLonValues, ProgressMonitor.NULL);

                double[] currRVLLatValues = new double[w*h];
                currRVLLatBand.readPixels(0, 0, w, h, currRVLLatValues, ProgressMonitor.NULL);

                double[] currRVLRadVelValues = new double[w*h];
                currRVLRadVelBand.readPixels(0, 0, w, h, currRVLRadVelValues, ProgressMonitor.NULL);

                if (displayAsOne) {
                    System.arraycopy(currRVLLonValues, 0, rvlLonValues, i * w*h, w*h);
                    System.arraycopy(currRVLLatValues, 0, rvlLatValues, i * w*h, w*h);
                    System.arraycopy(currRVLRadVelValues, 0, rvlRadVelValues, i * w*h, w*h);
                } else {
                    createColorSurfaceWithGradient(geoPos1, geoPos2, currRVLLatValues, currRVLLonValues, currRVLRadVelValues, w, h,
                            -6, 5, true, productRenderablesInfo.theRenderableListHash.get("rvl"), productRenderablesInfo, "rvl");
                }
            }

            if (displayAsOne) {
                //createColorSurfaceWithGradient(geoPos1, geoPos2, rvlLatValues, rvlLonValues, rvlRadVelValues,
                // numSwaths * rvlSwathWidth, rvlSwathHeight, -6, 5, true,
                // productRenderablesInfo.theRenderableListHash.get("rvl"), productRenderablesInfo, "rvl");
            }
        }

        double[][] rvlData = null;
        if (acquisitionMode.equalsIgnoreCase("WV") &&
                metadataRoot.getElement("Original_Product_Metadata") != null &&
                metadataRoot.getElement("Original_Product_Metadata").getElement("annotation") != null) {
            int numElements = metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getNumElements();
            if (numElements > 0) {
                rvlData = new double[3][numElements];

                int i = 0;
                for (MetadataElement element : metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getElements()) {

                    rvlData[0][i] = getData(element.getElement("rvlLat"));
                    rvlData[1][i] = getData(element.getElement("rvlLon"));
                    rvlData[2][i] = getData(element.getElement("rvlRadVel"));

                    i++;
                }
            }
        }

        if (rvlData != null && numRVLElements == 0) {
            createWVColorSurfaceWithGradient(product, rvlData[0], rvlData[1], rvlData[2], productRenderablesInfo.theRenderableListHash.get("rvl"), "rvl");
        }
    }
}
