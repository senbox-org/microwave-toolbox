/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.io.netcdf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.TiePointGrid;
import org.esa.snap.core.dataop.maptransf.Datum;
import org.esa.snap.core.dataop.maptransf.IdentityTransformDescriptor;
import org.esa.snap.core.dataop.maptransf.MapInfo;
import org.esa.snap.core.dataop.maptransf.MapProjection;
import org.esa.snap.core.dataop.maptransf.MapProjectionRegistry;
import org.esa.snap.core.util.Debug;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.netcdf.util.DataTypeUtils;
import org.esa.snap.dataio.netcdf.util.MetadataUtils;
import org.esa.snap.dataio.netcdf.util.ReaderUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.Group;
import ucar.nc2.Structure;
import ucar.nc2.Variable;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides some NetCDF related utility methods.
 */
public class NetCDFUtils {

    private NetCDFUtils() {
    }

    public static Band createBand(final Variable variable, final int rasterWidth, final int rasterHeight) {
        return createBand(variable, rasterWidth, rasterHeight, ProductData.TYPE_UNDEFINED);
    }

    public static Band createBand(final Variable variable, final int rasterWidth, final int rasterHeight, int dataType) {
        final NcAttributeMap attMap = NcAttributeMap.create(variable);
        final Band band = new Band(variable.getShortName(),
                (dataType == ProductData.TYPE_UNDEFINED) ? getRasterDataType(variable) : dataType,
                rasterWidth,
                rasterHeight);
        band.setDescription(getDescription(variable, attMap));
        band.setUnit(getUnit(variable, attMap));
        band.setScalingFactor(getScalingFactor(attMap));
        band.setScalingOffset(getAddOffset(attMap));

        final Number noDataValue = getNoDataValue(attMap);
        if (noDataValue != null) {
            band.setNoDataValue(noDataValue.doubleValue());
            band.setNoDataValueUsed(true);
        }
        return band;
    }

    public static TiePointGrid createTiePointGrid(final Variable variable, final int gridWidth, final int gridHeight,
                                                  final int sceneWidth, final int sceneHeight) throws IOException {
        final NcAttributeMap attMap = NcAttributeMap.create(variable);

        final double subSamplingX = (double) sceneWidth / (double) (gridWidth - 1);
        final double subSamplingY = (double) sceneHeight / (double) (gridHeight - 1);

        final Array data = variable.read();

        final float[] dataArray = new float[gridWidth*gridHeight];
        if (data.getRank() == 3) {
            int i = 0;
            for(int h=0;h<gridHeight;++h) {
                for(int w=0;w<gridWidth;++w) {
                    dataArray[i++] = data.getFloat(data.getIndex().set(0, h, w));
                }
            }
        } else {
            for (int i = 0; i < dataArray.length; ++i) {
                dataArray[i] = data.getFloat(i);
            }
        }

        final TiePointGrid tpg = new TiePointGrid(variable.getShortName(), gridWidth, gridHeight, 0, 0,
                subSamplingX, subSamplingY, dataArray);

        tpg.setDescription(getDescription(variable, attMap));
        tpg.setUnit(getUnit(variable, attMap));
        tpg.setScalingFactor(getScalingFactor(attMap));
        tpg.setScalingOffset(getAddOffset(attMap));

        final Number noDataValue = getNoDataValue(attMap);
        if (noDataValue != null) {
            tpg.setNoDataValue(noDataValue.doubleValue());
            tpg.setNoDataValueUsed(true);
        }
        return tpg;
    }

    private static String getDescription(final Variable variable, final NcAttributeMap attMap) {
        String desc = variable.getDescription();
        if (desc == null || desc.isEmpty()) {
            desc = attMap.getStringValue(NetcdfConstants.DESCRIPTION);
        }
        return desc;
    }

    private static String getUnit(final Variable variable, final NcAttributeMap attMap) {
        String unit = variable.getUnitsString();
        if (unit == null || unit.isEmpty()) {
            unit = attMap.getStringValue(NetcdfConstants.UNIT);
        }
        return unit;
    }

    private static double getScalingFactor(final NcAttributeMap attMap) {
        Number numValue = attMap.getNumericValue(NetcdfConstants.SCALE_FACTOR_ATT_NAME);
        if (numValue == null) {
            numValue = attMap.getNumericValue(NetcdfConstants.SLOPE_ATT_NAME);
        }
        return numValue != null ? numValue.doubleValue() : 1.0;
    }

    private static double getAddOffset(final NcAttributeMap attMap) {
        Number numValue = attMap.getNumericValue(NetcdfConstants.ADD_OFFSET_ATT_NAME);
        if (numValue == null) {
            numValue = attMap.getNumericValue(NetcdfConstants.INTERCEPT_ATT_NAME);
        }
        return numValue != null ? numValue.doubleValue() : 0.0;
    }

    private static Number getNoDataValue(final NcAttributeMap attMap) {
        Number noDataValue = attMap.getNumericValue(NetcdfConstants.FILL_VALUE_ATT_NAME);
        if (noDataValue == null) {
            noDataValue = attMap.getNumericValue(NetcdfConstants.MISSING_VALUE_ATT_NAME);
        }
        return noDataValue;
    }

    public static MapInfoX createMapInfoX(final Variable lonVar,
                                          final Variable latVar,
                                          final int sceneRasterWidth,
                                          final int sceneRasterHeight) throws IOException {
        float pixelX;
        float pixelY;
        float easting;
        float northing;
        float pixelSizeX;
        float pixelSizeY;

        final NcAttributeMap lonAttrMap = NcAttributeMap.create(lonVar);
        final Number lonValidMin = lonAttrMap.getNumericValue(NetcdfConstants.VALID_MIN_ATT_NAME);
        final Number lonStep = lonAttrMap.getNumericValue(NetcdfConstants.STEP_ATT_NAME);

        final NcAttributeMap latAttrMap = NcAttributeMap.create(latVar);
        final Number latValidMin = latAttrMap.getNumericValue(NetcdfConstants.VALID_MIN_ATT_NAME);
        final Number latStep = latAttrMap.getNumericValue(NetcdfConstants.STEP_ATT_NAME);

        boolean yFlipped;
        if (lonValidMin != null && lonStep != null && latValidMin != null && latStep != null) {
            // COARDS convention uses 'valid_min' and 'step' attributes
            pixelX = 0.5f;
            pixelY = (sceneRasterHeight - 1.0f) + 0.5f;
            easting = lonValidMin.floatValue();
            northing = latValidMin.floatValue();
            pixelSizeX = lonStep.floatValue();
            pixelSizeY = latStep.floatValue();
            // must flip
            yFlipped = true; // todo - check
        } else {
            // CF convention

            final Array lonData = lonVar.read();
            final Array latData = latVar.read();

            final Index i0 = lonData.getIndex().set(0);
            final Index i1 = lonData.getIndex().set(1);
            pixelSizeX = lonData.getFloat(i1) - lonData.getFloat(i0);
            easting = lonData.getFloat(i0);

            final int latSize = (int) latVar.getSize();
            final Index j0 = latData.getIndex().set(0);
            final Index j1 = latData.getIndex().set(1);
            pixelSizeY = latData.getFloat(j1) - latData.getFloat(j0);

            pixelX = 0.5f;
            pixelY = 0.5f;

            // this should be the 'normal' case
            if (pixelSizeY < 0) {
                pixelSizeY *= -1;
                yFlipped = false;
                northing = latData.getFloat(latData.getIndex().set(0));
            } else {
                yFlipped = true;
                northing = latData.getFloat(latData.getIndex().set(latSize - 1));
            }
        }

        if (pixelSizeX <= 0 || pixelSizeY <= 0) {
            return null;
        }

        final MapProjection projection = MapProjectionRegistry.getProjection(IdentityTransformDescriptor.NAME);
        final MapInfo mapInfo = new MapInfo(projection,
                pixelX, pixelY,
                easting, northing,
                pixelSizeX, pixelSizeY,
                Datum.WGS_84);
        mapInfo.setSceneWidth(sceneRasterWidth);
        mapInfo.setSceneHeight(sceneRasterHeight);
        return new MapInfoX(mapInfo, yFlipped);
    }

    private static int getRasterDataType(final Variable variable) {
        return getProductDataType(variable.getDataType(), variable.getDataType().isUnsigned(), true);
    }

    private static boolean isValidRasterDataType(final DataType dataType) {
        return getProductDataType(dataType, false, true) != -1;
    }

    private static int getProductDataType(final DataType dataType, final boolean unsigned, final boolean rasterDataOnly) {
        if (dataType == DataType.BYTE) {
            return unsigned ? ProductData.TYPE_UINT8 : ProductData.TYPE_INT8;
        } else if(dataType == DataType.UBYTE) {
            return ProductData.TYPE_UINT8;
        } else if(dataType == DataType.USHORT) {
            return ProductData.TYPE_UINT16;
        } else if (dataType == DataType.SHORT) {
            return unsigned ? ProductData.TYPE_UINT16 : ProductData.TYPE_INT16;
        } else if(dataType == DataType.UINT) {
            return ProductData.TYPE_UINT32;
        } else if (dataType == DataType.INT) {
            return unsigned ? ProductData.TYPE_UINT32 : ProductData.TYPE_INT32;
        } else if(dataType == DataType.LONG) {
            return ProductData.TYPE_INT64;
        } else if (dataType == DataType.FLOAT) {
            return ProductData.TYPE_FLOAT32;
        } else if (dataType == DataType.DOUBLE) {
            return ProductData.TYPE_FLOAT64;
        } else if (!rasterDataOnly) {
            if (dataType == DataType.CHAR || dataType == DataType.STRING) {
                return ProductData.TYPE_ASCII;
            }
        } else if (dataType == DataType.CHAR) {
            return unsigned ? ProductData.TYPE_UINT8 : ProductData.TYPE_INT8;
        }
        return -1;
    }

    public static void addGroups(final MetadataElement parentElem, final Group parentGroup) {
        final List<Group> groupList = parentGroup.getGroups();
        for (Group grp : groupList) {
            final MetadataElement newElem = new MetadataElement(grp.getShortName());
            parentElem.addElement(newElem);
            // recurse
            addGroups(newElem, grp);
        }

        addAttributes(parentElem, parentGroup);
    }

    public static void createMetadataElement(final MetadataElement parentElem, Variable variable, int maxNumValuesRead) {
        final MetadataElement element = MetadataUtils.readAttributeList(variable.attributes(), variable.getFullName());
        splitElement(parentElem, element);

        if (variable.getRank() == 1) {
            final MetadataElement valuesElem = new MetadataElement("Values");
            element.addElement(valuesElem);
            if (variable.getDataType() == DataType.STRUCTURE) {
                final Structure structure = (Structure) variable;
                final List<Variable> structVariables = structure.getVariables();
                for (Variable structVariable : structVariables) {
                    final String name = structVariable.getShortName();
                    final MetadataElement structElem = new MetadataElement(name);
                    valuesElem.addElement(structElem);
                    MetadataUtils.addAttribute(structVariable, structElem, maxNumValuesRead);
                }
            } else {
                MetadataUtils.addAttribute(variable, valuesElem, maxNumValuesRead);
            }
        }
    }

    private static void splitElement(final MetadataElement rootElem, final MetadataElement element) {
        if(element.getName().contains("/")) {
            String[] tokens = StringUtils.split(element.getName(), new char[] {'/'}, false);
            MetadataElement parentElem = rootElem;
            for(int i=0; i< tokens.length; i++) {
                String token = tokens[i];
                if(i == tokens.length-1) {
                    element.setName(token);
                    parentElem.addElement(element);
                    break;
                }
                if(parentElem.containsElement(token)) {
                    parentElem = parentElem.getElement(token);
                } else {
                    MetadataElement newElem = new MetadataElement(token);
                    parentElem.addElement(newElem);
                    parentElem = newElem;
                }
            }
        } else {
            rootElem.addElement(element);
        }
    }

    public static MetadataElement addAttributes(final MetadataElement parentElem, final String elemName,
                                                final List<Attribute> attribList) {
        final MetadataElement globalElem = new MetadataElement(elemName);
        parentElem.addElement(globalElem);
        for (Attribute at : attribList) {
            createMetadataAttributes(globalElem, at, at.getName());
        }
        return globalElem;
    }

    private static void addAttributes(final MetadataElement parentElem, final Group parentGroup) {
        final List<Attribute> attribList = parentGroup.getAttributes();
        for (Attribute at : attribList) {
            createMetadataAttributes(parentElem, at, at.getName());
        }
    }

    public static void addVariableMetadata(final MetadataElement root, final Variable variable, final int maxNumValuesRead) {
        String fullName = variable.getFullName();
        if (fullName == null || fullName.isEmpty()) {
            SystemUtils.LOG.warning("Skipping variable with null or empty name.");
            return;
        }

        // Tokenize the name by '/'
        // Using standard split. Consider StringUtils.split(fullName, '/') for potentially cleaner handling of edge cases.
        String[] pathParts = fullName.split("/");

        // Basic validation of path parts
        if (pathParts.length == 0 || (pathParts.length == 1 && pathParts[0].isEmpty())) {
            // Handles cases like name="/" or name=""
            SystemUtils.LOG.warning("Skipping variable with effectively empty path: " + fullName);
            return;
        }

        // Find or create the hierarchical structure up to the parent level
        MetadataElement parentElement = findOrCreateParentPathElement(root, pathParts);

        // Get the final component of the path (the leaf name)
        String leafName = pathParts[pathParts.length - 1];

        // Handle cases like "a/b/" which result in an empty leafName after split
        if (leafName.isEmpty()) {
            if (pathParts.length > 1) {
                // Option: Use the previous part as the name? Or skip? Let's skip for clarity.
                SystemUtils.LOG.warning("Variable name '" + fullName + "' ends with '/' or has empty component. Skipping leaf creation.");
                // If needed, uncomment below to use previous part:
                // leafName = pathParts[pathParts.length - 2];
                // SystemUtils.LOG.warning("Variable name '" + fullName + "' ends with '/'. Using '" + leafName + "' as leaf name.");
                return; // Skip if leaf name is empty
            } else {
                // Name was just "/" or similar resulting in single empty part after handling above zero check
                SystemUtils.LOG.warning("Variable name '" + fullName + "' results in an empty leaf name. Skipping.");
                return;
            }
        }

        // --- Adapt the original logic to use parentElement and leafName ---

        if (variable.getRank() == 1 && variable.getDataType() == DataType.STRUCTURE) {
            // --- Structure Variable ---
            final Structure structure = (Structure) variable;

            // Check if an element with leafName already exists under the parent
            MetadataElement structureElement = parentElement.getElement(leafName);
            boolean elementExisted = structureElement != null;

            if (!elementExisted) {
                // Create the element for the structure itself using MetadataUtils helper
                // This helper likely populates attributes listed in the variable's attribute list
                structureElement = MetadataUtils.readAttributeList(variable.getAttributes(), leafName);
                parentElement.addElement(structureElement); // Add to the correct parent
            } else {
                // Element already existed (was a path node). Reuse it.
                // Optionally merge attributes if MetadataUtils.readAttributeList adds essential ones
                // For now, just log and reuse.
                // SystemUtils.LOG.fine("Reusing existing element '" + leafName + "' for structure variable.");
            }

            // Add elements for each member variable *inside* the structure element
            final List<Variable> structVariables = structure.getVariables();
            for (Variable structVariable : structVariables) {
                final String memberName = structVariable.getShortName();
                if (memberName == null || memberName.isEmpty()) continue;

                // Check if member element already exists within the structure element
                MetadataElement memberElem = structureElement.getElement(memberName);
                if (memberElem == null) {
                    memberElem = new MetadataElement(memberName);
                    structureElement.addElement(memberElem); // Add member to structure's element
                }
                // Add attributes/data specific to the member variable to the member element
                // Uses MetadataUtils.addAttribute which might be different from this class's addAttribute
                // Ensure MetadataUtils.addAttribute exists and works as expected for structure members.
                try {
                    MetadataUtils.addAttribute(structVariable, memberElem, maxNumValuesRead);
                } catch (Exception e) {
                    // Catch potential issues if MetadataUtils.addAttribute isn't suitable here
                    SystemUtils.LOG.severe("Failed to add attribute for structure member '" + memberName +
                            "' using MetadataUtils.addAttribute " + e.getMessage());
                }
            }

        } else {
            // --- Non-Structure Variable (or Rank != 1 treated similarly) ---
            long variableSize = variable.getSize();
            // Use the class's internal getProductDataType method
            final int productDataType = getProductDataType(variable);

            // Check if an element for this variable already exists under the parent
            MetadataElement leafElement = parentElement.getElement(leafName);
            boolean elementExisted = leafElement != null;

            if (productDataType != ProductData.TYPE_UNDEFINED && (variableSize == 1 || productDataType == ProductData.TYPE_ASCII)) {
                // Simple type (single value or ASCII):
                // Create a basic element if it doesn't exist, then add the variable's data as an attribute.
                if (!elementExisted) {
                    leafElement = new MetadataElement(leafName);
                    // Add attributes listed in the variable itself to this new element *before* adding data?
                    // This wasn't in the original logic for simple types, but might be desirable.
                    for (Attribute att : variable.getAttributes()) {
                        createMetadataAttributes(leafElement, att, att.getShortName());
                    }
                    parentElement.addElement(leafElement);
                }
                // Call this class's addAttribute to add the variable's data to the leafElement
                // This method names the attribute based on shortName for simple types.
                addAttribute(variable, leafElement, maxNumValuesRead);

            } else if (productDataType != ProductData.TYPE_UNDEFINED) {
                // Complex type (arrays other than single value/ASCII):
                // Create element using MetadataUtils.readAttributeList if it doesn't exist, then add data attribute.
                if (!elementExisted) {
                    // Create element and add attributes listed in the variable's attributes via helper
                    leafElement = MetadataUtils.readAttributeList(variable.getAttributes(), leafName);
                    parentElement.addElement(leafElement);
                } else {
                    // Element already existed. Reuse it.
                    // Optionally merge attributes from variable.getAttributes() if MetadataUtils.readAttributeList
                    // adds essential ones that might be missing.
                    // SystemUtils.LOG.fine("Reusing existing element '" + leafName + "' for complex variable.");
                    // Example merge (if needed and createMetadataAttributes handles duplicates):
                    // for (Attribute att : variable.getAttributes()) {
                    //     createMetadataAttributes(leafElement, att, att.getShortName());
                    // }
                }

                // Call this class's addAttribute to add the variable's data (usually named "data")
                // as a MetadataAttribute to the leafElement.
                addAttribute(variable, leafElement, maxNumValuesRead);

            } else {
                // Product data type is undefined/unsupported
                SystemUtils.LOG.warning("Skipping variable '" + fullName + "' due to unsupported data type: " + variable.getDataType());
            }
        }
    }

    /**
     * Helper method to find or create the parent MetadataElement based on path components.
     * For a path "a/b/c", it ensures "a" and "b" exist hierarchically under root,
     * and returns the MetadataElement corresponding to "b".
     * Assumes MetadataElement has getElement(name) returning null if not found,
     * and addElement(MetadataElement).
     *
     * @param root      The starting root MetadataElement.
     * @param pathParts The array of names split by '/'.
     * @return The parent MetadataElement for the leaf node.
     */
    private static MetadataElement findOrCreateParentPathElement(final MetadataElement root, final String[] pathParts) {
        MetadataElement currentParent = root;
        // Iterate up to the second-to-last part (the directories)
        for (int i = 0; i < pathParts.length - 1; i++) {
            String partName = pathParts[i];
            if (partName == null || partName.isEmpty()) {
                // Skip empty parts (e.g., from "//" or leading "/")
                continue;
            }

            // Check if the child element exists
            MetadataElement child = currentParent.getElement(partName);
            if (child == null) {
                // Element doesn't exist, create it
                child = new MetadataElement(partName);
                currentParent.addElement(child);
            }
            // Move down to the child for the next iteration
            currentParent = child;
        }
        return currentParent; // This is the element where the leaf node should be added
    }

    private static int getProductDataType(Variable variable) {
        final DataType ncDataType = variable.getDataType();
        final boolean unsigned = variable.getDataType().isUnsigned();
        final boolean rasterDataOnly = false;
        return DataTypeUtils.getEquivalentProductDataType(ncDataType, unsigned, rasterDataOnly);
    }

    public static void addAttribute(Variable variable, MetadataElement valuesElem, int maxNumValuesRead) {
        final int productDataType = getProductDataType(variable);
        if (productDataType == -1) {
            return;
        }
        final Array values;
        try {
            long variableSize = variable.getSize();
            if (variableSize >= maxNumValuesRead && maxNumValuesRead >= 0) {
                values = variable.read(new int[]{0}, new int[]{maxNumValuesRead});
                valuesElem.setDescription("Showing " + maxNumValuesRead + " of " + variableSize + " values.");
            } else {
                values = variable.read();
            }
            String name = "data";
            if(variableSize == 1 || productDataType == ProductData.TYPE_ASCII) {
                name = variable.getShortName();
            }
            final ProductData pd = ReaderUtils.createProductData(productDataType, values);
            final MetadataAttribute attribute = new MetadataAttribute(name, pd, true);
            valuesElem.addAttribute(attribute);
        } catch (IOException | InvalidRangeException e) {
            Debug.trace(e);
        }
    }

    public static void createMetadataAttributes(final MetadataElement parentElem, final Attribute attribute,
                                                 final String name) {
        // todo - note that we still do not support NetCDF data type 'char' here!

        final int i = name.indexOf('/');
        if (i > 0) {
            final String elemName = name.substring(0, i);
            final String attName = name.substring(i + 1, name.length());
            MetadataElement newElem = parentElem.getElement(elemName);
            if (newElem == null) {
                newElem = new MetadataElement(elemName);
                parentElem.addElement(newElem);
            }
            createMetadataAttributes(newElem, attribute, attName);
        } else {
            final int productDataType = getProductDataType(attribute.getDataType(), false, false);
            if (productDataType != -1) {
                ProductData productData;
                if (attribute.isString()) {
                    String strValue = attribute.getStringValue();
                    if (strValue.startsWith(NetcdfConstants.UTC_TYPE)) {
                        strValue = strValue.substring(NetcdfConstants.UTC_TYPE.length(), strValue.length());
                        productData = AbstractMetadata.parseUTC(strValue);
                    } else {
                        productData = ProductData.createInstance(strValue);
                    }
                } else if (attribute.isArray()) {
                    productData = ProductData.createInstance(productDataType, attribute.getLength());
                    long size = attribute.getValues().getSize();
                    if(size > 0) {
                        productData.setElems(attribute.getValues().getStorage());
                    }
                } else {
                    productData = ProductData.createInstance(productDataType, 1);
                    long size = attribute.getValues().getSize();
                    if(size > 0) {
                        productData.setElems(attribute.getValues().getStorage());
                    }
                }
                final MetadataAttribute metadataAttribute = new MetadataAttribute(name, productData, true);
                parentElem.addAttribute(metadataAttribute);
            }
        }
    }

    public static String getProductType(final NcAttributeMap attMap, final String defaultType) {
        String productType = attMap.getStringValue("Product Type");
        if (productType == null) {
            productType = attMap.getStringValue("Product_Type");
            if (productType == null) {
                productType = attMap.getStringValue("Conventions");
                if (productType == null) {
                    productType = defaultType;
                }
            }
        }
        return productType;
    }

    public static String getProductDescription(final NcAttributeMap attMap) {
        String description = attMap.getStringValue("description");
        if (description == null) {
            description = attMap.getStringValue("title");
            if (description == null) {
                description = attMap.getStringValue("comment");
            }
        }
        return description;
    }

    public static ProductData.UTC getSceneRasterStartTime(NcAttributeMap globalAttributes) {
        return getSceneRasterTime(globalAttributes,
                NetcdfConstants.START_DATE_ATT_NAME,
                NetcdfConstants.START_TIME_ATT_NAME);
    }

    public static ProductData.UTC getSceneRasterStopTime(NcAttributeMap globalAttributes) {
        return getSceneRasterTime(globalAttributes,
                NetcdfConstants.STOP_DATE_ATT_NAME,
                NetcdfConstants.STOP_TIME_ATT_NAME);
    }

    private static ProductData.UTC getSceneRasterTime(NcAttributeMap globalAttributes,
                                                      final String dateAttName,
                                                      final String timeAttName) {
        final String dateStr = globalAttributes.getStringValue(dateAttName);
        final String timeStr = globalAttributes.getStringValue(timeAttName);
        final String dateTimeStr = getDateTimeString(dateStr, timeStr);

        if (dateTimeStr != null) {
            try {
                return parseDateTime(dateTimeStr);
            } catch (ParseException e) {
                SystemUtils.LOG.warning(
                        "Failed to parse time string '" + dateTimeStr + '\'');
            }
        }

        return null;
    }

    private static String getDateTimeString(String dateStr, String timeStr) {
        if (dateStr != null && dateStr.endsWith("UTC")) {
            dateStr = dateStr.substring(0, dateStr.length() - 3).trim();
        }
        if (timeStr != null && timeStr.endsWith("UTC")) {
            timeStr = timeStr.substring(0, timeStr.length() - 3).trim();
        }
        if (dateStr != null && timeStr != null) {
            return dateStr + ' ' + timeStr;
        }
        if (dateStr != null) {
            return dateStr + (dateStr.indexOf(':') == -1 ? " 00:00:00" : "");
        }
        if (timeStr != null) {
            return timeStr + (timeStr.indexOf(':') == -1 ? " 00:00:00" : "");
        }
        return null;
    }

    private static ProductData.UTC parseDateTime(String dateTimeStr) throws ParseException {
        return ProductData.UTC.parse(dateTimeStr, ProductData.UTC.createDateFormat("yyyy-MM-dd HH:mm:ss"));
    }

    public static Variable[] getRasterVariables(Map<NcRasterDim, List<Variable>> variableLists,
                                                NcRasterDim rasterDim) {
        final List<Variable> list = variableLists.get(rasterDim);
        return list.toArray(new Variable[0]);
    }

    public static Variable[] getTiePointGridVariables(Map<NcRasterDim, List<Variable>> variableLists,
                                                      Variable[] rasterVariables) {
        final List<Variable> tpgList = new ArrayList<>();
        final Set<NcRasterDim> keySet = variableLists.keySet();
        for (NcRasterDim o : keySet) {
            final List<Variable> varList = variableLists.get(o);
            for (Variable var : varList) {
                boolean found = false;
                for (Variable raster : rasterVariables) {
                    if (var == raster) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    tpgList.add(var);
                }
            }
        }
        return tpgList.toArray(new Variable[0]);
    }

    public static NcRasterDim getBestRasterDim(Map<NcRasterDim, List<Variable>> variableListMap) {

        final NcRasterDim[] keys = variableListMap.keySet().toArray(new NcRasterDim[0]);
        if (keys.length == 0) {
            return null;
        }
        final String[] bandNames = {"amplitude", "intensity", "phase", "band", "proc_data"};

        NcRasterDim bestRasterDim = null;
        for (final NcRasterDim rasterDim : keys) {
            // CF-Convention for regular lat/lon grids
            if (rasterDim.isTypicalRasterDim()) {
                return rasterDim;
            }
            final List<Variable> varList = variableListMap.get(rasterDim);
            if (contains(varList, bandNames)) {
                return rasterDim;
            }
            for (Variable v : varList) {
                final String vUnit = v.getUnitsString();
                if (vUnit != null) {
                    for (String unit : bandNames) {
                        if (vUnit.equalsIgnoreCase(unit))
                            return rasterDim;
                    }
                }
            }

            // otherwise go by the largest size
            if (bestRasterDim == null ||
                    (bestRasterDim.getDimX().getLength() * bestRasterDim.getDimY().getLength()) <
                            (rasterDim.getDimX().getLength() * rasterDim.getDimY().getLength())) {
                bestRasterDim = rasterDim;
            }
            // Otherwise, the best is the one which holds the most variables
            //if (bestVarList == null || varList.size() > bestVarList.size()) {
            //    bestRasterDim = rasterDim;
            //    bestVarList = varList;
            //}
        }

        return bestRasterDim;
    }

    private static boolean contains(List<Variable> varList, String[] nameList) {
        for (Variable v : varList) {
            final String vName = v.getName().toLowerCase();
            for (String str : nameList) {
                if (vName.contains(str))
                    return true;
            }
        }
        return false;
    }

    public static Map<NcRasterDim, List<Variable>> getVariableListMap(final Group group) {
        final Map<NcRasterDim, List<Variable>> variableLists = new HashMap<>(31);
        collectVariableLists(group, variableLists);
        return variableLists;
    }

    private static void collectVariableLists(Group group, Map<NcRasterDim, List<Variable>> variableLists) {
        final List<Variable> variables = group.getVariables();
        for (Variable variable : variables) {
            final int rank = variable.getRank();
            if (rank >= 2 && isValidRasterDataType(variable.getDataType())) {
                Dimension dimY = variable.getDimension(0);
                Dimension dimX = variable.getDimension(1);
                if (rank >= 3 && dimY.getLength() <= 32) {
                    final Dimension dim3 = variable.getDimension(2);
                    dimY = dimX;
                    dimX = dim3;
                }
                if (dimX.getLength() > 1 && dimY.getLength() > 1) {
                    NcRasterDim rasterDim = new NcRasterDim(dimX, dimY);
                    List<Variable> list = variableLists.get(rasterDim);
                    if (list == null) {
                        list = new ArrayList<>();
                        variableLists.put(rasterDim, list);
                    }
                    list.add(variable);
                }
            }
        }
        final List<Group> subGroups = group.getGroups();
        for (Group subGroup : subGroups) {
            collectVariableLists(subGroup, variableLists);
        }
    }

    public static boolean variableIsVector(Variable variable) {

        final int[] shape = variable.getShape();
        int cnt = 0;
        for (int i : shape) {
            if (i == 1) {
                cnt++;
            }
        }

        return cnt + 1 >= shape.length;
    }

    /**
     * Return type of the {@link NetCDFUtils#createMapInfoX}()
     * method. Comprises a {@link MapInfo} and a boolean indicating that the reader
     * should flip data along the Y-axis.
     */
    public static class MapInfoX {

        final MapInfo _mapInfo;
        final boolean _yFlipped;

        public MapInfoX(MapInfo mapInfo, boolean yFlipped) {
            _mapInfo = mapInfo;
            _yFlipped = yFlipped;
        }

        public MapInfo getMapInfo() {
            return _mapInfo;
        }

        public boolean isYFlipped() {
            return _yFlipped;
        }
    }
}
