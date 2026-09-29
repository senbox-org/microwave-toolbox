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
import org.esa.snap.core.gpf.annotations.SourceProducts;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.geocode.DInSAR;
import org.jlinda.core.utils.BandUtilsDoris;
import org.jlinda.core.utils.CplxContainer;
import org.jlinda.core.utils.ProductContainer;
import org.jlinda.core.utils.TileUtilsDoris;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/*
 See DInSAR class in jlinda-core for implementation details
 ...core class is not being used because of threading issues
 ...core class will be integrated soon in the operator

 Known issues: polyFit will trigger warning that maxError is larger then allowed/expected,
    see DInSAR.java for more info and solution for this problem. It is on TODO list.
*/

// TODO: support for multiple DEFO interferometric pairs in sourceProduct

@OperatorMetadata(alias = "Three-passDInSAR",
        category = "Radar/Interferometric/Products",
        authors = "Petar Marinkovic",
        version = "1.0",
        copyright = "Copyright (C) 2013 by PPO.labs",
        description = "Differential Interferometry")
public class DInSAROp extends Operator {

    @SourceProducts(description = "Source products: InSAR DEFO pair product, and InSAR (unwrapped) TOPO pair product")
    private Product[] sourceProducts;

    @TargetProduct
    private Product targetProduct;

    @Parameter(interval = "(1, 10]",
            description = "Degree of orbit interpolation polynomial",
            defaultValue = "3",
            label = "Orbit Interpolation Degree")
    private int orbitDegree = 3;


    // source maps
    private HashMap<Integer, CplxContainer> referenceMap = new HashMap<>();
    private HashMap<Integer, CplxContainer> secondaryDefoMap = new HashMap<>();

    private HashMap<Integer, CplxContainer> referenceTopoMap = new HashMap<>();
    private HashMap<Integer, CplxContainer> secondaryTopoMap = new HashMap<>();

    // target maps
    private HashMap<String, ProductContainer> targetMap = new HashMap<>();

    // dinsar core classes map
    private DInSAR dinsar;


    // operator tags
    private static final boolean CREATE_VIRTUAL_BAND = true;
    public static final String PRODUCT_TAG = "dinsar";

    private int sourceImageWidth;
    private int sourceImageHeight;

    private CplxContainer reference;
    private CplxContainer secondaryDefo;
    private CplxContainer secondaryTopo;

    private Product topoProduct = null;
    private Product defoProduct = null;

    private static final String PRODUCT_SUFFIX = "_DInSAR";

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

            if (sourceProducts.length != 2) {
                throw new OperatorException("DInSAROp: requires 2 InSAR products, 'TOPO pair' and 'DEFO pairs' products.");
            }

            // work out which is which product: loop through source product and check which product has a 'unwrapped phase' band
            sortOutSourceProducts(); // -> declares topoProduct and defoProduct

            constructSourceMetadata();
            constructTargetMetadata();
            createTargetProduct();
            getSourceImageDimension();

            // compute topo/defo pair ratio polynomial needed for scaling of topoPair
            baselineRatio();

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    private void sortOutSourceProducts() {

        for (Product product : sourceProducts) {
            if (productHasABSPhase(product)) {
                topoProduct = product;
            } else {
                defoProduct = product;
            }
        }
        if (topoProduct == null || defoProduct == null) {
            throw new OperatorException("DInSAROp requires one product with Unwrapped Phase and one with Wrapped Phase");
        }
    }

    private boolean productHasABSPhase(Product product) {
        for (Band band : product.getBands()) {
            if (band.getUnit().contains(Unit.ABS_PHASE)) {
                return true;
            }
        }
        return false;
    }

    private void baselineRatio() throws Exception {
        int[] secondaryKeys = new int[2];
        int referenceKey = 0;

        for (Integer keyReference : referenceMap.keySet()) {
            referenceKey = keyReference;
            int i = 0;
            for (Integer keySecondary : secondaryDefoMap.keySet()) {
                secondaryKeys[i++] = keySecondary;
            }
        }

        reference = referenceMap.get(referenceKey);
        secondaryTopo = secondaryTopoMap.get(secondaryKeys[0]);
        secondaryDefo = secondaryDefoMap.get(secondaryKeys[0]);

        dinsar = new DInSAR(reference.metaData, reference.orbit, secondaryDefo.metaData, secondaryDefo.orbit, secondaryTopo.metaData, secondaryTopo.orbit);
        dinsar.setDataWindow(new Window(0, sourceImageHeight, 0, sourceImageWidth));
        dinsar.computeBperpRatios();

    }

    private void getSourceImageDimension() {
        sourceImageWidth = defoProduct.getSceneRasterWidth();
        sourceImageHeight = defoProduct.getSceneRasterHeight();
    }

    private void createTargetProduct() {

        // construct target product
        targetProduct = new Product(defoProduct.getName() + PRODUCT_SUFFIX,
                defoProduct.getProductType(),
                defoProduct.getSceneRasterWidth(),
                defoProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(defoProduct, targetProduct);

        for (final Band band : targetProduct.getBands()) {
            targetProduct.removeBand(band);
        }

        for (String key : targetMap.keySet()) {

            String targetBandName_I = targetMap.get(key).targetBandName_I;
            String targetBandName_Q = targetMap.get(key).targetBandName_Q;
            targetProduct.addBand(targetBandName_I, ProductData.TYPE_FLOAT64);
            targetProduct.addBand(targetBandName_Q, ProductData.TYPE_FLOAT64);

            final String tag0 = targetMap.get(key).sourceRef.date;
            final String tag1 = targetMap.get(key).sourceSec.date;
            if (CREATE_VIRTUAL_BAND) {
                String countStr = "_" + PRODUCT_TAG + "_" + tag0 + "_" + tag1;
                ReaderUtils.createVirtualIntensityBand(targetProduct, targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);
                ReaderUtils.createVirtualPhaseBand(targetProduct, targetProduct.getBand(targetBandName_I), targetProduct.getBand(targetBandName_Q), countStr);
            }

        }

    }

    private void constructTargetMetadata() {

        for (Integer keyReference : referenceMap.keySet()) {

            CplxContainer reference = referenceMap.get(keyReference);

            int counter = 0;

            for (Integer keySecondary : secondaryDefoMap.keySet()) {

                if (counter == 0) {
                    // generate name for product bands
                    final String productName = keyReference.toString() + "_" + keySecondary.toString();

                    final CplxContainer secondary = secondaryDefoMap.get(keySecondary);
                    final ProductContainer product = new ProductContainer(productName, reference, secondary, true);

                    product.targetBandName_I = "i_" + PRODUCT_TAG + "_" + reference.date + "_" + secondary.date;
                    product.targetBandName_Q = "q_" + PRODUCT_TAG + "_" + reference.date + "_" + secondary.date;

                    // put ifg-product bands into map
                    targetMap.put(productName, product);

                    counter++;

                }

            }
        }
    }


    private void constructSourceMetadata() throws Exception {

        /** --- DEFO PRODUCT -----*/
        // define sourceReference/sourceSecondary name tags
        String referenceTag;
        String secondaryTag;
        MetadataElement referenceMeta;
        MetadataElement[] secondaryRoot;

        referenceTag = "ifg";
        secondaryTag = "dummy";

        // get sourceReference & sourceSecondary MetadataElement
        referenceMeta = AbstractMetadata.getAbstractedMetadata(defoProduct);

        /* organize metadata */

        // put sourceReference metadata into the referenceMap
        metaMapPut(referenceTag, referenceMeta, defoProduct, referenceMap);

        // put sourceSecondary metadata into secondaryDefoMap
        secondaryRoot = StackUtils.findSecondaryMetadataRoot(defoProduct).getElements();
        for (MetadataElement meta : secondaryRoot) {
            if (!meta.getName().equals(AbstractMetadata.ORIGINAL_PRODUCT_METADATA))
                metaMapPut(secondaryTag, meta, defoProduct, secondaryDefoMap);
        }

        /** --- TOPO PRODUCT -----*/

        // get sourceReference & sourceSecondary MetadataElement
        referenceMeta = AbstractMetadata.getAbstractedMetadata(topoProduct);

        /* organize metadata */

        // put sourceReference metadata into the referenceMap
        metaMapPut(referenceTag, referenceMeta, topoProduct, referenceTopoMap);

        // put sourceSecondary metadata into secondaryTopoMap
        secondaryRoot = StackUtils.findSecondaryMetadataRoot(topoProduct).getElements();
        for (MetadataElement meta : secondaryRoot) {
            metaMapPut(secondaryTag, meta, topoProduct, secondaryTopoMap);
        }

    }

    private void metaMapPut(final String tag,
                            final MetadataElement root,
                            final Product product,
                            final HashMap<Integer, CplxContainer> map) throws Exception {

        // pull out band names for this product
        final String[] bandNames = product.getBandNames();
        final int numOfBands = bandNames.length;

        // map key: ORBIT NUMBER
        int mapKey = root.getAttributeInt(AbstractMetadata.ABS_ORBIT);

        // metadata: construct classes and define bands
        final String date = OperatorUtils.getAcquisitionDate(root);
        final SLCImage meta = new SLCImage(root, product);
        final Orbit orbit = new Orbit(root, orbitDegree);

        // TODO: mlook factores are hard-coded for now
        meta.setMlAz(1);
        meta.setMlRg(1);

        Band bandReal = null;
        Band bandImag = null;

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

    private void checkUserInput() throws OperatorException {
        final InputProductValidator validator = new InputProductValidator(defoProduct);
        validator.checkIfCoregisteredStack();
        validator.checkIfSLC();
    }

    @Override
    public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {
        try {

            int y0 = targetRectangle.y;
            int yN = y0 + targetRectangle.height - 1;
            int x0 = targetRectangle.x;
            int xN = targetRectangle.x + targetRectangle.width - 1;
            final Window tileWindow = new Window(y0, yN, x0, xN);

            Band targetBand_I;
            Band targetBand_Q;
            ComplexDoubleMatrix complexDefoPair = null;
            DoubleMatrix doubleTopoPair = null;

            ProductContainer product = null;
            for (String ifgKey : targetMap.keySet()) {

                product = targetMap.get(ifgKey);

                /// check out results from source ///
                Tile tileReal = getSourceTile(product.sourceRef.realBand, targetRectangle);
                Tile tileImag = getSourceTile(product.sourceRef.imagBand, targetRectangle);
                complexDefoPair = TileUtilsDoris.pullComplexDoubleMatrix(tileReal, tileImag);

            }

            // always pull from topoProduct
            for (Band band : topoProduct.getBands()) {
                if (band.getName().contains("unw") || band.getUnit().contains(Unit.ABS_PHASE)) {
                    /// check out results from source ///
                    Tile tileReal = getSourceTile(band, targetRectangle);
                    doubleTopoPair = TileUtilsDoris.pullDoubleMatrix(tileReal);
                }
            }

            dinsar.applyDInSAR(tileWindow, complexDefoPair, doubleTopoPair);

            /// commit complexDefoPair back to target ///
            targetBand_I = targetProduct.getBand(product.targetBandName_I);
            Tile tileOutReal = targetTileMap.get(targetBand_I);
            TileUtilsDoris.pushDoubleMatrix(complexDefoPair.real(), tileOutReal, targetRectangle);

            targetBand_Q = targetProduct.getBand(product.targetBandName_Q);
            Tile tileOutImag = targetTileMap.get(targetBand_Q);
            TileUtilsDoris.pushDoubleMatrix(complexDefoPair.imag(), tileOutImag, targetRectangle);

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);

        }
    }

   public static class Spi extends OperatorSpi {

        public Spi() {
            super(DInSAROp.class);
        }
    }

}
