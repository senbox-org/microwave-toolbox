package org.jlinda.nest.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;
import org.junit.Test;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.TestUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RangeFilterOpTest {

    private final static OperatorSpi spi = new RangeFilterOp.Spi();

    /** RangeFilter.sanityChecks rejects an even walking-mean window, so the value set must offer odd values only. */
    @Test
    public void testWalkingMeanValueSetIsOddOnly() throws Exception {
        final org.esa.snap.core.gpf.annotations.Parameter p = parameter("nlMean");
        for (String v : p.valueSet()) {
            assertTrue("nlMean value " + v + " must be odd", Integer.parseInt(v) % 2 == 1);
        }
        assertTrue(Arrays.asList(p.valueSet()).contains(p.defaultValue()));
    }

    @Test
    public void testDeWeightingDefaultIsABoolean() throws Exception {
        assertEquals("false", parameter("doWeightCorrel").defaultValue());
    }

    private static org.esa.snap.core.gpf.annotations.Parameter parameter(final String fieldName) throws Exception {
        final Field f = RangeFilterOp.class.getDeclaredField(fieldName);
        final org.esa.snap.core.gpf.annotations.Parameter p = f.getAnnotation(org.esa.snap.core.gpf.annotations.Parameter.class);
        assertNotNull(p);
        return p;
    }

    @Test
    @STTM("SNAP-3848")
    public void testDualPolInput() throws IOException {

        Product srcProduct = createDualPolProduct();
        RangeFilterOp op = (RangeFilterOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertNotNull(trgProduct.getBand("i_IW1_VH_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VH_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VH_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("i_IW1_VV_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VV_ref_10Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VV_ref_10Jul2018"));

        assertNotNull(trgProduct.getBand("i_IW1_VH_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VH_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VH_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("i_IW1_VV_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("q_IW1_VV_sec1_22Jul2018"));
        assertNotNull(trgProduct.getBand("Intensity_IW1_VV_sec1_22Jul2018"));
    }

    /**
     * Create a dummy dual-pol product
     */
    private static Product createDualPolProduct() throws IOException {

        int size = 10;
        Band targetBandI;
        Band targetBandQ;

        final Product testProduct = TestUtils.createProduct("SLC", size, size);
        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VH_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VH_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VV_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VV_ref_10Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VH_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VH_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        targetBandI = TestUtils.createBand(testProduct, "i_IW1_VV_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.REAL, size, size, true);
        targetBandQ = TestUtils.createBand(testProduct, "q_IW1_VV_sec1_22Jul2018", ProductData.TYPE_FLOAT32,
                Unit.IMAGINARY, size, size, true);
        ReaderUtils.createVirtualIntensityBand(testProduct, targetBandI, targetBandQ, "");

        AbstractMetadataIO.Load(testProduct, testProduct.getMetadataRoot(), new File("src/test/resources/dualPolMetadata.xml"));

        final MetadataElement secRoot = StackUtils.findSecondaryMetadataRoot(testProduct);
        final MetadataElement[] secondaryElem = secRoot.getElements();
        secRoot.removeElement(secondaryElem[1]);
        secRoot.removeElement(secondaryElem[2]);

        return testProduct;
    }
}
