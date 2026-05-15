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
 * Simple linear regression: y = a + b * x.
 */
public class SimpleLinearRegression extends BaseSVRegression {

    @Override
    protected double[] getXVector(double x) {
        return new double[]{x};
    }

    @Override
    protected boolean useLogY() {
        return false;
    }

    @Override
    protected boolean useLogX() {
        return false;
    }

    public double getIntercept() {
        return getCoef()[0];
    }

    public double getSlope() {
        return getCoef()[1];
    }
}
