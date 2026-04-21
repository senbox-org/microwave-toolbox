package org.jlinda.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
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
import org.jlinda.core.Window;
import org.jlinda.core.filtering.AzimuthFilter;
import org.jlinda.core.utils.*;

import javax.media.jai.BorderExtender;
import java.awt.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@OperatorMetadata(alias = "AzimuthFilter",
        category = "Radar/Interferometric/Filtering/Spectral Filtering",
        authors = "Petar Marinkovic",
        version = "1.0",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Azimuth Filter")
public class AzimuthFilterOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(valueSet = {"64", "128", "256", "512", "1024", "2048"},
            description = "Length of filtering window",
            defaultValue = "256",
            label = "FFT Window Length")
    private int fftLength = 256;

    @Parameter(valueSet = {"0", "8", "16", "32", "64", "128", "256"},
            description = "Overlap between filtering windows in azimuth direction [lines]",
            defaultValue = "0",
            label = "Azimuth Filter Overlap")
    private int aziFilterOverlap = 0;

    @Parameter(valueSet = {"0.5", "0.75", "0.8", "0.9", "1"},
            description = "Weight for Hamming filter (1 is rectangular window)",
            defaultValue = "0.75",
            label = "Hamming Alpha")
    private float alphaHamming = (float) 0.75;

    // source
    private LinkedHashMap<Integer, CplxContainer> referenceMap = new LinkedHashMap<>();
    private LinkedHashMap<Integer, CplxContainer> secondaryMap = new LinkedHashMap<>();

    // target
    private LinkedHashMap<String, ProductContainer> targetMap = new LinkedHashMap<>();

    private static final int ORBIT_DEGREE = 3; // hardcoded
    private static final boolean CREATE_VIRTUAL_BAND = true;

    private static boolean doFilterReference = true;

    private static final String PRODUCT_NAME = "azimuth_filter";
    private static final String PRODUCT_TAG = "azifilt";

    private static final int OUT_PRODUCT_DATA_TYPE = ProductData.TYPE_FLOAT32;
    private int tileSizeX;
    private int tileSizeY;
    private int productSizeX;
    private int productSizeY;


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

        if (doFilterReference) {

            // this means there is only one secondary! but still do it in the loop
            // loop through references
            for (Integer keyReference : referenceMap.keySet()) {

                CplxContainer reference = referenceMap.get(keyReference);
                String sourceName_I = reference.realBand.getName();
                String sourceName_Q = reference.imagBand.getName();

                String targetName_I = sourceName_I + "_" + PRODUCT_TAG;
                String targetName_Q = sourceName_Q + "_" + PRODUCT_TAG;

                // generate name for product bands
                final String productName = keyReference.toString();

                for (Integer keySecondary : secondaryMap.keySet()) {

                    final CplxContainer secondary = secondaryMap.get(keySecondary);
                    final ProductContainer product = new ProductContainer(productName, reference, secondary, false);

                    product.targetBandName_I = targetName_I;
                    product.targetBandName_Q = targetName_Q;

                    // put ifg-product bands into map
                    targetMap.put(productName, product);

                }

            }
        }

        // loop through secondaries
        for (Integer key : secondaryMap.keySet()) {

            CplxContainer secondary = secondaryMap.get(key);
            String sourceName_I = secondary.realBand.getName();
            String sourceName_Q = secondary.imagBand.getName();

            String targetName_I = sourceName_I + "_" + PRODUCT_TAG;
            String targetName_Q = sourceName_Q + "_" + PRODUCT_TAG;

            // generate name for product bands
            final String productName = key.toString();

            for (Integer keyReference : referenceMap.keySet()) {

                final CplxContainer reference = referenceMap.get(keyReference);
                final ProductContainer product = new ProductContainer(productName, secondary, reference, false);

                product.targetBandName_I = targetName_I;
                product.targetBandName_Q = targetName_Q;

                // put ifg-product bands into map
                targetMap.put(productName, product);

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

        // check how many secondaries
        if (secondaryMap.keySet().toArray().length > 1) {
            doFilterReference = false;
        }

    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final HashMap<Integer, CplxContainer> map) throws Exception {

        // TODO: include polarization flags/checks!
        // pull out band names for this product
        final String[] bandNames = product.getBandNames();
        final int numOfBands = bandNames.length;

        // map key: ORBIT NUMBER
        int mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT);

        // metadata: construct classes and define bands
        final String date = OperatorUtils.getAcquisitionDate(root);
        final SLCImage meta = new SLCImage(root, product);
        final Orbit orbit = new Orbit(root, ORBIT_DEGREE);
        Band bandReal = null;
        Band bandImag = null;

        // TODO: boy this is one ugly construction!?
        // loop through all band names(!) : and pull out only one that matches criteria
        for (int i = 0; i < numOfBands; i++) {
            String bandName = bandNames[i];
            if (bandName.contains(tag) && bandName.contains(date)) {
                final Band band = product.getBandAt(i);
                if (BandUtilsDoris.isBandReal(band)) {
                    bandReal = band;
                } else if (BandUtilsDoris.isBandImag(band)) {
                    bandImag = band;
                }
            }
        }

        try {
            map.put(mapKey, new CplxContainer(date, meta, orbit, bandReal, bandImag));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createTargetProduct() throws Exception {

        // construct target product
        targetProduct = new Product(PRODUCT_NAME,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        /// set prefered tile size : should be used only for testing and dev
        targetProduct.setPreferredTileSize(128,128);
        tileSizeX = targetProduct.getPreferredTileSize().width;
        tileSizeY = targetProduct.getPreferredTileSize().height;
//        tileSizeX = sourceProduct.getPreferredTileSize().width;
//        tileSizeY = sourceProduct.getPreferredTileSize().height;

        productSizeX = sourceProduct.getSceneRasterWidth();
        productSizeY = sourceProduct.getSceneRasterHeight();

        // copy product nodes
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (String key : targetMap.keySet()) {

            final ProductContainer product = targetMap.get(key);

            Band targetBandI;
            Band targetBandQ;

            // generate REAL band of reference-sub-product
            targetBandI = targetProduct.addBand(product.targetBandName_I, OUT_PRODUCT_DATA_TYPE);
            ProductUtils.copyRasterDataNodeProperties(product.sourceRef.realBand, targetBandI);

            // generate IMAGINARY band of reference-sub-product
            targetBandQ = targetProduct.addBand(product.targetBandName_Q, OUT_PRODUCT_DATA_TYPE);
            ProductUtils.copyRasterDataNodeProperties(product.sourceRef.imagBand, targetBandQ);

            // generate virtual bands
            if (CREATE_VIRTUAL_BAND) {
                final String tag = product.sourceRef.date;
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetBandI, targetBandQ, ("_" + tag));
                ReaderUtils.createVirtualPhaseBand(targetProduct, targetBandI, targetBandQ, ("_" + tag));
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
            throw new OperatorException("Azimuth spectral filtering should be applied before other insar processing");
        }
    }

    private void updateTargetProductMetadata() {
        // update metadata of target product for the estimated polynomial
    }

    private void updateTargetProductGeocoding() {
        // update metadata of target product for the estimated polynomial
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
            int h = targetRectangle.height;
            int x0 = targetRectangle.x;
            int y0 = targetRectangle.y;
//            System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            boolean rectAdjusted = false;

            if (w < tileSizeX) {
                x0 = productSizeX - tileSizeX;
                w = tileSizeX;
                rectAdjusted = true;
            }
            if (h < tileSizeY) {
                y0 = productSizeY - tileSizeY;
                h = tileSizeY;
                rectAdjusted = true;
            }
//            final Rectangle rect = new Rectangle(targetRectangle);
            Rectangle rect = new Rectangle(x0, y0, w, h);
//            System.out.println("x0 = " + rect.x + ", y0 = " + rect.y + ", w = " + rect.width + ", h = " + rect.height);
//            System.out.println("------");

            // target
            Band targetBand;

            final BorderExtender border = BorderExtender.createInstance(BorderExtender.BORDER_ZERO);

            // loop over ifg(product)Container : both reference and secondary defined in container
            for (ProductContainer product : targetMap.values()) {

                // check out from source
                Tile tileRealReference = getSourceTile(product.sourceRef.realBand, rect, border);
                Tile tileImagReference = getSourceTile(product.sourceRef.imagBand, rect, border);
                final ComplexDoubleMatrix dataReference = TileUtilsDoris.pullComplexDoubleMatrix(tileRealReference, tileImagReference);

                // construct azimuthfilter
                final AzimuthFilter azimuthReference = new AzimuthFilter();

                // set filtering parameters
                azimuthReference.setHammingAlpha(alphaHamming);
                azimuthReference.setMetadata(product.sourceRef.metaData);
                azimuthReference.setMetadata1(product.sourceSec.metaData);
                // TODO: variable constant hard-coded, further testing needed
                azimuthReference.setVariableFilter(false); // hardcoded to const filtering!
                azimuthReference.setTile(new Window(rect));

                // set data for filtering
                azimuthReference.setData(dataReference);

                // define parameters and filter
                azimuthReference.defineParameters();
                azimuthReference.defineFilter();
                azimuthReference.applyFilter();

                ComplexDoubleMatrix filteredData;
                if (rectAdjusted) {

                    final int offsetX = rect.width - targetRectangle.width;
                    final int offsetY = rect.height - targetRectangle.height;

                    filteredData = new ComplexDoubleMatrix(targetRectangle.height, targetRectangle.width);

                    LinearAlgebraUtils.setdata(filteredData,
                            new Window(0, (long) (targetRectangle.height - 1),
                                    0, (long) (targetRectangle.width - 1)),
                            azimuthReference.getData(),
                            new Window(offsetY, rect.height - 1, offsetX, rect.width - 1));

                } else {
                    filteredData = azimuthReference.getData();
                }

                // get data from filter
                // commit real() to target
                targetBand = targetProduct.getBand(product.targetBandName_I);
                tileRealReference = targetTileMap.get(targetBand);
                TileUtilsDoris.pushFloatMatrix(filteredData.real(), tileRealReference, targetRectangle);

                // commit imag() to target
                targetBand = targetProduct.getBand(product.targetBandName_Q);
                tileImagReference = targetTileMap.get(targetBand);
                TileUtilsDoris.pushFloatMatrix(filteredData.imag(), tileImagReference, targetRectangle);

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
            super(AzimuthFilterOp.class);
        }
    }
}
