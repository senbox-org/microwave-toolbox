package org.jlinda.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.jblas.ComplexDoubleMatrix;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.filtering.RangeFilter;
import org.jlinda.core.utils.*;

import javax.media.jai.BorderExtender;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;

@OperatorMetadata(alias = "RangeFilter",
        category = "Radar/Interferometric/Filtering/Spectral Filtering",
        authors = "Petar Marinkovic",
        version = "1.0",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Range Filter")
public class RangeFilterOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"8", "16", "32", "64", "128", "256", "512", "1024"},
            description = "Length of filtering window",
            defaultValue = "8",
            label = "FFT Window Length")
    private int fftLength = 64;

//    @Parameter(valueSet = {"5", "10", "15", "20", "25", "30", "35", "40"},
//            description = "Overlap between tiles in range direction [pixels]",
//            defaultValue = "10",
//            label = "Range Filter Overlap")
//    private int rangeTileOverlap = 10;

    @Parameter(valueSet = {"0.5", "0.75", "0.8", "0.9", "1"},
            description = "Weight for Hamming filter (1 is rectangular window)",
            defaultValue = "0.75",
            label = "Hamming Alpha")
    private float alphaHamming = (float) 0.75;

    @Parameter(valueSet = {"5", "10", "15", "20", "25"},
            description = "Input value for (walking) mean averaging to reduce noise.",
            defaultValue = "15",
            label = "Walking Mean Window") //has to be odd!
    private int nlMean = 15;

    @Parameter(valueSet = {"3", "4", "5", "6", "7"},
            description = "Threshold on SNR for peak estimation",
            defaultValue = "5",
            label = "SNR Threshold")
    private float snrThresh = 5;

    @Parameter(valueSet = {"1", "2", "4"},
            description = "Oversampling factor (in range only).",
            defaultValue = "1",
            label = "Oversampling factor")
    private int ovsmpFactor = 1;

    @Parameter(valueSet = {"true", "false"},
            description = "Use weight values to bias higher frequencies",
            defaultValue = "off",
            label = "De-weighting")
    private boolean doWeightCorrel = false;

    // source
    private HashMap<String, CplxContainer> referenceMap = new HashMap<>();
    private HashMap<String, CplxContainer> secondaryMap = new HashMap<>();

    // target
    private HashMap<String, ProductContainer> targetMap = new HashMap<>();

    private String[] polarisations;

    private static final int ORBIT_DEGREE = 3; // hardcoded
    private static final boolean CREATE_VIRTUAL_BAND = true;

    private static int TILE_OVERLAP_X;
    private static int TILE_OVERLAP_Y;
    private static int TILE_EXTENT_X;
    private static int TILE_EXTENT_Y;

    private static final String PRODUCT_NAME = "range_filter";
    private static final String PRODUCT_TAG = "rngfilt";

    private static final int OUT_PRODUCT_DATA_TYPE = ProductData.TYPE_FLOAT32;


    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link Product} annotated with the
     * {@link TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {
        try {

            checkUserInput();
            constructSourceMetadata();
            constructTargetMetadata();

            // getSourceImageGeocodings();
            // estimateFlatEarthPolynomial();
            // updateTargetProductMetadata();
            // updateTargetProductGeocoding();

            createTargetProduct();

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void constructTargetMetadata() {

        // loop through references
        for (String keyReference : referenceMap.keySet()) {

            CplxContainer reference = referenceMap.get(keyReference);
            String referenceSourceName_I = reference.realBand.getName();
            String referenceSourceName_Q = reference.imagBand.getName();

            // generate name for product bands
            final String productName = keyReference.toString();

            for (String keySecondary : secondaryMap.keySet()) {

                final CplxContainer secondary = secondaryMap.get(keySecondary);
                if (reference.polarisation == null || reference.polarisation.equals(secondary.polarisation)) {
                    String secondarySourceName_I = secondary.realBand.getName();
                    String secondarySourceName_Q = secondary.imagBand.getName();

                    final ProductContainer product = new ProductContainer(productName, reference, secondaryMap.get(keySecondary), true);

                    product.masterSubProduct.targetBandName_I = referenceSourceName_I;
                    product.masterSubProduct.targetBandName_Q = referenceSourceName_Q;

                    product.slaveSubProduct.targetBandName_I = secondarySourceName_I;
                    product.slaveSubProduct.targetBandName_Q = secondarySourceName_Q;

                    // put ifg-product bands into map
                    targetMap.put(productName, product);
                }
            }
        }
    }

    private void constructSourceMetadata() throws Exception {

        // define sourceReference/sourceSecondary name tags
        final String referenceTag = "ref";
        final String secondaryTag = "sec";

        // get sourceReference & sourceSecondary MetadataElement
        final MetadataElement referenceMeta = AbstractMetadata.getAbstractedMetadata(sourceProduct);
        /* organize metadata */
        // put sourceReference metadata into the referenceMap
        metaMapPut(referenceTag, referenceMeta, sourceProduct, referenceMap);

        // put sourceSecondary metadata into secondaryMap
        MetadataElement[] secondaryRoot = StackUtils.findSecondaryMetadataRoot(sourceProduct).getElements();
        for (MetadataElement meta : secondaryRoot) {
            if (!meta.getName().equals(AbstractMetadata.ORIGINAL_PRODUCT_METADATA))
                metaMapPut(secondaryTag, meta, sourceProduct, secondaryMap);
        }

    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final HashMap<String, CplxContainer> map) throws Exception {

        // TODO: include polarization flags/checks!
        // pull out band names for this product
        final String[] bandNames = product.getBandNames();
        final int numOfBands = bandNames.length;

        // metadata: construct classes and define bands
        final String date = OperatorUtils.getAcquisitionDate(root);
        final SLCImage meta = new SLCImage(root, product);
        final Orbit orbit = new Orbit(root, ORBIT_DEGREE);

        // TODO: boy this is one ugly construction!?
        // loop through all band names(!) : and pull out only one that matches criteria
        for (String polarisation : polarisations) {
            final String pol = polarisation.isEmpty() ? "" : '_' + polarisation.toUpperCase();
            final String mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT) + pol;

            Band bandReal = null;
            Band bandImag = null;
            for (int i = 0; i < numOfBands; i++) {
                String bandName = bandNames[i];
                if (bandName.contains(tag) && bandName.contains(date)) {
                    if (pol.isEmpty() || bandName.contains(pol)) {
                        final Band band = product.getBandAt(i);
                        if (BandUtilsDoris.isBandReal(band)) {
                            bandReal = band;
                        } else if (BandUtilsDoris.isBandImag(band)) {
                            bandImag = band;
                        }

                        if (bandReal != null && bandImag != null) {
                            break;
                        }
                    }
                }
            }

            try {
                map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void createTargetProduct() throws Exception {

        // construct target product
        targetProduct = new Product(PRODUCT_NAME,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        /// set prefered tile size : should be used only for testing and dev
//        targetProduct.setPreferredTileSize(1024, 128);

        // copy product nodes
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (String key : targetMap.keySet()) {

            final ProductContainer ifg = targetMap.get(key);

            Band targetBandI;
            Band targetBandQ;

            // generate REAL band of reference-sub-product
            targetBandI = targetProduct.addBand(ifg.masterSubProduct.targetBandName_I, OUT_PRODUCT_DATA_TYPE);
            ProductUtils.copyRasterDataNodeProperties(ifg.sourceRef.realBand, targetBandI);

            // generate IMAGINARY band of reference-sub-product
            targetBandQ = targetProduct.addBand(ifg.masterSubProduct.targetBandName_Q, OUT_PRODUCT_DATA_TYPE);
            ProductUtils.copyRasterDataNodeProperties(ifg.sourceRef.imagBand, targetBandQ);

            // generate virtual bands
            if (CREATE_VIRTUAL_BAND) {
                final String tag = ifg.sourceRef.date;
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, "");
                final String suffix = ReaderUtils.createName(targetBandI.getName(), "");
                ReaderUtils.createVirtualPhaseBand(targetProduct, targetBandI, targetBandQ, suffix);
            }

            // generate REAL band of secondary-sub-product
            targetBandI = targetProduct.addBand(ifg.slaveSubProduct.targetBandName_I, OUT_PRODUCT_DATA_TYPE);
            ProductUtils.copyRasterDataNodeProperties(ifg.sourceRef.realBand, targetBandI);

            // generate IMAGINARY band
            targetBandQ = targetProduct.addBand(ifg.slaveSubProduct.targetBandName_Q, OUT_PRODUCT_DATA_TYPE);
            ProductUtils.copyRasterDataNodeProperties(ifg.sourceRef.imagBand, targetBandQ);

            // generate virtual bands
            if (CREATE_VIRTUAL_BAND) {
                final String tag = ifg.sourceSec.date;
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, "");
                final String suffix = ReaderUtils.createName(targetBandI.getName(), "");
                ReaderUtils.createVirtualPhaseBand(targetProduct, targetBandI, targetBandQ, suffix);
            }
        }
    }

    private void checkUserInput() throws OperatorException {

        final InputProductValidator validator = new InputProductValidator(sourceProduct);
        validator.checkIfCoregisteredStack();
        validator.checkIfSLC();

        boolean refSecBandsFound = false;
        for (Band band : sourceProduct.getBands()) {
            if(band.getName().toLowerCase().contains("ref") || band.getName().toLowerCase().contains("sec") ||
               band.getName().toLowerCase().contains("mst") || band.getName().toLowerCase().contains("slv")) {
                refSecBandsFound = true;
            }
        }
        if(!refSecBandsFound) {
            throw new OperatorException("Range spectral filtering should be applied before other insar processing");
        }

        polarisations = OperatorUtils.getPolarisations(sourceProduct);
        if (polarisations.length == 0) {
            polarisations = new String[]{""};
        }
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {
        try {

            int w = targetRectangle.width;
//            int x0 = targetRectangle.x;
//            int y0 = targetRectangle.y;
//            int h = targetRectangle.height;
//            System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            int extraRange = 0;

            if (!MathUtils.isPower2(w)) {

                double nextPow2 = Math.ceil(Math.log(w) / Math.log(2));
                int value = (int) FastMath.pow(2, nextPow2);
                extraRange = value - w;
//                targetRectangle.width = value;
            }

            // target
            Band targetBand;

            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            final Rectangle rect = new Rectangle(targetRectangle);
            rect.width += (TILE_OVERLAP_X + extraRange);
            rect.height += TILE_OVERLAP_Y;
            //System.out.println("x0 = " + rect.x + ", y0 = " + rect.y + ", w = " + rect.width + ", h = " + rect.height);

            boolean doFilterReference = true;
            if (referenceMap.keySet().toArray().length > 1) {
                doFilterReference = false;
            }

            // loop over ifg(product)Container
            for (String ifgTag : targetMap.keySet()) {

                final RangeFilter rangeFilter = new RangeFilter();

                rangeFilter.setAlphaHamming(alphaHamming);
                rangeFilter.setDoWeightCorrelFlag(doWeightCorrel);
                rangeFilter.setOvsFactor(ovsmpFactor);
                rangeFilter.setFftLength(fftLength);
                rangeFilter.setNlMean(nlMean);
                rangeFilter.setSNRthreshold(snrThresh);

                // get ifgContainer from pool
                final ProductContainer ifg = targetMap.get(ifgTag);

                // check out from source
                Tile tileRealReference = getSourceTile(ifg.sourceRef.realBand, rect, border);
                Tile tileImagReference = getSourceTile(ifg.sourceRef.imagBand, rect, border);
                final ComplexDoubleMatrix referenceMatrix = TileUtilsDoris.pullComplexDoubleMatrix(tileRealReference, tileImagReference);

                // check out from source
                Tile tileRealSecondary = getSourceTile(ifg.sourceSec.realBand, rect, border);
                Tile tileImagSecondary = getSourceTile(ifg.sourceSec.imagBand, rect, border);
                final ComplexDoubleMatrix secondaryMatrix = TileUtilsDoris.pullComplexDoubleMatrix(tileRealSecondary, tileImagSecondary);

                rangeFilter.setMetadata(ifg.sourceRef.metaData);
                rangeFilter.setData(referenceMatrix);

                rangeFilter.setMetadata1(ifg.sourceSec.metaData);
                rangeFilter.setData1(secondaryMatrix);

                // compute
                rangeFilter.defineParameters();
                rangeFilter.defineFilter();

                if (doFilterReference) {
                    rangeFilter.applyFilter();
                } else {
                    // apply only on secondary data!
                    rangeFilter.applyFilterSlave();
                }

                /// REFERENCE
                ComplexDoubleMatrix filteredReference;
                if (doFilterReference) {
                    filteredReference = rangeFilter.getData();
                } else {
                    filteredReference = referenceMatrix;
                }

                // commit real() to target
                targetBand = targetProduct.getBand(ifg.masterSubProduct.targetBandName_I);
                tileRealReference = targetTileMap.get(targetBand);
                TileUtilsDoris.pushFloatMatrix(filteredReference.real(), tileRealReference, targetRectangle);

                // commit imag() to target
                targetBand = targetProduct.getBand(ifg.masterSubProduct.targetBandName_Q);
                tileImagReference = targetTileMap.get(targetBand);
                TileUtilsDoris.pushFloatMatrix(filteredReference.imag(), tileImagReference, targetRectangle);

                /// SECONDARY
                final ComplexDoubleMatrix filteredSecondary = rangeFilter.getData1();
                // commit real() to target
                targetBand = targetProduct.getBand(ifg.slaveSubProduct.targetBandName_I);
                tileRealSecondary = targetTileMap.get(targetBand);
                TileUtilsDoris.pushFloatMatrix(filteredSecondary.real(), tileRealSecondary, targetRectangle);

                // commit imag() to target
                targetBand = targetProduct.getBand(ifg.slaveSubProduct.targetBandName_Q);
                tileImagSecondary = targetTileMap.get(targetBand);
                TileUtilsDoris.pushFloatMatrix(filteredSecondary.imag(), tileImagSecondary, targetRectangle);

//                // save imag band of computation : this is somehow too slow?
//                targetBand = targetProduct.getBand(ifg.targetBandName_Q);
//                tileReal = targetTileMap.get(targetBand);
//                targetBand = targetProduct.getBand(ifg.targetBandName_I);
//                tileImag = targetTileMap.get(targetBand);
//                TileUtilsDoris.pushComplexFloatMatrix(cplxIfg, tileReal, tileImag, targetRectangle);

            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
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
            super(RangeFilterOp.class);
        }
    }
}
