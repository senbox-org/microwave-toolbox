package org.jlinda.core.filtering;

import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.FloatMatrix;
import org.jlinda.core.SLCImage;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;

import static org.jlinda.core.io.DataReader.readCplxFloatData;
import static org.jlinda.core.io.DataReader.readFloatData;

public class RangeFilterTest {

    private static final double DELTA_03 = 1e-03;
    // External binary fixtures required by the @Ignore'd tests below.
    private static final String testDirectory = "/d2/etna_test/rangeFilterTest/";

    /// tile size ///
    private static int nRows = 128;
    private static int nCols = 128;

    private static ComplexDoubleMatrix masterCplx;
    private static ComplexDoubleMatrix slaveCplx;

    private static ComplexDoubleMatrix masterCplx_ACTUAL;
    private static ComplexDoubleMatrix slaveCplx_ACTUAL;

    @BeforeClass
    public static void setUpTestData() {
        // Tolerate missing fixtures so the class can load and runnable tests still execute.
        if (!new File(testDirectory).isDirectory()) {
            return;
        }
        try {
            masterCplx = readCplxFloatData(testDirectory + "slc_image_128_128.cr4.swap", 128, 128);
            slaveCplx = readCplxFloatData(testDirectory + "slc_image1_128_128.cr4.swap", 128, 128);
        } catch (Exception ignored) {
            // fixtures unavailable; the @Ignore'd tests below stay dormant
        }
    }

    /**
     * Regression test for the SLCImage range-bandwidth unit fix.
     *
     * SLCImage stores rangeBandwidth in Hz (see field-init at line 102 and the
     * .res-file path at line 279, which both produce Hz). Prior to the fix, the
     * MetadataElement constructor stored the raw MHz value from
     * {@link org.esa.snap.engine_utilities.datamodel.AbstractMetadata#range_bandwidth},
     * and {@link RangeFilter#defineParameters()} silently compensated with a
     * `* MEGA` multiplier. RangeFilter now consumes {@code getRangeBandwidth()}
     * directly, so the Hz invariant must hold for every SLCImage construction
     * path.
     */
    @Test
    public void rangeBandwidthInvariantIsHz() {
        SLCImage slc = new SLCImage();
        Assert.assertEquals("default ERS2 bandwidth must be in Hz",
                15.55e6, slc.getRangeBandwidth(), 1e-6);

        slc.setRangeBandwidth(56.5e6);
        Assert.assertEquals("setter round-trip preserves Hz",
                56.5e6, slc.getRangeBandwidth(), 1e-6);
    }

    @Ignore("requires binary fixtures at " + testDirectory)
    @Test
    public void testDefineFilter() throws Exception {

        // master metadata & data
        SLCImage masterMetadata = new SLCImage();
        masterMetadata.parseResFile(new File(testDirectory + "master.res"));
        masterCplx_ACTUAL = masterCplx.dup();

        // slave metadata & data
        SLCImage slaveMetadata = new SLCImage();
        slaveMetadata.parseResFile(new File(testDirectory + "slave.res"));
        slaveCplx_ACTUAL = slaveCplx.dup();

        RangeFilter rangeFilter = new RangeFilter();

        rangeFilter.setMetadata(masterMetadata);
        rangeFilter.setData(masterCplx_ACTUAL);

        rangeFilter.setMetadata1(slaveMetadata);
        rangeFilter.setData1(slaveCplx_ACTUAL);

        rangeFilter.defineParameters();
        rangeFilter.defineFilter();
        DoubleMatrix rngFilter_ACTUAL = rangeFilter.getFilter();

        String fileNameFilter = "filter_hamm_1_OFF.r4.swap";
        FloatMatrix rngFilter_EXPECTED = readFloatData(testDirectory + fileNameFilter, nRows, nCols);

        Assert.assertEquals(rngFilter_EXPECTED, rngFilter_ACTUAL.toFloat());
    }


    @Ignore("requires binary fixtures at " + testDirectory)
    @Test
    public void filterClass_HAMM() throws Exception {

        // master metadata & data
        SLCImage masterMetadata = new SLCImage();
        masterMetadata.parseResFile(new File(testDirectory + "master.res"));
        masterCplx_ACTUAL = masterCplx.dup();

        // slave metadata & data
        SLCImage slaveMetadata = new SLCImage();
        slaveMetadata.parseResFile(new File(testDirectory + "slave.res"));
        slaveCplx_ACTUAL = slaveCplx.dup();

        RangeFilter rangeFilter = new RangeFilter();

        rangeFilter.setMetadata(masterMetadata);
        rangeFilter.setData(masterCplx_ACTUAL);

        rangeFilter.setMetadata1(slaveMetadata);
        rangeFilter.setData1(slaveCplx_ACTUAL);

        rangeFilter.defineParameters();

        rangeFilter.defineFilter();
        rangeFilter.applyFilter();

        /// load Expected Data
        String fileMasterDataNameFiltered = "slc_image_filtered_hamm_1_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA_EXPECTED = loadExpectedData(fileMasterDataNameFiltered);

        String fileSlaveDataNameFiltered = "slc_image1_filtered_hamm_1_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA1_EXPECTED = loadExpectedData(fileSlaveDataNameFiltered);

        Assert.assertEquals(rngFilter_DATA_EXPECTED, rangeFilter.getData());
        Assert.assertEquals(rngFilter_DATA1_EXPECTED, rangeFilter.getData1());

    }

    @Ignore("requires binary fixtures at " + testDirectory)
    @Test
    public void filterClass_RECT() throws Exception {

        // master metadata & data
        SLCImage masterMetadata = new SLCImage();
        masterMetadata.parseResFile(new File(testDirectory + "master.res"));
        masterCplx_ACTUAL = masterCplx.dup();

        // slave metadata & data
        SLCImage slaveMetadata = new SLCImage();
        slaveMetadata.parseResFile(new File(testDirectory + "slave.res"));
        slaveCplx_ACTUAL = slaveCplx.dup();

        RangeFilter rangeFilter = new RangeFilter();

        rangeFilter.setMetadata(masterMetadata);
        rangeFilter.setData(masterCplx_ACTUAL);

        rangeFilter.setMetadata1(slaveMetadata);
        rangeFilter.setData1(slaveCplx_ACTUAL);

        rangeFilter.setAlphaHamming(1);
        rangeFilter.defineParameters();

        rangeFilter.defineFilter();
        rangeFilter.applyFilter();

        /// load Expected Data
        String fileMasterDataNameFiltered = "slc_image_filtered_rect_1_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA_EXPECTED = loadExpectedData(fileMasterDataNameFiltered);

        String fileSlaveDataNameFiltered = "slc_image1_filtered_rect_1_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA1_EXPECTED = loadExpectedData(fileSlaveDataNameFiltered);

        Assert.assertEquals(rngFilter_DATA_EXPECTED, rangeFilter.getData());
        Assert.assertEquals(rngFilter_DATA1_EXPECTED, rangeFilter.getData1());

    }

    @Ignore("requires binary fixtures at " + testDirectory)
    @Test
    public void filterClass_HAMM_OVSMP() throws Exception {

        // master metadata & data
        SLCImage masterMetadata = new SLCImage();
        masterMetadata.parseResFile(new File(testDirectory + "master.res"));
        masterCplx_ACTUAL = masterCplx.dup();

        // slave metadata & data
        SLCImage slaveMetadata = new SLCImage();
        slaveMetadata.parseResFile(new File(testDirectory + "slave.res"));
        slaveCplx_ACTUAL = slaveCplx.dup();

        RangeFilter rangeFilter = new RangeFilter();

        rangeFilter.setMetadata(masterMetadata);
        rangeFilter.setData(masterCplx_ACTUAL);

        rangeFilter.setMetadata1(slaveMetadata);
        rangeFilter.setData1(slaveCplx_ACTUAL);

        rangeFilter.setOvsFactor(2);
        rangeFilter.defineParameters();

        rangeFilter.defineFilter();
        rangeFilter.applyFilter();

        /// load Expected Data
        String fileMasterDataNameFiltered = "slc_image_filtered_hamm_2_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA_EXPECTED = loadExpectedData(fileMasterDataNameFiltered);

        String fileSlaveDataNameFiltered = "slc_image1_filtered_hamm_2_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA1_EXPECTED = loadExpectedData(fileSlaveDataNameFiltered);

        Assert.assertEquals(rngFilter_DATA_EXPECTED, rangeFilter.getData());
        Assert.assertEquals(rngFilter_DATA1_EXPECTED, rangeFilter.getData1());

    }

    @Ignore("requires binary fixtures at " + testDirectory)
    @Test
    public void filterClass_HAMM_OVSMP_SPLIT() throws Exception {

        // master metadata & data
        SLCImage masterMetadata = new SLCImage();
        masterMetadata.parseResFile(new File(testDirectory + "master.res"));
        masterCplx_ACTUAL = masterCplx.dup();

        // slave metadata & data
        SLCImage slaveMetadata = new SLCImage();
        slaveMetadata.parseResFile(new File(testDirectory + "slave.res"));
        slaveCplx_ACTUAL = slaveCplx.dup();

        RangeFilter rangeFilter = new RangeFilter();

        rangeFilter.setMetadata(masterMetadata);
        rangeFilter.setData(masterCplx_ACTUAL);

        rangeFilter.setMetadata1(slaveMetadata);
        rangeFilter.setData1(slaveCplx_ACTUAL);

        rangeFilter.setOvsFactor(2);
        rangeFilter.defineParameters();

        rangeFilter.defineFilter();
        rangeFilter.applyFilterMaster();
        rangeFilter.applyFilterSlave();

        /// load Expected Data
        String fileMasterDataNameFiltered = "slc_image_filtered_hamm_2_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA_EXPECTED = loadExpectedData(fileMasterDataNameFiltered);

        String fileSlaveDataNameFiltered = "slc_image1_filtered_hamm_2_OFF.cr4.swap";
        ComplexDoubleMatrix rngFilter_DATA1_EXPECTED = loadExpectedData(fileSlaveDataNameFiltered);

        Assert.assertEquals(rngFilter_DATA_EXPECTED, rangeFilter.getData());
        Assert.assertEquals(rngFilter_DATA1_EXPECTED, rangeFilter.getData1());

    }


    /// HELPER FUNCTIONS ///

    private ComplexDoubleMatrix loadExpectedData(String fileMasterDataNameFiltered) throws FileNotFoundException {
        return readCplxFloatData(testDirectory + fileMasterDataNameFiltered, nRows, nCols);
    }

    private void assertFilterBlock(ComplexDoubleMatrix masterCplx_rngFilter_EXPECTED, ComplexDoubleMatrix slaveCplx_rngFilter_EXPECTED) {
        Assert.assertArrayEquals(masterCplx_rngFilter_EXPECTED.toDoubleArray(), masterCplx_ACTUAL.toDoubleArray(), DELTA_03);
        Assert.assertArrayEquals(slaveCplx_rngFilter_EXPECTED.toDoubleArray(), slaveCplx_ACTUAL.toDoubleArray(), DELTA_03);
    }

}
