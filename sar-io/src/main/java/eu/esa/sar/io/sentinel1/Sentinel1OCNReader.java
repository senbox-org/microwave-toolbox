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
package eu.esa.sar.io.sentinel1;

import eu.esa.sar.io.netcdf.NetCDFUtils;
import org.esa.snap.core.dataio.geocoding.ComponentGeoCoding;
import org.esa.snap.core.dataio.geocoding.GeoCodingFactory;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.netcdf.util.MetadataUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Structure;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * NetCDF reader for Level-2 OCN products
 */
public class Sentinel1OCNReader {

    // A NetCDF file consists of
    // 1) attributes
    //      Global Attributes are added to Product as attributes in
    //          Metadata --> Original_Product_Metadata --> annotation --> <filename of MDS>.nc
    // 2) dimensions
    //      Dimensions are added to Product as attributes in
    //          Metadata --> Original_Product_Metadata --> annotation --> <filename of MDS>.nc
    // 3) variables
    //      - measurement data
    //          If rank is 1, it is added as "Values" in annotation for that variable, e.g., see oswK
    //          If rank > 1 but it is a vector of values, it is added as "values" in annotation for that variable
    //          If rank is 2, it is added as a band
    //          If rank is 3, it is added as a band. An "outer" grid of cells and then each cell is a single column of values.
    //          If rank is 4, it is added as a band. An "outer" grid of cells and then each cell is an "inner" grid of bins.
    //      - annotations
    //          scalar or 1D array with same name as variable
    //          Added to product as attributes in
    //          Metadata --> Original_Product_Metadata --> annotation --> <filename of MDS>.nc
    //
    // MDS = Measurement Data Set

    // This maps the MDS .nc file name to the NetcdfFile
    private final Map<String, NCFileData> bandNCFileMap = new HashMap<>(1);

    private static class NCFileData {
        String name;
        NetcdfFile netcdfFile;
        NCFileData(String name, NetcdfFile netcdfFile) {
            this.name = name;
            this.netcdfFile = netcdfFile;
        }
    }

    private final Sentinel1Level2Directory dataDir;
    private String mode;

    // For WV, there can be more than one MDS .nc file. See Table 4-3 in Product Spec v2/7 (S1-RS-MDA-52-7441).
    // Each MDS has the same variables, so we want unique band names for variables of same name from different .nc file.
    // Given a band name, we want to map back to the .nc file.
    private final Map<String, NetcdfFile> bandNameNCFileMap = new HashMap<>(1);

    private int sceneWidth = -1;
    private int sceneHeight = -1;

    public Sentinel1OCNReader(final Sentinel1Level2Directory dataDir) {

        this.dataDir = dataDir;
    }

    public void close() {
        for (NCFileData data : bandNCFileMap.values()) {
            try {
                data.netcdfFile.close();
            } catch (IOException e) {
                SystemUtils.LOG.severe("Sentinel1OCNReader.close: IOException when closing " + data.name);
            }
        }
    }

    public void addImageFile(final File file, final String name) throws IOException {
        // The image file here is the MDS .nc file.
        String imgNum = name.substring(name.lastIndexOf("-")+1);

        final NetcdfFile netcdfFile = NetcdfFile.open(file.getPath());
        bandNCFileMap.put(imgNum, new NCFileData(name, netcdfFile));
    }

    public int getSceneWidth() {
        return bandNCFileMap.size() == 1 ? sceneWidth : -1;
    }

    public int getSceneHeight() {
        return bandNCFileMap.size() == 1 ? sceneHeight : -1;
    }

    public void addNetCDFMetadata(final MetadataElement annotationElement) {

        List<String> keys = new ArrayList<>(bandNCFileMap.keySet());
        Collections.sort(keys);

        for (String imgNum : keys) { // for each MDS which is a .nc file

            //System.out.println("Sentinel1OCNReader.addNetCDFMetadataAndBands: file = " + file);

            final NCFileData data = bandNCFileMap.get(imgNum);
            final NetcdfFile netcdfFile = data.netcdfFile;
            final String file = data.name;

            // Add Global Attributes as Metadata
            final MetadataElement bandElem = NetCDFUtils.addAttributes(annotationElement,
                                                                       file,
                                                                       netcdfFile.getGlobalAttributes());

            // Add dimensions as Metadata
            final MetadataElement dimElem = new MetadataElement("Dimensions");
            bandElem.addElement(dimElem);
            List<Dimension> dimensionList = netcdfFile.getDimensions();
            for (Dimension d : dimensionList) {
                ProductData productData = ProductData.createInstance(ProductData.TYPE_UINT32, 1);
                productData.setElemUInt(d.getLength());
                final MetadataAttribute metadataAttribute = new MetadataAttribute(d.getFullName(), productData, true);
                dimElem.addAttribute(metadataAttribute);
                if (metadataAttribute.getName().equals("owiRaSize")) {
                    sceneWidth = metadataAttribute.getData().getElemInt(); // width = #columns
                } else if (metadataAttribute.getName().equals("owiAzSize")) {
                    sceneHeight = metadataAttribute.getData().getElemInt(); // height = #rows
                }
                //System.out.println("Sentinel1OCNReader.addNetCDFMetadata: add dimensions: " + metadataAttribute.getName()
                //    + " = " + metadataAttribute.getData().getElemInt());
            }

            final List<Variable> variableList = netcdfFile.getVariables();

            // Add attributes inside variables as Metadata
            for (Variable variable : variableList) {
                MetadataElement elem = createMetadataElement(variable, 1000);
                bandElem.addElement(elem);

                if (NetCDFUtils.variableIsVector(variable) && variable.getRank() > 1) {

                    final MetadataElement valuesElem = new MetadataElement("Values");
                    elem.addElement(valuesElem);
                    MetadataUtils.addAttribute(variable, valuesElem, 1000);
                }
            }
        }
    }

    public static MetadataElement createMetadataElement(Variable variable, int maxNumValuesRead) {
        final MetadataElement element = MetadataUtils.readAttributeList(variable.attributes(), variable.getFullName());
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
        return element;
    }

    public void addNetCDFBands(final Product product) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        mode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        List<String> keys = new ArrayList<>(bandNCFileMap.keySet());
        Collections.sort(keys);

        for (String imgNum : keys) { // for each MDS which is a .nc file

            final NCFileData data = bandNCFileMap.get(imgNum);
            final NetcdfFile netcdfFile = data.netcdfFile;
            final String file = data.name;

            // Add bands to product...

            int idx = file.indexOf("-");
            final String subswath = file.substring(idx+1, file.indexOf('-', file.indexOf('-') + 1)).toUpperCase();
            idx = file.indexOf("-ocn-");
            final String pol = file.substring(idx + 5, idx + 7).toUpperCase();

            idx = file.lastIndexOf('-');
            final String imageNum = file.substring(idx + 1, idx + 4);

            final List<Variable> variableList = netcdfFile.getVariables();
            for (Variable variable : variableList) {

                if (NetCDFUtils.variableIsVector(variable) && variable.getRank() > 1) {
                    continue;
                }

                String suffix = "_IMG" + imageNum + "_" + pol;
                if(mode.equals("WV")) {
                    suffix = "_"+ subswath + suffix;
                }
                String bandName;
                final int[] shape = variable.getShape();

                switch (variable.getRank()) {

                    case 1:
                        // The data has been added as part of annotation for the variable under "Values".
                        break;
                    case 2: {
                        bandName = createBandName(variable.getFullName(), suffix);
                        addBand(product, bandName, variable, shape[1], shape[0]);
                        bandNameNCFileMap.put(bandName, netcdfFile);

                        if (bandName.contains("owiNrcs")) {
                            product.setQuicklookBandName(bandName);
                        }
                        break;
                    }
                    case 3: {
                        // When the rank is 3, there is an "outer" grid of cells and each cell contains a vector of values.
                        // The "outer" grid is oswAzSize (rows) by oswRaSize (cols) of cells.
                        // Each cell is a vector of values.
                        // For owsSpecRes, the dimensions are oswAzSize x oswRaSize x oswAngularBinSize
                        // So it is more natural to have the band be (oswAzSize*oswAngularBinSize) rows by oswRaSize columns.
                        // All other rank 3 variables are oswAzSize x oswRaSize x oswPartitions
                        // To be consistent, the bands will be (oswAzSize*oswPartitions) rows by oswRaSize columns.

                        for(int cell = 1; cell <= shape[2]; ++cell) {
                            bandName = createBandName(variable.getFullName() +"_Swath" + cell, suffix);
                            // Tbe band will have dimensions: shape[0]*shape[2] (rows) by shape[1] (cols).
                            // So band width = shape[1] and band height = shape[0]*shape[2]
                            addBand(product, bandName, variable, shape[1], shape[0]);// * shape[2]);
                            bandNameNCFileMap.put(bandName, netcdfFile);
                        }
                        break;
                    }

                    case 4: {
                        // When the rank is 4, there is an "outer" grid of cells and each cell contains an "inner" grid of bins.
                        // The "outer" grid is oswAzSize (rows) by oswRaSize (cols) of cells.
                        // Each cell is oswAngularBinSize (rows) by oswWaveNumberBinSize (cols) of bins.
                        // shape[0] is height of "outer" grid.
                        // shape[1] is width of "outer" grid.
                        // shape[2] is height of "inner" grid.
                        // shape[3] is width of "inner" grid.

                        bandName = createBandName(variable.getFullName(), suffix);
                        // Tbe band will have dimensions: shape[0]*shape[2] (rows) by shape[1]*shape[3] (cols).
                        // So band width = shape[1]*shape[3] and band height = shape[0]*shape[2]
                        addBand(product, bandName, variable, shape[1] * shape[3], shape[0] * shape[2]);
                        bandNameNCFileMap.put(bandName, netcdfFile);
                        break;
                    }

                    case 5: {

                        bandName = createBandName(variable.getFullName(), suffix);
                        // Tbe band will have dimensions: shape[0]*shape[2] (rows) by shape[1]*shape[3] (cols).
                        // So band width = shape[1]*shape[3] and band height = shape[0]*shape[2]
                        addBand(product, bandName, variable, shape[1] * shape[3], shape[0] * shape[2]);
                        bandNameNCFileMap.put(bandName, netcdfFile);
                        break;
                    }

                    default:
                        SystemUtils.LOG.severe("SentinelOCNReader.addNetCDFMetadataAndBands: ERROR invalid variable rank "
                                + variable.getRank() + " for " + variable.getFullName());
                        break;
                }
            }
        }
    }

    private String createBandName(String name, String suffix) {
        return name + suffix;
    }

    public void addGeoCodingToBands(final Product product) throws IOException {

        final Band[] bands = product.getBands();
        for (Band band : bands) {
            GeoCoding geoCoding = findGeoCoding(bands, band);
            if(geoCoding != null) {
                band.setGeoCoding(geoCoding);
            }
        }
    }

    ComponentGeoCoding findGeoCoding(final Band[] bands, Band band) throws IOException {
        final String bandName = band.getName();
        final String prefix = bandName.substring(0, 3);
        final String imgName = bandName.substring(bandName.indexOf("_IMG"));
        final String swath = getSwath(bandName);

        Band latBand = null, lonBand = null;
        for (Band b : bands) {
            final String name = b.getName();
            if(name.contains(prefix) && name.contains(imgName)) {
                if(name.contains("Lat")) {
                    if(swath != null && name.contains("Swath") && !name.contains(swath)) {
                        continue;
                    }
                    latBand = b;
                } else if(name.contains("Lon")) {
                    if(swath != null && name.contains("Swath") && !name.contains(swath)) {
                        continue;
                    }
                    lonBand = b;
                }
            }
        }

        if (latBand != null && lonBand != null) {
            return GeoCodingFactory.createPixelGeoCoding(latBand, lonBand);
        }
        return null;
    }

    String getSwath(String name) {
        if(!name.contains("Swath"))
            return null;
        int a = name.indexOf("_Swath");
        int b = name.indexOf('_', a + 1);
        return name.substring(a, b);
    }

    int getSwathNumber(final String bandName) {
        String swath = getSwath(bandName);
        if(swath != null) {
            return Integer.parseInt(swath.substring(6)) -1;
        }
        return 0;
    }

    private void addBand(final Product product, String bandName, final Variable variable, final int width, final int height) {

        final Band band = NetCDFUtils.createBand(variable, width, height);
        band.setName(bandName);
        band.setNoDataValueUsed(true);
        band.setNoDataValue(-999);
        product.addBand(band);
    }

    public void readData(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                         int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                         int destOffsetY, int destWidth, int destHeight, ProductData destBuffer) {

        /*
        System.out.println("Sentinel1OCNReader.readData: sourceOffsetX = " + sourceOffsetX +
                " sourceOffsetY = " + sourceOffsetY +
                " sourceWidth = " + sourceWidth +
                " sourceHeight = " +  sourceHeight +
                " sourceStepX = " + sourceStepX +
                " sourceStepY = " + sourceStepY +
                " destOffsetX = " + destOffsetX +
                " destOffsetY = " + destOffsetY +
                " destWidth = " + destWidth +
                " destHeight = " + destHeight +
                " destBuffer.getNumElems() = " + destBuffer.getNumElems());
        */

        // Can source and destination have different height and width? TODO
        if (sourceWidth != destWidth || sourceHeight != destHeight) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readData: ERROR sourceWidth = " + sourceWidth + " sourceHeight = " + sourceHeight);
            return;
        }

        // It looks like this will be called once for the entire band at the beginning to fill up the display
        // and then when we slide the cursor over each pixel, this is called again just for that pixel.
        // In the former case, we see this print statement...
        //  Sentinel1OCNReader.readData: sourceOffsetX = 0 sourceOffsetY = 0 sourceWidth = 80 sourceHeight = 10 sourceStepX = 1 sourceStepY = 1 destOffsetX = 0 destOffsetY = 0 destWidth = 80 destHeight = 10 destBuffer.getNumElems() = 800
        // In the latter case, we see this print statement...
        //  Sentinel1OCNReader.readData: sourceOffsetX = 32 sourceOffsetY = 4 sourceWidth = 1 sourceHeight = 1 sourceStepX = 1 sourceStepY = 1 destOffsetX = 32 destOffsetY = 4 destWidth = 1 destHeight = 1 destBuffer.getNumElems() = 1
        // So it looks like we can ignore destOffsetX and destOffsetY.

        final String bandName = destBand.getName();
        final String varFullName = bandName.substring(0, bandName.indexOf('_'));

        //System.out.println("Sentinel1OCNReader.readData: bandName = " + bandName + " varFullName = " + varFullName);

        final NetcdfFile netcdfFile = bandNameNCFileMap.get(bandName);
        final Variable var = netcdfFile.findVariable(varFullName);

        switch (var.getRank()) {
            case 2:
                readDataForRank2Variable(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                        sourceStepX, sourceStepY, var, destWidth, destHeight, destBuffer);
                break;
            case 3:
                readDataForRank3Variable(bandName, sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                        sourceStepX, sourceStepY, var, destWidth, destHeight, destBuffer);
                break;
            case 4:
                readDataForRank4Variable(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                        sourceStepX, sourceStepY, var, destWidth, destHeight, destBuffer);
                break;
            case 5:
                readDataForRank5Variable(sourceOffsetX, sourceOffsetY, sourceWidth, sourceHeight,
                        sourceStepX, sourceStepY, var, destWidth, destHeight, destBuffer);
                break;
            default:
                SystemUtils.LOG.severe("SentinelOCNReader.readData: ERROR invalid variable rank "
                        + var.getRank() + " for " + var.getFullName());
                break;
        }
    }

    public synchronized void readDataForRank2Variable(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                         int sourceStepX, int sourceStepY, Variable var,
                                         int destWidth, int destHeight, ProductData destBuffer) {

        final int[] origin = {sourceOffsetY, sourceOffsetX};
        final int[] shape = {sourceHeight, sourceWidth};

        try {

            final Array srcArray = var.read(origin, shape);
            for (int i = 0; i < destHeight; i++) {
                final int srcStride = i * sourceWidth;
                final int dstStride = i * destWidth;
                for (int j = 0; j < destWidth; j++) {
                    destBuffer.setElemFloatAt(dstStride + j, srcArray.getFloat(srcStride + j));
                }
            }

        } catch (IOException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank2Variable: IOException when reading variable " + var.getFullName());

        } catch (InvalidRangeException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank2Variable: InvalidRangeException when reading variable " + var.getFullName());
        }
    }

    private synchronized void readDataForRank3Variable(final String bandName,
                                                       int sourceOffsetX, int sourceOffsetY,
                                                       int sourceWidth, int sourceHeight,
                                                      int sourceStepX, int sourceStepY, Variable var,
                                                      int destWidth, int destHeight, ProductData destBuffer) {

        final int swath = getSwathNumber(bandName);

        final int[] shape0 = var.getShape();
        shape0[2] = 1;

        // shape0[0] is height of "outer" grid.
        // shape0[1] is width of "outer" grid.
        // shape0[2] is height of the column in each cell in the "outer" grid.

        final int[] origin = {sourceOffsetY, sourceOffsetX, swath};

        final int outerYEnd = (sourceOffsetY + (sourceHeight - 1) * sourceStepY);
        final int outerXEnd = (sourceOffsetX + (sourceWidth - 1) * sourceStepX);

        final int[] shape = {outerYEnd - origin[0] + 1, outerXEnd - origin[1] + 1, 1};

        try {
            final Array srcArray = var.read(origin, shape);

            final int length = destBuffer.getNumElems();
            for(int i=0; i< length; ++i) {
                destBuffer.setElemFloatAt(i, srcArray.getFloat(i));
            }

        } catch (IOException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank3Variable: IOException when reading variable " + var.getFullName());

        } catch (InvalidRangeException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank3Variable: InvalidRangeException when reading variable " + var.getFullName());
        }
    }

    private synchronized void readDataForRank4Variable(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                          int sourceStepX, int sourceStepY, Variable var,
                                          int destWidth, int destHeight, ProductData destBuffer) {

        final int[] shape0 = var.getShape();

        // shape0[0] is height of "outer" grid.
        // shape0[1] is width of "outer" grid.
        // shape0[2] is height of "inner" grid.
        // shape0[3] is width of "inner" grid.

        final int[] origin = {sourceOffsetY / shape0[2], sourceOffsetX / shape0[3], 0, 0};

        //System.out.println("sourceOffsetY = " + sourceOffsetY + " shape0[2] = " + shape0[2] + " sourceOffsetX = " + sourceOffsetX + " shape0[3] = " + shape0[3]);
        //System.out.println("origin " + origin[0] + " " + origin[1]);

        final int outerYEnd = (sourceOffsetY + (sourceHeight - 1) * sourceStepY) / shape0[2];
        final int outerXEnd = (sourceOffsetX + (sourceWidth - 1) * sourceStepX) / shape0[3];

        //System.out.println("sourceHeight = " + sourceHeight + " sourceStepY = " + sourceStepY + " outerYEnd = " + outerYEnd);
        //System.out.println("sourceWidth = " + sourceWidth + " sourceStepX = " + sourceStepX + " outerXEnd = " + outerXEnd);

        final int[] shape = {outerYEnd - origin[0] + 1, outerXEnd - origin[1] + 1, shape0[2], shape0[3]};

        try {

            final Array srcArray = var.read(origin, shape);
            final int[] idx = new int[4];

            for (int i = 0; i < destHeight; i++) {

                // srcY is wrt to what is read in srcArray
                final int srcY = (sourceOffsetY - shape0[2] * origin[0]) + i * sourceStepY;
                idx[0] = srcY / shape[2];

                for (int j = 0; j < destWidth; j++) {

                    // srcX is wrt to what is read in srcArray
                    final int srcX = (sourceOffsetX - shape0[3] * origin[1]) + j * sourceStepX;

                    idx[1] = srcX / shape[3];
                    idx[2] = srcY - idx[0] * shape[2];
                    idx[3] = srcX - idx[1] * shape[3];

                    final int srcIdx = (idx[0] * shape[1] * shape[2] * shape[3]) +
                            (idx[1] * shape[2] * shape[3]) +
                            (idx[2] * shape[3]) +
                            idx[3];

                    final int destIdx = i * destWidth + j;

                    destBuffer.setElemFloatAt(destIdx, srcArray.getFloat(srcIdx));
                }
            }

        } catch (IOException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank4Variable: IOException when reading variable " + var.getFullName());

        } catch (InvalidRangeException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank4Variable: InvalidRangeException when reading variable " + var.getFullName());
        }
    }

    private synchronized void readDataForRank5Variable(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                                       int sourceStepX, int sourceStepY, Variable var,
                                                       int destWidth, int destHeight, ProductData destBuffer) {

        final int[] shape0 = var.getShape();

        // shape0[0] is height of "outer" grid.
        // shape0[1] is width of "outer" grid.
        // shape0[2] is height of "inner" grid.
        // shape0[3] is width of "inner" grid.

        final int[] origin = {sourceOffsetY / shape0[2], sourceOffsetX / shape0[3], 0, 0, 0};

        //System.out.println("sourceOffsetY = " + sourceOffsetY + " shape0[2] = " + shape0[2] + " sourceOffsetX = " + sourceOffsetX + " shape0[3] = " + shape0[3]);
        //System.out.println("origin " + origin[0] + " " + origin[1]);

        final int outerYEnd = (sourceOffsetY + (sourceHeight - 1) * sourceStepY) / shape0[2];
        final int outerXEnd = (sourceOffsetX + (sourceWidth - 1) * sourceStepX) / shape0[3];

        //System.out.println("sourceHeight = " + sourceHeight + " sourceStepY = " + sourceStepY + " outerYEnd = " + outerYEnd);
        //System.out.println("sourceWidth = " + sourceWidth + " sourceStepX = " + sourceStepX + " outerXEnd = " + outerXEnd);

        final int[] shape = {outerYEnd - origin[0] + 1, outerXEnd - origin[1] + 1, shape0[2], shape0[3], shape0[4]};

        try {

            final Array srcArray = var.read(origin, shape);
            final int[] idx = new int[4];

            for (int i = 0; i < destHeight; i++) {

                // srcY is wrt to what is read in srcArray
                final int srcY = (sourceOffsetY - shape0[2] * origin[0]) + i * sourceStepY;
                idx[0] = srcY / shape[2];

                for (int j = 0; j < destWidth; j++) {

                    // srcX is wrt to what is read in srcArray
                    final int srcX = (sourceOffsetX - shape0[3] * origin[1]) + j * sourceStepX;

                    idx[1] = srcX / shape[3];
                    idx[2] = srcY - idx[0] * shape[2];
                    idx[3] = srcX - idx[1] * shape[3];

                    final int srcIdx = (idx[0] * shape[1] * shape[2] * shape[3]) +
                            (idx[1] * shape[2] * shape[3]) +
                            (idx[2] * shape[3]) +
                            idx[3];

                    final int destIdx = i * destWidth + j;

                    destBuffer.setElemFloatAt(destIdx, srcArray.getFloat(srcIdx));
                }
            }

        } catch (InvalidRangeException e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank5Variable: InvalidRangeException when reading variable "
                    + var.getFullName() + " " + e.getMessage());
        } catch (Exception e) {

            SystemUtils.LOG.severe("Sentinel1OCNReader.readDataForRank5Variable: IOException when reading variable "
                    + var.getFullName()  + " " + e.getMessage());
        }
    }

    private void dumpVariableValues(final Variable variable, final String bandName) {

        try {
            Array arr = variable.read();
            for (int i = 0; i < arr.getSize(); i++) {
                System.out.println("Sentinel1OCNReader: " + variable.getFullName() + "[" + i + "] = " + arr.getFloat(i));
            }

        } catch (IOException e) {

            System.out.println("Sentinel1OCNReader: failed to read variable " + variable.getFullName() + " for band " + bandName);
        }
    }
}
