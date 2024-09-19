package eu.esa.sar.insar.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.metadata.AbstractMetadataIO;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static eu.esa.sar.insar.gpf.MultiMasterInSAROp.COHERENCE_BAND_NAME_PREFIX;
import static eu.esa.sar.insar.gpf.MultiMasterInSAROp.IFG_BAND_NAME_TAG;
import static eu.esa.sar.insar.gpf.MultiMasterInSAROp.INCIDENCE_ANGLE_BAND_NAME;
import static eu.esa.sar.insar.gpf.MultiMasterInSAROp.LAT_BAND_NAME;
import static eu.esa.sar.insar.gpf.MultiMasterInSAROp.LON_BAND_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

@STTM("SNAP-3575")
public class TestMultiReferenceInSAR {

    private Product sourceProduct, dualPolSrcProduct;

    @Before
    public void setUp() throws Exception {
        sourceProduct = createStackProduct();
        dualPolSrcProduct = createDualPolStackProduct();
    }

    @Test
    public void test_initialize_method_successfully_initializes_targetProduct() {
        MultiMasterInSAROp op = new MultiMasterInSAROp();
        op.setSourceProduct(sourceProduct);
        op.setParameter("includeWavenumber", false);
        op.setParameter("includeIncidenceAngle", false);
        op.setParameter("includeLatLon", false);

        Product targetProduct = op.getTargetProduct();
        assertNotNull(targetProduct);
        assertEquals(sourceProduct.getName() + "_mmifg", targetProduct.getName());
        assertEquals(sourceProduct.getProductType(), targetProduct.getProductType());
        assertEquals(sourceProduct.getSceneRasterWidth(), targetProduct.getSceneRasterWidth());
        assertEquals(sourceProduct.getSceneRasterHeight(), targetProduct.getSceneRasterHeight());
    }

    @Test
    public void test_createTargetProduct_method_copies_necessary_bands_from_sourceProduct_to_targetProduct() {
        MultiMasterInSAROp op = new MultiMasterInSAROp();
        op.setSourceProduct(sourceProduct);
        Product targetProduct = op.getTargetProduct();

        assertNotNull(targetProduct.getBand("elevation"));
        assertNotNull(targetProduct.getBand(INCIDENCE_ANGLE_BAND_NAME));
        assertNotNull(targetProduct.getBand(LAT_BAND_NAME));
        assertNotNull(targetProduct.getBand(LON_BAND_NAME));

        final String swath = "IW1";
        final String pol = "VV";
        for (String[] parsedPair : op.parsedPairs) {
            String ifgBandNameI = String.join("_", "i", IFG_BAND_NAME_TAG, swath, pol, String.join("_", parsedPair));
            String ifgBandNameQ = String.join("_", "q", IFG_BAND_NAME_TAG, swath, pol, String.join("_", parsedPair));
            assertNotNull(targetProduct.getBand(ifgBandNameI));
            assertNotNull(targetProduct.getBand(ifgBandNameQ));
            String coherenceBandName = String.join("_", COHERENCE_BAND_NAME_PREFIX, swath, pol, String.join("_", parsedPair));
            assertNotNull(targetProduct.getBand(coherenceBandName));
        }
    }

    @Test
    public void test_pairs_parameter_is_null_or_empty_and_default_pairs_are_generated() throws Exception {
        MultiMasterInSAROp op = new MultiMasterInSAROp();
        op.setSourceProduct(sourceProduct);
        Product targetProduct = op.getTargetProduct();
        List<String> defaultPairs = op.getPairs();
        assertNotNull(defaultPairs);
        assertFalse(defaultPairs.isEmpty());
    }

    @Test
    public void test_sourceProduct_does_not_contain_band_with_DEM_BAND_NAME_PREFIX() throws Exception {
        Product sourceProductWithoutElevationBand = createStackProduct();
        sourceProductWithoutElevationBand.removeBand(sourceProductWithoutElevationBand.getBand("elevation"));

        MultiMasterInSAROp op = new MultiMasterInSAROp();
        op.setSourceProduct(sourceProductWithoutElevationBand);
        assertThrows(OperatorException.class, () -> { op.initialize(); });
    }

    @Test
    public void test_targetProduct_is_missing_required_bands() throws Exception {
        Product sourceProductWithoutRequiredBands = createStackProduct();
        sourceProductWithoutRequiredBands.removeBand(sourceProductWithoutRequiredBands.getBand("i_VV_mst_26Apr2008"));

        MultiMasterInSAROp op = new MultiMasterInSAROp();
        op.setSourceProduct(sourceProductWithoutRequiredBands);
        assertThrows(OperatorException.class, () -> { op.initialize(); });
    }

    @Test
    @STTM("SNAP-3836")
    public void test_dual_pol_input() {
        MultiMasterInSAROp op = new MultiMasterInSAROp();
        op.setSourceProduct(dualPolSrcProduct);
        op.setParameter("includeWavenumber", false);
        Product targetProduct = op.getTargetProduct();

        assertNotNull(targetProduct.getBand("elevation"));

        final String swath = "IW1";
        final String[] polarisations = new String[]{"VV", "VH"};
        for (String[] parsedPair : op.parsedPairs) {
            for (String pol : polarisations) {
                String ifgBandNameI = String.join("_", "i", IFG_BAND_NAME_TAG, swath, pol, String.join("_", parsedPair));
                String ifgBandNameQ = String.join("_", "q", IFG_BAND_NAME_TAG, swath, pol, String.join("_", parsedPair));
                assertNotNull(targetProduct.getBand(ifgBandNameI));
                assertNotNull(targetProduct.getBand(ifgBandNameQ));
                String coherenceBandName = String.join("_", COHERENCE_BAND_NAME_PREFIX, swath, pol, String.join("_", parsedPair));
                assertNotNull(targetProduct.getBand(coherenceBandName));
            }
        }
    }

    private Product createStackProduct() throws IOException {
        int size = 10;
        Product srcProduct = TestUtils.createProduct("stackProduct", size, size);
        TestUtils.createBand(srcProduct, "i_VV_mst_26Apr2008", size, size);
        TestUtils.createBand(srcProduct, "q_VV_mst_26Apr2008", size, size);
        TestUtils.createBand(srcProduct, "i_VV_slv1_25Aug2007", size, size);
        TestUtils.createBand(srcProduct, "q_VV_slv1_25Aug2007", size, size);
        TestUtils.createBand(srcProduct, "i_VV_slv2_23Dec2006", size, size);
        TestUtils.createBand(srcProduct, "q_VV_slv2_23Dec2006", size, size);
        TestUtils.createBand(srcProduct, "elevation", size, size);

        AbstractMetadataIO.Load(srcProduct, srcProduct.getMetadataRoot(), new File("src/test/resources/metadata.xml"));

        return srcProduct;
    }

    private Product createDualPolStackProduct() throws IOException {
        int size = 10;
        Product srcProduct = TestUtils.createProduct("stackProduct", size, size);
        TestUtils.createBand(srcProduct, "i_VH_mst_26Apr2008", size, size);
        TestUtils.createBand(srcProduct, "q_VH_mst_26Apr2008", size, size);
        TestUtils.createBand(srcProduct, "i_VV_mst_26Apr2008", size, size);
        TestUtils.createBand(srcProduct, "q_VV_mst_26Apr2008", size, size);
        TestUtils.createBand(srcProduct, "i_VH_slv1_25Aug2007", size, size);
        TestUtils.createBand(srcProduct, "q_VH_slv1_25Aug2007", size, size);
        TestUtils.createBand(srcProduct, "i_VV_slv1_25Aug2007", size, size);
        TestUtils.createBand(srcProduct, "q_VV_slv1_25Aug2007", size, size);
        TestUtils.createBand(srcProduct, "i_VH_slv2_23Dec2006", size, size);
        TestUtils.createBand(srcProduct, "q_VH_slv2_23Dec2006", size, size);
        TestUtils.createBand(srcProduct, "i_VV_slv2_23Dec2006", size, size);
        TestUtils.createBand(srcProduct, "q_VV_slv2_23Dec2006", size, size);
        TestUtils.createBand(srcProduct, "elevation", size, size);

        AbstractMetadataIO.Load(srcProduct, srcProduct.getMetadataRoot(), new File("src/test/resources/metadata.xml"));

        return srcProduct;
    }
}
