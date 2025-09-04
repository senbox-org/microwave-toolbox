/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.utilities.gpf;

import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.barithm.BandArithmetic;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.jexp.ParseException;
import org.esa.snap.core.jexp.Term;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates a new product with only selected bands
 */

@OperatorMetadata(alias = "BandSelect",
        category = "Raster",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2024 by SkyWatch Space Applications Inc.",
        description = "Creates a new product with only selected bands")
public final class BandSelectOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    @Parameter(description = "The list of imagettes or sub-images", label = "Sub-Images")
    private String[] selectedSubImages;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private String[] sourceBandNames;

    @Parameter(description = "Band name regular expression pattern", label = "Band Name Pattern")
    private String bandNamePattern;

    @Parameter(label = "Source masks", description = "The source masks for the computation.", rasterDataNodeType = Mask.class, alias = "sourceMasks")
    private String[] sourceMaskNames;

    @Parameter(label = "Include references",
            description = "Add dependent bands and masks",
            defaultValue = "true")
    private boolean includeReferences;

    private final static String SUFFIX = "_subset";

    public final static String[] SUBIMAGE_PREFIXES = new String[] {"WV1_IMG", "WV2_IMG"};

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            Band[] selectedBands = getSelectedBands();
            if(selectedBands.length == 0) {
                throw new OperatorException("No valid bands found in target product");
            }

            Dimension dimension = getTargetDimension(selectedBands);

            targetProduct = new Product(sourceProduct.getName() + SUFFIX,
                    sourceProduct.getProductType(), dimension.width, dimension.height);

            // copy bands first so band geocodings can be copied
            addSelectedBands(selectedBands);

            ProductUtils.copyProductNodes(sourceProduct, targetProduct);

            if (sourceMaskNames != null) {
                Set<String> keep = new HashSet<>(Arrays.asList(sourceMaskNames));
                ProductNodeGroup<Mask> maskGroup = targetProduct.getMaskGroup();
                String[] targetMasks = maskGroup.getNodeNames();
                for (String m : targetMasks) {
                    if (!keep.contains(m)) {
                        maskGroup.remove(maskGroup.get(m));
                    }
                }
            }

            if (includeReferences) {
                ProductUtils.copyFlagBandsWithoutMasks(sourceProduct, targetProduct, true);
                ArrayList<String> refBands = new ArrayList<>();
                ArrayList<String>  refMasks = new ArrayList<>();
                for (String name : sourceBandNames) {
                    collectNotIncludedReferences(name, refBands, refMasks);
                }
                refBands.removeAll(Arrays.asList(sourceBandNames));
                refMasks.removeAll(sourceMaskNames != null
                        ? Arrays.asList(sourceMaskNames) : Collections.emptyList());
                refBands.forEach(b -> ProductUtils.copyBand(b, sourceProduct, targetProduct, true));
                refMasks.forEach(m -> ProductUtils.copyMasks(sourceProduct, targetProduct, new String[]{m}, true));
            }

            String[] subImages = getSubImages(targetProduct.getBandNames());

            updateTiePointGrids(targetProduct, subImages);

            updateMetadata(targetProduct, subImages);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private Dimension getTargetDimension(Band[] selectedBands) {
        int width = sourceProduct.getSceneRasterWidth();
        int height = sourceProduct.getSceneRasterHeight();

        int bandwidth = selectedBands[0].getRasterWidth();
        int bandheight = selectedBands[0].getRasterHeight();
        boolean allSameDimensions = true;

        for (Band band : selectedBands) {
            if (band.getRasterWidth() != bandwidth || band.getRasterHeight() != bandheight) {
                allSameDimensions = false;
                break;
            }
        }
        if (allSameDimensions || width == 0 || height == 0) {
            return new Dimension(bandwidth, bandheight);
        }

        return new Dimension(width, height);
    }

    private Band[] getSelectedBands() throws OperatorException {

        final List<Band> selectedBands = new ArrayList<>();
        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, true);

        for (Band srcBand : sourceBands) {
            // check first if polarisation is found
            if (selectedPolarisations != null && selectedPolarisations.length > 0) {
                boolean foundPol = false;
                String pol = OperatorUtils.getPolarizationFromBandName(srcBand.getName());
                if(pol == null) {
                    continue;
                }
                for(String selPol : selectedPolarisations) {
                    if(pol.equalsIgnoreCase(selPol)) {
                        foundPol = true;
                        break;
                    }
                }
                if(!foundPol) {
                    continue;
                }
            }

            // check if sub-image is found
            if (selectedSubImages != null && selectedSubImages.length > 0) {
                boolean found = false;
                String name = srcBand.getName();
                for(String subImage : selectedSubImages) {
                    if(name.contains(subImage)) {
                        found = true;
                        break;
                    }
                }
                if(!found) {
                    continue;
                }
            }

            // check regular expression such as contain mst "^.*mst.*$"
            if (bandNamePattern != null && !bandNamePattern.isEmpty()) {
                Pattern pattern = Pattern.compile(bandNamePattern);
                Matcher matcher = pattern.matcher(srcBand.getName());
                if (!matcher.matches()) {
                    continue;
                }
            }

            selectedBands.add(srcBand);
        }

        return selectedBands.toArray(new Band[0]);
    }

    private void addSelectedBands(Band[] selectedBands) {

        for (Band srcBand : selectedBands) {
            if (srcBand instanceof VirtualBand) {
                ProductUtils.copyVirtualBand(targetProduct, (VirtualBand) srcBand, srcBand.getName());
            } else {
                ProductUtils.copyBand(srcBand.getName(), sourceProduct, targetProduct, true);
            }
        }
    }

    private static void updateTiePointGrids(final Product targetProduct, String[] subImages) {
        final TiePointGrid[] tiePointGrids = targetProduct.getTiePointGrids();
        for (TiePointGrid tiePointGrid : tiePointGrids) {
            for(String subImagePrefix : SUBIMAGE_PREFIXES) {
                String tpgName = tiePointGrid.getName();
                if (tpgName.startsWith(subImagePrefix)) {
                    if(!isSubImage(tpgName, subImages)) {
                        targetProduct.removeTiePointGrid(tiePointGrid);
                    }
                }
            }
        }
        if(subImages.length == 1) {
            String subImage = subImages[0] + '_';
            TiePointGrid[] newTiePointGrids = targetProduct.getTiePointGrids();
            for(TiePointGrid tpg : newTiePointGrids) {
                if(tpg.getName().startsWith(subImage)) {
                    tpg.setName(tpg.getName().substring(subImage.length()));
                }
            }
        }
    }

    public static String[] getSubImages(String[] bandNames) {
        final Set<String> subImages = new TreeSet<>();
        for(String name : bandNames) {
            for(String subImagePrefix : BandSelectOp.SUBIMAGE_PREFIXES) {
                if(name.contains(subImagePrefix)) {
                    int start = name.indexOf(subImagePrefix);
                    int end = name.indexOf('_', start + subImagePrefix.length());
                    String subImageName = name.substring(start, end);
                    subImages.add(subImageName);
                    break;
                }
            }
        }
        return subImages.toArray(new String[0]);
    }

    private void updateMetadata(Product targetProduct, String[] subImages) {
        MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if (absRoot != null) {
            MetadataElement[] bandMetadata = AbstractMetadata.getBandAbsMetadataList(absRoot);
            for(MetadataElement bandMeta : bandMetadata) {
                if(!isSubImage(bandMeta.getName(), subImages)) {
                    absRoot.removeElement(bandMeta);
                } else {
                    if(bandMeta.containsAttribute(AbstractMetadata.swath)) {
                        absRoot.setAttributeString(AbstractMetadata.SWATH, bandMeta.getAttributeString(AbstractMetadata.swath));
                    }
                    if(bandMeta.containsAttribute(AbstractMetadata.first_line_time)) {
                        ProductData.UTC startTime = bandMeta.getAttributeUTC(AbstractMetadata.first_line_time);
                        absRoot.setAttributeUTC(AbstractMetadata.first_line_time, startTime);
                        targetProduct.setStartTime(startTime);
                    }
                    if(bandMeta.containsAttribute(AbstractMetadata.last_line_time)) {
                        ProductData.UTC endTime = bandMeta.getAttributeUTC(AbstractMetadata.last_line_time);
                        absRoot.setAttributeUTC(AbstractMetadata.last_line_time, endTime);
                        targetProduct.setEndTime(endTime);
                    }
                    if(bandMeta.containsAttribute(AbstractMetadata.line_time_interval)) {
                        absRoot.setAttributeDouble(AbstractMetadata.line_time_interval, bandMeta.getAttributeDouble(AbstractMetadata.line_time_interval));
                    }
                    if(bandMeta.containsAttribute(AbstractMetadata.num_output_lines)) {
                        absRoot.setAttributeDouble(AbstractMetadata.num_output_lines, bandMeta.getAttributeDouble(AbstractMetadata.num_output_lines));
                    }
                    if(bandMeta.containsAttribute(AbstractMetadata.num_samples_per_line)) {
                        absRoot.setAttributeDouble(AbstractMetadata.num_samples_per_line, bandMeta.getAttributeDouble(AbstractMetadata.num_samples_per_line));
                    }
                }
            }
        }
    }

    private static boolean isSubImage(String tpgName, String[] subImages) {
        for(String subImage : subImages) {
            if(tpgName.contains(subImage)) {
                return true;
            }
        }
        return false;
    }

    private void collectNotIncludedReferences(String nodeName, ArrayList<String> referencedBandNames, ArrayList<String> referencedMaskNames) {
        RasterDataNode rasterDataNode = sourceProduct.getRasterDataNode(nodeName);
        if (rasterDataNode == null) {
            throw new OperatorException(String.format("Source product does not contain a raster named '%s'.", nodeName));
        }
        final String validPixelExpression = rasterDataNode.getValidPixelExpression();
        collectReferencedRastersInExpression(validPixelExpression, referencedBandNames, referencedMaskNames);

        if (rasterDataNode instanceof VirtualBand || rasterDataNode instanceof Mask) {
            String strExpression = getRasterDataNodeExpression (rasterDataNode);
            collectReferencedRastersInExpression(strExpression, referencedBandNames, referencedMaskNames);
        }
    }

    private String getRasterDataNodeExpression(RasterDataNode rasterDataNode){
        if (rasterDataNode == null )
            return null;
        String strExpression = null;
        if (rasterDataNode instanceof VirtualBand) {
            strExpression = ((VirtualBand) rasterDataNode).getExpression();
        }else if  (rasterDataNode instanceof Mask) {
            Mask mask = (Mask) rasterDataNode;
            if (mask.getImageType() == Mask.BandMathsType.INSTANCE) {
                strExpression = Mask.BandMathsType.getExpression(mask);
            } else if (mask.getImageType() == Mask.RangeType.INSTANCE) {
                strExpression = Mask.RangeType.getRasterName(mask);
            }
        }
        return strExpression;
    }

    private void collectReferencedRastersInExpression(String expression, ArrayList<String> referencedBandNames, ArrayList<String> referencedMaskNames) {
        if (expression == null || expression.trim().isEmpty()) {
            return;
        }
        try {
            final Term term = sourceProduct.parseExpression(expression);
            final RasterDataNode[] refRasters = BandArithmetic.getRefRasters(term);
            for (RasterDataNode refRaster : refRasters) {
                final String refNodeName = refRaster.getName();
                Band bandNode =  sourceProduct.getBand(refNodeName) ;
                if ( bandNode!= null){
                    if (!referencedBandNames.contains(refNodeName)) {
                        referencedBandNames.add(refNodeName);
                    }
                    final String bandExpression = getRasterDataNodeExpression (bandNode);
                    collectReferencedRastersInExpression(bandExpression, referencedBandNames, referencedMaskNames);
                }

                Mask maskNode =  sourceProduct.getMaskGroup().get(refNodeName) ;
                if (maskNode != null){
                    if (!referencedMaskNames.contains(refNodeName)) {
                        referencedMaskNames.add(refNodeName);
                    }
                    final String maskExpression = getRasterDataNodeExpression (maskNode);
                    collectReferencedRastersInExpression(maskExpression, referencedBandNames, referencedMaskNames);
                }
            }
        } catch (ParseException e) {
            getLogger().log(Level.WARNING, e.getMessage(), e);
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(BandSelectOp.class);
        }
    }
}
