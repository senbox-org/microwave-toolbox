package org.jlinda.core.geom;

import org.jlinda.core.Orbit;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;

import static org.jlinda.core.io.DataReader.readFloatData;

/**
 * User: pmar@ppolabs.com
 * Date: 6/16/11
 * Time: 4:11 PM
 */
@Ignore
public class
        DemTileTest {

    private static final File masterResFile = new File("/d2/etna_test/demTest/master.res");

    private static final double DELTA_08 = 1e-08;

    static DemTile demTile;

    static SLCImage masterMeta;
    static Orbit masterOrbit;
    static Window masterTileWindow;

    //public static Logger initLog() {
    //    String filePathToLog4JProperties = "log4j.properties";
    //    Logger logger = logger.getLogger(TopoPhase.class);
    //    PropertyConfigurator.configure(filePathToLog4JProperties);
    //    return logger;
   // }

    @Before
    public void setUp() throws Exception {

       //initLog();

        double lat0 = 0.68067840827778847;
        double lon0 = 0.24434609527920614;
        int nLatPixels = 3601;
        int nLonPixels = 3601;
        double latitudeDelta = 1.4544410433280261e-05;
        double longitudeDelta = 1.4544410433280261e-05;
        long nodata = -32768;

        // initialize
        demTile = new DemTile(lat0,lon0,nLatPixels,nLonPixels,latitudeDelta,longitudeDelta,nodata);

        // initialize masterMeta
        masterMeta = new SLCImage();
        masterMeta.parseResFile(masterResFile);

        masterOrbit = new Orbit();
        masterOrbit.parseOrbit(masterResFile);
        masterOrbit.computeCoefficients(3);

        masterTileWindow = new Window(10000, 10127, 1500, 2011);

    }

    @Test
    public void testDemStats() throws Exception {

        // load test data
        String testDataDir = "/d2/etna_test/demTest/";
        String bufferFileName;

        final int nRows = 418;
        final int nCols = 532;

        bufferFileName = testDataDir + "dem_full_input.r4.swap";
        float[][] demBuffer = readFloatData(bufferFileName, nRows, nCols).toArray2();

        double[][] demBufferDouble = new double[nRows][nCols];
        // convert to double
        for (int i = 0; i < demBuffer.length; i++) {
            for (int j = 0; j < demBuffer[0].length; j++) {
                demBufferDouble[i][j] = (double) demBuffer[i][j];
            }
        }

        Assert.assertFalse(demTile.statsComputed);

        demTile.setData(demBufferDouble);
        demTile.stats();

        Assert.assertTrue(demTile.statsComputed);

        double demMin_EXPECTED = -10;
        Assert.assertEquals(demMin_EXPECTED, demTile.minValue, DELTA_08);

        double demMax_EXPECTED = 2051;
        Assert.assertEquals(demMax_EXPECTED, demTile.maxValue, DELTA_08);

        double demMean_EXPECTED = 237.479116451416;
        Assert.assertEquals(demMean_EXPECTED, demTile.meanValue, DELTA_08);


    }
}