/*
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
package org.csa.rstb.biomass.gpf;

import org.csa.rstb.biomass.gpf.regression.ExponentialRegression;
import org.csa.rstb.biomass.gpf.regression.MultipleLinearRegression;
import org.csa.rstb.biomass.gpf.regression.PolynomialRegression;
import org.csa.rstb.biomass.gpf.regression.SimpleLinearRegression;
import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestRegression {

    @Test
    public void simple_linear_recovers_known_line() {
        // y = 3 + 2x
        final double[] x = {0, 1, 2, 3, 4, 5};
        final double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = 3.0 + 2.0 * x[i];

        final SimpleLinearRegression reg = new SimpleLinearRegression();
        reg.setValues(y, x, 0.0, 0.0);

        assertEquals(3.0, reg.getIntercept(), 1.0e-9);
        assertEquals(2.0, reg.getSlope(), 1.0e-9);
        assertEquals(1.0, reg.getRSquared(), 1.0e-9);
        assertEquals(0.0, reg.getRMSE(), 1.0e-9);
    }

    @Test
    public void exponential_recovers_y_equals_a_exp_bx() {
        // y = 2.5 * exp(0.4 * x)
        final double a = 2.5, b = 0.4;
        final double[] x = {0, 1, 2, 3, 4, 5, 6};
        final double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = a * Math.exp(b * x[i]);

        final ExponentialRegression reg = new ExponentialRegression();
        reg.setValues(y, x, 0.0, 0.0);

        assertEquals(a, reg.getA(), 1.0e-6);
        assertEquals(b, reg.getB(), 1.0e-9);
    }

    @Test
    public void polynomial_recovers_quadratic() {
        // y = 1 + 2x + 3x^2
        final double[] x = {0, 1, 2, 3, 4, 5, 6};
        final double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = 1.0 + 2.0 * x[i] + 3.0 * x[i] * x[i];

        final PolynomialRegression reg = new PolynomialRegression(2);
        reg.setValues(y, x, 0.0, 0.0);

        final double[] coef = reg.getAllCoef();
        assertEquals(1.0, coef[0], 1.0e-9);
        assertEquals(2.0, coef[1], 1.0e-9);
        assertEquals(3.0, coef[2], 1.0e-9);
    }

    @Test
    public void multiple_linear_recovers_known_plane() {
        // y = 1 + 2*x1 + 3*x2
        final double[][] x = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}};
        final double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = 1.0 + 2.0 * x[i][0] + 3.0 * x[i][1];

        final MultipleLinearRegression reg = new MultipleLinearRegression();
        reg.setValues(y, x);

        assertEquals(1.0, reg.getIntercept(), 1.0e-9);
        final double[] slopes = reg.getSlopes();
        assertEquals(2.0, slopes[0], 1.0e-9);
        assertEquals(3.0, slopes[1], 1.0e-9);
        assertEquals(1.0, reg.getRSquared(), 1.0e-9);
    }

    @Test
    public void central_statistic_measure_mean_median_mode() {
        final ArrayList<Float> list = new ArrayList<>();
        list.add(2.0f);
        list.add(4.0f);
        list.add(4.0f);
        list.add(6.0f);
        final CentralStatisticMeasure csm = new CentralStatisticMeasure(list, -1f);
        assertEquals(4.0, csm.getMean(), 1.0e-6);
        assertEquals(4.0, csm.getMedian(), 1.0e-6);
        assertEquals(4.0, csm.getMode(), 1.0e-6);

        final ArrayList<Float> empty = new ArrayList<>();
        final CentralStatisticMeasure csmEmpty = new CentralStatisticMeasure(empty, -1f);
        assertEquals(-1.0, csmEmpty.getMean(), 0.0);
        assertEquals(-1.0, csmEmpty.getMedian(), 0.0);
        assertEquals(-1.0, csmEmpty.getMode(), 0.0);
    }

    @Test
    public void central_statistic_measure_median_even_count() {
        final ArrayList<Float> list = new ArrayList<>();
        list.add(1.0f);
        list.add(3.0f);
        list.add(5.0f);
        list.add(7.0f);
        // median of [1, 3, 5, 7] = (3 + 5) / 2 = 4
        final CentralStatisticMeasure csm = new CentralStatisticMeasure(list, -1f);
        assertEquals(4.0f, csm.getMedian(), 1.0e-6);
    }
}
