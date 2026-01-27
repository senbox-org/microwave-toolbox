package eu.esa.snap.cimr.ui;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.rcp.windows.ToolTopComponent;
import org.esa.snap.ui.product.ProductSceneView;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;


public class CimrSceneViewSelectionService extends ToolTopComponent {

    private final List<SelectionListener> selectionListeners = new ArrayList<>();
    private ProductSceneView selectedSceneView;


    @Override
    protected void productSceneViewSelected(@NonNull ProductSceneView view) {
        setSelectedSceneView(view);
    }

    @Override
    protected void productSceneViewDeselected(@NonNull ProductSceneView view) {
        setSelectedSceneView(null);
    }

    private void setSelectedSceneView(ProductSceneView newView) {
        ProductSceneView oldView = selectedSceneView;
        if (oldView == newView) {
            return;
        }
        if (newView != null) {
            Product p = newView.getProduct();
            if (p == null) {
                return;
            }
        }
        selectedSceneView = newView;
        fireSelectionChange(oldView, newView);
    }

    public synchronized void addSceneViewSelectionListener(SelectionListener l) {
        selectionListeners.add(l);
    }

    private void fireSelectionChange(ProductSceneView oldView, ProductSceneView newView) {
        for (SelectionListener listener : new ArrayList<>(selectionListeners)) {
            listener.handleSceneViewSelectionChanged(oldView, newView);
        }
    }


    public interface SelectionListener {
        void handleSceneViewSelectionChanged(ProductSceneView oldView, ProductSceneView newView);
    }
}
