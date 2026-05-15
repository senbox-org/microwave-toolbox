/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.biomass.gpf;

import com.bc.ceres.core.ProgressMonitor;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ProcessTimeMonitor;

import java.awt.*;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

// TODO
// DONE 1_) Use adaptive threshold and window size for sigma0_df (2nd last paragraph of [3]
// 2_) Use N in eg.7 in [1] as quality flag put that in a band (last paragraph of 4 in [2])
// 3_) When looping through pixels in square window, start from centre line and goes outwards, when hit a pixel that
//     is outside circle window, can skip ahead to next line
// DONE 4_) Let user choose mean, median or mode? (In 3.2.2 of [3], mean is used instead of median, so...)
// DONE 5_) Let user pick the polarization to process
// 6_) unit test
// 7_) aggregator operator
// DONE 8_) Spin off median, mean and mode
// DONE 9_) Error checking for UI parameters

/**
 * Forest Growing Stock Volume (GSV) can be retrieved from SAR backscatter using a multi-temporal approach
 * independent of in-situ data.
 */

@OperatorMetadata(alias = "BIOMASAR",
        category = "Radar/Biomass",
        authors = "Cecilia Wong",
        copyright = "Copyright (C) 2016 by Array Systems Computing Inc.",
        description = "Performs BIOMASAR algorithm to retrieve GSV")
public class BIOMASAROp extends Operator {

    //
    // The papers...
    //
    // [1] Retrieval of growing stock volume in boreal forest using hyper-temporal series of
    // Envisat ASAR ScanSAR backscatter measurements
    // Maurizio Santoro et al 2011  (Santoro_et_al_2011.pdf)
    //
    // [2] ESTIMATES OF FOREST GROWING STOCK VOLUME OF THE NORTHERN HEMISPHERE FROM ENVISAT ASAR
    // Maurizio Santoro et al  (2849916santoro.pdf)
    //
    // [3] Estimates of Forest Growing Stock Volume for Sweden, Central Siberia, and Québec Using Envisat Advanced
    // Synthetic Aperture Radar Backscatter Data
    // Maurizio Santoro et al 2013  (remotesensing-05-04503.pdf)
    //

    // In [1], end of 6.2, "A dataset consisting of 60 - 100 backscatter measurements seemed sufficient to reach
    // a critical mass of data, under the assumption that repeated acquisitions were collected during all seasons.".

    // In paragraph 5 of 5.2 in [1], it says VCF of above 70% corresponds to GSV above 150 or 200 m^3/ha depending on
    // study area.

    // The test/study area should probably contain a decent amount of dense forest. See 2nd paragraph of 5.2 in [1].
    // For the algorithm to work, the window around the pixel has to contain sufficient ground and dense forest pixels.

    // There is one source product containing
    // - a stack of sigma0 (backscatter) bands (possibly of different polarizations) that are calibrated, geocoded and
    //   co-registered SAR backscatter images; and
    // - one vegetation mask band (to mask out the non-vegetation pixels)
    // - one tree cover percentage band
    @SourceProduct
    Product sourceProduct;

    // TODO: In the target product, for debugging/testing purpose, there is an an error code band for each GSV band and
    // the tree cover percentage band is copied to the target product.

    // There is one target product containing one band of forest GSV for each polarization (selected to be processed
    // by the user) of the source sigma0 bands
    @TargetProduct
    Product targetProduct;

    //
    // User parameters...
    //
    // Default value is taken from here: set in @Parameter(defaultValue = "...").
    // Label is set in BIOMASAROpUI when DialogUtils.addComponent() is called.
    // Unit is included in the label.
    // Description is set in BIOMASAROpUI using JLabel.setToolTipText().
    // See BIOMASAROpUI.createPanel().
    //

    // For debugging...
    //
    @Parameter(defaultValue = "true")
    private boolean useDB = true;
    // ... for debugging

    // Minimum number of observations which is N in Eq. 7 in [1].
    @Parameter(description = "not used", defaultValue = "20", label = "not used", unit = "not used")
    private int minObservations = 20; // Last paragraph of 6.2 in [1] uses 20

    // Minimum weight (i.e., sigma0 contrast = sigma0_veg - sigma0_gr) which is w_i in E.q. 7 in [1]
    @Parameter(defaultValue = "0.5")
    private float minWeight = 0.5f; // dB; 2nd last paragraph of 3.2.3 in [3] uses 0.5

    // User might want to fiddle with the range of retrievable GSV (4th paragraph of 5 in [2]).
    //
    // Minimum retrievable GSV in Fig. 4 in [1]. GSV is the volume of the tree stems for all living species per unit
    // area (1st paragraph of 1 in [1]). So absolute minimum is zero.
    @Parameter(defaultValue = "0.0")
    private float minRetrievableGSV = 0.0f; // m^3/ha
    //
    // Maximum retrievable GSV in Fig. 4 in [1]. It is defined to be V_df + 50 at the end of 6.1 in [1].
    // Let user choose the offset from V_DF.
    @Parameter(defaultValue = "50.0")
    private float maxRetrievableGSVOffsetFromVdf = 50.0f; // m^3/ha; End of 6.1 in [1] uses 50.

    // TODO
    @Parameter(defaultValue = "200.0")
    private float vDF = 250.0f;

    // Retrieval GSV from sigma0 bands in HH polarization
    @Parameter(defaultValue = "true")
    private boolean retrieveHH = true;

    // Retrieval GSV from sigma0 bands in VV polarization
    @Parameter(defaultValue = "false")
    private boolean retrieveVV = false;

    // Retrieval GSV from sigma0 bands in HV polarization
    @Parameter(defaultValue = "false")
    private boolean retrieveHV = false;

    // Retrieval GSV from sigma0 bands in VH polarization
    @Parameter(defaultValue = "false")
    private boolean retrieveVH = false;

    // This is the range of backscatter values (dB) handled by the model (above Fig.4 in 4.3 of [1])
    // It is not clear what the values should be. [1] uses [-10, -8] dB.
    // In Fig.4 itself, it looks like the end of the range is -8.3 dB.
    // The backscatter range depends on the polarization.
    //
    // This is how it should be for the model to work...
    // -10 = min_sigma0 < sigma0_gr_dB < sigma0_for_dB < sigma0_DF_dB < sigma0_veg_dB < max_sigma0 = -8
    //
    // Minimum and maximum sigma0 handled by model for a polarization
    //
    @Parameter(defaultValue = "-10")
    private float minSigma0HH = -999f; // dB
    //
    @Parameter(defaultValue = "-8")
    private float maxSigma0HH = 999f; // dB
    //
    @Parameter(defaultValue = "-999")
    private float minSigma0VV = -999f; // dB
    //
    @Parameter(defaultValue = "999")
    private float maxSigma0VV = 999f; // dB
    //
    @Parameter(defaultValue = "-999")
    private float minSigma0HV = -999f; // dB
    //
    @Parameter(defaultValue = "999")
    private float maxSigma0HV = 999f; // dB
    //
    @Parameter(defaultValue = "-999")
    private float minSigma0VH = -999f; // dB
    //
    @Parameter(defaultValue = "999")
    private float maxSigma0VH = 999f; // dB

    // This is the buffer zone width (dB) of the sigma0 range (4.3 of [1], Fig.4 in [1]).
    // It is assumed to be the residual speckle noise component which can be estimated from the ENL
    // (Equivalent Number of Looks) (above Fig.4 in [1]).
    // It is not clear what the values should be. [1] uses approx. 0.5 dB in Fig.4.
    // See also end of 4.1.1 in [1], "The Equivalent Number of Looks (ENL) (Oliver & Quegan, 1998) after speckle
    // filtering was ≥60. The corresponding residual speckle noise was therefore less than 0.6 dB."
    //
    // Width of the buffer zone at the two ends of the sigma0 range for that polarization
    //
    @Parameter(defaultValue = "0.5")
    private float sigma0BufferZoneWidthHH = 0.5f; // dB; [1] uses approx. 0.5 dB in Fig.4.
    //
    @Parameter(defaultValue = "0.5")
    private float sigma0BufferZoneWidthVV = 0.5f; // dB
    //
    @Parameter(defaultValue = "0.5")
    private float sigma0BufferZoneWidthHV = 0.5f; // dB
    //
    @Parameter(defaultValue = "0.5")
    private float sigma0BufferZoneWidthVH = 0.5f; // dB

    // For sigma0_gr estimation
    //
    // Adaptive window radius and VCF threshold (Fig. 6 in [1])
    //
    // Window radius
    // If a fixed window radius is desired, set initWindowRadiusGr == maxWindowRadiusGr (windowRadiusStepSizeGr can be >= 0, it will be ignored).
    //
    @Parameter(defaultValue = "50")
    private int initWindowRadiusGr = 50; // pixels; Fig. 6 in [1], 3rd paragraph in 5.1 of [1]
    //
    @Parameter(defaultValue = "50")
    private int windowRadiusStepSizeGr = 50; // pixels; [1] does not specify step size; can play with it to find an optimal value
    //
    @Parameter(defaultValue = "200")
    private int maxWindowRadiusGr = 200; // pixels; end of 3rd paragraph in 5.1 of [1]; depends on the proportion of un-vegetated areas within the area of study (2nd last paragraph in 5.1 of [1])
    //
    // VCF threshold
    // If a fixed VCF threshold is desired, set initVCFThresholdGr == maxVCFThresholdGr (vcfThresholdStepSizeGr can be >= 0, it will be ignored).
    //
    @Parameter(defaultValue = "15")
    private float initVCFThresholdGr = 15.0f; // %; Fig. 6 in [1], 3rd paragraph in 5.1 of [1]
    //
    @Parameter(defaultValue = "5")
    private float vcfThresholdStepSizeGr = 5.0f; // %; [1] does not specify step size; can play with it to find an optimal value
    //
    @Parameter(defaultValue = "35")
    private float maxVCFThresholdGr = 35.0f; // %; 2nd last paragraph in 5.1 of [1]; but 3rd paragraph of 5.1 says 25%?
    //
    // Minimum Ground Pixels Percentage (2 tries); end of 2nd last paragraph in 5.1 of [1].
    // If only one try is desired, set the percentage for 2nd try to be equal to that for the 1st try.
    //
    @Parameter(defaultValue = "2.0")
    private float minPixelsPercentageGr1stTry = 2.0f; // %; end of 2nd last paragraph in 5.1 of [1]
    //
    @Parameter(defaultValue = "1.0")
    private float minPixelsPercentageGr2ndTry = 1.0f; // %; end of 2nd last paragraph in 5.1 of [1]
    //
    // Central statistic measure to use
    //
    static public final String USE_MEAN = "Use mean";
    static public final String USE_MEDIAN = "Use median";
    static public final String USE_MODE = "Use mode";
    //
    @Parameter(valueSet = {USE_MEAN, USE_MEDIAN, USE_MODE}, defaultValue = USE_MEDIAN)
    private String centralStatsMeasureGr = USE_MEDIAN; // 2nd paragraph of 5.1 in [1]

    // For sigma0_df estimation
    //
    // Window radius
    // In 5.2 of [1], the window radius is fixed at 100 pixels
    // In 3.2.2 of [3], it says that the VCF threshold and window size are both adaptive.
    // For adaptive algorithm, see Fig. 6 in [1].
    // To achieve fixed window radius, set initWindowRadiusDF == maxWindowRadiusDF (windowRadiusStepSizeDF can be >0, it will be ignored)
    //
    @Parameter(defaultValue = "50")
    private int initWindowRadiusDF = 50; // pixels; in 5.2 of [1], the window radius is fixed at 100 pixels; starts at 50 seems reasonable
    //
    @Parameter(defaultValue = "50")
    private int windowRadiusStepSizeDF = 50; // pixels; [1] does not specify step size; can play with it to find an optimal value
    //
    @Parameter(defaultValue = "200")
    private int maxWindowRadiusDF = 200; // pixels; in 5.2 of [1], the window radius is fixed at 100 pixels; stops at 200 seems reasonable
    //
    // VCF threshold
    // In 5.2 of [1], the the VCF threshold is a percentage of the maximum VCF in estimation window.
    // In 3.2.2 of [3], it says that the VCF threshold and window size are both adaptive. But it is
    // actually just referencing [1].
    //
    // Using the latter and setting the min VCF threshold to a low value may result in more
    // pixels being labelled as dense forest than using the former.
    // The problem with the former is a single very high VCF value in the window will cause the threshold to be so
    // high that no other pixel in the window will qualify as dense forest. E.g., say all the pixels in the
    // window have VCF below 65% with one pixel at 90%. Then the threshold will be 90%*percentageOfMaxVCF.
    // If percentageOfMaxVCF is 75%, then the threshold will be 67.5% and only that one pixel at 90% will be
    // labelled as dense forest.
    //
    // In the former, we can possibly make the percentage of max VCF adaptive.
    // Start at 75% and step down to 65% in steps of 5%. This has not been implemented.
    //
    // If a fixed VCF threshold is desired, choose VCF_THRESHOLD_USE_ADAPTIVE and set
    // initVCFThresholdDF == minVCFThresholdDF (vcfThresholdStepSizeDF can be >= 0, it will be ignored).
    //
    static public final String VCF_THRESHOLD_USE_ADAPTIVE = "Use adaptive VCF threshold";
    static public final String VCF_THRESHOLD_USE_PERCENTAGE_OF_MAX = "Use percentage of max VCF as threshold";
    //
    @Parameter(valueSet = {VCF_THRESHOLD_USE_ADAPTIVE, VCF_THRESHOLD_USE_PERCENTAGE_OF_MAX},
            description = "Choice of VCF threshold for sigma0 estimation for dense forest pixels",
            defaultValue = VCF_THRESHOLD_USE_ADAPTIVE, label = "Dense forest VCF Threshold")
    private String dfVCFThresholdToUse = VCF_THRESHOLD_USE_ADAPTIVE; // Adaptive is likely to result in more dense forest pixels

    private enum VCFThreshold {VCF_THRESHOLD_ADAPTIVE, VCF_THRESHOLD_PERCENTAGE_OF_MAX}

    private VCFThreshold vcfThreshold;

    //
    // Adaptive VCF threshold parameters
    //
    @Parameter(defaultValue = "80")
    private float initVCFThresholdDF = 80.0f; // %; [3] does not specify what this should be; but paragraph 5 in 5.2 of [1] suggests that it should be > 70%
    //
    @Parameter(defaultValue = "5")
    private float vcfThresholdStepSizeDF = 5.0f; // %; [3] does not specify step size; can play with it to find an optimal value
    //
    @Parameter(defaultValue = "70")
    private float minVCFThresholdDF = 70.0f; // %; [3] does not specify what this should be; but paragraph 5 in 5.2 of [1] suggests that it should be > 70%
    //
    // Percentage of maximum VCF parameters
    //
    // VCF Threshold is this percentage of the maximum VCF inside the estimation window
    @Parameter(defaultValue = "75")
    private float percentageOfMaxVCF = 75.0f; // %; #2 in the end of 5.2 in [1]
    //
    // Minimum Dense Forest Pixels Percentage (2 tries); end of 2nd last paragraph in [1].
    // If only one try is desired, set the percentage for 2nd try to be equal to that for the 1st try.
    //
    @Parameter(defaultValue = "2.0")
    private float minPixelsPercentageDF1stTry = 2.0f; // %
    //
    @Parameter(defaultValue = "1.0")
    private float minPixelsPercentageDF2ndTry = 1.0f; // %
    //
    // Central statistic measure to use
    //
    @Parameter(valueSet = {USE_MEAN, USE_MEDIAN, USE_MODE}, defaultValue = USE_MEAN)
    private String centralStatsMeasureDF = USE_MEAN;

    //
    // Other parameters, variables, classes etc. ...
    //

    static final String TARGET_PRODUCT_NAME = "BIOMASAR GSV";

    // Source sigma0 bands
    // Calibrated, geocoded and co-registered (bullet 1 in 1st paragraph of 4 in [1])
    static final String SIGMA0_BAND_NAME_KEYWORD = "sigma0";
    // -- All possible polarizations in the source sigma0 bands
    static final String HH = "hh";
    static final String VV = "vv";
    static final String HV = "hv";
    static final String VH = "vh";
    public static final ArrayList<String> allPols = new ArrayList<String>() {{
        add(HH);
        add(VV);
        add(HV);
        add(VH);
    }};
    // -- Maps a pol, e.g., HH to an array of source sigma0 bands in that polarization (key should be one of "allPols")
    final HashMap<String, ArrayList<Band>> sourceSigma0BandsMap = new HashMap<>();

    // TODO Source vegetation mask band
    // From 4 in [2]...
    // The backscatter from urban or water or snow/ice land-cover classes strongly impacts the model training by
    // causing an offset of the model parameters; hence, they need to be masked out a priori. The urban mask was
    // based on the MODIS map of global urban extent [12]. The water mask was based on the water class in the
    // GlobCover 2005 land cover dataset [13] below 60°N and the GLC2000 land cover dataset [14] elsewhere.
    // Snow/ice pixels were identified by means of the GLC2000 map.
    static final String VEG_MASK_BAND_NAME = "vegetation_mask";
    Band vegMaskBand = null;

    // Use MODIS Vegetation Continuous Fields (VCF) tree cover product. See [1].
    static final String TREE_COVER_PERCENTAGE_BAND_NAME_KEY_WORDS = "tree cover percentage";
    Band treeCoverPercentageBand = null;

    // Target GSV bands
    final HashMap<String, Boolean> retrievePP = new HashMap<>(); // key should be one of "allPols"
    static final String GSV_BAND_NAME_KEYWORD = "GSV";
    static final String GSV_UNIT = "m^3/ha";
    final ArrayList<Band> targetGSVBands = new ArrayList<>();

    static final float INVALID_FLOAT = Float.NaN;
    static final double INVALID_DOUBLE = Double.NaN;

    // It is assumed that all sigma0 bands will have the same values as their NO_DATA_VALUE.
    double SIGMA0_NO_DATA_VALUE = Double.NaN;

    // TODO All these parameter values below need to be confirmed.

    // This is a constant.
    static final float BETA = 0.006f; // ha/m^3; 4.2 in [1], 3rd last paragraph]

    // TODO This likely will have to be a source band. Not sure how to fine tune this.
    // From 2nd last paragraph of 4.2 in [1],
    // - V_df represents the GSV level typical for dense forests within the area to which the pixels belong
    // - Attaching one value of V_df to an area of the size of a region, i.e., of several thousands of km^2, is
    //   reasonable considering the variability of GSV for the densest forests is small at kilometric resolution.
    //
    // From 6.1 in [1] and 3rd paragraph of [2], an offset in V_df will result in a systematic bias in the
    // retrieved GSV.
    //
    // From 3rd paragraph of [2]...
    // "If the retrieved GSV aggregated at very coarse scale (e.g. 2° or more) appeared systematically biased over a
    // large area spanning several tiles with respect to a reference dataset of GSV, the GSV of dense forest had to
    // be fine tuned until the bias was minimized."
    //
    // "By definition, V_df corresponds to the 90th percentile of the GSV distribution in the area of interest" from
    // last paragraph of 3.2.2 in [3]. This paragraph talks about (very vaguely) how to obtain this value.
    //
    // V_df is the highest GSV in the area of interest (#3 in end of 5.2 in [1]).
    // It can be obtained from records of regional and national forest inventory statistics (end of 2nd last
    // paragraph of 5.2 in [1]). See also 3rd paragraph of 2 in [2] where it discusses if continuous V_df is
    // available as raster data...
    // "Where continuous fields of GSV were available in form of a raster dataset, the GSV of dense forest
    // corresponds to the 90th percentile of the histogram [.]. The value was set empirically when only
    // statistics from inventory reports were available. It is assumed that a single value of GSV of dense
    // forest is representative for a 2° × 2° area. To obtain a smooth transition of the GSV of dense forest
    // between adjacent tiles, the individual values were interpolated using a bilinear function. An offset in
    // the estimate of GSV of dense forest translated to a systematic bias of the retrieval [.]."
    //static final float V_DF = 250.0f; // m^3/ha; 2nd last paragraph of 4.2 in [1] suggests V_df is 300 or 150 and Fig.4 in [1] has 300 as max retrievable GSV which equals V_df + 50 from E.q.(9) in [1].

    // The term e^(-beta * V_df) in Eq.5 in [1]
    //static final float E_EXP_TERM = (float) Math.exp(-BETA * V_DF);
    private float eExpTerm = 0f;

    // In Fig.4 of [1], -10 dB is the minimum (start) sigma0 value handled by the model while -8 is the maximum (end).
    //
    // Maps a pol, e.g., HH to the start value of the modelled sigma0 range in that polarization
    final HashMap<String, Float> modelledSigma0StartMap = new HashMap<>(); // key should be one of "allPols"
    //
    // Maps a pol, e.g., HH to the end value of the modelled sigma0 range in that polarization
    final HashMap<String, Float> modelledSigma0EndMap = new HashMap<>(); // key should be one of "allPols"
    //
    // Maps a pol, e.g., HH to the buffer zone width at the two ends of the modelled sigma0 range in that polarization
    final HashMap<String, Float> bufferZoneWidthMap = new HashMap<>(); // key should be one of "allPols"

    private static class SquareWindow {

        // The start/end points for a square window centred at a given (x, y) with side equal to
        // (windowRadius*2 + 1).
        // x and y are wrt the whole raster image, not a tile.
        // x and y starts counting from zero.

        private int startX;
        private int startY;
        private int endX;
        private int endY;

        private SquareWindow(final int x, final int y, final int windowRadius, int w, int h) {
            startX = Math.max(0, x - windowRadius);
            startY = Math.max(0, y - windowRadius);
            endX = Math.min(x + windowRadius, w - 1);
            endY = Math.min(y + windowRadius, h - 1);
        }
    }

    //
    // For debugging...
    //
    final static short ERR_NOT_VEG = 1;
    final static short ERR_INSUFFICIENT_GR_PIXELS = 2;
    final static short ERR_INSUFFICIENT_DF_PIXELS = 3;
    final static short ERR_INSUFFICIENT_OBSERVATIONS = 4;
    final HashMap<String, Band> targetErrorCodeBands = new HashMap<>(); // key should be one of "allPols"
    //
    // To process just one pixel with debug text printed to out1, set debugX and debugY to be the x and y of that pixel.
    // debugX or debugY < 0 means process all pixels, i.e., no debugging.
    final int debugX = -999;
    final int debugY = -999;
    PrintStream out1 = null;
    // ... for debugging


    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.snap.core.datamodel.Product} annotated with the
     * {@link org.esa.snap.core.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.snap.core.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

//        if (debugX >= 0 && debugY >= 0) {
//            try {
//                out1 = new PrintStream(new FileOutputStream("C:\\rstb_products\\output.txt"));
//            } catch (FileNotFoundException e) {
//                System.out.println("DEBUG: computeInitialClusterCenters: failed to open file");
//            }
//        }

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfSARProduct();
            validator.checkIfCoregisteredStack();

            // Error checking for UI parameters

            if (minObservations <= 1) {
                System.out.println("BIOMASAROp.Initialize: WARNING UI param Minimum observations = " + minObservations + " should be > 1");
            }

            if (minWeight <= 0f) {
                System.out.println("BIOMASAROp.Initialize: WARNING UI param Minimum weight = " + minWeight + " should be > 0");
            }

            if (minRetrievableGSV < 0f) {
                System.out.println("BIOMASAROp.Initialize: WARNING UI param Minimum retrievable GSV = " + minRetrievableGSV + " should be at least 0");
            }

            if (maxRetrievableGSVOffsetFromVdf <= 0f) {
                System.out.println("BIOMASAROp.Initialize: WARNING UI param Maximum retrievable GSV offset from V_df = " + maxRetrievableGSVOffsetFromVdf + " should be > 0");
            }

            if (vDF <= 0f) {
                System.out.println("BIOMASAROp.Initialize: WARNING UI param vDF = " + vDF + " should be > 0");
            }

            // window radius ground pixels
            //
            if (initWindowRadiusGr < 1) {
                throw new OperatorException("Initial window radius for ground pixels = " + initWindowRadiusGr + " must be at least 1");
            }
            //
            if (maxWindowRadiusGr < initWindowRadiusGr) {
                throw new OperatorException("Max window radius for ground pixels = " + maxWindowRadiusGr + " cannot be < initial window radius = " + initWindowRadiusGr);
            }
            //
            if (windowRadiusStepSizeGr < 0) {
                throw new OperatorException("Window radius step size for ground pixels = " + windowRadiusStepSizeGr + " cannot be negative");
            } else if (windowRadiusStepSizeGr == 0) {
                if (maxWindowRadiusGr != initWindowRadiusGr) {
                    throw new OperatorException("Window radius step size for ground pixels cannot be zero if initial and maximum window radius are not equal");
                }
            }

            // VCF threshold ground pixels
            //
            if (initVCFThresholdGr <= 0f || initVCFThresholdGr >= 100f) {
                throw new OperatorException("Initial VCF threshold for ground pixels = " + initVCFThresholdGr + " must be in (0, 100) %");
            }
            //
            if (maxVCFThresholdGr < initVCFThresholdGr) {
                throw new OperatorException("Max VCF threshold for ground pixels = " + maxVCFThresholdGr + " cannot be < initial VCF threshold = " + initVCFThresholdGr);
            }
            //
            if (vcfThresholdStepSizeGr < 0f) {
                throw new OperatorException("VCF threshold step size for ground pixels = " + vcfThresholdStepSizeGr + " cannot be negative");
            } else if (vcfThresholdStepSizeGr == 0) {
                if (initVCFThresholdGr != maxVCFThresholdGr) {
                    throw new OperatorException("VCF threshold step size for ground pixels cannot be zero if initial and maximum VCF thresholds are not equal");
                }
            }

            // minimum percentage of ground pixels
            //
            if (minPixelsPercentageGr1stTry <= 0f || minPixelsPercentageGr1stTry >= 100f) {
                throw new OperatorException("Minimum percentage of ground pixels (1st try) = " + minPixelsPercentageGr1stTry + " must be in (0, 100) %");
            }
            //
            if (minPixelsPercentageGr2ndTry <= 0f || minPixelsPercentageGr2ndTry >= 100f) {
                throw new OperatorException("Minimum percentage of ground pixels (2nd try) = " + minPixelsPercentageGr2ndTry + " must be in (0, 100) %");
            }
            //
            if (minPixelsPercentageGr2ndTry >= minPixelsPercentageGr1stTry) {
                throw new OperatorException("Minimum percentage of ground pixels for 2nd try = " + minPixelsPercentageGr2ndTry + " should be < 1st try = " + minPixelsPercentageGr1stTry);
            }

            // window radius dense forest pixels
            //
            if (initWindowRadiusDF < 1) {
                throw new OperatorException("Initial window radius for dense forest pixels = " + initWindowRadiusDF + " must be at least 1");
            }
            //
            if (maxWindowRadiusDF < initWindowRadiusDF) {
                throw new OperatorException("Max window radius for dense forest pixels = " + maxWindowRadiusDF + " cannot be < initial window radius = " + initWindowRadiusDF);
            }
            //
            if (windowRadiusStepSizeDF < 0) {
                throw new OperatorException("Window radius step size for dense forest pixels = " + windowRadiusStepSizeDF + " cannot be negative");
            } else if (windowRadiusStepSizeDF == 0) {
                if (maxWindowRadiusDF != initWindowRadiusDF) {
                    throw new OperatorException("Window radius step size for dense forest pixels cannot be zero if initial and maximum window radius are not equal");
                }
            }

            // VCF threshold dense forest pixels
            //
            if (dfVCFThresholdToUse.equals(VCF_THRESHOLD_USE_ADAPTIVE)) {
                vcfThreshold = VCFThreshold.VCF_THRESHOLD_ADAPTIVE;

                if (initVCFThresholdDF <= 0f || initVCFThresholdDF >= 100f) {
                    throw new OperatorException("Initial VCF threshold for dense forest pixels = " + initVCFThresholdDF + " must be in (0, 100) %");
                }

                if (minVCFThresholdDF > initVCFThresholdDF) {
                    throw new OperatorException("Min VCF threshold for dense forest pixels = " + minVCFThresholdDF + " cannot be > initial VCF threshold = " + initVCFThresholdDF);
                }

                if (vcfThresholdStepSizeDF < 0f) {
                    throw new OperatorException("VCF threshold step size for dense forest pixels = " + vcfThresholdStepSizeDF + " cannot be negative");
                } else if (vcfThresholdStepSizeDF == 0) {
                    if (initVCFThresholdDF != minVCFThresholdDF) {
                        throw new OperatorException("VCF threshold step size for dense forest pixels cannot be zero if initial and minimum VCF thresholds are not equal");
                    }
                }

                if (minVCFThresholdDF <= maxVCFThresholdGr) {
                    throw new OperatorException("Min dense forest VCF threshold (" + minVCFThresholdDF + ") must be at greater than max ground VCF threshold (" + maxVCFThresholdGr + ")");
                }

            } else if (dfVCFThresholdToUse.equals(VCF_THRESHOLD_USE_PERCENTAGE_OF_MAX)) {
                vcfThreshold = VCFThreshold.VCF_THRESHOLD_PERCENTAGE_OF_MAX;

                if (percentageOfMaxVCF <= 0f || percentageOfMaxVCF > 100f) {
                    throw new OperatorException("Percentage of maximum VCF to use as threshold for dense forest pixels = " + percentageOfMaxVCF + " must be in (0, 100] %");
                }

            } else {
                throw new OperatorException("Unknown choice of VCF threshold for dense forest pixels = " + dfVCFThresholdToUse);
            }

            // minimum percentage of dense forest pixels
            //
            if (minPixelsPercentageDF1stTry <= 0f || minPixelsPercentageDF1stTry >= 100f) {
                throw new OperatorException("Minimum percentage of dense forest pixels (1st try) = " + minPixelsPercentageDF1stTry + " must be in (0, 100) %");
            }
            //
            if (minPixelsPercentageDF2ndTry <= 0f || minPixelsPercentageDF2ndTry >= 100f) {
                throw new OperatorException("Minimum percentage of dense forest pixels (2nd try) = " + minPixelsPercentageDF2ndTry + " must be in (0, 100) %");
            }
            //
            if (minPixelsPercentageDF2ndTry >= minPixelsPercentageDF1stTry) {
                throw new OperatorException("Minimum percentage of dense forest pixels for 2nd try = " + minPixelsPercentageDF2ndTry + " should be < 1st try = " + minPixelsPercentageDF1stTry);
            }

            retrievePP.put(HH, retrieveHH);
            retrievePP.put(VV, retrieveVV);
            retrievePP.put(HV, retrieveHV);
            retrievePP.put(VH, retrieveVH);

            modelledSigma0StartMap.put(HH, minSigma0HH);
            modelledSigma0EndMap.put(HH, maxSigma0HH);
            modelledSigma0StartMap.put(VV, minSigma0VV);
            modelledSigma0EndMap.put(VV, maxSigma0VV);
            modelledSigma0StartMap.put(HV, minSigma0HV);
            modelledSigma0EndMap.put(HV, maxSigma0HV);
            modelledSigma0StartMap.put(VH, minSigma0VH);
            modelledSigma0EndMap.put(VH, maxSigma0VH);

            bufferZoneWidthMap.put(HH, sigma0BufferZoneWidthHH);
            bufferZoneWidthMap.put(VV, sigma0BufferZoneWidthVV);
            bufferZoneWidthMap.put(HV, sigma0BufferZoneWidthHV);
            bufferZoneWidthMap.put(VH, sigma0BufferZoneWidthVH);

            for (String pp : allPols) {
                if (modelledSigma0StartMap.get(pp) >= modelledSigma0EndMap.get(pp)) {
                    throw new OperatorException("min sigma0_" + pp + " = " + modelledSigma0StartMap.get(pp) + " must be < max sigma0_" + pp + " = " + modelledSigma0EndMap.get(pp));
                }
                if (retrievePP.get(pp) && bufferZoneWidthMap.get(pp) < 0f) {
                    throw new OperatorException("sigma0 " + pp.toUpperCase() + " buffer zone width = " + bufferZoneWidthMap.get(pp) + " cannot be negative");
                }
            }

            eExpTerm = (float) FastMath.exp(-BETA * vDF);

            getSourceBands();
            createTargetProduct();
            //dumpUserParameters();

        } catch (Throwable e) {

            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        // The target product has the same dimensions as the source products.

        targetProduct = new Product(TARGET_PRODUCT_NAME,
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        for (String pp : sourceSigma0BandsMap.keySet()) {
            if (retrievePP.get(pp)) {

                final Band gsvBand = targetProduct.addBand(GSV_BAND_NAME_KEYWORD + "_" + pp, ProductData.TYPE_FLOAT32);
                gsvBand.setUnit(GSV_UNIT);
                gsvBand.setNoDataValue(INVALID_DOUBLE);
                gsvBand.setNoDataValueUsed(true);
                targetGSVBands.add(gsvBand);

                final Band errorCodeBand = targetProduct.addBand("error_code_" + pp, ProductData.TYPE_INT16);
                targetErrorCodeBands.put(pp, errorCodeBand);
            }
        }

        ProductUtils.copyBand(treeCoverPercentageBand.getName(), sourceProduct, targetProduct, true);

        if (targetGSVBands.size() == 0) {

            throw new OperatorException("No polarizations have been chosen for retrieval or there are no source sigma0 bands for the any of the chosen polarizations");
        }

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);
    }

    private void dumpUserParameters() {

        System.out.println("minObservations = " + minObservations);
        System.out.println("minWeight = " + minWeight);
        System.out.println("minRetrievableGSV = " + minRetrievableGSV);
        System.out.println("maxRetrievableGSVOffsetFromVdf = " + maxRetrievableGSVOffsetFromVdf);
        System.out.println("vDF = " + vDF);

        System.out.println("useDB = " + useDB);

        System.out.println("retrieveHH = " + retrieveHH);
        System.out.println("retrieveVV = " + retrieveVV);
        System.out.println("retrieveHV = " + retrieveHV);
        System.out.println("retrieveVH = " + retrieveVH);

        System.out.println("minSigma0HH = " + minSigma0HH + " maxSigma0HH = " + maxSigma0HH);
        System.out.println("minSigma0VV = " + minSigma0VV + " maxSigma0VV = " + maxSigma0VV);
        System.out.println("minSigma0HV = " + minSigma0HV + " maxSigma0HV = " + maxSigma0HV);
        System.out.println("minSigma0VH = " + minSigma0VH + " maxSigma0VH = " + maxSigma0VH);

        System.out.println("sigma0BufferZoneWidthHH = " + sigma0BufferZoneWidthHH);
        System.out.println("sigma0BufferZoneWidthVV = " + sigma0BufferZoneWidthVV);
        System.out.println("sigma0BufferZoneWidthHV = " + sigma0BufferZoneWidthHV);
        System.out.println("sigma0BufferZoneWidthVH = " + sigma0BufferZoneWidthVH);

        // Ground pixels...
        //
        System.out.println("initWindowRadiusGr = " + initWindowRadiusGr);
        System.out.println("windowRadiusStepSizeGr = " + windowRadiusStepSizeGr);
        System.out.println("maxWindowRadiusGr = " + maxWindowRadiusGr);
        //
        System.out.println("initVCFThresholdGr = " + initVCFThresholdGr);
        System.out.println("vcfThresholdStepSizeGr = " + vcfThresholdStepSizeGr);
        System.out.println("maxVCFThresholdGr = " + maxVCFThresholdGr);
        //
        System.out.println("minPixelsPercentageGr1stTry = " + minPixelsPercentageGr1stTry);
        System.out.println("minPixelsPercentageGr2ndTry = " + minPixelsPercentageGr2ndTry);
        //
        System.out.println("centralStatsMeasureGr = " + centralStatsMeasureGr);

        // Dense forest pixels...
        //
        System.out.println("initWindowRadiusDF = " + initWindowRadiusDF);
        System.out.println("windowRadiusStepSizeDF = " + windowRadiusStepSizeDF);
        System.out.println("maxWindowRadiusDF = " + maxWindowRadiusDF);
        //
        System.out.println("dfVCFThresholdToUse = " + dfVCFThresholdToUse);
        //
        System.out.println("initVCFThresholdDF = " + initVCFThresholdDF);
        System.out.println("vcfThresholdStepSizeDF = " + vcfThresholdStepSizeDF);
        System.out.println("minVCFThresholdDF = " + minVCFThresholdDF);
        //
        System.out.println("percentageOfMaxVCF = " + percentageOfMaxVCF);
        //
        System.out.println("minPixelsPercentageDF1stTry = " + minPixelsPercentageDF1stTry);
        System.out.println("minPixelsPercentageDF2ndTry = " + minPixelsPercentageDF2ndTry);
        //
        System.out.println("centralStatsMeasureDF = " + centralStatsMeasureDF);
    }

    private static boolean isPol(final String pp) {

        return (allPols.contains(pp.toLowerCase()));
    }

    private static String extractPolFromSourceBandName(final String bandname) {

        // TODO It is assumed that the source sigma0 band name contains the 2-character polarization in the 8th and 9th characters.

        if (bandname.length() >= 9) {
            return bandname.substring(7, 9);
        } else {
            return "";
        }
    }

    private static boolean isDecibel(final String unit) {

        // If no unit is specified, we assume it is not dB.

        if (unit == null) {
            return false;
        }

        final String unitInLowerCase = unit.toLowerCase();

        return (unitInLowerCase.contains("db") || unitInLowerCase.contains("decibel"));
    }

    private static float convertToDB(final float sigma0Val) {

        return 10.0f * (float) Math.log10(sigma0Val);
    }

    private void getSourceBands() {

        final Band[] bands = sourceProduct.getBands();

        for (final Band b : bands) {

            final String bandName = b.getName().toLowerCase();

            if (bandName.contains(SIGMA0_BAND_NAME_KEYWORD)) {

                final String pp = extractPolFromSourceBandName(bandName);
                final boolean isDB = isDecibel(b.getUnit());

                if (isPol(pp) && !isDB) {

                    SIGMA0_NO_DATA_VALUE = b.getNoDataValue();

                    if (sourceSigma0BandsMap.containsKey(pp)) {
                        sourceSigma0BandsMap.get(pp).add(b);
                    } else {
                        final ArrayList<Band> sourceSigma0Bands = new ArrayList<>();
                        sourceSigma0Bands.add(b);
                        sourceSigma0BandsMap.put(pp, sourceSigma0Bands);
                    }

                    //System.out.println("BIOMASAROp.getSourceBands: sigma0 bandname (" + bandName + ") is used; useDB = " + useDB + " isDB = " + isDB);
                }

            } else if (bandName.equals(VEG_MASK_BAND_NAME)) {

                if (vegMaskBand == null) {
                    vegMaskBand = b;
                } else {
                    System.out.println("BIOMASAROp.getSourceBands: WARNING Too many vegetation mask bands?");
                }

            } else if (bandName.contains(TREE_COVER_PERCENTAGE_BAND_NAME_KEY_WORDS)) {

                if (treeCoverPercentageBand == null) {
                    treeCoverPercentageBand = b;
                } else {
                    System.out.println("BIOMASAROp.getSourceBands: WARNING Too many tree cover percentage bands?");
                }
            }
        }

        if (sourceSigma0BandsMap.size() == 0) {
            throw new OperatorException("BIOMASAROP.getSourceBands: no sigma0 bands in dB");
        }

        for (String k : sourceSigma0BandsMap.keySet()) {
            final int numBands = sourceSigma0BandsMap.get(k).size();
            //System.out.println("BIOMASAROp.getSourceBands: number of sigma0 bands in " + k +
            //        " = " + numBands);
            if (retrievePP.get(k) && numBands < minObservations) {
                throw new OperatorException("BIOMASAROp.getSourceBands: too few sigma0 bands in " + k.toUpperCase() + " (" + numBands + " < " + minObservations + " = min observations)");
            }
        }


        if (treeCoverPercentageBand == null) {
            throw new OperatorException("BIOMASAROP.getSourceBands: no tree cover percentage band");
        }


        if (vegMaskBand == null) {

            vegMaskBand = treeCoverPercentageBand; // TODO just use tree cover percentage band as the vegetation mask band for now

            // TODO comment out throw for now
            //throw new OperatorException("BIOMASAROP.getSourceBands: no vegetation mask band");
        }

        //System.out.println("BIOMARSAROP.getSourceBands: SIGMA0_NO_DATA_VALUE = " + SIGMA0_NO_DATA_VALUE);
    }

    private static boolean isVegetation(final int x, final int y, final Tile vegMaskTile) {

        // TODO For now, use tree cover percentage band.
        // 253 means no data; 200 means water
        final float treeCoverPercentage = vegMaskTile.getDataBuffer().getElemFloatAt(vegMaskTile.getDataBufferIndex(x, y));
        return (treeCoverPercentage != 200f && treeCoverPercentage != 253f);

        // TODO Use some boolean(?) product to mask out the non-vegetation pixels

        /*
        return vegMaskTile.isSampleValid(x,y) &&
               vegMaskTile.getDataBuffer().getElemBooleanAt(vegMaskTile.getDataBufferIndex(x, y));
        */
    }

    private static boolean isInsideCircle(final int x, final int y, final int x0, final int y0, final int radius) {

        // (x0, y0) is centre of circle

        return !(Math.sqrt((x - x0) * (x - x0) + (y - y0) * (y - y0)) > radius);
    }

    private static boolean isValidPercentage(final float pct) {

        //return !(Float.isNaN(pct) || Float.isInfinite(pct) || pct < 0f || pct > 100f);
        return !(pct < 0f || pct > 100f);
    }

    private static float getMaxVCFInWindow(final SquareWindow sqWin, final Tile treeCoverPercentageTile) {

        // Return the maximum tree cover percentage inside the window centred at (pixX, pixY)

        float maxVCF = -999.0f;

        final ProductData treeCoverPercentageData = treeCoverPercentageTile.getDataBuffer();

        for (int x = sqWin.startX; x < sqWin.endX; x++) {

            for (int y = sqWin.startY; y < sqWin.endY; y++) {

                //if (isInsideCircle(x, y, pixX, pixY, windowRadius)) {

                final int index = treeCoverPercentageTile.getDataBufferIndex(x, y);

                final float treeCoverPct = treeCoverPercentageData.getElemFloatAt(index);

                if (isValidPercentage(treeCoverPct) && treeCoverPct > maxVCF) {
                    maxVCF = treeCoverPct;
                }
                // }
            }
        }

        return maxVCF;
    }

    private static float getPercentageOfPixelsForTreeCoverPercentageThreshold(
            // input...
            final SquareWindow sqWin,
            final Tile treeCoverPercentageTile,
            final float treeCoverPercentageThreshold,
            final boolean aboveThreshold,
            // output...
            final ArrayList<Integer> indices) {

        // Return percentage of pixels inside the window whose tree cover % is above or below "treeCoverThreshold".
        // "indices" contains the respective index of all the pixels inside the window whose tree cover % is above or
        // below "treeCoverThreshold".

        indices.clear();

        // total number of pixels inside the window including (pixX, pixY)
        int totalNumPixels = 0;

        // number of pixels inside the window, including (pixX, pixY), that are above or below "treeCoverThreshold"
        int numPixels = 0;

        // x and y are all absolute, i.e., wrt the raster image, not the tile

        final ProductData treeCoverPercentageData = treeCoverPercentageTile.getDataBuffer();

        final int startX = sqWin.startX, endX = sqWin.endX;
        final int startY = sqWin.startY, endY = sqWin.endY;
        for (int x = startX; x <= endX; x++) {

            for (int y = startY; y <= endY; y++) {

                //if (isInsideCircle(x, y, pixX, pixY, windowRadius)) {

                totalNumPixels++;

                final int index = treeCoverPercentageTile.getDataBufferIndex(x, y);
                final float treeCoverPct = treeCoverPercentageData.getElemFloatAt(index);

                if (treeCoverPct >= 0f && treeCoverPct <= 100f) {

                    if (aboveThreshold) {

                        if (treeCoverPct > treeCoverPercentageThreshold) {

                            //if (pixX == debugX && pixY == debugY) {
                            //    out1.println("DEBUG: aboveThreshold = " + aboveThreshold +  " x = " + x + " y = " + y + " index = " + index);
                            //}
                            numPixels++;
                            indices.add(index);
                        }

                    } else if (treeCoverPct < treeCoverPercentageThreshold) {

                        //if (pixX == debugX && pixY == debugY) {
                        //    out1.println("DEBUG: aboveThreshold = " + aboveThreshold + " x = " + x + " y = " + y + " index = " + index);
                        //}
                        numPixels++;
                        indices.add(index);
                    }
                }
                //}
            }
        }

        if (totalNumPixels > 0) {
            return 100f * (float) numPixels / (float) totalNumPixels; // %
        } else {
            // Should never happen
            System.out.println("BIOMASAROp.getPercentageOfPixelsForTreeCoverPercentageThreshold: WARNING Total number of pixels in window = " + totalNumPixels);
            return 0f;
        }
    }

    private ArrayList<Integer> getPixelIndicesForTreeCoverPercentageThreshold(
            // input...
            final SquareWindow sqWin,
            final Tile treeCoverPercentageTile,
            final boolean aboveThreshold // true means dense forest, false means ground
    ) {
        // Implement Fig. 6 in [1]

        // Inside a finite-size window centred at pixel identified by (x, y), use tree cover % to decide which
        // pixel in the window is (ground|dense forest). If the tree cover % is (below|above) some threshold, then it
        // is considered a (ground|dense forest) pixel.
        // Eventually, we want to take the (mean|median|mode) of the sigma0 of these pixels in the window as the
        // estimate of sigma0_(gr|df) (end of 2nd paragraph in 5.1 in [1]; 2nd paragraph of 5.2 in [1]).
        // Because we are taking the (mean|median|mode), there has to be sufficient number of pixels inside the
        // window for it to be statistically meaningful.

        // Return an array of the indices of the (ground|dense forest) pixels.

        // If aboveThreshold is true, then we are dealing with dense forest pixels else it is ground pixels.

        final int initWindowRadius = aboveThreshold ? initWindowRadiusDF : initWindowRadiusGr;
        final int maxWindowRadius = aboveThreshold ? maxWindowRadiusDF : maxWindowRadiusGr;
        final int windowRadiusStepSize = aboveThreshold ? windowRadiusStepSizeDF : windowRadiusStepSizeGr;

        final float vcfThresholdLimit = aboveThreshold ? minVCFThresholdDF : maxVCFThresholdGr;
        final float vcfThresholdStepSize = aboveThreshold ? vcfThresholdStepSizeDF : vcfThresholdStepSizeGr;

        final float initVCFThreshold = aboveThreshold ? initVCFThresholdDF : initVCFThresholdGr; // %
        float vcfThreshold = initVCFThreshold;
        int radius = initWindowRadius; // pixel

        // Have already checked that initWindowRadius(Gr|DF) <= maxWindowRadius(Gr|DF)
        final boolean isAdaptiveSizeWindow = (initWindowRadius != maxWindowRadius);

        // Have already checked that initVCFThreshold(Gr|DF) (<|>)= (max|min)WindowRadius(Gr|DF)
        final boolean isAdaptiveVCFThreshold = aboveThreshold ?
                (this.vcfThreshold.equals(VCFThreshold.VCF_THRESHOLD_ADAPTIVE) && (vcfThreshold != vcfThresholdLimit))
                : (vcfThreshold != vcfThresholdLimit);

        final float minPixelsPercentage1stTry = aboveThreshold ?
                minPixelsPercentageDF1stTry : minPixelsPercentageGr1stTry;
        final float minPixelsPercentage2ndTry = aboveThreshold ?
                minPixelsPercentageDF2ndTry : minPixelsPercentageGr2ndTry;
        float minPixelsPercentage = minPixelsPercentage1stTry;

        final ArrayList<Integer> indicesTmp = new ArrayList<>();
        boolean sufficientPixels1stTry = false;
        boolean sufficientPixels2ndTry = false;
        boolean percentageOfMax = aboveThreshold && this.vcfThreshold.equals(VCFThreshold.VCF_THRESHOLD_PERCENTAGE_OF_MAX);

        boolean done = false;
        while (!done) {

            // Window size has changed, so we need to update vcfThreshold if we are using % of max VCF in
            // the window as vcfThreshold.
            if (percentageOfMax) {

                final float maxVCF = getMaxVCFInWindow(sqWin, treeCoverPercentageTile); // %
                vcfThreshold = maxVCF * percentageOfMaxVCF / 100f; // %

                //out1.println("DEBUG: maxVCF = " + maxVCF + " vcfThreshold = " + vcfThreshold);

                if (vcfThreshold < maxVCFThresholdGr) {
                    // The VCF threshold for dense forest cannot be less than the max VCF threshold for ground
                    // Need to expand the window to see if we can find a pixel with higher VCF
                    if (isAdaptiveSizeWindow && (radius + windowRadiusStepSize <= maxWindowRadius)) {
                        radius += windowRadiusStepSize;
                        continue;
                    } else {
                        done = true;
                    }
                }
            }

            final float pixPct = getPercentageOfPixelsForTreeCoverPercentageThreshold(
                    sqWin,
                    treeCoverPercentageTile,
                    vcfThreshold,
                    aboveThreshold,
                    indicesTmp);

            if (pixPct > minPixelsPercentage) {

                done = true;

            } else {

                if (isAdaptiveSizeWindow && (radius + windowRadiusStepSize <= maxWindowRadius)) {

                    radius += windowRadiusStepSize;

                } else if (isAdaptiveVCFThreshold) {

                    // Have already checked that vcfThresholdStepSize > 0

                    radius = initWindowRadius;

                    if (aboveThreshold) {

                        // dense forest pixels

                        if (vcfThreshold - vcfThresholdStepSize >= vcfThresholdLimit) {

                            vcfThreshold -= vcfThresholdStepSize;

                        } else {

                            if (!sufficientPixels2ndTry) {

                                minPixelsPercentage = minPixelsPercentage2ndTry;
                                vcfThreshold = initVCFThreshold;
                                sufficientPixels2ndTry = true;

                            } else {
                                // no estimate
                                done = true;
                            }
                        }

                    } else {

                        // ground pixels

                        if (vcfThreshold + vcfThresholdStepSize <= vcfThresholdLimit) {

                            vcfThreshold += vcfThresholdStepSize;

                        } else {

                            if (!sufficientPixels2ndTry) {

                                minPixelsPercentage = minPixelsPercentage2ndTry;
                                vcfThreshold = initVCFThreshold;
                                sufficientPixels2ndTry = true;

                            } else {
                                // no estimate
                                done = true;
                            }
                        }
                    }

                } else {

                    // no estimate
                    done = true;
                }
            }
        }

        //if (x == debugX && y == debugY) {
        //    out1.println("DEBUG: sufficientPixels1stTry = " + sufficientPixels1stTry + " sufficientPixels2ndTry = " + sufficientPixels2ndTry);
        //}

        if (indicesTmp.size() > 0) {
            return indicesTmp;
        } else {
            return null;
        }
    }

    private float estimateSigma0(final Tile treeCoverPercentageTile, // for debugging
                                 final Tile sigma0Tile,
                                 //final float modelledSigma0Start, final float modelledSigma0End,
                                 ArrayList<Integer> indices, final String centralStatsMeasure) {

        float sigma0;

        ArrayList<Float> sigma0Values = new ArrayList<>();

        for (int idx : indices) {
            final float sigma0Val = sigma0Tile.getDataBuffer().getElemFloatAt(idx);
            // If useDB is true, then sigma0Val is already in dB, then no need to convert.
            //final float sigma0ValdB = useDB ? sigma0Val : convertToDB(sigma0Val);
            if (sigma0Val != SIGMA0_NO_DATA_VALUE && !Float.isNaN(sigma0Val) && !Float.isInfinite(sigma0Val)) {
                sigma0Values.add(sigma0Val);
                //if (centralStatsMeasure == USE_MEDIAN) {
                //out1.println("DEBBUG: estimateSigma0: idx = " + idx + " sigma0 = " + sigma0Val + " VCF = " + treeCoverPercentageTile.getDataBuffer().getElemFloatAt(idx));
                //}
            }
        }

        final CentralStatisticMeasure measure = new CentralStatisticMeasure(sigma0Values, INVALID_FLOAT);

        // sigma0 is the central statistic measure in "pixelTreeCoverPercentage" (end of 2nd paragraph of 5.1 in [1])
        switch (centralStatsMeasure) {
            case USE_MEAN:
                sigma0 = measure.getMean();
                break;
            case USE_MEDIAN:
                sigma0 = measure.getMedian();
                break;
            case USE_MODE:
                sigma0 = measure.getMode();
                break;
            default:
                throw new OperatorException("BIOMASAROP.estimateSigma0: unknown central statistics measure - " + centralStatsMeasure);
        }

        //out1.println("DEBUG: estimateSigma0: sigma0 = " + sigma0);

        return sigma0;
    }

    private float retrieveGSVForOnePixel( // inputs...
                                          final int x, final int y,
                                          final Tile treeCoverPercentageTile, // for debugging
                                          final Tile sigma0Tile,
                                          final ArrayList<Integer> indicesGr,
                                          final ArrayList<Integer> indicesDF,
                                          final float modelledSigma0Start, final float modelledSigma0End,
                                          final float bufferZoneWidth,
                                          // outputs...
                                          final float[] w // output weight
    ) {
        final float sigma0For = sigma0Tile.getDataBuffer().getElemFloatAt(sigma0Tile.getDataBufferIndex(x, y));

        // We have already checked that we have a valid sigma0 in this pixel using Tile.isSampleValid(), but
        // check again anyway.
        if (Float.isNaN(sigma0For) || Float.isInfinite(sigma0For)) {
            return INVALID_FLOAT;
        }

        final float sigma0FordB = convertToDB(sigma0For);

        // Fig.4 in [1]
        // If the measured sigma0 is outside of the modelled sigma0 range, do not try to retrieve GSV
        // If the measured sigma0 is inside Buffer Zone, then return min or max retrievable GSV
        /*
        if (sigma0FordB < modelledSigma0Start) {

            if (sigma0FordB < modelledSigma0Start - bufferZoneWidth) {
                return INVALID_FLOAT;
            } else {
                return minRetrievableGSV;
            }

        } else if (sigma0FordB > modelledSigma0End) {

            if (sigma0FordB > modelledSigma0End + bufferZoneWidth) {
                return INVALID_FLOAT;
            } else {
                return vDF + maxRetrievableGSVOffsetFromVdf;
            }
        }*/

        // Estimate sigma0_gr
        float sigma0Gr = estimateSigma0(treeCoverPercentageTile, sigma0Tile, indicesGr, centralStatsMeasureGr);

        // Estimate sigma0_df
        float sigma0DF = estimateSigma0(treeCoverPercentageTile, sigma0Tile, indicesDF, centralStatsMeasureDF);

        // Estimate sigma0_veg using Eq. 5 in [1]
        final float sigma0Veg = (sigma0DF - (sigma0Gr * eExpTerm)) / (1 - eExpTerm);

//        if (x==debugX && y==debugY) {
//            out1.println("DEBUG: sigma0Gr = " + sigma0Gr + " sigma0For = " + sigma0For + " sigma0DF = " + sigma0DF + " sigma0Veg = " + sigma0Veg + " eExpTerm = " + eExpTerm);
//        }

        final float sigma0VegdB = convertToDB(sigma0Veg);
        final float sigma0GrdB = convertToDB(sigma0Gr);

        // This is the weight (last paragraph of 4.3 in [1] below E.q. 7)
        // It is in dB. See paragraph below Eg.4 in 3.2.2 of [3]. Also, last paragraph of 6 in [1].
        w[0] = Math.abs(sigma0VegdB - sigma0GrdB);

        float gsv = INVALID_FLOAT;
        boolean done = false;

        //if (w[0] > minWeight && (sigma0Veg > sigma0For)) {
        if (w[0] > minWeight) {

            //final float sigma0ForVminDB = modelledSigma0Start;
            //final float sigma0ForVmaxDB = modelledSigma0End;
            final float sigma0ForVminDB = convertToDB(sigma0Gr);
            final float vMax = vDF + maxRetrievableGSVOffsetFromVdf;
            final float tmp = (float) FastMath.exp(-BETA * vMax);
            final float sigma0ForVmaxDB = convertToDB(sigma0Gr * tmp + sigma0DF * (1 - tmp));

            if (sigma0ForVminDB < sigma0ForVmaxDB) {

                if (sigma0FordB >= sigma0ForVminDB && sigma0FordB <= sigma0ForVmaxDB) {

                    gsv = (float) -(Math.log((sigma0Veg - sigma0For) / (sigma0Veg - sigma0Gr))) / BETA;

                } else if (sigma0FordB < sigma0ForVminDB) {

                    if (sigma0FordB < sigma0ForVminDB - bufferZoneWidth) {
                        gsv = INVALID_FLOAT;
                    } else {
                        gsv = minRetrievableGSV;
                    }

                } else if (sigma0FordB > sigma0ForVmaxDB) {

                    if (sigma0FordB > sigma0ForVmaxDB + bufferZoneWidth) {
                        gsv = INVALID_FLOAT;
                    } else {
                        gsv = vMax;
                    }
                }

            } else { // sigma0ForVminDB > sigma0ForVmaxDB

                if (sigma0FordB >= sigma0ForVmaxDB && sigma0FordB <= sigma0ForVminDB) {

                    gsv = (float) -(Math.log((sigma0Veg - sigma0For) / (sigma0Veg - sigma0Gr))) / BETA;

                } else if (sigma0FordB < sigma0ForVmaxDB) {

                    if (sigma0FordB < sigma0ForVmaxDB - bufferZoneWidth) {
                        gsv = INVALID_FLOAT;
                    } else {
                        gsv = vMax;
                    }

                } else if (sigma0FordB > sigma0ForVminDB) {

                    if (sigma0FordB > sigma0ForVminDB + bufferZoneWidth) {
                        gsv = INVALID_FLOAT;
                    } else {
                        gsv = minRetrievableGSV;
                    }
                }
            }

            //if (x==debugX && y==debugY) {
            //    out1.println("DEBUG: gsv = " + gsv + " done = " + done);
            //}
        }

        return gsv;
    }


    private void getSourceRectangleSpecs(final int tx0, // input target x0
                                         final int tw, // input target width
                                         final int maxW, // input max width (i.e., the raster size)
                                         final int sx0sw[]) { // output source x0 and width

        // Works for source y0 and height too

        // Source rectangle needs to be big enough to include a window centred at each pixel of the target
        // rectangle.

        // tx0 is zero-based (starts counting from 0)

        final int maxWindowRadius = Math.max(maxWindowRadiusGr, maxWindowRadiusDF);

        int sx0 = tx0 - maxWindowRadius;
        int sw = tw + 2 * maxWindowRadius;
        if (sx0 < 0) {
            sw -= Math.abs(sx0);
            sx0 = 0;
        }
        if (sx0 + sw > maxW) {
            sw = maxW - sx0;
        }

        //System.out.println("tx0 = " + tx0 + " tw = " + tw + " maxW = " + maxW + " maxWindowRadius = " + maxWindowRadius + " sx0 = " + sx0 + " sw = " + sw);

        sx0sw[0] = sx0;
        sx0sw[1] = sw;
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     * <p>This method shall never be called directly.
     * </p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm)
            throws OperatorException {

        final int tx0 = targetRectangle.x;
        final int ty0 = targetRectangle.y;
        final int tw = targetRectangle.width;
        final int th = targetRectangle.height;

        //SystemUtils.LOG.info("tx0 = " + tx0 + " ty0 = " + ty0 + " tw = " + tw + " th = " + th);

        // Source tiles for sigma0 and tree cover percentage (but not vegetation mask) have to take into account the
        // window around each pixel needed for estimation of sigma0_gr and sigma0_df.

        final int[] sx0sw = new int[2];

        getSourceRectangleSpecs(tx0, tw, sourceProduct.getSceneRasterWidth(), sx0sw);
        final int sx0 = sx0sw[0];
        final int sw = sx0sw[1];

        getSourceRectangleSpecs(ty0, th, sourceProduct.getSceneRasterHeight(), sx0sw);
        final int sy0 = sx0sw[0];
        final int sh = sx0sw[1];

        // tx0, ty0, sx0 and sy0 are all absolute, i.e., they are wrt the raster image, not the tile.

        //SystemUtils.LOG.info("Do: tx0 = " + tx0 + " ty0 = " + ty0 + " tw = " + tw + " th = " + th + "; sx0 = " + sx0 + " sy0 = " + sy0 + " sw = " + sw + " sh = " + sh);

        final Rectangle sourceRectangle = new Rectangle(sx0, sy0, sw, sh);

        final Tile vegMaskTile = getSourceTile(vegMaskBand, targetRectangle);
        final Tile treeCoverPercentageTile = getSourceTile(treeCoverPercentageBand, sourceRectangle);

        final int maxy = ty0 + th;
        final int maxx = tx0 + tw;

        for (Band b : targetGSVBands) {

            try {
                final String pol = b.getName().substring(4, 6);
                final ArrayList<Band> sourceSigma0Bands = sourceSigma0BandsMap.get(pol);
                final int numSigma0Bands = sourceSigma0Bands.size();

                final Tile[] sigma0Tile = new Tile[numSigma0Bands];
                for (int i = 0; i < numSigma0Bands; i++) {
                    sigma0Tile[i] = getSourceTile(sourceSigma0Bands.get(i), sourceRectangle);
                }

                final Tile targetTile = targetTiles.get(b);

                final Band errorCodeBand = targetErrorCodeBands.get(pol);
                final Tile errorCodeTile = targetTiles.get(errorCodeBand);

                final int totalPixels = th * tw;
                int numPixelsProcessed = 0;

                ProcessTimeMonitor ptm = new ProcessTimeMonitor();
                ptm.start();

                // Process pixel by pixel in the target tile
                for (int y = ty0; y < maxy; y++) { // loop through rows
                    if (pm.isCanceled()) {
                        break;
                    }
                    for (int x = tx0; x < maxx; x++) { // loop through columns

//                        if (debugX >= 0 && debugY >= 0) {
//                            if (x == debugX && y == debugY) {
//                                out1.println("DEBUG: debug...");
//                            } else {
//                                continue; // DEBUG
//                            }
//                        }

                        short errCode = 0;
                        float gsv = INVALID_FLOAT;

                        // TODO we can check here that at we have valid sigma0 values from sufficient bands before we do anything

                        final boolean isVeg = isVegetation(x, y, vegMaskTile);

                        if (isVeg) {

                            final SquareWindow sqWin = new SquareWindow(x, y, initWindowRadiusDF,
                                    sourceProduct.getSceneRasterWidth(),
                                    sourceProduct.getSceneRasterHeight());

                            // indices of the surrounding ground pixels
                            ArrayList<Integer> indicesGr =
                                    getPixelIndicesForTreeCoverPercentageThreshold(sqWin, treeCoverPercentageTile, false);
                            ArrayList<Integer> indicesDF = null;

                            if (indicesGr != null) {
                                // indices of the surrounding dense forest pixels
                                indicesDF =
                                        getPixelIndicesForTreeCoverPercentageThreshold(sqWin, treeCoverPercentageTile, true);

                                if (indicesDF == null) {
                                    errCode = ERR_INSUFFICIENT_DF_PIXELS;
                                }
                            } else {
                                errCode = ERR_INSUFFICIENT_GR_PIXELS;
                            }
//                            if (x == debugX && y == debugY) {
//                                out1.println("DEBUG: debug... indicesGr is null = " + (indicesGr == null) + " indicesDF is null = " + (indicesDF == null) + " errCode = " + errCode) ;
//                            }
                            if (indicesGr != null && indicesDF != null) {

                                // Implement E.q. 7 in [1]
                                // Note that W_max is a constant that can be taken outside of the summation and cancel out

                                float sumWV = 0.0f;
                                float sumW = 0.0f;
                                int numObservations = 0;

                                for (int i = 0; i < numSigma0Bands; i++) { // loop through all sigma0 bands

                                    //if (!sigma0Tile[i].isSampleValid(x, y)) {
                                    //    continue;
                                    //}

                                    final float[] w = new float[1];
                                    final float v = retrieveGSVForOnePixel(x, y,
                                            treeCoverPercentageTile,
                                            sigma0Tile[i],
                                            indicesGr,
                                            indicesDF,
                                            modelledSigma0StartMap.get(pol),
                                            modelledSigma0EndMap.get(pol),
                                            bufferZoneWidthMap.get(pol),
                                            w);
//                                    if (x == debugX && y == debugY) {
//                                        out1.println("DEBUG: v = " + v + " w = " + w[0]);
//                                    }
                                    // checking for w[0] >= minWeight is really redundant since it was checked in
                                    // retrieveGSVForOnePixel() but it does not hurt
                                    if (!Float.isNaN(v) && w[0] >= minWeight) {
                                        sumWV += w[0] * v;
                                        sumW += w[0];
                                        numObservations++;
//                                        if (x == debugX && y == debugY) {
//                                            out1.println("DEBUG: v = " + v + " w = " + w[0] + " use these");
//                                        }
                                    }
                                }

                                if (numObservations >= minObservations && Float.compare(sumW, 0.0f) != 0) {
                                    gsv = sumWV / sumW;
//                                    if (x == debugX && y == debugY) {
//                                        out1.println("DEBUG: final GSV = " + gsv + " n = " + numObservations);
//                                    }
                                    errCode = (short) numObservations;
                                } else {
                                    errCode = ERR_INSUFFICIENT_OBSERVATIONS;
                                }
                            }
                        } else {
                            errCode = ERR_NOT_VEG;
                        }

                        targetTile.getDataBuffer().setElemFloatAt(targetTile.getDataBufferIndex(x, y), gsv);
                        errorCodeTile.getDataBuffer().setElemIntAt(errorCodeTile.getDataBufferIndex(x, y), errCode);

                        numPixelsProcessed++;
                        if (numPixelsProcessed % 2000 == 0) {

                            //SystemUtils.LOG.info("BIOMASAROp: tx0 = " + tx0 + " ty0 = " + ty0 + "; " + numPixelsProcessed + "/" + totalPixels + " processed "+
                            //                             ProcessTimeMonitor.formatDuration(ptm.stop()));
                            //ptm.start();
                        }
                    }
                }
                //System.out.println("DONE: tx0 = " + tx0 + " ty0 = " + ty0 + " tw = " + tw + " th = " + th + "; sx0 = " + sx0 + " sy0 = " + sy0 + " sw = " + sw + " sh = " + sh);
            } catch (Throwable e) {
                //System.out.println("ERROR: tx0 = " + tx0 + " ty0 = " + ty0 + " tw = " + tw + " th = " + th + "; sx0 = " + sx0 + " sy0 = " + sy0 + " sw = " + sw + " sh = " + sh);
                OperatorUtils.catchOperatorException(getId(), e);
            } finally {
                pm.done();
            }
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.core.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator()
     * @see org.esa.snap.core.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(BIOMASAROp.class);
        }
    }
}