package org.jlinda.core.utils;

import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.util.SystemUtils;
import org.jblas.ComplexDouble;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.Solve;
import org.jlinda.core.Window;

import java.util.logging.Logger;

import static org.jblas.MatrixFunctions.pow;
import static org.jblas.MatrixFunctions.sqrt;

public class SarUtils {

    static Logger logger = SystemUtils.LOG;

    /**
     * HARMONIC INTERPOLATION
     * B=oversample(A, factorrow, factorcol);
     * 2 factors possible, extrapolation at end.
     * no vectors possible.
     */
    public static ComplexDoubleMatrix oversample(ComplexDoubleMatrix inputMatrix, final int factorRow, final int factorCol) throws IllegalArgumentException {

        final int l = inputMatrix.rows;
        final int p = inputMatrix.columns;
        final int halfL = l / 2;
        final int halfP = p / 2;
        final int L2 = factorRow * l;  // numRows of output matrix
        final int P2 = factorCol * p;  // columns of output matrix

        if (inputMatrix.isVector()) {
            logger.severe("oversample: only 2d matrices.");
            throw new IllegalArgumentException("oversample: only 2d matrices");
        }
        if (!MathUtils.isPower2(l) && factorRow != 1) {
            logger.severe("oversample: numlines != 2^n");
            throw new IllegalArgumentException("oversample: numlines != 2^n");
        }
        if (!MathUtils.isPower2(p) && factorCol != 1) {
            logger.severe("oversample: numcols != 2^n");
            throw new IllegalArgumentException("oversample: numcols != 2^n");
        }

        if (factorRow == 1 && factorCol == 1) {
            logger.info("oversample: both azimuth and range oversampling factors equal to 1!");
            logger.info("oversample: returning inputMatrix!");
            return inputMatrix;
        }

        final ComplexDouble half = new ComplexDouble(0.5);
        ComplexDoubleMatrix returnMatrix = new ComplexDoubleMatrix(L2, P2);

        final Window winA1;
        final Window winA2;
        final Window winR2;

        ComplexDoubleMatrix tempMatrix;
        if (factorRow == 1) {

            // 1d fourier transform per row
            tempMatrix = SpectralUtils.fft(inputMatrix, 2);

            // TODO: check this
            // divide by 2 because even fftlength
            tempMatrix.putColumn(halfP, tempMatrix.getColumn(halfP).mmuli(half));

            // zero padding windows
            winA1 = new Window(0, l - 1, 0, halfP);
            winA2 = new Window(0, l - 1, halfP, p - 1);
            winR2 = new Window(0, l - 1, P2 - halfP, P2 - 1);

            // prepare data
            LinearAlgebraUtils.setdata(returnMatrix, winA1, tempMatrix, winA1);
            LinearAlgebraUtils.setdata(returnMatrix, winR2, tempMatrix, winA2);

            // inverse fft per row
            SpectralUtils.invfft_inplace(returnMatrix, 2);

        } else if (factorCol == 1) {

            // 1d fourier transform per column
            tempMatrix = SpectralUtils.fft(inputMatrix, 1);

            // divide by 2 'cause even fftlength
            tempMatrix.putRow(halfL, tempMatrix.getRow(halfL).mmul(half));
//            for (i=0; i<p; ++i){
//                A(halfl,i) *= half;
//            }

            // zero padding windows
            winA1 = new Window(0, halfL, 0, p - 1);
            winR2 = new Window(L2 - halfL, L2 - 1, 0, p - 1);
            winA2 = new Window(halfL, l - 1, 0, p - 1);

            // prepare data
            LinearAlgebraUtils.setdata(returnMatrix, winA1, tempMatrix, winA1);
            LinearAlgebraUtils.setdata(returnMatrix, winR2, tempMatrix, winA2);

            // inverse fft per row
            SpectralUtils.invfft_inplace(returnMatrix, 1);

        } else {

            // define extra windows for 2d oversampling
            Window winA3;
            Window winA4;
            Window winR3;
            Window winR4;

            // A=fft2d(A)
            tempMatrix = SpectralUtils.fft2D(inputMatrix);

            // divide by 2 'cause even fftlength
            tempMatrix.putColumn(halfP, tempMatrix.getColumn(halfP).mmuli(half));
            tempMatrix.putRow(halfL, tempMatrix.getRow(halfL).mmuli(half));
//            for (i=0; i<l; ++i) {
//                A(i,halfp) *= half;
//            }
//            for (i=0; i<p; ++i) {
//                A(halfl,i) *= half;
//            }

            // zero padding windows
            winA1 = new Window(0, halfL, 0, halfP);   // zero padding windows
            winA2 = new Window(0, halfL, halfP, p - 1);
            winA3 = new Window(halfL, l - 1, 0, halfP);
            winA4 = new Window(halfL, l - 1, halfP, p - 1);
            winR2 = new Window(0, halfL, P2 - halfP, P2 - 1);
            winR3 = new Window(L2 - halfL, L2 - 1, 0, halfP);
            winR4 = new Window(L2 - halfL, L2 - 1, P2 - halfP, P2 - 1);

            // prepare data
            LinearAlgebraUtils.setdata(returnMatrix, winA1, tempMatrix, winA1);
            LinearAlgebraUtils.setdata(returnMatrix, winR2, tempMatrix, winA2);
            LinearAlgebraUtils.setdata(returnMatrix, winR3, tempMatrix, winA3);
            LinearAlgebraUtils.setdata(returnMatrix, winR4, tempMatrix, winA4);

            // inverse back in 2d
            SpectralUtils.invfft2D_inplace(returnMatrix);
        }

        // scale
        returnMatrix.mmuli((double) (factorRow * factorCol));
        return returnMatrix;

    }

    public static DoubleMatrix intensity(final ComplexDoubleMatrix inputMatrix) {
        return pow(inputMatrix.real(), 2).add(pow(inputMatrix.imag(), 2));
    }

    public static DoubleMatrix magnitude(final ComplexDoubleMatrix inputMatrix) {
        return sqrt(intensity(inputMatrix));
    }

    public static DoubleMatrix angle(final ComplexDoubleMatrix cplxData) {
        DoubleMatrix phaseData = new DoubleMatrix(cplxData.rows, cplxData.columns);
        for (int i = 0; i < cplxData.length; i++) {
            phaseData.put(i, FastMath.atan2(cplxData.getImag(i), cplxData.getReal(i)));
        }
        return phaseData;
    }

    public static DoubleMatrix coherence2(final ComplexDoubleMatrix input, final ComplexDoubleMatrix norms, final int winL, final int winP) {

//        logger.info("coherence ver #2");
//        if (!(winL >= winP)) {
//            logger.warning("coherence: estimator window size L<P not very efficiently programmed.");
//        }
//
//        if (input.rows != norms.rows) {
//            logger.severe("coherence: not same dimensions.");
//            throw new IllegalArgumentException("coherence: not the same dimensions.");
//        }

        // allocate output :: account for window overlap
        final int extent_RG = input.columns;
        final int extent_AZ = input.rows - winL + 1;
        final DoubleMatrix result = new DoubleMatrix(input.rows - winL + 1, input.columns - winP + 1);

        // temp variables
        int i, j, k, l;
        ComplexDouble sum;
        ComplexDouble power;
        final int leadingZeros = (winP - 1) / 2;  // number of pixels=0 floor...
        final int trailingZeros = (winP) / 2;     // floor...

        for (j = leadingZeros; j < extent_RG - trailingZeros; j++) {

            sum = new ComplexDouble(0);
            power = new ComplexDouble(0);

            //// Compute sum over first data block ////
            int minL = j - leadingZeros;
            int maxL = minL + winP;
            for (k = 0; k < winL; k++) {
                for (l = minL; l < maxL; l++) {
                    //sum.addi(input.get(k, l));
                    //power.addi(norms.get(k, l));
                    int inI = 2 * input.index(k, l);
                    sum.set(sum.real() + input.data[inI], sum.imag() + input.data[inI + 1]);
                    power.set(power.real() + norms.data[inI], power.imag() + norms.data[inI + 1]);
                }
            }
            result.put(0, minL, coherenceProduct(sum, power));

            //// Compute (relatively) sum over rest of data blocks ////
            final int maxI = extent_AZ - 1;
            for (i = 0; i < maxI; i++) {
                final int iwinL = i + winL;
                for (l = minL; l < maxL; l++) {
                    //sum.addi(input.get(iwinL, l).sub(input.get(i, l)));
                    //power.addi(norms.get(iwinL, l).sub(norms.get(i, l)));

                    int inI = 2 * input.index(i, l);
                    int inWinL = 2 * input.index(iwinL, l);
                    sum.set(sum.real() + (input.data[inWinL] - input.data[inI]), sum.imag() + (input.data[inWinL + 1] - input.data[inI + 1]));
                    power.set(power.real() + (norms.data[inWinL] - norms.data[inI]), power.imag() + (norms.data[inWinL + 1] - norms.data[inI + 1]));
                }
                result.put(i + 1, j - leadingZeros, coherenceProduct(sum, power));
            }
        }
        return result;
    }

    static double coherenceProduct(final ComplexDouble sum, final ComplexDouble power) {
        final double product = power.real() * power.imag();
//        return (product > 0.0) ? Math.sqrt(Math.pow(sum.abs(),2) / product) : 0.0;
        return (product > 0.0) ? sum.abs() / Math.sqrt(product) : 0.0;
    }

    public static ComplexDoubleMatrix multilook(final ComplexDoubleMatrix inputMatrix, final int factorRow, final int factorColumn) {

        if (factorRow == 1 && factorColumn == 1) {
            return inputMatrix;
        }

        logger.info("multilook input [inputMatrix] size: " +
                inputMatrix.length + " lines: " + inputMatrix.rows + " pixels: " + inputMatrix.columns);

        if (inputMatrix.rows / factorRow == 0 || inputMatrix.columns / factorColumn == 0) {
            logger.info("Multilooking was not necessary for this inputMatrix: inputMatrix.rows < mlR or buffer.columns < mlC");
            return inputMatrix;
        }

        ComplexDouble sum;
        final ComplexDouble factorLP = new ComplexDouble(factorRow * factorColumn);
        ComplexDoubleMatrix outputMatrix = new ComplexDoubleMatrix(inputMatrix.rows / factorRow, inputMatrix.columns / factorColumn);
        for (int i = 0; i < outputMatrix.rows; i++) {
            for (int j = 0; j < outputMatrix.columns; j++) {
                sum = new ComplexDouble(0);
                for (int k = i * factorRow; k < (i + 1) * factorRow; k++) {
                    for (int l = j * factorColumn; l < (j + 1) * factorColumn; l++) {
                        sum.addi(inputMatrix.get(k, l));
                    }
                }
                outputMatrix.put(i, j, sum.div(factorLP));
            }
        }
        return outputMatrix;
    }

    public static ComplexDoubleMatrix computeIfg(final ComplexDoubleMatrix masterData, final ComplexDoubleMatrix slaveData) throws Exception {
        return LinearAlgebraUtils.dotmult(masterData, slaveData.conj());
    }

    public static ComplexDoubleMatrix computeIfg(final ComplexDoubleMatrix masterData, final ComplexDoubleMatrix slaveData,
                                                 final int ovsFactorAz, final int ovsFactorRg) throws Exception {
        if (ovsFactorAz == 1 && ovsFactorRg == 1) {
            return computeIfg(masterData, slaveData);
        }   else {
            return computeIfg(oversample(masterData, ovsFactorAz, ovsFactorRg), oversample(slaveData, ovsFactorAz, ovsFactorRg));
        }

    }

    public static DoubleMatrix coherence3(final ComplexDoubleMatrix input, final ComplexDoubleMatrix norms, final int winL, final int winP) {
        int rows = input.rows;
        int cols = input.columns;

        double[] satInputReal = new double[rows * cols];
        double[] satInputImag = new double[rows * cols];
        double[] satNormsReal = new double[rows * cols];
        double[] satNormsImag = new double[rows * cols];

        double[] inputData = input.data;
        double[] normsData = norms.data;

        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                int idx = c * rows + r;
                int srcIdx = 2 * idx;

                double ir = inputData[srcIdx];
                double ii = inputData[srcIdx + 1];
                double nr = normsData[srcIdx];
                double ni = normsData[srcIdx + 1];

                if (r > 0) {
                    int upIdx = idx - 1;
                    ir += satInputReal[upIdx];
                    ii += satInputImag[upIdx];
                    nr += satNormsReal[upIdx];
                    ni += satNormsImag[upIdx];
                }
                if (c > 0) {
                    int leftIdx = (c - 1) * rows + r;
                    ir += satInputReal[leftIdx];
                    ii += satInputImag[leftIdx];
                    nr += satNormsReal[leftIdx];
                    ni += satNormsImag[leftIdx];
                }
                if (r > 0 && c > 0) {
                    int diagIdx = (c - 1) * rows + (r - 1);
                    ir -= satInputReal[diagIdx];
                    ii -= satInputImag[diagIdx];
                    nr -= satNormsReal[diagIdx];
                    ni -= satNormsImag[diagIdx];
                }

                satInputReal[idx] = ir;
                satInputImag[idx] = ii;
                satNormsReal[idx] = nr;
                satNormsImag[idx] = ni;
            }
        }

        int resRows = rows - winL + 1;
        int resCols = cols - winP + 1;
        DoubleMatrix result = new DoubleMatrix(resRows, resCols);
        double[] resData = result.data;

        for (int c = 0; c < resCols; c++) {
            for (int r = 0; r < resRows; r++) {
                int r1 = r;
                int c1 = c;
                int r2 = r + winL - 1;
                int c2 = c + winP - 1;

                int A = c2 * rows + r2;
                int B = c2 * rows + (r1 - 1);
                int C = (c1 - 1) * rows + r2;
                int D = (c1 - 1) * rows + (r1 - 1);

                double sumIr = satInputReal[A];
                double sumIi = satInputImag[A];
                double sumNr = satNormsReal[A];
                double sumNi = satNormsImag[A];

                if (r1 > 0) {
                    sumIr -= satInputReal[B];
                    sumIi -= satInputImag[B];
                    sumNr -= satNormsReal[B];
                    sumNi -= satNormsImag[B];
                }
                if (c1 > 0) {
                    sumIr -= satInputReal[C];
                    sumIi -= satInputImag[C];
                    sumNr -= satNormsReal[C];
                    sumNi -= satNormsImag[C];
                }
                if (r1 > 0 && c1 > 0) {
                    sumIr += satInputReal[D];
                    sumIi += satInputImag[D];
                    sumNr += satNormsReal[D];
                    sumNi += satNormsImag[D];
                }

                double product = sumNr * sumNi;
                double val = 0.0;
                if (product > 0.0) {
                    double sumAbs = Math.sqrt(sumIr * sumIr + sumIi * sumIi);
                    val = sumAbs / Math.sqrt(product);
                }

                resData[c * resRows + r] = val;
            }
        }

        return result;
    }

    public static DoubleMatrix coherence_LPR(final ComplexDoubleMatrix input, final ComplexDoubleMatrix norms, final int winL, final int winP) {
        final int rows = input.rows;
        final int cols = input.columns;
        final int resRows = rows - winL + 1;
        final int resCols = cols - winP + 1;
        final DoubleMatrix result = new DoubleMatrix(resRows, resCols);

        for (int r = 0; r < resRows; r++) {
            for (int c = 0; c < resCols; c++) {
                final int r0 = r + (winL - 1) / 2;
                final int c0 = c + (winP - 1) / 2;

                final ComplexDoubleMatrix ifgWin = new ComplexDoubleMatrix(winL, winP);
                final ComplexDoubleMatrix normsWin = new ComplexDoubleMatrix(winL, winP);

                for (int i = 0; i < winL; i++) {
                    for (int j = 0; j < winP; j++) {
                        ifgWin.put(i, j, input.get(r + i, c + j));
                        normsWin.put(i, j, norms.get(r + i, c + j));
                    }
                }

                final DoubleMatrix phase = angle(ifgWin);
                final int nPoints = (winL - 1) * winP + winL * (winP - 1);
                final DoubleMatrix A = new DoubleMatrix(nPoints, 2);
                final DoubleMatrix b = new DoubleMatrix(nPoints, 1);
                int k = 0;

                for (int i = 0; i < winL; i++) {
                    for (int j = 0; j < winP; j++) {
                        if (i < winL - 1) {
                            A.put(k, 0, 1);
                            A.put(k, 1, 0);
                            double phaseDiff = phase.get(i + 1, j) - phase.get(i, j);
                            b.put(k, Math.atan2(Math.sin(phaseDiff), Math.cos(phaseDiff)));
                            k++;
                        }
                        if (j < winP - 1) {
                            A.put(k, 0, 0);
                            A.put(k, 1, 1);
                            double phaseDiff = phase.get(i, j + 1) - phase.get(i, j);
                            b.put(k, Math.atan2(Math.sin(phaseDiff), Math.cos(phaseDiff)));
                            k++;
                        }
                    }
                }

                final DoubleMatrix At = A.transpose();
                final DoubleMatrix AtA = At.mmul(A);
                final DoubleMatrix Atb = At.mmul(b);
                final DoubleMatrix x = Solve.solve(AtA, Atb);
                final double rampAz = x.get(0);
                final double rampRg = x.get(1);

                ComplexDouble sumIfg = new ComplexDouble(0.0);
                ComplexDouble sumNorms = new ComplexDouble(0.0);

                for (int i = 0; i < winL; i++) {
                    for (int j = 0; j < winP; j++) {
                        final double ramp = rampAz * (i - winL / 2.0) + rampRg * (j - winP / 2.0);
                        final ComplexDouble rampC = new ComplexDouble(Math.cos(ramp), -Math.sin(ramp));
                        sumIfg.addi(ifgWin.get(i, j).mul(rampC));
                        sumNorms.addi(normsWin.get(i, j));
                    }
                }
                result.put(r, c, coherenceProduct(sumIfg, sumNorms));
            }
        }
        return result;
    }
}
