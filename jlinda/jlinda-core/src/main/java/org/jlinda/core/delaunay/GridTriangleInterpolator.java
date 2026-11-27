package org.jlinda.core.delaunay;

import org.jlinda.core.Window;

import java.util.Arrays;

/**
 * Linear interpolation of values sampled on a regular source grid (e.g. a DEM) whose nodes have been
 * mapped to scattered positions of an output grid (e.g. radar geometry), onto that output grid.
 * <p>
 * Same contract as the two-dimensional {@link TriangleInterpolator#gridDataLinear(double[][], double[][],
 * TriangleInterpolator.ZData[], Window, double, int, int, double, int)}, but instead of building a Delaunay
 * triangulation of the scattered positions from scratch it starts from the topology of the source grid: every
 * grid cell near the output window is split into two triangles, the mesh is made Delaunay by edge flips, and
 * the triangles are scan-converted onto the output grid. This is O(n) in the number of nodes.
 * <ul>
 * <li>The diagonal of a convex cell is chosen with the Delaunay in-circle test in the (x, y * xyRatio) metric
 * (non-convex cell: the interior diagonal, folded cell: the shorter one). Edges between two regular triangles
 * that still violate the Delaunay criterion are then flipped (Lawson's algorithm), so away from layover the
 * triangles are those of the Delaunay triangulation of the nodes and the result equals the Delaunay version.</li>
 * <li>Folded triangles (orientation opposite to the dominant one, i.e. layover) never overwrite an output sample
 * already covered by a regular triangle. Where regular triangles overlap (the front and back slopes around a
 * layover), the last one in source-grid row-major order wins. The Delaunay version instead joins nodes of the
 * different slopes into one non-overlapping triangulation, so inside and next to layover the two differ.</li>
 * <li>Cells with an invalid corner ({@code invalidIndex} in x or y) are skipped. If that leaves output samples
 * uncovered that the Delaunay version would cover (they lie inside the convex hull of the valid nodes), those
 * samples, and only those, are filled by the Delaunay version, so no holes appear that it would not have.</li>
 * </ul>
 * Samples on a triangle edge are included (the Delaunay version uses a strict test), so the covered area is
 * never smaller.
 */
public final class GridTriangleInterpolator {

    /** Cells within this distance of the output window, in units of x / xScale, take part in the mesh. */
    private static final double MESH_MARGIN = 12;

    /** Barycentric tolerance for samples on triangle edges. */
    private static final double EDGE_EPS = 1e-9;

    private static final byte NOT_COVERED = 0;
    private static final byte FOLDED = 1;
    private static final byte REGULAR = 2;

    /** Optional per-call diagnostics; pass {@code null} to skip. */
    public static final class Diagnostics {
        /** Number of regular / folded triangles covering each output sample, if allocated by the caller. */
        public int[][] regularCount, foldedCount;
        /** Number of folded triangles whose centroid is nearest to each output sample (also sub-pixel ones). */
        public int[][] foldedNear;
        public int invalidCells, foldedTriangles, flips;
        /** Output samples filled by the Delaunay fallback. */
        public int fallbackSamples;
        public boolean fallbackUsed;
    }

    private GridTriangleInterpolator() {
    }

    public static void gridDataLinear(final double[][] x_in, final double[][] y_in,
                                      final TriangleInterpolator.ZData[] zList, final Window window,
                                      final double xyRatio, final int xScale, final int yScale,
                                      final double invalidIndex, final int offset) throws Exception {
        gridDataLinear(x_in, y_in, zList, window, xyRatio, xScale, yScale, invalidIndex, offset, null);
    }

    public static void gridDataLinear(final double[][] x_in, final double[][] y_in,
                                      final TriangleInterpolator.ZData[] zList, final Window window,
                                      final double xyRatio, final int xScale, final int yScale,
                                      final double invalidIndex, final int offset,
                                      final Diagnostics diag) throws Exception {

        final int rows = x_in.length;
        final int cols = rows > 0 ? x_in[0].length : 0;
        final int nx = (int) window.lines();
        final int ny = (int) window.pixels();
        if (nx <= 0 || ny <= 0) {
            return;
        }

        // node positions in output-index space: output sample (i, j) sits at (u, v) = (i, j)
        final int n = rows * cols;
        final double[] u = new double[n];
        final double[] v = new double[n];
        final boolean[] valid = new boolean[n];
        final double u0 = window.linelo + offset;
        final double v0 = window.pixlo + offset;
        int numValid = 0;
        for (int r = 0, k = 0; r < rows; r++) {
            final double[] xr = x_in[r];
            final double[] yr = y_in[r];
            for (int c = 0; c < cols; c++, k++) {
                final double x = xr[c];
                final double y = yr[c];
                if (x == invalidIndex || y == invalidIndex || Double.isNaN(x) || Double.isNaN(y)) {
                    continue;
                }
                u[k] = (x - u0) / xScale;
                v[k] = (y - v0) / yScale;
                valid[k] = true;
                numValid++;
            }
        }
        if (numValid < 3) {
            return;
        }

        // Delaunay metric (x, y * xyRatio), expressed in index space
        final double vMetric = yScale * xyRatio / xScale;

        // dominant orientation of the mapped cells: folded (layover) cells have the opposite sign
        double signedArea = 0;
        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cols - 1; c++) {
                final int k00 = r * cols + c, k01 = k00 + 1, k10 = k00 + cols, k11 = k10 + 1;
                if (valid[k00] && valid[k01] && valid[k10] && valid[k11]) {
                    signedArea += orient(u, v, k00, k01, k11) + orient(u, v, k00, k11, k10);
                }
            }
        }
        final boolean reverse = signedArea < 0;

        // Triangulate the cells near the output window: cell q holds triangles 2q and 2q+1, vertices in the
        // grid's topological order (reversed if the mapping mirrors), so regular triangles have orient() > 0.
        final int cellCols = cols - 1;
        final int numCells = Math.max(0, rows - 1) * Math.max(0, cellCols);
        final int[] tv = new int[6 * numCells];
        final int[] tn = new int[6 * numCells];
        final boolean[] live = new boolean[numCells];
        final double uLo = -MESH_MARGIN, uHi = nx - 1 + MESH_MARGIN;
        final double vLo = -MESH_MARGIN / vMetric, vHi = ny - 1 + MESH_MARGIN / vMetric;
        int invalidCells = 0;

        for (int r = 0; r < rows - 1; r++) {
            for (int c = 0; c < cellCols; c++) {
                final int k00 = r * cols + c, k01 = k00 + 1, k10 = k00 + cols, k11 = k10 + 1;
                if (!(valid[k00] && valid[k01] && valid[k10] && valid[k11])) {
                    invalidCells++;
                    continue;
                }
                final double cuMin = Math.min(Math.min(u[k00], u[k01]), Math.min(u[k10], u[k11]));
                final double cuMax = Math.max(Math.max(u[k00], u[k01]), Math.max(u[k10], u[k11]));
                final double cvMin = Math.min(Math.min(v[k00], v[k01]), Math.min(v[k10], v[k11]));
                final double cvMax = Math.max(Math.max(v[k00], v[k01]), Math.max(v[k10], v[k11]));
                if (cuMax < uLo || cuMin > uHi || cvMax < vLo || cvMin > vHi) {
                    continue;
                }

                // diagonal A: 00-11 -> (00,01,11), (00,11,10);  diagonal B: 01-10 -> (00,01,10), (01,11,10)
                final double oA1 = orient(u, v, k00, k01, k11);
                final double oA2 = orient(u, v, k00, k11, k10);
                final double oB1 = orient(u, v, k00, k01, k10);
                final double oB2 = orient(u, v, k01, k11, k10);
                final boolean aConsistent = oA1 * oA2 > 0;
                final boolean bConsistent = oB1 * oB2 > 0;
                final boolean useA;
                if (aConsistent && bConsistent) {
                    // convex cell: Delaunay in-circle test, k10 inside circle(00,01,11) -> diagonal B
                    useA = inCircle(u, v, vMetric, k00, k01, k11, k10) * oA1 <= 0;
                } else if (aConsistent != bConsistent) {
                    // non-convex cell: the interior diagonal
                    useA = aConsistent;
                } else {
                    // folded or degenerate cell
                    useA = dist2(u, v, vMetric, k00, k11) <= dist2(u, v, vMetric, k01, k10);
                }

                final int q = r * cellCols + c;
                final int t1 = 6 * q, t2 = t1 + 3;
                if (useA) {
                    setTriangle(tv, t1, k00, k01, k11, reverse);
                    setTriangle(tv, t2, k00, k11, k10, reverse);
                } else {
                    setTriangle(tv, t1, k00, k01, k10, reverse);
                    setTriangle(tv, t2, k01, k11, k10, reverse);
                }
                live[q] = true;
            }
        }

        for (int q = 0; q < numCells; q++) {
            if (!live[q]) {
                continue;
            }
            for (int t = 2 * q; t <= 2 * q + 1; t++) {
                for (int k = 0; k < 3; k++) {
                    tn[3 * t + k] = findNeighbour(t, tv[3 * t + (k + 1) % 3], tv[3 * t + (k + 2) % 3],
                            cols, rows, tv, live);
                }
            }
        }

        final int flips = legalize(u, v, vMetric, tv, tn, live);

        final int nz = zList.length;
        final double[][] zv = new double[nz][3];
        final byte[] state = new byte[nx * ny];
        int foldedTriangles = 0;
        for (int q = 0; q < numCells; q++) {
            if (!live[q]) {
                continue;
            }
            for (int t = 2 * q; t <= 2 * q + 1; t++) {
                final int a = tv[3 * t], b = tv[3 * t + 1], c = tv[3 * t + 2];
                final double o = orient(u, v, a, b, c);
                if (o < 0) {
                    foldedTriangles++;
                    if (diag != null && diag.foldedNear != null) {
                        final long i = Math.round((u[a] + u[b] + u[c]) / 3), j = Math.round((v[a] + v[b] + v[c]) / 3);
                        if (i >= 0 && i < nx && j >= 0 && j < ny) {
                            diag.foldedNear[(int) i][(int) j]++;
                        }
                    }
                }
                rasterize(u, v, a, b, c, o, o > 0, cols, zList, zv, state, nx, ny, diag);
            }
        }

        if (diag != null) {
            diag.invalidCells = invalidCells;
            diag.foldedTriangles = foldedTriangles;
            diag.flips = flips;
        }

        if (needsFallback(u, v, valid, state, nx, ny)) {
            fillFromDelaunay(x_in, y_in, zList, window, xyRatio, xScale, yScale, invalidIndex, offset, state, nx, ny, diag);
        }
    }

    private static void setTriangle(final int[] tv, final int i, final int a, final int b, final int c,
                                    final boolean reverse) {
        tv[i] = a;
        tv[i + 1] = reverse ? c : b;
        tv[i + 2] = reverse ? b : c;
    }

    private static boolean hasVertices(final int[] tv, final int t, final int p, final int q) {
        final int a = tv[3 * t], b = tv[3 * t + 1], c = tv[3 * t + 2];
        return (a == p || b == p || c == p) && (a == q || b == q || c == q);
    }

    /** The triangle sharing the edge (p, q) with triangle t of the initial cell triangulation, or -1. */
    private static int findNeighbour(final int t, final int p, final int q, final int cols, final int rows,
                                     final int[] tv, final boolean[] live) {
        if (hasVertices(tv, t ^ 1, p, q)) {
            return t ^ 1;
        }
        final int cellCols = cols - 1;
        final int cell = t >> 1;
        final int cellRow = cell / cellCols, cellCol = cell % cellCols;
        final int rp = p / cols, cp = p % cols, rq = q / cols, cq = q % cols;
        final int other;
        if (rp == rq) {
            // horizontal grid edge on node row rp, between cell rows rp - 1 and rp
            final int r = cellRow == rp ? rp - 1 : rp;
            if (r < 0 || r >= rows - 1) {
                return -1;
            }
            other = r * cellCols + Math.min(cp, cq);
        } else if (cp == cq) {
            // vertical grid edge on node column cp, between cell columns cp - 1 and cp
            final int c = cellCol == cp ? cp - 1 : cp;
            if (c < 0 || c >= cellCols) {
                return -1;
            }
            other = Math.min(rp, rq) * cellCols + c;
        } else {
            return -1;
        }
        if (!live[other]) {
            return -1;
        }
        if (hasVertices(tv, 2 * other, p, q)) {
            return 2 * other;
        }
        return hasVertices(tv, 2 * other + 1, p, q) ? 2 * other + 1 : -1;
    }

    /**
     * Lawson's algorithm: flip edges between two regular triangles that violate the Delaunay criterion until
     * none does. On a fold-free triangulation this yields the Delaunay triangulation of its nodes.
     */
    private static int legalize(final double[] u, final double[] v, final double vMetric,
                                final int[] tv, final int[] tn, final boolean[] live) {
        int[] stack = new int[Math.max(16, tv.length / 3)];
        int top = 0;
        for (int q = 0; q < live.length; q++) {
            if (live[q]) {
                stack[top++] = 2 * q;
                stack[top++] = 2 * q + 1;
            }
        }
        final long maxFlips = 20L * top + 100;
        int flips = 0;
        while (top > 0 && flips < maxFlips) {
            final int t = stack[--top];
            for (int k = 0; k < 3; k++) {
                final int n = flip(u, v, vMetric, tv, tn, t, k);
                if (n >= 0) {
                    flips++;
                    if (top + 2 > stack.length) {
                        stack = Arrays.copyOf(stack, 2 * stack.length);
                    }
                    stack[top++] = t;
                    stack[top++] = n;
                    break;
                }
            }
        }
        return flips;
    }

    /** Flips the edge of t opposite its vertex k if it is illegal; returns the neighbour flipped with, or -1. */
    private static int flip(final double[] u, final double[] v, final double vMetric,
                            final int[] tv, final int[] tn, final int t, final int k) {
        final int n = tn[3 * t + k];
        if (n < 0) {
            return -1;
        }
        final int a = tv[3 * t + k], b = tv[3 * t + (k + 1) % 3], c = tv[3 * t + (k + 2) % 3];
        final int m = tn[3 * n] == t ? 0 : tn[3 * n + 1] == t ? 1 : 2;
        if (tn[3 * n + m] != t || tv[3 * n + (m + 1) % 3] != c || tv[3 * n + (m + 2) % 3] != b) {
            return -1;
        }
        final int d = tv[3 * n + m];
        if (orient(u, v, a, b, c) <= 0 || orient(u, v, d, c, b) <= 0
                || orient(u, v, a, b, d) <= 0 || orient(u, v, a, d, c) <= 0
                || !inCircleStrict(u, v, vMetric, a, b, c, d)) {
            return -1;
        }
        final int tAB = tn[3 * t + (k + 2) % 3];
        final int tCA = tn[3 * t + (k + 1) % 3];
        final int nBD = tn[3 * n + (m + 1) % 3];
        final int nDC = tn[3 * n + (m + 2) % 3];

        tv[3 * t] = a;
        tv[3 * t + 1] = b;
        tv[3 * t + 2] = d;
        tn[3 * t] = nBD;
        tn[3 * t + 1] = n;
        tn[3 * t + 2] = tAB;
        tv[3 * n] = a;
        tv[3 * n + 1] = d;
        tv[3 * n + 2] = c;
        tn[3 * n] = nDC;
        tn[3 * n + 1] = tCA;
        tn[3 * n + 2] = t;
        replaceNeighbour(tn, nBD, n, t);
        replaceNeighbour(tn, tCA, t, n);
        return n;
    }

    private static void replaceNeighbour(final int[] tn, final int tri, final int from, final int to) {
        if (tri < 0) {
            return;
        }
        for (int k = 0; k < 3; k++) {
            if (tn[3 * tri + k] == from) {
                tn[3 * tri + k] = to;
                return;
            }
        }
    }

    private static void rasterize(final double[] u, final double[] v, final int ka, final int kb, final int kc,
                                  final double area, final boolean regular, final int cols,
                                  final TriangleInterpolator.ZData[] zList, final double[][] zv, final byte[] state,
                                  final int nx, final int ny, final Diagnostics diag) {

        if (area == 0 || Double.isNaN(area)) {
            return;
        }
        final double ua = u[ka], va = v[ka], ub = u[kb], vb = v[kb], uc = u[kc], vc = v[kc];

        final int iMin = Math.max(0, (int) Math.ceil(Math.min(ua, Math.min(ub, uc)) - EDGE_EPS));
        final int iMax = Math.min(nx - 1, (int) Math.floor(Math.max(ua, Math.max(ub, uc)) + EDGE_EPS));
        final int jMin = Math.max(0, (int) Math.ceil(Math.min(va, Math.min(vb, vc)) - EDGE_EPS));
        final int jMax = Math.min(ny - 1, (int) Math.floor(Math.max(va, Math.max(vb, vc)) + EDGE_EPS));
        if (iMin > iMax || jMin > jMax) {
            return;
        }

        final int nz = zList.length;
        for (int d = 0; d < nz; d++) {
            zv[d][0] = zValue(zList[d], ka, cols);
            zv[d][1] = zValue(zList[d], kb, cols);
            zv[d][2] = zValue(zList[d], kc, cols);
        }

        // barycentric weights wa = area(p,b,c)/area, wb = area(p,c,a)/area are affine in (i, j)
        final double inv = 1.0 / area;
        final double daI = (vb - vc) * inv, daJ = (uc - ub) * inv;   // d wa / di, d wa / dj
        final double dbI = (vc - va) * inv, dbJ = (ua - uc) * inv;
        final double ea = (ub * vc - uc * vb) * inv;                 // wa at (0, 0)
        final double eb = (uc * va - ua * vc) * inv;

        for (int i = iMin; i <= iMax; i++) {
            final double waRow = ea + daI * i;
            final double wbRow = eb + dbI * i;
            final int rowOffset = i * ny;
            for (int j = jMin; j <= jMax; j++) {
                final double wa = waRow + daJ * j;
                final double wb = wbRow + dbJ * j;
                final double wc = 1.0 - wa - wb;
                if (wa < -EDGE_EPS || wb < -EDGE_EPS || wc < -EDGE_EPS) {
                    continue;
                }
                final int idx = rowOffset + j;
                if (diag != null) {
                    if (regular && diag.regularCount != null) {
                        diag.regularCount[i][j]++;
                    } else if (!regular && diag.foldedCount != null) {
                        diag.foldedCount[i][j]++;
                    }
                }
                if (regular) {
                    state[idx] = REGULAR;
                } else if (state[idx] == NOT_COVERED) {
                    state[idx] = FOLDED;
                } else {
                    continue;
                }
                for (int d = 0; d < nz; d++) {
                    final double[] z = zv[d];
                    zList[d].z_out[i][j] = wa * z[0] + wb * z[1] + wc * z[2];
                }
            }
        }
    }

    private static double zValue(final TriangleInterpolator.ZData data, final int k, final int cols) {
        if (data.z_1d_in != null) {
            return data.z_1d_in[k];
        }
        return data.z_in[k / cols][k % cols];
    }

    private static double orient(final double[] u, final double[] v, final int a, final int b, final int c) {
        return (u[b] - u[a]) * (v[c] - v[a]) - (u[c] - u[a]) * (v[b] - v[a]);
    }

    private static double dist2(final double[] u, final double[] v, final double vMetric, final int a, final int b) {
        final double du = u[b] - u[a];
        final double dv = (v[b] - v[a]) * vMetric;
        return du * du + dv * dv;
    }

    /** Positive if d lies inside the circle through a, b, c when (a, b, c) is counter-clockwise. */
    private static double inCircle(final double[] u, final double[] v, final double vMetric,
                                   final int a, final int b, final int c, final int d) {
        final double adx = u[a] - u[d], ady = (v[a] - v[d]) * vMetric;
        final double bdx = u[b] - u[d], bdy = (v[b] - v[d]) * vMetric;
        final double cdx = u[c] - u[d], cdy = (v[c] - v[d]) * vMetric;
        final double ad = adx * adx + ady * ady;
        final double bd = bdx * bdx + bdy * bdy;
        final double cd = cdx * cdx + cdy * cdy;
        // orientation in the metric differs from orient() in index space by the positive factor vMetric
        return adx * (bdy * cd - bd * cdy) - ady * (bdx * cd - bd * cdx) + ad * (bdx * cdy - bdy * cdx);
    }

    /** True if d is inside the circle through the counter-clockwise a, b, c by more than rounding error. */
    private static boolean inCircleStrict(final double[] u, final double[] v, final double vMetric,
                                          final int a, final int b, final int c, final int d) {
        final double adx = u[a] - u[d], ady = (v[a] - v[d]) * vMetric;
        final double bdx = u[b] - u[d], bdy = (v[b] - v[d]) * vMetric;
        final double cdx = u[c] - u[d], cdy = (v[c] - v[d]) * vMetric;
        final double ad = adx * adx + ady * ady;
        final double bd = bdx * bdx + bdy * bdy;
        final double cd = cdx * cdx + cdy * cdy;
        final double t1 = adx * (bdy * cd - bd * cdy);
        final double t2 = ady * (bdx * cd - bd * cdx);
        final double t3 = ad * (bdx * cdy - bdy * cdx);
        return t1 - t2 + t3 > 1e-12 * (Math.abs(t1) + Math.abs(t2) + Math.abs(t3));
    }

    /** True if an output sample is uncovered but inside the convex hull of the valid nodes. */
    private static boolean needsFallback(final double[] u, final double[] v, final boolean[] valid,
                                         final byte[] state, final int nx, final int ny) {
        boolean anyUncovered = false;
        for (byte s : state) {
            if (s == NOT_COVERED) {
                anyUncovered = true;
                break;
            }
        }
        if (!anyUncovered) {
            return false;
        }

        final double[][] hull = convexHull(u, v, valid);
        final double[] hu = hull[0], hv = hull[1];
        final int h = hu.length;
        if (h < 3) {
            return false;
        }
        for (int i = 0; i < nx; i++) {
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int e = 0; e < h; e++) {
                final double pu = hu[e], pv = hv[e];
                final double qu = hu[(e + 1) % h], qv = hv[(e + 1) % h];
                if ((pu - i) * (qu - i) > 0) {
                    continue;
                }
                if (pu == qu) {
                    lo = Math.min(lo, Math.min(pv, qv));
                    hi = Math.max(hi, Math.max(pv, qv));
                } else {
                    final double vi = pv + (i - pu) * (qv - pv) / (qu - pu);
                    lo = Math.min(lo, vi);
                    hi = Math.max(hi, vi);
                }
            }
            if (lo > hi) {
                continue;
            }
            final int jMin = Math.max(0, (int) Math.ceil(lo + EDGE_EPS));
            final int jMax = Math.min(ny - 1, (int) Math.floor(hi - EDGE_EPS));
            for (int j = jMin; j <= jMax; j++) {
                if (state[i * ny + j] == NOT_COVERED) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Andrew's monotone chain; returns {u[], v[]} of the hull vertices in order. */
    private static double[][] convexHull(final double[] u, final double[] v, final boolean[] valid) {
        int m = 0;
        for (boolean b : valid) {
            if (b) {
                m++;
            }
        }
        final double[] pu = new double[m], pv = new double[m];
        for (int k = 0, p = 0; k < valid.length; k++) {
            if (valid[k]) {
                pu[p] = u[k];
                pv[p] = v[k];
                p++;
            }
        }
        final Integer[] idx = new Integer[m];
        for (int p = 0; p < m; p++) {
            idx[p] = p;
        }
        Arrays.sort(idx, (a, b) -> pu[a] != pu[b] ? Double.compare(pu[a], pu[b]) : Double.compare(pv[a], pv[b]));

        final int[] hullIdx = new int[2 * m];
        int k = 0;
        for (int p = 0; p < m; p++) {
            while (k >= 2 && cross(pu, pv, hullIdx[k - 2], hullIdx[k - 1], idx[p]) <= 0) {
                k--;
            }
            hullIdx[k++] = idx[p];
        }
        for (int p = m - 2, t = k + 1; p >= 0; p--) {
            while (k >= t && cross(pu, pv, hullIdx[k - 2], hullIdx[k - 1], idx[p]) <= 0) {
                k--;
            }
            hullIdx[k++] = idx[p];
        }
        final int h = Math.max(0, k - 1);
        final double[] hu = new double[h], hv = new double[h];
        for (int p = 0; p < h; p++) {
            hu[p] = pu[hullIdx[p]];
            hv[p] = pv[hullIdx[p]];
        }
        return new double[][]{hu, hv};
    }

    private static double cross(final double[] pu, final double[] pv, final int o, final int a, final int b) {
        return (pu[a] - pu[o]) * (pv[b] - pv[o]) - (pv[a] - pv[o]) * (pu[b] - pu[o]);
    }

    private static void fillFromDelaunay(final double[][] x_in, final double[][] y_in,
                                         final TriangleInterpolator.ZData[] zList, final Window window,
                                         final double xyRatio, final int xScale, final int yScale,
                                         final double invalidIndex, final int offset, final byte[] state,
                                         final int nx, final int ny, final Diagnostics diag) throws Exception {
        final int nz = zList.length;
        final TriangleInterpolator.ZData[] tmp = new TriangleInterpolator.ZData[nz];
        for (int d = 0; d < nz; d++) {
            final double[][] out = new double[nx][ny];
            for (double[] row : out) {
                Arrays.fill(row, Double.NaN);
            }
            tmp[d] = zList[d].z_1d_in != null ? new TriangleInterpolator.ZData(zList[d].z_1d_in, out)
                    : new TriangleInterpolator.ZData(zList[d].z_in, out);
        }
        TriangleInterpolator.gridDataLinear(x_in, y_in, tmp, window, xyRatio, xScale, yScale, invalidIndex, offset);

        int filled = 0;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                if (state[i * ny + j] != NOT_COVERED || Double.isNaN(tmp[0].z_out[i][j])) {
                    continue;
                }
                for (int d = 0; d < nz; d++) {
                    zList[d].z_out[i][j] = tmp[d].z_out[i][j];
                }
                filled++;
            }
        }
        if (diag != null) {
            diag.fallbackUsed = true;
            diag.fallbackSamples = filled;
        }
    }
}
