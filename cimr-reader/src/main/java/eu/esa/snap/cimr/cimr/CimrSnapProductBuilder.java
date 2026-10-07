package eu.esa.snap.cimr.cimr;

import eu.esa.snap.cimr.grid.CimrGrid;
import eu.esa.snap.cimr.grid.GridBandDataSource;
import eu.esa.snap.cimr.grid.LazyCrsGeoCoding;
import eu.esa.snap.cimr.metadata.CimrProductMetadata;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.IndexCoding;

import java.io.File;
import java.util.Map;


public class CimrSnapProductBuilder {


    public static Product buildProduct(CimrProductMetadata metadata, CimrGridProduct cimrProduct, String path, String autoGrouping) throws Exception {
        CimrGrid grid = cimrProduct.getGlobalGrid();
        Product product = new Product(metadata.getProductName(), metadata.getProductType(), grid.getWidth(), grid.getHeight());

        addMetadata(metadata, product);
        addGeoCoding(grid, product);
        addBands(cimrProduct, product);

        product.setFileLocation(new File(path));
        product.setAutoGrouping(autoGrouping);

        return product;
    }

    private static void addMetadata(CimrProductMetadata metadata, Product product) {
        product.getMetadataRoot().addElement(metadata.getMetadataElement());
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

            Band band = product.addBand(desc.getName(), desc.getRasterDataType());
            band.setDescription(desc.getDescription());
            band.setUnit(desc.getUnit());
            // TODO: BL - 01/10/2026 - check fillValue handling for future test data
            //band.setNoDataValue(Double.NaN);
            band.setNoDataValueUsed(false);
            band.setSpectralWavelength(desc.getBand().getSpectralWaveLength());
            addSampleCoding(product, band, desc);

            CimrGridMultiLevelSource.attachToBand(band, dataSource, grid);
        }
    }

    private static void addSampleCoding(Product product, Band band, CimrBandDescriptor desc) {
        CimrSampleCoding sampleCoding = desc.getSampleCoding();
        if (sampleCoding == null) {
            return;
        }
        if (sampleCoding.getType() == CimrSampleCoding.Type.INDEX) {
            band.setSampleCoding(getOrCreateIndexCoding(product, sampleCoding));
        }
    }

    private static IndexCoding getOrCreateIndexCoding(Product product, CimrSampleCoding sampleCoding) {
        IndexCoding indexCoding = product.getIndexCodingGroup().get(sampleCoding.getName());
        if (indexCoding == null) {
            indexCoding = new IndexCoding(sampleCoding.getName());
            for (CimrSampleCodingEntry entry : sampleCoding.getEntries()) {
                indexCoding.addIndex(entry.getName(), entry.getValue(), entry.getDescription());
            }
            product.getIndexCodingGroup().add(indexCoding);
        }
        return indexCoding;
    }
}
