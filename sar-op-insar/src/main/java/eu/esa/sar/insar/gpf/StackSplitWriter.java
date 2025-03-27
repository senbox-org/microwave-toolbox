/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductSubsetBuilder;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.dataio.ProductWriter;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.subset.PixelSubsetRegion;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.StackUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Split a stack product into individual products
 */
@OperatorMetadata(alias = "Stack-Split",
        description = "Writes all bands to files.",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        autoWriteDisabled = true,
        category = "Radar/Coregistration/Stack Tools")
public class StackSplitWriter extends Operator {

    @TargetProduct
    private Product targetProduct;

    @SourceProduct(alias = "source", description = "The source product to be written.")
    private Product sourceProduct;

    @Parameter(defaultValue = "target", description = "The output folder to which the data product is written.")
    private File targetFolder;

    @Parameter(defaultValue = "BEAM-DIMAP",
            description = "The name of the output file format.")
    private String formatName;

    private final Map<Band, SubsetInfo> bandMap = new HashMap<>();

    public StackSplitWriter() {
        setRequiresAllBands(true);
    }

    @Override
    public void initialize() throws OperatorException {
        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfCoregisteredStack();

            if(targetFolder == null) {
                throw new OperatorException("Please add a target folder");
            }
            if (!targetFolder.exists()) {
                if(!targetFolder.mkdirs()) {
                    throw new IOException("Failed to create directory '" + targetFolder + "'.");
                }
            }

            final int width = sourceProduct.getSceneRasterWidth();
            final int height = sourceProduct.getSceneRasterHeight();

            targetProduct = sourceProduct;
            targetProduct.setPreferredTileSize(new Dimension(width, height));

            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);
            final String mstProductName = absRoot.getAttributeString(AbstractMetadata.PRODUCT, sourceProduct.getName());
            final String[] mstNames = StackUtils.getMasterBandNames(sourceProduct);
            //System.out.println("mstProductName = " + mstProductName);
            createSubset(mstProductName, getBandNames(mstNames));

            final String[] slvProductNames = StackUtils.getSlaveProductNames(sourceProduct);
            for(String slvProductName : slvProductNames) {
                final String[] slvBandNames = StackUtils.getSlaveBandNames(sourceProduct, slvProductName);
                //System.out.println("slvProductName = " + slvProductName);
                createSubset(slvProductName, getBandNames(slvBandNames));
            }

        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    private String[] getBandNames(final String[] names) {
        final Set<String> bandNames = new HashSet<>();
        for(String name : names) {
            final String suffix = StackUtils.getBandSuffix(name);
            for(String srcBandName : sourceProduct.getBandNames()) {
                if(srcBandName.endsWith(suffix)) {
                    bandNames.add(srcBandName);
                }
            }
        }
        return bandNames.toArray(new String[0]);
    }

    private void createSubset(final String productName, final String[] bandNames) throws IOException {

        final int width = sourceProduct.getSceneRasterWidth();
        final int height = sourceProduct.getSceneRasterHeight();

        final ProductSubsetDef subsetDef = new ProductSubsetDef();
        subsetDef.addNodeNames(sourceProduct.getTiePointGridNames());
        subsetDef.addNodeNames(bandNames);
        subsetDef.setSubsetRegion(new PixelSubsetRegion(0, 0, width, height, 0));
        subsetDef.setSubSampling(1, 1);
        subsetDef.setIgnoreMetadata(true);

        SubsetInfo subsetInfo = new SubsetInfo();
        subsetInfo.subsetBuilder = new ProductSubsetBuilder();
        subsetInfo.subsetProduct = subsetInfo.subsetBuilder.readProductNodes(sourceProduct, subsetDef);
        subsetInfo.file = new File(targetFolder, productName);

        // update band name
        for(Band trgBand : subsetInfo.subsetProduct.getBands()) {
            final String newBandName = StackUtils.getBandNameWithoutDate(trgBand.getName());
            subsetInfo.newBandNamingMap.put(newBandName, trgBand.getName());
            trgBand.setName(newBandName);

            // update virtual band expressions
            for(Band vBand : subsetInfo.subsetProduct.getBands()) {
                if(vBand instanceof VirtualBand) {
                    final VirtualBand virtBand = (VirtualBand)vBand;
                    String expression = virtBand.getExpression().replaceAll(trgBand.getName(), newBandName);
                    virtBand.setExpression(expression);
                }
            }
        }

        subsetInfo.productWriter = ProductIO.getProductWriter(formatName);
        if (subsetInfo.productWriter == null) {
            throw new OperatorException("No data product writer for the '" + formatName + "' format available");
        }
        subsetInfo.productWriter.setFormatName(formatName);
        subsetInfo.productWriter.setIncrementalMode(false);
        subsetInfo.subsetProduct.setProductWriter(subsetInfo.productWriter);
        for (String bandName : bandNames) {
            Band band = targetProduct.getBand(bandName);
            if (!(band instanceof VirtualBand)) {
                bandMap.put(band, subsetInfo);
                //System.out.println("createSubset: productName = " + productName + " put band " + band.getName());
                break;
            }
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            final SubsetInfo subsetInfo = bandMap.get(targetBand);
            if(subsetInfo == null)
                return;

            subsetInfo.productWriter.writeProductNodes(subsetInfo.subsetProduct, subsetInfo.file);

            final Rectangle trgRect = subsetInfo.subsetBuilder.getSubsetDef().getRegion();
            if (!subsetInfo.written) {
                writeTile(subsetInfo, trgRect);
            }
        } catch (Exception e) {
            if (e instanceof OperatorException) {
                throw (OperatorException) e;
            } else {
                throw new OperatorException(e);
            }
        }
    }

    private synchronized void writeTile(final SubsetInfo info, final Rectangle trgRect)
            throws IOException {
        if (info.written) return;

        for(Band trgBand : info.subsetProduct.getBands()) {
            final String oldBandName = info.newBandNamingMap.get(trgBand.getName());
            final Tile sourceTile = getSourceTile(sourceProduct.getBand(oldBandName), trgRect);
            final ProductData rawSamples = sourceTile.getRawSamples();

            //final String newBandName = StackUtils.getBandNameWithoutDate(bandName);
            info.productWriter.writeBandRasterData(trgBand,
                    0, 0, trgBand.getRasterWidth(), trgBand.getRasterHeight(), rawSamples, ProgressMonitor.NULL);
        }
        info.written = true;
    }

    @Override
    public void dispose() {
        try {
            for (Band band : bandMap.keySet()) {
                SubsetInfo info = bandMap.get(band);
                info.productWriter.close();
            }
        } catch (IOException ignore) {
        }
        super.dispose();
    }

    private static class SubsetInfo {
        Product subsetProduct;
        ProductSubsetBuilder subsetBuilder;
        File file;
        ProductWriter productWriter;
        boolean written = false;
        final Map<String, String> newBandNamingMap = new HashMap<>();
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(StackSplitWriter.class);
        }
    }

}
