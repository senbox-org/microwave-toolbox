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

/**
 * Regression base class.
 *
 * Holds the fitted coefficients, predicted Y, residuals, and the standard
 * quality metrics (r^2, adjusted r^2, RMSE, MAE, CV%).
 */
public abstract class BaseRegression {

    /** Fitted regression coefficients. */
    protected double[] coef = null;

    /**
     * Coefficient of determination
     * r^2 = 1 - residualSumOfSquares / totalSumOfSquares.
     */
    protected double rSquared = 0.0d;

    /**
     * Adjusted r^2 = 1 - (1 - r^2) * (n - 1) / (n - p - 1),
     * with p = number of regressors (excluding the constant), n = sample size.
     */
    protected double adjRSquared = 0.0d;

    protected double[] predictedY = null;

    /** residuals = observed y - predicted y. */
    protected double[] residuals = null;

    /** Root mean square error: sqrt(residualSumOfSquares / n). */
    protected double rmse = 0.0d;

    /** Mean absolute error: (1/n) * sum |residual|. */
    protected double mae = 0.0d;

    /**
     * Coefficient of variation (%): 100 * RMSE / mean(y).
     * Definition from Kross et al., "Assessment of RapidEye vegetation indices
     * for estimation of leaf area index and biomass in corn and soybean crops".
     */
    protected double cv = 0.0d;

    public double[] getCoef() {
        return coef;
    }

    public double getRSquared() {
        return rSquared;
    }

    public double getAdjustedRSquared() {
        return adjRSquared;
    }

    public double[] getPredictedY() {
        return predictedY;
    }

    public double[] getResiduals() {
        return residuals;
    }

    public double getRMSE() {
        return rmse;
    }

    public double getMAE() {
        return mae;
    }

    public double getCV() {
        return cv;
    }
}
