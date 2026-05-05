package eu.esa.snap.cimr.cimr;


public class CimrBandDescriptor {

    private final String name;
    private final String valueVarName;
    private final CimrFrequencyBand band;
    private final String[] geometryNames;
    private final String[] footprintVars;
    private final String groupPath;
    private final int feedIndex;
    private final CimrDescriptorKind kind;
    private final String[] dimensions;
    private final String dataType;
    private final String unit;
    private final String description;


    public CimrBandDescriptor(String name, String valueVarName, CimrFrequencyBand band, String[] geometryNames, String[] footprintVars, String groupPath, int feedIndex, CimrDescriptorKind kind, String[] dimensions, String dataType, String unit, String description) {
        this.name = name;
        this.valueVarName = valueVarName;
        this.band = band;
        this.geometryNames = geometryNames;
        this.footprintVars = footprintVars;
        this.groupPath = groupPath;
        this.feedIndex = feedIndex;
        this.kind = kind;
        this.dimensions = dimensions;
        this.dataType = dataType;
        this.unit = unit;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getValueVarName() {
        return valueVarName;
    }

    public CimrFrequencyBand getBand() {
        return band;
    }

    public String[] getGeometryNames() {
        return geometryNames;
    }

    public String[] getFootprintVars() {
        return footprintVars;
    }

    public String getGroupPath() {
        return groupPath;
    }

    public int getFeedIndex() {
        return feedIndex;
    }

    public CimrDescriptorKind getKind() {
        return kind;
    }

    public String[] getDimensions() {
        return dimensions;
    }

    public String getDataType() {
        return dataType;
    }

    public String getUnit() {
        return unit;
    }

    public String getDescription() {
        return description;
    }
}
