/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.io.nisar.subreaders;

import eu.esa.sar.io.nisar.util.NisarXConstants;
import hdf.object.FileFormat;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import ucar.nc2.Group;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NisarGCOVProductReader extends NisarSubReader {

    private FileFormat h5File;

    public NisarGCOVProductReader() {
        productType = "GCOV";
    }

    protected void open(final File inputFile) throws IOException {
        try {
            final FileFormat h5FileFormat = FileFormat.getFileFormat(FileFormat.FILE_TYPE_HDF5);
            this.h5File = h5FileFormat.createInstance(inputFile.getAbsolutePath(), FileFormat.READ);
            this.h5File.open();

        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (this.h5File != null) {
            try {
                this.h5File.close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    /**
     * Provides an implementation of the <code>readProductNodes</code> interface method. Clients implementing this
     * method can be sure that the input object and eventually the subset information has already been set.
     * <p/>
     * <p>This method is called as a last step in the <code>readProductNodes(input, subsetInfo)</code> method.
     */
    @Override
    public Product readProduct(final ProductReader reader, final File inputFile) throws IOException {
        open(inputFile);

        try {
            final hdf.object.Group rootNode = (hdf.object.Group) h5File.getRootObject();

            final Group groupLSAR = getLSARGroup();
            final Group groupID = getIndenificationGroup(groupLSAR);
            final Group groupFrequencyA = getFrequencyAGroup(groupLSAR);

            Variable[] rasterVariables = getRasterVariables(groupFrequencyA);

            final int rasterHeight = rasterVariables[0].getDimension(0).getLength();
            final int rasterWidth = rasterVariables[0].getDimension(1).getLength();
            productType = getProductType(groupID);

            product = new Product(inputFile.getName(),
                    productType,
                    rasterWidth, rasterHeight,
                    reader);
            product.setFileLocation(inputFile);

            addMetadataToProduct();
            addBandsToProduct();
            addTiePointGridsToProduct();
            addDopplerMetadata();

            return product;
        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());
            return null;
        }
    }

    @Override
    protected Variable[] getRasterVariables(final Group groupFrequencyA) {
        List<Variable> rasterVariables = new ArrayList<>();

        final Variable hh = groupFrequencyA.findVariable("HH");
        final Variable hv = groupFrequencyA.findVariable("HV");
        final Variable vh = groupFrequencyA.findVariable("VH");
        final Variable vv = groupFrequencyA.findVariable("VV");

        if (hh != null) {
            rasterVariables.add(hh);
        } else if (hv != null) {
            rasterVariables.add(hv);
        } else if (vh != null) {
            rasterVariables.add(vh);
        } else if (vv != null) {
            rasterVariables.add(vv);
        }

        return rasterVariables.toArray(new Variable[0]);
    }

    @Override
    protected void addBandsToProduct() {

        int cnt = 1;
        Map<String, Variable> variables = new HashMap<>();
        final Group groupScience = this.netcdfFile.getRootGroup().findGroup("science");
        final Group groupLSAR = groupScience.findGroup("LSAR");
        final Group groupRSLC = groupLSAR.findGroup("RSLC");
        final Group groupSwaths = groupRSLC.findGroup("swaths");
        final Group groupFrequencyA = groupSwaths.findGroup("frequencyA");

        final Variable hh = groupFrequencyA.findVariable("HH");
        final Variable hv = groupFrequencyA.findVariable("HV");
        final Variable vh = groupFrequencyA.findVariable("VH");
        final Variable vv = groupFrequencyA.findVariable("VV");

        String polStr = "";
        int width = 0, height = 0;
        if (hh != null) {
            variables.put(NisarXConstants.I_Q, hh);
            polStr = "HH";
            height = hh.getDimension(0).getLength();
            width = hh.getDimension(1).getLength();
        } else if (hv != null) {
            variables.put(NisarXConstants.I_Q, hv);
            polStr = "HV";
            height = hv.getDimension(0).getLength();
            width = hv.getDimension(1).getLength();
        } else if (vh != null) {
            variables.put(NisarXConstants.I_Q, vh);
            polStr = "VH";
            height = vh.getDimension(0).getLength();
            width = vh.getDimension(1).getLength();
        } else if (vv != null) {
            variables.put(NisarXConstants.I_Q, vv);
            polStr = "VV";
            height = vv.getDimension(0).getLength();
            width = vv.getDimension(1).getLength();
        }

//        final NcAttributeMap attMap = NcAttributeMap.create(variables.get(NisarXConstants.I_Q));

        try {
            final Band bandIQ = new Band("i_q" + polStr, ProductData.TYPE_FLOAT32, width, height);
            bandIQ.setDescription("I-Q band of the focused SLC image (HH)");
            bandIQ.setUnit("CFloat16");
            bandIQ.setNoDataValue(0);
            bandIQ.setNoDataValueUsed(true);
            product.addBand(bandIQ);
            bandMap.put(bandIQ, variables.get(NisarXConstants.I_Q));

//            final Band bandI = new Band("i_" + polStr, ProductData.TYPE_FLOAT32, width, height);
//            bandI.setDescription("I band of the focused SLC image (HH)");
//            bandI.setUnit(Unit.REAL);
//            bandI.setNoDataValue(0);
//            bandI.setNoDataValueUsed(true);
//            product.addBand(bandI);
//            bandMap.put(bandI, variables.get(NisarXConstants.I_Q));
//
//            final Band bandQ = new Band("q_" + polStr, ProductData.TYPE_FLOAT32, width, height);
//            bandI.setDescription("Q band of the focused SLC image (HH)");
//            bandQ.setUnit(Unit.IMAGINARY);
//            bandQ.setNoDataValue(0);
//            bandQ.setNoDataValueUsed(true);
//            product.addBand(bandQ);
//            bandMap.put(bandQ, variables.get(NisarXConstants.I_Q));
//
//            ReaderUtils.createVirtualIntensityBand(product, bandI, bandQ, polStr);

        } catch (Exception e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }
}