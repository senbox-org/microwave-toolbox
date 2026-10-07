package eu.esa.snap.cimr.metadata;

import eu.esa.snap.cimr.netcdf.NcUtil;
import eu.esa.snap.cimr.netcdf.NetcdfProductDataConverter;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


public class CimrProductMetadataReader {


    static final String PRODUCT_NAME_ATTRIBUTE = "product_name";
    static final String CIMR_METADATA_ELEMENT_NAME = "CIMR_Metadata";
    static final String GLOBAL_ATTRIBUTES_ELEMENT_NAME = "Global_Attributes";
    static final String GROUPS_ELEMENT_NAME = "Groups";
    static final String GROUP_ATTRIBUTES_ELEMENT_NAME = "Attributes";
    static final String VARIABLES_ELEMENT_NAME = "Variables";

    private static final NetcdfProductDataConverter PRODUCT_DATA_CONVERTER = new NetcdfProductDataConverter();
    private static final CimrVariableMetadataReader VARIABLE_METADATA_READER = new CimrVariableMetadataReader(PRODUCT_DATA_CONVERTER);


    private CimrProductMetadataReader() {}


    public static CimrProductMetadata read(NetcdfFile ncFile, String path, String defaultProductType) throws IOException {
        Map<String, Variable> metadataVariables = new HashMap<>();
        return new CimrProductMetadata(
                getProductName(ncFile, path),
                defaultProductType,
                readMetadataElement(ncFile, new NetcdfMetadataProvider(metadataVariables))
        );
    }


    private static MetadataElement readMetadataElement(NetcdfFile ncFile, NetcdfMetadataProvider metadataProvider) throws IOException {
        MetadataElement cimrRoot = new MetadataElement(CIMR_METADATA_ELEMENT_NAME);
        addGlobalAttributes(cimrRoot, ncFile);

        MetadataElement groupsRoot = getOrCreateElement(cimrRoot, GROUPS_ELEMENT_NAME);
        for (Group group : ncFile.getRootGroup().getGroups()) {
            addGroupIfNotEmpty(groupsRoot, group, metadataProvider);
        }
        return cimrRoot;
    }

    private static void addGlobalAttributes(MetadataElement cimrRoot, NetcdfFile ncFile) {
        MetadataElement globalAttributes = getOrCreateElement(cimrRoot, GLOBAL_ATTRIBUTES_ELEMENT_NAME);
        for (Attribute attribute : ncFile.getGlobalAttributes()) {
            addAttribute(globalAttributes, attribute);
        }
    }

    private static void addGroupIfNotEmpty(MetadataElement parent, Group group, NetcdfMetadataProvider metadataProvider) throws IOException {
        MetadataElement groupElement = new MetadataElement(group.getShortName());
        addGroupAttributes(groupElement, group);
        addMetadataVariables(groupElement, group, metadataProvider);

        for (Group child : group.getGroups()) {
            addGroupIfNotEmpty(groupElement, child, metadataProvider);
        }

        if (hasContent(groupElement)) {
            parent.addElement(groupElement);
        }
    }

    private static void addGroupAttributes(MetadataElement groupElement, Group group) {
        if (!group.attributes().iterator().hasNext()) {
            return;
        }

        MetadataElement attributesElement = getOrCreateElement(groupElement, GROUP_ATTRIBUTES_ELEMENT_NAME);
        for (Attribute attribute : group.attributes()) {
            addAttribute(attributesElement, attribute);
        }
    }

    private static void addMetadataVariables(MetadataElement groupElement, Group group, NetcdfMetadataProvider metadataProvider) {
        if (isExcludedVariableGroup(group)) {
            return;
        }

        MetadataElement variablesElement = null;
        for (Variable variable : group.getVariables()) {
            if (isExcludedVariable(group, variable)) {
                continue;
            }
            if (!isMetadataVariable(group, variable)) {
                continue;
            }

            if (variablesElement == null) {
                variablesElement = getOrCreateElement(groupElement, VARIABLES_ELEMENT_NAME);
            }

            String variablePath = variablePath(group, variable);
            metadataProvider.addVariable(variablePath, variable);
            MetadataElement variableElement = new CimrLazyMetadataElement(variable.getShortName(), variablePath, metadataProvider);
            addVariableAttributes(variableElement, variable);
            variablesElement.addElement(variableElement);
        }
    }

    private static void addVariableAttributes(MetadataElement variableElement, Variable variable) {
        addStringAttribute(variableElement, "data_type", variable.getDataType().toString());
        addStringAttribute(variableElement, "dimensions", variable.getDimensionsString());
        addStringAttribute(variableElement, "shape", shapeToString(variable.getShape()));
        for (Attribute attribute : variable.attributes()) {
            addAttribute(variableElement, attribute);
        }
    }

    private static void addStringAttribute(MetadataElement element, String name, String value) {
        if (value != null && !value.isBlank()) {
            element.addAttribute(new MetadataAttribute(name, ProductData.createInstance(value), true));
        }
    }

    private static String shapeToString(int[] shape) {
        if (shape == null || shape.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(shape[i]);
        }
        return builder.toString();
    }

    private static boolean isMetadataVariable(Group group, Variable variable) {
        if (variable.getRank() == 0) {
            return true;
        }

        String groupPath = normalizePath(group.getFullName());
        String variableName = variable.getShortName();
        return isStatusSatelliteMetadata(groupPath, variableName)
                || isStatusInstrumentMetadata(groupPath, variableName)
                || isMeasurementMetadata(groupPath, variableName)
                || isNavigationMetadata(groupPath, variableName)
                || isCalibrationMetadata(groupPath, variableName)
                || isQualityMetadata(groupPath, variableName);
    }

    private static boolean isStatusSatelliteMetadata(String groupPath, String variableName) {
        return groupPath.startsWith("Status/Satellite/")
                && ("x_position".equals(variableName)
                || "y_position".equals(variableName)
                || "z_position".equals(variableName)
                || "x_velocity".equals(variableName)
                || "y_velocity".equals(variableName)
                || "z_velocity".equals(variableName)
                || "q0".equals(variableName)
                || "q1".equals(variableName)
                || "q2".equals(variableName)
                || "q3".equals(variableName));
    }

    private static boolean isStatusInstrumentMetadata(String groupPath, String variableName) {
        return "Status/Instrument".equals(groupPath)
                && ("instrument_mode".equals(variableName)
                || variableName.startsWith("mode_start_time_utc_")
                || variableName.startsWith("mode_stop_time_utc_"));
    }

    private static boolean isMeasurementMetadata(String groupPath, String variableName) {
        return groupPath.startsWith("Data/Measurement_Data/")
                && "acq_time_utc".equals(variableName);
    }

    private static boolean isNavigationMetadata(String groupPath, String variableName) {
        return groupPath.startsWith("Data/Navigation_Data/")
                && ("processing_scan_angle_feeds_offsets".equals(variableName)
                || "telemetry_scan_angle".equals(variableName)
                || "orbit_angle".equals(variableName)
                || "spacecraft_altitude".equals(variableName)
                || "sub_satellite_lat".equals(variableName)
                || "sub_satellite_lon".equals(variableName)
                || "boresight2AntennaPlane".equals(variableName)
                || "antennaPlane2EarthFixed".equals(variableName));
    }

    private static boolean isCalibrationMetadata(String groupPath, String variableName) {
        return groupPath.startsWith("Data/Calibration_Data/")
                && ("t_cold_h".equals(variableName)
                || "t_cold_v".equals(variableName)
                || "t_hot_h".equals(variableName)
                || "t_hot_v".equals(variableName)
                || "thermistor_counts".equals(variableName));
    }

    private static boolean isQualityMetadata(String groupPath, String variableName) {
        return "Quality".equals(groupPath)
                && ("gap_start_time_utc".equals(variableName)
                || "gap_end_time_utc".equals(variableName));
    }

    private static boolean isExcludedVariableGroup(Group group) {
        String groupPath = normalizePath(group.getFullName());
        return "Data/Quality_Information".equals(groupPath)
                || groupPath.startsWith("Data/Quality_Information/")
                || "Data/Processing_Flags".equals(groupPath)
                || groupPath.startsWith("Data/Processing_Flags/");
    }

    private static boolean isExcludedVariable(Group group, Variable variable) {
        String groupPath = normalizePath(group.getFullName());
        return "Quality".equals(groupPath) && "overall_quality_flag".equals(variable.getShortName());
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String variablePath(Group group, Variable variable) {
        String groupPath = normalizePath(group.getFullName());
        if (groupPath.isEmpty()) {
            return variable.getShortName();
        }
        return groupPath + "/" + variable.getShortName();
    }

    private static boolean hasContent(MetadataElement element) {
        return element.getNumAttributes() > 0 || element.getNumElements() > 0;
    }

    private static void addAttribute(MetadataElement element, Attribute attribute) {
        element.addAttribute(new MetadataAttribute(attribute.getShortName(), PRODUCT_DATA_CONVERTER.toProductData(attribute), true));
    }

    private static MetadataElement getOrCreateElement(MetadataElement parent, String name) {
        MetadataElement element = parent.getElement(name);
        if (element == null) {
            element = new MetadataElement(name);
            parent.addElement(element);
        }
        return element;
    }

    private static String getProductName(NetcdfFile ncFile, String path) {
        String productName = NcUtil.getGlobalAttributeString(ncFile, PRODUCT_NAME_ATTRIBUTE);
        if (productName != null && !productName.isBlank()) {
            return productName.trim();
        }
        return getFileNameWithoutExtension(path);
    }

    private static String getFileNameWithoutExtension(String path) {
        String fileName = new File(path).getName();
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionIndex);
    }

    private static class NetcdfMetadataProvider implements CimrMetadataProvider {


        private final Map<String, Variable> variablesByPath;


        private NetcdfMetadataProvider(Map<String, Variable> variablesByPath) {
            this.variablesByPath = variablesByPath;
        }


        void addVariable(String variablePath, Variable variable) {
            variablesByPath.put(variablePath, variable);
        }

        @Override
        public MetadataElement readElement(String variablePath) throws IOException {
            Variable variable = variablesByPath.get(variablePath);
            if (variable == null) {
                return null;
            }
            return VARIABLE_METADATA_READER.read(variable);
        }
    }
}
