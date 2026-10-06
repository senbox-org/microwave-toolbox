package eu.esa.snap.cimr.cimr;

import org.esa.snap.core.datamodel.ProductData;


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
    private final int rasterDataType;
    private final String unit;
    private final String description;
    private final CimrSampleCoding sampleCoding;


    public CimrBandDescriptor(String name, String valueVarName, CimrFrequencyBand band, String[] geometryNames, String[] footprintVars, String groupPath, int feedIndex, CimrDescriptorKind kind, String[] dimensions, String dataType, String unit, String description) {
        this(name, valueVarName, band, geometryNames, footprintVars, groupPath, feedIndex, kind, dimensions, dataType, defaultRasterDataType(dataType), unit, description, null);
    }

    public CimrBandDescriptor(String name, String valueVarName, CimrFrequencyBand band, String[] geometryNames, String[] footprintVars, String groupPath, int feedIndex, CimrDescriptorKind kind, String[] dimensions, String dataType, String unit, String description, CimrSampleCoding sampleCoding) {
        this(name, valueVarName, band, geometryNames, footprintVars, groupPath, feedIndex, kind, dimensions, dataType, defaultRasterDataType(dataType), unit, description, sampleCoding);
    }

    public CimrBandDescriptor(String name, String valueVarName, CimrFrequencyBand band, String[] geometryNames, String[] footprintVars, String groupPath, int feedIndex, CimrDescriptorKind kind, String[] dimensions, String dataType, int rasterDataType, String unit, String description) {
        this(name, valueVarName, band, geometryNames, footprintVars, groupPath, feedIndex, kind, dimensions, dataType, rasterDataType, unit, description, null);
    }

    public CimrBandDescriptor(String name, String valueVarName, CimrFrequencyBand band, String[] geometryNames, String[] footprintVars, String groupPath, int feedIndex, CimrDescriptorKind kind, String[] dimensions, String dataType, int rasterDataType, String unit, String description, CimrSampleCoding sampleCoding) {
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
        this.rasterDataType = rasterDataType;
        this.unit = unit;
        this.description = description;
        this.sampleCoding = sampleCoding;
    }

    public CimrBandDescriptor withRasterDataType(int rasterDataType) {
        return new CimrBandDescriptor(name, valueVarName, band, geometryNames, footprintVars, groupPath, feedIndex, kind, dimensions, dataType, rasterDataType, unit, description, sampleCoding);
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

    public int getRasterDataType() {
        return rasterDataType;
    }

    public String getUnit() {
        return unit;
    }

    public String getDescription() {
        return description;
    }

    public CimrSampleCoding getSampleCoding() {
        return sampleCoding;
    }

    private static int defaultRasterDataType(String dataType) {
        if (dataType == null) {
            return ProductData.TYPE_FLOAT64;
        }
        return switch (dataType.toLowerCase()) {
            case "byte" -> ProductData.TYPE_INT8;
            case "short" -> ProductData.TYPE_INT16;
            case "int", "integer" -> ProductData.TYPE_INT32;
            case "long" -> ProductData.TYPE_INT64;
            case "float" -> ProductData.TYPE_FLOAT32;
            case "double" -> ProductData.TYPE_FLOAT64;
            default -> ProductData.TYPE_FLOAT64;
        };
    }
}
