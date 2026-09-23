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
package eu.esa.sar.insar.gpf;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Fixture-free tests of {@link GSLCEquivalenceLongTest#assertVerdict}. Kept out of the
 * long-test class because that class is gated behind -Denable.long.tests.
 */
public class GSLCEquivalenceVerdictTest {

    @Test
    public void verdictRejectsExitCodeDisagreeingWithGates() {
        assertRejected("expected an AssertionError when exit 1 accompanies no FAIL gate",
                () -> GSLCEquivalenceLongTest.assertVerdict(6, false, 1, false));
        assertRejected("expected an AssertionError when exit 0 accompanies a FAIL gate",
                () -> GSLCEquivalenceLongTest.assertVerdict(6, true, 0, false));
        GSLCEquivalenceLongTest.assertVerdict(6, true, 1, false);
        GSLCEquivalenceLongTest.assertVerdict(6, false, 0, false);
    }

    @Test
    public void verdictRejectsTooFewGateLines() {
        assertRejected("expected an AssertionError when fewer than "
                    + GSLCEquivalenceLongTest.MIN_EXPECTED_GATE_LINES + " GATE lines were printed",
                () -> GSLCEquivalenceLongTest.assertVerdict(2, false, 0, false));
    }

    @Test
    public void expectPassModeDemandsExitZero() {
        GSLCEquivalenceLongTest.assertVerdict(6, false, 0, true);
        assertRejected("expectPass mode must reject a non-zero exit",
                () -> GSLCEquivalenceLongTest.assertVerdict(6, true, 1, true));
    }

    /**
     * fail() throws AssertionError, so it cannot sit inside a try that catches
     * AssertionError - it would be swallowed and the negative case would pass vacuously.
     */
    private static void assertRejected(final String message, final Runnable call) {
        boolean rejected = false;
        try {
            call.run();
        } catch (AssertionError expected) {
            rejected = true;
        }
        assertTrue(message, rejected);
    }
}
