package org.jlinda.nest.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.junit.Test;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertNotNull;

public class RangeFilterOpTest {

    private final static OperatorSpi spi = new RangeFilterOp.Spi();

    @Test
    @STTM("SNAP-3848")
    public void testDualPolInput() throws IOException {

        Product srcProduct = createDualPolProduct();
        RangeFilterOp op = (RangeFilterOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertNotNull(trgProduct.getBand("i_IW1_VH_mst_10Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VH_mst_10Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VH_mst_10Jul2018"));
        assertNotNull(trgProduct.getBand("i_IW1_VV_mst_10Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VV_mst_10Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VV_mst_10Jul2018"));

        assertNotNull(trgProduct.getBand("i_IW1_VH_slv1_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VH_slv1_22Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VH_slv1_22Jul2018"));
        assertNotNull(trgProduct.getBand("i_IW1_VV_slv1_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VV_slv1_22Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VV_slv1_22Jul2018"));
    }

    /**
     * Create a dummy dual-pol product
     */
    private static Product createDualPolProduct() throws IOException {

        int size = 10;
        Band targetBandI;
        Band targetBandQ;

        final Product testProduct = TestUtils.createProduct("SLC", size, size);
        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VH_mst_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VH_mst_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VV_mst_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VV_mst_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VH_slv1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VH_slv1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VV_slv1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VV_slv1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        AbstractMetadataIO.Load(testProduct, testProduct.getMetadataRoot(), new File("src/test/resources/dualPolMetadata.xml"));

        final MetadataElement slvRoot = testProduct.getMetadataRoot().getElement(AbstractMetadata.SLAVE_METADATA_ROOT);
        final MetadataElement[] slaveElem = slvRoot.getElements();
        slvRoot.removeElement(slaveElem[1]);
        slvRoot.removeElement(slaveElem[2]);

        return testProduct;
    }
}
