package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.LazyCrsGeoCoding;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.GeoCoding;

import java.io.File;
import java.util.Map;


public class CimrSnapProductBuilder {

    private static final String AUTO_GROUPING = "L_BAND:C_BAND:X_BAND:KU_BAND:KA_BAND";


    public static Product buildProduct(String productName, String productType, CimrGridProduct cimrProduct, String path) throws Exception {
        CimrGrid grid = cimrProduct.getGlobalGrid();
        Product product = new Product(productName, productType, grid.getWidth(), grid.getHeight());

        addGeoCoding(grid, product);
        addBands(cimrProduct, product);

        product.setFileLocation(new File(path));
        product.setAutoGrouping(AUTO_GROUPING);

        return product;
    }

    private static void addGeoCoding(CimrGrid grid, Product product) {
        GeoCoding geoCoding = new LazyCrsGeoCoding(grid);
        product.setSceneGeoCoding(geoCoding);
    }


    private static void addBands(CimrGridProduct cimrProduct, Product product) {
        CimrGrid grid = cimrProduct.getGlobalGrid();

        for (Map.Entry<CimrBandDescriptor, GridBandDataSource> e : cimrProduct.getBands().entrySet()) {
            CimrBandDescriptor desc = e.getKey();
            GridBandDataSource dataSource = e.getValue();

            Band band = product.addBand(desc.getName(), ProductData.TYPE_FLOAT64);
            band.setDescription(desc.getDescription());
            band.setUnit(desc.getUnit());
            band.setNoDataValue(Double.NaN);
            band.setNoDataValueUsed(true);
            band.setSpectralWavelength(desc.getBand().getSpectralWaveLength());

            CimrGridMultiLevelSource.attachToBand(band, dataSource, grid);
        }
    }
}
