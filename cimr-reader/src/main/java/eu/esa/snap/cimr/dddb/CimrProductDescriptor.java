package eu.esa.snap.cimr.dddb;


public class CimrProductDescriptor {


    private String productType;
    private String version;
    private String bandsFile;
    private String[] descriptorFiles;
    private String autoGrouping;


    public CimrProductDescriptor() {
        this.productType = "";
        this.version = "";
        this.bandsFile = "";
        this.descriptorFiles = new String[0];
        this.autoGrouping = "";
    }


    public String getProductType() {
        return productType;
    }

    public void setProductType(String productType) {
        this.productType = productType;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getBandsFile() {
        return bandsFile;
    }

    public void setBandsFile(String bandsFile) {
        this.bandsFile = bandsFile;
    }

    public String[] getDescriptorFiles() {
        return descriptorFiles;
    }

    public void setDescriptorFiles(String[] descriptorFiles) {
        this.descriptorFiles = descriptorFiles;
    }

    public String getAutoGrouping() {
        return autoGrouping;
    }

    public void setAutoGrouping(String autoGrouping) {
        this.autoGrouping = autoGrouping;
    }

}
