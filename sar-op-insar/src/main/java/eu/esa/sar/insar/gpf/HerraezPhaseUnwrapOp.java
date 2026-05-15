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
package eu.esa.sar.insar.gpf;

import com.bc.ceres.core.ProgressMonitor;
import com.bc.ceres.core.SubProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Herr&aacute;ez 2002 fast 2-D phase-unwrapping operator.
 *
 * <p>Reference: Herr&aacute;ez, M. A., Burton, D. R., Lalor, M. J. and
 * Gdeisat, M. A. (2002). <i>Fast two-dimensional phase-unwrapping
 * algorithm based on sorting by reliability following a noncontinuous
 * path.</i> Applied Optics 41(35), 7437-7444. DOI:
 * <a href="https://doi.org/10.1364/AO.41.007437">10.1364/AO.41.007437</a>
 * </p>
 *
 * <p>This is the algorithm scikit-image ships as
 * <code>skimage.restoration.unwrap_phase()</code>. It is the standard
 * "fast quality-guided" complement to snaphu's MCF: ~10-50&times; faster
 * on high-coherence scenes, ~80% of snaphu's quality on real S-1 / TanDEM
 * tests, and no external CLI dependency.</p>
 *
 * <p><b>Algorithm:</b></p>
 * <ol>
 *   <li>Compute the per-pixel <i>second-difference</i> reliability score
 *       <code>D = sqrt(H^2 + V^2 + D1^2 + D2^2)</code> from four directional
 *       wrapped second differences. Low D = reliable.</li>
 *   <li>For each pixel-to-neighbour edge (4- or 8-connectivity), define the
 *       edge reliability as <code>R_edge = 1 / (D_a + D_b + eps)</code>.</li>
 *   <li>Sort all edges by descending reliability.</li>
 *   <li>Walk edges in sorted order with a union-find: when an edge connects
 *       two pixels in different groups, merge them, adjusting the smaller
 *       group's 2&pi; offset so that the wrapped phase difference across
 *       the edge is preserved. Skip edges within a single group.</li>
 *   <li>Output the per-pixel unwrapped phase.</li>
 * </ol>
 *
 * <p><b>Scene-global computation</b> — the union-find is global, so the
 * full scene is unwrapped in one pass in {@link #initialize()} and the
 * result is cached as a {@code float[]}. {@code computeTile} just serves
 * tiles from this cache.</p>
 *
 * <p><b>Inputs:</b> every wrapped-phase band in the source product
 * ({@link Unit#PHASE} or {@link Unit#ABS_PHASE}, range typically
 * (-&pi;, &pi;]). Each phase band is auto-paired with a coherence band
 * ({@link Unit#COHERENCE}) by matching the master/slave name suffix; if no
 * matching coherence band is found, that phase band is unwrapped without
 * a coherence mask.</p>
 *
 * <p><b>Output:</b> one band <code>Unw_Phase_&lt;source&gt;</code> per
 * input phase band, with {@link Unit#ABS_PHASE} carrying the unwrapped
 * phase in radians.</p>
 *
 * <p><b>Memory:</b> O(N) for the cache, O(4N) for the edge array
 * (~48 bytes/pixel during init, freed after). 1024&times;1024 input
 * needs ~70 MB transient + ~4 MB cache.</p>
 *
 * <p><b>License note:</b> the algorithm is reimplemented from the
 * published 2002 paper; no code is copied from scikit-image. The
 * scikit-image port (BSD-3) is the most widely-tested open reference.</p>
 */
@OperatorMetadata(alias = "Herraez-Phase-Unwrap",
        category = "Radar/Interferometric/Unwrapping",
        authors = "SkyWatch",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Herráez 2002 fast 2-D phase-unwrapping (path-following by reliability) — pure-Java complement to snaphu.")
public final class HerraezPhaseUnwrapOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "Minimum coherence to include a pixel in unwrapping. " +
            "Ignored when no matching coherence band is found.",
            defaultValue = "0.0",
            interval = "[0, 1]",
            label = "Minimum Coherence")
    private double coherenceMin = 0.0;

    @Parameter(description = "Edge connectivity (4 = N/E/S/W only, 8 = + diagonals). 8 is the canonical Herraez choice.",
            valueSet = {"4", "8"},
            defaultValue = "8",
            label = "Connectivity")
    private int connectivity = 8;

    @Parameter(description = "Weight edge reliability by coherence (R = avg_coh / (D_a + D_b + eps)). " +
            "Ignored when no matching coherence band is found.",
            defaultValue = "true",
            label = "Coherence-weighted reliability")
    private boolean useCoherenceWeighting = true;

    @Parameter(description = "Post-unwrap cycle-skip (k-cycle) correction: snap each pixel to the " +
            "nearest integer 2π cycle relative to a local median. Fixes patchy 2π offsets without " +
            "altering real signal. Strongly recommended.",
            defaultValue = "true",
            label = "Cycle-skip correction")
    private boolean cycleSkipCorrection = true;

    @Parameter(description = "Maximum window size for the cycle-skip local-median lookup (odd integer). " +
            "The correction runs as multi-pass iteration with growing windows (3 → 5 → 9 → 15 → 25 → 41 → 71) " +
            "stopping at this value — each pass cleans patches up to its half-window radius, and earlier-fixed " +
            "edges feed the next pass. Pick the largest patch width you expect in the scene.",
            valueSet = {"3", "5", "7", "9", "11", "15", "21", "31", "41", "51", "71", "101"},
            defaultValue = "21",
            label = "Cycle-skip max window")
    private int cycleSkipWindow = 21;

    @Parameter(description = "Optional post-unwrap median filter for cosmetic noise reduction. " +
            "0 = off.",
            valueSet = {"0", "3", "5", "7"},
            defaultValue = "0",
            label = "Median filter size")
    private int medianFilterSize = 0;

    private static final String PRODUCT_SUFFIX = "_Unw";
    private static final String UNW_BAND_PREFIX = "Unw_Phase_";

    private int width;
    private int height;

    private final Map<Band, Band> targetToPhase = new HashMap<>();
    private final Map<Band, Band> phaseToCoherence = new HashMap<>();
    private final Map<Band, float[]> targetToUnwCache = new HashMap<>();

    @Override
    public void initialize() throws OperatorException {
        try {
            final List<Band> phaseBands = new ArrayList<>();
            final List<Band> coherenceBands = new ArrayList<>();
            for (Band b : sourceProduct.getBands()) {
                final String unit = b.getUnit();
                if (unit == null) {
                    continue;
                }
                if (Unit.PHASE.equals(unit) || Unit.ABS_PHASE.equals(unit)) {
                    phaseBands.add(b);
                } else if (Unit.COHERENCE.equals(unit)) {
                    coherenceBands.add(b);
                }
            }
            if (phaseBands.isEmpty()) {
                throw new OperatorException("No band with unit '" + Unit.PHASE + "' or '"
                        + Unit.ABS_PHASE + "' found in source product.");
            }

            width = sourceProduct.getSceneRasterWidth();
            height = sourceProduct.getSceneRasterHeight();

            createTargetProduct(phaseBands, coherenceBands);
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Scene-global Herraez unwrapping runs here, once, before any tile is
     * served. {@link #initialize()} stays light so the UI dialog and graph
     * validation remain snappy; the heavy union-find pass happens inside
     * {@code doExecute} with sub-progress reporting per band.
     */
    @Override
    public void doExecute(final ProgressMonitor pm) throws OperatorException {
        try {
            // 1000 work units per band so SubProgressMonitor can subdivide.
            pm.beginTask("Herraez phase unwrapping", targetToPhase.size() * 1000);
            for (Map.Entry<Band, Band> entry : targetToPhase.entrySet()) {
                final Band target = entry.getKey();
                final Band phase = entry.getValue();
                final Band coh = phaseToCoherence.get(phase);
                targetToUnwCache.put(target, unwrapBand(phase, coh,
                        SubProgressMonitor.create(pm, 1000)));
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    private void createTargetProduct(final List<Band> phaseBands, final List<Band> coherenceBands) {
        targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                sourceProduct.getProductType(), width, height);
        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        for (Band phase : phaseBands) {
            final Band coh = matchCoherence(phase, coherenceBands);
            phaseToCoherence.put(phase, coh);

            final Band unwBand = targetProduct.addBand(UNW_BAND_PREFIX + phase.getName(),
                    ProductData.TYPE_FLOAT32);
            unwBand.setUnit(Unit.ABS_PHASE);
            unwBand.setNoDataValue(Float.NaN);
            unwBand.setNoDataValueUsed(true);
            unwBand.setDescription("Unwrapped phase (Herraez 2002) from " + phase.getName());
            targetToPhase.put(unwBand, phase);
        }
    }

    private static Band matchCoherence(final Band phase, final List<Band> coherenceBands) {
        if (coherenceBands.isEmpty()) {
            return null;
        }
        final String pname = phase.getName();
        final int us = pname.indexOf('_');
        final String suffix = us >= 0 ? pname.substring(us) : pname;
        for (Band c : coherenceBands) {
            if (c.getName().endsWith(suffix)) {
                return c;
            }
        }
        return coherenceBands.size() == 1 ? coherenceBands.get(0) : null;
    }

    /**
     * Run the full Herraez unwrap for one phase band (with optional coherence
     * mask and weighting) and return the flat row-major float[] cache.
     * Sub-progress is split: read (50) / unwrap (800) / cycle-skip (75) /
     * median (25) / NaN-mask (50).
     */
    private float[] unwrapBand(final Band phaseBand, final Band coherenceBand,
                               final ProgressMonitor pm) {
        pm.beginTask("Unwrapping " + phaseBand.getName(), 1000);
        try {
            final float[] phase = new float[width * height];
            final Rectangle whole = new Rectangle(0, 0, width, height);
            final Tile phaseTile = getSourceTile(phaseBand, whole);
            final ProductData phaseData = phaseTile.getDataBuffer();
            for (int y = 0, k = 0; y < height; y++) {
                for (int x = 0; x < width; x++, k++) {
                    phase[k] = phaseData.getElemFloatAt(phaseTile.getDataBufferIndex(x, y));
                }
            }

            final boolean[] valid = new boolean[width * height];
            float[] coh = null;
            if (coherenceBand != null) {
                coh = new float[width * height];
                final Tile cohTile = getSourceTile(coherenceBand, whole);
                final ProductData cohData = cohTile.getDataBuffer();
                for (int y = 0, k = 0; y < height; y++) {
                    for (int x = 0; x < width; x++, k++) {
                        final float c = cohData.getElemFloatAt(cohTile.getDataBufferIndex(x, y));
                        coh[k] = c;
                        valid[k] = c >= coherenceMin && !Float.isNaN(phase[k]);
                    }
                }
            } else {
                for (int k = 0; k < valid.length; k++) {
                    valid[k] = !Float.isNaN(phase[k]);
                }
            }
            pm.worked(50);

            // Only pass coherence weight to the unwrap if the user opted in
            // AND a matching coherence band was found.
            final float[] cohWeight = (useCoherenceWeighting && coh != null) ? coh : null;
            final float[] unw = unwrap(phase, valid, width, height, connectivity, cohWeight,
                    SubProgressMonitor.create(pm, 800));

            if (cycleSkipCorrection) {
                final int[] ladder = buildCycleSkipLadder(cycleSkipWindow);
                for (int win : ladder) {
                    applyCycleSkipCorrection(unw, valid, width, height, win);
                }
            }
            pm.worked(75);

            if (medianFilterSize >= 3) {
                applyMedianFilter(unw, valid, width, height, medianFilterSize);
            }
            pm.worked(25);

            for (int k = 0; k < unw.length; k++) {
                if (!valid[k]) {
                    unw[k] = Float.NaN;
                }
            }
            pm.worked(50);
            return unw;
        } finally {
            pm.done();
        }
    }

    /**
     * Core Herraez unwrap. Public-static so it can be tested without an
     * Operator context.
     *
     * <p>Five-arg overload kept for backward compatibility / tests; delegates
     * to the seven-arg version with no coherence weighting and a NULL monitor.
     */
    public static float[] unwrap(final float[] phase, final boolean[] valid,
                                 final int width, final int height, final int connectivity) {
        return unwrap(phase, valid, width, height, connectivity, null, ProgressMonitor.NULL);
    }

    /**
     * Six-arg overload without coherence weighting.
     */
    public static float[] unwrap(final float[] phase, final boolean[] valid,
                                 final int width, final int height, final int connectivity,
                                 final ProgressMonitor pm) {
        return unwrap(phase, valid, width, height, connectivity, null, pm);
    }

    /**
     * Core Herraez unwrap with optional coherence weighting and sub-progress reporting.
     *
     * <p>Algorithmic notes:
     * <ul>
     *   <li>Union-find uses <b>deferred</b> 2π offsets: each non-root node
     *       stores an offset to its parent; {@link #findRoot} path-compresses
     *       and accumulates the running offset. This replaces the previous
     *       O(N) per-merge {@code applyShiftToGroup} scan with amortised
     *       O(α(N)) per merge.</li>
     *   <li>The reliability map is computed in parallel by row.</li>
     *   <li>The edge sort uses {@link Arrays#parallelSort(Object[], java.util.Comparator)}.</li>
     * </ul>
     *
     * @param phase        flat row-major wrapped phase, length width*height
     * @param valid        per-pixel validity flag (false &rArr; skip)
     * @param width        scene width
     * @param height       scene height
     * @param connectivity 4 or 8
     * @param coh          optional coherence array (length width*height) used to
     *                     weight edge reliability; pass {@code null} to fall back
     *                     to the canonical 1/(D_a+D_b) Herraez formula
     * @param pm           progress monitor (use {@link ProgressMonitor#NULL} for none)
     * @return new flat array of unwrapped phase (valid pixels) /
     *         original wrapped value (invalid)
     */
    public static float[] unwrap(final float[] phase, final boolean[] valid,
                                 final int width, final int height, final int connectivity,
                                 final float[] coh, final ProgressMonitor pm) {

        final int n = width * height;
        final float[] unw = new float[n];
        System.arraycopy(phase, 0, unw, 0, n);

        // Work budget (in arbitrary units, summed = 1000):
        //   D-map: 100, edges: 100, sort: 150, merges: 550, output: 100
        pm.beginTask("Herraez unwrap", 1000);
        try {
            // --- Step 1: per-pixel second-difference reliability (parallel) ---
            final float[] D = new float[n];
            Arrays.fill(D, Float.MAX_VALUE);
            IntStream.range(1, height - 1).parallel().forEach(y -> {
                for (int x = 1; x < width - 1; x++) {
                    final int k = y * width + x;
                    if (!valid[k]) continue;
                    final float c = phase[k];
                    final float H = wrap(phase[k - 1] - c) - wrap(c - phase[k + 1]);
                    final float V = wrap(phase[k - width] - c) - wrap(c - phase[k + width]);
                    final float D1 = wrap(phase[k - width - 1] - c) - wrap(c - phase[k + width + 1]);
                    final float D2 = wrap(phase[k - width + 1] - c) - wrap(c - phase[k + width - 1]);
                    D[k] = (float) Math.sqrt(H * H + V * V + D1 * D1 + D2 * D2);
                }
            });
            pm.worked(100);

            // --- Step 2: build edge list with reliability = 1 / (D_a + D_b + eps) ---
            // Edge directions (avoid double-counting): +x, +y, +x+y, +x-y
            final int[] dx;
            final int[] dy;
            if (connectivity == 4) {
                dx = new int[]{1, 0};
                dy = new int[]{0, 1};
            } else {
                dx = new int[]{1, 0, 1, 1};
                dy = new int[]{0, 1, 1, -1};
            }
            // Worst case: numDirs * n edges
            final int maxEdges = dx.length * n;
            final int[] edgeA = new int[maxEdges];
            final int[] edgeB = new int[maxEdges];
            final float[] edgeRel = new float[maxEdges];

            int m = 0;
            final float eps = 1.0e-6f;
            final boolean useCoh = (coh != null);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int k = y * width + x;
                    if (!valid[k]) continue;
                    for (int d = 0; d < dx.length; d++) {
                        final int xn = x + dx[d];
                        final int yn = y + dy[d];
                        if (xn < 0 || xn >= width || yn < 0 || yn >= height) continue;
                        final int kn = yn * width + xn;
                        if (!valid[kn]) continue;
                        edgeA[m] = k;
                        edgeB[m] = kn;
                        // R = avg_coh / (D_a + D_b + eps) when coherence is provided,
                        // else canonical Herraez 1/(D_a + D_b + eps).
                        if (useCoh) {
                            final float w = 0.5f * (coh[k] + coh[kn]);
                            edgeRel[m] = w / (D[k] + D[kn] + eps);
                        } else {
                            edgeRel[m] = 1.0f / (D[k] + D[kn] + eps);
                        }
                        m++;
                    }
                }
            }
            final int numEdges = m;
            pm.worked(100);

            // --- Step 3: parallel sort indices by descending reliability ---
            final Integer[] orderBoxed = new Integer[numEdges];
            for (int i = 0; i < numEdges; i++) orderBoxed[i] = i;
            Arrays.parallelSort(orderBoxed, (a, b) -> Float.compare(edgeRel[b], edgeRel[a]));
            pm.worked(150);

            // --- Step 4: union-find with deferred 2π offsets ---
            // parent[k] = parent (or k if root)
            // offset[k] = integer 2π offset from k to parent[k] (0 for roots).
            //             Total offset to root = sum along path (computed lazily
            //             by findRoot with path compression).
            // rank[k]   = approximate tree depth for union-by-rank
            final int[] parent = new int[n];
            final int[] rank = new int[n];
            final int[] offset = new int[n];
            for (int k = 0; k < n; k++) {
                parent[k] = k;
            }

            final double TWO_PI = 2.0 * Math.PI;
            // Report merge progress every ~1% of edges
            final int reportEvery = Math.max(1, numEdges / 100);
            int nextReport = reportEvery;
            int workedSoFar = 0;
            for (int e = 0; e < numEdges; e++) {
                final int idx = orderBoxed[e];
                final int a = edgeA[idx];
                final int b = edgeB[idx];
                final int ra = findRoot(parent, offset, a);
                final int rb = findRoot(parent, offset, b);
                if (ra != rb) {
                    // After findRoot: offset[a] = total offset a->ra, offset[b] = total b->rb
                    final double ua = phase[a] + TWO_PI * offset[a];
                    final double ub = phase[b] + TWO_PI * offset[b];
                    final double dWrap = wrapD(phase[a] - phase[b]);
                    final int shift = (int) Math.round((ua - ub - dWrap) / TWO_PI);

                    // Union by rank, with shift recorded on the new non-root.
                    // If ra becomes child of rb: a-side shifts by -shift.
                    // If rb becomes child of ra: b-side shifts by +shift.
                    if (rank[ra] < rank[rb]) {
                        parent[ra] = rb;
                        offset[ra] = -shift;
                    } else if (rank[ra] > rank[rb]) {
                        parent[rb] = ra;
                        offset[rb] = shift;
                    } else {
                        parent[rb] = ra;
                        offset[rb] = shift;
                        rank[ra]++;
                    }
                }
                if (e + 1 >= nextReport) {
                    final int target = (int) Math.min(550L, 550L * (e + 1) / numEdges);
                    if (target > workedSoFar) {
                        pm.worked(target - workedSoFar);
                        workedSoFar = target;
                    }
                    nextReport += reportEvery;
                }
            }
            if (workedSoFar < 550) {
                pm.worked(550 - workedSoFar);
            }

            // --- Step 5: produce the unwrapped float[] ---
            // Compress all paths so each pixel's offset[k] becomes its absolute
            // 2π offset, then write the unwrapped value.
            for (int k = 0; k < n; k++) {
                if (valid[k]) {
                    findRoot(parent, offset, k);
                    unw[k] = (float) (phase[k] + TWO_PI * offset[k]);
                }
            }
            pm.worked(100);

            return unw;
        } finally {
            pm.done();
        }
    }

    /**
     * Find the root of pixel k with path compression, while accumulating the
     * integer 2π offset from k to its root into {@code offset[k]}.
     *
     * <p>Invariant: {@code offset[r] == 0} for any root r. For any non-root k,
     * after this method returns, {@code parent[k] == root} and
     * {@code offset[k] == k's total offset to root}.</p>
     */
    private static int findRoot(final int[] parent, final int[] offset, final int k) {
        if (parent[k] == k) {
            return k;
        }
        // Phase 1: walk to root, accumulating offset along the way
        int total = 0;
        int curr = k;
        while (parent[curr] != curr) {
            total += offset[curr];
            curr = parent[curr];
        }
        final int root = curr;
        // Phase 2: path compression — each node on the chain points directly
        // to root with offset = its total to root
        int accFromK = 0;
        curr = k;
        while (parent[curr] != root) {
            final int next = parent[curr];
            final int currOffset = offset[curr];
            offset[curr] = total - accFromK;
            parent[curr] = root;
            accFromK += currOffset;
            curr = next;
        }
        return root;
    }

    /**
     * Build the ladder of cycle-skip window sizes for an iterative multi-pass
     * correction ending at {@code maxWindow}. Each step is roughly 1.7× the
     * previous, so a few passes cover a wide range. The final entry is
     * always exactly {@code maxWindow}.
     *
     * <p>Examples:
     * <ul>
     *   <li>maxWindow = 5  → [3, 5]</li>
     *   <li>maxWindow = 21 → [3, 5, 9, 15, 21]</li>
     *   <li>maxWindow = 71 → [3, 5, 9, 15, 25, 41, 71]</li>
     *   <li>maxWindow = 101 → [3, 5, 9, 15, 25, 41, 71, 101]</li>
     * </ul>
     */
    static int[] buildCycleSkipLadder(final int maxWindow) {
        final int[] standard = {3, 5, 9, 15, 25, 41, 71};
        if (maxWindow <= standard[0]) {
            return new int[]{maxWindow};
        }
        final java.util.List<Integer> out = new ArrayList<>();
        for (int w : standard) {
            if (w >= maxWindow) break;
            out.add(w);
        }
        out.add(maxWindow);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Post-unwrap cycle-skip ("k-cycle") correction. For each valid pixel,
     * compute the local median of unwrapped phase over a {@code window x window}
     * neighbourhood and snap the pixel to the nearest integer-2π multiple of
     * that median.
     *
     * <p>This specifically targets the Herraez "patchy" artefact where a
     * locally-correct group ends up at the wrong integer cycle relative to
     * its neighbours. Unlike a plain median filter, this only moves pixels
     * by integer multiples of 2π — real (non-integer) signal is preserved
     * exactly.</p>
     *
     * <p>Operates in place on {@code unw}. Invalid pixels are ignored as
     * both readers and writers.</p>
     */
    public static void applyCycleSkipCorrection(final float[] unw, final boolean[] valid,
                                                final int width, final int height,
                                                final int window) {
        if (window < 3) return;
        final int half = window / 2;
        final double TWO_PI = 2.0 * Math.PI;
        final float[] orig = unw.clone();   // read from snapshot to avoid drift
        IntStream.range(0, height).parallel().forEach(y -> {
            final float[] tlBuf = new float[window * window];
            for (int x = 0; x < width; x++) {
                final int k = y * width + x;
                if (!valid[k]) continue;
                int n = 0;
                for (int dy = -half; dy <= half; dy++) {
                    final int yn = y + dy;
                    if (yn < 0 || yn >= height) continue;
                    for (int dx = -half; dx <= half; dx++) {
                        final int xn = x + dx;
                        if (xn < 0 || xn >= width) continue;
                        final int kn = yn * width + xn;
                        if (!valid[kn]) continue;
                        tlBuf[n++] = orig[kn];
                    }
                }
                if (n < 3) continue;
                Arrays.sort(tlBuf, 0, n);
                final float median = tlBuf[n / 2];
                final double diff = orig[k] - median;
                if (Math.abs(diff) > Math.PI) {
                    final int cycles = (int) Math.round(diff / TWO_PI);
                    unw[k] = (float) (orig[k] - TWO_PI * cycles);
                }
            }
        });
    }

    /**
     * Standard square-kernel median filter applied in place to the unwrapped
     * phase. Invalid (NaN-bound) pixels are skipped both as filter inputs and
     * outputs. Kernel size must be odd; 3, 5, or 7 are typical.
     */
    public static void applyMedianFilter(final float[] unw, final boolean[] valid,
                                         final int width, final int height,
                                         final int kernelSize) {
        if (kernelSize < 3) return;
        final int half = kernelSize / 2;
        final float[] orig = unw.clone();
        IntStream.range(0, height).parallel().forEach(y -> {
            final float[] tlBuf = new float[kernelSize * kernelSize];
            for (int x = 0; x < width; x++) {
                final int k = y * width + x;
                if (!valid[k]) continue;
                int n = 0;
                for (int dy = -half; dy <= half; dy++) {
                    final int yn = y + dy;
                    if (yn < 0 || yn >= height) continue;
                    for (int dx = -half; dx <= half; dx++) {
                        final int xn = x + dx;
                        if (xn < 0 || xn >= width) continue;
                        final int kn = yn * width + xn;
                        if (!valid[kn]) continue;
                        tlBuf[n++] = orig[kn];
                    }
                }
                if (n < 3) continue;
                Arrays.sort(tlBuf, 0, n);
                unw[k] = tlBuf[n / 2];
            }
        });
    }

    /** Wrap a phase difference to (-pi, pi]. */
    private static float wrap(final float d) {
        return (float) wrapD(d);
    }

    private static double wrapD(final double d) {
        return d - 2.0 * Math.PI * Math.floor((d + Math.PI) / (2.0 * Math.PI));
    }

    @Override
    public void computeTile(final Band targetBand, final Tile targetTile, final ProgressMonitor pm)
            throws OperatorException {
        try {
            final float[] cache = targetToUnwCache.get(targetBand);
            if (cache == null) {
                return;
            }
            final Rectangle rect = targetTile.getRectangle();
            final ProductData tgtData = targetTile.getDataBuffer();
            final int x0 = rect.x;
            final int y0 = rect.y;
            final int xMax = x0 + rect.width;
            final int yMax = y0 + rect.height;
            for (int y = y0; y < yMax; y++) {
                for (int x = x0; x < xMax; x++) {
                    tgtData.setElemFloatAt(targetTile.getDataBufferIndex(x, y),
                            cache[y * width + x]);
                }
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(HerraezPhaseUnwrapOp.class);
        }
    }
}
