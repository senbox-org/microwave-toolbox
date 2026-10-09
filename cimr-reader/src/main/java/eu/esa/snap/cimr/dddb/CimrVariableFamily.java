package eu.esa.snap.cimr.dddb;

import eu.esa.snap.cimr.dddb.descriptor.CimrSampleCoding;


public class CimrVariableFamily {


    private String kind;
    private String valueVarName;
    private String groupPath;
    private String[] dimensions;
    private String dataType;
    private String unit;
    private String description;
    private boolean expandFeeds;
    private String[] geometryVariables;
    private String[] footprintVariables;
    private CimrSampleCoding sampleCoding;


    public CimrVariableFamily() {
        this.kind = "";
        this.valueVarName = "";
        this.groupPath = "";
        this.dimensions = new String[0];
        this.dataType = "";
        this.unit = "";
        this.description = "";
        this.expandFeeds = true;
        this.geometryVariables = new String[0];
        this.footprintVariables = new String[0];
        this.sampleCoding = null;
    }


    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getValueVarName() {
        return valueVarName;
    }

    public void setValueVarName(String valueVarName) {
        this.valueVarName = valueVarName;
    }

    public String getGroupPath() {
        return groupPath;
    }

    public void setGroupPath(String groupPath) {
        this.groupPath = groupPath;
    }

    public String[] getDimensions() {
        return dimensions;
    }

    public void setDimensions(String[] dimensions) {
        this.dimensions = dimensions;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isExpandFeeds() {
        return expandFeeds;
    }

    public void setExpandFeeds(boolean expandFeeds) {
        this.expandFeeds = expandFeeds;
    }

    public String[] getGeometryVariables() {
        return geometryVariables;
    }

    public void setGeometryVariables(String[] geometryVariables) {
        this.geometryVariables = geometryVariables;
    }

    public String[] getFootprintVariables() {
        return footprintVariables;
    }

    public void setFootprintVariables(String[] footprintVariables) {
        this.footprintVariables = footprintVariables;
    }

    public CimrSampleCoding getSampleCoding() {
        return sampleCoding;
    }

    public void setSampleCoding(CimrSampleCoding sampleCoding) {
        this.sampleCoding = sampleCoding;
    }
}
