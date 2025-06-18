/*
 * Copyright (C) 2025 SkyWatch. https://www.skywatch.com
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
package eu.esa.sar.teststacks.corner_reflectors;

import com.bc.ceres.multilevel.MultiLevelImage;
import eu.esa.sar.teststacks.corner_reflectors.utils.Plot;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.RasterDataNode;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.internal.TileImpl;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.TileIndex;
import org.jblas.ComplexDoubleMatrix;
import org.jblas.DoubleMatrix;
import org.jblas.Solve;
import org.jlinda.core.Window;
import org.jlinda.core.utils.LinearAlgebraUtils;
import org.jlinda.core.utils.SpectralUtils;

import java.awt.*;
import java.awt.image.Raster;
import java.io.File;

public class FindCRPosition {

    private static final int maxShift = 32;
    private static final int patchSize = 2 * 32 + 1;
    private static final int upSamplingFactor = 8;

    public static PixelPos findCRPosition(final File folder, final double expY, final double expX, final Product srcProduct) {

        final PixelPos initPeakPos = getInitialPeakPosition(expX, expY, srcProduct);
        if (initPeakPos == null) {
            System.err.println("Error: Could not find initial peak.");
            return null;
        }

        // measure accurate peak position by up sampling
        // 1. get subset image centered at (maxX, maxY)
        final double[][] img = getSubsetImage(initPeakPos, srcProduct);
        if (img == null) {
            System.err.println("Error: Could not get subset image.");
            return null;
        }

        // --- Plotting the original subset with initial peak ---
        PixelPos expectedInSubset = new PixelPos(maxShift, maxShift); // Center of the patch
        PixelPos detectedInSubset = new PixelPos(initPeakPos.x - (int)expX + maxShift, initPeakPos.y - (int)expY + maxShift);
        Plot.plotAndSaveImage(img, expectedInSubset, detectedInSubset, new File(folder, "initial_peak_detection_"+expY+"_"+expX+".png"));

        // 2. create a Hamming window
        final double[] hamming = createHammingWindow(patchSize);

        // 3. filter the subset image with Hamming window
        final double[][] fltImg = filterImage(img, hamming);
        if (fltImg == null) {
            System.err.println("Error: Could not filter image.");
            return null;
        }

        // 4. up sample the subset image by 8 times
        final double[][] upImg = upSamplingImage(fltImg);

        // 5. find peak position in the up sampled image
        final PixelPos finePeakPos = getAccuratePeakPosition(upImg);
        if (finePeakPos == null) {
            System.err.println("Error: Could not find accurate peak in up-sampled image.");
            return null;
        }

        // --- Plotting the up-sampled image with fine peak ---
        Plot.plotAndSaveImage(upImg, null, finePeakPos, new File(folder, "upsampled_peak_detection_"+expY+"_"+expX+".png"));

        // 6. convert the up sampled peak position to normal peak position
        final double fineX = initPeakPos.x - maxShift + finePeakPos.x / upSamplingFactor;
        final double fineY = initPeakPos.y - maxShift + finePeakPos.y / upSamplingFactor;

        return new PixelPos(fineX, fineY);
    }

    private static PixelPos getInitialPeakPosition(final double expX, final double expY, final Product srcProduct) {

        final int xc = (int)expX;
        final int yc = (int)expY;
        final int x0 = xc - maxShift;
        final int y0 = yc - maxShift;
        final int xMax = xc + maxShift;
        final int yMax = yc + maxShift;
        if (x0 < 0 || y0 < 0 || xMax >= srcProduct.getSceneRasterWidth() || yMax >= srcProduct.getSceneRasterHeight()) {
            return null;
        }

        final Rectangle sourceRectangle = new Rectangle(x0, y0, patchSize,  patchSize);
        final Band srcBand = getIntensityBand(srcProduct);
        if (srcBand == null) {
            return null;
        }
        final Tile srcTile = getSourceTile(srcBand, sourceRectangle);
        final ProductData srcData = srcTile.getDataBuffer();
        final TileIndex srcIndex = new TileIndex(srcTile);

        double maxV = 0.0;
        int maxX = -1;
        int maxY = -1;
        double sumV = 0.0; // For mean
        double sumSqV = 0.0; // For std_dev
        int count = 0;

        for (int y = y0; y <= yMax; ++y) {
            srcIndex.calculateStride(y);
            for (int x = x0; x <= xMax; ++x) {
                final int srcIdx = srcIndex.getIndex(x);
                final double v = srcData.getElemDoubleAt(srcIdx);

                sumV += v;
                sumSqV += v * v;
                count++;

                if (v > maxV) {
                    maxV = v;
                    maxX = x;
                    maxY = y;
                }
            }
        }

        if (maxX == -1 || maxY == -1) {
            return null; // No data found, or all zeros
        }

        // --- Peak Validation ---
        double meanV = sumV / count;
        double stdDevV = FastMath.sqrt((sumSqV / count) - (meanV * meanV));

        // Example threshold: peak must be 'k' standard deviations above the mean
        final double k = 5.0; // This value needs empirical tuning
        if (maxV < (meanV + k * stdDevV)) {
            // Peak is not strong enough relative to the background
            System.out.println("Warning: Detected peak at (" + maxX + ", " + maxY + ") is not significant enough (maxV: " + maxV + ", meanV: " + meanV + ", stdDevV: " + stdDevV + ").");
            return null; // Indicate that no valid CR peak was found
        }

        // Additional check: minimum absolute intensity (if applicable)
        final double minCRIntensity = 100.0; // Example value, highly dependent on data
        if (maxV < minCRIntensity) {
            System.out.println("Warning: Detected peak intensity " + maxV + " is below minimum expected CR intensity " + minCRIntensity + ".");
            return null;
        }

        return new PixelPos(maxX, maxY);
    }

    private static Tile getSourceTile(RasterDataNode rasterDataNode, Rectangle region) {
        MultiLevelImage image = rasterDataNode.getSourceImage();
        Raster awtRaster = image.getData(region);
        return new TileImpl(rasterDataNode, awtRaster);
    }

    private static Band getIntensityBand(final Product srcProduct) {
        for (Band band : srcProduct.getBands()) {
            final String unit = band.getUnit();
            if (unit.contains(Unit.AMPLITUDE) || unit.contains(Unit.INTENSITY)) {
                return band;
            }
        }
        return null;
    }

    private static double[][] getSubsetImage(final PixelPos initPeakPos, final Product srcProduct) {

        final int xc = (int)initPeakPos.x;
        final int yc = (int)initPeakPos.y;
        final int x0 = xc - maxShift;
        final int y0 = yc - maxShift;
        final int xMax = xc + maxShift;
        final int yMax = yc + maxShift;
        if (x0 < 0 || y0 < 0 || xMax >= srcProduct.getSceneRasterWidth() || yMax >= srcProduct.getSceneRasterHeight()) {
            return null;
        }

        final Rectangle sourceRectangle = new Rectangle(x0, y0, patchSize,  patchSize);
        final Band srcBand = getIntensityBand(srcProduct);
        if (srcBand == null) {
            return null;
        }
        final Tile srcTile = getSourceTile(srcBand, sourceRectangle);
        final ProductData srcData = srcTile.getDataBuffer();
        final TileIndex srcIndex = new TileIndex(srcTile);

        double[][] subsetImg = new double[patchSize][patchSize];
        for (int y = y0; y <= yMax; ++y) {
            srcIndex.calculateStride(y);
            final int yy = y - y0;
            for (int x = x0; x <= xMax; ++x) {
                final int srcIdx = srcIndex.getIndex(x);
                final int xx = x - x0;
                subsetImg[yy][xx] = srcData.getElemDoubleAt(srcIdx);
            }
        }
        return subsetImg;
    }

    private static double[] createHammingWindow(final int winLen) {

        final double[] win = new double[winLen];
        for (int i = 0; i < winLen; ++i) {
            win[i] = 0.54 - 0.46 * FastMath.cos (2.0 * Math.PI * i / (winLen - 1));
        }
        return win;
    }

    private static double[][] filterImage(final double[][] img, final double[] hamming) {

        final int rows = img.length;
        final int cols = img[0].length;
        if (hamming.length != rows || hamming.length != cols) {
            return null;
        }

        final double[][] filImg = new double[rows][cols];
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                filImg[r][c] = img[r][c] * hamming[r] * hamming[c];
            }
        }
        return filImg;
    }

    private static double[][] upSamplingImage(final double[][] fltImg) {

        final int mid = patchSize / 2;
        final int patchSizeUp = upSamplingFactor * patchSize;

        // compute image spectrum
        final ComplexDoubleMatrix fltImgMat = new ComplexDoubleMatrix(new DoubleMatrix(fltImg));
        SpectralUtils.fft2D_inplace(fltImgMat);

        // perform zero padding
        final ComplexDoubleMatrix specZP = ComplexDoubleMatrix.zeros(patchSizeUp, patchSizeUp);

        // ul
        final int[] rowIndicesUL = new int[mid + 1];
        final int[] colIndicesUL = new int[mid + 1];
        for (int r = 0; r <= mid; ++r) {rowIndicesUL[r] = r;}
        for (int c = 0; c <= mid; ++c) {colIndicesUL[c] = c;}
        final ComplexDoubleMatrix ul = fltImgMat.get(rowIndicesUL, colIndicesUL);
        org.jlinda.core.Window winIn = new org.jlinda.core.Window();
        org.jlinda.core.Window winOutUL = new org.jlinda.core.Window(0, mid, 0, mid);
        LinearAlgebraUtils.setdata(specZP, winOutUL, ul, winIn);

        // ur
        final int[] rowIndicesUR = new int[mid + 1];
        final int[] colIndicesUR = new int[patchSize - mid];
        for (int r = 0; r <= mid; ++r) {rowIndicesUR[r] = r;}
        for (int c = mid; c < patchSize; ++c) {colIndicesUR[c - mid] = c;}
        final ComplexDoubleMatrix ur = fltImgMat.get(rowIndicesUR, colIndicesUR);
        org.jlinda.core.Window winOutUR = new org.jlinda.core.Window(0, mid, patchSizeUp - patchSize + mid, patchSizeUp - 1);
        LinearAlgebraUtils.setdata(specZP, winOutUR, ur, winIn);

        // ll
        final int[] rowIndicesLL = new int[patchSize - mid];
        final int[] colIndicesLL = new int[mid + 1];
        for (int r = mid; r < patchSize; ++r) {rowIndicesLL[r - mid] = r;}
        for (int c = 0; c <= mid; ++c) {colIndicesLL[c] = c;}
        final ComplexDoubleMatrix ll = fltImgMat.get(rowIndicesLL, colIndicesLL);
        org.jlinda.core.Window winOutLL = new org.jlinda.core.Window(patchSizeUp - patchSize + mid, patchSizeUp - 1, 0, mid);
        LinearAlgebraUtils.setdata(specZP, winOutLL, ll, winIn);

        // lr
        final int[] rowIndicesLR = new int[patchSize - mid];
        final int[] colIndicesLR = new int[patchSize - mid];
        for (int r = mid; r < patchSize; ++r) {rowIndicesLR[r - mid] = r;}
        for (int c = mid; c < patchSize; ++c) {colIndicesLR[c - mid] = c;}
        final ComplexDoubleMatrix lr = fltImgMat.get(rowIndicesLR, colIndicesLR);
        org.jlinda.core.Window winOutLR = new Window(patchSizeUp - patchSize + mid, patchSizeUp - 1,
                patchSizeUp - patchSize + mid, patchSizeUp - 1);
        LinearAlgebraUtils.setdata(specZP, winOutLR, lr, winIn);

        for (int c = 0; c < patchSizeUp; ++c) {
            specZP.put(mid, c, specZP.get(mid, c).mul(0.5));
            specZP.put(patchSizeUp - patchSize + mid, c, specZP.get(patchSizeUp - patchSize + mid, c).mul(0.5));
        }
        for (int r = 0; r < patchSizeUp; ++r) {
            specZP.put(r, mid, specZP.get(r, mid).mul(0.5));
            specZP.put(r, patchSizeUp - patchSize + mid, specZP.get(r, patchSizeUp - patchSize + mid).mul(0.5));
        }

        // ifft
        SpectralUtils.invfft2D_inplace(specZP);
        DoubleMatrix imgUpMat = specZP.real();
        return imgUpMat.toArray2();
    }

    private static PixelPos getAccuratePeakPosition(final double[][] upImg) {

        final int rows = upImg.length;
        final int cols = upImg[0].length;
        double max = Double.MIN_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                if (max < upImg[r][c]) {
                    max = upImg[r][c];
                    maxX = c;
                    maxY = r;
                }
            }
        }
        if (maxX == -1 || maxY == -1) {
            return null;
        }

        if (maxX > 0 && maxX < cols - 1 && maxY > 0 && maxY < rows - 1) {
            final double[] f = new double[]{
                    upImg[maxY - 1][maxX - 1], upImg[maxY][maxX - 1], upImg[maxY + 1][maxX - 1],
                    upImg[maxY - 1][maxX], upImg[maxY][maxX], upImg[maxY + 1][maxX],
                    upImg[maxY - 1][maxX + 1], upImg[maxY][maxX + 1], upImg[maxY + 1][maxX + 1]};

            final double[][] A = {{1, -1, -1, 1, 1, 1}, {1, -1, 0, 1, 0, 0}, {1, -1, 1, 1, -1, 1},
                    {1, 0, -1, 0, 0, 1}, {1, 0, 0, 0, 0, 0}, {1, 0, 1, 0, 0, 1}, {1, 1, -1, 1, -1, 1},
                    {1, 1, 0, 1, 0, 0}, {1, 1, 1, 1, 1, 1}};

            final DoubleMatrix fMat = new DoubleMatrix(f);
            final DoubleMatrix AMat = new DoubleMatrix(A);
            final DoubleMatrix cMat = Solve.pinv(AMat).mmul(fMat);

            final double[][] B = {{-2.0*cMat.get(3), -cMat.get(4)}, {-cMat.get(4), -2.0*cMat.get(5)}};
            final double[] b = {cMat.get(1), cMat.get(2)};
            final DoubleMatrix BMat = new DoubleMatrix(B);
            final DoubleMatrix bMat = new DoubleMatrix(b);
            final DoubleMatrix pMat = Solve.solve(BMat, bMat);
            final double fineMaxX = maxX + pMat.get(0);
            final double finalMaxY = maxY + pMat.get(1);
            return new PixelPos(fineMaxX, finalMaxY);

        } else {
            return new PixelPos(maxX, maxY);
        }
    }
}
