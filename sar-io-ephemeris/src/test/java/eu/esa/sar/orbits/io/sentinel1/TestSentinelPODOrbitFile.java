/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.orbits.io.sentinel1;

import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Orbits;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * To test SentinelPODOrbitFile
 */
public class TestSentinelPODOrbitFile {

    private final static File orbitFile = new File(TestData.inputSAR+"Orbits/S1A_OPER_AUX_RESORB_OPOD_20140611T152302_V20140525T151921_20140525T183641.EOF");

    final File sourceFile = TestData.inputS1_GRDSubset;

    @Before
    public void setUp() {
        // If the file does not exist: the test will be ignored
        assumeTrue(orbitFile + "not found", orbitFile.exists());
        assumeTrue(sourceFile + "not found", sourceFile.exists());
    }

    @Test
    public void testRetrieveOrbitFile() throws Exception {
        final File sourceFile = TestData.inputS1_GRDSubset;

        try(final Product sourceProduct = ProductIO.readProduct(sourceFile)) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            final SentinelPODOrbitFile podOrbitFile = new SentinelPODOrbitFile(absRoot, 3);
            podOrbitFile.retrieveOrbitFile(SentinelPODOrbitFile.PRECISE);
        }
    }

    @Test
    public void testSentinelPODOrbitFile() throws Exception {

        final MetadataElement absRoot;
        try(final Product sourceProduct = ProductIO.readProduct(sourceFile)) {
            absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

            final SentinelPODOrbitFile podOrbitFile = new SentinelPODOrbitFile(absRoot, 3);
            podOrbitFile.retrieveOrbitFile(SentinelPODOrbitFile.PRECISE);

            assertEquals("1.10.1", podOrbitFile.getVersion());

            // First OSV (exact match)
            String utcStr1 = "UTC=2015-08-27T22:59:43.000000";
            double utc1 = Sentinel1OrbitFileReader.toUTC(utcStr1).getMJD();
            Orbits.OrbitVector orbitData = podOrbitFile.getOrbitData(utc1);
            assertEquals(-368251.718419, orbitData.xPos, 0.00001);
            assertEquals(-2299963.236657, orbitData.yPos, 0.00001);
            assertEquals(6671242.884855, orbitData.zPos, 0.00001);
            assertEquals(-2359.280543798802, orbitData.xVel, 0.00001);
            assertEquals(6854.762657202235, orbitData.yVel, 0.00001);
            assertEquals(2228.1165815304676, orbitData.zVel, 0.00001);

            // Last OSV (exact match)
            utcStr1 = "UTC=2015-08-29T00:59:43.000000";
            utc1 = Sentinel1OrbitFileReader.toUTC(utcStr1).getMJD();
            orbitData = podOrbitFile.getOrbitData(utc1);
            assertEquals(-1801672.041717881, orbitData.xPos, 0.00001);
            assertEquals(-6843683.156036512, orbitData.yPos, 0.00001);
            assertEquals(-10341.538527628523, orbitData.zPos, 0.00001);
            assertEquals(-1531.6392250009976, orbitData.xVel, 0.00001);
            assertEquals(401.30609303488893, orbitData.yVel, 0.00001);
            assertEquals(7430.427411983959, orbitData.zVel, 0.00001);

            // 500th OSV (exact match)
            utcStr1 = "UTC=2015-08-28T00:23:03.000000";
            utc1 = Sentinel1OrbitFileReader.toUTC(utcStr1).getMJD();
            orbitData = podOrbitFile.getOrbitData(utc1);
            assertEquals(-942678.5061855946, orbitData.xPos, 0.00001);
            assertEquals(-6731873.704916789, orbitData.yPos, 0.00001);
            assertEquals(1959410.996368517, orbitData.zPos, 0.00001);
            assertEquals(-1304.7359917523959, orbitData.xVel, 0.00001);
            assertEquals(2269.5797016534198, orbitData.yVel, 0.00001);
            assertEquals(7132.5669622694795, orbitData.zVel, 0.00001);

            // between 450th and 451th OSV (closer to 450th)
            utcStr1 = "UTC=2015-08-28T00:14:45.000000";
            utc1 = Sentinel1OrbitFileReader.toUTC(utcStr1).getMJD();
            orbitData = podOrbitFile.getOrbitData(utc1);
            assertEquals(-178042.0701514957, orbitData.xPos, 0.00001);
            assertEquals(-6869995.856001431, orbitData.yPos, 0.00001);
            assertEquals(-1697201.8595343311, orbitData.zPos, 0.00001);
            assertEquals(-1645.6544666400837, orbitData.xVel, 0.00001);
            assertEquals(-1730.7971297834458, orbitData.yVel, 0.00001);
            assertEquals(7208.367468014132, orbitData.zVel, 0.00001);

            // between 450th and 451th OSV (closer to 451th)
            utcStr1 = "UTC=2015-08-28T00:14:50.999999";
            utc1 = Sentinel1OrbitFileReader.toUTC(utcStr1).getMJD();
            orbitData = podOrbitFile.getOrbitData(utc1);
            assertEquals(-187916.84263642266, orbitData.xPos, 0.00001);
            assertEquals(-6880237.812086614, orbitData.yPos, 0.00001);
            assertEquals(-1653917.501724767, orbitData.zPos, 0.00001);
            assertEquals(-1645.9188783108063, orbitData.xVel, 0.00001);
            assertEquals(-1683.1769834732386, orbitData.yVel, 0.00001);
            assertEquals(7219.7053536280455, orbitData.zVel, 0.00001);

            // times outside the file are extrapolated far from any orbit (1e26 to 1e38 m),
            // so they are not checked

            final Sentinel1OrbitFileReader orbitFileReader = new Sentinel1OrbitFileReader(podOrbitFile.getOrbitFile());
            orbitFileReader.read();

            String str = orbitFileReader.getMissionFromHeader();
            if (str != null) {
                TestUtils.log.info("Mission from Header = " + str);
                assert (str.startsWith("Sentinel-1"));
            }

            str = orbitFileReader.getFileTypeFromHeader();
            if (str != null) {
                TestUtils.log.info("File_Type from Header = " + str);
                assertEquals("AUX_POEORB", str);
            }

            str = orbitFileReader.getValidityStartFromHeader();
            if (str != null) {
                TestUtils.log.info("Validity_Start from Header = " + str);
                assertEquals("UTC=2015-08-27T22:59:43", str);
            }

            str = orbitFileReader.getValidityStopFromHeader();
            if (str != null) {
                TestUtils.log.info("Validity_Stop from Header = " + str);
                assertEquals("UTC=2015-08-29T00:59:43", str);
            }
        }
    }

    @Test
    public void testSentinelPODOrbitValidity() {
        final String vStart = Sentinel1OrbitFileReader.getValidityStartFromFilename("S1A_OPER_AUX_POEORB_OPOD_20140526T151322_V20140509T225944_20140511T005944.EOF");
        TestUtils.log.info("validity start from filename = " + vStart );
        assertEquals("UTC=2014-05-09T22:59:44", vStart);

        final String vStop = Sentinel1OrbitFileReader.getValidityStopFromFilename("S1A_OPER_AUX_POEORB_OPOD_20140526T151322_V20140509T225944_20140511T005944.EOF");
        TestUtils.log.info("validity stop from filename = " + vStop);
        assertEquals("UTC=2014-05-11T00:59:44", vStop);
    }

    @Test
    public void testSentinelPODOrbitFileOperations() throws Exception {

        String name = orbitFile.getName();
        final ProductData.UTC utcStart = Sentinel1OrbitFileReader.getValidityStartFromFilenameUTC(name);
        assertEquals(5258.6384375, utcStart.getMJD(), 0.00001);

        final ProductData.UTC utcEnd = Sentinel1OrbitFileReader.getValidityStopFromFilenameUTC(name);
        assertEquals(5258.775474537037, utcEnd.getMJD(), 0.00001);
    }
}
