package org.jlinda.core.geom;

import org.esa.snap.core.util.SystemUtils;

import java.util.logging.Logger;

public class DemTile {

    //// logger
    static Logger logger = SystemUtils.LOG;

    //// topoPhase global params
    double lat0;
    double lon0;
    long nLatPixels;
    long nLonPixels;

    double lat0_ABS;
    double lon0_ABS;
    long nLatPixels_ABS;
    long nLonPixels_ABS;

    double latitudeDelta;
    double longitudeDelta;

    // no data value
    Double noDataValue;

    //// actual demTileData
    double[][] data;

    /// extent : coordinates in radians
    double lambdaExtra;
    double phiExtra;

    /// tile coordinates in phi,lam
    boolean cornersComputed = false;

    public double phiMin;
    public double phiMax;
    public double lambdaMin;
    public double lambdaMax;

    /// tile index
    int indexPhi0DEM;
    int indexPhiNDEM;
    int indexLambda0DEM;
    int indexLambdaNDEM;

    //// tile stats
    boolean statsComputed = false;
    long totalNumPoints;
    long numNoData;
    long numValid;
    double meanValue;
    double minValue;
    double maxValue;


    public DemTile(double lat0, double lon0, long nLatPixels, long nLonPixels,
                   double latitudeDelta, double longitudeDelta, double noDataValue) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.nLatPixels = nLatPixels;
        this.nLonPixels = nLonPixels;
        this.latitudeDelta = latitudeDelta;
        this.longitudeDelta = longitudeDelta;
        this.noDataValue = noDataValue;
    }

    public void setLatitudeDelta(double latitudeDelta) {
        this.latitudeDelta = latitudeDelta;
    }

    public void setLongitudeDelta(double longitudeDelta) {
        this.longitudeDelta = longitudeDelta;
    }

    public void setNoDataValue(double noDataValue) {
        this.noDataValue = noDataValue;
    }

    public double[][] getData() {
        return data;
    }

    public void setData(double[][] data) {
        this.data = data;
    }

    // ----- Loop over DEM for stats ------------------------
    public void stats() throws Exception {

        // inital values
//        double min_dem_buffer = 100000.0;
//        double max_dem_buffer = -100000.0;
        double min_dem_buffer = data[0][0];
        double max_dem_buffer = data[0][0];

        if (noDataValue.equals(max_dem_buffer)) {
            max_dem_buffer = 0;
        }
        if (noDataValue.equals(min_dem_buffer)) {
            min_dem_buffer = 0;
        }

        try {
            totalNumPoints = data.length * data[0].length;
            for (double[] aData : data) {
                for (int j = 0; j < data[0].length; j++) {
                    if (!noDataValue.equals(aData[j])) {
                        numValid++;
                        meanValue += aData[j];           // divide by numValid later
                        if (aData[j] < min_dem_buffer)
                            min_dem_buffer = aData[j];
                        if (aData[j] > max_dem_buffer)
                            max_dem_buffer = aData[j];
                    } else {
                        numNoData++;
//                        System.out.println("dataValue = " + aData[j]);
                    }
                }
            }
        } catch (Exception e) {
            logger.severe("Something went wrong when computing DEM tile stats");
            logger.severe("Is DEM tile declared?");
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }

        //global stats
        minValue = min_dem_buffer;
        maxValue = max_dem_buffer;
        meanValue /= numValid;

        statsComputed = true;
        showStats();

    }

    private void showStats() {

        if (statsComputed) {
            System.out.println("DEM Tile Stats");
            System.out.println("------------------------------------------------");
            System.out.println("Total number of points: " + totalNumPoints);
            System.out.println("Number of valid points: " + numValid);
            System.out.println("Number of NODATA points: " + numNoData);
            System.out.println("Max height in meters at valid points: " + maxValue);
            System.out.println("Min height in meters at valid points: " + minValue);
            System.out.println("Mean height in meters at valid points: " + meanValue);
        } else {
            System.out.println("DEM Tile Stats");
            System.out.println("------------------------------------------------");
            System.out.println("DemTile.stats() method not invoked!");
        }

    }
}
