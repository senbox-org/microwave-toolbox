/*
 * Copyright (C) 2016 by Array Systems Computing Inc. http://www.array.ca
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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
package org.csa.rstb.biomass.gpf.regression;

import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

/**
 * Single-variable regression base. Subclasses choose:
 *  - {@link #getXVector(double)} — basis expansion of the x value (e.g.
 *    polynomial: [x, x^2, x^3, ...]);
 *  - {@link #useLogY()} / {@link #useLogX()} — whether to fit log-transformed
 *    observations (exponential / logarithmic / log-log).
 *
 * The fit is delegated to commons-math3's
 * {@link OLSMultipleLinearRegression}.
 */
public abstract class BaseSVRegression extends BaseRegression {

    protected abstract double[] getXVector(double x);

    protected abstract boolean useLogY();

    protected abstract boolean useLogX();

    /**
     * @param y    observed values (length n)
     * @param x    predictor values (length n, same indexing as y)
     * @param yMin lower bound used when {@link #useLogY()} is true and y can
     *             be non-positive: log(y - yMin + eps) is fit instead of log(y)
     * @param xMin lower bound used analogously for {@link #useLogX()}
     */
    public void setValues(final double[] y, final double[] x, final double yMin, final double xMin) {

        final double epsilon = 1e-30;

        if (y == null || x == null) {
            throw new IllegalArgumentException("Null input");
        }

        if (y.length != x.length) {
            throw new IllegalArgumentException("Number of y and x samples must agree");
        }

        mae = 0.0d;

        final OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();

        final double[][] xData = new double[x.length][];
        if (useLogX()) {
            if (xMin > 0.0) {
                for (int i = 0; i < x.length; i++) {
                    xData[i] = getXVector(Math.log(x[i]));
                }
            } else {
                for (int i = 0; i < x.length; i++) {
                    xData[i] = getXVector(Math.log(x[i] - xMin + epsilon));
                }
            }
        } else {
            for (int i = 0; i < x.length; i++) {
                xData[i] = getXVector(x[i]);
            }
        }

        ols.setNoIntercept(false);

        final double[] yData = new double[y.length];
        if (useLogY()) {
            if (yMin > 0.0) {
                for (int i = 0; i < yData.length; i++) {
                    yData[i] = Math.log(y[i]);
                }
            } else {
                for (int i = 0; i < yData.length; i++) {
                    yData[i] = Math.log(y[i] - yMin + epsilon);
                }
            }
        } else {
            System.arraycopy(y, 0, yData, 0, y.length);
        }
        ols.newSampleData(yData, xData);

        coef = ols.estimateRegressionParameters();

        double meanY = 0.0d;
        for (double aYData : yData) {
            meanY += aYData;
        }
        meanY /= yData.length;

        final double residualSumOfSquares = ols.calculateResidualSumOfSquares();
        final double totalSumOfSquares = ols.calculateTotalSumOfSquares();
        residuals = ols.estimateResiduals();

        for (double residual : residuals) {
            mae += Math.abs(residual);
        }

        rSquared = 1 - (residualSumOfSquares / totalSumOfSquares);
        rmse = Math.sqrt(residualSumOfSquares / x.length);
        final double n = x.length;
        final double p = xData[0].length;
        adjRSquared = (rSquared * (n - 1) - p) / (n - p - 1);
        mae /= x.length;
        cv = 100 * rmse / meanY;
    }
}
