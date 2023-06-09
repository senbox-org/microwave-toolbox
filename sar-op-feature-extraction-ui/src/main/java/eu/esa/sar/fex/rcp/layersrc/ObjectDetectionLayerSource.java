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
package eu.esa.sar.fex.rcp.layersrc;

import eu.esa.sar.fex.gpf.oceantools.ObjectDiscriminationOp;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.VectorDataNode;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.ui.layer.AbstractLayerSourceAssistantPage;
import org.esa.snap.ui.layer.LayerSource;
import org.esa.snap.ui.layer.LayerSourcePageContext;

import java.io.File;

/**
 * A source for ObjectDetection
 */
public class ObjectDetectionLayerSource implements LayerSource {

    @Override
    public boolean isApplicable(LayerSourcePageContext pageContext) {
        final Product product = SnapApp.getDefault().getSelectedProductSceneView().getProduct();

        VectorDataNode vectorDataNode = product.getVectorDataGroup().get(ObjectDiscriminationOp.VECTOR_NODE_NAME);
        if (vectorDataNode != null) {
            return true;
        }
        final File targetFile = ObjectDetectionLayer.getTargetFile(product);
        return targetFile != null;
    }

    @Override
    public boolean hasFirstPage() {
        return false;
    }

    @Override
    public AbstractLayerSourceAssistantPage getFirstPage(LayerSourcePageContext pageContext) {
        return null;
    }

    @Override
    public boolean canFinish(LayerSourcePageContext pageContext) {
        return true;
    }

    @Override
    public boolean performFinish(LayerSourcePageContext pageContext) {
        final Product product = SnapApp.getDefault().getSelectedProductSceneView().getProduct();
        final Band band = product.getBand(SnapApp.getDefault().getSelectedProductSceneView().getRaster().getName());

        final ObjectDetectionLayer fieldLayer = ObjectDetectionLayerType.createLayer(product, band);
        pageContext.getLayerContext().getRootLayer().getChildren().add(0, fieldLayer);
        return true;
    }

    @Override
    public void cancel(LayerSourcePageContext pageContext) {
    }
}
