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
 * Polynomial regression of order {@code degree}:
 * y = a + b1*x + b2*x^2 + ... + b_degree * x^degree.
 * Degree 1 reduces to simple linear regression.
 */
public class PolynomialRegression extends BaseSVRegression {

    final int degree;

    public PolynomialRegression(final int degree) {
        if (degree < 1) {
            throw new IllegalArgumentException("Degree must be at least 1");
        }
        this.degree = degree;
    }

    @Override
    protected double[] getXVector(double x) {
        final double[] xVector = new double[degree];
        xVector[0] = x;
        for (int i = 1; i < degree; i++) {
            xVector[i] = xVector[i - 1] * x;
        }
        return xVector;
    }

    @Override
    protected boolean useLogY() {
        return false;
    }

    @Override
    protected boolean useLogX() {
        return false;
    }

    public double getOneCoef(final int degree) {
        return getCoef()[degree];
    }

    /** @return [a, b1, b2, ..., b_degree]. */
    public double[] getAllCoef() {
        return getCoef();
    }
}
