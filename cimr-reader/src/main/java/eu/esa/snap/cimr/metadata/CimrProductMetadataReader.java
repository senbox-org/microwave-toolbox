package eu.esa.snap.cimr.metadata;

import eu.esa.snap.cimr.netcdf.NcUtil;
import eu.esa.snap.cimr.netcdf.NetcdfProductDataConverter;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;


public class CimrProductMetadataReader {


    static final String PRODUCT_NAME_ATTRIBUTE = "product_name";
    static final String CIMR_METADATA_ELEMENT_NAME = "CIMR_Metadata";
    static final String GLOBAL_ATTRIBUTES_ELEMENT_NAME = "Global_Attributes";
    static final String GROUPS_ELEMENT_NAME = "Groups";
    static final String GROUP_ATTRIBUTES_ELEMENT_NAME = "Attributes";
    static final String VARIABLES_ELEMENT_NAME = "Variables";
    static final String VALUE_ATTRIBUTE_NAME = "value";

    private static final NetcdfProductDataConverter PRODUCT_DATA_CONVERTER = new NetcdfProductDataConverter();


    private CimrProductMetadataReader() {}

    public static CimrProductMetadata read(NetcdfFile ncFile, String path, String defaultProductType) throws IOException {
        return new CimrProductMetadata(getProductName(ncFile, path), defaultProductType, readMetadataElement(ncFile));
    }


    private static MetadataElement readMetadataElement(NetcdfFile ncFile) throws IOException {
        MetadataElement cimrRoot = new MetadataElement(CIMR_METADATA_ELEMENT_NAME);
        addGlobalAttributes(cimrRoot, ncFile);

        MetadataElement groupsRoot = getOrCreateElement(cimrRoot, GROUPS_ELEMENT_NAME);
        for (Group group : ncFile.getRootGroup().getGroups()) {
            addGroupIfNotEmpty(groupsRoot, group);
        }
        return cimrRoot;
    }

    private static void addGlobalAttributes(MetadataElement cimrRoot, NetcdfFile ncFile) {
        MetadataElement globalAttributes = getOrCreateElement(cimrRoot, GLOBAL_ATTRIBUTES_ELEMENT_NAME);
        for (Attribute attribute : ncFile.getGlobalAttributes()) {
            addAttribute(globalAttributes, attribute);
        }
    }

    private static void addGroupIfNotEmpty(MetadataElement parent, Group group) throws IOException {
        MetadataElement groupElement = new MetadataElement(group.getShortName());
        addGroupAttributes(groupElement, group);
        addScalarVariables(groupElement, group);

        for (Group child : group.getGroups()) {
            addGroupIfNotEmpty(groupElement, child);
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

    private static void addScalarVariables(MetadataElement groupElement, Group group) throws IOException {
        if (isExcludedVariableGroup(group)) {
            return;
        }

        MetadataElement variablesElement = null;
        for (Variable variable : group.getVariables()) {
            if (variable.getRank() != 0) {
                continue;
            }
            if (isExcludedVariable(group, variable)) {
                continue;
            }

            if (variablesElement == null) {
                variablesElement = getOrCreateElement(groupElement, VARIABLES_ELEMENT_NAME);
            }

            MetadataElement variableElement = getOrCreateElement(variablesElement, variable.getShortName());
            variableElement.addAttribute(new MetadataAttribute(VALUE_ATTRIBUTE_NAME, PRODUCT_DATA_CONVERTER.toProductData(variable.read()), true));
            for (Attribute attribute : variable.attributes()) {
                addAttribute(variableElement, attribute);
            }
        }
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
}
