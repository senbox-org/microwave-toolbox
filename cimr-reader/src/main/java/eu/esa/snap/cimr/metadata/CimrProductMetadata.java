package eu.esa.snap.cimr.metadata;

import org.esa.snap.core.datamodel.MetadataElement;


public class CimrProductMetadata {


    private final String productName;
    private final String productType;
    private final MetadataElement metadataElement;


    public CimrProductMetadata(String productName, String productType, MetadataElement metadataElement) {
        this.productName = productName;
        this.productType = productType;
        this.metadataElement = metadataElement;
    }


    public String getProductName() {
        return productName;
    }

    public String getProductType() {
        return productType;
    }

    public MetadataElement getMetadataElement() {
        return metadataElement;
    }
}