package eu.esa.snap.cimr.dddb;


public class CimrBandDefinition {


    private String name;
    private int feedCount;
    private String feedDimension;
    private String sampleDimension;
    private String tiePointDimension;


    public CimrBandDefinition() {
        this.name = "";
        this.feedCount = 0;
        this.feedDimension = "";
        this.sampleDimension = "";
        this.tiePointDimension = "";
    }


    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getFeedCount() {
        return feedCount;
    }

    public void setFeedCount(int feedCount) {
        this.feedCount = feedCount;
    }

    public String getFeedDimension() {
        return feedDimension;
    }

    public void setFeedDimension(String feedDimension) {
        this.feedDimension = feedDimension;
    }

    public String getSampleDimension() {
        return sampleDimension;
    }

    public void setSampleDimension(String sampleDimension) {
        this.sampleDimension = sampleDimension;
    }

    public String getTiePointDimension() {
        return tiePointDimension;
    }

    public void setTiePointDimension(String tiePointDimension) {
        this.tiePointDimension = tiePointDimension;
    }
}
