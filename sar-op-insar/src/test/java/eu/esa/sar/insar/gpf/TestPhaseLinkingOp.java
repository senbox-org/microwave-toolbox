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

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.StackUtils;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Test;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestPhaseLinkingOp {

    private static final OperatorSpi spi = new PhaseLinkingOp.Spi();

    @Test
    public void spi_creates_operator() {
        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        assertNotNull(op);
    }

    @Test
    public void operator_metadata_alias_and_category() {
        final OperatorMetadata md = PhaseLinkingOp.class.getAnnotation(OperatorMetadata.class);
        assertNotNull(md);
        assertEquals("PhaseLinking", md.alias());
        assertEquals("Radar/Interferometric/Phase Linking", md.category());
    }

    /**
     * Regression: target band names must carry _ref / _sec<n> tags so downstream
     * InSAR operators (InterferogramOp, CoherenceOp, MultiMasterInSAROp) can resolve them.
     */
    @Test
    public void output_band_names_carry_ref_and_sec_tags() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        // Disable diagnostic bands for a clean assert; not relevant to naming.
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        // Master (01Jan2025) -> _ref. Two secondaries -> _sec1 / _sec2.
        assertNotNull("reference real band", target.getBand("i_pl_VV_ref_01Jan2025"));
        assertNotNull("reference imag band", target.getBand("q_pl_VV_ref_01Jan2025"));
        assertNotNull("secondary-1 real band", target.getBand("i_pl_VV_sec1_13Jan2025"));
        assertNotNull("secondary-1 imag band", target.getBand("q_pl_VV_sec1_13Jan2025"));
        assertNotNull("secondary-2 real band", target.getBand("i_pl_VV_sec2_25Jan2025"));
        assertNotNull("secondary-2 imag band", target.getBand("q_pl_VV_sec2_25Jan2025"));
    }

    /**
     * Regression: Reference_bands / Secondary_bands metadata must be rewritten to point
     * to the phase-linked band names; otherwise StackUtils helpers return stale names.
     */
    @Test
    public void stack_metadata_points_to_phase_linked_bands() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final String[] refBands = StackUtils.getReferenceBandNames(target);
        assertEquals("two reference bands expected", 2, refBands.length);
        for (String name : refBands) {
            assertTrue("reference band " + name + " must include _ref", name.contains(StackUtils.REF));
            assertNotNull("reference band " + name + " must exist in product", target.getBand(name));
        }

        final String[] secProducts = StackUtils.getSecondaryProductNames(target);
        assertEquals("two secondary products expected", 2, secProducts.length);
        for (String secName : secProducts) {
            final String[] secBands = StackUtils.getSecondaryBandNames(target, secName);
            assertEquals("two bands per secondary expected", 2, secBands.length);
            for (String name : secBands) {
                assertTrue("secondary band " + name + " must include _sec", name.contains(StackUtils.SEC));
                assertNotNull("secondary band " + name + " must exist in product", target.getBand(name));
            }
        }
    }

    /**
     * Regression: the phase-linked product must be a drop-in input for InterferogramOp -
     * i.e. InterferogramOp's polarisation-pairing helper must find at least one pol shared
     * between the new _ref and _sec bands. Pre-fix, PhaseLinking emitted bands like
     * "i_pl_VV_<date>" (no _ref/_sec tags), so this returned [""] - the downstream search
     * then matched zero bands and silently produced an empty interferogram product.
     */
    @Test
    public void phase_linked_output_is_consumable_by_interferogram_pairing() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final String[] sharedPols = InterferogramOp.getPolsSharedByRefSec(target, new String[]{"VV"});
        assertEquals("InterferogramOp must find VV shared between phase-linked _ref and _sec bands",
                1, sharedPols.length);
        assertEquals("VV", sharedPols[0]);
    }

    /**
     * Regression: when a centre pixel has a noData epoch, the operator previously zeroed every
     * epoch at that pixel. The fix passes the original SLC samples through, so valid epochs at
     * the pixel are preserved and downstream interferograms/coherence don't get giant zero
     * holes.
     */
    @Test
    public void invalid_centre_pixel_passes_through_original_samples() throws Exception {
        final String[] dates = {"01Jan2025", "13Jan2025", "25Jan2025"};
        final Product stack = buildCoregisteredComplexStack(dates, "VV");
        final int w = stack.getSceneRasterWidth();
        final int h = stack.getSceneRasterHeight();

        // Force the secondary-1 i/q band to be all-zero at the centre pixel only. The reference
        // and secondary-2 are left as TestUtils' increasing ramp, so they are non-zero everywhere.
        final int px = w / 2, py = h / 2;
        final org.esa.snap.core.datamodel.Band sec1I = stack.getBand("i_VV" + StackUtils.SEC + "1_13Jan2025");
        final org.esa.snap.core.datamodel.Band sec1Q = stack.getBand("q_VV" + StackUtils.SEC + "1_13Jan2025");
        assertNotNull(sec1I);
        assertNotNull(sec1Q);
        final float[] sec1Idata = (float[]) sec1I.getData().getElems();
        final float[] sec1Qdata = (float[]) sec1Q.getData().getElems();
        final int idx = py * w + px;
        sec1Idata[idx] = 0f;
        sec1Qdata[idx] = 0f;

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("outputTempCoherence", false);
        op.setParameter("outputShpCount", false);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final org.esa.snap.core.datamodel.Band tgtRefI =
                target.getBand("i_pl_VV" + StackUtils.REF + "_01Jan2025");
        final org.esa.snap.core.datamodel.Band tgtSec2I =
                target.getBand("i_pl_VV" + StackUtils.SEC + "2_25Jan2025");
        assertNotNull(tgtRefI);
        assertNotNull(tgtSec2I);

        final float[] refIpx = new float[w * h];
        final float[] sec2Ipx = new float[w * h];
        tgtRefI.readPixels(0, 0, w, h, refIpx);
        tgtSec2I.readPixels(0, 0, w, h, sec2Ipx);

        // At the marked centre pixel: secondary-1 was zeroed, so PhaseLinking can't form a clean
        // covariance there. Reference and secondary-2 had valid samples and must come through
        // unchanged (i.e. equal to the input's increasing ramp at that index, which is idx + 1.5f).
        final float expected = idx + 1.5f;
        assertEquals("reference epoch must pass through at noData-affected pixel",
                expected, refIpx[idx], 0f);
        assertEquals("secondary-2 epoch must pass through at noData-affected pixel",
                expected, sec2Ipx[idx], 0f);
    }

    /**
     * End-to-end: on a synthetic distributed-scatterer stack with a known per-epoch phase history,
     * the operator must recover that history (relative to the median reference epoch), report a high
     * temporal coherence, and find plenty of SHPs at an interior pixel.
     *
     * The stack is generated under a homogeneous-amplitude DS model: every pixel has identical unit
     * amplitude (so the SHP amplitude test accepts the whole window) with per-pixel per-epoch phase
     * noise eps ~ N(0, sigma^2) about the true epoch phase: s_k(q) = exp(j (phi_true[k] + eps)).
     * Then E[s_i conj(s_j)] = e^{-sigma^2} * exp(j (phi_true[i] - phi_true[j])) for i != j, i.e. a
     * constant coherence |T_ij| = gamma (sigma = sqrt(-ln gamma)), so the dominant eigenvector of
     * T_hat carries exactly the phase ramp we injected.
     */
    @Test
    public void recovers_known_phase_history_on_synthetic_DS_stack() throws Exception {
        final int w = 28, h = 28;
        final int nEpochs = 15;
        final String[] dates = makeDates(nEpochs, 12);   // 12-day cadence, chronological
        final int refIdx = nEpochs / 2;                  // operator default = median epoch

        // True single-master phase history relative to the median reference (kept inside (-pi, pi)
        // so there is no wrap ambiguity in the assertion).
        final double[] truePhase = new double[nEpochs];
        for (int k = 0; k < nEpochs; k++) {
            truePhase[k] = 0.18 * (k - refIdx);          // gentle linear ramp, max |.| ~ 1.26 rad
        }

        final double gamma = 0.92;
        final Product stack = buildSyntheticDSStack(w, h, dates, "VV", truePhase, gamma, 12345L);

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("windowAzimuth", 11);
        op.setParameter("windowRange", 11);
        op.setParameter("shpTest", "KS");
        op.setParameter("estimator", "EVD");
        op.setParameter("shpMin", 20);
        op.setParameter("tempCohMin", 0.0);              // never mask: we want the estimate at every pixel
        op.setParameter("outputTempCoherence", true);
        op.setParameter("outputShpCount", true);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        // Interior pixel whose 11x11 window lies fully inside the scene (no zero-extended borders).
        final int px = w / 2, py = h / 2;
        final int idx = py * w + px;

        double sumAbsErr = 0.0;
        final double[] estPhasePerEpoch = new double[nEpochs];
        for (int k = 0; k < nEpochs; k++) {
            final float[] iPix = new float[w * h];
            final float[] qPix = new float[w * h];
            outputBand(target, "i", "VV", dates, k).readPixels(0, 0, w, h, iPix);
            outputBand(target, "q", "VV", dates, k).readPixels(0, 0, w, h, qPix);

            final double estPhase = Math.atan2(qPix[idx], iPix[idx]);
            estPhasePerEpoch[k] = estPhase;
            final double expected = wrap(truePhase[k] - truePhase[refIdx]);
            final double err = Math.abs(wrap(estPhase - expected));
            sumAbsErr += err;
            assertEquals("recovered phase at epoch " + k + " (" + dates[k] + ")",
                    0.0, err, 0.35);
        }
        final double meanAbsErr = sumAbsErr / nEpochs;
        assertTrue("mean abs phase error too high: " + meanAbsErr, meanAbsErr < 0.15);

        // Datum-invariance (spec 2.5): the interferometric phase between two NON-reference epochs
        // must equal phi_i - phi_j, independent of which epoch is the zero-phase datum. This is the
        // property that makes discarding the reference's absolute phase safe for downstream.
        final int i1 = 2, j1 = nEpochs - 3;
        final double estRel = wrap(estPhasePerEpoch[i1] - estPhasePerEpoch[j1]);
        final double trueRel = wrap(truePhase[i1] - truePhase[j1]);
        assertEquals("relative phase between non-reference epochs must be datum-independent",
                0.0, wrap(estRel - trueRel), 0.35);

        // Reference epoch must be pinned to exactly zero phase.
        final float[] iRef = new float[w * h];
        final float[] qRef = new float[w * h];
        outputBand(target, "i", "VV", dates, refIdx).readPixels(0, 0, w, h, iRef);
        outputBand(target, "q", "VV", dates, refIdx).readPixels(0, 0, w, h, qRef);
        assertEquals("reference epoch imaginary part must be ~0", 0.0, qRef[idx], 1.0e-4 * Math.abs(iRef[idx]) + 1.0e-6);
        assertTrue("reference epoch real part must be positive (zero phase)", iRef[idx] > 0.0);

        // Temporal coherence band should be high for a gamma=0.92 DS.
        final float[] tc = new float[w * h];
        target.getBand("tempCoh_VV").readPixels(0, 0, w, h, tc);
        assertTrue("temporal coherence too low at DS pixel: " + tc[idx], tc[idx] > 0.8);

        // SHP count should comfortably exceed shpMin (full 11x11 window, homogeneous scene).
        final int[] shp = new int[w * h];
        target.getBand("numSHP_VV").readPixels(0, 0, w, h, shp);
        assertTrue("SHP count below shpMin at interior pixel: " + shp[idx], shp[idx] >= 20);
    }

    /**
     * Integration / "drop-in" benefit: feeding the phase-linked stack to interferogram formation
     * yields HIGHER distributed-scatterer coherence than the raw stack. The interferometric
     * coherence is computed directly (windowed |sum s_a conj(s_b)| / sqrt(sum|s_a|^2 sum|s_b|^2))
     * rather than via InterferogramOp, because that operator needs orbit/baseline metadata a minimal
     * synthetic stack does not carry; band-name/metadata consumability by InterferogramOp is covered
     * by {@link #phase_linked_output_is_consumable_by_interferogram_pairing()}.
     *
     * Model coherence here is gamma = 0.5, so the raw pairwise coherence sits near 0.5 while phase
     * linking (estimating each epoch phase from a 121-pixel window) denoises the per-pixel phase and
     * drives the linked-pair coherence close to 1.
     */
    @Test
    public void phase_linking_raises_ds_interferometric_coherence() throws Exception {
        final int w = 24, h = 24, nEpochs = 12;
        final String[] dates = makeDates(nEpochs, 12);
        final double gamma = 0.5;
        final double[] truePhase = new double[nEpochs];
        for (int k = 0; k < nEpochs; k++) truePhase[k] = 0.12 * (k - nEpochs / 2);
        final Product stack = buildSyntheticDSStack(w, h, dates, "VV", truePhase, gamma, 7L);

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("windowAzimuth", 11);
        op.setParameter("windowRange", 11);
        op.setParameter("shpMin", 20);
        op.setParameter("tempCohMin", 0.0);  // keep every pixel linked
        final Product target = op.getTargetProduct();
        assertNotNull(target);

        // Pair epoch 0 (master, _ref tag) with the median reference epoch.
        final int a = 0, b = nEpochs / 2;
        final float[] rawIa = readBand(stack, "i_VV" + StackUtils.REF + "_" + dates[a], w, h);
        final float[] rawQa = readBand(stack, "q_VV" + StackUtils.REF + "_" + dates[a], w, h);
        final float[] rawIb = readBand(stack, "i_VV" + StackUtils.SEC + b + "_" + dates[b], w, h);
        final float[] rawQb = readBand(stack, "q_VV" + StackUtils.SEC + b + "_" + dates[b], w, h);

        final float[] plIa = new float[w * h], plQa = new float[w * h];
        final float[] plIb = new float[w * h], plQb = new float[w * h];
        outputBand(target, "i", "VV", dates, a).readPixels(0, 0, w, h, plIa);
        outputBand(target, "q", "VV", dates, a).readPixels(0, 0, w, h, plQa);
        outputBand(target, "i", "VV", dates, b).readPixels(0, 0, w, h, plIb);
        outputBand(target, "q", "VV", dates, b).readPixels(0, 0, w, h, plQb);

        // 7x7 window centred well inside the scene (all pixels were phase-linked).
        final int cx = w / 2, cy = h / 2, half = 3;
        final double rawCoh = windowedCoherence(rawIa, rawQa, rawIb, rawQb, w, cx, cy, half);
        final double plCoh = windowedCoherence(plIa, plQa, plIb, plQb, w, cx, cy, half);

        assertTrue("raw coherence should be near the model gamma=0.5: " + rawCoh,
                rawCoh < 0.75);
        assertTrue("phase-linked coherence should be high: " + plCoh, plCoh > 0.85);
        assertTrue("phase linking must raise DS coherence (raw=" + rawCoh + " linked=" + plCoh + ")",
                plCoh - rawCoh > 0.2);
    }

    private static float[] readBand(final Product product, final String name, final int w, final int h)
            throws Exception {
        final org.esa.snap.core.datamodel.Band band = product.getBand(name);
        assertNotNull("source band " + name, band);
        final float[] data = new float[w * h];
        band.readPixels(0, 0, w, h, data);
        return data;
    }

    /** Windowed interferometric coherence between epochs a and b over a (2*half+1)^2 window. */
    private static double windowedCoherence(final float[] iA, final float[] qA,
                                            final float[] iB, final float[] qB,
                                            final int w, final int cx, final int cy, final int half) {
        double sumRe = 0.0, sumIm = 0.0, powA = 0.0, powB = 0.0;
        for (int y = cy - half; y <= cy + half; y++) {
            for (int x = cx - half; x <= cx + half; x++) {
                final int p = y * w + x;
                // s_a * conj(s_b)
                sumRe += iA[p] * iB[p] + qA[p] * qB[p];
                sumIm += qA[p] * iB[p] - iA[p] * qB[p];
                powA += iA[p] * iA[p] + qA[p] * qA[p];
                powB += iB[p] * iB[p] + qB[p] * qB[p];
            }
        }
        return Math.sqrt(sumRe * sumRe + sumIm * sumIm) / Math.sqrt(powA * powB);
    }

    /**
     * Rank-deficiency guard: when the SHP count is below the stack size the N x N sample covariance
     * is rank-deficient (rank &lt;= shpCount &lt; n), hence singular - the operator must NOT phase-link,
     * it must pass the original samples through (tempCoh = NaN). Here a 3x3 window yields ~9 SHPs
     * against a 15-epoch stack (9 &lt; 15), while shpMin is set to 5 so the classic shpMin gate would
     * not trip; only the rank guard does.
     */
    @Test
    public void rank_deficient_covariance_passes_through() throws Exception {
        final int w = 12, h = 12, nEpochs = 15;
        final String[] dates = makeDates(nEpochs, 12);
        final double[] truePhase = new double[nEpochs];
        for (int k = 0; k < nEpochs; k++) truePhase[k] = 0.1 * (k - nEpochs / 2);
        final Product stack = buildSyntheticDSStack(w, h, dates, "VV", truePhase, 0.9, 999L);

        final PhaseLinkingOp op = (PhaseLinkingOp) spi.createOperator();
        op.setSourceProduct(stack);
        op.setParameter("windowAzimuth", 3);
        op.setParameter("windowRange", 3);   // ~9 candidate SHPs < 15 epochs -> rank-deficient
        op.setParameter("shpMin", 5);         // below the SHP count, so only the rank guard can trip
        op.setParameter("outputShpCount", true);

        final Product target = op.getTargetProduct();
        assertNotNull(target);

        final int px = w / 2, py = h / 2, idx = py * w + px;

        // tempCoh must be NaN (pass-through), not a phase-linking value.
        final float[] tc = new float[w * h];
        target.getBand("tempCoh_VV").readPixels(0, 0, w, h, tc);
        assertTrue("rank-deficient pixel must be passed through (tempCoh NaN): " + tc[idx],
                Float.isNaN(tc[idx]));

        // The SHP count is what triggers the guard: >= shpMin (so shpMin did not trip) but < n.
        final int[] shp = new int[w * h];
        target.getBand("numSHP_VV").readPixels(0, 0, w, h, shp);
        assertTrue("SHP count should be >= shpMin(5) but < nEpochs(15): " + shp[idx],
                shp[idx] >= 5 && shp[idx] < nEpochs);

        // Pass-through preserves the original complex sample (here the reference epoch).
        final float[] srcI = new float[w * h];
        final float[] tgtI = new float[w * h];
        stack.getBand("i_VV" + StackUtils.REF + "_" + dates[0]).readPixels(0, 0, w, h, srcI);
        outputBand(target, "i", "VV", dates, 0).readPixels(0, 0, w, h, tgtI);
        assertEquals("rank-deficient pixel must pass the original reference sample through",
                srcI[idx], tgtI[idx], 1.0e-6f);
    }

    /** Resolves a phase-linked output band by chronological epoch index k (dates[0] -> _ref tag). */
    private static org.esa.snap.core.datamodel.Band outputBand(final Product target, final String reim,
                                                               final String pol, final String[] dates,
                                                               final int k) {
        // dates[0] is the stack master (_ref tag); dates[1..] are secondaries _sec1.._secN.
        final String roleTag = (k == 0) ? StackUtils.REF : StackUtils.SEC + k;
        final String name = reim + "_pl_" + pol + roleTag + "_" + dates[k];
        final org.esa.snap.core.datamodel.Band b = target.getBand(name);
        assertNotNull("output band " + name, b);
        return b;
    }

    private static String[] makeDates(final int n, final int stepDays) {
        final DateFormat fmt = new SimpleDateFormat("ddMMMyyyy", Locale.ENGLISH);
        final Calendar cal = Calendar.getInstance(Locale.ENGLISH);
        cal.clear();
        cal.set(2020, Calendar.JANUARY, 5, 12, 0, 0);
        final String[] d = new String[n];
        for (int k = 0; k < n; k++) {
            d[k] = fmt.format(cal.getTime());
            cal.add(Calendar.DAY_OF_MONTH, stepDays);
        }
        return d;
    }

    /**
     * Build a coregistered complex SLC stack of size w x h under the homogeneous-amplitude DS model
     * described on {@link #recovers_known_phase_history_on_synthetic_DS_stack()} (gamma is the
     * constant inter-epoch coherence). dates[0] is the master. Mirrors
     * {@link #buildCoregisteredComplexStack} but at a controllable size and with each i/q band
     * filled by the generative model rather than a ramp.
     */
    private static Product buildSyntheticDSStack(final int w, final int h, final String[] dates, final String pol,
                                                 final double[] truePhase, final double gamma, final long seed)
            throws Exception {
        final int n = dates.length;
        final Product product = TestUtils.createProduct("SLC", w, h);

        final DateFormat parser = new SimpleDateFormat("ddMMMyyyy HH:mm:ss", Locale.ENGLISH);
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        absRoot.setAttributeString(AbstractMetadata.MISSION, "TestMission");
        absRoot.setAttributeDouble("radar_frequency", 5405.0);
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time,
                ProductData.UTC.create(parser.parse(dates[0] + " 12:00:00"), 0));

        // Generate per-epoch i/q data under the DS model and attach it as each band is created.
        // Homogeneous-amplitude phase-noise model: every pixel has identical unit amplitude (so the
        // SHP amplitude test accepts the whole window) and per-pixel per-epoch phase noise eps of
        // std sigma, giving a constant coherence |T_ij| = E[exp(j(eps_i - eps_j))] = e^{-sigma^2} = gamma:
        //   s_k(q) = exp(j (truePhase[k] + eps)),   eps ~ N(0, sigma^2),   sigma = sqrt(-ln gamma).
        final Random rng = new Random(seed);
        final double sigma = Math.sqrt(-Math.log(gamma));
        final float[][] iData = new float[n][w * h];
        final float[][] qData = new float[n][w * h];
        for (int p = 0; p < w * h; p++) {
            for (int k = 0; k < n; k++) {
                final double phase = truePhase[k] + rng.nextGaussian() * sigma;
                iData[k][p] = (float) Math.cos(phase);
                qData[k][p] = (float) Math.sin(phase);
            }
        }

        // Reference (master) i/q bands.
        final String iRefName = "i_" + pol + StackUtils.REF + "_" + dates[0];
        final String qRefName = "q_" + pol + StackUtils.REF + "_" + dates[0];
        addFilledBand(product, iRefName, Unit.REAL, w, h, iData[0]);
        addFilledBand(product, qRefName, Unit.IMAGINARY, w, h, qData[0]);

        MetadataElement secRoot = product.getMetadataRoot().getElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        if (secRoot == null) {
            secRoot = new MetadataElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
            product.getMetadataRoot().addElement(secRoot);
        }
        secRoot.setAttributeString(AbstractMetadata.REFERENCE_BANDS, iRefName + " " + qRefName);

        for (int k = 1; k < n; k++) {
            final String secTag = StackUtils.SEC + k;
            final String iName = "i_" + pol + secTag + "_" + dates[k];
            final String qName = "q_" + pol + secTag + "_" + dates[k];
            addFilledBand(product, iName, Unit.REAL, w, h, iData[k]);
            addFilledBand(product, qName, Unit.IMAGINARY, w, h, qData[k]);

            final MetadataElement secMeta = new MetadataElement("secondary_" + dates[k]);
            secRoot.addElement(secMeta);
            secMeta.setAttributeUTC(AbstractMetadata.first_line_time,
                    ProductData.UTC.create(parser.parse(dates[k] + " 12:00:00"), 0));
            secMeta.setAttributeString(AbstractMetadata.SECONDARY_BANDS, iName + " " + qName);
        }

        return product;
    }

    private static void addFilledBand(final Product product, final String name, final String unit,
                                      final int w, final int h, final float[] data) {
        final org.esa.snap.core.datamodel.Band band =
                new org.esa.snap.core.datamodel.Band(name, ProductData.TYPE_FLOAT32, w, h);
        band.setUnit(unit);
        band.setData(ProductData.createInstance(data));
        product.addBand(band);
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a <= -Math.PI) a += 2.0 * Math.PI;
        return a;
    }

    /** Build a minimal valid coregistered complex SLC stack. dates[0] is the master. */
    private static Product buildCoregisteredComplexStack(final String[] dates, final String pol) throws Exception {
        final int w = 8, h = 8;
        final Product product = TestUtils.createProduct("SLC", w, h);

        final DateFormat parser = new SimpleDateFormat("ddMMMyyyy HH:mm:ss", Locale.ENGLISH);

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        absRoot.setAttributeInt(AbstractMetadata.coregistered_stack, 1);
        absRoot.setAttributeString(AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        // Use a non-S1 mission so the validator does not branch into TOPS-burst checks.
        absRoot.setAttributeString(AbstractMetadata.MISSION, "TestMission");
        // Required by InputProductValidator.isSARProduct().
        absRoot.setAttributeDouble("radar_frequency", 5405.0);
        absRoot.setAttributeUTC(AbstractMetadata.first_line_time,
                ProductData.UTC.create(parser.parse(dates[0] + " 12:00:00"), 0));

        // Reference (master) i/q bands.
        final String refSuffix = "_" + pol + StackUtils.REF + "_" + dates[0];
        final String iRefName = "i" + refSuffix;
        final String qRefName = "q" + refSuffix;
        TestUtils.createBand(product, iRefName, ProductData.TYPE_FLOAT32, Unit.REAL, w, h, true);
        TestUtils.createBand(product, qRefName, ProductData.TYPE_FLOAT32, Unit.IMAGINARY, w, h, true);

        // Secondary_Metadata root with one element per secondary product.
        MetadataElement secRoot = product.getMetadataRoot().getElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
        if (secRoot == null) {
            secRoot = new MetadataElement(AbstractMetadata.SECONDARY_METADATA_ROOT);
            product.getMetadataRoot().addElement(secRoot);
        }
        secRoot.setAttributeString(AbstractMetadata.REFERENCE_BANDS, iRefName + " " + qRefName);

        for (int k = 1; k < dates.length; k++) {
            final String secTag = StackUtils.SEC + k;
            final String iName = "i_" + pol + secTag + "_" + dates[k];
            final String qName = "q_" + pol + secTag + "_" + dates[k];
            TestUtils.createBand(product, iName, ProductData.TYPE_FLOAT32, Unit.REAL, w, h, true);
            TestUtils.createBand(product, qName, ProductData.TYPE_FLOAT32, Unit.IMAGINARY, w, h, true);

            final String secProductName = "secondary_" + dates[k];
            final MetadataElement secMeta = new MetadataElement(secProductName);
            secRoot.addElement(secMeta);
            secMeta.setAttributeUTC(AbstractMetadata.first_line_time,
                    ProductData.UTC.create(parser.parse(dates[k] + " 12:00:00"), 0));
            secMeta.setAttributeString(AbstractMetadata.SECONDARY_BANDS, iName + " " + qName);
        }

        return product;
    }
}
