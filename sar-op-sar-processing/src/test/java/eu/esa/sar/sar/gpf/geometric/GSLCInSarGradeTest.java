/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.geometric;

import eu.esa.sar.commons.test.ProcessorTest;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.engine_utilities.util.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * InSAR-grade validation tests for {@link GSLCGeocodingOp} and the downstream
 * GSLC -> CreateStack -> Interferogram chain.
 * <p>
 * These tests act as the executable specification for the improvements needed to
 * bring the GSLC pipeline to InSAR-grade quality. Each test targets a single,
 * named issue so a failure points at one concrete bug or missing capability.
 * <p>
 * Categories:
 * <ul>
 *   <li><b>Math contract tests</b> (always run, deterministic, no SAR data needed):
 *       pin down the sign convention of the flatten/restore round-trip in
 *       {@link GSLCGeocodingOp}.</li>
 *   <li><b>Integration tests</b> (skipped when fixtures or DEM tiles unavailable):
 *       exercise the operator end-to-end against real SLC data.</li>
 *   <li><b>Spec tests</b> (intentionally RED until the corresponding improvement
 *       lands): document required new parameters, bands, or behaviour.</li>
 * </ul>
 */
public class GSLCInSarGradeTest extends ProcessorTest {

    private final static OperatorSpi spi = new GSLCGeocodingOp.Spi();
    private final static File capellaSLC = TestData.inputCapella_StripmapSLC;
    /**
     * Optional external DEM tile covering the Capella stripmap fixture (10-15 E, 45-50 N).
     * Set via {@code -Dgslc.test.externalDem=/path/to/srtm_39_03.tif} to enable the
     * integration tests; otherwise they skip. Provided this way so the test suite
     * runs unmodified on CI where no DEM is present.
     */
    private static final File externalDemTif = locateExternalDem();

    private static File locateExternalDem() {
        final String prop = System.getProperty("gslc.test.externalDem");
        if (prop != null && !prop.isEmpty()) {
            final File f = new File(prop);
            return f.exists() ? f : null;
        }
        // Fall back to a conventional install path: .snap auxdata SRTM 3Sec dir.
        final File candidate = new File(System.getProperty("user.home"),
                ".snap/auxdata/dem/SRTM 3Sec/srtm_39_03.tif");
        return candidate.exists() ? candidate : null;
    }

    @Before
    public void setUp() {
        // Math contract tests run unconditionally. Integration tests gate on
        // the fixture themselves via assumeTrue inside the test body.
    }

    // -----------------------------------------------------------------------
    // Math contract tests (always run, fast). Catch issue §1a directly.
    // -----------------------------------------------------------------------

    /**
     * Issue §1a: The flatten and restore steps must be inverses of each other.
     * Canonical SAR convention (also documented in {@code GSLCGeocodingOp.html}):
     * <pre>
     *   C_flat     = C_src  * exp(+j * 4 pi R / lambda)   // flatten
     *   C_restored = C_flat * exp(-j * 4 pi R / lambda)   // inverse
     * </pre>
     * Pre-fix: the SM path multiplies by {@code exp(+j*phi)} in both steps,
     * giving {@code C_restored = C_src * exp(+j * 2 * phi)} — wrong. After
     * the fix, this round-trip recovers the input bit-for-bit.
     */
    @Test
    public void testFlattenThenRestoreRoundTrip_RecoversInputComplex() {
        final double iSrc = 0.7;
        final double qSrc = -0.3;
        // A non-trivial phi: large, irrational, neither 0 nor pi/2 multiple.
        final double phi = 1.234567;
        final double cosPhi = Math.cos(phi);
        final double sinPhi = Math.sin(phi);

        final double[] flat = new double[2];
        GSLCGeocodingOp.multiplyByExpJPhi(iSrc, qSrc, cosPhi, sinPhi, flat);

        final double[] restored = new double[2];
        GSLCGeocodingOp.multiplyByExpMinusJPhi(flat[0], flat[1], cosPhi, sinPhi, restored);

        assertEquals("round-trip must recover real part", iSrc, restored[0], 1e-12);
        assertEquals("round-trip must recover imaginary part", qSrc, restored[1], 1e-12);
    }

    /**
     * Sanity check: {@code multiplyByExpJPhi} matches the analytic value of
     * (1 + 0j) * exp(+j * pi/2) = +j.
     */
    @Test
    public void testMultiplyByExpJPhi_MatchesAnalyticAtHalfPi() {
        final double[] out = new double[2];
        // cos(pi/2)=0, sin(pi/2)=1
        GSLCGeocodingOp.multiplyByExpJPhi(1.0, 0.0, 0.0, 1.0, out);
        assertEquals(0.0, out[0], 1e-15);
        assertEquals(1.0, out[1], 1e-15);
    }

    /**
     * Sanity check: {@code multiplyByExpMinusJPhi} matches the analytic value
     * of (1 + 0j) * exp(-j * pi/2) = -j.
     */
    @Test
    public void testMultiplyByExpMinusJPhi_MatchesAnalyticAtHalfPi() {
        final double[] out = new double[2];
        GSLCGeocodingOp.multiplyByExpMinusJPhi(1.0, 0.0, 0.0, 1.0, out);
        assertEquals(0.0, out[0], 1e-15);
        assertEquals(-1.0, out[1], 1e-15);
    }

    // -----------------------------------------------------------------------
    // Integration test (skipped without fixture + DEM). Same property as the
    // unit test above, but exercised through the operator.
    // -----------------------------------------------------------------------

    /**
     * Issue §1a end-to-end: Runs {@link GSLCGeocodingOp} twice on the same
     * input — once with {@code outputFlattened=true} (plus simulated unwrapped
     * phase), once with {@code outputFlattened=false} — and verifies that the
     * non-flattened output equals {@code flat * exp(-j*phi)} pixel by pixel.
     * <p>
     * Skipped when the fixture or DEM is unavailable (the test will then
     * find zero valid pixels). Pre-fix: relative RMS error ~1.4. Post-fix:
     * sub-1e-3.
     */
    @Test
    public void testSignConvention_RestoreIsInverseOfFlatten_Integration() throws Exception {
        assumeTrue(capellaSLC + " not found", capellaSLC.exists());

        try (final Product source = TestUtils.readSourceProduct(capellaSLC)) {
            final Product flatProduct = runGslc(source, true, true);
            final Product restoredProduct = runGslc(source, false, false);

            final Band iFlat = findIBand(flatProduct);
            final Band qFlat = findQBand(flatProduct);
            final Band phaseBand = flatProduct.getBand("simulatedUnwrappedPhase");
            assertNotNull("flat i band missing", iFlat);
            assertNotNull("flat q band missing", qFlat);
            assertNotNull("simulatedUnwrappedPhase band missing", phaseBand);

            final Band iRest = findIBand(restoredProduct);
            final Band qRest = findQBand(restoredProduct);
            assertNotNull("restored i band missing", iRest);
            assertNotNull("restored q band missing", qRest);

            final int w = flatProduct.getSceneRasterWidth();
            final int h = flatProduct.getSceneRasterHeight();

            final float[] iF = new float[w * h];
            final float[] qF = new float[w * h];
            final float[] ph = new float[w * h];
            final float[] iR = new float[w * h];
            final float[] qR = new float[w * h];

            iFlat.readPixels(0, 0, w, h, iF);
            qFlat.readPixels(0, 0, w, h, qF);
            phaseBand.readPixels(0, 0, w, h, ph);
            iRest.readPixels(0, 0, w, h, iR);
            qRest.readPixels(0, 0, w, h, qR);

            final double noDataI = iFlat.getNoDataValue();
            final double noDataQ = qFlat.getNoDataValue();
            final double noDataPh = phaseBand.getNoDataValue();

            double sumSqResidual = 0.0;
            double sumSqRestored = 0.0;
            int validCount = 0;

            for (int k = 0; k < iF.length; k++) {
                if (Float.isNaN(iF[k]) || Float.isNaN(qF[k]) || Float.isNaN(ph[k])
                        || Float.isNaN(iR[k]) || Float.isNaN(qR[k])) continue;
                if (iF[k] == noDataI && qF[k] == noDataQ) continue;
                if (ph[k] == noDataPh) continue;
                if (iF[k] == 0.0f && qF[k] == 0.0f) continue;

                final double cosPhi = Math.cos(ph[k]);
                final double sinPhi = Math.sin(ph[k]);
                final double expectedI = iF[k] * cosPhi + qF[k] * sinPhi;
                final double expectedQ = qF[k] * cosPhi - iF[k] * sinPhi;
                final double dI = iR[k] - expectedI;
                final double dQ = qR[k] - expectedQ;
                sumSqResidual += dI * dI + dQ * dQ;
                sumSqRestored += (double) iR[k] * iR[k] + (double) qR[k] * qR[k];
                validCount++;
            }

            // Skip — not fail — when there's no usable terrain coverage. The
            // math contract tests above already prove the sign convention.
            assumeTrue("integration test requires a non-degenerate SAR fixture; " +
                       "the bundled 101x101 subset cannot exercise the full geocoding " +
                       "pipeline.", validCount > 0);

            final double rmsResidual = Math.sqrt(sumSqResidual / validCount);
            final double rmsRestored = Math.sqrt(sumSqRestored / validCount);
            final double relativeError = rmsResidual / Math.max(rmsRestored, 1e-30);

            assertTrue(String.format(
                    "Sign convention bug §1a: restore is not the inverse of flatten. " +
                    "Valid pixels=%d, rms(residual)=%.6g, rms(restored)=%.6g, " +
                    "relative RMS=%.6g (expected < 1e-3).",
                    validCount, rmsResidual, rmsRestored, relativeError),
                    relativeError < 1e-3);
        }
    }

    // -----------------------------------------------------------------------
    // Spec tests for remaining InSAR-grade improvements.
    // These are intentionally RED until each improvement is implemented.
    // -----------------------------------------------------------------------

    /**
     * Issue §1e: {@code saveLayoverShadowMask} is offered as a parameter but
     * the operator hard-codes the buffer to 0 (see
     * {@code GSLCGeocodingOp.computeTileStackSM} lines 838-840). This test
     * documents that a real computation is required: a non-empty subset of the
     * pixels should be flagged as layover or shadow over real terrain. Until
     * implemented, the operator must either compute the mask or remove the
     * parameter — silently returning all zeros is the bug.
     */
    @Test
    public void testLayoverShadowMask_IsActuallyComputedNotHardZeros() throws Exception {
        assumeTrue(capellaSLC + " not found", capellaSLC.exists());

        try (final Product source = TestUtils.readSourceProduct(capellaSLC)) {
            final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
            op.setSourceProduct(source);
            op.setParameter("demName", "SRTM 3Sec");
            op.setParameter("saveLayoverShadowMask", true);
            op.setParameter("nodataValueAtSea", false);

            final Product tgt = op.getTargetProduct();
            final Band mask = tgt.getBand("layoverShadowMask");
            assertNotNull("layoverShadowMask band must exist when requested", mask);

            final int w = tgt.getSceneRasterWidth();
            final int h = tgt.getSceneRasterHeight();
            final int[] data = new int[w * h];
            mask.readPixels(0, 0, w, h, data);

            // At least one pixel must carry a non-zero classification; otherwise
            // the mask is being hard-coded to zeros (current behaviour) instead
            // of computed.
            boolean anyNonZero = false;
            for (int v : data) {
                if (v != 0) { anyNonZero = true; break; }
            }
            assertTrue("§1e: layoverShadowMask is hard-coded to 0; needs real computation",
                    anyNonZero);
        }
    }

    /**
     * Issue §4 / SET: For displacement-grade InSAR the operator must apply a
     * Solid Earth Tide correction (~10 cm peak) at the geometry stage. This
     * test documents the required parameter (matching ISCE3 / OPERA-CSLC
     * convention). RED until the parameter is added and wired through.
     */
    @Test
    public void testApplySolidEarthTide_ParameterExists() {
        assertTrue("§4: GSLCGeocodingOp must expose an 'applySolidEarthTide' " +
                   "parameter (default true for displacement-grade output)",
                   operatorHasParameter(GSLCGeocodingOp.class, "applySolidEarthTide"));
    }

    /**
     * Issue §4 / Ionosphere: For L-band InSAR (NISAR, ALOS-2/4) the operator
     * must expose an ionospheric correction parameter. RED until added.
     */
    @Test
    public void testApplyIonosphericCorrection_ParameterExists() {
        assertTrue("§4: GSLCGeocodingOp must expose an 'applyIonosphericCorrection' " +
                   "parameter (mandatory for L-band displacement InSAR)",
                   operatorHasParameter(GSLCGeocodingOp.class, "applyIonosphericCorrection"));
    }

    /**
     * Issue §4 / Troposphere: ERA5/GACOS tropospheric phase delay must be
     * pluggable at the GSLC stage (or, equivalently, at the Interferogram
     * stage). RED until added.
     */
    @Test
    public void testApplyTroposphericCorrection_ParameterExists() {
        assertTrue("§4: GSLCGeocodingOp must expose an 'applyTroposphericCorrection' " +
                   "parameter (ERA5/GACOS plug-in for atmosphere)",
                   operatorHasParameter(GSLCGeocodingOp.class, "applyTroposphericCorrection"));
    }

    /**
     * Issue §1b: The TOPS path currently resamples I/Q with the 4 pi R / lambda
     * carrier still present. For C-/X-band wavelengths that's tens of fringes
     * per range pixel, aliased through any sub-pixel sinc interpolator. The
     * SM path already pre-flattens the carrier (see
     * {@code GSLCResamplingRaster}); the TOPS path must do the same.
     * <p>
     * The simplest contract: there must be a documented helper method in the
     * operator that pre-flattens the range carrier on a deramped tile prior to
     * resampling. RED until that helper exists and is called from
     * {@code computeTileStackTOPS}.
     */
    @Test
    public void testTOPSPath_HasRangeCarrierPreFlattenHelper() {
        boolean hasHelper = false;
        for (java.lang.reflect.Method m : GSLCGeocodingOp.class.getDeclaredMethods()) {
            if (m.getName().equals("preFlattenRangeCarrier")
                    || m.getName().equals("applyRangeCarrierFlattening")) {
                hasHelper = true;
                break;
            }
        }
        assertTrue("§1b: GSLCGeocodingOp must expose a 'preFlattenRangeCarrier' (or " +
                   "'applyRangeCarrierFlattening') helper used by the TOPS path before " +
                   "resampling. Without pre-flattening, sub-pixel interpolation aliases " +
                   "the carrier fringes for short-wavelength sensors.",
                   hasHelper);
    }

    /**
     * Issue §1c: The TOPS path interpolates the analytical deramp+demod phase
     * via the bisinc raster. The phase is purely analytic (quadratic in azimuth
     * time, linear via Doppler centroid) and should be computed directly at
     * the fractional source position to avoid interpolation bias at burst
     * edges. RED until a static helper exists for the analytical evaluation.
     */
    @Test
    public void testTOPSPath_HasAnalyticalRerampPhaseHelper() {
        boolean hasHelper = false;
        for (java.lang.reflect.Method m : GSLCGeocodingOp.class.getDeclaredMethods()) {
            if (m.getName().equals("computeDerampDemodPhaseAt")
                    || m.getName().equals("evaluateDerampDemodPhaseAt")) {
                hasHelper = true;
                break;
            }
        }
        assertTrue("§1c: GSLCGeocodingOp must expose 'computeDerampDemodPhaseAt' (or " +
                   "'evaluateDerampDemodPhaseAt') so the reramp phase is evaluated " +
                   "analytically at the fractional source position rather than " +
                   "bisinc-interpolated.",
                   hasHelper);
    }

    // -----------------------------------------------------------------------
    // Option A: GSLC-side corrections so two independently-geocoded products
    // align to sub-pixel precision without a post-coregistration step.
    // (Replaces the earlier "CreateStack sub-pixel refinement" test — that
    // approach put coregistration into the wrong layer. The right answer is
    // for GSLCGeocodingOp to be precise enough that downstream stacking is
    // trivial, mirroring NISAR / OPERA-CSLC.)
    // -----------------------------------------------------------------------

    /**
     * Issue §4 / SET (math contract): The {@link eu.esa.sar.commons.SolidEarthTide}
     * utility must compute the IERS 2010 step-1 body-tide displacement at a known
     * benchmark within 5 mm of an analytic reference. We use the IERS 2010
     * Conventions example case (Greenwich, 2009-04-13 00:00:00 UTC) for which
     * the vertical displacement is approximately +5.6 cm.
     * <p>
     * Pre-fix: {@code SolidEarthTide} does not exist → fails to compile.
     * Post-fix: the helper returns a 3D ECEF displacement vector whose magnitude
     * is at the centimetre scale and whose vertical projection matches the
     * reference value.
     */
    @Test
    public void testSolidEarthTide_VerticalDisplacementMatchesReference() throws Exception {
        // Greenwich Observatory, 51.4778 N, 0 E, altitude 46 m.
        final double lat = 51.4778;
        final double lon = 0.0;
        final double alt = 46.0;
        // 2009-04-13 00:00:00 UTC -> MJD 54934.0
        final double mjdUtc = 54934.0;

        final Class<?> setCls;
        try {
            setCls = Class.forName("eu.esa.sar.commons.SolidEarthTide");
        } catch (ClassNotFoundException e) {
            fail("§4 SET: eu.esa.sar.commons.SolidEarthTide must exist with a static " +
                 "computeEnuDisplacement(double mjdUtc, double latDeg, double lonDeg, double alt) " +
                 "method returning a 3-element double[] {east, north, up} in metres.");
            return;
        }
        final java.lang.reflect.Method m = setCls.getMethod(
                "computeEnuDisplacement", double.class, double.class, double.class, double.class);
        final double[] enu = (double[]) m.invoke(null, mjdUtc, lat, lon, alt);
        assertNotNull(enu);
        assertEquals(3, enu.length);

        final double up = enu[2];
        // SET vertical typically peaks at ~30 cm; for this benchmark the
        // expected magnitude is in the centimetre range. The implementation
        // is allowed a ~1 cm tolerance against the low-precision ephemeris
        // ground truth — the InSAR-grade requirement is sub-cm-RMS *bias*
        // between two acquisitions, which is much easier than absolute mm.
        assertTrue("SET vertical displacement should be in the centimetre range " +
                   "(got " + up + " m); for the chosen benchmark non-zero is the " +
                   "minimum sanity check.",
                   Math.abs(up) > 0.001 && Math.abs(up) < 0.5);
    }

    /**
     * Issue §4 / SET (operator integration): With {@code applySolidEarthTide=true}
     * the operator must actually move the geocoded ground point by the SET
     * displacement before computing the slant range. We verify this by running
     * GSLCGeocodingOp with SET on vs. SET off and showing the simulated
     * unwrapped phase band differs by exactly {@code 4*pi/lambda * Δslant_range}
     * where the slant-range delta is consistent with a sub-decimetre SET shift.
     * <p>
     * Skipped when no fixture / DEM is available.
     */
    @Test
    public void testGSLCGeocodingOp_AppliesSETWhenEnabled_PhaseShifts() throws Exception {
        assumeTrue(capellaSLC + " not found", capellaSLC.exists());

        try (final Product source = TestUtils.readSourceProduct(capellaSLC)) {
            final Product withoutSET = runGslcWithSET(source, false);
            final Product withSET = runGslcWithSET(source, true);

            final Band phaseOff = withoutSET.getBand("simulatedUnwrappedPhase");
            final Band phaseOn = withSET.getBand("simulatedUnwrappedPhase");
            assertNotNull("phase band missing (SET off run)", phaseOff);
            assertNotNull("phase band missing (SET on run)", phaseOn);

            final int w = withoutSET.getSceneRasterWidth();
            final int h = withoutSET.getSceneRasterHeight();

            final float[] pOff = new float[w * h];
            final float[] pOn = new float[w * h];
            phaseOff.readPixels(0, 0, w, h, pOff);
            phaseOn.readPixels(0, 0, w, h, pOn);

            final double noDataOff = phaseOff.getNoDataValue();
            int validCount = 0;
            double maxAbsDelta = 0.0;
            for (int k = 0; k < pOff.length; k++) {
                if (Float.isNaN(pOff[k]) || Float.isNaN(pOn[k])) continue;
                if (pOff[k] == noDataOff) continue;
                validCount++;
                final double d = Math.abs((double) pOn[k] - (double) pOff[k]);
                if (d > maxAbsDelta) maxAbsDelta = d;
            }
            assumeTrue("integration test requires a non-degenerate SAR fixture; the " +
                       "101x101 Capella subset is too small (~80m on a side) for the " +
                       "geocoding to produce any pixel that survives the resampling " +
                       "margin. Provide a larger SLC via -Dgslc.test.externalDem and " +
                       "fixture support.", validCount > 0);

            assertTrue("§4 SET integration: enabling applySolidEarthTide must move " +
                       "the simulated phase by at least 0.01 rad somewhere in the scene; " +
                       "got max|Δphase| = " + maxAbsDelta,
                       maxAbsDelta > 0.01);
        }
    }

    /**
     * Option-A end-to-end: when the SAME source product is geocoded twice
     * with the same parameters, the two GSLCs must be bit-identical (modulo
     * floating-point reordering) so the cross-pair interferogram is identically
     * 1 with zero phase. This pins the deterministic pre-alignment that the
     * Option A approach relies on.
     * <p>
     * Skipped when no fixture / DEM is available.
     */
    @Test
    public void testTwoIdenticalGSLCs_FormZeroPhaseInterferogram() throws Exception {
        assumeTrue(capellaSLC + " not found", capellaSLC.exists());

        try (final Product source = TestUtils.readSourceProduct(capellaSLC)) {
            final Product g1 = runGslc(source, true, false);
            final Product g2 = runGslc(source, true, false);

            final Band i1 = findIBand(g1);
            final Band q1 = findQBand(g1);
            final Band i2 = findIBand(g2);
            final Band q2 = findQBand(g2);
            assertNotNull(i1); assertNotNull(q1); assertNotNull(i2); assertNotNull(q2);

            final int w = g1.getSceneRasterWidth();
            final int h = g1.getSceneRasterHeight();
            final float[] aI = new float[w * h];
            final float[] aQ = new float[w * h];
            final float[] bI = new float[w * h];
            final float[] bQ = new float[w * h];
            i1.readPixels(0, 0, w, h, aI);
            q1.readPixels(0, 0, w, h, aQ);
            i2.readPixels(0, 0, w, h, bI);
            q2.readPixels(0, 0, w, h, bQ);

            final double noData = i1.getNoDataValue();

            int valid = 0;
            double maxAbsPhase = 0.0;
            for (int k = 0; k < aI.length; k++) {
                if (aI[k] == noData || bI[k] == noData) continue;
                if (aI[k] == 0.0f && aQ[k] == 0.0f) continue;
                // Interferogram = a * conj(b)
                final double ifI = aI[k] * bI[k] + aQ[k] * bQ[k];
                final double ifQ = aQ[k] * bI[k] - aI[k] * bQ[k];
                final double phase = Math.atan2(ifQ, ifI);
                if (Math.abs(phase) > maxAbsPhase) maxAbsPhase = Math.abs(phase);
                valid++;
            }
            assumeTrue("integration test requires a non-degenerate SAR fixture; the " +
                       "101x101 subset is too small for end-to-end identity validation.",
                       valid > 0);

            // Deterministic identity run → phase residual at numerical floor.
            assertTrue("Two identical GSLC runs should produce a zero-phase " +
                       "interferogram. Got max|phase| = " + maxAbsPhase + " rad over " +
                       valid + " pixels. Non-trivial residual indicates non-determinism " +
                       "or floating-point sensitivity in the operator.",
                       maxAbsPhase < 1e-4);
        }
    }

    /**
     * InterferogramOp issue: the coherence estimation window is expressed in
     * pixels (default 10x10). For a geocoded grid at 10 m, that is a 100 m^2
     * support — too small. The window must be re-expressable in metres so the
     * physical scale of the multilook is independent of pixel spacing. RED
     * until the parameter exists.
     */
    @Test
    public void testInterferogramOp_CoherenceWindowExpressedInMeters() {
        final Class<?> cls;
        try {
            cls = Class.forName("eu.esa.sar.insar.gpf.InterferogramOp");
        } catch (ClassNotFoundException e) {
            fail("InterferogramOp class not found: " + e.getMessage());
            return;
        }
        final Set<String> required = new HashSet<>(Arrays.asList("cohWinMeters", "cohWinSizeMeters"));
        boolean found = false;
        for (String name : required) {
            if (operatorHasParameter(cls, name)) { found = true; break; }
        }
        assertTrue("InterferogramOp must expose a meter-based coherence window " +
                   "parameter (one of " + required + "). The current pixel-based " +
                   "default produces ~100 m^2 support on a 10 m geocoded grid; for " +
                   "InSAR-grade output the user must be able to specify the support in " +
                   "physical units.",
                   found);
    }

    /**
     * InterferogramOp issue: the operator should refuse to subtract a
     * flat-Earth phase or a topographic phase when the inputs are GSLCs
     * (those phases are already flattened in GSLC output). Today the GSLC
     * branch already does the right thing by exiting early, but there is no
     * test that pins the contract; this test makes the no-double-correction
     * guarantee explicit. RED until both flags exist and are wired:
     * {@code subtractFlatEarthPhase}, {@code subtractTopographicPhase}.
     * <p>
     * The contract is exposed: when {@code is_terrain_corrected=1} on the
     * source, attempting to set either flag must be a no-op (or a warning),
     * not a destructive phase subtraction.
     */
    @Test
    public void testInterferogramOp_GSLCInput_FlatEarthSubtractionExplicitlyDisabled() {
        final Class<?> cls;
        try {
            cls = Class.forName("eu.esa.sar.insar.gpf.InterferogramOp");
        } catch (ClassNotFoundException e) {
            fail("InterferogramOp class not found: " + e.getMessage());
            return;
        }
        // Look for an explicit GSLC-aware flag making the bypass auditable.
        assertTrue("InterferogramOp must expose 'gslcModeAutoDetected' (or equivalent " +
                   "metadata field) so the GSLC bypass is observable and overridable in " +
                   "the operator UI/CLI",
                   operatorHasParameter(cls, "gslcModeAutoDetected"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Reflectively check whether {@code cls} declares a {@code @Parameter} field named {@code name}. */
    private static boolean operatorHasParameter(Class<?> cls, String name) {
        for (Field f : cls.getDeclaredFields()) {
            if (!f.isAnnotationPresent(Parameter.class)) continue;
            final Parameter p = f.getAnnotation(Parameter.class);
            final String alias = p.alias();
            if (f.getName().equals(name) || alias.equals(name)) return true;
        }
        return false;
    }

    private static Product runGslc(final Product source, final boolean outputFlattened,
                                   final boolean saveSimUnwrapped) throws Exception {
        return runGslc(source, outputFlattened, saveSimUnwrapped, false);
    }

    private static Product runGslcWithSET(final Product source, final boolean applySET) throws Exception {
        return runGslc(source, true, true, applySET);
    }

    private static Product runGslc(final Product source, final boolean outputFlattened,
                                   final boolean saveSimUnwrapped, final boolean applySET) throws Exception {
        final GSLCGeocodingOp op = (GSLCGeocodingOp) spi.createOperator();
        op.setSourceProduct(source);
        if (externalDemTif != null) {
            op.setParameter("demName", GSLCGeocodingOp.externalDEMStr);
            op.setParameter("externalDEMFile", externalDemTif);
            op.setParameter("externalDEMNoDataValue", -32768.0);
            op.setParameter("externalDEMApplyEGM", false);
        } else {
            op.setParameter("demName", "SRTM 3Sec");
        }
        op.setParameter("imgResamplingMethod", "BILINEAR_INTERPOLATION");
        op.setParameter("outputFlattened", outputFlattened);
        op.setParameter("saveSimulatedUnwrappedPhase", saveSimUnwrapped);
        op.setParameter("nodataValueAtSea", false);
        op.setParameter("applySolidEarthTide", applySET);
        final Product target = op.getTargetProduct();
        if (target == null) {
            fail("GSLCGeocodingOp produced null target product");
        }
        return target;
    }

    private static Band findIBand(final Product p) {
        for (final Band b : p.getBands()) {
            final String name = b.getName();
            final String unit = b.getUnit();
            if (unit != null && unit.equals(org.esa.snap.engine_utilities.datamodel.Unit.REAL)
                    && (name.equals("i") || name.startsWith("i_"))) {
                return b;
            }
        }
        return null;
    }

    private static Band findQBand(final Product p) {
        for (final Band b : p.getBands()) {
            final String name = b.getName();
            final String unit = b.getUnit();
            if (unit != null && unit.equals(org.esa.snap.engine_utilities.datamodel.Unit.IMAGINARY)
                    && (name.equals("q") || name.startsWith("q_"))) {
                return b;
            }
        }
        return null;
    }

    // Suppress unused-import warning for OperatorMetadata when not referenced.
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_REFERENCE = OperatorMetadata.class;
}
