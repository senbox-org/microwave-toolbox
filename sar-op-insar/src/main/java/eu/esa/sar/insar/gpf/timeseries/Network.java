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
package eu.esa.sar.insar.gpf.timeseries;

import java.util.ArrayList;
import java.util.List;

/**
 * Small-baseline interferogram network used by {@link
 * eu.esa.sar.insar.gpf.SBASInversionOp}.
 *
 * Stores the chronological epoch list, the M pairs (master, slave) as
 * indices into that list, and the M x (N - 1) design matrix A produced by
 * Berardino, Fornaro, Lanari &amp; Sansosti 2002, with the reference epoch's
 * column eliminated.
 *
 *   phi_k = phi_{j_k} - phi_{i_k} + n_k
 *   A_{k, j} = +1 if j is the slave-index-after-reference
 *   A_{k, i} = -1 if i is the master-index-after-reference
 *
 * Closed triplets (a, b, c) where (a&lt;b), (b&lt;c) and pairs (a,b), (b,c),
 * (a,c) all exist in the network are enumerated for closure-phase
 * diagnostics.
 */
public final class Network {

    private final List<Long> epochMjd;          // chronological master+secondary epochs (sorted)
    private final List<String> epochDates;      // ddMMMyyyy strings, same order
    private final int refIndex;                 // index of the reference epoch within epoch lists
    private final int[][] pairs;                // M x 2 of (masterIdx, slaveIdx) in epoch list
    private final double[][] A;                 // M x (N-1) design matrix (reference column removed)
    private final int[][] triplets;             // Q x 3 of (idxAB, idxBC, idxAC) into pairs[]

    /**
     * @param epochDates    chronological epoch date strings (ddMMMyyyy)
     * @param epochMjd      same epochs, milliseconds since some epoch (only used for sort consistency check)
     * @param pairMaster    M master indices (into the epoch list); each pair's master &lt; slave in time
     * @param pairSlave     M slave indices (into the epoch list)
     * @param refIndex      reference epoch index in the epoch list
     */
    public Network(final List<String> epochDates, final List<Long> epochMjd,
                   final int[] pairMaster, final int[] pairSlave, final int refIndex) {

        if (pairMaster.length != pairSlave.length) {
            throw new IllegalArgumentException("pairMaster and pairSlave length mismatch");
        }
        final int N = epochDates.size();
        if (refIndex < 0 || refIndex >= N) {
            throw new IllegalArgumentException("refIndex out of range");
        }
        this.epochDates = epochDates;
        this.epochMjd = epochMjd;
        this.refIndex = refIndex;
        final int M = pairMaster.length;
        this.pairs = new int[M][2];
        this.A = new double[M][N - 1];

        // Map full epoch index -> column index after removing the reference column.
        final int[] col = new int[N];
        int c = 0;
        for (int i = 0; i < N; i++) {
            col[i] = (i == refIndex) ? -1 : c++;
        }

        for (int k = 0; k < M; k++) {
            final int im = pairMaster[k];
            final int is = pairSlave[k];
            if (im < 0 || im >= N || is < 0 || is >= N || im == is) {
                throw new IllegalArgumentException("pair " + k + " invalid: " + im + "->" + is);
            }
            pairs[k][0] = im;
            pairs[k][1] = is;
            if (col[im] >= 0) A[k][col[im]] = -1.0;
            if (col[is] >= 0) A[k][col[is]] = +1.0;
        }

        // Triplets: enumerate (a, b, c) with a < b < c such that pairs (a,b), (b,c), (a,c) all exist.
        final int[][] pairMatrix = new int[N][N];
        for (int[] row : pairMatrix) {
            java.util.Arrays.fill(row, -1);
        }
        for (int k = 0; k < M; k++) {
            pairMatrix[pairs[k][0]][pairs[k][1]] = k;
            pairMatrix[pairs[k][1]][pairs[k][0]] = k;
        }
        final List<int[]> tripList = new ArrayList<>();
        for (int a = 0; a < N; a++) {
            for (int b = a + 1; b < N; b++) {
                if (pairMatrix[a][b] < 0) continue;
                for (int d = b + 1; d < N; d++) {
                    if (pairMatrix[b][d] < 0 || pairMatrix[a][d] < 0) continue;
                    tripList.add(new int[]{pairMatrix[a][b], pairMatrix[b][d], pairMatrix[a][d]});
                }
            }
        }
        this.triplets = tripList.toArray(new int[0][]);
    }

    public int numEpochs() {
        return epochDates.size();
    }

    public int numPairs() {
        return pairs.length;
    }

    public int numTriplets() {
        return triplets.length;
    }

    public int refIndex() {
        return refIndex;
    }

    public String epochDate(final int idx) {
        return epochDates.get(idx);
    }

    public long epochMjd(final int idx) {
        return epochMjd.get(idx);
    }

    /** Returns A[M][N-1], reference column already removed. */
    public double[][] designMatrix() {
        return A;
    }

    /** Pair (master, slave) indices into the epoch list. */
    public int[] pair(final int k) {
        return pairs[k];
    }

    /** Triplet (idxAB, idxBC, idxAC) into the pair list. */
    public int[] triplet(final int q) {
        return triplets[q];
    }

    /** Map a non-reference epoch index to its column in the design matrix. */
    public int columnForEpoch(final int epochIdx) {
        if (epochIdx == refIndex) return -1;
        return (epochIdx < refIndex) ? epochIdx : epochIdx - 1;
    }
}
