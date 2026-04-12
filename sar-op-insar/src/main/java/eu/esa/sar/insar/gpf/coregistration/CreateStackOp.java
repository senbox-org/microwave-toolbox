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
package eu.esa.sar.insar.gpf.coregistration;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.CRSGeoCodingHandler;
import eu.esa.sar.commons.Resolution;
import eu.esa.sar.commons.SARGeocoding;
import org.esa.snap.core.subset.PixelSubsetRegion;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import eu.esa.sar.insar.gpf.InSARStackOverview;
import org.esa.snap.core.dataio.ProductSubsetBuilder;
import org.esa.snap.core.dataio.ProductSubsetDef;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.GeoCoding;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductNodeGroup;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.FeatureUtils;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.ProductInformation;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;

import java.awt.Rectangle;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CreateStack operator.
 */
@OperatorMetadata(alias = "CreateStack",
        category = "Radar/Coregistration/Stack Tools",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Collocates two or more products based on their geo-codings.")
public class CreateStackOp extends Operator {

    @SourceProducts
    private Product[] sourceProduct;

    @Parameter(description = "The list of source bands.", alias = "masterBands",
            rasterDataNodeType = Band.class, label = "Reference Band")
    private String[] masterBandNames = null;

    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = Band.class, label = "Secondary Bands")
    private String[] slaveBandNames = null;

    private Product referenceProduct = null;
    private final Band[] referenceBands = new Band[2];

    @TargetProduct(description = "The target product which will use the reference's grid.")
    private Product targetProduct = null;

    @Parameter(defaultValue = "NONE",
            description = "The method to be used when resampling the secondary grid onto the reference grid.",
            label = "Resampling Type")
    private String resamplingType = "NONE";
    private Resampling selectedResampling = null;

    @Parameter(valueSet = {MASTER_EXTENT, MIN_EXTENT, MAX_EXTENT},
            defaultValue = MASTER_EXTENT,
            description = "The output image extents.",
            label = "Output Extents")
    private String extent = MASTER_EXTENT;

    public final static String MASTER_EXTENT = "Master";
    public final static String MIN_EXTENT = "Minimum";
    public final static String MAX_EXTENT = "Maximum";

    public final static String INITIAL_OFFSET_GEOLOCATION = "Product Geolocation";
    public final static String INITIAL_OFFSET_ORBIT = "Orbit";

    @Parameter(valueSet = {INITIAL_OFFSET_ORBIT, INITIAL_OFFSET_GEOLOCATION},
            defaultValue = INITIAL_OFFSET_ORBIT,
            description = "Method for computing initial offset between reference and secondary",
            label = "Initial Offset Method")
    private String initialOffsetMethod = INITIAL_OFFSET_ORBIT;

    private final Map<Band, Band> sourceRasterMap = new HashMap<>(10);
    private final Map<Product, int[]> secondaryOffsetMap = new HashMap<>(10);

    private boolean appendToReference = false;
    private boolean isResampling = false;

    private static final String PRODUCT_SUFFIX = "_Stack";

    @Override
    public void initialize() throws OperatorException {

        try {
            if (sourceProduct == null) {
                return;
            }

            if (sourceProduct.length < 2) {
                throw new OperatorException("Please select at least two source products");
            }

            for (final Product prod : sourceProduct) {
                final InputProductValidator validator = new InputProductValidator(prod);
                final MetadataElement prodAbsRoot = AbstractMetadata.getAbstractedMetadata(prod);
                final boolean isTerrainCorrected = prodAbsRoot != null &&
                        prodAbsRoot.getAttributeInt(AbstractMetadata.is_terrain_corrected, 0) == 1;
                if(validator.isTOPSARProduct() && !validator.isDebursted() && !isTerrainCorrected) {
                    throw new OperatorException("For S1 TOPS SLC products, TOPS Coregistration should be used");
                }

                if (prod.getSceneGeoCoding() == null) {
                    throw new OperatorException(
                            MessageFormat.format("Product ''{0}'' has no geo-coding", prod.getName()));
                }
            }

            if (masterBandNames == null || masterBandNames.length == 0 || getReferenceProduct(masterBandNames[0]) == null) {
                masterBandNames = getReferenceBands();
                if (masterBandNames.length == 0) {
                    targetProduct = OperatorUtils.createDummyTargetProduct(sourceProduct);
                    return;
                }
            }

            referenceProduct = getReferenceProduct(masterBandNames[0]);
            if (referenceProduct == null) {
                targetProduct = OperatorUtils.createDummyTargetProduct(sourceProduct);
                return;
            }

            appendToReference = AbstractMetadata.getAbstractedMetadata(referenceProduct).
                    getAttributeInt(AbstractMetadata.coregistered_stack, 0) == 1 ||
                    AbstractMetadata.getAbstractedMetadata(referenceProduct).getAttributeInt("collocated_stack", 0) == 1;
            final List<String> referenceProductBands = new ArrayList<>(referenceProduct.getNumBands());

            final Band[] secondaryBandList = getSecondaryBands();
            if (referenceProduct == null || secondaryBandList.length == 0 || secondaryBandList[0] == null) {
                targetProduct = OperatorUtils.createDummyTargetProduct(sourceProduct);
                return;
            }

            isResampling = !resamplingType.contains("NONE");
            if (!isResampling && !extent.equals(MASTER_EXTENT)) {
                throw new OperatorException("Please select only Master extents when resampling type is None");
            }

            if (appendToReference) {
                extent = MASTER_EXTENT;
            }

            switch (extent) {
                case MASTER_EXTENT:

                    targetProduct = new Product(OperatorUtils.createProductName(referenceProduct.getName(), PRODUCT_SUFFIX),
                                                referenceProduct.getProductType(),
                                                referenceProduct.getSceneRasterWidth(),
                                                referenceProduct.getSceneRasterHeight());

                    ProductUtils.copyProductNodes(referenceProduct, targetProduct);
                    break;
                case MIN_EXTENT:
                    determineMinExtents();
                    break;
                default:
                    determineMaxExtents();
                    break;
            }

            if (appendToReference) {
                // add all reference bands
                for (Band b : referenceProduct.getBands()) {
                    if (!(b instanceof VirtualBand)) {
                        final Band targetBand = new Band(b.getName(),
                                                         b.getDataType(),
                                                         targetProduct.getSceneRasterWidth(),
                                                         targetProduct.getSceneRasterHeight());
                        referenceProductBands.add(b.getName());
                        sourceRasterMap.put(targetBand, b);
                        targetProduct.addBand(targetBand);

                        ProductUtils.copyRasterDataNodeProperties(b, targetBand);
                        targetBand.setSourceImage(b.getSourceImage());
                    }
                }
            }

            String suffix = StackUtils.REF;
            // add reference bands first
            if (!appendToReference) {
                for (final Band srcBand : secondaryBandList) {
                    if (srcBand.getProduct() == referenceProduct) {
                        suffix = StackUtils.REF + StackUtils.createBandTimeStamp(srcBand.getProduct());
                        int dataType;
                        if (!extent.equals(MAX_EXTENT)) {
                            dataType = srcBand.getDataType();
                        } else {
                            dataType = ProductData.TYPE_FLOAT32;
                        }

                        final Band targetBand = new Band(srcBand.getName() + suffix,
                                                         dataType,
                                                         targetProduct.getSceneRasterWidth(),
                                                         targetProduct.getSceneRasterHeight());
                        referenceProductBands.add(targetBand.getName());
                        sourceRasterMap.put(targetBand, srcBand);
                        targetProduct.addBand(targetBand);

                        ProductUtils.copyRasterDataNodeProperties(srcBand, targetBand);
                        if(targetBand.getValidPixelExpression() != null) {
                            targetBand.setValidPixelExpression(srcBand.getValidPixelExpression().replace(srcBand.getName(), targetBand.getName()));
                        }

                        if (extent.equals(MASTER_EXTENT)) {
                            targetBand.setSourceImage(srcBand.getSourceImage());
                        }
                    }
                }
            }
            // then add secondary bands
            int cnt = 1;
            if (appendToReference) {
                for (Band trgBand : targetProduct.getBands()) {
                    final String name = trgBand.getName();
                    if (name.contains(StackUtils.SEC + cnt))
                        ++cnt;
                }
            }
            for (final Band srcBand : secondaryBandList) {
                if (srcBand.getProduct() != referenceProduct) {
                    if (srcBand.getUnit() != null && srcBand.getUnit().equals(Unit.IMAGINARY)) {
                    } else {
                        suffix = StackUtils.SEC + cnt++ + StackUtils.createBandTimeStamp(srcBand.getProduct());
                    }
                    final String tgtBandName = srcBand.getName() + suffix;

                    if (targetProduct.getBand(tgtBandName) == null) {
                        final Product srcProduct = srcBand.getProduct();
                        int dataType;
                        if (!isResampling) {
                            dataType = srcBand.getDataType();
                        } else {
                            dataType = ProductData.TYPE_FLOAT32;
                        }

                        final Band targetBand = new Band(tgtBandName,
                                                         dataType,
                                                         targetProduct.getSceneRasterWidth(),
                                                         targetProduct.getSceneRasterHeight());
                        sourceRasterMap.put(targetBand, srcBand);
                        targetProduct.addBand(targetBand);

                        ProductUtils.copyRasterDataNodeProperties(srcBand, targetBand);
                        if(targetBand.getValidPixelExpression() != null) {
                            targetBand.setValidPixelExpression(srcBand.getValidPixelExpression().replace(srcBand.getName(), targetBand.getName()));
                        }

                        if (!isResampling && extent.equals(MASTER_EXTENT) && srcProduct.isCompatibleProduct(targetProduct, 1.0e-3f)) {
                            targetBand.setSourceImage(srcBand.getSourceImage());
                        }

                        // Disable using of no data value in secondary so that valid 0s will be used in the interpolation
                        srcBand.setNoDataValueUsed(false);
                    }
                }
            }

            // copy secondary abstracted metadata
            copySecondaryMetadata();

            StackUtils.saveReferenceProductBandNames(targetProduct,
                                                  referenceProductBands.toArray(new String[0]));
            StackUtils.saveSecondaryProductNames(sourceProduct, targetProduct, referenceProduct, sourceRasterMap);

            updateMetadata();

            // copy GCPs if found to reference band
            final ProductNodeGroup<Placemark> referenceGCPgroup = referenceProduct.getGcpGroup();
            if (referenceGCPgroup.getNodeCount() > 0) {
                OperatorUtils.copyGCPsToTarget(referenceGCPgroup, GCPManager.instance().getGcpGroup(targetProduct.getBandAt(0)),
                                               targetProduct.getSceneGeoCoding());
            }

            if (isResampling) {
                selectedResampling = ResamplingFactory.createResampling(resamplingType);
                if(selectedResampling == null) {
                    throw new OperatorException("Resampling method "+ selectedResampling + " is invalid");
                }
            } else {
                if(initialOffsetMethod == null) {
                    initialOffsetMethod = INITIAL_OFFSET_ORBIT;
                }
                if (initialOffsetMethod.equals(INITIAL_OFFSET_GEOLOCATION)) {
                    computeTargetSecondaryCoordinateOffsets_GCP();
                }
                if (initialOffsetMethod.equals(INITIAL_OFFSET_ORBIT)) {
                    computeTargetSecondaryCoordinateOffsets_Orbits();
                }
            }

            // set non-elevation areas to no data value for the reference bands using the secondary bands
            if (!extent.equals(MAX_EXTENT)) {
                //DEMAssistedCoregistrationOp.setReferenceValidPixelExpression(targetProduct, true);
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private String[] getReferenceBands() {
        String[] masterBandNames = new String[] {};
        final Product defaultProd = sourceProduct[0];
        if (defaultProd != null) {
            int index = 0;
            for(Band band : defaultProd.getBands()) {
                if (band.getUnit() != null && band.getUnit().equals(Unit.REAL)) {
                    masterBandNames = new String[]{band.getName(),
                            defaultProd.getBandAt(index + 1).getName()};
                    break;
                }
                ++index;
            }
            if(masterBandNames.length == 0) {
                masterBandNames = new String[]{defaultProd.getBandAt(0).getName()};
            }
        }
        return masterBandNames;
    }

    private void updateMetadata() {
        final MetadataElement abstractedMetadata = AbstractMetadata.getAbstractedMetadata(targetProduct);
        if(abstractedMetadata != null) {
            abstractedMetadata.setAttributeInt("collocated_stack", 1);
        }

        final MetadataElement inputElem = ProductInformation.getInputProducts(targetProduct);

        getBaselines(sourceProduct, targetProduct);

        for (Product srcProduct : sourceProduct) {
            if (srcProduct == referenceProduct)
                continue;

            final MetadataElement secInputElem = ProductInformation.getInputProducts(srcProduct);
            final MetadataAttribute[] secInputProductAttrbList = secInputElem.getAttributes();
            for (MetadataAttribute attrib : secInputProductAttrbList) {
                final MetadataAttribute inputAttrb = AbstractMetadata.addAbstractedAttribute(inputElem, "InputProduct", ProductData.TYPE_ASCII, "", "");
                inputAttrb.getData().setElems(attrib.getData().getElemString());
            }
        }

        if (isBiomassL1c()) {
            MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack, 1);
        }
    }

    private boolean isBiomassL1c() {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        return mission.toLowerCase().contains("biomass") && (origProdRoot.getElement("annotation_coregistered") != null);
    }

    public static void getBaselines(final Product[] sourceProduct, final Product targetProduct) {
        try {
            final MetadataElement abstractedMetadata = AbstractMetadata.getAbstractedMetadata(targetProduct);
            final MetadataElement baselinesElem = getBaselinesElem(abstractedMetadata);

            final InSARStackOverview.IfgStack[] stackOverview = InSARStackOverview.calculateInSAROverview(sourceProduct);

            for(InSARStackOverview.IfgStack stack : stackOverview) {
                final InSARStackOverview.IfgPair[] secondaryList = stack.getMasterSlave();
                //System.out.println("======");
                //System.out.println("Ref_" + StackUtils.createBandTimeStamp(
                //        secondary[0].getMasterMetadata().getAbstractedMetadata().getProduct()).substring(1));

                final MetadataElement refElem = new MetadataElement("Ref_" + StackUtils.createBandTimeStamp(
                        secondaryList[0].getMasterMetadata().getAbstractedMetadata().getProduct()).substring(1));
                baselinesElem.addElement(refElem);

                for (InSARStackOverview.IfgPair secondary : secondaryList) {
                    //System.out.println("Secondary_" + StackUtils.createBandTimeStamp(
                    //        secondary.getSlaveMetadata().getAbstractedMetadata().getProduct()).substring(1) +
                    //        " perp baseline: " + secondary.getPerpendicularBaseline() +
                    //        " temp baseline: " + secondary.getTemporalBaseline());

                    final MetadataElement secElem = new MetadataElement("Secondary_" + StackUtils.createBandTimeStamp(
                            secondary.getSlaveMetadata().getAbstractedMetadata().getProduct()).substring(1));
                    refElem.addElement(secElem);

                    addAttrib(secElem, "Perp Baseline", secondary.getPerpendicularBaseline());
                    addAttrib(secElem, "Temp Baseline", secondary.getTemporalBaseline());
                    addAttrib(secElem, "Modelled Coherence", secondary.getCoherence());
                    addAttrib(secElem, "Height of Ambiguity", secondary.getHeightAmb());
                    addAttrib(secElem, "Doppler Difference", secondary.getDopplerDifference());
                }
                //System.out.println();
            }

        } catch (Error | Exception e) {
            // only log the warning and continue
            SystemUtils.LOG.warning("Unable to calculate baselines. " + e.getMessage());
        }
    }

    private static void addAttrib(final MetadataElement elem, final String tag, final double value) {
        final MetadataAttribute attrib = new MetadataAttribute(tag, ProductData.TYPE_FLOAT64);
        attrib.getData().setElemDouble(value);
        elem.addAttribute(attrib);
    }

    private static MetadataElement getBaselinesElem(final MetadataElement abstractedMetadata) {
        MetadataElement baselinesElem = abstractedMetadata.getElement("Baselines");
        if (baselinesElem == null) {
            baselinesElem = new MetadataElement("Baselines");
            abstractedMetadata.addElement(baselinesElem);
        }
        return baselinesElem;
    }

    private void copySecondaryMetadata() {
        final MetadataElement targetSecondaryMetadataRoot = AbstractMetadata.getSecondaryMetadata(targetProduct.getMetadataRoot());
        for (Product prod : sourceProduct) {
            if (prod != referenceProduct) {
                final MetadataElement secAbsMetadata = AbstractMetadata.getAbstractedMetadata(prod);
                if (secAbsMetadata != null) {
                    final String timeStamp = StackUtils.createBandTimeStamp(prod);
                    final MetadataElement targetSecondaryMetadata = new MetadataElement(prod.getName() + timeStamp);
                    targetSecondaryMetadataRoot.addElement(targetSecondaryMetadata);
                    ProductUtils.copyMetadata(secAbsMetadata, targetSecondaryMetadata);
                }
            }
        }
    }

    private Product getReferenceProduct(final String name) {
        final String referenceName = getProductName(name);
        for (Product prod : sourceProduct) {
            if (prod.getName().equals(referenceName)) {
                return prod;
            }
        }
        return null;
    }

    private Band[] getSecondaryBands() throws OperatorException {
        final List<Band> bandList = new ArrayList<>(5);

        // add reference band
        if (referenceProduct == null) {
            throw new OperatorException("referenceProduct is null");
        }
        if (masterBandNames.length > 2) {
            throw new OperatorException("Reference band should be one real band or a real and imaginary band");
        }
        referenceBands[0] = referenceProduct.getBand(getBandName(masterBandNames[0]));
        if (!appendToReference)
            bandList.add(referenceBands[0]);

        final String unit = referenceBands[0].getUnit();
        if (unit != null) {
            if (unit.contains(Unit.PHASE)) {
                throw new OperatorException("Phase band should not be selected for co-registration");
            } else if (unit.contains(Unit.IMAGINARY)) {
                throw new OperatorException("Real and imaginary reference bands should be selected in pairs");
            } else if (unit.contains(Unit.REAL)) {
                if (masterBandNames.length < 2) {
                    if (!contains(masterBandNames, slaveBandNames[0])) {
                        throw new OperatorException("Real and imaginary reference bands should be selected in pairs");
                    } else {
                        final int iBandIdx = referenceProduct.getBandIndex(getBandName(masterBandNames[0]));
                        referenceBands[1] = referenceProduct.getBandAt(iBandIdx + 1);
                        if (!referenceBands[1].getUnit().equals(Unit.IMAGINARY))
                            throw new OperatorException("For complex products select a real and an imaginary band");
                        if (!appendToReference)
                            bandList.add(referenceBands[1]);
                    }
                } else {
                    final Product prod = getReferenceProduct(masterBandNames[1]);
                    if (prod != referenceProduct) {
                        //throw new OperatorException("Please select reference bands from the same product");
                    }
                    referenceBands[1] = referenceProduct.getBand(getBandName(masterBandNames[1]));
                    if (!referenceBands[1].getUnit().equals(Unit.IMAGINARY))
                        throw new OperatorException("For complex products select a real and an imaginary band");
                    if (!appendToReference)
                        bandList.add(referenceBands[1]);
                }
            }
        }

        // add secondary bands
        if (slaveBandNames == null || slaveBandNames.length == 0 || contains(masterBandNames, slaveBandNames[0])) {
            for (Product secProduct : sourceProduct) {
                for (Band band : secProduct.getBands()) {
                    String bandUnit = band.getUnit();
                    if (bandUnit != null && bandUnit.equals(Unit.PHASE))
                        continue;
                    if (band instanceof VirtualBand && !(bandUnit != null && (bandUnit.equals(Unit.REAL) || bandUnit.equals(Unit.IMAGINARY))))
                        continue;
                    if (secProduct == referenceProduct && (band == referenceBands[0] || band == referenceBands[1] || appendToReference))
                        continue;

                    if(bandUnit == null) {
                        bandList.add(band);
                    } else {
                        for (Band refBand : referenceBands) {
                            if(bandUnit.equals(refBand.getUnit())) {
                                bandList.add(band);
                                break;
                            }
                        }
                    }
                }
            }
        } else {

            for (int i = 0; i < slaveBandNames.length; i++) {
                final String name = slaveBandNames[i];
                if (contains(masterBandNames, name)) {
                    throw new OperatorException("Please do not select the same band as reference and secondary");
                }
                final String bandName = getBandName(name);
                final String productName = getProductName(name);

                final Product prod = getProduct(productName, bandName);
                if (prod == null) continue;

                final Band band = prod.getBand(bandName);
                final String bandUnit = band.getUnit();
                if (bandUnit != null) {
                    if (bandUnit.contains(Unit.PHASE)) {
                        throw new OperatorException("Phase band should not be selected for co-registration");
                    } else if (bandUnit.contains(Unit.REAL) || bandUnit.contains(Unit.IMAGINARY)) {
                        if (slaveBandNames.length < 2) {
                            throw new OperatorException("Real and imaginary secondary bands should be selected in pairs");
                        }
                        final String nextBandName = getBandName(slaveBandNames[i + 1]);
                        final String nextBandProdName = getProductName(slaveBandNames[i + 1]);
                        if (!nextBandProdName.contains(productName)) {
                            throw new OperatorException("Real and imaginary secondary bands should be selected from the same product in pairs");
                        }
                        final Band nextBand = prod.getBand(nextBandName);
                        if ((bandUnit.contains(Unit.REAL) && !nextBand.getUnit().contains(Unit.IMAGINARY) ||
                                (bandUnit.contains(Unit.IMAGINARY) && !nextBand.getUnit().contains(Unit.REAL)))) {
                            throw new OperatorException("Real and imaginary secondary bands should be selected in pairs");
                        }
                        bandList.add(band);
                        bandList.add(nextBand);
                        i++;
                    } else {
                        bandList.add(band);
                    }
                } else {
                    bandList.add(band);
                }
            }
        }
        return bandList.toArray(new Band[0]);
    }

    private Product getProduct(final String productName, final String bandName) {
        for (Product prod : sourceProduct) {
            if (prod.getName().equals(productName)) {
                if (prod.getBand(bandName) != null)
                    return prod;
            }
        }
        return null;
    }

    private static boolean contains(final String[] nameList, final String name) {
        for (String nameInList : nameList) {
            if (name.equals(nameInList))
                return true;
        }
        return false;
    }

    private static String getBandName(final String name) {
        if (name.contains("::"))
            return name.substring(0, name.indexOf("::"));
        return name;
    }

    private String getProductName(final String name) {
        if (name.contains("::"))
            return name.substring(name.indexOf("::") + 2);
        return sourceProduct[0].getName();
    }

    /**
     * Minimum extents consists of the overlapping area
     */
    private void determineMinExtents() {

        Geometry tgtGeometry = FeatureUtils.createGeoBoundaryPolygon(referenceProduct);

        for (final Product secProd : sourceProduct) {
            if (secProd == referenceProduct) continue;

            final Geometry secGeometry = FeatureUtils.createGeoBoundaryPolygon(secProd);
            tgtGeometry = tgtGeometry.intersection(secGeometry);
        }

        final GeoCoding refGeoCoding = referenceProduct.getSceneGeoCoding();
        final PixelPos pixPos = new PixelPos();
        final GeoPos geoPos = new GeoPos();
        final double refWidth = referenceProduct.getSceneRasterWidth();
        final double refHeight = referenceProduct.getSceneRasterHeight();

        double maxX = 0, maxY = 0;
        double minX = refWidth;
        double minY = refHeight;
        for (Coordinate c : tgtGeometry.getCoordinates()) {
            //System.out.println("geo "+c.x +", "+ c.y);
            geoPos.setLocation(c.y, c.x);
            refGeoCoding.getPixelPos(geoPos, pixPos);
            //System.out.println("pix "+pixPos.x +", "+ pixPos.y);
            if (pixPos.isValid() && pixPos.x != -1 && pixPos.y != -1) {
                if (pixPos.x < minX) {
                    minX = Math.max(0, pixPos.x);
                }
                if (pixPos.y < minY) {
                    minY = Math.max(0, pixPos.y);
                }
                if (pixPos.x > maxX) {
                    maxX = Math.min(refWidth, pixPos.x);
                }
                if (pixPos.y > maxY) {
                    maxY = Math.min(refHeight, pixPos.y);
                }
            }
        }

        final ProductSubsetBuilder subsetReader = new ProductSubsetBuilder();
        final ProductSubsetDef subsetDef = new ProductSubsetDef();
        subsetDef.addNodeNames(referenceProduct.getTiePointGridNames());

        subsetDef.setSubsetRegion(new PixelSubsetRegion((int) minX, (int) minY, (int) (maxX - minX), (int) (maxY - minY), 0));
        subsetDef.setSubSampling(1, 1);
        subsetDef.setIgnoreMetadata(false);

        try {
            targetProduct = subsetReader.readProductNodes(referenceProduct, subsetDef);
            final Band[] bands = targetProduct.getBands();
            for (Band b : bands) {
                targetProduct.removeBand(b);
            }
        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    /**
     * Maximum extents consists of the overall area
     */
    private void determineMaxExtents() {

        final OperatorUtils.SceneProperties scnProp = new OperatorUtils.SceneProperties();
        OperatorUtils.computeImageGeoBoundary(sourceProduct, scnProp);

        final Resolution resolution = new Resolution(referenceProduct);
        final double rangeSpacing = resolution.getResX();
        final double azimuthSpacing = resolution.getResY();
        double pixelSize = Math.min(rangeSpacing, azimuthSpacing);

        OperatorUtils.getSceneDimensions(pixelSize, scnProp);

        int sceneWidth = scnProp.sceneWidth;
        int sceneHeight = scnProp.sceneHeight;
        final double ratio = sceneWidth / (double)sceneHeight;
        long dim = (long) sceneWidth * (long) sceneHeight;
        while (sceneWidth > 0 && sceneHeight > 0 && dim > Integer.MAX_VALUE) {
            sceneWidth -= 1000;
            sceneHeight = (int)(sceneWidth / ratio);
            dim = (long) sceneWidth * (long) sceneHeight;
        }

        final Product tempProduct = new Product(referenceProduct.getName(),
                                    referenceProduct.getProductType(),
                                    sceneWidth, sceneHeight);

        ProductUtils.copyProductNodes(referenceProduct, tempProduct);
        OperatorUtils.addGeoCoding(tempProduct, scnProp);

        try {
            final double pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSize);

            final CRSGeoCodingHandler crsHandler = new CRSGeoCodingHandler(tempProduct, "WGS84(DD)",
                    pixelSpacingInDegree, pixelSize,false, 0, 0);

            targetProduct = new Product(referenceProduct.getName(),
                    referenceProduct.getProductType(), crsHandler.getTargetWidth(), crsHandler.getTargetHeight());

            ProductUtils.copyProductNodes(referenceProduct, targetProduct);

            targetProduct.setSceneGeoCoding(crsHandler.getCrsGeoCoding());
        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void computeTargetSecondaryCoordinateOffsets_GCP() {

        final GeoCoding targGeoCoding = targetProduct.getSceneGeoCoding();
        final int targImageWidth = targetProduct.getSceneRasterWidth();
        final int targImageHeight = targetProduct.getSceneRasterHeight();

        final Geometry tgtGeometry = FeatureUtils.createGeoBoundaryPolygon(targetProduct);

        final PixelPos secPixelPos = new PixelPos();
        final PixelPos tgtPixelPos = new PixelPos();
        final GeoPos secGeoPos = new GeoPos();

        for (final Product secProd : sourceProduct) {
            if (secProd == referenceProduct && extent.equals(MASTER_EXTENT)) {
                secondaryOffsetMap.put(secProd, new int[]{0, 0});
                continue;
            }

            final GeoCoding secGeoCoding = secProd.getSceneGeoCoding();
            final int secImageWidth = secProd.getSceneRasterWidth();
            final int secImageHeight = secProd.getSceneRasterHeight();

            boolean foundOverlapPoint = false;

            // test corners
            secGeoCoding.getGeoPos(new PixelPos(10, 10), secGeoPos);
            if (false) {// (pixelPosValid(targGeoCoding, secGeoPos, tgtPixelPos, targImageWidth, targImageHeight)) {

                addOffset(secProd, 0 - (int) tgtPixelPos.x, 0 - (int) tgtPixelPos.y);
                foundOverlapPoint = true;
            }
            if (false) {//!foundOverlapPoint) {
                secGeoCoding.getGeoPos(new PixelPos(secImageWidth - 10, secImageHeight - 10), secGeoPos);
                if (pixelPosValid(targGeoCoding, secGeoPos, tgtPixelPos, targImageWidth, targImageHeight)) {

                    addOffset(secProd, 0 - secImageWidth - (int) tgtPixelPos.x, secImageHeight - (int) tgtPixelPos.y);
                    foundOverlapPoint = true;
                }
            }

            if (!foundOverlapPoint) {
                final Geometry secGeometry = FeatureUtils.createGeoBoundaryPolygon(secProd);
                final Geometry intersect = tgtGeometry.intersection(secGeometry);

                for (Coordinate c : intersect.getCoordinates()) {
                    getPixelPos(c.y, c.x, secGeoCoding, secPixelPos);

                    if (secPixelPos.isValid() && secPixelPos.x >= 0 && secPixelPos.x < secImageWidth &&
                            secPixelPos.y >= 0 && secPixelPos.y < secImageHeight) {

                        getPixelPos(c.y, c.x, targGeoCoding, tgtPixelPos);
                        if (tgtPixelPos.isValid() && tgtPixelPos.x >= 0 && tgtPixelPos.x < targImageWidth &&
                                tgtPixelPos.y >= 0 && tgtPixelPos.y < targImageHeight) {

                            addOffset(secProd, (int) secPixelPos.x - (int) tgtPixelPos.x, (int) secPixelPos.y - (int) tgtPixelPos.y);
                            foundOverlapPoint = true;
                            break;
                        }
                    }
                }
            }

            //if(foundOverlapPoint) {
            //    final int[] offset = secondaryOffsetMap.get(secProd);
            //    System.out.println("offset x="+offset[0]+" y="+offset[1]);
            //}

            if (!foundOverlapPoint) {
                throw new OperatorException("Product " + secProd.getName() + " has no overlap with reference product.");
            }
        }
    }

    private void computeTargetSecondaryCoordinateOffsets_Orbits() throws Exception {
        try {
            // Note: This procedure will always compute some overlap

            // Similar as for GCPs but for every GCP use orbit information
            if (!AbstractMetadata.hasAbstractedMetadata(targetProduct)) {
                throw new Exception("Orbit offset method is not support for product " + targetProduct.getName());
            }
            MetadataElement root = AbstractMetadata.getAbstractedMetadata(targetProduct);

            final int orbitDegree = 3;

            SLCImage metaReference = new SLCImage(root, targetProduct);
            Orbit orbitReference = new Orbit(root, orbitDegree);
            SLCImage metaSecondary;
            Orbit orbitSecondary;

            // Reference point in reference radar geometry
            Point tgtLP = metaReference.getApproxRadarCentreOriginal();

            MetadataElement orbitOffsets = new MetadataElement("Orbit_Offsets");
            MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(targetProduct);
            absRoot.addElement(orbitOffsets);
            for (final Product secProd : sourceProduct) {

                if (secProd == referenceProduct) {
                    // if reference product put 0-es for offset
                    secondaryOffsetMap.put(secProd, new int[]{0, 0});
                    continue;
                }

                // Secondary metadata
                if (!AbstractMetadata.hasAbstractedMetadata(secProd)) {
                    throw new Exception("Orbit offset method is not support for product " + secProd.getName());
                }
                root = AbstractMetadata.getAbstractedMetadata(secProd);
                metaSecondary = new SLCImage(root, secProd);
                orbitSecondary = new Orbit(root, orbitDegree);

                // (lp_reference) & (reference_orbit)-> (xyz_reference) & (secondary_orbit)-> (lp_secondary)
                Point tgtXYZ = orbitReference.lp2xyz(tgtLP, metaReference);
                Point secLP = orbitSecondary.xyz2lp(tgtXYZ, metaSecondary);

                // Offset: secondary minus reference
                Point offsetLP = secLP.min(tgtLP);

                int offsetX = (int) Math.floor(offsetLP.x + .5);
                int offsetY = (int) Math.floor(offsetLP.y + .5);

                // Add to metadata
                String timeStamp = StackUtils.createBandTimeStamp(secProd).substring(1);
                MetadataElement bandElem = null;
                for (String bandName : targetProduct.getBandNames()){
                    String bandTimeStamp = bandName.split("_")[bandName.split("_").length - 1];
                    if (bandTimeStamp.equals(timeStamp)){
                        bandElem = new MetadataElement("init_offsets" + StackUtils.getBandSuffix(bandName));
                        bandElem.setAttributeInt("init_offset_X", offsetX);
                        bandElem.setAttributeInt("init_offset_Y", offsetY);
                    }
                }
                orbitOffsets.addElement(bandElem);

                addOffset(secProd, offsetX, offsetY);

            }
        } catch (Exception e) {
            throw new IOException("Orbit offset method is not support for this product: "+e.getMessage());
        }
    }

    private static boolean pixelPosValid(final GeoCoding geoCoding, final GeoPos geoPos, final PixelPos pixelPos,
                                         final int width, final int height) {
        geoCoding.getPixelPos(geoPos, pixelPos);
        return (pixelPos.isValid() && pixelPos.x >= 0 && pixelPos.x < width &&
                pixelPos.y >= 0 && pixelPos.y < height);
    }

    private static void getPixelPos(final double lat, final double lon, final GeoCoding srcGeoCoding, final PixelPos pixelPos) {
        srcGeoCoding.getPixelPos(new GeoPos(lat, lon), pixelPos);
    }

    private void addOffset(final Product secProd, final int offsetX, final int offsetY) {
        secondaryOffsetMap.put(secProd, new int[]{offsetX, offsetY});
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm) throws OperatorException {
        try {
            final Band sourceRaster = sourceRasterMap.get(targetBand);
            final Product srcProduct = sourceRaster.getProduct();
            final int srcImageWidth = srcProduct.getSceneRasterWidth();
            final int srcImageHeight = srcProduct.getSceneRasterHeight();

            if (!isResampling) { // without resampling

                final float noDataValue = (float) targetBand.getGeophysicalNoDataValue();
                final Rectangle targetRectangle = targetTile.getRectangle();
                final ProductData trgData = targetTile.getDataBuffer();
                final int tx0 = targetRectangle.x;
                final int ty0 = targetRectangle.y;
                final int tw = targetRectangle.width;
                final int th = targetRectangle.height;
                final int maxX = tx0 + tw;
                final int maxY = ty0 + th;

                final int[] offset = secondaryOffsetMap.get(srcProduct);
                final int sx0 = Math.min(Math.max(0, tx0 + offset[0]), srcImageWidth - 1);
                final int sy0 = Math.min(Math.max(0, ty0 + offset[1]), srcImageHeight - 1);
                final int sw = Math.min(sx0 + tw - 1, srcImageWidth - 1) - sx0 + 1;
                final int sh = Math.min(sy0 + th - 1, srcImageHeight - 1) - sy0 + 1;
                final Rectangle srcRectangle = new Rectangle(sx0, sy0, sw, sh);
                final Tile srcTile = getSourceTile(sourceRaster, srcRectangle);
                final ProductData srcData = srcTile.getDataBuffer();

                final TileIndex trgIndex = new TileIndex(targetTile);
                final TileIndex srcIndex = new TileIndex(srcTile);

                boolean isInt = false;
                final int trgDataType = trgData.getType();
                if (trgDataType == srcData.getType() &&
                        (trgDataType == ProductData.TYPE_INT16 || trgDataType == ProductData.TYPE_INT32)) {
                    isInt = true;
                }

                for (int ty = ty0; ty < maxY; ++ty) {
                    final int sy = ty + offset[1];
                    final int trgOffset = trgIndex.calculateStride(ty);
                    if (sy < 0 || sy >= srcImageHeight) {
                        for (int tx = tx0; tx < maxX; ++tx) {
                            trgData.setElemDoubleAt(tx - trgOffset, noDataValue);
                        }
                        continue;
                    }
                    final int srcOffset = srcIndex.calculateStride(sy);
                    for (int tx = tx0; tx < maxX; ++tx) {
                        final int sx = tx + offset[0];

                        if (sx < 0 || sx >= srcImageWidth) {
                            trgData.setElemDoubleAt(tx - trgOffset, noDataValue);
                        } else {
                            if (isInt)
                                trgData.setElemIntAt(tx - trgOffset, srcData.getElemIntAt(sx - srcOffset));
                            else
                                trgData.setElemDoubleAt(tx - trgOffset, srcData.getElemDoubleAt(sx - srcOffset));
                        }
                    }
                }

            } else { // with resampling

                final Collocator col = new Collocator(this, srcProduct, targetProduct, targetTile.getRectangle());
                col.collocateSourceBand(sourceRaster, targetTile, selectedResampling);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    public static void checkPixelSpacing(final Product[] sourceProducts) {
        double savedRangeSpacing = 0.0;
        double savedAzimuthSpacing = 0.0;
        for (final Product prod : sourceProducts) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(prod);
            if (absRoot == null) {
                throw new OperatorException(
                        MessageFormat.format("Product ''{0}'' has no abstract metadata.", prod.getName()));
            }

            final double rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0);
            final double azimuthSpacing = absRoot.getAttributeDouble(AbstractMetadata.azimuth_spacing, 0);
            if(rangeSpacing == 0 || azimuthSpacing == 0)
                return;
            if (savedRangeSpacing > 0.0 && savedAzimuthSpacing > 0.0 &&
                    (Math.abs(rangeSpacing - savedRangeSpacing) > 0.05 ||
                            Math.abs(azimuthSpacing - savedAzimuthSpacing) > 0.05)) {
                throw new OperatorException("Resampling type cannot be NONE because pixel spacings" +
                                                    " are different for reference and secondary products");
            } else {
                savedRangeSpacing = rangeSpacing;
                savedAzimuthSpacing = azimuthSpacing;
            }
        }
    }

    // for unit test
    protected void setTestParameters(final String ext, final String offsetMethod) {
        this.extent = ext;
        this.initialOffsetMethod = offsetMethod;
    }

    /**
     * Operator SPI.
     */
    public static class Spi extends OperatorSpi {

        public Spi() {
            super(CreateStackOp.class);
        }
    }
}
