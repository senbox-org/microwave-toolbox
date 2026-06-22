package eu.esa.snap.cimr.ui;

import com.bc.ceres.binding.PropertySet;
import com.bc.ceres.glayer.Layer;
import com.bc.ceres.glayer.LayerType;
import com.bc.ceres.glayer.LayerTypeRegistry;
import com.bc.ceres.glayer.support.ImageLayer;
import com.bc.ceres.glayer.support.LayerUtils;
import eu.esa.snap.cimr.CimrL1BProductReader;
import eu.esa.snap.cimr.cimr.CimrFootprints;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.layer.WorldMapLayerType;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.ui.product.ProductSceneView;
import org.openide.modules.OnStart;
import org.openide.windows.OnShowing;

import java.util.logging.Logger;


public class CimrUIManager {

    private static final String WORLDMAP_TYPE_PROPERTY_NAME = "worldmap.type";
    private static final String BLUE_MARBLE_LAYER_TYPE = "BlueMarbleLayerType";

    private static final Logger LOG = Logger.getLogger(CimrUIManager.class.getName());
    private static volatile CimrSceneViewSelectionService sceneViewSelectionService;

    @OnStart
    public static class StartOp implements Runnable {
        @Override
        public void run() {
            LOG.info("Starting CIMR UI");
            sceneViewSelectionService = new CimrSceneViewSelectionService();
        }
    }

    @OnShowing
    public static class ShowingOp implements Runnable {
        @Override
        public void run() {
            LOG.info("CIMR UI showing – installing footprint overlay listener");
            sceneViewSelectionService.addSceneViewSelectionListener(CimrUIManager::handleSceneViewChange);
        }
    }

    private static void handleSceneViewChange(ProductSceneView oldView, ProductSceneView newView) {
//        if (oldView != null) {
//            oldView.getLayerCanvas().removeOverlay(CimrFootprintOverlay.INSTANCE);
//        }
        if (newView != null) {
            // add worldmap layer
            Layer worldMap = findWorldMapLayer(newView);
            if (worldMap == null) {
                worldMap = createWorldMapLayer();
                final Layer rootLayer = newView.getRootLayer();
                rootLayer.getChildren().add(worldMap);
            }
            worldMap.setVisible(true);

//            // add footprints
//            CimrL1BProductReader cimrReader = getCimrReader(newView);
//            if (cimrReader != null) {
//                RasterDataNode raster = newView.getRaster();
//                String band = raster.getName();
//                CimrFootprints fps = cimrReader.getFootprints(band);
//                if (!fps.getShapes().isEmpty()) {
//                    CimrFootprintOverlay.INSTANCE.setFootprints(fps);
//                    CimrFootprintOverlay.INSTANCE.setRaster(raster);
//                    newView.getLayerCanvas().addOverlay(CimrFootprintOverlay.INSTANCE);
//                }
//            }
        }
    }

//    private static CimrL1BProductReader getCimrReader(ProductSceneView view) {
//        RasterDataNode raster = view.getRaster();
//        if (raster == null) {
//            return null;
//        }
//        ProductReader reader = raster.getProductReader();
//        if (reader instanceof CimrL1BProductReader) {
//            return (CimrL1BProductReader) reader;
//        }
//        return null;
//    }

    private static Layer findWorldMapLayer(ProductSceneView view) {
        return LayerUtils.getChildLayer(view.getRootLayer(), LayerUtils.SearchMode.DEEP,
                layer -> layer.getLayerType() instanceof WorldMapLayerType);
    }

    private static Layer createWorldMapLayer() {
        final LayerType layerType = getWorldMapLayerType();
        final PropertySet template = layerType.createLayerConfig(null);
        template.setValue(ImageLayer.PROPERTY_NAME_PIXEL_BORDER_SHOWN, false);
        return layerType.createLayer(null, template);
    }

    private static LayerType getWorldMapLayerType() {
        String layerTypeClassName = SnapApp.getDefault().getPreferences().get(WORLDMAP_TYPE_PROPERTY_NAME, BLUE_MARBLE_LAYER_TYPE);
        return LayerTypeRegistry.getLayerType(layerTypeClassName);
    }
}
