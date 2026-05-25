/*
Copyright (C) 2010

This file is part of the Gabor applet
written by Max Bügler
http://www.maxbuegler.eu/

Gabor applet is free software; you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the
Free Software Foundation; either version 2, or (at your option) any
later version.

Gabor applet is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package eu.esa.sar.fex.gpf.texture;

import org.apache.commons.math3.util.FastMath;
import org.esa.snap.engine_utilities.eo.Constants;

/**
 * Date: May 7, 2010
 * Time: 3:25:34 PM
 * <p/>
 * Copyright 2010 by Max Buegler.
 * Licensed under General Public License Version 3
 */

public class GaborFilter {

    public static double[][] createGarborFilter(double lambda, double theta, double psi, double sigma, double gamma) {
        double sigma_x = sigma;
        double sigma_y = sigma / gamma;

        // Bounding box
        int nstds = 3;
        int xmax = (int) Math.ceil(Math.max(1, Math.max(Math.abs(nstds * sigma_x * FastMath.cos(theta)), Math.abs(nstds * sigma_y * FastMath.sin(theta)))));
        int ymax = (int) Math.ceil(Math.max(1, Math.max(Math.abs(nstds * sigma_x * FastMath.sin(theta)), Math.abs(nstds * sigma_y * FastMath.cos(theta)))));

        double[][] out = new double[2 * xmax + 1][2 * ymax + 1];

        // A Gabor kernel is a band-pass filter; its raw sum is near zero by construction.
        // Subtract the mean so the kernel is exactly DC-free, then normalize by L2 norm
        // so the filter response is comparable across (lambda, sigma, theta) choices.
        double sum = 0;
        final int count = (2 * xmax + 1) * (2 * ymax + 1);
        for (int x = -xmax; x <= xmax; x++) {
            for (int y = -ymax; y <= ymax; y++) {
                double x_theta = x * FastMath.cos(theta) + y * FastMath.sin(theta);
                double y_theta = -x * FastMath.sin(theta) + y * FastMath.cos(theta);
                out[x + xmax][y + ymax] = FastMath.exp(-(FastMath.pow(x_theta, 2) + FastMath.pow(gamma, 2) * FastMath.pow(y_theta, 2)) / (2 * FastMath.pow(sigma, 2))) * FastMath.cos(2 * Constants.PI * x_theta / lambda + psi);
                sum += out[x + xmax][y + ymax];
            }
        }
        final double mean = sum / count;
        double l2 = 0;
        for (int x = 0; x < 2 * xmax + 1; x++) {
            for (int y = 0; y < 2 * ymax + 1; y++) {
                out[x][y] -= mean;
                l2 += out[x][y] * out[x][y];
            }
        }
        final double norm = Math.sqrt(l2);
        if (norm > 0) {
            for (int x = 0; x < 2 * xmax + 1; x++) {
                for (int y = 0; y < 2 * ymax + 1; y++) {
                    out[x][y] /= norm;
                }
            }
        }
        return out;
    }


    public static int[][] applyGarborFilter(int[][] in, double[][] filter) {
        int xmax = (int) Math.floor(filter.length / 2.0);
        int ymax = (int) Math.floor(filter[0].length / 2.0);
        int[][] out = new int[in.length][in[0].length];
        for (int x = 0; x < in.length; x++) {
            for (int y = 0; y < in[0].length; y++) {
                double sum = 0;
                for (int xf = -xmax; xf <= xmax; xf++) {
                    for (int yf = -ymax; yf <= ymax; yf++) {
                        if (x - xf >= 0 && x - xf < in.length && y - yf >= 0 && y - yf < in[0].length)
                            sum += filter[xf + xmax][yf + ymax] * in[x - xf][y - yf];
                    }
                }
                out[x][y] = (int) Math.round(sum);
            }
        }
        return out;
    }
}
