package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.Sentinel1Utils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Layer 1 unit tests for the GSLC TOPS path: deterministic, no I/O, no DEM, no network.
 * Builds a synthetic {@link Sentinel1Utils.SubSwathInfo} and exercises the static
 * deramp/reramp, range-flatten and burst-selection helpers of {@link GSLCGeocodingOp}.
 * <p>
 * The Task 1.3 Layer-2 TOPS leg contract tests (file-gated on a real S1 IW3 2-burst fixture) live
 * in {@link GSLCTopsInSarLongTest} — split into their own {@code @RunWith(LongTestRunner.class)}
 * class rather than mixed in here, because that runner gates at the class level only and these
 * fast, always-run unit tests must not be disabled by {@code -Denable.long.tests}.
 */
public class GSLCTopsInSarTest {

    /** Build a deterministic single-subswath SubSwathInfo array for the helper tests. */
    private static Sentinel1Utils.SubSwathInfo[] synthSubSwath() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.linesPerBurst = 10;
        ss.numOfSamples = 5;
        ss.azimuthTimeInterval = 0.002;
        ss.dopplerRate = new double[2][5];
        ss.referenceTime = new double[2][5];
        ss.dopplerCentroid = new double[2][5];
        for (int b = 0; b < 2; b++) {
            for (int x = 0; x < 5; x++) {
                ss.dopplerRate[b][x] = 2000.0 + 10.0 * x + 3.0 * b;
                ss.referenceTime[b][x] = 0.01 + 0.001 * x + 0.0005 * b;
                ss.dopplerCentroid[b][x] = -50.0 + 7.0 * x - 4.0 * b;
            }
        }
        return new Sentinel1Utils.SubSwathInfo[]{ss};
    }

    /** Closed-form reference for the analytic reramp phase (mirrors S1 IPF DerampDemod). */
    private static double expectedPhase(Sentinel1Utils.SubSwathInfo ss, int burst,
                                        double xFrac, double yFrac) {
        final int firstLine = burst * ss.linesPerBurst;
        final double ta = (yFrac - firstLine) * ss.azimuthTimeInterval;
        final int x0 = (int) Math.floor(xFrac);
        final int x0c = Math.max(0, Math.min(ss.numOfSamples - 1, x0));
        final int x1c = Math.max(0, Math.min(ss.numOfSamples - 1, x0 + 1));
        final double fx = xFrac - x0;
        final double kt = ss.dopplerRate[burst][x0c] + fx * (ss.dopplerRate[burst][x1c] - ss.dopplerRate[burst][x0c]);
        final double tr = ss.referenceTime[burst][x0c] + fx * (ss.referenceTime[burst][x1c] - ss.referenceTime[burst][x0c]);
        final double fdc = ss.dopplerCentroid[burst][x0c] + fx * (ss.dopplerCentroid[burst][x1c] - ss.dopplerCentroid[burst][x0c]);
        final double dt = ta - tr;
        return -Math.PI * kt * dt * dt - 2.0 * Math.PI * fdc * ta;
    }

    @Test
    public void testComputeDerampDemodPhaseAt_MatchesClosedFormAtIntegerAndFractional() {
        final Sentinel1Utils.SubSwathInfo[] subSwath = synthSubSwath();
        final Sentinel1Utils.SubSwathInfo ss = subSwath[0];

        // Integer position in burst 1.
        assertEquals(expectedPhase(ss, 1, 2.0, 13.0),
                GSLCGeocodingOp.computeDerampDemodPhaseAt(subSwath, 1, 1, 2.0, 13.0), 1e-9);

        // Fractional range position (linear interpolation of kt/tr/fdc).
        assertEquals(expectedPhase(ss, 1, 2.4, 13.7),
                GSLCGeocodingOp.computeDerampDemodPhaseAt(subSwath, 1, 1, 2.4, 13.7), 1e-9);

        // Burst 0, near far-range edge (clamp branch x0+1 == numOfSamples).
        assertEquals(expectedPhase(ss, 0, 4.0, 3.0),
                GSLCGeocodingOp.computeDerampDemodPhaseAt(subSwath, 1, 0, 4.0, 3.0), 1e-9);
    }

    /**
     * Post-resample range-carrier flattening convention: with the (physically
     * baseband) i/q interpolated raw, the TOPS and SM paths flatten AFTER the
     * kernel by multiplying with exp(+j * 4 pi R_target / lambda); the inverse
     * exp(-j*phase) restores the natural SLC convention. This replaces the former
     * pre-resample "preFlattenRangeCarrier" helper, which injected an aliased
     * per-column carrier ahead of the sinc kernel (see
     * GSLCComplexResamplingFidelityTest for the fidelity contract).
     */
    @Test
    public void testPostResampleRangeFlatten_RoundTripsWithRestore() {
        final double origI = 0.7, origQ = -0.3;
        final double slantRange = 8.0e5 + 3 * 2.33, wavelength = 0.0555;
        final double phase = 4.0 * Math.PI * slantRange / wavelength;
        final double cosPhi = Math.cos(phase);
        final double sinPhi = Math.sin(phase);

        final double[] flat = new double[2];
        GSLCGeocodingOp.multiplyByExpJPhi(origI, origQ, cosPhi, sinPhi, flat);

        final double[] out = new double[2];
        GSLCGeocodingOp.multiplyByExpMinusJPhi(flat[0], flat[1], cosPhi, sinPhi, out);

        assertEquals(origI, out[0], 1e-9);
        assertEquals(origQ, out[1], 1e-9);
    }

    @Test
    public void testSelectBurst_MidpointRuleAndOutOfRange() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.burstFirstValidLineTime = new double[]{100.0, 110.0};
        ss.burstLastValidLineTime  = new double[]{112.0, 122.0};
        // overlap region is [110, 112]; midTime = (112 + 110)/2 = 111.

        assertEquals("inside burst 0 only", 0, GSLCGeocodingOp.selectBurst(105.0, ss));
        assertEquals("inside burst 1 only", 1, GSLCGeocodingOp.selectBurst(120.0, ss));
        assertEquals("overlap, below midpoint", 0, GSLCGeocodingOp.selectBurst(110.5, ss));
        assertEquals("overlap, at/above midpoint", 1, GSLCGeocodingOp.selectBurst(111.5, ss));
        assertEquals("before all bursts", -1, GSLCGeocodingOp.selectBurst(50.0, ss));
        assertEquals("after all bursts", -1, GSLCGeocodingOp.selectBurst(200.0, ss));
    }

    @Test
    public void testSelectBurst_LockedBoundaryOverridesMidpointOnlyInsideOverlap() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.burstFirstValidLineTime = new double[]{100.0, 110.0};
        ss.burstLastValidLineTime  = new double[]{112.0, 122.0};
        // overlap region is [110, 112]; own midpoint boundary would be 111.

        // Locked boundary above the midpoint: the overlap split moves with it.
        final double[] lock = {111.8};
        assertEquals("overlap, below locked boundary", 0,
                GSLCGeocodingOp.selectBurst(111.5, ss, lock));
        assertEquals("overlap, above locked boundary", 1,
                GSLCGeocodingOp.selectBurst(111.9, ss, lock));

        // Outside the overlap, containment decides regardless of the lock.
        assertEquals("inside burst 0 only, locked", 0, GSLCGeocodingOp.selectBurst(105.0, ss, lock));
        assertEquals("inside burst 1 only, locked", 1, GSLCGeocodingOp.selectBurst(120.0, ss, lock));

        // A lock transported OUTSIDE the physical overlap cannot select an invalid burst:
        // t=112.5 is contained only in burst 1, so burst 1 wins although t < 114.
        final double[] farLock = {114.0};
        assertEquals("whole overlap goes to burst 0", 0,
                GSLCGeocodingOp.selectBurst(111.9, ss, farLock));
        assertEquals("containment wins outside the overlap", 1,
                GSLCGeocodingOp.selectBurst(112.5, ss, farLock));

        // Null lock delegates to the midpoint rule.
        assertEquals("null lock, midpoint rule", 1, GSLCGeocodingOp.selectBurst(111.5, ss, null));
    }

    @Test
    public void testComputeLockedOverlapMidpoints_TransportsReferenceBoundary() {
        // Reference: bursts sensing-start at 1000/1010 s, valid windows trimmed 0.4 s at each end.
        final String refTable = "1000.0:1000.4:1011.6,1010.0:1010.4:1021.6";
        // Its own midpoint boundary: (1011.6 + 1010.4) / 2 = 1011.0.

        // This acquisition: one day + 0.25 s later (true time-axis offset 86400.25), but the
        // processor trimmed MORE at burst start (0.8 s) — so its own midpoint disagrees with
        // the reference's in ground terms.
        final double[] fl = {87400.25, 87410.25};
        final double[] fv = {87401.05, 87411.05};
        final double[] lv = {87411.85, 87421.85};
        // Own midpoint would be (87411.85 + 87411.05) / 2 = 87411.45.

        final double[] mid = GSLCGeocodingOp.computeLockedOverlapMidpoints(refTable, null, fl, fv, lv);
        assertEquals(1, mid.length);
        // Transported boundary = m_ref + Δ = 1011.0 + 86400.25 — 0.20 s away from the own
        // midpoint, immune to the valid-line trimming difference.
        assertEquals(87411.25, mid[0], 1e-9);
    }

    @Test
    public void testComputeLockedOverlapMidpoints_MatchesByBurstIdAcrossDifferentSplits() {
        // Reference split frames 3 ground bursts 501/502/503 (the S1A-vs-S1C 10-vs-9 case in
        // miniature: index alignment is ambiguous, the track-anchored IDs are not).
        final String refTable = "501:1000.0:1000.4:1011.6,502:1010.0:1010.4:1021.6,"
                + "503:1020.0:1020.4:1031.6";

        // This acquisition frames only 502/503, one day + 0.25 s later.
        final long[] ids = {502, 503};
        final double[] fl = {87410.25, 87420.25};
        final double[] fv = {87410.65, 87420.65};
        final double[] lv = {87421.85, 87431.85};

        final double[] mid = GSLCGeocodingOp.computeLockedOverlapMidpoints(refTable, ids, fl, fv, lv);
        assertEquals(1, mid.length);
        // Seam (502,503) locks to the reference's seam j=1: m_ref = (1021.6 + 1020.4)/2 = 1021.0,
        // Δ = 86400.25 — index alignment (j=0) would have given 1011.0 + Δ, one burst off.
        assertEquals(1021.0 + 86400.25, mid[0], 1e-9);

        // A seam whose burst pair is NOT in the reference window: the matched burst (503) is
        // preferred through its whole valid extent — the reference has no seam there, so
        // switching to the unmatched burst (504) any earlier would pair adjacent bursts
        // (disjoint Doppler) against the reference's continuing burst. Seams in the common
        // window still lock.
        final long[] ids2 = {502, 503, 504};
        final double[] fl2 = {87410.25, 87420.25, 87430.25};
        final double[] fv2 = {87410.65, 87420.65, 87430.65};
        final double[] lv2 = {87421.85, 87431.85, 87441.85};
        final double[] mid2 = GSLCGeocodingOp.computeLockedOverlapMidpoints(refTable, ids2, fl2, fv2, lv2);
        assertEquals(2, mid2.length);
        assertEquals("seam (502,503) locks", 1021.0 + 86400.25, mid2[0], 1e-9);
        assertEquals("edge seam (503,504): matched burst preferred to its full valid extent",
                lv2[1], mid2[1], 0.0);

        // Disjoint ID sets: nothing locks => null (all-midpoint fallback), not a crash.
        assertEquals(null, GSLCGeocodingOp.computeLockedOverlapMidpoints(
                refTable, new long[]{900, 901}, fl, fv, lv));
    }

    @Test
    public void testComputeLockedOverlapMidpoints_SelfLockIsIdentity() {
        // Locking a product to its own stamp must reproduce its own midpoint rule exactly —
        // with burst IDs (4-field rows) and without (3-field rows).
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 3;
        ss.burstFirstLineTime      = new double[]{1000.0, 1010.0, 1020.0};
        ss.burstFirstValidLineTime = new double[]{1000.4, 1010.5, 1020.6};
        ss.burstLastValidLineTime  = new double[]{1011.6, 1021.5, 1031.4};
        final long[] ids = {225584, 225585, 225586};

        for (final long[] stampIds : new long[][]{ids, null}) {
            final String table = GSLCGeocodingOp.formatBurstValidTimes(ss, stampIds);
            final double[] mid = GSLCGeocodingOp.computeLockedOverlapMidpoints(table, stampIds,
                    ss.burstFirstLineTime, ss.burstFirstValidLineTime, ss.burstLastValidLineTime);
            assertEquals(2, mid.length);
            assertEquals((1011.6 + 1010.5) / 2.0, mid[0], 0.0);
            assertEquals((1021.5 + 1020.6) / 2.0, mid[1], 0.0);
        }
    }

    @Test
    public void testComputeLockedOverlapMidpoints_RejectsUnusableTables() {
        final double[] fl = {100.0, 110.0};
        final double[] fv = {100.4, 110.4};
        final double[] lv = {111.6, 121.6};

        assertEquals("burst-count mismatch without IDs => no lock", null,
                GSLCGeocodingOp.computeLockedOverlapMidpoints("1:2:3", null, fl, fv, lv));
        assertEquals("two-field rows => no lock", null,
                GSLCGeocodingOp.computeLockedOverlapMidpoints(
                        "100.0:111.6,110.0:121.6", null, fl, fv, lv));
        assertEquals("garbage => no lock", null,
                GSLCGeocodingOp.computeLockedOverlapMidpoints(
                        "not:a:number,also:not:one", null, fl, fv, lv));
        assertEquals("blank => no lock", null,
                GSLCGeocodingOp.computeLockedOverlapMidpoints("  ", null, fl, fv, lv));
        assertEquals("null => no lock", null,
                GSLCGeocodingOp.computeLockedOverlapMidpoints(null, null, fl, fv, lv));

        // Single-burst product: nothing to lock, but not an error either.
        assertEquals(0, GSLCGeocodingOp.computeLockedOverlapMidpoints("1000.0:1000.4:1011.6", null,
                new double[]{2000.0}, new double[]{2000.4}, new double[]{2011.6}).length);
    }

    // Shared fixture for the burst-lock edge-seam/masking tests: reference frames ground bursts
    // 501/502/503 (burst cycle 10 s, valid overlap [fv[k+1], lv[k]] = 1.2 s), this acquisition
    // one day + 0.25 s later.
    private static final String REF_TABLE_501_503 =
            "501:1000.0:1000.4:1011.6,502:1010.0:1010.4:1021.6,503:1020.0:1020.4:1031.6";
    private static final double DELTA = 86400.25;

    @Test
    public void testComputeBurstLock_PushesEdgeSeamToMatchedBurstExtent() {
        // This acquisition has an EXTRA burst at the END (504, not in the reference): seam
        // (503,504) must resolve to the matched burst 503 through its whole valid extent.
        final long[] ids = {502, 503, 504};
        final double[] fl = {87410.25, 87420.25, 87430.25};
        final double[] fv = {87410.65, 87420.65, 87430.65};
        final double[] lv = {87421.85, 87431.85, 87441.85};
        final GSLCGeocodingOp.BurstLock lock =
                GSLCGeocodingOp.computeBurstLock(REF_TABLE_501_503, ids, fl, fv, lv);
        assertEquals(2, lock.overlapMid.length);
        assertEquals("interior seam locks normally", 1021.0 + DELTA, lock.overlapMid[0], 1e-9);
        assertEquals("edge seam pushed to matched burst's lastValid", lv[1], lock.overlapMid[1], 0.0);
        assertEquals(1, lock.matchedSeams);
        assertEquals(1, lock.pushedSeams);

        // Mirrored: EXTRA burst at the START (500): seam (500,501) must resolve to the matched
        // burst 501 from the start of its validity.
        final long[] idsS = {500, 501, 502};
        final double[] flS = {87390.25, 87400.25, 87410.25};
        final double[] fvS = {87390.65, 87400.65, 87410.65};
        final double[] lvS = {87401.85, 87411.85, 87421.85};
        final GSLCGeocodingOp.BurstLock lockS =
                GSLCGeocodingOp.computeBurstLock(REF_TABLE_501_503, idsS, flS, fvS, lvS);
        assertEquals("edge seam pushed to matched burst's firstValid", fvS[1], lockS.overlapMid[0], 0.0);
        assertEquals("interior seam locks normally", 1011.0 + DELTA, lockS.overlapMid[1], 1e-9);
    }

    @Test
    public void testComputeBurstLock_FlagsBurstsAbsentFromReferenceUnpairable() {
        final long[] ids = {502, 503, 504};
        final double[] fl = {87410.25, 87420.25, 87430.25};
        final double[] fv = {87410.65, 87420.65, 87430.65};
        final double[] lv = {87421.85, 87431.85, 87441.85};
        final GSLCGeocodingOp.BurstLock lock =
                GSLCGeocodingOp.computeBurstLock(REF_TABLE_501_503, ids, fl, fv, lv);
        assertTrue("502 is in the reference", lock.pairable[0]);
        assertTrue("503 is in the reference", lock.pairable[1]);
        assertFalse("504 is NOT in the reference", lock.pairable[2]);
    }

    @Test
    public void testComputeBurstLock_TransportsReferencePairableWindow() {
        // This acquisition frames 502/503/504; the reference additionally has 501 BEFORE the
        // common window. The reference switched from 501 to 502 at ITS midpoint rule — south of
        // that (transported) boundary the reference's data comes from a burst this product lacks,
        // so pairing is impossible: window start = (lvRef[0]+fvRef[1])/2 + Δ. The reference table
        // ends at 503, so the window ends at ref's lastValid[503] + Δ.
        final long[] ids = {502, 503, 504};
        final double[] fl = {87410.25, 87420.25, 87430.25};
        final double[] fv = {87410.65, 87420.65, 87430.65};
        final double[] lv = {87421.85, 87431.85, 87441.85};
        final GSLCGeocodingOp.BurstLock lock =
                GSLCGeocodingOp.computeBurstLock(REF_TABLE_501_503, ids, fl, fv, lv);
        assertEquals("window start = ref's own 501/502 switch, transported",
                1011.0 + DELTA, lock.refStartSod, 1e-9);
        assertEquals("window end = ref coverage end, transported",
                1031.6 + DELTA, lock.refEndSod, 1e-9);

        // Mirrored (the reference has the EXTRA burst at the END — the 2026-08-11 near-range
        // band): this acquisition frames only 501/502; the reference switches to its burst 503
        // at its own midpoint — beyond that, this product must not emit pairable-looking data.
        final long[] idsM = {501, 502};
        final double[] flM = {87400.25, 87410.25};
        final double[] fvM = {87400.65, 87410.65};
        final double[] lvM = {87411.85, 87421.85};
        final GSLCGeocodingOp.BurstLock lockM =
                GSLCGeocodingOp.computeBurstLock(REF_TABLE_501_503, idsM, flM, fvM, lvM);
        assertEquals("window start = ref coverage start, transported",
                1000.4 + DELTA, lockM.refStartSod, 1e-9);
        assertEquals("window end = ref's own 502/503 switch, transported",
                1021.0 + DELTA, lockM.refEndSod, 1e-9);
        assertTrue(lockM.pairable[0]);
        assertTrue(lockM.pairable[1]);
    }

    @Test
    public void testIsPairableWithReference() {
        final long[] ids = {502, 503, 504};
        final double[] fl = {87410.25, 87420.25, 87430.25};
        final double[] fv = {87410.65, 87420.65, 87430.65};
        final double[] lv = {87421.85, 87431.85, 87441.85};
        final GSLCGeocodingOp.BurstLock lock =
                GSLCGeocodingOp.computeBurstLock(REF_TABLE_501_503, ids, fl, fv, lv);

        assertFalse("unmatched burst is never pairable",
                GSLCGeocodingOp.isPairableWithReference(lock, 2, 87435.0));
        assertTrue("matched burst inside the window",
                GSLCGeocodingOp.isPairableWithReference(lock, 1, 87425.0));
        assertFalse("beyond the reference's coverage end",
                GSLCGeocodingOp.isPairableWithReference(lock, 1, 1031.6 + DELTA + 0.1));
        assertFalse("before the reference's 501/502 switch",
                GSLCGeocodingOp.isPairableWithReference(lock, 0, 1011.0 + DELTA - 0.1));
        assertTrue("no lock means no masking",
                GSLCGeocodingOp.isPairableWithReference(null, 0, 50.0));
    }

    @Test
    public void testFormatBurstValidTimes_StampsEffectivePartitionWhenLocked() {
        // A lock-built GSLC's output uses LOCKED/PUSHED boundaries, not its own midpoints. The
        // stamp must represent that effective partition, or a future stack that uses this product
        // as its reference reconstructs the wrong midpoints and reintroduces mixed-burst strips
        // (up to half the overlap at pushed seams).
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 3;
        ss.burstFirstLineTime      = new double[]{1000.0, 1010.0, 1020.0};
        ss.burstFirstValidLineTime = new double[]{1000.4, 1010.4, 1020.4};
        ss.burstLastValidLineTime  = new double[]{1011.6, 1021.6, 1031.6};
        final long[] ids = {501, 502, 503};
        // seam 0: locked boundary shifted +0.3 s off the own midpoint (own = 1011.0 -> 1011.3);
        // seam 1: pushed to the matched burst's full extent (own = 1021.0 -> lv[1] = 1021.6);
        // pairable window trimmed at both ends (masked output shrinks the usable span).
        final GSLCGeocodingOp.BurstLock lock = new GSLCGeocodingOp.BurstLock(
                new double[]{1011.3, 1021.6},
                new boolean[]{true, true, true},
                1001.0, 1030.5, 1, 1, 0.3);

        final String stamped = GSLCGeocodingOp.formatBurstValidTimes(ss, ids, lock);

        // A future secondary consuming this stamp with delta = 0 must land on the EFFECTIVE
        // boundaries, and the transported pairable window must match the masked extent.
        final GSLCGeocodingOp.BurstLock consumed = GSLCGeocodingOp.computeBurstLock(stamped, ids,
                ss.burstFirstLineTime, ss.burstFirstValidLineTime, ss.burstLastValidLineTime);
        assertEquals("locked boundary survives the stamp round-trip", 1011.3, consumed.overlapMid[0], 1e-9);
        assertEquals("pushed boundary survives the stamp round-trip", 1021.6, consumed.overlapMid[1], 1e-9);
        assertEquals("window start reflects the masked extent", 1001.0, consumed.refStartSod, 1e-9);
        assertEquals("window end reflects the masked extent", 1030.5, consumed.refEndSod, 1e-9);

        // No lock -> stamp is the plain table (unchanged legacy behavior).
        assertEquals(GSLCGeocodingOp.formatBurstValidTimes(ss, ids),
                GSLCGeocodingOp.formatBurstValidTimes(ss, ids, null));

        // NaN seam (unmatched, midpoint fallback at runtime) -> stamps the OWN midpoint;
        // out-of-overlap locked boundary -> containment-clamped to the overlap edge, exactly
        // as selectBurst resolves it at runtime.
        final GSLCGeocodingOp.BurstLock lock2 = new GSLCGeocodingOp.BurstLock(
                new double[]{Double.NaN, 1050.0},   // seam 1's lock lies far beyond lv[1]=1021.6
                new boolean[]{true, true, true},
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1, 0, 0.0);
        final GSLCGeocodingOp.BurstLock consumed2 = GSLCGeocodingOp.computeBurstLock(
                GSLCGeocodingOp.formatBurstValidTimes(ss, ids, lock2), ids,
                ss.burstFirstLineTime, ss.burstFirstValidLineTime, ss.burstLastValidLineTime);
        assertEquals("NaN seam stamps the own midpoint", (1011.6 + 1010.4) / 2.0,
                consumed2.overlapMid[0], 1e-9);
        assertEquals("out-of-overlap lock stamps the containment-clamped boundary", 1021.6,
                consumed2.overlapMid[1], 1e-9);
        assertEquals("infinite window bounds leave the span untouched", 1000.4,
                consumed2.refStartSod, 1e-9);
        assertEquals("infinite window bounds leave the span untouched", 1031.6,
                consumed2.refEndSod, 1e-9);
    }

    @Test
    public void testComputeBurstLock_SelfLockWindowIsOwnValidSpan() {
        // Locking a product to its own stamp: the pairable window must equal its own valid span,
        // so masking never removes a pixel containment would have kept.
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 3;
        ss.burstFirstLineTime      = new double[]{1000.0, 1010.0, 1020.0};
        ss.burstFirstValidLineTime = new double[]{1000.4, 1010.5, 1020.6};
        ss.burstLastValidLineTime  = new double[]{1011.6, 1021.5, 1031.4};
        final long[] ids = {225584, 225585, 225586};
        final String table = GSLCGeocodingOp.formatBurstValidTimes(ss, ids);
        final GSLCGeocodingOp.BurstLock lock = GSLCGeocodingOp.computeBurstLock(table, ids,
                ss.burstFirstLineTime, ss.burstFirstValidLineTime, ss.burstLastValidLineTime);
        assertEquals(1000.4, lock.refStartSod, 0.0);
        assertEquals(1031.4, lock.refEndSod, 0.0);
        assertEquals(2, lock.matchedSeams);
        assertEquals(0, lock.pushedSeams);
        for (int k = 0; k < 3; k++) {
            assertTrue(lock.pairable[k]);
        }
    }

    @Test
    public void testIsValidBurstSample_Boundaries() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 1;
        ss.linesPerBurst = 10;
        ss.firstValidLine = new int[]{2};
        ss.lastValidLine = new int[]{7};
        ss.firstValidSample = new int[1][10];
        ss.lastValidSample = new int[1][10];
        for (int line = 0; line < 10; line++) {
            ss.firstValidSample[0][line] = -1;
            ss.lastValidSample[0][line] = -1;
        }
        ss.firstValidSample[0][5] = 3;
        ss.lastValidSample[0][5] = 20;

        // burst 0, line 5, sample within [3,20]
        assertTrue(GSLCGeocodingOp.isValidBurstSample(0, 5.0, 10.0, ss));
        // sample below firstValidSample
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 5.0, 2.0, ss));
        // sample above lastValidSample
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 5.0, 25.0, ss));
        // line below firstValidLine
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 1.0, 10.0, ss));
        // line whose valid-sample range is -1 (line 6 is within [2,7] but unset)
        assertTrue(!GSLCGeocodingOp.isValidBurstSample(0, 6.0, 10.0, ss));
    }

    @Test
    public void testApplyAzimuthOffsetToBurstTimes_ShiftsAllBurstTimes() {
        final Sentinel1Utils.SubSwathInfo ss = new Sentinel1Utils.SubSwathInfo();
        ss.numOfBursts = 2;
        ss.azimuthTimeInterval = 0.002;
        ss.burstFirstLineTime      = new double[]{100.0, 110.0};
        ss.burstFirstValidLineTime = new double[]{100.5, 110.5};
        ss.burstLastValidLineTime  = new double[]{108.0, 118.0};

        // +5 px azimuth offset => shift each burst time by -5*azimuthTimeInterval seconds
        // (so a ground point's geometric row becomes originalRow + 5).
        GSLCGeocodingOp.applyAzimuthOffsetToBurstTimes(ss, 5.0);
        final double d = 5.0 * 0.002; // 0.01 s

        assertEquals(100.0 - d, ss.burstFirstLineTime[0], 1e-12);
        assertEquals(110.0 - d, ss.burstFirstLineTime[1], 1e-12);
        assertEquals(100.5 - d, ss.burstFirstValidLineTime[0], 1e-12);
        assertEquals(108.0 - d, ss.burstLastValidLineTime[0], 1e-12);
        assertEquals(118.0 - d, ss.burstLastValidLineTime[1], 1e-12);
    }
}
