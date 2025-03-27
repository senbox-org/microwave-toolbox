package org.jlinda.core;

import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.jlinda.core.io.ResFile;
import org.jlinda.core.utils.DateUtils;

import java.io.File;
import java.io.IOException;

import static org.jlinda.core.Constants.*;

public final class SLCImage {

    private MetadataElement abstractedMetadata;

    // sensor
    private String mission;
    private String sensor;
    private double radar_wavelength; // TODO: close this modifier

    // orbit
    private long orbitNumber;

    // geo & orientation
    private Point approxRadarCentreOriginal = new Point(); // use PixelPos as double!
    private final GeoPoint approxGeoCentreOriginal = new GeoPoint();
    private Point approxXYZCentreOriginal = new Point();
    private boolean nearRangeOnLeft = true;

    private double averageHeight;

    // azimuth annotations
    private double PRF;
    private double azimuthBandwidth;

    private double mjd;
    private double tAzi1;
    private double tAzi_original;
    private String azimuthWeightingWindow;
    private double lineTimeInterval;
    private double sceneCentreAzimuthTime;

    // range annotations
    private double rsr2x;
    private double rangeBandwidth;
    private double tRange1;
    private String rangeWeightingWindow;

    // ______ offset = X(l,p) - X(L,P) ______
    // ______ Where l,p are in the local slave coordinate system and ______
    // ______ where L,P are in the local master coordinate system ______
    // ______ These variables are stored in the slaveinfo variable only ______
    private int coarseOffsetP;          // offset in pixel (range) direction

    // oversampling factors
    private int ovsAz;                 // oversampling of SLC
    private int ovsRg;                 // oversampling of SLC

    // multilooking factors
    private int mlAz = 1;                 // multilooking of SLC
    private int mlRg = 1;                 // multilooking of SLC

    // relative to master geometry, or
    // absolute timing error of master
    // relative to master geometry, or
    // absolute timing error of master
    // timing errors
    private int azTimingError;        // timing error in azimuth direction

    // units: lines
    private int rgTimingError;        // timing error in range direction

    // units: pixels
    private boolean absTimingErrorFlag;   // FALSE if master time is NOT updated,

    // true if it is
    //    private static Rectangle originalWindow;       // position and size of the full scene
    Window originalWindow;       // position and size of the full scene
    Window currentWindow;        // position and size of the subset
    SlaveWindow slaveMasterOffsets;   // overlapping slave window in master coordinates
    public Doppler doppler;
    public boolean isBiStaticStack = false;

    public SLCImage() {

        this.sensor = "SLC_ERS";                    // default (vs. SLC_ASAR, JERS, RSAT)
        this.orbitNumber = 0;

        this.approxXYZCentreOriginal.x = 0.0;
        this.approxXYZCentreOriginal.y = 0.0;
        this.approxXYZCentreOriginal.z = 0.0;

        this.radar_wavelength = 0.0565646;          // [m] default ERS2

        this.mjd = 0.;
        this.tAzi1 = 0.0;                           // [s] sec of day
        this.tRange1 = 5.5458330 / 2.0e3;           // [s] one way, default ERS2
        this.rangeWeightingWindow = "HAMMING";
        this.rangeBandwidth = 15.55e6;              // [Hz] default ERS2

        this.PRF = 1679.902;                        // [Hz] default ERS2
        this.azimuthBandwidth = 1378.0;             // [Hz] default ERS2
        this.azimuthWeightingWindow = "HAMMING";

        this.rsr2x = 18.9624680 * 2.0e6;            // [Hz] default ERS2

        this.coarseOffsetP = 0;                     // by default

        this.ovsRg = 1;                             // by default
        this.ovsAz = 1;                             // by default

        this.absTimingErrorFlag = false;
        this.azTimingError = 0;                     // by default, unit lines
        this.rgTimingError = 0;                     // by default, unit pixels

        this.currentWindow = new Window(1, 25000, 1, 5000);
        this.originalWindow = new Window(1, 25000, 1, 5000);

        this.doppler = new Doppler();
        this.doppler.f_DC_a0 = 0.0;
        this.doppler.f_DC_a1 = 0.0;
        this.doppler.f_DC_a2 = 0.0;
//        f_DC_const = (actualDopplerChange() < maximumDopplerChange());


        this.slaveMasterOffsets = new SlaveWindow();
        this.abstractedMetadata = null;
    }

    public SLCImage(final MetadataElement element, final Product product) throws IOException {

        this();

        this.abstractedMetadata = element;
        this.sensor = element.getAttributeString(AbstractMetadata.MISSION);
        this.mission = sensor; // redundant parameter, for legacy use

        // orbit number
        this.orbitNumber = element.getAttributeInt(AbstractMetadata.REL_ORBIT);

        // units [meters]
        this.radar_wavelength = (LIGHT_SPEED / MEGA) / element.getAttributeDouble(AbstractMetadata.radar_frequency);

        // units [Hz]
        this.PRF = element.getAttributeDouble(AbstractMetadata.pulse_repetition_frequency);

        // zero doppler time to 1st pix of subset
        final String t_azi1_UTC = element.getAttributeUTC(AbstractMetadata.first_line_time).toString();
        this.mjd = element.getAttributeUTC(AbstractMetadata.first_line_time).getMJD();
        this.tAzi1 = DateUtils.dateTimeToSecOfDay(t_azi1_UTC);
        this.lineTimeInterval = element.getAttributeDouble(AbstractMetadata.line_time_interval);

        this.rangeBandwidth = element.getAttributeDouble(AbstractMetadata.range_bandwidth);
        this.azimuthBandwidth = element.getAttributeDouble(AbstractMetadata.azimuth_bandwidth);

        // 2 times range sampling rate [HZ]
        this.rsr2x = (element.getAttributeDouble(AbstractMetadata.range_sampling_rate) * MEGA * 2);

        // one way (!!!) time to first range pixels [sec]
        this.tRange1 = element.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel) / LIGHT_SPEED;

        this.approxRadarCentreOriginal.x = element.getAttributeDouble(AbstractMetadata.num_samples_per_line) / 2.0d;  // x direction is range!
        this.approxRadarCentreOriginal.y = element.getAttributeDouble(AbstractMetadata.num_output_lines) / 2.0d;  // y direction is azimuth

        if(product != null) {
            final GeoCoding geoCoding = product.getSceneGeoCoding();
            final PixelPos centerPos = new PixelPos(this.approxRadarCentreOriginal.x, this.approxRadarCentreOriginal.y);
            final GeoPos centerGeoPos = new GeoPos();
            geoCoding.getGeoPos(centerPos, centerGeoPos);

            this.approxGeoCentreOriginal.lat = centerGeoPos.lat;
            this.approxGeoCentreOriginal.lon = centerGeoPos.lon;

            this.nearRangeOnLeft = isNearRangeOnLeft(product);
            this.isBiStaticStack = isBiStaticStack(product);
        } else {
            this.approxGeoCentreOriginal.lat = (float) ((element.getAttributeDouble(AbstractMetadata.first_near_lat) +
                    element.getAttributeDouble(AbstractMetadata.first_far_lat) +
                    element.getAttributeDouble(AbstractMetadata.last_near_lat) +
                    element.getAttributeDouble(AbstractMetadata.last_far_lat)) / 4);

            this.approxGeoCentreOriginal.lon = (float) ((element.getAttributeDouble(AbstractMetadata.first_near_long) +
                    element.getAttributeDouble(AbstractMetadata.first_far_long) +
                    element.getAttributeDouble(AbstractMetadata.last_near_long) +
                    element.getAttributeDouble(AbstractMetadata.last_far_long)) / 4);
        }

        final double[] xyz = new double[3];
        Ellipsoid.ell2xyz(getApproxGeoCentreOriginal(), xyz);

        this.approxXYZCentreOriginal.x = xyz[0];
        this.approxXYZCentreOriginal.y = xyz[1];
        this.approxXYZCentreOriginal.z = xyz[2];

        // data windows: stored in windows structure
        final int pix0 = element.getAttributeInt(AbstractMetadata.subset_offset_x);
        final int pixN = pix0 + element.getAttributeInt(AbstractMetadata.num_samples_per_line);
        final int lin0 = element.getAttributeInt(AbstractMetadata.subset_offset_y);
        final int linN = lin0 + element.getAttributeInt(AbstractMetadata.num_output_lines);
        this.currentWindow = new Window(lin0, linN, pix0, pixN);

        // first set dopplers and get "original" 1st pixel time
        final AbstractMetadata.DopplerCentroidCoefficientList[] dopplersArray = AbstractMetadata.getDopplerCentroidCoefficients(element);
        if(dopplersArray.length == 0) {
            throw new IOException("Doppler Centroid Coefficients not found");
        }

        // original zero doppler time to 1st pix of (original) SLC
        final String t_azi_original = dopplersArray[0].time.toString();
        this.tAzi_original = DateUtils.dateTimeToSecOfDay(t_azi_original);

        if(dopplersArray[0].coefficients.length > 0)
            this.doppler.f_DC_a0 = dopplersArray[0].coefficients[0];
        if(dopplersArray[0].coefficients.length > 1)
            this.doppler.f_DC_a1 = dopplersArray[0].coefficients[1];
        if(dopplersArray[0].coefficients.length > 2)
            this.doppler.f_DC_a2 = dopplersArray[0].coefficients[2];
        this.doppler.checkConstant();

        this.mlAz = (int) element.getAttributeDouble(AbstractMetadata.azimuth_looks);
        this.mlRg = (int) element.getAttributeDouble(AbstractMetadata.range_looks);

        final double firstLineTimeInDays = element.getAttributeUTC(AbstractMetadata.first_line_time).getMJD();
        final double firstLineTime = (firstLineTimeInDays - (int) firstLineTimeInDays) * 86400.0;
        final double lastLineTimeInDays = element.getAttributeUTC(AbstractMetadata.last_line_time).getMJD();
        final double lastLineTime = (lastLineTimeInDays - (int) lastLineTimeInDays) * 86400.0;
        this.sceneCentreAzimuthTime = 0.5 * (firstLineTime + lastLineTime);
    }

    public MetadataElement getAbstractedMetadata() {
        return abstractedMetadata;
    }

    private boolean isNearRangeOnLeft(final Product product) {

        TiePointGrid incidenceAngle = OperatorUtils.getIncidenceAngle(product);
        if (incidenceAngle != null) {
            final double incidenceAngleToFirstPixel = incidenceAngle.getPixelDouble(0, 0);
            final double incidenceAngleToLastPixel = incidenceAngle.getPixelDouble(currentWindow.pixhi - 1, 0);
            return (incidenceAngleToFirstPixel < incidenceAngleToLastPixel);
        }
        return true;
    }

    private static boolean isBiStaticStack(final Product product) {
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        return absRoot != null && absRoot.getAttributeInt(AbstractMetadata.bistatic_stack, 0) == 1;
    }

    public void parseResFile(File resFileName) throws Exception {

        final ResFile resFile = new ResFile(resFileName);

        resFile.setSubBuffer("_Start_readfiles", "End_readfiles");

        this.orbitNumber = Long.parseLong(resFile.parseStringValue("Scene identification:").trim().split("\\s")[1]);
        this.radar_wavelength = resFile.parseDoubleValue("Radar_wavelength \\(m\\)");

        this.approxGeoCentreOriginal.lat = (float) resFile.parseDoubleValue("Scene_centre_latitude");
        this.approxGeoCentreOriginal.lon = (float) resFile.parseDoubleValue("Scene_centre_longitude");
        this.averageHeight = 0.0;

        this.approxXYZCentreOriginal = Ellipsoid.ell2xyz(Math.toRadians(approxGeoCentreOriginal.lat),
                Math.toRadians(approxGeoCentreOriginal.lon), averageHeight);

        // azimuth annotations
        this.PRF = resFile.parseDoubleValue("Pulse_Repetition_Frequency \\(computed, Hz\\)");
        this.azimuthBandwidth = resFile.parseDoubleValue("Total_azimuth_band_width \\(Hz\\)");
        this.tAzi1 = resFile.parseTimeValue("First_pixel_azimuth_time \\(UTC\\)");
        this.azimuthWeightingWindow = resFile.parseStringValue("Weighting_azimuth");

        this.mjd = resFile.parseDateTimeValue("First_pixel_azimuth_time \\(UTC\\)").getMJD();

        // range annotations
        this.rsr2x = resFile.parseDoubleValue("Range_sampling_rate \\(computed, MHz\\)") * 2 * MEGA;
        this.rangeBandwidth = resFile.parseDoubleValue("Total_range_band_width \\(MHz\\)") * MEGA;
        this.tRange1 = resFile.parseDoubleValue("Range_time_to_first_pixel \\(2way\\) \\(ms\\)") / 2 / 1000;
        this.rangeWeightingWindow = resFile.parseStringValue("Weighting_range");

        // data windows
        final int numberOfLinesTEMP = resFile.parseIntegerValue("Number_of_lines_original");
        final int numberOfPixelsTEMP = resFile.parseIntegerValue("Number_of_pixels_original");
        this.originalWindow = new Window(1, numberOfLinesTEMP, 1, numberOfPixelsTEMP);

        resFile.resetSubBuffer();
        resFile.setSubBuffer("_Start_crop", "End_crop");

        // current window
        this.currentWindow.linelo = resFile.parseIntegerValue("First_line \\(w.r.t. original_image\\)");
        this.currentWindow.linehi = resFile.parseIntegerValue("Last_line \\(w.r.t. original_image\\)");
        this.currentWindow.pixlo = resFile.parseIntegerValue("First_pixel \\(w.r.t. original_image\\)");
        this.currentWindow.pixhi = resFile.parseIntegerValue("Last_pixel \\(w.r.t. original_image\\)");

        resFile.resetSubBuffer();
        resFile.setSubBuffer("_Start_readfiles", "End_readfiles");
        // doppler
        this.doppler.f_DC_a0 = resFile.parseDoubleValue("Xtrack_f_DC_constant \\(Hz, early edge\\)");
        this.doppler.f_DC_a1 = resFile.parseDoubleValue("Xtrack_f_DC_linear \\(Hz/s, early edge\\)");
        this.doppler.f_DC_a2 = resFile.parseDoubleValue("Xtrack_f_DC_quadratic \\(Hz/s/s, early edge\\)");
        this.doppler.checkConstant();

        this.approxRadarCentreOriginal = new Point(originalWindow.pixels() / 2, originalWindow.lines() / 2);

    }

    /*---  RANGE CONVERSIONS ----*/

    // Convert pixel number to range time (1 is first pixel)
    public double pix2tr(double pixel) {
//        return tRange1 + ((pixel - 1.0) / rsr2x);
        if (nearRangeOnLeft) {
            return tRange1 + pixel / rsr2x;
        } else {
            return tRange1 + (currentWindow.pixhi - 1 - pixel) / rsr2x;
        }
    }

    // Convert pixel number to range (1 is first pixel)
    public double pix2range(double pixel) {
        return LIGHT_SPEED * pix2tr(pixel);
    }

    // Convert range time to pixel number (1 is first pixel)
    public double tr2pix(double rangeTime) {
//        return 1.0 + (rsr2x * (rangeTime - tRange1));
        if (nearRangeOnLeft) {
            return rsr2x * (rangeTime - tRange1);
        } else {
            return currentWindow.pixhi - 1 - rsr2x * (rangeTime - tRange1);
        }
    }

    /*---  AZIMUTH CONVERSIONS ---*/

    // Convert line number to azimuth time (1 is first line)
    public double line2ta(double line) {
        return tAzi1 + (line - 1) * lineTimeInterval;
//        return tAzi1 + ((line - 1.0) / PRF);
    }

    // Convert azimuth time to line number (1 is first line)
    public double ta2line(double azitime) {
        return (azitime - tAzi1) / lineTimeInterval;
//        return 1.0 + PRF * (azitime - tAzi1);
    }

    public Point lp2t(Point p) {
        return new Point(pix2tr(p.x), line2ta(p.y));
    }

    /*--- Getters and setters for Encapsulation ----*/
    public double getRadarWavelength() {
        return radar_wavelength;
    }

    public Point getApproxRadarCentreOriginal() {
        return new Point(approxRadarCentreOriginal);
    }

    public GeoPoint getApproxGeoCentreOriginal() {
        return new GeoPoint(approxGeoCentreOriginal);
    }

    public Point getApproxXYZCentreOriginal() {
        return new Point(approxXYZCentreOriginal);
    }

    public void setCurrentWindow(final Window window) {
        this.currentWindow = window;
    }

    public Window getCurrentWindow() {
        return new Window(currentWindow);
    }

    public Window getOriginalWindow() {
        return new Window(originalWindow);
    }

    public void setOriginalWindow(final Window window) {
        this.originalWindow = window;
        this.approxRadarCentreOriginal = new Point(originalWindow.pixels() / 2, originalWindow.lines() / 2);
    }
    public double getPRF() {
        return PRF;
    }

    public double getAzimuthBandwidth() {
        return azimuthBandwidth;
    }

    public int getCoarseOffsetP() {
        return coarseOffsetP;
    }

    public double gettRange1() {
        return tRange1;
    }

    public void settRange1(double tRange1) {
        this.tRange1 = tRange1;
    }

    public void settAzi1(double tAzi1) {this.tAzi1 = tAzi1;}

    public void setApproxGeoCentreOriginal(GeoPoint approxGeoCentreOriginal) {
        this.approxGeoCentreOriginal.lat = approxGeoCentreOriginal.lat;
        this.approxGeoCentreOriginal.lon = approxGeoCentreOriginal.lon;
        this.approxXYZCentreOriginal = Ellipsoid.ell2xyz(Math.toRadians(approxGeoCentreOriginal.lat),
                Math.toRadians(approxGeoCentreOriginal.lon), 0.0);
    }

    public double getRangeBandwidth() {
        return rangeBandwidth;
    }

    public void setRangeBandwidth(double rangeBandwidth) {
        this.rangeBandwidth = rangeBandwidth;
    }

    public double getRsr2x() {
        return rsr2x;
    }

    public void setRsr2x(double rsr2x) {
        this.rsr2x = rsr2x;
    }

    public void setCoarseOffsetP(int offsetP) {
        this.coarseOffsetP = offsetP;
    }

    public int getMlAz() {
        return mlAz;
    }

    public void setMlAz(int mlAz) {
        this.mlAz = mlAz;
    }

    public int getMlRg() {
        return mlRg;
    }

    public void setMlRg(int mlRg) {
        this.mlRg = mlRg;
    }

    public double getMjd() {
        return mjd;
    }

    public long getOrbitNumber() {
        return orbitNumber;
    }

    public String getSensor() {
        return sensor;
    }

    public String getMission() {
        return mission;
    }

    public int getOvsRg() {
        return ovsRg;
    }

    public void setOvsRg(int ovsRg) {
        this.ovsRg = ovsRg;
    }

    public void setSlaveMaterOffset() {
        this.slaveMasterOffsets = new SlaveWindow();
    }

    public SlaveWindow getSlaveMaterOffset() {
        return slaveMasterOffsets;
    }

    public void setSlaveMasterOffset(double ll00, double pp00, double ll0N, double pp0N, double llN0,
                                     double ppN0, double llNN, double ppNN) {

        this.slaveMasterOffsets = new SlaveWindow(ll00, pp00, ll0N, pp0N, llN0, ppN0, llNN, ppNN);

    }

    public double getSceneCentreAzimuthTime() { return this.sceneCentreAzimuthTime; }

    public class Doppler {

        // doppler
        // private static double[] f_DC; // TODO
        boolean f_DC_const_bool;
        double f_DC_a0;                // constant term Hz
        double f_DC_a1;                // linear term Hz/s
        double f_DC_a2;                // quadratic term Hz/s/s
        double f_DC_const;

        Doppler() {
            f_DC_const_bool = false;
            f_DC_a0 = 0;
            f_DC_a1 = 0;
            f_DC_a2 = 0;
            f_DC_const = 0;
        }

        public double getF_DC_a0() {
            return f_DC_a0;
        }

        public double getF_DC_a1() {
            return f_DC_a1;
        }

        public double getF_DC_a2() {
            return f_DC_a2;
        }

        public boolean isF_DC_const() {
            return f_DC_const_bool;
        }

        public double getF_DC_const() {
            return f_DC_const;
        }

//        public void setF_DC_const(boolean f_DC_const) {
//            this.f_DC_const_bool = f_DC_const;
//        }

        /*--- DOPPLER HELPER FUNCTIONS ---*/

        // critical value!
        private double maximumDopplerChange() {
            final double percent = 0.30; // 30% ~ 100 Hz or so for ERS
            return percent * Math.abs(PRF - azimuthBandwidth);
        }

        // actual doppler change
        private double actualDopplerChange() {
            final double slcFdc_p0 = pix2fdc(currentWindow.pixlo);
            final double slcFdc_p05 = computeFdc_const();
            final double slcFdc_pN = pix2fdc(currentWindow.pixhi);

            return Math.max(Math.abs(slcFdc_p0 - slcFdc_p05), Math.abs(slcFdc_p0 - slcFdc_pN));
        }

        private double computeFdc_const() {
            return pix2fdc((currentWindow.pixhi - currentWindow.pixlo) / 2);
        }

        private void checkConstant() {

            if (doppler.actualDopplerChange() < doppler.maximumDopplerChange()) {
                this.f_DC_const_bool = true;
            } else if (this.f_DC_a1 < EPS && this.f_DC_a2 < EPS) {
                this.f_DC_const_bool = true;
            }

            if (f_DC_const_bool) {
                f_DC_const = computeFdc_const();
            }

        }

        // Convert range pixel to fDC (1 is first pixel, can be ovs)
        public double pix2fdc(double pixel) {
            final double tau = (pixel - 1.0) / (rsr2x / 2.0);// two-way time
            return f_DC_a0 + (f_DC_a1 * tau) + (f_DC_a2 * FastMath.pow(tau, 2));
        }

    }

    // methods to compute range resolution
    // -----
    public double computeDeltaRange(double pixel) {
        return mlRg * (pix2range(pixel + 1) - pix2range(pixel));
    }

    public double computeDeltaRange(Point sarPixel) {
        return computeDeltaRange(sarPixel.x);
    }

    public double computeRangeResolution(double pixel) {
        return ((rsr2x / 2.) / rangeBandwidth) * (computeDeltaRange(pixel) / mlRg);
    }

    public double computeRangeResolution(Point sarPixel) {
        return computeRangeResolution(sarPixel.x);
    }

    public SLCImage clone() {
        SLCImage meta = new SLCImage();

        meta.abstractedMetadata = this.abstractedMetadata;
        meta.sensor = this.sensor;
        meta.mission = this.mission;
        meta.orbitNumber = this.orbitNumber;
        meta.radar_wavelength = this.radar_wavelength;
        meta.PRF = this.PRF;
        meta.mjd = this.mjd;
        meta.tAzi1 = this.tAzi1;
        meta.lineTimeInterval = this.lineTimeInterval;
        meta.rangeBandwidth = this.rangeBandwidth;
        meta.azimuthBandwidth = this.azimuthBandwidth;
        meta.rsr2x = this.rsr2x;
        meta.tRange1 = this.tRange1;
        meta.approxRadarCentreOriginal.x = this.approxRadarCentreOriginal.x;
        meta.approxRadarCentreOriginal.y = this.approxRadarCentreOriginal.y;
        meta.approxGeoCentreOriginal.lat = this.approxGeoCentreOriginal.lat;
        meta.approxGeoCentreOriginal.lon = this.approxGeoCentreOriginal.lon;
        meta.approxXYZCentreOriginal.x = this.approxXYZCentreOriginal.x;
        meta.approxXYZCentreOriginal.y = this.approxXYZCentreOriginal.y;
        meta.approxXYZCentreOriginal.z = this.approxXYZCentreOriginal.z;
        meta.currentWindow = new Window(currentWindow);
        meta.tAzi_original = this.tAzi_original;
        meta.doppler.f_DC_a0 = this.doppler.f_DC_a0;
        meta.doppler.f_DC_a1 = this.doppler.f_DC_a1;
        meta.doppler.f_DC_a2 = this.doppler.f_DC_a2;
        meta.doppler.f_DC_const_bool = this.doppler.f_DC_const_bool;
        meta.doppler.f_DC_const = this.doppler.f_DC_const;
        meta.mlAz = this.mlAz;
        meta.mlRg = this.mlRg;
        meta.nearRangeOnLeft = this.nearRangeOnLeft;
        meta.isBiStaticStack = this.isBiStaticStack;
        meta.sceneCentreAzimuthTime = this.sceneCentreAzimuthTime;

        return meta;
    }

    public static class SlaveWindow {

        public double l00, p00, l0N, p0N, lN0, pN0, lNN, pNN;

        SlaveWindow() {
            this.l00 = 0;
            this.p00 = 0;
            this.l0N = 0;
            this.p0N = 0;
            this.lN0 = 0;
            this.pN0 = 0;
            this.lNN = 0;
            this.pNN = 0;
        }

        SlaveWindow(double ll00, double pp00, double ll0N, double pp0N, double llN0,
                    double ppN0, double llNN, double ppNN) {
            this.l00 = ll00;
            this.p00 = pp00;
            this.l0N = ll0N;
            this.p0N = pp0N;
            this.lN0 = llN0;
            this.pN0 = ppN0;
            this.lNN = llNN;
            this.pNN = ppNN;
        }

        SlaveWindow(final SlaveWindow w) {
            l00 = w.l00;
            p00 = w.p00;
            l0N = w.l0N;
            p0N = w.p0N;
            lN0 = w.lN0;
            pN0 = w.pN0;
            lNN = w.lNN;
            pNN = w.pNN;
        }

    }
}
