package org.jlinda.core.filtering;

import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.util.SystemUtils;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jlinda.core.Constants;
import org.jlinda.core.SLCImage;
import org.jlinda.core.Window;
import org.jlinda.core.utils.*;

import java.util.logging.Logger;

import static org.jlinda.core.utils.LinearAlgebraUtils.*;

public class RangeFilter extends ProductDataFilter {

    static Logger logger = SystemUtils.LOG;

    // data
    private DoubleMatrix power;
    private int fftLength;

    // input tile
    private long nRows;
    private long nCols;

    private double RSR; // in MHz
    private double RBW; // in MHz

    // input for filtering
    private int nlMean = 15;
    private double SNRthreshold = 5;
    private double alphaHamming = 0.75;
    private int ovsFactor = 1;

    private boolean doHamming = false;
    private boolean doOversampleFlag = false;
    private boolean doWeightCorrelFlag = false;

// input for TILE coordinates
    // private Window absTile = new Window();

    //// CONSTRUCTORS /////
    public RangeFilter() {
    }

    public RangeFilter(SLCImage master, SLCImage slave, ComplexDoubleMatrix data, ComplexDoubleMatrix data1) throws Exception {
        this.setMetadata(master);
        this.setMetadata1(slave);
        this.setData(data);
        this.setData1(data1);
        defineParameters();
    }

    //// GETTERS AND SETTERS ////
    public void setAlphaHamming(double alphaHamming) {
        this.alphaHamming = alphaHamming;
    }

    public void setOvsFactor(int ovsFactor) {
        this.ovsFactor = ovsFactor;
    }

    public void setDoWeightCorrelFlag(boolean doWeightCorrelFlag) {
        this.doWeightCorrelFlag = doWeightCorrelFlag;
    }

    public void setFftLength(int fftLength) {
        this.fftLength = fftLength;
    }

    public void setNlMean(int nlMean) {
        this.nlMean = nlMean;
    }

    public void setSNRthreshold(double SNRthreshold) {
        this.SNRthreshold = SNRthreshold;
    }


    public void defineParameters() throws Exception {

        // declare filter matrix
        nRows = data.rows;
        nCols = data.columns;
//        filter = new DoubleMatrix((int) nRows, (int) nCols); // filter
        filter = DoubleMatrix.ones((int) nRows, (int) nCols); // filter

        // define filtering params
        RSR = 0.5 * metadata.getRsr2x();
        RBW = metadata.getRangeBandwidth() * Constants.MEGA;

        doOversampleFlag = (ovsFactor != 1);
        doHamming = (alphaHamming < 0.9999);

        computePowerMatrix(); // returns power
        fftLength = power.columns;

        sanityChecks();
    }

    @Override
    public void defineFilter() throws Exception {

        /// local variables ///
        /// define parameters ///
        int notFiltered = 0;
        final long outputLines = nRows - nlMean + 1;
        final long firstLine = ((nlMean - 1) / 2);        // indices in matrix system
        final long lastLine = firstLine + outputLines - 1;
        final boolean doHamming = (alphaHamming < 0.9999);

        /// shift parameters ////
        final double deltaF = RSR / nCols;

        DoubleMatrix freqAxis = defineFrequencyAxis(nCols, RSR);
        DoubleMatrix inverseHamming = null;
        if (doHamming) {
            inverseHamming = WeightWindows.inverseHamming(freqAxis, RBW, RSR, alphaHamming);
        }

        //// Use weighted correlation due to bias in normal definition
        // Note: Actually better de-weight with autoconvoluted hamming.
        if (doWeightCorrelFlag) {
            doWeightCorrel(RSR, RBW, fftLength, power);
        }

        DoubleMatrix nlMeanPower = computeNlMeanPower(nlMean, fftLength, power);

        long shift; // returned by max
        double meanSNR = 0.;
        double meanShift = 0.;

        // Start actual filtering
        for (long outLine = firstLine; outLine <= lastLine; ++outLine) {

            double totalPower = nlMeanPower.sum();
            double maxValue = nlMeanPower.max();
            shift = nlMeanPower.argmax();
            long lastShift = shift;
            double SNR = fftLength * (maxValue / (totalPower - maxValue));
            meanSNR += SNR;

            //// Check for negative shift
            boolean negShift = false;
            if (shift > (fftLength / 2)) {
                shift = fftLength - shift;
                lastShift = shift; // use this if current shift not OK.
                negShift = true;
            }

            // ______ Do actual filtering ______
            if (SNR < SNRthreshold) {
                notFiltered++; // update notFiltered counter
                shift = lastShift;
                //logger.warning("using last shift for filter");
            }

            meanShift += shift;
            DoubleMatrix filterVector = defineFilterVector(deltaF, freqAxis, inverseHamming, shift);

            //// Use freq. as returned by fft ////
            SpectralUtils.ifftshift_inplace(filterVector);

            if (!negShift) {
                filter.putRow((int) outLine, filterVector);
            } else {
                fliplr_inplace(filterVector);
                filter.putRow((int) outLine, filterVector);
            }

            /// Update 'walking' mean
            if (outLine != lastLine) {
                DoubleMatrix line1 = power.getRow((int) (outLine - firstLine));
                DoubleMatrix lineN = power.getRow((int) (outLine - firstLine + nlMean));
                nlMeanPower.addi(lineN.sub(line1));
            }

        } // loop over outLines

    }

    private DoubleMatrix defineFilterVector(double deltaF, DoubleMatrix freqAxis, DoubleMatrix inverseHamming, long shift) {

        DoubleMatrix filterVector;
        if (doHamming) {
            // newhamming is scaled and centered around new mean : filter is fftshifted
            filterVector = WeightWindows.hamming(freqAxis.sub(0.5 * shift * deltaF), RBW - (shift * deltaF),
                    RSR, alphaHamming);
            filterVector.muli(inverseHamming);
        } else {
            // no weighting of spectra
            filterVector = WeightWindows.rect((freqAxis.sub(.5 * shift * deltaF)).div((RBW - shift * deltaF)));
        }
        return filterVector;
    }

    @Override
    public void applyFilter() {

        /// Average power to reduce noise : fft.ing in-place over data rows ///
        //logger.info("Took FFT over rows of master, slave.");
        SpectralUtils.fft_inplace(data, 2);
        SpectralUtils.fft_inplace(data1, 2);

        LinearAlgebraUtils.dotmult_inplace(data, new ComplexDoubleMatrix(this.filter));
        LinearAlgebraUtils.fliplr_inplace(filter);
        LinearAlgebraUtils.dotmult_inplace(data1, new ComplexDoubleMatrix(this.filter));

        // IFFT of spectrally filtered data, and return these
        SpectralUtils.invfft_inplace(data, 2);
        SpectralUtils.invfft_inplace(data1, 2);

    }

    public void applyFilterMaster() {
        /// Average power to reduce noise : fft.ing in-place over data rows ///
        //logger.info("Took FFT over rows of master, slave.");
        SpectralUtils.fft_inplace(data, 2);
        LinearAlgebraUtils.dotmult_inplace(data, new ComplexDoubleMatrix(this.filter));
        // IFFT of spectrally filtered data, and return these
        SpectralUtils.invfft_inplace(data, 2);
    }

    public void applyFilterSlave() {

        //logger.info("Took FFT over rows of master, slave.");
        SpectralUtils.fft_inplace(data1, 2);
        LinearAlgebraUtils.fliplr_inplace(filter);
        LinearAlgebraUtils.dotmult_inplace(data1, new ComplexDoubleMatrix(this.filter));
        // IFFT of spectrally filtered data, and return these
        SpectralUtils.invfft_inplace(data1, 2);
    }


    //// HELPER PRIVATE METHODS ////

    private void sanityChecks() {
        /// sanity check on input paramaters ///
        if (!MathUtils.isOdd(nlMean)) {
            logger.severe("nlMean has to be odd.");
            throw new IllegalArgumentException("nlMean has to be odd.");
        }
        if (!MathUtils.isPower2(nCols)) {
            logger.severe("numPixels (FFT) has to be power of 2.");
            throw new IllegalArgumentException("numPixels (FFT) has to be power of 2.");
        }
        if (!MathUtils.isPower2(ovsFactor)) {
            logger.severe("oversample factor (FFT) has to be power of 2.");
            throw new IllegalArgumentException("oversample factor (FFT) has to be power of 2.");
        }
        if (data1.rows != nRows) {
            logger.severe("slave not same size as master.");
            throw new IllegalArgumentException("slave not same size as master.");
        }
        if (data1.columns != nCols) {
            logger.severe("slave not same size as master.");
            throw new IllegalArgumentException("slave not same size as master.");
        }
//        if (outputLines < 1) {
//            logger.warning("no outputLines, continuing....");
//        }
    }

    /* COMPUTE CPLX IFG ON THE FLY -> power */
    private void computePowerMatrix() throws Exception {
        ComplexDoubleMatrix ifg;
        if (doOversampleFlag) {
            ifg = SarUtils.computeIfg(data, data1, 1, ovsFactor);
        } else {
            ifg = SarUtils.computeIfg(data, data1);
        }
        SpectralUtils.fft_inplace(ifg, 2);  // ifg = fft over rows
        power = SarUtils.intensity(ifg);    // power   = ifg.*conj(ifg);
    }

    //// HELPER PRIVATE STATIC ////
    private static DoubleMatrix computeNlMeanPower(final long nlMean, final long fftLength, DoubleMatrix power) {
//        DoubleMatrix nlmeanpower = sum(power(0,nlMean-1, 0,fftlength-1),1);
//        final IntervalRange rangeRows = new IntervalRange(0, (int) (nlMean));
//        final IntervalRange rangeColumns = new IntervalRange(0, (int) (fftLength));
        final Window window = new Window(0, nlMean-1, 0, fftLength-1);
        DoubleMatrix temp = new DoubleMatrix((int)nlMean, (int)fftLength);
        setdata(temp, window, power, window);
        return temp.columnSums();
    }

    // EXPERIMENTAL function: not recommended with oversampling, tho results are ok?!
    private static void doWeightCorrel(final double RSR, final double RBW,  final long fftLength, DoubleMatrix data) {

        int j;
        int i;

        final long numLines = data.rows;
        final long numPixels = data.columns;

        // weigth = numpoints in spectral convolution for fft squared for power...
        int indexNoPeak = (int) ((1. - (RBW / RSR)) * numPixels);
        for (j = 0; j < fftLength; ++j) {

            long nPnts = Math.abs(numPixels - j);
            double weight = (nPnts < indexNoPeak) ? FastMath.pow(numPixels, 2) : FastMath.pow(nPnts, 2); // ==zero

            for (i = 0; i < numLines; ++i) {
                data.put(i, j, data.get(i, j) / weight);
            }
        }
    }

    private static DoubleMatrix defineFrequencyAxis(final long numPixs, final double RSR) {
        final double deltaF = RSR / numPixs;
        final double freq = -RSR / 2.;
        DoubleMatrix freqAxis = new DoubleMatrix(1, (int) numPixs);
        for (int i = 0; i < numPixs; ++i) {
            freqAxis.put(0, i, freq + (i * deltaF));
        }
        return freqAxis;
    }
}
