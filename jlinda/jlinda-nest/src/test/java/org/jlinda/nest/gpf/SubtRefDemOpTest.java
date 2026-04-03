package org.jlinda.nest.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.junit.Test;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SubtRefDemOpTest {

    private final static OperatorSpi spi = new SubtRefDemOp.Spi();

    @Test
    @STTM("SNAP-3827")
    public void testDualPolInput() throws IOException {

        Product srcProduct = createDualPolProduct();

        SubtRefDemOp op = (SubtRefDemOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        op.setParameter("orbitDegree", 3);
        op.setParameter("demName", "SRTM 3Sec");
        op.setParameter("tileExtensionPercent", "20");
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertNotNull(trgProduct.getBand("i_ifg_VV_10Jul2018_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_ifg_VV_10Jul2018_22Jul2018"));
        assertNotNull(trgProduct.getBand("coh_IW1_VV_10Jul2018_22Jul2018"));
        assertNotNull(trgProduct.getBand("i_ifg_VH_10Jul2018_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_ifg_VH_10Jul2018_22Jul2018"));
        assertNotNull(trgProduct.getBand("coh_IW1_VH_10Jul2018_22Jul2018"));

        assertNotNull(trgProduct.getBand("i_ifg_VH_10Jul2018_27Aug2018"));
        assertNotNull(trgProduct.getBand("q_ifg_VH_10Jul2018_27Aug2018"));
        assertNotNull(trgProduct.getBand("coh_IW1_VH_10Jul2018_27Aug2018"));
        assertNotNull(trgProduct.getBand("i_ifg_VV_10Jul2018_27Aug2018"));
        assertNotNull(trgProduct.getBand("q_ifg_VV_10Jul2018_27Aug2018"));
        assertNotNull(trgProduct.getBand("coh_IW1_VV_10Jul2018_27Aug2018"));

        assertNotNull(trgProduct.getBand("i_ifg_VV_10Jul2018_20Sep2018"));
        assertNotNull(trgProduct.getBand("q_ifg_VV_10Jul2018_20Sep2018"));
        assertNotNull(trgProduct.getBand("coh_IW1_VV_10Jul2018_20Sep2018"));
        assertNotNull(trgProduct.getBand("i_ifg_VH_10Jul2018_20Sep2018"));
        assertNotNull(trgProduct.getBand("q_ifg_VH_10Jul2018_20Sep2018"));
        assertNotNull(trgProduct.getBand("coh_IW1_VH_10Jul2018_20Sep2018"));
    }

    @Test
    @STTM("SNAP-3829")
    public void testMultiReferenceInput() throws IOException {

        Product srcProduct = createMultiRefProduct();

        SubtRefDemOp op = (SubtRefDemOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        op.setParameter("orbitDegree", 3);
        op.setParameter("demName", "SRTM 3Sec");
        op.setParameter("tileExtensionPercent", "20");
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertNotNull(trgProduct.getBand("i_ifg_VV_19Oct2016_12Nov2016"));
        assertNotNull(trgProduct.getBand("q_ifg_VV_19Oct2016_12Nov2016"));
        assertNotNull(trgProduct.getBand("coh_IW1_VV_19Oct2016_12Nov2016"));
        assertNotNull(trgProduct.getBand("i_ifg_VV_19Oct2016_12Nov2016"));
        assertNotNull(trgProduct.getBand("q_ifg_VV_19Oct2016_12Nov2016"));
        assertNotNull(trgProduct.getBand("coh_IW1_VV_19Oct2016_12Nov2016"));

        assertNotNull(trgProduct.getBand("i_ifg_VV_12Nov2016_06Dec2016"));
        assertNotNull(trgProduct.getBand("q_ifg_VV_12Nov2016_06Dec2016"));
        assertNotNull(trgProduct.getBand("coh_IW1_VV_12Nov2016_06Dec2016"));
        assertNotNull(trgProduct.getBand("i_ifg_VH_12Nov2016_06Dec2016"));
        assertNotNull(trgProduct.getBand("q_ifg_VH_12Nov2016_06Dec2016"));
        assertNotNull(trgProduct.getBand("coh_IW1_VH_12Nov2016_06Dec2016"));
    }

    /**
     * Create a dummy dual-pol product
     */
    private static Product createDualPolProduct() throws IOException {

        int size = 10;
        final Product testProduct = TestUtils.createProduct("SLC", size, size);
        TestUtils.createBand(testProduct, "i_ifg_IW1_VV_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VV_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VV_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);
        TestUtils.createBand(testProduct, "i_ifg_IW1_VH_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VH_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VH_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);

        TestUtils.createBand(testProduct, "i_ifg_IW1_VV_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VV_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VV_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);
        TestUtils.createBand(testProduct, "i_ifg_IW1_VH_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VH_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VH_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);

        TestUtils.createBand(testProduct, "i_ifg_IW1_VV_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VV_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VV_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);
        TestUtils.createBand(testProduct, "i_ifg_IW1_VH_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VH_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VH_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);

        AbstractMetadataIO.Load(testProduct, testProduct.getMetadataRoot(), new File("src/test/resources/dualPolMetadata.xml"));

        setGeocoding(testProduct);

        return testProduct;
    }

    private static void setGeocoding(final Product testProduct) {
        final TiePointGrid latGrid = new TiePointGrid(OperatorUtils.TPG_LATITUDE, 2, 2, 0.0f, 0.0f,
                10, 10,
                new float[]{30.79669952392578f, 30.947656631469727f, 31.480817794799805f, 31.631608963012695f});

        final TiePointGrid lonGrid = new TiePointGrid(OperatorUtils.TPG_LONGITUDE, 2, 2, 0.0f, 0.0f,
                10, 10,
                new float[]{34.09899139404297f, 35.03065872192383f, 33.944305419921875f, 34.88310241699219f},
                TiePointGrid.DISCONT_AT_360);

        for (TiePointGrid tpg : testProduct.getTiePointGrids()) {
            testProduct.removeTiePointGrid(tpg);
        }

        final TiePointGeoCoding tpGeoCoding = new TiePointGeoCoding(latGrid, lonGrid);
        testProduct.addTiePointGrid(latGrid);
        testProduct.addTiePointGrid(lonGrid);
        testProduct.setSceneGeoCoding(tpGeoCoding);
    }

    private Product createMultiRefProduct() throws IOException {

        int size = 10;
        final Product testProduct = TestUtils.createProduct("SLC", size, size);

        TestUtils.createBand(testProduct, "i_ifg_IW1_VV_19Oct2016_12Nov2016", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VV_19Oct2016_12Nov2016", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VV_19Oct2016_12Nov2016", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);
        TestUtils.createBand(testProduct, "i_ifg_IW1_VH_19Oct2016_12Nov2016", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VH_19Oct2016_12Nov2016", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VH_19Oct2016_12Nov2016", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);

        TestUtils.createBand(testProduct, "i_ifg_IW1_VV_12Nov2016_06Dec2016", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VV_12Nov2016_06Dec2016", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VV_12Nov2016_06Dec2016", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);
        TestUtils.createBand(testProduct, "i_ifg_IW1_VH_12Nov2016_06Dec2016", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        TestUtils.createBand(testProduct, "q_ifg_IW1_VH_12Nov2016_06Dec2016", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        TestUtils.createBand(testProduct, "coh_IW1_VH_12Nov2016_06Dec2016", ProductData.TYPE_FLOAT32,
                Unit.COHERENCE, size, size, true);

        AbstractMetadataIO.Load(testProduct, testProduct.getMetadataRoot(), new File("src/test/resources/multiRefMetadata.xml"));

        return testProduct;
    }
}
