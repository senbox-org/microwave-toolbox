package org.jlinda.nest.gpf;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.junit.Test;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.util.TestUtils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SubtRefDemOpTest {

    private final static OperatorSpi spi = new SubtRefDemOp.Spi();

    @Test
    @STTM("SNAP-3827")
    public void testMultiPol() {

        Product srcProduct = createOneTestProduct();

        SubtRefDemOp op = (SubtRefDemOp) spi.createOperator();
        op.setSourceProduct(srcProduct);
        op.setParameter("orbitDegree", 3);
        op.setParameter("demName", "SRTM 3Sec");
        op.setParameter("tileExtensionPercent", "20");
        Product trgProduct = op.getTargetProduct();

        assertNotNull(trgProduct);
        assertTrue(trgProduct.containsBand("i_ifg_VV_10Jul2018_22Jul2018"));
        assertTrue(trgProduct.containsBand("q_ifg_VV_10Jul2018_22Jul2018"));
        assertTrue(trgProduct.containsBand("coh_IW1_VV_10Jul2018_22Jul2018"));
        assertTrue(trgProduct.containsBand("i_ifg_VH_10Jul2018_22Jul2018"));
        assertTrue(trgProduct.containsBand("q_ifg_VH_10Jul2018_22Jul2018"));
        assertTrue(trgProduct.containsBand("coh_IW1_VH_10Jul2018_22Jul2018"));

        assertTrue(trgProduct.containsBand("i_ifg_VH_10Jul2018_27Aug2018"));
        assertTrue(trgProduct.containsBand("q_ifg_VH_10Jul2018_27Aug2018"));
        assertTrue(trgProduct.containsBand("coh_IW1_VH_10Jul2018_27Aug2018"));
        assertTrue(trgProduct.containsBand("i_ifg_VV_10Jul2018_27Aug2018"));
        assertTrue(trgProduct.containsBand("q_ifg_VV_10Jul2018_27Aug2018"));
        assertTrue(trgProduct.containsBand("coh_IW1_VV_10Jul2018_27Aug2018"));

        assertTrue(trgProduct.containsBand("i_ifg_VV_10Jul2018_20Sep2018"));
        assertTrue(trgProduct.containsBand("q_ifg_VV_10Jul2018_20Sep2018"));
        assertTrue(trgProduct.containsBand("coh_IW1_VV_10Jul2018_20Sep2018"));
        assertTrue(trgProduct.containsBand("i_ifg_VH_10Jul2018_20Sep2018"));
        assertTrue(trgProduct.containsBand("q_ifg_VH_10Jul2018_20Sep2018"));
        assertTrue(trgProduct.containsBand("coh_IW1_VH_10Jul2018_20Sep2018"));
    }

    /**
     * Creates a test product
     */
    private static Product createOneTestProduct() {

        final Product testProduct = TestUtils.createProduct("SLC", 10, 10);

        addBands(testProduct);

        addMetadata(testProduct);

        setGeocoding(testProduct);

        return testProduct;
    }

    private static void addBands(final Product testProduct) {
        final Band i_vv_slv1 = testProduct.addBand("i_ifg_IW1_VV_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32);
        i_vv_slv1.setUnit("real");
        final Band q_vv_slv1 = testProduct.addBand("q_ifg_IW1_VV_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32);
        q_vv_slv1.setUnit("imaginary");
        final Band coh_vv_slv1 = testProduct.addBand("coh_IW1_VV_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32);
        coh_vv_slv1.setUnit("coherence");
        final Band i_vh_slv1 = testProduct.addBand("i_ifg_IW1_VH_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32);
        i_vh_slv1.setUnit("real");
        final Band q_vh_slv1 = testProduct.addBand("q_ifg_IW1_VH_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32);
        q_vh_slv1.setUnit("imaginary");
        final Band coh_vh_slv1 = testProduct.addBand("coh_IW1_VH_10Jul2018_22Jul2018", ProductData.TYPE_FLOAT32);
        coh_vh_slv1.setUnit("coherence");

        final Band i_vv_slv2 = testProduct.addBand("i_ifg_IW1_VV_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32);
        i_vv_slv2.setUnit("real");
        final Band q_vv_slv2 = testProduct.addBand("q_ifg_IW1_VV_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32);
        q_vv_slv2.setUnit("imaginary");
        final Band coh_vv_slv2 = testProduct.addBand("coh_IW1_VV_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32);
        coh_vv_slv2.setUnit("coherence");
        final Band i_vh_slv2 = testProduct.addBand("i_ifg_IW1_VH_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32);
        i_vh_slv2.setUnit("real");
        final Band q_vh_slv2 = testProduct.addBand("q_ifg_IW1_VH_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32);
        q_vh_slv2.setUnit("imaginary");
        final Band coh_vh_slv2 = testProduct.addBand("coh_IW1_VH_10Jul2018_27Aug2018", ProductData.TYPE_FLOAT32);
        coh_vh_slv2.setUnit("coherence");

        final Band i_vv_slv3 = testProduct.addBand("i_ifg_IW1_VV_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32);
        i_vv_slv3.setUnit("real");
        final Band q_vv_slv3 = testProduct.addBand("q_ifg_IW1_VV_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32);
        q_vv_slv3.setUnit("imaginary");
        final Band coh_vv_slv3 = testProduct.addBand("coh_IW1_VV_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32);
        coh_vv_slv3.setUnit("coherence");
        final Band i_vh_slv3 = testProduct.addBand("i_ifg_IW1_VH_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32);
        i_vh_slv3.setUnit("real");
        final Band q_vh_slv3 = testProduct.addBand("q_ifg_IW1_VH_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32);
        q_vh_slv3.setUnit("imaginary");
        final Band coh_vh_slv3 = testProduct.addBand("coh_IW1_VH_10Jul2018_20Sep2018", ProductData.TYPE_FLOAT32);
        coh_vh_slv3.setUnit("coherence");
    }

    private static void addMetadata(final Product testProduct) {
        setAbstractedMetadata(testProduct);
        setSlaveMetadata(testProduct);
    }

    private static void setAbstractedMetadata(final Product testProduct) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(testProduct);
        absRoot.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "SLC");
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        absRoot.setAttributeInt(AbstractMetadata.ABS_ORBIT, 22734);
        absRoot.setAttributeInt(AbstractMetadata.REL_ORBIT, 87);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.parseUTC("10-JUL-2018 15:40:32.317759"));
        absRoot.setAttributeUTC(AbstractMetadata.last_line_time, AbstractMetadata.parseUTC("10-JUL-2018 15:40:43.670597"));
        absRoot.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        absRoot.setAttributeDouble(AbstractMetadata.pulse_repetition_frequency, 1717.128973878037);
        absRoot.setAttributeDouble(AbstractMetadata.line_time_interval, 0.002055556299999998);
        absRoot.setAttributeDouble(AbstractMetadata.range_bandwidth, 56.5);
        absRoot.setAttributeDouble(AbstractMetadata.azimuth_bandwidth, 327.0);
        absRoot.setAttributeDouble(AbstractMetadata.range_sampling_rate, 64.34523812571427);
        absRoot.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 799407.4451885885);
        absRoot.setAttributeInt(AbstractMetadata.num_output_lines, 10);
        absRoot.setAttributeInt(AbstractMetadata.num_samples_per_line, 10);
        absRoot.setAttributeInt(AbstractMetadata.subset_offset_x, 0);
        absRoot.setAttributeInt(AbstractMetadata.subset_offset_y, 0);
        absRoot.setAttributeDouble(AbstractMetadata.azimuth_looks, 1.0);
        absRoot.setAttributeDouble(AbstractMetadata.range_looks, 1.0);

        setMasterOrbitStateVectors(absRoot);
        setMasterDopplerCentroidCoefficients(absRoot);
    }

    private static void setMasterOrbitStateVectors(final MetadataElement absRoot) {

        MetadataElement orbitElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);
        if (orbitElem == null) {
            orbitElem = new MetadataElement(AbstractMetadata.orbit_state_vectors);
            absRoot.addElement(orbitElem);
        }
        final MetadataElement orbitVector1 = new MetadataElement("orbit_vector1");
        orbitVector1.setAttributeUTC("time", AbstractMetadata.parseUTC("10-JUL-2018 15:40:22.317759"));
        orbitVector1.setAttributeDouble("x_pos", 5311078.539825439);
        orbitVector1.setAttributeDouble("y_pos", 3128158.8990478516);
        orbitVector1.setAttributeDouble("z_pos", 3466999.82220459);
        orbitVector1.setAttributeDouble("x_vel", -2287.3079147934914);
        orbitVector1.setAttributeDouble("y_vel", -3291.4479906708);
        orbitVector1.setAttributeDouble("z_vel", 6452.212929785252);
        orbitElem.addElement(orbitVector1);

        final MetadataElement orbitVector10 = new MetadataElement("orbit_vector2");
        orbitVector10.setAttributeUTC("time", AbstractMetadata.parseUTC("10-JUL-2018 15:40:31.317759"));
        orbitVector10.setAttributeDouble("x_pos", 5290232.383346558);
        orbitVector10.setAttributeDouble("y_pos", 3098407.873626709);
        orbitVector10.setAttributeDouble("z_pos", 3524910.236907959);
        orbitVector10.setAttributeDouble("x_vel", -2345.1423029899597);
        orbitVector10.setAttributeDouble("y_vel", -3319.828203588724);
        orbitVector10.setAttributeDouble("z_vel", 6416.670112729073);
        orbitElem.addElement(orbitVector10);

        final MetadataElement orbitVector16 = new MetadataElement("orbit_vector3");
        orbitVector16.setAttributeUTC("time", AbstractMetadata.parseUTC("10-JUL-2018 15:40:37.317759"));
        orbitVector16.setAttributeDouble("x_pos", 5276046.113265991);
        orbitVector16.setAttributeDouble("y_pos", 3078432.6885681152);
        orbitVector16.setAttributeDouble("z_pos", 3563338.3226623535);
        orbitVector16.setAttributeDouble("x_vel", -2383.6007309556007);
        orbitVector16.setAttributeDouble("y_vel", -3338.538617923856);
        orbitVector16.setAttributeDouble("z_vel", 6392.648788243532);
        orbitElem.addElement(orbitVector16);

        final MetadataElement orbitVector31 = new MetadataElement("orbit_vector4");
        orbitVector31.setAttributeUTC("time", AbstractMetadata.parseUTC("10-JUL-2018 15:40:52.317759"));
        orbitVector31.setAttributeDouble("x_pos", 5239573.026245117);
        orbitVector31.setAttributeDouble("y_pos", 3028008.0087280273);
        orbitVector31.setAttributeDouble("z_pos", 3658771.1686401367);
        orbitVector31.setAttributeDouble("x_vel", -2479.3919491767883);
        orbitVector31.setAttributeDouble("y_vel", -3384.57561121881);
        orbitVector31.setAttributeDouble("z_vel", 6331.461325705051);
        orbitElem.addElement(orbitVector31);
    }

    private static void setMasterDopplerCentroidCoefficients(final MetadataElement absRoot) {
        MetadataElement dopCoefElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
        if (dopCoefElem == null) {
            dopCoefElem = new MetadataElement(AbstractMetadata.dop_coefficients);
            absRoot.addElement(dopCoefElem);
        }
        final MetadataElement dc1Elem = new MetadataElement("dop_coef_list.1");
        final MetadataElement c1Elem = new MetadataElement("coefficient.1");
        c1Elem.setAttributeDouble("dop_coef", -0.1806341);
        final MetadataElement c2Elem = new MetadataElement("coefficient.2");
        c2Elem.setAttributeDouble("dop_coef", -923.2402);
        final MetadataElement c3Elem = new MetadataElement("coefficient.3");
        c3Elem.setAttributeDouble("dop_coef", 236813.1);
        dc1Elem.setAttributeUTC("zero_doppler_time", AbstractMetadata.parseUTC("10-JUL-2018 15:40:29.314780"));
        dc1Elem.setAttributeDouble("slant_range_time", 5336427.094579077);
        dopCoefElem.addElement(dc1Elem);
    }

    private static void setSlaveMetadata(final Product testProduct) {

        final MetadataElement root = testProduct.getMetadataRoot();
        final MetadataElement slvRoot = new MetadataElement("Slave_Metadata");
        root.addElementAt(slvRoot, 1);

        final MetadataElement slv1Elem = new MetadataElement("S1A_IW_SLC__1SDV_20180722T154031_20180722T154058_022909_027C2A_8F14_Orb_IW1_22Jul2018");
        slv1Elem.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "SLC");
        slv1Elem.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        slv1Elem.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        slv1Elem.setAttributeInt(AbstractMetadata.ABS_ORBIT, 22909);
        slv1Elem.setAttributeInt(AbstractMetadata.REL_ORBIT, 87);
        slv1Elem.setAttributeInt(AbstractMetadata.coregistered_stack, 0);
        slv1Elem.setAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.parseUTC("22-JUL-2018 15:40:33.198197"));
        slv1Elem.setAttributeUTC(AbstractMetadata.last_line_time, AbstractMetadata.parseUTC("22-JUL-2018 15:40:44.548979"));
        slv1Elem.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        slv1Elem.setAttributeDouble(AbstractMetadata.pulse_repetition_frequency, 1717.128973878037);
        slv1Elem.setAttributeDouble(AbstractMetadata.line_time_interval, 0.002055556299999998);
        slv1Elem.setAttributeDouble(AbstractMetadata.range_bandwidth, 56.5);
        slv1Elem.setAttributeDouble(AbstractMetadata.azimuth_bandwidth, 327.0);
        slv1Elem.setAttributeDouble(AbstractMetadata.range_sampling_rate, 64.34523812571427);
        slv1Elem.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 799407.4451885885);
        slv1Elem.setAttributeInt(AbstractMetadata.num_output_lines, 10);
        slv1Elem.setAttributeInt(AbstractMetadata.num_samples_per_line, 10);
        slv1Elem.setAttributeInt(AbstractMetadata.subset_offset_x, 0);
        slv1Elem.setAttributeInt(AbstractMetadata.subset_offset_y, 0);
        slv1Elem.setAttributeDouble(AbstractMetadata.azimuth_looks, 1.0);
        slv1Elem.setAttributeDouble(AbstractMetadata.range_looks, 1.0);
        slv1Elem.setAttributeString("Slave_bands", "i_ifg_IW1_VV_10Jul2018_22Jul2018 q_ifg_IW1_VV_10Jul2018_22Jul2018 Phase_ifg_IW1_VV_10Jul2018_22Jul2018 coh_IW1_VV_10Jul2018_22Jul2018 i_ifg_IW1_VH_10Jul2018_22Jul2018 q_ifg_IW1_VH_10Jul2018_22Jul2018 Phase_ifg_IW1_VH_10Jul2018_22Jul2018 coh_IW1_VH_10Jul2018_22Jul2018");
        setSlave1OrbitStateVectors(slv1Elem);
        setSlave1DopplerCentroidCoefficients(slv1Elem);
        slvRoot.addElementAt(slv1Elem, 0);

        final MetadataElement slv2Elem = new MetadataElement("S1A_IW_SLC__1SDV_20180827T154033_20180827T154100_023434_028CE6_F163_Orb_IW1_27Aug2018");
        slv2Elem.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "SLC");
        slv2Elem.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        slv2Elem.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        slv2Elem.setAttributeInt(AbstractMetadata.ABS_ORBIT, 23434);
        slv2Elem.setAttributeInt(AbstractMetadata.REL_ORBIT, 87);
        slv2Elem.setAttributeInt(AbstractMetadata.coregistered_stack, 0);
        slv2Elem.setAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.parseUTC("27-AUG-2018 15:40:35.279438"));
        slv2Elem.setAttributeUTC(AbstractMetadata.last_line_time, AbstractMetadata.parseUTC("27-AUG-2018 15:40:46.630220"));
        slv2Elem.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        slv2Elem.setAttributeDouble(AbstractMetadata.pulse_repetition_frequency, 1717.128973878037);
        slv2Elem.setAttributeDouble(AbstractMetadata.line_time_interval, 0.002055556299999998);
        slv2Elem.setAttributeDouble(AbstractMetadata.range_bandwidth, 56.5);
        slv2Elem.setAttributeDouble(AbstractMetadata.azimuth_bandwidth, 327.0);
        slv2Elem.setAttributeDouble(AbstractMetadata.range_sampling_rate, 64.34523812571427);
        slv2Elem.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 799407.4451885885);
        slv2Elem.setAttributeInt(AbstractMetadata.num_output_lines, 10);
        slv2Elem.setAttributeInt(AbstractMetadata.num_samples_per_line, 10);
        slv2Elem.setAttributeInt(AbstractMetadata.subset_offset_x, 0);
        slv2Elem.setAttributeInt(AbstractMetadata.subset_offset_y, 0);
        slv2Elem.setAttributeDouble(AbstractMetadata.azimuth_looks, 1.0);
        slv2Elem.setAttributeDouble(AbstractMetadata.range_looks, 1.0);
        slv2Elem.setAttributeString("Slave_bands", "i_ifg_IW1_VV_10Jul2018_27Aug2018 q_ifg_IW1_VV_10Jul2018_27Aug2018 Phase_ifg_IW1_VV_10Jul2018_27Aug2018 coh_IW1_VV_10Jul2018_27Aug2018 i_ifg_IW1_VH_10Jul2018_27Aug2018 q_ifg_IW1_VH_10Jul2018_27Aug2018 Phase_ifg_IW1_VH_10Jul2018_27Aug2018 coh_IW1_VH_10Jul2018_27Aug2018");
        setSlave2OrbitStateVectors(slv2Elem);
        setSlave2DopplerCentroidCoefficients(slv2Elem);
        slvRoot.addElementAt(slv2Elem, 1);

        final MetadataElement slv3Elem = new MetadataElement("S1A_IW_SLC__1SDV_20180920T154034_20180920T154101_023784_02981E_302D_Orb_IW1_20Sep2018");
        slv3Elem.setAttributeString(AbstractMetadata.PRODUCT_TYPE, "SLC");
        slv3Elem.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        slv3Elem.setAttributeString(AbstractMetadata.MISSION, "SENTINEL-1A");
        slv3Elem.setAttributeInt(AbstractMetadata.ABS_ORBIT, 23784);
        slv3Elem.setAttributeInt(AbstractMetadata.REL_ORBIT, 87);
        slv3Elem.setAttributeInt(AbstractMetadata.coregistered_stack, 0);
        slv3Elem.setAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.parseUTC("20-SEP-2018 15:40:36.167062"));
        slv3Elem.setAttributeUTC(AbstractMetadata.last_line_time, AbstractMetadata.parseUTC("20-SEP-2018 15:40:47.519900"));
        slv3Elem.setAttributeDouble(AbstractMetadata.radar_frequency, 5405.000454334349);
        slv3Elem.setAttributeDouble(AbstractMetadata.pulse_repetition_frequency, 1717.128973878037);
        slv3Elem.setAttributeDouble(AbstractMetadata.line_time_interval, 0.002055556299999998);
        slv3Elem.setAttributeDouble(AbstractMetadata.range_bandwidth, 56.5);
        slv3Elem.setAttributeDouble(AbstractMetadata.azimuth_bandwidth, 327.0);
        slv3Elem.setAttributeDouble(AbstractMetadata.range_sampling_rate, 64.34523812571427);
        slv3Elem.setAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 799407.4451885885);
        slv3Elem.setAttributeInt(AbstractMetadata.num_output_lines, 10);
        slv3Elem.setAttributeInt(AbstractMetadata.num_samples_per_line, 10);
        slv3Elem.setAttributeInt(AbstractMetadata.subset_offset_x, 0);
        slv3Elem.setAttributeInt(AbstractMetadata.subset_offset_y, 0);
        slv3Elem.setAttributeDouble(AbstractMetadata.azimuth_looks, 1.0);
        slv3Elem.setAttributeDouble(AbstractMetadata.range_looks, 1.0);
        slv3Elem.setAttributeString("Slave_bands", "i_ifg_IW1_VV_10Jul2018_20Sep2018 q_ifg_IW1_VV_10Jul2018_20Sep2018 Phase_ifg_IW1_VV_10Jul2018_20Sep2018 coh_IW1_VV_10Jul2018_20Sep2018 i_ifg_IW1_VH_10Jul2018_20Sep2018 q_ifg_IW1_VH_10Jul2018_20Sep2018 Phase_ifg_IW1_VH_10Jul2018_20Sep2018 coh_IW1_VH_10Jul2018_20Sep2018");
        setSlave3OrbitStateVectors(slv3Elem);
        setSlave3DopplerCentroidCoefficients(slv3Elem);
        slvRoot.addElementAt(slv3Elem, 2);
    }

    private static void setSlave1OrbitStateVectors(final MetadataElement slv1Elem) {

        MetadataElement orbitElem = slv1Elem.getElement(AbstractMetadata.orbit_state_vectors);
        if (orbitElem == null) {
            orbitElem = new MetadataElement(AbstractMetadata.orbit_state_vectors);
            slv1Elem.addElement(orbitElem);
        }
        final MetadataElement orbitVector1 = new MetadataElement("orbit_vector1");
        orbitVector1.setAttributeUTC("time", AbstractMetadata.parseUTC("22-JUL-2018 15:40:23.198197"));
        orbitVector1.setAttributeDouble("x_pos", 5311119.944091797);
        orbitVector1.setAttributeDouble("y_pos", 3128060.756866455);
        orbitVector1.setAttributeDouble("z_pos", 3467019.2866516113);
        orbitVector1.setAttributeDouble("x_vel", -2287.3091543912888);
        orbitVector1.setAttributeDouble("y_vel", -3291.527448281646);
        orbitVector1.setAttributeDouble("z_vel", 6452.1836295723915);
        orbitElem.addElement(orbitVector1);

        final MetadataElement orbitVector10 = new MetadataElement("orbit_vector2");
        orbitVector10.setAttributeUTC("time", AbstractMetadata.parseUTC("22-JUL-2018 15:40:32.198197"));
        orbitVector10.setAttributeDouble("x_pos", 5290273.77456665);
        orbitVector10.setAttributeDouble("y_pos", 3098309.0209960938);
        orbitVector10.setAttributeDouble("z_pos", 3524929.4371032715);
        orbitVector10.setAttributeDouble("x_vel", -2345.1441240906715);
        orbitVector10.setAttributeDouble("y_vel", -3319.9067001342773);
        orbitVector10.setAttributeDouble("z_vel", 6416.640574365854);
        orbitElem.addElement(orbitVector10);

        final MetadataElement orbitVector20 = new MetadataElement("orbit_vector3");
        orbitVector20.setAttributeUTC("time", AbstractMetadata.parseUTC("22-JUL-2018 15:40:42.198197"));
        orbitVector20.setAttributeDouble("x_pos", 5266501.880813599);
        orbitVector20.setAttributeDouble("y_pos", 3064954.1177368164);
        orbitVector20.setAttributeDouble("z_pos", 3588895.543121338);
        orbitVector20.setAttributeDouble("x_vel", -2409.197666287422);
        orbitVector20.setAttributeDouble("y_vel", -3350.996031060815);
        orbitVector20.setAttributeDouble("z_vel", 6376.460414111614);
        orbitElem.addElement(orbitVector20);

        final MetadataElement orbitVector31 = new MetadataElement("orbit_vector4");
        orbitVector31.setAttributeUTC("time", AbstractMetadata.parseUTC("22-JUL-2018 15:40:53.198197"));
        orbitVector31.setAttributeDouble("x_pos", 5239614.364089966);
        orbitVector31.setAttributeDouble("y_pos", 3027907.531188965);
        orbitVector31.setAttributeDouble("z_pos", 3658789.7427368164);
        orbitVector31.setAttributeDouble("x_vel", -2479.395119011402);
        orbitVector31.setAttributeDouble("y_vel", -3384.651829585433);
        orbitVector31.setAttributeDouble("z_vel", 6331.431237578392);
        orbitElem.addElement(orbitVector31);
    }

    private static void setSlave2OrbitStateVectors(final MetadataElement slv2Elem) {

        MetadataElement orbitElem = slv2Elem.getElement(AbstractMetadata.orbit_state_vectors);
        if (orbitElem == null) {
            orbitElem = new MetadataElement(AbstractMetadata.orbit_state_vectors);
            slv2Elem.addElement(orbitElem);
        }
        final MetadataElement orbitVector1 = new MetadataElement("orbit_vector1");
        orbitVector1.setAttributeUTC("time", AbstractMetadata.parseUTC("27-AUG-2018 15:40:25.279438"));
        orbitVector1.setAttributeDouble("x_pos", 5311087.047546387);
        orbitVector1.setAttributeDouble("y_pos", 3128081.437713623);
        orbitVector1.setAttributeDouble("z_pos", 3467054.8760681152);
        orbitVector1.setAttributeDouble("x_vel", -2287.3642384409904);
        orbitVector1.setAttributeDouble("y_vel", -3291.5028032958508);
        orbitVector1.setAttributeDouble("z_vel", 6452.1706665456295);
        orbitElem.addElement(orbitVector1);

        final MetadataElement orbitVector10 = new MetadataElement("orbit_vector2");
        orbitVector10.setAttributeUTC("time", AbstractMetadata.parseUTC("27-AUG-2018 15:40:34.279438"));
        orbitVector10.setAttributeDouble("x_pos", 5290240.383880615);
        orbitVector10.setAttributeDouble("y_pos", 3098329.9232177734);
        orbitVector10.setAttributeDouble("z_pos", 3524964.9079589844);
        orbitVector10.setAttributeDouble("x_vel", -2345.198796749115);
        orbitVector10.setAttributeDouble("y_vel", -3319.8821765482426);
        orbitVector10.setAttributeDouble("z_vel", 6416.627283036709);
        orbitElem.addElement(orbitVector10);

        final MetadataElement orbitVector20 = new MetadataElement("orbit_vector3");
        orbitVector20.setAttributeUTC("time", AbstractMetadata.parseUTC("27-AUG-2018 15:40:44.279438"));
        orbitVector20.setAttributeDouble("x_pos", 5266467.945510864);
        orbitVector20.setAttributeDouble("y_pos", 3064975.2644958496);
        orbitVector20.setAttributeDouble("z_pos", 3588930.8793640137);
        orbitVector20.setAttributeDouble("x_vel", -2409.2518770694733);
        orbitVector20.setAttributeDouble("y_vel", -3350.971646428108);
        orbitVector20.setAttributeDouble("z_vel", 6376.446758896112);
        orbitElem.addElement(orbitVector20);

        final MetadataElement orbitVector31 = new MetadataElement("orbit_vector4");
        orbitVector31.setAttributeUTC("time", AbstractMetadata.parseUTC("27-AUG-2018 15:40:55.279438"));
        orbitVector31.setAttributeDouble("x_pos", 5239579.835479736);
        orbitVector31.setAttributeDouble("y_pos", 3027928.945587158);
        orbitVector31.setAttributeDouble("z_pos", 3658824.92678833);
        orbitVector31.setAttributeDouble("x_vel", -2479.4488176107407);
        orbitVector31.setAttributeDouble("y_vel", -3384.627603754401);
        orbitVector31.setAttributeDouble("z_vel", 6331.417184263468);
        orbitElem.addElement(orbitVector31);
    }

    private static void setSlave3OrbitStateVectors(final MetadataElement slv3Elem) {

        MetadataElement orbitElem = slv3Elem.getElement(AbstractMetadata.orbit_state_vectors);
        if (orbitElem == null) {
            orbitElem = new MetadataElement(AbstractMetadata.orbit_state_vectors);
            slv3Elem.addElement(orbitElem);
        }
        final MetadataElement orbitVector1 = new MetadataElement("orbit_vector1");
        orbitVector1.setAttributeUTC("time", AbstractMetadata.parseUTC("20-SEP-2018 15:40:26.167062"));
        orbitVector1.setAttributeDouble("x_pos", 5311086.204589844);
        orbitVector1.setAttributeDouble("y_pos", 3128042.965789795);
        orbitVector1.setAttributeDouble("z_pos", 3467081.8455810547);
        orbitVector1.setAttributeDouble("x_vel", -2287.374900341034);
        orbitVector1.setAttributeDouble("y_vel", -3291.525098711252);
        orbitVector1.setAttributeDouble("z_vel", 6452.160836368799);
        orbitElem.addElement(orbitVector1);

        final MetadataElement orbitVector10 = new MetadataElement("orbit_vector2");
        orbitVector10.setAttributeUTC("time", AbstractMetadata.parseUTC("20-SEP-2018 15:40:35.167062"));
        orbitVector10.setAttributeDouble("x_pos", 5290239.44442749);
        orbitVector10.setAttributeDouble("y_pos", 3098291.252166748);
        orbitVector10.setAttributeDouble("z_pos", 3524991.7873535156);
        orbitVector10.setAttributeDouble("x_vel", -2345.2095813155174);
        orbitVector10.setAttributeDouble("y_vel", -3319.9041152894497);
        orbitVector10.setAttributeDouble("z_vel", 6416.617110431194);
        orbitElem.addElement(orbitVector10);

        final MetadataElement orbitVector20 = new MetadataElement("orbit_vector3");
        orbitVector20.setAttributeUTC("time", AbstractMetadata.parseUTC("20-SEP-2018 15:40:45.167062"));
        orbitVector20.setAttributeDouble("x_pos", 5266466.897598267);
        orbitVector20.setAttributeDouble("y_pos", 3064936.37600708);
        orbitVector20.setAttributeDouble("z_pos", 3588957.655090332);
        orbitVector20.setAttributeDouble("x_vel", -2409.262790441513);
        orbitVector20.setAttributeDouble("y_vel", -3350.993182808161);
        orbitVector20.setAttributeDouble("z_vel", 6376.43620967865);
        orbitElem.addElement(orbitVector20);

        final MetadataElement orbitVector31 = new MetadataElement("orbit_vector4");
        orbitVector31.setAttributeUTC("time", AbstractMetadata.parseUTC("20-SEP-2018 15:40:56.167062"));
        orbitVector31.setAttributeDouble("x_pos", 5239578.667037964);
        orbitVector31.setAttributeDouble("y_pos", 3027889.822631836);
        orbitVector31.setAttributeDouble("z_pos", 3658851.5841674805);
        orbitVector31.setAttributeDouble("x_vel", -2479.4598646759987);
        orbitVector31.setAttributeDouble("y_vel", -3384.6486906558275);
        orbitVector31.setAttributeDouble("z_vel", 6331.406224608421);
        orbitElem.addElement(orbitVector31);
    }


    private static void setSlave1DopplerCentroidCoefficients(final MetadataElement slv1Elem) {

        MetadataElement dopCoefElem = slv1Elem.getElement(AbstractMetadata.dop_coefficients);
        if (dopCoefElem == null) {
            dopCoefElem = new MetadataElement(AbstractMetadata.dop_coefficients);
            slv1Elem.addElement(dopCoefElem);
        }
        final MetadataElement dc1Elem = new MetadataElement("dop_coef_list.1");
        final MetadataElement c1Elem = new MetadataElement("coefficient.1");
        c1Elem.setAttributeDouble("dop_coef", 0.873096);
        final MetadataElement c2Elem = new MetadataElement("coefficient.2");
        c2Elem.setAttributeDouble("dop_coef", 291.7465);
        final MetadataElement c3Elem = new MetadataElement("coefficient.3");
        c3Elem.setAttributeDouble("dop_coef", -87821.83);
        dc1Elem.setAttributeUTC("zero_doppler_time", AbstractMetadata.parseUTC("22-JUL-2018 15:40:30.189051"));
        dc1Elem.setAttributeDouble("slant_range_time", 5336427.094579077);
        dopCoefElem.addElement(dc1Elem);
    }

    private static void setSlave2DopplerCentroidCoefficients(final MetadataElement slv2Elem) {

        MetadataElement dopCoefElem = slv2Elem.getElement(AbstractMetadata.dop_coefficients);
        if (dopCoefElem == null) {
            dopCoefElem = new MetadataElement(AbstractMetadata.dop_coefficients);
            slv2Elem.addElement(dopCoefElem);
        }
        final MetadataElement dc1Elem = new MetadataElement("dop_coef_list.1");
        final MetadataElement c1Elem = new MetadataElement("coefficient.1");
        c1Elem.setAttributeDouble("dop_coef", 0.6322383);
        final MetadataElement c2Elem = new MetadataElement("coefficient.2");
        c2Elem.setAttributeDouble("dop_coef", 12.23609);
        final MetadataElement c3Elem = new MetadataElement("coefficient.3");
        c3Elem.setAttributeDouble("dop_coef", -13170.05);
        dc1Elem.setAttributeUTC("zero_doppler_time", AbstractMetadata.parseUTC("27-AUG-2018 15:40:32.262290"));
        dc1Elem.setAttributeDouble("slant_range_time", 5336427.094579077);
        dopCoefElem.addElement(dc1Elem);
    }

    private static void setSlave3DopplerCentroidCoefficients(final MetadataElement slv3Elem) {

        MetadataElement dopCoefElem = slv3Elem.getElement(AbstractMetadata.dop_coefficients);
        if (dopCoefElem == null) {
            dopCoefElem = new MetadataElement(AbstractMetadata.dop_coefficients);
            slv3Elem.addElement(dopCoefElem);
        }
        final MetadataElement dc1Elem = new MetadataElement("dop_coef_list.1");
        final MetadataElement c1Elem = new MetadataElement("coefficient.1");
        c1Elem.setAttributeDouble("dop_coef", -0.7664093);
        final MetadataElement c2Elem = new MetadataElement("coefficient.2");
        c2Elem.setAttributeDouble("dop_coef", 614.2977);
        final MetadataElement c3Elem = new MetadataElement("coefficient.3");
        c3Elem.setAttributeDouble("dop_coef", -143446.1);
        dc1Elem.setAttributeUTC("zero_doppler_time", AbstractMetadata.parseUTC("20-SEP-2018 15:40:33.139112"));
        dc1Elem.setAttributeDouble("slant_range_time", 5336427.094579077);
        dopCoefElem.addElement(dc1Elem);
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
}
