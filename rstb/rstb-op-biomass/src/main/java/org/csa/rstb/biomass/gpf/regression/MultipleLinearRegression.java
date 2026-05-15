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
 * Multiple-variable linear regression:
 * y = a0 + a1*x1 + a2*x2 + ... + an*xn.
 *
 * Delegates the fit to commons-math3
 * {@link OLSMultipleLinearRegression}.
 */
public class MultipleLinearRegression extends BaseRegression {

    public void setValues(final double[] y, final double[][] x) {

        if (y == null || x == null) {
            throw new IllegalArgumentException("Null input");
        }

        final OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.setNoIntercept(false);
        ols.newSampleData(y, x);

        coef = ols.estimateRegressionParameters();

        if (coef.length != x[0].length + 1) {
            throw new IllegalArgumentException("wrong number of regression coefficients");
        }

        double meanY = 0.0d;
        for (double aY : y) {
            meanY += aY;
        }
        meanY = meanY / y.length;

        predictedY = new double[y.length];
        residuals = ols.estimateResiduals();
        for (int i = 0; i < residuals.length; i++) {
            predictedY[i] = y[i] - residuals[i];
        }

        rSquared = ols.calculateRSquared();
        adjRSquared = ols.calculateAdjustedRSquared();

        final double n = y.length;
        final double residualSumOfSquares = ols.calculateResidualSumOfSquares();
        rmse = Math.sqrt(residualSumOfSquares / n);

        mae = 0.0d;
        for (double residual : residuals) {
            mae += Math.abs(residual);
        }
        mae /= n;

        cv = 100 * rmse / meanY;
    }

    public double getIntercept() {
        return coef[0];
    }

    public double[] getSlopes() {
        final double[] slopes = new double[coef.length - 1];
        System.arraycopy(coef, 1, slopes, 0, slopes.length);
        return slopes;
    }
}
