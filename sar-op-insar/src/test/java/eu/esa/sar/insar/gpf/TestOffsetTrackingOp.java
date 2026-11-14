/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.insar.gpf;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link OffsetTrackingOp}.
 */
public class TestOffsetTrackingOp {

    @Test
    public void testSpiCreatesOperator() {
        final OffsetTrackingOp op = (OffsetTrackingOp) new OffsetTrackingOp.Spi().createOperator();
        assertNotNull(op);
    }

    @Test
    public void testOperatorMetadata() {
        final OperatorMetadata md = OffsetTrackingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("Offset-Tracking", md.alias());
    }

    /**
     * The spatial averaging must equal a box mean of the ORIGINAL offsets (snapshot semantics),
     * and repeated parallel runs must give identical results. The averaging used to read
     * neighbours that other workers had already overwritten, so the output differed from the
     * box mean and from run to run.
     */
    @Test
    public void testAverageOffsetsIsSnapshotBoxMeanAndDeterministic() throws Exception {
        final int nAz = 40, nRg = 50, halfWin = 2;
        final double rgSpacing = 2.3, azSpacing = 13.9, invalid = -9999.0;
        final java.util.Random rnd = new java.util.Random(42);
        final double[][] rg0 = new double[nAz][nRg];
        final double[][] az0 = new double[nAz][nRg];
        final boolean[][] valid = new boolean[nAz][nRg];
        for (int i = 0; i < nAz; i++) {
            for (int j = 0; j < nRg; j++) {
                rg0[i][j] = rnd.nextGaussian() * 5.0;
                az0[i][j] = rnd.nextGaussian() * 5.0;
                valid[i][j] = rnd.nextDouble() > 0.1;
            }
        }

        double[][] firstRun = null;
        for (int run = 0; run < 5; run++) {
            final OffsetTrackingOp.VelocityData vd = new OffsetTrackingOp.VelocityData(nAz, nRg);
            for (int i = 0; i < nAz; i++) {
                for (int j = 0; j < nRg; j++) {
                    vd.refGCPx[i][j] = 10.0 * j;
                    vd.refGCPy[i][j] = 10.0 * i;
                    vd.rangeShift[i][j] = rg0[i][j];
                    vd.azimuthShift[i][j] = az0[i][j];
                    vd.secGCPx[i][j] = valid[i][j] ? vd.refGCPx[i][j] - rg0[i][j] / rgSpacing : invalid;
                    vd.secGCPy[i][j] = valid[i][j] ? vd.refGCPy[i][j] - az0[i][j] / azSpacing : invalid;
                }
            }
            OffsetTrackingOp.averageOffsets(vd, halfWin, rgSpacing, azSpacing, null, null);

            for (int i = 0; i < nAz; i++) {
                for (int j = 0; j < nRg; j++) {
                    if (!valid[i][j]) {
                        assertEquals("invalid GCP untouched", invalid, vd.secGCPx[i][j], 0.0);
                        continue;
                    }
                    double sr = 0, sa = 0;
                    int n = 0;
                    for (int ii = Math.max(0, i - halfWin); ii <= Math.min(nAz - 1, i + halfWin); ii++) {
                        for (int jj = Math.max(0, j - halfWin); jj <= Math.min(nRg - 1, j + halfWin); jj++) {
                            if (valid[ii][jj]) {
                                sr += rg0[ii][jj];
                                sa += az0[ii][jj];
                                n++;
                            }
                        }
                    }
                    assertEquals("range box mean at " + i + "," + j, sr / n, vd.rangeShift[i][j], 1e-12);
                    assertEquals("azimuth box mean at " + i + "," + j, sa / n, vd.azimuthShift[i][j], 1e-12);
                    assertEquals(vd.refGCPx[i][j] - (sr / n) / rgSpacing, vd.secGCPx[i][j], 1e-9);
                }
            }
            if (firstRun == null) {
                firstRun = vd.rangeShift;
            } else {
                for (int i = 0; i < nAz; i++) {
                    org.junit.Assert.assertArrayEquals(firstRun[i], vd.rangeShift[i], 0.0);
                }
            }
        }
    }
}
