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
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;

import java.util.Map;

public class OSW extends OCNComponent {

    public OSW(final Level2ProductLayer wwLayer,
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
    public void addProduct(final Product product, final ProductRenderablesInfo productRenderablesInfo) {

        final MetadataElement metadataRoot = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        double[][] oswData = null;

        if (acquisitionMode.equalsIgnoreCase("WV") &&
                metadataRoot.getElement("Original_Product_Metadata") != null &&
                metadataRoot.getElement("Original_Product_Metadata").getElement("annotation") != null) {
            int numElements = metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getNumElements();
            if (numElements > 0) {
                oswData = new double[5][numElements];

                int i = 0;
                for (MetadataElement element : metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getElements()) {
                    oswData[0][i] = getData(element.getElement("oswLat"));
                    oswData[1][i] = getData(element.getElement("oswLon"));
                    oswData[2][i] = getData(element.getElement("oswHs"));
                    oswData[3][i] = getData(element.getElement("oswWl"));
                    oswData[4][i] = getData(element.getElement("oswDirmet"));

                    i++;
                }
            }
        }

        if (oswData != null) {
            addWaveLengthArrows(oswData[0], oswData[1], oswData[3], oswData[4], productRenderablesInfo.theRenderableListHash.get("osw"));
            createWVColorSurfaceWithGradient(product, oswData[0], oswData[1], oswData[2], productRenderablesInfo.theRenderableListHash.get("osw"), "osw");
        }
    }
}
