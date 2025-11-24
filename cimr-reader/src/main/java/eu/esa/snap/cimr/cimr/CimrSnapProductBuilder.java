package eu.esa.snap.cimr.cimr;

import com.bc.ceres.multilevel.MultiLevelModel;
import com.bc.ceres.multilevel.support.DefaultMultiLevelModel;
import eu.esa.snap.cimr.grid.GlobalGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.CrsGeoCoding;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.Map;


public class CimrSnapProductBuilder {

    private static final String AUTO_GROUPING = "L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND";


    public static Product buildProduct(String productName, String productType, CimrGridProduct cimrProduct, String path) throws Exception {
        GlobalGrid grid = cimrProduct.getGlobalGrid();
        int width = grid.getWidth();
        int height = grid.getHeight();

        Product product = new Product(productName, productType, width, height);

        addGeoCoding(grid, width, height, product);
        addBands(cimrProduct, width, height, product);

        product.setFileLocation(new File(path));
        product.setAutoGrouping(AUTO_GROUPING);

        return product;
    }

    private static void addGeoCoding(GlobalGrid grid, int width, int height, Product product) throws FactoryException, TransformException {
        CoordinateReferenceSystem crs = grid.getProjection().getCrs();
        AffineTransform imageToModel = grid.getProjection().getAffineTransform(grid);

        GeoCoding geoCoding = new CrsGeoCoding(crs, new Rectangle(width, height), imageToModel);
        product.setSceneGeoCoding(geoCoding);
    }


    private static void addBands(CimrGridProduct cimrProduct, int width, int height, Product product) {
        int levelCount = 7;
        AffineTransform imageToModel = (AffineTransform) product.getSceneGeoCoding().getImageToMapTransform();
        MultiLevelModel mlModel = new DefaultMultiLevelModel(levelCount, imageToModel, width, height);

        for (Map.Entry<CimrBandDescriptor, GridBandDataSource> e : cimrProduct.getBands().entrySet()) {
            CimrBandDescriptor desc = e.getKey();
            GridBandDataSource dataSource = e.getValue();

            Band band = product.addBand(desc.getName(), ProductData.TYPE_FLOAT64);
            // TODO set noDataValue, description, unit, spectral_wavelength on band
            CimrGridMultiLevelSource.attachToBand(band, dataSource, mlModel);
        }
    }
}
