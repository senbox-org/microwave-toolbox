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
package eu.esa.sar.sentinel1.gpf;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.dataop.barithm.BandArithmetic;
import org.esa.snap.core.dataop.barithm.RasterDataSymbol;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.jexp.ParseException;
import org.esa.snap.core.jexp.Parser;
import org.esa.snap.core.jexp.Term;
import org.esa.snap.core.jexp.WritableNamespace;
import org.esa.snap.core.jexp.impl.ParserImpl;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Merges Sentinel-1 slice products
 */
@OperatorMetadata(alias = "SliceAssembly",
        category = "Radar/Sentinel-1 TOPS",
        authors = "Jun Lu, Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Merges Sentinel-1 slice products")
public final class SliceAssemblyOp extends Operator {

    @SourceProducts
    private Product[] sourceProducts;
    @TargetProduct
    private Product targetProduct;

    // Only bands whose polarization is selected will be in the output product.
    @Parameter(description = "The list of polarisations", label = "Polarisations")
    private String[] selectedPolarisations;

    private MetadataElement absRoot = null;
    private String productType = null;

    // The slice products will be in order in the array: 1st (top) slice is the 1st element in the array followed by
    // 2nd slice and so on.
    private Product[] sliceProducts;
    private final Map<Band, BandLines[]> bandLineMap = new HashMap<>();

    // This is the raster width and height of the target product
    private int targetWidth = 0, targetHeight = 0;

    // Map a swath such as "IW1" to the assembled image height and width.
    // For GRD, use "" for swath.
    // height is 1st element. width is 2nd element.
    private final Map<String, int[]> swathAssembledImageDimMap = new HashMap<>();

    // Map a slice product and swath such as "IW1" to the image height and width.
    // For GRD, use "" for swath.
    // height is 1st element. width is 2nd element.
    private final Map<Product, Map<String, int[]>> sliceSwathImageDimMap = new HashMap<>();

    private final Map<String, TiePointGeoCoding> swathGeocodingMap = new HashMap<>();

    // Burst information for each band in slice product (for SLC only)
    private final Map<Band, BurstDimension> sliceBandBurstDimMap = new HashMap<>();

    // Target burst information for each swath (for SLC only)
    private final Map<String, BurstDimension> targetSwathBurstDimMap = new HashMap<>();

    private boolean isMultiSwath = false;

    private static final String PRODUCT_SUFFIX = "_Asm";

    private double version = 0.0f;

    void setTestProducts(Product[] products) {
        sourceProducts = products;
    }

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public SliceAssemblyOp() {
    }

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
            for (Product srcProduct : sourceProducts) {
                final InputProductValidator validator = new InputProductValidator(srcProduct);
                validator.checkIfSARProduct();
                validator.checkIfSentinel1Product();
                validator.checkProductType(new String[]{"SLC", "GRD"});
                validator.checkAcquisitionMode(new String[]{"SM", "IW", "EW"});
            }

            sliceProducts = determineSliceProducts();
            productType = sliceProducts[0].getProductType();

            absRoot = AbstractMetadata.getAbstractedMetadata(sliceProducts[0]);

            if (selectedPolarisations == null || selectedPolarisations.length == 0) {
                final Sentinel1Utils su = new Sentinel1Utils(sliceProducts[0]);
                selectedPolarisations = su.getPolarizations();
            }

            getIPFVersion();

            checkSlantRangeTimes();

            createTargetProduct();

            updateTargetProductMetadata();

            determineBandStartEndTimes();
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    private void getIPFVersion() {
        final String procSysId = absRoot.getAttributeString(AbstractMetadata.ProcessingSystemIdentifier);
        version = Double.parseDouble(procSysId.substring(procSysId.lastIndexOf(" ")));
        //System.out.println("Sentinel1RemoveThermalNoiseOp: IPF version = " + version);
    }

    Product[] determineSliceProducts() throws Exception {
        if (sourceProducts.length < 2) {
            throw new Exception("Slice assembly requires at least two consecutive slice products");
        }

        // sort the slice products by start time
        Product[] sliceProducts = new Product[sourceProducts.length];
        System.arraycopy(sourceProducts, 0, sliceProducts, 0, sourceProducts.length);
        Arrays.sort(sliceProducts, Comparator.comparingDouble(p -> p.getStartTime().getMJD()));

        // get slice numbers to check if consecutive
        final TreeMap<Integer, Product> productSet = new TreeMap<>();
        int cnt = 0;
        for (Product srcProduct : sliceProducts) {
            final MetadataElement origMetaRoot = AbstractMetadata.getOriginalProductMetadata(srcProduct);
            final MetadataElement generalProductInformation = getGeneralProductInformation(origMetaRoot);
            if (!isSliceProduct(generalProductInformation)) {
                throw new Exception(srcProduct.getName() + " is not a slice product");
            }

            int sliceNumber = generalProductInformation.getAttributeInt("sliceNumber");
            if(sliceNumber < 1) {
                sliceNumber = cnt;
            }

            productSet.put(sliceNumber, srcProduct);
            ++cnt;
        }

        // check if consecutive
        Integer prev = productSet.firstKey();
        // Note that "The set's iterator returns the keys in ascending order".
        for (Integer i : productSet.keySet()) {
            if (!i.equals(prev)) {
                if (!prev.equals(i - 1)) {
                    throw new Exception("Products are not consecutive slices");
                }
                prev = i;
            }
        }

        // Note that "If productSet makes any guarantees as to what order its elements
        // are returned by its iterator, toArray() must return the elements in
        // the same order".
        return productSet.values().toArray(new Product[0]);
    }

    private static MetadataElement getGeneralProductInformation(final MetadataElement origMetaRoot) {
        final MetadataElement XFDU = origMetaRoot.getElement("XFDU");
        final MetadataElement metadataSection = XFDU.getElement("metadataSection");

        final MetadataElement metadataObject = findElementByID(metadataSection, "ID", "generalProductInformation");
        final MetadataElement metadataWrap = metadataObject.getElement("metadataWrap");
        final MetadataElement xmlData = metadataWrap.getElement("xmlData");
        MetadataElement generalProductInformation = xmlData.getElement("generalProductInformation");
        if (generalProductInformation == null)
            generalProductInformation = xmlData.getElement("standAloneProductInformation");
        return generalProductInformation;
    }

    private static boolean isSliceProduct(final MetadataElement generalProductInformation) {
        final String sliceProductFlag = generalProductInformation.getAttributeString("sliceProductFlag");
        return sliceProductFlag.equals("true");
    }

    private static MetadataElement findElementByID(final MetadataElement metadataSection, final String tag, final String id) {
        final MetadataElement[] metadataObjectList = metadataSection.getElements();

        for (MetadataElement metadataObject : metadataObjectList) {
            final String attrib = metadataObject.getAttributeString(tag, null);
            if (attrib.equals(id)) {
                return metadataObject;
            }
        }
        return null;
    }

    private static String extractSwathIdentifier(final String mdsName) {
        // E.g., it can be "iw" or "iw1"
        String swathID = mdsName.substring(4, 7);
        if (swathID.endsWith("-")) {
            swathID = swathID.substring(0, swathID.length() - 1);
        }
        return swathID;
    }

    private static double getSlantRangeTime(final Product product, final String sss) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotation = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationElems = annotation.getElements();

        double slantRangeTime = 0;

        for (MetadataElement e : annotationElems) {
            if (extractSwathIdentifier(e.getName()).equals(sss)) {
                MetadataElement prod = e.getElement("product");
                MetadataElement imgAnno = prod.getElement("imageAnnotation");
                MetadataElement imgInfo = imgAnno.getElement("imageInformation");
                slantRangeTime = imgInfo.getAttributeDouble("slantRangeTime");
                break;
            }
        }

        //System.out.println("return slant range time for " + sss + " = " + slantRangeTime);
        return slantRangeTime;
    }

    private void checkSlantRangeTimes() {

        final Product firstSliceProduct = sliceProducts[0];
        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(firstSliceProduct);
        final MetadataElement annotation = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationElems = annotation.getElements();

        // There is some redundancy here.
        // E.g. we will check for both
        // s1a-iw-grd-vh-20140920t050131-20140920t050156-002471-002aec-002.xml and
        // s1a-iw-grd-vv-20140920t050131-20140920t050156-002471-002aec-001.xml
        for (MetadataElement e : annotationElems) {
            final String swathID = extractSwathIdentifier(e.getName());
            final double slantRangeTime = getSlantRangeTime(firstSliceProduct, swathID);
            //System.out.println("Check slant range time for " + e.getName() + " " + sss + " = " + slantRangeTime);
            for (int i = 1; i < sliceProducts.length; i++) {
                double srt = getSlantRangeTime(sliceProducts[i], swathID);
                if (Math.abs(slantRangeTime - srt) > 1e-8) {
                    SystemUtils.LOG.warning("Slant range time don't agree: " + i + ' ' + swathID);
                }
            }
        }
    }

    private static ArrayList<String> getSwaths(final Product product) {

        ArrayList<String> swaths = new ArrayList<>();

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotation = origProdRoot.getElement("annotation");
        final MetadataElement[] annotationElems = annotation.getElements();

        for (MetadataElement e : annotationElems) {
            String sss = extractSwathIdentifier(e.getName()).toUpperCase();
            if (!swaths.contains(sss)) {
                swaths.add(sss);
            }
        }

        return swaths;
    }

    private static void getSwathDim(final Product product, final String swath, final int[] dim) {

        // dim[0] is height; dim[1] is width

        final Band[] bands = product.getBands();

        for (Band b : bands) {
            if (b.getName().contains(swath)) {
                dim[0] = b.getRasterHeight();
                dim[1] = b.getRasterWidth();
                break;
            }
        }
    }

    private void determineSliceBurstDimension() {

        for (Product srcProduct : sliceProducts) {
            for (Band srcBand : srcProduct.getBands()) {
                if (srcBand instanceof VirtualBand) {
                    continue;
                }
                final String swath = getSwathFromBandName(srcBand.getName());
                final String imageNum = getFirstImageName(srcProduct, swath);
                final MetadataElement sliceSwathTiming = getAnnotationElement(srcProduct, imageNum, "swathTiming");
                final int sliceLinesPerBurst = Integer.parseInt(sliceSwathTiming.getAttributeString("linesPerBurst"));
                final int sliceSamplesPerBurst = Integer.parseInt(sliceSwathTiming.getAttributeString("samplesPerBurst"));
                final MetadataElement sliceBurstList = sliceSwathTiming.getElement("burstList");
                final int sliceBurstListCount = Integer.parseInt(sliceBurstList.getAttributeString("count"));

                sliceBandBurstDimMap.put(srcBand, new BurstDimension(sliceLinesPerBurst, sliceSamplesPerBurst, sliceBurstListCount));
            }
        }
    }

    private void determineTargetBurstDimension() {

        // target burst dimension is determined by the maximum width/height of all bursts from all slices for given swath
        final ArrayList<String> swaths = getSwaths(sliceProducts[0]);

        for (String swath : swaths) {
            final String imageNum = getFirstImageName(sliceProducts[0], swath);

            int linesPerBurst = 0;
            int samplesPerBurst = 0;
            int count = 0;
            for (Product srcProduct : sliceProducts) {
                final MetadataElement sliceSwathTiming = getAnnotationElement(srcProduct, imageNum, "swathTiming");
                final int sliceLinesPerBurst = Integer.parseInt(sliceSwathTiming.getAttributeString("linesPerBurst"));
                if (linesPerBurst < sliceLinesPerBurst) {
                    linesPerBurst = sliceLinesPerBurst;
                }

                final int sliceSamplesPerBurst = Integer.parseInt(sliceSwathTiming.getAttributeString("samplesPerBurst"));
                if (samplesPerBurst < sliceSamplesPerBurst) {
                    samplesPerBurst = sliceSamplesPerBurst;
                }

                final MetadataElement sliceBurstList = sliceSwathTiming.getElement("burstList");
                final int sliceBurstListCount = Integer.parseInt(sliceBurstList.getAttributeString("count"));
                count += sliceBurstListCount;
            }

            targetSwathBurstDimMap.put(swath, new BurstDimension(linesPerBurst, samplesPerBurst, count));
        }
    }

    private String getFirstImageName(final Product product, final String swath) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement[] elements = getElementsToUpdate(origProdRoot, "annotation");
        for (MetadataElement e : elements) {
            if (e.getName().contains(swath.toLowerCase())) {
                return extractImageNumber(e.getName());
            }
        }
        return null;
    }

    private void computeTargetWidthAndHeight() {

        // In GRD products, all the bands will have the same width and height, so the width and height of the
        // product are equal to the width and height of the bands. E.g a GRD product will have these bands:
        // - Amplitude_VH
        // - Amplitude_VV
        // They will have the same width and height.
        //
        // In SLC products, each band belongs to a swath and bands belonging to the same swath will have the
        // same width and height. E.g. a SLC product will have these bands:
        //
        // IW1: w x h = 22553 x 14850
        // - i_IW1_VH
        // - q_IW1_VH
        // - i_IW1_VV
        // - q_IW1_VV
        //
        // IW2: w x h = 26326 x 15363
        // - i_IW2_VH
        // - q_IW2_VH
        // - i_IW2_VV
        // - q_IW2_VV
        //
        // IW3: w x h = 25321 x 15534
        // - i_IW3_VH
        // - q_IW3_VH
        // - i_IW3_VV
        // - q_IW3_VV
        //
        // We assume for such a SLC slice product, the scene raster width and height will be the maximum
        // among the swaths, so product scene raster width = 26326 and product scene raster height = 15534.
        //
        // Say 1st slice:
        //  IW1: w x h = 22553 x 14850
        //  IW2: w x h = 26326 x 15363
        //  IW3: w x h = 25321 x 15534
        //  product width = 26326 and height = 15534
        //
        // 2nd slice:
        //  IW1: w x h = 22571 x 14850
        //  IW2: w x h = 26351 x 15363
        //  IW3: w x h = 25350 x 15543
        // product width = 26351 and height = 15543
        //
        // Assemble the 2 slices:
        //  IW1: w x h = 22571 x 29700
        //  IW2: w x h = 26351 x 30726
        //  IW3: w x h = 25350 x 31077
        // product width = 26351 and height = 31077


        if (productType.equals("GRD")) {

            for (Product srcProduct : sliceProducts) {

                if (targetWidth < srcProduct.getSceneRasterWidth())
                    targetWidth = srcProduct.getSceneRasterWidth();
                targetHeight += srcProduct.getSceneRasterHeight();

                final Map<String, int[]> tmp = new HashMap<>();
                tmp.put("", new int[]{srcProduct.getSceneRasterHeight(), srcProduct.getSceneRasterWidth()});
                sliceSwathImageDimMap.put(srcProduct, tmp);
            }

            swathAssembledImageDimMap.put("", new int[]{targetHeight, targetWidth});

        } else {

            determineSliceBurstDimension();

            determineTargetBurstDimension();

            final ArrayList<String> swaths = getSwaths(sliceProducts[0]);
            final Map<String, Integer> swathHeight = new HashMap<>();
            final Map<String, Integer> swathWidth = new HashMap<>();

            for (String swath : swaths) {
                final BurstDimension tbd = targetSwathBurstDimMap.get(swath);
                swathHeight.put(swath, tbd.linesPerBurst * tbd.count);
                swathWidth.put(swath, tbd.samplesPerBurst);
            }

            for (Product srcProduct : sliceProducts) {
                for (String swath : swaths) {

                    final int[] dim = new int[2];
                    getSwathDim(srcProduct, swath, dim);

                    if (sliceSwathImageDimMap.containsKey(srcProduct)) {
                        final Map<String, int[]> tmp = sliceSwathImageDimMap.get(srcProduct);
                        tmp.put(swath, dim);
                    } else {
                        final Map<String, int[]> tmp = new HashMap<>();
                        tmp.put(swath, dim);
                        sliceSwathImageDimMap.put(srcProduct, tmp);
                    }
                }
            }

            for (String swath : swaths) {
                swathAssembledImageDimMap.put(swath, new int[]{swathHeight.get(swath), swathWidth.get(swath)});
                if (targetWidth < swathWidth.get(swath))
                    targetWidth = swathWidth.get(swath);
                if (targetHeight < swathHeight.get(swath))
                    targetHeight = swathHeight.get(swath);
            }
        }
    }

    private Dimension computeTargetBandWidthAndHeight(final String bandName) throws OperatorException {

        if (productType.equals("GRD")) {
            // See comments in computeTargetWidthAndHeight().
            // For band width, we take the max for that band among all slice products.
            final Dimension dim = new Dimension(0, 0);
            for (Product srcProduct : sliceProducts) {
                final Band srcBand = srcProduct.getBand(bandName);
                if (srcBand == null) {
                    throw new OperatorException(bandName + " not found in product " + srcProduct.getName());
                }

                dim.setSize(Math.max(dim.width, srcBand.getRasterWidth()), dim.height + srcBand.getRasterHeight());
            }
            return dim;

        } else {

            final String swath = getSwathFromBandName(bandName);
            final int width = targetSwathBurstDimMap.get(swath).samplesPerBurst;
            final int height = targetSwathBurstDimMap.get(swath).count * targetSwathBurstDimMap.get(swath).linesPerBurst;
            return new Dimension(width, height);
        }
    }

    private void createTargetProduct() {

        computeTargetWidthAndHeight();

        final Product firstSliceProduct = sliceProducts[0];
        final Product lastSliceProduct = sliceProducts[sliceProducts.length - 1];
        final String lastSliceStopDateAndTime = lastSliceProduct.getName().substring(33, 48);
        final String newProductName = firstSliceProduct.getName().substring(0, 33) + lastSliceStopDateAndTime +
                firstSliceProduct.getName().substring(48) + PRODUCT_SUFFIX;
        targetProduct = new Product(newProductName, productType, targetWidth, targetHeight);

        // We are creating each target band based on the source band in the first slice product only.
        final Band[] sourceBands = firstSliceProduct.getBands();
        for (Band srcBand : sourceBands) {
            boolean selectedPol = false;
            for (String pol : selectedPolarisations) {
                if (srcBand.getName().contains(pol)) {
                    selectedPol = true;
                    break;
                }
            }
            if (!selectedPol)
                continue;

            if (srcBand instanceof VirtualBand) {
                final VirtualBand sourceBand = (VirtualBand) srcBand;
                int destWidth = 0;
                int destHeight = 0;

                final Term term = createTerm(sourceBand.getExpression(), sliceProducts);
                final RasterDataSymbol[] refRasterDataSymbols = BandArithmetic.getRefRasterDataSymbols(term);
                // find new width and height used by corresponding target bands
                for (RasterDataSymbol symbol : refRasterDataSymbols) {
                    String name = symbol.getName();
                    final Band trgBand = targetProduct.getBand(name);
                    if (trgBand != null) {
                        destWidth = trgBand.getRasterWidth();
                        destHeight = trgBand.getRasterHeight();
                        break;
                    }
                }

                final VirtualBand targetBand = new VirtualBand(sourceBand.getName(), sourceBand.getDataType(),
                                                               destWidth, destHeight, sourceBand.getExpression());

                ProductUtils.copyRasterDataNodeProperties(sourceBand, targetBand);
                targetProduct.addBand(targetBand);
            } else {

                final Dimension dim = computeTargetBandWidthAndHeight(srcBand.getName());
                final Band newBand = new Band(srcBand.getName(), srcBand.getDataType(), dim.width, dim.height);
                ProductUtils.copyRasterDataNodeProperties(srcBand, newBand);

                targetProduct.addBand(newBand);
            }
        }

        ProductUtils.copyMetadata(firstSliceProduct, targetProduct);
        ProductUtils.copyFlagCodings(firstSliceProduct, targetProduct);
        ProductUtils.copyMasks(firstSliceProduct, targetProduct);
        ProductUtils.copyVectorData(firstSliceProduct, targetProduct);
        ProductUtils.copyIndexCodings(firstSliceProduct, targetProduct);
        targetProduct.setStartTime(firstSliceProduct.getStartTime());
        targetProduct.setEndTime(lastSliceProduct.getEndTime());
        targetProduct.setDescription(firstSliceProduct.getDescription());

        final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE);
        if (productType.equals("GRD")) {
            createTiePointGrids("");
            addGeocoding();
        } else {
            final ArrayList<String> swaths = getSwaths(firstSliceProduct);
            isMultiSwath = swaths.size() > 1;
            for (String swath : swaths) {
                createTiePointGrids(swath);
            }

            if (isMultiSwath) {
                createLatLonTiePointGridsForSLC();
            }
        }

        //targetProduct.setPreferredTileSize(targetWidth, 1);
    }

    private static Term createTerm(final String expression, final Product[] availableProducts) {
        WritableNamespace namespace = BandArithmetic.createDefaultNamespace(availableProducts, 0);
        final Term term;
        try {
            Parser parser = new ParserImpl(namespace, false);
            term = parser.parse(expression);
        } catch (ParseException e) {
            throw new OperatorException("Could not parse expression: " + expression, e);
        }
        return term;
    }

    private static MetadataElement[] getGeoGridForSwath(final Product product, final String sss) {

        // For GRD products, use "" for sss.

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement annotationElem = origProdRoot.getElement("annotation");

        final MetadataElement[] images = annotationElem.getElements();

        MetadataElement imgElem = null;
        if (sss.isEmpty()) {
            // This is GRD product, same grid for all bands, so just take the 1st one
            imgElem = annotationElem.getElementAt(0);
        } else {
            for (MetadataElement e : images) {
                if (extractSwathIdentifier(e.getName()).equals(sss.toLowerCase())) {
                    imgElem = e;
                    break;
                }
            }
        }

        if (imgElem == null) {
            throw new OperatorException("Cannot locate geolocation grid in metadata for " + sss);
        }

        final MetadataElement productElem = imgElem.getElement("product");
        final MetadataElement geolocationGrid = productElem.getElement("geolocationGrid");
        final MetadataElement geolocationGridPointList = geolocationGrid.getElement("geolocationGridPointList");
        return geolocationGridPointList.getElements();
    }

    /*private void createTiePointGrids(final String swath) {

        //System.out.println("SliceAssemblyOp.createTiePointGrids: " + swath);

        // For GRD, use "" for swath. For SLC, it should be something like "IW1".

        // One geolocationGridPointList for one swath.

        // geolocationGridPointList has a count and a list of geolocationGridPoint(s).
        // These geolocationGridPoint(s) should form an MxN grid, M = number of rows and N = number of columns.
        // Each geolocationGridPoint contains line (i.e. row) and pixel (i.e. column).
        // The horizontal or vertical spacing may not be even.
        // But from line to line, the pixels are in the same location.
        // E.g A 4x6 grid for an image of width 24 and height 17:
        // 1st row: line = 0;	pixels =	0	5	10	15	20	23
        // 2nd row: line = 6;	pixels = 	0	5	10	15	20	23
        // 3rd row: line = 12;	pixels = 	0	5	10	15	20	23
        // 4th row: line = 16;  pixels = 	0	5	10	15	20	23
        // According to Table 6-88 of Product Specs v2.9, each geolocationGridPoint is a point within the image.
        // So we cannot have a point with line 17 or pixels 24.
        //
        // We assume that from slice to slice, M may differ but N must be the same. However, the pixel spacing
        // from slice to slice may not be the same.
        // So the next slice to the example above can be a 5x6 grid for an image of width 30 and height 20:
        // 1st row: line = 0;	pixels =	0	6	12	18	24	29
        // 2nd row: line = 5;	pixels = 	0	6	12	18	24	29
        // 3rd row: line = 10;	pixels = 	0	6	12	18	24	29
        // 4th row: line = 15;  pixels = 	0	6	12	18	24	29
        // 5th row: line = 19;  pixels = 	0	6	12	18	24	29
        //
        // We concatenate the two geolocationGridPointList(s) together to form a 9x6 grid for an image of width 30 and
        // height 37:
        // 1st row: line = 0;	pixels =	0	5	10	15	20	23
        // 2nd row: line = 6;	pixels = 	0	5	10	15	20	23
        // 3rd row: line = 12;	pixels = 	0	5	10	15	20	23
        // 4th row: line = 16;  pixels = 	0	5	10	15	20	23
        // 5th row: line = 17;	pixels =	0	6	12	18	24	29
        // 6th row: line = 22;	pixels = 	0	6	12	18	24	29
        // 7th row: line = 27;	pixels = 	0	6	12	18	24	29
        // 8th row: line = 32;  pixels = 	0	6	12	18	24	29
        // 9th row: line = 36;  pixels = 	0	6	12	18	24	29

        int geoGridLen = 0;
        final ArrayList<MetadataElement[]> geoGrids = new ArrayList<>();
        int i = 0;
        for (Product product : sliceProducts) {
            MetadataElement[] geoGrid = getGeoGridForSwath(product, swath);
            geoGridLen += geoGrid.length;
            geoGrids.add(i, geoGrid);
            i++;
        }

        //System.out.println("geoGridLen = " + geoGridLen);

        final double[] latList = new double[geoGridLen];
        final double[] lngList = new double[geoGridLen];
        final double[] incidenceAngleList = new double[geoGridLen];
        final double[] elevAngleList = new double[geoGridLen];
        final double[] rangeTimeList = new double[geoGridLen];
        final int[] x = new int[geoGridLen];
        final int[] y = new int[geoGridLen];

        final int[] gridWidths = new int[sliceProducts.length];
        final int[] gridHeights = new int[sliceProducts.length];

        for (int j = 0; j < sliceProducts.length; j++) {
            gridWidths[j] = 0;
            gridHeights[j] = 0;
        }

        int gridHeight = 0;

        i = 0;
        int ptsInPrvSlices = 0;
        int heightOffset = 0;
        for (int j = 0; j < sliceProducts.length; j++) {

            if (j > 0) {
                heightOffset += sliceSwathImageDimMap.get(sliceProducts[j - 1]).get(swath)[0];
            }

            final MetadataElement[] geoGrid = geoGrids.get(j);

            for (MetadataElement ggPoint : geoGrid) {
                latList[i] = ggPoint.getAttributeDouble("latitude", 0);
                lngList[i] = ggPoint.getAttributeDouble("longitude", 0);
                incidenceAngleList[i] = ggPoint.getAttributeDouble("incidenceAngle", 0);
                elevAngleList[i] = ggPoint.getAttributeDouble("elevationAngle", 0);
                rangeTimeList[i] = ggPoint.getAttributeDouble("slantRangeTime", 0) * Constants.oneBillion; // s to ns

                x[i] = (int) ggPoint.getAttributeDouble("pixel", 0);
                if (x[i] == 0) {
                    // This means we are at the start of a new line
                    // gridWidths[j] will be updated to 0 at the 1st line.
                    // It will be updated to the correct value at the 2nd line.
                    if (gridWidths[j] == 0)
                        gridWidths[j] = i - ptsInPrvSlices;
                    ++gridHeights[j];
                }

                y[i] = (int) ggPoint.getAttributeDouble("line", 0) + heightOffset;

                ++i;
            }

            ptsInPrvSlices = i;

            gridHeight += gridHeights[j];
        }

        final int gridWidth = gridWidths[0];

        // We assume here that all the slice products will have the same width in the geolocation grid.
        // That is the same number of points in each line.
        for (int w : gridWidths) {
            if (w != gridWidth) {
                throw new OperatorException("geolocation grids have different widths among slice products");
            }
        }

        //System.out.println("gridWidth = " + gridWidth + " gridHeight = " + gridHeight);

        final int newGridWidth = gridWidth;
        final int newGridHeight = gridHeight;
        if (geoGridLen != (newGridWidth * newGridHeight)) {
            throw new OperatorException("wrong number of geolocation grid points");
        }
        final float[] newLatList = new float[newGridWidth * newGridHeight];
        final float[] newLonList = new float[newGridWidth * newGridHeight];
        final float[] newIncList = new float[newGridWidth * newGridHeight];
        final float[] newElevList = new float[newGridWidth * newGridHeight];
        final float[] newslrtList = new float[newGridWidth * newGridHeight];

        final int[] dim = swathAssembledImageDimMap.get(swath);
        final int sceneRasterWidth = dim[1];
        final int sceneRasterHeight = dim[0];

        //System.out.println("swath = " + swath + " width = " + sceneRasterWidth + " height = " + sceneRasterHeight);

        final double subSamplingX = (double) sceneRasterWidth / (newGridWidth - 1);
        final double subSamplingY = (double) sceneRasterHeight / (newGridHeight - 1);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, latList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newLatList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, lngList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newLonList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, incidenceAngleList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newIncList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, elevAngleList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newElevList);

        getListInEvenlySpacedGrid(sceneRasterWidth, sceneRasterHeight, gridWidth, gridHeight, x, y, rangeTimeList,
                                  newGridWidth, newGridHeight, subSamplingX, subSamplingY, newslrtList);

        final String prefix = swath.isEmpty() ? swath : swath + '_';

        final TiePointGrid latGrid = new TiePointGrid(prefix + OperatorUtils.TPG_LATITUDE,
                                                      newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLatList);
        latGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(latGrid);

        final TiePointGrid lonGrid = new TiePointGrid(prefix + OperatorUtils.TPG_LONGITUDE,
                                                      newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newLonList, TiePointGrid.DISCONT_AT_180);
        lonGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(lonGrid);

        final TiePointGrid incidentAngleGrid = new TiePointGrid(prefix + OperatorUtils.TPG_INCIDENT_ANGLE,
                                                                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newIncList);
        incidentAngleGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(incidentAngleGrid);

        final TiePointGrid elevAngleGrid = new TiePointGrid(prefix + OperatorUtils.TPG_ELEVATION_ANGLE,
                                                            newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newElevList);
        elevAngleGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(elevAngleGrid);

        final TiePointGrid slantRangeGrid = new TiePointGrid(prefix + OperatorUtils.TPG_SLANT_RANGE_TIME,
                                                             newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, newslrtList);
        slantRangeGrid.setUnit(Unit.NANOSECONDS);
        targetProduct.addTiePointGrid(slantRangeGrid);

        if (!swath.isEmpty()) {
            // This is for SLC
            final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
            swathGeocodingMap.put(swath, tpGeoCoding);
        }

        //System.out.println("SliceAssemblyOp.createTiePointGrids: DONE " + swath);
    }*/

    private void createTiePointGrids(final String swath) {

        // compute new TPG grid width and height for assembled image
        final ArrayList<MetadataElement[]> geoGrids = new ArrayList<>();
        for (int i = 0; i < sliceProducts.length; ++i) {
            MetadataElement[] geoGrid = getGeoGridForSwath(sliceProducts[i], swath);
            geoGrids.add(i, geoGrid);
        }

        final int[] gridWidths = new int[sliceProducts.length];
        final int[] gridHeights = new int[sliceProducts.length];
        for (int j = 0; j < sliceProducts.length; j++) {
            gridWidths[j] = 0;
            gridHeights[j] = 0;
        }

        int gridHeight = 0;

        int n = 0;
        int ptsInPrvSlices = 0;
        for (int i = 0; i < sliceProducts.length; i++) {
            final MetadataElement[] geoGrid = geoGrids.get(i);
            for (MetadataElement ggPoint : geoGrid) {
                final int pixel = (int) ggPoint.getAttributeDouble("pixel", 0);
                if (pixel == 0) {
                    if (gridWidths[i] == 0) {
                        gridWidths[i] = n - ptsInPrvSlices;
                    }
                    ++gridHeights[i];
                }
                ++n;
            }
            ptsInPrvSlices = n;
            gridHeight += gridHeights[i];
        }

        final int gridWidth = gridWidths[0];
        for (int w : gridWidths) {
            if (w != gridWidth) {
                throw new OperatorException("geolocation grids have different widths among slice products");
            }
        }

        // generate new TPG for assembled image using TPGs of slices
        final int newGridWidth = gridWidth;
        final int newGridHeight = gridHeight;

        final float[] latList = new float[newGridWidth * newGridHeight];
        final float[] lonList = new float[newGridWidth * newGridHeight];
        final float[] incList = new float[newGridWidth * newGridHeight];
        final float[] elevList = new float[newGridWidth * newGridHeight];
        final float[] slrtList = new float[newGridWidth * newGridHeight];

        final int[] dim = swathAssembledImageDimMap.get(swath);
        final int sceneRasterWidth = dim[1];
        final int sceneRasterHeight = dim[0];
        final double subSamplingX = (double) sceneRasterWidth / (newGridWidth - 1);
        final double subSamplingY = (double) sceneRasterHeight / (newGridHeight - 1);

        final String prefix = isMultiSwath ? swath + '_' : "";

        final TiePointGrid[] latTPG = new TiePointGrid[sliceProducts.length];
        final TiePointGrid[] lonTPG = new TiePointGrid[sliceProducts.length];
        final TiePointGrid[] incTPG = new TiePointGrid[sliceProducts.length];
        final TiePointGrid[] elevTPG = new TiePointGrid[sliceProducts.length];
        final TiePointGrid[] slrtTPG = new TiePointGrid[sliceProducts.length];
        for (int i = 0; i < sliceProducts.length; ++i) {
            latTPG[i] = sliceProducts[i].getTiePointGrid(prefix + OperatorUtils.TPG_LATITUDE);
            lonTPG[i] = sliceProducts[i].getTiePointGrid(prefix + OperatorUtils.TPG_LONGITUDE);
            incTPG[i] = sliceProducts[i].getTiePointGrid(prefix + OperatorUtils.TPG_INCIDENT_ANGLE);
            elevTPG[i] = sliceProducts[i].getTiePointGrid(prefix + OperatorUtils.TPG_ELEVATION_ANGLE);
            slrtTPG[i] = sliceProducts[i].getTiePointGrid(prefix + OperatorUtils.TPG_SLANT_RANGE_TIME);
        }

        int k = 0;
        for (int r = 0; r < newGridHeight; ++r) {
            final double y = r*subSamplingY;

            double yy = 0.0;
            int sliceIdx = 0, heightOffset = 0;
            for (int i = 0; i < sliceProducts.length; ++i) {
                heightOffset += sliceSwathImageDimMap.get(sliceProducts[i]).get(swath)[0];
                // The second condition is to fix the bug where rounding errors cause the last line to be
                // just outside the last slice, i.e., the first condition is never satisfied and we could
                // end up with zero sliceIdx.
                if (y <= heightOffset || i == sliceProducts.length - 1) {
                    yy = y - heightOffset + sliceSwathImageDimMap.get(sliceProducts[i]).get(swath)[0];
                    sliceIdx = i;
                    break;
                }
            }

            for (int c = 0; c < newGridWidth; ++c) {
                final double x = c*subSamplingX;
                latList[k] = (float)latTPG[sliceIdx].getPixelDouble(x, yy);
                lonList[k] = (float)lonTPG[sliceIdx].getPixelDouble(x, yy);
                incList[k] = (float)incTPG[sliceIdx].getPixelDouble(x, yy);
                elevList[k] = (float)elevTPG[sliceIdx].getPixelDouble(x, yy);
                slrtList[k] = (float)slrtTPG[sliceIdx].getPixelDouble(x, yy);
                k++;
            }
        }

        final TiePointGrid latGrid = new TiePointGrid(prefix + OperatorUtils.TPG_LATITUDE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, latList);
        latGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(latGrid);

        final TiePointGrid lonGrid = new TiePointGrid(prefix + OperatorUtils.TPG_LONGITUDE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, lonList, TiePointGrid.DISCONT_AT_180);
        lonGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(lonGrid);

        final TiePointGrid incidentAngleGrid = new TiePointGrid(prefix + OperatorUtils.TPG_INCIDENT_ANGLE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, incList);
        incidentAngleGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(incidentAngleGrid);

        final TiePointGrid elevAngleGrid = new TiePointGrid(prefix + OperatorUtils.TPG_ELEVATION_ANGLE,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, elevList);
        elevAngleGrid.setUnit(Unit.DEGREES);
        targetProduct.addTiePointGrid(elevAngleGrid);

        final TiePointGrid slantRangeGrid = new TiePointGrid(prefix + OperatorUtils.TPG_SLANT_RANGE_TIME,
                newGridWidth, newGridHeight, 0.5f, 0.5f, subSamplingX, subSamplingY, slrtList);
        slantRangeGrid.setUnit(Unit.NANOSECONDS);
        targetProduct.addTiePointGrid(slantRangeGrid);

        if (isMultiSwath) { // This is for multi-swath SLC
            final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
            swathGeocodingMap.put(swath, tpGeoCoding);
        }
    }

    private void createLatLonTiePointGridsForSLC() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sliceProducts[0]);
        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        int firstSwathNum = 9999, lastSwathNum = 0;
        for (String key : swathAssembledImageDimMap.keySet()) {
            int subnum = Integer.parseInt(key.substring(2));
            if (subnum < firstSwathNum) {
                firstSwathNum = subnum;
            }
            if (subnum > lastSwathNum) {
                lastSwathNum = subnum;
            }
        }

        final String firstSwath = acquisitionMode + firstSwathNum;
        final String lastSwath = acquisitionMode + lastSwathNum;

        final GeoCoding firstSwathGeoCoding = swathGeocodingMap.get(firstSwath);
        final int firstSWBandHeight = swathAssembledImageDimMap.get(firstSwath)[0];

        final GeoCoding lastSwathGeoCoding = swathGeocodingMap.get(lastSwath);
        final int lastSWBandWidth = swathAssembledImageDimMap.get(lastSwath)[1];
        final int lastSWBandHeight = swathAssembledImageDimMap.get(lastSwath)[0];

        final PixelPos ulPix = new PixelPos(0, 0);
        final PixelPos llPix = new PixelPos(0, firstSWBandHeight - 1);
        final GeoPos ulGeo = new GeoPos();
        final GeoPos llGeo = new GeoPos();
        firstSwathGeoCoding.getGeoPos(ulPix, ulGeo);
        firstSwathGeoCoding.getGeoPos(llPix, llGeo);

        final PixelPos urPix = new PixelPos(lastSWBandWidth - 1, 0);
        final PixelPos lrPix = new PixelPos(lastSWBandWidth - 1, lastSWBandHeight - 1);
        final GeoPos urGeo = new GeoPos();
        final GeoPos lrGeo = new GeoPos();
        lastSwathGeoCoding.getGeoPos(urPix, urGeo);
        lastSwathGeoCoding.getGeoPos(lrPix, lrGeo);

        final float[] latCorners = {(float) ulGeo.getLat(), (float) urGeo.getLat(), (float) llGeo.getLat(), (float) lrGeo.getLat()};
        final float[] lonCorners = {(float) ulGeo.getLon(), (float) urGeo.getLon(), (float) llGeo.getLon(), (float) lrGeo.getLon()};

        ReaderUtils.addGeoCoding(targetProduct, latCorners, lonCorners);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_lat, ulGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_near_long, ulGeo.getLon());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_lat, urGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_far_long, urGeo.getLon());

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_lat, llGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_near_long, llGeo.getLon());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_lat, lrGeo.getLat());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_far_long, lrGeo.getLon());
    }

    private void addGeocoding() {
        final TiePointGrid latGrid = targetProduct.getTiePointGrid(OperatorUtils.TPG_LATITUDE);
        final TiePointGrid lonGrid = targetProduct.getTiePointGrid(OperatorUtils.TPG_LONGITUDE);

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
        targetProduct.setSceneGeoCoding(tpGeoCoding);
    }

    private static String extractImageNumber(final String filename) {

        final int dotIdx = filename.indexOf('.');
        return filename.substring(dotIdx - 3, dotIdx);
    }

    private static ProductData getStopTime(final Product product, final String imageNum) {

        //System.out.println("getStopTime for " + product.getName() + " imageNum = " + imageNum);

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement calibration = origProdRoot.getElement("calibration");
        final MetadataElement[] calibrationElems = calibration.getElements();

        ProductData data = null;

        for (MetadataElement e : calibrationElems) {

            //System.out.println("getStopTime: " + e.getName());

            if (extractImageNumber(e.getName()).equals(imageNum)) {

                final MetadataElement calib = e.getElement("calibration");
                final MetadataElement adsHeader = calib.getElement("adsHeader");
                final MetadataAttribute stopTime = adsHeader.getAttribute("stopTime");
                //System.out.println("getStopTime: " + stopTime.getData().toString());

                data = stopTime.getData();
            }

        }

        return data;
    }

    private MetadataElement[] getElementsToUpdate(final MetadataElement root, String dataName) {

        final MetadataElement data = root.getElement(dataName);
        final MetadataElement[] dataElems = data.getElements();
        final ArrayList<MetadataElement> elemsToRemove = new ArrayList<>();

        // loop through each s1...-nnn.xml where nnn is the image number
        for (MetadataElement dataElem : dataElems) {

            boolean isSelected = false;
            for (String pol : selectedPolarisations) {
                if (dataElem.getName().toUpperCase().contains(pol)) {
                    isSelected = true;
                    break;
                }
            }

            if (!isSelected) {
                elemsToRemove.add(dataElem);
            }
        }

        for (MetadataElement dataElem : elemsToRemove) {
            //System.out.println("remove " + dataName + " for " + target.getName());
            data.removeElement(dataElem);
        }

        return data.getElements();
    }

    private static MetadataElement getNoiseAzimuthVectorList(final Product product, final String imageNum) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        for (MetadataElement e : origProdRoot.getElement("noise").getElements()) {
            if (extractImageNumber(e.getName()).equals(imageNum)) {
                return e.getElement("noise").getElement("noiseAzimuthVectorList");
            }
        }
        return null;
    }

    private static MetadataElement getCalibrationOrNoiseVectorList(final Product product, final String imageNum, final String dataName) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement data = origProdRoot.getElement(dataName);
        final MetadataElement[] elems = data.getElements();

        MetadataElement vectorList = null;

        for (MetadataElement e : elems) {

            if (extractImageNumber(e.getName()).equals(imageNum)) {

                final MetadataElement dat = e.getElement(dataName);
                vectorList = getVectorListElement(dat, dataName);
            }
        }

        return vectorList;
    }

    private static int getCalibrationOrNoisePixelSpacing(final Product product, final String imageNum, final String dataName) {

        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement data = origProdRoot.getElement(dataName);
        final MetadataElement[] dataElems = data.getElements();

        int pixelSpacing = 0;

        for (MetadataElement e : dataElems) {
            //System.out.println("getCalibrationOrNoisePixelCount: " + e.getName());
            if (extractImageNumber(e.getName()).equals(imageNum)) {
                final MetadataElement dat = e.getElement(dataName);
                final MetadataElement vectorList = getVectorListElement(dat, dataName);
                final MetadataElement firstVector = vectorList.getElementAt(0);
                final MetadataElement pixel = firstVector.getElement("pixel");

                final MetadataAttribute count = pixel.getAttribute("count");
                //System.out.println("getCalibrationOrNoisePixelCount: " + count.getData().toString());
                final int pixelCount = Integer.parseInt(count.getData().getElemString());

                final MetadataAttribute pixels = pixel.getAttribute("pixel");
                final String pixelsStr = pixels.getData().getElemString();
                final String[] pixelsArrayOfStr = pixelsStr.split(" ");

                if (pixelCount != pixelsArrayOfStr.length) {
                    throw new OperatorException("wrong pixel count " + product.getName() + ' ' + imageNum + ' ' + dataName + ' ' + pixelCount + ' ' + pixelsArrayOfStr.length);
                }

                if (pixelCount < 2) {
                    throw new OperatorException("wrong pixel count " + product.getName() + ' ' + imageNum + ' ' + dataName + ' ' + pixelCount);
                }

                final int pixel0 = Integer.parseInt(pixelsArrayOfStr[0]);
                final int pixel1 = Integer.parseInt(pixelsArrayOfStr[1]);
                //System.out.println("pixel0 = " + pixel0 + " pixel1 = " + pixel1);
                pixelSpacing = pixel1 - pixel0;

                break;
            }
        }

        return pixelSpacing;
    }

    private static void combineNoiseAzimuthVectorList(
            final MetadataElement targetVectorList, final MetadataElement sliceVectorList, final int sliceIndex) {

        final int tgtCount = Integer.parseInt(targetVectorList.getAttribute("count").getData().getElemString());
        final int sliceCount = Integer.parseInt(sliceVectorList.getAttribute("count").getData().getElemString());

        for (int i = 0; i < tgtCount; i++) {
            MetadataElement v = targetVectorList.getElementAt(i);
            final MetadataAttribute attribute = new MetadataAttribute("slice", ProductData.TYPE_ASCII);
            v.addAttribute(attribute);
            v.setAttributeString(attribute.getName(), "0");
        }

        for (int i = 0; i < sliceCount; i++) {
            MetadataElement v = sliceVectorList.getElementAt(i);
            MetadataElement newV = v.createDeepClone();
            final MetadataAttribute attribute = new MetadataAttribute("slice", ProductData.TYPE_ASCII);
            newV.addAttribute(attribute);
            newV.setAttributeString(attribute.getName(), String.valueOf(sliceIndex));
            targetVectorList.addElementAt(newV, tgtCount + i);
        }

        targetVectorList.setAttributeString("count", Integer.toString(tgtCount + sliceCount));
    }

    private static void concatenateVectors(final MetadataElement targetVectorList,
                                           final MetadataElement sliceVectorList,
                                           final int startVectorIdx,
                                           final int lineOffset) {

        int idx = Integer.parseInt(targetVectorList.getAttribute("count").getData().getElemString());
        final int numSliceLines = Integer.parseInt(sliceVectorList.getAttribute("count").getData().getElemString());

        final int topLastLine = Integer.parseInt(targetVectorList.getElementAt(idx - 1).getAttributeString("line"));
        final int bottom1stLine = lineOffset + Integer.parseInt(sliceVectorList.getElementAt(startVectorIdx).getAttributeString("line"));
        if (topLastLine >= bottom1stLine) {
            throw new OperatorException("last vector line of stop slice = " + topLastLine + " >= first vector line of bottom slice = " + bottom1stLine);
        }

        for (int i = startVectorIdx; i < numSliceLines; i++) {

            MetadataElement v = sliceVectorList.getElementAt(i);
            MetadataElement newV = v.createDeepClone();
            final int newLine = Integer.parseInt(v.getAttributeString("line")) + lineOffset;
            newV.setAttributeString("line", Integer.toString(newLine));
            targetVectorList.addElementAt(newV, idx);
            idx++;
        }

        targetVectorList.setAttributeString("count", Integer.toString(idx));
    }

    private static int getLastPixel(final MetadataElement vector) {

        final MetadataElement pixel = vector.getElement("pixel");
        final String pixelsStr = pixel.getAttributeString("pixel");
        final String[] pixelsArrayOfStr = pixelsStr.split(" ");

        return Integer.parseInt(pixelsArrayOfStr[pixelsArrayOfStr.length - 1]);
    }

    private static MetadataElement getVectorListElement(final MetadataElement element, final String dataName) {
        String vectorListName = dataName + "VectorList";
        if(!element.containsElement(vectorListName)) {
            vectorListName = dataName + "RangeVectorList";
        }
        return element.getElement(vectorListName);
    }

    private void updateAnnotation(final String dataName) {

        // dataName should be "calibration" or "noise"
        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        if(!targetOrigProdRoot.containsElement(dataName)) {
            return;
        }

        // The calibration or noise data in metadata in targetProduct at this point is copied from the 1st slice.
        // So we need to concatenate the vectors from the 2nd to last slices to target.

        // Note that for a slice image of say height 1000, the first calibration vectors can be at lines:
        // 0, 347, 709. ... or
        // -334 -201, 50, 108, ... or
        // 465, 8990, ...
        // and the last calibration vectors can be at lines:
        // 459, 600, 899 or
        // 799, 990, 1400, 1678, 1900

        // TODO: We should make the short vectors longer so that all vectors have "same" columns.
        // TODO: E.g., a vector from top slice (width = 20055) has pixels:
        // TODO: 0 40 80 ... 20040 20056
        // TODO: a vector from the bottom slice (width = 20086) has pixels:
        // TODO: 0 40 80 ... 20040 20080 20086
        // TODO: Assembled product has width 20086 and we need to use extrapolation to extend the vectors from the top
        // TODO: slice to have pixels:
        // TODO: 0 40 80 ... 20040 20080 20086

        final Product lastSliceProduct = sliceProducts[sliceProducts.length - 1];
        final MetadataElement[] targetDataElems = getElementsToUpdate(targetOrigProdRoot, dataName);

        // loop through each s1...-nnn.xml where nnn is the image number
        for (MetadataElement target : targetDataElems) {

            //System.out.println("update " + dataName + " for " + target.getName());

            final String swathID = extractSwathIdentifier(target.getName()); // e.g. "iw" for GRD or "iw1" for SLC
            final String imageNum = extractImageNumber(target.getName()); // e.g. "001"
            final MetadataElement targetDat = target.getElement(dataName);

            //System.out.println("swathID = " + swathID + "; imageNum = " + imageNum);

            // Update stopTime in adsHeader
            final MetadataElement targetADSHeader = targetDat.getElement("adsHeader");
            final ProductData lastSliceStopTime = getStopTime(lastSliceProduct, imageNum);
            AbstractMetadata.setAttribute(targetADSHeader, "stopTime", lastSliceStopTime.getElemString());

            //System.out.println("stopTime = " + lastSliceStopTime.getElemString());

            // Update (calibration|noise)VectorList

            final MetadataElement targetVectorList = getVectorListElement(targetDat, dataName);
            MetadataElement targetNoiseAzimuthVectorList = null;
            if (version >= 2.9 && dataName.equals("noise")) {
                targetNoiseAzimuthVectorList = targetDat.getElement("noiseAzimuthVectorList");
            }

            int numLines = Integer.parseInt(targetVectorList.getAttribute("count").getData().getElemString());
            final int pixelSpacing = getCalibrationOrNoisePixelSpacing(targetProduct, imageNum, dataName);  // TODO

            int height = productType.equals("GRD") ?
                    sliceProducts[0].getSceneRasterHeight() :
                    sliceSwathImageDimMap.get(sliceProducts[0]).get(swathID.toUpperCase())[0];

            //System.out.println("initial numLines = " + numLines + " pixelSpacing = " + pixelSpacing + " height = " + height);

            // Loop through 2nd to last slice products in order and concatenate the (calibration|noise) vectors from each
            // slice to the bottom of target
            for (int i = 1; i < sliceProducts.length; i++) {

                final Product sliceProduct = sliceProducts[i];
                final int slicePixelSpacing = getCalibrationOrNoisePixelSpacing(sliceProduct, imageNum, dataName);

                if (pixelSpacing != slicePixelSpacing) {
                    throw new OperatorException("slice products have different pixel spacing in " + dataName + " vectors: " + i + ' ' + pixelSpacing + ' ' + slicePixelSpacing);
                }

                final int targetLastVectorLine = Integer.parseInt(targetVectorList.getElementAt(
                        targetVectorList.getNumElements() - 1).getAttributeString("line"));
                //System.out.println("targetLastVectorLine = " + targetLastVectorLine + " slicePixelSpacing = " + slicePixelSpacing + " height = " + height + " pixelSpacing = " + pixelSpacing + " numLines = " + numLines);

                if (version >= 2.9 && dataName.equals("noise")) {
                    final MetadataElement sliceNoiseAzimuthVectorList = getNoiseAzimuthVectorList(sliceProduct, imageNum);
                    combineNoiseAzimuthVectorList(targetNoiseAzimuthVectorList, sliceNoiseAzimuthVectorList, i);
                }

                final MetadataElement sliceVectorList = getCalibrationOrNoiseVectorList(sliceProduct, imageNum, dataName);
                final int sliceNumLines = Integer.parseInt(sliceVectorList.getAttribute("count").getData().getElemString());

                final int sliceFirstVectorLine = Integer.parseInt(sliceVectorList.getElementAt(0).getAttributeString("line"));
                //System.out.println("sliceFirstVectorLine = " + sliceFirstVectorLine);

                int numLinesRemoved = 0;

                if (targetLastVectorLine == sliceFirstVectorLine + height - 1) {

                    concatenateVectors(targetVectorList, sliceVectorList, 0, height);

                } else {

                    // Ignore excess top calibration vectors from bottom slice.
                    // E.g., if the first calibration vectors of the slice are at lines: -700, -348, 456, ...
                    // then we want to ignore the calibration vector at -700.
                    int k;
                    for (k = 0; k < sliceNumLines; k++) {
                        if (Integer.parseInt(sliceVectorList.getElementAt(k).getAttributeString("line")) > 0) {
                            break;
                        }
                    }
                    if (k == (sliceNumLines - 1)) {
                        throw new OperatorException("Only one " + dataName + " vector in slice " + i);
                    }
                    // k is the first line we want to keep
                    k = (k == 0) ? k : k - 1;
                    final int sliceTopVectorLine =
                            Integer.parseInt(sliceVectorList.getElementAt(k).getAttributeString("line"));
                    final int sliceTopVectorLastPixel = getLastPixel(sliceVectorList.getElementAt(k));

                    // Remove excess bottom calibration vectors from target.
                    final ArrayList<MetadataElement> elemsToRemove = new ArrayList<>();
                    int j;
                    for (j = numLines - 1; j >= 0; j--) {

                        MetadataElement targetCalibVector = targetVectorList.getElementAt(j);
                        final int targetCalibVectorLine = Integer.parseInt(targetCalibVector.getAttributeString("line"));

                        if (targetCalibVectorLine >= (sliceTopVectorLine + height)) {

                            if ((j > 0 && Integer.parseInt(targetVectorList.getElementAt(j - 1).getAttributeString("line")) >= (height - 1)) ||
                                    (getLastPixel(targetCalibVector) <= sliceTopVectorLastPixel)) {

                                elemsToRemove.add(targetCalibVector);

                            } else {

                                break;
                            }

                        } else {

                            break;
                        }
                    }
                    numLinesRemoved = elemsToRemove.size();
                    for (MetadataElement e : elemsToRemove) {
                        targetVectorList.removeElement(e);
                    }

                    // Must update targetCalibVectorList count before calling concatenateCalibrationVectors()
                    targetVectorList.setAttributeString("count", Integer.toString(numLines - numLinesRemoved));

                    final int lastTargetLine = Integer.parseInt(targetVectorList.
                            getElementAt(numLines - numLinesRemoved - 1).getAttributeString("line"));

                    //System.out.println("lastTargetLine = " + lastTargetLine + " k = " + k);

                    // Get rid of overlaps in the bottom slice
                    for (j = k; j < sliceNumLines; j++) {
                        if (Integer.parseInt(sliceVectorList.getElementAt(j).getAttributeString("line")) + height
                                > lastTargetLine) {
                            k = j;
                            break;
                        }
                    }

                    // If target does not have enough calibration vectors to cover the bottom of the image, then we
                    // want to add back some top calibration vectors from the slice
                    for (j = k - 1; j >= 0; j--) {
                        final int sliceLine = Integer.parseInt(sliceVectorList.getElementAt(j).getAttributeString("line"));
                        if (sliceLine + height <= lastTargetLine) {
                            break;
                        }
                    }

                    concatenateVectors(targetVectorList, sliceVectorList, j + 1, height);
                    numLinesRemoved += (j + 1);
                }

                numLines += (sliceNumLines - numLinesRemoved);
                if (numLines != targetVectorList.getNumElements()) {
                    throw new OperatorException("numLines = " + numLines + " != numElems = " + targetVectorList.getNumElements());
                }
                targetVectorList.setAttributeString("count", Integer.toString(numLines));
                height += (productType.equals("GRD") ?
                        sliceProduct.getSceneRasterHeight() :
                        sliceSwathImageDimMap.get(sliceProduct).get(swathID.toUpperCase())[0]);
            }
        }
    }

    private static MetadataElement getAnnotationElement(final Product product, final String imageNum, final String elemName) {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
        final MetadataElement targetData = targetOrigProdRoot.getElement("annotation");

        final MetadataElement[] targetDataElems = targetData.getElements();
        MetadataElement elem = null;
        for (MetadataElement target : targetDataElems) {

            if (extractImageNumber(target.getName()).equals(imageNum)) {
                final MetadataElement productElem = target.getElement("product");
                elem = productElem.getElement(elemName);
                break;
            }
        }

        return elem;
    }

    private static String getProductLastLineUtcTime(final Product product, final String imageNum) {

        final MetadataElement imageAnnotationElem = getAnnotationElement(product, imageNum, "imageAnnotation");
        if (imageAnnotationElem == null) {
            return "";
        }
        final MetadataElement imageInformationElem = imageAnnotationElem.getElement("imageInformation");
        return imageInformationElem.getAttributeString("productLastLineUtcTime");
    }

    private void updateImageInformation() {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final MetadataElement[] targetDataElems = getElementsToUpdate(targetOrigProdRoot, "annotation");

        // loop through each s1...-nnn.xml where nnn is the image number
        for (MetadataElement target : targetDataElems) {

            final MetadataElement productElem = target.getElement("product");
            final MetadataElement imageAnnotationElem = productElem.getElement("imageAnnotation");
            final MetadataElement imageInformationElem = imageAnnotationElem.getElement("imageInformation");

            imageInformationElem.setAttributeString("productLastLineUtcTime",
                                                    getProductLastLineUtcTime(sliceProducts[sliceProducts.length - 1],
                                                                              extractImageNumber(target.getName())));

            final String swathID;
            if (productType.equals("GRD")) {
                swathID = "";
            } else {
                swathID = extractSwathIdentifier(target.getName());
            }
            imageInformationElem.setAttributeString("numberOfSamples", Integer.toString(swathAssembledImageDimMap.get(swathID.toUpperCase())[1]));
            imageInformationElem.setAttributeString("numberOfLines", Integer.toString(swathAssembledImageDimMap.get(swathID.toUpperCase())[0]));
        }
    }

    private void updateSwathTiming() {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        MetadataElement[] elements = getElementsToUpdate(targetOrigProdRoot, "annotation");

        for (MetadataElement e : elements) {

            final String imageNum = extractImageNumber(e.getName());
            final String swath = extractSwathIdentifier(e.getName()).toUpperCase();
            MetadataElement targetSwathTiming = e.getElement("product").getElement("swathTiming");
            MetadataElement targetBurstList = targetSwathTiming.getElement("burstList");
            int count = Integer.parseInt(targetBurstList.getAttributeString("count"));
            if (count == 0) {
                return;
            }

            final int linesPerBurst = targetSwathBurstDimMap.get(swath).linesPerBurst;
            final int samplesPerBurst = targetSwathBurstDimMap.get(swath).samplesPerBurst;
            targetBurstList.setAttributeString("count", "0");
            final MetadataElement[] burstListElems = targetBurstList.getElements();
            for (MetadataElement b : burstListElems) {
                targetBurstList.removeElement(b);
            }

            long targetByteIncr = 4L * linesPerBurst * samplesPerBurst;

            // update burst list
            int k = 0;
            long sliceFirstByteOffset = 0;
            for (int i = 0; i < sliceProducts.length; i++) {

                MetadataElement sliceSwathTiming = getAnnotationElement(sliceProducts[i], imageNum, "swathTiming");
                MetadataElement sliceBurstList = sliceSwathTiming.getElement("burstList");
                final int sliceBurstListCount = Integer.parseInt(sliceBurstList.getAttributeString("count"));
                if (sliceBurstListCount < 1) {
                    // This handles the case when it is a GRD product
                    continue;
                }

                MetadataElement[] sliceBurstListElems = sliceBurstList.getElements();
                final int sliceLinesPerBurst = Integer.parseInt(sliceSwathTiming.getAttributeString("linesPerBurst"));
                final int linesTpPad = linesPerBurst - sliceLinesPerBurst;
                long newByteOffset = 0;
                if (i == 0) {
                    sliceFirstByteOffset = Long.parseLong(sliceBurstListElems[0].getAttributeString("byteOffset"));
                }
                for (MetadataElement b : sliceBurstListElems) {
                    MetadataElement newB = b.createDeepClone();

                    // update byteOffset
                    newByteOffset = sliceFirstByteOffset + k * targetByteIncr;
                    newB.setAttributeString("byteOffset", Long.toString(newByteOffset));

                    // update firstValidSample / lastValidSample
                    if (linesTpPad > 0) {
                        final MetadataElement firstValidSampleElem = newB.getElement("firstValidSample");
                        final MetadataAttribute firstValidSampleAttr = firstValidSampleElem.getAttribute("firstValidSample");
                        String firstValidSampleDataStr = firstValidSampleAttr.getData().getElemString();
                        for (int j = 0; j < linesTpPad; ++j) {
                            firstValidSampleDataStr = firstValidSampleDataStr.concat(" -1");
                        }

                        firstValidSampleElem.setAttributeString("firstValidSample", firstValidSampleDataStr);
                        firstValidSampleElem.setAttributeString("count", String.valueOf(linesPerBurst));

                        final MetadataElement lastValidSampleElem = newB.getElement("lastValidSample");
                        final MetadataAttribute lastValidSampleAttr = lastValidSampleElem.getAttribute("lastValidSample");
                        String lastValidSampleDataStr = lastValidSampleAttr.getData().getElemString();
                        for (int j = 0; j < linesTpPad; ++j) {
                            lastValidSampleDataStr = lastValidSampleDataStr.concat(" -1");
                        }
                        lastValidSampleElem.setAttributeString("lastValidSample", lastValidSampleDataStr);
                        lastValidSampleElem.setAttributeString("count", String.valueOf(linesPerBurst));
                    }

                    targetBurstList.addElementAt(newB, k);
                    k++;
                }
            }

            targetSwathTiming.setAttributeString("linesPerBurst", Integer.toString(linesPerBurst));
            targetSwathTiming.setAttributeString("samplesPerBurst", Integer.toString(samplesPerBurst));
            targetBurstList.setAttributeString("count", Integer.toString(targetSwathBurstDimMap.get(swath).count));
        }
    }

    private void updateGeolocationGrid() {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        MetadataElement[] elements = getElementsToUpdate(targetOrigProdRoot, "annotation");

        for (MetadataElement e : elements) {

            final String imageNum = extractImageNumber(e.getName());
            MetadataElement targetGeolocationGrid = e.getElement("product").getElement("geolocationGrid");
            MetadataElement targetGeolocationGridPointList = targetGeolocationGrid.getElement("geolocationGridPointList");
            int count = Integer.parseInt(targetGeolocationGridPointList.getAttributeString("count"));

            MetadataElement sliceImageAnnotation = getAnnotationElement(sliceProducts[0], imageNum, "imageAnnotation");
            MetadataElement sliceImageInformation = sliceImageAnnotation.getElement("imageInformation");
            int numberOfLines = Integer.parseInt(sliceImageInformation.getAttributeString("numberOfLines"));
            for (int i = 1; i < sliceProducts.length; i++) {

                MetadataElement sliceGeolocationGrid = getAnnotationElement(sliceProducts[i], imageNum, "geolocationGrid");
                MetadataElement sliceGeolocationGridPointList = sliceGeolocationGrid.getElement("geolocationGridPointList");
                final int sliceCount = Integer.parseInt(sliceGeolocationGridPointList.getAttributeString("count"));
                if (sliceCount < 1) {
                    continue;
                }

                MetadataElement[] sliceGeolocationGridPoints = sliceGeolocationGridPointList.getElements();
                for (MetadataElement p : sliceGeolocationGridPoints) {
                    MetadataElement newP = p.createDeepClone();
                    final long sliceLine = Long.parseLong(p.getAttributeString("line"));
                    newP.setAttributeString("line", Long.toString(sliceLine + numberOfLines));
                    targetGeolocationGridPointList.addElementAt(newP, count++);
                }

                sliceImageAnnotation = getAnnotationElement(sliceProducts[i], imageNum, "imageAnnotation");
                sliceImageInformation = sliceImageAnnotation.getElement("imageInformation");
                numberOfLines += Integer.parseInt(sliceImageInformation.getAttributeString("numberOfLines"));
            }
            targetGeolocationGridPointList.setAttributeString("count", Integer.toString(count));
        }
    }

    private void updateDopplerCentroid() {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        MetadataElement[] elements = getElementsToUpdate(targetOrigProdRoot, "annotation");

        for (MetadataElement e : elements) {

            final String imageNum = extractImageNumber(e.getName());
            MetadataElement targetDopplerCentroid = e.getElement("product").getElement("dopplerCentroid");
            MetadataElement targetDCEstimateList = targetDopplerCentroid.getElement("dcEstimateList");
            int count = Integer.parseInt(targetDCEstimateList.getAttributeString("count"));
            for (int i = 1; i < sliceProducts.length; i++) {

                MetadataElement sliceDopplerCentroid = getAnnotationElement(sliceProducts[i], imageNum, "dopplerCentroid");
                MetadataElement sliceDCEstimateList = sliceDopplerCentroid.getElement("dcEstimateList");
                final int sliceCount = Integer.parseInt(sliceDCEstimateList.getAttributeString("count"));
                if (sliceCount < 1) {
                    continue;
                }

                MetadataElement[] sliceDCEstimates = sliceDCEstimateList.getElements();
                for (MetadataElement dc : sliceDCEstimates) {
                    targetDCEstimateList.addElementAt(dc.createDeepClone(), count++);
                }
            }
            targetDCEstimateList.setAttributeString("count", Integer.toString(count));
        }
    }

    private static MetadataElement getAzimuthFmRateList(final Product product, String imageNum) {

        final MetadataElement generalAnnotation = getAnnotationElement(product, imageNum, "generalAnnotation");
        return generalAnnotation.getElement("azimuthFmRateList");
    }

    private void updateAzimuthFmRateList() {

        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        final MetadataElement[] elements = getElementsToUpdate(targetOrigProdRoot, "annotation");

        for (MetadataElement e : elements) {

            final String imageNum = extractImageNumber(e.getName());
            final MetadataElement targetAzimuthFmRateList = e.getElement("product").getElement("generalAnnotation").getElement("azimuthFmRateList");
            int targetNewCount = Integer.parseInt(targetAzimuthFmRateList.getAttributeString("count"));

            for (int i = 1; i < sliceProducts.length; i++) {

                final MetadataElement sliceAzimuthFmRateList = getAzimuthFmRateList(sliceProducts[i], imageNum);
                final int sliceCount = Integer.parseInt(sliceAzimuthFmRateList.getAttributeString("count"));

                for (int j = 0; j < sliceCount; j++) {
                    final MetadataElement azimuthFmRate = sliceAzimuthFmRateList.getElementAt(j).createDeepClone();
                    targetAzimuthFmRateList.addElementAt(azimuthFmRate, targetNewCount + j);
                }

                targetNewCount += sliceCount;
            }

            targetAzimuthFmRateList.setAttributeString("count", Integer.toString(targetNewCount));
        }
    }

    private static void concatenateOrbitStateVectors(final List<OrbitStateVector> orbVectorList, final OrbitStateVector[] orbs) {

        // concatenate orbs to orbVectorList
        // orbVectorList may be empty

        if (orbVectorList.isEmpty()) {
            orbVectorList.addAll(Arrays.asList(orbs));
        } else {
            // We assume that "orbs" are in chronological order
            final double lastTime = orbVectorList.get(orbVectorList.size() - 1).time_mjd;
            final double firstTime = orbs[0].time_mjd;
            final double midTime = (lastTime + firstTime) / 2.0;
            int firstVecToRemove = -1;
            for (int i = 0; i < orbVectorList.size(); i++) {
                if (orbVectorList.get(i).time_mjd > midTime) {
                    firstVecToRemove = i;
                    break;
                }
            }

            if (firstVecToRemove != -1) {
                orbVectorList.subList(firstVecToRemove, orbVectorList.size()).clear();
            }

            for (OrbitStateVector orb : orbs) {
                if (orb.time_mjd > midTime) {
                    orbVectorList.add(orb);
                }
            }
        }
    }

    private void updateRFI() {
        final String dataName = "rfi";
        final MetadataElement targetOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(targetProduct);
        if(!targetOrigProdRoot.containsElement(dataName)) {
            return;
        }

        final Product lastSliceProduct = sliceProducts[sliceProducts.length - 1];
        final MetadataElement[] targetDataElems = getElementsToUpdate(targetOrigProdRoot, dataName);

        // loop through each s1...-nnn.xml where nnn is the image number
        for (MetadataElement target : targetDataElems) {

            final String swathID = extractSwathIdentifier(target.getName()); // e.g. "iw" for GRD or "iw1" for SLC
            final MetadataElement targetDat = target.getElement(dataName);

            // Update stopTime in adsHeader
            final MetadataElement targetADSHeader = targetDat.getElement("adsHeader");
            final String imageNum = extractImageNumber(target.getName()); // e.g. "001"
            final ProductData lastSliceStopTime = getStopTime(lastSliceProduct, imageNum);
            AbstractMetadata.setAttribute(targetADSHeader, "stopTime", lastSliceStopTime.getElemString());
            final String pol = targetADSHeader.getAttributeString("polarisation");

            final MetadataElement rfiDetectionReportListElem = targetDat.getElement("rfiDetectionFromNoiseReportList");
            final MetadataElement burstReportElem = targetDat.getElement("rfiBurstReportList");

            int rfiDetectionReportCount = rfiDetectionReportListElem.getNumElements();
            int burstReportCount = burstReportElem.getNumElements();

            for (int i = 1; i < sliceProducts.length; i++) {

                final Product sliceProduct = sliceProducts[i];
                final MetadataElement sliceOrigProdRoot = AbstractMetadata.getOriginalProductMetadata(sliceProduct);
                if(!sliceOrigProdRoot.containsElement(dataName)) {
                    continue;
                }

                final MetadataElement[] sliceDataElems = getElementsToUpdate(sliceOrigProdRoot, dataName);
                for (MetadataElement slice : sliceDataElems) {

                    final String sliceSwathID = extractSwathIdentifier(slice.getName()); // e.g. "iw" for GRD or "iw1" for SLC
                    if(!swathID.equals(sliceSwathID)) {
                        continue;
                    }
                    final MetadataElement sliceDat = slice.getElement(dataName);

                    final MetadataElement sliceADSHeader = sliceDat.getElement("adsHeader");
                    final String slicePol = sliceADSHeader.getAttributeString("polarisation");
                    if(!pol.equals(slicePol)) {
                        continue;
                    }

                    final MetadataElement sliceRfiDetectionReportListElem = sliceDat.getElement("rfiDetectionFromNoiseReportList");
                    final MetadataElement sliceBurstReportElem = sliceDat.getElement("rfiBurstReportList");

                    for(MetadataElement rfiDetectionReport : sliceRfiDetectionReportListElem.getElements()) {
                        rfiDetectionReportListElem.addElement(rfiDetectionReport.createDeepClone());
                        rfiDetectionReportCount++;
                    }

                    for(MetadataElement burstReport : sliceBurstReportElem.getElements()) {
                        burstReportElem.addElement(burstReport.createDeepClone());
                        burstReportCount++;
                    }
                }
            }

            rfiDetectionReportListElem.setAttributeString("count", String.valueOf(rfiDetectionReportCount));
            burstReportElem.setAttributeString("count", String.valueOf(burstReportCount));
        }
    }

    private void updateTargetProductMetadata() throws Exception {

        // All the metadata has been copied from the 1st slice product to the assembled target product.
        // Now we want to update the metadata that should not be "included", but "merged" or concatenated".

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        final Product firstSliceProduct = sliceProducts[0];
        final Product lastSliceProduct = sliceProducts[sliceProducts.length - 1];
        final MetadataElement absFirst = AbstractMetadata.getAbstractedMetadata(firstSliceProduct);
        final MetadataElement absLast = AbstractMetadata.getAbstractedMetadata(lastSliceProduct);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_line_time,
                                      absFirst.getAttributeUTC(AbstractMetadata.first_line_time));
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_line_time,
                                      absLast.getAttributeUTC(AbstractMetadata.last_line_time));

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, targetHeight);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, targetWidth);

        for (Band band : targetProduct.getBands()) {
            MetadataElement bandMeta = AbstractMetadata.getBandAbsMetadata(absTgt, band);

            if (bandMeta != null) {
                AbstractMetadata.setAttribute(bandMeta, AbstractMetadata.first_line_time,
                                              absFirst.getAttributeUTC(AbstractMetadata.first_line_time));
                AbstractMetadata.setAttribute(bandMeta, AbstractMetadata.last_line_time,
                                              absLast.getAttributeUTC(AbstractMetadata.last_line_time));

                AbstractMetadata.setAttribute(bandMeta, AbstractMetadata.num_output_lines, band.getRasterHeight());
                AbstractMetadata.setAttribute(bandMeta, AbstractMetadata.num_samples_per_line, band.getRasterWidth());
            }
        }

        final ArrayList<MetadataElement> bandMetaToRemove = new ArrayList<>();
        MetadataElement[] absTgtElems = absTgt.getElements();
        for (MetadataElement e : absTgtElems) {
            final String elemName = e.getName();
            if (elemName.contains("Band")) {
                boolean containsPol = false;
                for (String pol : selectedPolarisations) {
                    if (elemName.contains(pol)) {
                        containsPol = true;
                        break;
                    }
                }
                if(!containsPol) {
                    bandMetaToRemove.add(e);
                }
            }
        }
        for (MetadataElement e : bandMetaToRemove) {
            absTgt.removeElement(e);
        }

        final List<OrbitStateVector> orbVectorList = new ArrayList<>();
        final List<AbstractMetadata.SRGRCoefficientList> srgrList = new ArrayList<>();
        final List<AbstractMetadata.DopplerCentroidCoefficientList> dopList = new ArrayList<>();
        for (Product srcProduct : sliceProducts) {
            final MetadataElement absSrc = AbstractMetadata.getAbstractedMetadata(srcProduct);

            // update orbit state vectors
            final OrbitStateVector[] orbs = AbstractMetadata.getOrbitStateVectors(absSrc);
            concatenateOrbitStateVectors(orbVectorList, orbs);

            // update srgr coeffs
            final AbstractMetadata.SRGRCoefficientList[] srgr = AbstractMetadata.getSRGRCoefficients(absSrc);
            srgrList.addAll(Arrays.asList(srgr));

            // update Doppler centroid coeffs
            final AbstractMetadata.DopplerCentroidCoefficientList[] dop = AbstractMetadata.getDopplerCentroidCoefficients(absSrc);
            dopList.addAll(Arrays.asList(dop));
        }

        AbstractMetadata.setOrbitStateVectors(absTgt, orbVectorList.toArray(new OrbitStateVector[0]));
        AbstractMetadata.setSRGRCoefficients(absTgt, srgrList.toArray(new AbstractMetadata.SRGRCoefficientList[0]));
        AbstractMetadata.setDopplerCentroidCoefficients(absTgt, dopList.toArray(new AbstractMetadata.DopplerCentroidCoefficientList[0]));

        updateAnnotation("calibration");
        updateAnnotation("noise");
        updateRFI();

        updateImageInformation();

        updateSwathTiming();

        updateGeolocationGrid();

        updateDopplerCentroid();

        updateAzimuthFmRateList();
    }

    private String getSwathFromBandName(final String bandName) {

        return bandName.substring(2, 5).toLowerCase().toUpperCase();
    }

    private void determineBandStartEndTimes() {

        for (Band targetBand : targetProduct.getBands()) {
            if (targetBand instanceof VirtualBand) {
                continue;
            }
            final List<BandLines> bandLineList = new ArrayList<>(sliceProducts.length);
            int height = 0;
            for (Product srcProduct : sliceProducts) {
                final Band srcBand = srcProduct.getBand(targetBand.getName());
                int start = height;
                if (productType.equals("GRD")) {
                    height += srcBand.getRasterHeight();
                } else {
                    final String swath = getSwathFromBandName(srcBand.getName());
                    height += sliceBandBurstDimMap.get(srcBand).count * targetSwathBurstDimMap.get(swath).linesPerBurst;
                }
                int end = height;
                bandLineList.add(new BandLines(srcBand, start, end));
            }
            final BandLines[] lines = bandLineList.toArray(new BandLines[0]);
            bandLineMap.put(targetBand, lines);
        }
    }

    private int getSourceProductLineIndex(
            final int y, final BandLines line, final int targetLinesPerBurst, final int sourceLinesPerBurst) {

        if (targetLinesPerBurst == sourceLinesPerBurst) {
            return y - line.start;
        }

        final int yy = y - line.start;
        final int burstIdx = yy / targetLinesPerBurst;
        final int lineIdxInBurst = yy  - burstIdx * targetLinesPerBurst;
        if (lineIdxInBurst >= sourceLinesPerBurst) {
            return -1;
        } else {
            return burstIdx * sourceLinesPerBurst + lineIdxInBurst;
        }
    }


    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetBand The target band.
     * @param targetTile The current tile associated with the target band to be computed.
     * @param pm         A progress monitor which should be used to determine computation cancellation requests.
     * @throws OperatorException If an error occurs during computation of the target raster.
     */
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {

        try {
            final Rectangle targetTileRectangle = targetTile.getRectangle();
            final int tx0 = targetTileRectangle.x;
            final int ty0 = targetTileRectangle.y;
            final int maxY = ty0 + targetTileRectangle.height;
            final int maxX = tx0 + targetTileRectangle.width;

            if (targetTileRectangle.width < 2)
                return;

            final BandLines[] lines = bandLineMap.get(targetBand);
            final ProductData trgData = targetTile.getDataBuffer();

            final TileIndex trgIndex = new TileIndex(targetTile);
            final Rectangle srcRect = new Rectangle();

            int targetLinesPerBurst = 0;
            if (!productType.equals("GRD")) {
                final String swath = getSwathFromBandName(targetBand.getName());
                targetLinesPerBurst = targetSwathBurstDimMap.get(swath).linesPerBurst;
            }

            BandLines line = lines[0];
            for (int y = ty0; y < maxY; ++y) {

                for (BandLines l : lines) {
                    if (y >= l.start && y < l.end) {
                        line = l;
                        break;
                    }
                }

                // convert target product line index y to source product line index yy
                int yy = 0;
                if (productType.equals("GRD")) {
                    yy = y - line.start;
                } else {
                    final int sourceLinesPerBurst = sliceBandBurstDimMap.get(line.band).linesPerBurst;
                    yy = getSourceProductLineIndex(y, line, targetLinesPerBurst, sourceLinesPerBurst);
                    if (yy == -1) {
                        continue;
                    }
                }

                final int sxMax = Math.min(maxX, line.band.getRasterWidth() - 1);
                srcRect.setBounds(tx0, yy, sxMax - tx0 + 1, 1);
                final Tile sourceRaster = getSourceTile(line.band, srcRect);
                final ProductData srcData = sourceRaster.getDataBuffer();
                final TileIndex srcIndex = new TileIndex(sourceRaster);
                trgIndex.calculateStride(y);
                srcIndex.calculateStride(yy);

                for (int x = tx0; x < sxMax; ++x) {
                    trgData.setElemDoubleAt(trgIndex.getIndex(x), srcData.getElemDoubleAt(srcIndex.getIndex(x)));
                }
            }
        } catch (Throwable e) {
            throw new OperatorException(e.getMessage());
        }
    }

    private static class BandLines {
        final int start;
        final int end;
        final Band band;

        BandLines(final Band band, final int s, final int e) {
            this.band = band;
            this.start = s;
            this.end = e;
        }
    }

    private static class BurstDimension {
        final int linesPerBurst;
        final int samplesPerBurst;
        final int count;

        BurstDimension(final int linesPerBurst, final int samplesPerBurst, final int count) {
            this.linesPerBurst = linesPerBurst;
            this.samplesPerBurst = samplesPerBurst;
            this.count = count;
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
            super(SliceAssemblyOp.class);
        }
    }
}
